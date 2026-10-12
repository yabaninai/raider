package raider.repl

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.events.{EventError, EventHub, EventPayload}
import raider.ui.{RegistryError, SessionRegistry, TabId}
import zio.{IO, ZIO}

/** Product bounded-scrollback surface of the REPL shell host (card RAI-104).
  *
  * The frozen RAI-057 [[SessionRegistry]] owns the mechanics: per-tab
  * scrollback bounded to the last [[SessionRegistry.ScrollbackCap]]
  * Diagnostics/ToolProgress lines with an honest eviction counter, and the
  * typed `RegistryError.UnknownTab` for any operation on a tab that is not
  * open. This object is the PRODUCT-side consumer of that frozen surface inside
  * the REPL module — the home of the product shell host (RAI-102):
  *
  *   - [[Screen]] renders one tab's scrollback with the eviction counter
  *     VISIBLE (P2S-01): header `last N of M lines (K evicted, cap C)` over the
  *     byte-exact window content.
  *   - [[screen]] reads label + scrollback through the frozen registry; the
  *     typed unknown-tab error propagates as-is and a lookup never creates a
  *     tab (P2S-02: no phantom tabs).
  *   - [[publishLines]] is the product burst source: every line becomes one
  *     Diagnostics envelope through the REAL hub (ADR-024) — the same seam a
  *     live runtime publishes on — so bounds hold on the product event loop,
  *     not on a mock (P2S-03).
  *
  * Wiring only: no loop, no terminal writes (the shell owns the ONE writer,
  * RAI-061); [[Screen.render]] returns the exact lines for a caller to print
  * above the prompt. Interactive command wiring into the shell input loop stays
  * with the user-input integration cards (per the RAI-102 record the
  * Main.ttyLoop/chat-loop integration is the consumers' step); acceptance
  * P2S-01..03 drives this surface through the product wiring
  * (`TabShellHost.wiring`) in `raider.repl.TabShellScrollbackSpec`.
  */
object TabShellScrollback:

  /** One tab's scrollback screen. `evicted + lines.size` is the total number of
    * scrollback lines the tab actually received; nothing else is hidden — the
    * counter is part of the render, never a silent drop.
    */
  final case class Screen(
      label: String,
      cap: Int,
      evicted: Long,
      lines: Vector[String]
  ):

    /** Exact rendered lines: the header carries the eviction counter and the
      * cap; the window content follows byte-exact (RAI-102 scrollback
      * byte-precision carries over to the screen).
      */
    def render: Vector[String] =
      Vector(
        s"scrollback [$label]: last ${lines.size} of " +
          s"${evicted + lines.size} lines ($evicted evicted, cap $cap)"
      ) ++ lines

  /** Product read of one tab's bounded scrollback + label. Consumes the frozen
    * registry only: `RegistryError.UnknownTab` for a tab that is not open
    * (typed, P2S-02).
    */
  def screen(
      registry: SessionRegistry,
      tabId: TabId
  ): IO[RegistryError, Screen] =
    for
      view <- registry.tab(tabId)
      scr <- registry.scrollback(tabId)
    yield Screen(
      view.label,
      SessionRegistry.ScrollbackCap,
      scr.evicted,
      scr.lines
    )

  /** Product burst feeder: publish each line as ONE Diagnostics envelope
    * through the real hub. Chunking/pacing across a large burst belongs to the
    * caller — this is a plain publish loop, no scheduler of its own (a card
    * here adds no second runtime).
    */
  def publishLines(
      hub: EventHub,
      jobId: JobId,
      rootId: RootId,
      sessionId: SessionId,
      lines: Vector[String]
  ): IO[EventError, Unit] =
    ZIO.foreachDiscard(lines)(line =>
      hub.publish(jobId, rootId, sessionId, EventPayload.Diagnostics(line))
    )

end TabShellScrollback
