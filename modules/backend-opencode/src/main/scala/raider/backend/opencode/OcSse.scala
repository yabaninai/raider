package raider.backend.opencode

import java.nio.charset.StandardCharsets

/** Byte-level SSE framing for the OpenCode global event stream. Deliberately
 * self-contained (no dependency on raider.core or provider-chat) per the
 * RAI-035 module isolation decision; semantics follow the same SSE grammar:
 *   - lines terminated by LF or CRLF;
 *   - an empty line terminates an event frame;
 *   - only `data:` lines carry payload; comments (`:`), `event:`, `id:` and
 *     `retry:` lines are ignored;
 *   - multiple `data:` lines in one frame are joined with LF;
 *   - frame size is bounded: exceeding the bound raises [[OcSse.Overflow]]
 *     which callers map to a typed StreamProtocol failure.
 *
 * Bytes are accumulated per frame and decoded to UTF-8 at frame boundaries
 * only, so multi-byte characters split across network chunks are handled.
 */
final class OcSseFramer(maxFrameBytes: Int):

  private val buf = new scala.collection.mutable.ArrayBuffer[Byte](1024)
  private var contentOnLine = false
  private var lastWasCr = false

  /** Feeds raw bytes; returns frames completed by this chunk (possibly none).
   */
  def push(chunk: Array[Byte]): Vector[String] =
    val frames = scala.collection.mutable.ArrayBuffer.empty[String]
    var i = 0
    while i < chunk.length do
      val b = chunk(i)
      i += 1
      if b == '\r' then
        lastWasCr = true
        endLine(frames)
      else if b == '\n' then
        if !lastWasCr then endLine(frames)
        else lastWasCr = false
      else
        lastWasCr = false
        contentOnLine = true
        buf += b
        if buf.length > maxFrameBytes then throw new OcSse.Overflow(maxFrameBytes)
    frames.toVector

  /** A content line keeps its boundary in the buffer (LF appended) so
    * multi-line frames preserve their structure; the empty line at the frame
    * end emits the buffer minus that trailing LF.
    */
  private def endLine(frames: scala.collection.mutable.ArrayBuffer[String]): Unit =
    lastWasCr = false
    if contentOnLine then buf += '\n'
    else if buf.nonEmpty then
      val body = buf.toArray
      frames += new String(body, 0, body.length - 1, StandardCharsets.UTF_8)
      buf.clear()
    contentOnLine = false

object OcSse:

  /** Frame-size violation signal; callers map it to a typed StreamProtocol
   * failure.
   */
  final class Overflow(val limit: Int) extends RuntimeException(s"SSE frame exceeded $limit bytes")
  /** Extracts the joined `data:` payload of a raw SSE frame. Returns None for
   * frames without data lines (e.g. comment keep-alives).
   */
  def dataPayload(frame: String): Option[String] =
    val dataLines =
      frame.split("\n", -1).iterator.flatMap { raw =>
        val line = if raw.endsWith("\r") then raw.dropRight(1) else raw
        if line.startsWith("data:") then
          val rest = line.substring(5)
          Some(if rest.startsWith(" ") then rest.substring(1) else rest)
        else None
      }.toVector
    if dataLines.isEmpty then None else Some(dataLines.mkString("\n"))
