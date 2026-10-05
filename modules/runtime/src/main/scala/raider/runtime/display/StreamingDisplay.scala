package raider.runtime.display

import raider.core.*
import zio.{UIO, Scope, ZIO}
import zio.stream.ZStream

/** Transparent display decorator over a ModelBackend (nightly Phase 2.1/3.3):
  * text deltas and ready tool calls are surfaced to caller sinks the moment
  * they flow through, while the event stream itself passes through unchanged —
  * the AgentLoop keeps its single execution semantics and the backend keeps its
  * exact contract.
  *
  * Sinks are injected so tests (and non-console hosts) capture display output
  * without touching real stdout/stderr. Shared by the CLI chat loop and the
  * REPL facade.
  */
final case class StreamingDisplay(
    underlying: ModelBackend,
    onTextDelta: String => UIO[Unit],
    onToolCall: (name: String, argumentsJson: String) => UIO[Unit]
) extends ModelBackend:

  override def capabilities: ModelCapabilities = underlying.capabilities

  override def stream(
      input: ModelRequest
  ): ZStream[Scope, RaiderError, ModelEvent] =
    underlying
      .stream(input)
      .tap:
        case ModelEvent.TextDelta(_, text) => onTextDelta(text)
        case ModelEvent.ToolCallReady(_, _, name, args) =>
          onToolCall(name, args)
        case _ => ZIO.unit
