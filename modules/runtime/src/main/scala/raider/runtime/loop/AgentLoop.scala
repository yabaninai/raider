package raider.runtime.loop

import raider.core.{
  ModelBackend,
  ModelEvent,
  ModelRequest,
  RaiderError,
  RecoveryClass,
  RequestMessage,
  RootId,
  ToolCall,
  ToolCallId,
  ToolRegistry
}
import raider.core.plugin.{HookOutcome, PluginHost}
import raider.runtime.admission.{Admission, SlotKind}
import raider.runtime.budget.{BudgetLimits, CostEstimate, MicroUsd}
import raider.runtime.events.ToolOutcome
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
  * events are the only executable tool requests — `ToolCallDelta` fragments are
  * never executed (§8). Unknown tools and malformed argument JSON are
  * structured feedback messages to the model (bounded by `maxRounds`), never
  * silent ignores; a DEFINITIVE tool failure (or a non-JSON tool output) fails
  * the whole run with the tool's typed error code — mutational tool calls are
  * never auto-retried after an ambiguous or failed outcome (AGENTS trust
  * boundaries; §5 Uncertain stays Uncertain).
  *
  * Cancellation accounting is owned by Admission brackets (RAI-009): cancel
  * while waiting charges nothing; cancel after permit before dispatch releases
  * and refunds; interrupt DURING model/tool dispatch keeps the charge and marks
  * the reservation Uncertain — unknown is never zeroed (§7).
  *
  * Tools dispatch through the Tool slot (`withSlotFree`) strictly sequentially
  * in model-emitted order: §6 serializes mutating tools by default; parallel
  * `concurrentSafe` fan-out is a later slice, not silent here.
  *
  * Final validation: the answer must be non-empty and marker-free — the loop
  * never injects `InternalMarker` anywhere, and a backend answer that carries
  * it is an OutputValidation defect, not a pass.
  *
  * Event emission (RAI-058): `events` is an OPTIONAL context binding this run
  * to one job/root/session of an EventHub (ADR-024 §2.3/§6); `None` (the
  * default) = zero overhead. The same context may carry an optional JobJournal
  * (RAI-093, see [[LoopEvents]]): every emitted envelope is appended there
  * failure-absorbingly, and a journaled `Terminal` triggers a checkpoint — the
  * loop itself owns no journal logic. Emission seams, in program order:
  *   - `Waiting(Slot)` (RELIABLE, bounded) before each Model/Tool slot wait;
  *   - `ModelCallStarted(round, model, Some(n))` → stream pass →
  *     `ModelCallFinished(round, durationMs, usage = unknown)` — the real
  *     round, never -1; Finished is emitted on failure too; usage is honestly
  *     unknown (no cost is known at this level);
  *   - `ToolStarted(callId, tool, argsSummary ≤512)` → `ToolFinished(callId,
  *     tool, Succeeded | Timeout | Cancelled | Failed(reason))` — INCLUDING the
  *     failure path (today's trace emits nothing on failure); refusals are
  *     documented as `ToolFinished(Failed("refused: ..."))` without a
  *     ToolStarted;
  *   - `Diagnostics("round N text: …")` — TextReceived equivalent;
  *   - `Diagnostics("plugin hook …")` — RAI-095 lifecycle hooks: one line per
  *     `PluginHost` hook outcome, `runStart` after the RunStarted trace,
  *     `runFinish` before the RunFinished/RunFailed trace. Hooks NEVER
  *     influence the run's result or typed error channel (outcomes are only
  *     logged; `plugins = None` — the default — is a literal no-op).
  *   - `phase_started`/`phase_finished` (RAI-153, ADR-032 v2 lineage wire) —
  *     the plan phase only (the one boundary the runtime knows, RAI-107): enter
  *     before the first attempt, exit on every outcome; the frozen hub family
  *     is untouched (see [[LoopEvents.PhaseSink]]).
  * Emission never breaks the run (failure-absorbing, see [[LoopEvents]]); the
  * existing `trace` callback keeps its exact call points and event types (its
  * round fields now carry the real round instead of the old -1 hardcode); job
  * status/Terminal remain a job-layer obligation (RAI-059).
  */
