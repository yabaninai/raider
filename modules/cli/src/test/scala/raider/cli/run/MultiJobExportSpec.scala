package raider.cli.run

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.{BudgetLimits, CostEstimate}
import raider.runtime.delegate.{DelegationEvents, DelegationToolset}
import raider.runtime.events.{
  EventEnvelope,
  EventHub,
  EventPayload,
  ToolOutcome
}
import raider.runtime.jobs.{
  JobHandle,
  JobManager,
  JobStatus,
  OnTransition,
  StatusLedger
}
import raider.runtime.lineage.io.{
  LineageEventReader,
  LineageWireReader,
  ParsedWireEvents
}
import raider.runtime.lineage.model.{GraphHash, NodeId, SessionNode}
import raider.runtime.lineage.reduce.{LineageReducer, ReduceError}
import raider.runtime.lineage.wire.{LineageEnvelope, LineagePayload}
import raider.runtime.loop.{AgentLoop, LoopEvents}
import zio.stream.ZStream
import zio.test.*
import zio.{Duration as ZDuration, Promise, Scope, Task, UIO, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** MJE-01/MJE-02/MJE-03 acceptance (card RAI-144, ADR-032 §6): delegate child
  * jobs join the RUN's single events.jsonl with the ADR-032 correlation fields;
  * the versioned reducer builds ONE tree with real parent-child edges (the
  * canonical fork record lives in the parent's stream; no invented edges);
  * every child segment carries pointers to its REAL per-job native artifacts;
  * per-job export seq stays dense; the interleave is deterministic on replay
  * and the graph hash is invariant under the cross-job interleave; a child
  * failure is typed; a torn/detached child tail follows the LIN5-03
  * partial-graph discipline.
  *
  * Two layers:
  *   - the END-TO-END scripted delegation run — the real machinery
  *     ([[EventHub]], [[JobManager]] with the composed capture+ledger listener,
  *     [[DelegationToolset]] forks, [[EventsJsonlWriter]], [[MultiJobExport]])
  *     wired exactly like [[HeadlessRunner.executeAsJob]] wires a
  *     delegation-capable loop;
  *   - JOIN-LEVEL flows over directly forked jobs, for deterministic
  *     interleaving, torn-tail, detached-child and post-terminal shapes.
  */
object MultiJobExportSpec extends ZIOSpecDefault:

  private val rootId = RootId("rai-144-root")
  private val sessionId = StatusLedger.sessionId

  private def tempDir(prefix: String): Path =
    Files.createTempDirectory(prefix)

  /** Content-routed turn backend (RAI-092 STAB-01 discipline, mirrored from
    * AgentLoopDelegationSpec): the route picks the turn from request CONTENT,
    * never from a call index; `Left` fails the stream (typed child failure).
    */
  private final class TurnBackend(
      route: ModelRequest => Either[RaiderError, Vector[ModelEvent]]
  ) extends ModelBackend:

    def capabilities: ModelCapabilities =
      ModelCapabilities(
        CapabilityStatus.Supported,
        CapabilityStatus.Supported,
        CapabilityStatus.Unknown
      )

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap:
        ZIO.succeed:
          route(input) match
            case Right(events) =>
              ZStream(ModelEvent.Started(AttemptId("rai-144-att"))) ++
                ZStream.fromIterable(events)
            case Left(err) => ZStream.fail(err)

  private def childIdOf(req: ModelRequest): String =
    val content =
      req.messages.collect { case RequestMessage("tool", c) => c }.mkString
    val r = """"child_id":"(j_\d+)"""".r
    r.findFirstMatchIn(content).map(_.group(1)).getOrElse("j_missing")

  private def limits: Either[RaiderError, BudgetLimits] =
    BudgetLimits.make(
      maxConcurrentLlm = 1,
      maxConcurrentTools = 2,
      maxAttempts = 64,
      maxChildren = 4
    )

  private def isFrozenTerminal(e: LineageEnvelope): Boolean =
    e.payload match
      case LineagePayload.Frozen(_: EventPayload.Terminal) => true
      case _                                               => false

  private def isFrozenDelegated(e: LineageEnvelope): Boolean =
    e.payload match
      case LineagePayload.Frozen(_: EventPayload.Delegated) => true
      case _                                                => false

  private def isProvenance(e: LineageEnvelope): Boolean =
    e.payload match
      case _: LineagePayload.RunProvenance => true
      case _                               => false

  /** Strict read of the exported file: a fixture-level failure (file unreadable
    * or content-refused) is a loud test error, never a silent pass.
    */
  private def readFileStrict(dir: Path): ParsedWireEvents =
    LineageWireReader.read(dir.resolve("events.jsonl")) match
      case Right(p) => p
      case Left(e) =>
        throw new IllegalStateException(s"events.jsonl read refused: $e")

  private def parsed(dir: Path): ZIO[Any, Nothing, ParsedWireEvents] =
    ZIO.succeed(readFileStrict(dir))

  /** Per-job export seqs must be dense 1,2,3,… (the export-canonical seq
    * space).
    */
  private def densePerJobSeq(envs: Vector[LineageEnvelope]): Boolean =
    envs
      .groupBy(_.jobId.value)
      .forall { case (_, lines) =>
        lines.map(_.seq) == (1L to lines.length.toLong).toVector
      }

  /** Re-derives the unified reduction from the FILE (replay discipline). */
  private def replay(dir: Path): Either[ReduceError, String] =
    LineageReducer
      .reduceWire(readFileStrict(dir).envelopes)
      .map(red => GraphHash(red.graph))

  /** Bounded wall-clock poll (TestClock-safe, same discipline as
    * [[EventExport.awaitCaughtUp]]).
    */
  private def pollUntil(cond: UIO[Boolean], budgetMs: Long = 5000L): UIO[Unit] =
    val deadline = System.currentTimeMillis() + budgetMs
    def loop: UIO[Unit] =
      cond.flatMap { ok =>
        if ok || System.currentTimeMillis() >= deadline then ZIO.unit
        else ZIO.sleep(ZDuration.fromMillis(2L)) *> loop
      }
    loop

  // -- END-TO-END scripted delegation run (the real delegation machinery) ----

  private case class FixtureRun(
      parentJobId: JobId,
      result: Option[Either[RaiderError, String]],
      segments: Vector[MultiJobExport.SegmentView],
      dir: Path
  )

  /** Delegates, awaits the real child id, finishes. `childTurn` is the child's
    * single model turn (or a typed stream failure — the child-failure shape).
    */
  private def delegationRoute(
      childTurn: Either[RaiderError, Vector[ModelEvent]],
      finalText: String
  ): ModelRequest => Either[RaiderError, Vector[ModelEvent]] = {
    val a = AttemptId("rai-144-att")
    val c1 = ToolCallId("call-1")
    val c2 = ToolCallId("call-2")
    val delegate = Vector(
      ModelEvent.ToolCallReady(
        a,
        c1,
        "delegate",
        """{"agent":"scout","input":"find x"}"""
      ),
      ModelEvent.Finished(a, "r")
    )
    val awaitTurn = (req: ModelRequest) =>
      Vector(
        ModelEvent.ToolCallReady(
          a,
          c2,
          "await_agent",
          s"""{"child_id":"${childIdOf(req)}"}"""
        ),
        ModelEvent.Finished(a, "r")
      )
    val finalTurn = Vector(
      ModelEvent.TextDelta(a, finalText),
      ModelEvent.Finished(a, "stop")
    )
    req =>
      val single = req.messages.size == 1
      val head = req.messages.headOption.map(_.content).getOrElse("")
      val awaitDone =
        req.messages.exists(m =>
          m.role == "tool" && m.content.contains("call-2")
        )
      if single && head == "u" then Right(delegate)
      else if single && head == "find x" then childTurn
      else if awaitDone then Right(finalTurn)
      else Right(awaitTurn(req))
  }

  private def scriptedDelegationRun(
      dir: Path
  )(
      route: ModelRequest => Either[RaiderError, Vector[ModelEvent]]
  ): ZIO[Any, Nothing, FixtureRun] =
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
        lims <- ZIO.fromEither(limits).orDie
        admission <- Admission.make(Right(lims)).orDie
        backend = new TurnBackend(route)
        loopRoot = AgentLoop.newRoot()
        toolset <- ZIO
          .fromEither(
            DelegationToolset.make(
              manager,
              backend,
              admission,
              lims,
              ToolRegistry.empty,
              Set("scout"),
              loopRoot,
              delegationEvents = Some(DelegationEvents(hub, loopRoot))
            )
          )
          .orDie
        registry = toolset
          .augment(ToolRegistry.empty)
          .fold(e => throw e, identity)
        barrier <- Promise.make[Nothing, JobId]
        job = Task(
          barrier.await.flatMap { jobId =>
            val le = LoopEvents(hub, jobId, loopRoot, StatusLedger.sessionId)
            val loop = AgentLoop(
              backend,
              registry,
              "fixture-parent",
              admission,
              loopRoot,
              CostEstimate.Unknown,
              events = Some(le)
            )
            loop
              .runText(List(RequestMessage("user", "u")), lims, 16)
              .timeoutFail(RaiderError.DeadlineExceeded("fixture timeout"))(
                ZDuration.fromSeconds(30)
              )
          }
        )
        handle <- manager.start(loopRoot, "fixture-campaign", job)
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
        result <- manager
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
        _ <- join.close
        segments <- join.segmentsView
      yield FixtureRun(handle.id, Some(result), segments, dir)
    }

  // -- JOIN-LEVEL flows --------------------------------------------------------

  private case class Exported(
      parentJobId: JobId,
      wire: Vector[LineageEnvelope],
      segments: Vector[MultiJobExport.SegmentView]
  )

  /** Builds the join over a run-local hub/writer/manager with the composed
    * capture+ledger listener (the exact HeadlessRunner composition).
    */
  private def harness(
      dir: Path
  ): ZIO[Scope, Nothing, (EventHub, JobManager, MultiJobExport)] =
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
    yield (hub, manager, join)

  /** Runs the campaign job with the exact runner collector discipline; the body
    * runs concurrently (forks/introduces children, releases them).
    */
  private def driveCampaign(dir: Path)(
      body: (EventHub, JobManager, Promise[Nothing, Unit]) => ZIO[
        Any,
        RaiderError,
        Unit
      ]
  ): ZIO[Scope, Nothing, Exported] =
    for
      (hub, manager, join) <- harness(dir)
      release <- Promise.make[Nothing, Unit]
      _ <- body(hub, manager, release).forkDaemon
      barrier <- Promise.make[Nothing, JobId]
      job = Task(
        barrier.await.flatMap { jobId =>
          val le = LoopEvents(hub, jobId, rootId, sessionId)
          le.modelCallStarted(1, "m", 1) *>
            le.modelCallFinished(1, 5L) *>
            release.await.unit
        }
      )
      handle <- manager.start(rootId, "campaign", job)
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
      _ <- manager.await(handle).either
      _ <- join
        .drain()
        .zipRight(
          EventExport.awaitCaughtUp(hub, handle.id, join.campaignWritten)
        )
      _ <- collector.interrupt
      _ <- join.close
      wire <- join.exportedWire
      segments <- join.segmentsView
    yield Exported(handle.id, wire, segments)

  /** Forks a child job the delegator way: the task emits `events` (bound to its
    * own id), then parks until `release`. The `Delegated` fork record is NOT
    * published here — the caller publishes it under the CHILD's id (the
    * DelegationEvents convention).
    */
  private def forkChild(
      hub: EventHub,
      manager: JobManager,
      release: Promise[Nothing, Unit]
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
            events(le) *> release.await.unit
          }
        )
      )
      _ <- barrier.succeed(handle.id).unit
    yield handle

  private def publishIntroduction(
      hub: EventHub,
      childId: JobId
  ): UIO[Unit] =
    hub
      .publish(
        childId,
        rootId,
        sessionId,
        EventPayload.Delegated(childId, "scout")
      )
      .catchAll(_ => ZIO.unit)

  // -- NESTED-chain flows (RAI-154, WIR-02) -----------------------------------

  private case class NestedExported(
      campaignId: JobId,
      wire: Vector[LineageEnvelope],
      segments: Vector[MultiJobExport.SegmentView]
  )

  /** The exact runner collector discipline over a run-local harness; unlike
    * [[driveCampaign]] the body also receives the join, so the fixture can
    * register per-level fork anchors between introductions.
    */
  private def driveNested(dir: Path)(
      body: (
          EventHub,
          JobManager,
          MultiJobExport,
          Promise[Nothing, Unit]
      ) => ZIO[Any, RaiderError, Unit]
  ): ZIO[Scope, Nothing, NestedExported] =
    for
      (hub, manager, join) <- harness(dir)
      release <- Promise.make[Nothing, Unit]
      _ <- body(hub, manager, join, release).forkDaemon
      barrier <- Promise.make[Nothing, JobId]
      job = Task(
        barrier.await.flatMap { jobId =>
          val le = LoopEvents(hub, jobId, rootId, sessionId)
          le.modelCallStarted(1, "m", 1) *>
            le.modelCallFinished(1, 5L) *>
            release.await.unit
        }
      )
      handle <- manager.start(rootId, "campaign", job)
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
      _ <- manager.await(handle).either
      _ <- join
        .drain()
        .zipRight(
          EventExport.awaitCaughtUp(hub, handle.id, join.campaignWritten)
        )
      _ <- collector.interrupt
      _ <- join.close
      wire <- join.exportedWire
      segments <- join.segmentsView
    yield NestedExported(handle.id, wire, segments)

  /** The CANONICAL fork records of an exported stream: frozen v1
    * `Delegated(child, _)` envelopes carried by a stream OTHER than the child's
    * own (the introductions the joined stream represents once, in the forking
    * job's stream).
    */
  private def canonicalForkRecords(
      envs: Vector[LineageEnvelope]
  ): Vector[(JobId, JobId, LineageEnvelope)] =
    envs.collect {
      case e @ LineageEnvelope(
            1,
            _,
            jid,
            _,
            _,
            _,
            LineagePayload.Frozen(EventPayload.Delegated(c, _)),
            _
          ) if jid != c =>
        (jid, c, e)
    }

  def spec = suite("multi-job lineage export (RAI-144)")(
    test(
      "SW-01: drain settles captured introductions through the hub watermark before close"
    ) {
      live {
        ZIO.scoped {
          for
            dir <- ZIO.attempt(tempDir("rai-157-sw01")).orDie
            (hub, manager, join) <- harness(dir)
            release <- Promise.make[Nothing, Unit]
            child <- forkChild(hub, manager, release)(_ => ZIO.unit)
            // Publish only after the child capture was installed by the
            // Running transition. The hub watermark is authoritative even
            // when its forwarding fiber has not delivered this introduction.
            _ <- publishIntroduction(hub, child.id)
            _ <- pollUntil(
              hub.snapshot(child.id).map(_.exists(_.lastSeq >= 2L)),
              5000L
            )
            _ <- join.drain(2000L)
            segments <- join.segmentsView
            _ <- release.succeed(()).unit
            _ <- manager.awaitSettled(child)
          yield assertTrue(
            segments.exists(_.childJobId == child.id)
          )
        }
      }
    },
    // -- MJE-01 ---------------------------------------------------------------
    test(
      "MJE-01: a scripted delegation run exports ONE events.jsonl; the reducer builds one tree; child provenance pointers resolve"
    ) {
      live {
        ZIO.scoped {
          for
            dir <- ZIO.attempt(tempDir("rai-144-mje01")).orDie
            childAnswer = Right(
              Vector(
                ModelEvent.TextDelta(AttemptId("rai-144-att"), "found x"),
                ModelEvent.Finished(AttemptId("rai-144-att"), "stop")
              )
            )
            run <- scriptedDelegationRun(dir)(
              delegationRoute(childAnswer, "child said: found x")
            )
            p <- parsed(dir)
            envs = p.envelopes
            childLines = envs.filter(_.invocationId.isDefined)
            parentLines = envs.filterNot(_.invocationId.isDefined)
            forkRecords = parentLines.filter(e =>
              e.jobId == run.parentJobId && isFrozenDelegated(
                e
              ) && e.schemaVersion == 1
            )
            provenance = envs.collectFirst {
              case le @ LineageEnvelope(
                    2,
                    _,
                    _,
                    _,
                    _,
                    _,
                    rp: LineagePayload.RunProvenance,
                    _
                  ) =>
                (le, rp)
            }
            childJobId = childLines.headOption
              .map(_.jobId.value)
              .getOrElse("<none>")
            nativeRead = run.segments.headOption.map(s =>
              LineageEventReader.read(s.nativePath)
            )
            reduction = LineageReducer.reduceWire(envs)
            delegationNode = reduction.toOption.flatMap(
              _.graph.nodes.collectFirst { case d: SessionNode.Delegation => d }
            )
            artPaths = provenance.toList.flatMap(_._2.artifacts.map(_.path))
            artifactsExist = artPaths.map(p => Files.exists(Path.of(p)))
          yield assertTrue(
            p.truncatedTail.isEmpty,
            // the parent's stream stays the frozen v1 wire (the ONLY v2 line
            // without correlation is the provenance record); the child's
            // joined lines are the correlated v2 copies
            parentLines.nonEmpty,
            parentLines
              .filterNot(isProvenance)
              .forall(e => e.schemaVersion == 1 && e.invocationId.isEmpty),
            parentLines
              .filter(isProvenance)
              .forall(e => e.schemaVersion == 2 && e.invocationId.isEmpty),
            childLines.nonEmpty && childLines.forall(_.schemaVersion == 2),
            // exactly ONE canonical fork record, in the PARENT's stream
            forkRecords.size == 1,
            // the child's own copy of the fork record never joins
            !childLines.exists(isFrozenDelegated),
            // exactly one provenance record: root run, campaign job, placed
            // immediately before the campaign Terminal
            provenance.isDefined,
            provenance.map(_._1.jobId) == Some(run.parentJobId),
            provenance.map(_._2.parentRunId) == Some(None),
            provenance.map(le => envs.indexOf(le._1)) ==
              Some(
                envs.lastIndexWhere(e =>
                  e.jobId == run.parentJobId && isFrozenTerminal(e)
                ) - 1
              ),
            // the pointers resolve to the REAL per-job artifacts
            artifactsExist.forall(identity),
            artifactsExist.size == 2 * run.segments.size,
            run.segments.forall(s =>
              artPaths.contains(s.dir.toString) && artPaths
                .contains(s.nativePath.toString)
            ),
            run.segments.map(_.invocationId).sorted ==
              childLines.map(_.invocationId.get).distinct.sorted,
            // the child's NATIVE segment is a valid frozen-v1 single-job stream
            nativeRead.fold(false) {
              case Right(pe) =>
                pe.truncatedTail.isEmpty && pe.envelopes.nonEmpty && pe.envelopes
                  .exists(_.payload match {
                    case EventPayload.Delegated(_, _) => true
                    case _                            => false
                  })
              case Left(_) => false
            },
            // ONE tree: the strict versioned reduction succeeds, carries a
            // real Delegation node parented inside the parent's stream, and a
            // stable 64-hex graph hash
            reduction.isRight,
            reduction.map(red => GraphHash(red.graph).length) == Right(64),
            delegationNode.exists(d =>
              d.id.value.startsWith(run.parentJobId.value)
            ),
            run.result.exists {
              case Right(text) => text.contains("child said: found x")
              case Left(e) => throw new IllegalStateException(s"run failed: $e")
            },
            childJobId != "<none>"
          )
        }
      }
    },
    // -- MJE-02 ---------------------------------------------------------------
    test(
      "MJE-02: per-job seq dense; interleave deterministic on replay; graph hash stable and invariant under the cross-job interleave"
    ) {
      live {
        ZIO.scoped {
          for
            dir <- ZIO.attempt(tempDir("rai-144-mje02")).orDie
            exported <- driveCampaign(dir) { (hub, manager, release) =>
              for
                releaseChildren <- Promise.make[Nothing, Unit]
                childA <- forkChild(hub, manager, releaseChildren)(le =>
                  le.modelCallStarted(1, "m", 1) *>
                    le.modelCallFinished(1, 5L)
                )
                childB <- forkChild(hub, manager, releaseChildren)(le =>
                  le.toolStarted("c1", "bash", "ls") *>
                    le.toolFinished("c1", "bash", ToolOutcome.Succeeded)
                )
                _ <- publishIntroduction(hub, childA.id)
                _ <- publishIntroduction(hub, childB.id)
                _ <- releaseChildren.succeed(()).unit
                _ <- manager.await(childA).either
                _ <- manager.await(childB).either
                _ <- release.succeed(()).unit
              yield ()
            }
            p <- parsed(dir)
            envs = p.envelopes
            // A DIFFERENT valid cross-job interleave of the SAME envelopes:
            // only the CHILD lines may permute (their seqs are per-job), and
            // only AFTER the last canonical fork record (each child's lines
            // must stay after its introduction); parent lines, fork records
            // and the provenance record keep their positions.
            forkRecs = envs.collect {
              case e @ LineageEnvelope(
                    1,
                    _,
                    jid,
                    _,
                    _,
                    _,
                    LineagePayload.Frozen(EventPayload.Delegated(c, _)),
                    _
                  )
                  if jid == exported.parentJobId && c != exported.parentJobId =>
                (c, e)
            }
            childJobIds = forkRecs.map(_._1).toSet
            lastForkIdx = envs.lastIndexWhere(e => forkRecs.exists(_._2 == e))
            tail = envs.drop(lastForkIdx + 1)
            tailChild = tail.filter(e => childJobIds.contains(e.jobId))
            tailParent = tail.filterNot(e => childJobIds.contains(e.jobId))
            tailChildReordered =
              tailChild.groupBy(_.jobId).values.toList.reverse.flatten
            it = tailChildReordered.iterator
            newTail = tail.map(e =>
              if childJobIds.contains(e.jobId) then it.next() else e
            )
            reordered = envs.take(lastForkIdx + 1) ++ newTail
            r1 = LineageReducer.reduceWire(envs)
            r2 = LineageReducer.reduceWire(reordered)
            replay1 = replay(dir)
            replay2 = replay(dir)
            replay3 = replay(dir)
          yield assertTrue(
            p.truncatedTail.isEmpty,
            densePerJobSeq(envs),
            forkRecs.size == 2,
            tailChild.map(_.jobId).distinct.size == 2,
            reordered != envs, // the interleave genuinely differs
            r1.isRight,
            r2.isRight,
            r1.map(red => GraphHash(red.graph)) == r2.map(red =>
              GraphHash(red.graph)
            ),
            replay1 == replay2,
            replay2 == replay3,
            replay1 == r1.map(red => GraphHash(red.graph))
          )
        }
      }
    },
    // -- MJE-03 ---------------------------------------------------------------
    test(
      "MJE-03a: a child failure is typed in the unified export — Terminal(Failed) joined with correlation, Delegation node present, no silent swallow"
    ) {
      live {
        ZIO.scoped {
          for
            dir <- ZIO.attempt(tempDir("rai-144-mje03a")).orDie
            run <- scriptedDelegationRun(dir)(
              delegationRoute(
                Left(RaiderError.ToolFailed("child boom")),
                "child failed"
              )
            )
            p <- parsed(dir)
            envs = p.envelopes
            childTerminal = envs.collectFirst {
              case LineageEnvelope(
                    2,
                    _,
                    jid,
                    _,
                    _,
                    _,
                    LineagePayload.Frozen(EventPayload.Terminal(st)),
                    inv
                  ) if jid.value != run.parentJobId.value =>
                (st, inv)
            }
            reduction = LineageReducer.reduceWire(envs)
            hasDelegation = reduction.toOption.exists(
              _.graph.nodes.exists(_.isInstanceOf[SessionNode.Delegation])
            )
          yield assertTrue(
            p.truncatedTail.isEmpty,
            childTerminal.exists(_._1 == JobStatus.Failed),
            childTerminal.flatMap(_._2).isDefined,
            reduction.isRight,
            hasDelegation,
            run.result.exists {
              case Right(text) => text.contains("child failed")
              case Left(e) => throw new IllegalStateException(s"run failed: $e")
            }
          )
        }
      }
    },
    test(
      "MJE-03b: a torn final line is an honest truncated tail; the prefix reduces under LIN5-03 partial semantics"
    ) {
      live {
        ZIO.scoped {
          for
            dir <- ZIO.attempt(tempDir("rai-144-mje03b")).orDie
            exported <- driveCampaign(dir) { (hub, manager, release) =>
              for
                releaseChildren <- Promise.make[Nothing, Unit]
                child <- forkChild(hub, manager, releaseChildren)(le =>
                  le.modelCallStarted(1, "m", 1) *>
                    le.modelCallFinished(1, 5L)
                )
                _ <- publishIntroduction(hub, child.id)
                _ <- releaseChildren.succeed(()).unit
                _ <- manager.await(child).either
                _ <- release.succeed(()).unit
              yield ()
            }
            file = dir.resolve("events.jsonl")
            raw <- ZIO.attempt(Files.readString(file, UTF_8)).orDie
            // tear the FINAL line mid-record (crash mid-write shape)
            _ <- ZIO
              .attempt(
                Files.writeString(file, raw.substring(0, raw.length - 6), UTF_8)
              )
              .orDie
            p <- parsed(dir)
            // Which campaign line is LAST is itself an interleave property
            // (a settled child's Terminal may land after the campaign's):
            // the torn prefix loses exactly the last envelope.
            lastJob = exported.wire.lastOption
              .map(_.jobId.value)
              .getOrElse("<none>")
            strict = LineageReducer.reduceWire(p.envelopes)
            full = LineageReducer.reduceWire(exported.wire)
            partial1 = LineageReducer.reducePartialWire(p.envelopes)
            partial2 = LineageReducer.reducePartialWire(p.envelopes)
          yield assertTrue(
            p.truncatedTail.isDefined,
            lastJob != "<none>",
            strict == Left(ReduceError.MissingTerminal(lastJob)),
            full.isRight,
            partial1.isRight,
            partial2.isRight,
            // every completed construct survived the cut: the torn prefix's
            // partial graph equals the full reduction
            partial1.map(x => GraphHash(x.graph)) == full.map(red =>
              GraphHash(red.graph)
            ),
            partial1.map(x => GraphHash(x.graph)) == partial2.map(x =>
              GraphHash(x.graph)
            )
          )
        }
      }
    },
    test(
      "MJE-03c: a detached child (no Terminal) is refused typed by the strict entry and keeps its completed nodes under the partial entry"
    ) {
      live {
        ZIO.scoped {
          for
            dir <- ZIO.attempt(tempDir("rai-144-mje03c")).orDie
            exported <- driveNested(dir) { (hub, manager, join, release) =>
              for
                releaseChildren <- Promise.make[Nothing, Unit]
                child <- forkChild(hub, manager, releaseChildren)(le =>
                  le.modelCallStarted(1, "m", 1) *>
                    le.modelCallFinished(1, 5L)
                )
                _ <- publishIntroduction(hub, child.id)
                // Determinism pin (MJE-NESTED discipline): the introduction is
                // CONNECTED before the campaign can settle — a mid-campaign
                // introduction must join and strict-refuse MissingTerminal;
                // without this the pin asserts the scheduler, not the exporter.
                _ <- pollUntil(
                  join.segmentsView.map(_.exists(_.childJobId == child.id))
                )
                // the child is NEVER released: the campaign settles first
                _ <- release.succeed(()).unit
              yield ()
            }
            envs = exported.wire
            childId = envs
              .find(_.invocationId.isDefined)
              .map(_.jobId.value)
              .getOrElse("<none>")
            strict = LineageReducer.reduceWire(envs)
            partial = LineageReducer.reducePartialWire(envs)
            childNode = partial.toOption.flatMap(
              _.graph.nodes.find(n => n.id.value.startsWith(s"$childId:"))
            )
          yield assertTrue(
            childId != "<none>",
            strict == Left(ReduceError.MissingTerminal(childId)),
            partial.isRight,
            childNode.isDefined,
            // the retained child node resolves its base edge to the parent's
            // Delegation node — no dangling edges in the partial graph
            childNode.exists(n =>
              n.parent.isDefined && n.parent.get.value
                .startsWith(exported.campaignId.value)
            ),
            partial.map(x => GraphHash(x.graph).length) == Right(64)
          )
        }
      }
    },
    test(
      "MJE honesty: a fork observed after the campaign Terminal connects as a NATIVE-ONLY segment — nothing joins, no invented introduction"
    ) {
      live {
        ZIO.scoped {
          for
            dir <- ZIO.attempt(tempDir("rai-144-postterm")).orDie
            (hub, manager, join) <- harness(dir)
            childRelease <- Promise.make[Nothing, Unit]
            child <- forkChild(hub, manager, childRelease)(le =>
              le.modelCallStarted(1, "m", 1) *>
                le.modelCallFinished(1, 5L)
            )
            barrier <- Promise.make[Nothing, JobId]
            job = Task(
              barrier.await.flatMap { jobId =>
                val le = LoopEvents(hub, jobId, rootId, sessionId)
                le.modelCallStarted(1, "m", 1) *>
                  le.modelCallFinished(1, 5L)
              }
            )
            handle <- manager.start(rootId, "campaign", job)
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
            _ <- manager.await(handle).either
            _ <- join
              .drain()
              .zipRight(
                EventExport.awaitCaughtUp(hub, handle.id, join.campaignWritten)
              )
            _ <- collector.interrupt
            // The campaign Terminal is EXPORTED; only now does the fork
            // record of the still-parked child arrive (the hub accepted it
            // because the CHILD itself is not terminal).
            _ <- publishIntroduction(hub, child.id)
            _ <- pollUntil(join.segmentsView.map(_.nonEmpty))
            _ <- join.close
            wire <- join.exportedWire
            segments <- join.segmentsView
            nativeExists <- segments.headOption match
              case Some(s) => ZIO.succeed(Files.exists(s.nativePath))
              case None    => ZIO.succeed(false)
            nativeLines <- segments.headOption match
              case Some(s) =>
                ZIO
                  .attempt(
                    Files
                      .readAllLines(s.nativePath, UTF_8)
                      .asScala
                      .count(_.nonEmpty)
                  )
                  .orDie
              case None => ZIO.succeed(0)
            reduction = LineageReducer.reduceWire(wire)
            // cleanup: release and settle the parked child
            _ <- childRelease.succeed(()).unit
            _ <- manager.awaitSettled(child)
          yield assertTrue(
            segments.size == 1,
            // nothing of the child joined the run stream
            wire.forall(_.jobId == handle.id),
            !wire.exists(_.invocationId.isDefined),
            // and the segment's native artifacts exist with the held events
            nativeExists,
            nativeLines >= 2,
            // the joined stream is still a VALID campaign (strict reduction)
            reduction.isRight
          )
        }
      }
    },
    // -- RAI-154 / WIR-02: per-level fork anchoring ----------------------------
    test(
      "MJE-NESTED (RAI-154 WIR-02): a root-to-child-to-grandchild chain anchors every fork record in the FORKING job's stream — no root-to-grandchild edge; single-level anchoring unchanged"
    ) {
      live {
        ZIO.scoped {
          for
            dir <- ZIO.attempt(tempDir("rai-154-nested")).orDie
            exported <- driveNested(dir) { (hub, manager, join, release) =>
              for
                child <- forkChild(hub, manager, release)(le =>
                  le.modelCallStarted(1, "m", 1) *>
                    le.modelCallFinished(1, 5L)
                )
                _ <- publishIntroduction(hub, child.id)
                // Wiring discipline the exporter pins: the grandchild is
                // forked only after the child's own introduction CONNECTED
                // (deterministic join order; no introduction reorder).
                _ <- pollUntil(
                  join.segmentsView.map(_.exists(_.childJobId == child.id))
                )
                grand <- forkChild(hub, manager, release)(le =>
                  le.toolStarted("c1", "bash", "ls") *>
                    le.toolFinished("c1", "bash", ToolOutcome.Succeeded)
                )
                // The wiring declares the topology: the CHILD forked the
                // grandchild (the site that builds the child's delegation
                // loop knows the child's job id).
                _ <- join.anchorForkUnder(grand.id, child.id)
                _ <- publishIntroduction(hub, grand.id)
                _ <- pollUntil(
                  join.segmentsView.map(_.exists(_.childJobId == grand.id))
                )
                _ <- release.succeed(()).unit
                _ <- manager.await(child).either
                _ <- manager.await(grand).either
              yield ()
            }
            envs0 = exported.wire
            p <- parsed(dir)
            envs = p.envelopes
            campaignId = exported.campaignId
            childSeg = exported.segments.headOption.getOrElse(
              throw new IllegalStateException("no child segment joined")
            )
            grandSeg = exported.segments
              .lift(1)
              .getOrElse(
                throw new IllegalStateException("no grandchild segment joined")
              )
            forkRecs = canonicalForkRecords(envs)
            campaignRecs = forkRecs.filter(_._1 == campaignId)
            childStreamRecs = forkRecs.filter(_._1 == childSeg.childJobId)
            reduction = LineageReducer.reduceWire(envs)
            delegationNodes = reduction.toOption
              .map(_.graph.nodes.collect { case d: SessionNode.Delegation =>
                d
              })
              .getOrElse(Vector.empty)
            childDelegation = delegationNodes.find(
              _.childJobId == childSeg.childJobId
            )
            grandDelegation = delegationNodes.find(
              _.childJobId == grandSeg.childJobId
            )
            // the grandchild's OWN node (its tool call) resolves its base
            // edge into the grandchild's per-level parent chain
            grandTool = reduction.toOption.flatMap(
              _.graph.nodes.collectFirst { case t: SessionNode.ToolCall => t }
            )
            graphHash = reduction.map(red => GraphHash(red.graph))
          yield assertTrue(
            p.truncatedTail.isEmpty,
            envs == envs0, // the in-memory fold mirrors the file byte-for-byte
            exported.segments.size == 2,
            childSeg.invocationId == "inv-1",
            grandSeg.invocationId == "inv-2",
            // exactly TWO canonical fork records: the child's in the
            // CAMPAIGN stream (single-level anchoring unchanged) and the
            // grandchild's in the CHILD's stream (per-level anchoring)
            forkRecs.size == 2,
            campaignRecs.map(_._2) == Vector(childSeg.childJobId),
            childStreamRecs.map(_._2) == Vector(grandSeg.childJobId),
            // the joined stream strictly reduces with the real per-level
            // edges: campaign -> child -> grandchild, never campaign ->
            // grandchild
            reduction.isRight,
            childDelegation.exists(d =>
              d.id.value.startsWith(campaignId.value)
            ),
            grandDelegation.exists(d =>
              d.id.value.startsWith(childSeg.childJobId.value)
            ),
            // The reducer's parent rule (shipped, single-level parity): a
            // Delegation node parents under the carrying stream's current
            // model round, else its innermost phase, else its base edge. The
            // PER-LEVEL pin is therefore: the grandchild's record is anchored
            // in the CHILD's stream AND its parent edge resolves into the
            // CHILD's stream — never into the campaign's (the wrong
            // root-to-grandchild edge this card fixes).
            grandDelegation
              .flatMap(_.parent)
              .exists(p => p.value.startsWith(childSeg.childJobId.value)),
            grandTool.exists(t =>
              t.parent.isDefined && t.parent.get == grandDelegation
                .map(_.id)
                .getOrElse(NodeId("<none>"))
            ),
            graphHash.map(_.length) == Right(64),
            densePerJobSeq(envs)
          )
        }
      }
    }
  ) @@ TestAspect.timeout(ZDuration.fromSeconds(300))

end MultiJobExportSpec
