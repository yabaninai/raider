<!-- Translated from Russian original. Key terms preserved as-is. -->

# Companion cards в репозитории Yabanin

Required profiles применяются по [activation stage](../quality-gates.md); component cases и final milestone obligations различаются. Изолированные lanes допускаются по [parallel runbook](../parallel-development.md).


Статус: все карточки todo; acceptance IDs и commands — требования, не результаты. Metadata: [backlog.json](backlog.json); общий DoD: [индекс](README.md). Номера clauses в Context — навигация; полный applicable invariant обязателен.

## YB-RAI-01: yb harness connect raider

Status: todo. Milestone: Yabanin. Dependencies: RAI-037, RAI-038.

**Сценарий и контракт.** Managed Raider config adapter/registry/connect/disconnect/doctor. Preserve permissions/user settings/backups, references на keys, drift и version schema. Wrapper-first CI already works без этого PR.

**Context.** yabanin-integration.md; целевой Yabanin AGENTS.md и applicable Go skills; quality runner его репозитория

**Allowed paths.** `pkg/harness/adapters/**`, `pkg/trcli/harness*`, `docs/harness.md`. Tests находятся внутри тех же modules/contract areas. До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: YB-01: connect twice идемпотентно; disconnect restores config, doctor verifies deployed capabilities.
- Error/adversarial: YB-02: config drift/schema conflict/helper returns parent in required mode fail without overwrite.
- Cancellation/concurrency: YB-03: atomic config transaction interrupted mid-write retains recoverable backup and no key literals.

**Required gates.** В Yabanin: его AGENTS.md/Go skills; make quality-changed, make quality-changed QUALITY_EXECUTE=1, make quality-full и relevant integration. Cross-product manifest включает оба fingerprints; gates Raider не заменяют gates Yabanin.

**Non-goals.** Менять Raider runtime, platform scheduler, silent auth fallback.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation. Experimental/optional adapter выключается explicit capability flag.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.

## YB-RAI-02: Real Raider executor вместо FakeExecutor

Status: todo. Milestone: Yabanin. Dependencies: RAI-022, RAI-024, RAI-039, YB-RAI-01.

**Сценарий и контракт.** Прежде design протокола mapping Existing Job/Task IDs/events/run/result/cancel/uncertainty; then owned raider-run process/bundle executor. One lifecycle owner, actual source CLI FakeExecutor gap acknowledged.

**Context.** yabanin-integration.md; целевой Yabanin AGENTS.md и applicable Go skills; quality runner его репозитория

**Allowed paths.** `pkg/delegation/controller/**`, `pkg/trcli/delegate*`, `docs/delegation*`. Tests находятся внутри тех же modules/contract areas. До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: YB-04: persisted delegation job maps real Raider result/exit/events and attempts accounting.
- Error/adversarial: YB-05: missing binary/bundle mismatch/protocol crash cannot simulated success.
- Cancellation/concurrency: YB-06: controller cancel/retry/process exit race; no competing root owners or automatic mutation replay.

**Required gates.** В Yabanin: его AGENTS.md/Go skills; make quality-changed, make quality-changed QUALITY_EXECUTE=1, make quality-full и relevant integration. Cross-product manifest включает оба fingerprints; gates Raider не заменяют gates Yabanin.

**Non-goals.** Rewrite Go controller/MCP orchestrator, assume existing CLI already live executor.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation. Experimental/optional adapter выключается explicit capability flag.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.

## YB-RAI-03: CI/root analytics drilldown

Status: todo. Milestone: Yabanin. Dependencies: RAI-040, RAI-042, YB-RAI-01.

**Сценарий и контракт.** Existing explorer/CI pipeline totals correlate root/job/workflow/attempt provenance and Raider artifacts with permitted filters. High cardinal IDs not automatic rollup dimensions; missing telemetry visibly pending.

**Context.** yabanin-integration.md; целевой Yabanin AGENTS.md и applicable Go skills; quality runner его репозитория

**Allowed paths.** `services/admind/**`, `docs/analytics*`. Tests находятся внутри тех же modules/contract areas. До назначения уточнить concrete files; расширение path boundary требует отдельной scope card.

**Acceptance.**

- Normal: YB-07: one pipeline/root matches accounting totals and actual model breakdown, safely escaped links.
- Error/adversarial: YB-08: no permissions/missing usage/broken lineage/unknown price retain incomplete states.
- Cancellation/concurrency: YB-09: logger lag/late reconciliation/no double-count retry and subtree totals.

**Required gates.** В Yabanin: его AGENTS.md/Go skills; make quality-changed, make quality-changed QUALITY_EXECUTE=1, make quality-full и relevant integration. Cross-product manifest включает оба fingerprints; gates Raider не заменяют gates Yabanin.

**Non-goals.** New DB schema/migration without own design card, trust client labels as auth, inflated aggregates.

**Compatibility/rollback.** Frozen schemas/API и downstream fixtures не менять молча; versioned migration/ADR отдельно. Откат feature не replay side effects; persistent incomplete/uncertain artifacts сохраняются для reconciliation. Experimental/optional adapter выключается explicit capability flag.

**Deliverables.** Минимальный diff в allowed paths, перечисленные executable fixtures с actual IDs, обновлённый relevant contract/example, actual work record по template и текущий evidence/log hashes. Реальные платные вызовы/публикация не implied. Если задача слишком велика, перед coding разбить на независимые дочерние карточки; родитель принимает их совместный contract gate.
