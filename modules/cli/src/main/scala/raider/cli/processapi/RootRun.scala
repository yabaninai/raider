package raider.cli.processapi

import raider.core.{
  JobId,
  RaiderError,
  RequestMessage,
  RootId,
  Task,
  ToolRegistry
}
import raider.cli.run.{
  Credentials,
  EventExport,
  EventsJsonlWriter,
  ProviderBackend
}
import raider.runtime.admission.Admission
import raider.runtime.budget.{BudgetLimits, CostEstimate}
import raider.runtime.events.{EventEnvelope, EventHub, EventPayload}
import raider.runtime.jobs.{JobHandle, JobManager, StatusLedger}
import raider.runtime.loop.{AgentLoop, LoopEvents}
import zio.{Fiber, Promise, Scope, UIO, ZIO}

import java.nio.file.Path

/** RAI-201 run composition: the adapter builds the SAME runtime stack as the
  * headless runner — hub, ledger, JobManager, one admitted root whose body is
  * held on a barrier until after `hub.subscribe` (loss-window-free event
  * capture from per-job seq 1), LoopEvents emission, AgentLoop.runText — but
  * WITHOUT the CI-only layers this card does not own: no delegation wiring, no
  * multi-job export, no lineage fold, no transcript.json (v1 limitations,
  * documented in contracts/process-api). Envelopes stream through the caller's
  * `forward` (observation wire + bounded retention) AND into the canonical
  * events.jsonl checkpoint.
  */
object RootRun:

  final case class Started(
      handle: JobHandle[String],
      forwarder: Fiber.Runtime[Nothing, Unit],
      admission: Admission
  )

  final case class LaunchConfig(
      provider: String,
      model: String,
      coder: Boolean,
      workspace: Path,
      baseUrl: String,
      env: Map[String, String]
  )

  /** Preflight + dispatch of ONE root for `task`. Typed failures are launch
    * refusals (no root started, no spend, no artifacts beyond the empty work
    * dir); the caller turns them into `preflight_failed` error frames.
    */
  def start(
      cfg: LaunchConfig,
      hub: EventHub,
      manager: JobManager,
      writer: EventsJsonlWriter,
      root: RootId,
      task: String
  )(
      forward: EventEnvelope => UIO[Unit]
  ): ZIO[Scope, RaiderError, Started] =
    for
      limits <- ZIO.fromEither(
        BudgetLimits.make(maxConcurrentTools = 2, maxAttempts = 400)
      )
      admission <- Admission.make(Right(limits))
      // RAI-064 preflight discipline, unchanged: a live provider requires the
      // env credential path BEFORE any dispatch/spend; mock needs no key.
      keyOpt <- ZIO.fromEither(Credentials.resolve(cfg.provider, cfg.env))
      registry <-
        if cfg.coder then
          raider.tools.CodingToolset
            .make(cfg.workspace.toString)
            .flatMap(st => ZIO.fromEither(st.registry))
        else ZIO.succeed(ToolRegistry.empty)
      backend <- ProviderBackend.make(cfg.provider, cfg.baseUrl, keyOpt)
      model = if cfg.coder then cfg.model else "builtin:plain"
      barrier <- Promise.make[Nothing, JobId]
      droppedBefore <- hub.droppedDiagnostics
      // raider.core.Task is the runtime's lazy work unit (NOT a ZIO alias):
      // importing a Task never dispatches anything; JobManager.start owns
      // execution. The inner expression is a ZIO description of the launch.
      job = Task(
        barrier.await.flatMap { jobId =>
          val le = LoopEvents(hub, jobId, root, StatusLedger.sessionId)
          val emitMarker = hub.droppedDiagnostics.flatMap { dropped =>
            val lost = dropped - droppedBefore
            ZIO.when(lost > 0L)(
              le.emit(EventPayload.DiagnosticsCoalesced(lost))
            )
          }
          val loop = AgentLoop(
            backend,
            registry,
            model,
            admission,
            root,
            CostEstimate.Unknown,
            events = Some(le)
          )
          loop
            .runText(
              List(RequestMessage("user", task)),
              limits,
              limits.maxAttempts
            )
            .foldZIO(
              err => emitMarker *> ZIO.fail(err),
              ok => emitMarker.as(ok)
            )
        }
      )
      handle <- manager.start(root, "process-api", job)
      subscribed <- hub.subscribe(handle.id)
      (snapshot, stream) = subscribed
      startEnvelopes = EventExport.startTransition(snapshot).toVector
      _ <- ZIO.foreachDiscard(startEnvelopes)(writer.append)
      _ <- ZIO.foreachDiscard(startEnvelopes)(forward)
      forwarder <- EventExport
        .inOrder(snapshot.lastSeq)(stream)
        .foreach(env => writer.append(env) *> forward(env))
        .forkScoped
      _ <- barrier.succeed(handle.id).unit
    yield Started(handle, forwarder, admission)

end RootRun
