package raider.runtime.lineage.model

/** THE single static tool-name → [[RecoveryClass]] taxonomy of the lineage
  * layer (RAI-148, ADR-027 §6.2 point 2; frozen until the wave-2 schema card).
  *
  * Every consumer that needs a recovery verdict classifies through [[classify]]
  * here — the batch [[raider.runtime.lineage.reduce.LineageReducer]], the live
  * [[raider.runtime.lineage.live.LiveGraphBuilder]] and the CLI isolation guard
  * (`raider.cli.lineage.IsolatedRerun`). There is deliberately NO second copy
  * of these sets anywhere: the previous three private copies drifted silently
  * (the owner-flagged RAI-148 defect missed `fs_patch` and `proc_run`,
  * classifying real source-modifying repairs ReadOnly), which is exactly the
  * hazard two reviews flagged.
  *
  * Classes (ADR-027 §2/§6.2 discipline, unchanged):
  *   - '''ExecTools''' — running them executes code and can mutate the world:
  *     `bash`, `sh`, `process`, `python`, `proc_run`.
  *   - '''WriteTools''' — running them writes/patches/moves/removes files:
  *     `apply_patch`, `edit`, `fs_edit`, `fs_patch`, `fs_write`, `mkdir`, `mv`,
  *     `patch`, `rm`, `touch`, `write`.
  *   - everything else is [[RecoveryClass.ReadOnly]] — the ADR default, so an
  *     UNKNOWN tool name never gains Mutating power by accident (TAX-03).
  *
  * [[RecoveryClass.Idempotent]] is deliberately NEVER returned: a rate-limit
  * retry does not make a mutating tool safe to re-run; the value stays reserved
  * for the schema card and a config-driven extension (non-goal of RAI-148).
  *
  * Adding a name is a one-line change in exactly this file; adding a new
  * RecoveryClass case or making this table config-driven is a schema-card
  * decision, not a local edit.
  */
object RecoveryTaxonomy:

  /** Exec-class tool names (ADR-027 §6.2): their execution runs commands/code,
    * so live re-run isolation must treat them as Mutating.
    */
  val ExecTools: Set[String] =
    Set("bash", "process", "proc_run", "python", "sh")

  /** Write-class tool names (ADR-027 §6.2): they modify the workspace.
    */
  val WriteTools: Set[String] = Set(
    "apply_patch",
    "edit",
    "fs_edit",
    "fs_patch",
    "fs_write",
    "mkdir",
    "mv",
    "patch",
    "rm",
    "touch",
    "write"
  )

  /** The static verdict for `tool`: Mutating for the exec/write classes,
    * ReadOnly for everything else (unknown names included).
    */
  def classify(tool: String): RecoveryClass =
    if ExecTools.contains(tool) || WriteTools.contains(tool) then
      RecoveryClass.Mutating
    else RecoveryClass.ReadOnly

  /** The guard-shaped predicate: is a live re-run of `tool` a mutating one
    * ([[raider.cli.lineage.IsolatedRerun]] policy input).
    */
  def isMutating(tool: String): Boolean =
    classify(tool) == RecoveryClass.Mutating

end RecoveryTaxonomy
