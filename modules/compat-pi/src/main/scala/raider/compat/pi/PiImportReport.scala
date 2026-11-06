package raider.compat.pi

import zio.json.*

/** Explicit per-field entry used across supported/ignored/rejected sections. */
final case class FieldEntry(field: String, detail: String)

object FieldEntry:
  given JsonCodec[FieldEntry] = DeriveJsonCodec.gen[FieldEntry]

/** One mapped pi tool and its Raider target. */
final case class ToolMapping(
    pi: String,
    raider: String,
    degraded: Boolean,
    note: String
)

object ToolMapping:
  given JsonCodec[ToolMapping] = DeriveJsonCodec.gen[ToolMapping]

/** One pi tool with no Raider equivalent (rejected, never approximated). */
final case class UnsupportedTool(pi: String, reason: String)

object UnsupportedTool:
  given JsonCodec[UnsupportedTool] = DeriveJsonCodec.gen[UnsupportedTool]

/** Tool policy of one imported profile.
  *
  * kind: "explicit" (frontmatter tool list), "piDefault" (no tools declared; pi
  * default built-ins assumed) or "rejected" (tools declared in a shape the
  * bounded parser refuses to guess at).
  */
final case class PiToolPolicy(
    kind: String,
    mapped: List[ToolMapping],
    unsupported: List[UnsupportedTool]
)

object PiToolPolicy:
  given JsonCodec[PiToolPolicy] = DeriveJsonCodec.gen[PiToolPolicy]

/** An imported pi agent profile (the agents/<name>.md files). */
final case class PiImportedProfile(
    name: String,
    sourcePath: String,
    description: String,
    systemPrompt: String,
    requestedModel: String,
    toolPolicy: PiToolPolicy,
    supported: List[FieldEntry],
    ignored: List[FieldEntry],
    rejected: List[FieldEntry]
)

object PiImportedProfile:
  given JsonCodec[PiImportedProfile] = DeriveJsonCodec.gen[PiImportedProfile]

/** An imported pi workflow prompt preset (the prompts/<name>.md files). */
final case class PiImportedPrompt(
    name: String,
    sourcePath: String,
    description: String,
    promptText: String,
    supported: List[FieldEntry],
    ignored: List[FieldEntry],
    rejected: List[FieldEntry]
)

object PiImportedPrompt:
  given JsonCodec[PiImportedPrompt] = DeriveJsonCodec.gen[PiImportedPrompt]

/** A corpus file that is documentation/reference, not a profile or prompt. */
final case class PiReferenceFile(path: String, role: String, detail: String)

object PiReferenceFile:
  given JsonCodec[PiReferenceFile] = DeriveJsonCodec.gen[PiReferenceFile]

/** A corpus file that could not be imported, with a typed reason. */
final case class PiRejectedFile(path: String, reason: String)

object PiRejectedFile:
  given JsonCodec[PiRejectedFile] = DeriveJsonCodec.gen[PiRejectedFile]

/** Cross-cutting explicit report section entry. */
final case class PiSectionEntry(scope: String, detail: String)

object PiSectionEntry:
  given JsonCodec[PiSectionEntry] = DeriveJsonCodec.gen[PiSectionEntry]

/** Honesty counters. Definitions (pinned by the golden spec, PIIMP-01):
  *
  *   - supported: profile/prompt field entries that were imported + mapped pi
  *     tools;
  *   - ignored: enumerated non-imported semantics (unknown frontmatter keys,
  *     workflow chain placeholders) + reference files;
  *   - rejected: rejected fields, unsupported tools and rejected files.
  */
final case class Counts(supported: Int, ignored: Int, rejected: Int)

object Counts:
  given JsonCodec[Counts] = DeriveJsonCodec.gen[Counts]

