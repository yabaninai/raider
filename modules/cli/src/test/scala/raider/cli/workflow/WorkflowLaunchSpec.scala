package raider.cli.workflow

import zio.test.*
import zio.test.Assertion.*
import zio.{Task as ZTask, ZIO, ZLayer}
import zio.json.DecoderOps

import raider.cli.run.CiReport

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

/** CWF-D01/D03 launch matrix for RAI-155.d, driven by a LOCAL OpenAI-
  * compatible SSE stand on the loopback (ephemeral port — no network egress, no
  * spend, never a live endpoint; the AnthropicStandSpec discipline):
  *
  *   - D01: the EDITED generated workflow (the `baseUrl` editable constant
  *     rewritten to the stand URL — the manual edit survives compilation) is
  *     compiled with the product `raider compile` and launched by BOTH
  *     launchers on SEPARATE fresh workspaces: A. the launcher artifact itself,
  *     run LOCALLY as a plain main (`java -cp ... runWf155d_launch inputA.json`
  *     — the RAI-158 renderer naming; the unedited shape now launches without
  *     any rename workaround); B. the NON-TTY headless CLI launcher (the
  *     `raider workflow run` discipline) with full ci-runtime §7 artifacts.
  *     Identical workflow logic (the SAME compiled classes), equivalent
  *     normalized outputs (the checks verdict line and the stand's final answer
  *     appear in both reports), REAL tool effects (the agent stage's proc_run
  *     touch creates marker.txt on each fresh workspace) and an INDEPENDENT
  *     check verdict (`/bin/test -f` runs on the new workspace — "the agent
  *     said done" is never the verdict).
  *   - D03: SIGTERM during active model work on the headless launcher (a real
  *     `java -cp ... raider.cli.Main workflow run` child, killed with SIGTERM
  *     once the stand sees the request) yields the bounded stop (143), a
  *     truthful incomplete artifact (Interrupted status, events.jsonl), and NO
  *     automatic retry (the stand saw exactly one request). Two concurrent CLI
  *     launches share no accidental state: distinct roots, distinct work
  *     directories, both green on their own workspace.
  *
  * The stand + compiled build are ONE shared suite resource (a hang switch on
  * the stand arms the SIGTERM case against the SAME baked URL).
  */
