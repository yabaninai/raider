package raider.yabanin.labels

import java.nio.charset.StandardCharsets

/** Byte-wise URL path-segment escaping with Go `net/url` `PathEscape`
  * semantics.
  *
  * The Yabanin gateway emits and parses label values with Go's
  * `net/url.PathEscape`, so Raider must produce byte-identical output. Rules
  * (Go `escape(s, encodePathSegment)`):
  *   - ASCII alphanumerics and `-_.~` pass through unchanged;
 *   - among the reserved characters `$&+,:;=?@` only `/` `;` `,` `?` are
 *     escaped — `$`, `&`, `+`, `:`, `=`, `@` pass through;
  *   - every other byte (including `%`, quote characters, controls, and all
  *     non-ASCII UTF-8 bytes) becomes `%XX` with uppercase hex.
  *
  * [[unescape]] is the strict inverse for canonical input only. It rejects
  * truncated or non-hexadecimal escapes, escaped bytes that `escape` would
  * have kept raw, raw bytes that `escape` would have escaped, and byte
  * sequences that do not decode to valid UTF-8 — each as a typed error with
  * a character position in the encoded input.
  */
object PathEscape:

  private val HexUpper = "0123456789ABCDEF"

  /** Escapes `value` byte-wise exactly as Go `net/url.PathEscape` does. */
  def escape(value: String): String =
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    val out = new java.lang.StringBuilder(bytes.length + 8)
    bytes.foreach { b =>
      val c = b & 0xff
      if shouldKeep(c) then out.append(c.toChar)
      else
        out.append('%')
        out.append(HexUpper.charAt(c >> 4))
        out.append(HexUpper.charAt(c & 0x0f))
    }
    out.toString

  /** Typed decode failures. `position` is a character offset into the
    * encoded input.
    */
  sealed trait DecodeError extends Product with Serializable:
    def position: Int
    def message: String

  /** `%` not followed by two hexadecimal digits; position is the index of the `%`. */
  final case class InvalidEscape(position: Int, detail: String) extends DecodeError:
    def message = s"invalid percent escape at char $position: $detail"

  /** A byte appeared in a form `escape` would not produce: an escaped byte
    * that should be raw, or a raw character that should be escaped.
    */
  final case class NonCanonicalByte(position: Int, byte: Int) extends DecodeError:
    def message =
      val b = byte & 0xff
      s"non-canonical byte at char $position: byte 0x${hex2(b)} must be ${if shouldKeep(b) then "raw" else "percent-escaped"}"

  /** The decoded bytes are not valid UTF-8. */
  final case class InvalidUtf8(position: Int, detail: String) extends DecodeError:
    def message = s"decoded bytes are not valid UTF-8 at char $position: $detail"

  /** Strictly decodes a canonical path-escaped value. Hexadecimal digits are
    * accepted in either case (Go emits uppercase; wrappers may author the
    * raw text), but canonicality of the keep/escape set is enforced.
    */
  def unescape(encoded: String): Either[DecodeError, String] =
    val bytes = new scala.collection.mutable.ArrayBuffer[Byte](encoded.length)
    val origins = new scala.collection.mutable.ArrayBuffer[Int](encoded.length)
    var i = 0
    var failure: Option[DecodeError] = None
    while failure.isEmpty && i < encoded.length do
      val c = encoded.charAt(i)
      if c == '%' then
        if i + 2 >= encoded.length then
          failure = Some(InvalidEscape(i, "truncated: expected two hexadecimal digits"))
        else
          val d1 = hexValue(encoded.charAt(i + 1))
          val d2 = hexValue(encoded.charAt(i + 2))
          if d1.isEmpty then
            failure = Some(InvalidEscape(i, s"expected hexadecimal digit, found '${shown(encoded.charAt(i + 1))}'"))
          else if d2.isEmpty then
            failure = Some(InvalidEscape(i, s"expected second hexadecimal digit, found '${shown(encoded.charAt(i + 2))}'"))
          else
            val b = (d1.get << 4) | d2.get
            if b < 0x80 && shouldKeep(b) then
              failure = Some(NonCanonicalByte(i, b))
            else
              bytes += b.toByte
              origins += i
            i += 2
      else if c >= 0x80 || !shouldKeep(c.toInt) then
        failure = Some(NonCanonicalByte(i, c.toInt))
      else
        bytes += c.toByte
        origins += i
      i += 1
    failure match
      case Some(e) => Left(e)
      case None    => validateUtf8(bytes.toVector, origins.toVector)

  /** The keep-set of Go `escape(s, encodePathSegment)`. */
  private def shouldKeep(c: Int): Boolean =
    val ch = c & 0xff
    (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') ||
      ch == '-' || ch == '_' || ch == '.' || ch == '~' ||
      ch == '$' || ch == '&' || ch == '+' || ch == ':' || ch == '=' || ch == '@'

  private def hexValue(c: Char): Option[Int] =
    if c >= '0' && c <= '9' then Some(c - '0')
    else if c >= 'a' && c <= 'f' then Some(c - 'a' + 10)
    else if c >= 'A' && c <= 'F' then Some(c - 'A' + 10)
    else None

  private def hex2(v: Int): String =
    s"${HexUpper.charAt((v >> 4) & 0xf)}${HexUpper.charAt(v & 0xf)}"

  private def shown(c: Char): String =
    if c >= ' ' && c < 0x7f then s"'$c'" else f"'\\u${c.toInt}%04x'"

  /** Validates the decoded byte array as strict UTF-8 (no overlong forms, no
    * surrogates, no out-of-range code points) and reports failures at the
    * character position of the offending input byte.
    */
  private def validateUtf8(bytes: Vector[Byte], origins: Vector[Int]): Either[DecodeError, String] =
    var i = 0
    var failure: Option[DecodeError] = None
    while failure.isEmpty && i < bytes.length do
      val b0 = bytes(i) & 0xff
      if b0 < 0x80 then i += 1
      else
        val need: Int =
          if (b0 & 0xe0) == 0xc0 then 1
          else if (b0 & 0xf0) == 0xe0 then 2
          else if (b0 & 0xf8) == 0xf0 then 3
          else -1
        if need < 0 then
          failure = Some(InvalidUtf8(origins(i), s"invalid leading byte 0x${hex2(b0)}"))
        else if i + need >= bytes.length then
          failure = Some(InvalidUtf8(origins(i), "truncated UTF-8 sequence"))
        else
          var k = 1
          var badContinuation = false
          while !badContinuation && k <= need do
            if (bytes(i + k) & 0xc0) != 0x80 then badContinuation = true
            else k += 1
          if badContinuation then
            failure = Some(InvalidUtf8(origins(i + k), s"invalid continuation byte 0x${hex2(bytes(i + k) & 0xff)}"))
          else
            val cp: Int =
              need match
                case 1 => (b0 & 0x1f) << 6 | (bytes(i + 1) & 0x3f)
                case 2 => (b0 & 0x0f) << 12 | (bytes(i + 1) & 0x3f) << 6 | (bytes(i + 2) & 0x3f)
                case _ => (b0 & 0x07) << 18 | (bytes(i + 1) & 0x3f) << 12 | (bytes(i + 2) & 0x3f) << 6 | (bytes(i + 3) & 0x3f)
            val minCp: Int = need match
              case 1 => 0x80
              case 2 => 0x800
              case _ => 0x10000
            if cp < minCp then
              failure = Some(InvalidUtf8(origins(i), f"overlong encoding (U+$cp%04X)"))
            else if cp >= 0xd800 && cp <= 0xdfff then
              failure = Some(InvalidUtf8(origins(i), f"UTF-16 surrogate code point (U+$cp%04X)"))
            else if cp > 0x10ffff then
              failure = Some(InvalidUtf8(origins(i), f"code point out of range (U+$cp%04X)"))
            else
              i += need + 1
    failure match
      case Some(e) => Left(e)
      case None    => Right(new String(bytes.toArray, StandardCharsets.UTF_8))