/** Full pi -> Raider compatibility import report (card RAI-032). */
final case class PiImportReport(
    schemaVersion: Int,
    provenance: PiProvenance,
    snapshotId: String,
    profiles: List[PiImportedProfile],
    workflowPrompts: List[PiImportedPrompt],
    referenceFiles: List[PiReferenceFile],
    parityGaps: List[PiSectionEntry],
    degraded: List[PiSectionEntry],
    rejectedFiles: List[PiRejectedFile],
    counts: Counts
):

  /** Canonical JSON: object keys sorted alphabetically, 2-space indentation,
    * trailing newline. Deterministic across runs and machines (no timestamps,
    * no map iteration order, no locale-dependent number formatting).
    */
  def renderCanonicalJson: String =
    val compact = this.toJson
    MiniJson.parse(compact) match
      case Right(ast) => CanonicalJson.pretty(ast) + "\n"
      case Left(err) =>
        sys.error(s"internal: report is not valid JSON (${err.take(80)})")

object PiImportReport:
  given JsonCodec[PiImportReport] = DeriveJsonCodec.gen[PiImportReport]
  val SchemaVersion: Int = 1

/** Strict minimal JSON parser used to normalize the derived codec output into
  * the pinned canonical form. Kept dependency-free and deliberately small: it
  * only ever sees JSON produced by this module's derived zio-json codecs.
  */
private[pi] object MiniJson:

  sealed trait Value
  final case class JStr(value: String) extends Value
  final case class JNum(raw: String) extends Value
  final case class JBool(value: Boolean) extends Value
  case object JNull extends Value
  final case class JArr(items: List[Value]) extends Value
  final case class JObj(fields: List[(String, Value)]) extends Value

  def parse(text: String): Either[String, Value] =
    try
      val p = new Parser(text)
      p.skipWs()
      val v = p.value()
      p.skipWs()
      if !p.atEnd then
        throw new ParseException("trailing characters after JSON value", p.pos)
      Right(v)
    catch case e: ParseException => Left(e.getMessage)

  private final class ParseException(message: String, pos: Int)
      extends RuntimeException(s"$message (at offset $pos)")

  private final class Parser(text: String):
    var pos: Int = 0

    def atEnd: Boolean = pos >= text.length

    def skipWs(): Unit =
      while !atEnd && " \t\n\r".indexOf(text.charAt(pos)) >= 0 do pos += 1

    def value(): Value =
      if atEnd then throw new ParseException("unexpected end of input", pos)
      text.charAt(pos) match
        case '{' => obj()
        case '[' => arr()
        case '"' => JStr(string())
        case 't' => literal("true"); JBool(true)
        case 'f' => literal("false"); JBool(false)
        case 'n' => literal("null"); JNull
        case _   => JNum(number())

    private def literal(word: String): Unit =
      if !text.startsWith(word, pos) then
        throw new ParseException(s"expected '$word'", pos)
      pos += word.length

    private def obj(): Value =
      expect('{')
      skipWs()
      if !atEnd && text.charAt(pos) == '}' then
        pos += 1
        JObj(Nil)
      else
        val buf = List.newBuilder[(String, Value)]
        var done = false
        while !done do
          skipWs()
          val k = string()
          skipWs()
          expect(':')
          skipWs()
          buf += ((k, value()))
          skipWs()
          if atEnd then throw new ParseException("unterminated object", pos)
          text.charAt(pos) match
            case ',' => pos += 1
            case '}' => pos += 1; done = true
            case _   => throw new ParseException("expected ',' or '}'", pos)
        JObj(buf.result())

    private def arr(): Value =
      expect('[')
      skipWs()
      if !atEnd && text.charAt(pos) == ']' then
        pos += 1
        JArr(Nil)
      else
        val buf = List.newBuilder[Value]
        var done = false
        while !done do
          skipWs()
          buf += value()
          skipWs()
          if atEnd then throw new ParseException("unterminated array", pos)
          text.charAt(pos) match
            case ',' => pos += 1
            case ']' => pos += 1; done = true
            case _   => throw new ParseException("expected ',' or ']'", pos)
        JArr(buf.result())

    private def string(): String =
      expect('"')
      val sb = new StringBuilder
      var done = false
      while !done do
        if atEnd then throw new ParseException("unterminated string", pos)
        val c = text.charAt(pos)
        pos += 1
        if c == '"' then done = true
        else if c == '\\' then
          if atEnd then throw new ParseException("unterminated escape", pos)
          val e = text.charAt(pos)
          pos += 1
          e match
            case '"'  => sb += '"'
            case '\\' => sb += '\\'
            case '/'  => sb += '/'
            case 'b'  => sb += '\b'
            case 'f'  => sb += '\f'
            case 'n'  => sb += '\n'
            case 'r'  => sb += '\r'
            case 't'  => sb += '\t'
            case 'u'  => sb.append(unicodeEscape())
            case other =>
              throw new ParseException(s"invalid escape '\\$other'", pos)
        else sb += c
      sb.result()

    private def unicodeEscape(): Char =
      if pos + 4 > text.length then
        throw new ParseException("truncated \\u escape", pos)
      val hex = text.substring(pos, pos + 4)
      pos += 4
      try Integer.parseInt(hex, 16).toChar
      catch
        case _: NumberFormatException =>
          throw new ParseException(s"invalid \\u escape '$hex'", pos - 4)

    private def number(): String =
      val start = pos
      if atEnd || !"-+0123456789".contains(text.charAt(pos)) then
        throw new ParseException("invalid JSON value", pos)
      while !atEnd && "-+0123456789.eE".contains(text.charAt(pos)) do pos += 1
      val raw = text.substring(start, pos)
      try scala.math.BigDecimal(raw)
      catch
        case _: NumberFormatException =>
          throw new ParseException(s"invalid number '$raw'", start)
      raw

    private def expect(c: Char): Unit =
      if atEnd || text.charAt(pos) != c then
        throw new ParseException(s"expected '$c'", pos)
      pos += 1

