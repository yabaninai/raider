package raider.tools.files.edit

import raider.core.RaiderError
import raider.tools.files.read.Workspace
import zio.test._
import zio.ZIO

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path}
import java.security.MessageDigest

/** fs_delete unit-level acceptance (RAI-260 baseline + RAI-276 hardening).
  *
  * RAI-276 adds an EXPLICIT `Files.isRegularFile` guard (NOFOLLOW semantics,
  * consistent with the deny-by-default symlink policy that resolves links
  * itself instead of letting filesystem calls follow them) to the staged delete
  * sequence. Before it, a workspace-local special file passed the
  * exists/isDirectory/size checks (a fifo reports size 0 without blocking) and
  * reached `Files.readAllBytes`: with NO writer attached the staged apply
  * blocks indefinitely (reproduced: two red attempts hung until the process was
  * killed — see docs/work/records/RAI-276.md), and with a cooperating writer
  * the read returns empty bytes whose sha matches the supplied provenance, so
  * the pre-guard code UNLINKS THE FIFO as if it were a regular file. DEL-06
  * drives that writer handshake deterministically (no virtual-clock timeouts,
  * no fiber interruption) and demands the typed refusal naming the path with
  * the fifo untouched.
  *
  * DEL-01..DEL-05 pin the unchanged behavior the card requires: regular-file
  * success and provenance, stale-sha denial, directory refusal, in-workspace
  * symlink resolution (the link node survives dangling after the target is
  * unlinked), and write-path-policy denial for escaping symlinks.
  */
