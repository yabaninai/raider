package raider.tools.files.edit

import raider.core.RaiderError
import raider.tools.files.read.Workspace
import zio.test._
import zio.ZIO

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

object EditToolSpec extends ZIOSpecDefault:

  private def makeWorkspace(): ZIO[Any, Nothing, Workspace] =
    ZIO.attempt {
      val dir = Files.createTempDirectory("raider-edit")
      Files.writeString(dir.resolve("existing.txt"), "hello world\n", UTF_8)
      Workspace(dir.toRealPath())
    }.orDie

  private def tool(ws: Workspace) = new EditTool(ws)

  def spec = suite("EditTool (RAI-015)")(
    test("EDIT-01: create new file with empty expected_sha256") {
      for
        ws <- makeWorkspace()
        res <- tool(ws).write("new.txt", "new content\n", "")
      yield assertTrue(res.created, res.newSha256.nonEmpty, res.bytes == 12)
    },
    test("EDIT-01: modify existing file with correct expected_sha256") {
      for
        ws <- makeWorkspace()
        sha <- ZIO.fromEither(
          raider.tools.files.edit.EditTool
            .sha256OfFile(ws.root.resolve("existing.txt"))
        )
        res <- tool(ws).write("existing.txt", "modified content\n", sha)
      yield assertTrue(!res.created, res.oldSha256 == sha, res.newSha256 != sha)
    },
    test("EDIT-02: stale expected_sha256 → failure without mutation") {
      for
        ws <- makeWorkspace()
        res <- tool(ws).write("existing.txt", "changed\n", "wrong_sha").exit
        content = Files.readString(ws.root.resolve("existing.txt"), UTF_8)
      yield assertTrue(res.isFailure, content == "hello world\n")
    },
    test("EDIT-02: path escape → ToolDenied") {
      for
        ws <- makeWorkspace()
        res <- tool(ws).write("../../../etc/passwd", "pwned", "").exit
      yield assertTrue(res.isFailure)
    },
    test("EDIT-01: create then modify then verify roundtrip") {
      for
        ws <- makeWorkspace()
        t = tool(ws)
        r1 <- t.write("roundtrip.txt", "v1\n", "")
        sha1 = r1.newSha256
        r2 <- t.write("roundtrip.txt", "v2\n", sha1)
        r3 <- t.write("roundtrip.txt", "v3\n", r2.newSha256)
        content = Files.readString(ws.root.resolve("roundtrip.txt"), UTF_8)
      yield assertTrue(
        r1.created,
        !r2.created,
        !r3.created,
        r3.newSha256 != r2.newSha256,
        content == "v3\n"
      )
    }
  )
