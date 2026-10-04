package raider.cli.chat

import raider.core.*
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
  * Usage: raider chat [--provider openai] [--base-url URL] [--model NAME]
  * [--workspace PATH]
  *
  * Commands: :help :quit :clear :context :tools :trace
  */
object ChatLoop:

  private val Prompt = "raider> "
  private val MaxHistoryMessages = 40 // bounded context

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
      workspace: Option[String]
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
      historyRef <- Ref.make(List.empty[RequestMessage])
      sysPrompt = Some(
        """You are a coding agent working inside a Scala 3 + ZIO project.
You have tools: fs_read, fs_search, fs_edit, proc_run.
All paths are relative to the workspace root.

Use tools to understand code before making changes.
Use fs_edit with sha256 verification for edits.
Use proc_run to verify compilation after changes.
Give clear, concise answers.""".stripMargin
      )
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
          stderr.println(f"\n${C}bold${C.reset}── run ── model=$m msgs=$msgs")
        case raider.core.trace.TraceEvent.RoundStarted(_, r) =>
          stderr.println(f"${C}dim${C.reset}[round $r]${C.reset}")
        case raider.core.trace.TraceEvent.ModelCall(_, _, msgs) =>
          stderr.println(s"  ${C}cyan${C.reset}→ model${C.reset} ($msgs msgs)")
        case raider.core.trace.TraceEvent.TextReceived(_, _, text) =>
          stderr.println(
            s"  ${C}green${C.reset}← text${C.reset}: ${text.take(120)}"
          )
        case raider.core.trace.TraceEvent.ToolCallStarted(
              _,
              _,
              tool,
              _,
              args
            ) =>
          stderr.println(
            s"  ${C}blue${C.reset}→ $tool${C.reset} ${args.take(120)}"
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
            if ok then s"${C}green${C.reset}✓" else s"${C}red${C.reset}✗"
          stderr.println(s"  $marker ← $tool: ${result.take(150)}")
        case raider.core.trace.TraceEvent.ToolCallRefused(
              _,
              _,
              tool,
              _,
              reason
            ) =>
          stderr.println(
            s"  ${C}yellow${C.reset}⊘ $tool${C.reset} refused: $reason"
          )
        case raider.core.trace.TraceEvent.ChildDelegated(_, agent, childId) =>
          stderr.println(
            s"  ${C}cyan${C.reset}→ delegate: $agent → $childId${C.reset}"
          )
        case raider.core.trace.TraceEvent.ChildAwaited(_, childId, status) =>
          stderr.println(
            s"  ${C}cyan${C.reset}← child $childId: $status${C.reset}"
          )
        case raider.core.trace.TraceEvent.RunFinished(_, outcome, detail) =>
          stderr.println(
            s"${C}bold${C.reset}── done: ${detail.take(120)}${C.reset}\n"
          )
        case raider.core.trace.TraceEvent.RunFailed(_, code, detail) =>
          stderr.println(
            s"${C}red${C.reset}── FAILED [$code]: ${detail.take(120)}${C.reset}\n"
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
                  case zio.Exit.Success(answer) =>
                    println(answer)
                    println()
                  case zio.Exit.Failure(cause) =>
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
      // Append the new user message
      newUser = RequestMessage("user", input)
      messages = (history :+ newUser).takeRight(MaxHistoryMessages)
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

  private def makeBackendSimple(
      provider: String,
      baseUrl: String,
      apiKey: String
  ): ModelBackend =
    provider match
      case "openai" =>
        try
          val config = raider.provider.chat.OpenAIChatBackend.Config
            .make(baseUrl, apiKey, callTimeoutMs = 300000L)
          config match
            case Right(cfg) =>
              zio.Unsafe.unsafe { implicit u =>
                zio.Runtime.default.unsafe.run(
                  raider.provider.chat.OpenAIChatBackend.make(Right(cfg))
                ) match
                  case zio.Exit.Success(b) => b
                  case _                   => MockBackendFallback.backend
              }
            case Left(_) => MockBackendFallback.backend
        catch case _: Exception => MockBackendFallback.backend
      case _ => MockBackendFallback.backend

  private def makeRegistry(
      workspace: Option[String]
  ): ZIO[Any, Nothing, ToolRegistry] =
    val wsPath = workspace.getOrElse(Paths.get("").toAbsolutePath.toString)
    raider.tools.CodingToolset
      .make(wsPath)
      .flatMap(ts => ZIO.fromEither(ts.registry))
      .orElse(ZIO.succeed(ToolRegistry.empty))

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
      println("  commands: :help :quit :clear :context :tools :trace")
      println()

  private def printHelp(): Unit =
    println("""Commands:
      |  :help     this text
      |  :quit     exit (also :q, :exit, Ctrl+D)
      |  :clear    clear conversation context (model forgets everything)
      |  :context  show conversation history summary
      |  :tools    list available tools
      |  :trace    show recent trace events (tool calls, model calls)
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
