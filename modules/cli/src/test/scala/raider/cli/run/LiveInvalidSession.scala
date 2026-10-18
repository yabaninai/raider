package raider.cli.run

import raider.core.{RootId, Task}
import raider.runtime.events.EventHub
import raider.runtime.jobs.{JobManager, OnTransition, StatusLedger}
import raider.runtime.lineage.model.RecoveryClass
import raider.runtime.loop.LoopEvents
import zio.{Promise, ZIO}

import java.nio.file.{Files, Path}

/** Runs a real JobManager campaign through MultiJobExport's production live
  * phase sink. The duplicate phase finish is a genuine invalid incremental fold
  * input; it is not a fabricated projection error or a compiler result.
  */
private[run] object LiveInvalidSession:

  def run(
      parent: Path
  ): ZIO[
    zio.Scope,
    Nothing,
    (Int, (Option[LiveLineageProjection], Option[String]))
  ] =
    val rootId = RootId("lin3-invalid-root")
    val sessionId = StatusLedger.sessionId
    for
      dir <- ZIO
        .attempt(Files.createTempDirectory(parent, "live-invalid-"))
        .orDie
      hub <- EventHub.make()
      writer <- EventsJsonlWriter.open(dir.resolve("events.jsonl"))
      _ <- ZIO.addFinalizer(writer.close)
      join <- MultiJobExport.make(hub, writer, dir)
      _ <- ZIO.addFinalizer(join.close)
      ledger = StatusLedger.onTransition(hub, rootId)
      listener: OnTransition = (job, from, to) =>
        join.capture(job, from, to) *> ledger(job, from, to)
      manager <- JobManager.make(onTransition = Some(listener))
      started <- Promise.make[Nothing, raider.core.JobId]
      // The campaign task receives its actual assigned job id before emission.
      realCampaign = Task {
        started.await.flatMap { jobId =>
          val events = LoopEvents(
            hub,
            jobId,
            rootId,
            sessionId,
            phases = Some(join.phaseSink)
          )
          events.phaseStarted("plan", RecoveryClass.ReadOnly) *>
            events.phaseFinished("plan") *>
            events.phaseFinished("plan")
        }
      }
      handle <- manager.start(rootId, "lin3-live-invalid", realCampaign)
      _ <- join.campaign(handle.id)
      _ <- started.succeed(handle.id).unit
      subscribed <- hub.subscribe(handle.id)
      (snapshot, stream) = subscribed
      _ <- ZIO.foreachDiscard(EventExport.startTransition(snapshot).toVector)(
        join.exportParent
      )
      collector <- EventExport
        .inOrder(snapshot.lastSeq)(stream)
        .foreach(join.exportParent)
        .forkDaemon
      result <- manager.await(handle).either
      _ <- EventExport.awaitCaughtUp(hub, handle.id, join.campaignWritten)
      _ <- collector.interrupt
      live <- join.currentProjection
    yield (if result.isRight then 0 else 1, live)

end LiveInvalidSession
