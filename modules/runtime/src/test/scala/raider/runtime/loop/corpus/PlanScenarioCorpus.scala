package raider.runtime.loop.corpus

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*

import raider.core.*
import raider.runtime.admission.{Admission, SlotKind}
import raider.runtime.budget.{
  BudgetLedger,
  BudgetLimits,
  CostEstimate,
  MicroUsd,
  UsageReport
}
import raider.runtime.jobs.JobManager
import raider.runtime.loop.{
  AgentLoop,
  PlanApprovalError,
  PlanMode,
  PlanPhaseError,
  PlanProposal,
  PlanSession,
  PlanStore
}
import raider.testkit.ScriptedSequenceBackend
import zio.json.*
import zio.stream.ZStream
import zio.test.*
import zio.{Duration as ZDuration, Exit as ZExit, Promise, Ref, Scope, UIO, ZIO}

/** RAI-114: deterministic scripted scenario corpus for the plan mode
  * (RAI-107/ADR-028), test scope only. The corpus collects SCENARIOS AS DATA:
  * each [[Scenario]] pins the tools (by the frozen `RecoveryClass`), the
  * scripted backend calls of the plan phase, the session protocol, and the
  * EXPECTED typed verdict — exact typed values (attempt counts, codes, ledger
  * micros), never "it did not throw".
  *
  * A future eval profile can consume this corpus as data: [[jsonlLines]] /
  * [[parseJsonl]] serialize every scenario byte-for-byte reproducibly (no wall
  * clock, no RNG), [[exec]] runs one scenario against a fresh loop/session
  * stack (unique TempDir, own admission/manager/backend — scenarios share
  * nothing and may run concurrently), and [[verify]] checks the pinned verdict.
  *
  * Non-goals (card): this suite does NOT weaken or replace the PLAN-01/02/03
  * acceptance specs of RAI-107; the protected corpus under `contracts/eval/`
  * and the gate registry are untouched.
  */
