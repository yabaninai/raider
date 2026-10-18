package raider.cli.run

import raider.core.{JobId, RootId, Task}
import raider.runtime.events.{EventHub, EventPayload}
import raider.runtime.jobs.{JobManager, OnTransition, StatusLedger}
import raider.runtime.lineage.io.LineageWireReader
import raider.runtime.lineage.model.{GraphHash, RecoveryClass}
import raider.runtime.lineage.wire.{
  LineageEnvelope,
  LineagePayload,
  LineageWire
}
import raider.runtime.loop.LoopEvents
import zio.test.*
import zio.{Promise, Ref, ZIO}

import java.nio.file.Files

/** RAI-153 acceptance (PHE-01 exporter level): the LIVE phase-emission sink of
  * [[MultiJobExport]] — the campaign loop's `phase_started`/`phase_finished`
  * records land in the run's `events.jsonl` at the emitted positions.
  *
  * Pinned:
  *   - position: a phase envelope is appended AFTER every campaign hub envelope
  *     published before the call and BEFORE every envelope published after it
  *     (the settle-to-export discipline over the single collector fiber), so
  *     the reduced graph shape reflects emission order;
  *   - wire shape: `schemaVersion: 2`, the campaign job id, export-canonical
  *     per-job seqs (dense 1,2,3,… — proven by the versioned reader accepting
  *     the file), `invocationId` absent (the ROOT job's v2 envelopes carry no
  *     correlation key, ADR-032 §1);
  *   - watermark honesty: phase lines do NOT count into
  *     [[MultiJobExport.campaignWritten]] (they are not hub envelopes), so the
  *     settle watermark stays exact;
  *   - replay discipline: the file equals the exporter's fold buffer
  *     byte-for-byte (the reduction input is the file);
  *   - honesty: a phase emitted before the campaign job is known is DROPPED,
  *     never invented.
  *
  * Deterministic scripted fixtures only; no network, no spend.
  */
