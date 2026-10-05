package raider.repl.facade

import raider.core.*
import raider.runtime.budget.BudgetLimits
import zio.*
import zio.stream.ZStream
import zio.test.*

import scala.jdk.CollectionConverters.*

/** REPL live-wiring fixtures (nightly Phase 3): session carries model/echo
  * defaults, every facade ask prepends the REPL system prompt, and the
  * configured model name reaches the backend request.
  */
object ReplLiveWiringSpec extends ZIOSpecDefault:

  /** Backend that records requests and answers once. */
  private final class Recording(events: Vector[ModelEvent])
      extends ModelBackend:

    val requests =
      java.util.concurrent.CopyOnWriteArrayList[ModelRequest]()

    val capabilities: ModelCapabilities = ModelCapabilities(
      CapabilityStatus.Supported,
      CapabilityStatus.Supported,
      CapabilityStatus.Unknown
    )

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap:
        ZIO.succeed:
          requests.add(input)
          ZStream.fromIterable(events)

  private val scripted =
    Vector(
      ModelEvent.Started(AttemptId("rw-att")),
      ModelEvent.TextDelta(AttemptId("rw-att"), "answer"),
      ModelEvent.Finished(AttemptId("rw-att"), "stop")
    )

  private def makeSession(backend: ModelBackend, model: String = "scripted") =
    ReplSession.make(
      backend,
      BudgetLimits.make(maxConcurrentTools = 2, maxAttempts = 8),
      ToolRegistry.empty,
      model = model,
      echoStream = true
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ReplLiveWiring")(
      test("session defaults: model=scripted, echoStream=false") {
        for s <- ReplSession.make(
            Recording(scripted),
            BudgetLimits.make()
          )
        yield assertTrue(s.model == "scripted", s.echoStream == false)
      },
      test("session carries configured model + echo flag") {
        for s <- makeSession(Recording(scripted), model = "qwen-local")
        yield assertTrue(s.model == "qwen-local", s.echoStream == true)
      },
      test("every facade ask prepends the REPL system prompt") {
        for
          s <- makeSession(Recording(scripted))
          _ <- FacadeOps.textAttempt(
            s,
            AgentRef("scout"),
            "ping"
          )
          req = s.backend match
            case r: Recording => r.requests.asScala.toList.head
            case _            => sys.error("unreachable")
        yield assertTrue(
          req.messages.headOption.exists(_.role == "system"),
          req.messages.headOption.exists(
            _.content.contains("coding agent in a Scala 3 + ZIO project")
          ),
          req.messages.headOption
            .exists(_.content.contains("fs_read, fs_search, fs_edit")),
          req.messages.last.role == "user"
        )
      },
      test("configured live model name replaces the scripted label") {
        for
          s <- makeSession(Recording(scripted), model = "qwen-local")
          _ <- FacadeOps.textAttempt(s, AgentRef("scout"), "ping")
          req = s.backend match
            case r: Recording => r.requests.asScala.toList.head
            case _            => sys.error("unreachable")
        yield assertTrue(req.model == "qwen-local")
      },
      test(
        "scripted default keeps the scripted:<agent> label (existing behavior)"
      ) {
        for
          s <- makeSession(Recording(scripted))
          _ <- FacadeOps.textAttempt(s, AgentRef("scout"), "ping")
          req = s.backend match
            case r: Recording => r.requests.asScala.toList.head
            case _            => sys.error("unreachable")
        yield assertTrue(req.model == "scripted:scout")
      }
    )
