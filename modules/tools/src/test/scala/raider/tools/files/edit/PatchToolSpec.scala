package raider.tools.files.edit

import raider.core.RaiderError
import raider.tools.files.read.Workspace
import zio.{Scope, ZIO}
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** fs_patch contract (nightly Phase 2.4): unified-diff application with sha
  * provenance, workspace containment, atomic write. Pure engine tested
  * separately from the workspace-backed tool.
  */
object PatchToolSpec extends ZIOSpecDefault:

  private def withWorkspace[A](
      f: (Workspace, java.nio.file.Path) => ZIO[Any, RaiderError, A]
  ): ZIO[Any, RaiderError, A] =
    ZIO
      .acquireReleaseWith(
        ZIO
          .attempt(Files.createTempDirectory("raider-patch-test"))
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

  private val Original =
    """package demo
      |
      |object Calc:
      |  def add(a: Int, b: Int): Int = a + b
      |  def sub(a: Int, b: Int): Int = a - b
      |""".stripMargin

  private val SimplePatch =
    """--- a/Calc.scala
      |+++ b/Calc.scala
      |@@ -1,5 +1,6 @@
      | package demo
      |
      | object Calc:
      |+  def mul(a: Int, b: Int): Int = a * b
      |   def add(a: Int, b: Int): Int = a + b
      |   def sub(a: Int, b: Int): Int = a - b
      |""".stripMargin

  private def failuresOf(exit: zio.Exit[RaiderError, ?]): Option[RaiderError] =
    exit match
      case zio.Exit.Failure(cause) => cause.failures.headOption
      case _                       => None

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("PatchTool")(
      suite("UnifiedDiff.parse")(
        test("parses hunk headers and +/-/context lines") {
          val parsed = UnifiedDiff.parse(SimplePatch)
          val h = parsed.toOption.get.head
          assertTrue(
            parsed.isRight,
            parsed.toOption.get.size == 1,
            h.oldStart == 1,
            h.header.contains("@@ -1"),
            h.expected ==
              List(
                "package demo",
                "",
                "object Calc:",
                "  def add(a: Int, b: Int): Int = a + b",
                "  def sub(a: Int, b: Int): Int = a - b"
              ),
            h.replacement ==
              List(
                "package demo",
                "",
                "object Calc:",
                "  def mul(a: Int, b: Int): Int = a * b",
                "  def add(a: Int, b: Int): Int = a + b",
                "  def sub(a: Int, b: Int): Int = a - b"
              )
          )
        },
        test("malformed hunk body is a parse error") {
          assertTrue(
            UnifiedDiff.parse("@@ -1,2 +1,2 @@\n??garbage").isLeft
          )
        }
      ),
      suite("fs_patch apply")(
        test("applies a simple patch; result carries old/new sha") {
          withWorkspace { (ws, dir) =>
            val sha = write(dir, "Calc.scala", Original)
            val tool = new PatchTool(ws)
            tool.apply("Calc.scala", SimplePatch, sha).map { result =>
              val out =
                new String(Files.readAllBytes(dir.resolve("Calc.scala")), UTF_8)
              assertTrue(
                result.oldSha256 == sha,
                result.newSha256 != sha,
                result.created == false,
                out.contains("def mul(a: Int, b: Int): Int = a * b")
              )
            }
          }
        },
        test("multi-hunk patch applies both hunks") {
          withWorkspace { (ws, dir) =>
            val sha = write(dir, "Calc.scala", Original)
            val tool = new PatchTool(ws)
            val multi =
              """@@ -2,3 +2,3 @@
                |
                | object Calc:
                |-  def add(a: Int, b: Int): Int = a + b
                |+  def add(a: Int, b: Int): Int = a + b // sum
                |@@ -5,2 +5,3 @@
                |   def sub(a: Int, b: Int): Int = a - b
                |+  def div(a: Int, b: Int): Int = a / b
                |""".stripMargin
            tool.apply("Calc.scala", multi, sha).map { result =>
              val out =
                new String(Files.readAllBytes(dir.resolve("Calc.scala")), UTF_8)
              assertTrue(
                out.contains("def add(a: Int, b: Int): Int = a + b // sum"),
                out.contains("def div(a: Int, b: Int): Int = a / b"),
                result.newSha256 != sha
              )
            }
          }
        },
        test("context mismatch fails with a typed error, file untouched") {
          withWorkspace { (ws, dir) =>
            val sha = write(dir, "Calc.scala", Original)
            val before = Files.readAllBytes(dir.resolve("Calc.scala"))
            val tool = new PatchTool(ws)
            val bad =
              """@@ -1,2 +1,2 @@
                | package demo
                |-THIS LINE IS NOT IN THE FILE
                |+replaced
                |""".stripMargin
            for
              res <- tool.apply("Calc.scala", bad, sha).exit
              // eager read in the effect chain: zio-test assertions are lazy,
              // but the temp dir is deleted by the release finalizer before
              // they evaluate
              after <- ZIO
                .attempt(Files.readAllBytes(dir.resolve("Calc.scala")))
                .orDie
            yield
              val err = failuresOf(res)
              assertTrue(
                err.exists(_.isInstanceOf[RaiderError.InputValidation]),
                err.exists(_.detail.contains("context mismatch")),
                after.sameElements(before)
              )
          }
        },
        test("stale sha is refused (ToolDenied), same as fs_edit") {
          withWorkspace { (ws, dir) =>
            write(dir, "Calc.scala", Original)
            val tool = new PatchTool(ws)
            tool
              .apply("Calc.scala", SimplePatch, "deadbeef" + "0" * 56)
              .exit
              .map: res =>
                val err = failuresOf(res)
                assertTrue(
                  err.exists(_.isInstanceOf[RaiderError.ToolDenied]),
                  err.exists(_.detail.contains("STALE PATCH"))
                )
          }
        },
        test("empty expected sha is an input validation error") {
          withWorkspace { (ws, dir) =>
            write(dir, "Calc.scala", Original)
            val tool = new PatchTool(ws)
            tool
              .apply("Calc.scala", SimplePatch, "")
              .exit
              .map: res =>
                val err = failuresOf(res)
                assertTrue(
                  err.exists(_.isInstanceOf[RaiderError.InputValidation]),
                  err.exists(_.detail.contains("expected_sha256"))
                )
          }
        },
        test("path escaping the workspace is denied") {
          withWorkspace { (ws, _) =>
            val tool = new PatchTool(ws)
            tool
              .apply("../outside.txt", SimplePatch, "a" * 64)
              .exit
              .map: res =>
                val err = failuresOf(res)
                assertTrue(err.exists(_.isInstanceOf[RaiderError.ToolDenied]))
          }
        },
        test("trailing newline property is preserved") {
          withWorkspace { (ws, dir) =>
            val sha = write(dir, "Calc.scala", Original) // ends with \n
            val tool = new PatchTool(ws)
            tool.apply("Calc.scala", SimplePatch, sha).map { _ =>
              val out =
                new String(Files.readAllBytes(dir.resolve("Calc.scala")), UTF_8)
              assertTrue(out.endsWith("\n"))
            }
          }
        }
      )
    )
