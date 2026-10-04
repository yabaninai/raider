# Передача разработки z.ai

Для OpenCode: [готовый handoff](handoff-opencode.md) и project command `/raider-finish`. Z.ai может быть coding provider; runtime независим от vendor.

Ниже исторический single-card prompt. Для текущей цели параллельной разработки и завершения рабочего продукта использовать [finish prompt](prompts/opencode-finish.txt); он учитывает review, actual board и stage-aware gates.

## 1. Начальный prompt

```text
Ты разрабатываешь raider, Scala agent harness для CI pipelines с полноценным REPL для отладки.
Репозиторий: https://github.com/yabaninai/raider.git.

Обязательный контекст: AGENTS.md, docs/PLAN.md, docs/dsl-experience.md,
docs/product-repl.md, docs/ci-runtime.md, docs/provider-protocols.md и назначенная карточка. Прочие спецификации загружай
по relevant clauses карточки, не игнорируя обязательные invariants.

Пакет сейчас содержит план; runtime/build/gates ещё не реализованы.
Начни с RAI-001 → RAI-003 → RAI-002 → RAI-004 по dependencies. Не реализуй сразу
всю программу. Одна карточка — один проверяемый diff и work record.

Главный UX:
  scout.ask("вопрос") — foreground answer;
  val job = scout.start("задача") — background;
  job.await / job.send("уточнение") / job.cancel();
  (scout andThen reviewer).ask("задача");
  all(scout("A"), reviewer("B")).run();
  batch(inputs, parallelism=2)(scout(_)).run().

Agent/Workflow/Session apply(input) создаёт Task без model/tool execution.
ask/start — explicit launch из REPL facade; один runtime владеет всеми jobs.
Не делай custom parser fake-Scala и не скрывай Future/Await/global effects.

Основа: pinned Scala 3 + ZIO 2 + официальный compiler REPL adapter/JLine;
exact toolchain выбирается и проверяется RAI-001/003. M0 native agent loop
работает с scripted/mock backend, OpenAI-compatible Chat/SSE, Anthropic-compatible Messages/SSE, tools и headless CI.

Raider работает без Yabanin через оба first-class compatible API. Yabanin
optional; его Go code не меняй в Raider карточках. docs/yabanin-integration.md и source snapshot описывают проверенные
поверхности и gaps; deployment capabilities подтверждаются doctor/stand.

Повседневные gates без paid LLM. Не менять thresholds/protected verifiers
в продуктовых карточках. Missing/scanner error/skipped/stale — не passed.
Если API неясен, подготовь ADR/reproducer, не меняй его молча.

Выход: actual changed paths, commands/exit codes, valid evidence path,
limitations и следующая ready card. Work record создать только после
реальной работы, подготовить до финального fingerprint gate.
```

## 2. Как назначать следующую карточку

```text
Выполни RAI-<NNN> из docs/tasks/<milestone-file>.md.
Accepted dependencies: <IDs + actual records>.
Relevant contracts: <sections>.
Allowed paths: <paths>.
Baseline: <source fingerprint/commit>.
Не расширяй scope. Исполни required gates и передай evidence.
```

Review отдельным свежим контекстом по prompt из [model workflow](model-workflow.md). Owner не обязан вручную проверять каждую строку, но final acceptance/gate policy ownership остаётся у него.

## 3. Первые результаты, которые нужно получить

1. RAI-001: REPL/headless feasibility prototype, transcripts и ADR.
2. RAI-003: clean build, pinned toolchain, canonical commands.
3. RAI-002: compile-tested API и DefinitionContext/CI schemas.
4. RAI-004: quality runner с валидируемыми manifests и policy.
5. RAI-005/006: profile/config и deterministic backend.
6. Потом native runtime/REPL vertical slice по graph, не wholesale implementation.

## 4. Чего не поручать одним запросом

«Сделай весь raider», «сделай production-ready», «добавь всё из pi/OpenCode» и «сделай весь Yabanin integration» не являются карточками. В каждой нужны точные inputs, contracts, normal/error/cancellation cases и exit criteria.

## 5. Статус финансов и публикации

Этот пакет не разрешает spend, deployment, release publishing или push. Development uses local/mock tests. Live tests запускаются owner-selected отдельным capped профилем. Значения keys передаются через environment/helper вне git. Model/provider names в примерах — роли, не обещание текущей доступности.
