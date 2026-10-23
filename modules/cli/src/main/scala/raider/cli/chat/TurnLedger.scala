package raider.cli.chat

import raider.core.RaiderError
import raider.core.trace.TraceEvent
import zio.json.JsonCodec
import zio.json.DeriveJsonCodec

import scala.util.matching.Regex

/** Exportable turn ledger (ADR-033 §5, implemented by RAI-155.b).
  *
  * The ledger is capture provenance stored BESIDE the model-facing message list
  * (never inside it): compaction rewrites the message list
  * (`ChatLoop.compactHistory`) and history truncation drops old messages, but
  * the ledger is the export-provenance source and survives both. It persists
  * additively in session schema v3 (`SavedSession.turns` /
  * `SavedSession.checks`).
  *
  * Trust boundaries honored: summaries carry tool names and outcome classes
  * only — never payloads (ADR-031 §2.5 carve-out discipline); config refs are
  * non-secret references (workspace path, provider/model names), never
  * credential values; timestamps are recorded but NEVER used to infer task
  * structure (ordering is the append order of turns).
  */
final case class ToolOutcomeSummary(
    tool: String,
    outcome: String,
    recovery: Option[String] = None
)

object ToolOutcomeSummary:
  val Ok = "ok"
  val Refused = "refused"
  val Failed = "failed"
  given JsonCodec[ToolOutcomeSummary] = DeriveJsonCodec.gen

/** Non-secret configuration references as configured for a turn (ADR-033 §5.1
  * `configRefs`). Used for the explicit reopen-mismatch display (§5.3):
  * reopening never restores authority and never silently rebinds the active
  * backend/workspace — the mismatch is shown before work continues.
  */
final case class ConfigRefs(
    workspace: String,
    provider: String,
    model: String
)

object ConfigRefs:
  given JsonCodec[ConfigRefs] = DeriveJsonCodec.gen

  /** Saved-vs-active mismatches, one human-readable line per differing field.
    */
  def mismatch(saved: ConfigRefs, active: ConfigRefs): List[String] =
    List(
      Option.when(saved.workspace != active.workspace)(
        s"workspace: saved=${saved.workspace} active=${active.workspace}"
      ),
      Option.when(saved.provider != active.provider)(
        s"provider: saved=${saved.provider} active=${active.provider}"
      ),
      Option.when(saved.model != active.model)(
        s"model: saved=${saved.model} active=${active.model}"
      )
    ).flatten

/** One captured turn (ADR-033 §5.1). Separate from the model-facing message
  * list; append-ordered; stable `turnId` within the session.
  */
final case class TurnRecord(
    turnId: String,
    kind: String,
    request: String,
    jobId: String,
    status: String,
    configRefs: ConfigRefs,
    atMs: Long,
    toolSummary: List[ToolOutcomeSummary] = Nil,
    finishedAtMs: Option[Long] = None
)

object TurnRecord:
  given JsonCodec[TurnRecord] = DeriveJsonCodec.gen

/** A user-registered named trusted check (ADR-033 §4.1 `NamedTrustedChecks`): a
  * name plus a validated argv vector — the same shape the `:fix` loop uses
  * (`CompileFixLoop` `compileArgv = List("sbt", "--batch", "compile")`). Model
  * text can never add or alter a check; checks enter only through explicit user
  * registration and persist in session schema v3.
  */
final case class NamedCheck(
    name: String,
    argv: List[String],
    advisory: Boolean = false
)