object PlanScenarioCorpus:

  /** A corpus tool: name + frozen recovery class is the entire identity the
    * plan-mode enforcement keys on ([[PlanMode.of]] derives the read-only
    * subset from exactly this). Execution counters belong to the runner.
    */
  final case class ToolSpec(name: String, recovery: RecoveryClass)

  /** One scripted backend call of the plan phase: either a tool round (the
    * model asks for a tool, the loop answers and calls the model again) or the
    * final text answer (a plan JSON — or malformed garbage for the repair
    * scenarios).
    */
  enum PlanCall:
    case ToolRound(callId: String, tool: String, argumentsJson: String)
    case FinalText(text: String)

  /** Session protocol the runner drives for the scenario:
    *   - [[PlanPhase]] — startPlan + awaitPlan, nothing else;
    *   - [[Approve]] — plan phase, then ONE approval (exactly-once denied on a
    *     second attempt), execution finishes with `executionFinalText`;
    *   - [[CancelThenReplan]] — cancel the LIVE plan phase inside its model
    *     dispatch (bounded cleanup), typed deny of approve-after-cancel, then a
    *     repeated plan from `Cancelled`.
    */
  enum Protocol:
    case PlanPhase
    case Approve(executionFinalText: String)
    case CancelThenReplan

  /** Pinned typed verdicts. One variant per protocol; the (Protocol, Verdict)
    * pairing is enforced by [[verify]].
    */
  enum Verdict:

    // Plan phase accepted: exact proposal, exact model-call count, exact
    // tool surface advertised to the model every round, exact per-tool
    // execution counts (a tool absent from the map must be 0), exact
    // policy-denial and repair-feedback message counts, exact artifact and
    // lineage shape.
    case Accepted(
        title: String,
        steps: List[String],
        planModelCalls: Int,
        advertisedTools: List[String],
        toolExecs: Map[String, Int],
        deniedToolMessages: Int,
        repairFeedbackRequests: Int,
        artifactEntries: Int,
        sessionLabels: List[String]
    )

    // Plan phase typed-rejected after the repair budget: exact code, exact
    // attempt count, bounded reason pin, nothing persisted.
    case Rejected(
        code: String,
        attempts: Int,
        reasonContains: String,
        artifactEntries: Int
    )

    // Approval transition: exactly one execution job, exact answer, exact
    // lineage labels before/after, the plan as leading context message,
    // second approval typed-denied (detail prefix pin).
    case ApprovedOnce(
        proposalTitle: String,
        proposalSteps: List[String],
        execAnswer: String,
        labelsAfterPlan: List[String],
        labelsAfterApprove: List[String],
        leadingContextContains: String,
        secondApproveDetailPrefix: String
    )

    // Bounded cancel cleanup: exact ledger (reserved drained, Uncertain
    // kept), no permit leak, typed cancel outcome, cancel != approval
    // (exact detail), nothing new dispatched after the cancel, the repeated
    // plan succeeds and persists, lineage keeps both plan jobs, no ghosts.
    case CancelledThenReplanned(
        cancelJobErrorCode: String,
        reservedActiveMicros: Long,
        uncertainTotalMicros: Long,
        freeModelSlots: Int,
        freeToolSlots: Int,
        approveAfterCancelDetail: String,
        requestsAfterCancel: Int,
        replanTitle: String,
        replanSteps: List[String],
        labelsFinal: List[String],
        artifactAfterReplan: Boolean
    )

  end Verdict

  /** One recorded scripted-backend scenario: pure data. `estimateMicros` pins
    * the attempt price (Some(n) = `CostEstimate.Priced`, None = `Unknown`); the
    * cancel scenario needs a priced estimate so the Uncertain-charge invariant
    * is observable.
    */
  final case class Scenario(
      id: String,
      description: String,
      tools: List[ToolSpec],
      maxRepairAttempts: Int,
      maxRounds: Int,
      userPrompt: String,
      estimateMicros: Option[Long],
      planCalls: List[PlanCall],
      protocol: Protocol,
      verdict: Verdict
  )

  // ---------- zio.json codecs: the corpus is data for the future eval profile.

  given JsonEncoder[RecoveryClass] = JsonEncoder[String].contramap(_.toString)

  given JsonDecoder[RecoveryClass] =
    JsonDecoder[String].mapOrFail {
      case "ReadOnly"   => Right(RecoveryClass.ReadOnly)
      case "Idempotent" => Right(RecoveryClass.Idempotent)
      case "Mutating"   => Right(RecoveryClass.Mutating)
      case other        => Left(s"unknown recovery class: $other")
    }

  given JsonEncoder[ToolSpec] = DeriveJsonEncoder.gen[ToolSpec]
  given JsonDecoder[ToolSpec] = DeriveJsonDecoder.gen[ToolSpec]
  given JsonEncoder[PlanCall] = DeriveJsonEncoder.gen[PlanCall]
  given JsonDecoder[PlanCall] = DeriveJsonDecoder.gen[PlanCall]
  given JsonEncoder[Protocol] = DeriveJsonEncoder.gen[Protocol]
  given JsonDecoder[Protocol] = DeriveJsonDecoder.gen[Protocol]
  given JsonEncoder[Verdict] = DeriveJsonEncoder.gen[Verdict]
  given JsonDecoder[Verdict] = DeriveJsonDecoder.gen[Verdict]
  given JsonEncoder[Scenario] = DeriveJsonEncoder.gen[Scenario]
  given JsonDecoder[Scenario] = DeriveJsonDecoder.gen[Scenario]

  /** THE corpus (PEVAL-01: six scenarios, one per ADR-028 §4 behavior).
    */
  val scenarios: Vector[Scenario] = Vector(
    Scenario(
      id = "s1-ro-work",
      description =
        "plan phase executes a ReadOnly tool for real and accepts the plan",
      tools = List(ToolSpec("lookup", RecoveryClass.ReadOnly)),
      maxRepairAttempts = 2,
      maxRounds = 8,
      userPrompt = "plan the inventory sweep",
      estimateMicros = None,
      planCalls = List(
        PlanCall.ToolRound("s1-c1", "lookup", "{}"),
        PlanCall.FinalText(
          """{"title":"Inventory sweep","steps":["list files","summarize"]}"""
        )
      ),
      protocol = Protocol.PlanPhase,
      verdict = Verdict.Accepted(
        title = "Inventory sweep",
        steps = List("list files", "summarize"),
        planModelCalls = 2,
        advertisedTools = List("lookup"),
        toolExecs = Map("lookup" -> 1),
        deniedToolMessages = 0,
        repairFeedbackRequests = 0,
        artifactEntries = 1,
        sessionLabels = List("plan")
      )
    ),
    Scenario(
      id = "s2-mutating-denied",
      description =
        "Mutating and Idempotent attempts get typed policy denials and never execute; the ReadOnly tool still executes",
      tools = List(
        ToolSpec("write_file", RecoveryClass.Mutating),
        ToolSpec("touch_file", RecoveryClass.Idempotent),
        ToolSpec("lookup", RecoveryClass.ReadOnly)
      ),
      maxRepairAttempts = 2,
      maxRounds = 8,
      userPrompt = "plan the sandbox audit",
      estimateMicros = None,
      planCalls = List(
        PlanCall.ToolRound("s2-c1", "write_file", "1"),
        PlanCall.ToolRound("s2-c2", "touch_file", "2"),
        PlanCall.ToolRound("s2-c3", "lookup", "3"),
        PlanCall.FinalText(
          """{"title":"Sandbox audit","steps":["check perms"]}"""
        )
      ),
      protocol = Protocol.PlanPhase,
      verdict = Verdict.Accepted(
        title = "Sandbox audit",
        steps = List("check perms"),
        planModelCalls = 4,
        advertisedTools = List("lookup"),
        toolExecs = Map("write_file" -> 0, "touch_file" -> 0, "lookup" -> 1),
        deniedToolMessages = 2,
        repairFeedbackRequests = 0,
        artifactEntries = 1,
        sessionLabels = List("plan")
      )
    ),
    Scenario(
      id = "s3-malformed-repaired",
      description =
        "malformed plan -> structured repair feedback -> repaired plan accepted and persisted",
      tools = List.empty,
      maxRepairAttempts = 2,
      maxRounds = 8,
      userPrompt = "plan something",
      estimateMicros = None,
      planCalls = List(
        PlanCall.FinalText("I will figure something out"),
        PlanCall.FinalText(
          """{"title":"Repaired roadmap","steps":["step one"]}"""
        )
      ),
      protocol = Protocol.PlanPhase,
      verdict = Verdict.Accepted(
        title = "Repaired roadmap",
        steps = List("step one"),
        planModelCalls = 2,
        advertisedTools = List.empty,
        toolExecs = Map.empty,
        deniedToolMessages = 0,
        repairFeedbackRequests = 1,
        artifactEntries = 1,
        sessionLabels = List("plan")
      )
    ),
    Scenario(
      id = "s4-malformed-exhausted",
      description =
        "malformed plan after the repair budget -> typed PlanRejected, artifact never written",
      tools = List.empty,
      maxRepairAttempts = 1,
      maxRounds = 8,
      userPrompt = "plan something",
      estimateMicros = None,
      planCalls = List(
        PlanCall.FinalText("nope one"),
        PlanCall.FinalText("nope two")
      ),
      protocol = Protocol.PlanPhase,
      verdict = Verdict.Rejected(
        code = "RA-PLAN-REJECTED",
        attempts = 2,
        reasonContains = "plan is not valid JSON",
        artifactEntries = 0
      )
    ),
    Scenario(
      id = "s5-approve-exactly-once",
      description =
        "approval creates exactly one execution job with the plan as context; the second approval is typed-denied",
      tools = List.empty,
      maxRepairAttempts = 2,
      maxRounds = 8,
      userPrompt = "plan the feature",
      estimateMicros = None,
      planCalls = List(
        PlanCall.FinalText(
          """{"title":"Ship the feature","steps":["read the file","write the test"]}"""
        )
      ),
      protocol = Protocol.Approve(executionFinalText = "\"done\""),
      verdict = Verdict.ApprovedOnce(
        proposalTitle = "Ship the feature",
        proposalSteps = List("read the file", "write the test"),
        execAnswer = "\"done\"",
        labelsAfterPlan = List("plan"),
        labelsAfterApprove = List("plan", "plan-exec"),
        leadingContextContains = "Ship the feature",
        secondApproveDetailPrefix = "plan already approved"
      )
    ),
    Scenario(
      id = "s6-cancel-replan",
      description =
        "cancel of a live plan phase: bounded cleanup (ledger/slots/state), cancel != approval, repeated plan works",
      tools = List.empty,
      maxRepairAttempts = 2,
      maxRounds = 8,
      userPrompt = "plan the interruptible thing",
      estimateMicros = Some(250L),
      // Two IDENTICAL scripts on purpose: whichever of the cancel race's
      // outcomes materializes, the repeated plan replays a valid plan and the
      // extra script keeps the sequence never exhausted (flake-free).
      planCalls = List(
        PlanCall.FinalText(
          """{"title":"Interruptible plan","steps":["step a","step b"]}"""
        ),
        PlanCall.FinalText(
          """{"title":"Interruptible plan","steps":["step a","step b"]}"""
        )
      ),
      protocol = Protocol.CancelThenReplan,
      verdict = Verdict.CancelledThenReplanned(
        cancelJobErrorCode = "RA-CANCEL",
        reservedActiveMicros = 0L,
        uncertainTotalMicros = 250L,
        freeModelSlots = 1,
        freeToolSlots = 1,
        approveAfterCancelDetail =
          "plan phase was cancelled: cancel is not approval",
        requestsAfterCancel = 1,
        replanTitle = "Interruptible plan",
        replanSteps = List("step a", "step b"),
        labelsFinal = List("plan", "plan"),
        artifactAfterReplan = true
      )
    )
  )

  /** Canonical JSONL: one scenario per line (no trailing newline inside the
    * returned lines). Deterministic: the same corpus always produces the same
    * bytes.
    */
  def jsonlLines(
      scenarios: Vector[Scenario] = PlanScenarioCorpus.scenarios
  ): Vector[String] =
    scenarios.map(_.toJson)

  /** Strict inverse of [[jsonlLines]]: unknown recovery classes, unknown
    * verdict/protocol constructors and malformed JSON are decode failures.
    */
  def parseJsonl(lines: Vector[String]): Either[String, Vector[Scenario]] =
    lines.zipWithIndex.foldLeft[Either[String, Vector[Scenario]]](
      Right(Vector.empty)
    ) { case (acc, (line, idx)) =>
      acc.flatMap { parsed =>
        val trimmed = line.trim
        if trimmed.isEmpty then Right(parsed)
        else
          trimmed
            .fromJson[Scenario]
            .left
            .map(err => s"line ${idx + 1}: $err")
            .map(parsed :+ _)
      }
    }

  /** SHA-256 of the canonical corpus document (evidence-grade identity). */
  def sha256Hex(lines: Vector[String]): String =
    val bytes =
      (if lines.isEmpty then "" else lines.mkString("", "\n", "\n"))
        .getBytes(StandardCharsets.UTF_8)
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  // ---------- runner.

  /** Everything the verifier may pin, observed by actually executing one
    * scenario. Superset by design: each protocol fills the fields it can, the
    * rest stays None.
    */
  final case class Observed(
      scenarioId: String,
      dir: Path,
      planOutcome: Either[PlanPhaseError, PlanProposal],
      planRequests: Vector[ModelRequest],
      requestsFinal: Vector[ModelRequest],
      labelsAfterPlan: List[String],
      labelsFinal: List[String],
      toolCounts: Map[String, Int],
      dirEntries: Vector[String],
      artifactReparse: Option[Either[String, PlanProposal]],
      execAnswer: Option[Either[RaiderError, String]],
      secondApprove: Option[PlanApprovalError],
      cancelRequested: Option[Boolean],
      cancelJobExit: Option[ZExit[RaiderError, ?]],
      cancelLedger: Option[UsageReport],
      cancelSlots: Option[(Int, Int)],
      sessionStateAfterCancel: Option[PlanSession.State],
      approveAfterCancel: Option[PlanApprovalError],
      requestsAfterCancel: Option[Vector[ModelRequest]],
      replanProposal: Option[PlanProposal]
  )

  /** Executes one scenario against a FRESH loop/session stack: unique TempDir,
    * own Admission/JobManager/backend/root. No state is shared with any other
    * scenario, so scenarios may run concurrently (PEVAL-03).
    */
  def exec(scenario: Scenario): ZIO[Scope, Nothing, Observed] =
    live {
      scenario.protocol match
        case Protocol.CancelThenReplan => execCancelThenReplan(scenario)
        case _                         => execSimple(scenario)
    }

  private def limits(attempts: Int = 16): Either[RaiderError, BudgetLimits] =
    BudgetLimits.make(maxAttempts = attempts)

  private def limitsOrThrow(attempts: Int = 16): BudgetLimits =
    limits(attempts).fold(e => throw e, identity)

  private def estimateOf(scenario: Scenario): CostEstimate =
    scenario.estimateMicros match
      case Some(micros) => CostEstimate.Priced(MicroUsd(micros))
      case None         => CostEstimate.Unknown

  private def compileCall(
      call: PlanCall,
      attempt: AttemptId
  ): Vector[ModelEvent] = call match
    case PlanCall.ToolRound(callId, tool, args) =>
      Vector[ModelEvent](
        ModelEvent.Started(attempt),
        ModelEvent.ToolCallReady(attempt, ToolCallId(callId), tool, args),
        ModelEvent.Finished(attempt, "tool_round")
      )
    case PlanCall.FinalText(text) =>
      Vector[ModelEvent](
        ModelEvent.Started(attempt),
        ModelEvent.TextDelta(attempt, text),
        ModelEvent.Finished(attempt, "stop")
      )

  private final class CountingTool(spec: ToolSpec, counter: Ref[Int])
      extends Tool:
    def name = spec.name
    def version = 1
    def description = s"corpus tool ${spec.name}"
    def recovery = spec.recovery
    def timeoutMs = 5000L
    def capabilities = ToolCapabilities(concurrentSafe = false)

    def invoke(argumentsJson: String) =
      counter.update(_ + 1).as("""{"ok":true}""")

  /** Deterministic parking spot inside the first dispatch: the request is
    * recorded, then the stream parks on the gate — so a cancel lands while the
    * plan job is genuinely live (same discipline as the RAI-107 bounded-cancel
    * acceptance).
    */
  private final class GatedBackend(
      underlying: ScriptedSequenceBackend,
      gate: Promise[Nothing, Unit]
  ) extends ModelBackend:

    private val seen = zio.Unsafe.unsafe { implicit u =>
      Ref.unsafe.make(Vector.empty[ModelRequest])
    }

    def capabilities: ModelCapabilities = underlying.capabilities

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.fromZIO(seen.update(_ :+ input) *> gate.await) *>
        underlying.stream(input)

    def requests: UIO[Vector[ModelRequest]] = seen.get

    def release(): UIO[Unit] = gate.succeed(()).unit
  end GatedBackend

  private def waitUntilRequested(
      requests: UIO[Vector[ModelRequest]],
      n: Int
  ): ZIO[Any, Nothing, Unit] =
    requests
      .repeatUntil(_.size >= n)
      .timeout(ZDuration.fromSeconds(5))
      .unit

  private def newTempDir(scenario: Scenario): ZIO[Any, Nothing, Path] =
    ZIO
      .attempt(
        Files.createTempDirectory(s"raider-plan-corpus-${scenario.id}-")
      )
      .orDie

  private def dirListing(dir: Path): Vector[String] =
    Files
      .list(dir)
      .iterator()
      .asScala
      .map(_.getFileName.toString)
      .toVector
      .sorted

  private def readStored(
      dir: Path,
      root: RootId
  ): Option[Either[String, PlanProposal]] =
    val path = PlanStore.artifactPath(dir, root)
    if Files.exists(path) then Some(PlanProposal.parse(Files.readString(path)))
    else None

  private def exitErrorCode(exit: ZExit[RaiderError, ?]): Option[String] =
    exit match
      case ZExit.Failure(cause) => cause.failures.headOption.map(_.code)
      case ZExit.Success(_)     => None

  /** Shared stack for every scenario: tools with counters, scripted backend
    * (gated when `gate` is present), admission, manager, plan-enabled loop,
    * session. Returns the recorder of the ACTIVE backend.
    */
  private def buildStack(
      scenario: Scenario,
      dir: Path,
      gate: Option[Promise[Nothing, Unit]]
  ): ZIO[
    Scope,
    Nothing,
    (
        UIO[Vector[ModelRequest]],
        Admission,
        JobManager,
        AgentLoop,
        PlanSession,
        Map[String, Ref[Int]]
    )
  ] =
    for
      counters <- ZIO
        .foreach(scenario.tools)(spec => Ref.make(0).map(r => spec -> r))
      countingTools = counters.map((spec, r) => CountingTool(spec, r))
      registry = ToolRegistry
        .build(countingTools.toList)
        .fold(e => throw e, identity)
      attempt = AttemptId(s"att-${scenario.id}")
      // The Approve protocol adds the execution job's final model call as
      // its own scripted script (the approval runs an ordinary runText).
      allCalls = scenario.protocol match
        case Protocol.Approve(executionFinalText) =>
          scenario.planCalls :+ PlanCall.FinalText(executionFinalText)
        case _ => scenario.planCalls
      scripted <- ScriptedSequenceBackend(
        allCalls.map(call => compileCall(call, attempt))*
      )
      (recBackend, recRequests) <- gate match
        case Some(g) =>
          val gb = GatedBackend(scripted, g)
          ZIO.succeed((gb: ModelBackend, gb.requests))
        case None =>
          ZIO.succeed(
            (scripted: ModelBackend, scripted.requests)
          )
      adm <- Admission.make(limits()).orDie
      manager <- JobManager.make()
      root = AgentLoop.newRoot()
      loop = AgentLoop(
        recBackend,
        registry,
        "corpus-model",
        adm,
        root,
        estimateOf(scenario),
        planMode = Some(PlanMode.of(registry, dir, scenario.maxRepairAttempts))
      )
      session <- PlanSession.make(loop, manager)
    yield (
      recRequests,
      adm,
      manager,
      loop,
      session,
      counters.map((spec, r) => spec.name -> r).toMap
    )

  private def collectCounts(
      counters: Map[String, Ref[Int]]
  ): ZIO[Any, Nothing, Map[String, Int]] =
    ZIO
      .foreach(counters.toList)((name, r) => r.get.map(name -> _))
      .map(_.toMap)

  /** PlanPhase and Approve protocols: the plan phase runs to its verdict;
    * Approve additionally drives the approval edge and the exactly-once denial.
    */
  private def execSimple(scenario: Scenario): ZIO[Scope, Nothing, Observed] =
    for
      dir <- newTempDir(scenario)
      built <- buildStack(scenario, dir, gate = None)
      (requests, adm, manager, loop, session, counters) = built
      planJob <- session
        .startPlan(
          List(RequestMessage("user", scenario.userPrompt)),
          limitsOrThrow(),
          scenario.maxRounds
        )
        .orDie
      _ <- waitUntilRequested(requests, scenario.planCalls.size)
      planOutcome <- session.awaitPlan(planJob).either
      labelsAfterPlan <- manager.adminView.map(_.map(_.label))
      planRequests <- requests
      toolCounts <- collectCounts(counters)
      root = loop.root
      execAnswer <- scenario.protocol match
        case Protocol.Approve(_) =>
          for
            exec <- session
              .approve(
                List(RequestMessage("user", "execute the approved plan")),
                limitsOrThrow(),
                scenario.maxRounds
              )
              .foldZIO(e => ZIO.dieMessage(e.detail), ZIO.succeed(_))
            _ <- waitUntilRequested(requests, scenario.planCalls.size + 1)
            answer <- manager.await(exec).either
          yield Some(answer)
        case _ => ZIO.succeed(None)
      labelsAfterApprove <- manager.adminView.map(_.map(_.label))
      secondApprove <- scenario.protocol match
        case Protocol.Approve(_) =>
          session
            .approve(
              List(RequestMessage("user", "again")),
              limitsOrThrow(),
              scenario.maxRounds
            )
            .flip
            .foldZIO(
              _ => ZIO.dieMessage("second approval created an execution job"),
              err => ZIO.succeed(Some(err))
            )
        case _ => ZIO.succeed(None)
      requestsFinal <- requests
      labelsFinal = labelsAfterApprove // == labelsAfterPlan for PlanPhase
    yield Observed(
      scenarioId = scenario.id,
      dir = dir,
      planOutcome = planOutcome,
      planRequests = planRequests,
      requestsFinal = requestsFinal,
      labelsAfterPlan = labelsAfterPlan,
      labelsFinal = labelsFinal,
      toolCounts = toolCounts,
      dirEntries = dirListing(dir),
      artifactReparse = readStored(dir, root),
      execAnswer = execAnswer,
      secondApprove = secondApprove,
      cancelRequested = None,
      cancelJobExit = None,
      cancelLedger = None,
      cancelSlots = None,
      sessionStateAfterCancel = None,
      approveAfterCancel = None,
      requestsAfterCancel = None,
      replanProposal = None
    )

  private def execCancelThenReplan(
      scenario: Scenario
  ): ZIO[Scope, Nothing, Observed] =
    for
      dir <- newTempDir(scenario)
      gate <- Promise.make[Nothing, Unit]
      built <- buildStack(scenario, dir, gate = Some(gate))
      (requests, adm, manager, loop, session, counters) = built
      root = loop.root
      planJob <- session
        .startPlan(
          List(RequestMessage("user", scenario.userPrompt)),
          limitsOrThrow(),
          scenario.maxRounds
        )
        .orDie
      _ <- waitUntilRequested(requests, 1)
      cancelled <- session.cancelPlan()
      outcome <- manager.await(planJob).exit
      usage <- adm.budgetLedger.usage
      modelFree <- adm.availableSlots(SlotKind.Model)
      toolFree <- adm.availableSlots(SlotKind.Tool)
      stateAfterCancel <- session.state
      approveAfterCancel <- session
        .approve(
          List(RequestMessage("user", "execute")),
          limitsOrThrow(),
          scenario.maxRounds
        )
        .flip
        .foldZIO(
          _ => ZIO.dieMessage("approve after cancel created an execution job"),
          err => ZIO.succeed(Some(err))
        )
      labelsAfterCancel <- manager.adminView.map(_.map(_.label))
      requestsAfterCancel <- requests
      // The repeated plan from Cancelled: release the parked dispatch first,
      // so the new plan job owns the model slot.
      _ <- gate.succeed(()).unit
      planJob2 <- session
        .startPlan(
          List(RequestMessage("user", "plan again")),
          limitsOrThrow(),
          scenario.maxRounds
        )
        .orDie
      replan <- session.awaitPlan(planJob2).either
      labelsFinal <- manager.adminView.map(_.map(_.label))
      toolCounts <- collectCounts(counters)
      requestsFinal <- requests
    yield Observed(
      scenarioId = scenario.id,
      dir = dir,
      planOutcome = replan,
      planRequests = requestsAfterCancel,
      requestsFinal = requestsFinal,
      labelsAfterPlan = labelsAfterCancel,
      labelsFinal = labelsFinal,
      toolCounts = toolCounts,
      dirEntries = dirListing(dir),
      artifactReparse = readStored(dir, root),
      execAnswer = None,
      secondApprove = None,
      cancelRequested = Some(cancelled),
      cancelJobExit = Some(outcome),
      cancelLedger = Some(usage),
      cancelSlots = Some((modelFree, toolFree)),
      sessionStateAfterCancel = Some(stateAfterCancel),
      approveAfterCancel = approveAfterCancel,
      requestsAfterCancel = Some(requestsAfterCancel),
      replanProposal = replan.toOption
    )

  // ---------- verifier: pinned verdict vs observed facts.

  /** Checks the scenario's pinned verdict against the observed facts. The
    * (Protocol, Verdict) pairing is data-consistent by construction; a
    * mismatched pair is a corpus authoring defect and fails loudly.
    */
  def verify(scenario: Scenario, o: Observed): TestResult =
    (scenario.protocol, scenario.verdict) match
      case (Protocol.PlanPhase, v: Verdict.Accepted) =>
        verifyAccepted(o, v)
      case (Protocol.PlanPhase, v: Verdict.Rejected) =>
        verifyRejected(o, v)
      case (Protocol.Approve(_), v: Verdict.ApprovedOnce) =>
        verifyApprovedOnce(o, v)
      case (Protocol.CancelThenReplan, v: Verdict.CancelledThenReplanned) =>
        verifyCancelledThenReplanned(o, v)
      case _ =>
        // Unreachable while the corpus data stays consistent: a mismatched
        // (protocol, verdict) pair is an authoring defect, not a pass —
        // the always-false pin fails loudly.
        assertTrue(o.scenarioId.isEmpty)

  private def deniedCount(
      messages: List[RequestMessage],
      marker: String
  ): Int =
    messages.count(m => m.role == "tool" && m.content.contains(marker))

  private def verifyAccepted(o: Observed, v: Verdict.Accepted): TestResult =
    val last = o.planRequests.lastOption
    assertTrue(
      o.planOutcome == Right(PlanProposal(v.title, v.steps.toVector)),
      o.planRequests.size == v.planModelCalls,
      o.toolCounts == v.toolExecs,
      o.planRequests
        .forall(_.tools.map(_.name).sorted == v.advertisedTools.sorted),
      last
        .map(r => deniedCount(r.messages, "RA-TOOLDENY"))
        .contains(v.deniedToolMessages),
      last
        .map(r => deniedCount(r.messages, "tool_denied"))
        .contains(v.deniedToolMessages),
      o.planRequests.count(
        _.messages.exists(_.content.contains("RA-PLAN-REJECTED"))
      ) == v.repairFeedbackRequests,
      o.dirEntries.size == v.artifactEntries,
      o.artifactReparse == Some(
        Right(PlanProposal(v.title, v.steps.toVector))
      ),
      o.labelsAfterPlan == v.sessionLabels,
      last.isDefined
    )

  private def verifyRejected(o: Observed, v: Verdict.Rejected): TestResult =
    assertTrue(
      o.planOutcome.fold(_.code, _ => "") == v.code,
      o.planOutcome.left.toOption.exists {
        case PlanPhaseError.PlanRejected(reason, attempts) =>
          attempts == v.attempts && reason.contains(v.reasonContains)
        case _ => false
      },
      o.dirEntries.size == v.artifactEntries,
      o.artifactReparse.isEmpty
    )

  private def verifyApprovedOnce(
      o: Observed,
      v: Verdict.ApprovedOnce
  ): TestResult =
    assertTrue(
      o.planOutcome == Right(
        PlanProposal(v.proposalTitle, v.proposalSteps.toVector)
      ),
      o.execAnswer == Some(Right(v.execAnswer)),
      o.labelsAfterPlan == v.labelsAfterPlan,
      o.labelsFinal == v.labelsAfterApprove,
      o.requestsFinal.size == 2,
      o.requestsFinal(1)
        .messages
        .headOption
        .exists(m =>
          m.role == AgentLoop.UserRole &&
            m.content.contains(v.leadingContextContains)
        ),
      o.secondApprove.exists(_.detail.startsWith(v.secondApproveDetailPrefix)),
      o.dirEntries.size == 1,
      o.artifactReparse == Some(
        Right(PlanProposal(v.proposalTitle, v.proposalSteps.toVector))
      )
    )

  private def verifyCancelledThenReplanned(
      o: Observed,
      v: Verdict.CancelledThenReplanned
  ): TestResult =
    assertTrue(
      o.cancelRequested.contains(true),
      o.cancelJobExit.flatMap(exitErrorCode).contains(v.cancelJobErrorCode),
      o.cancelLedger.map(_.reservedActive) == Some(
        MicroUsd(v.reservedActiveMicros)
      ),
      o.cancelLedger.map(_.uncertainTotal) == Some(
        MicroUsd(v.uncertainTotalMicros)
      ),
      o.cancelSlots.contains((v.freeModelSlots, v.freeToolSlots)),
      o.sessionStateAfterCancel.contains(PlanSession.Cancelled),
      o.approveAfterCancel.map(_.detail).contains(v.approveAfterCancelDetail),
      o.requestsAfterCancel.map(_.size).contains(v.requestsAfterCancel),
      o.replanProposal == Some(
        PlanProposal(v.replanTitle, v.replanSteps.toVector)
      ),
      o.labelsFinal == v.labelsFinal,
      o.artifactReparse.isDefined == v.artifactAfterReplan
    )

end PlanScenarioCorpus
