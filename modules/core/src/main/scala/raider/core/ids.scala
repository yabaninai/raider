package raider.core

/** Typed identifiers (runtime-contracts §3). Opaque-ish value classes keep
  * codecs and REPL printing simple; all descendants/events carry root lineage
  * ids.
  */
object ids:
  final case class SessionId(value: String) extends AnyVal
  final case class RootId(value: String) extends AnyVal
  final case class JobId(value: String) extends AnyVal
  final case class StepId(value: String) extends AnyVal
  final case class AttemptId(value: String) extends AnyVal
  final case class ToolCallId(value: String) extends AnyVal
  final case class MessageId(value: String) extends AnyVal
  final case class ArtifactId(value: String) extends AnyVal

// Package-scope aliases for the ids vocabulary (RAI-252). An explicit
// `type` + `val` pair per name instead of `export ids.*`: the package-level
// export compiled into the synthetic ids$package forwarder object with a
// dotc method-emission order that permutes between clean compile rounds
// (RAI-240 limitation 1), so adapter jars were never byte-identical across
// clean rounds. Explicit definitions emit in source order. The public names
// resolve exactly where the export put them: `SessionId` as a type and as a
// term (constructor/pattern) via `import raider.core.*`, and unchanged via
// the qualified `raider.core.ids.*` path.
type SessionId = ids.SessionId
val SessionId: ids.SessionId.type = ids.SessionId
type RootId = ids.RootId
val RootId: ids.RootId.type = ids.RootId
type JobId = ids.JobId
val JobId: ids.JobId.type = ids.JobId
type StepId = ids.StepId
val StepId: ids.StepId.type = ids.StepId
type AttemptId = ids.AttemptId
val AttemptId: ids.AttemptId.type = ids.AttemptId
type ToolCallId = ids.ToolCallId
val ToolCallId: ids.ToolCallId.type = ids.ToolCallId
type MessageId = ids.MessageId
val MessageId: ids.MessageId.type = ids.MessageId
type ArtifactId = ids.ArtifactId
val ArtifactId: ids.ArtifactId.type = ids.ArtifactId