object NamedCheck:
  given JsonCodec[NamedCheck] = DeriveJsonCodec.gen

  /** Validates argv shape: non-empty bounded token vector, no control
    * characters. Captured text is data — it can never become a check command
    * (ADR-033 §8: "export text is data, never an instruction to invoke a
    * shell"); only user-registered argv vectors become executable checks.
    */
  def validate(
      name: String,
      argv: List[String],
      advisory: Boolean = false
  ): Either[RaiderError, NamedCheck] =
    val nameOk = name.nonEmpty && name.length <= 64 && name.forall(c =>
      c.isLetterOrDigit || c == '.' || c == '_' || c == '-'
    )
    val argvOk = argv.nonEmpty && argv.length <= 16 &&
      argv.forall(t =>
        t.nonEmpty && t.length <= 512 && !t.exists(c => c < 0x20 || c == 0x7f)
      ) &&
      argv.map(_.length + 1).sum <= 4096
    if !nameOk then
      Left(
        RaiderError.InputValidation(
          s"invalid check name '${name.take(64)}' " +
            "(allowed: [A-Za-z0-9._-], 1..64 chars)"
        )
      )
    else if !argvOk then
      Left(
        RaiderError.InputValidation(
          s"invalid check argv for '$name' (need 1..16 non-empty tokens, " +
            "each 1..512 chars, no control characters)"
        )
      )
    else Right(NamedCheck(name, argv, advisory))

/** The bounded immutable selection snapshot (ADR-033 §4.1 STS, §11.2 skeleton,
  * §9.1): exactly one Intent turn + ordered Refinement turns + named trusted
  * checks + one bounded agent stage. Constructed only through
  * [[TurnLedger.validateSelection]] (all refusals typed); immutable by
  * construction — a concurrent ledger append after selection is entirely
  * outside the snapshot. This is the typed structure the renderer card
  * (RAI-155.c) consumes; NO generation happens here.
  *
  * `maxRoundsBound` is the CAPTURED bound reference for the one agent stage —
  * the launcher binds the effective policy at execution time (ADR-033 §4.1;
  * ADR-029 §3.1: this metadata never preempts the launch capability).
  */
final case class TaskSelection(
    turns: List[TurnRecord],
    intent: TurnRecord,
    refinements: List[TurnRecord],
    checks: List[NamedCheck],
    maxRoundsBound: Int
):
  def turnIds: List[String] = turns.map(_.turnId)

