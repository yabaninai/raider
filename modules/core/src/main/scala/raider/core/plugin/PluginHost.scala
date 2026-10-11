package raider.core.plugin

import raider.core.plugin.loader.{HookDiscovery, LoadedPlugin}

/** RAI-095 (ADR-025 §2.5): the lifecycle-hook host over already-loaded plugins.
  *
  * A host owns a fixed `List[LoadedPlugin]` (P4.B loader output) and one
  * [[HookRunner]] (RAI-084). Hook instances are discovered ONCE, at
  * construction, by the loader-side scan ([[raider.core.plugin.loader.
  * HookDiscovery]]): the standard ServiceLoader over each plugin's own
  * classloader — the same parent-delegate identity rule the loader slice uses,
  * so a host never opens its own classloader — keeping only instances whose
  * declared `RaiderHook.name` is also DECLARED as a `hook` capability of that
  * plugin's manifest. An undeclared provider is never dispatched (the loader's
  * declared==provided discipline, applied to hooks); a provider record that
  * cannot even be instantiated is skipped: hook wiring must never break the
  * host. This class itself stays effect-free (PLUG-03): the scan is the loader
  * package's business.
  *
  * `runStart` / `runFinish` dispatch exactly the hooks NAMED
  * [[PluginHost.StartHook]] / [[PluginHost.FinishHook]], in plugin order,
  * through [[HookRunner.runHook]] with the read-only `metaJson` handed over
  * verbatim (never parsed or mutated). The seam is a FLAT function: the
  * returned `List[HookOutcome]` IS the log — a hook that throws, hangs past its
  * bound or is misdeclared becomes a typed outcome, never an exception, and
  * consumers only ever LOG the outcomes. Hooks NEVER influence the caller's
  * result (card non-goal): this class fails only on construction misuse
  * (non-positive runner bound, like [[HookRunner]] itself), never per hook.
  *
  * Honesty boundaries (ADR-025 §1): hook code runs with full process authority;
  * the runner's bound is an availability guard, NOT containment, and the
  * discovery scan grants no isolation beyond parent-delegate identity.
  */
final class PluginHost(plugins: List[LoadedPlugin], runner: HookRunner):

  /** Discovered hook instances in dispatch order: plugin list order, then each
    * plugin's own provider order. Fixed at construction.
    */
  private val resolved: List[RaiderHook] =
    plugins.flatMap(HookDiscovery.declaredHooks)

  /** Distinct names of the hooks this host would dispatch (diagnostics aid;
    * empty when no plugin declares and provides a lifecycle hook).
    */
  def hookNames: List[String] = resolved.map(_.name).distinct

  /** Run every hook named `runStart` with `metaJson`; outcomes in dispatch
    * order. Never throws from hook code ([[HookRunner]] guarantee).
    */
  def runStart(metaJson: String): List[HookOutcome] =
    dispatch(PluginHost.StartHook, metaJson)

  /** Run every hook named `runFinish` with `metaJson`; outcomes in dispatch
    * order. Never throws from hook code ([[HookRunner]] guarantee).
    */
  def runFinish(metaJson: String): List[HookOutcome] =
    dispatch(PluginHost.FinishHook, metaJson)

  private def dispatch(
      hookName: String,
      metaJson: String
  ): List[HookOutcome] =
    resolved.collect {
      case hook if hook.name == hookName =>
        runner.runHook(hook, hookName, metaJson)
    }

end PluginHost

object PluginHost:

  /** The one lifecycle hook name `runStart` dispatches under. */
  val StartHook: String = "runStart"

  /** The one lifecycle hook name `runFinish` dispatches under. */
  val FinishHook: String = "runFinish"

  /** A host over no plugins: dispatches nothing. (Loop callers pass `None`
    * instead — that path is a literal no-op, zero overhead.)
    */
  def empty: PluginHost = PluginHost(Nil, HookRunner())

end PluginHost
