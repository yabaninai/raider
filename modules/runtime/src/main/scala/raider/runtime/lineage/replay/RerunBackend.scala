package raider.runtime.lineage.replay

import java.nio.file.Path

import raider.core.RaiderError
import raider.runtime.budget.BudgetLimits
import raider.runtime.lineage.model.SessionGraph
import zio.{Scope, ZIO}

/** Provenance and fresh inputs for one explicitly requested live rerun.
  * `snapshotGeneration` is the committed pointer generation used to obtain the
  * recorded graph. `prompt` is supplied for this execution and is never
  * substituted with a recorded model prompt.
  */
final case class RerunRequest(
    workspace: Path,
    prompt: String,
    model: String,
    snapshotGeneration: String,
    recordedGraph: SessionGraph,
    limits: BudgetLimits,
    maxRounds: Int
)

/** Result produced by a fresh execution. The recorded graph remains provenance
  * only; no historical tool call or mutation is replayed by this interface.
  */
final case class RerunExecution(answer: String, graph: SessionGraph)

/** Runtime-owned effect boundary implemented by the CLI's execution harness.
  * Implementations must honor cancellation and the request's budget.
  */
trait RerunBackend:

  def execute(
      request: RerunRequest
  ): ZIO[Scope, RaiderError, RerunExecution]

/** Runtime entry point for invoking an explicitly configured live backend. */
object LiveRerun:

  def execute(
      backend: RerunBackend,
      request: RerunRequest
  ): ZIO[Scope, RaiderError, RerunExecution] =
    if request.maxRounds < 1 then
      ZIO.fail(
        RaiderError.InputValidation("live rerun maxRounds must be positive")
      )
    else backend.execute(request)
