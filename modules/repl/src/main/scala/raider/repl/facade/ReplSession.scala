package raider.repl.facade

import raider.core.{ModelBackend, RaiderError, ToolRegistry}
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.jobs.JobManager
import raider.runtime.tools.approval.ToolApprovalHook
import zio.ZIO

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
      toolApproval: Option[ToolApprovalHook] = None
  ): ZIO[Any, RaiderError, ReplSession] =
    for
      valid <- ZIO.fromEither(limits)
      admission <- Admission.make(Right(valid))
      jobs <- JobManager.make()
    yield ReplSessionImpl(
      backend,
      jobs,
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
      admission: Admission,
      limits: BudgetLimits,
      override val tools: ToolRegistry,
      override val model: String,
      override val echoStream: Boolean,
      override val renderSink: Option[TextRenderSink],
      override val toolApproval: Option[ToolApprovalHook]
  ) extends ReplSession

end ReplSession
