package raider.cli.lineage

import raider.dsl.lineage.data.{LineageDataDocument, NodeData}

import scala.jdk.CollectionConverters.*

import zio.test.*
import zio.{Scope, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** RAI-148 acceptance (docs/tasks/lineage.md, ADR-027 §2/§6.2): the LIN6-02
  * isolation guard must classify `fs_patch` and `proc_run` as Mutating — before
  * the fix the captured BillingCi repair shape (a real `fs_patch` source repair
  * followed by a `proc_run` build verify, both annotated with the stale
  * ReadOnly verdict of their recording-time taxonomy) was classified ReadOnly
  * and the guard let a mutating replay through WITHOUT isolation.
  *
  *   - TAX-02-01: [[IsolatedRerun.mutatingTools]] classifies the captured
  *     repair shape from the recorded RESPONSE envelopes (fs_patch + proc_run)
  *     even though every annotation still says ReadOnly — same
  *     classify-from-response discipline as IR-02.
  *   - TAX-02-02 (the defect reproducer): replaying the repair record against
  *     an IN-PLACE alias of the original is refused TYPED
  *     ([[RerunError.MutatingUnisolated]]) BEFORE any execution — the original
  *     stays byte-identical and the guard creates nothing under `parent`.
  *   - TAX-02-03: the same record against a FAITHFUL isolated copy
  *     ([[WorkspaceIsolation.isolate]] output) proceeds — `Right(())`.
  *   - TAX-03: a genuinely ReadOnly record (fs_read responses only) still needs
  *     no isolation — the guard discipline is unchanged for tools the taxonomy
  *     does not know.
  *
  * RAI-268 (additive): the RAI-260 structured mutation tools `fs_patch_multi`
  * and `fs_delete` are now Mutating in the canonical taxonomy, so the guard
  * must classify a record carrying them as mutating (TAX-02-04) and refuse an
  * in-place replay typed (TAX-02-05) while a faithful isolated copy proceeds
  * (TAX-02-06); exact-name matching still holds — case variants and near-miss
  * names in responses stay ReadOnly (TAX-03-RAI268).
  *
  * No network, no paid calls, NO replay execution: the guard reads the record
  * and file bytes only — it never starts a backend, toolset or loop.
  */
object RecoveryTaxonomyGuardSpec extends ZIOSpecDefault:

  /** The captured BillingCi repair, as a record convention response: round 1
    * patches the source (fs_patch), round 2 runs the build (proc_run). The `\n`
    * sequences are JSON string escapes inside the response payload.
    */
  private val RepairRound1Response =
    """{"tool_calls":[{"id":"call-1","name":"fs_patch",""" +
      """"arguments":{"path":"src/BillingCi.scala",""" +
      """"diff":"--- a/src/BillingCi.scala\n+++ b/src/BillingCi.scala\n@@ -1,1 +1,2 @@",""" +
      """"expected_sha256":"0000000000000000000000000000000000000000000000000000000000000000"}}]}"""

  private val VerifyRound2Response =
    """{"tool_calls":[{"id":"call-2","name":"proc_run",""" +
      """"arguments":{"argv":["make","test"],"timeout_s":120}}]}"""

  /** The captured repair shape: BOTH responses carry mutating tool_calls, while
    * the recorded annotations stay stale (ReadOnly) — the recording-time
    * taxonomy under-reported the repair, which is exactly the RAI-148 defect.
    */
  private def repairedDocument: LineageDataDocument =
    withResponse(
      withResponse(
        ReplayHarness.document,
        ReplayHarness.Round1Ref,
        RepairRound1Response
      ),
      ReplayHarness.Round2Ref,
      VerifyRound2Response
    )

  private def withResponse(
      doc: LineageDataDocument,
      dataRef: String,
      response: String
  ): LineageDataDocument =
    doc.data.get(dataRef) match
      case Some(m: NodeData.ModelCallData) =>
        doc.copy(data = doc.data.updated(dataRef, m.copy(response = response)))
      case _ => sys.error(s"fixture bug: '$dataRef' is not a ModelCallData ref")

  // -- RAI-268 fixtures -------------------------------------------------------

  /** One recorded round whose `tool_calls` envelope carries BOTH RAI-260
    * structured mutation tools (the OpenAI-compatible wire allows several calls
    * per round): an `fs_patch_multi` batch and an `fs_delete`.
    */
  private val Rai260MutationRoundResponse =
    """{"tool_calls":[""" +
      """{"id":"call-m1","name":"fs_patch_multi",""" +
      """"arguments":{"diff":"diff --git a/src/BillingCi.scala",""" +
      """"expected_sha256":{"src/BillingCi.scala":"0000000000000000000000000000000000000000000000000000000000000000"}}},""" +
      """{"id":"call-d1","name":"fs_delete",""" +
      """"arguments":{"path":"obsolete.txt",""" +
      """"expected_sha256":"0000000000000000000000000000000000000000000000000000000000000000"}}]}"""

  /** Adversarial round: case variants and near-misses of the two names — the
    * exact-name taxonomy must NOT classify any of them Mutating.
    */
  private val Rai260NameVariantRoundResponse =
    """{"tool_calls":[""" +
      """{"id":"call-v1","name":"FS_DELETE","arguments":{"path":"x.txt"}},""" +
      """{"id":"call-v2","name":"fs_delete ","arguments":{"path":"y.txt"}},""" +
      """{"id":"call-v3","name":"FS_PATCH_MULTI","arguments":{"diff":"x"}},""" +
      """{"id":"call-v4","name":"fs_patch_multi_v2","arguments":{"diff":"x"}}]}"""

  /** The RAI-268 repair record: the recorded round-1 response carries the two
    * structured mutation calls while the recorded annotations stay stale
    * (ReadOnly) — the classify-from-response discipline must not under-report
    * the replay's mutations.
    */
  private def rai260MutationDocument: LineageDataDocument =
    withResponse(
      ReplayHarness.document,
      ReplayHarness.Round1Ref,
      Rai260MutationRoundResponse
    )

  private def rai260NameVariantDocument: LineageDataDocument =
    withResponse(
      ReplayHarness.document,
      ReplayHarness.Round1Ref,
      Rai260NameVariantRoundResponse
    )

  /** Fixture workspace mirroring the captured repair target: the fixture file
    * plus the repaired source file, both byte-snapshotted by the guard checks.
    */
  private def freshWorkspace: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val ws = Files.createTempDirectory("raider-rai148-ws")
        Files.writeString(
          ws.resolve(ReplayHarness.FixtureFile),
          ReplayHarness.FixtureFileContent,
          UTF_8
        )
        Files.createDirectories(ws.resolve("src"))
        Files.writeString(
          ws.resolve("src").resolve("BillingCi.scala"),
          "object BillingCi // recorded pre-repair bytes\n",
          UTF_8
        )
        ws
      }.orDie
    )(ws => ZIO.attempt(deleteRecursively(ws)).orDie.ignore)

  private def freshParent: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(
      ZIO.attempt(Files.createTempDirectory("raider-rai148-parent")).orDie
    )(p => ZIO.attempt(deleteRecursively(p)).orDie.ignore)

  private def snapshotOf(ws: Path): ZIO[Any, Nothing, Map[Path, Array[Byte]]] =
    ZIO.attempt(WorkspaceIsolation.snapshot(ws)).orDie

  private def verifyUntouched(
      ws: Path,
      before: Map[Path, Array[Byte]]
  ): ZIO[Any, Nothing, Either[IsolationError, Unit]] =
    ZIO.attempt(WorkspaceIsolation.verifyUntouched(ws, before)).orDie

  private def listDirs(parent: Path): Vector[Path] =
    val stream = Files.list(parent)
    try
      stream
        .iterator()
        .asScala
        .toVector
        .filter(Files.isDirectory(_))
        .sortBy(_.toString)
    finally stream.close()

  private def deleteRecursively(root: Path): Unit =
    val stream = Files.walk(root)
    try
      stream
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .forEach(p => Files.deleteIfExists(p))
      ()
    finally stream.close()

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("RecoveryTaxonomyGuard (RAI-148)")(
      test(
        "TAX-02-01: mutatingTools classifies the captured fs_patch/proc_run repair from the responses (stale ReadOnly annotations ignored)"
      ) {
        val doc = repairedDocument
        val annotations = doc.data.values.collect {
          case t: NodeData.ToolCallData => t.tool -> t.recovery
        }.toMap
        assertTrue(
          IsolatedRerun.mutatingTools(doc) == Vector("fs_patch", "proc_run"),
          annotations.get("fs_read") == Some("ReadOnly")
        )
      },
      test(
        "TAX-02-02: replaying the repair record in place is refused typed BEFORE any execution (the pre-fix silent pass)"
      ) {
        live {
          ZIO.scoped {
            for
              ws <- freshWorkspace
              parent <- freshParent
              before <- snapshotOf(ws)
              alias = ws.resolve("..").resolve(ws.getFileName)
              verdict <- ZIO
                .attempt(
                  IsolatedRerun
                    .requireIsolatedTarget(repairedDocument, ws, alias)
                )
                .orDie
              untouched <- verifyUntouched(ws, before)
              noDirt <- ZIO.attempt(listDirs(parent).isEmpty).orDie
            yield assertTrue(
              verdict == Left(
                RerunError.MutatingUnisolated(Vector("fs_patch", "proc_run"))
              ),
              untouched == Right(()),
              noDirt
            )
          }
        }
      },
      test(
        "TAX-02-03: the same record against a faithful isolated copy proceeds"
      ) {
        live {
          ZIO.scoped {
            for
              ws <- freshWorkspace
              parent <- freshParent
              copy <- ZIO.attempt {
                WorkspaceIsolation.isolate(ws, parent) match
                  case Right(p) => p
                  case Left(error) =>
                    throw new IllegalStateException(
                      s"fixture bug: isolate failed: $error"
                    )
              }.orDie
              verdict <- ZIO
                .attempt(
                  IsolatedRerun
                    .requireIsolatedTarget(repairedDocument, ws, copy)
                )
                .orDie
              before <- snapshotOf(ws)
              untouched <- verifyUntouched(ws, before)
            yield assertTrue(
              copy != ws,
              verdict == Right(()),
              untouched == Right(())
            )
          }
        }
      },
      test(
        "TAX-03: a ReadOnly record (fs_read responses only) still needs no isolation"
      ) {
        live {
          ZIO.scoped {
            for
              ws <- freshWorkspace
              parent <- freshParent
              verdict <- ZIO
                .attempt(
                  IsolatedRerun
                    .requireIsolatedTarget(ReplayHarness.document, ws, ws)
                )
                .orDie
            yield assertTrue(
              IsolatedRerun.mutatingTools(ReplayHarness.document).isEmpty,
              verdict == Right(())
            )
          }
        }
      },
      test(
        "TAX-02-04 (RAI-268): mutatingTools classifies fs_patch_multi and fs_delete from the responses (stale ReadOnly annotations ignored)"
      ) {
        val doc = rai260MutationDocument
        val annotations = doc.data.values.collect {
          case t: NodeData.ToolCallData => t.tool -> t.recovery
        }.toMap
        assertTrue(
          IsolatedRerun.mutatingTools(doc) ==
            Vector("fs_delete", "fs_patch_multi"),
          annotations.get("fs_read") == Some("ReadOnly")
        )
      },
      test(
        "TAX-02-05 (RAI-268): replaying the fs_patch_multi/fs_delete record in place is refused typed BEFORE any execution"
      ) {
        live {
          ZIO.scoped {
            for
              ws <- freshWorkspace
              parent <- freshParent
              before <- snapshotOf(ws)
              alias = ws.resolve("..").resolve(ws.getFileName)
              verdict <- ZIO
                .attempt(
                  IsolatedRerun
                    .requireIsolatedTarget(rai260MutationDocument, ws, alias)
                )
                .orDie
              untouched <- verifyUntouched(ws, before)
              noDirt <- ZIO.attempt(listDirs(parent).isEmpty).orDie
            yield assertTrue(
              verdict == Left(
                RerunError.MutatingUnisolated(
                  Vector("fs_delete", "fs_patch_multi")
                )
              ),
              untouched == Right(()),
              noDirt
            )
          }
        }
      },
      test(
        "TAX-02-06 (RAI-268): the fs_patch_multi/fs_delete record against a faithful isolated copy proceeds"
      ) {
        live {
          ZIO.scoped {
            for
              ws <- freshWorkspace
              parent <- freshParent
              copy <- ZIO.attempt {
                WorkspaceIsolation.isolate(ws, parent) match
                  case Right(p) => p
                  case Left(error) =>
                    throw new IllegalStateException(
                      s"fixture bug: isolate failed: $error"
                    )
              }.orDie
              verdict <- ZIO
                .attempt(
                  IsolatedRerun
                    .requireIsolatedTarget(rai260MutationDocument, ws, copy)
                )
                .orDie
              before <- snapshotOf(ws)
              untouched <- verifyUntouched(ws, before)
            yield assertTrue(
              copy != ws,
              verdict == Right(()),
              untouched == Right(())
            )
          }
        }
      },
      test(
        "TAX-03 (RAI-268): exact-name matching at the guard — case variants and near-misses in responses stay ReadOnly, no isolation demanded"
      ) {
        live {
          ZIO.scoped {
            for
              ws <- freshWorkspace
              parent <- freshParent
              verdict <- ZIO
                .attempt(
                  IsolatedRerun.requireIsolatedTarget(
                    rai260NameVariantDocument,
                    ws,
                    ws
                  )
                )
                .orDie
            yield assertTrue(
              IsolatedRerun.mutatingTools(rai260NameVariantDocument).isEmpty,
              verdict == Right(())
            )
          }
        }
      }
    )

end RecoveryTaxonomyGuardSpec
