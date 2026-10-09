package raider.cli.run

import raider.core.*
import raider.provider.anthropic.AnthropicMessagesBackend
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.display.RateLimitRetries
import raider.runtime.loop.AgentLoop
import zio.stream.ZStream
import zio.test.*
import zio.test.live
import zio.{Duration as ZDuration, Scope, ZIO}

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** RAI-079 (ANT-01 deferred round, RAI-071 follow-up): the FULL
  * `--provider anthropic` tool round through the REAL cli wiring —
  * [[ProviderBackend]] factory → native Anthropic Messages backend
  * (NON-streaming, so no SSE is needed) → [[AgentLoop]] → REAL workspace fs
  * tools on a throwaway temp dir — against a LOCAL JDK HttpServer stand on the
  * loopback (ephemeral port). No network egress, no spend, never a live
  * endpoint.
  *
  * Scripted wire: round 1 answers `stop_reason=tool_use` with one `tool_use`
  * block (fs_read); round 2 — reached only after the loop turned the REAL tool
  * output into a NATIVE `tool_result` block — answers the final text, gated on
  * the actual file content arriving in the request, so a fake or
  * short-circuited tool cannot pass. Credentials are asserted on the wire:
  * every request must carry the exact `x-api-key` the factory was given and the
  * pinned `anthropic-version`.
  *
  * Usage is asserted honestly: token counts are EXACTLY the scripted wire
  * values with `priceKnown=false` — the backend reports what the provider sent
  * and never invents a price.
  */
