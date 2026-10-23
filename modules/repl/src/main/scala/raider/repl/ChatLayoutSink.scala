package raider.repl

import raider.repl.facade.TextRenderSink
import raider.ui.{ChatAnsiRenderer, ChatLayout}
import zio.UIO

/** Streaming chat view used by the opt-in `--chat` shell. The sink is created
  * in the Scala REPL interpreter world and owns only bounded render state; the
  * JLine reader remains the input/history owner.
  */
final class ChatLayoutSink(
    renderBridge: java.util.function.Consumer[String],
    scriptedDeltaDelay: zio.Duration = zio.Duration.fromMillis(0),
    model: String = "unknown"
) extends TextRenderSink:
  private val renderer = new ChatAnsiRenderer
  private var messages = Vector.empty[ChatLayout.Message]
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
            messages = messages.updated(
              messages.size - 1,
              last.copy(text = last.text + text)
            )
          else
            messages =
              messages :+ ChatLayout.Message(ChatLayout.Role.Assistant, text)
          streamState = "streaming"
          draw(s"streaming turn $turnCount chunk $deltaCount")
    }

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
      scrollOffset = scrollOffset
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
