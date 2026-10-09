package raider.tools.files.edit

/** Unified-diff engine (nightly Phase 2.4): pure parsing + application of
  * `@@ -start,count +start,count @@` hunks over in-memory content.
  *
  * Application semantics: every hunk carries an EXPECTED sequence (context and
  * deletion lines). Hunks apply in order, each anchored at its declared
  * oldStart when the context matches there, otherwise at the first earlier
  * position where the expected window matches — context must actually match, a
  * wrong window is a typed error, never a blind insert. Hunks must land at
  * strictly increasing, non-overlapping positions.
  *
  * Line-based: the engine works on the content split into lines (no trailing
  * newline in each element); callers preserve the file's trailing-newline
  * property.
  */
object UnifiedDiff:

  sealed trait HunkLine:
    def text: String

  object HunkLine:
    final case class Context(text: String) extends HunkLine
    final case class Add(text: String) extends HunkLine
    final case class Del(text: String) extends HunkLine

  final case class Hunk(
      oldStart: Int,
      oldCount: Int,
      newStart: Int,
      newCount: Int,
      lines: List[HunkLine],
      header: String
  ):

    /** Lines the original is expected to show in the hunk's window. */
    lazy val expected: List[String] =
      lines.collect:
        case HunkLine.Context(t) => t
        case HunkLine.Del(t)     => t

    /** Lines that replace the expected window. */
    lazy val replacement: List[String] =
      lines.collect:
        case HunkLine.Context(t) => t
        case HunkLine.Add(t)     => t

  private val HunkHeader =
    raw"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*".r

  /** Parse a unified diff body (headers `---/+++` optional, ignored). */
  def parse(diff: String): Either[String, List[Hunk]] =
    val lines = diff.linesIterator.toList
    val hunks = scala.collection.mutable.ListBuffer.empty[Hunk]
    var current: Option[Hunk.Builder] = None

    def close(): Either[String, Unit] = current match
      case None => Right(())
      case Some(b) =>
        if b.lines.isEmpty then Left(s"hunk ${b.header} has no body")
        else
          hunks += b.build()
          current = None
          Right(())

    val result = lines.foldLeft[Either[String, Unit]](Right(())) { (acc, raw) =>
      acc.flatMap { _ =>
        raw match
          case HunkHeader(o, oc, n, nc) =>
            close().map { _ =>
              current = Some(
                Hunk.Builder(
                  s"@@ -$o,$oc +$n,$nc @@",
                  o.toInt,
                  oc.toInt,
                  n.toInt,
                  nc.toInt
                )
              )
            }
          case _ =>
            current match
              case None =>
                // file-level header/metadata lines are ignored outside hunks
                Right(())
              case Some(b) =>
                raw match
                  case l if l.startsWith(" ") =>
                    b.add(HunkLine.Context(l.drop(1))); Right(())
                  case l if l.startsWith("+") =>
                    b.add(HunkLine.Add(l.drop(1))); Right(())
                  case l if l.startsWith("-") =>
                    b.add(HunkLine.Del(l.drop(1))); Right(())
                  case l if l.startsWith("\\") =>
                    Right(()) // "\ No newline at end of file"
                  case "" =>
                    b.add(HunkLine.Context("")); Right(())
                  case other =>
                    Left(
                      s"malformed diff line inside hunk ${b.header}: " +
                        other.take(40)
                    )
      }
    }
    result.flatMap(_ => close().map(_ => hunks.toList))

  /** Apply hunks to line content. Returns the new lines or a typed error. */
  def applyTo(
      src: List[String],
      hunks: List[Hunk]
  ): Either[String, List[String]] =
    hunks
      .foldLeft[Either[String, (List[String], Int)]](Right((src, 0))) {
        case (acc, hunk) =>
          acc.flatMap { (lines, consumed) =>
            applyHunk(lines, hunk, consumed).map { (out, nextPos) =>
              (out, nextPos)
            }
          }
      }
      .map { (lines, _) => lines }

  private def applyHunk(
      src: List[String],
      hunk: Hunk,
      consumed: Int
  ): Either[String, (List[String], Int)] =
    val expected = hunk.expected
    val start0 = math.max(0, hunk.oldStart - 1)
    val candidates =
      if expected.isEmpty then LazyList.empty
      else
        val head = expected.head
        val positions =
          LazyList
            .range(0, src.length)
            .filter(p => p >= consumed && src(p) == head)
        // declared anchor first, then the earliest match in reading order
        positions.sortBy(p => if p == start0 then 0 else p + 1)
    candidates.find(p => windowMatches(src, p, expected)) match
      case Some(p) =>
        val out =
          src.take(p) ++ hunk.replacement ++ src.drop(p + expected.length)
        Right((out, p + hunk.replacement.length))
      case None =>
        Left(
          s"context mismatch: hunk ${hunk.header} does not match the file " +
            s"(expected ${expected.size} lines starting '${expected.headOption.take(1)}')"
        )

  private def windowMatches(
      src: List[String],
      pos: Int,
      expected: List[String]
  ): Boolean =
    expected.zipWithIndex.forall { (t, i) =>
      pos + i < src.length && src(pos + i) == t
    }

  private object Hunk:

    final class Builder(
        val header: String,
        oldStart: Int,
        oldCount: Int,
        newStart: Int,
        newCount: Int
    ):
      private val buf = scala.collection.mutable.ListBuffer.empty[HunkLine]
      def add(l: HunkLine): Unit = buf += l
      def lines: List[HunkLine] = buf.toList

      def build(): Hunk =
        val body = buf.toList
        Hunk(
          oldStart,
          math.max(oldCount, body.count(l => !l.isInstanceOf[HunkLine.Add])),
          newStart,
          math.max(newCount, body.count(l => !l.isInstanceOf[HunkLine.Del])),
          body,
          header
        )

