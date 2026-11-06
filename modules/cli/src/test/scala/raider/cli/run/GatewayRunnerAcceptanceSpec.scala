package raider.cli.run

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import raider.core.result.{ExecutionStatus, RunResult}
import zio.{ZIO, durationInt}
import zio.json.DecoderOps
import zio.test.*
import zio.test.live

import java.net.{InetAddress, InetSocketAddress, ServerSocket}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path}
import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

/** GW17 close-out (RAI-287) — candidate GATEWAY integration checks at the
  * RUNNER level (headless runner, the public product entry) against a local
  * Yabanin gateway contract stand, plus the EXPLICIT no-hidden-fallback
  * acceptance.
  *
  * The gateway is consumed through the generic OpenAI-compatible wire exactly
  * as the gateway baseline prescribes (docs/providers/gateway-baseline.md §2):
  * `--provider openai` with the wrapper-provided base URL, the child credential
  * through the product's env channels only
  * (`RAIDER_API_KEY`/`RAIDER_API_KEY_CMD`), and NO session minting by Raider —
  * exactly one lifecycle owner exists, the wrapper.
  *
  * The acceptance core (AGENTS.md trust boundary: "There is no hidden direct
  * fallback from a gateway after auth/policy failure"):
  *   - GW17-R2/R3: a gateway auth (401) / policy (403) refusal produces the
  *     TYPED refusal (RA-AUTH, exit 20) and ZERO direct-transport calls —
  *     asserted on a second armed "direct origin" stand (an OpenAI-compatible
  *     endpoint a hidden fallback could have switched to): its request log
  *     stays EMPTY;
  *   - GW17-R4: an unreachable gateway is the same typed refusal (RA-UNAVAIL)
  *     with zero direct-origin contact;
  *   - GW17-R1/R5: the happy path rides ONE verbatim completions endpoint (no
  *     /models probe, no mint/lifecycle request of Raider's own), and two
  *     concurrent invocations each own their stand/port/out-dir (RUN-03).
  *
  * Stands are per-test owned: loopback, ephemeral ports, acquire/release
  * teardown; temp work dirs are per-test and deleted on release. No real
  * network, no spend, no live gateway.
  */
