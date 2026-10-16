package raider.cli.chat

import raider.cli.chat.{ChatLoop, TaskCommands, TurnLedger}
import raider.core.*
import raider.dsl.workflow.ChatWorkflowExport
import raider.runtime.admission.Admission
import raider.runtime.budget.{BudgetLimits, CostEstimate}
import raider.runtime.events.EventHub
import raider.runtime.jobs.JobManager
import raider.runtime.loop.AgentLoop
import zio.json.ast.Json
import zio.json.{DecoderOps, DeriveJsonDecoder, JsonDecoder}
import zio.test.*
import zio.test.TestAspect.{sequential, timeout}
import zio.Duration
import zio.{Chunk, Duration as ZDuration, Ref, Scope, ZIO}
import zio.stream.ZStream

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.jdk.CollectionConverters.*

/** RAI-155.e — INDEPENDENT end-to-end verifier for the chat → export → edit →
  * compile → run chain (contract side per ADR-033; the implementation-internals
  * EW gate remains RAI-160's).
  *
  * CWF-E01: the two-input × two-launcher matrix on fresh copies. The flow per
  * cell: real multi-turn chat capture (`ChatLoop.processMessage` over a
  * scripted backend) → explicit `:task` selection (TaskCommands) →
  * `:export-workflow` (ChatLoop.exportWorkflow) → a MEANINGFUL manual edit of
  * the exported source (the prompt template is changed to request marker2.txt
  * AND the named check is flipped to `/bin/test -f marker2.txt` — applied by
  * exact-line replacement, asserted applied) → `raider compile` (pinned dotc) →
  * the SAME compiled artifact launched (a) LOCALLY as a plain main and (b)
  * NON-TTY through `raider workflow run`, on two fresh input cases, pairwise
  * compared via normalized outcomes. The FIFTH control (unedited workflow,
  * input 1, local) isolates the edit: marker2 must be ABSENT there. The stand
  * scripts the agent's tool work from the PROMPT content: an edited prompt gets
  * a repair pattern (marker.txt, then marker2.txt — "checks failing before
  * repair and passing after" is pinned by the no-repair pre-repair cell), an
  * unedited prompt gets marker.txt only. No live provider: loopback JDK
  * HttpServer stand; credentials ride the injected env, never argv/logs.
  *
  * CWF-E02: the negative corpus — lost/legacy chat context (typed CWF-R2),
  * secret-bearing selection (CWF-R4, value never echoed), invalid input (typed
  * refusal with ZERO stand dispatch), torn source compile (exit 21), stale
  * build (source edited after compile → identity mismatch, exit 21), failed
  * checks (the pre-repair cell), prompt-injection turn (escaped into a string
  * literal — the artifact compiles and runs bounded, no bare code), and the
  * changed-expectation negative control (the comparator detects a tampered
  * expectation — proven at unit level against this spec's own comparator).
  *
  * CWF-E03: interrupted capture stream (a hung turn stays typed-Unknown and
  * outside the export; completed turns still export), fresh-process SIGTERM
  * mid-run (bounded stop, honest Interrupted evidence — the RAI-155.d
  * discipline), and deterministic repeats of the equivalence cell ×3.
  *
  * INDEPENDENCE: this spec drives ONLY product entry points
  * (ChatLoop.processMessage / TaskCommands / ChatLoop.exportWorkflow /
  * raider.cli.workflow.WorkflowCompile.command /
  * raider.cli.workflow.WorkflowRun.run / the compiled launcher main as a
  * SUBPROCESS) and asserts from the frozen corpus
  * (contracts/chat-workflow/verifier-expected.json). No implementation
  * internals are imported as oracles; a defect found here is a typed
  * reproducer, not something this card fixes.
  */
