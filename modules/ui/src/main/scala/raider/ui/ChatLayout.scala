package raider.ui

import org.jline.utils.WCWidth

/** Pure chat-first frame composition for the inline terminal surface.
  *
  * The chat history is bounded before rendering. Tabs and the auxiliary view
  * are chrome around the persistent conversation rather than replacement
  * screens. Input remains owned by the caller's JLine reader.
  */
object ChatLayout:

  enum Role:
    case User, Assistant, Status

  final case class Message(role: Role, text: String)

  final case class Frame(
      columns: Int,
      rows: Int,
      messages: Vector[Message],
      tabs: Vector[String],
      status: String,
      auxiliary: Option[Vector[String]] = None,
      scrollOffset: Int = 0
  )

  val HistoryLimit: Int = 200

  /** Render a bounded frame. Width is measured in terminal cells, not UTF-16
    * code units; wide and combining code points therefore do not overlap.
    */
  def render(frame: Frame): Vector[String] =
    val columns = math.max(1, frame.columns)
    val rows = math.max(4, frame.rows)
    val chatRows = rows - 3
    val source =
      frame.messages.takeRight(HistoryLimit).flatMap(wrap(_, columns))
    val auxiliary = frame.auxiliary.toVector.flatten.map("  " + _)
    val content = source ++ auxiliary
    val end = (content.size - math.max(0, frame.scrollOffset)).max(0)
    val body = content.take(end).takeRight(chatRows)
    val padded = Vector.fill(chatRows - body.size)("") ++ body
    val tabLine = frame.tabs.mkString("  ")
    val statusLine = frame.status
    (Vector(" CHAT ") ++ padded ++ Vector(tabLine, statusLine))
      .map(clip(_, columns))

  private def wrap(message: Message, columns: Int): Vector[String] =
    val prefix = message.role match
      case Role.User      => "you: "
      case Role.Assistant => "assistant: "
      case Role.Status    => "status: "
    val text = message.text.replace("\r\n", "\n").replace('\r', '\n')
    text.split("\n", -1).toVector.flatMap { line =>
      val initial = Vector.newBuilder[String]
      var current = new StringBuilder(prefix)
      var used = cellWidth(prefix)
      line.codePoints().toArray.foreach { cp =>
        val glyph = new String(Character.toChars(cp))
        val width = math.max(0, WCWidth.wcwidth(cp))
        if used + width > columns && current.nonEmpty then
          initial += current.toString
          current = new StringBuilder
          used = 0
        current.append(glyph)
        used += width
      }
      initial += current.toString
      initial.result()
    }

  private def clip(text: String, columns: Int): String =
    val out = new StringBuilder
    var used = 0
    val points = text.codePoints().toArray
    var i = 0
    var fits = true
    while i < points.length && fits do
      val cp = points(i)
      val width = math.max(0, WCWidth.wcwidth(cp))
      if used + width <= columns then
        out.append(new String(Character.toChars(cp)))
        used += width
      else fits = false
      i += 1
    out.toString

  private def cellWidth(text: String): Int =
    text.codePoints().toArray.map(cp => math.max(0, WCWidth.wcwidth(cp))).sum

end ChatLayout

/** Diff renderer seam: emits only rows whose logical text changed. The caller
  * owns the terminal write and lifecycle, keeping rendering independent of
  * input and runtime ownership.
  */
final class ChatAnsiRenderer:
  private var previous = Vector.empty[String]
  private var previousSize = Option.empty[(Int, Int)]

  def draw(frame: ChatLayout.Frame): Vector[String] = synchronized:
    val next = ChatLayout.render(frame)
    val size = (math.max(1, frame.columns), math.max(4, frame.rows))
    val resized = previousSize.exists(_ != size)
    val changed = next.indices.collect {
      case row if previous.lift(row).forall(_ != next(row)) =>
        s"\u001b[${row + 1};1H${next(row)}\u001b[K"
    }.toVector
    val clearOldRows =
      if resized && previous.size > next.size then
        (next.size until previous.size).map(row =>
          s"\u001b[${row + 1};1H\u001b[K"
        )
      else Vector.empty
    val repaint =
      if resized then
        next.indices.map { row =>
          s"\u001b[${row + 1};1H${next(row)}\u001b[K"
        }
      else changed
    previous = next
    previousSize = Some(size)
    if repaint.isEmpty && clearOldRows.isEmpty then Vector.empty
    else
      Vector("\u001b[?25l") ++ repaint ++ clearOldRows ++ Vector(
        "\u001b[?25h"
      )

  def invalidate(): Unit = synchronized {
    previous = Vector.empty
    previousSize = None
  }

end ChatAnsiRenderer
