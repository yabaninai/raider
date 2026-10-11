package raider.ui

/** The Lineage auxiliary tab (card LIN-04B slice, ADR-027 §3.3): an
  * [[AuxTabProvider]]-compatible tab wired into the ONE `:aux` seam of LIN-04A.
  *
  *   - The data seam is deliberately MINIMAL and dependency-free: the provider
  *     takes two PURE readers — `connected` (is the lineage data source
  *     attached?) and `projection` (the ready text projection of the current
  *     graph). No IO here and no ui→cli/runtime/dsl dependency: the future full
  *     LIN-04 wiring (the cli adapter over the dsl renderer, the live graph
  *     from the session, the PTY acceptance) supplies its own closures at the
  *     composition root. NOTE (LIN-04B): the full live-graph acceptance is a
  *     separate card after LIN-03 full — this slice is the pure provider
  *     contract only.
  *   - `available` re-asks `connected` on every `:aux` (honest, never cached);
  *     while it is false the shell renders the typed unavailable hint and
  *     `projection` is never consulted (the LIN-04A discipline).
  *   - `render` re-asks `projection` on every switch; a whitespace-only
  *     projection renders the honest empty-state line — never a blank screen.
  *   - Both readers MUST be pure and total (no IO, no blocking, no throw) — the
  *     same snapshot-reader discipline as [[ShellViews]] (RAI-088). The
  *     provider performs no IO itself, holds no locks, and there is exactly ONE
  *     terminal writer ([[ShellTerminal.printAbove]] through
  *     [[ShellFeedback.render]]).
  */
object LineageTab:

  /** The tab title (fixed by the card: the ONE Lineage tab). */
  final val title: String = "Lineage"

  /** The honest empty state: the source is connected but has nothing to project
    * yet — a typed content line, never a blank screen.
    */
  final val emptyProjection: String =
    "no lineage yet: the connected source has no projection"

  /** Build the provider over the pure `connected`/`projection` readers. */
  def provider(
      connected: () => Boolean,
      projection: () => String
  ): AuxTabProvider =
    new AuxTabProvider:
      def title: String = LineageTab.title

      def available: Boolean = connected()

      def render: String =
        val text = projection()
        if text.trim.isEmpty then emptyProjection else text