object WorkflowLaunchSpec extends ZIOSpecDefault:

  // ----- fixture -------------------------------------------------------------

  private val probe = StsSelection(
    sessionId = "sess-155d-launch",
    intent = StsTurn(
      "l1",
      StsTurnKind.Intent,
      StsTurnStatus.Completed,
      "Write the launch marker for the 155.d launch probe"
    ),
    refinements = List(
      StsTurn(
        "l2",
        StsTurnKind.Refinement,
        StsTurnStatus.Completed,
        "Keep the marker content exact"
      )
    ),
    taskParams = List(
      StsParam("targetPath", "marker.txt"),
      StsParam("summary", "155.d launch probe task")
    ),
    checks = List(
      NamedCheck("marker", List("/bin/test", "-f", "marker.txt"))
    ),
    agentStage = StsAgentStage(maxRounds = 3),
    origin = StsOrigin(
      providerKind = "openai-compatible",
      model = "stand-model-155d",
      baseUrl = Some("http://127.0.0.1:1/v1") // EDITED to the stand URL below
    )
  )

  private val FinalAnswer = "stand final answer 155d"
  private val StandKey = "stand-key-155d"

  // ----- RAI-217 pinned trusted-check policy digests --------------------------
  // The exact CiReport.policyDigest hex for this spec's fixture policies
  // (SHA-256 over the canonical form
  // "trusted-check-policy-v1\n<byteLen>:<checkId>:<advisory>\n"). The literals
  // are PINNED: any drift in the digest computation or in the serialized
  // advisory flag makes the child result.json assertions fail instead of
  // silently re-deriving the expected value from the same code under test.
  //   "trusted-check-policy-v1\n13:fixture-check:true\n"
  private val PinnedAdvisoryDigest =
    "aaeb905c9f435b3501537aeb3dbae4a51a7950e21e6c807e8678176c2970b0c6"

  //   "trusted-check-policy-v1\n13:fixture-check:false\n"
  private val PinnedBlockingDigest =
    "df3f96a4763d99bfbf9ff9783d7f5dc18a37688538b774805cc65edc8a96bdaa"

  //   "trusted-check-policy-v1\n6:marker:false\n" (the CWF-D01 marker check)
  private val PinnedMarkerDigest =
    "3cd56db0d4511666eba42ff786964cf85a28e7c5700b77809bcee7af3b213a2a"

  private val advisoryPolicy =
    List(CiReport.CheckPolicy("fixture-check", advisory = true))

  private val blockingPolicy =
    List(CiReport.CheckPolicy("fixture-check", advisory = false))

  private val markerPolicy =
    List(CiReport.CheckPolicy("marker", advisory = false))

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
    """{"id":"f1","choices":[{"index":0,"delta":{"role":"assistant","content":"stand final answer 155d"},"finish_reason":null}]}""",
    """{"id":"f1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
  )

  private def sse(frames: Seq[String]): Array[Byte] =
    (frames.map(f => "data: " + f + "\n\n").mkString + "data: [DONE]\n\n")
      .getBytes(StandardCharsets.UTF_8)

  private final case class StandHandle(
      url: String,
      server: HttpServer,
      requests: AtomicInteger,
      hang: AtomicBoolean,
      // Review round 1 (finding 3): the stand ASSERTS the credential on the
      // wire — requests carrying exactly `Authorization: Bearer <StandKey>`
      // are counted, and CWF-D01 asserts the per-launch delta.
      authOk: AtomicInteger
  ):
    def stop(): Unit = server.stop(0)
    def requestCount: Int = requests.get

  /** Scripted (round 1 -> tool_calls; round 2, gated on the `role:"tool"`
    * result message, -> final text) with a HANG switch: when armed, every
    * request sleeps ~30 s after being counted (active model work for the
    * SIGTERM case). The count happens BEFORE any gating so the SIGTERM test can
    * observe the in-flight request.
    */
  private def startStand(): StandHandle =
    val requests = new AtomicInteger(0)
    val hang = new AtomicBoolean(false)
    val authOk = new AtomicInteger(0)
    val server = HttpServer.create(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      0
    )
    server.createContext(
      "/v1/chat/completions",
      (ex: HttpExchange) =>
        try
          requests.incrementAndGet()
          val auth =
            Option(ex.getRequestHeaders.getFirst("Authorization")).getOrElse("")
          if auth == "Bearer " + StandKey then authOk.incrementAndGet()
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
      hang,
      authOk
    )

  private def reply(ex: HttpExchange, code: Int, body: Array[Byte]): Unit =
    ex.getResponseHeaders.add("Content-Type", "text/event-stream")
    ex.sendResponseHeaders(
      code,
      if body.isEmpty then -1 else body.length.toLong
    )
    if body.nonEmpty then ex.getResponseBody.write(body)

  // ----- shared suite environment --------------------------------------------

  private final case class LaunchEnv(stand: StandHandle, build: Path)

  /** ONE scripted stand + ONE compiled build of the EDITED source, shared by
    * the whole suite (the build bakes the stand URL; the hang switch serves the
    * SIGTERM case against the same URL).
    */
  private val envLayer: ZLayer[Any, Throwable, LaunchEnv] =
    ZLayer.scoped {
      ZIO
        .acquireRelease(ZIO.attempt {
          val h = startStand()
          h
        })(h => ZIO.succeed(h.stop()))
        .flatMap { stand =>
          ZIO.attempt {
            val rendered = ChatWorkflowRenderer
              .render(probe, RenderOptions("wf155d_launch"))
              .fold(e => throw new IllegalStateException(e.detail), identity)
            val work = Files.createTempDirectory("l155d-build")
            val src = work.resolve("wf155d_launch.scala")
            // The EDIT (CWF-D01): rewrite the editable baseUrl constant to
            // the stand URL; the edit must survive compilation.
            val edited = rendered.source
              .replace(
                "val baseUrl: String = \"http://127.0.0.1:1/v1\"",
                "val baseUrl: String = \"" + stand.url + "\""
              )
            // RAI-158: the renderer's launcher main is `runWf155d_launch`
            // (case-insensitively distinct from the object) — the OLD manual
            // main-rename workaround (the RAI-155.d collision mitigation) is
            // gone; the fixture edit is now ONLY the baseUrl constant.
            if edited == rendered.source ||
              !edited.contains("@main def runWf155d_launch(")
            then throw new IllegalStateException("fixture edits did not apply")
            Files.write(src, edited.getBytes(StandardCharsets.UTF_8))
            val out = work.resolve("build")
            val exit = WorkflowCompile.command(
              List(src.toString, "--out", out.toString)
            )
            if exit != 0 then
              throw new IllegalStateException("raider compile failed: " + exit)
            // The build identity must record the EDITED source bytes.
            val buildIdentity = WorkflowBuildIdentity
              .load(out)
              .fold(
                e => throw new IllegalStateException(e.detail),
                i => i
              )
            val editedDigest =
              WorkflowBuildIdentity.sha256Hex(Files.readAllBytes(src))
            if buildIdentity.sourceDigest != editedDigest then
              throw new IllegalStateException(
                "build identity does not record the EDITED source"
              )
            LaunchEnv(stand, out)
          }
        }
    }

  // ----- helpers --------------------------------------------------------------

  private def userClasspath: String =
    val lib = m2Jar(
      "org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar"
    )
    val base = System.getProperty("java.class.path", "")
    if base.isEmpty then lib.toString
    else lib + java.io.File.pathSeparator + base

  private def m2Jar(rel: String): String =
    val root =
      sys.env.get("RAIDER_M2_REPO").map(java.nio.file.Paths.get(_)).getOrElse {
        val cwd = java.nio.file.Paths.get("").toAbsolutePath
        Iterator
          .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
          .takeWhile(_.isDefined)
          .collect { case Some(dir) => dir }
          .find(dir => Files.isDirectory(dir.resolve("artifacts/tools/m2")))
          .getOrElse(cwd)
          .resolve("artifacts/tools/m2")
      }
    val p = root.resolve(rel)
    if Files.isRegularFile(p) then p.toString
    else throw new IllegalStateException("missing pinned jar: " + p)

  private def freshWorkspace(): Path = Files.createTempDirectory("l155d-ws")

  private def inputJson(ws: Path): String =
    "{\n  \"schemaVersion\": 1,\n  \"workspace\": \"" +
      ws.toString.replace("\\", "\\\\") +
      "\",\n  \"task\": {\n    \"targetPath\": \"marker.txt\",\n" +
      "    \"summary\": \"155.d launch probe task\"\n  }\n}"

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

  /** Tolerant read of one artifact of the first work directory ("" when the run
    * never produced it — the assertions then fail on content, not on a thrown
    * IO error).
    */
  private def artifactOf(out: Path, name: String): String =
    val dirs = workDirsOf(out)
    if dirs.isEmpty then ""
    else
      val f = dirs.head.resolve(name)
      if !Files.isRegularFile(f) then ""
      else Files.readString(f, StandardCharsets.UTF_8)

  private def resultJsonOf(out: Path): String =
    artifactOf(out, "result.json")

  /** RAI-217: the result.json digest claim equals the pinned hex ("sha256-"
    * prefix, exact match — a missing or tampered claim fails the assertion).
    */
  private def digestIs(resultJson: String, pinnedHex: String): Boolean =
    jsonStr(resultJson, "checkPolicyDigest") == "sha256-" + pinnedHex

  private def jsonStr(json: String, field: String): String =
    zio.json.ast.Json.decoder
      .decodeJson(json)
      .toOption
      .flatMap {
        case obj: zio.json.ast.Json.Obj =>
          obj.fields.collectFirst {
            case (k, zio.json.ast.Json.Str(v)) if k == field => v
          }
        case _ => None
      }
      .getOrElse("")

  /** Drains a child's stdout on a daemon thread, CAPTURING the lines (the local
    * artifact main prints its workflow report there).
    */
  private def drainAsync(
      p: java.lang.Process
  ): (Thread, java.util.concurrent.CopyOnWriteArrayList[String]) =
    val lines = new java.util.concurrent.CopyOnWriteArrayList[String]
    val t = new Thread(() =>
      val r = new java.io.BufferedReader(
        new java.io.InputStreamReader(p.getInputStream, StandardCharsets.UTF_8)
      )
      var line = Option(r.readLine())
      while line.isDefined do
        lines.add(line.get)
        line = Option(r.readLine())
    )
    t.setDaemon(true)
    t.start()
    (t, lines)

  private def spawnJava(
      env: Map[String, String],
      args: String*
  ): java.lang.Process =
    val pb = new java.lang.ProcessBuilder(("java" +: args)*)
    pb.redirectErrorStream(true)
    env.foreach { (k, v) => pb.environment().put(k, v) }
    pb.start()

  private def compiledCheckBuild(
      stand: StandHandle,
      advisory: Boolean,
      argv: List[String]
  ): Path =
    val selection = probe.copy(
      checks = List(NamedCheck("fixture-check", argv, advisory))
    )
    val rendered = ChatWorkflowRenderer
      .render(selection, RenderOptions("wf_report_child"))
      .fold(e => throw new IllegalStateException(e.detail), identity)
    val edited = rendered.source.replace(
      "val baseUrl: String = \"http://127.0.0.1:1/v1\"",
      "val baseUrl: String = \"" + stand.url + "\""
    )
    if edited == rendered.source then
      throw new IllegalStateException("mock stand URL edit did not apply")
    val work = Files.createTempDirectory("rai186-check-child")
    val source = work.resolve("wf_report_child.scala")
    Files.writeString(source, edited, StandardCharsets.UTF_8)
    val out = work.resolve("build")
    val exit =
      WorkflowCompile.command(List(source.toString, "--out", out.toString))
    if exit != 0 then
      throw new IllegalStateException(
        "raider workflow fixture compile failed: " + exit
      )
    out

  private def spawnWorkflowChild(
      build: Path,
      input: Path,
      out: Path
  ): java.lang.Process =
    spawnJava(
      Map("RAIDER_API_KEY" -> StandKey),
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

  // ----- spec ----------------------------------------------------------------

  private def suiteSpec =
    suite("raider workflow launch matrix (RAI-155.d CWF-D01/D03)")(
      test(
        "CWF-D01: the SAME edited artifact launches locally (plain main) and non-TTY (CLI) on fresh workspaces with equivalent outputs, real tool effects and an independent green check"
      ) {
        ZIO.serviceWithZIO[LaunchEnv] { env =>
          val build = env.build
          val countBefore = env.stand.requestCount
          val authBefore = env.stand.authOk.get()
          val wsA = freshWorkspace()
          val wsB = freshWorkspace()
          val inA =
            Files.createTempDirectory("l155d-in-a").resolve("inputA.json")
          val inB =
            Files.createTempDirectory("l155d-in-b").resolve("inputB.json")
          Files.writeString(inA, inputJson(wsA))
          Files.writeString(inB, inputJson(wsB))

          // -- Launch A: the artifact ITSELF, locally, as a plain main. ----
          // (The renderer's launcher main: runWf155d_launch — RAI-158.)
          val outA = Files.createTempDirectory("l155d-local")
          val argvA = Vector(
            "-cp",
            userClasspath + java.io.File.pathSeparator + build
              .resolve("classes"),
            "runWf155d_launch",
            inA.toString
          )
          val pA = spawnJava(Map("RAIDER_API_KEY" -> StandKey), argvA*)
          val (drainThreadA, stdoutA) = drainAsync(pA)
          val finishedA = pA.waitFor(180, TimeUnit.SECONDS)
          val exitA = if finishedA then pA.exitValue() else -1
          drainThreadA.join(2000)

          // -- Launch B: the NON-TTY CLI launcher (full §7 artifacts). -----
          val outB = Files.createTempDirectory("l155d-cli")
          for runB <- WorkflowRun.run(
              WorkflowRun.Args(build, inB, outB, None),
              Map("RAIDER_API_KEY" -> StandKey)
            )
          yield
            val runJsonB = runJsonOf(outB)
            // RAI-217: the passing run's result.json pins its own compiled
            // policy digest (the marker check declaration, advisory = false),
            // and the summary names the plain passed verdict truthfully.
            val resultJsonB = resultJsonOf(outB)
            val summaryB = artifactOf(outB, "summary.md")
            val reportA = stdoutA.asScala.mkString("\n")
            val checksLine = "checks passed: marker; target=marker.txt"
            assertTrue(
              finishedA,
              exitA == 0, // local launcher artifact: exit 0 = checks green
              // Equivalent normalized outputs: the checks verdict line AND
              // the stand's final answer appear in BOTH reports.
              reportA.contains(checksLine),
              reportA.contains(FinalAnswer),
              runB.exitCode == 0,
              runB.error.isEmpty,
              runJsonB.contains("\"status\":\"Succeeded\""),
              runJsonB.contains(checksLine),
              runJsonB.contains(FinalAnswer),
              runJsonB.contains("\"buildIdentity\""),
              runJsonB.contains("\"inputDigest\""),
              runJsonB.contains("\"taskVerdict\":\"trusted-checks-passed\""),
              digestIs(resultJsonB, PinnedMarkerDigest),
              PinnedMarkerDigest == CiReport.policyDigest(markerPolicy),
              summaryB.contains("verdict: **trusted-checks-passed**"),
              // REAL tool effects on BOTH fresh workspaces (the agent
              // stage's proc_run created the markers; the check only
              // verified them — an independent verdict per workspace).
              Files.exists(wsA.resolve("marker.txt")),
              Files.exists(wsB.resolve("marker.txt")),
              // 2 model rounds x 2 launches through the SAME stand
              // (per-test delta: the stand is shared across the suite).
              env.stand.requestCount - countBefore == 4,
              // Review round 1 (finding 3): the key is ASSERTED on the wire —
              // ALL 4 model rounds of BOTH launchers carried exactly
              // `Authorization: Bearer stand-key-155d` (env -> credential ->
              // backend header).
              env.stand.authOk.get() - authBefore == 4
            )
        }
      },
      test(
        "WRI-02/WRI-03: SIGTERM during WorkflowRun model work -> bounded stop (143), truthful interruption evidence, no fake completion"
      ) {
        ZIO.serviceWithZIO[LaunchEnv] { env =>
          val build = env.build
          val countBefore = env.stand.requestCount
          val ws = freshWorkspace()
          val in =
            Files.createTempDirectory("l155d-in-sig").resolve("input.json")
          Files.writeString(in, inputJson(ws))
          val out = Files.createTempDirectory("l155d-sig")
          // A REAL headless launcher process (raider.cli.Main workflow run).
          val p = spawnJava(
            Map("RAIDER_API_KEY" -> StandKey),
            "-cp",
            System.getProperty("java.class.path", ""),
            "raider.cli.Main",
            "workflow",
            "run",
            "--classes",
            build.toString,
            "--input",
            in.toString,
            "--out",
            out.toString
          )
          val (drainThread, _) = drainAsync(p)
          env.stand.hang.set(true) // arm: the next request hangs in-flight
          // Wait until THIS child's model work is actively in flight (the
          // delta: the stand counter is cumulative across the suite).
          val deadline = System.currentTimeMillis() + 90000L
          def awaitRequest: ZIO[Any, Nothing, Unit] =
            if env.stand.requestCount - countBefore >= 1 ||
              System.currentTimeMillis() >= deadline
            then ZIO.unit
            else ZIO.sleep(zio.Duration.fromMillis(100L)) *> awaitRequest
          awaitRequest *> ZIO.succeed {
            val delta = env.stand.requestCount - countBefore
            val inFlight = delta >= 1
            // SIGTERM (Process.destroy sends SIGTERM on POSIX).
            p.destroy()
            val done = p.waitFor(25, TimeUnit.SECONDS)
            val exit = if done then p.exitValue() else -1
            env.stand.hang.set(false) // disarm for the remaining suite
            val workDirs = workDirsOf(out)
            val runJson = runJsonOf(out)
            val eventsExist =
              workDirs.nonEmpty && Files.isRegularFile(
                workDirs.head.resolve("events.jsonl")
              )
            // Truthful incomplete evidence: EITHER the run's own finalizers
            // landed (Interrupted run.json) OR the bounded stop expired and
            // the hook wrote the interrupted marker (never green either way).
            val interruptedMarker =
              Files
                .list(out)
                .iterator()
                .asScala
                .map(_.getFileName.toString)
                .exists(n =>
                  n.startsWith("interrupted-") && n.endsWith(".json")
                )
            drainThread.join(2000)
            assertTrue(
              inFlight,
              done, // bounded stop: the launcher returned within the wait
              exit == 143, // POSIX SIGTERM death after the bounded shutdown hook
              delta >= 1 && delta <= 3,
              workDirs.nonEmpty,
              eventsExist,
              // Truthful incomplete evidence: Interrupted, never green.
              (
                runJson.contains("\"status\":\"Interrupted\"") ||
                  interruptedMarker
              ),
              !runJson.contains("\"status\":\"Succeeded\"")
            )
          }
        }
      },
      test(
        "FCI-REPORT-01: real workflow children record advisory failure with exit 0 and blocking failure with exit 10"
      ) {
        ZIO.serviceWithZIO[LaunchEnv] { env =>
          val advisoryBuild = compiledCheckBuild(
            env.stand,
            advisory = true,
            List("/usr/bin/false")
          )
          val blockingBuild = compiledCheckBuild(
            env.stand,
            advisory = false,
            List("/usr/bin/false")
          )
          val advisoryWs = freshWorkspace()
          val blockingWs = freshWorkspace()
          val advisoryInput = Files.createTempFile("rai186-advisory", ".json")
          val blockingInput = Files.createTempFile("rai186-blocking", ".json")
          Files.writeString(advisoryInput, inputJson(advisoryWs))
          Files.writeString(blockingInput, inputJson(blockingWs))
          val advisoryOut = Files.createTempDirectory("rai186-advisory-out")
          val blockingOut = Files.createTempDirectory("rai186-blocking-out")
          val advisoryChild =
            spawnWorkflowChild(advisoryBuild, advisoryInput, advisoryOut)
          val advisoryDrain = drainAsync(advisoryChild)._1
          val advisoryFinished = advisoryChild.waitFor(90, TimeUnit.SECONDS)
          val advisoryExit =
            if advisoryFinished then advisoryChild.exitValue() else -1
          val blockingChild =
            spawnWorkflowChild(blockingBuild, blockingInput, blockingOut)
          val blockingDrain = drainAsync(blockingChild)._1
          val blockingFinished = blockingChild.waitFor(90, TimeUnit.SECONDS)
          val blockingExit =
            if blockingFinished then blockingChild.exitValue() else -1
          advisoryDrain.join(2000)
          blockingDrain.join(2000)
          val advisoryDir = workDirsOf(advisoryOut).head
          val blockingDir = workDirsOf(blockingOut).head
          val advisoryResult =
            Files.readString(advisoryDir.resolve("result.json"))
          val blockingResult =
            Files.readString(blockingDir.resolve("result.json"))
          // RAI-217: the human summary names the recorded verdict — a
          // success-with-advisory run never reads as a plain passed run.
          val advisorySummary =
            Files.readString(advisoryDir.resolve("summary.md"))
          val blockingSummary =
            Files.readString(blockingDir.resolve("summary.md"))
          // JUnit stays truthful on the advisory path: the failed advisory
          // check keeps its recorded <failure>.
          val advisoryJunit =
            Files.readString(advisoryDir.resolve("checks.junit.xml"))
          assertTrue(
            advisoryFinished,
            advisoryExit == 0,
            advisoryResult.contains(
              "\"taskVerdict\":\"success-with-advisory\""
            ),
            advisoryResult.contains("\"fixture-check\""),
            // RAI-217: result.json pins the compiled policy digest — the two
            // children ran the SAME failing argv under DIFFERENT frozen
            // declarations, and each result carries its own digest.
            digestIs(advisoryResult, PinnedAdvisoryDigest),
            // A mismatched pin fails: neither result satisfies the other
            // policy's digest (the advisory flag is part of the canonical
            // form).
            !digestIs(advisoryResult, PinnedBlockingDigest),
            blockingFinished,
            blockingExit == 10,
            blockingResult.contains("\"taskVerdict\":\"task-failed\""),
            blockingResult.contains("\"fixture-check\""),
            digestIs(blockingResult, PinnedBlockingDigest),
            !digestIs(blockingResult, PinnedAdvisoryDigest),
            // The pins stay bound to the classifier's canonical digest for
            // the exact fixture declarations compiled into these children.
            PinnedAdvisoryDigest == CiReport.policyDigest(advisoryPolicy),
            PinnedBlockingDigest == CiReport.policyDigest(blockingPolicy),
            PinnedAdvisoryDigest != PinnedBlockingDigest,
            // The advisory summary names the advisory verdict; the blocking
            // summary names the failure.
            advisorySummary.contains("verdict: **success-with-advisory**"),
            blockingSummary.contains("verdict: **task-failed**"),
            advisoryJunit.contains("<failure")
          )
        }
      },
      test(
        "FCI-REPORT-04: hostile trusted-check stdout is neutralized in summary and XML-escaped in JUnit"
      ) {
        ZIO.serviceWithZIO[LaunchEnv] { env =>
          val hostile = "<&>\" ::error::check ::warning::next"
          val script =
            "printf '%s\\n' '" + hostile.replace("'", "'\\''") + "'; exit 1"
          val build = compiledCheckBuild(
            env.stand,
            advisory = false,
            List("/bin/sh", "-c", script)
          )
          val ws = freshWorkspace()
          val input = Files.createTempFile("rai186-hostile", ".json")
          val out = Files.createTempDirectory("rai186-hostile-out")
          Files.writeString(input, inputJson(ws))
          val child = spawnWorkflowChild(build, input, out)
          val drain = drainAsync(child)._1
          val finished = child.waitFor(90, TimeUnit.SECONDS)
          val exit = if finished then child.exitValue() else -1
          drain.join(2000)
          val workDir = workDirsOf(out).head
          val summary = Files.readString(workDir.resolve("summary.md"))
          val junit = Files.readString(workDir.resolve("checks.junit.xml"))
          assertTrue(
            finished,
            exit == 10,
            summary.contains(
              "%3A%3Aerror%3A%3Acheck %3A%3Awarning%3A%3Anext%0A"
            ),
            !summary.contains("::error::"),
            !summary.contains("::warning::"),
            !summary.contains("<&>\""),
            summary.contains("&lt;&amp;&gt;\""),
            junit.contains("&lt;&amp;&gt;&quot;"),
            junit.contains("::error::check"),
            !junit.contains("<failure message=\"<&>")
          )
        }
      },
      test(
        "FCI-REPORT-03: real child artifact-write failure after a blocking check outranks the verdict"
      ) {
        ZIO.serviceWithZIO[LaunchEnv] { env =>
          val readyName = "check-started.signal"
          val script =
            "touch " + readyName + "; sleep 2; exit 1"
          val build = compiledCheckBuild(
            env.stand,
            advisory = false,
            List("/bin/sh", "-c", script)
          )
          val ws = freshWorkspace()
          val input = Files.createTempFile("rai186-write-failure", ".json")
          val out = Files.createTempDirectory("rai186-write-failure-out")
          Files.writeString(input, inputJson(ws))
          val child = spawnWorkflowChild(build, input, out)
          val drain = drainAsync(child)._1
          val deadline = System.currentTimeMillis() + 90000L
          var workDir = Option.empty[Path]
          while workDir.isEmpty && System.currentTimeMillis() < deadline do
            workDir = workDirsOf(out).headOption
            Thread.sleep(10L)
          val startedDeadline = System.currentTimeMillis() + 90000L
          while !Files.exists(ws.resolve(readyName)) &&
            System.currentTimeMillis() < startedDeadline
          do Thread.sleep(10L)
          val checkStarted = Files.exists(ws.resolve(readyName))
          val moved = out.resolve("preserved-work-dir")
          val originalWorkDir =
            workDir.getOrElse(out.resolve("missing-work-dir"))
          if checkStarted && workDir.nonEmpty then
            Files.move(originalWorkDir, moved)
            Files.writeString(
              originalWorkDir,
              "deliberately blocks artifact writes"
            )
          val finished = child.waitFor(90, TimeUnit.SECONDS)
          val exit = if finished then child.exitValue() else -1
          if !finished then child.destroyForcibly()
          drain.join(2000)
          val hasTemporary =
            if !Files.isDirectory(moved) then false
            else
              val paths = Files.walk(moved)
              try
                paths
                  .iterator()
                  .asScala
                  .exists(p =>
                    Files.isRegularFile(p) && p.getFileName.toString
                      .endsWith(".tmp")
                  )
              finally paths.close()
          assertTrue(
            checkStarted,
            workDir.nonEmpty,
            finished,
            exit == 27,
            Files.isRegularFile(originalWorkDir),
            Files.isDirectory(moved),
            !Files.exists(moved.resolve("result.json")),
            !hasTemporary
          )
        }
      },
      test(
        "FCI-REPORT-05: SIGTERM after check completion preserves honest finalization state without temporary files"
      ) {
        ZIO.serviceWithZIO[LaunchEnv] { env =>
          val build = compiledCheckBuild(
            env.stand,
            advisory = false,
            List("/bin/sh", "-c", "head -c 65536 /dev/zero; exit 1")
          )
          val ws = freshWorkspace()
          val input = Files.createTempFile("rai186-finalize-signal", ".json")
          val out = Files.createTempDirectory("rai186-finalize-signal-out")
          Files.writeString(input, inputJson(ws))
          val child = spawnWorkflowChild(build, input, out)
          val drain = drainAsync(child)._1
          val deadline = System.currentTimeMillis() + 90000L
          var workDir = Option.empty[Path]
          var terminalObserved = false
          while !terminalObserved && System.currentTimeMillis() < deadline do
            workDir = workDirsOf(out).headOption
            terminalObserved = workDir.exists { dir =>
              val events = dir.resolve("events.jsonl")
              Files.isRegularFile(events) && {
                val text = Files.readString(events)
                text.contains("Failed") || text.contains("Succeeded")
              }
            }
            if !terminalObserved then Thread.sleep(1L)
          if terminalObserved then child.destroy()
          val finished = child.waitFor(15, TimeUnit.SECONDS)
          val exit = if finished then child.exitValue() else -1
          if !finished then child.destroyForcibly()
          drain.join(2000)
          val dir = workDir.getOrElse(out.resolve("missing-work-dir"))
          val files =
            if Files.isDirectory(dir) then
              val stream = Files.walk(dir)
              try
                stream
                  .iterator()
                  .asScala
                  .filter(Files.isRegularFile(_))
                  .toVector
              finally stream.close()
            else Vector.empty
          val manifest = dir.resolve("artifacts.json")
          val manifestAbsentOrComplete =
            if !Files.exists(manifest) then true
            else
              val tmpPresent =
                files.exists(_.getFileName.toString.endsWith(".tmp"))
              val raw = Files.readString(manifest)
              val entries = raw.fromJson[List[raider.core.result.ArtifactEntry]]
              !tmpPresent && entries.exists(_.nonEmpty) && entries.exists(
                _.forall { entry =>
                  val artifact = dir.resolve(entry.path)
                  Files.isRegularFile(artifact) &&
                  Files.size(artifact) == entry.sizeBytes &&
                  WorkflowBuildIdentity.sha256Hex(
                    Files.readAllBytes(artifact)
                  ) == entry.sha256
                }
              )
          val runJson =
            if Files.isRegularFile(dir.resolve("run.json")) then
              Files.readString(dir.resolve("run.json"))
            else ""
          assertTrue(
            terminalObserved,
            finished,
            exit == 143,
            files.forall(path => !path.getFileName.toString.endsWith(".tmp")),
            manifestAbsentOrComplete,
            !runJson.contains("\"status\":\"Succeeded\"")
          )
        }
      },
      test(
        "CWF-D03: two concurrent CLI launches share no accidental state (distinct roots/work dirs; both green on their own workspace)"
      ) {
        ZIO.serviceWithZIO[LaunchEnv] { env =>
          val build = env.build
          val ws1 = freshWorkspace()
          val ws2 = freshWorkspace()
          val in1 =
            Files.createTempDirectory("l155d-in-c1").resolve("input1.json")
          val in2 =
            Files.createTempDirectory("l155d-in-c2").resolve("input2.json")
          Files.writeString(in1, inputJson(ws1))
          Files.writeString(in2, inputJson(ws2))
          val out1 = Files.createTempDirectory("l155d-conc1")
          val out2 = Files.createTempDirectory("l155d-conc2")
          val cliEnv = Map("RAIDER_API_KEY" -> StandKey)
          for
            o1Fib <- WorkflowRun
              .run(WorkflowRun.Args(build, in1, out1, None), cliEnv)
              .fork
            o2Fib <- WorkflowRun
              .run(WorkflowRun.Args(build, in2, out2, None), cliEnv)
              .fork
            o1 <- o1Fib.join
            o2 <- o2Fib.join
          yield
            val root1 = jsonStr(runJsonOf(out1), "rootId")
            val root2 = jsonStr(runJsonOf(out2), "rootId")
            assertTrue(
              o1.exitCode == 0,
              o2.exitCode == 0,
              root1.nonEmpty && root2.nonEmpty && root1 != root2,
              workDirsOf(out1).head != workDirsOf(out2).head,
              Files.exists(ws1.resolve("marker.txt")),
              Files.exists(ws2.resolve("marker.txt"))
            )
        }
      }
    )

  def spec =
    // RAI-178: WorkflowRun.run ensurings + the in-flight readiness poll sleep
    // on the ambient clock and freeze on the never-advanced TestClock
    // (fiber-verified family root cause; docs/work/records/RAI-178.md).
    // Live clock = production discipline; assertions unchanged.
    (suiteSpec @@ TestAspect.sequential @@ TestAspect.withLiveClock)
      .provideLayerShared(envLayer)

end WorkflowLaunchSpec