object GatewayRunnerAcceptanceSpec extends ZIOSpecDefault:

  private val GatewayKey = "yb-child-gw17-fixture-key"

  /** Minimal embedded gateway contract stand: the accepted wire only — POST
    * `/gw/v1/chat/completions` (Bearer child credential, deterministic scenario
    * per model id) and GET `/gw/v1/models` (liveness). Every request is logged
    * (method/path/auth) for the lifecycle and fallback assertions.
    */
  private final case class GatewayStand(
      baseUrl: String,
      server: HttpServer,
      log: CopyOnWriteArrayList[(String, String, Option[String])]
  ):
    def stop(): Unit = server.stop(0)

    def requests: Vector[(String, String, Option[String])] =
      log.asScala.toVector

    def paths: Vector[String] = requests.map(_._2)

  private def startGatewayStand(
      key: String = GatewayKey
  ): ZIO[Any, Nothing, GatewayStand] =
    ZIO.attempt {
      val log = new CopyOnWriteArrayList[(String, String, Option[String])]
      val server = HttpServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
        0
      )
      def reply(ex: HttpExchange, code: Int, payload: String): Unit =
        val bytes = payload.getBytes(UTF_8)
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.length)
        ex.getResponseBody.write(bytes)
      server.createContext(
        "/gw/v1/models",
        (ex: HttpExchange) =>
          try
            log.add((ex.getRequestMethod, ex.getRequestURI.getPath, None))
            reply(
              ex,
              200,
              """{"object":"list","data":[{"id":"gw-flash","object":"model"}]}"""
            )
          finally ex.close()
      )
      server.createContext(
        "/gw/v1/chat/completions",
        (ex: HttpExchange) =>
          try
            val auth = Option(ex.getRequestHeaders.getFirst("Authorization"))
            log.add(("POST", ex.getRequestURI.getPath, auth))
            ex.getRequestMethod match
              case "POST" =>
                auth match
                  case None =>
                    reply(
                      ex,
                      401,
                      """{"error":{"message":"missing child credential"}}"""
                    )
                  case Some(a) if a != s"Bearer $key" =>
                    reply(
                      ex,
                      401,
                      """{"error":{"message":"invalid child credential"}}"""
                    )
                  case Some(_) =>
                    val body =
                      new String(ex.getRequestBody.readAllBytes(), UTF_8)
                    if body.contains("gw-policy-deny") then
                      reply(
                        ex,
                        403,
                        """{"error":{"message":"policy: model not permitted for this child"}}"""
                      )
                    else
                      reply(
                        ex,
                        200,
                        """{"id":"chatcmpl-gw17-r1","choices":[{"index":0,
                        "message":{"role":"assistant","content":"gateway ok"},
                        "finish_reason":"stop"}],
                        "usage":{"prompt_tokens":23,"completion_tokens":11}}"""
                      )
              case _ =>
                reply(ex, 405, """{"error":{"message":"method not allowed"}}""")
          finally ex.close()
      )
      server.start()
      GatewayStand(
        s"http://127.0.0.1:${server.getAddress.getPort}/gw/v1",
        server,
        log
      )
    }.orDie

  /** An ARMED "direct provider origin" stand — the OpenAI-compatible endpoint a
    * hidden fallback would have to switch to after a gateway refusal. It
    * answers every completions call with a valid body, so ANY contact would
    * succeed and be logged; the no-hidden-fallback legs assert its log stays
    * EMPTY.
    */
  private final case class DirectOriginStand(
      baseUrl: String,
      server: HttpServer,
      log: CopyOnWriteArrayList[String]
  ):
    def stop(): Unit = server.stop(0)
    def requestCount: Int = log.size()
    def paths: Vector[String] = log.asScala.toVector

  private def startDirectOriginStand(): ZIO[Any, Nothing, DirectOriginStand] =
    ZIO.attempt {
      val log = new CopyOnWriteArrayList[String]
      val server = HttpServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
        0
      )
      server.createContext(
        "/",
        (ex: HttpExchange) =>
          try
            log.add(ex.getRequestURI.getPath)
            val bytes =
              ("""{"id":"chatcmpl-direct-1","choices":[{"index":0,
                "message":{"role":"assistant","content":"direct origin answered"},
                "finish_reason":"stop"}],
                "usage":{"prompt_tokens":1,"completion_tokens":1}}""").getBytes(
                UTF_8
              )
            ex.sendResponseHeaders(200, bytes.length)
            ex.getResponseBody.write(bytes)
          finally ex.close()
      )
      server.start()
      DirectOriginStand(
        s"http://127.0.0.1:${server.getAddress.getPort}/v1",
        server,
        log
      )
    }.orDie

  /** A reliably dead loopback origin: a bound-then-closed port. */
  private def deadOriginUrl(): ZIO[Any, Nothing, String] =
    ZIO.attempt {
      val probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress)
      val port = probe.getLocalPort
      probe.close()
      s"http://127.0.0.1:$port/v1"
    }.orDie

  /** Per-test temp out dir, deleted on release — no shared fixed paths. */
  private def withTempDir[A](
      use: Path => ZIO[Any, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt(Files.createTempDirectory("raider-gw17-out"))
    )(d => ZIO.attempt(deleteRecursively(d)).orDie)(use)

  private def deleteRecursively(p: Path): Unit =
    if Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) then
      val listing = Files.newDirectoryStream(p)
      try listing.forEach(entry => deleteRecursively(entry))
      finally listing.close()
      Files.delete(p)
    else if Files.exists(p, LinkOption.NOFOLLOW_LINKS) then Files.delete(p)

  private def runArgs(
      out: Path,
      agent: String,
      baseUrl: String,
      model: String,
      workspace: Option[Path] = None
  ) =
    CliArgs
      .parse(
        List(
          "run",
          "--agent",
          agent,
          "--input",
          "gateway contract probe",
          "--out",
          out.toString,
          "--provider",
          "openai",
          "--base-url",
          baseUrl,
          "--model",
          model
        ) ++ workspace.toList.flatMap(w => List("--workspace", w.toString))
      )
      .fold(e => throw e, identity)

  private def readRunJson(workDir: Path): RunResult =
    val text = Files.readString(workDir.resolve("run.json"), UTF_8)
    text.fromJson[RunResult] match
      case Right(r) => r
      case Left(e)  => throw new IllegalStateException(s"bad run.json: $e")

  def spec = suite("GW17 gateway runner acceptance (local gateway stand)")(
    test(
      "GW17-R1 happy path: headless run against the gateway stand — one verbatim completions call, no probe, no mint"
    ) {
      withTempDir { out =>
        withGatewayAndDirect { (gw, direct) =>
          for
            outcome <- HeadlessRunner.run(
              runArgs(out, "scout", gw.baseUrl, "gw-flash"),
              Map(Credentials.EnvKey -> GatewayKey)
            )
            runJson = readRunJson(outcome.workDir)
          yield assertTrue(
            outcome.exitCode == 0,
            runJson.status == ExecutionStatus.Succeeded,
            runJson.error.isEmpty,
            runJson.output.exists(o => o.json.contains("gateway ok")),
            // exactly one attempt, on exactly the accepted endpoint
            gw.requests == Vector(
              ("POST", "/gw/v1/chat/completions", Some(s"Bearer $GatewayKey"))
            ),
            // no endpoint probing, no session-mint / lifecycle request of
            // Raider's own (the wrapper is the only lifecycle owner)
            !gw.paths.exists(p => !p.endsWith("/chat/completions")),
            direct.requestCount == 0
          )
        }
      }
    },
    test(
      "GW17-R2 NO-HIDDEN-FALLBACK (auth): gateway 401 → typed RA-AUTH exit 20, ZERO direct-origin calls"
    ) {
      withTempDir { out =>
        withGatewayAndDirect { (gw, direct) =>
          for
            outcome <- HeadlessRunner.run(
              runArgs(out, "scout", gw.baseUrl, "gw-flash"),
              Map(Credentials.EnvKey -> "yb-wrong-child-key")
            )
            runJson = readRunJson(outcome.workDir)
          yield assertTrue(
            // the typed refusal: exit family 20 (RuntimeFailure), RA-AUTH
            outcome.exitCode == raider.core.result.ExitCodes.RuntimeFailure,
            runJson.status == ExecutionStatus.Failed,
            runJson.output.isEmpty,
            runJson.error.exists(e => e.code == "RA-AUTH"),
            runJson.error.exists(_.detail.contains("401")),
            // exactly ONE gateway attempt — an auth refusal is never retried
            gw.requests == Vector(
              (
                "POST",
                "/gw/v1/chat/completions",
                Some("Bearer yb-wrong-child-key")
              )
            ),
            // THE acceptance: zero contact with any direct provider origin
            direct.requestCount == 0,
            direct.paths.isEmpty
          )
        }
      }
    },
    test(
      "GW17-R3 NO-HIDDEN-FALLBACK (policy): gateway 403 → typed RA-AUTH exit 20, ZERO direct-origin calls"
    ) {
      withTempDir { out =>
        withGatewayAndDirect { (gw, direct) =>
          for
            ws <- ZIO.attempt(Files.createTempDirectory("raider-gw17-ws")).orDie
            outcome <- HeadlessRunner.run(
              runArgs(out, "coder", gw.baseUrl, "gw-policy-deny", Some(ws)),
              Map(Credentials.EnvKey -> GatewayKey)
            )
            runJson = readRunJson(outcome.workDir)
          yield assertTrue(
            outcome.exitCode == raider.core.result.ExitCodes.RuntimeFailure,
            runJson.status == ExecutionStatus.Failed,
            runJson.output.isEmpty,
            runJson.error.exists(e => e.code == "RA-AUTH"),
            runJson.error.exists(_.detail.contains("403")),
            // one gateway attempt (policy refusal, first-shot) …
            gw.requests.length == 1,
            gw.paths.forall(_.endsWith("/chat/completions")),
            // … and ZERO direct-transport calls anywhere else
            direct.requestCount == 0,
            direct.paths.isEmpty
          )
        }
      }
    },
    test(
      "GW17-R4 unreachable gateway → typed RA-UNAVAIL exit 20, ZERO direct-origin calls"
    ) {
      withTempDir { out =>
        withGatewayAndDirect { (_, direct) =>
          for
            dead <- deadOriginUrl()
            outcome <- HeadlessRunner.run(
              runArgs(out, "scout", dead, "gw-flash"),
              Map(Credentials.EnvKey -> GatewayKey)
            )
            runJson = readRunJson(outcome.workDir)
          yield assertTrue(
            outcome.exitCode == raider.core.result.ExitCodes.RuntimeFailure,
            runJson.status == ExecutionStatus.Failed,
            runJson.output.isEmpty,
            runJson.error.exists(e => e.code == "RA-UNAVAIL"),
            // the fallback ban covers the unreachable-gateway path too
            direct.requestCount == 0,
            direct.paths.isEmpty
          )
        }
      }
    },
    test(
      "GW17-R5 ownership/concurrency: two parallel invocations, each its own stand/port/out-dir, no cross-talk"
    ) {
      // One fully-owned invocation resource: gateway stand + temp out dir
      // acquired/released together; the runner call AND the run.json read
      // happen inside the scope (release deletes the dir).
      def one(): ZIO[
        Any,
        Nothing,
        (HeadlessRunner.Outcome, GatewayStand, RunResult)
      ] =
        ZIO.acquireReleaseWith(
          for
            gw <- startGatewayStand()
            out <- ZIO
              .attempt(Files.createTempDirectory("raider-gw17-par"))
              .orDie
          yield (gw, out)
        ) { case (gw, out) =>
          ZIO.succeed {
            gw.stop()
            deleteRecursively(out)
          }
        } { case (gw, out) =>
          HeadlessRunner
            .run(
              runArgs(out, "scout", gw.baseUrl, "gw-flash"),
              Map(Credentials.EnvKey -> GatewayKey)
            )
            .map(o => (o, gw, readRunJson(o.workDir)))
        }
      for
        pair <- ZIO
          .foreachPar((1 to 2).toList)(_ => one())
          .timeoutFail(new IllegalStateException("parallel gateway runs hung"))(
            90.seconds
          )
          .map { runs =>
            val List((o1, gw1, r1), (o2, gw2, r2)) = runs
            (
              o1.exitCode,
              o2.exitCode,
              r1,
              r2,
              gw1.requests.length,
              gw2.requests.length,
              gw1.baseUrl != gw2.baseUrl,
              o1.workDir != o2.workDir
            )
          }
        (e1, e2, r1, r2, n1, n2, distinctStands, distinctWork) = pair
      yield assertTrue(
        e1 == 0,
        e2 == 0,
        r1.output.exists(_.json.contains("gateway ok")),
        r2.output.exists(_.json.contains("gateway ok")),
        // isolation: each stand saw exactly its own single request
        n1 == 1,
        n2 == 1,
        distinctStands,
        distinctWork
      )
    }
  ) @@ TestAspect.withLiveClock

  /** Acquire both stands for one test; release both (cancellation-safe). */
  private def withGatewayAndDirect[A](
      use: (GatewayStand, DirectOriginStand) => ZIO[Any, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(startGatewayStand())(s => ZIO.succeed(s.stop())) {
      gw =>
        ZIO.acquireReleaseWith(startDirectOriginStand())(s =>
          ZIO.succeed(s.stop())
        ) {
          use(gw, _)
        }
    }
