package raider.core.sdlc

import raider.core.{Program, Task}
import zio.test.*
import zio.json.*

object SdlcStagesSpec extends ZIOSpecDefault:
  import SdlcEvidence.*
  import SdlcStages.*

  private val candidate = CandidateIdentity(
    "repo",
    "worktree",
    Some("base"),
    Some("head"),
    Some("candidate-1"),
    "tree-a",
    "task-a",
    "plan-a",
    "policy-a",
    "config-a"
  )

  private def description(
      stages: List[Stage],
      repairs: Int = 0,
      reviews: Int = 0
  ) =
    WorkflowDescription(
      stages.map(s =>
        StageDescription(s, List("input"), List("output"), List("evidence"))
      ),
      Limits(repairs, reviews)
    )

  def spec = suite("SDLC stage and evidence behavior")(
    test(
      "SDLC-STG-01 stages execute in declaration order and evidence roundtrips as a versioned artifact"
    ) {
      val order = scala.collection.mutable.ListBuffer.empty[Stage]
      val stages = List(
        Stage.Task,
        Stage.Plan,
        Stage.Implement,
        Stage.Checks,
        Stage.Review
      )
      val handlers = stages.map { stage =>
        stage -> Program.fromFunction[Context, StageResult](
          s"${stage.toString}"
        ) { context =>
          Task.succeed {
            order += stage;
            StageResult(context, successful = true, stage.toString)
          }
        }
      }.toMap
      val plan = description(stages)
      val program = SdlcStages.program(plan, handlers).toOption.get
      val check = CheckResult(
        "compile",
        "definition",
        "argv",
        CheckOutcome.Passed,
        Some(0),
        "scala",
        "verifier",
        candidate,
        "out",
        "policy-a"
      )
      val json = SdlcEvidence.artifact("check-result", check)
      val decoded = SdlcEvidence.decodeArtifact(json).toOption.get
      val restored = decoded.payload.fromJson[CheckResult].toOption.get
      raider.core.Task.zio(program(Context(candidate))).map { results =>
        assertTrue(
          order.toList == stages,
          results.map(_.detail) == stages.map(_.toString),
          restored == check,
          checksApply(candidate, List(restored)),
          decoded.schemaVersion == 1
        )
      }
    },
    test(
      "SDLC-STG-02 candidate or policy changes invalidate prior check and review evidence"
    ) {
      val check = CheckResult(
        "test",
        "def",
        "argv",
        CheckOutcome.Passed,
        Some(0),
        "toolchain",
        "verifier",
        candidate,
        "out",
        "policy-a"
      )
      val verdict = ReviewVerdict(
        "reviewer",
        ReviewDecision.Acceptable,
        "findings",
        candidate.taskDigest,
        candidate.planDigest,
        candidate,
        List("check-digest"),
        candidate.policyDigest
      )
      val changed =
        candidate.copy(treeDigest = "tree-b", revision = Some("candidate-2"))
      val changedPolicy = candidate.copy(policyDigest = "policy-b")
      assertTrue(
        checksApply(candidate, List(check)),
        !checksApply(changed, List(check)),
        !checksApply(changedPolicy, List(check)),
        reviewApplies(candidate, verdict),
        !reviewApplies(changed, verdict),
        CandidateIdentity.validate(candidate).isRight,
        CandidateIdentity.validate(candidate.copy(repository = "")).isLeft
      )
    },
    test(
      "SDLC-STG-05 candidate digest is deterministic and binds every identity field; unknown artifact fields refuse"
    ) {
      val same = candidate.copy()
      val extended = SdlcEvidence
        .artifact("check-result", "record")
        .dropRight(1) + ",\"futureField\":true}"
      assertTrue(
        CandidateIdentity.digest(candidate) == CandidateIdentity.digest(same),
        CandidateIdentity.digest(candidate) != CandidateIdentity.digest(
          candidate.copy(treeDigest = "tree-other")
        ),
        CandidateIdentity.digest(candidate) != CandidateIdentity.digest(
          candidate.copy(baseRevision = None)
        ),
        SdlcEvidence.decodeArtifact(extended) == Left(
          ArtifactDecodeError.UnknownFields(List("futureField"))
        ),
        SdlcEvidence
          .decodeArtifact(
            SdlcEvidence.artifact("check-result", "record")
          )
          .isRight
      )
    },
    test(
      "SDLC-STG-03 invalid stage order, over-budget loops, and failed stages refuse or stop without retry"
    ) {
      val required = List(
        Stage.Task,
        Stage.Plan,
        Stage.Implement,
        Stage.Checks,
        Stage.Review
      )
      val invalidOrder = description(required.reverse)
      val overBudget = description(required ++ List(Stage.Repair), repairs = 0)
      val stages = required ++ List(Stage.Repair)
      val calls = scala.collection.mutable.ListBuffer.empty[Stage]
      val handlers = stages.distinct.map { stage =>
        stage -> Program.fromFunction[Context, StageResult](stage.toString) {
          context =>
            Task.succeed {
              calls += stage;
              StageResult(
                context,
                successful = stage != Stage.Checks,
                stage.toString
              )
            }
        }
      }.toMap
      val runnable = SdlcStages
        .program(description(required, repairs = 1), handlers)
        .toOption
        .get
      raider.core.Task.zio(runnable(Context(candidate))).map { output =>
        assertTrue(
          SdlcStages.validate(invalidOrder).isLeft,
          SdlcStages.validate(overBudget).isLeft,
          calls.toList == List(
            Stage.Task,
            Stage.Plan,
            Stage.Implement,
            Stage.Checks
          ),
          output.lastOption.exists(!_.successful)
        )
      }
    },
    test(
      "SDLC-STG-04 one declared repair rechecks the new candidate within its configured limit"
    ) {
      val stages = List(
        Stage.Task,
        Stage.Plan,
        Stage.Implement,
        Stage.Checks,
        Stage.Repair,
        Stage.Checks,
        Stage.Review
      )
      val calls = scala.collection.mutable.ListBuffer.empty[Stage]
      val handlers = stages.distinct.map { stage =>
        stage -> Program.fromFunction[Context, StageResult](stage.toString) {
          context =>
            Task.succeed {
              calls += stage
              if stage == Stage.Checks && calls.count(_ == Stage.Checks) == 1
              then
                StageResult(
                  context,
                  successful = false,
                  "checks failed",
                  repairNeeded = true
                )
              else
                val updated =
                  if stage == Stage.Repair then
                    context.copy(
                      candidate = candidate.copy(
                        revision = Some("candidate-repaired"),
                        treeDigest = "tree-repaired"
                      )
                    )
                  else context
                StageResult(updated, successful = true, stage.toString)
            }
        }
      }.toMap
      val plan = description(stages, repairs = 1)
      val program = SdlcStages.program(plan, handlers).toOption.get
      raider.core.Task.zio(program(Context(candidate))).map { results =>
        assertTrue(
          SdlcStages.validate(plan).isRight,
          calls.toList == stages,
          results.last.context.candidate.revision
            .contains("candidate-repaired"),
          results.count(_.detail == "checks failed") == 1
        )
      }
    },
    test(
      "SDLC-REL-C-02 parsed evidence binds release to one accepted candidate and authority"
    ) {
      val check = CheckResult(
        "compile",
        "definition",
        "argv",
        CheckOutcome.Passed,
        Some(0),
        "scala",
        "verifier",
        candidate,
        "output",
        candidate.policyDigest
      )
      val review = ReviewVerdict(
        "reviewer",
        ReviewDecision.Acceptable,
        "findings",
        candidate.taskDigest,
        candidate.planDigest,
        candidate,
        Nil,
        candidate.policyDigest
      )
      val integration = IntegrationResult(
        "op",
        "local",
        candidate,
        "target",
        "preconditions",
        None,
        IntegrationOutcome.Applied,
        Nil,
        candidate.policyDigest
      )
      val records = List(
        Record.Check(check),
        Record.Review(review),
        Record.Integration(integration)
      )
      val authorized = Context(
        candidate,
        typedEvidence = records,
        ownerCapabilities = Set("release"),
        authorizedCheckpoint = Some("release-approval")
      )
      assertTrue(
        validateReleaseEvidence(candidate, records).isRight,
        validateReleaseEvidence(
          candidate.copy(treeDigest = "changed"),
          records
        ).isLeft,
        validateReleaseEvidence(candidate, records.dropRight(1)).isLeft,
        authorizeLocalRelease(authorized).isRight,
        authorizeLocalRelease(
          authorized.copy(ownerCapabilities = Set.empty)
        ).isLeft,
        authorizeLocalRelease(
          authorized.copy(authorizedCheckpoint = None)
        ).isLeft
      )
    }
  )

end SdlcStagesSpec
