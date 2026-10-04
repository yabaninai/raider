package raider.core

/** Typed identifiers (runtime-contracts §3). Opaque-ish value classes keep codecs
  * and REPL printing simple; all descendants/events carry root lineage ids. */
object ids:
  final case class SessionId(value: String) extends AnyVal
  final case class RootId(value: String) extends AnyVal
  final case class JobId(value: String) extends AnyVal
  final case class StepId(value: String) extends AnyVal
  final case class AttemptId(value: String) extends AnyVal
  final case class ToolCallId(value: String) extends AnyVal
  final case class MessageId(value: String) extends AnyVal
  final case class ArtifactId(value: String) extends AnyVal

export ids.*
