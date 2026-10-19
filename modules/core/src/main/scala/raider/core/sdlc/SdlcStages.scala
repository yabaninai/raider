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
      evidence: List[String] = Nil,
      typedEvidence: List[SdlcEvidence.Record] = Nil,
      ownerCapabilities: Set[String] = Set.empty,
      authorizedCheckpoint: Option[String] = None
  )

  final case class StageResult(
      context: Context,
      successful: Boolean,
      detail: String,
      repairNeeded: Boolean = false,
      typedEvidence: List[SdlcEvidence.Record] = Nil
  )

  type Handler = Program[Context, StageResult]

  enum ReleaseRefusal:
    case MissingChecks, ChecksRejected, MissingReview, ReviewRejected
    case MissingIntegration, IntegrationRejected, CandidateMismatch
    case MissingCapability, MissingCheckpoint

  /** Validate parsed records by their typed candidate identity, never by IDs or
    * caller-provided string references.
    */
  def validateReleaseEvidence(
      candidate: SdlcEvidence.CandidateIdentity,
      records: List[SdlcEvidence.Record]
  ): Either[ReleaseRefusal, String] =
    import ReleaseRefusal.*
    val checks = records.collect { case SdlcEvidence.Record.Check(value) =>
      value
    }
    val reviews = records.collect { case SdlcEvidence.Record.Review(value) =>
      value
    }
    val integrations = records.collect {
      case SdlcEvidence.Record.Integration(value) => value
    }
    val digest = SdlcEvidence.CandidateIdentity.digest(candidate)
    for
      _ <- Either.cond(checks.nonEmpty, (), MissingChecks)
      _ <- Either.cond(
        checks.forall(c =>
          c.outcome == SdlcEvidence.CheckOutcome.Passed && c.candidate == candidate && c.policyDigest == candidate.policyDigest
        ),
        (),
        ChecksRejected
      )
      _ <- Either.cond(reviews.nonEmpty, (), MissingReview)
      _ <- Either.cond(
        reviews.forall(r =>
          r.verdict == SdlcEvidence.ReviewDecision.Acceptable && SdlcEvidence
            .reviewApplies(candidate, r)
        ),
        (),
        ReviewRejected
      )
      _ <- Either.cond(integrations.nonEmpty, (), MissingIntegration)
      _ <- Either.cond(
        integrations.forall(i =>
          Set(
            SdlcEvidence.IntegrationOutcome.Applied,
            SdlcEvidence.IntegrationOutcome.AlreadyApplied
          )(i.outcome) && i.candidate == candidate
        ),
        (),
        IntegrationRejected
      )
      _ <- Either.cond(
        (checks.map(_.candidate) ++ reviews.map(_.candidate) ++ integrations
          .map(_.candidate))
          .forall(SdlcEvidence.CandidateIdentity.digest(_) == digest),
        (),
        CandidateMismatch
      )
    yield digest

  def authorizeLocalRelease(context: Context): Either[ReleaseRefusal, String] =
    import ReleaseRefusal.*
    for
      digest <- validateReleaseEvidence(
        context.candidate,
        context.typedEvidence
      )
      _ <- Either.cond(
        context.ownerCapabilities("release"),
        (),
        MissingCapability
      )
      _ <- Either.cond(
        context.authorizedCheckpoint.exists(_.trim.nonEmpty),
        (),
        MissingCheckpoint
      )
    yield digest

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
              else if stageDescription.stage == Stage.LocalRelease then
                authorizeLocalRelease(prior) match
                  case Left(refusal) =>
                    Task.succeed(
                      results :+ StageResult(
                        prior,
                        successful = false,
                        s"release refused: $refusal"
                      )
                    )
                  case Right(_) =>
                    executeHandler(stageDescription, prior, results, handlers)
              else executeHandler(stageDescription, prior, results, handlers)
            }
          }
          executed.map(_.toList)
        })
    }

  private def executeHandler(
      description: StageDescription,
      context: Context,
      results: Vector[StageResult],
      handlers: Map[Stage, Handler]
  ): Task[Vector[StageResult]] =
    handlers(description.stage)(context).map { result =>
      val updated = result.copy(context =
        result.context.copy(typedEvidence =
          result.context.typedEvidence ++ result.typedEvidence
        )
      )
      results :+ updated
    }

end SdlcStages
