package raider.cli.portable

import raider.cli.workflow.{WorkflowCompile, WorkflowRun}
import raider.dsl.workflow.{
  ChatWorkflowRenderer,
  NamedCheck,
  RenderOptions,
  StsAgentStage,
  StsOrigin,
  StsParam,
  StsSelection,
  StsTurn,
  StsTurnKind,
  StsTurnStatus
}
import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestAspect

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.jdk.CollectionConverters.*

/** RAI-199 XE-CLI-01/02/03 against ONE scripted OpenAI-compatible stand and ONE
  * compiled build of the SAME scripted task:
  *
  *   - XE-CLI-01 (parity): the SAME scripted task launches through the COMPILED
  *     route (WorkflowRun on the edited artifact) and the PORTABLE route
  *     (`workflow run --doc` with the launcher-pinned trusted registry) on
  *     fresh workspaces with equivalent verdicts (trusted marker check),
  *     equivalent artifacts (run.json with the trusted verdict line) and REAL
  *     tool effects on both workspaces. check/explain stay zero-dispatch (they
  *     are covered without any stand in PortableCheckSpec/PortableExplainSpec).
  *   - XE-CLI-02 (typed refusal): a hard --deadline-s deadline against a hung
  *     stand dies with the typed RA-DEADLINE exit, never a fake completion.
  *   - XE-CLI-03 (non-TTY discipline): a parent-delivered SIGTERM during model
  *     work yields the bounded stop (143), truthful incomplete evidence and no
  *     orphans; two concurrent portable launches share no accidental state.
  *
  * The stand + compiled build are ONE shared suite resource (the hang switch
  * arms the SIGTERM/deadline cases against the SAME baked URL).
  */