/** Canonical pretty printer: sorted object keys, 2-space indent, no trailing
  * whitespace, stable number/string forms.
  */
private[pi] object CanonicalJson:

  def pretty(value: MiniJson.Value): String =
    val sb = new StringBuilder
    write(value, sb, 0)
    sb.result()

  private def write(v: MiniJson.Value, sb: StringBuilder, depth: Int): Unit =
    val pad = "  " * depth
    v match
      case MiniJson.JObj(fields) =>
        if fields.isEmpty then sb ++= "{}"
        else
          sb ++= "{\n"
          val sorted = fields.sortBy(_._1)
          sorted.zipWithIndex.foreach { case ((k, value), i) =>
            sb ++= ("  " * (depth + 1))
            sb ++= escape(k)
            sb ++= ": "
            write(value, sb, depth + 1)
            if i < sorted.length - 1 then sb ++= ","
            sb ++= "\n"
          }
          sb ++= pad
          sb ++= "}"
      case MiniJson.JArr(items) =>
        if items.isEmpty then sb ++= "[]"
        else
          sb ++= "[\n"
          items.zipWithIndex.foreach { case (item, i) =>
            sb ++= ("  " * (depth + 1))
            write(item, sb, depth + 1)
            if i < items.length - 1 then sb ++= ","
            sb ++= "\n"
          }
          sb ++= pad
          sb ++= "]"
      case MiniJson.JStr(value)  => sb ++= escape(value)
      case MiniJson.JNum(raw)    => sb ++= raw
      case MiniJson.JBool(value) => sb ++= value.toString
      case MiniJson.JNull        => sb ++= "null"

  private def escape(s: String): String =
    val sb = new StringBuilder
    sb += '"'
    s.foreach {
      case '"'          => sb ++= "\\\""
      case '\\'         => sb ++= "\\\\"
      case '\b'         => sb ++= "\\b"
      case '\f'         => sb ++= "\\f"
      case '\n'         => sb ++= "\\n"
      case '\r'         => sb ++= "\\r"
      case '\t'         => sb ++= "\\t"
      case c if c < ' ' => sb ++= f"\\u${c.toInt}%04x"
      case c            => sb += c
    }
    sb += '"'
    sb.result()