object PhaseEmissionExportSpec extends ZIOSpecDefault:

  private val rootId = RootId("rai-153-root")
  private val sessionId = StatusLedger.sessionId

  def spec = suite("multi-job export live phase sink (RAI-153)")(
    test(
      "PHE-01: phase envelopes land at the emitted positions, v2-shaped, export-canonical seqs, file == fold buffer, watermark honest"
    ) {
      ZIO.scoped {
        for
          dir <- ZIO.attempt(Files.createTempDirectory("rai-153-export"))
          hub <- EventHub.make()
          writer <- EventsJsonlWriter.open(dir.resolve("events.jsonl"))
          _ <- ZIO.addFinalizer(writer.close)
          join <- MultiJobExport.make(hub, writer, dir)
          _ <- ZIO.addFinalizer(join.close)
          ledger = StatusLedger.onTransition(hub, rootId)
          listener: OnTransition = (j, f, t) =>
            join.capture(j, f, t) *> ledger(j, f, t)
          manager <- JobManager.make(onTransition = Some(listener))
          barrier <- Promise.make[Nothing, raider.core.JobId]
          campaign = Task(
            barrier.await.flatMap { jobId =>
              val le = LoopEvents(
                hub,
                jobId,
                rootId,
                sessionId,
                phases = Some(join.phaseSink)
              )
              // emission order IS the expected file order (single fiber):
              // A-start, A-finish, phase_started, B-start, B-finish,
              // phase_finished
              le.modelCallStarted(1, "m", 1) *>
                le.modelCallFinished(1, 1L) *>
                le.phaseStarted("plan", RecoveryClass.ReadOnly) *>
                le.modelCallStarted(2, "m", 1) *>
                le.modelCallFinished(2, 1L) *>
                le.phaseFinished("plan")
            }
          )
          handle <- manager.start(rootId, "phase-camp", campaign)
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
          _ <- EventExport.awaitCaughtUp(hub, handle.id, join.campaignWritten)
          _ <- collector.interrupt
          wire <- join.exportedWire
          written <- join.campaignWritten
          lastSeq <- hub.snapshot(handle.id).map(_.map(_.lastSeq))
          parsed = LineageWireReader.read(dir.resolve("events.jsonl"))
        yield
          val envs = wire
          val camp = handle.id
          def idx(p: LineagePayload => Boolean): Int =
            envs.indexWhere(e => e.jobId == camp && p(e.payload))
          val aStart = idx {
            case LineagePayload.Frozen(
                  EventPayload.ModelCallStarted(1, _, _)
                ) =>
              true
            case _ => false
          }
          val aFinish = idx {
            case LineagePayload.Frozen(
                  EventPayload.ModelCallFinished(1, _, _)
                ) =>
              true
            case _ => false
          }
          val bStart = idx {
            case LineagePayload.Frozen(
                  EventPayload.ModelCallStarted(2, _, _)
                ) =>
              true
            case _ => false
          }
          val bFinish = idx {
            case LineagePayload.Frozen(
                  EventPayload.ModelCallFinished(2, _, _)
                ) =>
              true
            case _ => false
          }
          val phStart = idx {
            case _: LineagePayload.PhaseStarted => true
            case _                              => false
          }
          val phFinish = idx {
            case _: LineagePayload.PhaseFinished => true
            case _                               => false
          }
          val phaseEnvs = envs.filter(e =>
            e.jobId == camp &&
              (e.payload match
                case _: LineagePayload.PhaseStarted |
                    _: LineagePayload.PhaseFinished =>
                  true
                case _ => false)
          )
          assertTrue(
            // position: enter between A and B, exit after B
            aStart >= 0 && aFinish >= 0 && bStart >= 0 && bFinish >= 0,
            aStart < phStart && phStart < bStart,
            bFinish < phFinish,
            // wire shape: v2, campaign job, no correlation key
            phaseEnvs.size == 2,
            phaseEnvs.forall(e => e.schemaVersion == LineageWire.V2),
            phaseEnvs.forall(e => e.invocationId.isEmpty),
            phaseEnvs.head.payload match
              case LineagePayload.PhaseStarted(
                    "plan",
                    RecoveryClass.ReadOnly,
                    None
                  ) =>
                true
              case _ => false
            ,
            phaseEnvs.last.payload match
              case LineagePayload.PhaseFinished("plan") => true
              case _                                    => false
            ,
            // watermark honesty: phase lines are not hub envelopes
            lastSeq.isDefined && written == lastSeq.get,
            // replay discipline: the FILE is the buffer, reader-valid (the
            // reader enforces dense per-job seqs 1,2,3,…)
            parsed match
              case Right(p) =>
                p.envelopes == envs && p.truncatedTail.isEmpty
              case Left(_) => false
          )
      }
    },
    test(
      "LIN3-01: 200 ordered production campaign rounds keep sampled live projections consistent with the locked export prefix"
    ) {
      ZIO.scoped {
        for
          dir <- ZIO.attempt(
            Files.createTempDirectory("lin3-live-campaign-200")
          )
          hub <- EventHub.make()
          writer <- EventsJsonlWriter.open(dir.resolve("events.jsonl"))
          _ <- ZIO.addFinalizer(writer.close)
          join <- MultiJobExport.make(hub, writer, dir)
          _ <- ZIO.addFinalizer(join.close)
          ledger = StatusLedger.onTransition(hub, rootId)
          listener: OnTransition = (j, f, t) =>
            join.capture(j, f, t) *> ledger(j, f, t)
          manager <- JobManager.make(onTransition = Some(listener))
          barrier <- Promise.make[Nothing, JobId]
          samples <- Ref.make(
            Vector.empty[
              (
                  Int,
                  Option[LiveLineageProjection],
                  Option[String],
                  Vector[raider.runtime.lineage.wire.LineageEnvelope]
              )
            ]
          )
          campaign = Task(
            barrier.await.flatMap { jobId =>
              val events = LoopEvents(
                hub,
                jobId,
                rootId,
                sessionId,
                phases = Some(join.phaseSink)
              )
              ZIO.foreachDiscard(1 to 200) { round =>
                val phase = f"round-$round%03d"
                events.modelCallStarted(round, "fixture-model", 1) *>
                  events.modelCallFinished(round, 1L) *>
                  events.phaseStarted(phase, RecoveryClass.ReadOnly) *>
                  events.phaseFinished(phase) *>
                  (if round % 25 == 0 then
                     join.currentProjection.flatMap {
                       case (projection, error) =>
                         join.exportedWire.flatMap(wire =>
                           samples.update(
                             _ :+ ((round, projection, error, wire))
                           )
                         )
                     }
                   else ZIO.unit)
              }
            }
          )
          handle <- manager.start(rootId, "lin3-production-campaign", campaign)
          _ <- join.campaign(handle.id)
          subscribed <- hub.subscribe(handle.id)
          (snapshot, stream) = subscribed
          _ <- ZIO.foreachDiscard(
            EventExport.startTransition(snapshot).toVector
          )(
            join.exportParent
          )
          collector <- EventExport
            .inOrder(snapshot.lastSeq)(stream)
            .foreach(e => join.exportParent(e))
            .forkDaemon
          _ <- barrier.succeed(handle.id).unit
          result <- manager.await(handle).either
          _ <- EventExport.awaitCaughtUp(hub, handle.id, join.campaignWritten)
          _ <- collector.interrupt
          checkpoints <- samples.get
          (finalProjection, finalError) <- join.currentProjection
          exported <- join.exportedWire
          parsed = LineageWireReader.read(dir.resolve("events.jsonl"))
          validSamples = checkpoints.forall {
            case (round, projection, error, prefix) =>
              round % 25 == 0 && error.isEmpty && projection.exists(p =>
                p.wire == prefix && p.graphHash == GraphHash(p.graph) &&
                  p.source.isDefined &&
                  prefix.count(
                    _.payload.isInstanceOf[LineagePayload.PhaseFinished]
                  ) == round &&
                  p.graph.nodes.count(
                    _.isInstanceOf[
                      raider.runtime.lineage.model.SessionNode.ModelCall
                    ]
                  ) == round
              )
          }
        yield assertTrue(
          result.isRight,
          checkpoints.map(_._1) == Vector(25, 50, 75, 100, 125, 150, 175, 200),
          validSamples,
          finalError.isEmpty,
          finalProjection.exists(p =>
            p.wire == exported && p.graphHash == GraphHash(p.graph) &&
              p.wire.count(
                _.payload.isInstanceOf[LineagePayload.PhaseFinished]
              ) == 200 &&
              p.graph.nodes.count(
                _.isInstanceOf[
                  raider.runtime.lineage.model.SessionNode.ModelCall
                ]
              ) == 200
          ),
          parsed.exists(p => p.truncatedTail.isEmpty && p.envelopes == exported)
        )
      }
    },
    test(
      "honesty: a phase emitted before the campaign job is known is dropped, never invented"
    ) {
      ZIO.scoped {
        for
          dir <- ZIO.attempt(Files.createTempDirectory("rai-153-nocamp"))
          hub <- EventHub.make()
          writer <- EventsJsonlWriter.open(dir.resolve("events.jsonl"))
          _ <- ZIO.addFinalizer(writer.close)
          join <- MultiJobExport.make(hub, writer, dir)
          _ <- ZIO.addFinalizer(join.close)
          _ <- join.phaseSink.phaseStarted(
            "plan",
            RecoveryClass.ReadOnly,
            None,
            rootId,
            sessionId,
            0L
          )
          wire <- join.exportedWire
        yield assertTrue(
          wire.isEmpty,
          wire.forall(e =>
            e.payload match
              case _: LineagePayload.PhaseStarted |
                  _: LineagePayload.PhaseFinished =>
                false
              case _ => true
          )
        )
      }
    }
  ) @@ TestAspect.timeout(zio.Duration.fromSeconds(120))

end PhaseEmissionExportSpec