object PortableParitySpec extends ZIOSpecDefault:

  // ----- fixture -------------------------------------------------------------

  private val probe = StsSelection(
    sessionId = "sess-199-parity",
    intent = StsTurn(
      "l1",
      StsTurnKind.Intent,
      StsTurnStatus.Completed,
      "Write the launch marker for the 199 parity probe"
    ),
    refinements = List(
      StsTurn(
        "l2",
        StsTurnKind.Refinement,
        StsTurnStatus.Completed,
        "Keep the marker content exact"
      )
    ),
    taskParams = List(
      StsParam("targetPath", "marker.txt"),
      StsParam("summary", "199 parity probe task")
    ),
    checks = List(
      NamedCheck("marker", List("/bin/test", "-f", "marker.txt"))
    ),
    agentStage = StsAgentStage(maxRounds = 3),
    origin = StsOrigin(
      providerKind = "openai-compatible",
      model = "stand-model-199",
      baseUrl = Some("http://127.0.0.1:1/v1") // EDITED to the stand URL below
    )
  )

  private val FinalAnswer = "stand final answer 199"
  private val StandKey = "stand-key-199"

  /** Round 1: streaming tool_calls — the agent stage must really execute
    * proc_run (touch marker.txt) inside the input workspace.
    */
  private val toolRoundFrames: Vector[String] = Vector(
    """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}""",
    """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"callA","function":{"name":"proc_run","arguments":""}}]},"finish_reason":null}]}""",
    """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"argv\":"}}]},"finish_reason":null}]}""",
    """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"[\"/usr/bin/touch\",\"marker.txt\"]}"}}]},"finish_reason":null}]}""",
    """{"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
  )

  private val finalFrames: Vector[String] = Vector(
    """{"id":"f1","choices":[{"index":0,"delta":{"role":"assistant","content":"stand final answer 199"},"finish_reason":null}]}""",
    """{"id":"f1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
  )

  private def sse(frames: Seq[String]): Array[Byte] =
    (frames.map(f => "data: " + f + "\n\n").mkString + "data: [DONE]\n\n")
      .getBytes(StandardCharsets.UTF_8)

  private final case class StandHandle(
      url: String,
      server: HttpServer,
      requests: AtomicInteger,
      hang: AtomicBoolean,
      /** Requests carrying exactly `Authorization: Bearer <StandKey>` — the key
        * is ASSERTED on the wire (never argv, never artifact).
        */
      authOk: AtomicInteger
  ):
    def stop(): Unit = server.stop(0)
    def requestCount: Int = requests.get

  /** Non-streaming Chat Completions bodies (the portable route's pinned
    * OpenAIChatBackend requests a PLAIN body; the compiled artifact's own
    * streaming client requests SSE — the stand serves BOTH wire styles).
    */
  private def plainToolRound: String =
    """{"id":"p1","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"callA","function":{"name":"proc_run","arguments":"{\"argv\":[\"/usr/bin/touch\",\"marker.txt\"]}"}}]},"finish_reason":"tool_calls"}]}"""

  private def plainFinal: String =
    """{"id":"p2","choices":[{"index":0,"message":{"role":"assistant","content":"stand final answer 199"},"finish_reason":"stop"}]}"""

  /** Scripted (round 1 -> tool_calls; round 2, gated on the `role:"tool"`
    * result message, -> final text) with a HANG switch: when armed, every
    * request sleeps ~30 s after being counted (active model work for the
    * SIGTERM/deadline cases). The count happens BEFORE any gating. Wire style:
    * requests carrying a stream flag get SSE frames (the compiled route);
    * everything else gets one plain JSON completion (the portable route's
    * pinned non-streaming backend).
    */
  private def startStand(): StandHandle =
    val requests = new AtomicInteger(0)
    val hang = new AtomicBoolean(false)
    val authOk = new AtomicInteger(0)
    val server = HttpServer.create(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      0
    )
    server.createContext(
      "/v1/chat/completions",
      (ex: HttpExchange) =>
        try
          requests.incrementAndGet()
          val auth =
            Option(ex.getRequestHeaders.getFirst("Authorization")).getOrElse("")
          if auth == "Bearer " + StandKey then authOk.incrementAndGet()
          val body =
            new String(
              ex.getRequestBody.readAllBytes(),
              StandardCharsets.UTF_8
            )
          val toolResultDone = body.contains("\"role\":\"tool\"")
          if hang.get then
            Thread.sleep(30000L)
            reply(ex, 200, sse(finalFrames))
          else if body.contains("stream") then
            reply(
              ex,
              200,
              sse(if toolResultDone then finalFrames else toolRoundFrames)
            )
          else
            val plain =
              if toolResultDone then plainFinal else plainToolRound
            reply(
              ex,
              200,
              plain.getBytes(StandardCharsets.UTF_8)
            )
          ()
        finally ex.close()
    )
    // A bounded daemon pool: a hung handler (deadline/SIGTERM arming) must
    // not serialize the remaining suite behind a 30 s sleep.
    server.setExecutor(
      java.util.concurrent.Executors.newFixedThreadPool(
        4,
        (r: Runnable) =>
          val t = new Thread(r)
          t.setDaemon(true)
          t
      )
    )
    server.start()
    StandHandle(
      "http://127.0.0.1:" + server.getAddress.getPort + "/v1",
      server,
      requests,
      hang,
      authOk
    )

  private def reply(ex: HttpExchange, code: Int, body: Array[Byte]): Unit =
    ex.getResponseHeaders.add("Content-Type", "text/event-stream")
    ex.sendResponseHeaders(
      code,
      if body.isEmpty then -1 else body.length.toLong
    )
    if body.nonEmpty then ex.getResponseBody.write(body)

  // ----- shared suite environment --------------------------------------------

  private final case class ParityEnv(stand: StandHandle, build: Path)

  /** ONE scripted stand + ONE compiled build of the EDITED source, shared by
    * the whole suite (the build bakes the stand URL; the hang switch serves the
    * SIGTERM/deadline cases against the same URL).
    */
  private val envLayer: ZLayer[Any, Throwable, ParityEnv] =
    ZLayer.scoped {
      ZIO
        .acquireRelease(ZIO.attempt(startStand()))(h => ZIO.succeed(h.stop()))
        .flatMap { stand =>
          ZIO.attempt {
            val rendered = ChatWorkflowRenderer
              .render(probe, RenderOptions("wf199_parity"))
              .fold(e => throw new IllegalStateException(e.detail), identity)
            val work = Files.createTempDirectory("p199-build")
            val src = work.resolve("wf199_parity.scala")
            val edited = rendered.source
              .replace(
                "val baseUrl: String = \"http://127.0.0.1:1/v1\"",
                "val baseUrl: String = \"" + stand.url + "\""
              )
            if edited == rendered.source ||
              !edited.contains("@main def runWf199_parity(")
            then throw new IllegalStateException("fixture edits did not apply")
            Files.write(src, edited.getBytes(StandardCharsets.UTF_8))
            val out = work.resolve("build")
            val exit = WorkflowCompile.command(
              List(src.toString, "--out", out.toString)
            )
            if exit != 0 then
              throw new IllegalStateException("raider compile failed: " + exit)
            ParityEnv(stand, out)
          }
        }
    }

  // ----- helpers --------------------------------------------------------------

  private def repoFile(rel: String): Path =
    val cwd = java.nio.file.Paths.get("").toAbsolutePath
    Iterator
      .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) => dir }
      .find(dir =>
        Files.isDirectory(dir.resolve("contracts/acceptance/portable"))
      )
      .getOrElse(
        throw new IllegalStateException("repository root not found from " + cwd)
      )
      .resolve(rel)

  private def freshWorkspace(): Path = Files.createTempDirectory("p199-ws")

  private def compiledInput(ws: Path): Path =
    val f = Files.createTempDirectory("p199-in").resolve("input.json")
    Files.writeString(
      f,
      "{\n  \"schemaVersion\": 1,\n  \"workspace\": \"" +
        ws.toString.replace("\\", "\\\\") +
        "\",\n  \"task\": {\n    \"targetPath\": \"marker.txt\",\n" +
        "    \"summary\": \"199 parity probe task\"\n  }\n}",
      StandardCharsets.UTF_8
    )
    f

  private def portableInput(): Path =
    repoFile("contracts/acceptance/portable/input.json")

  private def portableDoc(): Path =
    repoFile("contracts/acceptance/portable/parity-verdict.json")

  private def workDirsOf(out: Path): Vector[Path] =
    val listing = Files.list(out)
    try
      listing
        .iterator()
        .asScala
        .toVector
        .filter(p =>
          Files.isDirectory(p) && p.getFileName.toString.startsWith("work-")
        )
    finally listing.close()

  private def runJsonOf(out: Path): String =
    workDirsOf(out)
      .map(_.resolve("run.json"))
      .find(Files.isRegularFile(_)) match
      case Some(f) => Files.readString(f, StandardCharsets.UTF_8)
      case None    => ""

  private def jsonStr(json: String, field: String): String =
    zio.json.ast.Json.decoder
      .decodeJson(json)
      .toOption
      .flatMap {
        case obj: zio.json.ast.Json.Obj =>
          obj.fields.collectFirst {
            case (k, zio.json.ast.Json.Str(v)) if k == field => v
          }
        case _ => None
      }
      .getOrElse("")

  /** Drains a child's stdout on a daemon thread (the launcher's final status
    * line goes there).
    */
  private def drainAsync(p: java.lang.Process): Thread =
    val t = new Thread(() =>
      val r = new java.io.BufferedReader(
        new java.io.InputStreamReader(p.getInputStream, StandardCharsets.UTF_8)
      )
      var line = Option(r.readLine())
      while line.isDefined do line = Option(r.readLine())
    )
    t.setDaemon(true)
    t.start()
    t

  private def spawnJava(
      env: Map[String, String],
      args: String*
  ): java.lang.Process =
    val pb = new java.lang.ProcessBuilder(("java" +: args)*)
    pb.redirectErrorStream(true)
    env.foreach { (k, v) => pb.environment().put(k, v) }
    pb.start()

  /** Polls until the stand has seen `n` more requests (in-flight readiness).
    */
  private def awaitRequests(
      stand: StandHandle,
      before: Int,
      n: Int
  ): ZIO[Any, Nothing, Unit] =
    val deadline = java.lang.System.currentTimeMillis() + 90000L
    def loop: ZIO[Any, Nothing, Unit] =
      if stand.requestCount - before >= n ||
        java.lang.System.currentTimeMillis() >= deadline
      then ZIO.unit
      else ZIO.sleep(zio.Duration.fromMillis(100L)) *> loop
    loop

  private def portableArgs(
      stand: StandHandle,
      input: Path,
      out: Path,
      ws: Path,
      deadlineSeconds: Option[Long] = None
  ): PortableRun.Args =
    PortableRun.Args(
      doc = portableDoc(),
      input = input,
      out = out,
      workspace = ws,
      provider = "openai",
      model = "stand-model-199",
      baseUrl = Some(stand.url),
      attempts = 8,
      deadlineSeconds = deadlineSeconds
    )

  private val cliEnv: Map[String, String] = Map("RAIDER_API_KEY" -> StandKey)

  // ----- spec ----------------------------------------------------------------

  private def suiteSpec =
    suite("raider portable parity matrix (RAI-199 XE-CLI-01/02/03)")(
      test(
        "XE-CLI-01: the SAME scripted task passes through the COMPILED route and the PORTABLE route with equivalent trusted verdicts, artifacts and real tool effects on fresh workspaces"
      ) {
        ZIO.serviceWithZIO[ParityEnv] { env =>
          val countBefore = env.stand.requestCount
          val authBefore = env.stand.authOk.get()
          val wsA = freshWorkspace()
          val wsB = freshWorkspace()
          val inA = compiledInput(wsA)
          val outA = Files.createTempDirectory("p199-compiled")
          val outB = Files.createTempDirectory("p199-portable")
          val inB = portableInput()
          for
            // -- Compiled route: the edited artifact via the CLI launcher. --
            oA <- WorkflowRun.run(
              WorkflowRun.Args(env.build, inA, outA, None),
              cliEnv
            )
            // -- Portable route: the SAME task as a portable document. ------
            oB <- PortableRun.run(
              portableArgs(env.stand, inB, outB, wsB),
              cliEnv
            )
          yield
            val runJsonA = runJsonOf(outA)
            val runJsonB = runJsonOf(outB)
            assertTrue(
              oA.exitCode == 0,
              oA.error.isEmpty,
              oB.exitCode == 0,
              oB.error.isEmpty,
              // Equivalent trusted verdicts, BOTH sides.
              runJsonA.contains("\"taskVerdict\":\"trusted-checks-passed\""),
              runJsonB.contains("\"taskVerdict\":\"trusted-checks-passed\""),
              // Equivalent artifacts: digests and the input splice.
              runJsonA.contains("\"inputDigest\""),
              runJsonB.contains("\"inputDigest\""),
              runJsonB.contains("\"docDigest\":\"sha256-"),
              runJsonB.contains("\"effectiveLimits\""),
              // REAL tool effects on BOTH fresh workspaces (the agent stage's
              // proc_run created the markers; the trusted checks only
              // verified them — an independent verdict per route).
              Files.exists(wsA.resolve("marker.txt")),
              Files.exists(wsB.resolve("marker.txt")),
              // 2 model rounds (compiled) + 2 model rounds (portable prepare
              // agent; the gate is a TRUSTED check and never dispatches).
              env.stand.requestCount - countBefore == 4,
              // The key is asserted on the wire for ALL FOUR rounds.
              env.stand.authOk.get() - authBefore == 4
            )
        }
      },
      test(
        "XE-CLI-02: a hard --deadline-s against a hung stand dies typed RA-DEADLINE (exit 24) with a truthful artifact"
      ) {
        ZIO.serviceWithZIO[ParityEnv] { env =>
          val ws = freshWorkspace()
          val out = Files.createTempDirectory("p199-deadline")
          env.stand.hang.set(true) // arm: requests hang in-flight
          val result = PortableRun
            .run(
              portableArgs(
                env.stand,
                portableInput(),
                out,
                ws,
                deadlineSeconds = Some(2L)
              ),
              cliEnv
            )
            .ensuring(ZIO.succeed(env.stand.hang.set(false)))
          result.map { o =>
            val runJson = runJsonOf(out)
            assertTrue(
              o.exitCode == raider.core.result.ExitCodes.DeadlineExceeded,
              o.error.exists(_.code == "RA-DEADLINE"),
              !runJson.contains("\"taskVerdict\""),
              eventsExistIn(out),
              !runJson.contains("\"status\":\"Succeeded\"")
            )
          }
        }
      },
      test(
        "XE-CLI-03: SIGTERM during portable model work -> bounded stop (143), truthful incomplete evidence, no fake completion"
      ) {
        ZIO.serviceWithZIO[ParityEnv] { env =>
          val countBefore = env.stand.requestCount
          val ws = freshWorkspace()
          val out = Files.createTempDirectory("p199-sig")
          val in = portableInput()
          // A REAL headless launcher process (raider.cli.Main workflow run).
          val p = spawnJava(
            cliEnv,
            "-cp",
            java.lang.System.getProperty("java.class.path", ""),
            "raider.cli.Main",
            "workflow",
            "run",
            "--doc",
            portableDoc().toString,
            "--input",
            in.toString,
            "--out",
            out.toString,
            "--workspace",
            ws.toString,
            "--provider",
            "openai",
            "--model",
            "stand-model-199",
            "--base-url",
            env.stand.url,
            "--attempts",
            "3"
          )
          val drainThread = drainAsync(p)
          env.stand.hang.set(true) // arm: the next request hangs in-flight
          awaitRequests(env.stand, countBefore, 1) *> ZIO.succeed {
            try
              // SIGTERM (Process.destroy sends SIGTERM on POSIX).
              p.destroy()
              val done = p.waitFor(25, TimeUnit.SECONDS)
              val exit = if done then p.exitValue() else -1
              val workDirs = workDirsOf(out)
              val runJson = runJsonOf(out)
              val eventsExist =
                workDirs.nonEmpty && Files.isRegularFile(
                  workDirs.head.resolve("events.jsonl")
                )
              val interruptedMarker =
                Files
                  .list(out)
                  .iterator()
                  .asScala
                  .map(_.getFileName.toString)
                  .exists(n =>
                    n.startsWith("interrupted-") && n.endsWith(".json")
                  )
              drainThread.join(2000)
              assertTrue(
                done, // bounded stop: the launcher returned within the wait
                exit == 143, // POSIX SIGTERM death after the bounded shutdown hook
                workDirs.nonEmpty,
                eventsExist,
                // Truthful incomplete evidence: Interrupted, never green.
                (
                  runJson.contains("\"status\":\"Interrupted\"") ||
                    interruptedMarker
                ),
                !runJson.contains("\"status\":\"Succeeded\"")
              )
            finally env.stand.hang.set(false) // disarm for the remaining suite
          }
        }
      },
      test(
        "XE-CLI-03: two concurrent portable launches share no accidental state (distinct roots/work dirs; both green on their own workspace)"
      ) {
        ZIO.serviceWithZIO[ParityEnv] { env =>
          val ws1 = freshWorkspace()
          val ws2 = freshWorkspace()
          val out1 = Files.createTempDirectory("p199-conc1")
          val out2 = Files.createTempDirectory("p199-conc2")
          for
            o1Fib <- PortableRun
              .run(portableArgs(env.stand, portableInput(), out1, ws1), cliEnv)
              .fork
            o2Fib <- PortableRun
              .run(portableArgs(env.stand, portableInput(), out2, ws2), cliEnv)
              .fork
            o1 <- o1Fib.join
            o2 <- o2Fib.join
          yield
            val root1 = jsonStr(runJsonOf(out1), "rootId")
            val root2 = jsonStr(runJsonOf(out2), "rootId")
            assertTrue(
              o1.exitCode == 0,
              o2.exitCode == 0,
              root1.nonEmpty && root2.nonEmpty && root1 != root2,
              workDirsOf(out1).head != workDirsOf(out2).head,
              Files.exists(ws1.resolve("marker.txt")),
              Files.exists(ws2.resolve("marker.txt"))
            )
        }
      }
    )

  private def eventsExistIn(out: Path): Boolean =
    workDirsOf(out)
      .map(_.resolve("events.jsonl"))
      .exists(Files.isRegularFile(_))

  def spec =
    // Live clock: artifact timestamps and the deadline bound are wall-clock
    // discipline (RAI-178 note); assertions unchanged.
    (suiteSpec @@ TestAspect.sequential @@ TestAspect.withLiveClock)
      .provideLayerShared(envLayer)

end PortableParitySpec
