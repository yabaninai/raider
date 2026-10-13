package raider.dsl.lineage.proj.runtime

import raider.core.{RaiderError, Task}
import raider.dsl.lineage.proj.NodeKind

/** Structural report of one projected lineage node in the dry run (ADR-031).
  * The report is pure data derived exclusively from the step constructor
  * arguments: `kind` is the projection layer's parameterized `NodeKind`,
  * `dataRef` is the KEY into the lineage-data resource (payloads never enter
  * code or reports), `inputs` carries the threaded dataflow ids, `nesting` is
  * the static depth the renderer emits (roots at 0, each named-def level +1).
  */
final case class StepReport(
    id: String,
    kind: NodeKind,
    dataRef: Option[String],
    inputs: Vector[String],
    nesting: Int
)

/** Typed failure of a projected step (ADR-031 §2.4): the structured context of
  * a recorded-failed node plus the recorded reason. The `Task` error channel is
  * the frozen sealed `RaiderError` family, so this value reaches consumers
  * chained as the JDK cause of `RaiderError.OutputValidation` — see
  * [[LineageStep.failed]].
  */
final case class LineageStepFailure(step: StepReport, reason: String)
    extends RuntimeException(LineageStepFailure.message(step, reason))

object LineageStepFailure:

  /** Deterministic single-line detail: enters the public explanation slot of
    * the channel error and this failure's message. Recorded lineage data is
    * redacted upstream (runtime-contracts §13); the reason is a recorded
    * outcome phrase, never a secret.
    */
  private def message(step: StepReport, reason: String): String =
    "lineage dry-run step failed: id=" + step.id +
      " kind=" + step.kind.productPrefix +
      " dataRef=" + step.dataRef.getOrElse("-") +
      " inputs=[" + step.inputs.mkString(",") + "]" +
      " nesting=" + step.nesting +
      " reason=" + reason

end LineageStepFailure

/** The lineage projection runtime (ADR-031): typed dry-run step constructors
  * the GENERATED code references. Construction and execution dispatch NOTHING —
  * no provider call, no tool call, no I/O; every constructor is a pure function
  * of its arguments and the runtime holds no shared mutable state. The only
  * combinators used are `Task.succeed` / `Task.fail` / `map` / `flatMap`;
  * observation happens exclusively through `Task.zio` on the built `Program`
  * (no second execution semantics).
  *
  * Uniform vector shape: every step evaluates to the pre-order report fragment
  * of its subtree (one element for an atomic step; own node + descendants for a
  * nested def), so [[seq]] composes atomic steps and nested sub-Program defs
  * uniformly. Sequencing via [[seq]] is declaration order == graph child order;
  * repeat execution is deterministic (PRC-01/PRC-03).
  */
object LineageStep:

  /** Model-call node: reports the node — never contacts a model. */
  def model(
      id: String,
      model: String,
      round: Int,
      dataRef: Option[String],
      inputs: Vector[String],
      nesting: Int = 0
  ): Task[Vector[StepReport]] =
    Task.succeed(
      Vector(
        StepReport(
          id,
          NodeKind.ModelCall(model, round),
          dataRef,
          inputs,
          nesting
        )
      )
    )

  /** Tool-call node: reports the node — never dispatches a tool. */
  def tool(
      id: String,
      tool: String,
      recovery: String,
      dataRef: Option[String],
      inputs: Vector[String],
      nesting: Int = 0
  ): Task[Vector[StepReport]] =
    Task.succeed(
      Vector(
        StepReport(
          id,
          NodeKind.ToolCall(tool, recovery),
          dataRef,
          inputs,
          nesting
        )
      )
    )

  /** Delegation node: reports the node — the delegated child subtree is a
    * nested generated def (a sub-Program) spliced by [[seq]].
    */
  def delegation(
      id: String,
      agent: String,
      dataRef: Option[String],
      inputs: Vector[String],
      nesting: Int = 0
  ): Task[Vector[StepReport]] =
    Task.succeed(
      Vector(
        StepReport(id, NodeKind.Delegation(agent), dataRef, inputs, nesting)
      )
    )

  /** Phase node: reports the node — the phase body is a nested generated def (a
    * sub-Program) spliced by [[seq]].
    */
  def phase(
      id: String,
      name: String,
      dataRef: Option[String],
      inputs: Vector[String],
      nesting: Int = 0
  ): Task[Vector[StepReport]] =
    Task.succeed(
      Vector(StepReport(id, NodeKind.Phase(name), dataRef, inputs, nesting))
    )

  /** A recorded-failed node: `Task.fail` typed (ADR-031 §2.4). The channel
    * error is `RaiderError.OutputValidation` (RA-OUT — a dry-run validation of
    * a recorded outcome; `ToolFailed` would falsely imply a live dispatch)
    * whose detail is the failure's deterministic message; the fully typed
    * [[LineageStepFailure]] is chained as its JDK cause. `initCause` runs
    * exactly once here at construction, so the resulting error is frozen before
    * any run — repeated or concurrent executions share one immutable failure
    * value. The first failed step short-circuits the whole `seq` chain;
    * downstream steps are never evaluated.
    */
  def failed(
      id: String,
      kind: NodeKind,
      dataRef: Option[String],
      inputs: Vector[String],
      nesting: Int,
      reason: String
  ): Task[Vector[StepReport]] =
    val failure =
      LineageStepFailure(StepReport(id, kind, dataRef, inputs, nesting), reason)
    val error = RaiderError.OutputValidation(failure.getMessage)
    error.initCause(failure)
    Task.fail(error)

  /** Sequential composition: a `Task.flatMap` chain preserving declaration
    * order; an empty chain succeeds with an empty vector (a projection with
    * zero executable statements is a renderer bug — RAI-141 asserts at least
    * one real statement per body).
    */
  def seq(steps: Task[Vector[StepReport]]*): Task[Vector[StepReport]] =
    steps.foldLeft(Task.succeed(Vector.empty[StepReport])) { (acc, step) =>
      acc.flatMap(done => step.map(done ++ _))
    }

end LineageStep
