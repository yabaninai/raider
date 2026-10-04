package raider.runtime.loop

import raider.core.{ModelBackend, ModelEvent, ModelRequest, RaiderError,
                    RequestMessage, RootId, ToolCall, ToolCallId, ToolRegistry}
import raider.runtime.admission.{Admission, SlotKind}
import raider.runtime.budget.{BudgetLimits, CostEstimate, MicroUsd}
import zio.{Duration, Scope, ZIO}
import zio.json.ast.Json
import zio.json.EncoderOps
import zio.stream.ZStream

import java.util.concurrent.atomic.AtomicLong

/** RAI-010.a: the single AGENT execution point (runtime-contracts §8) — not a
  * second runtime. Construction IS the freeze point: backend, tool registry,
  * resolved model, admission, root and the per-attempt cost estimate are fixed
  * here; each `runText` call binds the frozen conversation to the declared
  * ceilings. With an empty registry it degenerates exactly into
  * `Runner.runText` (proven by fixture), so the REPL keeps one semantics.
  *
  * Per round: ONE admission attempt (Model slot + BudgetLedger reserve in the
  * §6 acquisition order) around one `backend.stream` pass. `ToolCallReady`
  * events are the only executable tool requests — `ToolCallDelta` fragments
  * are never executed (§8). Unknown tools and malformed argument JSON are
  * structured feedback messages to the model (bounded by `maxRounds`), never
  * silent ignores; a DEFINITIVE tool failure (or a non-JSON tool output) fails
  * the whole run with the tool's typed error code — mutational tool calls are
  * never auto-retried after an ambiguous or failed outcome (AGENTS trust
  * boundaries; §5 Uncertain stays Uncertain).
  *
  * Cancellation accounting is owned by Admission brackets (RAI-009): cancel
  * while waiting charges nothing; cancel after permit before dispatch releases
  * and refunds; interrupt DURING model/tool dispatch keeps the charge and
  * marks the reservation Uncertain — unknown is never zeroed (§7).
  *
  * Tools dispatch through the Tool slot (`withSlotFree`) strictly sequentially
  * in model-emitted order: §6 serializes mutating tools by default; parallel
  * `concurrentSafe` fan-out is a later slice, not silent here.
  *
  * Final validation: the answer must be non-empty and marker-free — the loop
  * never injects `InternalMarker` anywhere, and a backend answer that carries
  * it is an OutputValidation defect, not a pass.
  */
