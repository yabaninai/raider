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
    scriptedDeltaDelay: zio.Duration = zio.Duration.fromMillis(0)
) extends TextRenderSink:
  private val renderer = new ChatAnsiRenderer
  private var messages = Vector.empty[ChatLayout.Message]
  private var tabs = Vector("chat")
  private var auxiliary = false
  private var scrollOffset = 0
  private var cancelled = false
  private var deltaCount = 0
  private var turnCount = 0

  def beginUser(text: String): Unit = synchronized:
    auxiliary = false
    cancelled = false
    turnCount += 1
    messages = (messages :+ ChatLayout.Message(ChatLayout.Role.User, text))
      .takeRight(ChatLayout.HistoryLimit)
    draw("streaming")

  def beginAssistant(): Unit = synchronized:
    deltaCount = 0
    messages = messages :+ ChatLayout.Message(ChatLayout.Role.Assistant, "")
    draw("streaming")

  def completeAssistant(): Unit = synchronized:
    if !cancelled then draw("ready")

  def finishAssistant(): Unit = completeAssistant()

  def cancelAssistant(): Unit = synchronized:
    cancelled = true
    draw("cancelled | partial response retained")

  def showAux(): Unit = synchronized:
    auxiliary = true
    draw("auxiliary surface | :chat returns")

  def showChat(): Unit = synchronized:
    auxiliary = false
    scrollOffset = 0
    draw("ready")

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
          draw(s"streaming turn $turnCount chunk $deltaCount")
    }

  private def draw(status: String): Unit =
    val auxiliaryLines =
      if auxiliary then
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
      status = s"$status | :aux :chat :help :quit",
      auxiliary = auxiliaryLines,
      scrollOffset = scrollOffset
    )
    val output = renderer.draw(frame).mkString
    if output.nonEmpty then renderBridge.accept(output)

end ChatLayoutSink
