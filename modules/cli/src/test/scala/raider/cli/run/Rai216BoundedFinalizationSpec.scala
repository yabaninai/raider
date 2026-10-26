package raider.cli.run

import zio.test.*
import zio.test.Assertion.*
import zio.{ZIO, ZLayer}

import raider.cli.workflow.WorkflowCompile
import raider.dsl.workflow.{
  ChatWorkflowRenderer,
  NamedCheck,
  RenderOptions,
  StsAgentStage,
  StsOrigin,
  StsParam,
  StsSelection,
  StsTurn,
  StsTurnKind,
  StsTurnStatus
}

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** RAI-216 bounded finalization of the GENERATED check runner, driven by a
  * LOCAL OpenAI-compatible SSE stand on the loopback (ephemeral port — no
  * network egress, no spend; the WorkflowLaunchSpec discipline). The generated
  * drain join is now deadline-bounded, so a grandchild that inherits the
  * check's stdout write end cannot stall report finalization indefinitely:
  *
  *   - RAI-216-A: the check exits 0 immediately while a real grandchild keeps
  *     the pipe open past the (shortened, editable) finalization bound — the
  *     launcher must return BEFORE the grandchild releases the pipe, with the
  *     typed errored outcome (blocking exit 10, task-failed) carrying the
  *     truthful partial capture as evidence, never a green verdict.
  *   - RAI-216-B: the check overruns the wall bound and is destroyed while a
  *     real grandchild holds the pipe — finalization lands promptly with the
  *     ordinary destroyed message, exit 10, never a hang, never green. Platform
  *     finding (JDK 21 macOS, ProcessImpl.destroy): the destroy itself closes
  *     the capture stream synchronously, so the destroyed-path cutoff note —
  *     correct and retained in the generated code for platforms whose destroy
  *     leaves the capture open — is not observable here; its natural-exit
  *     counterpart is demonstrated by RAI-216-A.
  *   - RAI-216-C: an ordinary check whose pipe closes instantly is unchanged
  *     under the bounded join — green run, trusted-checks-passed, exit 0.
  *   - RAI-216-S: SIGTERM DURING the bounded drain wait keeps the exit-143
  *     semantics intact with truthful interruption evidence (never green).
  *
  * Process-tree reaping of the surviving holder is explicitly a non-goal of
  * RAI-216: the launcher's obligation is to stop waiting, not to reap the
  * subtree. The fixture holder is a real `sh -c` shell that stays alive holding
  * the inherited stdout write end while producing nothing; the check pipe is
  * therefore empty and the capture reader is parked in a blocking read when the
  * direct child exits.
  *
  * Platform note measured while building this fixture (macOS/OpenJDK 21): on
  * NATURAL child exit the reaper's `processExited()` hook is synchronized on
  * the capture stream monitor, which the parked reader holds from inside its
  * blocking read — the close cannot proceed, so the hold is effectively
  * deterministic when the pipe is empty (hence the silent holder). On the
  * DESTROY path the JDK closes the capture stream synchronously in the
  * destroying thread (ProcessImpl.destroy), so no fixture can hold a capture
  * across a destroy on this platform and the destroyed-path cutoff note is
  * unreachable here (see RAI-216-B). Attempt retries below absorb residual
  * scheduling variance only; a genuine failure of the mechanism under test (for
  * example a hang, a green verdict on partial capture, or exit 0 after SIGTERM)
  * fails the test on the very first attempt.
  */
