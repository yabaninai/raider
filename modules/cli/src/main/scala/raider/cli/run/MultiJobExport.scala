package raider.cli.run

import raider.core.JobId
import raider.runtime.events.{EventEnvelope, EventHub, EventPayload}
import raider.runtime.jobs.{JobStatus, OnTransition}
import raider.runtime.lineage.wire.{
  ArtifactPointer,
  LineageEnvelope,
  LineagePayload,
  LineageWire
}
import zio.{Fiber, Ref, Semaphore, UIO, ZIO}

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** RAI-144 (ADR-032 §6): the multi-job lineage export — the component that
  * joins delegate CHILD jobs' envelopes into the RUN's single
  * `events.jsonl` with the ADR-032 correlation fields, so the versioned
  * reducer ([[raider.runtime.lineage.reduce.LineageReducer.reduceWire]]) builds
  * ONE provenance tree with real parent-child edges.
  *
  * == What is joined, and how
  *
  * The run owns one [[EventHub]] and one [[JobManager]]; delegation forks
  * ([[raider.runtime.delegate.DelegationToolset]]) start every child on the
  * SAME manager and publish the frozen `Delegated(childJobId, agent)` fork
  * record under the CHILD's own job id
  * ([[raider.runtime.delegate.DelegationEvents]] convention). This exporter:
  *
  *   - ''captures'' every job losslessly: the `capture` transition listener is
  *     composed BEFORE the status ledger in the manager's single
  *     `onTransition` hook, and the hook fires after the committed
  *     Queued→Running transition but BEFORE the job fiber is forked
  *     ([[raider.runtime.jobs.JobManager]] discipline) — so the subscriber is
  *     registered before the job can publish anything, and the per-job stream
  *     is complete from seq 1 ([[EventExport.inOrder]] re-establishes per-job
  *     seq order against the unspecified cross-class arrival order);
  *   - ''connects'' a captured job as a CHILD SEGMENT when its stream carries
  *     the `Delegated(childId, …)` introduction: it assigns the
  *     invocation-level join key `invocationId` (`inv-<n>`, in join order),
  *     creates the child's NATIVE per-job artifacts
  *     (`<workDir>/jobs/<childJobId>/events.jsonl` — the verbatim hub stream of
  *     that job, frozen v1 lines, readable by the frozen v1 reader), and joins
  *     the segment into the run stream as `schemaVersion: 2` envelopes with
  *     `invocationId` set;
  *   - ''canonicalizes the fork record'': the joined stream represents the
  *     introduction ONCE, in the FORKING job's stream (RAI-154 per-level
  *     anchoring: the job registered for the child via
  *     [[anchorForkUnder]]; unregistered children anchor in the CAMPAIGN
  *     (root) stream exactly as before) — a frozen v1
  *     `Delegated(childJobId, agent)` envelope appended at the moment the
  *     child's fork record was observed (the reducer's parent control edge,
  *     ADR-032 §1: "the parent control edge still comes only from the parent's
  *     frozen `Delegated` payload"). The child's own copy of the fork record
  *     stays in the child's NATIVE segment file only; it never enters the
  *     joined stream (a second introduction would be
  *     `ReduceError.DuplicateDelegation`). The application-owned index pattern
  *     is NOT an edge source: no edge exists between roots the correlation
  *     contract does not link — a captured job whose stream never carries the
  *     `Delegated` introduction is never joined (its held envelopes are dropped
  *     at [[close]]; nothing is invented);
  *   - ''records provenance'': when the CAMPAIGN job's `Terminal` is exported
  *     and at least one segment was joined, ONE `run_provenance` record (v2,
  *     campaign job id, `parentRunId` absent — the run is the root run, never
  *     guessed) is appended immediately before the Terminal, carrying
  *     `{kind: "work_dir"} / {kind: "events"}` pointers to every child
  *     segment's native artifacts, in invocation order.
  *
  * == Seq space and interleave policy (pinned; the reducer's canonical order)
  *
  * The exported stream's per-job `seq` is the EXPORT-CANONICAL sequence:
  * dense `1,2,3,…` per job in FILE order, assigned under the export lock at
  * append time. For a run without delegation the file order equals the hub's
  * per-job seq order, so the export is byte-identical to the pre-existing v1
  * layout (no silent artifact-layout change). When the canonical fork record
  * or the provenance record is interposed, later envelopes of that job simply
  * take the next export seq — the raw hub numbering stays observable in the
  * per-job native segment files, which keep the verbatim hub envelopes.
  *
  * Interleave policy: parent and child envelopes interleave by EMISSION time
  * at one serialized sink — every line is appended under a single export lock
  * (the same semaphore that updates the in-memory fold buffer), so the file
  * order and the reduction input are always the same sequence. Within a job,
  * appends follow strict per-job seq order. The reducer's canonical order IS
  * this file order (a pure fold over the stream); replay determinism is
  * byte-level (re-reading the file re-yields the same fold and the same
  * [[raider.runtime.lineage.model.GraphHash]]), and the hash is additionally
  * INVARIANT under the cross-job interleave itself: node ids are `<job>:n<seq>`
  * and every edge is within-job or via the parent's Delegation node, while the
  * canonical form sorts node lines by `(seq, id)` (GraphHash, LIN1-A-03).
  * Replay discipline: a torn FINAL line is the honest truncated tail
  * ([[raider.runtime.lineage.io.LineageWireReader]]), reduced under LIN5-03
  * partial-graph semantics.
  *
  * == Honesty rules
  *
  *   - A fork observed AFTER the campaign Terminal was exported connects as a
  *     NATIVE-ONLY segment (its files exist and are complete, but nothing is
  *     joined): a post-terminal introduction would be an invented edge or an
  *     invalid stream (`ReduceError.AfterTerminal`), so the joined stream never
  *     contains one.
  *   - A detached child (no Terminal before the run ends) leaves a segment
  *     without a Terminal in the joined stream; the strict reduction refuses
  *     it typed (`ReduceError.MissingTerminal`) and the LIN5-03 partial entry
  *     ([[raider.runtime.lineage.reduce.LineageReducer.reducePartialWire]])
  *     keeps the completed nodes. A child failure is a typed `Terminal`
  *     envelope in the child's segment (and a `Delegation` node in the unified
  *     tree) — never swallowed.
  *   - Diagnostics-class envelopes dropped INSIDE the hub never consume a seq
  *     (ADR-024 §2.3), so per-job export seqs stay dense for children too; the
  *     per-child drop-count marker the root gets (`DiagnosticsCoalesced`) is
  *     not synthesized for children (documented limitation — the hub counter
  *     is run-global, not per-job).
  *
  * The exporter never touches the run's exit code or the loop: file
  * environment failures surface as defects exactly like the underlying
  * [[EventsJsonlWriter]] contract (an unwritable artifact environment fails
  * the run), and everything else is a pure UIO flow.
  */
