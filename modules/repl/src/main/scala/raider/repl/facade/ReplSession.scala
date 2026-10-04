package raider.repl.facade

import raider.core.{ModelBackend, RaiderError, ToolRegistry}
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.jobs.JobManager
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
  */
trait ReplSession:
  def backend: ModelBackend
  def jobs: JobManager
  def admission: Admission
  def limits: BudgetLimits
  def tools: ToolRegistry = ToolRegistry.empty

object ReplSession:

  /** Wire a session. `limits` must pass BudgetLimits.make (BUD-02: invalid
    * ceilings never construct a session). `tools` must come from the validated
    * ToolRegistry constructor (`of`/`build`), so it is valid by construction.
    */
  def make(
      backend: ModelBackend,
      limits: Either[RaiderError, BudgetLimits] = BudgetLimits.make(),
      tools: ToolRegistry = ToolRegistry.empty
  ): ZIO[Any, RaiderError, ReplSession] =
    for
      valid <- ZIO.fromEither(limits)
      admission <- Admission.make(Right(valid))
      jobs <- JobManager.make()
    yield ReplSessionImpl(backend, jobs, admission, valid, tools)

  private final case class ReplSessionImpl(
      backend: ModelBackend,
      jobs: JobManager,
      admission: Admission,
      limits: BudgetLimits,
      override val tools: ToolRegistry
  ) extends ReplSession

end ReplSession
