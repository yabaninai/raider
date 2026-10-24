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

  enum Theme:
    case Dark, Light

  final case class Message(role: Role, text: String)

  final case class Frame(
      columns: Int,
      rows: Int,
      messages: Vector[Message],
      tabs: Vector[String],
      status: String,
      auxiliary: Option[Vector[String]] = None,
      scrollOffset: Int = 0,
      theme: Theme = Theme.Dark
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
    (Vector("┌─ CHAT " + "─" * (columns - 8).max(0)) ++ padded ++
      Vector(tabLine, statusLine))
      .map(clip(_, columns))
      .zipWithIndex
      .map { (line, index) =>
        if index == 0 then colorFrameLine(line, frame.theme) else line
      }

  private def wrap(message: Message, columns: Int): Vector[String] =
    val prefix = message.role match
      case Role.User      => "you: "
      case Role.Assistant => "assistant: "
      case Role.Status    => "status: "
    val text = message.text
      .replace("\u001b", "␛")
      .replace("\r\n", "\n")
      .replace('\r', '\n')
    val blocks = Markdown.render(text)
    blocks.flatMap { block =>
      val initial = Vector.newBuilder[String]
      var current = new StringBuilder(prefix + block.prefix)
      var used = cellWidth(prefix + block.prefix)
      block.text.codePoints().toArray.foreach { cp =>
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
      val rendered = initial.result()
      val highlighted =
        if block.diff then rendered.map(Diff.color)
        else if block.codeLanguage.nonEmpty then
          rendered.map(line => Syntax.highlight(line, block.codeLanguage.get))
        else rendered.map(line => Markdown.color(line, block.kind))
      highlighted
    }

  private def colorFrameLine(line: String, theme: Theme): String =
    val color = theme match
      case Theme.Dark  => "\u001b[38;5;75m"
      case Theme.Light => "\u001b[38;5;25m"
    s"$color$line\u001b[0m"

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

/** Small, bounded streaming formatter. Every snapshot is parsed from scratch,
  * so incomplete fences and headings are safe to render and recover on update.
  */
private object Markdown:

  final case class Block(
      prefix: String,
      text: String,
      kind: String = "body",
      codeLanguage: Option[String] = None,
      diff: Boolean = false
  )

  private val Limit = 65536

  def render(input: String): Vector[Block] =
    val text = input.take(Limit).replace("\r\n", "\n").replace('\r', '\n')
    var fence: Option[String] = None
    text.split("\n", -1).toVector.map { raw =>
      if raw.trim.startsWith("```") then
        val tag = raw.trim.drop(3).take(16).toLowerCase
        fence = if fence.isEmpty then Some(tag) else None
        Block(
          "",
          if fence.nonEmpty then "┌────────────" else "└────────────",
          "fence"
        )
      else
        fence match
          case Some(language) =>
            Block("│ ", raw, "code", Some(language), language == "diff")
          case None if raw.startsWith("### ") => Block("", raw.drop(4), "h3")
          case None if raw.startsWith("## ")  => Block("", raw.drop(3), "h2")
          case None if raw.startsWith("# ")   => Block("", raw.drop(2), "h1")
          case None if raw.startsWith("- ") || raw.startsWith("* ") =>
            Block("", "• " + raw.drop(2), "list")
          case None if raw.matches("[0-9]+\\. .*") =>
            val dot = raw.indexOf('.')
            Block("", "• " + raw.drop(dot + 1).trim, "list")
          case None
              if raw.startsWith("@@ ") || raw.startsWith("--- ") || raw
                .startsWith("+++ ") =>
            Block("", raw, "diff", diff = true)
          case None => Block("", raw)
    }

  def color(line: String, kind: String): String =
    val code = kind match
      case "h1" | "h2" | "h3" => "\u001b[1;36m"
      case "list"             => "\u001b[36m"
      case "fence" | "code"   => "\u001b[90m"
      case _                  => ""
    if code.isEmpty then line else s"$code$line\u001b[0m"

private object Syntax:
  private val Max = 65536

  private val Token =
    raw"""(//.*|/\*.*?\*/|"(?:\\.|[^"])*"|'(?:\\.|[^'])*'|\b(?:val|var|def|class|object|trait|enum|if|else|match|case|new|true|false|null|extends|import|package)\b|\b[0-9]+(?:\.[0-9]+)?\b)""".r

  def highlight(line: String, language: String): String =
    if language != "scala" && language != "json" && language != "" then line
    else
      val bounded = line.take(Max)
      Token.replaceAllIn(
        bounded,
        m =>
          val token = m.matched
          val color =
            if token.startsWith("//") || token.startsWith("/*") then "90"
            else if token.startsWith("\"") || token.startsWith("'") then "32"
            else if token.headOption.exists(_.isDigit) then "33"
            else if language == "json" && Set("true", "false", "null").contains(
                token
              )
            then "35"
            else "34"
          java.util.regex.Matcher
            .quoteReplacement(s"\u001b[${color}m$token\u001b[0m")
      )

private object Diff:

  def color(line: String): String =
    val marker = line.indexOf("│ ")
    val content = if marker >= 0 then line.drop(marker + 2) else line
    val color =
      if content.startsWith("+") && !content.startsWith("+++") then "32"
      else if content.startsWith("-") && !content.startsWith("---") then "31"
      else if content.startsWith("@@") then "36"
      else ""
    if color.isEmpty then line
    else
      val prefix = if marker >= 0 then line.take(marker + 2) else ""
      s"$prefix\u001b[${color}m$content\u001b[0m"

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