object Rai216BoundedFinalizationSpec extends ZIOSpecDefault:

  // ----- fixture -------------------------------------------------------------

  private val probe = StsSelection(
    sessionId = "sess-rai216-bounded-finalization",
    intent = StsTurn(
      "r1",
      StsTurnKind.Intent,
      StsTurnStatus.Completed,
      "Write the launch marker for the RAI-216 bounded finalization probe"
    ),
    refinements = List(
      StsTurn(
        "r2",
        StsTurnKind.Refinement,
        StsTurnStatus.Completed,
        "Keep the marker content exact"
      )
    ),
    taskParams = List(
      StsParam("targetPath", "marker.txt"),
      StsParam("summary", "RAI-216 bounded finalization probe task")
    ),
    checks = List(
      NamedCheck(
        "fixture-check",
        List("/bin/sh", "-c", "exec sh \"$RAI216_CASE\""),
        false
      )
    ),
    agentStage = StsAgentStage(maxRounds = 3),
    origin = StsOrigin(
      providerKind = "openai-compatible",
      model = "stand-model-rai216",
      baseUrl = Some("http://127.0.0.1:1/v1") // EDITED to the stand URL below
    )
  )

  private val FinalAnswer = "stand final answer rai216"
  private val StandKey = "stand-key-rai216"

  /** Round 1: streaming tool_calls — the agent stage must really execute
    * proc_run (touch marker.txt) inside the input workspace.
    */
  private val toolRoundFrames: Vector[String] = Vector(
    """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}""",
    """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"callA","function":{"name":"proc_run","arguments":""}}]},"finish_reason":null}]}""",
    """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"argv\":"}}]},"finish_reason":null}]}""",
    """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"[\"/usr/bin/touch\",\"marker.txt\"]}"}}]},"finish_reason":null}]}""",
    """{"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
  )

  private val finalFrames: Vector[String] = Vector(
    """{"id":"f1","choices":[{"index":0,"delta":{"role":"assistant","content":"stand final answer rai216"},"finish_reason":null}]}""",
    """{"id":"f1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
  )

  private def sse(frames: Seq[String]): Array[Byte] =
    (frames.map(f => "data: " + f + "\n\n").mkString + "data: [DONE]\n\n")
      .getBytes(StandardCharsets.UTF_8)

  private final case class StandHandle(
      url: String,
      server: HttpServer,
      requests: AtomicInteger,
      hang: AtomicBoolean
  ):
    def stop(): Unit = server.stop(0)

  /** Scripted (round 1 -> tool_calls; round 2, gated on the `role:"tool"`
    * result message, -> final text), mirroring WorkflowLaunchSpec.
    */
  private def startStand(): StandHandle =
    val requests = new AtomicInteger(0)
    val hang = new AtomicBoolean(false)
    val server = HttpServer.create(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      0
    )
    server.createContext(
      "/v1/chat/completions",
      (ex: HttpExchange) =>
        try
          requests.incrementAndGet()
          if hang.get then
            Thread.sleep(30000L)
            reply(ex, 200, sse(finalFrames))
          else
            val body =
              new String(
                ex.getRequestBody.readAllBytes(),
                StandardCharsets.UTF_8
              )
            if body.contains("\"role\":\"tool\"") then
              reply(ex, 200, sse(finalFrames))
            else reply(ex, 200, sse(toolRoundFrames))
          ()
        finally ex.close()
    )
    server.start()
    StandHandle(
      "http://127.0.0.1:" + server.getAddress.getPort + "/v1",
      server,
      requests,
      hang
    )

  private def reply(ex: HttpExchange, code: Int, body: Array[Byte]): Unit =
    ex.getResponseHeaders.add("Content-Type", "text/event-stream")
    ex.sendResponseHeaders(
      code,
      if body.isEmpty then -1 else body.length.toLong
    )
    if body.nonEmpty then ex.getResponseBody.write(body)

  // ----- shared suite environment --------------------------------------------

  private final case class LaunchEnv(stand: StandHandle, fast: Path, sig: Path)

  /** ONE scripted stand + TWO compiled builds of the EDITED generated source:
    * `fast` rewrites the editable checkTimeoutSeconds constant to 2s so the
    * bounded drain elapses quickly; `sig` keeps the default 120s bound so the
    * SIGTERM case has a wide bounded-drain window. Both bake the stand URL.
    */
  private val envLayer: ZLayer[Any, Throwable, LaunchEnv] =
    ZLayer.scoped {
      ZIO
        .acquireRelease(ZIO.attempt(startStand()))(h => ZIO.succeed(h.stop()))
        .flatMap { stand =>
          ZIO.attempt {
            val fast = compiledCaseBuild(stand, boundSeconds = 2)
            val sig = compiledCaseBuild(stand, boundSeconds = 120)
            LaunchEnv(stand, fast, sig)
          }
        }
    }

  // ----- helpers --------------------------------------------------------------

  private def compiledCaseBuild(stand: StandHandle, boundSeconds: Int): Path =
    val rendered = ChatWorkflowRenderer
      .render(probe, RenderOptions("rai216_final"))
      .fold(e => throw new IllegalStateException(e.detail), identity)
    val edited = rendered.source
      .replace(
        "val baseUrl: String = \"http://127.0.0.1:1/v1\"",
        "val baseUrl: String = \"" + stand.url + "\""
      )
      .replace(
        "val checkTimeoutSeconds: Int = 120",
        "val checkTimeoutSeconds: Int = " + boundSeconds
      )
    if edited == rendered.source ||
      !edited.contains("val checkTimeoutSeconds: Int = " + boundSeconds) ||
      !edited.contains("@main def runRai216_final(")
    then throw new IllegalStateException("fixture edits did not apply")
    val work = Files.createTempDirectory("rai216-build")
    val source = work.resolve("rai216_final.scala")
    Files.writeString(source, edited, StandardCharsets.UTF_8)
    val out = work.resolve("build")
    val exit =
      WorkflowCompile.command(List(source.toString, "--out", out.toString))
    if exit != 0 then
      throw new IllegalStateException("raider compile failed: " + exit)
    out

  private def freshWorkspace(): Path = Files.createTempDirectory("rai216-ws")

  private def inputJson(ws: Path): String =
    "{\n  \"schemaVersion\": 1,\n  \"workspace\": \"" +
      ws.toString.replace("\\", "\\\\") +
      "\",\n  \"task\": {\n    \"targetPath\": \"marker.txt\",\n" +
      "    \"summary\": \"RAI-216 bounded finalization probe task\"\n  }\n}"

  private def workDirsOf(out: Path): Vector[Path] =
    val listing = Files.list(out)
    try
      listing
        .iterator()
        .asScala
        .toVector
        .filter(p =>
          Files.isDirectory(p) && p.getFileName.toString.startsWith("work-")
        )
    finally listing.close()

  private def runJsonOf(out: Path): String =
    val dirs = workDirsOf(out)
    if dirs.isEmpty then ""
    else
      val f = dirs.head.resolve("run.json")
      if !Files.isRegularFile(f) then ""
      else Files.readString(f, StandardCharsets.UTF_8)

  /** Drains a child's stdout on a daemon thread (the launcher report goes
    * there; an unfixed hang would leave this thread blocked for good).
    */
  private def drainAsync(p: java.lang.Process): Thread =
    val t = new Thread(() =>
      val r = new java.io.BufferedReader(
        new java.io.InputStreamReader(p.getInputStream, StandardCharsets.UTF_8)
      )
      var line = Option(r.readLine())
      while line.isDefined do line = Option(r.readLine())
    )
    t.setDaemon(true)
    t.start()
    t

  private def spawnCaseChild(
      build: Path,
      input: Path,
      out: Path,
      caseScript: Path
  ): java.lang.Process =
    val pb = new java.lang.ProcessBuilder(
      "java",
      "-cp",
      System.getProperty("java.class.path", ""),
      "raider.cli.Main",
      "workflow",
      "run",
      "--classes",
      build.toString,
      "--input",
      input.toString,
      "--out",
      out.toString
    )
    pb.redirectErrorStream(true)
    pb.environment().put("RAIDER_API_KEY", StandKey)
    // The check process inherits the LAUNCHER's environment, so the fixture
    // case script is selected per child through this single variable.
    pb.environment().put("RAI216_CASE", caseScript.toString)
    pb.start()

  /** The real pipe-holding grandchild: a shell that touches its armed marker
    * and then stays alive holding the inherited stdout write end without
    * writing. It must produce NOTHING (see the platform note): bytes in flight
    * make the reaper's exit-time drain swap in stragglers and the capture
    * reader deterministically EOFs, defeating the hold. The hold lives exactly
    * as long as this shell, well past every bound in the cases below.
    */
  private def casePipeHolder(seconds: Long): String =
    "sh -c \"touch rai216-holder-armed; sleep " + seconds +
      "; echo late-grandchild-line\" &\n"

  /** Reads the launcher artifacts defensively: a crashed attempt may have
    * produced no work directory or no artifact files at all.
    */
  private def artifactsOf(out: Path): (Vector[Path], String, String) =
    val dirs = workDirsOf(out)
    def readIn(dir: Path, name: String): String =
      val f = dir.resolve(name)
      if Files.isRegularFile(f) then Files.readString(f) else ""
    val head = dirs.headOption
    (
      dirs,
      head.fold("")(readIn(_, "result.json")),
      head.fold("")(readIn(_, "checks.junit.xml"))
    )

  // ----- spec ----------------------------------------------------------------

  private def suiteSpec = suite(
    "raider generated check runner bounded finalization (RAI-216)"
  )(
    test(
      "RAI-216-A: a grandchild holding the stdout pipe cannot stall finalization; the bounded drain yields the truthful partial state with blocking exit 10"
    ) {
      ZIO.serviceWithZIO[LaunchEnv] { env =>
        val caseScript = Files.createTempFile("rai216-case-a", ".sh")
        Files.writeString(
          caseScript,
          casePipeHolder(20L) + "echo partial-child-line\nexit 0\n"
        )
        val maxAttempts = 3
        var attempt = 0
        var verdict = false
        var diag = "no attempts ran"
        while attempt < maxAttempts && !verdict do
          attempt += 1
          val ws = freshWorkspace()
          val input = Files.createTempFile("rai216-a", ".json")
          val out = Files.createTempDirectory("rai216-a-out")
          Files.writeString(input, inputJson(ws))
          val startedAt = System.currentTimeMillis()
          val child = spawnCaseChild(env.fast, input, out, caseScript)
          val drain = drainAsync(child)
          val finished = child.waitFor(60, TimeUnit.SECONDS)
          val elapsed = System.currentTimeMillis() - startedAt
          val exit = if finished then child.exitValue() else -1
          if !finished then child.destroyForcibly()
          drain.join(2000)
          val (dirs, result, junit) = artifactsOf(out)
          val conds = List(
            finished,
            // The grandchild holds the write end for 20s: an unbounded join
            // would have returned only after it released the pipe (green).
            elapsed < 15000L,
            dirs.nonEmpty,
            exit == 10,
            result.contains("\"taskVerdict\":\"task-failed\""),
            result.contains("\"fixture-check\""),
            junit.contains("finalization bound"),
            junit.contains("bytes captured before the cutoff"),
            junit.contains("partial-child-line"),
            !junit.contains("late-grandchild-line")
          )
          verdict = conds.forall(identity)
          if !verdict then
            diag = "attempt " + attempt + ": finished=" + finished +
              " elapsed=" + elapsed + " exit=" + exit + " dirs=" + dirs.size +
              " cutoffNote=" + junit.contains("finalization bound") +
              " partialOnly=" + junit.contains("partial-child-line") +
              " noLate=" + !junit.contains("late-grandchild-line")
        if !verdict then System.err.println("[rai216-a] " + diag)
        assertTrue(verdict)
      }
    },
    test(
      "RAI-216-B: a destroyed overrun with a pipe-holding grandchild finalizes promptly with the ordinary destroyed message, exit 10, never a hang"
    ) {
      ZIO.serviceWithZIO[LaunchEnv] { env =>
        val caseScript = Files.createTempFile("rai216-case-b", ".sh")
        // The check must be in its ordinary wall overrun (destroyed at ~2s)
        // while the holder keeps the inherited write end alive past the
        // finalization. Platform note: on JDK 21 macOS the destroy closes the
        // capture stream itself (ProcessImpl.destroy), so the destroyed-path
        // cutoff note — correct on other platforms and retained in the
        // generated code — cannot be observed here; see the class Scaladoc.
        Files.writeString(
          caseScript,
          casePipeHolder(20L) + "sh -c \"sleep 30\"\nexit 3\n"
        )
        val maxAttempts = 3
        var attempt = 0
        var verdict = false
        var diag = "no attempts ran"
        while attempt < maxAttempts && !verdict do
          attempt += 1
          val ws = freshWorkspace()
          val input = Files.createTempFile("rai216-b", ".json")
          val out = Files.createTempDirectory("rai216-b-out")
          Files.writeString(input, inputJson(ws))
          val startedAt = System.currentTimeMillis()
          val child = spawnCaseChild(env.fast, input, out, caseScript)
          val drain = drainAsync(child)
          val finished = child.waitFor(60, TimeUnit.SECONDS)
          val elapsed = System.currentTimeMillis() - startedAt
          val exit = if finished then child.exitValue() else -1
          if !finished then child.destroyForcibly()
          drain.join(2000)
          val (dirs, result, junit) = artifactsOf(out)
          val conds = List(
            finished,
            // The destroyed overrun must finalize promptly even with the
            // holder alive: an unbounded wait would hang the launcher here.
            elapsed < 15000L,
            dirs.nonEmpty,
            exit == 10,
            result.contains("\"taskVerdict\":\"task-failed\""),
            result.contains("\"fixture-check\""),
            junit.contains("exceeded the 2s bound and was destroyed"),
            !junit.contains("late-grandchild-line")
          )
          verdict = conds.forall(identity)
          if !verdict then
            diag = "attempt " + attempt + ": finished=" + finished +
              " elapsed=" + elapsed + " exit=" + exit + " dirs=" + dirs.size +
              " destroyedNote=" +
              junit.contains("exceeded the 2s bound and was destroyed") +
              " taskFailed=" + result.contains(
                "\"taskVerdict\":\"task-failed\""
              ) +
              " noLate=" + !junit.contains("late-grandchild-line")
        if !verdict then System.err.println("[rai216-b] " + diag)
        assertTrue(verdict)
      }
    },
    test(
      "RAI-216-C: an ordinary check whose pipe closes instantly is unchanged under the bounded join (green run, exit 0)"
    ) {
      ZIO.serviceWithZIO[LaunchEnv] { env =>
        val caseScript = Files.createTempFile("rai216-case-c", ".sh")
        Files.writeString(caseScript, "echo ordinary-line\nexit 0\n")
        val ws = freshWorkspace()
        val input = Files.createTempFile("rai216-c", ".json")
        val out = Files.createTempDirectory("rai216-c-out")
        Files.writeString(input, inputJson(ws))
        val child = spawnCaseChild(env.sig, input, out, caseScript)
        val drain = drainAsync(child)
        val finished = child.waitFor(90, TimeUnit.SECONDS)
        val exit = if finished then child.exitValue() else -1
        if !finished then child.destroyForcibly()
        drain.join(2000)
        val dir = workDirsOf(out).head
        val result = Files.readString(dir.resolve("result.json"))
        val junit = Files.readString(dir.resolve("checks.junit.xml"))
        assertTrue(
          finished,
          exit == 0,
          result.contains("\"taskVerdict\":\"trusted-checks-passed\""),
          result.contains("\"fixture-check\""),
          !junit.contains("<failure"),
          !junit.contains("<error")
        )
      }
    },
    test(
      "RAI-216-S: SIGTERM during the bounded drain wait keeps the exit-143 semantics intact with truthful interruption evidence"
    ) {
      ZIO.serviceWithZIO[LaunchEnv] { env =>
        val caseScript = Files.createTempFile("rai216-case-s", ".sh")
        Files.writeString(
          caseScript,
          "touch rai216-sig-armed\n" + casePipeHolder(60L) +
            "echo partial-sig\nexit 0\n"
        )
        val maxAttempts = 3
        var attempt = 0
        var verdict = false
        var diag = "no attempts ran"
        while attempt < maxAttempts && !verdict do
          attempt += 1
          val ws = freshWorkspace()
          val input = Files.createTempFile("rai216-s", ".json")
          val out = Files.createTempDirectory("rai216-s-out")
          Files.writeString(input, inputJson(ws))
          val child = spawnCaseChild(env.sig, input, out, caseScript)
          val drain = drainAsync(child)
          // Wait until the check is running (armed marker) AND the holder is
          // started (holder marker); with the default 120s bound the launcher
          // is then parked in the bounded drain join (the holder keeps the
          // write end for 60s), so the SIGTERM lands inside that window.
          val deadline = System.currentTimeMillis() + 90000L
          def armedFiles =
            Files.exists(ws.resolve("rai216-sig-armed")) &&
              Files.exists(ws.resolve("rai216-holder-armed"))
          while !armedFiles && System.currentTimeMillis() < deadline
          do Thread.sleep(10L)
          val armed = armedFiles
          if armed then Thread.sleep(500L)
          child.destroy() // POSIX SIGTERM
          val done = child.waitFor(25, TimeUnit.SECONDS)
          val exit = if done then child.exitValue() else -1
          if !done then child.destroyForcibly()
          drain.join(2000)
          val workDirs = workDirsOf(out)
          val runJson = runJsonOf(out)
          val eventsExist =
            workDirs.nonEmpty && Files.isRegularFile(
              workDirs.head.resolve("events.jsonl")
            )
          val interruptedMarker = workDirs.exists { dir =>
            val listing = Files.list(dir)
            try
              listing
                .iterator()
                .asScala
                .map(_.getFileName.toString)
                .exists(n =>
                  n.startsWith("interrupted-") && n.endsWith(".json")
                )
            finally listing.close()
          }
          val conds = List(
            armed,
            done, // bounded stop: the launcher returned within the wait
            exit == 143,
            workDirs.nonEmpty,
            eventsExist,
            // Truthful incomplete evidence: Interrupted, never green.
            runJson.contains("\"status\":\"Interrupted\"") || interruptedMarker,
            !runJson.contains("\"status\":\"Succeeded\"")
          )
          verdict = conds.forall(identity)
          if !verdict then
            diag = "attempt " + attempt + ": armed=" + armed + " done=" + done +
              " exit=" + exit + " dirs=" + workDirs.size +
              " interruptedEvidence=" +
              (runJson.contains(
                "\"status\":\"Interrupted\""
              ) || interruptedMarker) +
              " green=" + runJson.contains("\"status\":\"Succeeded\"")
        if !verdict then System.err.println("[rai216-s] " + diag)
        assertTrue(verdict)
      }
    }
  )

  def spec =
    // The real-child launches and readiness polls need the live clock and a
    // serialized shared environment (the WorkflowLaunchSpec discipline).
    (suiteSpec @@ TestAspect.sequential @@ TestAspect.withLiveClock)
      .provideLayerShared(envLayer)

end Rai216BoundedFinalizationSpec
