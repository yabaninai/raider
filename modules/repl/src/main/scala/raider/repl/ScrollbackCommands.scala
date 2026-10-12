package raider.repl

import raider.ui.{RegistryError, SessionRegistry, ShellTerminal, TabId, TabView}
import zio.{IO, Task, UIO, ZIO}

/** RAI-113: the interactive `:scrollback [tabId]` command of the product tabs
  * surface (the `--tabs` entry, RAI-108) — the consumer step RAI-104 named as
  * the "next layer": it renders one tab's bounded scrollback through the
  * PRODUCT `TabShellScrollback.screen`/`render` ABOVE the prompt.
  *
  * Structure follows the two accepted precedents:
  *
  *   - INTERCEPTOR (RAI-110, `PlanPanel.commandTerminal`): a `ShellTerminal`
  *     decorator that consumes exactly the registry names BEFORE the frozen
  *     raider.ui router sees the line; every other line passes through
  *     byte-exact; EOF passes through (the shell quits honestly). Unlike
  *     `:approve`, a scrollback dispatch holds NO gate — it is a bounded
  *     registry read plus the ONE-writer `printAbove` — so it runs INLINE on
  *     the read path and the render deterministically lands before the next
  *     prompt redraw (P2SR-03: one writer, no tear, editable prompt).
  *   - FRESHNESS REGISTRY (RAI-111, `ReplChatCommands`):
  *     `ScrollbackCommands.names` is the ONE source of truth of the command
  *     surface; the parser matches exactly these names (no second hand list),
  *     and the combined nearest- hint catalog (`ShellPalette.allCommands`)
  *     derives this registry. The freshness fixtures in
  *     `ScrollbackCommandsSpec` pin registry ↔ parser ↔ catalog coherence; TAB
  *     completion stays router-only (the frozen router cannot route this
  *     command — the RAI-111 documented choice for non-router surfaces).
  *
  * Resolution of the `[tabId]` token mirrors the frozen `:switch <n|label>`
  * precedence (RAI-121, `CommandRouter.resolveTab`): an all-digit token is the
  * 1-based index (the `:tabs` listing order) FIRST and exclusively — in range
  * it resolves, out of range it is the typed `UnknownTab`, and a digit token
  * NEVER falls through to id/label/prefix (exactly `:switch`: a tab labeled "2"
  * is not reachable by the token "2", and a lone "2024-report" does not
  * resurrect an out-of-range "2" as a prefix match). A non-digit token then
  * tries the registry read-model steps `:switch` does not expose (its tokens
  * carry no tab ids): exact tab-id value, exact label, unique label prefix.
  * Every failure — missing token, out-of-range index, unknown/ambiguous label —
  * is the TYPED `RegistryError.UnknownTab` carrying the raw token (P2SR-02); a
  * failed lookup never creates a phantom tab and the shell stays alive; the
  * command works again immediately after an error.
  *
  * Output goes through the ONE writer (`ShellTerminal.printAbove`): success
  * prints the exact `TabShellScrollback.Screen.render` lines (the eviction-
  * counter header + the byte-exact window, P2SR-01); a typed failure prints ONE
  * line `error: <RegistryError.message>`.
  */
final class ScrollbackCommands(registry: SessionRegistry):

  /** The scrollback-command terminal decorator (RAI-110 pattern): lines whose
    * first token is a [[ScrollbackCommands.names]] entry are consumed here —
    * dispatched inline, the outcome printed as ONE atomic block through the
    * SAME ONE writer — and the NEXT line is read, so the frozen router never
    * sees a scrollback command. `NotScrollback` lines (including a trailing
    * `:quit` and every draft) pass through byte-exact.
    */
  def commandTerminal(underlying: ShellTerminal): ShellTerminal =
    new ShellTerminal:
      def printAbove(lines: Vector[String]): UIO[Unit] =
        underlying.printAbove(lines)

      def readLine(prompt: String): Task[Option[String]] =
        underlying.readLine(prompt).flatMap {
          case None => ZIO.succeed(None)
          case Some(raw) =>
            ScrollbackCommands.parse(raw) match
              case ScrollbackMatch.NotScrollback => ZIO.succeed(Some(raw))
              case ScrollbackMatch.Parsed(token) =>
                dispatch(underlying, token) *> readLine(prompt)
        }

      def close: UIO[Unit] = underlying.close

  /** One scrollback command on the read path: NO gate is held (a bounded
    * registry read + one printAbove block), so the inline dispatch cannot
    * deadlock anything and keeps the transcript order deterministic.
    */
  private def dispatch(
      underlying: ShellTerminal,
      token: Option[String]
  ): UIO[Unit] =
    ScrollbackCommands
      .run(registry, token)
      .either
      .flatMap(outcome =>
        underlying.printAbove(ScrollbackCommands.outcomeLines(outcome))
      )

