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

  private final class Session(
      term: String = "xterm-256color",
      combinedFeature: Boolean = false
  ):

    private val base =
      Files.createTempDirectory(s"rai188-pty-${UUID.randomUUID()}")

    private val home = Files.createDirectories(base.resolve("home"))
    private val resizeRequest = base.resolve("resize.request")
    private val resizeResult = base.resolve("resize.result")

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
    builder.environment().put("TERM", term)
    builder.environment().put("HOME", home.toString)
    builder.environment().put("RAIDER_PROVIDER", "mock")
    builder.environment().put("RAI188_RESIZE_REQUEST", resizeRequest.toString)
    if combinedFeature then
      builder.environment().put("RAI191_COMBINED_FIXTURE", "1")
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

    def waitFor(fragment: String, timeoutSeconds: Int = 20): Boolean =
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

    def resize(rows: Int, columns: Int): Boolean =
      Files.deleteIfExists(resizeResult)
      Files.writeString(
        resizeRequest,
        s"$rows $columns",
        StandardCharsets.UTF_8
      )
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
      while Files.exists(resizeRequest) && System.nanoTime() < deadline do
        Thread.sleep(20)
      !Files.exists(resizeRequest) &&
      Files.exists(resizeResult) &&
      Files.readString(resizeResult, StandardCharsets.UTF_8) == "0"

    def typeDraft(text: String): Unit = send(text)

    def saveTranscript(): Path =
      val dir = Files.createDirectories(Paths.get("target/rai188-pty"))
      val path = dir.resolve(s"chat-${UUID.randomUUID()}.txt")
      Files.writeString(path, transcript, StandardCharsets.UTF_8)
      path

    def saveRawTranscript(): Path =
      val dir = Files.createDirectories(Paths.get("target/rai188-pty"))
      val path = dir.resolve(s"chat-raw-${UUID.randomUUID()}.txt")
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
          val idleFooter =
            session.waitFor("model scripted | budget unknown | stream idle", 20)
          val streamingFooter = session.waitFor(
            "model scripted | budget unknown | stream streaming",
            20
          )
          session.send("second prompt\r")
          val secondStream =
            session.waitForCount("stream idle | ready | :aux", 2, 20)
          session.send(":scroll-up\r")
          val scrolled = session.waitFor("scrolled up 1")
          session.send(":scroll-down\r")
          val scrollReturned = session.waitFor("ready | :aux")
          session.send(":aux\r")
          val auxShown = session.waitFor("lineage unavailable")
          session.send(":chat\r")
          val returnedToChat = session.waitFor("you> ")
          session.send(
            "\u0010"
          ) // Ctrl-P: JLine's immediately preceding history item.
          val recalled = session.waitFor("you> :chat")
          session.send("\r")
          val historyCommandAccepted = session.waitFor("you> ")
          session.send(":tabs\r")
          val tabsReachable = session.waitFor("no open tabs")
          session.send(":quit\r")
          val exited = session.waitForExit(20)
          val transcript = session.transcript
          val raw = session.rawTranscript
          val evidence = session.saveTranscript()
          assertTrue(
            ready,
            idleFooter,
            pastedUnicode,
            streamed,
            streamingFooter,
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
      },
      test("session listing and focus switching preserve per-tab drafts") {
        val session = new Session
        try
          val prompt = session.waitFor("you> ", 120)
          session.send(":new alpha\r")
          val alpha = session.waitFor("tab created: alpha")
          session.send(":new beta\r")
          val beta = session.waitFor("tab created: beta")
          session.send(":sessions\r")
          val listed =
            session.waitFor("*2. beta [") && session.waitFor("1. alpha [")
          session.send(":switch alpha\r")
          val switched = session.waitFor("focused: alpha [")
          session.send(":switch beta\r")
          val switchedBack = session.waitFor("focused: beta [")
          session.send(":switch alpha\r")
          val restored = session.waitFor("focused: alpha [")
          session.send(":quit\r")
          val exited = session.waitForExit(20)
          val evidence = session.saveTranscript()
          assertTrue(
            prompt,
            alpha,
            beta,
            listed,
            switched,
            switchedBack,
            restored,
            exited,
            Files.size(evidence) > 0
          )
        finally session.close()
      },
      test(
        "tasks panel opens and accepts navigation while no plan is available"
      ) {
        val session = new Session
        try
          val prompt = session.waitFor("you> ", 120)
          session.send(":tasks\r")
          val open = session.waitFor("Tasks / todo — idle")
          val honestEmpty = session.waitFor("No plan tasks available")
          session.send(":task-down\r")
          val movedDown = session.waitFor("No plan tasks available")
          session.send(":task-up\r")
          val movedUp = session.waitFor("Use :task-up / :task-down to navigate")
          session.send(":chat\r")
          val returned = session.waitFor("you> ")
          session.send(":quit\r")
          val exited = session.waitForExit(20)
          val evidence = session.saveTranscript()
          assertTrue(
            prompt,
            open,
            honestEmpty,
            movedDown,
            movedUp,
            returned,
            exited,
            Files.size(evidence) > 0
          )
        finally session.close()
      },
      test(
        "chat cancellation, editable draft, resize, and multiline paste use the shell writer"
      ) {
        val session = new Session
        try
          val prompt = session.waitFor("you> ", 120)
          session.send("stream before draft\r")
          val streamStarted = session.waitFor("assistant:", 20)
          session.typeDraft("draft-café-界")
          val draftPreserved = session.waitFor("you> draft-café-界", 20)
          val generationReady = session.waitFor("ready | :aux", 20)
          session.send("\r")
          val draftSubmitted = session.waitFor("you: draft-café-界", 20)
          session.send("cancel me after the first deltas\r")
          val partialResponse =
            session.waitFor("streaming turn 3 chunk 1", 20)
          session.send(":cancel\r")
          val cancelled =
            session.waitFor("cancelled | partial response retained", 30)
          val resizeApplied = session.resize(rows = 22, columns = 70)
          session.send(
            "\u001b[200~pasted first line\npasted second line 界\u001b[201~\r"
          )
          val multiline = session.waitFor("pasted second line 界", 20)
          session.send("resize reflow marker 界 café\r")
          val resizedRender = session.waitFor("resize reflow marker 界 café", 20)
          session.send(":quit\r")
          val exited = session.waitForExit(20)
          val transcript = session.transcript
          val evidence = session.saveTranscript()
          assertTrue(
            prompt,
            streamStarted,
            partialResponse,
            cancelled,
            draftPreserved,
            generationReady,
            draftSubmitted,
            resizeApplied,
            multiline,
            resizedRender,
            exited,
            transcript.contains("assistant:"),
            !transcript.contains("Exception"),
            Files.size(evidence) > 0
          )
        finally
          session.saveTranscript()
          session.close()
      },
      test(
        "one chat turn renders markdown, highlighted code, and parsed diff"
      ) {
        val session = new Session(combinedFeature = true)
        try
          val prompt = session.waitFor("you> ", 120)
          session.send("render all formats\r")
          val heading = session.waitFor("Combined view", 30)
          val code = session.waitFor("val answer = 42", 30)
          val diff = session.waitFor("+new", 30)
          val ready = session.waitFor("stream idle | ready", 30)
          val raw = session.rawTranscript
          session.send(":quit\r")
          val exited = session.waitForExit(20)
          val evidence = session.saveRawTranscript()
          assertTrue(
            prompt,
            heading,
            code,
            diff,
            ready,
            raw.contains("\u001b[34mval\u001b[0m"),
            raw.contains("\u001b[31m-old\u001b[0m"),
            raw.contains("\u001b[32m+new\u001b[0m"),
            exited,
            Files.size(evidence) > 0,
            !session.transcript.contains("Exception")
          )
        finally session.close()
      }
    ) @@ TestAspect.sequential @@ TestAspect.timeout(
      zio.Duration.fromSeconds(240)
    )

end ChatLayoutPtySpec
