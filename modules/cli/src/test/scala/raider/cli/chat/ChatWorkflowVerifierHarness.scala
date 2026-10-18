package raider.cli.chat

import zio.*
import zio.json.*

import zio.stream.ZStream

import raider.core.{
  AttemptId,
  CapabilityStatus,
  ModelBackend,
  ModelCapabilities,
  ModelEvent,
  ModelRequest,
  RaiderError,
  RequestMessage,
  ToolRegistry
}
import raider.cli.run.Credentials
import raider.cli.workflow.WorkflowCompile
import raider.runtime.admission.Admission
import raider.runtime.budget.{BudgetLimits, CostEstimate}
import raider.runtime.events.EventHub
import raider.runtime.jobs.JobManager
import raider.runtime.loop.AgentLoop

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** INDEPENDENT end-to-end verifier harness for card RAI-155.e (CWF-E01..E03).
  *
  * Independence discipline: this harness drives ONLY real product entry points
  * — `ChatLoop.processMessage` (real chat capture), `TaskCommands.*` (the real
  * `:task` implementations), `ChatLoop.exportWorkflow` (the real
  * `:export-workflow` command path), `WorkflowCompile.command` (the real
  * `raider compile` on the pinned toolchain), and the compiled launcher main /
  * `raider.cli.Main workflow run` as FRESH subprocesses. All pinned
  * expectations under `contracts/chat-workflow` (derived from ADR-033 §4–§9 and
  * the ci-runtime exit table), never from generator internals or implementation
  * snapshots.
  *
  * External prerequisites (declared, not hidden): the pinned dotc 3.9.0 jars
  * under `artifacts/tools/m2` (resolved by the product compile command) and a
  * loopback scripted OpenAI-compatible stand (ephemeral port, SSE frames; no
  * live provider, no paid calls).
  */
