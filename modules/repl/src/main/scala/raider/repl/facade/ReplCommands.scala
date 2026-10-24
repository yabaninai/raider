package raider.repl.facade

import raider.core.{JobId, RaiderError, RequestMessage}
import raider.provider.anthropic.AnthropicMessagesBackend
import raider.runtime.jobs.JobStatus
import raider.testkit.ScriptedModelBackend
import zio.ZIO

/** Console-facing text entry points (fast-REPL W10 slice).
  *
  * ARCHITECTURE NOTE (integration finding 2026-10-03): the in-process
  * scala3-repl classloader (AbstractFileClassLoader) loads classpath classes
  * SELF-FIRST, so object statics of this facade exist TWICE — once in the
  * console JVM world and once in the interpreter world. All REPL-owned state
  * (session, started-job handles) therefore lives in the INTERPRETER world
  * only; the console main never touches statics directly — it evaluates the
  * expression forms of these methods through the engine and prints the text.
  */
object ReplCommands:

  private var chatJob: Option[raider.runtime.jobs.JobHandle[String]] = None

  private val sessionRef =
    java.util.concurrent.atomic.AtomicReference[Option[ReplSession]](None)

  /** Called by the prelude right after the session is constructed. */
  def install(s: ReplSession): Unit = sessionRef.set(Some(s))

  /** Start chat through the session-owned job manager. The detached waiter is
    * runtime-owned; the REPL compiler remains confined to the input thread.
    */
  def startChat(prompt: String): Unit =
    val sink = session.renderSink
    sink.foreach(_.asInstanceOf[raider.repl.ChatLayoutSink].beginUser(prompt))
    sink.foreach(_.asInstanceOf[raider.repl.ChatLayoutSink].beginAssistant())
    val job = FacadeOps.startSteerable(
      session,
      AgentRef("scout"),
      prompt,
      identity
    )
    chatJob = Some(job)
    FacadeOps.unsafeRun(
      session.jobs
        .await(job)
        .tap(_ =>
          ZIO.succeed(
            sink.foreach(
              _.asInstanceOf[raider.repl.ChatLayoutSink].finishAssistant()
            )
          )
        )
        .forkDaemon
    )

  /** Start a real PlanSession proposal and keep its serialized snapshot live
    * for the chat shell's tasks panel.
    */
  def startPlan(prompt: String): Unit =
    val planJob = FacadeOps.unsafeRun(
      session.planSession.startPlan(
        List(RequestMessage("user", prompt)),
        session.limits,
        session.limits.maxAttempts
      )
    )
    FacadeOps.unsafeRun(session.planPanel.track(planJob).forkDaemon.unit)

  /** Request bounded cancellation and keep any text already rendered. */
  def cancelChat(): Boolean =
    val requested =
      chatJob.exists(job => FacadeOps.unsafeRun(session.jobs.cancel(job)))
    session.renderSink.foreach { sink =>
      val chat = sink.asInstanceOf[raider.repl.ChatLayoutSink]
      if requested then chat.cancelAssistant() else chat.completeAssistant()
    }
    requested

  /** Resolve the active chat tool approval in the interpreter world. */
  def resolveToolApproval(token: String, approve: Boolean): Boolean =
    session.renderSink match
      case Some(sink: raider.repl.ChatLayoutSink) =>
        FacadeOps.unsafeRun(sink.resolveToolApproval(token, approve))
      case _ => false

  private def session: ReplSession =
    sessionRef.get.getOrElse(
      throw RaiderError.Configuration(
        "REPL prelude not initialized (no session installed)"
      )
    )

  private def jobsText: String =
    val snapshots = FacadeOps.unsafeRun:
      ZIO.foreach(FacadeOps.allHandles)(h => session.jobs.snapshotAny(h))
    if snapshots.isEmpty then "no jobs started in this session"
    else
      snapshots
        .map { s =>
          // RAI-132: mark steerable jobs — steering channel open, job not
          // terminal (terminal jobs refuse sends typed).
          val mark =
            if FacadeOps.isSteerable(s.id, s.status) then "  [steerable]"
            else ""
          FacadeOps.renderSnapshot(s) + mark
        }
        .mkString("\n")

  private def cancelText(rawId: String): String =
    FacadeOps.handleById(JobId(rawId)) match
      case None =>
        s"unknown job $rawId (see :jobs)"
      case Some(handle) =>
        if FacadeOps.unsafeRun(session.jobs.cancel(handle)) then
          s"cancellation requested for ${handle.id.value}"
        else s"${handle.id.value} is already terminal"

  /** Backend mode line for the banner (printed inside the interpreter world).
    * Honest by contract: a scripted fixture says so; a live backend names its
    * wire family (RAI-098: Anthropic Messages is distinct from the
    * OpenAI-compatible transport); anything else names the model actually
    * configured (never a bare "unknown").
    */
  def printBackendLine(): Unit =
    val mode = session.backend match
      case s: ScriptedModelBackend if s.fixtureMode =>
        "fixture/mock — ScriptedModelBackend, live providers NOT wired in this slice"
      case _: AnthropicMessagesBackend =>
        s"live — Anthropic Messages streaming, model ${session.model}"
      case _ => s"live — OpenAI-compatible streaming, model ${session.model}"
    println(s"  backend: $mode")

  /** :jobs — the rendered job table. */
  def printJobs(): Unit =
    println(jobsText)

  /** :log <id> — the trace of a job (rounds, tools, results). */
  def printLog(rawId: String): Unit =
    println(logText(rawId))

  private def logText(rawId: String): String =
    FacadeOps.handleById(JobId(rawId)) match
      case None         => s"unknown job $rawId (see :jobs)"
      case Some(handle) =>
        // read the trace from the facade's started jobs
        FacadeOps.traceOf(handle.id) match
          case Some(traceRef) =>
            val events = FacadeOps.unsafeRun(traceRef.get)
            if events.isEmpty then
              s"${handle.id.value}: no trace recorded (job may still be running or finished before tracing was added)"
            else
              val sb = new StringBuilder
              sb ++= s"=== trace: ${handle.id.value} (${events.size} events) ==="
              events.foreach:
                case raider.core.trace.TraceEvent
                      .RunStarted(_, model, msgCount) =>
                  sb ++= s"\n  RUN START  model=$model messages=$msgCount"
                case raider.core.trace.TraceEvent.RoundStarted(_, round) =>
                  sb ++= s"\n  ROUND $round"
                case raider.core.trace.TraceEvent.ModelCall(_, _, msgCount) =>
                  sb ++= s"\n    → model call (${msgCount} messages)"
                case raider.core.trace.TraceEvent.TextReceived(_, _, text) =>
                  sb ++= s"\n    ← text: ${text.take(120)}${
                      if text.length > 120 then "…" else ""
                    }"
                case raider.core.trace.TraceEvent.ToolCallStarted(
                      _,
                      _,
                      tool,
                      callId,
                      args
                    ) =>
                  sb ++= s"\n    → tool: $tool ($callId) args: ${args.take(80)}${
                      if args.length > 80 then "…" else ""
                    }"
                case raider.core.trace.TraceEvent.ToolCallFinished(
                      _,
                      _,
                      tool,
                      _,
                      result,
                      ok
                    ) =>
                  sb ++= s"\n    ← tool: $tool ${
                      if ok then "OK" else "FAIL"
                    }: ${result.take(120)}${
                      if result.length > 120 then "…" else ""
                    }"
                case raider.core.trace.TraceEvent.ToolCallRefused(
                      _,
                      _,
                      tool,
                      _,
                      reason
                    ) =>
                  sb ++= s"\n    ← tool REFUSED: $tool ($reason)"
                case raider.core.trace.TraceEvent
                      .ChildDelegated(_, agent, childId) =>
                  sb ++= s"\n    → delegate: $agent → $childId"
                case raider.core.trace.TraceEvent
                      .ChildAwaited(_, childId, status) =>
                  sb ++= s"\n    ← child $childId: $status"
                case raider.core.trace.TraceEvent
                      .RunFinished(_, outcome, detail) =>
                  sb ++= s"\n  RUN $outcome: ${detail.take(120)}"
                case raider.core.trace.TraceEvent.RunFailed(_, code, detail) =>
                  sb ++= s"\n  RUN FAILED ($code): ${detail.take(120)}"
              sb.toString
          case None =>
            s"${handle.id.value}: no trace available (traces require --trace or the coder agent)"

  /** :cancel <id> — outcome text. */
  def printCancel(rawId: String): Unit =
    println(cancelText(rawId))

  /** :send <id> <text> — the typed steering receipt (RAI-132): queued (applies
    * at the next model turn), later applied, or rejected with the verbatim
    * runtime mailbox refusal. Never silent.
    */
  def printSend(rawId: String, text: String): Unit =
    println(sendText(rawId, text))

  private def sendText(rawId: String, text: String): String =
    FacadeOps.unsafeRun:
      FacadeOps.sendToJob(JobId(rawId), text).map(SteerReceipt.render)

  /** Honest exit warning for active jobs (empty = silent). */
  def printShutdownWarning(): Unit =
    val n = FacadeOps.unsafeRun:
      ZIO
        .foreach(FacadeOps.allHandles)(h => session.jobs.snapshotAny(h))
        .map(_.count(s => !JobStatus.terminal(s.status)))
    if n > 0 then
      println(
        s"warning: $n job(s) still active; they are dropped at process exit (no durable resume in this slice)"
      )

end ReplCommands