final case class AgentLoop(
    backend: ModelBackend,
    tools: ToolRegistry,
    model: String,
    admission: Admission,
    root: RootId,
    attemptEstimate: CostEstimate = CostEstimate.Unknown,
    trace: raider.core.trace.TraceEvent => zio.UIO[Unit] = _ => zio.ZIO.unit,
    systemPrompt: Option[String] = None,
    events: Option[LoopEvents] = None,
    // RAI-095: optional lifecycle hook host; None (default) = zero overhead
    plugins: Option[PluginHost] = None,
    // RAI-107 (ADR-028): optional plan-mode binding; None (default) = plan
    // mode OFF — `runText` is untouched and `runPlan` refuses typed. When
    // present, ONLY `runPlan` (the plan phase) enforces the read-only toolset
    // derived from the frozen `RecoveryClass`; ordinary runs keep full tools.
    planMode: Option[PlanMode] = None
):

  /** Event emission (RAI-058): `None` = zero-overhead no-op at every seam. */
  private def ev(f: LoopEvents => zio.UIO[Unit]): zio.UIO[Unit] =
    events match
      case Some(e) => f(e)
      case None    => zio.ZIO.unit

  /** RAI-095: one lifecycle hook phase (runStart/runFinish), isolated from the
    * run's fate:
    *   - `plugins = None` (the default) is a literal `unit` — zero overhead: no
    *     host, no hook, no work is even described;
    *   - with a host, the bounded SYNCHRONOUS dispatch ([[PluginHost.runStart]]
    *     / [[PluginHost.runFinish]] → [[HookRunner.runHook]], one daemon thread
    *     + bounded wait per hook) runs on the blocking pool so a hook waiting
    *     out its bound never starves the executor; `runHook` never leaks
    *     exceptions (RAI-084), so the phase is failure-free by construction;
    *   - outcomes are only LOGGED: when an `events` channel exists, each
    *     outcome becomes one `Diagnostics` line (bounded by LoopEvents);
    *     without a channel the outcomes are dropped. Hooks NEVER influence the
    *     run's result or typed error channel (card non-goal).
    */
  private def hookPhase(
      run: PluginHost => List[HookOutcome]
  ): zio.UIO[Unit] =
    plugins match
      case None => zio.ZIO.unit
      case Some(host) =>
        zio.ZIO
          .blocking(zio.ZIO.succeed(run(host)))
          .flatMap: outcomes =>
            events match
              case None => zio.ZIO.unit
              case Some(e) =>
                zio.ZIO.foreachDiscard(outcomes)(o =>
                  e.diagnostics(AgentLoop.hookText(o))
                )

  /** One agent run: bounded tool rounds until a validated final text.
    *
    * `maxRounds` bounds MODEL attempts of this run and must satisfy
    * `1 <= maxRounds <= ceilings.maxAttempts` — every round is a billable
    * attempt, so exhausting rounds or attempts is a typed LocalBudgetExceeded
    * (RA-LBUDGET); configuration violations are refused BEFORE any call.
    */
  def runText(
      messages: List[RequestMessage],
      ceilings: BudgetLimits,
      maxRounds: Int
  ): ZIO[Any, RaiderError, String] =
    runLoop(messages, ceilings, maxRounds, enforceReadOnly = false)

  /** The shared body of `runText` and the plan attempts: identical validation,
    * traces and hook seams; `enforceReadOnly` is the ONLY difference and is
    * `false` for ordinary runs (RAI-107: plan enforcement never leaks into the
    * execution mode).
    */
  private def runLoop(
      messages: List[RequestMessage],
      ceilings: BudgetLimits,
      maxRounds: Int,
      enforceReadOnly: Boolean
  ): ZIO[Any, RaiderError, String] =
    validateRunArgs(messages, ceilings, maxRounds) *>
      trace(
        raider.core.trace.TraceEvent
          .RunStarted(AgentLoop.now(), model, messages.size)
      ) *>
      // RAI-095: lifecycle hooks run AFTER RunStarted; the read-only
      // metaJson identifies the run — conversation content never crosses
      // the SPI seam
      hookPhase(_.runStart(hookMetaJson)) *>
      rounds(messages, ceilings, maxRounds, 1, enforceReadOnly).tapBoth(
        e =>
          // RAI-095: runFinish BEFORE the RunFailed trace emission
          hookPhase(_.runFinish(hookMetaJson)) *>
            trace(
              raider.core.trace.TraceEvent
                .RunFailed(AgentLoop.now(), e.code, e.detail.take(160))
            ),
        t =>
          // RAI-095: runFinish BEFORE the RunFinished trace emission
          hookPhase(_.runFinish(hookMetaJson)) *>
            trace(
              raider.core.trace.TraceEvent
                .RunFinished(AgentLoop.now(), "Succeeded", t.take(160))
            )
      )

  /** RAI-107 (ADR-028 §2): the bounded PLAN phase.
    *
    * The phase is an adaptation of the ordinary loop, not a second runtime:
    *   - the model sees ONLY the `RecoveryClass.ReadOnly` tools of the bound
    *     [[PlanMode]] (surface narrowing), and a Mutating/Idempotent call
    *     emitted anyway is a typed policy denial (`RA-TOOLDENY`) fed back as a
    *     structured tool message — the loop stays alive (PLAN-01);
    *   - the final answer must parse into a [[PlanProposal]]; a malformed
    *     candidate triggers a bounded repair round (a `plan_rejected`
    *     structured message; quality-gates §7.11 — every repair is a fresh
    *     billable loop, bounded by `PlanMode.maxRepairAttempts`); exhausting
    *     the budget is the typed [[PlanPhaseError.PlanRejected]] and NO
    *     artifact is written (PLAN-02);
    *   - an accepted proposal is persisted ATOMICALLY (tmp+move) by
    *     [[PlanStore]] next to the run artifacts. Execution never starts here:
    *     the transition to execution is the explicit approval edge owned by
    *     [[PlanSession]] (PLAN-03);
    *   - the phase lives in the common root budget (every attempt goes through
    *     the same admission brackets) and is cancellable like any run (bounded
    *     cleanup, §7.4/7.5).
    *
    * Requires `planMode`; without it the call is a typed refusal before any
    * spend (RA-CFG: plan mode is off by default).
    */
  def runPlan(
      messages: List[RequestMessage],
      ceilings: BudgetLimits,
      maxRounds: Int
  ): ZIO[Any, PlanPhaseError, PlanProposal] =
    planMode match
      case None =>
        ZIO.fail(
          PlanPhaseError.Underlying(
            RaiderError.InputValidation(
              "plan mode is off: build the AgentLoop with a PlanMode binding (ADR-028) to run a plan phase"
            )
          )
        )
      case Some(pm) =>
        if pm.maxRepairAttempts < 0 then
          ZIO.fail(
            PlanPhaseError.Underlying(
              RaiderError.Configuration(
                s"maxRepairAttempts must be >= 0, got ${pm.maxRepairAttempts}"
              )
            )
          )
        else if messages.isEmpty then
          ZIO.fail(
            PlanPhaseError.Underlying(
              RaiderError.InputValidation(
                "agent loop needs at least one message"
              )
            )
          )
        else
          // RAI-153 (ADR-032 §1): the plan phase is a REAL phase boundary the
          // runtime knows (RAI-107 §2: the bounded plan phase, repairs are
          // attempts INSIDE it) — one `phase_started` before the first attempt
          // and one `phase_finished` on EVERY exit (success, typed rejection,
          // cancellation; the onExit finalizer runs uninterruptibly). Recovery
          // is the LINEAGE taxonomy's ReadOnly: the plan phase enforces the
          // read-only toolset (PLAN-01) — the lineage-layer enum, not the
          // `raider.core` tool-axis enum. The ordinary `runText` mode emits NO
          // phases: the runtime tracks no further phase semantics (card
          // non-goal — none are invented).
          ev(
            _.phaseStarted(
              AgentLoop.planPhaseName,
              raider.runtime.lineage.model.RecoveryClass.ReadOnly,
              None
            )
          ) *>
            planAttempt(
              messages,
              ceilings,
              maxRounds,
              attemptNo = 1,
              lastAttemptNo = pm.maxRepairAttempts + 1
            ).onExit(_ => ev(_.phaseFinished(AgentLoop.planPhaseName)))

  /** One bounded plan attempt: the read-only loop up to the final answer, then
    * structural validation; malformed → repair or the typed rejection.
    */
  private def planAttempt(
      messages: List[RequestMessage],
      ceilings: BudgetLimits,
      maxRounds: Int,
      attemptNo: Int,
      lastAttemptNo: Int
  ): ZIO[Any, PlanPhaseError, PlanProposal] =
    ev(_.diagnostics(s"plan attempt $attemptNo/$lastAttemptNo started")) *>
      runLoop(messages, ceilings, maxRounds, enforceReadOnly = true).foldZIO(
        err =>
          err match
            case RaiderError.OutputValidation(what) =>
              // empty/marker final answer: malformed candidate, repair like any
              // other rejected shape (bounded, billable — §7.11)
              repairOrReject(
                messages,
                ceilings,
                maxRounds,
                candidate = None,
                reason = what,
                attemptNo,
                lastAttemptNo
              )
            case other => ZIO.fail(PlanPhaseError.Underlying(other)),
        candidate =>
          PlanProposal.parse(candidate) match
            case Left(reason) =>
              repairOrReject(
                messages,
                ceilings,
                maxRounds,
                candidate = Some(candidate),
                reason,
                attemptNo,
                lastAttemptNo
              )
            case Right(proposal) =>
              artifactsDir match
                case None =>
                  ZIO.fail(
                    PlanPhaseError.Underlying(
                      RaiderError.Configuration(
                        "plan mode binding lost its artifacts directory"
                      )
                    )
                  )
                case Some(dir) =>
                  PlanStore
                    .write(dir, root, proposal)
                    .foldZIO(
                      e => ZIO.fail(PlanPhaseError.Underlying(e)),
                      path =>
                        // human-readable plan line ONLY via Diagnostics
                        // (ADR-024 frozen family — no new wire event)
                        ev(
                          _.diagnostics(
                            s"plan proposal accepted: ${proposal.title} " +
                              s"(${proposal.steps.size} step(s)) -> ${path.getFileName}"
                          )
                        ) *> ZIO.succeed(proposal)
                    )
      )

  /** The bounded repair edge (§7.11): feed the rejected candidate and a
    * structured `plan_rejected` reason back to the model and retry ONCE LESS
    * than before; exhausting the budget is the typed `PlanRejected` and the
    * artifact is never written.
    */
  private def repairOrReject(
      messages: List[RequestMessage],
      ceilings: BudgetLimits,
      maxRounds: Int,
      candidate: Option[String],
      reason: String,
      attemptNo: Int,
      lastAttemptNo: Int
  ): ZIO[Any, PlanPhaseError, PlanProposal] =
    val boundedReason = reason.take(PlanProposal.MaxReasonChars)
    if attemptNo >= lastAttemptNo then
      ev(
        _.diagnostics(
          s"plan rejected: $boundedReason (attempts: $lastAttemptNo)"
        )
      ) *>
        ZIO.fail(
          PlanPhaseError.PlanRejected(boundedReason, lastAttemptNo)
        )
    else
      ev(
        _.diagnostics(
          s"plan attempt $attemptNo/$lastAttemptNo rejected: $boundedReason; " +
            "one bounded repair follows"
        )
      ) *>
        planAttempt(
          messages ++
            candidate
              .filter(_.trim.nonEmpty)
              .map(t => RequestMessage(AgentLoop.AssistantRole, t)) :+
            RequestMessage(
              AgentLoop.UserRole,
              s"""{"error":"plan_rejected","code":"RA-PLAN-REJECTED","reason":${boundedReason.toJson}}""" +
                s" Re-emit the complete plan as one JSON object: ${PlanProposal.SchemaHint}."
            ),
          ceilings,
          maxRounds,
          attemptNo + 1,
          lastAttemptNo
        )

  private def artifactsDir: Option[java.nio.file.Path] =
    planMode.map(_.artifactsDir)

  /** RAI-095: the read-only lifecycle-hook input — run identity only (model,
    * root), as one JSON document. Never conversation content: the hook SPI
    * hands over exactly one read-only string, and the loop keeps the envelope
    * minimal by construction.
    */
  private def hookMetaJson: String =
    s"{\"model\":${model.toJson},\"root\":${root.value.toJson}}"

  /** Extracted argument validation of a run (identical rules for `runText` and
    * the plan phase): configuration violations are refused BEFORE any spend.
    */
  private def validateRunArgs(
      messages: List[RequestMessage],
      ceilings: BudgetLimits,
      maxRounds: Int
  ): ZIO[Any, RaiderError, Unit] =
    if messages.isEmpty then
      ZIO.fail(
        RaiderError.InputValidation("agent loop needs at least one message")
      )
    else if ceilings.maxAttempts < 1 then
      ZIO.fail(
        RaiderError.Configuration(
          s"maxAttempts must be >= 1, got ${ceilings.maxAttempts}"
        )
      )
    else if maxRounds < 1 then
      ZIO.fail(
        RaiderError.Configuration(s"maxRounds must be >= 1, got $maxRounds")
      )
    else if maxRounds > ceilings.maxAttempts then
      ZIO.fail(
        RaiderError.Configuration(
          s"maxRounds=$maxRounds exceeds ceilings.maxAttempts=${ceilings.maxAttempts}: " +
            "every round is a billable attempt"
        )
      )
    else ZIO.unit

  private def rounds(
      messages: List[RequestMessage],
      ceilings: BudgetLimits,
      maxRounds: Int,
      round: Int,
      enforceReadOnly: Boolean
  ): ZIO[Any, RaiderError, String] =
    if round > maxRounds then
      ZIO.fail(
        RaiderError.LocalBudgetExceeded(
          s"maxRounds=$maxRounds exhausted with pending tool calls " +
            s"(root ${root.value})"
        )
      )
    else
      trace(
        raider.core.trace.TraceEvent.RoundStarted(AgentLoop.now(), round)
      ) *>
        // RoundStarted equivalent (RAI-058): no dedicated payload case in the
        // frozen family — Diagnostics text, bounded by LoopEvents
        ev(_.diagnostics(s"round $round started")) *>
        attempt(messages, round, enforceReadOnly).flatMap:
          case AttemptResult.Final(text) =>
            trace(
              raider.core.trace.TraceEvent
                .TextReceived(AgentLoop.now(), round, text.take(200))
            ) *>
              // TextReceived equivalent (RAI-058)
              ev(_.diagnostics(s"round $round text: ${text.take(200)}")) *>
              ZIO.fromEither(validateFinal(text)).as(text)
          case AttemptResult.ToolRound(text, calls) =>
            for
              // §6 default: serialized dispatch in emission order
              results <- ZIO.foreach(calls.toList)(c =>
                dispatch(c, round, enforceReadOnly)
              )
              assistant =
                if text.trim.isEmpty then Nil
                else List(RequestMessage(AgentLoop.AssistantRole, text))
              answer <- rounds(
                messages ++ assistant ++ results,
                ceilings,
                maxRounds,
                round + 1,
                enforceReadOnly
              )
            yield answer

  private def attempt(
      messages: List[RequestMessage],
      round: Int,
      enforceReadOnly: Boolean
  ): ZIO[Any, RaiderError, AttemptResult] =
    // RAI-107: in the plan phase the model is offered ONLY the ReadOnly
    // subset of the bound PlanMode (surface narrowing by RecoveryClass)
    val advertised =
      if enforceReadOnly then planMode.fold(tools)(_.readOnlyTools) else tools
    val toolDefs = advertised.names.map { n =>
      advertised.lookup(n).map { t =>
        raider.core.ToolDefinition(n, t.description, t.parametersJsonSchema)
      }
    }.flatten
    val allMessages = systemPrompt match
      case Some(sys) => RequestMessage("system", sys) +: messages
      case None      => messages
    val request =
      ModelRequest(model, allMessages, AgentLoop.MaxOutputTokens, toolDefs)
    // Observed wall time of the stream pass (honest, never invented)
    val startedAt = System.currentTimeMillis()
    trace(
      raider.core.trace.TraceEvent
        .ModelCall(AgentLoop.now(), round, messages.size)
    ) *>
      // RELIABLE, bounded (RAI-058): the run is about to wait for the Model
      // slot; a publish Backpressure is absorbed and the run continues
      ev(_.waitingSlot) *>
      admission.withSlot(SlotKind.Model, root, attemptEstimate, None):
        ev(_.modelCallStarted(round, model, messages.size)) *>
          ZIO
            .scoped(collect(backend.stream(request)))
            .map(r => (r, MicroUsd(0)))
            // Finished on success AND on failure: the call has terminated;
            // usage is honestly unknown (no cost is known at this level)
            .tapBoth(
              _ =>
                ev(
                  _.modelCallFinished(
                    round,
                    System.currentTimeMillis() - startedAt
                  )
                ),
              _ =>
                ev(
                  _.modelCallFinished(
                    round,
                    System.currentTimeMillis() - startedAt
                  )
                )
            )

  private def collect(
      events: ZStream[Scope, RaiderError, ModelEvent]
  ): ZIO[Scope, RaiderError, AttemptResult] =
    events
      .runFold(AgentAttemptState.empty):
        case (st, ModelEvent.TextDelta(_, text)) =>
          st.copy(text = st.text + text)
        case (st, ModelEvent.ToolCallReady(_, call, name, args)) =>
          st.copy(calls = st.calls :+ ToolCall(name, call, args))
        case (st, ModelEvent.Failed(_, err)) => st.copy(failure = Some(err))
        case (st, ModelEvent.Finished(_, _)) => st.copy(finished = true)
        case (st, _)                         => st
      .flatMap: st =>
        st.failure match
          case Some(err) => ZIO.fail(err)
          case None if !st.finished =>
            ZIO.fail(
              RaiderError
                .StreamProtocol("backend stream ended without Finished")
            )
          case None if st.calls.isEmpty =>
            ZIO.succeed(AttemptResult.Final(st.text))
          case _ =>
            ZIO.succeed(AttemptResult.ToolRound(st.text, st.calls))

  /** One tool call → the tool-role message matched back by tool_call_id (§8).
    * NOTE: fields are destructured ONCE — Scala 3.9 chokes (E046 cyclic) on
    * `x.call.y` member-select chains here, so no such chain survives.
    */
  private def dispatch(
      toolCall: ToolCall,
      round: Int,
      enforceReadOnly: Boolean
  ): ZIO[Any, RaiderError, RequestMessage] =
    // NOTE: one select per val — Scala 3.9 chokes (E046 cyclic) on
    // `x.call.y` member-select chains in this file, so none survive.
    val callName = extractName(toolCall)
    val callId: ToolCallId = extractCallId(toolCall)
    val callIdStr = callId.value
    val argsJson = extractArgs(toolCall)
    tools.lookup(callName) match
      case None =>
        // structured feedback with bounded attempts — never a silent ignore
        trace(
          raider.core.trace.TraceEvent.ToolCallRefused(
            AgentLoop.now(),
            round,
            callName,
            callIdStr,
            "unknown_tool"
          )
        ) *>
          // Documented refusal outcome (RAI-058): ToolFinished(Failed)
          // WITHOUT a preceding ToolStarted — the tool never started
          ev(
            _.toolFinished(
              callIdStr,
              callName,
              ToolOutcome.Failed(LoopEvents.refusedUnknownTool)
            )
          ) *>
          ZIO.succeed:
            val nameJson = callName.toJson
            toolMessage(
              callId,
              s"""{"error":"unknown_tool","name":$nameJson}"""
            )
      case Some(tool) =>
        if enforceReadOnly && tool.recovery != RecoveryClass.ReadOnly then
          // RAI-107 (PLAN-01): typed policy denial in the plan phase — the
          // tool is KNOWN but not ReadOnly; the loop stays alive and the
          // denial travels back as structured feedback (RA-TOOLDENY)
          planDenial(callName, callId, round)
        else
          Json.decoder.decodeJson(argsJson) match
            case Left(err) =>
              val errJson = err.toJson
              // Documented refusal outcome (RAI-058), symmetric to unknown tool
              ev(
                _.toolFinished(
                  callIdStr,
                  callName,
                  ToolOutcome.Failed(LoopEvents.refusedInvalidArgs)
                )
              ) *>
                ZIO.succeed(
                  toolMessage(
                    callId,
                    s"""{"error":"invalid_arguments","detail":$errJson}"""
                  )
                )
            case Right(_) =>
              trace(
                raider.core.trace.TraceEvent.ToolCallStarted(
                  AgentLoop.now(),
                  round,
                  tool.name,
                  callIdStr,
                  argsJson.take(200)
                )
              ) *>
                // RELIABLE, bounded (RAI-058): about to wait for the Tool slot
                ev(_.waitingSlot) *>
                admission
                  .withSlotFree(SlotKind.Tool, root, None):
                    ZIO.scoped:
                      ev(_.toolStarted(callIdStr, tool.name, argsJson)) *>
                        // Interruption during the call bypasses tapBoth AND
                        // catchAllCause alike (ZIO interruption is not a catchable
                        // cause) — the honest Cancelled outcome is therefore
                        // registered as a finalizer: onInterrupt runs it on the
                        // interrupted fiber before it dies, uninterruptibly, so
                        // the emission completes and is never lost. Scoped to the
                        // call: a refusal before ToolStarted emits nothing.
                        (tool
                          .invoke(argsJson)
                          .timeout(Duration.fromMillis(tool.timeoutMs))
                          .flatMap:
                            case None =>
                              ZIO.fail(
                                RaiderError.DeadlineExceeded(
                                  s"tool '${tool.name}' exceeded timeoutMs=${tool.timeoutMs}"
                                )
                              )
                            case Some(output) => ZIO.succeed(output)
                          )
                          .onInterrupt(
                            ZIO.uninterruptible(
                              ev(
                                _.toolFinished(
                                  callIdStr,
                                  tool.name,
                                  ToolOutcome.Cancelled
                                )
                              )
                            )
                          )
                  .flatMap(output => deliver(toolCall, tool, output))
                  // ToolFinished on EVERY definitive exit, including failure:
                  // timeout → Timeout, cancellation refusal → Cancelled,
                  // anything else → Failed(reason) (RAI-058)
                  .tapBoth(
                    err =>
                      ev(
                        _.toolFinished(
                          callIdStr,
                          tool.name,
                          AgentLoop.toolOutcomeOf(err)
                        )
                      ),
                    _ =>
                      ev(
                        _.toolFinished(
                          callIdStr,
                          tool.name,
                          ToolOutcome.Succeeded
                        )
                      )
                  )
                  .tap(resultMsg =>
                    trace(
                      raider.core.trace.TraceEvent.ToolCallFinished(
                        AgentLoop.now(),
                        round,
                        tool.name,
                        callIdStr,
                        resultMsg.content.take(300),
                        success = true
                      )
                    )
                  )

  /** RAI-107 (PLAN-01): the typed plan-phase policy denial. Mirrors the
    * documented refusal shape (ToolFinished(Failed) WITHOUT a preceding
    * ToolStarted — the tool never started) and feeds the model a structured
    * `tool_denied` message carrying the frozen `RA-TOOLDENY` code. The loop
    * stays alive: the denial is feedback, never a run failure.
    */
  private def planDenial(
      callName: String,
      callId: ToolCallId,
      round: Int
  ): ZIO[Any, RaiderError, RequestMessage] =
    trace(
      raider.core.trace.TraceEvent.ToolCallRefused(
        AgentLoop.now(),
        round,
        callName,
        callId.value,
        "plan_mode_read_only"
      )
    ) *>
      ev(
        _.toolFinished(
          callId.value,
          callName,
          ToolOutcome.Failed(LoopEvents.refusedPlanReadOnly)
        )
      ) *>
      ZIO.succeed:
        val nameJson = callName.toJson
        toolMessage(
          callId,
          s"""{"error":"tool_denied","code":"RA-TOOLDENY","name":$nameJson,""" +
            """"reason":"plan phase allows read-only tools only"}"""
        )

  /** A definitive tool result must be valid JSON to enter the message envelope;
    * a tool that returns garbage is a ToolFailed defect, not model feedback
    * (defects are never masked as conversational content, §14).
    */
  private def deliver(
      toolCall: ToolCall,
      tool: raider.core.Tool,
      output: String
  ): ZIO[Any, RaiderError, RequestMessage] =
    val callId: ToolCallId = extractCallId(toolCall)
    Json.decoder.decodeJson(output) match
      case Left(_) =>
        ZIO.fail(
          RaiderError.ToolFailed(
            s"tool '${tool.name}' returned non-JSON output (${output.length} chars)"
          )
        )
      case Right(_) => ZIO.succeed(toolMessage(callId, output))

  private def toolMessage(
      callId: ToolCallId,
      payloadJson: String
  ): RequestMessage =
    val id = callId.value
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
      Left(
        RaiderError.OutputValidation(
          "final answer contains the reserved internal marker"
        )
      )
    else Right(())

