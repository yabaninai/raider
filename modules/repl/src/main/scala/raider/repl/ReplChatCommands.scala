package raider.repl

/** Companion registry of the chat commands of the REPL cycle (card RAI-111).
  *
  * `Commands.scala` (W10 slice) exposes no machine-readable registry: the
  * command surface lives in the `handle` match and in the prose of
  * `Commands.helpText`. Editing `Commands` is outside this card's boundary, so
  * the palette derives from THIS registry instead, and freshness against the
  * source of truth is enforced by reading (never by a second hand list):
  *
  *   - SOURCE OF TRUTH: `Commands` / `Commands.helpText` — the card-designated
  *     owner of the chat-command surface. This registry is a reflect-free
  *     mapping of that surface: each entry names one command exactly as
  *     `handle` dispatches it, with the usage/summary text of its helpText
  *     line.
  *   - FRESHNESS: `ReplChatPaletteSpec` parses the command lines of
  *     `Commands.helpText` and requires the result to EQUAL this catalog
  *     (names, usage and summaries, in order). A command added to or removed
  *     from Commands, or a help line edited, fails the spec until the registry
  *     follows — the same listed-name discipline as the RAI-106
  *     CommandRouter-probe pattern.
  *
  * WHY A MAPPING, NOT RUNTIME PARSING OF helpText (the honest-choice
  * justification): product-side prose parsing would make the palette silently
  * track formatting changes of a help string (brittle by construction), while a
  * typed registry keeps the palette surface explicit and puts the drift
  * detection where it belongs — a test against the source of truth. The residue
  * is documented: a name present in helpText but NOT actually dispatched by
  * `Commands.handle` could only be caught by probing `handle` with a live
  * engine; out of scope here, helpText is owner-maintained truth.
  *
  * Pure data: no engine, no effects. Consumers: [[ShellPalette]] (the derived
  * chat catalog for hints) and [[CompletionSupport]] (TAB completion of
  * `:`-words in the REPL cycle).
  */
object ReplChatCommands:

  /** One chat command: the dispatch name (as `Commands.handle` matches it), the
    * usage token of its helpText line (arguments included) and the help
    * summary. Aliases (:h/:q/:exit) are intentionally absent — they are not
    * helpText lines, and helpText is the source of truth.
    */
  final case class Command(name: String, usage: String, summary: String)

  /** The chat-command catalog, in Commands.helpText order. */
  val catalog: Vector[Command] = Vector(
    Command(":help", ":help", "this text"),
    Command(
      ":jobs",
      ":jobs",
      "list jobs started through the facade (id, status, label)"
    ),
    Command(
      ":cancel",
      ":cancel <id>",
      "request cancellation of a job (false if already terminal)"
    ),
    Command(
      ":log",
      ":log <id>",
      "show the trace (rounds, tools, results) of a job"
    ),
    Command(
      ":reset",
      ":reset",
      "clear interpreter bindings; facade prelude is re-imported"
    ),
    Command(
      ":quit",
      ":quit",
      "exit the REPL (active jobs are reported, then dropped)"
    )
  )

  /** Dispatch names only (the palette/completion surface), helpText order. */
  val names: Vector[String] = catalog.map(_.name)

end ReplChatCommands
