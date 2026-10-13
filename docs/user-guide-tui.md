# Use the TUI

The TUI manages tabs, drafts, status, and event scrollback. **Typing a task in a
tab currently saves a draft; it does not start an agent.** To ask a model or
execute Scala, use the [Scala REPL](user-guide-repl.md) or [CLI](user-guide.md).

## Start

Complete [setup](onboarding.md) first. From the repository root:

```sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main --tabs
```

Run this in an interactive terminal. The banner should identify tabs mode and a scripted fixture stream. Mock mode shows demo activity without
contacting a model. If the classpath file is missing, build it with
`sh scripts/build/user-examples.sh`.

## Open two tabs

At the `raider>` prompt, enter:

```text
:new work
:new review
:tabs
:switch work
```

A new tab becomes focused. `:tabs` lists the tabs with their row numbers,
labels, status, and unread counts; the focused row has a marker. Mock activity
may update these rows while you work.

Rename the focused tab and switch between the two:

```text
:rename implementation
:next
:prev
```

`:next` and `:prev` wrap around the tab list. Focusing a tab clears its unread
count. You can also switch by row number, for example `:switch 2`.

## Keep a draft

With the `implementation` tab focused, type:

```text
Check the discount boundary
```

The shell echoes the line and appends it to that tab's draft. Switch to another
tab and back: the draft stays with its tab. Drafts are local to the running
shell; they are not saved conversations, and there is currently no submit
command. Scala expressions typed here are also stored as text.

## Read recent events

```text
:scrollback implementation
```

The command displays recent event text for that tab, including the number of
retained and evicted lines. It keeps up to 500 lines per tab. Supply a label,
row number, tab ID, or unique label prefix; bare `:scrollback` needs a target.
In mock mode these are demonstration events.

## Close or exit

`:close` acts on the focused tab. For a running tab, the shell asks whether to
hide it (`h`) or request cancellation (`c`). In this standalone TUI, new tabs
have synthetic job bindings: hiding removes the tab, while the cancellation
choice reports a request without cancelling a real runtime job. A completed
tab closes directly.

Use `:quit` to leave the TUI. To switch to the Scala REPL, exit and launch
without `--tabs`:

```sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main
```

Now `scout.ask("Hello")` is evaluated as Scala. See [model setup](onboarding.md#connect-a-model)
to replace mock replies with model responses. Configuring a live model does
not add prompt submission to the tab shell.

## Command reminder

| Command | Action |
| --- | --- |
| `:help` | Show the available commands |
| `:new <label>` | Create and focus a tab |
| `:tabs` | List tabs |
| `:switch <number-or-label>` | Focus a tab |
| `:next`, `:prev` | Move focus |
| `:rename <label>` | Rename the focused tab |
| `:scrollback <tab>` | Show recent event text |
| `:close` | Close or hide the focused tab |
| `:quit` | Exit |

Type a command prefix such as `:sw` and press Tab for completion.
