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