object AnthropicStandSpec extends ZIOSpecDefault:

  private val StandKey = "stand-key-x"
  private val Marker = "raider-stand-marker-42"
  private val FinalText = s"stand read: $Marker"

  /** Round 1: stop_reason=tool_use with one native tool_use block. */
  private val ToolRound1 =
    """{"id":"astand_tool1","type":"message","role":"assistant",""" +
      """"content":[{"type":"tool_use","id":"standcall1","name":"fs_read",""" +
      """"input":{"path":"NOTES.txt"}}],"stop_reason":"tool_use",""" +
      """"usage":{"input_tokens":11,"output_tokens":5}}"""

  /** Round 2: final text; distinct usage so both rounds are tellable apart. */
  private def finalBody(text: String) =
    s"""{"id":"astand_final","type":"message","role":"assistant",""" +
      s""""content":[{"type":"text","text":"$text"}],"stop_reason":"end_turn",""" +
      s""""usage":{"input_tokens":23,"output_tokens":7}}"""

  // ---------- stand ----------

  private final case class StandHandle(
      url: String,
      server: HttpServer,
      requestsSeen: AtomicInteger,
      bodies: CopyOnWriteArrayList[String],
      apiKeys: CopyOnWriteArrayList[String],
      apiVersions: CopyOnWriteArrayList[String]
  ):
    def stop(): Unit = server.stop(0)
    def requestCount: Int = requestsSeen.get
    def body(k: Int): String = bodies.get(k)
    def keysSeen: List[String] = apiKeys.asScala.toList
    def versionsSeen: List[String] = apiVersions.asScala.toList

  private def startStand(): StandHandle =
    val requestsSeen = new AtomicInteger(0)
    val bodies = new CopyOnWriteArrayList[String]
    val apiKeys = new CopyOnWriteArrayList[String]
    val apiVersions = new CopyOnWriteArrayList[String]
    val server = HttpServer.create(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      0
    )
    server.createContext(
      "/v1/messages",
      (ex: HttpExchange) =>
        try
          requestsSeen.incrementAndGet()
          def reply(status: Int, body: String): Unit =
            val b = body.getBytes(UTF_8)
            ex.getResponseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, b.length)
            ex.getResponseBody.write(b)
          val key =
            Option(ex.getRequestHeaders.getFirst("x-api-key")).getOrElse("")
          val version =
            Option(ex.getRequestHeaders.getFirst("anthropic-version"))
              .getOrElse("")
          apiKeys.add(key)
          apiVersions.add(version)
          if key != StandKey then
            reply(
              401,
              """{"type":"error","error":{"type":"authentication_error",""" +
                """"message":"invalid x-api-key"}}"""
            )
          else if version != AnthropicMessagesBackend.PinnedApiVersion then
            reply(
              400,
              """{"type":"error","error":{"type":"invalid_request_error",""" +
                """"message":"anthropic-version must be """ +
                s"${AnthropicMessagesBackend.PinnedApiVersion}\"}}"
            )
          else
            val body = new String(ex.getRequestBody.readAllBytes(), UTF_8)
            bodies.add(body)
            // The tool result travels as a NATIVE tool_result block in a user
            // message — round 2 is keyed on it being present in the request.
            if !body.contains("tool_result") then reply(200, ToolRound1)
            else if body.contains(Marker) then reply(200, finalBody(FinalText))
            else
              reply(
                200,
                finalBody(
                  "tool result did not carry the real file content " +
                    "(marker missing)"
                )
              )
        finally ex.close()
    )
    server.start()
    StandHandle(
      s"http://127.0.0.1:${server.getAddress.getPort}/v1",
      server,
      requestsSeen,
      bodies,
      apiKeys,
      apiVersions
    )

  private def withStand[A](
      use: StandHandle => ZIO[Any, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(ZIO.succeed(startStand()))(h =>
      ZIO.succeed(h.stop())
    )(use)

  // ---------- event recording (the loop does not expose backend events) -----

  private final class RecordingBackend(underlying: ModelBackend)
      extends ModelBackend:
    private val seen = new CopyOnWriteArrayList[ModelEvent]
    def capabilities: ModelCapabilities = underlying.capabilities
    def events: List[ModelEvent] = seen.asScala.toList

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      underlying.stream(input).tap(ev => ZIO.succeed(seen.add(ev)))

  private def limits(): BudgetLimits =
    BudgetLimits
      .make(maxAttempts = 8, maxConcurrentTools = 2)
      .fold(e => throw e, identity)

  def spec = suite("anthropic stand tool round (RAI-079)")(
    test(
      "ANT-01 deferred round: stand tool_use → REAL fs_read → native tool_result → final text"
    ) {
      val work = Files.createTempDirectory("raider-ant-stand")
      Files.writeString(work.resolve("NOTES.txt"), s"$Marker\n", UTF_8)
      live {
        withStand { handle =>
          for
            built <- ProviderBackend
              .make("anthropic", handle.url, Some(StandKey))
            rec = new RecordingBackend(built)
            ws <- raider.tools.CodingToolset.make(work.toString)
            registry = ws.registry.fold(e => throw e, identity)
            adm <- Admission.make(Right(limits()))
            loop = AgentLoop(
              rec,
              registry,
              "stand-claude",
              adm,
              AgentLoop.newRoot()
            )
            answer <- loop
              .runText(
                List(RequestMessage("user", "read NOTES.txt and summarize")),
                limits(),
                4
              )
              .timeout(ZDuration.fromSeconds(60))
            evs = rec.events
            toolCalls = evs.collect {
              case ModelEvent.ToolCallReady(_, id, name, args) =>
                (id, name, args)
            }
            usages = evs.collect { case ModelEvent.UsageObserved(_, u, known) =>
              (u, known)
            }
            attempts = evs.collect { case ModelEvent.Started(a) => a }
            finishes = evs.collect { case ModelEvent.Finished(a, r) =>
              (a, r)
            }
          yield assertTrue(
            // the factory built the live-wire wrapper around the native
            // NON-streaming Messages backend (SSE not needed for the round)
            built.isInstanceOf[RateLimitRetries],
            built.capabilities.streaming == CapabilityStatus.Unsupported,
            answer == Some(FinalText),
            // exactly two model rounds hit the stand
            handle.requestCount == 2,
            // round 1: NON-streaming request, no tool result yet
            handle.body(0).contains("\"stream\":false"),
            !handle.body(0).contains("tool_result"),
            // round 2 exists ONLY because the loop really ran fs_read and the
            // backend translated the envelope into a NATIVE tool_result block
            handle.body(1).contains("tool_result"),
            handle.body(1).contains("\"tool_use_id\":\"standcall1\""),
            handle.body(1).contains(Marker), // REAL content reached the model
            handle.body(1).contains("sha256"), // fs_read provenance rode along
            // credentials flowed through the factory onto the wire
            handle.keysSeen.forall(_ == StandKey),
            handle.versionsSeen.forall(
              _ == AnthropicMessagesBackend.PinnedApiVersion
            ),
            // the loop saw the tool call exactly as scripted
            toolCalls == Vector(
              (ToolCallId("standcall1"), "fs_read", """{"path":"NOTES.txt"}""")
            ),
            // usage honesty: exactly the scripted wire values, price NEVER
            // invented (priceKnown=false, currency None)
            usages == Vector(
              (Usage(11, 5, None), false),
              (Usage(23, 7, None), false)
            ),
            attempts == Vector(
              AttemptId("astand_tool1"),
              AttemptId("astand_final")
            ),
            finishes == Vector(
              (AttemptId("astand_tool1"), "tool_use"),
              (AttemptId("astand_final"), "end_turn")
            )
          )
        }
      }
    },
    test(
      "wrong stand key → typed RA-AUTH through the factory path, no retry"
    ) {
      val work = Files.createTempDirectory("raider-ant-stand-auth")
      live {
        withStand { handle =>
          for
            built <- ProviderBackend
              .make("anthropic", handle.url, Some("wrong-key-1"))
            ws <- raider.tools.CodingToolset.make(work.toString)
            registry = ws.registry.fold(e => throw e, identity)
            adm <- Admission.make(Right(limits()))
            loop = AgentLoop(
              built,
              registry,
              "stand-claude",
              adm,
              AgentLoop.newRoot()
            )
            res <- loop
              .runText(
                List(RequestMessage("user", "u")),
                limits(),
                4
              )
              .timeout(ZDuration.fromSeconds(60))
              .either
          yield assertTrue(
            res.fold(_.code == "RA-AUTH", _ => false),
            res.fold(_.detail.contains("401"), _ => false),
            handle.requestCount == 1, // auth failure is NOT a 429: no retry
            handle.keysSeen == List("wrong-key-1")
          )
        }
      }
    }
  )
