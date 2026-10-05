# ADR-016: Terminal UI renderer — custom diff-based ANSI on JLine (MIT-only)

Status: **Accepted** (owner directive, 2026-10-05). Supersedes the open
"ADR-TUI" question in [the 10k roadmap](../roadmap-10k.md). Implementation
design: [docs/tui-design.md](../tui-design.md).
Scope: `raider.ui` (Phase 2 of the 10k roadmap), Phase-0 spike.

## Context

The 10k roadmap's tab/status UX needs a full-screen-capable terminal renderer:
tab bar, per-agent status lines, scrolling log panes, live progress. The owner
requires the project to remain usable under **MIT**; the runtime dependency
graph must never include copyleft licenses (enforced by the `license-audit`
gate since 2026-10-05).

Candidates evaluated:

| Option | License | Verdict |
| --- | --- | --- |
| Lanterna 3.1.x (widget toolkit) | **LGPL-3.0** | REJECTED — license incompatible with MIT-only policy, regardless of technical fit |
| Mordant (Kotlin) | MIT | REJECTED for now — pulls the Kotlin stdlib into a Scala-only product and ships styling, not the widget layer we need; revisit only with an owner-approved ADR |
| Web renderer first (zio-http) | Apache-2.0 | DEFERRED — Phase 7, gated; terminal-first is the product instinct |
| **Custom diff-based ANSI renderer over JLine 4** | JLine is **BSD-3**, already in the graph (REPL dependency) | **ACCEPTED** |

## Decision

1. `raider.ui` implements a `ScreenRenderer` seam; the first implementation is
   `AnsiRenderer`: an in-memory cell buffer + diff redraw over JLine 4's
   `Terminal` (raw mode, explicit ANSI sequences). No new runtime dependency.
2. Scope discipline: the renderer draws exactly four things — tab bar, status
   lines, a scrolling log/pane region, and the prompt row. No general widget
   toolkit will be built.
3. Performance/compatibility bar (Phase-0 spike must prove it, then become the
   Phase-2 terminal-matrix gate): stable output in tmux, screen, iTerm2,
   Terminal.app and the IntelliJ run console under resize; ≤16 ms frame diff
   at 120×40 with a 10k-line scrollback backing store.
4. The seam keeps a future web renderer (Phase 7) implementable against the
   same StatusLedger/EventBus inputs — the renderer is a view, never a runtime.

## Consequences

- Zero license risk; zero new dependencies.
- ~+200h engineering cost vs a ready widget toolkit — accepted and budgeted
  in Phase 2 (the UI surface is deliberately small: 4 components).
- Terminal quirks (alternate screen buffer, resize storms, Windows terminals)
  are OUR responsibility — covered by the spike and the matrix gate, not by a
  library. Windows Terminal support is best-effort behind the matrix gate.
- If the spike disproves feasibility (flicker/perf unfixable), the fallback is
  NOT Lanterna: it is a reduced inline UI (status line + tab hints without
  full-screen panes, JLine-only) — still MIT-clean. Full-screen panes then
  move to the Phase-7 web console.
