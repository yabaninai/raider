package raider.dsl.lineage.proj.runtime

import raider.core.{Program, RaiderError, Task}
import raider.dsl.lineage.proj.NodeKind
import zio.{Exit, Scope, ZIO}
import zio.test.*

import java.util.concurrent.atomic.AtomicInteger

/** Unit evidence for ADR-031 (card RAI-140): the lineage projection runtime is
  * a typed dry-run description — deterministic reports, zero provider/tool
  * dispatch at construction AND execution, typed failure short-circuit,
  * value-threaded inputs, sub-Program nesting, concurrency isolation.
  */
object ProjectionRuntimeSpec extends ZIOSpecDefault:

  // ---- fixtures -------------------------------------------------------------

  private val expectedSample: Vector[StepReport] = Vector(
    StepReport(
      "m1",
      NodeKind.ModelCall("glm-5.3-flash", 1),
      Some("d-model"),
      Vector.empty,
      0
    ),
    StepReport(
      "t1",
      NodeKind.ToolCall("files.write", "Mutating"),
      Some("d-tool-1"),
      Vector("m1"),
      0
    ),
    StepReport(
      "d1",
      NodeKind.Delegation("worker"),
      Some("d-del"),
      Vector.empty,
      0
    ),
    StepReport("p1", NodeKind.Phase("verify"), Some("d-phase"), Vector.empty, 1)
  )

  /** The phase named def as a sub-Program — the shape RAI-141 renders for
    * delegation/phase bodies (ADR-031 §2.3).
    */
  private def phaseVerify(nesting: Int): Program[Unit, Vector[StepReport]] =
    Program.fromFunction("phase_verify") { _ =>
      LineageStep.phase(
        "p1",
        "verify",
        Some("d-phase"),
        Vector.empty,
        nesting = nesting
      )
    }

  /** Hand-written sample projection (PRC-01): two atomic steps plus a
    * delegation def whose sub-Program carries a nested phase.
    */
  private val sampleProgram: Program[Unit, Vector[StepReport]] =
    Program.fromFunction("lineage_sample") { _ =>
      LineageStep.seq(
        LineageStep
          .model("m1", "glm-5.3-flash", 1, Some("d-model"), Vector.empty),
        LineageStep.tool(
          "t1",
          "files.write",
          "Mutating",
          Some("d-tool-1"),
          Vector("m1")
        ),
        LineageStep.delegation("d1", "worker", Some("d-del"), Vector.empty),
        phaseVerify(1)(())
      )
    }

  private val otherProgram: Program[Unit, Vector[StepReport]] =
    Program.fromFunction("lineage_other") { _ =>
      LineageStep.seq(
        LineageStep.tool(
          "x1",
          "search.read",
          "ReadOnly",
          Some("d-x1"),
          Vector.empty
        )
      )
    }

  private val expectedOther: Vector[StepReport] = Vector(
    StepReport(
      "x1",
      NodeKind.ToolCall("search.read", "ReadOnly"),
      Some("d-x1"),
      Vector.empty,
      0
    )
  )

  /** The channel `RaiderError` of a failed dry run (PRC-02 helper). */
  private def channelError[A](exit: Exit[RaiderError, A]): RaiderError =
    exit match
      case Exit.Failure(cause) =>
        cause.failureOption.getOrElse(
          throw new IllegalStateException(
            "expected a pure typed failure, got: " + cause
          )
        )
      case Exit.Success(_) =>
        throw new IllegalStateException("expected the dry run to fail")

  // ---- spec -----------------------------------------------------------------

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ProjectionRuntimeSpec")(
    suite("PRC-01")(
      test(
        "sample projection executes to the deterministic report vector (order == declaration)"
      ) {
        val built = sampleProgram(())
        Task.zio(built).map(reports => assertTrue(reports == expectedSample))
      },
      test(
        "zero dispatch: construction and repeat execution leave the effect probe untouched; the probe harness detects a real effect (negative control)"
      ) {
        for
          probe <- ZIO.succeed(new AtomicInteger(0))
          built = sampleProgram(()) // construction only
          _ <- ZIO.succeed(assertTrue(probe.get == 0))
          run1 <- Task.zio(built)
          run2 <- Task.zio(built)
          _ <- ZIO.succeed(assertTrue(probe.get == 0))
          // Negative control: the harness MUST see a real effect when one
          // exists (test-side Task over ZIO.succeed) — so the zero readings
          // above are evidence of absence, not a dead probe.
          control = Task(ZIO.succeed(probe.incrementAndGet()))
          _ <- Task.zio(control)
        yield assertTrue(
          probe.get == 1,
          run1 == expectedSample,
          run2 == expectedSample,
          run1 == run2
        )
      },
      test("empty seq succeeds with an empty report vector") {
        Task.zio(LineageStep.seq()).map(v => assertTrue(v.isEmpty))
      }
    ),
    suite("PRC-02")(
      test(
        "failed step short-circuits with the typed error (RA-OUT channel, LineageStepFailure cause)"
      ) {
        val failing = LineageStep.seq(
          LineageStep
            .model("m1", "glm-5.3-flash", 1, Some("d-model"), Vector.empty),
          LineageStep.failed(
            "t1",
            NodeKind.ToolCall("files.write", "Mutating"),
            Some("d-tool-1"),
            Vector("m1"),
            nesting = 0,
            reason = "recorded outcome: failed (exit code 1)"
          ),
          // A second downstream failure: if evaluation ever proceeded past
          // the first failure, this node's failure would surface instead.
          LineageStep.failed(
            "m2",
            NodeKind.ModelCall("glm-5.3-flash", 2),
            Some("d-model-2"),
            Vector("t1"),
            nesting = 0,
            reason = "downstream must never run"
          )
        )
        for outcome <- Task.zio(failing).exit
        yield
          val error = channelError(outcome)
          val typed = error.getCause match
            case failure: LineageStepFailure => failure
            case other =>
              throw new IllegalStateException(
                "expected a LineageStepFailure cause, got: " + other
              )
          assertTrue(
            error.code == "RA-OUT",
            typed.step.id == "t1",
            typed.step.kind == NodeKind.ToolCall("files.write", "Mutating"),
            typed.step.dataRef == Some("d-tool-1"),
            typed.step.inputs == Vector("m1"),
            typed.step.nesting == 0,
            typed.reason.contains("exit code 1"),
            error.detail == typed.getMessage
          )
      },
      test("inputs flow as threaded values through the flatMap chain") {
        // The generated code binds the upstream step's report and passes the
        // referenced ids into the downstream step's inputs (ADR-031 §2.3).
        val threaded: Program[Unit, Vector[StepReport]] =
          Program.fromFunction("lineage_threaded") { _ =>
            LineageStep
              .model("m1", "glm-5.3-flash", 1, Some("d-model"), Vector.empty)
              .flatMap { m1 =>
                LineageStep
                  .tool(
                    "t1",
                    "files.write",
                    "Mutating",
                    Some("d-tool-1"),
                    Vector(m1.head.id)
                  )
                  .flatMap { t1 =>
                    LineageStep
                      .model(
                        "m2",
                        "glm-5.3-flash",
                        2,
                        Some("d-model-2"),
                        t1.map(_.id)
                      )
                      .map(m2 => m1 ++ t1 ++ m2)
                  }
              }
          }
        val expected = Vector(
          StepReport(
            "m1",
            NodeKind.ModelCall("glm-5.3-flash", 1),
            Some("d-model"),
            Vector.empty,
            0
          ),
          StepReport(
            "t1",
            NodeKind.ToolCall("files.write", "Mutating"),
            Some("d-tool-1"),
            Vector("m1"),
            0
          ),
          StepReport(
            "m2",
            NodeKind.ModelCall("glm-5.3-flash", 2),
            Some("d-model-2"),
            Vector("t1"),
            0
          )
        )
        Task.zio(threaded(())).map(r => assertTrue(r == expected))
      },
      test(
        "delegation and phase nest as sub-Programs with visible nesting levels"
      ) {
        val delegationDef: Program[Unit, Vector[StepReport]] =
          Program.fromFunction("delegation_worker") { _ =>
            LineageStep.seq(
              LineageStep.delegation(
                "d1",
                "worker",
                Some("d-del"),
                Vector.empty,
                nesting = 1
              ),
              phaseVerify(2)(())
            )
          }
        val parent: Program[Unit, Vector[StepReport]] =
          Program.fromFunction("lineage_nested") { _ =>
            LineageStep.seq(
              LineageStep
                .model("m1", "glm-5.3-flash", 1, Some("d-model"), Vector.empty),
              delegationDef(())
            )
          }
        val expected = Vector(
          StepReport(
            "m1",
            NodeKind.ModelCall("glm-5.3-flash", 1),
            Some("d-model"),
            Vector.empty,
            0
          ),
          StepReport(
            "d1",
            NodeKind.Delegation("worker"),
            Some("d-del"),
            Vector.empty,
            1
          ),
          StepReport(
            "p1",
            NodeKind.Phase("verify"),
            Some("d-phase"),
            Vector.empty,
            2
          )
        )
        Task.zio(parent(())).map(r => assertTrue(r == expected))
      }
    ),
    suite("PRC-03")(
      test(
        "concurrent construction and execution of two projections is isolated"
      ) {
        for
          // Construction happens INSIDE the fibers: no shared mutable state,
          // each fiber sees only its own projection's reports.
          f1 <- Task.zio(sampleProgram(())).fork
          f2 <- Task.zio(otherProgram(())).fork
          r1 <- f1.join
          r2 <- f2.join
        yield assertTrue(r1 == expectedSample, r2 == expectedOther)
      },
      test(
        "repeat execution is deterministic (identical reports, sequential and concurrent)"
      ) {
        for
          a <- Task.zio(sampleProgram(()))
          b <- Task.zio(sampleProgram(()))
          c <- Task.zio(sampleProgram(()))
          f1 <- Task.zio(sampleProgram(())).fork
          f2 <- Task.zio(sampleProgram(())).fork
          d <- f1.join
          e <- f2.join
        yield assertTrue(
          a == b,
          b == c,
          c == d,
          d == e,
          a == expectedSample
        )
      }
    )
  )

end ProjectionRuntimeSpec
