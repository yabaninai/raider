package raider.cli.run

import raider.core.RaiderError
import zio.json.{DecoderOps, DeriveJsonDecoder, JsonDecoder}
import zio.test.*
import zio.ZIO

import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ExecutorService, Executors}
import scala.jdk.CollectionConverters.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}

/** RAI-258: aggregate run ceilings for the native coder path, driven through
  * the PRODUCT entry (`raider run --agent coder`). Three ceilings travel as
  * argv flags and bind the parent loop AND every delegated child jointly:
  *   - `--max-tokens <n>` — aggregate provider-token ceiling (input+output sums
  *     over every dispatch of the run);
  *   - `--max-dispatches <n>` — aggregate model-attempt ceiling;
  *   - `--run-deadline <seconds>` — aggregate wall-clock ceiling from run
  *     start.
  *
  * When a ceiling trips mid-run: the next model dispatch is refused with a
  * TYPED error (ProviderBudget → exit 23; DeadlineExceeded → exit 24, the
  * frozen §8 mapping), live children are cancelled out-of-band and settle
  * terminally, and the accounting stays exact (no double-count between parent
  * and children, no invented spend).
  *
  * The scripted policy is a LOCAL OpenAI-compatible stand (JDK HttpServer on
  * the loopback, ephemeral port — the CoderDelegationRunSpec framing): no real
  * network, no paid calls, credentials only through the injected test env
  * (RAIDER_API_KEY), never argv. Content routing (RAI-092 discipline): the
  * route picks the turn from the request CONTENT (single-message child turns,
  * delegate/await tool-result shapes), never from a call index. Every response
  * carries usage, so the token ledger is real from the wire's point of view.
  *
  * Determinism: the gated stand releases the parent's second `delegate`
  * response only after the first child has hit the stand, and the `await`
  * response only after BOTH children have hit it — so the two children JOINTLY
  * exhaust the token ceiling (card acceptance: "two children jointly exhausting
  * a token ceiling").
  */
