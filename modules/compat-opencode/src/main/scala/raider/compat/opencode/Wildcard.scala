package raider.compat.opencode

/** Faithful port of the pinned OpenCode Wildcard.match
  * (vendor/opencode-profiles/upstream/core/util/wildcard.ts): both sides are
  * backslash-normalized, regex specials are escaped, "*" spans any sequence,
  * "?" spans exactly one character, a trailing " *" makes the space and
  * suffix optional, the match is anchored and dot-all, and case-sensitive
  * outside Windows.
  */
object Wildcard:

  def matches(input: String, pattern: String): Boolean =
    val normalizedInput = input.replace('\\', '/')
    val normalizedPattern = pattern.replace('\\', '/')
    // Escape regex specials (except "*" and "?", which become wildcards next),
    // mirroring the TS character class [.+^${}()|[\]\\] with a plain char test.
    val escaped = normalizedPattern
      .flatMap(c => if "\\.+^${}()|\\".indexOf(c) >= 0 || c == '[' || c == ']' then "\\" + c else c.toString)
      .replace("*", ".*")
      .replace("?", ".")
    val adjusted =
      if escaped.endsWith(" .*") then escaped.substring(0, escaped.length - 3) + "( .*)?" else escaped
    normalizedInput.matches("(?s)" + adjusted)
