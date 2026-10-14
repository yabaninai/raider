package raider.cli.chat

import raider.core.RaiderError
import zio.Ref
import zio.ZIO

/** Explicit task-selection commands (ADR-033 §11.1 proposals, implemented by
  * RAI-155.b): `:task mark`, `:task select`, `:task check`, plus `:task list` /
  * `:task status`. Parsing and the ZIO operations are pure/testable; the
  * ChatLoop dispatch layer only blocks on them (console-adapter discipline).
  *
  * Selection semantics (ADR-033 §4.1, §9.1): `:task select <n>` selects the
  * LAST `n` captured turns as one bounded contiguous span, validated at select
  * time against the Supported Task Specification — exactly ONE intent turn,
  * ordered refinements (ledger order = selection order), named trusted checks,
  * all turns Completed. The result is an immutable snapshot stored in the
  * session's selection ref — the typed structure the renderer card (RAI-155.c)
  * consumes. No generation happens in this card.
  */
object TaskCommands:

  sealed trait TaskCmd

  final case class Mark(n: Int, kind: String) extends TaskCmd
  final case class Select(n: Int) extends TaskCmd
  final case class Check(name: String, argv: List[String]) extends TaskCmd
  case object ListCmd extends TaskCmd
  case object StatusCmd extends TaskCmd
  final case class Usage(text: String) extends TaskCmd

  val usageText: String =
    "usage: :task list | :task status | :task mark <n> <intent|refinement|ordinary> " +
      "| :task select <n> | :task check <name> <argv tokens...>"

  def parse(line: String): TaskCmd =
    val toks = line.trim.split("\\s+").toList.filter(_.nonEmpty)
    toks match
      case Nil             => Usage(usageText)
      case "list" :: Nil   => ListCmd
      case "status" :: Nil => StatusCmd
      case "mark" :: nTok :: kindTok :: Nil =>
        nTok.toIntOption match
          case None =>
            Usage(s"mark needs a turn number and a kind: $usageText")
          case Some(n) =>
            TurnLedger.Kind.normalize(kindTok) match
              case Some(kind) => Mark(n, kind)
              case None =>
                Usage(
                  s"unknown kind '$kindTok' (use intent|refinement|ordinary)"
                )
      case "select" :: nTok :: Nil =>
        nTok.toIntOption match
          case Some(n) => Select(n)
          case None =>
            Usage(s"select needs a turn count: :task select <n> (last n turns)")
      case "check" :: name :: argv if argv.nonEmpty => Check(name, argv)
      case "check" :: _ => Usage("usage: :task check <name> <argv tokens...>")
      case _            => Usage(usageText)

  /** `:task mark <n> <kind>` — set the task-selection kind of ledger turn n
    * (1-based ledger order). Marking is metadata only; STS validity is enforced
    * at select time (ADR-033 §4.1).
    */
  def mark(
      ledgerRef: Ref[Vector[TurnRecord]],
      n: Int,
      kind: String
  ): ZIO[Any, RaiderError, Unit] =
    ledgerRef
      .modify { ts =>
        val i = n - 1
        if i < 0 || i >= ts.size then
          (
            Left(
              RaiderError.InputValidation(
                s"no such turn #$n (the ledger has ${ts.size} turn(s)); " +
                  "see :task list"
              )
            ),
            ts
          )
        else (Right(()), ts.updated(i, ts(i).copy(kind = kind)))
      }
      .flatMap(ZIO.fromEither(_))

  /** `:task check <name> <argv...>` — register (or replace) a named trusted
    * check with a validated argv (ADR-033 §4.1; model text can never add or
    * alter a check).
    */
  def registerCheck(
      checksRef: Ref[List[NamedCheck]],
      name: String,
      argv: List[String]
  ): ZIO[Any, RaiderError, Unit] =
    NamedCheck.validate(name, argv) match
      case Left(err) => ZIO.fail(err)
      case Right(check) =>
        checksRef.update { cs =>
          if cs.exists(_.name == check.name) then
            cs.map(c => if c.name == check.name then check else c)
          else cs :+ check
        }

  /** `:task select <n>` — validate and build the bounded immutable selection
    * snapshot (the .c entry point).
    */
  def select(
      turns: List[TurnRecord],
      checks: List[NamedCheck],
      span: Int,
      maxRoundsBound: Int
  ): Either[RaiderError, TaskSelection] =
    TurnLedger.validateSelection(turns, checks, span, maxRoundsBound)

  def renderList(turns: Vector[TurnRecord]): String =
    if turns.isEmpty then
      "turn ledger: empty (conversation-only context; nothing to select)"
    else
      val lines = turns.zipWithIndex.map { case (t, i) =>
        f"#${i + 1}%d ${t.turnId}%-20s ${t.kind}%-10s ${t.status}%-11s " +
          s"tools=${t.toolSummary.size}%d ${t.request.take(48)}"
      }
      ("turn ledger (append order):" :: lines.toList).mkString("\n  ")

  def renderSelection(sel: TaskSelection): String =
    val base = sel.turns.map(t => s"${t.turnId}[${t.kind}]").mkString(", ")
    val refs = sel.refinements.map(_.turnId) match
      case Nil  => "none"
      case refs => refs.mkString(", ")
    val checks =
      sel.checks.map(c => s"${c.name} = ${c.argv.mkString(" ")}").mkString("; ")
    s"""selection ready (${sel.turns.size} turn(s)): $base
       |  intent: ${sel.intent.turnId} "${sel.intent.request.take(80)}"
       |  refinements (selection order): $refs
       |  checks: $checks
       |  agent stage: ONE bounded stage, captured maxRoundsBound=${sel.maxRoundsBound} (the launcher binds the effective policy at execution time)
       |This snapshot is the typed input for the workflow renderer (RAI-155.c); nothing is generated yet.""".stripMargin

  def renderStatus(
      turns: Vector[TurnRecord],
      checks: List[NamedCheck],
      selection: Option[TaskSelection]
  ): String =
    if turns.isEmpty then
      "export readiness: NOT READY — no turn ledger in this session " +
        "(conversation-only save or nothing captured). Export cannot invent " +
        "absent execution records; re-run the task in chat and mark it with :task mark."
    else
      val marked = turns.count(t =>
        t.kind == TurnLedger.Kind.Intent || t.kind == TurnLedger.Kind.Refinement
      )
      val pending = turns.count(t => t.status != TurnLedger.Status.Completed)
      selection match
        case Some(sel) =>
          s"export readiness: READY — selection snapshot ${sel.turnIds.mkString("[", ",", "]")} " +
            s"(${sel.refinements.size} refinement(s), ${sel.checks.size} check(s)) is the renderer input"
        case None =>
          s"export readiness: NOT READY — ${turns.size} turn(s) captured, $marked marked, " +
            s"$pending not Completed, ${checks.size} check(s) registered. " +
            "Mark an intent (:task mark <n> intent), register a check (:task check ...), then :task select <n>."

  /** Reopen notices (ADR-033 §5.3): a legacy conversation-only session is named
    * explicitly (export-refused later, typed); saved config refs are compared
    * with the active configuration and any mismatch is displayed BEFORE work
    * continues. Reopening never restores authority.
    */
  def restoreNotices(
      sessionTurns: List[TurnRecord],
      active: ConfigRefs
  ): List[String] =
    if sessionTurns.isEmpty then
      List(
        "note: this session has no turn ledger (conversation-only save); " +
          "export selection is unavailable — re-run the task in chat with explicit :task mark"
      )
    else
      val saved = sessionTurns.last.configRefs
      val mismatches = ConfigRefs.mismatch(saved, active)
      val mismatchLines =
        if mismatches.isEmpty then
          List("note: saved config references match the active configuration")
        else
          List(
            "config mismatch (reopening does not restore authority; the ACTIVE configuration stays in effect):"
          ) ++ mismatches.map(m => s"  - $m")
      mismatchLines

end TaskCommands