final class MultiJobExport private (
    hub: EventHub,
    runWriter: EventsJsonlWriter,
    workDir: Path,
    joinLock: Semaphore,
    wire: Ref[Vector[LineageEnvelope]],
    segmentsRef: Ref[Vector[MultiJobExport.Segment]],
    campaignRef: Ref[Option[JobId]],
    rootTerminalExported: Ref[Boolean],
    invocationCounter: Ref[Long],
    campaignLines: Ref[Long],
    forkOwners: Ref[Map[String, JobId]]
):

  private val captures =
    new ConcurrentHashMap[String, MultiJobExport.ChildCapture]

  // Export-canonical per-job seq counters — file order, guarded by joinLock.
  private val fileSeq =
    scala.collection.mutable.HashMap.empty[String, Long]

  /** The campaign (root) job id — set by the runner right after the job is
    * started, before any child can exist (children are forked by the campaign's
    * own loop). Required before a segment can connect.
    */
  def campaign(jobId: JobId): UIO[Unit] = campaignRef.set(Some(jobId))

  /** RAI-154 (WIR-02): the per-level fork anchoring registration — declares
    * WHICH job forked `child`. The frozen `Delegated(childJobId, agent)`
    * payload carries no parent id, so the topology source is the WIRING: the
    * site that builds a delegation-capable loop for `forkingJob` registers
    * every child that loop may fork, BEFORE the child's fork record can be
    * delivered (the same trust level that publishes the fork record itself). A
    * registration is a declaration of an existing fact, never an edge
    * invention: the anchored record still carries only the frozen payload's
    * verbatim `childJobId`/`agent`.
    *
    * Unregistered children anchor at the campaign (root) stream exactly as
    * before — the single-level export stays byte-identical (MJE-01 pin).
    */
  def anchorForkUnder(child: JobId, forkingJob: JobId): UIO[Unit] =
    forkOwners.update(_.updated(child.value, forkingJob))

  /** The transition-listener side: subscribe to every job the moment it is
    * RUNNING, before its fiber is forked. Compose this BEFORE the status ledger
    * in the manager's single `onTransition` hook, so the subscription is
    * registered before the job's first envelope is published (no loss window,
    * no watermark skip).
    */
  def capture: OnTransition = (jobId, _, to) =>
    if to == JobStatus.Running then register(jobId) else ZIO.unit

  private def register(jobId: JobId): UIO[Unit] =
    hub.subscribe(jobId).flatMap { case (snapshot, stream) =>
      for
        pending <- Ref.make(Vector.empty[EventEnvelope])
        // `.interruptible` is MANDATORY here: this fiber is forked from inside
        // the JobManager's transition hook, which runs in an
        // uninterruptibleMask (the registration section) — a bare forkDaemon
        // would give the capture fiber a non-interruptible start status and
        // `close` could never settle it (the same F-.5 discipline as
        // JobManager/EventHub).
        fiber <- EventExport
          .inOrder(snapshot.lastSeq)(stream)
          .foreach(e => deliver(jobId, pending, e))
          .interruptible
          .forkDaemon
        _ <- ZIO.succeed(
          captures.put(jobId.value, MultiJobExport.ChildCapture(fiber, pending))
        )
      yield ()
    }

  /** One captured envelope of one job: the introduction connects the segment;
    * before it, envelopes are held; after it, they are joined (and natively
    * written); the Terminal finishes the segment.
    */
  private def deliver(
      jobId: JobId,
      pending: Ref[Vector[EventEnvelope]],
      e: EventEnvelope
  ): UIO[Unit] =
    e.payload match
      case EventPayload.Delegated(child, agent) if child == jobId =>
        connect(jobId, pending, e, agent)
      case _ =>
        isConnected(jobId).flatMap {
          case false => pending.update(_ :+ e)
          case true =>
            e.payload match
              case _: EventPayload.Terminal =>
                joinedAppend(jobId, e) *> finishSegment(jobId)
              // A `Delegated` record on any stream other than its own child's
              // (or a second one after connect): the introduction is
              // canonically represented once, in the parent's stream — this
              // copy stays native-only and never joins.
              case _: EventPayload.Delegated =>
                segmentOf(jobId).flatMap {
                  case Some(seg) => seg.appendNative(e).unit
                  case None      => ZIO.unit
                }
              case _ => joinedAppend(jobId, e)
        }

  /** Connects a captured job as a child segment: invocation id, native segment
    * artifacts, the canonical fork record, and the v2 copies of the held
    * prefix. Runs under the export lock with the file appends, so the joined
    * file order places the fork record BEFORE every joined envelope of the
    * child.
    *
    * RAI-154 (WIR-02) per-level anchoring: the canonical fork record is
    * anchored in the FORKING job's stream — the child registered for this child
    * via [[anchorForkUnder]] when one exists, else the campaign (root) stream,
    * which keeps every unregistered (single-level) introduction byte-identical
    * to the pre-RAI-154 layout (MJE-01 pin). A registration naming a job that
    * has not connected is a loud defect, never a silent wrong-shape export (the
    * per-level wiring discipline: the forking job's own introduction connects
    * before it forks). Post-terminal honesty applies PER LEVEL: a fork observed
    * after the anchoring job's Terminal was exported connects as a NATIVE-ONLY
    * segment (nothing joins — an interposed introduction would be
    * `ReduceError.AfterTerminal` or an invented edge).
    */
  private def connect(
      jobId: JobId,
      pending: Ref[Vector[EventEnvelope]],
      forkEnv: EventEnvelope,
      agent: String
  ): UIO[Unit] =
    isConnected(jobId).flatMap { already =>
      if already then ZIO.unit // a repeated introduction joins nothing
      else
        joinLock.withPermit {
          for
            held <- pending.getAndSet(Vector.empty)
            n <- invocationCounter.updateAndGet(_ + 1L)
            inv = s"inv-$n"
            dir = workDir
              .resolve("jobs")
              .resolve(jobId.value)
              .toAbsolutePath
            native <- EventsJsonlWriter.open(dir.resolve("events.jsonl"))
            segComplete <- Ref.make(false)
            rootDone <- rootTerminalExported.get
            campaignJob <- campaignRef.get
            // RAI-154: the registered FORKING job (the campaign registration
            // is the absence of one — the single-level default).
            owner <- forkOwners.get.map(
              _.get(jobId.value).filterNot(cid => campaignJob.contains(cid))
            )
            ownerSeg <- owner match
              case None => ZIO.none
              case Some(cid) =>
                segmentOf(cid).flatMap {
                  case Some(seg) => ZIO.some(seg)
                  case None =>
                    ZIO.die(
                      new IllegalStateException(
                        s"MultiJobExport: per-level fork anchor for " +
                          s"${jobId.value} names ${cid.value}, which has not " +
                          "connected (wiring discipline: the forking job's " +
                          "own introduction must connect before it forks)"
                      )
                    )
                }
            ownerComplete <- ownerSeg match
              case Some(seg) => seg.isComplete
              case None      => ZIO.succeed(false)
            seg = MultiJobExport.Segment(
              jobId,
              inv,
              dir,
              dir.resolve("events.jsonl"),
              native,
              joinedToRun = !rootDone && !ownerComplete,
              segComplete
            )
            _ <- segmentsRef.update(_ :+ seg)
            joined <-
              if !seg.joinedToRun then
                ZIO.succeed(Vector.empty[LineageEnvelope])
              else
                ownerSeg.map(_.childJobId).orElse(campaignJob) match
                  case None =>
                    // Unreachable by contract: the runner sets the campaign
                    // job before its loop can fork anything. Loud, never a
                    // silent wrong-shape export.
                    ZIO.die(
                      new IllegalStateException(
                        "MultiJobExport: campaign job id not set before a fork connected"
                      )
                    )
                  case Some(parent) =>
                    // The canonical fork record in the FORKING job's stream:
                    // the frozen v1 Delegated payload, export seq, the fork's
                    // own timestamp. Nothing is invented: childJobId and agent
                    // are verbatim from the child's fork record.
                    val forkRec = LineageEnvelope(
                      LineageWire.V1,
                      nextSeq(parent.value),
                      parent,
                      forkEnv.rootId,
                      forkEnv.sessionId,
                      forkEnv.atMs,
                      LineagePayload.Frozen(
                        EventPayload.Delegated(jobId, agent)
                      ),
                      None
                    )
                    val copies = held.map(e => joinedCopy(e, inv))
                    ZIO.succeed(forkRec +: copies)
            _ <- ZIO.foreachDiscard(joined)(runWriter.appendLineage)
            _ <- wire.update(_ ++ joined)
            // The native segment: verbatim hub envelopes (hub seqs), including
            // the child's own fork-record copy — the provenance-preserved
            // original the joined stream points back to.
            _ <- ZIO.foreachDiscard(held :+ forkEnv)(e => native.append(e).unit)
          yield ()
        }
    }

  /** Joins one connected child envelope: v2 copy with `invocationId` into the
    * run stream, verbatim v1 into the native segment — one export-lock section,
    * so the fold buffer and the file never diverge.
    */
  private def joinedAppend(jobId: JobId, e: EventEnvelope): UIO[Unit] =
    joinLock.withPermit {
      segmentOf(jobId).flatMap {
        case None =>
          ZIO.die(
            new IllegalStateException(
              s"MultiJobExport: joined append without a segment: ${jobId.value}"
            )
          )
        case Some(seg) =>
          if !seg.joinedToRun then seg.appendNative(e).unit
          else
            val line = joinedCopy(e, seg.invocationId)
            runWriter.appendLineage(line) *> seg
              .appendNative(e)
              .unit *> wire.update(_ :+ line)
      }
    }

  /** The parent-side (campaign) export: every campaign envelope is appended
    * verbatim (rebuilt at its export seq); the campaign's Terminal additionally
    * carries the run_provenance record BEFORE it when segments were joined.
    */
  def exportParent(env: EventEnvelope): UIO[Unit] =
    joinLock.withPermit {
      val campaignEnvelope = env.payload match
        case _: EventPayload.Terminal => true
        case _                        => false
      for
        campaignJob <- campaignRef.get
        isCampaign = campaignJob.contains(env.jobId)
        extra <-
          if isCampaign && campaignEnvelope then
            // The campaign is over: no later connect may join (post-terminal
            // forks connect native-only).
            rootTerminalExported.set(true) *> segmentsRef.get.flatMap { segs =>
              if segs.isEmpty then ZIO.succeed(Vector.empty[LineageEnvelope])
              else
                campaignJob match
                  case Some(parent) =>
                    val prov = LineageEnvelope(
                      LineageWire.V2,
                      nextSeq(parent.value),
                      parent,
                      env.rootId,
                      env.sessionId,
                      env.atMs,
                      LineagePayload.RunProvenance(None, artifactsOf(segs)),
                      None
                    )
                    ZIO.succeed(Vector(prov))
                  case None => ZIO.succeed(Vector.empty[LineageEnvelope])
            }
          else ZIO.succeed(Vector.empty[LineageEnvelope])
        all = extra :+ exported(env)
        _ <- ZIO.foreachDiscard(all)(runWriter.appendLineage)
        _ <- wire.update(_ ++ all)
        // The campaign watermark counts CAMPAIGN hub envelopes only (see
        // [[campaignWritten]]).
        _ <-
          if campaignJob.contains(env.jobId) then campaignLines.update(_ + 1L)
          else ZIO.unit
      yield ()
    }

  /** Quiesces the export: interrupts every capture fiber and closes every
    * native segment writer. Idempotent; the fold buffer stays readable.
    */
  def close: UIO[Unit] =
    ZIO.succeed(captures.values.asScala.toVector).flatMap { all =>
      ZIO.foreachDiscard(all)(c => c.fiber.interrupt.unit) *>
        segmentsRef.get.flatMap(segs => ZIO.foreachDiscard(segs)(_.closeNative))
    }

  /** The exported stream so far — the exact fold input that mirrors the file.
    */
  def exportedWire: UIO[Vector[LineageEnvelope]] = wire.get

  /** The number of CAMPAIGN envelopes exported so far (one per campaign hub
    * envelope; the interposed fork/provenance records do not count). This is
    * the watermark a settled-run wait compares against the campaign job's hub
    * `lastSeq` — the raw line count of the sink is NOT usable once child
    * segments share the file (it would satisfy before the campaign's own tail
    * landed and cut the Terminal off).
    */
  def campaignWritten: UIO[Long] = campaignLines.get

  /** Bounded best-effort wait until every CONNECTED segment reached its
    * Terminal (the same "close the tiny settle-to-export window" discipline as
    * [[EventExport.awaitCaughtUp]]): a child that already settled must have its
    * tail joined before the run's reduction reads the stream, or an honest
    * complete child would export as a torn one. Never a gate: a detached child
    * simply lets the budget expire. Call BEFORE [[close]].
    */
  def drain(budgetMs: Long = 2000L): UIO[Unit] =
    pollUntil(
      segmentsRef.get.flatMap(segs =>
        ZIO.foreach(segs)(_.isComplete).map(_.forall(identity))
      ),
      budgetMs
    )

  /** The joined segments in invocation order (child id, invocation id, native
    * artifact locations).
    */
  def segmentsView: UIO[Vector[MultiJobExport.SegmentView]] =
    segmentsRef.get.map(
      _.map(s =>
        MultiJobExport.SegmentView(
          s.childJobId,
          s.invocationId,
          s.dir,
          s.nativePath
        )
      )
    )

  /** childJobId -> invocationId for the joined segments (the lineage-data v2
    * `delegationData.invocationId` surface).
    */
  def invocations: UIO[Map[String, String]] =
    segmentsView.map(_.map(s => s.childJobId.value -> s.invocationId).toMap)

  // -- internals --------------------------------------------------------------

  private def segmentOf(jobId: JobId): UIO[Option[MultiJobExport.Segment]] =
    segmentsRef.get.map(_.find(_.childJobId == jobId))

  private def isConnected(jobId: JobId): UIO[Boolean] =
    segmentOf(jobId).map(_.isDefined)

  private def finishSegment(jobId: JobId): UIO[Unit] =
    segmentOf(jobId).flatMap {
      case Some(seg) => seg.markComplete
      case None      => ZIO.unit
    }

  /** Bounded wall-clock poll (TestClock-safe, same discipline as
    * [[EventExport.awaitCaughtUp]]).
    */
  private def pollUntil(cond: UIO[Boolean], budgetMs: Long): UIO[Unit] =
    val deadline = System.currentTimeMillis() + budgetMs
    def loop: UIO[Unit] =
      cond.flatMap { ok =>
        if ok || System.currentTimeMillis() >= deadline then ZIO.unit
        else
          ZIO.succeed {
            java.util.concurrent.TimeUnit.MILLISECONDS.sleep(2L)
            ()
          } *> loop
      }
    loop

  /** The export-canonical per-job seq: dense 1,2,3,… in file order. MUST be
    * called under the export lock.
    */
  private def nextSeq(job: String): Long =
    val n = fileSeq.getOrElse(job, 0L) + 1L
    fileSeq(job) = n
    n

  /** The v2 joined copy of a child envelope: verbatim fields, export seq, the
    * segment's invocationId. MUST be called under the export lock.
    */
  private def joinedCopy(e: EventEnvelope, inv: String): LineageEnvelope =
    LineageEnvelope(
      LineageWire.V2,
      nextSeq(e.jobId.value),
      e.jobId,
      e.rootId,
      e.sessionId,
      e.atMs,
      LineagePayload.Frozen(e.payload),
      Some(inv)
    )

  /** The v1 campaign line: verbatim fields, export seq (equal to the hub seq
    * whenever no record was interposed, so a delegation-free run stays
    * byte-identical to the pre-existing v1 layout). MUST be called under the
    * export lock.
    */
  private def exported(env: EventEnvelope): LineageEnvelope =
    LineageEnvelope(
      env.schemaVersion,
      nextSeq(env.jobId.value),
      env.jobId,
      env.rootId,
      env.sessionId,
      env.atMs,
      LineagePayload.Frozen(env.payload),
      None
    )

  private def artifactsOf(
      segs: Vector[MultiJobExport.Segment]
  ): Vector[ArtifactPointer] =
    segs.flatMap { s =>
      Vector(
        ArtifactPointer("work_dir", s.dir.toString),
        ArtifactPointer("events", s.nativePath.toString)
      )
    }

