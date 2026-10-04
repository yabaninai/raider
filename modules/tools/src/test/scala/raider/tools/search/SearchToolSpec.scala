package raider.tools.search

import raider.core.RaiderError
import raider.tools.files.read.{ReadTool, Workspace}
import zio.test.{live, *}
import zio.{Exit as ZExit, ZIO}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** RAI-014.a SEARCH-01..03: bounded content search without shell; glob filter;
  * empty query defined; binary skipped; declared truncation. */
object SearchToolSpec extends ZIOSpecDefault:

  private def tempWorkspace(): Path = Files.createTempDirectory("raider-search-ws")

  private def write(root: Path, rel: String, content: String): Path =
    val p = root.resolve(rel)
    Files.createDirectories(p.getParent)
    Files.write(p, content.getBytes(StandardCharsets.UTF_8))
    p

  def spec = suite("SearchTool RAI-014.a")(
    test("SEARCH-01: substring search returns file+line+snippet in walk order") {
      ZIO.scoped {
        for
          root <- ZIO.attemptBlocking(tempWorkspace()).orDie
          _ <- ZIO.attemptBlocking {
                 write(root, "src/parser.scala", "def parse(): Int =\n  parser.parse(input)\n")
                 write(root, "docs/notes.md", "the parse function is pure\n")
                 write(root, "src/util.scala", "no match here\n")
               }.orDie
          tool <- Workspace.make(root.toString).map(new SearchTool(_))
          res  <- tool.search("parse")
        yield assertTrue(
          res.matches.size == 3,
          res.matches.count(_.file == "src/parser.scala") == 2,
          res.matches.exists(m => m.file == "docs/notes.md" && m.lineNumber == 1),
          res.matches.forall(_.line.contains("parse")),
          !res.truncated,
          res.filesScanned == 3
        )
      }
    },
    test("SEARCH-01: empty query is a DEFINED empty result (documented)") {
      ZIO.scoped {
        for
          root <- ZIO.attemptBlocking(tempWorkspace()).orDie
          _    <- ZIO.attemptBlocking(write(root, "a.txt", "content")).orDie
          tool <- Workspace.make(root.toString).map(new SearchTool(_))
          res  <- tool.search("")
        yield assertTrue(res.matches.isEmpty, !res.truncated, res.filesScanned == 0)
      }
    },
    test("SEARCH-02: glob filter restricts scanned files") {
      ZIO.scoped {
        for
          root <- ZIO.attemptBlocking(tempWorkspace()).orDie
          _ <- ZIO.attemptBlocking {
                 write(root, "src/a.scala", "needle here\n")
                 write(root, "src/b.md", "needle there\n")
               }.orDie
          tool <- Workspace.make(root.toString).map(new SearchTool(_))
          res  <- tool.search("needle", glob = Some("*.scala"))
        yield assertTrue(
          res.matches.map(_.file) == List("src/a.scala"),
          res.filesScanned == 1 // b.md not scanned due to glob
        )
      }
    },
    test("SEARCH-02: match bound truncates with a declared flag; binary skipped") {
      ZIO.scoped {
        for
          root <- ZIO.attemptBlocking(tempWorkspace()).orDie
          _ <- ZIO.attemptBlocking {
                 write(root, "many.txt", (1 to 20).map(i => s"hit $i").mkString("\n"))
                 Files.write(root.resolve("bin.dat"), Array[Byte](9, 0, 8))
               }.orDie
          tool <- Workspace.make(root.toString).map(new SearchTool(_))
          res  <- tool.search("hit", maxMatches = 5)
        yield assertTrue(
          res.matches.size == 5,
          res.truncated,
          res.matches.forall(m => !m.file.endsWith("bin.dat")) // binary never scanned
        )
      }
    },
    test("SEARCH-02: maxMatches beyond the hard bound is a typed error (no clamp)") {
      ZIO.scoped {
        for
          root <- ZIO.attemptBlocking(tempWorkspace()).orDie
          tool <- Workspace.make(root.toString).map(new SearchTool(_))
          res  <- tool.search("x", maxMatches = SearchTool.HardMaxMatches + 1).exit
        yield assertTrue(
          res.isFailure,
          res match
            case ZExit.Failure(cause) =>
              cause.failures.headOption.map(_.code).contains("RA-INP")
            case _ => false
        )
      }
    }
  )
