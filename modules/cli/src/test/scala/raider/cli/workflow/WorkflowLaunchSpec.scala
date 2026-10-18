package raider.cli.workflow

import zio.test.*
import zio.test.Assertion.*
import zio.{Task as ZTask, ZIO, ZLayer}

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
    (suiteSpec @@ TestAspect.sequential).provideLayerShared(envLayer)

end WorkflowLaunchSpec
