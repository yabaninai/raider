package raider.repl.facade

import raider.core.{JobId, ModelBackend, RaiderError, ToolRegistry}
import raider.runtime.admission.Admission
import raider.runtime.budget.{BudgetLedger, BudgetLimits, CostEstimate}
import raider.runtime.events.EventHub
import raider.runtime.events.EventCodecs.given
import raider.runtime.jobs.JobManager
import raider.runtime.loop.{AgentLoop, PlanMode, PlanSession}
import raider.runtime.tools.approval.ToolApprovalHook
import raider.repl.PlanPanel
import zio.ZIO
import zio.json.EncoderOps

/** Frozen contract (fast-REPL slice, phase 0). One live session binding the
  * model backend, the job registry and admission together; the REPL session
  * owns root jobs (runtime-contracts §5) and the facade boundary uses exactly
  * this capability (`using ReplSession`).
  *
  * `tools` is the session-frozen ToolRegistry the agent loop serves (RAI-010.a
  * wiring point). The default is the empty registry — with it the loop
  * degenerates exactly into the previous Runner.runText behavior, so existing
  * REPL behavior is unchanged; registering tools from console commands is a
  * RAI-018/020 obligation, not a silent new surface here.
  *
  * Nightly Phase 3 (additive, defaulted — source-compatible with the frozen
  * surface): `model` is the model name the facade sends to the backend
  * ("scripted" keeps the historical `scripted:<agent>` label), `echoStream`
  * turns on real-time delta/tool-echo for interactive REPL use (off by default
  * so fixture tests stay quiet).
  */
trait ReplSession:
  def backend: ModelBackend
  def jobs: JobManager
  def admission: Admission
  def limits: BudgetLimits

  /** The one ADR-024 hub owned by this interpreter-world session. */
  def eventHub: EventHub
  def planSession: PlanSession
  def planPanel: PlanPanel
  def tools: ToolRegistry = ToolRegistry.empty
  def model: String = "scripted"
  def echoStream: Boolean = false
  def renderSink: Option[TextRenderSink] = None
  def toolApproval: Option[ToolApprovalHook] = None

/** Typed destination for model text deltas. The direct terminal echo remains
  * the fallback when no interactive renderer is installed.
  */
trait TextRenderSink:
  def textDelta(text: String): ZIO[Any, Nothing, Unit]

  /** Serialized ADR-024 event bridge. Strings are the only values that cross
    * the scala3-repl classloader boundary.
    */
  def eventJson(json: String): ZIO[Any, Nothing, Unit] = ZIO.unit

  /** Serialized plan snapshot on the same queue bridge as ADR-024 events. */
  def planSnapshotJson(json: String): ZIO[Any, Nothing, Unit] = ZIO.unit

  /** Render a tool-call card on the same chat output path as text. */
  def toolCallCard(
      name: String,
      arguments: String,
      outcome: Option[String]
  ): ZIO[Any, Nothing, Unit] = ZIO.unit

object ReplSession:

  /** Wire a session. `limits` must pass BudgetLimits.make (BUD-02: invalid
    * ceilings never construct a session). `tools` must come from the validated
    * ToolRegistry constructor (`of`/`build`), so it is valid by construction.
    */
  def make(
      backend: ModelBackend,
      limits: Either[RaiderError, BudgetLimits] = BudgetLimits.make(),
      tools: ToolRegistry = ToolRegistry.empty,
      model: String = "scripted",
      echoStream: Boolean = false,
      renderSink: Option[TextRenderSink] = None,
      toolApproval: Option[ToolApprovalHook] = None,
      planArtifacts: java.nio.file.Path = java.nio.file.Paths
        .get(".raider", "plans")
        .toAbsolutePath
  ): ZIO[Any, RaiderError, ReplSession] =
    for
      valid <- ZIO.fromEither(limits)
      jobs <- JobManager.make()
      eventHub <- EventHub.make()
      ledger <- BudgetLedger.make(valid, Some(eventHub))
      admission <- Admission.make(valid, ledger)
      planRoot = AgentLoop.newRoot()
      planLoop = AgentLoop(
        backend,
        tools,
        model,
        admission,
        planRoot,
        CostEstimate.Unknown,
        planMode = Some(PlanMode.of(tools, planArtifacts))
      )
      planSession <- PlanSession.make(planLoop, jobs)
      planPanel <- PlanPanel.make(
        planSession,
        valid,
        valid.maxAttempts,
        json => renderSink.fold(ZIO.unit)(_.planSnapshotJson(json))
      )
      _ <- renderSink match
        case None => ZIO.unit
        case Some(sink) =>
          eventHub.subscribe(JobId(planRoot.value)).flatMap {
            case (_, stream) =>
              stream
                .mapZIO(envelope => sink.eventJson(envelope.toJson))
                .runDrain
                .forkDaemon
                .unit
          }
    yield ReplSessionImpl(
      backend,
      jobs,
      eventHub,
      planSession,
      planPanel,
      admission,
      valid,
      tools,
      model,
      echoStream,
      renderSink,
      toolApproval
    )

  private final case class ReplSessionImpl(
      backend: ModelBackend,
      jobs: JobManager,
      eventHub: EventHub,
      planSession: PlanSession,
      planPanel: PlanPanel,
      admission: Admission,
      limits: BudgetLimits,
      override val tools: ToolRegistry,
      override val model: String,
      override val echoStream: Boolean,
      override val renderSink: Option[TextRenderSink],
      override val toolApproval: Option[ToolApprovalHook]
  ) extends ReplSession

end ReplSession
