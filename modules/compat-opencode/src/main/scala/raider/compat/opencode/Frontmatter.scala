package raider.compat.opencode

import zio.Chunk
import zio.json.ast.Json

import scala.collection.mutable

/** Line-based parser for the YAML frontmatter subset used by OpenCode agent
  * markdown (pinned ConfigMarkdown + the sanitize fallback in
  * vendor/opencode-profiles/upstream/core/config-markdown.ts): scalars with
  * optional quoting, booleans, numbers, nested maps in key order, inline JSON
  * sequence items, block scalars ("|"), comments, and blank lines. Values
  * with unquoted colons keep everything after the first colon.
  */
object Frontmatter:

  final case class Parsed(fields: Json.Obj, body: String, issues: Vector[String])

  def parse(content: String): Parsed =
    val lines = content.split("\n", -1).toVector.map(line => line.stripSuffix("\r"))
    if lines.isEmpty || lines.head.trim != "---" then
      Parsed(Json.Obj(List.empty*), content, Vector.empty)
    else
      val closing = lines.indexWhere(line => line.trim == "---", 1)
      if closing < 0 then
        Parsed(Json.Obj(List.empty*), content, Vector("unterminated frontmatter block"))
      else
        val body = lines.drop(closing + 1).mkString("\n")
        Parsed(parseFields(lines.slice(1, closing)), body, Vector.empty)

  private final class Frame(val indent: Int, val key: String, val buf: mutable.ListBuffer[(String, Json)])

  private def parseFields(lines: Vector[String]): Json.Obj =
    val numbered = lines.zipWithIndex.filter((l, _) => l.trim.nonEmpty && !l.trim.startsWith("#"))
    val root = mutable.ListBuffer.empty[(String, Json)]
    val stack = mutable.ArrayDeque.empty[Frame]

    def closeTop(): Unit =
      val f = stack.removeLast()
      val parent = if stack.isEmpty then root else stack.last.buf
      parent += (f.key -> Json.Obj(f.buf.toList*))

    // A line whose indent is smaller than the frame indent closes the frame;
    // a line at exactly the frame indent is a sibling inside it.
    def closeAbove(indent: Int): Unit =
      while stack.nonEmpty && indent < stack.last.indent do closeTop()

    def currentBuf(): mutable.ListBuffer[(String, Json)] =
      if stack.isEmpty then root else stack.last.buf

    def indentOf(lineNo: Int): Int = lines(lineNo).takeWhile(_ == ' ').length

    def posAfter(lineNo: Int, from: Int): Int =
      numbered.indexWhere(_._2 > lineNo, from) match
        case -1  => numbered.size
        case pos => pos

    var pos = 0
    while pos < numbered.size do
      val lineNo = numbered(pos)._2
      val line = lines(lineNo)
      val indent = line.takeWhile(_ == ' ').length
      val trimmed = line.trim
      closeAbove(indent)
      val buf = currentBuf()
      var consumedThrough = lineNo

      trimmed.indexOf(':') match
        case -1 =>
          pos = posAfter(lineNo, pos + 1) // stray line: ignore
        case colonAt =>
          val key = unquote(trimmed.substring(0, colonAt))
          val rawValue = trimmed.substring(colonAt + 1).trim

          if rawValue == "|" || rawValue == ">" then
            // Block scalar: consume the following deeper lines verbatim.
            val childIndentOpt = numbered.view.drop(pos + 1).headOption.map(entry => indentOf(entry._2))
            childIndentOpt match
              case Some(ci) =>
                val collected = mutable.ListBuffer.empty[String]
                var p = pos + 1
                var stop = false
                while p < numbered.size && !stop do
                  val n = numbered(p)._2
                  if indentOf(n) >= ci then
                    collected += lines(n).drop(ci)
                    consumedThrough = n
                    p += 1
                  else stop = true
                buf += (key -> Json.Str(collected.mkString("\n")))
                pos = posAfter(consumedThrough, p)
              case None =>
                buf += (key -> Json.Str(""))
                pos = posAfter(lineNo, pos + 1)
          else if rawValue.isEmpty then
            val nextOpt = numbered.view.drop(pos + 1).headOption
            nextOpt match
              case Some(next) =>
                val nextLineNo = next._2
                val nextIndent = indentOf(nextLineNo)
                if nextIndent > indent then
                  if lines(nextLineNo).trim.startsWith("-") then
                    // Sequence: consecutive dash items at the child indent.
                    val items = mutable.ListBuffer.empty[Json]
                    var p = pos + 1
                    var stop = false
                    while p < numbered.size && !stop do
                      val n = numbered(p)._2
                      val nl = lines(n)
                      if indentOf(n) == nextIndent && nl.trim.startsWith("-") then
                        items += parseItem(nl.trim.substring(1).trim)
                        consumedThrough = n
                        p += 1
                      else stop = true
                    buf += (key -> Json.Arr(Chunk.fromIterable(items)))
                    pos = posAfter(consumedThrough, p)
                  else
                    stack.append(Frame(nextIndent, key, mutable.ListBuffer.empty[(String, Json)]))
                    pos = posAfter(lineNo, pos + 1)
                else
                  buf += (key -> Json.Null)
                  pos = posAfter(lineNo, pos + 1)
              case None =>
                buf += (key -> Json.Null)
                pos = posAfter(lineNo, pos + 1)
          else
            buf += (key -> parseScalar(rawValue))
            pos = posAfter(lineNo, pos + 1)

    while stack.nonEmpty do closeTop()
    Json.Obj(root.toList*)

  private def parseItem(text: String): Json =
    if text.startsWith("{") || text.startsWith("[") then
      Json.decoder.decodeJson(text) match
        case Right(v) => v
        case Left(_)  => Json.Str(text)
    else parseScalar(text)

  private def parseScalar(raw: String): Json =
    val v = raw.trim
    if v.length >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'")) then
      Json.Str(v.substring(1, v.length - 1))
    else if v == "true" then Json.Bool(true)
    else if v == "false" then Json.Bool(false)
    else if v.matches("""-?\d+(\.\d+)?""") then Json.Num(BigDecimal(v).underlying)
    else Json.Str(v)

  private def unquote(key: String): String =
    val k = key.trim
    if k.length >= 2 && (k.startsWith("\"") && k.endsWith("\"") || k.startsWith("'") && k.endsWith("'")) then
      k.substring(1, k.length - 1)
    else k

  def str(fields: Json.Obj, key: String): Option[String] =
    fields.get(key).collect { case Json.Str(s) => s }

  def bool(fields: Json.Obj, key: String): Option[Boolean] =
    fields.get(key).collect { case Json.Bool(b) => b }

  def num(fields: Json.Obj, key: String): Option[String] =
    fields.get(key).collect { case Json.Num(n) => n.toString }

  def obj(fields: Json.Obj, key: String): Option[Json.Obj] =
    fields.get(key).collect { case o @ Json.Obj(_) => o }

  def keys(obj: Json.Obj): Vector[String] = obj.fields.map(_._1).toVector
