package raider.repl.dist

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import zio.test.*

/** PTY acceptance for the INSTALLED REPL launcher (RAI-262). The session runs
  * the unpacked distribution's raider-repl script under /usr/bin/script (real
  * pty, xterm-256color) against the real scala3-repl compiler — same mechanics
  * as ChatLayoutPtySpec, but through the packaging entry instead of the sbt
  * classpath.
  */
object InstalledReplPtySpec extends ZIOSpecDefault:

  private val Ansi =
    raw"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\u0007\x1b]*(?:\u0007|\x1b\\)|\x1b[()][0-9A-B]|\x1b[=><]|\x1b7|\x1b8".r

  private final class Session(launcher: Path, workDir: Path):

    private val base =
      Files.createTempDirectory(s"rai262-pty-${UUID.randomUUID()}")

    private val home = Files.createDirectories(base.resolve("home"))

    private val inner =
      s"/bin/stty rows 32 cols 100; exec '${launcher.toString}'"

    private val builder = new ProcessBuilder(
      "/usr/bin/script",
      "-q",
      "/dev/null",
      "/bin/sh",
      "-c",
      inner
    )

    builder.redirectErrorStream(true)
    builder.directory(workDir.toFile)
    builder.environment().put("TERM", "xterm-256color")
    builder.environment().put("HOME", home.toString)
    builder.environment().put("RAIDER_PROVIDER", "mock")
    builder.environment().remove("RAIDER_API_KEY")
    builder.environment().remove("RAIDER_API_KEY_CMD")
    builder.environment().remove("OPENAI_API_KEY")
    builder.environment().remove("ANTHROPIC_API_KEY")
    val process = builder.start()

    private val input = process.getOutputStream
    private val bytes = ByteArrayOutputStream()
    private val lock = new Object

    private val pump = new Thread(() => {
      val stream = process.getInputStream
      val buffer = new Array[Byte](4096)
      try
        var n = stream.read(buffer)
        while n >= 0 do
          lock.synchronized(bytes.write(buffer, 0, n))
          n = stream.read(buffer)
      catch case _: java.io.IOException => ()
    })

    pump.setDaemon(true)
    pump.start()

    def send(text: String): Unit = input.synchronized {
      input.write(text.getBytes(StandardCharsets.UTF_8))
      input.flush()
    }

    def transcript: String =
      Ansi.replaceAllIn(
        new String(
          lock.synchronized(bytes.toByteArray),
          StandardCharsets.UTF_8
        ),
        ""
      )

    def waitFor(fragment: String, timeoutSeconds: Int): Boolean =
      val deadline =
        System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
      while System.nanoTime() < deadline do
        if transcript.contains(fragment) then return true
        Thread.sleep(100)
      transcript.contains(fragment)

    def waitForExit(timeoutSeconds: Int): Boolean =
      process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

    /** Destroys the REPL's own JVM descendant (SIGTERM), leaving the PTY
      * wrapper alone, and returns the WRAPPER's exit code once it reaps the
      * child: the script -> sh -c "exec launcher" chain makes the REPL JVM the
      * direct child, and script(1) propagates the child status, so a clean
      * SIGTERM path surfaces as exit code 143 on the wrapper.
      */
    def destroyDescendantAndAwait(timeoutSeconds: Long): Option[Int] =
      val descendants = process.descendants().iterator().asScala.toVector
      val replJvm = descendants.find { h =>
        val command = h.info().command()
        command.isPresent && command.get().contains("java")
      }
      replJvm match
        case None => None
        case Some(handle) =>
          handle.destroy()
          handle.onExit().get(timeoutSeconds, TimeUnit.SECONDS)
          if process.waitFor(timeoutSeconds, TimeUnit.SECONDS) then
            Some(process.exitValue())
          else None

    def teardown(): Unit =
      process.destroyForcibly()
      DistHarness.deleteRecursive(base)

  private def saveTranscript(tag: String, text: String): Path =
    val dir = Files.createDirectories(Paths.get("target/rai262-pty"))
    val path = dir.resolve(s"$tag-${UUID.randomUUID()}.txt")
    Files.writeString(path, text, StandardCharsets.UTF_8)
    path

  override def spec: Spec[Any, Any] = suite("installed repl pty")(
    test("installed launcher drives the real terminal REPL over PTY") {
      InstalledDistFixture.withFreshDir("rai262-pty-install") { dir =>
        val launcher =
          DistHarness
            .unpack(InstalledDistFixture.staged.archive, dir)
            .resolve(InstalledDistFixture.DistName)
            .resolve("raider-repl")
        val session = new Session(launcher, dir)
        try
          val sawBanner = session.waitFor("Raider REPL", 240)
          val sawPrompt = session.waitFor("raider> ", 60)
          session.send("val x = 41\r")
          val sawBinding = session.waitFor("val x: Int = 41", 60)
          session.send("x + 1\r")
          val sawResult = session.waitFor("val res0: Int = 42", 60)
          session.send(":quit\r")
          val exited = session.waitForExit(30)
          val transcript = session.transcript
          saveTranscript("installed-eval", transcript)
          assertTrue(
            sawBanner,
            sawPrompt,
            sawBinding,
            sawResult,
            exited,
            transcript.contains("val x: Int = 41"),
            transcript.contains("val res0: Int = 42"),
            !transcript.contains("Exception")
          )
        finally session.teardown()
      }
    },
    test(
      "SIGTERM inside a PTY session exits 143 through the PTY wrapper (bounded)"
    ) {
      InstalledDistFixture.withFreshDir("rai262-pty-sigterm") { dir =>
        val launcher =
          DistHarness
            .unpack(InstalledDistFixture.staged.archive, dir)
            .resolve(InstalledDistFixture.DistName)
            .resolve("raider-repl")
        val session = new Session(launcher, dir)
        try
          val sawBanner = session.waitFor("Raider REPL", 240)
          val wrapperExit = session.destroyDescendantAndAwait(30)
          saveTranscript("installed-sigterm", session.transcript)
          assertTrue(sawBanner, wrapperExit.contains(143))
        finally session.teardown()
      }
    }
  )
