package raider.provider.chat.stream

import raider.core.RaiderError

/** RAI-012: incremental SSE framing over RAW BYTES (provider-protocols §3).
  *
  * Byte-level framing is UTF-8-safe: 0x0A never occurs inside a multi-byte
  * UTF-8 sequence, so event boundaries can be found before decoding. Complete
  * frames are decoded as UTF-8 and reduced to their `data:` payloads
  * (multi-line data joins with "\n"; `:`-comment lines and event:/id:/retry:
  * fields are ignored — the Chat Completions stream uses data lines only).
  *
  * ALL parse state (partial line bytes AND the accumulating data lines) lives
  * in fields, so an event split across any chunk boundary — including a
  * boundary between the data line's newline and the terminating blank line —
  * survives intact (SSE-01 partition property). The buffer is bounded: a frame
  * longer than `maxFrameBytes` is a typed StreamProtocol failure, never
  * unbounded memory (SSE-02/03).
  *
  * Same framer is reusable by the Anthropic wire later; the semantic layer
  * (ChatChunkParser) is deliberately separate.
  */
final class SseFramer(maxFrameBytes: Int):

  private val buf = new scala.collection.mutable.ArrayBuffer[Byte](256)
  private val dataLines = new scala.collection.mutable.ArrayBuffer[String](2)
  private var hasData = false
  private var closed = false

  /** Feed the next arbitrary TCP chunk; returns newly completed events. */
  def feed(chunk: Array[Byte]): Either[RaiderError, Vector[String]] =
    if closed then Left(RaiderError.StreamProtocol("SSE framer already closed"))
    else
      buf ++= chunk
      if buf.size > maxFrameBytes then failOversize()
      else drain(finalCall = false)

  /** Stream EOF: dispatches a trailing unterminated event (per SSE spec), after
    * which the framer is closed.
    */
  def finish(): Either[RaiderError, Vector[String]] =
    if closed then Left(RaiderError.StreamProtocol("SSE framer already closed"))
    else
      closed = true
      drain(finalCall = true)

  private def failOversize(): Either[RaiderError, Vector[String]] =
    val size = buf.size
    reset()
    closed = true
    Left(
      RaiderError.StreamProtocol(
        s"SSE frame exceeds maxFrameBytes=$maxFrameBytes (buffered $size bytes)"
      )
    )

  private def reset(): Unit =
    buf.clear()
    dataLines.clear()
    hasData = false

  private def dispatch(
      into: scala.collection.mutable.ArrayBuffer[String]
  ): Unit =
    if hasData then
      into += dataLines.mkString("\n")
      dataLines.clear()
      hasData = false

  /** Scan complete lines; an empty line dispatches the accumulated event. */
  private def drain(finalCall: Boolean): Either[RaiderError, Vector[String]] =
    val events = new scala.collection.mutable.ArrayBuffer[String](2)
    var pos = 0
    var broken = false
    while !broken && pos < buf.size do
      val nl = buf.indexOf('\n'.toByte, pos) // Byte: a Char never matches
      if nl < 0 then broken = true
      else
        val raw = buf.slice(pos, nl)
        pos = nl + 1
        val line = stripTrailingCr(raw)
        if line.isEmpty then dispatch(events)
        else if line.head != ':' && line.startsWith("data:") then
          val payload = line.drop(5)
          dataLines +=
            (if payload.startsWith(" ") then payload.drop(1) else payload)
          hasData = true
        // other fields (event:/id:/retry:) and comments: ignored (documented)

    if broken then
      // keep the partial line for the next chunk
      val rest = buf.slice(pos, buf.size)
      if rest.size > maxFrameBytes then return failOversize()
      buf.clear()
      buf ++= rest
    else
      buf.clear()
      if finalCall then dispatch(events) // trailing unterminated event

    Right(events.toVector)

  private def stripTrailingCr(
      bytes: scala.collection.mutable.ArrayBuffer[Byte]
  ): String =
    val end =
      if bytes.nonEmpty && bytes.last == '\r'.toByte then bytes.size - 1
      else bytes.size
    new String(bytes.toArray, 0, end, java.nio.charset.StandardCharsets.UTF_8)

object SseFramer:

  /** Test helper: split a corpus into byte chunks of size k (SSE-01 property).
    */
  def partitions(bytes: Array[Byte], k: Int): Vector[Array[Byte]] =
    if bytes.isEmpty then Vector(Array.emptyByteArray)
    else bytes.grouped(math.max(1, k)).toVector

end SseFramer
