package audithook

import raider.core.plugin.{
  CapabilityRef,
  PluginDescriptor,
  PluginVersion,
  RaiderHook,
  RaiderPlugin
}

/** RAI-096 SDK fixture: a run-start AUDIT HOOK on the narrow v1 `RaiderHook`
  * SPI (RAI-084).
  *
  * `RaiderHook.apply` is one synchronous JSON-string -> JSON-string call; the
  * per-call time bound is the host's `HookRunner` business, not the trait's.
  * This hook marks a run start and embeds the read-only input verbatim — the
  * runner never parses either side, so the hook must not mutate `inputJson`
  * either. Hooks declare their registry name (`audit.runStart`, its own
  * namespace — ADR-025 §2.1) and are dispatched under exactly that name.
  *
  * Discovery (v1 honesty): the loader validates the hook CAPABILITY but does
  * not instantiate hook instances yet; the jar still ships the standard
  * `META-INF/services/raider.core.plugin.RaiderHook` entry so a host walking
  * the loaded plugin's classloader (ServiceLoader) finds this provider and
  * hands it to `HookRunner.runHook` under the declared name.
  */
class AuditRunStartHook extends RaiderHook:

  def name: String = "audit.runStart"

  def apply(inputJson: String): String =
    "{\"hook\":\"audit.runStart\",\"event\":\"runStart\",\"input\":" +
      inputJson + "}"

class AuditHookPlugin extends RaiderPlugin:

  def descriptor: PluginDescriptor = PluginDescriptor(
    name = "audit-hook",
    version = PluginVersion(1, 0, 0),
    minApiVersion = 1,
    maxApiVersion = 1,
    capabilities = List(CapabilityRef("hook", "audit.runStart"))
  )
