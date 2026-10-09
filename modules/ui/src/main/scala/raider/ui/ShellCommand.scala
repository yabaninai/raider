package raider.ui

/** One routed shell action (card RAI-061).
  *
  * `Switch`/`Close` carry the tab id RESOLVED by [[CommandRouter]] against the
  * registry read model at parse time; `Rename`/`Draft` apply to the focused tab
  * at execution time (which may have moved since — the executor re-checks and
  * reports typed failures, never silently ignores).
  */
enum ShellCommand:
  case Tabs
  case New(label: String)
  case Switch(tabId: TabId)
  case Next
  case Prev
  case Rename(label: String)
  case Close(tabId: TabId)
  case Help
  case Quit

  /** `:plugins` (card RAI-088): the loaded-plugin screen; the rows come from
    * the [[ShellViews]] provider — the UI performs no IO of its own.
    */
  case Plugins

  /** `:state <scope>` (card RAI-088): the StateStore usage/quota screen for one
    * scope; an absent store renders as a typed hint, never an error.
    */
  case State(scope: String)

  /** Any non-command input: appended to the focused tab's draft (never an
    * event, never published — ADR-024 §2.5).
    */
  case Draft(line: String)

/** Typed, user-facing input problems (card RAI-061 SHELL-02: unknown
  * session/command is a typed error with a hint — never a silent ignore).
  */
sealed trait ShellInputError:
  def message: String

  /** Actionable follow-up lines: what IS available (the palette). */
  def hints: Vector[String]

object ShellInputError:

  final case class UnknownCommand(raw: String) extends ShellInputError:
    val message: String = s"unknown command: $raw"

    val hints: Vector[String] =
      Vector(s"available commands: ${CommandRouter.commands.mkString(" ")}")

  final case class MissingArgument(command: String, usage: String)
      extends ShellInputError:
    val message: String = s"missing argument for $command"
    val hints: Vector[String] = Vector(s"usage: $usage")

  final case class NoOpenTabs(action: String) extends ShellInputError:
    val message: String = s"$action: no open tabs"
    val hints: Vector[String] = Vector("usage: :new <label>")

  final case class BadTabIndex(raw: String, count: Int) extends ShellInputError:
    val message: String = s"bad tab number: $raw (open tabs: $count)"

    val hints: Vector[String] = Vector(
      "usage: :switch <n|label>; numbers are 1-based as shown by :tabs"
    )

  final case class UnknownTabLabel(query: String, available: Vector[String])
      extends ShellInputError:
    val message: String = s"unknown tab: $query"

    val hints: Vector[String] =
      if available.isEmpty then Vector.empty
      else Vector(s"open tabs: ${available.mkString(", ")}")

  final case class AmbiguousTabLabel(query: String, matches: Vector[String])
      extends ShellInputError:

    val message: String =
      s"ambiguous tab: $query (matches: ${matches.mkString(", ")})"

    val hints: Vector[String] = Vector(
      "type more characters of the label or use the 1-based tab number"
    )
