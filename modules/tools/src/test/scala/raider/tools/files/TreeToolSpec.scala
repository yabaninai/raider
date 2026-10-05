package raider.tools.files

import raider.core.RaiderError
import raider.tools.files.read.Workspace
import zio.test.*
import zio.{Scope, ZIO}

import java.nio.file.Files

/** fs_tree contract (nightly Phase 2.5): bounded workspace tree as data —
  * depth-capped, entry-count capped, skip-listed dirs, optional glob filter,
  * workspace containment.
  */
object TreeToolSpec extends ZIOSpecDefault:

  private def withWorkspace[A](
      f: (Workspace, java.nio.file.Path) => ZIO[Any, RaiderError, A]
  ): ZIO[Any, RaiderError, A] =
    ZIO
      .acquireReleaseWith(
        ZIO
          .attempt(Files.createTempDirectory("raider-tree-test"))
          .mapError(e => RaiderError.ToolFailed(e.getMessage.take(80)))
      )(dir => ZIO.attempt(Delete.recursive(dir)).catchAll(_ => ZIO.unit)) {
        dir =>
          Workspace.make(dir.toString).flatMap(ws => f(ws, dir))
      }

  private object Delete:

    def recursive(p: java.nio.file.Path): Unit =
      import scala.jdk.CollectionConverters.*
      if Files.isDirectory(p) then
        Files.list(p).iterator().asScala.foreach(recursive)
      Files.deleteIfExists(p)
      ()

  /** Layout: src/Main.scala, src/util/Help.scala docs/readme.md
    * .git/hidden_object (skipped) target/junk.class (skipped)
    * node_modules/pkg.js (skipped)
    */
  private def layout(dir: java.nio.file.Path): Unit =
    Files.createDirectories(dir.resolve("src/util"))
    Files.createDirectories(dir.resolve("docs"))
    Files.createDirectories(dir.resolve(".git"))
    Files.createDirectories(dir.resolve("target"))
    Files.createDirectories(dir.resolve("node_modules"))
    Files.writeString(dir.resolve("src/Main.scala"), "object Main")
    Files.writeString(dir.resolve("src/util/Help.scala"), "object Help")
    Files.writeString(dir.resolve("docs/readme.md"), "# doc")
    Files.writeString(dir.resolve(".git/hidden_object"), "x")
    Files.writeString(dir.resolve("target/junk.class"), "x")
    Files.writeString(dir.resolve("node_modules/pkg.js"), "x")

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("TreeTool")(
      test(
        "lists files with relative paths, skipping .git/target/node_modules"
      ) {
        withWorkspace { (ws, dir) =>
          layout(dir)
          val tool = new TreeTool(ws)
          tool
            .tree(depth = 3, glob = None)
            .map: r =>
              val paths = r.entries.map(_.path)
              assertTrue(
                paths.contains("src/Main.scala"),
                paths.contains("src/util/Help.scala"),
                paths.contains("docs/readme.md"),
                !paths.exists(_.startsWith(".git")),
                !paths.exists(_.startsWith("target")),
                !paths.exists(_.startsWith("node_modules")),
                r.truncated == false,
                r.total == 3
              )
        }
      },
      test("glob filter keeps only matching files") {
        withWorkspace { (ws, dir) =>
          layout(dir)
          val tool = new TreeTool(ws)
          tool
            .tree(depth = 5, glob = Some("*.scala"))
            .map: r =>
              val paths = r.entries.map(_.path)
              assertTrue(
                paths.sorted == List("src/Main.scala", "src/util/Help.scala"),
                r.total == 2
              )
        }
      },
      test("depth cap bounds traversal") {
        withWorkspace { (ws, dir) =>
          Files.createDirectories(dir.resolve("a/b/c/d"))
          Files.writeString(dir.resolve("a/b/c/d/deep.txt"), "x")
          Files.writeString(dir.resolve("a/shallow.txt"), "x")
          val tool = new TreeTool(ws)
          tool
            .tree(depth = 2, glob = None)
            .map: r =>
              val paths = r.entries.map(_.path)
              assertTrue(
                paths.contains("a/shallow.txt"),
                !paths.exists(_.contains("deep.txt"))
              )
        }
      },
      test("depth is clamped to max 5") {
        withWorkspace { (ws, _) =>
          val tool = new TreeTool(ws)
          tool
            .tree(depth = 99, glob = None)
            .map: r =>
              assertTrue(r.depthUsed == 5)
        }
      },
      test("entry cap truncates and reports truncation") {
        withWorkspace { (ws, dir) =>
          (1 to 12).foreach: i =>
            Files.writeString(dir.resolve(s"f$i.txt"), "x")
          val tool = new TreeTool(ws)
          tool
            .tree(depth = 1, glob = None, maxEntries = 5)
            .map: r =>
              assertTrue(
                r.entries.size == 5,
                r.truncated == true,
                r.total == 12
              )
        }
      },
      test("symlink escaping the workspace is refused") {
        withWorkspace { (ws, dir) =>
          Files.createDirectories(dir.resolve("src"))
          Files.writeString(dir.resolve("src/real.txt"), "x")
          val outside =
            Files.createTempFile("raider-tree-outside", ".txt")
          Files.write(outside, "secret".getBytes)
          Files.createSymbolicLink(
            dir.resolve("src/escape.txt"),
            outside
          )
          val tool = new TreeTool(ws)
          tool
            .tree(depth = 3, glob = None)
            .map: r =>
              val paths = r.entries.map(_.path)
              assertTrue(!paths.contains("src/escape.txt"))
        }
      }
    )