final case class AgentLoop(
  backend: ModelBackend,
  tools: ToolRegistry,
  model: String,
  admission: Admission,
  root: RootId,
  attemptEstimate: CostEstimate = CostEstimate.Unknown,
  trace: raider.core.trace.TraceEvent => zio.UIO[Unit] = _ => zio.ZIO.unit,
  systemPrompt: Option[String] = None
):

  /** One agent run: bounded tool rounds until a validated final text.
    *
    * `maxRounds` bounds MODEL attempts of this run and must satisfy
    * `1 <= maxRounds <= ceilings.maxAttempts` — every round is a billable
    * attempt, so exhausting rounds or attempts is a typed LocalBudgetExceeded
    * (RA-LBUDGET); configuration violations are refused BEFORE any call.
    */
  def runText(messages: List[RequestMessage], ceilings: BudgetLimits,
      maxRounds: Int): ZIO[Any, RaiderError, String] =
    if messages.isEmpty then
      ZIO.fail(RaiderError.InputValidation("agent loop needs at least one message"))
    else if ceilings.maxAttempts < 1 then
      ZIO.fail(RaiderError.Configuration(
        s"maxAttempts must be >= 1, got ${ceilings.maxAttempts}"))
    else if maxRounds < 1 then
      ZIO.fail(RaiderError.Configuration(s"maxRounds must be >= 1, got $maxRounds"))
    else if maxRounds > ceilings.maxAttempts then
      ZIO.fail(RaiderError.Configuration(
        s"maxRounds=$maxRounds exceeds ceilings.maxAttempts=${ceilings.maxAttempts}: " +
          "every round is a billable attempt"))
    else
      trace(raider.core.trace.TraceEvent.RunStarted(AgentLoop.now(), model, messages.size)) *>
        rounds(messages, ceilings, maxRounds, 1).tapBoth(
          e => trace(raider.core.trace.TraceEvent.RunFailed(AgentLoop.now(), e.code, e.detail.take(160))),
          t => trace(raider.core.trace.TraceEvent.RunFinished(AgentLoop.now(), "Succeeded", t.take(160)))
        )

  private def rounds(messages: List[RequestMessage], ceilings: BudgetLimits,
      maxRounds: Int, round: Int): ZIO[Any, RaiderError, String] =
    if round > maxRounds then
      ZIO.fail(RaiderError.LocalBudgetExceeded(
        s"maxRounds=$maxRounds exhausted with pending tool calls " +
          s"(root ${root.value})"))
    else
      trace(raider.core.trace.TraceEvent.RoundStarted(AgentLoop.now(), round)) *>
      attempt(messages).flatMap:
        case AttemptResult.Final(text) =>
          trace(raider.core.trace.TraceEvent.TextReceived(AgentLoop.now(), round, text.take(200))) *>
            ZIO.fromEither(validateFinal(text)).as(text)
        case AttemptResult.ToolRound(text, calls) =>
          for
            // §6 default: serialized dispatch in emission order
            results   <- ZIO.foreach(calls.toList)(dispatch)
            assistant  = if text.trim.isEmpty then Nil
                         else List(RequestMessage(AgentLoop.AssistantRole, text))
            answer    <- rounds(messages ++ assistant ++ results, ceilings,
                             maxRounds, round + 1)
          yield answer

  private def attempt(messages: List[RequestMessage])
      : ZIO[Any, RaiderError, AttemptResult] =
    val toolDefs = tools.names.map { n =>
      tools.lookup(n).map { t =>
        raider.core.ToolDefinition(n, t.description,
          t.parametersJsonSchema)
      }
    }.flatten
    val allMessages = systemPrompt match
      case Some(sys) => RequestMessage("system", sys) +: messages
      case None      => messages
    val request = ModelRequest(model, allMessages, AgentLoop.MaxOutputTokens, toolDefs)
    trace(raider.core.trace.TraceEvent.ModelCall(AgentLoop.now(), -1, messages.size)) *>
    admission.withSlot(SlotKind.Model, root, attemptEstimate, None):
      ZIO.scoped(collect(backend.stream(request))).map(r => (r, MicroUsd(0)))

  private def collect(events: ZStream[Scope, RaiderError, ModelEvent])
      : ZIO[Scope, RaiderError, AttemptResult] =
    events.runFold(AgentAttemptState.empty):
      case (st, ModelEvent.TextDelta(_, text)) => st.copy(text = st.text + text)
      case (st, ModelEvent.ToolCallReady(_, call, name, args)) =>
        st.copy(calls = st.calls :+ ToolCall(name, call, args))
      case (st, ModelEvent.Failed(_, err))     => st.copy(failure = Some(err))
      case (st, ModelEvent.Finished(_, _))     => st.copy(finished = true)
      case (st, _)                             => st
    .flatMap: st =>
      st.failure match
        case Some(err) => ZIO.fail(err)
        case None if !st.finished =>
          ZIO.fail(RaiderError.StreamProtocol(
            "backend stream ended without Finished"))
        case None if st.calls.isEmpty =>
          ZIO.succeed(AttemptResult.Final(st.text))
        case _ =>
          ZIO.succeed(AttemptResult.ToolRound(st.text, st.calls))

  /** One tool call → the tool-role message matched back by tool_call_id (§8).
    * NOTE: fields are destructured ONCE — Scala 3.9 chokes (E046 cyclic) on
    * `x.call.y` member-select chains here, so no such chain survives. */
  private def dispatch(toolCall: ToolCall): ZIO[Any, RaiderError, RequestMessage] =
    // NOTE: one select per val — Scala 3.9 chokes (E046 cyclic) on
    // `x.call.y` member-select chains in this file, so none survive.
    val callName = extractName(toolCall)
    val callId: ToolCallId = extractCallId(toolCall)
    val argsJson = extractArgs(toolCall)
    tools.lookup(callName) match
      case None =>
        // structured feedback with bounded attempts — never a silent ignore
        trace(raider.core.trace.TraceEvent.ToolCallRefused(
          AgentLoop.now(), -1, callName, callId.value, "unknown_tool")) *>
          ZIO.succeed:
            val nameJson = callName.toJson
            toolMessage(callId,
              s"""{"error":"unknown_tool","name":$nameJson}""")
      case Some(tool) =>
        Json.decoder.decodeJson(argsJson) match
          case Left(err) =>
            val errJson = err.toJson
            ZIO.succeed(toolMessage(callId,
              s"""{"error":"invalid_arguments","detail":$errJson}"""))
          case Right(_) =>
            trace(raider.core.trace.TraceEvent.ToolCallStarted(
              AgentLoop.now(), -1, tool.name, callId.value, argsJson.take(200))) *>
            admission.withSlotFree(SlotKind.Tool, root, None):
              ZIO.scoped:
                tool.invoke(argsJson)
                  .timeout(Duration.fromMillis(tool.timeoutMs))
                  .flatMap:
                    case None =>
                      ZIO.fail(RaiderError.DeadlineExceeded(
                        s"tool '${tool.name}' exceeded timeoutMs=${tool.timeoutMs}"))
                    case Some(output) => ZIO.succeed(output)
            .flatMap(output => deliver(toolCall, tool, output))
            .tap(resultMsg => trace(raider.core.trace.TraceEvent.ToolCallFinished(
              AgentLoop.now(), -1, tool.name, callId.value,
              resultMsg.content.take(300), success = true)))

  /** A definitive tool result must be valid JSON to enter the message
    * envelope; a tool that returns garbage is a ToolFailed defect, not model
    * feedback (defects are never masked as conversational content, §14). */
  private def deliver(toolCall: ToolCall, tool: raider.core.Tool, output: String)
      : ZIO[Any, RaiderError, RequestMessage] =
    val callId: ToolCallId = extractCallId(toolCall)
    Json.decoder.decodeJson(output) match
      case Left(_) =>
        ZIO.fail(RaiderError.ToolFailed(
          s"tool '${tool.name}' returned non-JSON output (${output.length} chars)"))
      case Right(_) => ZIO.succeed(toolMessage(callId, output))

  private def toolMessage(callId: ToolCallId, payloadJson: String): RequestMessage =
    val id      = callId.value
    // Truncate large tool results — keep first 2000 chars to avoid
    // filling the model's context window with file dumps
    val payload =
      if payloadJson.length > AgentLoop.MaxToolResultChars
      then payloadJson.take(AgentLoop.MaxToolResultChars) + "...[truncated]"
      else payloadJson
    val content = s"""{"tool_call_id":"$id","output":$payload}"""
    RequestMessage(AgentLoop.ToolRole, content)

  private def extractName(tc: ToolCall): String = tc.name
  private def extractCallId(tc: ToolCall): ToolCallId =
    tc.id
  private def extractArgs(tc: ToolCall): String = tc.argumentsJson

  private def validateFinal(text: String): Either[RaiderError, Unit] =
    if text.trim.isEmpty then
      Left(RaiderError.OutputValidation("final answer is empty"))
    else if text.contains(AgentLoop.InternalMarker) then
      Left(RaiderError.OutputValidation(
        "final answer contains the reserved internal marker"))
    else Right(())
