package raider.runtime.lineage.io

import raider.runtime.lineage.wire.{LineageEnvelope, LineageWire}
import zio.json.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Result of reading an events.jsonl file through the versioned wire reader
  * (ADR-032, card RAI-143): every decodable envelope in file order — each
  * keeping its own `schemaVersion` as typed data — plus an honest marker when
  * the LAST line was undecodable. Same torn-tail discipline as
  * [[LineageEventReader]]: the prefix before a truncated tail is fully
  * validated; the marker never hides the envelopes already read.
  */
final case class ParsedWireEvents(
    envelopes: Vector[LineageEnvelope],
    truncatedTail: Option[TruncatedTail]
)

/** Typed content-level failures of [[LineageWireReader.read]]. Separate from
  * [[LineageError]] on purpose: the frozen v1 reader and its error type are
  * pinned (v1 consumers keep rejecting `schemaVersion: 2`); this enum adds only
  * what the versioned rules need.
  */
enum LineageWireError:
  case MalformedLine(lineNo: Int, detail: String)

  /** Envelope schemaVersion is neither the frozen 1 nor the ADR-032 revision 2
    * — an unknown future version is refused, never guessed.
    */
  case SchemaVersionUnsupported(found: Int)

  /** A `schemaVersion: 1` envelope carries the v2-only `invocationId` field: a
    * version-mixing anomaly, refused typed (never silently ignored).
    */
  case InvocationIdOnV1(lineNo: Int)

  /** Per-job `seq` does not run 1,2,3,... — a gap or a duplicate. */
  case SeqGap(jobId: String, expected: Long, found: Long)

  case Empty
end LineageWireError

/** Reads one events.jsonl file through the VERSIONED wire (ADR-032 §2): JSONL,
  * one [[LineageEnvelope]] per line.
  *
  * Rules (mirror of [[LineageEventReader]] plus the version rules):
  *   - an empty file (zero lines) is `Left(LineageWireError.Empty)`;
  *   - the LAST line failing to decode is a truncated tail ([[TruncatedTail]])
  *     — not an error; an undecodable line BEFORE the tail is
  *     `Left(MalformedLine)`;
  *   - a decoded envelope with `schemaVersion` not in {1, 2} is
  *     `Left(SchemaVersionUnsupported)`;
  *   - a `schemaVersion: 1` envelope with `invocationId` is
  *     `Left(InvocationIdOnV1)`;
  *   - per `jobId`, `seq` must run 1,2,3,... — the first violation is
  *     `Left(SeqGap)`;
  *   - lines are processed in file order; the FIRST violation wins.
  *
  * v1 decode is bit-for-bit the frozen reader's decode: valid v1 lines produce
  * the same envelopes (wrapped as
  * [[raider.runtime.lineage.wire.LineagePayload.Frozen]]) and re-encode to the
  * frozen bytes.
  */
object LineageWireReader:

  def read(path: Path): Either[LineageWireError, ParsedWireEvents] =
    val lines =
      Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector
    if lines.isEmpty then Left(LineageWireError.Empty)
    else
      val lastLine = lines.length
      val good = Vector.newBuilder[LineageEnvelope]
      val nextSeq = scala.collection.mutable.HashMap.empty[String, Long]
      var lineNo = 1
      var failure = Option.empty[LineageWireError]
      var tail = Option.empty[TruncatedTail]
      while lineNo <= lastLine && failure.isEmpty && tail.isEmpty do
        val raw = lines(lineNo - 1)
        raw.fromJson[LineageEnvelope] match
          case Left(detail) =>
            // Only the last line gets the honest tail marker; any earlier
            // undecodable line is a hard MalformedLine.
            if lineNo == lastLine then
              tail = Some(TruncatedTail(lineNo, raw.length))
            else failure = Some(LineageWireError.MalformedLine(lineNo, detail))
          case Right(envelope) =>
            if envelope.schemaVersion != LineageWire.V1
              && envelope.schemaVersion != LineageWire.V2
            then
              failure = Some(
                LineageWireError.SchemaVersionUnsupported(
                  envelope.schemaVersion
                )
              )
            else if envelope.schemaVersion == LineageWire.V1 && envelope.invocationId.isDefined
            then failure = Some(LineageWireError.InvocationIdOnV1(lineNo))
            else
              val expected = nextSeq.getOrElse(envelope.jobId.value, 1L)
              if envelope.seq != expected then
                failure = Some(
                  LineageWireError.SeqGap(
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
        case None      => Right(ParsedWireEvents(good.result(), tail))

end LineageWireReader
