package raider.runtime.loop

import raider.core.{RaiderError, RootId, ToolRegistry}
import raider.core.RecoveryClass
import zio.json.ast.Json
import zio.json.{
  DeriveJsonDecoder,
  DeriveJsonEncoder,
  JsonDecoder,
  JsonEncoder,
  EncoderOps
}
import zio.ZIO

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}

/** RAI-107 / ADR-028: the structured result of the plan phase.
  *
  * A runtime-layer type (NOT a core schema, NOT a wire event): the frozen
  * ADR-024 event family is untouched — human-readable plan lines travel only
  * through the existing `Diagnostics` payload. The proposal persists as JSON
  * next to the run artifacts ([[PlanStore]]); the model must emit exactly this
  * shape as its final plan-phase answer:
  * `{"title": "...", "steps": ["...", "..."]}`.
  *
  * Validation is structural and total: non-empty title, at least one step,
  * every step non-empty (whitespace-trimmed values are normalized). Anything
  * else is malformed and produces the typed [[PlanPhaseError.PlanRejected]]
  * after the bounded repair budget is spent.
  */
final case class PlanProposal(title: String, steps: Vector[String])

object PlanProposal:

  given JsonEncoder[PlanProposal] = DeriveJsonEncoder.gen[PlanProposal]
  given JsonDecoder[PlanProposal] = DeriveJsonDecoder.gen[PlanProposal]

  /** Structural parse of a model-emitted final answer into a proposal. The
    * `Left` reason is bounded machine-readable feedback for the repair round
    * (it reaches the model as a `plan_rejected` message, never as prose hope).
    */
  def parse(candidate: String): Either[String, PlanProposal] =
    Json.decoder.decodeJson(candidate) match
      case Left(err) =>
        Left(s"plan is not valid JSON: ${err.take(MaxReasonChars)}")
      case Right(_) =>
        summon[JsonDecoder[PlanProposal]].decodeJson(candidate) match
          case Left(err) =>
            Left(
              s"plan JSON does not match the proposal schema " +
                s"($SchemaHint): ${err.take(MaxReasonChars)}"
            )
          case Right(raw) => validate(raw)

  /** Structural validation + normalization (trim). Total: every malformed shape
    * yields a bounded reason, never an exception.
    */
  def validate(raw: PlanProposal): Either[String, PlanProposal] =
    val title = raw.title.trim
    val steps = raw.steps.map(_.trim)
    if title.isEmpty then Left("plan title must be a non-empty string")
    else if steps.isEmpty then
      Left("plan must contain at least one step in \"steps\"")
    else if steps.exists(_.isEmpty) then
      Left("every plan step must be a non-empty string")
    else if steps.size > MaxSteps then
      Left(s"plan must contain at most $MaxSteps steps, got ${steps.size}")
    else Right(PlanProposal(title, steps))

  /** The exact shape the model is asked to re-emit on repair. */
  val SchemaHint: String = "{\"title\":\"...\",\"steps\":[\"...\"]}"

  /** Hygiene bound for repair feedback text (mirrors LoopEvents.maxReasonChars
    * discipline: unbounded strings never enter the message envelope).
    */
  val MaxReasonChars: Int = 512

  /** Structural hygiene bound: a plan is a bounded artifact, not a dump. */
  val MaxSteps: Int = 64

end PlanProposal

/** Typed failures of the plan phase (RAI-107, ADR-028 §2). A runtime-layer ADT:
  * `RaiderError` is a sealed core family and is NOT extended. `PlanRejected` is
  * the typed malformed-plan outcome; every model/budget/admission failure keeps
  * its original typed error under [[Underlying]].
  */
enum PlanPhaseError:
  case PlanRejected(reason: String, planAttempts: Int)
  case Underlying(error: RaiderError)

  def code: String = this match
    case PlanRejected(_, _) => "RA-PLAN-REJECTED"
    case Underlying(error)  => error.code

  def detail: String = this match
    case PlanRejected(reason, attempts) =>
      s"plan rejected after $attempts attempt(s): $reason"
    case Underlying(error) => error.detail

end PlanPhaseError

/** Typed approval failures of a [[PlanSession]] (RAI-107 / ADR-028: approval is
  * explicit, exactly-once, and `cancel != approval`).
  */