end ScrollbackCommands

/** Result of matching one raw input line against the scrollback registry
  * (total, pure; the RAI-110 `PlanCommandMatch` shape). `NotScrollback` lines
  * pass through to the frozen router untouched; a `Parsed` match carries the
  * OPTIONAL tab token (the trimmed remainder after the command name).
  */
enum ScrollbackMatch:
  case NotScrollback
  case Parsed(token: Option[String])

object ScrollbackCommands:

  /** The registered command names — the source of truth of this surface. The
    * parser, the freshness fixtures and the hint catalog all read THIS vector;
    * there is no second hand list anywhere.
    */
  val names: Vector[String] = Vector(":scrollback")

  /** The typed failure for a MISSING tab id (no argument given): the empty id
    * is by definition not an open tab — the same typed contract as any other
    * unresolvable token (card RAI-113: missing tabId is UnknownTab, not a
    * focused-tab default).
    */
  val missingTab: RegistryError = RegistryError.UnknownTab(TabId(""))

  /** Pure matcher over the [[names]] registry: the FIRST whitespace token
    * decides (case-insensitive, like `Commands.handle` and `PlanCommands`). The
    * token may carry an argument; a near miss (`:scrollbackx`) is never a
    * partial match.
    */
  def parse(line: String): ScrollbackMatch =
    val split = line.trim.split("\\s+", 2)
    val token = split(0).toLowerCase
    if names.contains(token) then
      val rest = if split.length > 1 then split(1).trim else ""
      ScrollbackMatch.Parsed(if rest.isEmpty then None else Some(rest))
    else ScrollbackMatch.NotScrollback

  /** Resolve a tab token with the frozen `:switch` precedence (RAI-121): an
    * all-digit token is the 1-based `:tabs`-listing index FIRST and exclusively
    * — exactly `CommandRouter.resolveTab`: in range it resolves, out of range
    * is the typed `RegistryError.UnknownTab`, and a digit token never falls
    * through to id/label/prefix. A non-digit token then tries exact tab-id
    * value (the registry read-model step `:switch` does not have), exact label,
    * unique label prefix. EVERY failure is the typed `RegistryError.UnknownTab`
    * of the raw token — ambiguity is honest "cannot pick one", not a silent
    * first pick.
    */
  def resolve(
      token: String,
      tabs: List[TabView]
  ): Either[RegistryError, TabId] =
    // An empty token matches no tab (a prefix "" would match every label —
    // guarded before the prefix search; parse never produces it, the direct
    // callers get the honest typed answer).
    if token.isEmpty then Left(RegistryError.UnknownTab(TabId("")))
    else if token.forall(_.isDigit) then
      // Frozen `:switch` precedence FIRST (RAI-121): a digit token IS the
      // 1-based index and never falls through — the out-of-range answer is
      // the typed unknown, not a label/prefix guess.
      token.toIntOption match
        case Some(n) if n >= 1 && n <= tabs.size => Right(tabs(n - 1).id)
        case _ => Left(RegistryError.UnknownTab(TabId(token)))
    else
      val byId = tabs.filter(_.id.value == token)
      if byId.size == 1 then Right(byId.head.id)
      else
        val exact = tabs.filter(_.label == token)
        if exact.size == 1 then Right(exact.head.id)
        else
          val byPrefix = tabs.filter(_.label.startsWith(token))
          byPrefix match
            case single :: Nil => Right(single.id)
            case _             => Left(RegistryError.UnknownTab(TabId(token)))

  /** The command edge: resolve the optional token and read the PRODUCT screen
    * through `TabShellScrollback.screen` (the frozen registry propagates its
    * typed `RegistryError.UnknownTab` as-is). A missing token fails with
    * [[missingTab]] — never a focused-tab guess (card contract).
    */
  def run(
      registry: SessionRegistry,
      token: Option[String]
  ): IO[RegistryError, TabShellScrollback.Screen] =
    token match
      case None => ZIO.fail(missingTab)
      case Some(value) =>
        registry.tabs.flatMap { tabs =>
          ZIO.fromEither(resolve(value, tabs)).flatMap { tabId =>
            TabShellScrollback.screen(registry, tabId)
          }
        }

  /** The exact printed block for one outcome: the typed error line, or the
    * product screen render (eviction-counter header + byte-exact window). The
    * error line is end-trimmed for the transcript (the typed value keeps the
    * raw empty tab id; only the presentation drops the trailing space of the
    * missing-id message).
    */
  def outcomeLines(
      outcome: Either[RegistryError, TabShellScrollback.Screen]
  ): Vector[String] =
    outcome match
      case Left(err)     => Vector(s"error: ${err.message}".stripTrailing)
      case Right(screen) => screen.render

end ScrollbackCommands
