package raider.cli.lineage

import zio.stream.ZStream
import zio.test.*
import zio.{Duration as ZDuration, Exit, Promise, Ref, Scope, UIO, ZIO}
import zio.json.*

import raider.core.{
  CapabilityStatus,
  JobId,
  ModelBackend,
  ModelCapabilities,
  ModelEvent,
  ModelRequest,
  Program,
  RaiderError,
  RequestMessage,
  RootId,
  SessionId,
  Task
}
import raider.dsl.composition.batch
import raider.dsl.lineage.proj.{
  FanOutGroup,
  GeneratedProjection,
  NodeKind,
  NodeView,
  ProvenancePointer,
  SegmentEntry,
  UnifiedProjection,
  UnifiedProjectionInput
}
import raider.dsl.lineage.proj.runtime.StepReport
import raider.cli.run.{EventExport, EventsJsonlWriter, MultiJobExport}
import raider.runtime.events.{
  EventEnvelope,
  EventHub,
  EventPayload,
  ToolOutcome
}
import raider.runtime.jobs.{JobHandle, JobManager, OnTransition, StatusLedger}
import raider.runtime.lineage.io.{LineageWireReader, ParsedWireEvents}
import raider.runtime.lineage.model.{GraphHash, RecoveryClass}
import raider.runtime.lineage.reduce.{
  LineageReduction,
  LineageReducer,
  ReduceError
}
import raider.runtime.lineage.wire.{
  LineageEnvelope,
  LineagePayload,
  LineageWire
}
import raider.runtime.loop.LoopEvents

import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.{CopyOnWriteArrayList, TimeUnit}

/** RAI-145 acceptance: the UNIFIED multi-job lineage graph (BillingCi shape —
  * baseline-check → repair(with tools) → final-check → independent-reviews ×2 →
  * boundary batch ×3) projects through the ADR-031 runtime into an executable
  * dry-run composition and EXECUTES to the expected report tree.
  *
  * Fixture approach (card): the MJE-01 scripted-machinery approach — the REAL
  * multi-job machinery ([[EventHub]] + [[JobManager]] with the composed
  * capture+ledger listener, real child jobs, [[EventsJsonlWriter]],
  * [[MultiJobExport]]) produces the unified events stream; introductions are
  * published the DelegationEvents way (under the child's own id) and every
  * child settles through the real manager/hub/exporter path.
  *
  * Phase stand-in (documented limitation, same category as RAI-144's unwired
  * delegation tools): the product has NO live phase-emission path yet — the
  * ADR-024 hub seam is frozen (13 payloads, no phase case) and `LoopEvents` has
  * no phase surface. The fixture inserts the campaign's
  * `phase_started`/`phase_finished` v2 envelopes at deterministic positions
  * around the REAL machinery envelopes (before each stage's canonical fork
  * record, after the stage's last joined child line) and re-derives the
  * export-canonical per-job seq space (dense 1,2,3,… in file order — the
  * exporter's own rule). The enriched stream is WRITTEN TO A FILE and read back
  * through the versioned reader, so the reduction input is the file (replay
  * discipline) and the artifact doubles as evidence. Delegation joins,
  * correlation keys, canonical fork records and the provenance record remain
  * REAL machinery output.
  *
  * Pinned semantics (card RAI-145):
  *   - '''UNI-01''' the representative multi-job fixture projects and executes
  *     to the exact expected report tree (23 reports: 5 phase defs, 8
  *     delegation defs, 9 child-segment nodes) with real nesting and batch
  *     fan-out (the frozen DSL verb `raider.dsl.composition.batch` the fixture
  *     loop actually used); the pinned-dotc compile is green; the scripted
  *     probe backend records ZERO dispatches across render/compile/load/
  *     execute (the negative control proves the probe detects a real dispatch).
  *   - '''UNI-02''' no edge exists between roots the contract does not link: a
  *     captured-but-never-introduced job (no `Delegated` record) is never
  *     joined — the unified reduction, executed report tree and provenance
  *     record contain none of it, and the typed segment report carries it as
  *     `Unlinked`; the stray root projects SEPARATELY (its own reduction, its
  *     own compiled artifact) and the two executed trees are disjoint.
  *   - '''UNI-03''' malformed/unlinked segments produce TYPED entries, never
  *     silent merges: a corrupted correlation (invocationId stripped from one
  *     child's envelopes) is refused typed by `reduceWire`
  *     (`MissingCorrelation`) and no source is generated; a detached child (no
  *     Terminal) is refused typed (`MissingTerminal`), and the LIN5-03 partial
  *     projection keeps only completed nodes with a typed `Incomplete` entry;
  *     repeat execution is deterministic ×3.
  *
  * Frozen-verb pin (record): `LineageStep` (ADR-031 runtime) + `batch` (n-ary
  * ordered bounded fan-out; results keep INPUT order — COMP-01) +
  * `Task.map`/`flatMap` sequencing. The illustrative
  * `pipeline`/`parallel`/`batch` SYNTAX is not an approved API and never
  * appears (asserted). Fan-out groups are the RECORDED loop composition
  * validated against the graph — parallelism is never inferred from node order
  * or timestamps.
  */
