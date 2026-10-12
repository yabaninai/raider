package raider.repl

import raider.ui.CommandRouter
import raider.ui.ShellInputError

/** Command palette of the product TabShell (card RAI-106, P2P-01/P2P-02).
  *
  * The catalog is DERIVED from the frozen `raider.ui.CommandRouter` API, never
  * duplicated by hand — otherwise a freshness test would be impossible:
  *
  *   - a name is REGISTERED iff `CommandRouter.parse` routes it to anything
  *     other than `ShellInputError.UnknownCommand` (argument/tab requirements
  *     still count as registered — the command exists);
  *   - the palette (`complete`), the nearest-match suggestion (`nearest`) and
  *     the freshness fixtures in [[raider.repl]] specs are all functions of
  *     that probe, so a command removed from the router disappears from the
  *     palette and a listed-but-unroutable name fails the freshness spec.
  *
  * Pure: no terminal, no effects. The JLine adapter ([[PaletteCompleter]]) and
  * the unknown-command decorator ([[PaletteShellTerminal]]) consume it.
  */
object ShellPalette:

  /** The registered command catalog: `CommandRouter.commands` filtered through
    * the routing probe (order preserved — it is also the help/hint-bar order).
    */
  val commands: Vector[String] = CommandRouter.commands.filter(isRegistered)

  /** A name is registered iff the frozen router does not answer
    * `UnknownCommand` for it (probed with no tabs: tab-dependent commands still
    * surface their argument/tab errors, which proves registration). Only
    * `:`-prefixed names can be commands at all — anything else routes to
    * `Draft`, which is not a registration signal.
    */
  def isRegistered(name: String): Boolean =
    name.startsWith(":") &&
      (CommandRouter.parse(name, Nil, None) match
        case Left(ShellInputError.UnknownCommand(_)) => false
        case _                                       => true)

  /** Prefix search over the registered catalog (the palette lookup behind TAB):
    * an empty prefix yields nothing — the full list is the empty-prefix listing
    * of the JLine completer, not an insertion candidate.
    */
  def complete(prefix: String): Vector[String] =
    if prefix.isEmpty then Vector.empty
    else commands.filter(_.startsWith(prefix))

  /** Nearest registered command for an unknown token (card P2P-02): unique
    * prefix matches win (shortest, then lexicographic); otherwise the
    * Levenshtein-closest registered command within a small threshold (2,
    * growing slowly with length). A registered token never yields a suggestion,
    * and a far-out token (`:zzzz`) yields none — the plain available-commands
    * hint stays the only hint (P2W-02 behavior unchanged).
    */
  def nearest(raw: String): Option[String] =
    if isRegistered(raw) then None
    else
      val byPrefix =
        commands.filter(_.startsWith(raw)).sortBy(c => (c.length, c))
      byPrefix.headOption.orElse {
        val threshold = math.max(2, raw.length / 4)
        commands
          .map(c => c -> levenshtein(raw, c))
          .filter((_, d) => d <= threshold)
          .sortBy((c, d) => (d, c.length, c))
          .headOption
          .map((c, _) => c)
      }

  /** The rendered extra hint line (indented like the frozen hints block). */
  def suggestionLine(raw: String): Option[String] =
    nearest(raw).map(c => s"  did you mean: $c?")

  /** Plain Levenshtein distance (insert/delete/substitute, each cost 1). */
  private def levenshtein(a: String, b: String): Int =
    val prev = Array.tabulate(b.length + 1)(identity)
    val curr = new Array[Int](b.length + 1)
    for i <- 1 to a.length do
      curr(0) = i
      for j <- 1 to b.length do
        val sub = if a(i - 1) == b(j - 1) then 0 else 1
        curr(j) = math.min(
          math.min(curr(j - 1) + 1, prev(j) + 1),
          prev(j - 1) + sub
        )
      java.lang.System.arraycopy(curr, 0, prev, 0, curr.length)
    prev(b.length)

end ShellPalette