object DeleteToolSpec extends ZIOSpecDefault:

  private def makeWorkspace(): ZIO[Any, Nothing, Workspace] =
    ZIO.attempt {
      val dir = Files.createTempDirectory("raider-delete")
      Workspace(dir.toRealPath())
    }.orDie

  private def tool(ws: Workspace) = new DeleteTool(ws)

  private def sha256Hex(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private val EmptySha256 = sha256Hex("")

  /** The typed error of a completed call, if any (mirrors the MUT-04 exit
    * matching style: `cause.failures` over `Exit.Failure`).
    */
  private def typedErrorOf(
      res: Option[zio.Exit[RaiderError, DeleteResult]]
  ): Option[RaiderError] =
    res match
      case Some(zio.Exit.Failure(cause)) => cause.failures.headOption
      case Some(zio.Exit.Success(_))     => None
      case None                          => None

  /** Platform capability probe (one bounded process call, cached per JVM run):
    * can this host create a fifo at all? The JDK has no fifo creation
    * primitive, so the spec shells out to POSIX `mkfifo`. On macOS and Linux CI
    * the probe succeeds and DEL-06 executes for real; on a platform that cannot
    * create the special file the test is marked IGNORED with that disclosed
    * assumption instead of failing — an ignored skip is never counted as a pass
    * (see the RAI-276 work record).
    */
  private lazy val fifoCapable: Boolean =
    try
      val dir = Files.createTempDirectory("raider-fifo-probe")
      try
        val proc =
          new ProcessBuilder("mkfifo", dir.resolve("probe.fifo").toString)
            .redirectErrorStream(true)
            .start()
        val output =
          new String(proc.getInputStream.readAllBytes(), UTF_8).trim
        proc.waitFor() == 0
      finally
        Files.deleteIfExists(dir.resolve("probe.fifo"))
        Files.deleteIfExists(dir)
    catch case _: Exception => false

  /** POSIX mkfifo via ProcessBuilder (the sanctioned spawn API; the JDK has no
    * fifo creation primitive). Only called after `fifoCapable` probed true, so
    * a failure here is a loud environment error, never a silent skip.
    */
  private def mkfifo(dir: Path, name: String): ZIO[Any, Nothing, Unit] =
    ZIO.attempt {
      val proc = new ProcessBuilder("mkfifo", dir.resolve(name).toString)
        .redirectErrorStream(true)
        .start()
      val output =
        new String(proc.getInputStream.readAllBytes(), UTF_8).trim
      val code = proc.waitFor()
      if code != 0 then sys.error(s"mkfifo exited $code: $output")
    }.orDie

  def spec = suite("DeleteTool (RAI-260 delete, RAI-276 regular-file guard)")(
    test(
      "DEL-01: deletes a regular file exactly as before, carrying provenance"
    ) {
      for
        ws <- makeWorkspace()
        _ <- ZIO
          .attempt(
            Files.writeString(ws.root.resolve("gone.txt"), "bye\n", UTF_8)
          )
          .orDie
        sha = sha256Hex("bye\n")
        res <- tool(ws).delete("gone.txt", sha)
        fileGone = !Files.exists(ws.root.resolve("gone.txt"))
      yield assertTrue(
        res.path == "gone.txt",
        res.oldSha256 == sha,
        res.bytes == 4,
        fileGone
      )
    },
    test("DEL-02: stale sha is a typed denial; the file survives (unchanged)") {
      for
        ws <- makeWorkspace()
        _ <- ZIO
          .attempt(
            Files.writeString(ws.root.resolve("keep.txt"), "keep\n", UTF_8)
          )
          .orDie
        res <- tool(ws).delete("keep.txt", EmptySha256).exit
        content = Files.readString(ws.root.resolve("keep.txt"), UTF_8)
        typedError = typedErrorOf(Some(res))
      yield assertTrue(
        res.isFailure,
        typedError.exists(_.isInstanceOf[RaiderError.ToolDenied]),
        typedError.exists(e => e.detail.contains("STALE DELETE")),
        content == "keep\n"
      )
    },
    test("DEL-03: directory target keeps the unchanged typed refusal") {
      for
        ws <- makeWorkspace()
        _ <- ZIO
          .attempt(Files.createDirectories(ws.root.resolve("a-dir")))
          .orDie
        res <- tool(ws).delete("a-dir", EmptySha256).exit
        dirStays = Files.exists(ws.root.resolve("a-dir"))
        typedError = typedErrorOf(Some(res))
      yield assertTrue(
        typedError.exists(_.isInstanceOf[RaiderError.InputValidation]),
        typedError.exists(e =>
          e.detail.contains("refusing to delete a directory: a-dir")
        ),
        dirStays
      )
    },
    test(
      "DEL-04: in-workspace symlink still resolves through the policy and deletes the target; the link node survives dangling"
    ) {
      for
        ws <- makeWorkspace()
        _ <- ZIO
          .attempt(
            Files.writeString(ws.root.resolve("target.txt"), "real\n", UTF_8)
          )
          .orDie
        _ <- ZIO
          .attempt(
            Files.createSymbolicLink(
              ws.root.resolve("link.txt"),
              ws.root.resolve("target.txt")
            )
          )
          .orDie
        sha = sha256Hex("real\n")
        res <- tool(ws).delete("link.txt", sha)
        targetGone = !Files.exists(ws.root.resolve("target.txt"))
        linkNodeSurvives = Files.exists(
          ws.root.resolve("link.txt"),
          LinkOption.NOFOLLOW_LINKS
        )
        linkDangling = !Files.exists(ws.root.resolve("link.txt"))
      yield assertTrue(
        res.path == "link.txt",
        res.oldSha256 == sha,
        targetGone,
        linkNodeSurvives,
        linkDangling
      )
    },
    test(
      "DEL-05: symlink escaping the workspace stays a typed policy denial, victim untouched"
    ) {
      val outside = Files
        .createTempDirectory("raider-delete-outside")
        .resolve("victim.txt")
      for
        ws <- makeWorkspace()
        _ <- ZIO.attempt {
          Files.writeString(outside, "victim\n", UTF_8)
          Files.createSymbolicLink(
            ws.root.resolve("esc.txt"),
            outside
          )
          ()
        }.orDie
        res <- tool(ws).delete("esc.txt", sha256Hex("victim\n")).exit
        victimStays = Files.exists(outside)
        typedError = typedErrorOf(Some(res))
      yield assertTrue(
        typedError.exists(_.isInstanceOf[RaiderError.ToolDenied]),
        typedError.exists(e => e.detail.contains("symlink escapes workspace")),
        victimStays
      )
    },
    test(
      "DEL-06: workspace-local fifo is refused typed, naming the path, BEFORE any read or unlink (RAI-276)"
    ) {
      for
        ws <- makeWorkspace()
        _ <- mkfifo(ws.root, "pipe.fifo")
        // Cooperating writer (deterministic FIFO handshake, no clocks and no
        // fiber interruption): a daemon fiber opens the fifo O_WRONLY, which
        // BLOCKS until the staged apply's reader opens it, then closes
        // immediately — the close is what delivers EOF, so on the PRE-GUARD
        // implementation Files.readAllBytes returns empty bytes, sha("")
        // matches the supplied provenance, and the fifo IS UNLINKED as if it
        // were a regular file (the concrete defect this card closes; without
        // any writer the same call simply hangs forever — both behaviors
        // reproduced and recorded). On the guarded implementation the refusal
        // happens BEFORE any open, so the writer never unblocks; that daemon
        // fiber is abandoned (one lingering blocking-pool thread per
        // execution, disclosed in the work record).
        _ <- ZIO.attemptBlocking {
          val out =
            new java.io.FileOutputStream(ws.root.resolve("pipe.fifo").toFile)
          out.close() // EOF for the reader once both ends have opened
        }.forkDaemon
        res <- tool(ws).delete("pipe.fifo", EmptySha256).exit
        fifoStays = Files.exists(ws.root.resolve("pipe.fifo"))
        typedError = typedErrorOf(Some(res))
      yield assertTrue(
        typedError.exists(_.isInstanceOf[RaiderError.InputValidation]),
        typedError.exists(e =>
          e.detail.contains(
            "refusing to delete a non-regular file: pipe.fifo"
          )
        ),
        fifoStays
      )
    }.when(fifoCapable)
  )