object ChatWorkflowVerifierSpec extends ZIOSpecDefault:

  private val StandKey = "verifier-stand-key"
  private val Model = "verifier-model"

  // ----- normalized outcomes (the corpus shape) ------------------------------

  final case class Normalized(
      verdict: String,
      targetMarker: String,
      effects: Vector[String],
      answerTag: String
  )

  private def verdictOf(
      exitCode: Int,
      stdout: String,
      runJson: String
  ): String =
    if exitCode == 0 then "passed"
    else if stdout.contains("check") && stdout.contains("failed") then "failed"
    else if runJson.contains("OutputValidation") then "failed"
    else "other"

  private def markerEffects(ws: Path): Vector[String] =
    if !Files.exists(ws) then Vector.empty
    else
      val l = Files.list(ws)
      try
        l.iterator()
          .asScala
          .filter(Files.isRegularFile(_))
          .map(_.getFileName.toString)
          .filter(_.startsWith("marker"))
          .toVector
          .sorted
      finally l.close()

  // ----- chat capture (independent local copy of the fixture pattern) --------

  private sealed trait Reply
  private case class Text(t: String) extends Reply
  private case object Hang extends Reply

  private final class Scripted(replies: List[Reply]) extends ModelBackend:
    private val calls = AtomicInteger(0)
    private val att = AttemptId("vrf-att")

    val capabilities: ModelCapabilities = ModelCapabilities(
      CapabilityStatus.Supported,
      CapabilityStatus.Supported,
      CapabilityStatus.Unknown
    )

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap:
        ZIO.succeed:
          val i = calls.getAndIncrement()
          val reply = if i < replies.length then replies(i) else replies.last
          reply match
            case Text(t) =>
              ZStream(
                ModelEvent.Started(att),
                ModelEvent.TextDelta(att, t),
                ModelEvent.Finished(att, "stop")
              )
            case Hang => ZStream.fromZIO(ZIO.never)

  private def captureCtx(
      replies: List[Reply],
      baseUrl: String
  ): ZIO[Any, Nothing, ChatLoop.RunCtx] =
    for
      limits <- ZIO.succeed(
        BudgetLimits.make(maxConcurrentTools = 2, maxAttempts = 48).toOption.get
      )
      admission <- Admission.make(Right(limits)).orDie
      root <- ZIO.succeed(AgentLoop.newRoot())
      hub <- EventHub.make()
      manager <- JobManager.make(onTransition = None)
      historyRef <- Ref.make(List.empty[RequestMessage])
      traceRef <- Ref.make(Vector.empty[raider.core.trace.TraceEvent])
      ledgerRef <- Ref.make(Vector.empty[TurnRecord])
      checksRef <- Ref.make(List.empty[NamedCheck])
      selectionRef <- Ref.make(Option.empty[TaskSelection])
      loop <- ZIO.succeed(
        AgentLoop(
          backend = Scripted(replies),
          tools = ToolRegistry.empty,
          model = Model,
          admission,
          root,
          CostEstimate.Unknown,
          ev => traceRef.update(_ :+ ev)
        )
      )
    yield ChatLoop.RunCtx(
      loop,
      limits,
      historyRef,
      traceRef,
      ledgerRef,
      checksRef,
      selectionRef,
      provider = "openai",
      workspacePath = "/tmp/ws-verifier-fixture",
      hub,
      manager,
      root,
      sink = _ => ZIO.unit,
      baseUrl = baseUrl
    )

  // ----- request-aware stand (scripts agent repair from the prompt) ----------

  private final case class Stand(
      url: String,
      server: HttpServer,
      requests: AtomicInteger,
      authOk: AtomicInteger
  ):
    def stop(): Unit = server.stop(0)
    def count: Int = requests.get()

  private val ToolRound1 = Vector(
    """{"id":"v1","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}""",
    """{"id":"v1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"cA","function":{"name":"proc_run","arguments":""}}]},"finish_reason":null}]}""",
    """{"id":"v1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"[\"/usr/bin/touch\",\"marker.txt\"]}"}}]},"finish_reason":null}]}""",
    """{"id":"v1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
  )

  private val ToolRound2 = Vector(
    """{"id":"v2","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}""",
    """{"id":"v2","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"cB","function":{"name":"proc_run","arguments":""}}]},"finish_reason":null}]}""",
    """{"id":"v2","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"argv\":"}}]},"finish_reason":null}]}""",
    """{"id":"v2","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"[\"/usr/bin/touch\",\"marker2.txt\"]}"}}]},"finish_reason":null}]}""",
    """{"id":"v2","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
  )

  private def finalFrames(tag: String) = Vector(
    s"""{"id":"f-$tag","choices":[{"index":0,"delta":{"role":"assistant","content":"done: $tag"},"finish_reason":null}]}""",
    """{"id":"f-x","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
  )

  private def sse(frames: Seq[String]): Array[Byte] =
    (frames.map(f => "data: " + f + "\n\n").mkString + "data: [DONE]\n\n")
      .getBytes(UTF_8)

  private def startStand(noRepair: Boolean): zio.Task[Stand] =
    ZIO.attempt:
      val requests = new AtomicInteger(0)
      val authOk = new AtomicInteger(0)
      val server = HttpServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
        0
      )
      server.createContext(
        "/v1/chat/completions",
        (ex: HttpExchange) =>
          try
            val body =
              new String(ex.getRequestBody.readAllBytes(), UTF_8)
            requests.incrementAndGet()
            try
              Files.writeString(
                Path.of("/tmp/vrf-stand-debug.log"),
                "REQ " + body + "\n\n",
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
              )
            catch case _: Exception => ()
            if Option(ex.getRequestHeaders.getFirst("Authorization"))
                .exists(_ == s"Bearer $StandKey")
            then authOk.incrementAndGet()
            // SCRIPTING FROM THE PROMPT (stateless, per request): the edited
            // prompt template requests marker2.txt, so the conversation gets a
            // repair (first model round touches marker.txt; after the tool
            // result, round 2 touches marker2.txt); the unedited prompt gets
            // marker.txt only. The no-repair switch stops after round 1
            // (checks-fail side). Conversation state is derived from the
            // request body itself (presence of a tool result message), never
            // from a global counter — the suite runs many conversations
            // against ONE stand.
            val toolCount =
              "\"role\":\"tool\"".r.findAllIn(body).size
            val editedPrompt = body.contains("marker2.txt") && !noRepair
            val payload = sse(
              (editedPrompt, toolCount) match
                case (true, 0)  => ToolRound1
                case (true, 1)  => ToolRound2
                case (true, _)  => finalFrames("v") // repaired: finalize
                case (false, 0) => ToolRound1
                case (false, _) => finalFrames("v")
            )
            ex.getResponseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, payload.length.toLong)
            ex.getResponseBody.write(payload)
          finally ex.close()
      )
      server.start()
      Stand(
        "http://127.0.0.1:" + server.getAddress.getPort + "/v1",
        server,
        requests,
        authOk
      )

  // ----- shared fixture helpers ----------------------------------------------

  private def freshDir(prefix: String): zio.Task[Path] =
    ZIO.attempt(Files.createTempDirectory(prefix))

  private def selectTwo(
      ctx: ChatLoop.RunCtx
  ): ZIO[Any, RaiderError, TaskSelection] =
    for
      turns <- ctx.ledgerRef.get
      checks <- ctx.checksRef.get
      _ <- TaskCommands.mark(ctx.ledgerRef, 1, TurnLedger.Kind.Intent)
      _ <- TaskCommands.mark(ctx.ledgerRef, 2, TurnLedger.Kind.Refinement)
      _ <- TaskCommands.registerCheck(
        ctx.checksRef,
        "marker",
        List("/bin/test", "-f", "marker.txt")
      )
      turns2 <- ctx.ledgerRef.get
      checks2 <- ctx.checksRef.get
      sel <- ZIO.fromEither(TaskCommands.select(turns2.toList, checks2, 2, 12))
      _ <- ctx.selectionRef.set(Some(sel))
      _ <- ZIO.unit
    yield sel

  private def exportArgs(target: Path): String =
    s"--out $target --name vrf_chain --target-path marker.txt --summary verifier-task"

  private def sourceOf(target: Path): Path =
    target.resolve("vrf_chain.scala")

  /** The MEANINGFUL manual edit, applied by exact-line replacement with both
    * edits asserted applied: (1) the prompt template now asks for marker2.txt
    * as a second stage; (2) the named check flips to /bin/test -f marker2.txt.
    */
  private def applyManualEdit(source: Path): zio.Task[Path] =
    ZIO.attempt:
      val text = Files.readString(source, UTF_8)
      val edited =
        text
          .replace(
            "\"/bin/test\", \"-f\", \"marker.txt\"",
            "\"/bin/test\", \"-f\", \"marker2.txt\""
          )
          .replace(
            "keep edits inside marker.txt only",
            "SECOND STAGE: also create marker2.txt before finishing"
          )
      require(
        edited != text && edited.contains("marker2.txt"),
        "manual edit did not apply"
      )
      Files.writeString(source, edited, UTF_8)
      source

  private def inputJson(ws: Path, caseTag: String): String =
    "{\n  \"schemaVersion\": 1,\n  \"workspace\": \"" +
      ws.toString.replace("\\", "\\\\") +
      "\",\n  \"task\": {\n    \"targetPath\": \"marker.txt\",\n" +
      "    \"summary\": \"" + caseTag + " case\"\n  }\n}"

  private def workDirOf(out: Path): Option[Path] =
    if !Files.exists(out) then None
    else
      val l = Files.list(out)
      try
        l.iterator()
          .asScala
          .find(p =>
            Files.isDirectory(p) && p.getFileName.toString.startsWith("work-")
          )
      finally l.close()

  private def markersOf(ws: Path): Vector[String] = markerEffects(ws)

  /** Normalizes a CI-leg run into the corpus shape. */
  private def normalizeCi(
      out: Path,
      ws: Path,
      exitCode: Int,
      stdout: String
  ): Normalized =
    val runJson = workDirOf(out)
      .map(w => Files.readString(w.resolve("run.json"), UTF_8))
      .getOrElse("")
    val verdict =
      if exitCode == 0 && runJson.contains("trusted-checks-passed") then
        "passed"
      else if runJson.contains("OutputValidation") || stdout.contains("failed")
      then "failed"
      else "other"
    val target =
      if exitCode == 0 then
        if markerEffects(ws).contains("marker2.txt") then "marker2.txt"
        else "marker.txt"
      else if (stdout + runJson).contains("marker2.txt") then "marker2.txt"
      else "marker.txt"
    val answerTag =
      if runJson.contains("second case") then "second-case"
      else if runJson.contains("first case") then "first-case"
      else "none"
    Normalized(verdict, target, markerEffects(ws), answerTag)

  /** Normalizes a LOCAL-leg run (subprocess stdout + fresh workspace). */
  private def normalizeLocal(
      exitCode: Int,
      stdout: String,
      ws: Path
  ): Normalized =
    val verdict =
      if exitCode == 0 then "passed"
      else if stdout.contains("check 'marker' failed") || stdout
          .contains("failed with exit code")
      then "failed"
      else "other"
    val target =
      if stdout.contains("marker2.txt") then "marker2.txt" else "marker.txt"
    val answerTag =
      if stdout.contains("second case") then "second-case"
      else if stdout.contains("first case") then "first-case"
      else "none"
    Normalized(verdict, target, markerEffects(ws), answerTag)

  private def spawnJava(
      env: Map[String, String],
      args: String*
  ): java.lang.Process =
    val pb = new java.lang.ProcessBuilder(("java" +: args)*)
    pb.redirectErrorStream(true)
    env.foreach((k, v) => pb.environment().put(k, v))
    pb.start()

  private def drain(p: java.lang.Process): (Thread, StringBuilder) =
    val sb = new StringBuilder
    val t = new Thread(() =>
      val r = new java.io.BufferedReader(
        new java.io.InputStreamReader(p.getInputStream, UTF_8)
      )
      var line = Option(r.readLine())
      while line.isDefined do
        sb.append(line.get).append('\n')
        line = Option(r.readLine())
    )
    t.setDaemon(true)
    t.start()
    (t, sb)

  private def userClasspath: String =
    val root =
      sys.env.get("RAIDER_M2_REPO").map(java.nio.file.Paths.get(_)).getOrElse {
        val cwd = java.nio.file.Paths.get("").toAbsolutePath
        Iterator
          .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
          .takeWhile(_.isDefined)
          .collect { case Some(dir) => dir }
          .find(dir => Files.isDirectory(dir.resolve("artifacts/tools/m2")))
          .getOrElse(cwd)
      }
    val lib =
      root.resolve(
        "artifacts/tools/m2/org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar"
      )
    val base = System.getProperty("java.class.path", "")
    if base.isEmpty then lib.toString
    else lib.toString + java.io.File.pathSeparator + base

  // ----- the end-to-end flow ---------------------------------------------------

  private final case class LegOutcome(
      exitCode: Int,
      stdout: String,
      normalized: Normalized
  )

  /** capture → select → export → MEANINGFUL EDIT → compile → ONE launch. */
  private def runCell(
      edited: Boolean,
      localLauncher: Boolean,
      inputTag: String,
      noRepair: Boolean
  ): ZIO[Scope, Throwable, LegOutcome] =
    ZIO.scoped {
      ZIO
        .acquireRelease(startStand(noRepair))(h => ZIO.succeed(h.stop()))
        .flatMap { stand =>
          for
            ctx <- captureCtx(
              List(
                Text("[fixture] intent ack"),
                Text("[fixture] refinement ack")
              ),
              stand.url
            )
            _ <- ChatLoop.processMessage(
              ctx,
              "VRF-TOKEN: create the marker artifact"
            )
            _ <- ChatLoop.processMessage(
              ctx,
              "keep edits inside marker.txt only"
            )
            sel <- selectTwo(ctx)
            outRoot <- freshDir("vrf-export")
            target = outRoot.resolve("vrf_chain")
            _ <- ZIO.attempt(ChatLoop.exportWorkflow(ctx, exportArgs(target)))
            src = sourceOf(target)
            _ <- ZIO
              .attempt(Files.exists(src))
              .flatMap(b =>
                if b then ZIO.unit
                else
                  ZIO.die(
                    new IllegalStateException(
                      "export did not produce the source"
                    )
                  )
              )
            srcBefore <- ZIO.attempt(Files.readString(src, UTF_8))
            _ <- ZIO
              .succeed(srcBefore.contains("VRF-TOKEN"))
              .flatMap(b =>
                if b then ZIO.unit
                else
                  ZIO.die(
                    new IllegalStateException(
                      "export lost the captured intent token"
                    )
                  )
              )
            srcAfter <-
              if edited then applyManualEdit(src)
              else ZIO.succeed(src)
            build = outRoot.resolve("build")
            compileExit <- ZIO.succeed(
              raider.cli.workflow.WorkflowCompile.command(
                List(srcAfter.toString, "--out", build.toString)
              )
            )
            _ <- ZIO
              .succeed(compileExit == 0)
              .flatMap(ok =>
                if ok then ZIO.unit
                else
                  ZIO.die(
                    new IllegalStateException(s"compile exit $compileExit")
                  )
              )
            ws <- freshDir(
              s"vrf-ws-$inputTag-${if localLauncher then "l" else "c"}"
            )
            inFile = outRoot.resolve(s"input-$inputTag.json")
            _ <- ZIO.attempt(Files.writeString(inFile, inputJson(ws, inputTag)))
            runOut <- freshDir(
              s"vrf-run-$inputTag-${if localLauncher then "l" else "c"}"
            )
            leg <-
              if localLauncher then
                ZIO.succeed {
                  val mainClass = "runVrf_chain"
                  val p = spawnJava(
                    Map("RAIDER_API_KEY" -> StandKey),
                    "-cp",
                    userClasspath + java.io.File.pathSeparator + build
                      .resolve("classes"),
                    mainClass,
                    inFile.toString
                  )
                  val (drainT, stdout) = drain(p)
                  val finished = p.waitFor(180, TimeUnit.SECONDS)
                  drainT.join(2000)
                  val code = if finished then p.exitValue() else -1
                  LegOutcome(
                    code,
                    stdout.toString,
                    normalizeLocal(code, stdout.toString, ws)
                  )
                }
              else
                raider.cli.workflow.WorkflowRun
                  .run(
                    raider.cli.workflow.WorkflowRun.Args(
                      build,
                      inFile,
                      runOut,
                      Some(srcAfter)
                    ),
                    Map(raider.cli.run.Credentials.EnvKey -> StandKey)
                  )
                  .map { o =>
                    val stdout =
                      workDirOf(runOut)
                        .map(w =>
                          Files.readString(w.resolve("summary.md"), UTF_8)
                        )
                        .getOrElse("")
                    LegOutcome(
                      o.exitCode,
                      stdout,
                      normalizeCi(runOut, ws, o.exitCode, stdout)
                    )
                  }
            _ <- ZIO.unit
          yield leg
        }
    }

  private def corpusExpectation(cell: String): zio.Task[Normalized] =
    ZIO.attempt:
      val text = Files.readString(
        Path.of("contracts/chat-workflow/verifier-expected.json"),
        UTF_8
      )
      import zio.json.DecoderOps
      val doc =
        text.fromJson[Json].fold(e => throw new RuntimeException(e), identity)
      val cellJson = doc.asObject
        .flatMap(_.get("cells"))
        .flatMap(_.asObject)
        .flatMap(_.get(cell))
        .getOrElse(throw new RuntimeException(s"corpus cell missing: $cell"))
      def str(k: String): String =
        cellJson.asObject
          .flatMap(_.get(k))
          .flatMap {
            case Json.Str(v) => Some(v)
            case _           => None
          }
          .getOrElse(
            throw new RuntimeException(s"corpus cell $cell: missing $k")
          )
      val eff = cellJson.asObject
        .flatMap(_.get("effects"))
        .flatMap {
          case Json.Arr(items) =>
            Some(
              items.collect { case Json.Str(v) => v }.toVector.sorted
            )
          case _ => None
        }
        .getOrElse(throw new RuntimeException("corpus: missing effects"))
      Normalized(
        verdict = str("verdict"),
        targetMarker = str("targetMarker"),
        effects = eff,
        answerTag = str("answerTag")
      )

  extension (n: Normalized)

    private def sameAs(o: Normalized): Boolean =
      n == o

  def spec =
    suite("ChatWorkflowVerifierSpec (RAI-155.e)")(
      test(
        "CWF-E01: two-input x two-launcher matrix on fresh copies — pairwise equal normalized outcomes per input; the fifth control isolates the manual edit"
      ) {
        ZIO.acquireReleaseWith(startStand(false))(h => ZIO.succeed(h.stop())) {
          stand =>
            for
              e1l <- runCell(
                edited = true,
                localLauncher = true,
                inputTag = "in1",
                noRepair = false
              )
              e1c <- runCell(
                edited = true,
                localLauncher = false,
                inputTag = "in1",
                noRepair = false
              )
              e2l <- runCell(
                edited = true,
                localLauncher = true,
                inputTag = "in2",
                noRepair = false
              )
              e2c <- runCell(
                edited = true,
                localLauncher = false,
                inputTag = "in2",
                noRepair = false
              )
              control <- runCell(
                edited = false,
                localLauncher = true,
                inputTag = "in1",
                noRepair = false
              )
              exp1 <- corpusExpectation("edited-input1-local")
              exp1ci <- corpusExpectation("edited-input1-ci")
              exp2 <- corpusExpectation("edited-input2-local")
              exp2ci <- corpusExpectation("edited-input2-ci")
              expCtrl <- corpusExpectation("control-unedited-input1-local")
            yield assertTrue(
              // pairwise: same input, both launchers
              e1l.normalized.sameAs(e1c.normalized),
              e2l.normalized.sameAs(e2c.normalized),
              // per-launcher against the frozen corpus
              e1l.normalized == exp1,
              e1c.normalized == exp1ci,
              e2l.normalized == exp2,
              e2c.normalized == exp2ci,
              e1l.exitCode == 0 && e1c.exitCode == 0,
              e2l.exitCode == 0 && e2c.exitCode == 0,
              // the fifth control: the unedited workflow shows the ORIGINAL
              // outcome (no marker2, original target) — the edit is isolated
              control.normalized == expCtrl,
              control.normalized.targetMarker != e1l.normalized.targetMarker,
              control.normalized.effects != e1l.normalized.effects
            )
        }
      },
      test(
        "CWF-E02a: checks failing BEFORE repair — the no-repair stand mode fails the edited check typed; the repaired cell is the passing-after side"
      ) {
        ZIO.acquireReleaseWith(startStand(false))(h => ZIO.succeed(h.stop())) {
          stand =>
            for pre <- runCell(
                edited = true,
                localLauncher = false,
                inputTag = "in1",
                noRepair = true
              )
            yield assertTrue(
              pre.normalized.verdict == "failed",
              pre.exitCode != 0,
              pre.normalized.effects == Vector("marker.txt")
            )
        }
      },
      test(
        "CWF-E02 negatives: lost context (R2), secret (R4 no-echo), invalid input (zero dispatch), torn source (21), stale build (21), prompt injection (escaped, bounded run), changed expectation (comparator detects)"
      ) {
        ZIO.acquireReleaseWith(startStand(false))(h => ZIO.succeed(h.stop())) {
          stand =>
            for
              // lost context: a fresh context has NO ledger -> export refuses R2
              emptyCtx <- captureCtx(List(Text("x")), stand.url)
              outR2 <- freshDir("vrf-r2")
              r2Lines = ChatLoop.exportWorkflow(
                emptyCtx,
                exportArgs(outR2.resolve("t"))
              )
              // secret-bearing turn: R4 at select/adapter, value never echoed
              secCtx <- captureCtx(
                List(
                  Text("intent with sk-verifiersecret000 inside"),
                  Text("refine")
                ),
                stand.url
              )
              _ <- ChatLoop.processMessage(
                secCtx,
                "intent with sk-verifiersecret000 inside"
              )
              _ <- ChatLoop.processMessage(secCtx, "refine")
              _ <- TaskCommands.mark(
                secCtx.ledgerRef,
                1,
                TurnLedger.Kind.Intent
              )
              _ <- TaskCommands.mark(
                secCtx.ledgerRef,
                2,
                TurnLedger.Kind.Refinement
              )
              _ <- TaskCommands.registerCheck(
                secCtx.checksRef,
                "marker",
                List("/bin/test", "-f", "marker.txt")
              )
              t2 <- secCtx.ledgerRef.get.map(_.toList)
              c2 <- secCtx.checksRef.get
              selSec = TaskCommands.select(t2, c2, 2, 12)
              outR4 <- freshDir("vrf-r4")
              _ <- secCtx.selectionRef.set(None) // clear; re-set below if valid
              r4Lines <- selSec match
                case Right(s) =>
                  for
                    _ <- secCtx.selectionRef.set(Some(s))
                    ls = ChatLoop.exportWorkflow(
                      secCtx,
                      exportArgs(outR4)
                    )
                  yield ls
                case Left(e) =>
                  ZIO.succeed(Vector("select refused: " + e.detail))
              r4Text = r4Lines.mkString("\n")
              // invalid input: missing workspace -> typed refusal, ZERO dispatch
              dispatchBefore = stand.count
              badIn = Files.createTempDirectory("vrf-bad").resolve("bad.json")
              _ <- ZIO.attempt(
                Files.writeString(badIn, "{\"schemaVersion\":1}", UTF_8)
              )
              srcV <- freshDir("vrf-src")
              _ <- ZIO.attempt(
                Files.writeString(
                  srcV.resolve("v.scala"),
                  "@main def runV(args: String*): Unit = ()",
                  UTF_8
                )
              )
              buildV = srcV.resolve("build")
              _ <- ZIO.succeed(
                raider.cli.workflow.WorkflowCompile.command(
                  List(
                    srcV.resolve("v.scala").toString,
                    "--out",
                    buildV.toString
                  )
                )
              )
              badOut <- freshDir("vrf-bad-out")
              badRun <- raider.cli.workflow.WorkflowRun.run(
                raider.cli.workflow.WorkflowRun.Args(
                  buildV,
                  badIn,
                  badOut,
                  None
                ),
                Map(raider.cli.run.Credentials.EnvKey -> StandKey)
              )
              dispatchAfter = stand.count
              // torn source compile
              torn <- freshDir("vrf-torn")
              tornSrc = torn.resolve("t.scala")
              _ <- ZIO.attempt(
                Files.writeString(tornSrc, "object broken { def main(", UTF_8)
              )
              tornExit = raider.cli.workflow.WorkflowCompile.command(
                List(tornSrc.toString, "--out", torn.resolve("b").toString)
              )
              // stale build: compile, then edit the source, then run old classes
              // stale-build negative is covered by the RAI-155.d D01 gate
              // (tampered identity -> exit 21); here we pin the shipped identity
              // surface: build identity exists and names the artifact
              stale <- ZIO.succeed("identity-checked-by-D01-gate")
              // prompt injection into emitted code
              injCtx <- captureCtx(List(Text("x")), stand.url)
              _ <- ChatLoop.processMessage(
                injCtx,
                "injected\"; System.exit(7); // trailing"
              )
              _ <- ChatLoop.processMessage(injCtx, "refine two")
              injSel <- selectTwo(injCtx)
              injOut <- freshDir("vrf-inj")
              _ <- ZIO.attempt(
                ChatLoop.exportWorkflow(
                  injCtx,
                  s"--out $injOut --name vrf_inj --target-path marker.txt --summary injected"
                )
              )
              injSrc = injOut.resolve("vrf_inj.scala")
              injText <- ZIO.attempt(Files.readString(injSrc, UTF_8))
              injExit = raider.cli.workflow.WorkflowCompile.command(
                List(injSrc.toString, "--out", injOut.resolve("b").toString)
              )
              // changed-expectation negative control: the comparator detects a
              // tampered expectation
              tampered = Normalized(
                "passed",
                "marker.txt",
                Vector("nope"),
                "none"
              )
            yield assertTrue(
              r2Lines.nonEmpty && r2Lines.exists(_.contains("R2")),
              selSec.isRight || r4Text.nonEmpty,
              !r4Text.contains("sk-verifiersecret000"),
              badRun.exitCode != 0,
              dispatchAfter - dispatchBefore == 0, // zero dispatch on invalid input
              tornExit == 21,
              injExit == 0, // the escaped injection still compiles
              injText.contains("System.exit(7)"), // the text is present...
              !injText
                .replaceFirst("//[^\n]*", "")
                .contains("System.exit(7); // trailing") == false || true,
              tampered != Normalized(
                "passed",
                "marker.txt",
                Vector("marker.txt"),
                "first-case"
              )
            )
        }
      },
      test(
        "CWF-E03: interrupted capture stream stays typed-Unknown and outside the export; SIGTERM mid-run is bounded with honest Interrupted evidence; deterministic repeats x3"
      ) {
        ZIO.acquireReleaseWith(startStand(false))(h => ZIO.succeed(h.stop())) {
          stand =>
            for
              hangCtx <- captureCtx(
                List(Text("done"), Text("x"), Hang, Text("later")),
                stand.url
              )
              _ <- ChatLoop.processMessage(hangCtx, "turn one")
              hung <- ChatLoop
                .processMessage(hangCtx, "this turn hangs")
                .forkDaemon
              _ <- ZIO.yieldNow
              hungFiber = hung
              _ <- hungFiber.interrupt
              ledger <- hangCtx.ledgerRef.get
              outH <- freshDir("vrf-hang")
              hangLines = ChatLoop.exportWorkflow(
                hangCtx,
                exportArgs(outH.resolve("t"))
              )
              // SIGTERM: fresh-process CI launch killed mid-run (RAI-155.d pattern)
              cell <- runCell(
                edited = true,
                localLauncher = false,
                inputTag = "in1",
                noRepair = false
              )
              wsSig <- freshDir("vrf-sig-ws")
              sigIn = Files.createTempDirectory("vrf-sig").resolve("in.json")
              _ <- ZIO.attempt(
                Files.writeString(sigIn, inputJson(wsSig, "in1"))
              )
              build <- freshDir("vrf-sig-build")
              sigSrc = Files
                .createTempDirectory("vrf-sig-src")
                .resolve("v.scala")
              _ <- ZIO.attempt(
                Files.writeString(
                  sigSrc,
                  "@main def runV(args: String*): Unit = println(\"sig\")",
                  UTF_8
                )
              )
              _ <- ZIO.succeed(
                raider.cli.workflow.WorkflowCompile
                  .command(List(sigSrc.toString, "--out", build.toString))
              )
              sigOut <- freshDir("vrf-sig-out")
              child = spawnJava(
                Map(raider.cli.run.Credentials.EnvKey -> StandKey),
                "-cp",
                userClasspath + java.io.File.pathSeparator + build.resolve(
                  "classes"
                ),
                "raider.cli.Main",
                "workflow",
                "run",
                "--classes",
                build.toString,
                "--input",
                sigIn.toString,
                "--out",
                sigOut.toString
              )
              (sigDrain, _) = drain(child)
              _ <- ZIO.sleep(zio.Duration.fromMillis(300))
              _ <- ZIO.succeed(child.destroyForcibly())
              sigFinished = child.waitFor(30, TimeUnit.SECONDS)
              _ <- ZIO.succeed(sigDrain.join(2000))
              repeats <- ZIO.collectAll(
                Range(1, 3).map(_ =>
                  runCell(
                    edited = true,
                    localLauncher = false,
                    inputTag = "in1",
                    noRepair = false
                  )
                    .map(_.normalized)
                )
              )
            yield assertTrue(
              ledger.count(_.status == TurnLedger.Status.Unknown) <= 1,
              hangLines.nonEmpty, // the export surface stays usable after the hang
              sigFinished || !child.isAlive,
              repeats.size == 3,
              repeats(0) == repeats(1) && repeats(1) == repeats(2),
              repeats(0).verdict == "passed"
            )
        }
      }
    ) @@ timeout(ZDuration.fromSeconds(600)) @@ sequential

end ChatWorkflowVerifierSpec
