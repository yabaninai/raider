package raider.repl

import raider.repl.engine.{EvalResult, ReplEngine}
import org.jline.reader.{
  EndOfFileException,
  LineReader,
  LineReaderBuilder,
  UserInterruptException
}
import org.jline.terminal.{Terminal, TerminalBuilder}
import zio.ZIO
import zio.Unsafe
import raider.runtime.events.{EventCodecs, EventEnvelope, EventPayload}
import zio.json.{DecoderOps, DeriveJsonDecoder, JsonDecoder}

/** W10 REPL entrypoint (fast-path slice).
  *
  * One process, one ZIO runtime (zio.Runtime.default — the spike-proven single
  * interpreter), one ReplEngine over the real Scala 3.9.0 compiler.
  *
  * ARCHITECTURE (integration finding 2026-10-03): the in-process scala3-repl
  * classloader loads classpath classes SELF-FIRST, so facade statics exist
  * twice and must never be shared between this console world and the
  * interpreter world. The session is therefore CONSTRUCTED INSIDE the
  * interpreter by the prelude below; the console only feeds lines and prints
  * engine output. This applies to the LIVE branch too (regression finding
  * 2026-10-08: the Phase-3 prelude read console-world MainBridge statics and
  * the interpreter world saw null — backend construction now happens INSIDE the
  * prelude, exactly like the scripted branch).
  *
  * Multiline input accumulates until braces/parens balance and the line does
  * not end in a continuation token; a blank line always submits the pending
  * buffer. Non-TTY stdin runs through a plain pipe reader (the Homebrew sbt
  * launcher redirects stdin from /dev/null on --batch, so piped smoke uses the
  * direct java classpath path — scripts/quality/repl_smoke.sh).
  */
