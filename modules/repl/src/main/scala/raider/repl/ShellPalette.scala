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
  *
  * RAI-111 adds the chat commands of the REPL cycle (:jobs/:cancel/:log/:reset
  * + the rest of the Commands surface) as a SECOND derived catalog:
  * [[chatCommands]] comes from the [[ReplChatCommands]] registry whose
  * freshness against the source of truth (Commands.helpText) is enforced by
  * ReplChatPaletteSpec. The shell catalog [[commands]] is UNCHANGED — the
  * frozen router stays the only executable surface of the TabShell, so TAB
  * completion keeps offering only routable commands. The combined
  * [[allCommands]] feeds the nearest-command hint: a near miss of a chat
  * command suggests it, while a name that IS a chat command never yields the
  * absurd self-suggestion.
  */
object ShellPalette:

  /** The registered command catalog: `CommandRouter.commands` filtered through
    * the routing probe (order preserved — it is also the help/hint-bar order).
    */
  val commands: Vector[String] = CommandRouter.commands.filter(isRegistered)

  /** The chat-command catalog of the REPL cycle (card RAI-111): derived from
    * the [[ReplChatCommands]] companion registry — never a hand list here.
    */
  val chatCommands: Vector[String] = ReplChatCommands.names

  /** Router catalog first, then the chat commands that are not router commands
    * (:help/:quit exist on both surfaces — listed once), then the RAI-113
    * scrollback command that is on neither surface (derived from the
    * [[ScrollbackCommands]] registry — never a hand list here). This is the
    * catalog of the nearest-command hint, the one hint surface that is not
    * frozen. TAB completion is NOT extended: `complete` keeps offering only
    * routable (router) commands — the RAI-111 documented choice for non-router
    * surfaces.
    */
  val allCommands: Vector[String] =
    commands ++ chatCommands.filterNot(commands.contains) ++
      ScrollbackCommands.names
        .filterNot(n => commands.contains(n) || chatCommands.contains(n))

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

  /** Prefix search over the chat catalog (card RAI-111; the REPL-cycle lookup
    * behind TAB): an empty prefix yields nothing, mirroring [[complete]].
    */
  def completeChat(prefix: String): Vector[String] =
    if prefix.isEmpty then Vector.empty
    else chatCommands.filter(_.startsWith(prefix))

  /** Nearest registered command for an unknown token (card P2P-02, extended by
    * RAI-111 to the combined catalog): unique prefix matches win (shortest,
    * then lexicographic); otherwise the Levenshtein-closest command within a
    * small threshold (2, growing slowly with length). A token that IS a command
    * of either surface never yields a suggestion (in tabs mode a chat name is
    * simply not routable — hinting it against itself would be absurd), and a
    * far-out token (`:zzzz`) yields none — the plain available-commands hint
    * stays the only hint (P2W-02 behavior unchanged).
    */
  def nearest(raw: String): Option[String] =
    if allCommands.contains(raw) then None
    else
      val byPrefix =
        allCommands.filter(_.startsWith(raw)).sortBy(c => (c.length, c))
      byPrefix.headOption.orElse {
        val threshold = math.max(2, raw.length / 4)
        allCommands
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
