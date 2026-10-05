# Raider TUI Design — custom renderer on JLine (MIT-only)

Implementation design for [ADR-016](adr/ADR-016-tui-renderer.md) (accepted):
a **custom diff-based ANSI renderer over JLine 4** — no Lanterna, no new
runtime dependency, MIT-clean. This document is the "how"; the roadmap
(`docs/roadmap-10k.md` Phase 0/2) is the "when".

## 1. The one hard problem, and the trick that solves it

Concurrent output while a prompt is active is the classic TUI killer. JLine
solves it for us: `LineReader.printAbove(...)` prints lines ABOVE the active
prompt without corrupting it (the same mechanism scala-cli/Ammonite use for
concurrent output). **Mode A (inline) is built entirely on printAbove** —
which is why it can be small, robust, and shipped first.

## 2. Three render modes behind one seam

```scala
trait ScreenRenderer:
  def init(size: Size): UIO[Unit]
  def draw(frame: Frame): UIO[Unit]      // diff-based; renderer owns the strategy
  def onResize(size: Size): UIO[Unit]    // full repaint
  def shutdown(): UIO[Unit]
```

| Mode | When | Technique | Native scrollback | Scope |
| --- | --- | --- | --- | --- |
| **A `inline`** | v1 (spike → Phase 2 start) | JLine `LineReader` + `printAbove`; one in-place rewriteable status line (`\r` + `ESC[K`) | kept (terminal's own) | ~600h incl. sessions wiring |
| **B `fullscreen`** | v2 (Phase 2) | Alternate screen (`ESC[?1049h`), cell buffer + diff redraw, panes per tab | replaced by bounded ring buffers per tab (+ `Ctrl+B` dump to file) | +~800h |
| **C `web`** | Phase 7, gated | same events over WebSocket (zio-http) | n/a | 900h budgeted |

v1 rationale: inline keeps native scrollback and copy/paste, avoids all
alternate-screen terminal quirks, and tab switching = separator + bounded
tail replay (200 lines) from the switched-to tab's ring buffer. Fullscreen
panes arrive only when v1 usage proves they are missed.

## 3. Frame model (pure, testable — no terminal needed for tests)

```scala
final case class Span(text: String, style: Style)          // Style: palette enum
final case class Line(spans: List[Span])
final case class Frame(lines: List[Line])                  // logical frame

// Mode B internals:
final case class Cell(ch: Char, style: Style)
final class CellBuffer(val cols: Int, val rows: Int)       // Array[Cell], mutable hot path
```

- Style = small palette enum (fg/bg/bold/dim) → ANSI SGR table, degrading
  16-color → 256 → truecolor by `$TERM` detection.
- Components render `Line`s: `TabBar`, `StatusLine(agent)`, `LogPane(ring)`,
  `InputLine`. Frames are pure values → **golden frame tests need no terminal**.
- Mode B diff: per-row dirty bitmap; emit `ESC[r;cH` + changed runs only;
  resize ⇒ full repaint. Budget: ≤16 ms diff at 120×40 (gate).

## 4. Threading model (ZIO — nothing prints directly)

```
AgentLoop/session fibers ──AgentEvents──► EventBus (ZHub, sliding ring)
        │                                       │
        │            eventFiber ◄───────────────┘  updates tab ring buffers
        │                                       │  + StatusLedger, marks dirty
        ▼                                       ▼
  StreamingDisplay sinks            renderFiber (awaits dirty, coalesces,
  emit events, never print            ≤30 fps, draws via ScreenRenderer)
                                             ▲
inputFiber ──UIEvent(Key/Paste/Resize)───────┘   JLine NonBlockingReader
                                                  poll (50 ms timeout)
```

- **Only the render fiber writes to the terminal.** Sessions, tools and model
  streaming never print — they emit events (this is why any renderer works).
- renderFiber coalesces: if multiple dirty ticks land within a frame window,
  one draw. Mode A "draw" = printAbove(new log lines) + status-line rewrite.
- Input fiber never blocks output; paste is bracketed-paste aware.

## 5. Input handling

- Mode A: JLine `LineReader` keeps owning the bottom line (history, editing,
  multiline continuation — the REPL already proves this path); special keys
  (Ctrl+T/W, Ctrl+PgUp/PgDn, Alt+1..9, Ctrl+K, F1/F2) are read via widgets /
  key bindings and dispatched to the UI controller.
- Mode B: raw keys → a minimal single-line editor component we own
  (insert/backspace, ←→, Home/End, Ctrl+A/E/K/U/W, ↑↓ history). Bounded,
  well-understood, golden-tested.
- Terminal resize: JLine `Signal.WINCH` → `onResize` → full repaint (Mode B) /
  chrome re-render (Mode A).
- Ctrl+C stays interrupt-current-agent (existing semantics); Ctrl+Q quits with
  a running-agents confirm.

## 6. Module layout

```
modules/ui  (raider-ui; new in Phase 0 spike, grown in Phase 2)
└── raider.ui
    ├── ScreenRenderer.scala      // seam + Size/Frame/Line/Span/Style
    ├── InlineRenderer.scala      // Mode A: LineReader + printAbove
    ├── AnsiRenderer.scala        // Mode B: CellBuffer + diff + alt-screen
    ├── CellBuffer.scala          // pure; golden-ANSI tests
    ├── KeyBindings.scala         // key → UiCommand table
    └── (Phase 2) SessionRegistry, TabController, RingBuffer
```

`raider-ui` depends only on `raiderCore` + JLine (+ zio). Sessions/loops come
from `raiderRuntime` in Phase 2 — the spike ships renderers + tests only.

## 7. Testing strategy (the gate culture, applied)

1. **Golden frames** (pure): Frame → expected ANSI string for AnsiRenderer;
   Line → printAbove expectations for InlineRenderer. No terminal required.
2. **PTY smoke** (`scripts/quality/tui_render_smoke.py`, pattern of
   `repl_pty_smoke.py`): spawn `raider ui --demo` under a PTY; assert frames
   contain the tab bar, status line updates on a scripted event feed, resize
   (SIGWINCH + size change) leaves no ghost cells, quit exits 0.
3. **Terminal matrix** (manual + scripted where possible): tmux, screen,
   iTerm2, Terminal.app, IntelliJ run console.
4. Existing project rules unchanged: `-Werror`, scalafmt/scalafix,
   forbidden-apis, license-audit (MIT-only — JLine BSD is already audited).

## 8. Spike acceptance (Phase 0 exit)

| Check | Bar |
| --- | --- |
| Mode A concurrency | 200 printAbove lines + live status rewrite while prompt active — zero corrupted prompts across 100 PTY runs |
| Mode B perf | ≤16 ms diff/frame at 120×40, 10k-line ring backing store |
| Resize | WINCH at 5 random sizes → no ghost cells, no exception |
| Matrix | 5 terminals from §7.3 visually verified + PTY asserts pass |
| License | `license-audit` gate stays green (no new deps) |

## 9. Phase mapping

- **Phase 0 (spike)**: raider-ui module with renderers + golden + PTY smoke;
  no sessions. Proves §8.
- **Phase 2 (tabs v1)**: Mode A wired to SessionRegistry/EventBus/StatusLedger
  (ADR-2); `raider ui` becomes the default frontend; `chat` stays as a plain
  mode.
- **Phase 2 later (tabs v2)**: Mode B full-screen panes if v1 usage demands.
