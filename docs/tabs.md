<!-- RAI-072: documentation of ACTUALLY implemented session-tab behavior
     (ui module, cards RAI-056/057/061/088, LIN-04A). Adds no new decisions;
     every claim has a file:line reference in this tree. -->

# Session tabs: SessionRegistry and the tab shell

The tab layer is the UI module `modules/ui/src/main/scala/raider/ui/`: a
read-model registry of open tabs over the runtime→UI event seam (see
[events.md](events.md)), with a thin JLine shell on top. This document describes
implemented behavior only.

## 1. Implementation status (honest account)

- The registry and shell are implemented and covered by unit tests
  (`modules/ui/src/test/scala/raider/ui/`: `SessionRegistrySpec`,
  `TabShellSpec`, `AuxTabSpec`, `ShellViewsStateSpec`) and the PTY probe
  `scripts/quality/tabs_pty_probe.py`.
- `TabShell` in this tree is NOT connected to the production REPL entry point:
  calls to `TabShell.run` occur only in tests (`TabShellSpec.scala:121`,
  `TabShellPtyApp.scala:68` under `modules/ui/src/test/scala/raider/ui/`); no
  consumers of `raider.ui` exist in `modules/*/src/main` outside the module.
- `:new` creates a tab with SYNTHETIC job/root IDs (`ShellExecutor.scala:61-66`
  under `modules/ui/src/main/scala/raider/ui/`); binding a tab to a real
  runtime job (card RAI-062) and REPL cancel binding are not implemented.

## 2. SessionRegistry

`SessionRegistry` is UI tab state over `EventHub`
(`SessionRegistry.scala:116-134` in
`modules/ui/src/main/scala/raider/ui/SessionRegistry.scala`). Key properties:

- Tab identity: `TabId` is assigned once at `create` and never changes
  (`SessionRegistry.scala:18-24`); tabs are immutable — observable state lives
  in registry refs (`SessionRegistry.scala:240-254`).
- `create(jobId, rootId, label)`: first subscribe to the hub without a loss
  window, then create state and start a consumer fiber; the new tab gets focus
  (`SessionRegistry.scala:137-140,289-337`).
- Each per-tab consumer receives one hub envelope: deduplicate and reorder by
  `seq` before folding (the hub does not specify relative delivery order across
  classes) using `Reorder` (`SessionRegistry.scala:256-282`); fold with
  `SessionReducer.reduce` (`SessionRegistry.scala:445-463`).
- Read model: `tabs` (creation order), `tab(tabId)`, `focused`, `snapshot`,
  `history`, `scrollback` (`SessionRegistry.scala:142-208,339-424`). Status in
  `TabView` comes from folding the hub (`EventHub.snapshot`), the authoritative
  job state; `lastSeq` is the tab's local watermark
  (`SessionRegistry.scala:63-79,613-627`).
- Unread: every event for a non-focused tab increments its counter; focusing
  resets it to 0 (`SessionRegistry.scala:521-528`). Focus and navigation are
  pure UI operations: no publishing, cancellation, or job/hub/policy calls
  (`SessionRegistry.scala:125-128,379-394`).
- Draft is a tab-local UI string (not an event and not published):
  `draftAppend`/`draftClear`/`draftGet` (`SessionRegistry.scala:190-197`).
- Bounded buffers with honest eviction counters: history holds the last 200
  folds (`HistoryCap`, `SessionRegistry.scala:212-213,465-478`); scrollback
  holds the last 500 Diagnostics/ToolProgress lines (`ScrollbackCap`,
  `SessionRegistry.scala:215-216,480-509`). A Terminal snapshot always remains
  the last history item and appears in `snapshot`
  (`SessionRegistry.scala:44-48`).
- Scrollback text splitting: CRLF → LF, split on LF; a trailing newline is a
  terminator, not an empty line (`SessionRegistry.scala:511-519`).
- Errors are typed: `UnknownTab` and `NoTabs`; unknown values are never silent
  (`SessionRegistry.scala:27-38`).
- `close(tabId)`: job status comes from the hub snapshot (authoritative; `None`
  means the job has not published yet and is treated as live). A terminal job
  is removed (`Closed`); for a live job, the registry changes nothing and
  returns `RunningChoice` (`SessionRegistry.scala:148-157,350-366`). Both
  decisions carry the lineage set: other open tabs with the same `rootId`, in
  creation order (`SessionRegistry.scala:97-114,603-611`). Removal interrupts
  the consumer fiber (which unregisters its hub queue) and atomically repairs
  focus; repeated close is idempotent (`SessionRegistry.scala:562-601`).
- `hide(tabId)` removes a tab from the UI without touching runtime
  (`SessionRegistry.scala:159-162,368-372`). `rename(tabId, label)` changes
  only the display label (`SessionRegistry.scala:164-168,374-375`).
- Navigation is ONE call: `focus`/`focusNext`/`focusPrev` (a wrapping ring in
  creation order) (`SessionRegistry.scala:173-188,530-560`).
- There is no `stop()`: consumer fibers are interrupted by `close`/`hide`; the
  registry is a process-lifetime object (`SessionRegistry.scala:131-134`).

## 3. TabShell

`TabShell` is a thin JLine loop over the registry and hub
(`TabShell.scala:7-25` in `modules/ui/src/main/scala/raider/ui/TabShell.scala`):

- ONE writer/reader: all visible output goes through `ShellTerminal.printAbove`
  (JLine `printAbove` preserves the draft during background writes); no raw
  `println` in the session (`TabShell.scala:11-14`; `ShellTerminal.scala:12-33,41-49`).
- Each loop iteration reads one line, routes a command, renders feedback, and
  refreshes the prompt with current focus; EOF exits cleanly
  (`TabShell.scala:88-126`).
