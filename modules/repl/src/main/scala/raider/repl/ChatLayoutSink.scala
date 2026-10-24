package raider.repl

import raider.repl.facade.TextRenderSink
import raider.runtime.tools.approval.{
  ToolApprovalContext,
  ToolApprovalDecision,
  ToolApprovalHook
}
import raider.ui.{ChatAnsiRenderer, ChatLayout}
import raider.tools.files.edit.UnifiedDiff
import zio.{UIO, ZIO}

/** Streaming chat view used by the opt-in `--chat` shell. The sink is created
  * in the Scala REPL interpreter world and owns only bounded render state; the
  * JLine reader remains the input/history owner.
  */
final class ChatLayoutSink(
    renderBridge: java.util.function.Consumer[String],
    scriptedDeltaDelay: zio.Duration = zio.Duration.fromMillis(0),
    model: String = "unknown"
) extends TextRenderSink
    with ToolApprovalHook:
  private val renderer = new ChatAnsiRenderer
  private var messages = Vector.empty[ChatLayout.Message]
  private var parsedDiffHunks = Vector.empty[ChatLayout.DiffHunk]
  private var tabs = Vector("chat")
  private var auxiliary = false
  private var scrollOffset = 0
  private var cancelled = false
  private var deltaCount = 0
  private var turnCount = 0
  private var streamState = "idle"
  private var taskSnapshot = PlanTab.Snapshot.idle
  private var taskCursor = 0
  private var tasksVisible = false

  /** Replace the display snapshot from the existing ADR-028 plan panel. */
  def updateTasks(snapshot: PlanTab.Snapshot): Unit = synchronized:
    taskSnapshot = snapshot
    taskCursor =
      taskCursor.max(0).min(snapshot.proposal.fold(0)(_.steps.size - 1).max(0))
    if tasksVisible then draw(streamState)

  def showTasks(): Unit = synchronized:
    tasksVisible = true
    auxiliary = false
    draw(streamState)

  def moveTask(delta: Int): Unit = synchronized:
    val count = taskSnapshot.proposal.fold(0)(_.steps.size)
    if count > 0 then taskCursor = (taskCursor + delta).max(0).min(count - 1)
    if tasksVisible then draw(streamState)

  private var approvalSeq = 0L

  private var pendingApproval
      : Option[(String, zio.Promise[Nothing, ToolApprovalDecision])] = None

  def beginUser(text: String): Unit = synchronized:
    auxiliary = false
    cancelled = false
    turnCount += 1
    streamState = "streaming"
    messages = (messages :+ ChatLayout.Message(ChatLayout.Role.User, text))
      .takeRight(ChatLayout.HistoryLimit)
    draw("streaming")

  def beginAssistant(): Unit = synchronized:
    streamState = "streaming"
    deltaCount = 0
    parsedDiffHunks = Vector.empty
    messages = messages :+ ChatLayout.Message(ChatLayout.Role.Assistant, "")
    draw("streaming")

  def completeAssistant(): Unit = synchronized:
    if !cancelled then
      streamState = "idle"
      draw("ready")

  def finishAssistant(): Unit = completeAssistant()

  def cancelAssistant(): Unit = synchronized:
    cancelled = true
    streamState = "cancelled"
    draw("cancelled | partial response retained")

  def showAux(): Unit = synchronized:
    auxiliary = true
    draw("auxiliary surface | :chat returns")

  def showChat(): Unit = synchronized:
    auxiliary = false
    tasksVisible = false
    scrollOffset = 0
    draw(streamState)

  def scrollUp(): Unit = synchronized:
    scrollOffset = (scrollOffset + 1).min(ChatLayout.HistoryLimit)
    draw(s"scrolled up $scrollOffset")

  def scrollDown(): Unit = synchronized:
    scrollOffset = (scrollOffset - 1).max(0)
    draw(if scrollOffset == 0 then "ready" else s"scrolled up $scrollOffset")

  def textDelta(text: String): UIO[Unit] =
    zio.ZIO.sleep(scriptedDeltaDelay) *> zio.ZIO.succeed {
      synchronized:
        if !cancelled then
          deltaCount += 1
          if messages.lastOption.exists(_.role == ChatLayout.Role.Assistant)
          then
            val last = messages.last
            val updated = last.copy(text = last.text + text)
            messages = messages.updated(
              messages.size - 1,
              updated
            )
            parsedDiffHunks = ChatLayoutSink.parseDiffHunks(updated.text)
          else
            messages =
              messages :+ ChatLayout.Message(ChatLayout.Role.Assistant, text)
          streamState = "streaming"
          draw(s"streaming turn $turnCount chunk $deltaCount")
    }

  override def toolCallCard(
      name: String,
      arguments: String,
      outcome: Option[String]
  ): UIO[Unit] = ZIO.succeed {
    synchronized:
      val summary = ChatLayoutSink.bounded(arguments, 240)
      val result = outcome.fold("running")(_.take(240))
      val card = s"tool card | $name | args: $summary | outcome: $result"
      messages = (messages :+ ChatLayout.Message(ChatLayout.Role.Status, card))
        .takeRight(ChatLayout.HistoryLimit)
      draw(if outcome.isDefined then "ready" else "tool running")
  }

  /** Wait for a decision delivered by the shell's existing single-writer input
    * loop. The generated token binds the decision to this one invocation; a
    * duplicate or stale token cannot settle a later call.
    */
  override def decide(context: ToolApprovalContext): UIO[ToolApprovalDecision] =
    zio.Promise
      .make[Nothing, ToolApprovalDecision]
      .flatMap: decision =>
        val registered = synchronized:
          if pendingApproval.nonEmpty then false
          else
            approvalSeq += 1
            val token = s"${context.requestId}-$approvalSeq"
            pendingApproval = Some(token -> decision)
            val card =
              s"approval required | $token | ${context.tool} | ${context.recovery} | " +
                s"args: ${ChatLayoutSink.bounded(context.argumentsJson, 240)} | " +
                s"type :approve $token or :deny $token"
            messages =
              (messages :+ ChatLayout.Message(ChatLayout.Role.Status, card))
                .takeRight(ChatLayout.HistoryLimit)
            draw("approval pending")
            true
        if !registered then ZIO.succeed(ToolApprovalDecision.Deny)
        else
          decision.await.ensuring(
            ZIO.succeed {
              synchronized:
                pendingApproval match
                  case Some((_, current)) if current eq decision =>
                    pendingApproval = None
                    draw("approval cancelled")
                  case _ => ()
            }
          )

  /** Resolve only the currently displayed request. Returns false for stale,
    * duplicate, malformed, or unknown decisions.
    */
  def resolveToolApproval(token: String, approve: Boolean): UIO[Boolean] =
    val resolution = synchronized:
      pendingApproval match
        case Some((expected, promise)) if token == expected =>
          pendingApproval = None
          val result =
            if approve then ToolApprovalDecision.Approve
            else ToolApprovalDecision.Deny
          Some((promise, result))
        case _ => None
    resolution match
      case Some((promise, result)) =>
        promise.succeed(result).as(true) <* ZIO.succeed {
          synchronized:
            draw(if approve then "approval granted" else "approval denied")
        }
      case None => ZIO.succeed(false)

  private def draw(status: String): Unit =
    val auxiliaryLines =
      if tasksVisible then Some(taskLines)
      else if auxiliary then
        Some(Vector("Auxiliary surface", "Session status: ready"))
      else None
    val frame = ChatLayout.Frame(
      columns = Option(System.getProperty("raider.chat.columns"))
        .flatMap(_.toIntOption)
        .getOrElse(100),
      rows = Option(System.getProperty("raider.chat.rows"))
        .flatMap(_.toIntOption)
        .getOrElse(30),
      messages = messages,
      tabs = tabs,
      status =
        s"model $model | budget unknown | stream $streamState | $status | :aux :chat :help :quit",
      auxiliary = auxiliaryLines,
      scrollOffset = scrollOffset,
      diffHunks = parsedDiffHunks
    )
    val output = renderer.draw(frame).mkString
    if output.nonEmpty then renderBridge.accept(output)

  private def taskLines: Vector[String] =
    val proposal = taskSnapshot.proposal
    val lines = Vector(s"Tasks / todo — ${taskSnapshot.phase.name}") ++
      proposal.fold(Vector("No plan tasks available")) { plan =>
        Vector(s"${plan.title}") ++ plan.steps.zipWithIndex.map {
          (step, index) =>
            val marker = if index == taskCursor then ">" else " "
            f"$marker ${index + 1}%2d. $step"
        }
      }
    lines :+ "Use :task-up / :task-down to navigate; :chat returns"

