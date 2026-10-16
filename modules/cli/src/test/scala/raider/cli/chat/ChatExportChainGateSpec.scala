package raider.cli.chat

import zio.*
import zio.stream.ZStream
import zio.test.*
import zio.test.Assertion.*

import raider.core.*
import raider.cli.run.Credentials
import raider.cli.workflow.{WorkflowCompile, WorkflowRun}
import raider.dsl.workflow.ChatWorkflowExport
import raider.runtime.admission.Admission
import raider.runtime.budget.{BudgetLimits, CostEstimate}
import raider.runtime.events.EventHub
import raider.runtime.jobs.JobManager
import raider.runtime.loop.AgentLoop

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** RAI-160 chain gate (EW-01..EW-03): the RAI-155 chain SELECT -> EXPORT ->
  * COMPILE -> RUN works end-to-end with NO pre-baked example.
  *
  *   - EW-01: turns are captured through the REAL chat loop
  * (`ChatLoop.processMessage` over a scripted backend, live
  * JobManager/EventHub/Admission — the TaskCaptureSpec pattern); `:task`
  * mark/select build the snapshot; `:export-workflow` (the real
  * `ChatLoop.exportWorkflow` command path) writes the §6.2 layout — the target
  * does not exist and the captured intent token appears NOWHERE on disk before
  * the export (derived, not pre-baked); the generated source carries the
  * captured intent text; `raider compile` (the product WorkflowCompile, pinned
  * dotc) succeeds; `raider workflow run` (the product WorkflowRun launcher)
  * drives the compiled artifact against a scripted local OpenAI-compatible
  * stand: REAL tool effect (proc_run creates marker.txt on the fresh workspace)
  * and an INDEPENDENT check verdict (/bin/test -f on the NEW workspace,
  * run.json taskVerdict=trusted-checks-passed). Re-export ×2 to distinct
  * targets is byte-identical.
  *   - EW-02: export refusals are typed end-to-end through the command path:
  * legacy/no-ledger session (CWF-R2), secret-bearing snapshot refused at the
  * ADAPTER boundary (CWF-R4 — independent of select time, value never echoed),
  * staging remnant + existing target (CWF-R5, no silent partial writes).
  *   - EW-03: an export performed WHILE a turn is mid-stream leaves the
  * ledger and the artifact intact (the snapshot is a turn-boundary bound; the
  * in-flight turn stays typed-Unknown and outside the export); concurrent
  * exports to distinct out-dirs are isolated and byte-identical.
  *
  * No live provider: the stand is a loopback JDK HttpServer (ephemeral port,
  * scripted SSE frames — the AnthropicStandSpec/WorkflowLaunchSpec discipline);
  * compile dispatches nothing; credentials ride the injected env of
  * WorkflowRun.run (never argv/logs).
  */
