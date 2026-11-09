package raider.yabanin.labels

import zio.Scope
import zio.test.*
import zio.test.Assertion.*

/** Byte-exact Go `net/url.PathEscape` contract coverage (RAI-037, contract
  * §1.1). Asserts every pinned escape case, the canonical round-trip guarantees
  * from `contracts/yabanin/README.md` §1.4, and the strict typed decode
  * failures of `PathEscape.unescape`.
  */
object PathEscapeSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("PathEscape")(
    test("escape matches every pinned pathescape-golden case byte-for-byte") {
      val golden = Goldens.pathEscape
      val results = golden.cases.map { c =>
        if PathEscape.escape(c.input) == c.expected then assertTrue(true)
        else
          assertTrue(
            s"case ${c.name}: escape(${show(c.input)}) = ${show(PathEscape.escape(c.input))}, want ${show(c.expected)}" == ""
          )
      }
      assertTrue(golden.cases.size >= 30) && results.foldLeft[TestResult](
        assertTrue(true)
      )(_ && _)
    },
    test(
      "unescape inverts escape for every pinned case (canonical round-trip)"
    ) {
      val golden = Goldens.pathEscape
      val results = golden.cases.map { c =>
        val back = PathEscape.unescape(c.expected)
        val viaPlain = PathEscape.unescape(PathEscape.escape(c.input))
        if back == Right(c.input) && viaPlain == Right(c.input) then
          assertTrue(true)
        else
          assertTrue(
            s"case ${c.name}: unescape(expected)=$back, unescape(escape(input))=$viaPlain, want Right(${show(c.input)})" == ""
          )
      }
      results.foldLeft[TestResult](assertTrue(true))(_ && _)
    },
    test("canonical rendered headers round-trip through the strict decoder") {
      val golden = Goldens.labels
      val results = golden.render_cases.map { c =>
        val pairs = c.client_labels.map(p => (p.key, p.value))
        val set = LabelHeader.fromClient(pairs, LabelLimits.Default)
        val parsed = LabelHeader.parseRaw(set.renderHeader, LabelLimits.Default)
        val asPairs = parsed.map(ps => ps.map(l => (l.key, l.value)))
        if asPairs == Right(set.accepted.map(l => (l.key, l.value))) then
          assertTrue(true)
        else assertTrue(s"case ${c.name}: parseRaw(rendered) = $asPairs" == "")
      }
      results.foldLeft[TestResult](assertTrue(true))(_ && _)
    },
    test("lowercase hex digits are accepted by the strict decoder") {
      assertTrue(PathEscape.unescape("a%2fb") == Right("a/b")) &&
      assertTrue(PathEscape.unescape("%e6%97%a5") == Right("日"))
    },
    test(
      "truncated or non-hexadecimal escapes are InvalidEscape with positions"
    ) {
      PathEscape.unescape("%4") match
        case Left(PathEscape.InvalidEscape(pos, _)) => assertTrue(pos == 0)
        case other => assertTrue(s"want InvalidEscape, got $other" == "")
      PathEscape.unescape("ab%ZZ") match
        case Left(PathEscape.InvalidEscape(pos, _)) => assertTrue(pos == 2)
        case other => assertTrue(s"want InvalidEscape, got $other" == "")
    },
    test("escaped bytes the escaper would keep raw are NonCanonicalByte") {
      // PathEscape keeps ASCII alphanumerics raw, so escaping them is
      // non-canonical; escapes of bytes it WOULD escape (like %2F) are
      // canonical and decode fine (contract README §1.4).
      PathEscape.unescape("%41") match
        case Left(PathEscape.NonCanonicalByte(pos, byte)) =>
          assertTrue(pos == 0 && byte == 0x41)
        case other => assertTrue(s"want NonCanonicalByte, got $other" == "")
      PathEscape.unescape("a%2Fb") match
        case Right(value) => assertTrue(value == "a/b")
        case other        => assertTrue(s"want Right(a/b), got $other" == "")
    },
    test("raw bytes the escaper would escape are NonCanonicalByte") {
      PathEscape.unescape("a/b") match
        case Left(PathEscape.NonCanonicalByte(pos, byte)) =>
          assertTrue(pos == 1 && byte == '/')
        case other => assertTrue(s"want NonCanonicalByte, got $other" == "")
      PathEscape.unescape("café") match
        case Left(PathEscape.NonCanonicalByte(pos, _)) => assertTrue(pos == 3)
        case other => assertTrue(s"want NonCanonicalByte, got $other" == "")
    },
    test("decoded bytes that are not valid UTF-8 are InvalidUtf8") {
      PathEscape.unescape("%C3") match
        case Left(PathEscape.InvalidUtf8(pos, detail)) =>
          assertTrue(pos == 0 && detail.contains("truncated"))
        case other => assertTrue(s"want InvalidUtf8, got $other" == "")
      PathEscape.unescape("%C3%28") match
        case Left(PathEscape.InvalidUtf8(pos, detail)) =>
          assertTrue(pos == 0 && detail.contains("continuation"))
        case other => assertTrue(s"want InvalidUtf8, got $other" == "")
      PathEscape.unescape("%C0%80") match
        case Left(PathEscape.InvalidUtf8(pos, detail)) =>
          assertTrue(pos == 0 && detail.contains("overlong"))
        case other => assertTrue(s"want InvalidUtf8, got $other" == "")
      PathEscape.unescape("%ED%A0%80") match
        case Left(PathEscape.InvalidUtf8(pos, detail)) =>
          assertTrue(pos == 0 && detail.contains("surrogate"))
        case other => assertTrue(s"want InvalidUtf8, got $other" == "")
      PathEscape.unescape("%F4%90%80%80") match
        case Left(PathEscape.InvalidUtf8(pos, detail)) =>
          assertTrue(pos == 0 && detail.contains("out of range"))
        case other => assertTrue(s"want InvalidUtf8, got $other" == "")
    },
    test("valid multibyte UTF-8 decodes exactly (wide characters preserved)") {
      assertTrue(PathEscape.unescape("%E6%97%A5%E6%9C%AC") == Right("日本")) &&
      assertTrue(PathEscape.unescape("%F0%9D%95%8F") == Right("𝕏")) &&
      assertTrue(PathEscape.escape("𝕏") == "%F0%9D%95%8F")
    }
  )

  private def show(s: String): String =
    s.map { c =>
      if c >= ' ' && c < 0x7f then c.toString else f"\\u${c.toInt}%04x"
    }.mkString
