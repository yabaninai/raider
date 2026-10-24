package raider.cli.chat

import raider.core.*
import raider.cli.CoderPrompts
import raider.cli.run.{Credentials, EventExport}
import raider.core.result.ExitCodes
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.events.EventHub
import raider.runtime.events.EventEnvelope
import raider.runtime.jobs.{JobHandle, JobManager, StatusLedger}
import raider.runtime.loop.{AgentLoop, LoopEvents}
import raider.runtime.tools.approval.{
  ToolApproval,
  ToolApprovalContext,
  ToolApprovalDecision,
  ToolApprovalHook
}
import zio.{Exit, Promise, Ref, Scope, ZIO}
import zio.json.EncoderOps

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

/** Interactive chat with context continuity: each message carries the FULL
  * conversation history, so the model remembers previous exchanges. This is the
  * critical missing piece for self-hosting — without it, every message starts
  * from scratch and iterative coding is impossible.
  *
  * REAL-TIME STREAMING (nightly Phase 2.1): the OpenAI-compatible transport is
  * the SSE streaming backend wrapped in StreamingDisplay — text deltas print to
  * stdout as they arrive, ready tool calls print `→ tool(args)` to stderr the
  * moment the model emits them, and tool results print `← tool: preview` when
  * they complete (tracer).
  *
  * Usage: raider chat [--provider mock|openai|anthropic] [--base-url URL]
  * [--model NAME] [--workspace PATH]
  *
  * Commands: :help :quit :clear :context :tools :trace
  */
