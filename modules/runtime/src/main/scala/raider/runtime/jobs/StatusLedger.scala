package raider.runtime.jobs

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.events.{EventHub, EventPayload}
import zio.ZIO

/** StatusLedger adapter (RAI-059, ADR-024 §2.6): bridges a [[JobManager]]'s
  * optional [[OnTransition]] hook to an [[EventHub]].
  *
  * Payload mapping (frozen family, ADR-024 §5):
  *   - `to` in the [[JobStatus.Terminal]] subtrait → `Terminal(outcome)`.
  *     Exactly once per job: the manager fires the hook exactly once per
  *     COMMITTED status change, and a terminal status admits no further
  *     transition (canTransition), so the stream contains exactly one
  *     `Terminal`, last by per-job seq (EMJ-01);
  *   - any other `to` → `JobStatusChanged(from, to, reason = None)` — the job
  *     layer carries no wait reason; the typed `Waiting(reason)` events are
  *     owned by the loop layer (RAI-058, `LoopEvents`).
  *
  * Envelope routing: the job's OWN id (its per-job sequence), the adapter's
  * fixed `rootId` (one JobManager wiring owns one root — the delegation toolset
  * runs all children under the delegating root, runtime-contracts §11), and the
  * empty session (the job layer has no session identity of its own).
  *
  * Every `EventError` (Backpressure, Malformed) is swallowed: emission never
  * breaks, masks or delays the job operation (EMJ-02/EMJ-03) — the manager
  * additionally absorbs listener defects and re-raises interruptions, so the
  * full hook path cannot alter any typed outcome.
  */
object StatusLedger:

  /** The job layer has no session identity of its own: the empty session. */
  val sessionId: SessionId = SessionId("")

  /** Build the [[OnTransition]] hook that publishes into `hub` for `rootId`. */
  def onTransition(
      hub: EventHub,
      rootId: RootId
  ): OnTransition =
    (jobId, from, to) =>
      hub
        .publish(
          jobId,
          rootId,
          sessionId,
          to match
            case t: JobStatus.Terminal => EventPayload.Terminal(t)
            case other => EventPayload.JobStatusChanged(from, other, None)
        )
        .catchAll(_ => ZIO.unit)

end StatusLedger
