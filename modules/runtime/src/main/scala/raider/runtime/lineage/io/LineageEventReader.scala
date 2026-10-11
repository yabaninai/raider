package raider.runtime.lineage.io

import raider.runtime.events.EventCodecs.given
import raider.runtime.events.EventEnvelope
import zio.json.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Result of reading an events.jsonl file (LIN-01B, ADR-027): every decodable
  * envelope in file order plus an honest marker when the LAST line was
  * undecodable. The prefix before a truncated tail is fully validated — the
  * marker never hides or invalidates the envelopes already read.
  */
final case class ParsedEvents(
    envelopes: Vector[EventEnvelope],
    truncatedTail: Option[TruncatedTail]
)

/** Honest marker of an undecodable LAST line (typically a torn final record
  * from a crash mid-write, or any other trailing garbage): 1-based line number
  * and the length of the raw text prefix in characters (line terminator
  * excluded). The tail is reported, never silently dropped OR silently guessed
  * — and it is not an error: the bytes before it are read normally.
  */
final case class TruncatedTail(lineNo: Int, rawPrefixLen: Int)

/** Typed content-level failures of [[LineageEventReader.read]] (LIN-01B).
  * Environment-level IO failures (missing/unreadable file) are NOT typed here —
  * they surface as the underlying `IOException`, the same honesty rule as the
  * journal slice (content distrust is content-level, IO is environment-level).
  */
enum LineageError:
  case MalformedLine(lineNo: Int, detail: String)
  case SchemaVersionUnsupported(found: Int)
  case SeqGap(jobId: String, expected: Long, found: Long)
  case Empty

/** Reads one events.jsonl file: JSONL, one [[EventEnvelope]] per line, decoded
  * with the frozen ADR-024 codecs ([[raider.runtime.events.EventCodecs]] — no
  * local wire dialect, no schema extensions).
  *
  * Rules (LIN-01B):
  *   - an empty file (zero lines) is `Left(LineageError.Empty)`;
  *   - the LAST line failing to decode is a truncated tail ([[TruncatedTail]])
  *     reported in [[ParsedEvents]] — not an error;
  *   - an undecodable line BEFORE the tail is `Left(MalformedLine)`;
  *   - a decoded envelope with `schemaVersion != 1` is
  *     `Left(SchemaVersionUnsupported)`;
  *   - per `jobId`, `seq` must run 1,2,3,... — monotonic from 1, no gaps and no
  *     duplicates; the first violation is `Left(SeqGap(expected, found))`;
  *   - lines are processed in file order; the FIRST violation wins.
  */
object LineageEventReader:

  def read(path: Path): Either[LineageError, ParsedEvents] =
    val lines =
      Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector
    if lines.isEmpty then Left(LineageError.Empty)
    else
      val lastLine = lines.length
      val good = Vector.newBuilder[EventEnvelope]
      val nextSeq = scala.collection.mutable.HashMap.empty[String, Long]
      var lineNo = 1
      var failure = Option.empty[LineageError]
      var tail = Option.empty[TruncatedTail]
      while lineNo <= lastLine && failure.isEmpty && tail.isEmpty do
        val raw = lines(lineNo - 1)
        raw.fromJson[EventEnvelope] match
          case Left(detail) =>
            // Only the last line gets the honest tail marker; any earlier
            // undecodable line is a hard MalformedLine.
            if lineNo == lastLine then
              tail = Some(TruncatedTail(lineNo, raw.length))
            else failure = Some(LineageError.MalformedLine(lineNo, detail))
          case Right(envelope) =>
            if envelope.schemaVersion != 1 then
              failure = Some(
                LineageError.SchemaVersionUnsupported(envelope.schemaVersion)
              )
            else
              val expected = nextSeq.getOrElse(envelope.jobId.value, 1L)
              if envelope.seq != expected then
                failure = Some(
                  LineageError.SeqGap(
                    envelope.jobId.value,
                    expected,
                    envelope.seq
                  )
                )
              else
                nextSeq(envelope.jobId.value) = envelope.seq + 1L
                good += envelope
        lineNo += 1
      failure match
        case Some(err) => Left(err)
        case None      => Right(ParsedEvents(good.result(), tail))

end LineageEventReader