object ChatLoop:

  private val Prompt = "raider> "
  private[chat] val MaxHistoryMessages = 40 // bounded context

  // Conversation compaction (nightly Phase 2.3): past the threshold the oldest
  // slice is summarized into one system message and the recent tail stays
  // intact; anything between the summarized head and the kept tail is dropped.
  private[chat] val CompactThreshold = 30
  private[chat] val CompactKeep = 20
  private[chat] val CompactSummarizeMax = 10

  /** Pure compaction plan: None = nothing to do; Some((summarize, keep)). */
  private[chat] def splitForCompaction(
      history: List[RequestMessage],
      force: Boolean = false
  ): Option[(List[RequestMessage], List[RequestMessage])] =
    val applies = force || history.size > CompactThreshold
    if !applies || history.size <= CompactKeep then None
    else
      val recent = history.takeRight(CompactKeep)
      val oldCount = history.size - CompactKeep
      val toSummarize = history.take(math.min(oldCount, CompactSummarizeMax))
      Some((toSummarize, recent))

  // Known-valid limits (defaults + delegation-capable tool slots); total
  // fallback so admission setup can never depend on a null value.
  private val defaultLimits: BudgetLimits =
    BudgetLimits(
      hardUsdCap = None,
      maxConcurrentLlm = 1,
      maxConcurrentTools = 2,
      maxChildren = 64,
      maxDepth = 16,
      maxAttempts = 48,
      queueCapacity = 256
    )

  /** One chat session's wiring (RAI-060): the hub + JobManager live for the
    * whole session (one root per chat run); each user message runs as ONE job
    * of that manager, so the status line spans queued→model→tool→terminal with
    * the job layer (RAI-059) owning the transitions.
    *
    * RAI-155.b (ADR-033 §5): `ledgerRef` is the exportable turn ledger —
    * capture provenance kept BESIDE the message history, so compaction (which
    * rewrites `historyRef`) and history truncation never touch it. `checksRef`
    * holds user-registered named trusted checks; `selectionRef` holds the
    * validated immutable selection snapshot (the RAI-155.c renderer entry
    * point).
    */
  private[chat] final case class RunCtx(
      loop: AgentLoop, // base loop, events = None (per-message copy adds them)
      limits: BudgetLimits,
      historyRef: Ref[List[RequestMessage]],
      traceRef: Ref[Vector[raider.core.trace.TraceEvent]],
      ledgerRef: Ref[Vector[TurnRecord]],
      checksRef: Ref[List[NamedCheck]],
      selectionRef: Ref[Option[TaskSelection]],
      provider: String,
      workspacePath: String,
      hub: EventHub,
      manager: JobManager,
      root: RootId,
      sink: String => zio.UIO[Unit],
      // RAI-160: the session's non-secret transport base-URL reference
      // (ADR-033 §5.1 configRefs discipline — a reference, never a credential);
      // carried into the export origin. Defaulted so existing test
      // constructions compile unchanged.
      baseUrl: String = "",
      private[chat] var approvalInput: Option[BufferedReader] = None
  )

  def run(
      provider: String,
      baseUrl: String,
      model: String,
      workspace: Option[String],
      resume: Option[String] = None
  ): ZIO[Any, Nothing, Int] =
    // RAI-064 preflight: a live provider requires a credential from the env
    // BEFORE any spend — fail fast with a clear typed message. A missing key
    // must NEVER silently fall back to a mock (that would masquerade as live).
    ZIO
      .fromEither(Credentials.resolve(provider))
      .foldZIO(
        err =>
          ZIO.succeed {
            System.err.println(s"raider: ${err.code}: ${err.detail}")
            ExitCodes.ConfigInvalid
          },
        keyOpt =>
          runWithKey(provider, baseUrl, model, keyOpt, workspace, resume)
      )

  private def runWithKey(
      provider: String,
      baseUrl: String,
      model: String,
      keyOpt: Option[String],
      workspace: Option[String],
      resume: Option[String]
  ): ZIO[Any, Nothing, Int] =
    for
      // RAI-155.b: one resolved workspace reference shared by the toolset and
      // the capture configRefs (non-secret path reference, §5.1)
      wsPath <- ZIO.succeed(
        workspace.getOrElse(Paths.get("").toAbsolutePath.toString)
      )
      registry <- makeRegistry(wsPath)
      limits <- ZIO.succeed(
        BudgetLimits
          .make(maxConcurrentTools = 2, maxAttempts = 48)
          .getOrElse(defaultLimits)
      )
      admissionE <- Admission.make(Right(limits)).either
      admission = admissionE.fold(
        _ =>
          zio.Unsafe.unsafe { implicit u =>
            zio.Runtime.default.unsafe
              .run(Admission.make(Right(defaultLimits)))
              .getOrThrow()
          },
        identity
      )
      root <- ZIO.succeed(AgentLoop.newRoot())
      // RAI-060: the event seam of this chat session — hub (ADR-024 §2.2/§2.3)
      // + job layer (RAI-059); StatusLedger bridges job transitions into the
      // hub, LoopEvents (per message) bridges loop emissions.
      hub <- EventHub.make()
      manager <- JobManager.make(
        onTransition = Some(StatusLedger.onTransition(hub, root))
      )
      writer <- ZIO.succeed(new ChatWriter)
      backend = makeBackendSimple(provider, baseUrl, keyOpt, writer)
      traceRef <- Ref.make(Vector.empty[raider.core.trace.TraceEvent])
      // VERBOSE trace: prints to stderr in real-time + records to Ref
      tracer <- ZIO.succeed(makeTracer(traceRef))
      // --resume seeds the conversation from a saved session (Phase 2.2)
      historyRef <- Ref.make(List.empty[RequestMessage])
      // RAI-155.b capture state (ADR-033 §5): ledger + named checks + the
      // validated selection snapshot (selection is session-authoring state,
      // not persisted; it is rebuilt explicitly with :task select)
      ledgerRef <- Ref.make(Vector.empty[TurnRecord])
      checksRef <- Ref.make(List.empty[NamedCheck])
      selectionRef <- Ref.make(Option.empty[TaskSelection])
      _ <- resume match
        case Some(name) =>
          SessionStore
            .load(SessionStore.defaultBase, name)
            .foldZIO(
              err =>
                ZIO.succeed(
                  System.err.println(
                    s"resume failed [${err.code}]: ${err.detail}; starting empty"
                  )
                ),
              session =>
                historyRef.set(session.messages) *>
                  ledgerRef.set(session.turns.toVector) *>
                  checksRef.set(session.checks) *>
                  ZIO.succeed {
                    println(
                      s"resumed '$name': ${session.messages.size} messages " +
                        s"(saved ${session.timestamp}, model ${session.model})"
                    )
                    // §5.3: reopen names a missing ledger explicitly and shows
                    // saved-vs-active config mismatch; authority is never restored
                    TaskCommands
                      .restoreNotices(
                        session.turns,
                        ConfigRefs(wsPath, provider, model)
                      )
                      .foreach(n => println(s"  $n"))
                  }
            )
        case None => ZIO.unit
      // single-sourced coder prompt (raider.cli.CoderPrompts, Phase 4.3)
      sysPrompt = Some(CoderPrompts.CoderSystemPrompt)
      loop <- ZIO.succeed(
        AgentLoop(
          backend,
          registry,
          model,
          admission,
          root,
          raider.runtime.budget.CostEstimate.Unknown,
          tracer,
          sysPrompt
        )
      )
      _ <- banner(registry, model, workspace)
      ctx = RunCtx(
        loop,
        limits,
        historyRef,
        traceRef,
        ledgerRef,
        checksRef,
        selectionRef,
        provider,
        wsPath,
        hub,
        manager,
        root,
        sink = line => ZIO.succeed(writer.statusLine(line)),
        baseUrl = baseUrl
      )
      exitCode <- repl(ctx)
    yield exitCode

  /** Prints every event to stderr in real-time (colored) AND records it. */
  private def makeTracer(
      ref: Ref[Vector[raider.core.trace.TraceEvent]]
  ): raider.core.trace.TraceEvent => zio.UIO[Unit] =
    ev =>
      logEvent(ev) *>
        ref.update(_ :+ ev)

  private def logEvent(ev: raider.core.trace.TraceEvent): zio.UIO[Unit] =
    ZIO.succeed:
      val stderr = System.err
      ev match
        case raider.core.trace.TraceEvent.RunStarted(_, m, msgs) =>
          stderr.println(f"\n${C.bold}${C.reset}── run ── model=$m msgs=$msgs")
        case raider.core.trace.TraceEvent.RoundStarted(_, r) =>
          stderr.println(f"${C.dim}${C.reset}[round $r]${C.reset}")
        case raider.core.trace.TraceEvent.ModelCall(_, _, msgs) =>
          stderr.println(s"  ${C.cyan}${C.reset}→ model${C.reset} ($msgs msgs)")
        // TextReceived is recorded for :trace but NOT printed here: text
        // deltas already stream to stdout in real time (Phase 2.1)
        case raider.core.trace.TraceEvent.TextReceived(_, _, _) => ()
        case raider.core.trace.TraceEvent.ToolCallStarted(
              _,
              _,
              tool,
              _,
              args
            ) =>
          stderr.println(
            s"  ${C.blue}${C.reset}→ $tool${C.reset} ${args.take(120)}"
          )
        case raider.core.trace.TraceEvent.ToolCallFinished(
              _,
              _,
              tool,
              _,
              result,
              ok
            ) =>
          val marker =
            if ok then s"${C.green}${C.reset}✓" else s"${C.red}${C.reset}✗"
          stderr.println(s"  $marker ← $tool: ${result.take(150)}")
        case raider.core.trace.TraceEvent.ToolCallRefused(
              _,
              _,
              tool,
              _,
              reason
            ) =>
          stderr.println(
            s"  ${C.yellow}${C.reset}⊘ $tool${C.reset} refused: $reason"
          )
        case raider.core.trace.TraceEvent.ChildDelegated(_, agent, childId) =>
          stderr.println(
            s"  ${C.cyan}${C.reset}→ delegate: $agent → $childId${C.reset}"
          )
        case raider.core.trace.TraceEvent.ChildAwaited(_, childId, status) =>
          stderr.println(
            s"  ${C.cyan}${C.reset}← child $childId: $status${C.reset}"
          )
        case raider.core.trace.TraceEvent.RunFinished(_, outcome, detail) =>
          stderr.println(
            s"${C.bold}${C.reset}── done: ${detail.take(120)}${C.reset}\n"
          )
        case raider.core.trace.TraceEvent.RunFailed(_, code, detail) =>
          stderr.println(
            s"${C.red}${C.reset}── FAILED [$code]: ${detail.take(120)}${C.reset}\n"
          )

  private object C:
    val reset = "\u001b[0m"
    val bold = "\u001b[1m"
    val red = "\u001b[31m"
    val green = "\u001b[32m"
    val yellow = "\u001b[33m"
    val blue = "\u001b[34m"
    val cyan = "\u001b[36m"
    val dim = "\u001b[2m"

  private def repl(ctx: RunCtx): ZIO[Any, Nothing, Int] =
    ZIO.succeed:
      val in = new BufferedReader(
        new InputStreamReader(System.in, StandardCharsets.UTF_8)
      )
      ctx.approvalInput = Some(in)
      var running = true
      var exit = 0
      while running do
        print(Prompt)
        System.out.flush()
        // readLine returns null at EOF — wrapped in Option (null-free code rule)
        Option(in.readLine()) match
          case None => running = false // EOF
          case Some(line) =>
            line.trim match
              case "" => // skip empty
              case ":q" | ":quit" | ":exit" =>
                running = false
              case ":help" =>
                printHelp()
              case cmd if cmd.startsWith(":pipe") =>
                val spec = line.trim.drop(5).trim
                handlePipeline(ctx, spec)
              case cmd if cmd.startsWith(":delegate") =>
                val spec = line.trim.drop(9).trim
                handleDelegate(ctx, spec)
              case cmd if cmd.startsWith(":task") =>
                handleTask(ctx, cmd.drop(5).trim)
              case cmd if cmd.startsWith(":export-workflow") =>
                exportWorkflow(ctx, cmd.drop(":export-workflow".length).trim)
                  .foreach(println)
              case ":clear" =>
                zio.Runtime.default.unsafe
                  .run(ctx.historyRef.set(List.empty))
                  .getOrThrow()
                zio.Runtime.default.unsafe
                  .run(ctx.traceRef.set(Vector.empty))
                  .getOrThrow()
                println("context cleared")
              case cmd if cmd.startsWith(":save") =>
                val name = cmd.drop(5).trim
                handleSave(ctx, name)
              case ":sessions" =>
                handleListSessions()
              case cmd if cmd.startsWith(":load") =>
                val name = cmd.drop(5).trim
                handleLoad(ctx, name)
              case ":compact" =>
                handleCompact(ctx.loop, ctx.limits, ctx.historyRef)
              case ":fix" =>
                handleFix(ctx.loop, ctx.limits, ctx.historyRef)
              case ":context" =>
                val h =
                  zio.Runtime.default.unsafe
                    .run(ctx.historyRef.get)
                    .getOrThrow()
                println(s"context: ${h.size} messages")
                h.zipWithIndex.foreach { case (m, i) =>
                  println(s"  [$i] ${m.role}: ${m.content.take(60)}...")
                }
              case ":tools" =>
                ctx.loop.tools.names.foreach(t => println(s"  $t"))
              case ":trace" =>
                val evts =
                  zio.Runtime.default.unsafe.run(ctx.traceRef.get).getOrThrow()
                println(s"trace: ${evts.size} events")
                evts.take(10).foreach {
                  case raider.core.trace.TraceEvent.ToolCallStarted(
                        _,
                        _,
                        tool,
                        _,
                        args
                      ) =>
                    println(s"  → $tool $args")
                  case raider.core.trace.TraceEvent.ToolCallFinished(
                        _,
                        _,
                        tool,
                        _,
                        result,
                        _
                      ) =>
                    println(s"  ← $tool: ${result.take(80)}")
                  case raider.core.trace.TraceEvent.RunStarted(_, m, _) =>
                    println(s"  RUN START model=$m")
                  case raider.core.trace.TraceEvent
                        .RunFinished(_, outcome, _) =>
                    println(s"  RUN $outcome")
                  case other =>
                    println(s"  ${other.getClass.getSimpleName.take(30)}")
                }
              case input =>
                val exit = zio.Runtime.default.unsafe
                  .run(processMessage(ctx, input))
                exit match
                  case zio.Exit.Success(_) =>
                    // the answer already streamed to stdout as deltas; just
                    // separate it from the next prompt
                    println()
                  case zio.Exit.Failure(cause) =>
                    println() // close the streamed-partial line before errors
                    cause.failures.headOption match
                      case Some(err) =>
                        println(s"ERROR [${err.code}]: ${err.detail.take(200)}")
                        println()
                      case None =>
                        println("interrupted or died")
                        println()
      exit

  /** One user message as ONE job (RAI-060): the job's body is held on a barrier
    * until AFTER the status-line renderer subscribed (no loss window), so the
    * renderer sees every envelope from the start transition onward. LoopEvents
    * (RAI-058) binds the loop emissions to the job's own id; JobManager +
    * StatusLedger (RAI-059) own the transitions and the single Terminal.
    */
  private[chat] def runJob[R](
      hub: EventHub,
      manager: JobManager,
      root: RootId,
      sink: String => zio.UIO[Unit],
      label: String,
      onStart: JobHandle[?] => zio.UIO[Unit] = _ => ZIO.unit,
      onSettled: (JobId, Vector[EventEnvelope], R) => zio.UIO[Unit] =
        (_: JobId, _: Vector[EventEnvelope], _: R) => ZIO.unit
  )(body: JobId => ZIO[Any, RaiderError, R]): ZIO[Any, RaiderError, R] =
    for
      barrier <- Promise.make[Nothing, JobId]
      handle <- manager.start(
        root,
        label,
        Task(barrier.await.flatMap(jobId => body(jobId)))
      )
      renderer <- StatusLine.start(hub, handle.id, sink)
      _ <- onStart(handle)
      _ <- barrier.succeed(handle.id).unit
      result <- manager
        .await(handle)
        .ensuring(
          EventExport
            .awaitCaughtUp(hub, handle.id, renderer.foldedSeq.get)
            .zipRight(renderer.fiber.interrupt)
        )
      exported <- renderer.exported.get
      // StatusLedger publishes the JobManager's actual Terminal before await
      // settles. Export the captured stream as-is; the lineage publisher must
      // refuse an incomplete stream rather than synthesize a closing marker.
      _ <- onSettled(handle.id, exported, result)
    yield result

  /** One user message as ONE job (RAI-060) + capture (RAI-155.b, ADR-033 §5): a
    * ledger turn begins inside the job body with status `Unknown` (the
    * pending/torn state) and receives its terminal status only when the job
    * settles — a crash before that write leaves the record typed-`Unknown`,
    * never silently lost (§5.4). Interruption/defect → `Interrupted`; typed
    * failure → `Failed`; success → `Completed`. The per-tool outcome summary
    * comes from the trace events recorded during the turn (names + outcome
    * classes only, no payloads). The failure/success of the message itself is
    * re-raised unchanged — capture is observation, never control flow.
    */
  private[chat] def processMessage(
      ctx: RunCtx,
      input: String
  ): ZIO[Any, RaiderError, String] =
    runJob[SettledChat](
      ctx.hub,
      ctx.manager,
      ctx.root,
      ctx.sink,
      "chat-message",
      onSettled =
        (jobId: JobId, events: Vector[EventEnvelope], settled: SettledChat) =>
          ZIO.succeed {
            val runsRoot = Paths.get(ctx.workspacePath, ".raider", "runs")
            ChatLineageExport.publish(
              runsRoot,
              jobId,
              events,
              ChatLineageExport.responses(settled.trace, settled.answer),
              if ctx.provider == "mock" then None else Some(ctx.loop.model)
            ) match
              case Right(_) => ()
              case Left(error) =>
                System.err.println(
                  s"chat lineage publication unavailable: ${error.take(200)}"
                )
          }
    ) { jobId =>
      val loop = ctx.loop.copy(events =
        Some(LoopEvents(ctx.hub, jobId, ctx.root, StatusLedger.sessionId))
      )
      val refs = ConfigRefs(ctx.workspacePath, ctx.provider, ctx.loop.model)
      val turn = TurnLedger.beginTurn(
        input,
        jobId.value,
        refs,
        System.currentTimeMillis()
      )
      for
        traceBefore <- ctx.traceRef.get.map(_.size)
        _ <- ctx.ledgerRef.update(_ :+ turn)
        outcome <- processMessageBody(
          loop,
          ctx.limits,
          ctx.historyRef,
          ctx.traceRef,
          input,
          ctx.approvalInput
        ).exit
        traceAfter <- ctx.traceRef.get
        tools = TurnLedger.toolOutcomes(traceAfter.drop(traceBefore))
        status = outcome match
          case Exit.Success(_) => TurnLedger.Status.Completed
          case Exit.Failure(cause) =>
            TurnLedger.terminalStatus(cause.failures.headOption)
        _ <- ctx.ledgerRef.update: ts =>
          ts.map: t =>
            if t.turnId == turn.turnId then
              TurnLedger.finishTurn(
                t,
                status,
                tools,
                System.currentTimeMillis()
              )
            else t
        answer <- outcome match
          case Exit.Success(value) => ZIO.succeed(value)
          case Exit.Failure(cause) => ZIO.failCause(cause)
      yield SettledChat(answer, traceAfter.drop(traceBefore))
    }.map(_.answer)

  private final case class SettledChat(
      answer: String,
      trace: Vector[raider.core.trace.TraceEvent]
  )

  private def processMessageBody(
      loop: AgentLoop,
      limits: BudgetLimits,
      historyRef: Ref[List[RequestMessage]],
      traceRef: Ref[Vector[raider.core.trace.TraceEvent]],
      input: String,
      approvalInput: Option[BufferedReader]
  ): ZIO[Any, RaiderError, String] =
    for
      guardedLoop = loop.copy(
        tools =
          ToolApproval.guard(loop.tools, Some(cliApprovalHook(approvalInput)))
      )
      history <- historyRef.get
      // automatic compaction BEFORE the model call when context exceeds the
      // threshold (Phase 2.3): the model sees the compacted context
      _ <- compactHistory(loop, limits, historyRef, force = false)
      compacted <- historyRef.get
      // Append the new user message
      newUser = RequestMessage("user", input)
      messages = (compacted :+ newUser).takeRight(MaxHistoryMessages)
      // Run the loop with FULL conversation
      answer <- guardedLoop.runText(
        messages,
        limits,
        math.min(limits.maxAttempts, 12)
      )
      // Record trace events
      _ <- traceRef.update(
        _ :+ raider.core.trace.TraceEvent.RunStarted(
          java.time.Instant.now().toString,
          loop.model,
          messages.size
        )
      )
      _ <- traceRef.update(
        _ :+ raider.core.trace.TraceEvent.RunFinished(
          java.time.Instant.now().toString,
          "Succeeded",
          answer.take(160)
        )
      )
      // Update history: user message + assistant answer
      _ <- historyRef.update { h =>
        (h :+ newUser :+ RequestMessage("assistant", answer))
          .takeRight(MaxHistoryMessages)
      }
    yield answer

  private def cliApprovalHook(
      approvalInput: Option[BufferedReader]
  ): ToolApprovalHook = new ToolApprovalHook:
    def decide(context: ToolApprovalContext) =
      val prompt =
        s"Approve mutating tool '${context.tool}' with arguments " +
          s"${context.argumentsJson}? Type 'yes' to approve; anything else denies: "
      ZIO.succeed(System.err.print(prompt)) *>
        ZIO
          .attemptBlocking(
            approvalInput.fold(scala.io.StdIn.readLine())(_.readLine())
          )
          .map: answer =>
            if Option(answer).exists(_.trim.equalsIgnoreCase("yes")) then
              ToolApprovalDecision.Approve
            else ToolApprovalDecision.Deny
          .orElseSucceed(ToolApprovalDecision.Deny)

  /** Summarize the oldest slice into one system message (Phase 2.3). The
    * summary itself is a side model call (1 round) and never enters the
    * history; on summary failure the history is left untouched.
    *
    * RAI-155.b (ADR-033 §5.2): this rewrite touches ONLY the model-facing
    * message list (`historyRef.set` below is the exact provenance-destroying
    * statement the contract calls out). The turn ledger lives in a separate ref
    * and is never dropped or rewritten here — the ledger, not the message list,
    * is the export-provenance source.
    */
  private def compactHistory(
      loop: AgentLoop,
      limits: BudgetLimits,
      historyRef: Ref[List[RequestMessage]],
      force: Boolean
  ): ZIO[Any, Nothing, Unit] =
    historyRef.get.flatMap: history =>
      ChatLoop.splitForCompaction(history, force) match
        case None => ZIO.unit
        case Some((old, recent)) =>
          val transcript =
            old.map(m => s"${m.role}: ${m.content.take(300)}").mkString("\n")
          val ask = RequestMessage(
            "user",
            s"Summarize in 2 sentences the following conversation excerpt " +
              s"(keep key decisions, file paths, error messages):\n$transcript"
          )
          loop
            .runText(List(ask), limits, 1)
            .foldZIO(
              _ => ZIO.unit, // summary failure keeps history untouched
              summary =>
                val sysMsg = RequestMessage(
                  "system",
                  s"Summary of earlier conversation (older messages were compacted): $summary"
                )
                historyRef.set(List(sysMsg) ++ recent)
            )

  /** :compact — force manual compaction and report the size change. */
  private def handleCompact(
      loop: AgentLoop,
      limits: BudgetLimits,
      historyRef: Ref[List[RequestMessage]]
  ): Unit =
    zio.Unsafe.unsafe { implicit u =>
      val runtime = zio.Runtime.default
      val before = runtime.unsafe.run(historyRef.get).getOrThrow()
      runtime.unsafe
        .run(compactHistory(loop, limits, historyRef, force = true))
        .getOrThrow()
      val after = runtime.unsafe.run(historyRef.get).getOrThrow()
      if after.size < before.size then
        println(s"compacted: ${before.size} → ${after.size} messages")
      else println("nothing to compact")
    }

  /** Backend wiring for chat (RAI-071): openai (SSE streaming) and anthropic
    * (native Messages wire, SSE streaming) are first-class; any other value
    * keeps the honest mock-fallback backend — a failed setup NEVER masquerades
    * as a live wire. `private[chat]` for the backend-class unit tests.
    */
  private[chat] def makeBackendSimple(
      provider: String,
      baseUrl: String,
      keyOpt: Option[String],
      writer: ChatWriter
  ): ModelBackend =
    val raw = provider match
      case "openai" =>
        // RAI-064: the key comes from the env (preflight resolved it above);
        // for a live provider keyOpt is guaranteed Some(non-empty) here.
        try
          val config = raider.provider.chat.OpenAIChatBackend.Config
            .make(baseUrl, keyOpt.getOrElse(""), callTimeoutMs = 300000L)
          config match
            case Right(cfg) =>
              zio.Unsafe.unsafe { implicit u =>
                // STREAMING wire (Phase 2.1): text deltas surface as they
                // arrive instead of after the full response; 429s back off
                // exponentially (Phase 4.2)
                zio.Runtime.default.unsafe.run(
                  raider.provider.chat.stream.OpenAIChatStreamingBackend
                    .make(Right(cfg))
                    .map(raider.runtime.display.RateLimitRetries(_))
                ) match
                  case zio.Exit.Success(b) => b
                  case _                   => MockBackendFallback.backend
              }
            case Left(_) => MockBackendFallback.backend
        catch case _: Exception => MockBackendFallback.backend
      case "anthropic" =>
        // RAI-071: native Messages wire (`/v1/messages`, x-api-key +
        // pinned anthropic-version) with SSE streaming — the SAME
        // env-resolved key, the same RateLimitRetries parity as openai.
        try
          raider.provider.anthropic.AnthropicMessagesBackend.Config
            .make(baseUrl, keyOpt.getOrElse(""), callTimeoutMs = 300000L) match
            case Right(cfg) =>
              zio.Unsafe.unsafe { implicit u =>
                zio.Runtime.default.unsafe.run(
                  raider.provider.anthropic.AnthropicMessagesBackend
                    .make(Right(cfg), streaming = true)
                    .map(raider.runtime.display.RateLimitRetries(_))
                ) match
                  case zio.Exit.Success(b) => b
                  case _                   => MockBackendFallback.backend
              }
            case Left(_) => MockBackendFallback.backend
        catch case _: Exception => MockBackendFallback.backend
      case _ => MockBackendFallback.backend
    // Real-time display: deltas → stdout via the SHARED writer (one-writer
    // discipline with the status line), ready tool calls → stderr
    raider.runtime.display.StreamingDisplay(
      raw,
      onTextDelta = t => ZIO.succeed(writer.text(t)),
      onToolCall = (name, args) =>
        ZIO.succeed:
          System.err.println(
            s"  ${C.blue}${C.reset}→ $name${C.reset} ${args.take(160)}"
          )
    )

  private def makeRegistry(wsPath: String): ZIO[Any, Nothing, ToolRegistry] =
    raider.tools.CodingToolset
      .make(wsPath)
      .flatMap(ts => ZIO.fromEither(ts.registry))
      .orElse(ZIO.succeed(ToolRegistry.empty))

  /** :save <name> — persist the current conversation (Phase 2.2) PLUS the
    * capture state (RAI-155.b: turn ledger + named checks, session schema v3).
    * The write is atomic (SessionStore), so a crash mid-save never exposes a
    * half-written document; an in-flight turn persists as typed-`Unknown`.
    */
  private def handleSave(ctx: RunCtx, name: String): Unit =
    if name.isEmpty then println("usage: :save <name>")
    else
      zio.Unsafe.unsafe { implicit u =>
        val history =
          zio.Runtime.default.unsafe.run(ctx.historyRef.get).getOrThrow()
        if history.isEmpty then println("nothing to save: history is empty")
        else
          val turns =
            zio.Runtime.default.unsafe.run(ctx.ledgerRef.get).getOrThrow()
          val checks =
            zio.Runtime.default.unsafe.run(ctx.checksRef.get).getOrThrow()
          val session = SavedSession(
            id = name,
            messages = history,
            model = ctx.loop.model,
            timestamp = java.time.Instant.now().toString,
            turns = turns.toList,
            checks = checks
          )
          zio.Runtime.default.unsafe
            .run(SessionStore.save(SessionStore.defaultBase, session)) match
            case zio.Exit.Success(path) =>
              println(
                s"saved ${history.size} messages, ${turns.size} turn(s), " +
                  s"${checks.size} check(s) → $path"
              )
            case zio.Exit.Failure(cause) =>
              cause.failures.headOption.foreach: err =>
                println(s"save failed [${err.code}]: ${err.detail.take(160)}")
      }

  /** :sessions — list saved sessions (Phase 2.2). */
  private def handleListSessions(): Unit =
    zio.Unsafe.unsafe { implicit u =>
      zio.Runtime.default.unsafe
        .run(SessionStore.list(SessionStore.defaultBase)) match
        case zio.Exit.Success(Nil) => println("no saved sessions")
        case zio.Exit.Success(sessions) =>
          println(s"${sessions.size} saved session(s):")
          sessions.foreach: s =>
            println(
              f"  ${s.id}%-24s ${s.messages.size}%4d msgs  model=${s.model}  ${s.timestamp}"
            )
        case zio.Exit.Failure(cause) =>
          cause.failures.headOption.foreach: err =>
            println(s"list failed [${err.code}]: ${err.detail.take(160)}")
    }

  /** :load <name> — replace the current conversation with a saved session.
    * RAI-155.b: the turn ledger and named checks are restored with it; a legacy
    * conversation-only session loads read-only (empty ledger) and the reopen
    * notices name that fact plus any saved-vs-active config mismatch explicitly
    * (ADR-033 §5.3 — reopening never restores authority).
    */
  private def handleLoad(ctx: RunCtx, name: String): Unit =
    if name.isEmpty then println("usage: :load <name>")
    else
      zio.Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe
          .run(SessionStore.load(SessionStore.defaultBase, name)) match
          case zio.Exit.Success(session) =>
            zio.Runtime.default.unsafe
              .run(ctx.historyRef.set(session.messages))
              .getOrThrow()
            zio.Runtime.default.unsafe
              .run(ctx.ledgerRef.set(session.turns.toVector))
              .getOrThrow()
            zio.Runtime.default.unsafe
              .run(ctx.checksRef.set(session.checks))
              .getOrThrow()
            zio.Runtime.default.unsafe
              .run(ctx.selectionRef.set(None))
              .getOrThrow()
            println(
              s"loaded '$name': ${session.messages.size} messages, " +
                s"${session.turns.size} turn(s) " +
                s"(saved ${session.timestamp}, model ${session.model})"
            )
            TaskCommands
              .restoreNotices(
                session.turns,
                ConfigRefs(ctx.workspacePath, ctx.provider, ctx.loop.model)
              )
              .foreach(n => println(s"  $n"))
          case zio.Exit.Failure(cause) =>
            cause.failures.headOption.foreach: err =>
              println(s"load failed [${err.code}]: ${err.detail.take(160)}")
      }

  /** :task — explicit task marking and selection for workflow export
    * (RAI-155.b, ADR-033 §4/§5/§11.1). Typeds refusals print with their
    * structured detail; a successful select stores the immutable snapshot in
    * `ctx.selectionRef` — the typed structure the renderer card consumes.
    */
  private def handleTask(ctx: RunCtx, rest: String): Unit =
    zio.Unsafe.unsafe { implicit u =>
      val runtime = zio.Runtime.default
      def turns: Vector[TurnRecord] =
        runtime.unsafe.run(ctx.ledgerRef.get).getOrThrow()
      def checks: List[NamedCheck] =
        runtime.unsafe.run(ctx.checksRef.get).getOrThrow()
      // same bound formula as processMessageBody's model rounds
      def maxRoundsBound: Int = math.min(ctx.limits.maxAttempts, 12)
      TaskCommands.parse(rest) match
        case TaskCommands.Usage(text) => println(text)
        case TaskCommands.ListCmd     => println(TaskCommands.renderList(turns))
        case TaskCommands.StatusCmd =>
          val sel = runtime.unsafe.run(ctx.selectionRef.get).getOrThrow()
          println(TaskCommands.renderStatus(turns, checks, sel))
        case TaskCommands.Mark(n, kind) =>
          runtime.unsafe
            .run(TaskCommands.mark(ctx.ledgerRef, n, kind)) match
            case zio.Exit.Success(()) =>
              // selection (if any) predates this mark: it must be re-selected
              // explicitly — never silently mutated
              runtime.unsafe.run(ctx.selectionRef.set(None)).getOrThrow()
              println(
                s"marked turn #$n as ${kind.toLowerCase} (re-select the task to include it)"
              )
            case zio.Exit.Failure(cause) =>
              cause.failures.headOption.foreach: err =>
                println(s"task failed [${err.code}]: ${err.detail.take(160)}")
        case TaskCommands.Check(name, argv) =>
          runtime.unsafe
            .run(TaskCommands.registerCheck(ctx.checksRef, name, argv)) match
            case zio.Exit.Success(()) =>
              println(s"check registered: $name = ${argv.mkString(" ")}")
            case zio.Exit.Failure(cause) =>
              cause.failures.headOption.foreach: err =>
                println(s"task failed [${err.code}]: ${err.detail.take(160)}")
        case TaskCommands.Select(n) =>
          TaskCommands.select(turns.toList, checks, n, maxRoundsBound) match
            case Left(err) =>
              println(s"task refused [${err.code}]: ${err.detail.take(400)}")
            case Right(sel) =>
              runtime.unsafe
                .run(ctx.selectionRef.set(Some(sel)))
                .getOrThrow()
              println(TaskCommands.renderSelection(sel))
    }

  /** :export-workflow — wire the stored selection snapshot through the RAI-160
    * adapter into `ChatWorkflowExport.exportTo` (RAI-155.c; ADR-033 §6 export
    * semantics, §8 typed refusals). Console-adapter discipline: the handler is
    * a thin blocking printer over `ExportWorkflowCommand.execute`, which
    * returns the exact lines (the same path the fixtures drive). Export
    * dispatches nothing agent-shaped — it renders text and writes files
    * (staging + rename, R5 discipline inherited).
    */
  private[chat] def exportWorkflow(ctx: RunCtx, rest: String): Vector[String] =
    zio.Unsafe.unsafe { implicit u =>
      val runtime = zio.Runtime.default
      val selection = runtime.unsafe.run(ctx.selectionRef.get).getOrThrow()
      val ledgerSize =
        runtime.unsafe.run(ctx.ledgerRef.get.map(_.size)).getOrThrow()
      ExportWorkflowCommand.execute(
        selection = selection,
        ledgerSize = ledgerSize,
        sessionId = ctx.root.value,
        chatProvider = ctx.provider,
        model = ctx.loop.model,
        baseUrl = ctx.baseUrl,
        argLine = rest
      )
    }

  /** :fix — run the compile→errors→model-fix loop (Phase 4.1). */
  private def handleFix(
      loop: AgentLoop,
      limits: BudgetLimits,
      historyRef: Ref[List[RequestMessage]]
  ): Unit =
    zio.Unsafe.unsafe { implicit u =>
      println("fix loop: compiling…")
      val report = zio.Runtime.default.unsafe
        .run(
          CompileFixLoop
            .run(loop, loop.tools, limits, historyRef)
            .fold(
              err => s"fix loop failed [${err.code}]: ${err.detail.take(160)}",
              o =>
                if o.success then
                  s"fix loop: compile PASSES after ${o.attempts} attempt(s)"
                else
                  s"fix loop: still FAILING after ${o.attempts} attempt(s); " +
                    "remaining errors:\n" + o.lastErrors
                      .take(10)
                      .map(e => s"  $e")
                      .mkString("\n")
            )
        )
        .getOrThrow()
      println(report)
    }

  /** :pipe — sequential pipeline: :pipe scout("find") then worker("fix") then
    * reviewer("check") Each step gets the PREVIOUS step's output as context.
    */
  private def handlePipeline(ctx: RunCtx, spec: String): Unit =
    val steps = spec.split(" then ").map(_.trim).filter(_.nonEmpty).toList
    if steps.isEmpty then
      println("usage: :pipe agent(\"prompt\") then agent(\"prompt\") ...")
    else
      println(s"pipeline: ${steps.size} steps")
      var context = ""
      steps.zipWithIndex.foreach { case (step, i) =>
        val prompt = extractPrompt(step).getOrElse(step)
        println(s"  [${i + 1}/${steps.size}] $prompt")
        val result = zio.Unsafe.unsafe { implicit u =>
          zio.Runtime.default.unsafe.run(
            processMessage(
              ctx,
              if context.isEmpty then prompt
              else s"Context from previous step:\n$context\n\nTask: $prompt"
            )
          )
        }
        result match
          case zio.Exit.Success(answer) =>
            println(s"  → ${answer.take(200)}")
            context = answer
          case zio.Exit.Failure(cause) =>
            cause.failures.headOption match
              case Some(err) =>
                println(s"  ✗ ERROR [${err.code}]: ${err.detail.take(120)}")
              case None => println(s"  ✗ step failed")
      }
      println()

  /** :delegate — fire-and-forget subagent task: :delegate worker("fix the bug")
    */
  private def handleDelegate(ctx: RunCtx, spec: String): Unit =
    val prompt = extractPrompt(spec).getOrElse(spec)
    println(s"delegated: $prompt")
    val result = zio.Unsafe.unsafe { implicit u =>
      zio.Runtime.default.unsafe
        .run(processMessage(ctx, prompt))
    }
    result match
      case zio.Exit.Success(answer) => println(s"done: ${answer.take(300)}")
      case zio.Exit.Failure(cause) =>
        cause.failures.headOption match
          case Some(err) =>
            println(s"ERROR [${err.code}]: ${err.detail.take(120)}")
          case None => println("delegation failed")
    println()

  /** Extract "prompt" from agent("prompt") pattern */
  private def extractPrompt(step: String): Option[String] =
    val start = step.indexOf("(\"")
    val end = step.lastIndexOf("\")")
    if start >= 0 && end > start then Some(step.substring(start + 2, end))
    else None

  private def banner(
      registry: ToolRegistry,
      model: String,
      workspace: Option[String]
  ): ZIO[Any, Nothing, Unit] =
    ZIO.succeed:
      println("Raider chat — interactive coding session")
      println(s"  model: $model")
      println(s"  tools: ${registry.names.mkString(", ")}")
      println(s"  workspace: ${workspace.getOrElse("CWD")}")
      println(
        "  commands: :help :quit :clear :context :tools :trace :save :sessions :load :task :export-workflow"
      )
      println()

  private def printHelp(): Unit =
    println("""Commands:
      |  :help     this text
      |  :quit     exit (also :q, :exit, Ctrl+D)
      |  :clear    clear conversation context (model forgets everything)
      |  :context  show conversation history summary
      |  :tools    list available tools
      |  :trace    show recent trace events (tool calls, model calls)
      |  :save <name>    save the current conversation to ~/.raider/sessions/
      |  :sessions       list saved sessions
      |  :load <name>    load a saved session into the current context
      |  :compact        summarize the oldest messages into a system note
      |  :fix            run sbt compile; send errors to the model to fix (≤3 rounds)
      |  :pipe     sequential pipeline: :pipe scout("find") then worker("fix") then reviewer("check")
      |  :delegate fire-and-forget task: :delegate worker("fix the bug")
      |  :task     explicit task marking/selection for workflow export:
      |              :task list                          show the captured turn ledger
      |              :task mark <n> <intent|refinement|ordinary>
      |              :task select <n>                    select the last n turns (validated; typed refusals)
      |              :task check <name> <argv tokens...> register a named trusted check (e.g. compile sbt --batch compile)
      |              :task status                        export-readiness summary
      |            (capture survives compaction and save/load; legacy
      |            conversation-only sessions cannot be exported from)
      |  :export-workflow [--out <dir>] [--name <name>] [--target-path <path>] [--summary <text>]
      |            export the selected task snapshot as an editable workflow
      |            (staging+rename, never overwrites; typed refusals; the
      |            artifact compiles with 'raider compile' and runs with
      |            'raider workflow run')
      |Anything else is sent to the model with full conversation context.
      |The model can use tools (fs_read, fs_search, fs_edit, proc_run)
      |to work on files in your workspace.""".stripMargin)

  /** Fallback mock backend for chat mode (only when provider setup fails). */
  object MockBackendFallback:

    val backend: ModelBackend = new ModelBackend:
      def capabilities: ModelCapabilities =
        ModelCapabilities(
          streaming = CapabilityStatus.Supported,
          tools = CapabilityStatus.Unsupported,
          cancellationAck = CapabilityStatus.Unknown
        )
      def stream(
          input: ModelRequest
      ): zio.stream.ZStream[Scope, RaiderError, ModelEvent] =
        zio.stream.ZStream(
          ModelEvent.Started(AttemptId("chat-mock")),
          ModelEvent.TextDelta(
            AttemptId("chat-mock"),
            "[fallback-mock] backend setup failed; check --provider " +
              "(mock|openai|anthropic), --base-url/--model and RAIDER_API_KEY"
          ),
          ModelEvent.Finished(AttemptId("chat-mock"), "stop")
        )

end ChatLoop