end MultiJobExport

object MultiJobExport:

  /** A connected child segment: the correlation key plus the REAL per-job
    * native artifacts the provenance record points at.
    */
  private final class Segment(
      val childJobId: JobId,
      val invocationId: String,
      val dir: Path,
      val nativePath: Path,
      native: EventsJsonlWriter,
      val joinedToRun: Boolean,
      complete: Ref[Boolean]
  ):
    def appendNative(e: EventEnvelope): UIO[Long] = native.append(e)
    def closeNative: UIO[Unit] = native.close
    def markComplete: UIO[Unit] = complete.set(true)
    def isComplete: UIO[Boolean] = complete.get

  /** Read-only segment view for the runner/artifacts. */
  final case class SegmentView(
      childJobId: JobId,
      invocationId: String,
      dir: Path,
      nativePath: Path
  )

  private final case class ChildCapture(
      fiber: Fiber.Runtime[Nothing, Unit],
      pending: Ref[Vector[EventEnvelope]]
  )

  /** Builds the exporter over the run's hub and events sink. */
  def make(
      hub: EventHub,
      runWriter: EventsJsonlWriter,
      workDir: Path
  ): UIO[MultiJobExport] =
    for
      lock <- Semaphore.make(1L)
      wireRef <- Ref.make(Vector.empty[LineageEnvelope])
      segsRef <- Ref.make(Vector.empty[Segment])
      campaignRef <- Ref.make(Option.empty[JobId])
      rootDoneRef <- Ref.make(false)
      counter <- Ref.make(0L)
      campaignCounter <- Ref.make(0L)
      ownersRef <- Ref.make(Map.empty[String, JobId])
    yield new MultiJobExport(
      hub,
      runWriter,
      workDir,
      lock,
      wireRef,
      segsRef,
      campaignRef,
      rootDoneRef,
      counter,
      campaignCounter,
      ownersRef
    )

end MultiJobExport
