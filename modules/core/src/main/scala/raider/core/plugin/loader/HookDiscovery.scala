package raider.core.plugin.loader

import raider.core.plugin.{CapabilityRef, RaiderHook}

import java.util.{ServiceConfigurationError, ServiceLoader}

/** RAI-095 hook discovery (ADR-025 §2.4/§2.5): the loader-side scan for a
  * loaded plugin's DECLARED hook instances.
  *
  * Discovery is the loader package's business (this is where the classloader
  * machinery lives — the P4.A model/preflight package stays effect-free by
  * design, PLUG-03): the standard `ServiceLoader` over the plugin's OWN
  * classloader — the same parent-delegate identity rule the loader slice uses,
  * no new classloader is opened. Only instances whose declared
  * `RaiderHook.name` is also DECLARED as a `hook` capability of that plugin's
  * manifest are returned: an undeclared provider is never surfaced for dispatch
  * (the loader's declared==provided discipline, applied to hooks).
  *
  * Total by construction: a provider record that cannot even be instantiated
  * (`ServiceConfigurationError`) ends the scan for that plugin — a typed
  * refusal has no hook name to carry here, so skipping the broken record (and
  * only it) is the honest move; the jar already passed load preflight, and hook
  * wiring must never break the host.
  */
object HookDiscovery:

  /** The plugin's declared hook instances, in provider order. Empty when the
    * plugin declares no hook capability or provides no matching instance.
    */
  def declaredHooks(p: LoadedPlugin): List[RaiderHook] =
    val declared =
      p.manifest.capabilities.collect { case CapabilityRef("hook", n) =>
        n
      }.toSet
    val it = ServiceLoader.load(classOf[RaiderHook], p.classLoader).iterator
    val found = List.newBuilder[RaiderHook]
    var open = true
    while open do
      try
        if it.hasNext then
          val h = it.next
          if declared.contains(h.name) then found += h
        else open = false
      catch
        case _: ServiceConfigurationError =>
          open = false // skip the broken provider record, keep the host whole
    found.result()

end HookDiscovery
