package raider.repl

import raider.runtime.events.EventHub
import raider.ui.{
  AuxTabProvider,
  CloseChoiceResolver,
  SessionRegistry,
  ShellTerminal,
  TabShell
}
import zio.{Scope, UIO, ZIO}

/** Product host of the accepted raider.ui TabShell inside the product REPL
  * (card RAI-102). Wiring home decided by the ratified contract-change
  * (coordinator commit 71278c1, 2026-10-12): the interactive product REPL is
  * `raider.repl` (JLine already first-class here); the headless raiderCli /
  * chat loop stays JLine-free (quality-gates §12 package smoke unchanged).
  *
  *   - The shell OWNS the terminal (RAI-061: the ONE writer is
  *     `ShellTerminal.printAbove`) — no other component of this host ever
  *     writes to the terminal, so the editable prompt survives background
  *     renders (spike RAI-056).
  *   - Sessions live on the ADR-024 event seam: tabs bind to jobs through
  *     [[EventHub]] + [[SessionRegistry]] (RAI-057); publishers push envelopes
  *     into the hub, never into the terminal; the registry never cancels and
  *     never publishes (ADR-024 §2.5).
  *   - The aux seam (LIN-04A) is wired WITHOUT a LineageTab provider:
  *     `TabShell.run` defaults (`views = ShellViews.detached`, `aux = None`),
  *     so `:aux` renders its typed hint until a provider is attached by a later
  *     contract step.
  *
  * This object is wiring only — no loop, no rendering, no UI mechanics beyond
  * the accepted RAI-061/062 shell (card non-goals). The PTY-driven acceptance
  * (TabShellHostPtySpec, P2W-01..03) drives exactly this wiring in a real
  * pseudo-terminal.
  */
object TabShellHost:

  /** Everything the shell and the job side share on the ADR-024 seam. */
  final case class Wiring(hub: EventHub, registry: SessionRegistry)

  /** Build the seam: one hub (two bounded buffers, ADR-024 §2.3), one registry
    * folding its events into the tab read model (RAI-057).
    */
  def wiring: UIO[Wiring] =
    for
      hub <- EventHub.make()
      registry <- SessionRegistry.make(hub)
    yield Wiring(hub, registry)

  /** RAI-106 hook: the product terminal is the palette-enabled ONE writer
    * ([[PaletteShellTerminal.jline]] — TAB completion over the registered
    * command catalog + the nearest-command line on unknown commands). Default
    * of [[run]]; the frozen ShellTerminal.jline stays available for any seam
    * that wants the pre-palette surface.
    */
  def paletteTerminal: ZIO[Scope, Throwable, ShellTerminal] =
    PaletteShellTerminal.jline

  /** Host the shell until `:quit`/EOF. Scoped: the terminal and every
    * background pump/hub subscription die with the scope. `:aux` stays
    * provider-less (typed hint, no LineageTab) — see the RAI-110 overload below
    * for the plan-tab wiring.
    */
  def run(wiring: Wiring): ZIO[Scope, Throwable, Unit] =
    for
      terminal <- paletteTerminal
      resolver = CloseChoiceResolver.fromTerminal(
        terminal,
        TabShell.Config().closePrompt
      )
      _ <- TabShell.run(wiring.hub, wiring.registry, terminal, resolver)
    yield ()

  /** RAI-110 hook (ADR-028 §3): host the shell with the ONE plan tab. ADDITIVE
    * overload, paletteTerminal pattern (RAI-106): the provider-less [[run]]
    * above is byte-identical in behavior. The plan panel supplies
    *   - the pure AuxTabProvider of the plan tab (`aux = Some(...)`), and
    *   - the plan-command terminal that intercepts `:approve`/`:reject` BEFORE
    *     the frozen router sees the line (Commands.planCommands).
    */
  def run(wiring: Wiring, plan: PlanPanel): ZIO[Scope, Throwable, Unit] =
    for
      terminal <- paletteTerminal
      resolver = CloseChoiceResolver.fromTerminal(
        terminal,
        TabShell.Config().closePrompt
      )
      _ <- TabShell.run(
        wiring.hub,
        wiring.registry,
        plan.commandTerminal(terminal),
        resolver,
        aux = Some(plan.provider)
      )
    yield ()

  /** RAI-113 hook: host the shell with the scrollback command interceptor — the
    * interactive `:scrollback [tabId]` render (product
    * `TabShellScrollback.screen`/`render` above the prompt, card RAI-113).
    * ADDITIVE overload, plan-command pattern (RAI-110): the provider-less
    * [[run]] and the plan overload are byte-identical in behavior; this one
    * decorates the palette terminal with [[ScrollbackCommands.commandTerminal]]
    * so the frozen router never sees a scrollback line. The interceptor holds
    * no gate — its dispatch is a bounded registry read plus the ONE-writer
    * `printAbove`, run INLINE so the render lands before the next prompt
    * redraw.
    */
  def run(
      wiring: Wiring,
      scrollback: ScrollbackCommands
  ): ZIO[Scope, Throwable, Unit] =
    for
      terminal <- paletteTerminal
      resolver = CloseChoiceResolver.fromTerminal(
        terminal,
        TabShell.Config().closePrompt
      )
      _ <- TabShell.run(
        wiring.hub,
        wiring.registry,
        scrollback.commandTerminal(terminal),
        resolver
      )
    yield ()

  /** Product lineage wiring. The provider is refreshed by its scoped adapter;
    * TabShell only reads its atomic snapshot on `:aux` and never does IO.
    */
  def run(
      wiring: Wiring,
      scrollback: ScrollbackCommands,
      lineage: AuxTabProvider
  ): ZIO[Scope, Throwable, Unit] =
    for
      terminal <- paletteTerminal
      resolver = CloseChoiceResolver.fromTerminal(
        terminal,
        TabShell.Config().closePrompt
      )
      _ <- TabShell.run(
        wiring.hub,
        wiring.registry,
        scrollback.commandTerminal(terminal),
        resolver,
        aux = Some(lineage)
      )
    yield ()

end TabShellHost