object ChatExportChainGateSpec extends ZIOSpecDefault:

  private val IntentToken = "EW01-INTENT-" + "TOKEN-" + "7f3a"

  private val IntentText =
    s"$IntentToken: make the marker module compile and keep checks green"

  private val RefinementText = "keep edits inside marker.txt only"
  private val Summary = "chain gate probe task"
  private val StandKey = "chain-stand-key"
  private val FinalAnswer = "chain stand final answer"

  // ----- scripted capture backend (TaskCaptureSpec pattern) ------------------

  private sealed trait Reply
  private case class Text(t: String) extends Reply
  private case object Hang extends Reply

  private final class Scripted(replies: List[Reply]) extends ModelBackend:
    private val calls = AtomicInteger(0)
    private val att = AttemptId("chain-att")

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

  private def makeCtx(
      backend: ModelBackend,
      provider: String,
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
          backend,
          ToolRegistry.empty,
          "chain-model",
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
      provider = provider,
      workspacePath = "/tmp/ws-chain-fixture",
      hub,
      manager,
      root,
      sink = _ => ZIO.unit,
      baseUrl = baseUrl
    )

  private def waitLedger(
      ref: Ref[Vector[TurnRecord]],
      n: Int
  ): UIO[Option[Vector[TurnRecord]]] =
    def poll(i: Int): UIO[Option[Vector[TurnRecord]]] =
      ref.get.flatMap: ts =>
        if ts.size >= n then ZIO.succeed(Some(ts))
        else if i <= 0 then ZIO.succeed(None)
        else ZIO.yieldNow *> poll(i - 1)
    poll(200_000)

  // ----- scripted OpenAI-compatible stand (WorkflowLaunchSpec pattern) -------

  private val toolRoundFrames: Vector[String] = Vector(
    """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}""",
    """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"callA","function":{"name":"proc_run","arguments":""}}]},"finish_reason":null}]}""",
    """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"argv\":"}}]},"finish_reason":null}]}""",
    """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"[\"/usr/bin/touch\",\"marker.txt\"]}"}}]},"finish_reason":null}]}""",
    """{"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
  )

  private val finalFrames: Vector[String] = Vector(
    """{"id":"f1","choices":[{"index":0,"delta":{"role":"assistant","content":"chain stand final answer"},"finish_reason":null}]}""",
    """{"id":"f1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
  )

  private def sse(frames: Seq[String]): Array[Byte] =
    (frames.map(f => "data: " + f + "\n\n").mkString + "data: [DONE]\n\n")
      .getBytes(StandardCharsets.UTF_8)

  private final case class Stand(url: String, server: HttpServer):
    def stop(): Unit = server.stop(0)

  private def startStand(): zio.Task[Stand] =
    ZIO.attempt:
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
            val frames =
              if body.contains("\"role\":\"tool\"") then finalFrames
              else toolRoundFrames
            val payload = sse(frames)
            ex.getResponseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, payload.length.toLong)
            ex.getResponseBody.write(payload)
            ()
          finally ex.close()
      )
      server.start()
      Stand("http://127.0.0.1:" + server.getAddress.getPort + "/v1", server)

  // ----- fixture helpers -------------------------------------------------------

  private def freshDir(prefix: String): zio.Task[Path] =
    ZIO.attempt(Files.createTempDirectory(prefix))

  /** Marks + checks + selects through the REAL :task implementations and stores
    * the snapshot (what the :task select handler does).
    */
  private def markAndSelect(
      ctx: ChatLoop.RunCtx,
      span: Int
  ): ZIO[Any, RaiderError, TaskSelection] =
    for
      turns <- ctx.ledgerRef.get
      checks <- ctx.checksRef.get
      _ <- TaskCommands.mark(ctx.ledgerRef, 1, TurnLedger.Kind.Intent)
      _ <- ZIO.foreachDiscard(2 to span)(i =>
        TaskCommands.mark(ctx.ledgerRef, i, TurnLedger.Kind.Refinement)
      )
      _ <- TaskCommands.registerCheck(
        ctx.checksRef,
        "marker",
        List("/bin/test", "-f", "marker.txt")
      )
      turns2 <- ctx.ledgerRef.get
      checks2 <- ctx.checksRef.get
      sel = TaskCommands.select(turns2.toList, checks2, span, 12)
      _ <- sel match
        case Right(value) => ctx.selectionRef.set(Some(value))
        case Left(err) =>
          ZIO.die(new IllegalStateException("select refused: " + err.detail))
    yield sel.toOption.get

  private def exportArgs(target: Path): String =
    s"--out $target --name ew01_chain --target-path marker.txt --summary $Summary"

  private def bytesOf(p: Path): Array[Byte] = Files.readAllBytes(p)

  private def sourceOf(target: Path): Path =
    target.resolve("ew01_chain.scala")

  /** The captured intent token must appear NOWHERE under the directory (the
    * export is derived from the captured turns, never pre-baked).
    */
  private def tokenAbsentUnder(root: Path): Boolean =
    if !Files.exists(root) then true
    else
      val walk = Files.walk(root)
      try
        walk
          .iterator()
          .asScala
          .filter(Files.isRegularFile(_))
          .forall { p =>
            val bytes = bytesOf(p)
            new String(bytes, StandardCharsets.UTF_8)
              .contains(IntentToken) == false
          }
      finally walk.close()

  private def cleanupTree(root: Path): Unit =
    try
      Files
        .walk(root)
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .forEach(p => Files.deleteIfExists(p))
      ()
    catch case NonFatal(_) => ()

  private def inputJson(ws: Path): String =
    "{\n  \"schemaVersion\": 1,\n  \"workspace\": \"" +
      ws.toString.replace("\\", "\\\\") +
      "\",\n  \"task\": {\n    \"targetPath\": \"marker.txt\",\n" +
      "    \"summary\": \"" + Summary + "\"\n  }\n}"

  private def workDirOf(out: Path): Option[Path] =
    val listing = Files.list(out)
    try
      listing
        .iterator()
        .asScala
        .find(p =>
          Files.isDirectory(p) && p.getFileName.toString.startsWith("work-")
        )
    finally listing.close()

  // ----- spec ------------------------------------------------------------------

  def spec =
    suite("ChatExportChainGate (RAI-160, EW-01..EW-03)")(
      test(
        "EW-01: capture -> :task select -> :export-workflow -> pinned compile -> scripted-policy workflow run, with a derived (never pre-baked) artifact and byte-identical re-export"
      ) {
        ZIO.acquireReleaseWith(startStand())(h => ZIO.succeed(h.stop())) {
          stand =>
            for
              ctx <- makeCtx(
                Scripted(
                  List(
                    Text("[fixture] intent ack"),
                    Text("[fixture] refinement ack")
                  )
                ),
                provider = "openai",
                baseUrl = stand.url
              )
              // -- capture through the REAL loop (two jobs, two ledger turns)
              _ <- ChatLoop.processMessage(ctx, IntentText)
              _ <- ChatLoop.processMessage(ctx, RefinementText)
              ledger <- ctx.ledgerRef.get
              sel <- markAndSelect(ctx, span = 2)

              // -- the pre-export state: nothing derived exists yet
              outRoot <- freshDir("ew01-out")
              target = outRoot.resolve("chain-a")
              targetAbsent = !Files.exists(target)
              tokenAbsentBefore = tokenAbsentUnder(outRoot)

              // -- :export-workflow (the real command path)
              lines = ChatLoop.exportWorkflow(ctx, exportArgs(target))
              exported = Files.exists(sourceOf(target))
              srcText =
                if exported then
                  Files.readString(sourceOf(target), StandardCharsets.UTF_8)
                else ""
              derivedFromCapture =
                srcText.contains(IntentToken) &&
                  srcText.contains(RefinementText) &&
                  srcText.contains(stand.url) && // origin came from the session
                  srcText.contains("val maxRounds: Int = 12")

              // -- raider compile (product command, pinned dotc)
              buildDir = outRoot.resolve("build")
              compileExit = WorkflowCompile.command(
                List(sourceOf(target).toString, "--out", buildDir.toString)
              )

              // -- raider workflow run on a scripted policy
              ws <- freshDir("ew01-ws")
              inFile = outRoot.resolve("input.json")
              _ <- ZIO.attempt(
                Files.writeString(inFile, inputJson(ws))
              )
              runOut <- freshDir("ew01-run")
              outcome <- WorkflowRun.run(
                WorkflowRun.Args(
                  classes = buildDir,
                  input = inFile,
                  out = runOut,
                  source = Some(sourceOf(target))
                ),
                env = Map(Credentials.EnvKey -> StandKey)
              )
              runJson = workDirOf(runOut)
                .map(w =>
                  Files
                    .readString(w.resolve("run.json"), StandardCharsets.UTF_8)
                )
                .getOrElse("")
              markerUp = Files.exists(ws.resolve("marker.txt"))

              // -- deterministic re-export x2 (same snapshot, distinct target)
              targetB = outRoot.resolve("chain-b")
              lines2 = ChatLoop.exportWorkflow(ctx, exportArgs(targetB))
              files = Vector(
                "ew01_chain.scala",
                "ew01_chain-manifest.json",
                "ew01_chain.input.json"
              )
              identical = files.forall(f =>
                bytesOf(target.resolve(f)).toSeq == bytesOf(
                  targetB.resolve(f)
                ).toSeq
              )
            yield assertTrue(
              ledger.size == 2,
              ledger.map(_.status) == List(
                TurnLedger.Status.Completed,
                TurnLedger.Status.Completed
              ),
              sel.turnIds == ledger.map(_.turnId).toList,
              targetAbsent,
              tokenAbsentBefore,
              lines.nonEmpty && lines.head.startsWith("workflow exported:"),
              lines.exists(l =>
                l.contains("manifest: ") && l.contains(
                  "ew01_chain-manifest.json"
                )
              ),
              lines.exists(l =>
                l.contains("input example: ") && l.contains(
                  "ew01_chain.input.json"
                )
              ),
              exported,
              derivedFromCapture,
              compileExit == 0,
              outcome.exitCode == 0,
              outcome.error.isEmpty,
              runJson.contains("\"status\": \"Succeeded\"") ||
                runJson.contains("\"status\":\"Succeeded\""),
              runJson.contains("trusted-checks-passed"),
              markerUp, // REAL tool effect by the scripted agent stage
              lines2.nonEmpty && lines2.head.startsWith("workflow exported:"),
              identical
            )
        }
      },
      test(
        "EW-02: export refusals typed end-to-end — legacy session (R2), adapter-boundary secret snapshot (R4), staging remnant + existing target (R5); no partial writes"
      ) {
        for
          ctx <- makeCtx(
            Scripted(
              List(
                Text("[fixture] intent ack"),
                Text("[fixture] refinement ack")
              )
            ),
            provider = "openai",
            baseUrl = "http://127.0.0.1:1/v1"
          )
          outRoot <- freshDir("ew02-out")

          // R2: legacy (no ledger, no selection) session — export refuses
          // before anything is written
          r2Lines = ChatLoop.exportWorkflow(
            ctx,
            exportArgs(outRoot.resolve("r2-target"))
          )

          // capture through the REAL loop, then build the snapshot through
          // the REAL :task implementations
          _ <- ChatLoop.processMessage(ctx, IntentText)
          _ <- ChatLoop.processMessage(ctx, RefinementText)
          sel <- markAndSelect(ctx, span = 2)

          // R5 remnant: a leftover staging directory blocks the target
          r5Target = outRoot.resolve("r5-target")
          _ <- ZIO.attempt:
            Files.createDirectory(
              outRoot.resolve(".r5-target" + ChatWorkflowExport.StagingSuffix)
            )
          r5Lines = ChatLoop.exportWorkflow(ctx, exportArgs(r5Target))

          // R4 at the adapter boundary: a snapshot built DIRECTLY (bypassing
          // select-time validation) with credential-shaped request text —
          // the adapter is the second, independent gate
          secret =
            "sk-" + "abcdefghijklmnopqrstuvwxyz012345" // synthetic; concatenated so the secret-literal scanner does not flag the refusal fixture
          ledger <- ctx.ledgerRef.get
          leakTurn = TurnLedger
            .finishTurn(
              TurnLedger.beginTurn(
                "use key " + secret + " for the stand",
                "job-leak",
                ConfigRefs("/tmp/ws", "openai", "chain-model"),
                0L
              ),
              TurnLedger.Status.Completed,
              Nil,
              0L
            )
            .copy(kind = TurnLedger.Kind.Refinement)
          leaky = TaskSelection(
            turns = List(ledger.head, leakTurn),
            intent = ledger.head,
            refinements = List(leakTurn),
            checks = List(
              NamedCheck("marker", List("/bin/test", "-f", "marker.txt"))
            ),
            maxRoundsBound = 12
          )
          _ <- ctx.selectionRef.set(Some(leaky))
          r4Target = outRoot.resolve("r4-target")
          r4Lines = ChatLoop.exportWorkflow(ctx, exportArgs(r4Target))
          _ <- ctx.selectionRef.set(Some(sel))

          // R5 existing target: re-export never overwrites user edits
          okTarget = outRoot.resolve("ok-target")
          firstLines = ChatLoop.exportWorkflow(ctx, exportArgs(okTarget))
          okBytes = bytesOf(sourceOf(okTarget))
          secondLines = ChatLoop.exportWorkflow(ctx, exportArgs(okTarget))
        yield assertTrue(
          r2Lines.size == 1,
          r2Lines.head.startsWith("export refused [RA-INP]: CWF-R2"),
          r2Lines.head.contains("cannot invent absent execution records"),
          !Files.exists(outRoot.resolve("r2-target")),
          r5Lines.size == 1,
          r5Lines.head.startsWith("export refused [RA-INP]: CWF-R5"),
          r5Lines.head.contains("interrupted export remnant"),
          !Files.exists(r5Target),
          r4Lines.size == 1,
          r4Lines.head.startsWith("export refused [RA-INP]: CWF-R4"),
          r4Lines.head.contains("t-"),
          !r4Lines.head.contains(secret),
          !Files.exists(r4Target),
          firstLines.head.startsWith("workflow exported:"),
          secondLines.size == 1,
          secondLines.head.startsWith("export refused [RA-INP]: CWF-R5"),
          secondLines.head.contains("never overwrites"),
          bytesOf(sourceOf(okTarget)).toSeq == okBytes.toSeq
        )
      },
      test(
        "EW-03: an export during an active streaming turn corrupts neither the ledger nor the artifact; concurrent exports to distinct out-dirs are isolated"
      ) {
        for
          ctx <- makeCtx(
            Scripted(
              List(
                Text("[fixture] ack1"),
                Text("[fixture] ack2"),
                Hang // the third message hangs mid-job
              )
            ),
            provider = "openai",
            baseUrl = "http://127.0.0.1:1/v1"
          )
          _ <- ChatLoop.processMessage(ctx, IntentText)
          _ <- ChatLoop.processMessage(ctx, RefinementText)
          sel <- markAndSelect(ctx, span = 2)

          outRoot <- freshDir("ew03-out")
          // quiescent reference export
          refTarget = outRoot.resolve("ref")
          _ = ChatLoop.exportWorkflow(ctx, exportArgs(refTarget))

          // a turn goes mid-flight (status Unknown, outside the snapshot)
          fiber <- ChatLoop.processMessage(ctx, "in-flight streaming turn").fork
          begun <- waitLedger(ctx.ledgerRef, 3)
          midFlight <- ctx.ledgerRef.get

          // export WHILE the turn streams: the turn-boundary snapshot governs
          duringTarget = outRoot.resolve("during")
          duringLines = ChatLoop.exportWorkflow(
            ctx,
            exportArgs(duringTarget)
          )
          afterExport <- ctx.ledgerRef.get
          _ <- fiber.interrupt
          afterInterrupt <- ctx.ledgerRef.get

          // concurrent exports to DISTINCT out-dirs: isolated, both complete
          concA = outRoot.resolve("conc-a")
          concB = outRoot.resolve("conc-b")
          exportTo = (p: Path) =>
            ZIO.succeed(ChatLoop.exportWorkflow(ctx, exportArgs(p)))
          fa <- exportTo(concA).fork
          fb <- exportTo(concB).fork
          la <- fa.join
          lb <- fb.join
          files = Vector(
            "ew01_chain.scala",
            "ew01_chain-manifest.json",
            "ew01_chain.input.json"
          )
          refVsDuring = files.forall(f =>
            bytesOf(refTarget.resolve(f)).toSeq ==
              bytesOf(duringTarget.resolve(f)).toSeq
          )
          concIdentical = files.forall(f =>
            bytesOf(concA.resolve(f)).toSeq == bytesOf(concB.resolve(f)).toSeq
          )
        yield assertTrue(
          begun.isDefined,
          midFlight.size == 3,
          midFlight(2).status == TurnLedger.Status.Unknown,
          midFlight(2).request == "in-flight streaming turn",
          duringLines.head.startsWith("workflow exported:"),
          afterExport.size == 3, // the export did not touch the ledger
          refVsDuring, // the artifact is identical to the quiescent export
          afterInterrupt.size == 3,
          afterInterrupt(2).status == TurnLedger.Status.Unknown,
          afterInterrupt(2).finishedAtMs.isEmpty,
          la.head.startsWith("workflow exported:"),
          lb.head.startsWith("workflow exported:"),
          concIdentical
        )
      }
    ) @@ TestAspect.timeout(600.seconds)

end ChatExportChainGateSpec