enum PlanApprovalError:
  case AlreadyApproved(jobId: String)
  case NoPlan(reason: String)
  case Cancelled

  def detail: String = this match
    case AlreadyApproved(jobId) =>
      s"plan already approved: execution job $jobId exists (exactly-once)"
    case NoPlan(reason) => s"no approved plan to execute: $reason"
    case Cancelled      => "plan phase was cancelled: cancel is not approval"

end PlanApprovalError

/** Atomic persistence of the plan artifact "next to the run artifacts" (ADR-028
  * §2). The directory is caller-provided (wiring owns the layout); the file is
  * `<plan-<root>.json>` written tmp+move (`ATOMIC_MOVE`): a reader never
  * observes a torn plan, and a rejected/cancelled phase writes nothing.
  *
  * Same no-fsync honesty as [[raider.runtime.journal.JobJournal]]: the write is
  * flushed to the OS page cache only — power-loss durability is not claimed.
  * Environment failures are typed (`JournalFailure` — persistent-artifact
  * failure), never silent.
  */
object PlanStore:

  def fileName(root: RootId): String = s"plan-${root.value}.json"

  def artifactPath(dir: Path, root: RootId): Path = dir.resolve(fileName(root))

  /** Persist the proposal atomically; returns the final artifact path. */
  def write(
      dir: Path,
      root: RootId,
      proposal: PlanProposal
  ): ZIO[Any, RaiderError, Path] =
    ZIO
      .attempt {
        Files.createDirectories(dir)
        val target = artifactPath(dir, root)
        val tmp = Files.createTempFile(dir, ".plan-", ".tmp")
        try
          Files.writeString(
            tmp,
            proposal.toJson + "\n",
            StandardCharsets.UTF_8
          )
          Files.move(
            tmp,
            target,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
          )
        catch
          case t: Throwable =>
            Files.deleteIfExists(tmp)
            throw t
        target
      }
      .mapError(e =>
        RaiderError.JournalFailure(
          s"plan artifact persist failed for root ${root.value}: ${String.valueOf(e)}"
        )
      )

end PlanStore

/** RAI-107 (ADR-028 §2): the plan-mode binding of one [[AgentLoop]] — the
  * RA-CFG config object of the runtime layer, OFF by default: an AgentLoop
  * built without `planMode` behaves exactly as before, and `runPlan` on it is a
  * typed refusal. Fields:
  *   - `fullTools` — the registry of the ordinary execution mode;
  *   - `readOnlyTools` — its strict `RecoveryClass.ReadOnly` subset, the ONLY
  *     toolset the plan phase advertises to the model and executes (enforcement
  *     by the frozen recovery classes, never prompt-only);
  *   - `artifactsDir` — where [[PlanStore]] persists the accepted proposal
  *     (wiring owns the artifacts layout; ADR-028 "next to the run artifacts");
  *   - `maxRepairAttempts` — the bounded repair budget for malformed plans
  *     (quality-gates §7.11: repair is an attempt, never unbounded).
  */
final case class PlanMode(
    fullTools: ToolRegistry,
    readOnlyTools: ToolRegistry,
    artifactsDir: Path,
    maxRepairAttempts: Int
)

object PlanMode:

  /** Default bounded repair budget (1 rejected candidate + 2 repairs = at most
    * 3 plan attempts, every one a billable admission attempt on the root).
    */
  val DefaultMaxRepairAttempts: Int = 2

  /** Derives the read-only view of a registry. The subset of a valid registry
    * is always a valid registry (unique non-empty names, versions, timeouts are
    * untouched), so the impossible `Left` is a defect, not a case.
    */
  def of(
      full: ToolRegistry,
      artifactsDir: Path,
      maxRepairAttempts: Int = DefaultMaxRepairAttempts
  ): PlanMode =
    ToolRegistry.build(
      full.names
        .flatMap(n => full.lookup(n))
        .filter(t => t.recovery == RecoveryClass.ReadOnly)
    ) match
      case Right(ro) =>
        PlanMode(full, ro, artifactsDir, maxRepairAttempts)
      case Left(bad) =>
        throw new IllegalStateException(
          s"readOnly subset of a valid registry must be valid: ${bad.detail}"
        )

end PlanMode