end AgentLoop

object AgentLoop:

  val ToolRole: String = "tool"
  val AssistantRole: String = "assistant"
  val UserRole: String = "user"

  /** RAI-153: the wire name of the plan phase (RAI-107 §2 semantics; the same
    * label the plan-phase job carries). `phase_finished` closes it by name.
    */
  val planPhaseName: String = "plan"

  /** Reserved sentinel the loop never injects anywhere; its presence in a final
    * answer is an output-validation defect (marker-free contract).
    */
  val InternalMarker: String = "⟦raider:internal⟧"

  private val counter = AtomicLong(0)

  /** Fresh root for a standalone run (per-attempt accounting parity with
    * Runner.runText; per-job root correlation is a facade obligation).
    */
  def newRoot(): RootId = RootId(s"loop_${counter.incrementAndGet()}")

  private[loop] val MaxOutputTokens =
    4096 // reasoning models burn tokens on thinking
  private[loop] val MaxToolResultChars = 2000 // truncate large tool results

  private def now(): String = java.time.Instant.now().toString

  /** Maps a DEFINITIVE tool dispatch failure to its observed outcome (RAI-058):
    * `DeadlineExceeded` (tool timeout) → `Timeout`; a post-permit root
    * cancellation refusal → `Cancelled`; everything else → `Failed(reason)`
    * with the typed error detail (bounded). Unclear stays unclear: no outcome
    * is invented beyond the typed error itself.
    */
  private[loop] def toolOutcomeOf(err: RaiderError): ToolOutcome =
    err match
      case _: RaiderError.DeadlineExceeded => ToolOutcome.Timeout
      case _: RaiderError.Cancelled        => ToolOutcome.Cancelled
      case other =>
        ToolOutcome.Failed(other.detail.take(LoopEvents.maxReasonChars))

  /** RAI-095: one [[HookOutcome]] → one bounded Diagnostics line
    * ([[LoopEvents.diagnostics]] truncates to 2000 chars). Completed hooks
    * report their verbatim output; failures report the typed runner detail.
    */
  private def hookText(o: HookOutcome): String = o match
    case HookOutcome.Completed(name, outputJson) =>
      s"plugin hook '$name' completed: $outputJson"
    case HookOutcome.Failed(name, failure) =>
      s"plugin hook '$name' failed: ${failure.detail}"

end AgentLoop

private[loop] sealed trait AttemptResult

private[loop] object AttemptResult:
  final case class Final(text: String) extends AttemptResult

  final case class ToolRound(text: String, calls: Vector[ToolCall])
      extends AttemptResult

private[loop] final case class AgentAttemptState(
    text: String,
    calls: Vector[ToolCall],
    failure: Option[RaiderError],
    finished: Boolean
)

private[loop] object AgentAttemptState:

  val empty: AgentAttemptState =
    AgentAttemptState("", Vector.empty, None, false)
