package raider.repl

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import java.util.concurrent.TimeUnit
import zio.test.*

/** Product-entry PTY acceptance for the chat layout and scripted model stream.
  */
object ChatLayoutPtySpec extends ZIOSpecDefault:

  private val Ansi =
    raw"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\u0007\x1b]*(?:\u0007|\x1b\\)|\x1b[()][0-9A-B]|\x1b[=><]|\x1b7|\x1b8".r

  private final class Session(term: String = "xterm-256color"):

    private val base =
      Files.createTempDirectory(s"rai188-pty-${UUID.randomUUID()}")

    private val home = Files.createDirectories(base.resolve("home"))

    private val javaBin =
      Paths.get(System.getProperty("java.home"), "bin", "java")

    private val inner =
      s"/bin/stty rows 32 cols 100; exec '${javaBin.toString}' -cp '${System.getProperty("java.class.path")}' raider.repl.Main --chat"

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
    builder.environment().put("TERM", term)
    builder.environment().put("HOME", home.toString)
    builder.environment().put("RAIDER_PROVIDER", "mock")
    builder.environment().remove("RAIDER_API_KEY")
    builder.environment().remove("RAIDER_API_KEY_CMD")
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

    def waitFor(fragment: String, timeoutSeconds: Int = 40): Boolean =
      val deadline =
        System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
      while System.nanoTime() < deadline do
        if transcript.contains(fragment) then return true
        Thread.sleep(100)
      transcript.contains(fragment)

    def waitForCount(
        fragment: String,
        count: Int,
        timeoutSeconds: Int
    ): Boolean =
      val deadline =
        System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
      while System.nanoTime() < deadline do
        if transcript.sliding(fragment.length).count(_ == fragment) >= count
        then return true
        Thread.sleep(100)
      transcript.sliding(fragment.length).count(_ == fragment) >= count

    def waitForExit(timeoutSeconds: Int): Boolean =
      process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

    def saveTranscript(): Path =
      val dir = Files.createDirectories(Paths.get("target/rai188-pty"))
      val path = dir.resolve(s"chat-${UUID.randomUUID()}.txt")
      Files.writeString(path, transcript, StandardCharsets.UTF_8)
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
    suite("ChatLayoutPtySpec (RAI-188 product entry)")(
      test(
        "scripted deltas render in chat; aux returns; JLine history remains live"
      ) {
        val session = new Session
        try
          val ready = session.waitFor("you> ", 120)
          session.send("\u001b[200~wide input 界 and café\u001b[201~\r")
          val pastedUnicode = session.waitFor("[scripted-fixture] mock answer")
          session.send("first prompt\r")
          val streamed = session.waitFor("[scripted-fixture] mock answer")
          session.send("second prompt\r")
          val secondStream = session.waitForCount("ready | :aux", 2, 40)
          session.send(":scroll-up\r")
          val scrolled = session.waitFor("scrolled up 1")
          session.send(":scroll-down\r")
          val scrollReturned = session.waitFor("ready | :aux")
          session.send(":aux\r")
          val auxShown = session.waitFor("Auxiliary surface")
          session.send(":chat\r")
          val returnedToChat = session.waitFor("you> ")
          session.send(
            "\u0010"
          ) // Ctrl-P: JLine's immediately preceding history item.
          val recalled = session.waitFor("you> :chat")
          session.send("\r")
          val historyCommandAccepted = session.waitFor("you> ")
          session.send(":tabs\r")
          val tabsReachable =
            session.waitFor("tabs: chat (focused), auxiliary (:aux)")
          session.send(":quit\r")
          val exited = session.waitForExit(20)
          val transcript = session.transcript
          val raw = session.rawTranscript
          val evidence = session.saveTranscript()
          assertTrue(
            ready,
            pastedUnicode,
            streamed,
            secondStream,
            transcript.contains("you: wide input 界 and café"),
            scrolled,
            scrollReturned,
            auxShown,
            returnedToChat,
            recalled,
            historyCommandAccepted,
            tabsReachable,
            exited,
            raw.takeRight(160).contains("\u001b[?2004l"),
            Files.size(evidence) > 0,
            !transcript.contains("Exception")
          )
        finally
          session.saveTranscript()
          session.close()
      },
      test("dumb terminal selects the ordinary line REPL without crashing") {
        val session = new Session("dumb")
        try
          val fallback =
            session.waitFor("Chat layout unavailable for this terminal", 120)
          val prompt = session.waitFor("raider> ", 20)
          session.send(":quit\r")
          val exited = session.waitForExit(20)
          val evidence = session.saveTranscript()
          assertTrue(
            fallback,
            prompt,
            exited,
            !session.transcript.contains("Exception"),
            Files.size(evidence) > 0
          )
        finally session.close()
      }
    ) @@ TestAspect.sequential @@ TestAspect.timeout(
      zio.Duration.fromSeconds(240)
    )

end ChatLayoutPtySpec