end UnifiedDiff

/** Multi-file git-style diff decomposition (RAI-067): splits a diff into
  * per-file sections (`diff --git a/… b/…`) and extracts the metadata needed to
  * validate ALL targets BEFORE anything is written.
  *
  * Pure parsing only — no filesystem access. Section bodies stay raw lines so
  * the existing single-file hunk engine (UnifiedDiff.parse) can be reused per
  * section.
  *
  * Limitations (typed, never silent): binary sections, rename/copy sections and
  * symlink/gitlink modes (120000/160000) parse into `Unsupported` kinds that
  * callers must turn into typed per-file failures. `--no-prefix` diffs and
  * quoted non-ASCII paths are not supported.
  */
object MultiFileDiff:

  enum Kind:
    case Modify, Create, Delete
    case Unsupported(reason: String)

  final case class FileSection(
      target: Option[String],
      kind: Kind,
      modes: Set[String],
      endsWithoutNewline: Boolean,
      body: List[String]
  )

  private val GitRest = "^diff --git (.+)$".r

  /** Split a git-style diff into sections. Structural garbage (content before
    * the first `diff --git`, no sections at all, unattributable targets) is a
    * global error; section-local problems surface per file at validation time.
    */
  def parseSections(diff: String): Either[String, List[FileSection]] =
    val lines = diff.linesIterator.toList
    val starts =
      lines.zipWithIndex.collect {
        case (l, i) if l.startsWith("diff --git ") => i
      }
    starts match
      case Nil =>
        Left(
          "no 'diff --git' sections found (multi-file apply requires git-style sections)"
        )
      case first :: _ if lines.take(first).exists(_.trim.nonEmpty) =>
        Left("unexpected content before the first 'diff --git' section")
      case first :: _ =>
        val bounds = starts :+ lines.length
        val parsed =
          starts.zip(bounds.tail).map((s, e) => parseSection(lines.slice(s, e)))
        duplicates(parsed)

  private def duplicates(
      ss: List[FileSection]
  ): Either[String, List[FileSection]] =
    val dup = ss.flatMap(_.target).groupBy(identity).collect {
      case (t, ts) if ts.size > 1 => t
    }
    if dup.nonEmpty then
      Left(s"duplicate target in diff: ${dup.mkString(", ")}")
    else Right(ss)

  private def parseSection(body: List[String]): FileSection =
    var gitOld: Option[String] = None
    var gitNew: Option[String] = None
    var oldPath: Option[String] = None
    var newPath: Option[String] = None
    var newFile = false
    var deletedFile = false
    var renameOrCopy = false
    var binary = false
    val modes = scala.collection.mutable.LinkedHashSet.empty[String]

    body.headOption.foreach {
      case GitRest(rest) =>
        splitGitPaths(rest) match
          case (o, n) =>
            gitOld = o
            gitNew = n
      case _ => ()
    }

    var inHunks = false
    body.tail.foreach { l =>
      if l.startsWith("@@") then inHunks = true
      else if !inHunks then
        if l.startsWith("--- ") then oldPath = stripMarker(l.drop(4))
        else if l.startsWith("+++ ") then newPath = stripMarker(l.drop(4))
        else if l.startsWith("new file mode ") then
          newFile = true; modes += l.drop("new file mode ".length).trim
        else if l.startsWith("deleted file mode ") then
          deletedFile = true; modes += l.drop("deleted file mode ".length).trim
        else if l.startsWith("old mode ") then modes += l.drop(9).trim
        else if l.startsWith("new mode ") then modes += l.drop(9).trim
        else if l.startsWith("rename ") || l.startsWith("copy ") then
          renameOrCopy = true
        else if l.startsWith("Binary files ") || l.startsWith(
            "GIT binary patch"
          )
        then binary = true
        else if l.startsWith("index ") then
          l.split(' ')
            .lastOption
            .filter(_.forall(_.isDigit))
            .foreach(m => modes += m)
      // other extended headers (index hashes, similarity, …) are informational
    }

    val kind: Kind =
      if binary then Kind.Unsupported("binary patches are not supported")
      else if renameOrCopy then
        Kind.Unsupported("rename/copy sections are not supported")
      else if deletedFile then Kind.Delete
      else if newFile then Kind.Create
      else Kind.Modify

    val target: Option[String] = kind match
      case Kind.Delete         => oldPath.orElse(gitOld)
      case Kind.Create         => newPath.orElse(gitNew)
      case Kind.Modify         => newPath.orElse(oldPath).orElse(gitNew)
      case Kind.Unsupported(_) => newPath.orElse(oldPath).orElse(gitNew)

    FileSection(
      target = target,
      kind = kind,
      modes = modes.toSet,
      endsWithoutNewline = body.lastOption.exists(_.startsWith("\\")),
      body = body
    )

  /** `a/path` / `b/path` (optionally quoted, TAB-terminated) → relative path;
    * `/dev/null` → None.
    */
  private def stripMarker(s: String): Option[String] =
    val raw0 = s.takeWhile(_ != '\t').trim
    val raw =
      if raw0.length >= 2 && raw0.startsWith("\"") && raw0.endsWith("\"") then
        unquote(raw0)
      else raw0
    if raw == "/dev/null" then None
    else
      Some(
        if raw.startsWith("a/") || raw.startsWith("b/") then raw.drop(2)
        else raw
      )

  private def splitGitPaths(rest: String): (Option[String], Option[String]) =
    val trimmed = rest.trim
    if trimmed.startsWith("\"") then
      val end = trimmed.indexOf('"', 1)
      if end > 0 then
        val old = stripAOrB(Some(unquote(trimmed.take(end + 1))))
        val newPart = trimmed.drop(end + 1).trim
        val newP =
          if newPart.startsWith("\"") && newPart.endsWith("\"") then
            Some(unquote(newPart))
          else Some(newPart)
        (old, stripAOrB(newP))
      else (None, None)
    else
      trimmed.indexOf(" b/") match
        case -1 => (stripPrefix(trimmed), None)
        case i =>
          (stripPrefix(trimmed.take(i)), stripPrefix(trimmed.drop(i + 1)))

  private def stripAOrB(p: Option[String]): Option[String] =
    p.flatMap(stripPrefix)

  private def stripPrefix(s: String): Option[String] =
    val t = s.takeWhile(_ != '\t').trim
    if t == "/dev/null" || t.isEmpty then None
    else Some(if t.startsWith("a/") || t.startsWith("b/") then t.drop(2) else t)

  private def unquote(s: String): String =
    val sb = new StringBuilder
    val body = s.substring(1, s.length - 1)
    var i = 0
    while i < body.length do
      val c = body.charAt(i)
      if c == '\\' && i + 1 < body.length then
        i += 1
        body.charAt(i) match
          case 'n'  => sb.append('\n')
          case 't'  => sb.append('\t')
          case 'r'  => sb.append('\r')
          case '\\' => sb.append('\\')
          case '"'  => sb.append('"')
          case other =>
            sb.append('\\')
              .append(other) // octal escapes kept literal (limitation)
      else sb.append(c)
      i += 1
    sb.result()

end MultiFileDiff
