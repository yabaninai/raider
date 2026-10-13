package raider.cli.run

import raider.core.{ModelBackend, RaiderError, RootId, ToolRegistry}
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.delegate.{DelegationEvents, DelegationToolset}
import raider.runtime.events.EventHub
import raider.runtime.jobs.JobManager

/** RAI-154 (WIR-01): the headless CODER agent's delegation wiring — the product
  * entry that closes the RAI-144 limitation ("the export path is fully wired in
  * the runner and exercised end-to-end by the scripted fixture; a future wiring
  * card flips it on for real headless runs with zero exporter changes").
  *
  * The policy surface is the EXISTING runtime-contracts §11 / LOOP-01
  * delegation toolset, unchanged:
  *   - the four model tools `delegate` / `await_agent` / `cancel_agent` /
  *     `list_agents` ([[DelegationToolset]]) over the SAME [[JobManager]], root
  *     admission and root budget as the campaign loop (one owner, one root
  *     budget; children's attempts land on the delegating root);
  *   - policy gates, all typed: the agent-name whitelist (unknown names get
  *     structured `unknown_agent` feedback), `maxChildren` (structured
  *     `max_children` feedback), and the construction validations
  *     ([[RaiderError.Configuration]] — `maxConcurrentTools >= 2` so an
  *     awaiting parent cannot starve a tool-using child, no base-registry name
  *     clashes, positive mailbox capacity). No new policy layer is invented
  *     here.
  *
  * Allowed child agents: every built-in agent name ([[CliArgs.BuiltInAgents]]).
  * Recursion is structurally impossible, not an accident: a forked child runs
  * the BASE registry only (§11: "children CANNOT re-delegate — the child
  * registry is the base registry only"), so a child named `coder` carries the
  * coding tools without delegation tools. Widening or narrowing the set is a
  * policy change with its own card.
  *
  * Event seam: [[DelegationEvents]] publishes the frozen
  * `Delegated(childJobId, agent)` fork record under the CHILD's job id on the
  * RUN's hub, so the [[MultiJobExport]] capture (composed before the status
  * ledger in the manager's single `onTransition` hook) joins every child
  * segment into the run's single events.jsonl — MJE-01 semantics through the
  * product entry. Emission never alters the tool feedback JSON.
  *
  * All errors stay typed: this is a pure `Either[RaiderError, _]` composition —
  * a refused wiring is a typed preflight-style failure, never a partial
  * registry.
  */
object CoderDelegationWiring:

  /** The delegation-capable child agents of a headless coder run (see the class
    * doc for the recursion note).
    */
  val AllowedAgents: Set[String] = CliArgs.BuiltInAgents

  /** The coder loop's registry: the base coding registry augmented with the §11
    * delegation tools. `base` must not define delegation tool names (the
    * toolset construction refuses a clash typed).
    */
  def augment(
      manager: JobManager,
      hub: EventHub,
      backend: ModelBackend,
      admission: Admission,
      limits: BudgetLimits,
      root: RootId,
      base: ToolRegistry
  ): Either[RaiderError, ToolRegistry] =
    DelegationToolset
      .make(
        manager,
        backend,
        admission,
        limits,
        base,
        AllowedAgents,
        root,
        delegationEvents = Some(DelegationEvents(hub, root))
      )
      .flatMap(_.augment(base))

end CoderDelegationWiring
