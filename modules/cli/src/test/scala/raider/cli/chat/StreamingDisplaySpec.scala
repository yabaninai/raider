package raider.cli.chat

import raider.core.*
import raider.runtime.display.StreamingDisplay
import zio.*
import zio.stream.ZStream
import zio.test.*

import scala.collection.mutable.ListBuffer

/** Streaming display contract (nightly Phase 2.1): the wrapper is a transparent
  * ModelBackend decorator that surfaces text deltas and ready tool calls to
  * caller-provided sinks WHILE the events flow through unchanged in order.
  */
object StreamingDisplaySpec extends ZIOSpecDefault:

  private val attempt = AttemptId("sd-att")

  /** Scripted backend: replays events; optionally fails typed. Records the last
    * request for passthrough assertions.
    */
  private final class Scripted(
      events: Vector[ModelEvent],
      failWith: Option[RaiderError] = None
  ) extends ModelBackend:
    @volatile var lastRequest: Option[ModelRequest] = None

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
          lastRequest = Some(input)
          failWith match
            case Some(err) =>
              ZStream(
                ModelEvent.Started(attempt),
                ModelEvent.Failed(attempt, err)
              )
            case None =>
              ZStream.fromIterable(ModelEvent.Started(attempt) +: events)

  private def collect(
      backend: ModelBackend,
      messages: List[RequestMessage] = List(RequestMessage("user", "hi"))
  ): ZIO[Scope, RaiderError, Chunk[ModelEvent]] =
    backend
      .stream(ModelRequest("m", messages, 128, Nil))
      .runCollect

  override def spec: Spec[Any, Any] =
    suite("StreamingDisplay")(
      test("text deltas reach the sink in order; events pass through intact") {
        val scripted = Scripted(
          Vector(
            ModelEvent.TextDelta(attempt, "Hel"),
            ModelEvent.TextDelta(attempt, "lo "),
            ModelEvent
              .ToolCallReady(attempt, ToolCallId("c1"), "fs_read", "{}"),
            ModelEvent.TextDelta(attempt, "world"),
            ModelEvent.Finished(attempt, "stop")
          )
        )
        val seen = ListBuffer.empty[String]
        val display = StreamingDisplay(
          scripted,
          onTextDelta = t => ZIO.succeed(seen += t),
          onToolCall = (n, a) => ZIO.succeed(seen += s"TOOL:$n($a)")
        )
        for events <- ZIO.scoped(collect(display))
        yield assertTrue(
          seen.toList == List("Hel", "lo ", "TOOL:fs_read({})", "world"),
          events.map(_.getClass.getSimpleName) == Vector(
            "Started",
            "TextDelta",
            "TextDelta",
            "ToolCallReady",
            "TextDelta",
            "Finished"
          ),
          events.collect { case ModelEvent.TextDelta(_, t) => t }.mkString ==
            "Hello world"
        )
      },
      test(
        "only ToolCallReady triggers the tool notice (deltas never execute/display)"
      ) {
        val scripted = Scripted(
          Vector(
            ModelEvent.ToolCallDelta(attempt, ToolCallId("c1"), 0, "{\""),
            ModelEvent.ToolCallDelta(attempt, ToolCallId("c1"), 1, "a\":1}"),
            ModelEvent.ToolCallReady(
              attempt,
              ToolCallId("c1"),
              "proc_run",
              "{\"a\":1}"
            ),
            ModelEvent.Finished(attempt, "stop")
          )
        )
        val tools = ListBuffer.empty[String]
        val display = StreamingDisplay(
          scripted,
          onTextDelta = _ => ZIO.unit,
          onToolCall = (n, a) => ZIO.succeed(tools += s"$n $a")
        )
        for _ <- ZIO.scoped(collect(display))
        yield assertTrue(tools.toList == List("proc_run {\"a\":1}"))
      },
      test("passthrough: the wrapped backend sees the exact request") {
        val scripted = Scripted(Vector(ModelEvent.Finished(attempt, "stop")))
        val display =
          StreamingDisplay(scripted, _ => ZIO.unit, (_, _) => ZIO.unit)
        val req = ModelRequest(
          "model-x",
          List(RequestMessage("user", "q")),
          256,
          Nil
        )
        for _ <- ZIO.scoped(
            display.stream(req).runCollect
          )
        yield assertTrue(scripted.lastRequest == Some(req))
      },
      test(
        "typed failure surfaces as the in-band Failed event, unchanged " +
          "(AgentLoop folds it into state — mirrors real backend semantics)"
      ) {
        val scripted =
          Scripted(
            Vector.empty,
            failWith = Some(RaiderError.ProviderAuth("401"))
          )
        val display =
          StreamingDisplay(scripted, _ => ZIO.unit, (_, _) => ZIO.unit)
        for events <- ZIO.scoped(collect(display))
        yield assertTrue(
          events.collectFirst { case ModelEvent.Failed(_, e) => e.code } ==
            Some("RA-AUTH")
        )
      }
    )