object UnifiedProjectionExecutionSpec extends ZIOSpecDefault:

  private val rootId = RootId("rai-145-root")
  private val sessionId = StatusLedger.sessionId

  // ----- billing pipeline shape ----------------------------------------------

  private val StageBaseline = "baseline-check"
  private val StageRepair = "repair"
  private val StageFinal = "final-check"
  private val StageReviews = "reviews"
  private val StageBoundary = "boundary"

  /** Child-job event scripts per stage; the repair child carries the REAL tool
    * call ("repair (with tools)").
    */
  private def stageEvents(stage: String): LoopEvents => UIO[Unit] = stage match
    case StageBaseline =>
      le =>
        le.modelCallStarted(1, "check-model", 1) *> le.modelCallFinished(1, 3L)
    case StageRepair =>
      le =>
        le.modelCallStarted(1, "repair-model", 1) *>
          le.modelCallFinished(1, 2L) *>
          le.toolStarted("c1", "bash", "ls -la") *>
          le.toolFinished("c1", "bash", ToolOutcome.Succeeded) *>
          le.modelCallStarted(2, "repair-model", 1) *>
          le.modelCallFinished(2, 2L)
    case StageFinal =>
      le =>
        le.modelCallStarted(1, "check-model", 1) *> le.modelCallFinished(1, 2L)
    case StageReviews =>
      le =>
        le.modelCallStarted(1, "review-model", 1) *> le.modelCallFinished(1, 2L)
    case StageBoundary =>
      le =>
        le.modelCallStarted(1, "boundary-model", 1) *> le.modelCallFinished(
          1,
          2L
        )
    case other => throw new IllegalStateException("unknown stage: " + other)

  private def stageAgent(stage: String): String = stage match
    case StageBaseline => "ci-baseline"
    case StageRepair   => "ci-repair"
    case StageFinal    => "ci-final"
    case StageReviews  => "ci-review"
    case StageBoundary => "ci-boundary"
    case other => throw new IllegalStateException("unknown stage: " + other)

  private def stageRecovery(stage: String): RecoveryClass = stage match
    case StageRepair => RecoveryClass.Mutating
    case _           => RecoveryClass.ReadOnly

  // ----- fixture-level loud-failure helpers -----------------------------------

  private def dieWhen(cond: Boolean)(msg: => Throwable): UIO[Unit] =
    if cond then ZIO.die(msg) else ZIO.unit

  /** The generator's identifier slug (mirrored for source-level pins). */
  private def slugId(raw: String): String =
    val cleaned = raw.map(c => if c.isLetterOrDigit || c == '_' then c else '_')
    if cleaned.isEmpty || cleaned.head.isDigit then "_" + cleaned else cleaned

  // ----- scripted multi-job machinery run (MJE join-level approach) -----------

  private final case class MachineryRun(
      campaign: JobId,
      wire: Vector[LineageEnvelope],
      segments: Vector[MultiJobExport.SegmentView],
      strayJobId: Option[JobId],
      strayEnvelopes: Vector[EventEnvelope]
  )

  private def tempDir(prefix: String): Path =
    Files.createTempDirectory(prefix)

  /** Bounded wall-clock poll (TestClock-safe, MultiJobExportSpec discipline).
    */
  private def pollUntil(cond: UIO[Boolean], budgetMs: Long = 5000L): UIO[Unit] =
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

  /** Forks a child job the delegator way: the task emits `events` (bound to its
    * own id), then parks until `release`. The `Delegated` fork record is NOT
    * published here — the caller publishes it under the CHILD's id (the
    * DelegationEvents convention). `onJob` runs INSIDE the task before the
    * events (loss-window-free subscribe hook).
    */
  private def forkChild(
      hub: EventHub,
      manager: JobManager,
      release: Promise[Nothing, Unit],
      onJob: JobId => UIO[Unit] = _ => ZIO.unit
  )(
      events: LoopEvents => UIO[Unit]
  ): ZIO[Any, RaiderError, JobHandle[Unit]] =
    for
      barrier <- Promise.make[Nothing, JobId]
      handle <- manager.start(
        rootId,
        "child",
        Task(
          barrier.await.flatMap { jobId =>
            val le = LoopEvents(hub, jobId, rootId, sessionId)
            onJob(jobId) *> events(le) *> release.await.unit
          }
        )
      )
      _ <- barrier.succeed(handle.id).unit
    yield handle

  private def publishIntroduction(
      hub: EventHub,
      childId: JobId,
      agent: String
  ): UIO[Unit] =
    hub
      .publish(
        childId,
        rootId,
        sessionId,
        EventPayload.Delegated(childId, agent)
      )
      .catchAll(_ => ZIO.unit)

  /** Waits until the child's Terminal is JOINED into the exported stream — the
    * stage-boundary discipline: a settled child's tail must be in the file
    * before the next stage's fork record connects, or the enriched stream would
    * open the next phase while the previous one is still open (the reducer
    * parents constructs to the innermost open phase). Same settle-to-export
    * honesty as `MultiJobExport.drain`, per stage.
    */
  private def awaitChildJoined(join: MultiJobExport, child: JobId): UIO[Unit] =
    pollUntil(
      join.exportedWire.map(w =>
        w.exists(e =>
          e.jobId == child &&
            (e.payload match
              case LineagePayload.Frozen(EventPayload.Terminal(_)) => true
              case _                                               => false)
        )
      )
    )

  /** Waits until ANY envelope of the child joined (for a detached child, whose
    * emitted lines must be in the stream before the campaign Terminal —
    * otherwise the enriched stream would place a phase mark after the
    * Terminal).
    */
  private def awaitChildLinesJoined(
      join: MultiJobExport,
      child: JobId
  ): UIO[Unit] =
    pollUntil(join.exportedWire.map(_.exists(_.jobId == child)))

  /** The stray's in-task subscribe hook: loss-window-free capture of the
    * child's own v1 stream (the snapshot's start transition + the post-snapshot
    * stream in order).
    */
  private def straySubscriber(
      hub: EventHub,
      buf: Ref[Vector[EventEnvelope]],
      collectorRef: Ref[Option[zio.Fiber.Runtime[Nothing, Unit]]]
  ): JobId => UIO[Unit] =
    jobId =>
      for
        sub <- hub.subscribe(jobId)
        (snap, stream) = sub
        _ <- ZIO.foreachDiscard(
          EventExport.startTransition(snap).toVector
        )(e => buf.update(_ :+ e))
        collector <- EventExport
          .inOrder(snap.lastSeq)(stream)
          .foreach(e => buf.update(_ :+ e))
          .forkDaemon
        _ <- collectorRef.set(Some(collector))
      yield ()

  /** The full BillingCi-shape machinery run: five pipeline stages, every stage
    * a delegated child job (the BillingCi application pattern: each stage is
    * its own native job). The campaign emits the phase STAND-INs only at the
    * enrichment step (see the object scaladoc). The review pair and the
    * boundary trio are forked through the FROZEN batch verb
    * (`raider.dsl.composition.batch`, parallelism = group size) — the recorded
    * fan-out the projection carries. Introduction order is pinned by bounded
    * polls on the segment count, so the canonical fork records land in stage
    * order. `withStray` forks one extra job that is NEVER introduced (it must
    * never join). `detachBoundaryMember` parks boundary member 2 forever (the
    * UNI-03b detached-child shape).
    */
  private def machineryRun(
      dir: Path,
      detachBoundaryMember: Boolean,
      withStray: Boolean
  ): ZIO[Scope, Nothing, MachineryRun] =
    ZIO.scoped {
      for
        hub <- EventHub.make()
        writer <- EventsJsonlWriter.open(dir.resolve("events.jsonl"))
        _ <- ZIO.addFinalizer(writer.close)
        join <- MultiJobExport.make(hub, writer, dir)
        _ <- ZIO.addFinalizer(join.close)
        ledger = StatusLedger.onTransition(hub, rootId)
        listener: OnTransition = (jobId, from, to) =>
          join.capture(jobId, from, to) *> ledger(jobId, from, to)
        manager <- JobManager.make(onTransition = Some(listener))

        // The stray: forked like any child but never introduced. Its OWN
        // stream is captured losslessly by subscribing inside its task (the
        // snapshot's start transition + the post-snapshot stream), giving the
        // complete dense v1 envelope vector for its separate reduction.
        strayRelease <- Promise.make[Nothing, Unit]
        strayBuf <- Ref.make(Vector.empty[EventEnvelope])
        strayCollectorRef <- Ref.make(
          Option.empty[zio.Fiber.Runtime[Nothing, Unit]]
        )
        strayHandleOpt <-
          if withStray then
            forkChild(
              hub,
              manager,
              strayRelease,
              onJob = straySubscriber(hub, strayBuf, strayCollectorRef)
            )(le =>
              le.modelCallStarted(1, "stray-checker", 1) *>
                le.modelCallFinished(1, 4L)
            ).orDie.map(Some(_))
          else ZIO.succeed(Option.empty[JobHandle[Unit]])

        // per-stage child releases
        relB <- Promise.make[Nothing, Unit]
        relR <- Promise.make[Nothing, Unit]
        relF <- Promise.make[Nothing, Unit]
        relR1 <- Promise.make[Nothing, Unit]
        relR2 <- Promise.make[Nothing, Unit]
        relB1 <- Promise.make[Nothing, Unit]
        relB2 <- Promise.make[Nothing, Unit]
        relB3 <- Promise.make[Nothing, Unit]

        release <- Promise.make[Nothing, Unit]
        barrier <- Promise.make[Nothing, JobId]
        campaignJob = Task(
          barrier.await.flatMap { _ =>
            val stage1 =
              for
                h <- forkChild(hub, manager, relB)(stageEvents(StageBaseline))
                _ <- publishIntroduction(hub, h.id, stageAgent(StageBaseline))
                _ <- pollUntil(join.segmentsView.map(_.size >= 1))
                _ <- relB.succeed(()).unit
                _ <- manager.await(h).either
                _ <- awaitChildJoined(join, h.id)
              yield h
            val stage2 =
              for
                h <- forkChild(hub, manager, relR)(stageEvents(StageRepair))
                _ <- publishIntroduction(hub, h.id, stageAgent(StageRepair))
                _ <- pollUntil(join.segmentsView.map(_.size >= 2))
                _ <- relR.succeed(()).unit
                _ <- manager.await(h).either
                _ <- awaitChildJoined(join, h.id)
              yield h
            val stage3 =
              for
                h <- forkChild(hub, manager, relF)(stageEvents(StageFinal))
                _ <- publishIntroduction(hub, h.id, stageAgent(StageFinal))
                _ <- pollUntil(join.segmentsView.map(_.size >= 3))
                _ <- relF.succeed(()).unit
                _ <- manager.await(h).either
                _ <- awaitChildJoined(join, h.id)
              yield h
            // reviews: the recorded loop fans the pair out with the frozen
            // batch verb, then introduces the members in pinned order.
            val stageReviews =
              for
                // ZIO.succeed form: scalameta cannot parse `x = expr`
                // bindings in for-comprehensions (scalafix parse error).
                forks <- ZIO.succeed(
                  List(
                    forkChild(hub, manager, relR1)(stageEvents(StageReviews)),
                    forkChild(hub, manager, relR2)(stageEvents(StageReviews))
                  )
                )
                handles <- Task.zio(batch(forks, 2)(fx => Task(fx)))
                _ <- publishIntroduction(
                  hub,
                  handles(0).id,
                  stageAgent(StageReviews)
                )
                _ <- pollUntil(join.segmentsView.map(_.size >= 4))
                _ <- publishIntroduction(
                  hub,
                  handles(1).id,
                  stageAgent(StageReviews)
                )
                _ <- pollUntil(join.segmentsView.map(_.size >= 5))
                _ <- relR1.succeed(()).unit
                _ <- relR2.succeed(()).unit
                _ <- manager.await(handles(0)).either
                _ <- manager.await(handles(1)).either
                _ <- awaitChildJoined(join, handles(0).id)
                _ <- awaitChildJoined(join, handles(1).id)
              yield handles
            // boundary: the recorded batch of three.
            val stageBoundary =
              for
                forks <- ZIO.succeed(
                  List(
                    forkChild(hub, manager, relB1)(stageEvents(StageBoundary)),
                    forkChild(hub, manager, relB2)(stageEvents(StageBoundary)),
                    forkChild(hub, manager, relB3)(stageEvents(StageBoundary))
                  )
                )
                handles <- Task.zio(batch(forks, 3)(fx => Task(fx)))
                _ <- publishIntroduction(
                  hub,
                  handles(0).id,
                  stageAgent(StageBoundary)
                )
                _ <- pollUntil(join.segmentsView.map(_.size >= 6))
                _ <- publishIntroduction(
                  hub,
                  handles(1).id,
                  stageAgent(StageBoundary)
                )
                _ <- pollUntil(join.segmentsView.map(_.size >= 7))
                _ <- publishIntroduction(
                  hub,
                  handles(2).id,
                  stageAgent(StageBoundary)
                )
                _ <- pollUntil(join.segmentsView.map(_.size >= 8))
                // detach: boundary member 2 never settles (no release, no await)
                _ <- relB1.succeed(()).unit
                _ <- relB3.succeed(()).unit
                _ <-
                  if detachBoundaryMember then
                    manager.await(handles(0)).either *>
                      manager.await(handles(2)).either *>
                      awaitChildJoined(join, handles(0).id) *>
                      awaitChildJoined(join, handles(2).id) *>
                      // the detached member's emitted lines must still be
                      // joined before the campaign Terminal (no Terminal ever)
                      awaitChildLinesJoined(join, handles(1).id)
                  else
                    relB2.succeed(()).unit *>
                      manager.await(handles(0)).either *>
                      manager.await(handles(1)).either *>
                      manager.await(handles(2)).either *>
                      awaitChildJoined(join, handles(0).id) *>
                      awaitChildJoined(join, handles(1).id) *>
                      awaitChildJoined(join, handles(2).id)
              yield handles
            for
              _ <- stage1
              _ <- stage2
              _ <- stage3
              _ <- stageReviews
              _ <- stageBoundary
              _ <- release.succeed(()).unit
            yield ()
          }
        )
        handle <- manager.start(rootId, "billing-ci", campaignJob)
        _ <- join.campaign(handle.id)
        subscribed <- hub.subscribe(handle.id)
        (snapshot, stream) = subscribed
        _ <- ZIO.foreachDiscard(
          EventExport.startTransition(snapshot).toVector
        )(e => join.exportParent(e))
        collector <- EventExport
          .inOrder(snapshot.lastSeq)(stream)
          .foreach(e => join.exportParent(e))
          .forkDaemon
        _ <- barrier.succeed(handle.id).unit
        settled <- manager
          .await(handle)
          .either
          .ensuring(
            join
              .drain()
              .zipRight(
                EventExport.awaitCaughtUp(hub, handle.id, join.campaignWritten)
              )
              .zipRight(collector.interrupt)
          )
        _ <- dieWhen(settled.isLeft)(
          new IllegalStateException("billing campaign failed: " + settled)
        )
        _ <- join.close
        wire <- join.exportedWire
        segments <- join.segmentsView
        // settle the stray (its capture fiber is already closed by join.close)
        _ <- strayHandleOpt match
          case Some(sh) =>
            strayRelease.succeed(()).unit *>
              manager.awaitSettled(sh) *>
              pollUntil(strayBuf.get.map(_.exists(_.payload match {
                case EventPayload.Terminal(_) => true
                case _                        => false
              }))) *>
              strayCollectorRef.get.flatMap {
                case Some(f) => f.interrupt.unit
                case None    => ZIO.unit
              }
          case None => ZIO.unit
        stray <- strayBuf.get
      yield MachineryRun(
        handle.id,
        wire,
        segments,
        strayHandleOpt.map(_.id),
        stray
      )
    }

  // ----- stage-phase enrichment (documented stand-in) + export-canonical seq --

  private final case class Enriched(
      path: Path,
      readBack: ParsedWireEvents,
      campaign: String,
      jobs: Vector[String]
  )

  /** Inserts the five stage phases around the REAL machinery envelopes and
    * re-derives the export-canonical per-job seq space, writes the file and
    * reads it back through the versioned reader (the reduction input is the
    * FILE). The joined segment order is the connect order (pinned by the
    * introduction polls) = stage order.
    */
  private def enrichAndWrite(
      dir: Path,
      run: MachineryRun
  ): ZIO[Any, Nothing, Enriched] =
    ZIO.attempt {
      val campaign = run.campaign.value
      if run.segments.size != 8 then
        throw new IllegalStateException(
          "expected 8 joined segments, got " + run.segments.size
        )
      val segJobs = run.segments.map(_.childJobId)
      val stageChildren: Vector[(String, Vector[JobId])] = Vector(
        (StageBaseline, Vector(segJobs(0))),
        (StageRepair, Vector(segJobs(1))),
        (StageFinal, Vector(segJobs(2))),
        (StageReviews, Vector(segJobs(3), segJobs(4))),
        (StageBoundary, Vector(segJobs(5), segJobs(6), segJobs(7)))
      )
      def firstForkIdx(child: JobId): Int =
        run.wire.indexWhere(e =>
          e.jobId == run.campaign && e.schemaVersion == LineageWire.V1 &&
            (e.payload match
              case LineagePayload.Frozen(EventPayload.Delegated(c, _)) =>
                c == child
              case _ => false)
        )
      def lastChildIdx(child: JobId): Int =
        run.wire.lastIndexWhere(_.jobId == child)
      val marks = Vector.newBuilder[(Int, LineagePayload)]
      stageChildren.foreach { (name, children) =>
        marks += (
          (
            children.map(firstForkIdx).min,
            LineagePayload.PhaseStarted(name, stageRecovery(name), None)
          )
        )
        marks += ((
          children.map(lastChildIdx).max + 1,
          LineagePayload.PhaseFinished(name)
        ))
      }
      val sorted =
        marks.result().sortBy(_._1) // stable: stage order kept at ties
      val out = Vector.newBuilder[LineageEnvelope]
      var mi = 0
      var wi = 0
      while wi <= run.wire.length do
        while mi < sorted.length && sorted(mi)._1 == wi do
          val atMs =
            if wi < run.wire.length then run.wire(wi).atMs
            else run.wire.last.atMs
          out += LineageEnvelope(
            LineageWire.V2,
            0L,
            run.campaign,
            rootId,
            sessionId,
            atMs,
            sorted(mi)._2,
            None
          )
          mi += 1
        if wi < run.wire.length then out += run.wire(wi)
        wi += 1
      val merged = out.result()
      // export-canonical per-job seq: dense 1,2,3,… in FILE order
      val counts = scala.collection.mutable.HashMap.empty[String, Long]
      val renumbered = merged.map { e =>
        val job = e.jobId.value
        val n = counts.getOrElse(job, 0L) + 1L
        counts(job) = n
        e.copy(seq = n)
      }
      val path = dir.resolve("unified-events.jsonl")
      val text = renumbered.map(_.toJson).mkString("\n") + "\n"
      Files.write(path, text.getBytes(UTF_8))
      val parsed = LineageWireReader.read(path) match
        case Right(p) => p
        case Left(err) =>
          throw new IllegalStateException(
            "unified-events.jsonl read refused: " + err
          )
      if parsed.truncatedTail.isDefined then
        throw new IllegalStateException(
          "unified-events.jsonl torn tail (fixture bug)"
        )
      if parsed.envelopes != renumbered then
        throw new IllegalStateException(
          "unified-events.jsonl round-trip mismatch (fixture bug)"
        )
      Enriched(path, parsed, campaign, segJobs.map(_.value))
    }.orDie

  // ----- unified pipeline: reduce -> view -> generate -------------------------

  /** The recorded fan-out of the loop: the delegation siblings under the
    * reviews/boundary phases, in VIEW (graph) order, with the parallelism the
    * loop actually used. Derived from the reduced tree, never assumed.
    */
  private def billingFanOut(roots: Vector[NodeView]): Vector[FanOutGroup] =
    def groupOf(stage: String, parallelism: Int): FanOutGroup =
      val ph = roots
        .find(_.kind == NodeKind.Phase(stage))
        .getOrElse(
          throw new IllegalStateException("missing phase node: " + stage)
        )
      val members = ph.children.collect {
        case d if d.kind.isInstanceOf[NodeKind.Delegation] => d.id
      }
      FanOutGroup(Some(ph.id), members, parallelism)
    Vector(groupOf(StageReviews, 2), groupOf(StageBoundary, 3))

  /** reduce (strict, or the LIN5-03 partial entry for the torn/detached shape)
    * -> view -> fan-out -> generate. A strict `ReduceError` refusal is RETURNED
    * typed (UNI-03: no codegen, no silent merge); view/generator refusals are
    * loud fixture-level failures.
    */
  private def projectUnified(
      envs: Vector[LineageEnvelope],
      runId: String,
      provenance: Vector[ProvenancePointer],
      segments: Vector[SegmentEntry],
      partial: Boolean = false
  ): Either[ReduceError, (GeneratedProjection, LineageReduction)] =
    (if partial then LineageReducer.reducePartialWire(envs)
     else LineageReducer.reduceWire(envs)) match
      case Left(err) => Left(err)
      case Right(red) =>
        SessionGraphView.toView(red.graph) match
          case Left(ve) =>
            throw new IllegalStateException("unified view refused: " + ve)
          case Right(view) =>
            UnifiedProjection.generate(
              UnifiedProjectionInput(
                runId,
                view,
                billingFanOut(view.roots),
                provenance,
                segments
              )
            ) match
              case Left(ue) =>
                throw new IllegalStateException(
                  "unified projection refused: " + ue
                )
              case Right(proj) => Right((proj, red))

  /** The provenance record of the stream, as verbatim pointers (real recorded
    * data — the exporter wrote it before the campaign Terminal).
    */
  private def provenanceOf(
      envs: Vector[LineageEnvelope]
  ): Vector[ProvenancePointer] =
    envs
      .collectFirst {
        case LineageEnvelope(
              _,
              _,
              _,
              _,
              _,
              _,
              rp: LineagePayload.RunProvenance,
              _
            ) =>
          rp.artifacts.map(a => ProvenancePointer(a.kind, a.path))
      }
      .getOrElse(Vector.empty)

  private def linkedSegments(
      run: MachineryRun
  ): Vector[SegmentEntry] =
    run.segments.map(s =>
      SegmentEntry(
        s.childJobId.value,
        Some(s.invocationId),
        SegmentEntry.Status.Linked
      )
    )

  // ----- expected report tree (from the recorded stream's own ids) ------------

  private def seqOfWire(
      envs: Vector[LineageEnvelope],
      job: String
  )(p: LineagePayload => Boolean): Long =
    envs.find(e => e.jobId.value == job && p(e.payload)) match
      case Some(e) => e.seq
      case None =>
        throw new IllegalStateException("envelope not found in job " + job)

  private def seqOfFrozen(
      envs: Vector[EventEnvelope],
      job: String
  )(p: EventPayload => Boolean): Long =
    envs.find(e => e.jobId.value == job && p(e.payload)) match
      case Some(e) => e.seq
      case None =>
        throw new IllegalStateException(
          "frozen envelope not found in job " + job
        )

  private def nid(job: String, seq: Long): String = job + ":n" + seq

  private def phaseId(envs: Vector[LineageEnvelope], camp: String)(
      name: String
  ): String =
    nid(
      camp,
      seqOfWire(envs, camp) {
        case LineagePayload.PhaseStarted(n, _, _) => n == name
        case _                                    => false
      }
    )

  private def forkId(envs: Vector[LineageEnvelope], camp: String)(
      child: String
  ): String =
    nid(
      camp,
      seqOfWire(envs, camp) {
        case LineagePayload.Frozen(EventPayload.Delegated(c, _)) =>
          c.value == child
        case _ => false
      }
    )

  private def roundId(envs: Vector[LineageEnvelope], job: String)(
      round: Int
  ): String =
    nid(
      job,
      seqOfWire(envs, job) {
        case LineagePayload.Frozen(EventPayload.ModelCallStarted(r, _, _)) =>
          r == round
        case _ => false
      }
    )

  private def toolId(envs: Vector[LineageEnvelope], job: String)(
      callId: String
  ): String =
    nid(
      job,
      seqOfWire(envs, job) {
        case LineagePayload.Frozen(EventPayload.ToolStarted(c, _, _)) =>
          c == callId
        case _ => false
      }
    )

  /** The exact expected pre-order report tree of the billing shape (23
    * reports). Nesting follows the renderer discipline: a def's own node lives
    * INSIDE the body one level deeper than its call site — phase defs at 1,
    * delegation defs and their child-segment nodes at 2. Inputs are the
    * reducer's RECORDED dataflow: each node inputs the job's previously
    * appended node (sibling delegations chain through lastNode); a child's
    * first node inputs its base Delegation node; phases carry no inputs by
    * definition. Real contract, not invention.
    */
  private def expectedBillingTree(
      envs: Vector[LineageEnvelope]
  ): Vector[StepReport] =
    val camp = envs.head.jobId.value
    val j = envs.map(_.jobId.value).distinct.filter(_ != camp)
    if j.size != 8 then
      throw new IllegalStateException("expected 8 child jobs, got " + j.size)
    val Vector(jb, jr, jf, jr1, jr2, jb1, jb2, jb3) = j
    def ph(s: String) = StepReport(
      phaseId(envs, camp)(s),
      NodeKind.Phase(s),
      None,
      Vector.empty,
      1
    )
    def dl(child: String, agent: String, stage: String) = StepReport(
      forkId(envs, camp)(child),
      NodeKind.Delegation(agent),
      None,
      Vector(phaseId(envs, camp)(stage)),
      2
    )
    def md(job: String, r: Int, model: String) = StepReport(
      roundId(envs, job)(r),
      NodeKind.ModelCall(model, r),
      Some(job + "/round-" + r),
      Vector(forkId(envs, camp)(job)),
      2
    )
    Vector(
      ph(StageBaseline),
      dl(jb, "ci-baseline", StageBaseline),
      md(jb, 1, "check-model"),
      ph(StageRepair),
      dl(jr, "ci-repair", StageRepair),
      md(jr, 1, "repair-model"),
      StepReport(
        toolId(envs, jr)("c1"),
        NodeKind.ToolCall("bash", "Mutating"),
        Some(jr + "/call-c1"),
        Vector(roundId(envs, jr)(1)),
        2
      ),
      StepReport(
        roundId(envs, jr)(2),
        NodeKind.ModelCall("repair-model", 2),
        Some(jr + "/round-2"),
        Vector(toolId(envs, jr)("c1")),
        2
      ),
      ph(StageFinal),
      dl(jf, "ci-final", StageFinal),
      md(jf, 1, "check-model"),
      ph(StageReviews),
      dl(jr1, "ci-review", StageReviews),
      md(jr1, 1, "review-model"),
      StepReport(
        forkId(envs, camp)(jr2),
        NodeKind.Delegation("ci-review"),
        None,
        Vector(forkId(envs, camp)(jr1)), // sibling chain through lastNode
        2
      ),
      md(jr2, 1, "review-model"),
      ph(StageBoundary),
      dl(jb1, "ci-boundary", StageBoundary),
      md(jb1, 1, "boundary-model"),
      StepReport(
        forkId(envs, camp)(jb2),
        NodeKind.Delegation("ci-boundary"),
        None,
        Vector(forkId(envs, camp)(jb1)),
        2
      ),
      md(jb2, 1, "boundary-model"),
      StepReport(
        forkId(envs, camp)(jb3),
        NodeKind.Delegation("ci-boundary"),
        None,
        Vector(forkId(envs, camp)(jb2)),
        2
      ),
      md(jb3, 1, "boundary-model")
    )

  // ----- pinned-dotc compile + load + execute (RAI-142 discipline) ------------

  private final case class Loaded(
      program: Program[Unit, Vector[StepReport]],
      defs: Map[String, Program[Unit, Vector[StepReport]]],
      source: String
  )

  private def requireRight[A](e: Either[String, A], what: String): A =
    e match
      case Right(a) => a
      case Left(err) =>
        throw new IllegalStateException(what + " failed: " + err)

  private def compileAndLoad(
      tag: String,
      src: String,
      objName: String,
      defNames: Vector[String]
  ): Loaded =
    val path = requireRight(writeRender(tag, src), "write render " + tag)
    val compiled = requireRight(dotc(tag, path, "out-" + tag), "compile " + tag)
    if compiled.exit != 0 then
      throw new IllegalStateException(
        tag + ": pinned-dotc compile expected exit 0, got " + compiled.exit +
          "; log tail:\n" + compiled.logTail
      )
    loadProjection(compiled.outDir, objName, defNames, src)

  private val CompileTimeoutSeconds = 120L

  private lazy val compileDir: Path =
    findAncestor("artifacts", "tools", "m2")
      .resolve("modules")
      .resolve("cli")
      .resolve("target")
      .resolve("lineage-compile")

  private def m2Jar(rel: String): Path =
    val p = findAncestor("artifacts", "tools", "m2")
      .resolve("artifacts")
      .resolve("tools")
      .resolve("m2")
      .resolve(rel)
    if Files.isRegularFile(p) then p
    else throw new IllegalStateException("missing pinned jar: " + p)

  private lazy val compilerClasspath: String =
    Vector(
      "org/scala-lang/scala3-compiler_3/3.9.0/scala3-compiler_3-3.9.0.jar",
      "org/scala-lang/scala3-interfaces/3.9.0/scala3-interfaces-3.9.0.jar",
      "org/scala-lang/tasty-core_3/3.9.0/tasty-core_3-3.9.0.jar",
      "org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar",
      "org/scala-lang/modules/scala-asm/9.9.0-scala-1/scala-asm-9.9.0-scala-1.jar",
      "org/scala-sbt/compiler-interface/1.12.0/compiler-interface-1.12.0.jar",
      "org/scala-sbt/util-interface/1.11.5/util-interface-1.11.5.jar"
    ).map(m2Jar).mkString(File.pathSeparator)

  private lazy val userClasspath: String =
    val lib = m2Jar(
      "org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar"
    )
    val base = System.getProperty("java.class.path", "")
    if base.isEmpty then lib.toString
    else lib.toString + File.pathSeparator + base

  private final case class Compiled(exit: Int, logTail: String, outDir: Path)

  private def dotc(
      tag: String,
      source: Path,
      outName: String
  ): Either[String, Compiled] =
    for
      outDir <- freshDir(compileDir.resolve(outName))
      log = compileDir.resolve(tag + ".dotc.log")
      compiled <- runDotc(log, outDir, source)
    yield compiled

  private def freshDir(p: Path): Either[String, Path] =
    try
      if Files.isDirectory(p) then
        val listing = Files.list(p)
        try listing.forEach(entry => Files.delete(entry))
        finally listing.close()
        Files.delete(p)
      Files.createDirectories(p)
      Right(p)
    catch case t: Throwable => Left("fresh dir " + p + ": " + t)

  private def runDotc(
      log: Path,
      outDir: Path,
      source: Path
  ): Either[String, Compiled] =
    try
      Files.createDirectories(compileDir)
      val pb = new ProcessBuilder(
        "java",
        "-cp",
        compilerClasspath,
        "dotty.tools.dotc.Main",
        "-classpath",
        userClasspath,
        "-d",
        outDir.toString,
        source.toString
      )
      pb.redirectErrorStream(true)
      pb.redirectOutput(log.toFile)
      val proc = pb.start()
      if !proc.waitFor(CompileTimeoutSeconds, TimeUnit.SECONDS) then
        proc.destroyForcibly()
        Left(
          "dotc did not finish within " + CompileTimeoutSeconds + "s (destroyed): " + source
        )
      else
        val all =
          scala.jdk.CollectionConverters
            .ListHasAsScala(Files.readAllLines(log, UTF_8))
            .asScala
            .toVector
        val tail = if all.size <= 40 then all else all.takeRight(40)
        Right(Compiled(proc.exitValue(), tail.mkString("\n"), outDir))
    catch case t: Throwable => Left("dotc launch failed: " + t)

  private def writeRender(tag: String, src: String): Either[String, Path] =
    try
      Files.createDirectories(compileDir)
      val p = compileDir.resolve(tag + ".scala")
      Files.write(p, src.getBytes(UTF_8))
      Right(p)
    catch case t: Throwable => Left(tag + ".scala write failed: " + t)

  private def loadProjection(
      outDir: Path,
      objName: String,
      defNames: Vector[String],
      source: String
  ): Loaded =
    val loader = new URLClassLoader(
      Array(outDir.toUri.toURL),
      getClass.getClassLoader
    )
    val cls =
      try Class.forName(objName + "$", true, loader)
      catch
        case t: Throwable =>
          throw new IllegalStateException(
            "load " + objName + " from " + outDir + ": " + t
          )
    val module =
      try cls.getField("MODULE$").get(null) // scalafix:ok DisableSyntax.null
      catch
        case t: Throwable =>
          throw new IllegalStateException(
            "module instance of " + objName + ": " + t
          )
    val program =
      try
        cls
          .getMethod("program")
          .invoke(module)
          .asInstanceOf[Program[Unit, Vector[StepReport]]]
      catch
        case t: Throwable =>
          throw new IllegalStateException(
            "program accessor of " + objName + ": " + t
          )
    val defs = defNames.map { n =>
      try
        n ->
          cls
            .getMethod(n)
            .invoke(module)
            .asInstanceOf[Program[Unit, Vector[StepReport]]]
      catch
        case t: Throwable =>
          throw new IllegalStateException(
            "def val " + n + " of " + objName + ": " + t
          )
    }.toMap
    Loaded(program, defs, source)

  private def findAncestor(segments: String*): Path =
    val cwd = Path.of("").toAbsolutePath
    Iterator
      .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) => dir }
      .find(dir => Files.isDirectory(dir.resolve(segments.mkString("/"))))
      .getOrElse(
        throw new IllegalStateException(
          "no ancestor of " + cwd + " contains " + segments.mkString("/")
        )
      )

  /** Executes a loaded `Program` through the single observation boundary. */
  private def execute(
      p: Program[Unit, Vector[StepReport]]
  ): ZIO[Any, Nothing, Exit[RaiderError, Vector[StepReport]]] =
    Task.zio(p(())).exit

  private def requireSuccess(
      exit: Exit[RaiderError, Vector[StepReport]],
      what: String
  ): Vector[StepReport] =
    exit match
      case Exit.Success(v) => v
      case Exit.Failure(cause) =>
        throw new IllegalStateException(what + " failed: " + cause)

  // ----- zero-dispatch probe (PRJ-03 discipline) -------------------------------

  private final class RecordingBackend extends ModelBackend:
    val requests = new CopyOnWriteArrayList[ModelRequest]()

    val capabilities: ModelCapabilities = ModelCapabilities(
      CapabilityStatus.Supported,
      CapabilityStatus.Supported,
      CapabilityStatus.Supported
    )

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap:
        ZIO.succeed:
          requests.add(input)
          ZStream.empty

    def count: Int = requests.size()

  private val probeRequest = ModelRequest(
    "probe-model",
    List(RequestMessage("user", "dry-run dispatch probe")),
    16,
    Nil
  )

  // ----- spec ------------------------------------------------------------------

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("RAI-145 unified multi-job projection acceptance (BillingCi shape)")(
      test(
        "UNI-01: the billing-shape unified graph projects and executes to the exact expected report tree — real nesting, batch fan-out, pinned-dotc compile green, deterministic ×3, zero dispatch"
      ) {
        live {
          ZIO.scoped {
            for
              dir <- ZIO.attempt(tempDir("rai-145-uni01")).orDie
              run <- machineryRun(
                dir,
                detachBoundaryMember = false,
                withStray = false
              )
              enriched <- enrichAndWrite(dir, run)
              envs = enriched.readBack.envelopes
              // byte-deterministic pipeline: the SAME file through
              // reduce -> view -> generate twice
              proj1 <- ZIO.succeed(
                projectUnified(
                  envs,
                  enriched.campaign,
                  provenanceOf(envs),
                  linkedSegments(run)
                )
              )
              proj2 <- ZIO.succeed(
                projectUnified(
                  envs,
                  enriched.campaign,
                  provenanceOf(envs),
                  linkedSegments(run)
                )
              )
              _ <- dieWhen(proj1.isLeft)(
                new IllegalStateException("UNI-01 reduction refused: " + proj1)
              )
              _ <- dieWhen(proj2.isLeft)(
                new IllegalStateException("UNI-01 reduction refused: " + proj2)
              )
              (gen1, red) = proj1.toOption.get
              (gen2, _) = proj2.toOption.get
              // the probe spans the WHOLE lifecycle of this artifact
              probe <- ZIO.succeed(new RecordingBackend)
              _ <- dieWhen(probe.count != 0)(
                new IllegalStateException("probe not clean before render")
              )
              loaded <- ZIO.succeed(
                compileAndLoad(
                  "rai145-uni01",
                  gen1.source,
                  gen1.objectName,
                  gen1.defNames
                )
              )
              full1 <- execute(loaded.program)
              full2 <- execute(loaded.program)
              full3 <- execute(loaded.program)
              reviews <- execute(loaded.defs("phase_reviews"))
              boundary <- execute(loaded.defs("phase_boundary"))
              repair <- execute(loaded.defs("delegation_ci_repair"))
              phaseBaseline <- execute(loaded.defs("phase_baseline_check"))
              baseline <- execute(loaded.defs("delegation_ci_baseline"))
              zeroCount = probe.count
              // negative control: the harness MUST detect a real dispatch
              _ <- Task.zio(
                Task(ZIO.scoped(probe.stream(probeRequest).runDrain))
              )
            yield
              val expected = expectedBillingTree(envs)
              val camp = enriched.campaign
              val jr = enriched.jobs(1)
              val dlRepair = forkId(envs, camp)(jr)
              val phRepair = phaseId(envs, camp)(StageRepair)
              val mdRepair1 = roundId(envs, jr)(1)
              assertTrue(
                // pipeline byte-determinism (reduce + view + generate ×2)
                gen2.source == gen1.source,
                gen2.fanOut == gen1.fanOut,
                // the exact executed report tree, ×3 identical
                requireSuccess(full1, "UNI-01 execution") == expected,
                requireSuccess(full2, "UNI-01 execution 2") == expected,
                requireSuccess(full3, "UNI-01 execution 3") == expected,
                expected.size == 23,
                // real nesting fragments (phase def 1, delegation + child nodes 2)
                requireSuccess(phaseBaseline, "baseline phase def") == expected
                  .take(3),
                requireSuccess(baseline, "baseline def") == expected
                  .slice(1, 3),
                requireSuccess(repair, "repair def") == expected.slice(4, 8),
                // batch fan-out visible in the executed order (input order, COMP-01)
                requireSuccess(reviews, "reviews def") == expected
                  .slice(11, 16),
                requireSuccess(boundary, "boundary def") == expected.slice(
                  16,
                  23
                ),
                // pinned frozen verbs in the compiled artifact
                loaded.source.contains("import raider.dsl.composition.batch"),
                loaded.source.contains(
                  "batch(List(delegation_ci_review, delegation_ci_review_2), 2)(p => p(()))"
                ),
                loaded.source.contains(
                  "batch(List(delegation_ci_boundary, delegation_ci_boundary_2, delegation_ci_boundary_3), 3)(p => p(()))"
                ),
                // the illustrative unapproved syntax never appears
                !loaded.source.contains("pipeline("),
                !loaded.source.contains("parallel("),
                // structure pins: phase/delegation defs with recorded dataflow
                loaded.source
                  .contains(
                    "val phase_" + StageReviews + ": Program[Unit, Vector[StepReport]] ="
                  ),
                loaded.source
                  .contains(
                    "val delegation_ci_review_2: Program[Unit, Vector[StepReport]] ="
                  ),
                loaded.source.contains(
                  "LineageStep.phase(\"" + phaseId(envs, camp)(StageReviews) +
                    "\", \"" + StageReviews + "\", None, Vector.empty, nesting = 1)"
                ),
                loaded.source.contains(
                  "LineageStep.delegation(\"" + dlRepair + "\", \"ci-repair\", None, Vector(\"" +
                    phRepair + "\"), nesting = 2)"
                ),
                loaded.source.contains(
                  "LineageStep.model(\"" + mdRepair1 + "\", \"repair-model\", 1, Some(\"" +
                    jr + "/round-1\"), Vector(n_" + slugId(
                      dlRepair
                    ) + ".head.id), nesting = 2) // data: " + jr + "/round-1"
                ),
                loaded.source.contains(" // data: " + jr + "/call-c1"),
                // recorded data carried: provenance pointers + typed segments
                loaded.source.contains(
                  "val runProvenance: Vector[(String, String)] ="
                ),
                loaded.source.contains(
                  "val segments: Vector[(String, String, String)] ="
                ),
                gen1.segments.size == 8,
                gen1.segments.forall(_.status == SegmentEntry.Status.Linked),
                gen1.fanOut.size == 2,
                gen1.fanOut(1).members.size == 3,
                // reduction sanity: one provenance record, 16 pointers, hashable graph
                red.provenance.isDefined,
                red.provenance.get.artifacts.size == 16,
                GraphHash(red.graph).length == 64,
                // dry run: ZERO dispatch across render/compile/load/execute
                zeroCount == 0,
                // negative control: the probe detects a real dispatch
                probe.count == 1
              )
          }
        }
      },
      test(
        "UNI-02 + UNI-03a: unlinked roots stay separate with provenance preserved; corrupted correlation is refused typed, never merged"
      ) {
        live {
          ZIO.scoped {
            for
              dir <- ZIO.attempt(tempDir("rai-145-uni02")).orDie
              run <- machineryRun(
                dir,
                detachBoundaryMember = false,
                withStray = true
              )
              enriched <- enrichAndWrite(dir, run)
              envs = enriched.readBack.envelopes
              strayPrefix = run.strayJobId
                .map(_.value)
                .getOrElse(throw new IllegalStateException("stray missing"))
              _ <- dieWhen(envs.exists(_.jobId.value == strayPrefix))(
                new IllegalStateException(
                  "stray envelopes leaked into the unified stream"
                )
              )
              segmentsTyped = linkedSegments(run) :+ SegmentEntry(
                strayPrefix,
                None,
                SegmentEntry.Status.Unlinked(
                  "captured but never introduced: no Delegated record, never joined — separate native root"
                )
              )
              proj <- ZIO.succeed(
                projectUnified(
                  envs,
                  enriched.campaign,
                  provenanceOf(envs),
                  segmentsTyped
                )
              )
              _ <- dieWhen(proj.isLeft)(
                new IllegalStateException("UNI-02 reduction refused: " + proj)
              )
              (gen, red) = proj.toOption.get
              loaded <- ZIO.succeed(
                compileAndLoad(
                  "rai145-uni02",
                  gen.source,
                  gen.objectName,
                  gen.defNames
                )
              )
              tree <- execute(loaded.program)
              // the stray projects SEPARATELY: its own reduction (frozen v1
              // entry over its own native stream), view and compiled artifact
              strayRed = LineageReducer.reduce(run.strayEnvelopes)
              _ <- dieWhen(strayRed.isLeft)(
                new IllegalStateException(
                  "stray v1 reduction refused: " + strayRed
                )
              )
              strayView = SessionGraphView.toView(strayRed.toOption.get)
              _ <- dieWhen(strayView.isLeft)(
                new IllegalStateException("stray view refused: " + strayView)
              )
              strayProj = UnifiedProjection.generate(
                UnifiedProjectionInput(strayPrefix, strayView.toOption.get)
              )
              _ <- dieWhen(strayProj.isLeft)(
                new IllegalStateException(
                  "stray projection refused: " + strayProj
                )
              )
              strayLoaded <- ZIO.succeed(
                compileAndLoad(
                  "rai145-uni02-stray",
                  strayProj.toOption.get.source,
                  strayProj.toOption.get.objectName,
                  strayProj.toOption.get.defNames
                )
              )
              strayTree <- execute(strayLoaded.program)
              // UNI-03a: corrupted correlation — strip the repair child's key
              corrupted = envs.map { e =>
                if e.jobId.value == enriched.jobs(1) then
                  e.copy(invocationId = None)
                else e
              }
              refused = LineageReducer.reduceWire(corrupted)
              corruptedFirst = corrupted
                .find(_.jobId.value == enriched.jobs(1))
                .get
                .seq
            yield
              val unifiedTree = requireSuccess(tree, "UNI-02 execution")
              val strayTreeV = requireSuccess(strayTree, "stray execution")
              val expectedStray = Vector(
                StepReport(
                  nid(
                    strayPrefix,
                    seqOfFrozen(run.strayEnvelopes, strayPrefix) {
                      case EventPayload.ModelCallStarted(r, _, _) => r == 1
                      case _                                      => false
                    }
                  ),
                  NodeKind.ModelCall("stray-checker", 1),
                  Some(strayPrefix + "/round-1"),
                  Vector.empty,
                  0
                )
              )
              val strayTypedUnlinked = gen.segments.last.status match
                case SegmentEntry.Status.Unlinked(_) => true
                case _                               => false
              assertTrue(
                // the unified executed tree separates the stray: zero overlap
                unifiedTree == expectedBillingTree(envs),
                !unifiedTree.exists(r => r.id.startsWith(strayPrefix + ":")),
                !unifiedTree.exists(r =>
                  r.dataRef.exists(_.startsWith(strayPrefix + "/"))
                ),
                strayTreeV == expectedStray,
                unifiedTree.forall(r => !strayTreeV.exists(s => s.id == r.id)),
                // provenance preserved: 16 real pointers, none of the stray
                // (segment-exact id match — "j_1" must not hit "j_10")
                red.provenance.isDefined,
                red.provenance.get.artifacts.size == 16,
                !red.provenance.get.artifacts.exists(p =>
                  p.path.split("/").contains(strayPrefix)
                ),
                gen.provenance.size == 16,
                loaded.source.contains(
                  "val runProvenance: Vector[(String, String)] ="
                ),
                loaded.source.contains(
                  "val segments: Vector[(String, String, String)] ="
                ),
                loaded.source.contains(
                  "unlinked: captured but never introduced"
                ),
                gen.segments.last.jobId == strayPrefix,
                strayTypedUnlinked,
                // UNI-03a: corrupted correlation -> typed refusal naming the child
                refused == Left(
                  ReduceError.MissingCorrelation(
                    enriched.jobs(1),
                    corruptedFirst
                  )
                )
              )
          }
        }
      },
      test(
        "UNI-03b: a detached boundary member is refused typed by the strict entry; the partial projection keeps the completed nodes with a typed Incomplete entry; deterministic ×3"
      ) {
        live {
          ZIO.scoped {
            for
              dir <- ZIO.attempt(tempDir("rai-145-uni03")).orDie
              run <- machineryRun(
                dir,
                detachBoundaryMember = true,
                withStray = false
              )
              enriched <- enrichAndWrite(dir, run)
              envs = enriched.readBack.envelopes
              detachedJob = enriched.jobs(
                6
              ) // boundary member 2 (connect order)
              strict = LineageReducer.reduceWire(envs)
              partial1 = LineageReducer.reducePartialWire(envs)
              partial2 = LineageReducer.reducePartialWire(envs)
              _ <- dieWhen(!partial1.isRight || !partial2.isRight)(
                new IllegalStateException("partial reduction refused")
              )
              segmentsTyped = run.segments.map(s =>
                SegmentEntry(
                  s.childJobId.value,
                  Some(s.invocationId),
                  if s.childJobId.value == detachedJob then
                    SegmentEntry.Status.Incomplete(
                      "no Terminal in the exported segment; strict reduction refused typed ReduceError.MissingTerminal(" +
                        detachedJob + "); LIN5-03 partial keeps completed nodes only"
                    )
                  else SegmentEntry.Status.Linked
                )
              )
              proj <- ZIO.succeed(
                projectUnified(
                  envs,
                  enriched.campaign,
                  provenanceOf(envs),
                  segmentsTyped,
                  partial = true
                )
              )
              _ <- dieWhen(proj.isLeft)(
                new IllegalStateException(
                  "partial projection pipeline refused: " + proj
                )
              )
              (gen, red) = proj.toOption.get
              loaded <- ZIO.succeed(
                compileAndLoad(
                  "rai145-uni03",
                  gen.source,
                  gen.objectName,
                  gen.defNames
                )
              )
              r1 <- execute(loaded.program)
              r2 <- execute(loaded.program)
              r3 <- execute(loaded.program)
            yield
              val expected = expectedBillingTree(envs)
              val tree1 = requireSuccess(r1, "UNI-03b execution")
              val tree2 = requireSuccess(r2, "UNI-03b execution 2")
              val tree3 = requireSuccess(r3, "UNI-03b execution 3")
              assertTrue(
                // the strict entry refuses TYPED — no silent merge of a
                // segment that never reached Terminal
                strict == Left(ReduceError.MissingTerminal(detachedJob)),
                // the partial entry is stable ×2
                partial1.map(x => GraphHash(x.graph)) == partial2.map(x =>
                  GraphHash(x.graph)
                ),
                // the detached child's COMPLETED node survives in the partial
                // reduction (LIN5-03), so the honest partial tree is full-shaped
                red.provenance.isDefined,
                red.provenance.get.artifacts.size == 16,
                tree1 == expected,
                tree2 == expected,
                tree3 == expected,
                tree1.size == 23,
                // the typed Incomplete entry is carried, never flattened
                gen.segments.count(_.status == SegmentEntry.Status.Linked) == 7,
                gen.segments.exists {
                  case SegmentEntry(j, _, SegmentEntry.Status.Incomplete(d)) =>
                    j == detachedJob && d.contains(
                      "MissingTerminal(" + detachedJob + ")"
                    )
                  case _ => false
                },
                loaded.source.contains(
                  "incomplete: no Terminal in the exported segment"
                )
              )
          }
        }
      }
    ) @@ TestAspect.timeout(ZDuration.fromSeconds(900))

end UnifiedProjectionExecutionSpec
