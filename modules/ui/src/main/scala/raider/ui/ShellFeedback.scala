package raider.ui

/** What one executed command reports (card RAI-061). Typed, so SHELL tests
  * assert structure; [[ShellFeedback.render]] turns it into the exact lines the
  * ONE writer prints above the prompt.
  */
enum ShellFeedback:
  case TabBar(lines: Vector[String])
  case Help(lines: Vector[String])

  /** `:new` — the tab is bound to a SYNTHETIC unique job/root id; the real
    * runtime binding is card RAI-062.
    */
  case Created(tab: TabView)
  case Switched(tab: TabView)
  case Renamed(tab: TabView)

  /** `:close` on a terminal tab: removed; `affected` lists the other open
    * same-root tabs (root close reports its children).
    */
  case ClosedTerminal(
      tabId: TabId,
      label: String,
      affected: List[(TabId, String)]
  )

  /** The resolver chose [[TabCloseChoice.Hide]]: the tab leaves the UI, the job
    * keeps running.
    */
  case Hidden(tabId: TabId, label: String, affected: List[(TabId, String)])

  /** The resolver chose [[TabCloseChoice.CancelRequested]]: REPORTED only —
    * nothing in this layer ever cancels (the runtime binding is RAI-062); the
    * tab stays open.
    */
  case CancelReported(
      tabId: TabId,
      label: String,
      affected: List[(TabId, String)]
  )

  case DraftEchoed(tabId: TabId, label: String, appended: String, draft: String)

  /** `:plugins` (card RAI-088): the loaded-plugin rows from the provider. */
  case Plugins(plugins: List[PluginView])

  /** `:state <scope>` (card RAI-088): usage/quota for one scope. */
  case State(scope: String, usage: StateView)

  /** `:state <scope>` with no store attached (card RAI-088): a typed hint. */
  case StateStoreMissing(scope: String)

  /** `:aux` (card LIN-04A): the auxiliary tab screen — title + raw provider
    * content; [[ShellFeedback.render]] splits it into the exact lines of ONE
    * printAbove block.
    */
  case AuxTab(title: String, content: String)

  /** `:aux` with no auxiliary tab registered (card LIN-04A): a typed hint. */
  case AuxTabMissing

  /** `:aux` when the provider reports unavailable (card LIN-04A, LIN4-02 stub):
    * a typed hint — the content is not read.
    */
  case AuxTabUnavailable(title: String)

  case Hint(error: ShellInputError)
  case Failure(error: RegistryError)
  case Quit

object ShellFeedback:

  /** Exact rendered lines (pure, deterministic — asserted in SHELL tests). */
  def render(f: ShellFeedback): Vector[String] =
    f match
      case ShellFeedback.TabBar(lines) => lines
      case ShellFeedback.Help(lines)   => lines
      case ShellFeedback.Created(tab) =>
        Vector(
          s"tab created: ${tab.label} (job ${tab.jobId.value}, root ${tab.rootId.value}; synthetic binding until RAI-062)"
        )
      case ShellFeedback.Switched(tab) =>
        Vector(s"focused: ${tab.label} [${tab.status}]")
      case ShellFeedback.Renamed(tab) =>
        Vector(s"renamed to: ${tab.label}")
      case ShellFeedback.ClosedTerminal(_, label, affected) =>
        Vector(s"closed: $label") ++ affectedLines(affected)
      case ShellFeedback.Hidden(_, label, affected) =>
        Vector(
          s"hidden: $label (the job keeps running; :tabs will not show it)"
        ) ++ affectedLines(affected)
      case ShellFeedback.CancelReported(_, label, affected) =>
        Vector(
          s"cancel requested for: $label — reported to the runtime binding, NOT self-cancelled; the tab stays open (cancel binding: RAI-062)"
        ) ++ affectedLines(affected)
      case ShellFeedback.DraftEchoed(_, label, appended, draft) =>
        Vector(s"echo: $appended", s"draft [$label]: $draft")
      case ShellFeedback.Plugins(plugins) => ShellScreens.plugins(plugins)
      case ShellFeedback.State(scope, usage) =>
        ShellScreens.state(scope, usage)
      case ShellFeedback.StateStoreMissing(scope) =>
        ShellScreens.stateMissing(scope)
      case ShellFeedback.AuxTab(title, content) =>
        AuxTabRenderer.screen(title, content)
      case ShellFeedback.AuxTabMissing =>
        Vector("hint: no auxiliary tab is registered")
      case ShellFeedback.AuxTabUnavailable(title) =>
        Vector(s"hint: auxiliary tab [$title] is not available")
      case ShellFeedback.Hint(error) =>
        ("hint: " + error.message) +: error.hints.map("  " + _)
      case ShellFeedback.Failure(error) =>
        Vector("error: " + error.message)
      case ShellFeedback.Quit => Vector.empty

  private def affectedLines(affected: List[(TabId, String)]): Vector[String] =
    if affected.isEmpty then Vector.empty
    else
      Vector("affected same-root tabs:") ++
        affected.map((id, label) => s"  - $label (${id.value})")