object ChatWorkflowVerifierHarness:

  // ------------------------------------------------------------------//
  // Frozen expectation corpus (contracts/chat-workflow/expectations)    //
  // ------------------------------------------------------------------//

  /** Locates the repo root by walking up from the process CWD until the build
    * file is found (works forked with cwd = repo root and from any module
    * directory).
    */
  def repoRoot: Path =
    val cwd = Paths.get("").toAbsolutePath
    Iterator
      .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) => dir }
      .find(dir => Files.isRegularFile(dir.resolve("build.sbt")))
      .getOrElse(cwd)

  def corpusPath(rel: String): Path =
    repoRoot.resolve("contracts").resolve("chat-workflow").resolve(rel)

  private def readCorpus(rel: String): String =
    Files.readString(corpusPath(rel), StandardCharsets.UTF_8)

  // ---- CWF-E01 expectations ----
  final case class E01Capture(
      capturedTurns: Int,
      ordinaryTurnsNotSelected: Int,
      selectionSpan: Int,
      allCapturedTurnsCompleted: Boolean,
      selectionTurnIdsMatchLedgerOrder: Boolean,
      standRequestsDuringCapture: Int
  )

  final case class E01Export(
      successLinePrefix: String,
      artifactCount: Int,
      artifactSuffixes: List[String],
      manifestKind: String,
      manifestSchemaVersion: Int,
      reExportByteIdentical: Boolean,
      capturedIntentTokenPresentInSource: Boolean,
      intentTokenAbsentOnDiskBeforeExport: Boolean,
      standRequestsDuringExportDelta: Int
  )

  final case class E01ManualEdit(
      promptTokenBefore: String,
      promptTokenAfter: String,
      checkArgvBefore: List[String],
      checkArgvAfter: List[String],
      replacementsCount: Int
  )

  final case class E01Compile(
      expectedExitCode: Int,
      buildIdentityFile: String,
      pinnedToolchainVersion: String,
      standRequestsDuringCompileDelta: Int
  )

  final case class E01Sts(
      independentCheckFailsBeforeAgentWork: Boolean,
      independentCheckPassesAfterAgentWork: Boolean,
      realToolEffectPerRun: Boolean,
      minNewStandRequestsPerRun: Int
  )

  final case class E01Matrix(
      inputCases: Int,
      launchers: List[String],
      freshWorkspaceCopies: Int,
      expectedExitCode: Int,
      expectedCiStatus: String,
      expectedCiTaskVerdict: String,
      expectedCiArtifacts: List[String],
      pairwiseNormalizedEqualForSameInput: Boolean,
      differentInputsObservablyDiffer: Boolean,
      controlRunsUneditedSourceOnInput1: Boolean
  )

  final case class E01EditIsolation(
      editedPublishTarget: String,
      uneditedPublishTarget: String,
      editedReportDiffersFromControl: Boolean,
      bothControlAndEditedSucceed: Boolean
  )

  final case class E01Identity(
      ciSourceDigestMatchesCompiledSource: Boolean,
      sameClassesDirUsedByBothLaunchers: Boolean,
      manifestSourceDigestEqualsPreEditExport: Boolean
  )

  final case class E01Doc(
      expectationId: String,
      capture: E01Capture,
      exportStage: E01Export,
      manualEdit: E01ManualEdit,
      compile: E01Compile,
      stsRepairPattern: E01Sts,
      matrix: E01Matrix,
      editIsolation: E01EditIsolation,
      identity: E01Identity
  )

  given JsonCodec[E01Capture] = DeriveJsonCodec.gen
  given JsonCodec[E01Export] = DeriveJsonCodec.gen
  given JsonCodec[E01ManualEdit] = DeriveJsonCodec.gen
  given JsonCodec[E01Compile] = DeriveJsonCodec.gen
  given JsonCodec[E01Sts] = DeriveJsonCodec.gen
  given JsonCodec[E01Matrix] = DeriveJsonCodec.gen
  given JsonCodec[E01EditIsolation] = DeriveJsonCodec.gen
  given JsonCodec[E01Identity] = DeriveJsonCodec.gen
  given JsonCodec[E01Doc] = DeriveJsonCodec.gen

  // ---- CWF-E02 expectations ----
  final case class E02Case(
      id: String,
      kind: String,
      expectedExitCode: Option[Int] = None,
      expectedRefusalPrefix: Option[String] = None,
      expectedErrorCode: Option[String] = None,
      expectedDetailContains: Option[List[String]] = None,
      expectedRunStatus: Option[String] = None,
      expectedTargetAbsent: Option[Boolean] = None,
      expectedParentNotCreated: Option[Boolean] = None,
      expectedSecretNeverEchoed: Option[Boolean] = None,
      expectedVerdictAbsent: Option[Boolean] = None,
      expectNoDispatch: Option[Boolean] = None,
      expectedInjectionInert: Option[Boolean] = None,
      expectedCredentialAbsentFromSource: Option[Boolean] = None,
      expectedChecksUnchangedByModelText: Option[Boolean] = None,
      note: String = ""
  )

  final case class E02Doc(expectationId: String, cases: List[E02Case])
  given JsonCodec[E02Case] = DeriveJsonCodec.gen
  given JsonCodec[E02Doc] = DeriveJsonCodec.gen

  def e02Case(doc: E02Doc, id: String): E02Case =
    doc.cases
      .find(_.id == id)
      .getOrElse(
        sys.error(s"corpus CWF-E02 is missing case '$id' (corpus integrity)")
      )

  // ---- CWF-E03 expectations ----
  final case class E03Interrupted(
      inFlightTurnStatus: String,
      inFlightTurnRequestPreserved: Boolean,
      selectRefusalPrefix: String,
      selectRefusalNamesOffendingTurn: Boolean,
      completedPrefixStillSelectable: Boolean,
      noRedispatchAfterInterrupt: Boolean
  )

  final case class E03Concurrent(
      bothSucceed: Boolean,
      byteIdentical: Boolean
  )

  final case class E03Snapshot(
      exportDuringStreamingSucceeds: Boolean,
      artifactEqualsQuiescentReference: Boolean,
      ledgerUntouchedByExport: Boolean,
      inFlightTurnOutsideSnapshot: Boolean,
      concurrentExportsToDistinctTargets: E03Concurrent,
      stagingRemnantRefused: Boolean
  )

  final case class E03Sigterm(
      freshProcessLaunch: Boolean,
      expectedExitCodes: List[Int],
      truthfulIncompleteArtifact: Boolean,
      neverReportsSucceeded: Boolean,
      noAutomaticRetry: Boolean,
      runStartedBeforeSignal: Boolean
  )

  final case class E03Repeat(
      repeatedRuns: Int,
      normalizedResultsEqual: Boolean,
      reExportByteIdentical: Boolean
  )

  final case class E03Doc(
      expectationId: String,
      interruptedCapture: E03Interrupted,
      exportSnapshot: E03Snapshot,
      sigterm: E03Sigterm,
      deterministicRepeat: E03Repeat
  )

  given JsonCodec[E03Interrupted] = DeriveJsonCodec.gen
  given JsonCodec[E03Concurrent] = DeriveJsonCodec.gen
  given JsonCodec[E03Snapshot] = DeriveJsonCodec.gen
  given JsonCodec[E03Sigterm] = DeriveJsonCodec.gen
  given JsonCodec[E03Repeat] = DeriveJsonCodec.gen
  given JsonCodec[E03Doc] = DeriveJsonCodec.gen

  private def loadCorpus[A](
      rel: String
  )(using c: zio.json.JsonDecoder[A]): Task[A] =
    ZIO.attempt(readCorpus(rel).fromJson[A]).flatMap {
      case Right(doc) => ZIO.succeed(doc)
      case Left(error) =>
        ZIO.die(
          new IllegalStateException(s"corpus $rel does not decode: $error")
        )
    }

  // Independently pinned verifier-input anchor. Keep this in the actual E02
  // expectation-loading path; the contract corpus has its own independent
  // integrity assertion. This is tamper evidence for verifier inputs, not a
  // security boundary against changes to verifier code or this anchor.
  private val E02ExpectationSha256 =
    "797dcb60df69bbf920b647d8739f697b98f96caab1f801c054fd2104abbfff3d"

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private[chat] def loadE02Bytes(bytes: Array[Byte]): Task[E02Doc] =
    if sha256(bytes) != E02ExpectationSha256 then
      ZIO.fail(new IllegalStateException("verifier input digest mismatch"))
    else
      ZIO
        .attempt(new String(bytes, StandardCharsets.UTF_8).fromJson[E02Doc])
        .flatMap {
          case Right(doc) => ZIO.succeed(doc)
          case Left(error) =>
            ZIO.fail(
              new IllegalStateException(
                s"corpus expectations/CWF-E02.json does not decode: $error"
              )
            )
        }

  def loadE01: Task[E01Doc] = loadCorpus("expectations/CWF-E01.json")

  def loadE02: Task[E02Doc] =
    ZIO
      .attempt(Files.readAllBytes(corpusPath("expectations/CWF-E02.json")))
      .flatMap(loadE02Bytes)

  def loadE03: Task[E03Doc] = loadCorpus("expectations/CWF-E03.json")

  // ------------------------------------------------------------------//
  // Scripted CAPTURE backend (counting; the chat-side model surface)    //
  // ------------------------------------------------------------------//

  final class ScriptedCapture(
      replies: List[String],
      hangAfter: Option[Int] = None
  ) extends ModelBackend:
    private val calls = AtomicInteger(0)
    val att = AttemptId("cwf-verifier-capture")

    val capabilities: ModelCapabilities = ModelCapabilities(
      CapabilityStatus.Supported,
      CapabilityStatus.Supported,
      CapabilityStatus.Unknown
    )

    def callCount: Int = calls.get

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap:
        ZIO.succeed:
          val i = calls.getAndIncrement()
          if hangAfter.exists(h => i >= h) then
            ZStream.fromZIO(ZIO.never) // hangs mid-turn (interruption cases)
          else
            val t =
              if i < replies.length then replies(i) else replies.last
            ZStream(
              ModelEvent.Started(att),
              ModelEvent.TextDelta(att, t),
              ModelEvent.Finished(att, "stop")
            )

  // ------------------------------------------------------------------//
  // Scripted OpenAI-compatible RUN stand (loopback, SSE)                //
  // ------------------------------------------------------------------//

  enum StandMode:
    /** Normal publish: the tool round creates the prompt-named publish target
      * in the workspace; the final answer echoes target + task instance.
      */
    case Normal

    /** Bogus: the agent CLAIMS done without any tool call (the check must fail
      * — R9).
      */
    case Bogus

    /** Delay: sleeps before answering (SIGTERM window for a fresh process).
      */
    case Delay(delayMs: Long)

    /** Text-only responses for actual ChatLoop capture over the OpenAI wire. */
    case Capture

  final case class Stand(url: String, server: HttpServer, counts: StandCounts):
    def stop(): Unit = server.stop(0)
    def totalRequests: Int = counts.total.get
    def captureRequests: Int = counts.captureHits.get

  final case class StandCounts(
      total: AtomicInteger = AtomicInteger(0),
      toolRounds: AtomicInteger = AtomicInteger(0),
      finals: AtomicInteger = AtomicInteger(0),
      bogusClaims: AtomicInteger = AtomicInteger(0),
      delayHits: AtomicInteger = AtomicInteger(0),
      captureHits: AtomicInteger = AtomicInteger(0)
  )

  val PublishTokenPrefix = "PUBLISH-TARGET: "

  private val TaskInstanceRegex = raw"Task input: ([A-Za-z0-9-]+)\\n".r

  private def publishTargetOf(body: String): String =
    if body.contains(PublishTokenPrefix + "marker2.txt") then "marker2.txt"
    else "marker.txt"

  private def taskInstanceOf(body: String): String =
    TaskInstanceRegex
      .findFirstMatchIn(body)
      .map(_.group(1))
      .getOrElse("unknown-instance")

  private def toolRoundFrames(target: String): Vector[String] = Vector(
    """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}""",
    s"""{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"callA","function":{"name":"proc_run","arguments":""}}]},"finish_reason":null}]}""",
    """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"argv\":"}}]},"finish_reason":null}]}""",
    s"""{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"[\\"/usr/bin/touch\\",\\"$target\\"]}"}}]},"finish_reason":null}]}""",
    """{"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
  )

  private def finalFrames(answer: String): Vector[String] = Vector(
    s"""{"id":"f1","choices":[{"index":0,"delta":{"role":"assistant","content":"$answer"},"finish_reason":null}]}""",
    """{"id":"f1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
  )

  private def sse(frames: Seq[String]): Array[Byte] =
    (frames.map(f => "data: " + f + "\n\n").mkString + "data: [DONE]\n\n")
      .getBytes(StandardCharsets.UTF_8)

  def startStand(mode: StandMode): Task[Stand] =
    ZIO.attempt:
      val counts = StandCounts()
      val server = HttpServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
        0
      )
      server.createContext(
        "/v1/chat/completions",
        (ex: HttpExchange) =>
          try
            val body = new String(
              ex.getRequestBody.readAllBytes(),
              StandardCharsets.UTF_8
            )
            counts.total.incrementAndGet()
            mode match
              case StandMode.Capture if !body.contains("Task input: ") =>
                val n = counts.captureHits.getAndIncrement()
                val prompt =
                  if n < 4 then
                    List(
                      "[fixture] ordinary ack",
                      "[fixture] intent ack",
                      "[fixture] refinement ack 1",
                      "[fixture] refinement ack 2"
                    )(n)
                  else "[fixture] additional capture ack"
                writeSse(ex, sse(finalFrames(prompt)))
              case StandMode.Capture | StandMode.Normal =>
                val target = publishTargetOf(body)
                if body.contains("\"role\":\"tool\"") then
                  counts.finals.incrementAndGet()
                  writeSse(
                    ex,
                    sse(
                      finalFrames(
                        "published " + target + " for task instance " +
                          taskInstanceOf(body)
                      )
                    )
                  )
                else
                  counts.toolRounds.incrementAndGet()
                  writeSse(ex, sse(toolRoundFrames(target)))
              case StandMode.Delay(ms) =>
                counts.delayHits.incrementAndGet()
                Thread.sleep(ms)
                val frames = sse(finalFrames("late answer after delay"))
                writeSse(ex, frames)
              case StandMode.Bogus =>
                counts.bogusClaims.incrementAndGet()
                writeSse(
                  ex,
                  sse(
                    finalFrames(
                      "done: the marker is published as requested (bogus agent claim, no tool call)"
                    )
                  )
                )
          finally ex.close()
      )
      server.start()
      Stand(
        "http://127.0.0.1:" + server.getAddress.getPort + "/v1",
        server,
        counts
      )

  private def writeSse(ex: HttpExchange, payload: Array[Byte]): Unit =
    ex.getResponseHeaders.add("Content-Type", "text/event-stream")
    ex.sendResponseHeaders(200, payload.length.toLong)
    ex.getResponseBody.write(payload)
    ()

  // ------------------------------------------------------------------//
  // Capture session context (real ChatLoop RunCtx)                      //
  // ------------------------------------------------------------------//

  final case class CaptureCtx(
      ctx: ChatLoop.RunCtx,
      callCount: () => Int
  )

  def makeCaptureCtx(
      baseUrl: String,
      model: String,
      hangAfter: Option[Int] = None,
      useOpenAiStand: Boolean = false
  ): ZIO[Any, Nothing, CaptureCtx] =
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
      scriptedBackend = ScriptedCapture(
        List(
          "[fixture] ordinary ack",
          "[fixture] intent ack",
          "[fixture] refinement ack 1",
          "[fixture] refinement ack 2"
        ),
        hangAfter
      )
      backend: ModelBackend <-
        if useOpenAiStand then
          raider.provider.chat.stream.OpenAIChatStreamingBackend
            .make(
              raider.provider.chat.OpenAIChatBackend.Config
                .make(baseUrl, "verifier-local-key")
            )
            .orDie
        else ZIO.succeed(scriptedBackend)
      loop <- ZIO.succeed(
        AgentLoop(
          backend,
          ToolRegistry.empty,
          model,
          admission,
          root,
          CostEstimate.Unknown,
          ev => traceRef.update(_ :+ ev)
        )
      )
      ctx = ChatLoop.RunCtx(
        loop,
        limits,
        historyRef,
        traceRef,
        ledgerRef,
        checksRef,
        selectionRef,
        provider = "openai",
        workspacePath = "/tmp/cwf-verifier-fixture-ws",
        hub,
        manager,
        root,
        sink = _ => ZIO.unit,
        baseUrl = baseUrl
      )
    yield CaptureCtx(
      ctx,
      if useOpenAiStand then () => 0 else () => scriptedBackend.callCount
    )

  /** Sends one user message through the REAL chat capture path. */
  def chat(ctx: CaptureCtx, text: String): ZIO[Any, RaiderError, String] =
    ChatLoop.processMessage(ctx.ctx, text)

  /** Bounded poll until the ledger carries at least `n` turns (the in-flight
    * turn becomes visible as soon as it BEGINS — status Unknown).
    */
  def waitLedgerSize(
      ctx: CaptureCtx,
      n: Int,
      tries: Int = 200_000
  ): UIO[Boolean] =
    def poll(i: Int): UIO[Boolean] =
      ctx.ctx.ledgerRef.get.flatMap: ts =>
        if ts.size >= n then ZIO.succeed(true)
        else if i <= 0 then ZIO.succeed(false)
        else ZIO.yieldNow *> poll(i - 1)
    poll(tries)

  /** Capture -> select -> export -> compile: the REAL chain, shared by the
    * run-based verifier suites (no hand-authored workflow anywhere).
    */
  def prepareArtifact(
      stand: Stand,
      outRoot: Path,
      name: String,
      summary: String,
      intentText: String,
      refinementText: String,
      checkName: String,
      checkArgv: List[String],
      maxRounds: Int = 8
  ): zio.Task[(Path, Path)] =
    for
      cap <- makeCaptureCtx(baseUrl = stand.url, model = "verifier-model")
      _ <- chat(cap, s"$name ordinary context turn, never selected")
      _ <- chat(cap, intentText)
      _ <- chat(cap, refinementText)
      selected <- markSelectStore(
        cap,
        span = 2,
        maxRounds = maxRounds,
        checkName = checkName,
        checkArgv = checkArgv
      )
      _ <- ZIO.when(selected.isLeft)(
        ZIO.die(
          new IllegalStateException(
            "fixture select refused: " + selected.swap.toOption.get.detail
          )
        )
      )
      target = outRoot.resolve(s"$name-target")
      lines = exportWorkflow(cap, target, name, "marker.txt", summary)
      _ <- ZIO.when(!lines.head.startsWith("workflow exported:"))(
        ZIO.die(
          new IllegalStateException(
            "fixture export failed: " + lines.mkString("; ")
          )
        )
      )
      sourcePath = target.resolve(s"$name.scala")
      buildDir = outRoot.resolve(s"$name-build")
      compileExit <- ZIO.attemptBlocking(
        WorkflowCompile.command(
          List(sourcePath.toString, "--out", buildDir.toString)
        )
      )
      _ <- ZIO.when(compileExit != 0)(
        ZIO.die(new IllegalStateException("fixture compile failed"))
      )
    yield (sourcePath, buildDir)

  /** Marks turns and builds the explicit selection through the REAL :task
    * implementations (mark 2 = intent, 3..span+1 = refinements; turn 1 stays
    * Ordinary and outside the span).
    */
  def markSelectStore(
      ctx: CaptureCtx,
      span: Int,
      maxRounds: Int,
      checkName: String,
      checkArgv: List[String]
  ): ZIO[Any, RaiderError, Either[RaiderError, TaskSelection]] =
    for
      _ <- TaskCommands.mark(ctx.ctx.ledgerRef, 2, TurnLedger.Kind.Intent)
      _ <- ZIO.foreachDiscard(3 to span + 1)(i =>
        TaskCommands.mark(ctx.ctx.ledgerRef, i, TurnLedger.Kind.Refinement)
      )
      _ <- TaskCommands.registerCheck(ctx.ctx.checksRef, checkName, checkArgv)
      turns <- ctx.ctx.ledgerRef.get
      checks <- ctx.ctx.checksRef.get
      selected = TaskCommands.select(turns.toList, checks, span, maxRounds)
      _ <- ZIO.when(selected.isRight)(
        ctx.ctx.selectionRef.set(Some(selected.toOption.get))
      )
    yield selected

  /** The real :export-workflow command path. */
  def exportWorkflow(
      ctx: CaptureCtx,
      target: Path,
      name: String,
      targetPath: String,
      summary: String
  ): Vector[String] =
    ChatLoop.exportWorkflow(
      ctx.ctx,
      s"--out $target --name $name --target-path $targetPath --summary $summary"
    )

  // ------------------------------------------------------------------//
  // Fixture workspaces (fresh copies) and run inputs                    //
  // ------------------------------------------------------------------//

  def freshWorkspaceCopy(tag: String): Task[Path] =
    ZIO.attempt:
      val tmpl = Files.createTempDirectory(s"cwf-verifier-template-$tag")
      Files.writeString(
        tmpl.resolve("README.md"),
        "fixture repository copy for the CWF verifier (RAI-155.e)\n"
      )
      Files.createDirectories(tmpl.resolve("src").resolve("marker-module"))
      Files.writeString(
        tmpl.resolve("src").resolve("marker-module").resolve("notes.md"),
        "the marker module publishes its marker on request\n"
      )
      val ws = Files.createTempDirectory(s"cwf-verifier-ws-$tag")
      copyTree(tmpl, ws)
      deleteTree(tmpl)
      ws

  def copyTree(src: Path, dst: Path): Unit =
    Files.walk(src).iterator().asScala.foreach { p =>
      val rel = src.relativize(p)
      val target = dst.resolve(rel)
      if Files.isDirectory(p) then Files.createDirectories(target)
      else Files.copy(p, target)
    }
    ()

  def deleteTree(root: Path): Unit =
    if Files.exists(root) then
      Files
        .walk(root)
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .forEach(p => Files.deleteIfExists(p))
      ()

  def writeInput(ws: Path, targetPath: String, summary: String): Task[Path] =
    ZIO.attempt:
      val inFile = Files.createTempFile(s"cwf-verifier-input-$summary", ".json")
      val body =
        "{\n  \"schemaVersion\": 1,\n  \"workspace\": \"" +
          ws.toString.replace("\\", "\\\\") +
          "\",\n  \"task\": {\n    \"targetPath\": \"" + targetPath +
          "\",\n    \"summary\": \"" + summary + "\"\n  }\n}\n"
      Files.writeString(inFile, body)
      inFile

  // ------------------------------------------------------------------//
  // Independent checker (the SAME argv the trusted check carries)       //
  // ------------------------------------------------------------------//

  /** Runs the independent checker argv in the workspace; returns its exit code
    * (the STS repair-pattern probe: fails before the agent works, passes
    * after).
    */
  def independentCheck(ws: Path, argv: List[String]): Task[Int] =
    ZIO.attempt:
      val proc = new java.lang.ProcessBuilder(argv*)
        .directory(ws.toFile)
        .redirectErrorStream(true)
        .start()
      val done = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
      if done then proc.exitValue()
      else
        proc.destroyForcibly()
        -1

  // ------------------------------------------------------------------//
  // Launchers: fresh-process local main + fresh-process raider workflow //
  // ------------------------------------------------------------------//

  final case class ProcessRun(
      exitCode: Int,
      stdout: String,
      stderr: String
  )

  private def javaBin: String =
    Paths.get(java.lang.System.getProperty("java.home"), "bin", "java").toString

  private def runProcess(
      argv: List[String],
      envKey: Option[String],
      timeoutSeconds: Long
  ): Task[ProcessRun] =
    ZIO.attemptBlocking:
      val pb = new java.lang.ProcessBuilder(argv*)
      pb.environment().put("PATH", sys.env.getOrElse("PATH", "/usr/bin:/bin"))
      // Credential hygiene: when the case requires NO credential, the env var
      // (and helper reference) must be REMOVED, never inherited — the
      // no-credential preflight case is only honest if nothing leaks in.
      envKey match
        case Some(k) => pb.environment().put(Credentials.EnvKey, k)
        case None =>
          pb.environment().remove(Credentials.EnvKey)
          pb.environment().remove(Credentials.EnvHelper)
      val proc = pb.start()
      val outReader =
        new java.io.BufferedReader(
          new java.io.InputStreamReader(
            proc.getInputStream,
            StandardCharsets.UTF_8
          )
        )
      val errReader =
        new java.io.BufferedReader(
          new java.io.InputStreamReader(
            proc.getErrorStream,
            StandardCharsets.UTF_8
          )
        )
      val outBuf = new StringBuilder
      val errBuf = new StringBuilder
      val outThread = new Thread(() =>
        outReader.lines().forEach(l => outBuf.append(l).append('\n'))
      )
      val errThread = new Thread(() =>
        errReader.lines().forEach(l => errBuf.append(l).append('\n'))
      )
      outThread.setDaemon(true)
      errThread.setDaemon(true)
      outThread.start()
      errThread.start()
      val finished =
        proc.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
      if !finished then
        proc.destroy()
        if !proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) then
          proc.destroyForcibly()
        ProcessRun(-1, outBuf.result(), errBuf.result() + "[verifier timeout]")
      else
        outThread.join(2000)
        errThread.join(2000)
        ProcessRun(proc.exitValue(), outBuf.result(), errBuf.result())

  /** The CURRENT test classpath (the forked cli test JVM carries the real
    * module test classpath — the same one `raider compile` hands to dotc).
    */
  def currentClasspath: String =
    java.lang.System.getProperty("java.class.path", "")

  /** LOCAL launcher (Option L): the compiled workflow main as a plain fresh
    * subprocess on a fresh input file.
    */
  def runLocalMain(
      classesDir: Path,
      launcherMainClass: String,
      input: Path,
      envKey: Option[String]
  ): Task[ProcessRun] =
    runProcess(
      List(
        javaBin,
        "-cp",
        classesDir.resolve("classes").toString +
          java.io.File.pathSeparator + currentClasspath,
        launcherMainClass,
        input.toString
      ),
      envKey,
      timeoutSeconds = 180
    )

  final case class CiRun(
      process: ProcessRun,
      workDir: Option[Path],
      runArtifact: Option[RunArtifact]
  )

  /** CI launcher: `raider.cli.Main workflow run` as a fresh NON-TTY subprocess
    * (the product command; exit codes per the ci-runtime table). The work dir
    * is located by scanning `--out` for the `work-*` directory the launcher
    * creates; run.json is the product-written artifact.
    */
  def runCiLauncher(
      classesDir: Path,
      input: Path,
      out: Path,
      source: Option[Path],
      envKey: Option[String]
  ): Task[CiRun] =
    val argv =
      List(
        javaBin,
        "-cp",
        currentClasspath,
        "raider.cli.Main",
        "workflow",
        "run",
        "--classes",
        classesDir.toString,
        "--input",
        input.toString,
        "--out",
        out.toString
      ) ++ source.toList.flatMap(s => List("--source", s.toString))
    runProcess(argv, envKey, timeoutSeconds = 180).map { proc =>
      val workDir = findWorkDir(out)
      val runArtifact = workDir
        .map(_.resolve("run.json"))
        .filter(Files.isRegularFile(_))
        .map(p => Files.readString(p, StandardCharsets.UTF_8))
        .flatMap(decodeRunArtifact)
      CiRun(proc, workDir, runArtifact)
    }

  /** First `work-*` directory under `out` (the launcher's run work dir). */
  def findWorkDir(out: Path): Option[Path] =
    if !Files.isDirectory(out) then None
    else
      val listing = Files.list(out)
      try
        listing
          .iterator()
          .asScala
          .find(p =>
            Files.isDirectory(p) && p.getFileName.toString.startsWith("work-")
          )
      finally listing.close()

  /** Sends SIGTERM to the process behind a started CI launcher subprocess
    * (fresh-process cancellation ownership case).
    */
  def sigtermProcess(
      argv: List[String],
      envKey: Option[String]
  ): Task[Process] =
    ZIO.attempt:
      val pb = new java.lang.ProcessBuilder(argv*)
      pb.environment().put("PATH", sys.env.getOrElse("PATH", "/usr/bin:/bin"))
      envKey.foreach(k => pb.environment().put(Credentials.EnvKey, k))
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
      pb.redirectError(ProcessBuilder.Redirect.DISCARD)
      pb.start()

  def ciLauncherArgv(
      classesDir: Path,
      input: Path,
      out: Path,
      source: Option[Path]
  ): List[String] =
    List(
      javaBin,
      "-cp",
      currentClasspath,
      "raider.cli.Main",
      "workflow",
      "run",
      "--classes",
      classesDir.toString,
      "--input",
      input.toString,
      "--out",
      out.toString
    ) ++ source.toList.flatMap(s => List("--source", s.toString))

  // ------------------------------------------------------------------//
  // run.json / manifest reading (typed, product-output artifacts)       //
  // ------------------------------------------------------------------//

  /** The subset of the §7 run.json document the verifier reads (extra fields
    * are ignored by the decoder).
    */
  final case class ArtOutput(json: String)
  final case class ArtIdentity(sourceDigest: String)
  final case class ArtError(code: String, detail: String)

  final case class RunArtifact(
      status: String,
      exitCode: Int,
      taskVerdict: Option[String],
      output: Option[ArtOutput],
      buildIdentity: Option[ArtIdentity],
      error: Option[ArtError]
  )

  object ArtOutput:
    given zio.json.JsonCodec[ArtOutput] = DeriveJsonCodec.gen

  object ArtIdentity:
    given zio.json.JsonCodec[ArtIdentity] = DeriveJsonCodec.gen

  object ArtError:
    given zio.json.JsonCodec[ArtError] = DeriveJsonCodec.gen

  object RunArtifact:
    given zio.json.JsonCodec[RunArtifact] = DeriveJsonCodec.gen

  /** The manifest subset the verifier reads (§6.4; extra fields ignored). */
  final case class ManifestArtifact(
      schemaVersion: Int,
      kind: String,
      buildIdentity: Option[ArtIdentity]
  )

  object ManifestArtifact:
    given zio.json.JsonCodec[ManifestArtifact] = DeriveJsonCodec.gen

  /** The build-identity subset the verifier reads (the product compile
    * artifact's identity record; extra fields ignored).
    */
  final case class ArtToolchain(scalaVersion: String, compiler: String)

  final case class BuildIdentityArtifact(
      schemaVersion: Int,
      kind: String,
      sourceDigest: String,
      toolchain: ArtToolchain
  )

  object ArtToolchain:
    given zio.json.JsonCodec[ArtToolchain] = DeriveJsonCodec.gen

  object BuildIdentityArtifact:
    given zio.json.JsonCodec[BuildIdentityArtifact] = DeriveJsonCodec.gen

  def decodeBuildIdentity(text: String): Option[BuildIdentityArtifact] =
    text.fromJson[BuildIdentityArtifact].toOption

  def decodeRunArtifact(text: String): Option[RunArtifact] =
    text.fromJson[RunArtifact].toOption

  def runStatus(a: Option[RunArtifact]): Option[String] = a.map(_.status)

  def runExitCode(a: Option[RunArtifact]): Option[Int] = Some(
    a.map(_.exitCode).getOrElse(-1)
  )

  def runVerdict(a: Option[RunArtifact]): Option[String] =
    a.flatMap(_.taskVerdict)

  /** The workflow report from run.json (`output.json` is the JSON-encoded
    * report string).
    */
  def runReport(a: Option[RunArtifact]): Option[String] =
    a.flatMap(_.output).flatMap(o => o.json.fromJson[String].toOption)

  /** buildIdentity.sourceDigest without the sha256- prefix. */
  def runSourceDigest(a: Option[RunArtifact]): Option[String] =
    a.flatMap(_.buildIdentity).map(_.sourceDigest.stripPrefix("sha256-"))

  def runErrorCode(a: Option[RunArtifact]): Option[String] =
    a.flatMap(_.error).map(_.code)

  def runErrorDetail(a: Option[RunArtifact]): Option[String] =
    a.flatMap(_.error).map(_.detail)

  // ------------------------------------------------------------------//
  // Deterministic normalization (spec: contracts/chat-workflow/README)  //
  // ------------------------------------------------------------------//

  final case class NormalizedRun(
      launcher: String,
      exitCode: Int,
      report: String,
      effects: List[String],
      taskInstance: String,
      sourceDigest: String
  )

  val PublishCandidates: List[String] = List("marker.txt", "marker2.txt")

  def effectsOf(ws: Path): List[String] =
    PublishCandidates.filter(p => Files.exists(ws.resolve(p))).sorted

  def normalizeLocal(
      run: ProcessRun,
      ws: Path,
      taskInstance: String,
      sourceDigest: String
  ): NormalizedRun =
    NormalizedRun(
      launcher = "local-main",
      exitCode = run.exitCode,
      report = run.stdout.trim,
      effects = effectsOf(ws),
      taskInstance = taskInstance,
      sourceDigest = sourceDigest
    )

  def normalizeCi(
      run: CiRun,
      ws: Path,
      taskInstance: String
  ): NormalizedRun =
    NormalizedRun(
      launcher = "raider-workflow-run",
      exitCode = run.process.exitCode,
      report = runReport(run.runArtifact).getOrElse(""),
      effects = effectsOf(ws),
      taskInstance = taskInstance,
      sourceDigest = runSourceDigest(run.runArtifact).getOrElse("")
    )

  /** Pairwise rule (README normalization spec): two runs are OBSERVABLY equal
    * when every kept field matches except `launcher`, which identifies the
    * launcher surface, not the outcome.
    */
  def observablyEqual(a: NormalizedRun, b: NormalizedRun): Boolean =
    a.copy(launcher = b.launcher) == b

  // ------------------------------------------------------------------//
  // Manual edit (the owner's meaningful-edit surface)                   //
  // ------------------------------------------------------------------//

  /** Applies the MEANINGFUL manual edit to an exported source: flips the
    * prompt-template publish token AND the check argv target. Returns the
    * edited text and the number of replacements performed (the corpus pins the
    * expected count).
    */
  def applyManualEdit(
      sourceText: String,
      from: String,
      to: String
  ): (String, Int) =
    val count =
      sourceText.split(java.util.regex.Pattern.quote(from), -1).length - 1
    (sourceText.replace(from, to), count)

  /** The `@main` launcher class name, read from the EXPORTED source (product
    * output — never from renderer internals).
    */
  def launcherMainClassOf(sourceText: String): Option[String] =
    raw"@main def (\w+)\(".r
      .findFirstMatchIn(sourceText)
      .map(_.group(1))

  // ------------------------------------------------------------------//
  // Misc                                                                //
  // ------------------------------------------------------------------//

  def sha256OfFile(p: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(Files.readAllBytes(p))
    hash.map(b => f"${b & 0xff}%02x").mkString

  def sha256OfText(text: String): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(text.getBytes(StandardCharsets.UTF_8))
    hash.map(b => f"${b & 0xff}%02x").mkString

  /** The captured intent token must appear NOWHERE under the directory before
    * the export (the artifact is derived from the capture, never pre-baked).
    */
  def tokenAbsentUnder(root: Path, token: String): Boolean =
    if !Files.exists(root) then true
    else
      val walk = Files.walk(root)
      try
        walk
          .iterator()
          .asScala
          .filter(Files.isRegularFile(_))
          .forall { p =>
            val text = Files.readString(p, StandardCharsets.UTF_8)
            !text.contains(token)
          }
      finally walk.close()

  def safeDeleteTree(root: Path): Unit =
    try deleteTree(root)
    catch case NonFatal(_) => ()

end ChatWorkflowVerifierHarness
