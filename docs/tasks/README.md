<!-- Translated from Russian original. Key terms preserved as-is. -->

# Карточки реализации Raider

Актуальное уточнение 2026-10-03: [working target](../working-product.md), [parallel runbook](../parallel-development.md), [stage-aware gates](../quality-gates.md). Старые IDs сохранены; milestone приёмка и полезный coding-agent результат различаются.


53 Raider cards и 3 optional Yabanin companion cards. Все имеют статус todo; это проверяемая программа разработки, а не выполненный board.

| Scope | Документ |
| --- | --- |
| M0 001–026 | [Foundation/runtime/protocols/REPL/CI](m0.md) |
| M1 027–040 | [Extensibility и optional integrations](m1.md) |
| M2 041–047 / P2 048–053 | [Release/P2](m2-p2.md) |
| YB-RAI-01–03 | [Изменения в Yabanin](yabanin.md) |
| Машинные metadata/dependencies | [backlog.json](backlog.json) |

## Как назначать работу

1. Выбрать карточку с accepted prerequisites. Старт: RAI-001 → RAI-003 → RAI-002 → RAI-004; потом config/testkit/runtime.
2. Прочитать AGENTS.md, [основной план](../PLAN.md), applicable contracts и точную карточку.
3. Уточнить allowed directories до конкретных files/symbols. Размер coding packet: один observable behavior, обычно 1–3 production files.
4. Большую milestone card разбить перед исполнением на `.a/.b/.c`: accepted signatures, свои tests/paths/dependencies/records. Все acceptance родителя сохраняются; новая архитектура не прячется внутри child task.
5. Сначала behavioral fixture, затем implementation; [review workflow](../model-workflow.md) и [quality gates](../quality-gates.md) обязательны.
6. Создать [work record](../templates/work-record.md) после реальной работы и до final fingerprint gate. Ссылки actual evidence в ignored artifacts; todo не помечать accepted из summary модели.

## Общий DoD

Accepted dependencies; normal/error/cancel fixtures; required profiles исполнены, актуальны и проходят; независимое свежее review; актуальные docs/API fixtures; no new unresolved blocker; корректные limitations. Все profiles всегда включают runner self при применимости. Missing tool/stand/scanner/hosted runner evidence — unavailable/pending, никогда pass.

Для M0 RAI-026 — joint acceptance. Для M2 RAI-047 — release candidate, без implied publishing. Optional Yabanin/bridges не блокируют standalone release, но рекламируемая capability требует своего полного evidence. P2 не включается автоматически в v1.

## Индекс зависимостей

Markdown содержит full contract, JSON канонический для ID/title/dependency/profile metadata; docs gate проверяет их согласованность. Текущий progress хранить отдельным board/work records, не терять исходные acceptance. Дочерние cards добавлять в backlog metadata при назначении, фиксируя parent linkage.

