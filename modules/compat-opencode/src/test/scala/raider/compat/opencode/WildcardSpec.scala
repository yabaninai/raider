package raider.compat.opencode

import zio.Scope
import zio.test.*
import zio.test.Assertion.*

/** Faithfulness spec for the pinned Wildcard.match semantics
  * (vendor/opencode-profiles/upstream/core/util/wildcard.ts):
  * backslash -> slash normalization, regex-special escaping, "*" -> ".*",
  * "?" -> ".", trailing " *" -> "( .*)?", anchored, dotall, case-sensitive
  * on non-Windows.
  */
object WildcardSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("Wildcard")(
    test("matches exact literals") {
      assertTrue(Wildcard.matches("git push", "git push")) &&
      assertTrue(!Wildcard.matches("git pulls", "git push"))
    },
    test("star matches across separators") {
      assertTrue(Wildcard.matches("src/a.ts", "src/*")) &&
      assertTrue(Wildcard.matches("src/deep/a.ts", "src/*")) &&
      assertTrue(!Wildcard.matches("srcx/a.ts", "src/*"))
    },
    test("star matches within a segment") {
      assertTrue(Wildcard.matches("a.ts", "*.ts")) &&
      assertTrue(Wildcard.matches("main.test.ts", "*.ts")) &&
      assertTrue(!Wildcard.matches("ts", "*.ts"))
    },
    test("question mark matches exactly one character") {
      assertTrue(Wildcard.matches("a/b", "a?b")) &&
      assertTrue(!Wildcard.matches("ab", "a?b")) &&
      assertTrue(!Wildcard.matches("a//b", "a?b"))
    },
    test("trailing space-star also matches without the suffix") {
      // The glob "git *" (space + star) triggers the optional-suffix branch;
      // Node-verified against the pinned upstream (a literal dot in the glob
      // is escaped and would NOT trigger it).
      assertTrue(Wildcard.matches("git", "git *")) &&
      assertTrue(Wildcard.matches("git push", "git *")) &&
      assertTrue(!Wildcard.matches("gitx", "git *")) &&
      assertTrue(!Wildcard.matches("git", "git .*"))
    },
    test("normalizes backslashes in both sides") {
      assertTrue(Wildcard.matches("a\\b.ts", "a/b.ts")) &&
      assertTrue(Wildcard.matches("a/b.ts", "a\\b.ts"))
    },
    test("escapes regex specials") {
      assertTrue(Wildcard.matches("a.b", "a.b")) &&
      assertTrue(!Wildcard.matches("axb", "a.b")) &&
      assertTrue(Wildcard.matches("price$100", "price$100")) &&
      assertTrue(Wildcard.matches("a+b", "a+b")) &&
      assertTrue(Wildcard.matches("[x]", "[x]"))
    },
    test("is case sensitive") {
      assertTrue(!Wildcard.matches("Read", "read")) &&
      assertTrue(!Wildcard.matches(".ENV", "*.env"))
    },
    test("dotall: star spans newlines") {
      assertTrue(Wildcard.matches("a\nb", "a*")) &&
      assertTrue(Wildcard.matches("line1\nline2", "*line2"))
    },
    test("matches the whole input only") {
      // Anchoring keeps ".env" from matching "prefix.env", but the star is a
      // plain ".*" and spans separators (Node-verified upstream behavior).
      assertTrue(!Wildcard.matches("prefix.env", ".env")) &&
      assertTrue(Wildcard.matches(".env", ".env")) &&
      assertTrue(Wildcard.matches("foo.env", "*.env")) &&
      assertTrue(Wildcard.matches("foo/.env", "*.env"))
    },
  )

