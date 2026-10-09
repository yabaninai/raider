package raider.runtime.state

import raider.core.{JobId, RootId}

/** State scope (m1.md RAI-076, roadmap-12k P5.A).
  *
  *   - [[Agent]] — private scratch space of one job's agent;
  *   - [[Root]] — blackboard shared across one run's jobs/children;
  *   - [[Global]] — cross-run preferences.
  *
  * Explicit-Global-only rule: nothing in the state API resolves a default scope
  * — reaching [[Global]] is always an explicit value chosen by the caller and
  * visible in its events/args.
  */
enum Scope:
  case Agent(jobId: JobId)
  case Root(rootId: RootId)
  case Global

  /** Human-readable identity for error messages. */
  def describe: String = this match
    case Scope.Agent(j) => s"agent(${j.value})"
    case Scope.Root(r)  => s"root(${r.value})"
    case Scope.Global   => "global"

end Scope