- **RAI-001** [Feasibility headless и настоящего Scala REPL](m0.md); dependencies: нет; profiles: bootstrap.
- **RAI-002** [Frozen публичные API и versioned схемы](m0.md); dependencies: RAI-001, RAI-003; profiles: static, contracts, docs.
- **RAI-003** [Pinned build и разделение distributions](m0.md); dependencies: RAI-001; profiles: bootstrap, static, contracts.
- **RAI-004** [Quality runner, classifier и evidence](m0.md); dependencies: RAI-002, RAI-003; profiles: self, static, contracts, security.
- **RAI-005** [Profiles, policies и независимый provider config](m0.md); dependencies: RAI-002, RAI-004; profiles: static, unit, contracts, security.
- **RAI-006** [Deterministic testkit без LLM](m0.md); dependencies: RAI-002, RAI-004; profiles: static, unit, contracts.
- **RAI-007** [Job registry и atomic lifecycle](m0.md); dependencies: RAI-002, RAI-006; profiles: static, unit, contracts, runtime.
- **RAI-008** [Structured scopes и отмена subtree](m0.md); dependencies: RAI-007; profiles: static, unit, contracts, runtime.
- **RAI-009** [Root budget и admission без deadlocks](m0.md); dependencies: RAI-005, RAI-008; profiles: static, unit, contracts, runtime, performance.
- **RAI-010** [Native agent loop](m0.md); dependencies: RAI-005, RAI-006, RAI-009; profiles: static, unit, contracts, runtime, eval.
- **RAI-011** [OpenAI-compatible HTTP Chat](m0.md); dependencies: RAI-005, RAI-006, RAI-010; profiles: static, unit, contracts, transport, security.
- **RAI-012** [OpenAI SSE streaming state machine](m0.md); dependencies: RAI-011; profiles: static, unit, contracts, transport, runtime.
- **RAI-013** [Anthropic-compatible Messages и streaming](m0.md); dependencies: RAI-010, RAI-012; profiles: static, unit, contracts, transport, runtime, security.
- **RAI-014** [Чтение и поиск с workspace containment](m0.md); dependencies: RAI-005, RAI-008; profiles: static, unit, tools, security.
- **RAI-015** [Безопасная мутация и patch artifacts](m0.md); dependencies: RAI-014; profiles: static, unit, tools, runtime, security.
- **RAI-016** [Owned process tools и isolated execution](m0.md); dependencies: RAI-005, RAI-008, RAI-014; profiles: static, unit, tools, runtime, security, ci.
- **RAI-017** [Единая typed composition](m0.md); dependencies: RAI-002, RAI-008, RAI-009; profiles: static, unit, contracts, runtime, docs.
- **RAI-018** [Ergonomic REPL launch facade](m0.md); dependencies: RAI-010, RAI-017; profiles: static, unit, contracts, runtime, repl, docs.
- **RAI-019** [Pinned compiler/JLine REPL adapter](m0.md); dependencies: RAI-001, RAI-018; profiles: static, unit, contracts, repl, security, performance.
- **RAI-020** [Prelude, registry commands и definition load](m0.md); dependencies: RAI-005, RAI-019; profiles: static, unit, contracts, repl, docs, security.
- **RAI-021** [Steering mailbox и checked model delegation](m0.md); dependencies: RAI-010, RAI-018, RAI-020; profiles: static, unit, contracts, runtime, eval.
- **RAI-022** [Compiled bundles и headless Runner](m0.md); dependencies: RAI-002, RAI-005, RAI-010, RAI-017; profiles: static, unit, contracts, ci, runtime, docs, security.
- **RAI-023** [CI context и trust preflight](m0.md); dependencies: RAI-005, RAI-022; profiles: static, unit, contracts, ci, security.
- **RAI-024** [Trusted checks, artifacts и exit contracts](m0.md); dependencies: RAI-002, RAI-016, RAI-022, RAI-023; profiles: static, unit, contracts, ci, tools, security, eval.
- **RAI-025** [GitHub/GitLab/generic wrappers](m0.md); dependencies: RAI-023, RAI-024; profiles: static, unit, contracts, ci, docs, security.
- **RAI-026** [Совместная MVP-приёмка](m0.md); dependencies: RAI-012, RAI-013, RAI-014, RAI-015, RAI-016, RAI-017, RAI-018, RAI-019, RAI-020, RAI-021, RAI-022, RAI-023, RAI-024, RAI-025; profiles: full, ci, repl, transport, eval.
- **RAI-027** [Journal и архивирование без replay](m1.md); dependencies: RAI-026; profiles: static, unit, contracts, runtime, ci, security.
- **RAI-028** [Typed agents, tools и bounded output repair](m1.md); dependencies: RAI-002, RAI-010, RAI-027; profiles: static, unit, contracts, runtime, eval, docs.
- **RAI-029** [Definition loaders и context policy](m1.md); dependencies: RAI-020, RAI-027; profiles: static, unit, contracts, docs, security.
- **RAI-030** [Context limits и compaction](m1.md); dependencies: RAI-027, RAI-028, RAI-029; profiles: static, unit, contracts, runtime, eval, security.
- **RAI-031** [Versioned JVM plugin SPI](m1.md); dependencies: RAI-028, RAI-029; profiles: static, unit, contracts, runtime, security, compatibility.
- **RAI-032** [Pi profile importer с provenance](m1.md); dependencies: RAI-029, RAI-031; profiles: static, unit, contracts, docs, compatibility, security, eval.
- **RAI-033** [OpenCode profile importer](m1.md); dependencies: RAI-029, RAI-031; profiles: static, unit, contracts, docs, compatibility, security, eval.
- **RAI-034** [Pi RPC external backend](m1.md); dependencies: RAI-027, RAI-031, RAI-032; profiles: static, unit, contracts, runtime, tools, transport, compatibility, security.
- **RAI-035** [OpenCode HTTP external backend](m1.md); dependencies: RAI-027, RAI-031, RAI-033; profiles: static, unit, contracts, runtime, transport, compatibility, security.
- **RAI-036** [Conversation sessions с serialized turns](m1.md); dependencies: RAI-027, RAI-030; profiles: static, unit, contracts, runtime, repl, security.
- **RAI-037** [Optional Yabanin profile, labels и wrapper env](m1.md); dependencies: RAI-011, RAI-012, RAI-023, RAI-027; profiles: static, unit, contracts, transport, ci, security.
- **RAI-038** [Yabanin root session keys и finish](m1.md); dependencies: RAI-037, RAI-009; profiles: static, unit, contracts, transport, runtime, ci, security.
- **RAI-039** [Pinned Yabanin stand acceptance](m1.md); dependencies: RAI-037, RAI-038; profiles: integration, ci, transport, security.
- **RAI-040** [Agent-eval contract export](m1.md); dependencies: RAI-027, RAI-028, RAI-024; profiles: static, unit, contracts, eval, security.
- **RAI-041** [Protected task eval и adversarial corpus](m2-p2.md); dependencies: RAI-024, RAI-028, RAI-040; profiles: eval, ci, contracts, security.
- **RAI-042** [Accounting reconciliation без ложных денег](m2-p2.md); dependencies: RAI-038, RAI-039, RAI-040; profiles: static, unit, contracts, runtime, transport, integration, ci.
- **RAI-043** [Soak и calibrated performance](m2-p2.md); dependencies: RAI-026, RAI-027, RAI-030; profiles: performance, runtime, ci, repl.
- **RAI-044** [JVM/OCI distribution и supply-chain inventory](m2-p2.md); dependencies: RAI-022, RAI-026, RAI-031; profiles: release, ci, security, docs.
- **RAI-045** [CI разработки самого Raider](m2-p2.md); dependencies: RAI-004, RAI-026, RAI-044; profiles: self, full, security, ci.
- **RAI-046** [Executable examples и документация](m2-p2.md); dependencies: RAI-026, RAI-028, RAI-032, RAI-033, RAI-044; profiles: docs, contracts, ci, repl, compatibility.
- **RAI-047** [Release candidate acceptance](m2-p2.md); dependencies: RAI-041, RAI-043, RAI-044, RAI-045, RAI-046; profiles: release, full, ci, security.
- **RAI-048** [Daemon attach и explicit job ownership](m2-p2.md); dependencies: RAI-047; profiles: static, unit, contracts, runtime, security, ci.
- **RAI-049** [Recovery только registered serializable plans](m2-p2.md); dependencies: RAI-027, RAI-047; profiles: static, unit, contracts, runtime, eval, security.
- **RAI-050** [Shared budget authority между CI jobs](m2-p2.md); dependencies: RAI-009, RAI-042, RAI-047; profiles: static, unit, contracts, runtime, integration, security.
- **RAI-051** [Capped live сравнение моделей](m2-p2.md); dependencies: RAI-040, RAI-041, RAI-047; profiles: eval, integration, security.
- **RAI-052** [raceSuccess/quorum coordination](m2-p2.md); dependencies: RAI-017, RAI-043, RAI-047; profiles: static, unit, contracts, runtime, eval, performance.
- **RAI-053** [MCP tool adapter](m2-p2.md); dependencies: RAI-031, RAI-047; profiles: static, unit, contracts, transport, tools, security, compatibility.
- **YB-RAI-01** [yb harness connect raider](yabanin.md); dependencies: RAI-037, RAI-038; profiles: yabanin-quality-changed, yabanin-quality-full, integration.
- **YB-RAI-02** [Real Raider executor вместо FakeExecutor](yabanin.md); dependencies: RAI-022, RAI-024, RAI-039, YB-RAI-01; profiles: yabanin-quality-changed, yabanin-quality-full, integration.
- **YB-RAI-03** [CI/root analytics drilldown](yabanin.md); dependencies: RAI-040, RAI-042, YB-RAI-01; profiles: yabanin-quality-changed, yabanin-quality-full, integration.
