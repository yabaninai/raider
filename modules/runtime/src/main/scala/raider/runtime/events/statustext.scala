package raider.runtime.events

import raider.runtime.jobs.JobStatus

/** RAI-097: the ONE canonical one-line status text over a [[SessionSnapshot]].
  *
  * Pure function of the snapshot only: no IO, no clock, no random — the same
  * snapshot always renders the same string (UNI-01/UNI-03). The frozen format
  * is `<body> | <counters>` where every token comes from an ACTUAL snapshot
  * field (job status, wait reason, active tool, usage, truncation counters);
  * nothing is ever invented (UNI-02): a reason nobody reported renders as
  * `unknown`, a cost nobody observed renders as `cost unknown` — never $0
  * (ADR-024 §2.4 honesty rule).
  *
  * Stream metadata (`lastSeq`, `updatedAtMs`) is deliberately NOT part of the
  * line: it changes with every envelope and would break change-detection by
  * string equality in the chat status line.
  */
object StatusText:

  /** The full one-line render (never multi-line). */
  def render(s: SessionSnapshot): String = s"${body(s)} | ${counters(s)}"

  /** The status body, from actual snapshot fields only:
    *   - terminal → `terminal <status>`
    *   - Queued → `queued`; Cancelling → `cancelling`
    *   - Waiting → `waiting(<reason|unknown>)`; WaitingApproval →
    *     `waiting(approval)`
    *   - Running → `tool <name>` while a tool is active, else `running`
    */
  def body(s: SessionSnapshot): String =
    statusWord(s.status, s.waitReason).getOrElse:
      // Running: the word depends on the active tool, not the bare status
      s.activeTool match
        case Some((tool, _)) => toolText(tool)
        case None            => "running"

  /** The ONE frozen status word for a (status, reported reason) pair (RAI-097)
    * — shared by the snapshot [[body]] and by stream-driven refinements that
    * follow the latest event instead of the folded status. `None` for Running,
    * whose word needs the active-tool context (see [[body]]).
    */
  def statusWord(
      status: JobStatus,
      reason: Option[WaitReason]
  ): Option[String] =
    status match
      case t: JobStatus.Terminal     => Some(terminalText(t))
      case JobStatus.Queued          => Some("queued")
      case JobStatus.Waiting         => Some(waitingText(reason))
      case JobStatus.WaitingApproval => Some("waiting(approval)")
      case JobStatus.Cancelling      => Some("cancelling")
      case JobStatus.Running         => None

  /** `terminal <status>` — the terminal word. */
  def terminalText(t: JobStatus.Terminal): String =
    s"terminal ${t.toString.toLowerCase}"

  /** `waiting(<reason|unknown>)` — an unreported reason stays honest. */
  def waitingText(reason: Option[WaitReason]): String =
    s"waiting(${reason.fold("unknown")(_.toString.toLowerCase)})"

  /** `tool <name>`. */
  def toolText(tool: String): String = s"tool $tool"

  /** The honest counters tail: the observed cost (UNKNOWN when nobody observed
    * it — the pristine all-zero snapshot and the loop's `unknown = true` usage
    * both render as `cost unknown`, never $0), plus the progress-tail
    * truncation counters when something was actually cut or evicted.
    */
  def counters(s: SessionSnapshot): String =
    val u = s.usage
    val cost =
      if u.unknown || (u.reserved.value == 0L && u.observed.value == 0L) then
        "cost unknown"
      else f"cost $$${u.observed.value.toDouble / 1000000.0}%.6f"
    val t = s.progressTail
    if t.truncatedLines > 0 || t.truncatedChars > 0 then
      s"$cost, tail +${t.truncatedLines} lines/+${t.truncatedChars} chars"
    else cost

end StatusText
