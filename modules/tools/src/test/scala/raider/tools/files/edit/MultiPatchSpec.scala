package raider.tools.files.edit

import raider.core.RaiderError
import raider.tools.files.read.Workspace
import zio.{Scope, ZIO}
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** RAI-067 contract: multi-file unified diff with all-or-nothing apply and an
  * honest partial-failure report (applied/failed/rolledBack/staged).
  * PATCH2-01..03; the single-file fs_patch path stays covered by PatchToolSpec.
  */
object MultiPatchSpec extends ZIOSpecDefault:

  private def withWorkspace[A](
      f: (Workspace, java.nio.file.Path) => ZIO[Any, RaiderError, A]
  ): ZIO[Any, RaiderError, A] =
    ZIO
      .acquireReleaseWith(
        ZIO
          .attempt(Files.createTempDirectory("raider-multipatch-test"))
          .mapError(e => RaiderError.ToolFailed(e.getMessage.take(80)))
      )(dir =>
        ZIO
          .attempt(Delete.recursive(dir))
          .catchAll(_ => ZIO.unit)
      ) { dir =>
        Workspace.make(dir.toString).flatMap(ws => f(ws, dir))
      }

  private object Delete:

    def recursive(p: java.nio.file.Path): Unit =
      import scala.jdk.CollectionConverters.*
      if Files.isDirectory(p) then
        Files.list(p).iterator().asScala.foreach(recursive)
      Files.deleteIfExists(p)
      ()

  private def write(
      dir: java.nio.file.Path,
      name: String,
      content: String
  ): String =
    Files.writeString(dir.resolve(name), content, UTF_8)
    EditTool.sha256OfFile(dir.resolve(name)).toOption.get

  private def readBytes(dir: java.nio.file.Path, name: String): Array[Byte] =
    if Files.exists(dir.resolve(name)) then
      Files.readAllBytes(dir.resolve(name))
    else Array.emptyByteArray

  private def sameBytes(a: Array[Byte], b: Array[Byte]): Boolean =
    java.util.Arrays.equals(a, b)

  /** A modify-section hunk replacing the line `editFrom` with `editTo`. */
  private def modPatch(
      name: String,
      lines: List[String],
      editFrom: String,
      editTo: String
  ): String =
    val n = lines.size
    val body = lines
      .map: l =>
        if l == editFrom then s"-$l\n+$editTo" else s" $l"
      .mkString("\n")
    s"""diff --git a/$name b/$name
       |index 0000000..1111111 100644
       |--- a/$name
       |+++ b/$name
       |@@ -1,$n +1,$n @@
       |$body
       |""".stripMargin

  private val LinesA = List("alpha-1", "alpha-2")
  private val LinesB = List("beta-1", "beta-2")
  private val LinesC = List("gamma-1", "gamma-2")
  private val LinesD = List("delta-1", "delta-2")
  private val LinesGh = List("ghost-1", "ghost-2")

  private def failOf(r: MultiPatchResult, file: String): Option[FileFailure] =
    r.failed.find(_.file == file)

  private def failuresOf(exit: zio.Exit[RaiderError, ?]): Option[RaiderError] =
    exit match
      case zio.Exit.Failure(cause) => cause.failures.headOption
      case _                       => None

  private val A = "alpha-1\nalpha-2\n"
  private val B = "beta-1\nbeta-2\n"
  private val C = "gamma-1\ngamma-2\n"

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("MultiPatch RAI-067")(
      suite("PATCH2-01: multi-file apply + rollback semantics")(
        test("applies 3 files; report carries per-file results and new shas") {
          withWorkspace { (ws, dir) =>
            val shas = Map(
              "A.txt" -> write(dir, "A.txt", A),
              "B.txt" -> write(dir, "B.txt", B),
              "C.txt" -> write(dir, "C.txt", C)
            )
            val diff =
              modPatch("A.txt", LinesA, "alpha-2", "alpha-2-edited") +
                modPatch("B.txt", LinesB, "beta-2", "beta-2-edited") +
                modPatch("C.txt", LinesC, "gamma-2", "gamma-2-edited")
            new PatchTool(ws).applyAll(diff, shas).map { r =>
              val a = new String(readBytes(dir, "A.txt"), UTF_8)
              val b = new String(readBytes(dir, "B.txt"), UTF_8)
              val c = new String(readBytes(dir, "C.txt"), UTF_8)
              assertTrue(
                r.failed.isEmpty,
                !r.rolledBack,
                r.staged.isEmpty,
                r.applied.map(_.path) == List("A.txt", "B.txt", "C.txt"),
                r.applied.forall(e => e.newSha256 != e.oldSha256),
                a.contains("alpha-2-edited") && a.contains("alpha-1\n"),
                b.contains("beta-2-edited"),
                c.contains("gamma-2-edited")
              )
            }
          }
        },
        test(
          "failure on the 2nd of 3 files: disk untouched, failed[] has file+reason, staged names the discarded work"
        ) {
          withWorkspace { (ws, dir) =>
            val shaA = write(dir, "A.txt", A)
            val shaB = write(dir, "B.txt", B)
            val shaC = write(dir, "C.txt", C)
            val diff =
              modPatch("A.txt", LinesA, "alpha-2", "alpha-2-edited") +
                modPatch("B.txt", LinesB, "beta-2", "beta-2-edited") +
                modPatch("C.txt", LinesC, "gamma-2", "gamma-2-edited")
            // B's provenance is stale: the whole patch must be refused
            val shas = Map(
              "A.txt" -> shaA,
              "B.txt" -> ("deadbeef" + "0" * 56),
              "C.txt" -> shaC
            )
            val before =
              (
                readBytes(dir, "A.txt"),
                readBytes(dir, "B.txt"),
                readBytes(dir, "C.txt")
              )
            new PatchTool(ws).applyAll(diff, shas).map { r =>
              val after =
                (
                  readBytes(dir, "A.txt"),
                  readBytes(dir, "B.txt"),
                  readBytes(dir, "C.txt")
                )
              val failB = failOf(r, "B.txt")
              val unchanged =
                sameBytes(before._1, after._1) &&
                  sameBytes(before._2, after._2) &&
                  sameBytes(before._3, after._3)
              assertTrue(
                r.applied.isEmpty,
                r.rolledBack,
                r.staged == List("A.txt", "C.txt"),
                failB.map(_.code).contains("RA-TOOLDENY"),
                failB.exists(_.reason.contains("STALE PATCH")),
                unchanged
              )
            }
          }
        }
      ),
      suite("PATCH2-02: typed per-file errors, nothing half-applied")(
        test("sha mismatch is ToolDenied, disk clean") {
          withWorkspace { (ws, dir) =>
            write(dir, "A.txt", A)
            write(dir, "B.txt", B)
            val diff =
              modPatch("A.txt", LinesA, "alpha-2", "alpha-2-edited") +
                modPatch("B.txt", LinesB, "beta-2", "beta-2-edited")
            val shas = Map(
              "A.txt" -> ("deadbeef" + "0" * 56),
              "B.txt" -> ("deadbeef" + "1" * 56)
            )
            val before = (readBytes(dir, "A.txt"), readBytes(dir, "B.txt"))
            new PatchTool(ws).applyAll(diff, shas).map { r =>
              val after = (readBytes(dir, "A.txt"), readBytes(dir, "B.txt"))
              val unchanged =
                sameBytes(before._1, after._1) && sameBytes(before._2, after._2)
              assertTrue(
                r.applied.isEmpty,
                r.rolledBack,
                r.staged.isEmpty,
                failOf(r, "A.txt").map(_.code).contains("RA-TOOLDENY"),
                failOf(r, "B.txt").map(_.code).contains("RA-TOOLDENY"),
                unchanged
              )
            }
          }
        },
        test(
          "malformed hunk body is a typed InputValidation for that file, disk clean"
        ) {
          withWorkspace { (ws, dir) =>
            write(dir, "A.txt", A)
            write(dir, "B.txt", B)
            val broken =
              """diff --git a/B.txt b/B.txt
                |--- a/B.txt
                |+++ b/B.txt
                |@@ -1,2 +1,2 @@
                | beta-1
                |??garbage-not-a-diff-line
                |+replaced
                |""".stripMargin
            val diff =
              modPatch("A.txt", LinesA, "alpha-2", "alpha-2-edited") + broken
            val shas = Map(
              "A.txt" -> EditTool
                .sha256OfFile(dir.resolve("A.txt"))
                .toOption
                .get,
              "B.txt" -> EditTool
                .sha256OfFile(dir.resolve("B.txt"))
                .toOption
                .get
            )
            val before = (readBytes(dir, "A.txt"), readBytes(dir, "B.txt"))
            new PatchTool(ws).applyAll(diff, shas).map { r =>
              val after = (readBytes(dir, "A.txt"), readBytes(dir, "B.txt"))
              val unchanged =
                sameBytes(before._1, after._1) && sameBytes(before._2, after._2)
              assertTrue(
                r.applied.isEmpty,
                r.rolledBack,
                r.staged == List("A.txt"),
                failOf(r, "B.txt").map(_.code).contains("RA-INP"),
                failOf(r, "B.txt")
                  .exists(_.reason.contains("malformed section")),
                unchanged
              )
            }
          }
        },
        test("missing target file is a typed ToolFailed, disk clean") {
          withWorkspace { (ws, dir) =>
            write(dir, "A.txt", A)
            val diff =
              modPatch("Ghost.txt", LinesGh, "ghost-2", "ghost-2-edited")
            val shas = Map("Ghost.txt" -> "0" * 64)
            val beforeA = readBytes(dir, "A.txt")
            new PatchTool(ws).applyAll(diff, shas).map { r =>
              val afterA = readBytes(dir, "A.txt")
              assertTrue(
                r.applied.isEmpty,
                r.rolledBack,
                failOf(r, "Ghost.txt").map(_.code).contains("RA-TOOLFAIL"),
                failOf(r, "Ghost.txt")
                  .exists(_.reason.contains("file not found")),
                sameBytes(beforeA, afterA)
              )
            }
          }
        },
        test("path escaping the workspace is ToolDenied per file") {
          withWorkspace { (ws, dir) =>
            write(dir, "A.txt", A)
            val outside =
              """diff --git a/../escape.txt b/../escape.txt
                |--- a/../escape.txt
                |+++ b/../escape.txt
                |@@ -1,1 +1,1 @@
                |-secret
                |+pwned
                |""".stripMargin
            val shas = Map(
              "A.txt" -> EditTool
                .sha256OfFile(dir.resolve("A.txt"))
                .toOption
                .get,
              "../escape.txt" -> "0" * 64
            )
            new PatchTool(ws).applyAll(outside, shas).map { r =>
              assertTrue(
                r.applied.isEmpty,
                r.rolledBack,
                failOf(r, "../escape.txt").map(_.code).contains("RA-TOOLDENY")
              )
            }
          }
        },
        test("diff without 'diff --git' sections is a global InputValidation") {
          withWorkspace { (ws, dir) =>
            write(dir, "A.txt", A)
            val legacy =
              """--- a/A.txt
                |+++ b/A.txt
                |@@ -1,2 +1,2 @@
                | alpha-1
                |-alpha-2
                |+edited
                |""".stripMargin
            new PatchTool(ws)
              .applyAll(legacy, Map("A.txt" -> "0" * 64))
              .exit
              .map: res =>
                assertTrue(
                  failuresOf(res)
                    .exists(_.isInstanceOf[RaiderError.InputValidation]),
                  failuresOf(res)
                    .exists(_.detail.contains("no 'diff --git' sections"))
                )
          }
        }
      ),
      suite("PATCH2-03: parallel applies on disjoint files")(
        test(
          "two disjoint applyAll run concurrently; both land, no corruption"
        ) {
          withWorkspace { (ws, dir) =>
            val shas = Map(
              "A.txt" -> write(dir, "A.txt", A),
              "B.txt" -> write(dir, "B.txt", B),
              "C.txt" -> write(dir, "C.txt", C),
              "D.txt" -> write(dir, "D.txt", "delta-1\ndelta-2\n")
            )
            val diff1 =
              modPatch("A.txt", LinesA, "alpha-2", "alpha-2-edited") +
                modPatch("B.txt", LinesB, "beta-2", "beta-2-edited")
            val diff2 =
              modPatch("C.txt", LinesC, "gamma-2", "gamma-2-edited") +
                modPatch("D.txt", LinesD, "delta-2", "delta-2-edited")
            val tool = new PatchTool(ws)
            ZIO
              .collectAllPar(
                List(tool.applyAll(diff1, shas), tool.applyAll(diff2, shas))
              )
              .map { results =>
                val (r1, r2) = (results(0), results(1))
                val a = new String(readBytes(dir, "A.txt"), UTF_8)
                val d = new String(readBytes(dir, "D.txt"), UTF_8)
                assertTrue(
                  r1.failed.isEmpty && !r1.rolledBack && r1.applied.size == 2,
                  r2.failed.isEmpty && !r2.rolledBack && r2.applied.size == 2,
                  a.contains("alpha-2-edited"),
                  d.contains("delta-2-edited")
                )
              }
          }
        },
        test(
          "escaping target is refused while a disjoint valid apply succeeds in parallel"
        ) {
          withWorkspace { (ws, dir) =>
            val shas = Map(
              "A.txt" -> write(dir, "A.txt", A),
              "../escape.txt" -> "0" * 64
            )
            val escape =
              """diff --git a/../escape.txt b/../escape.txt
                |--- a/../escape.txt
                |+++ b/../escape.txt
                |@@ -1,1 +1,1 @@
                |-secret
                |+pwned
                |""".stripMargin
            val tool = new PatchTool(ws)
            ZIO
              .collectAllPar(
                List(
                  tool.applyAll(
                    modPatch("A.txt", LinesA, "alpha-2", "alpha-2-edited"),
                    Map("A.txt" -> shas("A.txt"))
                  ),
                  tool.applyAll(escape, shas)
                )
              )
              .map { results =>
                val (ok, denied) = (results(0), results(1))
                val a = new String(readBytes(dir, "A.txt"), UTF_8)
                assertTrue(
                  ok.applied.size == 1 && ok.failed.isEmpty,
                  denied.applied.isEmpty && denied.rolledBack,
                  failOf(denied, "../escape.txt")
                    .map(_.code)
                    .contains("RA-TOOLDENY"),
                  a.contains("alpha-2-edited")
                )
              }
          }
        }
      ),
      suite("RAI-067: create and delete sections")(
        test("create + delete + modify in one diff apply atomically") {
          withWorkspace { (ws, dir) =>
            val shaKeep = write(dir, "keep.txt", "keep-1\nkeep-2\n")
            val shaGone = write(dir, "gone.txt", "gone-1\ngone-2\ngone-3\n")
            val diff =
              """diff --git a/fresh.txt b/fresh.txt
                |new file mode 100644
                |index 0000000..aaaaaaa
                |--- /dev/null
                |+++ b/fresh.txt
                |@@ -0,0 +1,2 @@
                |+fresh-1
                |+fresh-2
                |diff --git a/gone.txt b/gone.txt
                |deleted file mode 100644
                |index bbbbbbb..0000000
                |--- a/gone.txt
                |+++ /dev/null
                |@@ -1,3 +0,0 @@
                |-gone-1
                |-gone-2
                |-gone-3
                |diff --git a/keep.txt b/keep.txt
                |index cccccc..dddddd 100644
                |--- a/keep.txt
                |+++ b/keep.txt
                |@@ -1,2 +1,2 @@
                | keep-1
                |-keep-2
                |+keep-2-edited
                |""".stripMargin
            val shas = Map("gone.txt" -> shaGone, "keep.txt" -> shaKeep)
            new PatchTool(ws).applyAll(diff, shas).map { r =>
              val fresh = new String(readBytes(dir, "fresh.txt"), UTF_8)
              val keep = new String(readBytes(dir, "keep.txt"), UTF_8)
              val goneGone = !Files.exists(dir.resolve("gone.txt"))
              val freshRes = r.applied.find(_.path == "fresh.txt")
              val goneRes = r.applied.find(_.path == "gone.txt")
              assertTrue(
                r.failed.isEmpty,
                !r.rolledBack,
                r.applied.size == 3,
                freshRes.exists(_.created),
                goneRes.exists(_.newSha256.isEmpty),
                goneGone,
                fresh == "fresh-1\nfresh-2\n",
                keep.contains("keep-2-edited")
              )
            }
          }
        },
        test("delete patch that leaves lines is a typed InputValidation") {
          withWorkspace { (ws, dir) =>
            val shaGone = write(dir, "gone.txt", "gone-1\ngone-2\ngone-3\n")
            val partialDelete =
              """diff --git a/gone.txt b/gone.txt
                |deleted file mode 100644
                |--- a/gone.txt
                |+++ /dev/null
                |@@ -1,3 +0,0 @@
                |-gone-1
                |-gone-2
                |""".stripMargin
            new PatchTool(ws)
              .applyAll(partialDelete, Map("gone.txt" -> shaGone))
              .map { r =>
                val stillThere = Files.exists(dir.resolve("gone.txt"))
                assertTrue(
                  r.applied.isEmpty,
                  r.rolledBack,
                  failOf(r, "gone.txt").map(_.code).contains("RA-INP"),
                  failOf(r, "gone.txt")
                    .exists(_.reason.contains("refusing partial deletion")),
                  stillThere
                )
              }
          }
        },
        test("new-file patch against an existing file is ToolDenied") {
          withWorkspace { (ws, dir) =>
            write(dir, "fresh.txt", "already-here\n")
            val create =
              """diff --git a/fresh.txt b/fresh.txt
                |new file mode 100644
                |--- /dev/null
                |+++ b/fresh.txt
                |@@ -0,0 +1,1 @@
                |+fresh-1
                |""".stripMargin
            new PatchTool(ws)
              .applyAll(create, Map.empty)
              .map { r =>
                val fresh = new String(readBytes(dir, "fresh.txt"), UTF_8)
                assertTrue(
                  r.applied.isEmpty,
                  r.rolledBack,
                  r.failed.nonEmpty,
                  failOf(r, "fresh.txt").map(_.code).contains("RA-TOOLDENY"),
                  failOf(r, "fresh.txt")
                    .exists(_.reason.contains("already exists")),
                  fresh == "already-here\n"
                )
              }
          }
        }
      ),
      suite("RAI-067: global structural validation")(
        test("duplicate target across sections is a global InputValidation") {
          withWorkspace { (ws, dir) =>
            write(dir, "A.txt", A)
            val diff =
              modPatch("A.txt", LinesA, "alpha-2", "alpha-2-edited") +
                modPatch("A.txt", LinesA, "alpha-2", "alpha-2-again")
            new PatchTool(ws)
              .applyAll(diff, Map("A.txt" -> "0" * 64))
              .exit
              .map: res =>
                assertTrue(
                  failuresOf(res)
                    .exists(_.isInstanceOf[RaiderError.InputValidation]),
                  failuresOf(res).exists(_.detail.contains("duplicate target"))
                )
          }
        },
        test("empty diff is an InputValidation") {
          withWorkspace { (ws, _) =>
            new PatchTool(ws)
              .applyAll("   ", Map.empty)
              .exit
              .map: res =>
                assertTrue(
                  failuresOf(res)
                    .exists(_.isInstanceOf[RaiderError.InputValidation])
                )
          }
        }
      )
    )
