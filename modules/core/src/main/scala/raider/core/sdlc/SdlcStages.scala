package raider.core.sdlc

import raider.core.{Program, RaiderError, Task}
import zio.json.*

/** Descriptive bounded stage composition; dispatch remains in the existing
  * Task/Runner.
  */
object SdlcStages:

  enum Stage:

    case Task, Plan, Implement, Checks, Repair, Review, Integration,
      LocalRelease

  object Stage:
    given JsonCodec[Stage] = DeriveJsonCodec.gen

  final case class StageDescription(
      stage: Stage,
      inputRefs: List[String],
      outputRefs: List[String],
      evidenceRefs: List[String]
  )

  object StageDescription:
    given JsonCodec[StageDescription] = DeriveJsonCodec.gen

  final case class Limits(maxRepairs: Int, maxReviews: Int)

  object Limits:
    given JsonCodec[Limits] = DeriveJsonCodec.gen

  final case class WorkflowDescription(
      stages: List[StageDescription],
      limits: Limits
  )

  object WorkflowDescription:
    given JsonCodec[WorkflowDescription] = DeriveJsonCodec.gen

  final case class Context(
      candidate: SdlcEvidence.CandidateIdentity,
      evidence: List[String] = Nil
  )

  final case class StageResult(
      context: Context,
      successful: Boolean,
      detail: String,
      repairNeeded: Boolean = false,
      typedEvidence: List[SdlcEvidence.Record] = Nil
  )

  type Handler = Program[Context, StageResult]

  private val requiredPrefix =
    List(Stage.Task, Stage.Plan, Stage.Implement, Stage.Checks)

  def validate(description: WorkflowDescription): Either[RaiderError, Unit] =
    val stages = description.stages.map(_.stage)
    val validBounds =
      description.limits.maxRepairs >= 0 && description.limits.maxReviews >= 0
    val orderedPrefix = stages.take(requiredPrefix.size) == requiredPrefix
    val tail = stages.drop(requiredPrefix.size)
    var repairs = 0
    var reviews = 0
    var sawReview = false
    var sawIntegration = false
    var sawRelease = false
    var previous: Option[Stage] = Some(Stage.Checks)
    var validTail = true
    tail.zipWithIndex.foreach { case (stage, index) =>
      val next = tail.lift(index + 1)
      stage match
        case Stage.Repair =>
          repairs += 1
          if !Set(Some(Stage.Checks), Some(Stage.Review))
              .contains(previous) || next != Some(Stage.Checks)
          then validTail = false
        case Stage.Checks =>
          if previous != Some(Stage.Repair) then validTail = false
        case Stage.Review =>
          reviews += 1
          sawReview = true
          if !Set(Some(Stage.Checks), Some(Stage.Review)).contains(previous)
          then validTail = false
        case Stage.Integration =>
          if !sawReview || sawIntegration || sawRelease || previous != Some(
              Stage.Review
            )
          then validTail = false
          sawIntegration = true
        case Stage.LocalRelease =>
          if !sawReview || sawRelease || previous != Some(
              Stage.Review
            ) && previous != Some(Stage.Integration)
          then validTail = false
          sawRelease = true
        case _ => validTail = false
      previous = Some(stage)
    }
    val withinBounds = repairs <= description.limits.maxRepairs &&
      reviews >= 1 && reviews <= description.limits.maxReviews + 1
    if !validBounds then
      Left(
        RaiderError.InputValidation("repair/review bounds must be non-negative")
      )
    else if !orderedPrefix || !validTail || !withinBounds then
      Left(
        RaiderError.InputValidation(
          "invalid SDLC stage order or configured loop bound"
        )
      )
    else Right(())

  /** Execute descriptions in order using one ordinary Program/Task composition.
    * A failed check may flow only into its explicitly described repair stage;
    * all other failures short-circuit. There is no scheduler or durable resume.
    */
  def program(
      description: WorkflowDescription,
      handlers: Map[Stage, Handler]
  ): Either[RaiderError, Program[Context, List[StageResult]]] =
    validate(description).flatMap { _ =>
      val missing =
        description.stages.map(_.stage).distinct.filterNot(handlers.contains)
      if missing.nonEmpty then
        Left(
          RaiderError.Configuration(
            s"missing stage handlers: ${missing.mkString(",")}"
          )
        )
      else
        Right(Program.fromFunction("sdlc-stages") { initial =>
          val executed = description.stages.foldLeft[Task[Vector[StageResult]]](
            Task.succeed(Vector.empty)
          ) { case (acc, stageDescription) =>
            acc.flatMap { results =>
              val prior = results.lastOption.map(_.context).getOrElse(initial)
              val previousFailed = results.lastOption.exists(!_.successful)
              val repairContinuation = previousFailed &&
                results.lastOption.exists(_.repairNeeded) &&
                stageDescription.stage == Stage.Repair
              if previousFailed && !repairContinuation then
                Task.succeed(results)
              else handlers(stageDescription.stage)(prior).map(results :+ _)
            }
          }
          executed.map(_.toList)
        })
    }

end SdlcStages
