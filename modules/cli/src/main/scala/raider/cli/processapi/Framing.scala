package raider.cli.processapi

import raider.cli.processapi.Protocol.ProtocolFailure
import zio.json.JsonDecoder
import zio.json.ast.Json

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.{
  CharacterCodingException,
  CharsetDecoder,
  CodingErrorAction,
  StandardCharsets
}

/** Incremental LF-delimited frame reader for the process-api stdin (ADR-037 §3,
  * §6; fixtures `fragmented-frames-unicode`, `oversize-frame`, `eof`).
  *
  * ONLY the byte 0x0A splits frames — U+2028/U+2029 and any other byte sequence
  * is ordinary payload data, so a submit text containing line separators
  * survives fragmented delivery untouched. Each completed frame is decoded as
  * STRICT UTF-8 (malformed sequences surface as `invalid_utf8`, never as silent
  * replacement characters) and parsed as JSON; a frame whose payload exceeds
  * [[FrameMaxBytes]] is rejected (`oversize_frame`) BEFORE any JSON decoding,
  * and the remainder of that line is discarded to the next LF without logging
  * payload bytes. A trailing byte run without LF at EOF is a protocol error
  * (`finish`) — a partial frame is never dispatched.
  */
object Framing:

  /** ADR-037 §5: at most one frame at 1 MiB; the payload bound itself. */
  val FrameMaxBytes: Int = 1048576

  private val newline: Byte = 0x0a

  final class LineAssembler:
    private val buffer = new ByteArrayOutputStream(8192)
    private var discarding: Boolean = false

    /** Feed one chunk of stdin bytes; returns completed frames in order. Each
      * entry is the parsed JSON object, or the protocol failure for that frame.
      * Fatal failures stop the SESSION, but the assembler keeps its framing
      * state consistent so at most one failure is reported per offending frame.
      */
    def feed(chunk: Array[Byte]): Vector[Either[ProtocolFailure, Json]] =
      val out = Vector.newBuilder[Either[ProtocolFailure, Json]]
      var i = 0
      while i < chunk.length do
        val b = chunk(i)
        if discarding then
          // Inside an over-long frame: drop bytes silently until the line
          // ends, then report the failure exactly once.
          if b == newline then
            out += Left(
              Protocol.OversizeFrame(
                FrameMaxBytes.toLong + 1L
              )
            )
            discarding = false
        else if b == newline then out ++= completeLine()
        else if buffer.size() >= FrameMaxBytes then
          buffer.reset()
          discarding = true
        else buffer.write(b)
        i += 1
      out.result()

    /** EOF handling: bytes held without a trailing LF are a protocol error (a
      * partial frame is never dispatched). Returns the failure for that
      * trailing run, if any.
      */
    def finish(): Option[ProtocolFailure] =
      if discarding then
        discarding = false
        buffer.reset()
        Some(Protocol.OversizeFrame(FrameMaxBytes.toLong + 1L))
      else if buffer.size() > 0 then
        buffer.reset()
        Some(
          Protocol.MalformedFrame(
            "frame ended without a newline (EOF mid-frame); never dispatched"
          )
        )
      else None

    private def completeLine(): Vector[Either[ProtocolFailure, Json]] =
      val bytes = buffer.toByteArray
      buffer.reset()
      if bytes.length > FrameMaxBytes then
        Vector(Left(Protocol.OversizeFrame(bytes.length.toLong)))
      else if bytes.length == 0 then
        Vector(Left(Protocol.MalformedFrame("empty frame")))
      else Vector(decodeFrame(bytes))

  private def decodeFrame(bytes: Array[Byte]): Either[ProtocolFailure, Json] =
    try
      val text = strictUtf8.decode(ByteBuffer.wrap(bytes)).toString
      JsonDecoder[Json].decodeJson(text) match
        case Right(obj: Json.Obj) => Right(obj)
        case Right(_) => Left(Malformed("frame must be a JSON object"))
        case Left(_)  =>
          // The decoder's message may quote payload fragments — never relay it.
          Left(Malformed("invalid JSON"))
    catch
      case _: CharacterCodingException => Left(Protocol.InvalidUtf8)
      case _: IllegalArgumentException =>
        Left(Malformed("unterminated escape sequence"))

  private def Malformed(reason: String): Protocol.MalformedFrame =
    Protocol.MalformedFrame(reason)

  private def strictUtf8: CharsetDecoder =
    StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)

end Framing
