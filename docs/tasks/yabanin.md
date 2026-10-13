<!-- Translated from the Russian original. Key terms preserved as-is. -->

# Companion cards in the Yabanin repository

Required profiles follow the [activation stage](../quality-gates.md); component cases and final milestone obligations are distinct. Isolated lanes are permitted under the [parallel runbook](../parallel-development.md).

Status: all cards are todo; acceptance IDs and commands are requirements, not results. Metadata: [backlog.json](backlog.json); shared DoD: [index](README.md). Clause numbers in Context are navigation aids; the complete applicable invariant is mandatory.

## YB-RAI-01: Connect Raider to the yb harness

Status: todo. Milestone: Yabanin. Dependencies: RAI-037, RAI-038.

**Scenario and contract.** Managed Raider config adapter/registry/connect/disconnect/doctor. Preserve permissions, user settings, and backups; use references to keys; handle drift and version schema. Wrapper-first CI already works without this PR.

**Context.** yabanin-integration.md; target Yabanin AGENTS.md and applicable Go skills; its repository's quality runner

**Allowed paths.** `pkg/harness/adapters/**`, `pkg/trcli/harness*`, `docs/harness.md`. Tests belong within the same modules/contract areas. Clarify concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: YB-01: connect twice is idempotent; disconnect restores config; doctor verifies deployed capabilities.
- Error/adversarial: YB-02: config drift/schema conflict/helper returns parent in required mode fail without overwrite.
- Cancellation/concurrency: YB-03: atomic config transaction interrupted mid-write retains recoverable backup and no key literals.

**Required gates.** In Yabanin: its AGENTS.md/Go skills; make quality-changed, make quality-changed QUALITY_EXECUTE=1, make quality-full, and relevant integration. The cross-product manifest includes both fingerprints; Raider gates do not replace Yabanin gates.

**Non-goals.** Change Raider runtime, platform scheduler, or silent auth fallback.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; use a versioned migration/ADR separately. Feature rollback does not replay side effects; persistent incomplete/uncertain artifacts remain for reconciliation. An experimental/optional adapter is disabled by an explicit capability flag.

**Deliverables.** Minimal diff in allowed paths, listed executable fixtures with actual IDs, updated relevant contract/example, actual work record using the template, and current evidence/log hashes. Real paid calls/publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their joint contract gate.

## YB-RAI-02: Real Raider executor instead of FakeExecutor

Status: todo. Milestone: Yabanin. Dependencies: RAI-022, RAI-024, RAI-039, YB-RAI-01.

**Scenario and contract.** First design the protocol mapping Existing Job/Task IDs/events/run/result/cancel/uncertainty; then implement an owned raider-run process/bundle executor. One lifecycle owner; acknowledge the actual source CLI FakeExecutor gap.

**Context.** yabanin-integration.md; target Yabanin AGENTS.md and applicable Go skills; its repository's quality runner

**Allowed paths.** `pkg/delegation/controller/**`, `pkg/trcli/delegate*`, `docs/delegation*`. Tests belong within the same modules/contract areas. Clarify concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: YB-04: persisted delegation job maps real Raider result/exit/events and attempts accounting.
- Error/adversarial: YB-05: missing binary/bundle mismatch/protocol crash cannot simulated success.
- Cancellation/concurrency: YB-06: controller cancel/retry/process exit race; no competing root owners or automatic mutation replay.

**Required gates.** In Yabanin: its AGENTS.md/Go skills; make quality-changed, make quality-changed QUALITY_EXECUTE=1, make quality-full, and relevant integration. The cross-product manifest includes both fingerprints; Raider gates do not replace Yabanin gates.

**Non-goals.** Rewrite the Go controller/MCP orchestrator or assume the existing CLI is already a live executor.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; use a versioned migration/ADR separately. Feature rollback does not replay side effects; persistent incomplete/uncertain artifacts remain for reconciliation. An experimental/optional adapter is disabled by an explicit capability flag.

**Deliverables.** Minimal diff in allowed paths, listed executable fixtures with actual IDs, updated relevant contract/example, actual work record using the template, and current evidence/log hashes. Real paid calls/publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their joint contract gate.

## YB-RAI-03: CI/root analytics drilldown

Status: todo. Milestone: Yabanin. Dependencies: RAI-040, RAI-042, YB-RAI-01.

**Scenario and contract.** Existing explorer/CI pipeline totals correlate root/job/workflow/attempt provenance and Raider artifacts with permitted filters. High cardinal IDs not automatic rollup dimensions; missing telemetry visibly pending.

**Context.** yabanin-integration.md; target Yabanin AGENTS.md and applicable Go skills; its repository's quality runner

**Allowed paths.** `services/admind/**`, `docs/analytics*`. Tests belong within the same modules/contract areas. Clarify concrete files before assignment; expanding the path boundary requires a separate scope card.

**Acceptance.**

- Normal: YB-07: one pipeline/root matches accounting totals and actual model breakdown, safely escaped links.
- Error/adversarial: YB-08: no permissions/missing usage/broken lineage/unknown price retain incomplete states.
- Cancellation/concurrency: YB-09: logger lag/late reconciliation/no double-count retry and subtree totals.

**Required gates.** In Yabanin: its AGENTS.md/Go skills; make quality-changed, make quality-changed QUALITY_EXECUTE=1, make quality-full, and relevant integration. The cross-product manifest includes both fingerprints; Raider gates do not replace Yabanin gates.

**Non-goals.** New DB schema/migration without its own design card, trusting client labels as auth, or inflated aggregates.

**Compatibility/rollback.** Do not silently change frozen schemas/APIs or downstream fixtures; use a versioned migration/ADR separately. Feature rollback does not replay side effects; persistent incomplete/uncertain artifacts remain for reconciliation. An experimental/optional adapter is disabled by an explicit capability flag.

**Deliverables.** Minimal diff in allowed paths, listed executable fixtures with actual IDs, updated relevant contract/example, actual work record using the template, and current evidence/log hashes. Real paid calls/publication are not implied. If the task is too large, split it into independent child cards before coding; the parent accepts their joint contract gate.
