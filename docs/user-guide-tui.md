# Tab shell guide

Raider's interactive tab shell shows tab status, unread event counts, and recent
event text. It is a separate mode from the compiler-backed Scala REPL:
`--tabs` selects the shell; omit it for the Scala evaluation loop in
[onboarding](onboarding.md) and the [REPL guide](user-guide-repl.md).

## Prepare and launch

Start with the JDK and sbt requirements in [onboarding](onboarding.md). From
the repository root, compile the REPL module and write its runtime classpath:

```sh
mkdir -p artifacts/user-tabs
sbt --batch --error 'raiderRepl/Compile/compile' \
  'export raiderRepl/Compile/fullClasspath' \
  > artifacts/user-tabs/build.log 2>&1
tail -n 1 artifacts/user-tabs/build.log \
  > artifacts/user-tabs/classpath.txt
RAIDER_CP=$(cat artifacts/user-tabs/classpath.txt)
```

Launch in an interactive terminal with the scripted mock stream:

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main --tabs
```

The banner identifies the Scala engine and tab mode and reports the scripted
fixture stream (mock provider; no network). The prompt is `raider>`. This mode
is not Scala evaluation; use the no-`--tabs` [onboarding command](onboarding.md).

## Open and navigate tabs

Create two example tabs. A new tab becomes focused; the bar shows a focus marker,
label, status, and any unread count:

```text
raider> :new work
*1. work [Running]
raider> :new review
 1. work [Running]
*2. review [Running]
```

The command also prints generated synthetic job and root IDs. After the mock
stream publishes its first event, rows show `Running`. Scripted diagnostics
redraw the bar; an unfocused tab can show an unread badge. This is demonstration data, not an
agent's progress or model answer.

List tabs at any time and focus one by its 1-based row number or label:

```text
raider> :tabs
 1. implementation [Running] (3 unread)
*2. review [Running]
raider> :switch work
focused: work [Running]
```

`:next` and `:prev` move focus in creation order and wrap around; focusing a tab
clears its unread count. `:rename <label>` changes only the focused tab's label:

```text
raider> :rename implementation
renamed to: implementation
```

`:help` prints the command list. Type a command prefix such as `:sw` and press
Tab for completion. Unknown commands may show a nearest-command hint. Ordinary
text is not a shell command, even if it contains Scala code.

## Drafts and scrollback

**Typing ordinary text and pressing Enter only appends that line to the focused
tab's local draft. It does not send a prompt, call a model, execute a job, or
evaluate Scala.** The shell echoes the new line and the accumulated draft:

```text
raider> Check the boundary case
 echo: Check the boundary case
 draft [implementation]: Check the boundary case
```

Drafts stay with their tabs when you switch away and back. They are UI-local,
not a saved conversation or job input; this shell has no submit command.

Inspect recent event text with `:scrollback <tab>`:

```text
raider> :scrollback implementation
scrollback [implementation]: last 220 of 220 lines (0 evicted, cap 500)
ev work 1 payload xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Use a 1-based row number, exact tab ID or label, or unique label prefix for
`<tab>`. A target is required; bare `:scrollback` does not default to the focused
tab. The screen reports remaining and evicted lines. Scrollback keeps up to 500
event-text lines per tab.

## Close a tab and exit

`:close` acts on the focused tab. If it is running, choose hide (`h`) or cancel
request (`c`):

```text
raider> :close
tab is running - [h]ide (job keeps running) or [c]ancel-request?
```

Here `:new` creates a synthetic binding, not a runtime job. `h` removes the tab
from view; `c` reports a request but leaves it open. No real cancellation is
wired. A terminal tab closes directly. `:quit` or end-of-file exits the shell.

## Current scope

Mock mode demonstrates tabs, status, unread counts, drafts, and bounded
scrollback without network access. Even with a live provider configured, drafts do not call a model or start Scala
workflows; `:new` remains synthetic. Use the separate Scala
REPL or headless CLI for those tasks.
