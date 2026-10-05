package raider.cli.chat

import raider.core.*
import raider.cli.CoderPrompts
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.loop.AgentLoop
import zio.{Ref, Scope, ZIO}
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
  * Usage: raider chat [--provider openai] [--base-url URL] [--model NAME]
  * [--workspace PATH]
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

  def run(
      provider: String,
      baseUrl: String,
      model: String,
      apiKey: String,
      workspace: Option[String],
      resume: Option[String] = None
  ): ZIO[Any, Nothing, Int] =
    for
      registry <- makeRegistry(workspace)
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
      backend = makeBackendSimple(provider, baseUrl, apiKey)
      traceRef <- Ref.make(Vector.empty[raider.core.trace.TraceEvent])
      // VERBOSE trace: prints to stderr in real-time + records to Ref
      tracer <- ZIO.succeed(makeTracer(traceRef))
      // --resume seeds the conversation from a saved session (Phase 2.2)
      historyRef <- Ref.make(List.empty[RequestMessage])
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
                  ZIO.succeed(
                    println(
                      s"resumed '$name': ${session.messages.size} messages " +
                        s"(saved ${session.timestamp}, model ${session.model})"
                    )
                  )
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
      exitCode <- repl(loop, limits, historyRef, traceRef)
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

  private def repl(
      loop: AgentLoop,
      limits: BudgetLimits,
      historyRef: Ref[List[RequestMessage]],
      traceRef: Ref[Vector[raider.core.trace.TraceEvent]]
  ): ZIO[Any, Nothing, Int] =
    ZIO.succeed:
      val in = new BufferedReader(
        new InputStreamReader(System.in, StandardCharsets.UTF_8)
      )
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
                handlePipeline(loop, limits, historyRef, traceRef, spec)
              case cmd if cmd.startsWith(":delegate") =>
                val spec = line.trim.drop(9).trim
                handleDelegate(loop, limits, historyRef, traceRef, spec)
              case ":clear" =>
                zio.Runtime.default.unsafe
                  .run(historyRef.set(List.empty))
                  .getOrThrow()
                zio.Runtime.default.unsafe
                  .run(traceRef.set(Vector.empty))
                  .getOrThrow()
                println("context cleared")
              case cmd if cmd.startsWith(":save") =>
                val name = cmd.drop(5).trim
                handleSave(historyRef, loop.model, name)
              case ":sessions" =>
                handleListSessions()
              case cmd if cmd.startsWith(":load") =>
                val name = cmd.drop(5).trim
                handleLoad(historyRef, name)
              case ":compact" =>
                handleCompact(loop, limits, historyRef)
              case ":fix" =>
                handleFix(loop, limits, historyRef)
              case ":context" =>
                val h =
                  zio.Runtime.default.unsafe.run(historyRef.get).getOrThrow()
                println(s"context: ${h.size} messages")
                h.zipWithIndex.foreach { case (m, i) =>
                  println(s"  [$i] ${m.role}: ${m.content.take(60)}...")
                }
              case ":tools" =>
                loop.tools.names.foreach(t => println(s"  $t"))
              case ":trace" =>
                val evts =
                  zio.Runtime.default.unsafe.run(traceRef.get).getOrThrow()
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
                  .run(
                    processMessage(loop, limits, historyRef, traceRef, input)
                  )
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

  private def processMessage(
      loop: AgentLoop,
      limits: BudgetLimits,
      historyRef: Ref[List[RequestMessage]],
      traceRef: Ref[Vector[raider.core.trace.TraceEvent]],
      input: String
  ): ZIO[Any, RaiderError, String] =
    for
      history <- historyRef.get
      // automatic compaction BEFORE the model call when context exceeds the
      // threshold (Phase 2.3): the model sees the compacted context
      _ <- compactHistory(loop, limits, historyRef, force = false)
      compacted <- historyRef.get
      // Append the new user message
      newUser = RequestMessage("user", input)
      messages = (compacted :+ newUser).takeRight(MaxHistoryMessages)
      // Run the loop with FULL conversation
      answer <- loop.runText(messages, limits, math.min(limits.maxAttempts, 12))
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

  /** Summarize the oldest slice into one system message (Phase 2.3). The
    * summary itself is a side model call (1 round) and never enters the
    * history; on summary failure the history is left untouched.
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

  private def makeBackendSimple(
      provider: String,
      baseUrl: String,
      apiKey: String
  ): ModelBackend =
    val raw = provider match
      case "openai" =>
        try
          val config = raider.provider.chat.OpenAIChatBackend.Config
            .make(baseUrl, apiKey, callTimeoutMs = 300000L)
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
      case _ => MockBackendFallback.backend
    // Real-time display: deltas → stdout, ready tool calls → stderr
    raider.runtime.display.StreamingDisplay(
      raw,
      onTextDelta = t => ZIO.succeed { print(t); System.out.flush() },
      onToolCall = (name, args) =>
        ZIO.succeed:
          System.err.println(
            s"  ${C.blue}${C.reset}→ $name${C.reset} ${args.take(160)}"
          )
    )

  private def makeRegistry(
      workspace: Option[String]
  ): ZIO[Any, Nothing, ToolRegistry] =
    val wsPath = workspace.getOrElse(Paths.get("").toAbsolutePath.toString)
    raider.tools.CodingToolset
      .make(wsPath)
      .flatMap(ts => ZIO.fromEither(ts.registry))
      .orElse(ZIO.succeed(ToolRegistry.empty))

  /** :save <name> — persist the current conversation history (Phase 2.2). */
  private def handleSave(
      historyRef: Ref[List[RequestMessage]],
      model: String,
      name: String
  ): Unit =
    if name.isEmpty then println("usage: :save <name>")
    else
      zio.Unsafe.unsafe { implicit u =>
        val history =
          zio.Runtime.default.unsafe.run(historyRef.get).getOrThrow()
        if history.isEmpty then println("nothing to save: history is empty")
        else
          val session = SavedSession(
            id = name,
            messages = history,
            model = model,
            timestamp = java.time.Instant.now().toString
          )
          zio.Runtime.default.unsafe
            .run(SessionStore.save(SessionStore.defaultBase, session)) match
            case zio.Exit.Success(path) =>
              println(s"saved ${history.size} messages → $path")
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

  /** :load <name> — replace the current conversation with a saved session. */
  private def handleLoad(
      historyRef: Ref[List[RequestMessage]],
      name: String
  ): Unit =
    if name.isEmpty then println("usage: :load <name>")
    else
      zio.Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe
          .run(SessionStore.load(SessionStore.defaultBase, name)) match
          case zio.Exit.Success(session) =>
            zio.Runtime.default.unsafe
              .run(historyRef.set(session.messages))
              .getOrThrow()
            println(
              s"loaded '$name': ${session.messages.size} messages " +
                s"(saved ${session.timestamp}, model ${session.model})"
            )
          case zio.Exit.Failure(cause) =>
            cause.failures.headOption.foreach: err =>
              println(s"load failed [${err.code}]: ${err.detail.take(160)}")
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
  private def handlePipeline(
      loop: AgentLoop,
      limits: BudgetLimits,
      historyRef: Ref[List[RequestMessage]],
      traceRef: Ref[Vector[raider.core.trace.TraceEvent]],
      spec: String
  ): Unit =
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
              loop,
              limits,
              historyRef,
              traceRef,
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
  private def handleDelegate(
      loop: AgentLoop,
      limits: BudgetLimits,
      historyRef: Ref[List[RequestMessage]],
      traceRef: Ref[Vector[raider.core.trace.TraceEvent]],
      spec: String
  ): Unit =
    val prompt = extractPrompt(spec).getOrElse(spec)
    println(s"delegated: $prompt")
    val result = zio.Unsafe.unsafe { implicit u =>
      zio.Runtime.default.unsafe
        .run(processMessage(loop, limits, historyRef, traceRef, prompt))
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
        "  commands: :help :quit :clear :context :tools :trace :save :sessions :load"
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
            "[fallback-mock] backend not available. Use --provider openai --base-url http://127.0.0.1:8081/v1"
          ),
          ModelEvent.Finished(AttemptId("chat-mock"), "stop")
        )

end ChatLoop
