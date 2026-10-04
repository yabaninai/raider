package raider.core

import zio.json._

/** Run trace (runtime-contracts §13 subset): bounded per-run event log that
  * makes agent work OBSERVABLE — every model round, every tool call, every
  * result, every child delegation. Traces are pure DATA (JSON-encodable);
  * recording them dispatches nothing.
  *
  * Traces feed: the CLI `--trace` flag (transcript.json per work dir), the REPL
  * `:log <job>` command, and child-agent observability (§11).
  */
object trace:

  sealed trait TraceEvent:
    def at: String // ISO timestamp

  object TraceEvent:

    final case class RunStarted(at: String, model: String, messages: Int)
        extends TraceEvent

    final case class RoundStarted(at: String, round: Int) extends TraceEvent

    final case class ModelCall(at: String, round: Int, messageCount: Int)
        extends TraceEvent

    final case class TextReceived(at: String, round: Int, text: String)
        extends TraceEvent

    final case class ToolCallStarted(
        at: String,
        round: Int,
        tool: String,
        callId: String,
        argumentsJson: String
    ) extends TraceEvent

    final case class ToolCallFinished(
        at: String,
        round: Int,
        tool: String,
        callId: String,
        resultJson: String,
        success: Boolean
    ) extends TraceEvent

    final case class ToolCallRefused(
        at: String,
        round: Int,
        tool: String,
        callId: String,
        reason: String
    ) extends TraceEvent

    final case class ChildDelegated(at: String, agent: String, childId: String)
        extends TraceEvent

    final case class ChildAwaited(at: String, childId: String, status: String)
        extends TraceEvent

    final case class RunFinished(at: String, outcome: String, detail: String)
        extends TraceEvent

    final case class RunFailed(at: String, code: String, detail: String)
        extends TraceEvent

    given JsonCodec[TraceEvent] = DeriveJsonCodec.gen[TraceEvent]

  /** Bounded run trace (immutable snapshot). */
  final case class RunTrace(
      rootId: String,
      model: String,
      events: List[TraceEvent]
  ):
    def +(e: TraceEvent): RunTrace = copy(events = events :+ e)

  object RunTrace:
    val empty: RunTrace = RunTrace("", "", Nil)
    given JsonCodec[RunTrace] = DeriveJsonCodec.gen[RunTrace]

export trace.{TraceEvent, RunTrace}
