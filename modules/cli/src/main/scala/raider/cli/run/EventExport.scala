package raider.cli.run

import raider.core.JobId
import raider.runtime.events.{
  EventEnvelope,
  EventHub,
  EventPayload,
  SessionSnapshot
}
import raider.runtime.jobs.JobStatus
import zio.{Clock, Duration, UIO, ZIO}
import zio.json.EncoderOps
import zio.stream.ZStream

/** RAI-060: consumer-side helpers over the runtime→UI event seam (ADR-024
  * §2.2–§2.3). Pure CLI-side plumbing — no runtime semantics are changed here:
  * the hub stays the source of truth for sequences, routing and drops.
  */
object EventExport:

  /** Reorders a per-job envelope stream into strict `seq` order (RAI-060
    * STAT-03). Reliable and diagnostics envelopes of one job are relayed by two
    * independent hub forwarders, so their ARRIVAL order is unspecified; the hub
    * contract says consumers must reorder by `seq` before reducing.
    *
    * Envelopes are held in a bounded pending map and drained as soon as the
    * next expected sequence (initially `startAfter + 1`) becomes available. A
    * delivered hub stream never gaps — a dropped diagnostic never consumed a
    * sequence (ADR-024 §2.3) — so the drain always completes; the `maxPending`
    * cap is purely defensive (a pathological gap flushes the smallest stuck
    * sequence rather than growing without bound).
    */
  def inOrder(startAfter: Long, maxPending: Int = 4096)(
      stream: ZStream[Any, Nothing, EventEnvelope]
  ): ZStream[Any, Nothing, EventEnvelope] =
    stream
      .mapAccum((startAfter + 1L, Map.empty[Long, EventEnvelope])) {
        case ((next, pending), e) =>
          val out = Vector.newBuilder[EventEnvelope]
          var rest = pending.updated(e.seq, e)
          var n = next
          while rest.contains(n) do
            out += rest(n)
            rest = rest - n
            n += 1
          if rest.size > maxPending then
            val min = rest.keysIterator.min
            out += rest(min)
            rest = rest - min
            n = min + 1
          ((n, rest), out.result())
      }
      .flatMap(ZStream.fromIterable(_))

  /** Canonical events.jsonl serialization (RAI-060 STAT-02): one REAL
    * `EventEnvelope` per line, schemaVersion 1, the zio-json codec from
    * `raider.runtime.events`, sorted by per-job seq (duplicates dropped
    * defensively — a well-formed collection never produces them).
    */
  def jsonl(envelopes: Vector[EventEnvelope]): String =
    import raider.runtime.events.EventCodecs.given
    envelopes
      .sortBy(_.seq)
      .distinctBy(_.seq)
      .map(_.toJson + "\n")
      .mkString

  /** The deterministic pre-subscribe prefix of a barrier-started job (RAI-059 +
    * RAI-060): the JobManager fires the start transition Queued→Running BEFORE
    * the handle is published, so a subscriber created after `manager.start`
    * holds that single envelope in its snapshot, not in its stream. With the
    * job body held on a barrier until after `subscribe`, the start transition
    * is the ONLY snapshot event (`lastSeq == 1`), and its `atMs` is exactly the
    * snapshot's `updatedAtMs` — the reconstruction is exact, nothing is
    * invented.
    */
  def startTransition(snapshot: SessionSnapshot): Option[EventEnvelope] =
    if snapshot.lastSeq == 1L then
      Some(
        EventEnvelope(
          schemaVersion = 1,
          seq = 1L,
          jobId = snapshot.jobId,
          rootId = snapshot.rootId,
          sessionId = snapshot.sessionId,
          atMs = snapshot.updatedAtMs,
          payload = EventPayload.JobStatusChanged(
            JobStatus.Queued,
            JobStatus.Running,
            None
          )
        )
      )
    else None

  /** Bounded best-effort wait until a consumer's `observed` sequence has caught
    * up with the hub's folded `lastSeq` for `jobId` (or `budgetMs` expires).
    * Never a gate: it only closes the tiny window between "job settled" and
    * "forwarder relayed the last envelope into the consumer", so a following
    * interrupt cannot cut off the tail (e.g. Terminal).
    *
    * The wait is WALL-CLOCK based; the short sleep explicitly uses the live
    * clock so an embedder's frozen virtual clock cannot strand the deadline
    * check after a probe.
    */
  def awaitCaughtUp(
      hub: EventHub,
      jobId: JobId,
      observed: UIO[Long],
      budgetMs: Long = 5000L
  ): UIO[Unit] =
    val deadline = System.currentTimeMillis() + budgetMs
    def loop: UIO[Unit] =
      hub.snapshot(jobId).flatMap {
        case None => ZIO.unit
        case Some(snap) =>
          observed.flatMap { have =>
            if have >= snap.lastSeq then ZIO.unit
            else if System.currentTimeMillis() >= deadline then ZIO.unit
            else
              ZIO.withClock(Clock.ClockLive)(
                ZIO.sleep(zio.Duration.fromMillis(2L))
              ) *> loop
          }
      }
    loop

end EventExport
