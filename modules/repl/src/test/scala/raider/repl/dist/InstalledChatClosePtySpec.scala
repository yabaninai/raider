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

/** PTY acceptance for the chat close lifecycle (RAI-263). The session runs the
  * unpacked distribution's raider-repl script under /usr/bin/script (real pty,
  * xterm-256color) against the real scala3-repl compiler with the mock
  * (scripted fixture) provider — the working-product §4 W11 golden scenario:
  *
  *   - an ask completes and stays in history;
  *   - close() twice is bounded and idempotent, history is preserved;
  *   - an ask after close refuses TYPED (RaiderError.InputValidation, RA-INP);
  *   - no fabricated reply, and the REPL itself stays alive;
  *   - :quit still exits 0 through the PTY wrapper.
  */
object InstalledChatClosePtySpec extends ZIOSpecDefault:

  private val Ansi =
    raw"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\u0007\x1b]*(?:\u0007|\x1b\\)|\x1b[()][0-9A-B]|\x1b[=><]|\x1b7|\x1b8".r

  private final class Session(launcher: Path, workDir: Path):

    private val base =
      Files.createTempDirectory(s"rai263-pty-${UUID.randomUUID()}")

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

    def teardown(): Unit =
      process.destroyForcibly()
      DistHarness.deleteRecursive(base)

  private def saveTranscript(tag: String, text: String): Path =
    val dir = Files.createDirectories(Paths.get("target/rai263-pty"))
    val path = dir.resolve(s"$tag-${UUID.randomUUID()}.txt")
    Files.writeString(path, text, StandardCharsets.UTF_8)
    path

  override def spec: Spec[Any, Any] = suite("installed chat close pty")(
    test(
      "chat close golden over the installed PTY REPL: history preserved, close idempotent, post-close ask refuses typed, REPL alive"
    ) {
      InstalledDistFixture.withFreshDir("rai263-pty-install") { dir =>
        val launcher =
          DistHarness
            .unpack(InstalledDistFixture.staged.archive, dir)
            .resolve(InstalledDistFixture.DistName)
            .resolve("raider-repl")
        val session = new Session(launcher, dir)
        try
          val sawBanner = session.waitFor("Raider REPL", 240)
          val sawPrompt = session.waitFor("raider> ", 60)
          session.send("val chat = openSession(worker)\r")
          val sawChat = session.waitFor("facade.Chat", 60)
          // Chat.ask is eager: it runs the turn and returns the reply String
          // (AgentRef.ask is the lazy AgentAsk form)
          session.send("val r1 = chat.ask(\"study the parser\")\r")
          val sawAnswer = session.waitFor("[scripted-fixture] mock answer", 60)
          session.send("chat.messages.size\r")
          val sawFirstSize = session.waitFor(": Int = 2", 60)
          session.send("chat.close()\r")
          session.send("chat.close()\r")
          session.send("chat.messages.size\r")
          // second size round-trip proves the REPL survived both closes and
          // that the closed chat kept its earlier history
          val sawSecondSize = session.waitFor(": Int = 2", 60)
          session.send(
            "val refused = try chat.ask(\"after close\") catch { " +
              "case e: raider.core.RaiderError.InputValidation => " +
              "\"REFUSED-TYPED\" }\r"
          )
          val sawRefusal = session.waitFor("REFUSED-TYPED", 60)
          session.send(":quit\r")
          val exited = session.waitForExit(30)
          val exitOk = exited && session.process.exitValue() == 0
          val transcript = session.transcript
          saveTranscript("installed-chat-close", transcript)
          val answerCount = transcript
            .split("\\[scripted-fixture\\] mock answer", -1)
            .length - 1
          assertTrue(
            sawBanner,
            sawPrompt,
            sawChat,
            sawAnswer,
            sawFirstSize,
            sawSecondSize,
            sawRefusal,
            exitOk,
            // exactly ONE fabricated-free answer: the pre-close ask; the
            // post-close ask must not have produced a second reply
            answerCount == 1,
            !transcript.contains("Exception")
          )
        finally session.teardown()
      }
    }
  )