- Pure renderers are separated: `CommandRouter` (`CommandRouter.scala:3-6`),
  `TabBarRenderer` (`TabBarRenderer.scala:3-5`), and `ShellFeedback.render`
  (`ShellFeedback.scala:73-113` under `modules/ui/src/main/scala/raider/ui/`).
- Background pump: subscribe to the hub for the viewed job and redraw the tab
  bar (label + status + unread) for EACH delivered envelope; fibers are scoped
  and interrupted with the shell scope (`TabShell.scala:130-174`).

## 4. Commands

The palette consists of exactly these commands (`CommandRouter.scala:11-24`;
`:help` help is in `TabBarRenderer.scala:23-37`, with a hint line under every
bar rendering in `TabBarRenderer.scala:39-41`):

| Command | Behavior |
| --- | --- |
| `:tabs` | Tab bar: creation order, `*` marks focus, session status uses the common `StatusText` format (RAI-097: `<body> \| <counters>`, same rendering as CLI chat; honest unknown is “cost unknown”), unread badge (hidden at 0) (`TabBarRenderer.scala:8-20`, `statustext.scala`; empty state: `TabBarRenderer.scala:12`) |
| `:new <label>` | New tab with synthetic job/root (see §1); missing argument is a typed error (`ShellCommand.scala:60-63`) |
| `:switch <n\|label>` | 1-based number (as in `:tabs`), exact label, then unique prefix; ambiguous/unknown is a typed error with alternatives (`CommandRouter.scala:35-39,118-148`) |
| `:next` / `:prev` | Focus next/previous tab (resets unread) (`ShellExecutor.scala:104-119`) |
| `:rename <label>` | Rename focused tab; UI only (`ShellExecutor.scala:120-129`) |
| `:close` | Close focused tab (flow in §5) (`ShellExecutor.scala:130,164-200`) |
| `:plugins` | Loaded plugins screen with name/version/active from a PURE provider; honestly shows `(no plugins)` when empty (`ShellExecutor.scala:133-134`; `ShellViews.scala:32-46,110-124`) |
| `:state <scope>` | Scope usage/quota; when there is no store, show a typed hint, not an error (`ShellExecutor.scala:135-140`; `ShellViews.scala:126-138`) |
| `:aux` | The sole auxiliary tab (§6) (`ShellExecutor.scala:141-148`) |
| `:help` | Palette (`ShellExecutor.scala:131-132`) |
| `:quit` | Exit the shell (`ShellFeedback.scala:69,113`) |
| Any input without `:` | Draft for the focused tab (byte-exact), with echo feedback (`CommandRouter.scala:52-55`; `ShellExecutor.scala:150-162`; `ShellFeedback.scala:96-97`) |

Input errors are typed and include hints: unknown command, missing argument,
no open tabs, invalid number/label, ambiguous prefix (`ShellCommand.scala:46-92`).
Registry errors never escape the executor; they become `ShellFeedback.Failure`
(`error: ...`, `ShellExecutor.scala:55-58`).

## 5. `:close` flow and cancellation

The registry returns a typed decision; `CloseChoiceResolver` chooses what to do
for a live tab (`ShellExecutor.scala:8-14,164-175`):

- `Closed(affected)` — the job is terminal according to the hub snapshot: the
  tab is removed and other open tabs with the same root are listed
  (`ShellFeedback.scala:86-87`).
- For a live tab, show `[h]ide / [c]ancel-request` (`TabShell.scala:28-32`):
  `h`/`hide` → `registry.hide` (the job keeps running and the tab disappears
  from `:tabs`; `ShellFeedback.scala:88-91`); `c`/`cancel` →
  `TabCloseChoice.CancelRequested` — REPORT ONLY: the UI layer itself never
  cancels, interrupts, or publishes the job; the tab stays open
  (`ShellFeedback.scala:32-40,92-95`; `ShellExecutor.scala:164-198`). There is
  no direct runtime cancel binding in the UI layer (RAI-062 is not implemented).
- The interactive resolver asks again up to 3 times; invalid input, EOF, or a
  dead terminal safely defaults to the non-destructive `CancelRequested`
  (report-only) choice (`ShellExecutor.scala:16-53`).

## 6. Auxiliary tab `:aux`

- Exactly ONE aux tab: a pure `AuxTabProvider` (`title`, `available`, `render`)
  is connected when constructing the shell; default is none (honest “no tab”)
  (`AuxTab.scala:3-17,18-33` in
  `modules/ui/src/main/scala/raider/ui/AuxTab.scala`).
- `render` is called on demand, once per display, never during construction or
  speculatively; `available = false` renders a typed hint and does not read
  content (`AuxTab.scala:9-15,23-27`; `ShellFeedback.scala:59-65,105-108`).
- Display-only tab, with no drafts; switching to it and back does not touch the
  session registry — focus/unread/draft remain with session tabs
  (`AuxTab.scala:13-16`; `ShellCommand.scala:31-36`).
- Rendering is one block: title + lines (CRLF → LF; trailing newline is a
  terminator), via one `printAbove` (`AuxTab.scala:39-57`).

## 7. `:plugins`/`:state` screens

The UI performs no IO itself: data comes from PURE `ShellViews` providers,
connected when constructing the shell; providers are total and do not throw;
missing store is `None` (`ShellViews.scala:9-15,32-46`). Scope-string
convention: `""`/`"agent"` is the agent's private scope, `root:<rootId>` is the
run's shared blackboard, `global` is explicit cross-run scope; any other string
is `None` (`ShellViews.scala:48-60`). The real `StateStore` adapter (RAI-094)
provides live usage/quotas (`ShellViews.scala:62-102`).
