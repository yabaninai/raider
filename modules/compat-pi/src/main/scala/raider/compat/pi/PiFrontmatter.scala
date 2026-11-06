package raider.compat.pi

/** Bounded parser for pi agent-profile frontmatter (card RAI-032).
  *
  * Mirrors only the YAML subset that pi's own discovery source
  * (`vendor/pi-profiles/upstream/agents.ts`) relies on for the example corpus:
  * `---` delimiters, `key: scalar` fields, inline `[a, b]` arrays and a
  * free-form Markdown body. Block-style YAML (list items, block scalars) is
  * reported as an unsupported shape instead of being guessed at; a YAML
  * dependency is deliberately not added.
  */
object PiFrontmatter:

  sealed trait Value
  final case class Scalar(raw: String) extends Value
  final case class ScalarList(items: List[String]) extends Value
  final case class UnsupportedShape(detail: String) extends Value

  final case class Doc(fields: List[(String, Value)], body: String)

  /** Parse `---`-delimited frontmatter; Left is a typed rejection reason. */
  def parse(text: String): Either[String, Doc] =
    val lines = text.replace("\r\n", "\n").linesIterator.toList
    lines match
      case first :: rest if first.trim == "---" =>
        val close = rest.indexWhere(_.trim == "---")
        if close < 0 then
          Left("unterminated frontmatter: no closing '---' delimiter")
        else
          val body = rest.drop(close + 1).mkString("\n")
          parseFields(rest.take(close)).map(Doc(_, body))
      case _ =>
        Left("missing frontmatter: file does not open with '---'")

  /** Accumulator entry: key, inline remainder (None = the colon line was empty,
    * so block items may follow), collected block items.
    */
  private type Acc = List[(String, Option[String], List[String])]

  private def parseFields(
      lines: List[String]
  ): Either[String, List[(String, Value)]] =
    val nonBlank = lines.filterNot(_.trim.isEmpty)
    nonBlank
      .foldLeft[Either[String, Acc]](Right(Nil)) {
        case (Left(err), _) => Left(err)
        case (Right(acc), line) =>
          val t = line.trim
          if t.startsWith("- ") then blockItem(acc, t)
          else keyedField(acc, t)
      }
      .map { acc =>
        acc.reverse.map { (key, inline, blockItems) =>
          inline match
            case Some(s) =>
              key -> classifyInline(s)
            case None =>
              if blockItems.isEmpty then key -> Scalar("")
              else
                key -> UnsupportedShape(
                  s"block-style YAML list ('- item' lines) is not supported " +
                    s"by the bounded importer parser (${blockItems.length} item(s))"
                )
        }
      }

  private def blockItem(acc: Acc, t: String): Either[String, Acc] =
    acc match
      case (key, None, items) :: rest =>
        Right((key, None, t.drop(2).trim :: items) :: rest)
      case (key, Some(_), _) :: _ =>
        Left(s"frontmatter list item after an inline value for key '$key'")
      case Nil =>
        Left("frontmatter list item without a preceding 'key:' field")

  private def keyedField(acc: Acc, t: String): Either[String, Acc] =
    val idx = t.indexOf(':')
    if idx <= 0 then
      Left(s"frontmatter field is not 'key: value': ${t.take(60)}")
    else
      val key = t.take(idx).trim
      val rest = t.drop(idx + 1).trim
      if acc.exists(_._1 == key) then Left(s"duplicate frontmatter key: '$key'")
      else
        val inline = if rest.isEmpty then None else Some(rest)
        Right((key, inline, Nil) :: acc)

  private def classifyInline(s: String): Value =
    val t = s.trim
    if t.startsWith("[") && t.endsWith("]") then
      val inner = t.substring(1, t.length - 1)
      ScalarList(inner.split(',').toList.map(_.trim).filter(_.nonEmpty))
    else if t.startsWith("|") || t.startsWith(">") then
      UnsupportedShape(
        "multi-line block scalar (YAML '|'/'>' shape) is not supported " +
          "by the bounded importer parser"
      )
    else Scalar(t)
