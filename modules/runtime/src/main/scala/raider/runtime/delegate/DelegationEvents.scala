package raider.runtime.delegate

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.events.{EventHub, EventPayload}
import zio.{UIO, ZIO}

/** Optional delegation emission context (RAI-059, ADR-024 §2.6): binds one
  * [[DelegationToolset]] to an [[EventHub]].
  *
  * Payloads (frozen family, ADR-024 §5) and their per-job routing:
  *   - `Delegated(childJobId, agent)` — RELIABLE, published on FORK under the
  *     CHILD's job id: it belongs to the child's own per-job stream (where the
  *     child's JobStatusChanged/Terminal events, if the JobManager carries a
  *     listener, continue the same sequence);
  *   - `Diagnostics("child … completed: …")` — DIAGNOSTICS (non-blocking,
  *     droppable with a counter), the ChildAwaited equivalent, routed under the
  *     synthetic per-root pool `JobId(rootId.value)` — the SAME convention as
  *     [[raider.runtime.budget.BudgetLedger]]. Reason: the child's own stream
  *     is frozen at `Terminal` (the hub reducer refuses any event after a
  *     terminal snapshot as `Malformed`, and `await_agent` observes completion
  *     only AFTER the settle has published `Terminal`), so a post-completion
  *     event under the child id would always be rejected. The per-root
  *     delegation lifecycle stream never receives a `Terminal` and stays
  *     gap-free across every child of the root. A child that is never awaited
  *     has no completion observation in this slice (child watching is RAI-063).
  *
  * `None` at the call site = zero overhead: every emission point degrades to
  * `ZIO.unit`. Emission never alters the tool feedback JSON: every `EventError`
  * is swallowed (the `LoopEvents` convention, RAI-058); an interruption during
  * publish propagates as the run's own cancellation.
  */
final case class DelegationEvents(
    hub: EventHub,
    rootId: RootId
):

  private val session = SessionId("")

  /** The synthetic per-root pool id for delegation lifecycle diagnostics (never
    * terminal — see the class doc).
    */
  private def lifecycleJob: JobId = JobId(rootId.value)

  /** RELIABLE: the child fork is registered under `agent`. */
  def delegated(childJobId: JobId, agent: String): UIO[Unit] =
    hub
      .publish(
        childJobId,
        rootId,
        session,
        EventPayload.Delegated(childJobId, agent)
      )
      .catchAll(_ => ZIO.unit)

  /** DIAGNOSTICS: the ChildAwaited equivalent — the child completion as
    * observed by `await_agent` (`status` = Succeeded | Failed, `detail` = the
    * typed error code on failure), under the per-root lifecycle pool.
    */
  def childCompleted(
      childJobId: JobId,
      status: String,
      detail: Option[String]
  ): UIO[Unit] =
    val text = detail match
      case Some(d) => s"child ${childJobId.value} completed: $status ($d)"
      case None    => s"child ${childJobId.value} completed: $status"
    hub
      .publish(
        lifecycleJob,
        rootId,
        session,
        EventPayload.Diagnostics(
          text.take(DelegationEvents.maxDiagnosticsChars)
        )
      )
      .catchAll(_ => ZIO.unit)

object DelegationEvents:

  /** `EventPayload.Diagnostics.text` bound (ADR-024 §5). */
  val maxDiagnosticsChars: Int = 2000

end DelegationEvents