object Main:

  private val chatFrames =
    new java.util.concurrent.ArrayBlockingQueue[String](256)

  private val chatPlanSnapshot =
    new java.util.concurrent.atomic.AtomicReference[Option[
      PlanSnapshotPayload
    ]](
      None
    )

  private val chatRenderBridge = new java.util.function.Consumer[String]:
    def accept(frame: String): Unit =
      try chatFrames.put(frame)
      catch
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          throw new IllegalStateException("chat render bridge interrupted")

  private[repl] def installChatRenderBridge(): Unit =
    System.getProperties.put("raider.chat.renderBridge", chatRenderBridge)

  private[repl] def renderChatFrames(
      terminal: raider.ui.ShellTerminal
  ): zio.ZIO[zio.Scope, Nothing, Unit] =
    zio.ZIO
      .attemptBlockingInterrupt(chatFrames.take())
      .orDie
      .flatMap { frame =>
        if frame.startsWith("\u0000PLAN:") then
          renderPlanSnapshot(terminal, frame.substring("\u0000PLAN:".length))
        else if frame.startsWith("\u0000ADR024:") then
          renderSessionEvent(terminal, frame.substring("\u0000ADR024:".length))
        else
          val (columns, rows) = terminal.size
          System.setProperty("raider.chat.columns", columns.toString)
          System.setProperty("raider.chat.rows", rows.toString)
          terminal.printAbove(Vector(frame))
      }
      .forever
      .forkScoped
      .unit

  private def renderSessionEvent(
      terminal: raider.ui.ShellTerminal,
      json: String
  ): zio.UIO[Unit] =
    import EventCodecs.given
    json.fromJson[EventEnvelope] match
      case Right(envelope) =>
        envelope.payload match
          case EventPayload.BudgetChanged(reserved, observed, unknown) =>
            terminal.printAbove(
              Vector(
                s"budget | reserved=${reserved.value} micro-USD | " +
                  s"observed=${observed.value} micro-USD | " +
                  s"unpriced attempts=$unknown"
              )
            )
          case _ => zio.ZIO.unit
      case Left(_) => zio.ZIO.unit

  private final case class PlanSnapshotPayload(
      phase: String,
      title: Option[String],
      tasks: Vector[String],
      rejection: Option[String],
      execution: Option[String]
  )

  private object PlanSnapshotPayload:

    given JsonDecoder[PlanSnapshotPayload] =
      DeriveJsonDecoder.gen[PlanSnapshotPayload]

  private def renderPlanSnapshot(
      terminal: raider.ui.ShellTerminal,
      json: String
  ): zio.UIO[Unit] =
    import PlanSnapshotPayload.given
    json.fromJson[PlanSnapshotPayload] match
      case Right(snapshot) =>
        chatPlanSnapshot.set(Some(snapshot))
        val heading = snapshot.title.fold(
          s"tasks | plan ${snapshot.phase}"
        )(title => s"tasks | plan ${snapshot.phase}: $title")
        val tasks =
          if snapshot.tasks.isEmpty then
            Vector(
              if snapshot.phase == "idle" then "tasks | no plan started"
              else "tasks | no populated tasks in this snapshot"
            )
          else
            snapshot.tasks.zipWithIndex.map { case (task, index) =>
              s"tasks | ${index + 1}. $task"
            }
        terminal.printAbove(
          Vector(heading) ++ tasks ++
            snapshot.rejection.map(reason => s"tasks | unavailable: $reason") ++
            snapshot.execution.map(id => s"tasks | execution job $id")
        )
      case Left(_) =>
        chatPlanSnapshot.set(None)
        terminal.printAbove(Vector("tasks | snapshot unavailable"))

  private[repl] def chatAuxiliary(
      lineage: raider.ui.AuxTabProvider
  ): raider.ui.AuxTabProvider = new raider.ui.AuxTabProvider:
    def title: String = "Tasks"

    def available: Boolean = true

    def render: String =
      chatPlanSnapshot.get() match
        case Some(snapshot) if snapshot.phase == "idle" =>
          Vector(
            "no plan started",
            if lineage.available then lineage.render
            else "lineage unavailable"
          ).mkString("\n")
        case Some(snapshot) =>
          val heading = snapshot.title.fold(s"plan ${snapshot.phase}")(title =>
            s"plan ${snapshot.phase}: $title"
          )
          val tasks =
            if snapshot.tasks.isEmpty then
              Vector(
                if snapshot.phase == "idle" then "no plan started"
                else "no populated tasks in this snapshot"
              )
            else
              snapshot.tasks.zipWithIndex.map { case (task, index) =>
                s"${index + 1}. $task"
              }
          (Vector(heading) ++ tasks ++ snapshot.rejection.map(reason =>
            s"unavailable: $reason"
          ) ++ snapshot.execution.map(id => s"execution job $id"))
            .mkString("\n")
        case None if lineage.available => lineage.render
        case None                      => "no plan started; lineage unavailable"

  private[repl] def updateChatSize(terminal: raider.ui.ShellTerminal): Unit =
    val (columns, rows) = terminal.size
    System.setProperty("raider.chat.columns", columns.toString)
    System.setProperty("raider.chat.rows", rows.toString)

  private val Prompt = "raider> "
  private val Continuation = "     | "

  private val attemptId = "fixture-attempt-1"

  /** Prelude replayed through the engine itself (spike pattern): the scripted
    * backend, the session (installed into ReplCommands for the console
    * commands) and the facade `given` + ready agent handles all become ordinary
    * interpreter-world bindings.
    */
  /** RAI-090 wiring plan: the prelude sources plus console-world notices to
    * print at live-mode start. If RAIDER_PROVIDER is a live provider (openai |
    * anthropic), the API key is resolved from the environment (RAIDER_API_KEY
    * or RAIDER_API_KEY_CMD, via LiveCredentials); a resolvable key under
    * `openai` wires the LIVE streaming backend + coding tools (nightly Phase
    * 3.1: RAIDER_MODEL, live-echo session), and under `anthropic` the LIVE
    * Anthropic Messages backend (RAI-098, SSE streaming — the build edge to
    * raider-provider-anthropic was added by the coordinator). A MISSING key
    * never crashes the REPL: it prints a clear how-to-enable notice and the
    * scripted/mock path runs unchanged. `env` is injectable for tests.
    */
  private[repl] final case class PreludePlan(
      sources: List[String],
      notices: List[String]
  ):

    /** RAI-108: true when this plan wires the scripted/mock backend (mock
      * provider, an unresolvable live key, or a live provider whose transport
      * this slice does not wire) — the ONLY mode where the fixture tab-stream
      * provider ([[ScriptedTabStream]]) may run; a live backend never gets
      * synthetic events.
      */
    private[repl] def isScripted: Boolean =
      sources.exists(_.contains("ScriptedModelBackend"))

  private[repl] def preludePlan(
      env: Map[String, String] = sys.env
  ): PreludePlan =
    val provider = env.getOrElse("RAIDER_PROVIDER", "mock")
    val baseUrl = env.getOrElse("RAIDER_BASE_URL", "http://127.0.0.1:8081/v1")
    val model = env.getOrElse("RAIDER_MODEL", "local")

    val keyOutcome =
      if LiveCredentials.isLive(provider) then
        LiveCredentials.resolve(provider, env)
      else Right(None)

    keyOutcome match
      case Right(Some(key)) if provider == "openai" =>
        PreludePlan(liveOpenaiSources(baseUrl, model, key), Nil)
      case Right(Some(key)) if provider == "anthropic" =>
        // RAI-098: raider-provider-anthropic IS a repl dependency now (the
        // build.sbt edge was added by the coordinator), so a resolvable key
        // wires the real AnthropicMessagesBackend — same honest live rules
        // as the openai branch, no fake fallback, notice-free start.
        PreludePlan(liveAnthropicSources(baseUrl, model, key), Nil)
      case Right(Some(key)) =>
        // live provider whose transport this REPL slice does not wire (all
        // CURRENT LiveCredentials.LiveProviders — openai, anthropic — are
        // wired above; this is the defensive branch for a future provider
        // added to LiveCredentials before its transport lands here). Honest
        // mock fallback — never a fake live wire. Only the masked key form
        // may reach the notice.
        PreludePlan(
          scriptedSources(env.get("RAI208_PLAN_FIXTURE").contains("1")),
          List(
            s"RAIDER_PROVIDER=$provider: API key resolved (masked: " +
              s"${LiveCredentials.mask(key)}), but this REPL slice wires " +
              "only the OpenAI-compatible and Anthropic live transports; " +
              "continuing in mock."
          )
        )
      case Right(None) =>
        PreludePlan(
          scriptedSources(env.get("RAI208_PLAN_FIXTURE").contains("1")),
          Nil
        ) // provider=mock — unchanged path
      case Left(message) =>
        PreludePlan(
          scriptedSources(env.get("RAI208_PLAN_FIXTURE").contains("1")),
          List(message)
        )

  /** Live OpenAI-compatible wiring: the env-resolved key (RAI-090) replaces the
    * old hardcoded "no-key" placeholder. The key exists only as an
    * interpreter-world binding — the prelude is evaluated silently, so the
    * value is never echoed; live wiring stays INSIDE the interpreter world (the
    * REPL classloader loads raider classes self-first, so console-world statics
    * (MainBridge) are a DIFFERENT class from the one seen here and a
    * ThreadLocal written there reads as null here (2026-10-08)).
    *
    * RAI-098: the session/tools/facade tail is shared verbatim with the
    * anthropic branch ([[liveTail]]) — only the backend construction differs.
    */
  private def liveOpenaiSources(
      baseUrl: String,
      model: String,
      apiKey: String
  ): List[String] =
    List(
      "import raider.repl.facade.*",
      "import raider.runtime.budget.BudgetLimits",
      s"val __baseUrl = \"${scalaStr(baseUrl)}\"",
      s"val __apiKey = \"${scalaStr(apiKey)}\"",
      """val __cfg = raider.provider.chat.OpenAIChatBackend.Config
        |  .make(__baseUrl, __apiKey, 300000L) match { case Right(c) => c }""".stripMargin,
      """val __backend = zio.Unsafe.unsafe { implicit u =>
         zio.Runtime.default.unsafe.run(
           raider.provider.chat.stream.OpenAIChatStreamingBackend
             .make(Right(__cfg))
             .map(raider.runtime.display.RateLimitRetries(_)))
           .getOrThrow() }"""
    ) ++ liveTail(model)

  /** RAI-098: live Anthropic-compatible wiring — the real
    * `raider.provider.anthropic.AnthropicMessagesBackend` via its factory (SSE
    * streaming, pinned anthropic-version, x-api-key), constructed INSIDE the
    * interpreter world exactly like the openai branch. Construction only builds
    * the JDK HttpClient — nothing is dialed until an ask runs. The masked key
    * never appears here: like openai, the raw key exists only as the
    * silently-evaluated escaped binding; notices (mask only) belong to the
    * no-key/unwired fallbacks above.
    */
  private def liveAnthropicSources(
      baseUrl: String,
      model: String,
      apiKey: String
  ): List[String] =
    List(
      "import raider.repl.facade.*",
      "import raider.runtime.budget.BudgetLimits",
      s"val __baseUrl = \"${scalaStr(baseUrl)}\"",
      s"val __apiKey = \"${scalaStr(apiKey)}\"",
      """val __cfg = raider.provider.anthropic.AnthropicMessagesBackend.Config
        |  .make(__baseUrl, __apiKey, callTimeoutMs = 300000L) match { case Right(c) => c }""".stripMargin,
      """val __backend = zio.Unsafe.unsafe { implicit u =>
         zio.Runtime.default.unsafe.run(
           raider.provider.anthropic.AnthropicMessagesBackend
             .make(Right(__cfg), streaming = true))
           .getOrThrow() }"""
    ) ++ liveTail(model)

  /** Shared tail of every live prelude (RAI-098 extraction, content unchanged
    * from the RAI-090 openai slice): coding tools, the live-echo session and
    * the facade bindings. Byte-identical to the pre-extraction openai output.
    */
  private def liveTail(model: String): List[String] =
    List(
      """val __tools = zio.Unsafe.unsafe { implicit u =>
         zio.Runtime.default.unsafe.run(
           raider.tools.CodingToolset
             .make(java.nio.file.Paths.get("").toAbsolutePath.toString)
             .flatMap(ts => zio.ZIO.fromEither(ts.registry)))
           .getOrThrow() }""",
      s"val __model = \"${scalaStr(model)}\"",
      """val __session = zio.Unsafe.unsafe { implicit u =>
         zio.Runtime.default.unsafe.run(
           ReplSession.make(__backend,
             BudgetLimits.make(maxConcurrentTools = 2, maxAttempts = 48),
             __tools,
             model = __model,
             echoStream = true))
           .getOrThrow() }""",
      "ReplCommands.install(__session)",
      "given ReplSession = __session",
      """val scout = AgentRef("scout")""",
      """val worker = AgentRef("worker")""",
      """val coder = AgentRef("coder")"""
    )

  /** The scripted fixture branch — the historical mock path, unchanged by
    * RAI-090; reused verbatim for the live-without-key fallback.
    */
  private def scriptedSources(planFixture: Boolean = false): List[String] =
    val response =
      if planFixture then
        "{\"title\":\"PTY wiring plan\",\"steps\":[" +
          "\"Inspect the session hub\",\"Verify budget events\"]}"
      else
        "[scripted-fixture] mock answer (ScriptedModelBackend; no live network in this slice)"
    List(
      "import raider.repl.facade.*",
      "import raider.runtime.budget.BudgetLimits",
      "import raider.testkit.ScriptedModelBackend",
      s"val __backend = zio.Unsafe.unsafe { implicit u => zio.Runtime.default.unsafe.run(ScriptedModelBackend(raider.core.ModelEvent.Started(raider.core.AttemptId(\"$attemptId\")), raider.core.ModelEvent.TextDelta(raider.core.AttemptId(\"$attemptId\"), \"${scalaStr(response)}\"), raider.core.ModelEvent.Finished(raider.core.AttemptId(\"$attemptId\"), \"stop\"))).getOrThrow() }",
      "val __model = \"scripted\"",
      // scripted branch: echo stays OFF (fixture tests/smoke assert on the
      // plain transcript; live echo is an interactive-live-provider feature)
      "val __session = zio.Unsafe.unsafe { implicit u => zio.Runtime.default.unsafe.run(ReplSession.make(__backend, BudgetLimits.make())).getOrThrow() }",
      "ReplCommands.install(__session)",
      "given ReplSession = __session",
      "val scout = AgentRef(\"scout\")",
      "val worker = AgentRef(\"worker\")"
    )

  /** Escapes a value into the body of a Scala string literal so an env-sourced
    * key/URL/model containing quotes, backslashes or control chars cannot break
    * the generated prelude source (defense in depth: secrets must never leak,
    * not even via a compile-error echo of a broken line).
    */
  private def scalaStr(s: String): String =
    s.flatMap:
      case '\\'         => "\\\\"
      case '"'          => "\\\""
      case '\n'         => "\\n"
      case '\r'         => "\\r"
      case '\t'         => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString

  def main(args: Array[String]): Unit =
    // RAI-108: the product tabs entry sits behind the EXPLICIT --tabs flag;
    // every other argument keeps the historical ignore-behavior, so the legacy
    // path (piped smoke, scripts) is untouched bit-for-bit.
    val tabs = args.contains("--tabs")
    val chat = args.contains("--chat") && !tabs
    val engine =
      Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe.run(ReplEngine.make()).getOrThrow()
      }
    // the banner's backend line needs the interpreter-world session, so the
    // prelude runs first
    if chat then installChatRenderBridge()
    val plan = runPrelude(engine, chat)
    banner(engine, plan, tabs, chat)
    if tabs then runTabs(engine, plan)
    else if chat then runChat(engine, plan)
    else loop(engine)

  private def banner(
      engine: ReplEngine,
      plan: PreludePlan,
      tabs: Boolean,
      chat: Boolean
  ): Unit =
    println("Raider REPL (W10 fast-path slice)")
    println("  engine : scala3-repl 3.9.0 (in-process compiler)")
    printCaptured(engine, "raider.repl.facade.ReplCommands.printBackendLine()")
    if tabs then
      // Honest mode banner: the shell owns the input loop now; the legacy
      // command set and the Scala eval loop live in the no-flag mode.
      println(
        s"  mode   : tabs — product TabShell (RAI-108); commands: ${raider.ui.CommandRouter.commands.mkString(" ")}"
      )
      if plan.isScripted then
        println(
          "  stream : scripted fixture feed per tab (mock provider; no network)"
        )
      else
        println(
          "  stream : live backend wired; tab streams follow jobs started by the runtime"
        )
      println("  note   : Scala eval loop runs WITHOUT --tabs (legacy mode)")
    else if chat then
      println(
        "  mode   : chat — TabShell input loop with streaming chat renderer; :sessions, :tasks and :aux available"
      )
      println("  note   : Scala eval loop runs WITHOUT --chat (legacy mode)")
    else
      println(
        """  try    : scout.ask("hello")   |   val j = worker.start("bg"); j.await()"""
      )
      println("  cmds   : :help :jobs :cancel <id> :reset :quit")

  /** RAI-108: the product tabs run behind --tabs. [[TabShellHost]] owns the
    * terminal (the ONE writer, RAI-061) until `:quit`/EOF; the legacy loop is
    * NOT running concurrently (one input loop per process). RAI-113: the shell
    * terminal carries the `:scrollback [tabId]` interceptor (repl-level
    * decorator — the frozen router never sees the line). In scripted/mock mode
    * the fixture stream provider publishes per-tab job streams through the real
    * EventHub; scoped — shell, hub subscriptions and the fixture stream die
    * together, then the honest engine shutdown reports active jobs.
    */
  private def runTabs(engine: ReplEngine, plan: PreludePlan): Unit =
    Unsafe.unsafe { implicit u =>
      zio.Runtime.default.unsafe
        .run(
          ZIO.scoped {
            for
              wiring <- TabShellHost.wiring
              lineageDir = sys.env
                .get("RAIDER_LINEAGE_DIR")
                .map(
                  java.nio.file.Paths.get(_).toAbsolutePath.normalize()
                )
              lineage <- ReplLineageProvider.make(lineageDir)
              _ <- ScriptedTabStream.run(wiring).when(plan.isScripted).fork
              _ <- TabShellHost.run(
                wiring,
                ScrollbackCommands(wiring.registry),
                lineage
              )
            yield ()
          }
        )
        .getOrThrow()
    }
    shutdown(engine)

  private def runChat(engine: ReplEngine, plan: PreludePlan): Unit =
    if sys.env.get("TERM").contains("dumb") then
      println("Chat layout unavailable for this terminal; using the line REPL.")
      ttyLoop(engine)
      return
    Unsafe.unsafe { implicit u =>
      zio.Runtime.default.unsafe
        .run(
          ZIO.scoped {
            for
              wiring <- TabShellHost.wiring
              lineageDir = sys.env
                .get("RAIDER_LINEAGE_DIR")
                .map(java.nio.file.Paths.get(_).toAbsolutePath.normalize())
              lineage <- ReplLineageProvider.make(lineageDir)
              _ <- ScriptedTabStream.run(wiring).when(plan.isScripted).fork
              _ <- TabShellHost.runChat(
                wiring,
                ScrollbackCommands(wiring.registry),
                new TabShellHostChatMode(engine),
                Main.chatAuxiliary(lineage)
              )
            yield ()
          }
        )
        .getOrThrow()
    }
    shutdown(engine)

  private def runPrelude(
      engine: ReplEngine,
      chat: Boolean = false
  ): PreludePlan =
    val initial = preludePlan()
    val plan =
      if chat then
        initial.copy(
          sources = withChatRenderer(initial.sources, initial.isScripted)
        )
      else initial
    // RAI-090: a live start without a resolvable key explains how to enable
    // live mode BEFORE the banner; the REPL then keeps running the scripted/
    // mock path (also re-printed on :reset, which re-reads the environment).
    plan.notices.foreach(println)
    plan.sources.foreach(src => submit(src, engine, silent = true))
    plan

  private def withChatRenderer(
      sources: List[String],
      scripted: Boolean
  ): List[String] =
    val wired = sources.flatMap { source =>
      if source.startsWith("val __session =") then
        val withSink =
          if source.contains("echoStream = true") then
            source.replace(
              "echoStream = true",
              "echoStream = true, renderSink = Some(__chatSink), toolApproval = Some(__chatSink)"
            )
          else
            source.replace(
              "ReplSession.make(__backend, BudgetLimits.make())",
              "ReplSession.make(__backend, BudgetLimits.make(), echoStream = true, renderSink = Some(__chatSink), toolApproval = Some(__chatSink))"
            )
        List(
          s"val __chatSink = new raider.repl.ChatLayoutSink(System.getProperties.get(\"raider.chat.renderBridge\").asInstanceOf[java.util.function.Consumer[String]], zio.Duration.fromMillis(${
              if scripted then 180 else 0
            }L), model = __model)",
          withSink
        )
      else List(source)
    }
    wired

  private def evalChatControl(engine: ReplEngine, source: String): Unit =
    def emit(text: String): Unit =
      if System.getProperties.containsKey("raider.chat.renderBridge") then
        chatRenderBridge.accept(text)
      else println(text)

    Unsafe.unsafe { implicit u =>
      zio.Runtime.default.unsafe.run(engine.eval(source)).getOrThrow() match
        case EvalResult.Value(repr) if repr.trim.nonEmpty => emit(repr.trim)
        case EvalResult.CompileError(msg)                 => emit(msg.trim)
        case EvalResult.RuntimeFailure(msg) =>
          emit(s"runtime failure: ${msg.trim}")
        case _ => ()
    }

  private final class TabShellHostChatMode(engine: ReplEngine)
      extends raider.ui.TabShell.ChatMode:
    import zio.UIO

    def command(raw: String): Option[UIO[Unit]] =
      raw.trim match
        case command if command.startsWith(":plan ") =>
          val prompt = command.stripPrefix(":plan ").trim
          if prompt.isEmpty then
            Some(ZIO.succeed(chatRenderBridge.accept("usage: :plan <task>")))
          else
            Some(
              ZIO.succeed(
                evalChatControl(
                  engine,
                  s"raider.repl.facade.ReplCommands.startPlan(\"${scalaStr(prompt)}\")"
                )
              )
            )
        case ":plan" =>
          Some(ZIO.succeed(chatRenderBridge.accept("usage: :plan <task>")))
        case decision if decision.startsWith(":approve ") =>
          val token = decision.stripPrefix(":approve ").trim
          Some(
            ZIO.succeed(
              evalChatControl(
                engine,
                s"raider.repl.facade.ReplCommands.resolveToolApproval(\"${scalaStr(token)}\", true)"
              )
            )
          )
        case decision if decision.startsWith(":deny ") =>
          val token = decision.stripPrefix(":deny ").trim
          Some(
            ZIO.succeed(
              evalChatControl(
                engine,
                s"raider.repl.facade.ReplCommands.resolveToolApproval(\"${scalaStr(token)}\", false)"
              )
            )
          )
        case ":approve" | ":deny" =>
          Some(
            ZIO.succeed(
              chatRenderBridge.accept(
                "decision requires the exact request token"
              )
            )
          )
        case ":cancel" =>
          Some(
            ZIO.succeed(
              evalChatControl(
                engine,
                "val _ = raider.repl.facade.ReplCommands.cancelChat()"
              )
            )
          )
        case ":chat" =>
          Some(ZIO.succeed(evalChatControl(engine, "__chatSink.showChat()")))
        case ":scroll-up" =>
          Some(ZIO.succeed(evalChatControl(engine, "__chatSink.scrollUp()")))
        case ":scroll-down" =>
          Some(ZIO.succeed(evalChatControl(engine, "__chatSink.scrollDown()")))
        case ":tasks" =>
          Some(ZIO.succeed(evalChatControl(engine, "__chatSink.showTasks()")))
        case ":task-up" =>
          Some(ZIO.succeed(evalChatControl(engine, "__chatSink.moveTask(-1)")))
        case ":task-down" =>
          Some(ZIO.succeed(evalChatControl(engine, "__chatSink.moveTask(1)")))
        case _ => None

    def submit(prompt: String): UIO[Unit] =
      val literal = scalaStr(prompt)
      ZIO.succeed(
        evalChatControl(
          engine,
          s"raider.repl.facade.ReplCommands.startChat(\"$literal\")"
        )
      )

  private def loop(engine: ReplEngine): Unit =
    if Option(System.console()).isEmpty then pipeLoop(engine)
    else ttyLoop(engine)

  /** Non-TTY path (piped smoke): plain stdin reader, no terminal detection
    * games. Same line semantics as the interactive loop: commands, multiline
    * buffer, blank-line submit.
    */
  private def pipeLoop(engine: ReplEngine): Unit =
    val in = java.io.BufferedReader(
      java.io
        .InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8)
    )
    val pending = new StringBuilder
    var running = true
    while running do
      print(if pending.isEmpty then Prompt else Continuation)
      System.out.flush()
      // readLine returns null at EOF — wrapped in Option (null-free code rule)
      Option(in.readLine()) match
        case None =>
          driveEof(pending)
          running = false
        case Some(line) => running = drive(engine, pending, line)
    shutdown(engine)

  /** Interactive TTY path: JLine line editor; Ctrl+C clears the pending buffer
    * and redraws the prompt (never kills the loop).
    *
    * Completion (RAI-068): the EngineCompleter asks the engine for real dotty
    * candidates and ScalaWordParser keeps insertion local to the trailing
    * identifier segment. Wiring model and crash guarantees are documented in
    * CompletionSupport; the completer runs inside readLine on this thread,
    * serialized against eval by control flow.
    */
  private def ttyLoop(engine: ReplEngine): Unit =
    val reader = LineReaderBuilder
      .builder()
      .terminal(makeTerminal())
      .completer(CompletionSupport.EngineCompleter(engine))
      .parser(CompletionSupport.ScalaWordParser)
      .build()
    // JLine 4 does not bind Ctrl+C by default: without this the interrupt
    // byte is swallowed and the pending multiline buffer survives ^C.
    // "abort" is readLine's special Reference that throws UserInterruptException.
    val mainKeyMap = Option(reader.getKeyMaps.get("main"))
    mainKeyMap.foreach(
      _.bind(new org.jline.reader.Reference("abort"), "\u0003")
    )
    val pending = new StringBuilder
    var running = true
    while running do
      // readLine throws EndOfFileException at EOF — normalized to None
      val lineOpt =
        try
          Some(
            reader.readLine(if pending.isEmpty then Prompt else Continuation)
          )
        catch
          case _: EndOfFileException => None
          case _: UserInterruptException =>
            if pending.nonEmpty then println("(pending input cleared)")
            pending.clear()
            Some("")
      lineOpt match
        case None =>
          driveEof(pending)
          running = false
        case Some(line) => running = drive(engine, pending, line)
    shutdown(engine)

  /** One line of console input against the shared pending buffer; false = quit.
    */
  private def drive(
      engine: ReplEngine,
      pending: StringBuilder,
      line: String
  ): Boolean =
    line match
      case "" =>
        if pending.nonEmpty then
          val src = pending.toString
          pending.clear()
          submitPending(src, engine)
        true
      case cmd if pending.isEmpty && Commands.isTypeQuery(cmd.trim) =>
        // RAI-124: a :type query joins the SAME multiline pending-buffer path
        // as Scala input (the :load/paste path): keep typing; the balanced
        // buffer submits as the driver's single-line `:type` command
        // (Commands.typeQuerySource flattens it).
        pending.append(cmd).append('\n')
        if complete(pending.toString) then
          val src = pending.toString
          pending.clear()
          submitPending(src, engine)
        true
      case cmd if pending.isEmpty && cmd.trim.startsWith(":") =>
        Commands.handle(cmd, engine, () => runPrelude(engine))
      case input =>
        pending.append(input).append('\n')
        if complete(pending.toString) then
          val src = pending.toString
          pending.clear()
          submitPending(src, engine)
        true

  /** Submit an assembled pending buffer: a `:type` query goes through
    * Commands.handle as the flattened single-line command (RAI-124); anything
    * else is ordinary Scala input.
    */
  private def submitPending(src: String, engine: ReplEngine): Unit =
    if Commands.isTypeQuery(src.trim) then
      Commands.handle(
        Commands.typeQuerySource(src),
        engine,
        () => runPrelude(engine)
      )
    else submit(src, engine)

  private def driveEof(pending: StringBuilder): Unit =
    if pending.nonEmpty then
      println("(EOF with pending input; buffer discarded)")

  private def makeTerminal(): Terminal =
    TerminalBuilder.terminal()

  /** Continuation heuristic (accepted W10 trade-off): char-count balance of (),
    * {} and [] plus no trailing continuation token. A wrong guess is never
    * sticky — a compile error preserves state, and a blank line always submits
    * the pending buffer as-is.
    */
  private def complete(src: String): Boolean =
    val trimmed = src.trim
    trimmed.nonEmpty &&
    Seq(('(', ')'), ('{', '}'), ('[', ']')).forall((o, c) =>
      src.count(_ == o) == src.count(_ == c)
    ) &&
    !continuationEndings.exists(trimmed.endsWith)

  private val continuationEndings =
    Set("=", "=>", ",", ".", "+", "-", "*", "/", "%", "&&", "||", "with", ":")

  private def submit(
      src: String,
      engine: ReplEngine,
      silent: Boolean = false
  ): Unit =
    val result =
      Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe.run(engine.eval(src)).getOrThrow()
      }
    if !silent then
      result match
        case EvalResult.Value(repr)       => println(repr.trim)
        case EvalResult.CompileError(msg) => println(msg.trim)
        case EvalResult.RuntimeFailure(msg) =>
          println(s"runtime failure: ${msg.trim}")
    ()

  private def printCaptured(engine: ReplEngine, expr: String): Unit =
    val result =
      Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe.run(engine.eval(expr)).getOrThrow()
      }
    result match
      case EvalResult.Value(repr) =>
        val text = repr.trim
        if text.nonEmpty then println(text)
      case EvalResult.CompileError(msg) =>
        println(
          s"prelude broken? ${msg.trim.linesIterator.take(3).mkString(" | ")}"
        )
      case EvalResult.RuntimeFailure(m) =>
        println(s"command failed: $m")

  /** Honest shutdown: the interpreter world reports active jobs; they are
    * daemon fibers of this process and there is no durable resume here.
    */
  private def shutdown(engine: ReplEngine): Unit =
    printCaptured(
      engine,
      "raider.repl.facade.ReplCommands.printShutdownWarning()"
    )
    println("bye")
    // deterministic exit: the JVM must not linger after the console loop ends
    Runtime.getRuntime.exit(0)

end Main
