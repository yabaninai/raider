package raider.ui

/** The ONE auxiliary tab (card LIN-04A, ADR-027 §3.3): the minimal tab-host
  * seam for one extra tab next to the session tabs.
  *
  *   - Registration is at shell construction: at most one provider is wired
  *     into [[TabShell]] (`Option[AuxTabProvider]`, default none — the honest
  *     "no auxiliary tab" state). The data comes from the provider; the UI
  *     performs NO IO (the RAI-088 [[ShellViews]] discipline).
  *   - `render` is a PURE function called on demand — once per switch to the
  *     tab, never at construction, never speculatively, and never while
  *     `available` is false.
  *   - No drafts (the P2 draft machinery is out of scope for this card): the
  *     tab is display-only. Switching to it and back never touches the session
  *     registry — focus, unread and drafts stay with the session tabs (ADR-024
  *     §2.5 ownership discipline).
  */
trait AuxTabProvider:

  /** The tab's display title (screen header; the future tab bar, LIN4-02). */
  def title: String

  /** Whether the tab currently has content to show. `false` renders the typed
    * "not available" hint (LIN4-02 stub) — never an empty screen, and the
    * provider's content is not read.
    */
  def available: Boolean

  /** The tab content: ONE string, read by the shell only when the tab is shown.
    * Lines are split on LF (CRLF normalized); a trailing newline is a
    * terminator, not an empty line.
    */
  def render: String

/** Pure rendering of the auxiliary tab (card LIN-04A). No terminal, no effects
  * — the block goes to the ONE writer ([[ShellTerminal.printAbove]]) through
  * [[ShellFeedback.render]].
  */
object AuxTabRenderer:

  /** The content of `render` as complete lines (same line semantics as the
    * scrollback splitter, card RAI-057: CRLF → LF, split on LF, a trailing
    * newline is a terminator).
    */
  def lines(render: String): Vector[String] =
    val normalized = render.replace("\r\n", "\n")
    val parts = normalized.split("\n", -1).toVector
    if parts.nonEmpty && parts.last.isEmpty && normalized.endsWith("\n")
    then parts.dropRight(1)
    else parts

  /** The whole screen: the typed header + the content lines. ONE block — the
    * caller prints it through a single printAbove call, so concurrent bar
    * renders cannot tear it (the PSH-03 atomicity discipline).
    */
  def screen(title: String, render: String): Vector[String] =
    Vector(s"aux tab [$title]:") ++ lines(render)