end ChatLayoutSink

private object ChatLayoutSink:

  private val FencedDiff = raw"(?s)```diff\s*\n(.*?)```".r

  /** Parse complete streamed diff fences with the existing tools parser.
    * Streaming-safe: an unterminated fence matches nothing yet, and a typed
    * parse error drops only that fence (the raw text still renders). CR and
    * CRLF are normalized first so carriage returns never reach rendered hunk
    * rows.
    */
  private[repl] def parseDiffHunks(text: String): Vector[ChatLayout.DiffHunk] =
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    FencedDiff
      .findAllMatchIn(normalized)
      .flatMap(m => UnifiedDiff.parse(m.group(1)).toOption.toVector.flatten)
      .map { hunk =>
        ChatLayout.DiffHunk(
          hunk.header,
          hunk.lines.toVector.map:
            case UnifiedDiff.HunkLine.Context(line) =>
              ChatLayout.DiffLine.Context(line)
            case UnifiedDiff.HunkLine.Add(line) => ChatLayout.DiffLine.Add(line)
            case UnifiedDiff.HunkLine.Del(line) =>
              ChatLayout.DiffLine.Remove(line)
        )
      }
      .toVector

  def bounded(value: String, limit: Int): String =
    val singleLine = value.replace('\r', ' ').replace('\n', ' ')
    if singleLine.length <= limit then singleLine
    else singleLine.take(limit - 1) + "…"
