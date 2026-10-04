package raider.core

import raider.core.bundle.*
import raider.core.launch.*
import raider.core.result.*
import zio.test.Assertion.*
import zio.test._

import zio.json.{DecoderOps, EncoderOps}
import zio.json.ast.Json

/** RAI-002 remainder acceptance (core codecs): bundle/result schemas and the
  * explicit launch-facade capability. Positive roundtrips plus typed negatives
  * — nothing here dispatches anything (schemas are data).
  */
object RemainderSchemasSpec extends ZIOSpecDefault:

  private def manifest(entries: BundleEntry*) = BundleManifest(
    schemaVersion = 1,
    bundleId = "pipelines",
    entries = entries.toList,
    declaredTrustLevel = "trusted-local"
  )

  private val repair = BundleEntry("repair", "demo.Pipelines$repair$", 1)

  def spec = suite("RAI-002 remainder schemas")(
    suite("bundle")(
      test("valid manifest roundtrips and selects its entry") {
        val m =
          manifest(repair, BundleEntry("inspect", "demo.Pipelines$inspect$", 2))
        val json = m.toJson
        val decoded = json.fromJson[BundleManifest]
        assertTrue(
          decoded == Right(m),
          BundleManifest.validate(m).isRight,
          BundleManifest.entry(m, "repair") == Right(repair)
        )
      },
      test(
        "negatives: unknown version, empty ids, no entries, dup names, bad version"
      ) {
        val m = manifest(repair)
        val bad1 = m.copy(schemaVersion = 9)
        val bad2 = m.copy(bundleId = " ")
        val bad3 = m.copy(entries = Nil)
        val bad4 = manifest(repair, BundleEntry("repair", "X", 1))
        val bad5 = manifest(repair.copy(version = 0))
        val bad6 = manifest(repair.copy(mainClass = " "))
        val bad7 = m.copy(declaredTrustLevel = "")
        assertTrue(
          BundleManifest.validate(bad1).swap.exists(_.code == "RA-INP"),
          BundleManifest
            .validate(bad2)
            .swap
            .exists(_.detail.contains("bundleId")),
          BundleManifest
            .validate(bad3)
            .swap
            .exists(_.detail.contains("at least one")),
          BundleManifest
            .validate(bad4)
            .swap
            .exists(_.detail.contains("duplicate")),
          BundleManifest
            .validate(bad5)
            .swap
            .exists(_.detail.contains("version")),
          BundleManifest
            .validate(bad6)
            .swap
            .exists(_.detail.contains("mainClass")),
          BundleManifest
            .validate(bad7)
            .swap
            .exists(_.detail.contains("Trust")) ||
            BundleManifest
              .validate(bad7)
              .swap
              .exists(_.detail.contains("trust"))
        )
      },
      test("unknown entry lookup is a structured error listing known names") {
        val m = manifest(repair, BundleEntry("inspect", "I", 1))
        val res = BundleManifest.entry(m, "nope")
        assertTrue(res.swap.exists { e =>
          e.code == "RA-INP" && e.detail.contains("unknown workflow 'nope'") &&
          e.detail.contains("inspect") && e.detail.contains("repair")
        })
      }
    ),
    suite("result")(
      test(
        "full RunResult roundtrips (error stays flat, status is enum string)"
      ) {
        import raider.core.codecs.given
        val r = RunResult(
          schemaVersion = 1,
          rootId = RootId("r-1"),
          status = ExecutionStatus.Failed,
          exitCode = ExitCodes.TaskRejected,
          taskVerdict = Some("fail: hidden assertion A4"),
          checks = List(
            CheckResult("unit", CheckOutcome.Passed, "ok"),
            CheckResult("verify", CheckOutcome.Failed, "A4")
          ),
          artifacts = List(
            ArtifactEntry(
              ArtifactId("a-1"),
              "a" * 64,
              "application/json",
              128,
              "out/result.json"
            )
          ),
          output = Some(OutputPayload("raider/task-json", 1, """{"v":1}""")),
          error = Some(RaiderError.ToolFailed("verify failed")),
          startedAt = "2026-10-03T21:00:00Z",
          finishedAt = "2026-10-03T21:00:05Z"
        )
        val json = r.toJson
        val decoded = json.fromJson[RunResult]
        assertTrue(
          decoded == Right(r),
          json.contains(""""status":"Failed""""),
          json.contains(""""code":"RA-TOOLFAIL"""")
        )
      },
      test("valid minimal result passes validation") {
        val r = RunResult(
          1,
          RootId("r-2"),
          ExecutionStatus.Succeeded,
          ExitCodes.Success,
          None,
          Nil,
          Nil,
          None,
          None,
          "t1",
          "t2"
        )
        assertTrue(RunResult.validate(r).isRight)
      },
      test(
        "negatives: unknown exit code, bad sha, negative size, invalid output JSON"
      ) {
        val base = RunResult(
          1,
          RootId("r-3"),
          ExecutionStatus.Succeeded,
          ExitCodes.Success,
          None,
          Nil,
          Nil,
          None,
          None,
          "t1",
          "t2"
        )
        val badExit = base.copy(exitCode = 99)
        val badSha = base.copy(artifacts =
          List(ArtifactEntry(ArtifactId("a"), "XYZ", "text/plain", 1, "p"))
        )
        val negSize = base.copy(artifacts =
          List(ArtifactEntry(ArtifactId("a"), "a" * 64, "text/plain", -5, "p"))
        )
        val badJson =
          base.copy(output = Some(OutputPayload("c", 1, "{not json")))
        val emptyTs = base.copy(finishedAt = "")
        assertTrue(
          RunResult
            .validate(badExit)
            .swap
            .exists(_.detail.contains("unknown exitCode")),
          RunResult.validate(badSha).swap.exists(_.detail.contains("sha256")),
          RunResult
            .validate(negSize)
            .swap
            .exists(_.detail.contains("sizeBytes")),
          RunResult
            .validate(badJson)
            .swap
            .exists(_.detail.contains("not valid JSON")),
          RunResult
            .validate(emptyTs)
            .swap
            .exists(_.detail.contains("timestamps"))
        )
      },
      test("exit-code mapping is the declared stable set, all distinct") {
        assertTrue(
          ExitCodes.all.size == 12,
          ExitCodes.all == Set(0, 10, 20, 21, 22, 23, 24, 25, 26, 27, 130, 143),
          ExitCodes.Uncertain == 26,
          ExitCodes.SigInt == 130,
          ExitCodes.SigTerm == 143
        )
      }
    ),
    suite("launch capability")(
      test("DefinitionContext validates and resolves registered names") {
        val cap = new LaunchCapability:
          def sessionId = SessionId("s-1")
        val ctx =
          DefinitionContext(cap, List("scout", "worker"), List("inspect"))
        assertTrue(
          DefinitionContext.validate(ctx).isRight,
          DefinitionContext.requireAgent(ctx, "scout") == Right("scout"),
          DefinitionContext.requireWorkflow(ctx, "inspect") == Right("inspect")
        )
      },
      test(
        "negatives: duplicates, empty names, unknown lookups name the known set"
      ) {
        val cap = new LaunchCapability:
          def sessionId = SessionId("s-2")
        val dup = DefinitionContext(cap, List("scout", "scout"), Nil)
        val empty = DefinitionContext(cap, List(" "), Nil)
        val unknown = DefinitionContext(cap, List("scout"), List("inspect"))
        assertTrue(
          DefinitionContext
            .validate(dup)
            .swap
            .exists(_.detail.contains("duplicate agent")),
          DefinitionContext.validate(empty).swap.exists(_.code == "RA-INP"),
          DefinitionContext.requireAgent(unknown, "nope").swap.exists { e =>
            e.code == "RA-INP" && e.detail.contains("unknown agent 'nope'") &&
            e.detail.contains("scout")
          },
          DefinitionContext.requireWorkflow(unknown, "repair").swap.exists {
            e =>
              e.detail.contains("unknown workflow 'repair'") && e.detail
                .contains("inspect")
          }
        )
      },
      test(
        "capability is explicit: no capability instance, no launch (marker trait)"
      ) {
        // LaunchCapability is a trait with no global instance in core: the only
        // way to obtain one is to construct it explicitly (prelude/loader).
        val cap = new LaunchCapability:
          def sessionId = SessionId("s-3")
        assertTrue(cap.sessionId == SessionId("s-3"))
      }
    )
  )