object CoderAggregateCeilingSpec extends ZIOSpecDefault:

  private val Key = "rai-258-stand-key"

  /** Minimal request view for the stand's routing. */
  private final case class ReqWire(model: String, messages: List[ReqMessage])
  private final case class ReqMessage(role: String, content: String)

  private object ReqWire:
    given JsonDecoder[ReqMessage] = DeriveJsonDecoder.gen[ReqMessage]
    given JsonDecoder[ReqWire] = DeriveJsonDecoder.gen[ReqWire]

  private def toolResults(req: ReqWire): List[String] =
    req.messages.collect { case m if m.role == "tool" => m.content }

  private val ChildInputs = Set("find x", "find y")

  /** A child loop's turn: the single delegated-input message. */
  private def isChildTurn(req: ReqWire): Boolean =
    req.messages.size == 1 && ChildInputs.contains(req.messages.head.content)

  /** All child ids mentioned in delegate tool results, in order. */
  private def childIds(results: List[String]): List[String] =
    val r = """"child_id":"(j_\d+)"""".r
    results.flatMap(m => r.findFirstMatchIn(m).map(_.group(1)))

  private def esc(s: String): String = s.replace("\"", "\\\"")

  private def textResponse(
      id: String,
      text: String,
      inTok: Long,
      outTok: Long
  ): String =
    s"""{"id":"$id","choices":[{"index":0,"message":{"role":"assistant",
       "content":"$text"},"finish_reason":"stop"}],
       "usage":{"prompt_tokens":$inTok,"completion_tokens":$outTok}}"""

  private def toolResponse(
      id: String,
      callId: String,
      tool: String,
      argsJson: String,
      inTok: Long,
      outTok: Long
  ): String =
    s"""{"id":"$id","choices":[{"index":0,"message":{"role":"assistant",
       "content":null,"tool_calls":[{"id":"$callId","type":"function",
       "function":{"name":"$tool","arguments":"${esc(argsJson)}"}}]},
       "finish_reason":"tool_calls"}],
       "usage":{"prompt_tokens":$inTok,"completion_tokens":$outTok}}"""

  private def delegateResponse(callId: String, input: String): String =
    toolResponse(
      s"chatcmpl-rai258-$callId",
      callId,
      "delegate",
      s"""{"agent":"scout","input":"$input"}""",
      11,
      5
    )

  private def awaitResponse(callId: String, cid: String): String =
    toolResponse(
      s"chatcmpl-rai258-$callId",
      callId,
      "await_agent",
      s"""{"child_id":"$cid"}""",
      21,
      4
    )

  private val ChildResponse =
    textResponse("chatcmpl-rai258-child", "found it", 9, 3)

  private val FinalResponse = textResponse(
    "chatcmpl-rai258-final",
    "both children reported",
    31,
    6
  )

  /** `stallChildMs` delays child responses (aggregate-deadline scenario:
    * children are mid-dispatch when the deadline trips and must be cancelled
    * and settled terminally). `gate` makes the delegate/await responses wait
    * for the children to have hit the stand first (joint-exhaustion
    * determinism); it MUST be false when children stall, or the gated parent
    * round would wait on a child that never completes.
    */
  private def startStand(
      stallChildMs: Long,
      gate: Boolean
  ): (String, HttpServer, ExecutorService, CopyOnWriteArrayList[ReqWire]) =
    val requests = new CopyOnWriteArrayList[ReqWire]()
    val childHits = new AtomicInteger(0)
    val server = HttpServer.create(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      0
    )
    val pool = Executors.newFixedThreadPool(4)
    server.setExecutor(pool)
    server.createContext(
      "/v1/chat/completions",
      (exchange: HttpExchange) =>
        try
          def reply(code: Int, body: String): Unit =
            val bytes = body.getBytes(UTF_8)
            exchange.sendResponseHeaders(code, bytes.length)
            exchange.getResponseBody.write(bytes)
          def awaitChildHits(n: Int): Unit =
            val until = System.currentTimeMillis() + 10000L
            while childHits.get() < n && System.currentTimeMillis() < until do
              Thread.sleep(10L)
          Option(exchange.getRequestHeaders.getFirst("Authorization")) match
            case None =>
              reply(401, """{"error":{"message":"missing api key"}}""")
            case Some(a) if a != s"Bearer $Key" =>
              reply(401, """{"error":{"message":"incorrect api key"}}""")
            case Some(_) =>
              val body =
                new String(exchange.getRequestBody.readAllBytes(), UTF_8)
              body.fromJson[ReqWire] match
                case Left(_) =>
                  reply(400, """{"error":{"message":"bad request"}}""")
                case Right(req) =>
                  requests.add(req)
                  if req.model != "stand-model" then
                    reply(
                      400,
                      s"""{"error":{"message":"model ${req.model} not deployed; expected stand-model"}}"""
                    )
                  else if isChildTurn(req) then
                    if stallChildMs > 0 then Thread.sleep(stallChildMs)
                    childHits.incrementAndGet()
                    reply(200, ChildResponse)
                  else
                    val results = toolResults(req)
                    val ids = childIds(results)
                    val statuses =
                      results.count(_.contains("\"status\""))
                    if ids.size == 0 then
                      // campaign turn 1: delegate the first child
                      reply(200, delegateResponse("call_1", "find x"))
                    else if ids.size == 1 then
                      if gate then awaitChildHits(1)
                      // campaign turn 2: delegate the second child
                      reply(200, delegateResponse("call_2", "find y"))
                    else if statuses == 0 then
                      if gate then awaitChildHits(2)
                      // both children running: await the first
                      reply(200, awaitResponse("call_3", ids(0)))
                    else if statuses == 1 then
                      // first await returned: await the second
                      reply(200, awaitResponse("call_4", ids(1)))
                    else
                      // both awaits returned: finish
                      reply(200, FinalResponse)
        finally exchange.close()
    )
    server.start()
    (
      s"http://127.0.0.1:${server.getAddress.getPort}/v1",
      server,
      pool,
      requests
    )

  private def stopStand(h: (String, HttpServer, ExecutorService, ?)): Unit =
    h._2.stop(0)
    h._3.shutdownNow()

  private def parseArgs(
      extra: List[String],
      out: Path,
      ws: Path,
      baseUrl: String
  ): Either[RaiderError, CliArgs] =
    CliArgs.parse(
      List(
        "run",
        "--agent",
        "coder",
        "--input",
        "delegate two searches",
        "--out",
        out.toString,
        "--provider",
        "openai",
        "--base-url",
        baseUrl,
        "--model",
        "stand-model",
        "--workspace",
        ws.toString
      ) ++ extra
    )

  private def runParsed(
      parsed: Either[RaiderError, CliArgs],
      requests: CopyOnWriteArrayList[ReqWire]
  ): ZIO[
    Any,
    Nothing,
    Either[String, (HeadlessRunner.Outcome, List[ReqWire])]
  ] =
    parsed match
      case Left(e) =>
        ZIO.succeed(Left(s"parse refused the argv: ${e.code}: ${e.detail}"))
      case Right(args) =>
        HeadlessRunner
          .run(args, Map("RAIDER_API_KEY" -> Key))
          .map(o => Right((o, requests.asScala.toList)))

  // RAI-178 house pattern: the suite drives the REAL product runner whose
  // settle/export path polls with ZIO.sleep on the ambient clock — a live
  // clock is required (a real `raider run` never runs on a frozen clock).
  def spec = suite(
    "headless coder aggregate ceilings through the product entry (RAI-258)"
  )(
    test(
      "aggregate token ceiling: --max-tokens 60 denies a two-child coder run at the ceiling (exit 23), both children hit the wire, dispatches stay bounded"
    ) {
      ZIO.acquireReleaseWith(ZIO.succeed(startStand(0L, gate = true)))(stand =>
        ZIO.succeed(stopStand(stand))
      ) { stand =>
        val requests = stand._4
        ZIO.scoped {
          for
            work <- ZIO
              .attempt(Files.createTempDirectory("rai-258-tok"))
              .orDie
            ws <- ZIO
              .attempt(Files.createTempDirectory("rai-258-tok-ws"))
              .orDie
            outcome <- runParsed(
              parseArgs(List("--max-tokens", "60"), work, ws, stand._1),
              requests
            )
          yield outcome match
            case Left(err) =>
              assertTrue(err.isEmpty)
            case Right((o, hits)) =>
              val childReqs = hits.filter(isChildTurn)
              assertTrue(
                o.exitCode == 23,
                childReqs.size == 2,
                hits.size == 5,
                o.result.isEmpty || o.exitCode != 0
              )
        }
      }
    },
    test(
      "control: the same two-child run with a generous --max-tokens 100000 stays green (exit 0) — the ceiling wiring does not falsely deny"
    ) {
      ZIO.acquireReleaseWith(ZIO.succeed(startStand(0L, gate = true)))(stand =>
        ZIO.succeed(stopStand(stand))
      ) { stand =>
        val requests = stand._4
        ZIO.scoped {
          for
            work <- ZIO
              .attempt(Files.createTempDirectory("rai-258-ok"))
              .orDie
            ws <- ZIO
              .attempt(Files.createTempDirectory("rai-258-ok-ws"))
              .orDie
            outcome <- runParsed(
              parseArgs(List("--max-tokens", "100000"), work, ws, stand._1),
              requests
            )
          yield outcome match
            case Left(err) =>
              assertTrue(err.isEmpty)
            case Right((o, hits)) =>
              assertTrue(
                o.exitCode == 0,
                hits.size == 7,
                o.result
                  .exists(r =>
                    r.output.exists(_.json.contains("both children reported"))
                  )
              )
        }
      }
    },
    test(
      "aggregate run deadline: --run-deadline 1 cancels the in-flight child mid-dispatch and the queued child mid-wait, fails the run typed (exit 24), well bounded in time"
    ) {
      ZIO.acquireReleaseWith(
        ZIO.succeed(startStand(4000L, gate = false))
      )(stand => ZIO.succeed(stopStand(stand))) { stand =>
        val requests = stand._4
        ZIO.scoped {
          for
            work <- ZIO
              .attempt(Files.createTempDirectory("rai-258-dl"))
              .orDie
            ws <- ZIO
              .attempt(Files.createTempDirectory("rai-258-dl-ws"))
              .orDie
            startedAt <- ZIO.succeed(System.currentTimeMillis())
            outcome <- runParsed(
              parseArgs(List("--run-deadline", "1"), work, ws, stand._1),
              requests
            )
            elapsedMs <- ZIO.succeed(System.currentTimeMillis() - startedAt)
          yield outcome match
            case Left(err) =>
              assertTrue(err.isEmpty)
            case Right((o, hits)) =>
              val childReqs = hits.filter(isChildTurn)
              // Wire accounting under the product's admission limits
              // (maxConcurrentLlm = 1): the pre-trip interleaving of root
              // rounds and child dispatches is scheduler-dependent (no gate
              // here), but the ceiling pins the STABLE properties: exactly
              // ONE child can be mid-dispatch when the 1s deadline trips (a
              // second child, if any, sits queued for the model slot and is
              // cancelled without ever reaching the wire); the wire carries
              // only a bounded pre-trip campaign prefix (2 hits child-first,
              // 4 hits root-first); and NOTHING hits the wire after the trip
              // — the post-trip root dispatch is refused by the ceiling
              // admission BEFORE the wire. Exact gated ordering is the
              // component spec's job, not the e2e's.
              assertTrue(
                o.exitCode == 24,
                // The honest-failure path still emits a typed RunResult —
                // Failed status via exit 24 and the EXACT deadline trip.
                o.result.exists(r =>
                  r.exitCode == 24 &&
                    r.error.contains(
                      RaiderError.DeadlineExceeded(
                        "aggregate run deadline of 1s elapsed; " +
                          "further model dispatch is refused"
                      )
                    )
                ),
                childReqs.size == 1,
                hits.size >= 2 && hits.size <= 4,
                elapsedMs < 30000L
              )
        }
      }
    },
    test(
      "--max-dispatches must be a positive number of dispatches (typed parse refusal before any dispatch)"
    ) {
      val parsed = parseArgs(
        List("--max-dispatches", "0"),
        Files.createTempDirectory("rai-258-bad"),
        Files.createTempDirectory("rai-258-bad-ws"),
        "http://127.0.0.1:9/v1"
      )
      parsed match
        case Right(_) =>
          assertTrue(false)
        case Left(e: RaiderError.Configuration) =>
          assertTrue(
            e.detail.contains(
              "--max-dispatches must be a positive number of dispatches"
            )
          )
        case Left(other) =>
          assertTrue(other.detail.isEmpty)
    },
    test(
      "ceiling flags are agent-only: --max-tokens with --bundle/--workflow is a typed parse refusal"
    ) {
      val work = Files.createTempDirectory("rai-258-bdl")
      val parsed = CliArgs.parse(
        List(
          "run",
          "--bundle",
          (work.resolve("b.jar")).toString,
          "--workflow",
          "wf",
          "--input",
          "x",
          "--out",
          work.toString,
          "--base-url",
          "http://127.0.0.1:9/v1",
          "--max-tokens",
          "100"
        )
      )
      parsed match
        case Right(_) =>
          assertTrue(false)
        case Left(e: RaiderError.Configuration) =>
          assertTrue(
            e.detail.contains("--max-tokens") && e.detail.contains("--agent")
          )
        case Left(other) =>
          assertTrue(other.detail.isEmpty)
    },
    test(
      "ceiling flags are coder-only: --max-tokens with --agent scout is a typed preflight refusal (exit 21) before any dispatch"
    ) {
      // RF-1 (RAI-258 review): the non-coder AGENT-scope refusal is a
      // HeadlessRunner preflight refusal (CliArgs.parse ACCEPTS the flags for
      // any built-in agent; the runner refuses them for every agent but
      // coder). The dead base-url keeps the case adversarial: any wire call
      // would surface as a provider error, never as the typed refusal.
      val work = Files.createTempDirectory("rai-258-scout")
      val ws = Files.createTempDirectory("rai-258-scout-ws")
      val parsed = CliArgs.parse(
        List(
          "run",
          "--agent",
          "scout",
          "--input",
          "x",
          "--out",
          work.toString,
          "--provider",
          "openai",
          "--base-url",
          "http://127.0.0.1:9/v1",
          "--model",
          "stand-model",
          "--workspace",
          ws.toString,
          "--max-tokens",
          "100"
        )
      )
      parsed match
        case Left(e) =>
          ZIO.succeed(
            assertTrue(
              s"parse refused the argv: ${e.code}: ${e.detail}".isEmpty
            )
          )
        case Right(args) =>
          HeadlessRunner.run(args, Map("RAIDER_API_KEY" -> Key)).map { o =>
            assertTrue(
              o.exitCode == 21,
              o.result.exists(r =>
                r.exitCode == 21 &&
                  r.error.contains(
                    RaiderError.Configuration(
                      "--max-dispatches/--max-tokens/--run-deadline apply to " +
                        "the native coder agent (--agent coder); other " +
                        "agents do not delegate"
                    )
                  )
              )
            )
          }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(
    zio.Duration.fromSeconds(300)
  )

end CoderAggregateCeilingSpec