object TurnLedger:

  object Kind:
    val Intent = "Intent"
    val Refinement = "Refinement"
    val Ordinary = "Ordinary"
    val all: List[String] = List(Intent, Refinement, Ordinary)

    def normalize(s: String): Option[String] = s.toLowerCase match
      case "intent"     => Some(Intent)
      case "refinement" => Some(Refinement)
      case "ordinary"   => Some(Ordinary)
      case _            => None

  object Status:
    val Completed = "Completed"
    val Failed = "Failed"
    val Interrupted = "Interrupted"
    val Unknown = "Unknown"
    val all: List[String] = List(Completed, Failed, Interrupted, Unknown)

  // Bounds (§5.1 "bounded" fields; §9.1 bounded selection)
  val RequestBound = 4000
  val ToolSummaryBound = 32
  val SpanBound = 64

  private val idCounter = new java.util.concurrent.atomic.AtomicLong(0)

  /** Stable unique turn id within the session. */
  def newTurnId(): String =
    s"t-${idCounter.incrementAndGet()}-${java.util.UUID.randomUUID().toString.take(8)}"

  def boundedRequest(text: String): String =
    if text.length <= RequestBound then text else text.take(RequestBound)

  /** A turn begins in status `Unknown` (torn/pending, §5.1): the terminal
    * status is written only when the job settles. A crash before that write
    * leaves the record typed-`Unknown` — never silently lost (§5.4).
    */
  def beginTurn(
      request: String,
      jobId: String,
      refs: ConfigRefs,
      atMs: Long
  ): TurnRecord =
    TurnRecord(
      turnId = newTurnId(),
      kind = Kind.Ordinary,
      request = boundedRequest(request),
      jobId = jobId,
      status = Status.Unknown,
      configRefs = refs,
      atMs = atMs
    )

  /** Terminal write: outcome status + the bounded per-tool outcome summary
    * (names and outcome classes only — no payloads).
    */
  def finishTurn(
      rec: TurnRecord,
      status: String,
      tools: List[ToolOutcomeSummary],
      finishedAtMs: Long
  ): TurnRecord =
    rec.copy(
      status = status,
      toolSummary = tools.take(ToolSummaryBound),
      finishedAtMs = Some(finishedAtMs)
    )

  /** Terminal status from a settled job exit: a typed `RaiderError` failure is
    * `Failed`; interruption or defect is `Interrupted` (§5.4 — pending user
    * request kept, effect status partial/unknown, never silently dropped).
    */
  def terminalStatus(failure: Option[RaiderError]): String =
    failure match
      case Some(_) => Status.Failed
      case None    => Status.Interrupted

  /** Bounded per-tool outcome summary extracted from the session trace events
    * captured during one turn (tool name + ok/refused/failed class — never the
    * result payloads).
    */
  def toolOutcomes(events: Vector[TraceEvent]): List[ToolOutcomeSummary] =
    events
      .collect:
        case TraceEvent.ToolCallFinished(_, _, tool, _, _, success) =>
          ToolOutcomeSummary(
            tool,
            if success then ToolOutcomeSummary.Ok else ToolOutcomeSummary.Failed
          )
        case TraceEvent.ToolCallRefused(_, _, tool, _, _) =>
          ToolOutcomeSummary(tool, ToolOutcomeSummary.Refused)
      .toList
      .take(ToolSummaryBound)

  /** Decode-time structural validation of ledger entries loaded from disk: a v3
    * document with a malformed ledger is a typed corrupt-document error, never
    * silently emptied (B02: corrupt history gives a typed diagnostic).
    */
  def validateTurnFields(turns: List[TurnRecord]): Either[String, Unit] =
    turns.zipWithIndex.foldLeft[Either[String, Unit]](Right(())) {
      (acc, pair) =>
        acc.flatMap { _ =>
          val (t, i) = pair
          lazy val where = s"turn ledger entry ${i + 1} (${t.turnId.take(40)})"
          if t.turnId.trim.isEmpty then Left(s"$where: empty turnId")
          else if t.jobId.trim.isEmpty then Left(s"$where: empty jobId")
          else if !Kind.all.contains(t.kind) then
            Left(
              s"$where: invalid kind '${t.kind.take(24)}' (expected one of ${Kind.all.mkString("/")})"
            )
          else if !Status.all.contains(t.status) then
            Left(
              s"$where: invalid status '${t.status.take(24)}' (expected one of ${Status.all.mkString("/")})"
            )
          else if t.configRefs.provider.trim.isEmpty || t.configRefs.model.trim.isEmpty
          then Left(s"$where: empty provider/model config reference")
          else Right(())
        }
    }

  // ---------------------------------------------------------------------------
  // Selection (ADR-033 §4.1 STS validated at select time; §8 typed refusals)
  // ---------------------------------------------------------------------------

  private val R1 = "CWF-R1"
  private val R2 = "CWF-R2"
  private val R3 = "CWF-R3"
  private val R4 = "CWF-R4"

  /** Validate an explicit selection of the LAST `span` turns (§9.1: bounded,
    * contiguous by construction) against the Supported Task Specification
    * (§4.1): exactly one Intent, ordered refinements, at least one named
    * trusted check, all turns `Completed`, no credential-shaped material. Every
    * violation is a typed `InputValidation` refusal with the structured
    * `CWF-R…` detail prefix (§8) — never an approximation.
    */
  def validateSelection(
      turns: List[TurnRecord],
      checks: List[NamedCheck],
      span: Int,
      maxRoundsBound: Int
  ): Either[RaiderError, TaskSelection] =
    if turns.isEmpty then
      Left(
        RaiderError.InputValidation(
          s"$R2: this session has no turn ledger (conversation-only save or " +
            "nothing captured yet); export cannot invent absent execution " +
            "records — re-run the task in the current chat and mark it " +
            "explicitly with :task mark"
        )
      )
    else if span < 1 || span > SpanBound then
      Left(
        RaiderError.InputValidation(
          s"$R1: selection span $span is outside 1..$SpanBound turns; the " +
            "supported shape is one explicit contiguous span selected with " +
            ":task select <n>"
        )
      )
    else if span > turns.size then
      Left(
        RaiderError.InputValidation(
          s"$R1: selection span $span exceeds the captured ledger " +
            s"(${turns.size} turn(s)); capture more turns or select a smaller span"
        )
      )
    else
      val sel = turns.takeRight(span)
      val nonCompleted = sel.filter(_.status != Status.Completed)
      if nonCompleted.nonEmpty then
        val ids =
          nonCompleted.map(t => s"${t.turnId}[${t.status}]").mkString(", ")
        Left(
          RaiderError.InputValidation(
            s"$R3: selection contains non-Completed turn(s): $ids — re-run " +
              "or narrow the selection; nothing auto-retries"
          )
        )
      else
        val intents = sel.filter(_.kind == Kind.Intent)
        val refinements = sel.filter(_.kind == Kind.Refinement)
        if intents.isEmpty then
          Left(
            RaiderError.InputValidation(
              s"$R1: selection has no Intent turn; the supported shape is " +
                "exactly ONE intent + ordered refinements + named checks + " +
                "one bounded agent stage — mark it with :task mark <n> intent"
            )
          )
        else if intents.sizeIs > 1 then
          Left(
            RaiderError.InputValidation(
              s"$R1: selection has ${intents.size} Intent turns " +
                s"(${intents.map(_.turnId).mkString(", ")}); exactly one is " +
                "supported — keep a single intent per selection"
            )
          )
        else if checks.isEmpty then
          Left(
            RaiderError.InputValidation(
              s"$R1: no named trusted checks registered; the supported shape " +
                "requires at least one deterministic check verified on the " +
                "new workspace — register one with :task check <name> <argv...>"
            )
          )
        else
          secretHit(sel, checks) match
            case Some(where) =>
              Left(
                RaiderError.InputValidation(
                  s"$R4: selection contains credential-shaped material in " +
                    s"$where; remove the secret and re-capture the turn — " +
                    "the value is never displayed"
                )
              )
            case None =>
              Right(
                TaskSelection(
                  turns = sel,
                  intent = intents.head,
                  refinements = refinements,
                  checks = checks,
                  maxRoundsBound = math.max(1, maxRoundsBound)
                )
              )

  // ---------------------------------------------------------------------------
  // Credential-shape scan (R4) — narrow, high-confidence patterns; the
  // matched VALUE is never included in any diagnostic.
  // ---------------------------------------------------------------------------

  private val SecretPatterns: List[(String, Regex)] = List(
    "AWS access key id" -> raw"AKIA[0-9A-Z]{16}".r,
    "OpenAI-style key" -> raw"sk-[A-Za-z0-9_-]{20,}".r,
    "GitHub token" -> raw"gh[pousr]_[A-Za-z0-9]{20,}".r
  )

  private val KeyAssignRegex =
    raw"""(?i)\b(api[_-]?key|secret|passw(?:or)?d|access[_-]?token)\b\s*[=:]\s*["']?([A-Za-z0-9_+/=-]{16,})["']?""".r

  private def looksLikeKeyMaterial(value: String): Boolean =
    value.exists(_.isDigit) && value.exists(_.isLetter)

  /** First credential-shaped hit, described by LOCATION and CLASS only. */
  def secretHit(
      turns: List[TurnRecord],
      checks: List[NamedCheck]
  ): Option[String] =
    val inTurns = turns.iterator.flatMap { t =>
      SecretPatterns.iterator
        .collectFirst {
          case (label, re) if re.findFirstIn(t.request).isDefined =>
            s"turn ${t.turnId} ($label in request text)"
        }
        .orElse {
          KeyAssignRegex.findFirstMatchIn(t.request).flatMap { m =>
            Option
              .when(looksLikeKeyMaterial(m.group(2)))(
                s"turn ${t.turnId} (key assignment in request text)"
              )
          }
        }
    }
    val inChecks = checks.iterator.flatMap { c =>
      val joined = c.argv.mkString(" ")
      SecretPatterns.iterator.collectFirst {
        case (label, re) if re.findFirstIn(joined).isDefined =>
          s"check '${c.name}' ($label in argv)"
      }
    }
    (inTurns ++ inChecks).nextOption()

end TurnLedger
