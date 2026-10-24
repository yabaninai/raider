package raider.ui

/** Pure input router (card RAI-061): raw line + registry read model → typed
  * [[ShellCommand]] or typed [[ShellInputError]]. No terminal, no effects —
  * SHELL-02 exercises it directly, the [[TabShell]] loop is a thin adapter.
  */
object CommandRouter:

  /** The whole command palette; listed by `:help` and by unknown-command hints.
    */
  val commands: Vector[String] = Vector(
    ":tabs",
    ":sessions",
    ":switch",
    ":new",
    ":next",
    ":prev",
    ":rename",
    ":close",
    ":plugins",
    ":state",
    ":aux",
    ":help",
    ":quit"
  )

  private val usage: Map[String, String] = Map(
    ":new" -> ":new <label>",
    ":switch" -> ":switch <n|label>",
    ":rename" -> ":rename <label>",
    ":state" -> ":state <scope>"
  )

  /** Route one input line.
    *
    *   - blank lines and non-command input → `Draft` (the loop also skips
    *     blanks before routing; both are harmless here).
    *   - `:switch <n|label>` resolves against `tabs`: 1-based index (as shown
    *     by `:tabs`), exact label, then unique label prefix; ambiguous or
    *     unknown → typed error listing the alternatives.
    *   - `:close` resolves the FOCUSED tab; no open tabs → typed error.
    *   - Screen commands need no open tabs: `:plugins` (RAI-088) takes no
    *     argument, `:state <scope>` requires a scope (missing → typed usage
    *     error), `:aux` (LIN-04A) takes no argument; their data comes from the
    *     providers ([[ShellViews]] / [[AuxTabProvider]]).
    */
  def parse(
      raw: String,
      tabs: List[TabView],
      focused: Option[TabId]
  ): Either[ShellInputError, ShellCommand] =
    // Trimming is for COMMAND detection only; a Draft carries the raw line
    // byte-exact (SHELL-01: drafts are byte-identical across switches).
    val probe = raw.trim
    if probe.isEmpty then Right(ShellCommand.Draft(raw))
    else if !probe.startsWith(":") then Right(ShellCommand.Draft(raw))
    else
      val split = probe.split("\\s+", 2)
      val name = split(0)
      val rest = if split.length > 1 then split(1).trim else ""
      name match
        case ":tabs" | ":sessions" => Right(ShellCommand.Tabs)
        case ":next"               => Right(ShellCommand.Next)
        case ":prev"               => Right(ShellCommand.Prev)
        case ":help"               => Right(ShellCommand.Help)
        case ":quit"               => Right(ShellCommand.Quit)
        case ":new" =>
          requireArg(name, rest).map(ShellCommand.New(_))
        case ":rename" =>
          for
            label <- requireArg(name, rest)
            _ <- requireOpenTabs(name, tabs)
          yield ShellCommand.Rename(label)
        case ":switch" =>
          for
            token <- requireArg(name, rest)
            _ <- requireOpenTabs(name, tabs)
            tabId <- resolveTab(token, tabs)
          yield ShellCommand.Switch(tabId)
        case ":close" =>
          for
            _ <- requireOpenTabs(name, tabs)
            tabId <- requireFocused(name, tabs, focused)
          yield ShellCommand.Close(tabId)
        case ":plugins" => Right(ShellCommand.Plugins)
        case ":state"   =>
          // Screen commands need no open tabs (RAI-088); :state needs a scope
          requireArg(name, rest).map(ShellCommand.State(_))
        case ":aux" =>
          // The auxiliary tab (LIN-04A) needs no open tabs and no argument:
          // its data comes from the provider wired at construction
          Right(ShellCommand.Aux)
        case _ => Left(ShellInputError.UnknownCommand(name))

  private def requireArg(
      command: String,
      rest: String
  ): Either[ShellInputError, String] =
    if rest.isEmpty
    then Left(ShellInputError.MissingArgument(command, usage(command)))
    else Right(rest)

  private def requireOpenTabs(
      command: String,
      tabs: List[TabView]
  ): Either[ShellInputError, Unit] =
    if tabs.isEmpty then Left(ShellInputError.NoOpenTabs(command))
    else Right(())

  private def requireFocused(
      command: String,
      tabs: List[TabView],
      focused: Option[TabId]
  ): Either[ShellInputError, TabId] =
    focused match
      case Some(id) if tabs.exists(_.id == id) => Right(id)
      case _ => Left(ShellInputError.NoOpenTabs(command))

  private def resolveTab(
      token: String,
      tabs: List[TabView]
  ): Either[ShellInputError, TabId] =
    if token.nonEmpty && token.forall(_.isDigit) then
      token.toIntOption match
        case Some(n) if n >= 1 && n <= tabs.size => Right(tabs(n - 1).id)
        case _ => Left(ShellInputError.BadTabIndex(token, tabs.size))
    else
      val exact = tabs.filter(_.label == token)
      if exact.size == 1 then Right(exact.head.id)
      else
        val byPrefix = tabs.filter(_.label.startsWith(token))
        byPrefix match
          case single :: Nil => Right(single.id)
          case _ =>
            if byPrefix.size > 1
            then
              Left(
                ShellInputError.AmbiguousTabLabel(
                  token,
                  byPrefix.map(_.label).toVector
                )
              )
            else
              Left(
                ShellInputError.UnknownTabLabel(
                  token,
                  tabs.map(_.label).toVector
                )
              )