end AgentLoop

object AgentLoop:

  val ToolRole: String = "tool"
  val AssistantRole: String = "assistant"

  /** Reserved sentinel the loop never injects anywhere; its presence in a
    * final answer is an output-validation defect (marker-free contract). */
  val InternalMarker: String = "⟦raider:internal⟧"

  private val counter = AtomicLong(0)

  /** Fresh root for a standalone run (per-attempt accounting parity with
    * Runner.runText; per-job root correlation is a facade obligation). */
  def newRoot(): RootId = RootId(s"loop_${counter.incrementAndGet()}")

  private[loop] val MaxOutputTokens = 4096 // reasoning models burn tokens on thinking
  private[loop] val MaxToolResultChars = 2000 // truncate large tool results

  private def now(): String = java.time.Instant.now().toString
end AgentLoop

private[loop] sealed trait AttemptResult
private[loop] object AttemptResult:
  final case class Final(text: String) extends AttemptResult
  final case class ToolRound(text: String, calls: Vector[ToolCall])
      extends AttemptResult

private[loop] final case class AgentAttemptState(
  text: String, calls: Vector[ToolCall],
  failure: Option[RaiderError], finished: Boolean)
private[loop] object AgentAttemptState:
  val empty: AgentAttemptState = AgentAttemptState("", Vector.empty, None, false)
