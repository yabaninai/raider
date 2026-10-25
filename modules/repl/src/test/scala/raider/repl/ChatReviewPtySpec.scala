package raider.repl

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import java.util.concurrent.TimeUnit
import zio.test.*

/** RAI-192 product-entry PTY acceptance: per-hunk diff review driven through
  * the real TabShell chat loop, provider switching via :model, and the
  * credential redaction rule (a configured live key never reaches the
  * transcript). Runs the product Main with a scripted model; the review fixture
  * streams a diff fence with ---/+++ target headers and the patch lands on a
  * file pre-created in the session working directory (the same directory the
  * production fs_patch policy uses as its root).
  */
object ChatReviewPtySpec extends ZIOSpecDefault:

  private val Ansi =
    raw"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\u0007\x1b]*(?:\u0007|\x1b\\)|\x1b[()][0-9A-B]|\x1b[=><]|\x1b7|\x1b8".r

  private val ReviewFile = "raider-review-demo.txt"
  private val ReviewInitial = "alpha\nbeta\ngamma\n"
  private val ReviewApplied = "alpha\nBETA\ngamma\n"

  private final class Session(
      reviewFixture: Boolean = false,
      withApiKey: Option[String] = None
  ):

    private val base =
      Files.createTempDirectory(s"rai192-pty-${UUID.randomUUID()}")

    // The chat REPL (and therefore PatchTool) uses the process cwd as its
    // workspace root; pre-create the review target there.
    if reviewFixture then
      Files.writeString(base.resolve(ReviewFile), ReviewInitial)

    private val home = Files.createDirectories(base.resolve("home"))
    private val resizeRequest = base.resolve("resize.request")
    private val resizeResult = base.resolve("resize.result")

    def baseDir: Path = base
    def reviewTarget: Path = base.resolve(ReviewFile)

    private val javaBin =
      Paths.get(System.getProperty("java.home"), "bin", "java")

    private val inner =
      s"/bin/stty rows 32 cols 100; exec '${javaBin.toString}' -cp '${System.getProperty("java.class.path")}' raider.repl.ChatLayoutPtyApp"

    private val builder = new ProcessBuilder(
      "/usr/bin/script",
      "-q",
      "/dev/null",
      "/bin/sh",
      "-c",
      inner
    )

    builder.redirectErrorStream(true)
    builder.directory(base.toFile)
    builder.environment().put("TERM", "xterm-256color")
    builder.environment().put("HOME", home.toString)
    builder.environment().put("RAIDER_PROVIDER", "mock")
    builder.environment().put("RAI188_RESIZE_REQUEST", resizeRequest.toString)
    if reviewFixture then
      builder.environment().put("RAI192_REVIEW_FIXTURE", "1")
    builder.environment().remove("RAIDER_API_KEY")
    builder.environment().remove("RAIDER_API_KEY_CMD")
    withApiKey.foreach(key => builder.environment().put("RAIDER_API_KEY", key))
    private val process = builder.start()
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

    def rawTranscript: String = lock.synchronized {
      new String(bytes.toByteArray, StandardCharsets.UTF_8)
    }

    def waitFor(fragment: String, timeoutSeconds: Int = 20): Boolean =
      val deadline =
        System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
      while System.nanoTime() < deadline do
        if transcript.contains(fragment) then return true
        Thread.sleep(100)
      transcript.contains(fragment)

    def waitForExit(timeoutSeconds: Int): Boolean =
      process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

    def saveTranscript(): Path =
      val dir = Files.createDirectories(Paths.get("target/rai192-pty"))
      val path = dir.resolve(s"review-${UUID.randomUUID()}.txt")
      Files.writeString(path, transcript, StandardCharsets.UTF_8)
      path

    def saveRawTranscript(): Path =
      val dir = Files.createDirectories(Paths.get("target/rai192-pty"))
      val path = dir.resolve(s"review-raw-${UUID.randomUUID()}.txt")
      Files.writeString(path, rawTranscript, StandardCharsets.UTF_8)
      path

    def close(): Unit =
      try
        if process.isAlive then
          send(":quit\r")
          if !process.waitFor(10, TimeUnit.SECONDS) then
            process.destroyForcibly()
        input.close()
        val walk = Files.walk(base)
        try
          walk
            .sorted(java.util.Comparator.reverseOrder[Path]())
            .forEach(Files.deleteIfExists(_))
        finally walk.close()
      catch case _: Exception => ()

  def spec =
    suite("ChatReviewPtySpec (RAI-192 per-hunk review + provider switch)")(
      test(
        "accept one hunk, apply through fs_patch, switch providers without restart"
      ) {
        val session = new Session(reviewFixture = true)
        try
          val prompt = session.waitFor("you> ", 120)
          session.send("review this proposal\r")
          val diffRendered = session.waitFor("+BETA", 30)
          val affordance =
            session.waitFor("diff review open: 0/1 decided", 30)
          session.send(":hunk 1 accept\r")
          val accepted = session.waitFor("hunk 1 accepted (1/1 decided)", 20)
          session.send(":hunk apply\r")
          val applied =
            session.waitFor(
              s"applied 1 hunk(s) to $ReviewFile",
              20
            )
          val diskContent =
            if Files.exists(session.reviewTarget) then
              Files.readString(session.reviewTarget)
            else "<missing>"
          val diskVerified = diskContent == ReviewApplied
          session.send(":hunk apply\r")
          val secondApplyRefused =
            session.waitFor("refusing to apply twice", 45)
          session.send(":model\r")
          val described = session.waitFor("provider mock | model scripted", 45)
          session.send(":model mock scripted-2\r")
          val switchedMock =
            session.waitFor("model switched: mock/scripted-2", 60)
          session.send(":model bogus\r")
          val unknownRefused = session.waitFor("unknown provider 'bogus'", 45)
          session.send(":model openai gpt-test\r")
          val keyRefused =
            session.waitFor("no API key resolved for 'openai'", 45) &&
              session.waitFor("model unchanged: scripted-2", 45)
          // A full turn on the switched session proves the swap is live: the
          // mock backend streams its own stub (unique to post-switch turns).
          // The stub turn is not a diff, so the per-turn review stays closed.
          session.send("post-switch turn\r")
          val stubStreamed =
            session.waitFor("[mock backend] scripted stub; no live network", 60)
          session.send(":hunk\r")
          val reviewClosed =
            session.waitFor("no diff review open", 45)
          session.send(":aux\r")
          val lineageTab = session.waitFor("lineage unavailable", 45)
          session.send(":chat\r")
          val returnedToChat = session.waitFor("you> ", 15)
          session.send(":quit\r")
          val exited = session.waitForExit(60)
          val transcript = session.transcript
          val evidence = session.saveTranscript()
          assertTrue(
            prompt,
            diffRendered,
            affordance,
            accepted,
            applied,
            diskVerified,
            secondApplyRefused,
            described,
            switchedMock,
            unknownRefused,
            keyRefused,
            stubStreamed,
            reviewClosed,
            lineageTab,
            returnedToChat,
            exited,
            transcript.contains("ready | diff review open: 0/1 decided"),
            !transcript.contains("Exception"),
            Files.size(evidence) > 0
          )
        finally
          session.saveTranscript()
          session.close()
      },
      test(
        "configured live key switches to openai without ever reaching the transcript"
      ) {
        val secret = "sk-pty-redaction-check-1234567890abcdef"
        val session = new Session(withApiKey = Some(secret))
        try
          val prompt = session.waitFor("you> ", 120)
          session.send(":model openai gpt-test\r")
          val switched =
            session.waitFor("model switched: openai/gpt-test", 60)
          session.send(":model\r")
          val described =
            session.waitFor("provider openai | model gpt-test", 45)
          session.send(":quit\r")
          val exited = session.waitForExit(60)
          val raw = session.rawTranscript
          val evidence = session.saveRawTranscript()
          assertTrue(
            prompt,
            switched,
            described,
            exited,
            !raw.contains(secret),
            Files.size(evidence) > 0,
            !session.transcript.contains("Exception")
          )
        finally
          session.saveRawTranscript()
          session.close()
      }
    ) @@ TestAspect.sequential @@ TestAspect.timeout(
      zio.Duration.fromSeconds(180)
    )

end ChatReviewPtySpec
