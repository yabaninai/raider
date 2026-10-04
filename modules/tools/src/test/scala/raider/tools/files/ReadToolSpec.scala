package raider.tools.files

import raider.core.RaiderError
import raider.tools.files.read.{ReadResult, ReadTool, Workspace}
import zio.test.{live, *}
import zio.{Exit as ZExit, ZIO}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

/** RAI-014.a READ-01..03: bounded ranged read, workspace containment
  * (realpath), declared truncation, provenance (sha256), typed failures.
  */
object ReadToolSpec extends ZIOSpecDefault:

  private def tempWorkspace(): Path =
    val root = Files.createTempDirectory("raider-read-ws")
    root

  private def write(root: Path, rel: String, content: String): Path =
    val p = root.resolve(rel)
    Files.createDirectories(p.getParent)
    Files.write(p, content.getBytes(StandardCharsets.UTF_8))
    p

  def spec = suite("ReadTool RAI-014.a")(
    test("READ-01: ranged read returns the correct window with provenance") {
      ZIO.scoped {
        for
          root <- ZIO.attemptBlocking(tempWorkspace()).orDie
          tool <- Workspace.make(root.toString).map(new ReadTool(_))
          lines = (1 to 100).map(i => s"line-$i").mkString("\n")
          _ <- ZIO.attemptBlocking(write(root, "src/big.txt", lines)).orDie
          res <- tool.read("src/big.txt", offsetLine = 10, maxLines = 5)
          expectedSha = ReadTool.sha256Hex(
            Files.readAllBytes(root.resolve("src/big.txt"))
          )
        yield assertTrue(
          res.content == List(
            "line-11",
            "line-12",
            "line-13",
            "line-14",
            "line-15"
          ),
          res.totalLines == 100,
          res.truncated, // 15 < 100
          res.sha256 == expectedSha,
          res.bytes == lines.getBytes(StandardCharsets.UTF_8).length
        )
      }
    },
    test("READ-01: empty file is a defined result (no lines, not truncated)") {
      ZIO.scoped {
        for
          root <- ZIO.attemptBlocking(tempWorkspace()).orDie
          tool <- Workspace.make(root.toString).map(new ReadTool(_))
          _ <- ZIO.attemptBlocking(write(root, "empty.txt", "")).orDie
          res <- tool.read("empty.txt")
        yield assertTrue(
          res.content == Nil,
          res.totalLines == 0,
          !res.truncated,
          res.bytes == 0L
        )
      }
    },
    test("READ-02: traversal outside the workspace is denied (typed)") {
      ZIO.scoped {
        for
          root <- ZIO.attemptBlocking(tempWorkspace()).orDie
          tool <- Workspace.make(root.toString).map(new ReadTool(_))
          res <- tool.read("a/../../etc/passwd").exit
        yield assertTrue(
          res.isFailure,
          res match
            case ZExit.Failure(cause) =>
              cause.failures.headOption.map(_.code).contains("RA-TOOLDENY")
            case _ => false
        )
      }
    },
    test("READ-02: symlink escape is denied, inner symlink stays allowed") {
      ZIO.scoped {
        for
          root <- ZIO.attemptBlocking(tempWorkspace()).orDie
          outside <- ZIO.attemptBlocking {
            val secret = Files.createTempFile("outside", ".txt")
            Files.writeString(secret, "secret")
            secret
          }.orDie
          _ <- ZIO.attemptBlocking {
            Files.createSymbolicLink(root.resolve("escape.slink"), outside)
            val innerTarget = write(root, "data/inner.txt", "inner-content")
            Files.createSymbolicLink(root.resolve("ok.slink"), innerTarget)
          }.orDie
          tool <- Workspace.make(root.toString).map(new ReadTool(_))
          denied <- tool.read("escape.slink").exit
          allowed <- tool.read("ok.slink")
        yield assertTrue(
          denied.isFailure,
          denied match
            case ZExit.Failure(cause) =>
              cause.failures.headOption.map(_.code).contains("RA-TOOLDENY")
            case _ => false,
          allowed.content == List(
            "inner-content"
          ) // inner symlink resolves inside
        )
      }
    },
    test(
      "READ-02: binary file (NUL byte) is a declared typed failure, no dump"
    ) {
      ZIO.scoped {
        for
          root <- ZIO.attemptBlocking(tempWorkspace()).orDie
          tool <- Workspace.make(root.toString).map(new ReadTool(_))
          _ <- ZIO.attemptBlocking {
            Files.write(root.resolve("bin.dat"), Array[Byte](1, 0, 2, 3))
          }.orDie
          res <- tool.read("bin.dat").exit
          text = res match
            case ZExit.Failure(cause) =>
              cause.failures.headOption.map(_.detail).getOrElse("")
            case _ => ""
        yield assertTrue(
          res.isFailure,
          text.contains("binary file")
        )
      }
    },
    test("READ-02: oversize read is declared truncation, never silent") {
      ZIO.scoped {
        for
          root <- ZIO.attemptBlocking(tempWorkspace()).orDie
          tool <- Workspace.make(root.toString).map(new ReadTool(_))
          _ <- ZIO
            .attemptBlocking(
              write(
                root,
                "long.txt",
                (1 to 200).map(i => s"L$i").mkString("\n")
              )
            )
            .orDie
          res <- tool.read("long.txt", maxLines = 100)
        yield assertTrue(
          res.content.length == 100,
          res.content.last == "L100",
          res.truncated,
          res.totalLines == 200
        )
      }
    },
    test(
      "READ-02: maxLines beyond the hard bound is a typed error (no clamp)"
    ) {
      ZIO.scoped {
        for
          root <- ZIO.attemptBlocking(tempWorkspace()).orDie
          tool <- Workspace.make(root.toString).map(new ReadTool(_))
          _ <- ZIO.attemptBlocking(write(root, "f.txt", "x")).orDie
          res <- tool.read("f.txt", maxLines = ReadTool.HardMaxLines + 1).exit
        yield assertTrue(
          res.isFailure,
          res match
            case ZExit.Failure(cause) =>
              cause.failures.headOption.map(_.code).contains("RA-INP")
            case _ => false
        )
      }
    },
    test("READ-03: deleted file between listing and read is a typed failure") {
      ZIO.scoped {
        for
          root <- ZIO.attemptBlocking(tempWorkspace()).orDie
          tool <- Workspace.make(root.toString).map(new ReadTool(_))
          p <- ZIO.attemptBlocking(write(root, "gone.txt", "bye")).orDie
          _ <- ZIO.attemptBlocking(Files.delete(p)).orDie
          res <- tool.read("gone.txt").exit
        yield assertTrue(res.isFailure) // no fake successful read
      }
    }
  )
