<!-- Translated from Russian original. Key terms preserved as-is. -->

# Рабочий Raider: цель и обязательная приёмка

Редакция 2026-10-03 по запросу владельца: довести Raider до полезного самостоятельного coding agent с хорошим Scala DSL; разработку вести параллельно. Это уточнение [исходного плана](PLAN.md), а не утверждение о реализованных возможностях. [Текущая оценка](reviews/2026-10-03-readiness.md) фиксирует факты и дефекты.

## 1. Что требуется получить

Устанавливаемый CLI/REPL, который через native loop читает существующий проект, находит код, создаёт/меняет/удаляет файлы, запускает проверки, использует результат проверки в следующем шаге и отдаёт проверяемый результат. Он работает через прямые OpenAI-compatible и Anthropic-compatible API; Yabanin подключается опционально. Сценарии review, patch+checks и research/generation исполняются из REPL и внутри CI job одним runtime.

Ориентир OpenCode здесь — основной coding workflow: tools, инструкции проекта, permissions, история/context, subagents, CLI и расширение. Уровень качества решений модели доказывается сравнительным прогоном, а не количеством Scala классов или успешными mock tests. Сопоставимость всего upstream UI/IDE/web/cloud ecosystem отдельно не заявляется. Основные поверхности конкурента: [tools](https://opencode.ai/docs/tools/), [rules](https://opencode.ai/docs/rules/), [CLI](https://opencode.ai/docs/cli/), [plugins](https://opencode.ai/docs/plugins/).

Три независимых факта в итоговом отчёте:

- `engineering_ready`: собранный продукт проходит все offline/contract/real-process/PTY проверки ниже.
- `live_verified`: scoped summary плюс per-profile statuses `direct-chat`, `direct-anthropic`, `gateway`; каждый содержит actual/pending/unavailable, endpoint identity без credentials, model/build hash и evidence. Один OpenAI smoke не подтверждает Anthropic или gateway. Проверенный profile выполняет coding scenario с настоящей моделью, реальными изменениями и checks.
- `opencode_comparison`: одинаковый набор задач пройден Raider/OpenCode с записанными версиями, моделью, budgets, attempts и независимым verdict. До этого результата нельзя писать «не хуже OpenCode».

Отсутствие endpoint/account/budget оставляет live-пункты pending и не мешает завершить engineering. Native tools/runtime нельзя подменять внешним OpenCode engine и называть это собственной parity.

## 2. Definition of working

| ID | Обязательный результат | Приёмка и доказательство | Основные карточки |
| --- | --- | --- | --- |
| W01 | Воспроизводимая установка | JDK/toolchain pinned; clean-directory build/install; `raider --help`, REPL и headless запускаются | 003,022,044 |
| W02 | Native coding loop | Из fixture repo модельный loop читает, меняет, запускает тест, получает failure и исправляет; actual diff и hidden assertions | 010,014–016,024,026,041 |
| W03 | Полезные файловые tools | Numbered/ranged reads, glob/discovery, documented ignore rules, bounded search, continuation для truncation; create/edit/delete, multi-file patch и explicit partial-failure contract | 014,015 |
| W04 | Реальные процессы | argv/cwd/env/output bounds, exit status, deadline; child+grandchild cleanup; неопределённость не скрыта | 016,022 |
| W05 | Оба direct wires | OpenAI Chat и Anthropic Messages: полный tool round, streaming/nonstreaming, fragmented Unicode/JSON, errors/usage/cancel, без Yabanin | 011–013 |
| W06 | Policy | read-only/write/exec boundaries, child intersection, noTTY NeedsApproval, секреты/защищённые verifier files недоступны tools | 005,008,014–016,023 |
| W07 | Контекст проекта | Workspace/profile/instructions precedence, file provenance, relevant context, ограничения больших outputs и no silent policy expansion | 005,029,030 |
| W08 | Sessions | Serialized turns, цель и tool IDs переживают несколько turns; journal/archive/reopen; compaction bounded, не replay мутации | 027,030,036 |
| W09 | Собственные subagents | DSL fork и model delegate, один owner/root budget, await/send/cancel/lineage, maxLLM=1 без deadlock | 007–010,017,021 |
| W10 | Настоящий Scala REPL | val/def/case class/multiline/completion, сохранение bindings, prompt после ошибки; streaming не повреждает набираемый ввод | 018–020 |
| W11 | Хороший DSL | Все golden scenarios ниже compile+run; typed negative cases; bare Task без effects; pipeline codecs/checks | 002,017–020,028 |
| W12 | CI portability | Один bundle generic/GitHub/GitLab contexts; nonTTY/closed stdin/SIGTERM/exits/artifacts/JUnit; trusted bundle/policy | 022–026 |
| W13 | Расширение без core edits | Отдельный пример подключает custom tool + agent profile через public Scala SPI и реально исполняет tool | 028,029,031 |
| W14 | Root limits и truthful accounting | time/attempt/children/token bounds, known/unknown prices, refunds/reservations, retry/repair; no fake distributed cap | 009,027,028 |
| W15 | Самостоятельный вход | Built-in worker/scout доступны сразу после connection/policy setup; обычная задача без написания workflow | 005,020,022,046 |
| W16 | Реальные task fixtures | Review planted defect, bugfix, new file/module, refactor, multi-file change, research report; workspace tests/verdict artifacts | 026,041,046 |
| W17 | Обязательная базовая поддержка optional gateway | Yabanin-compatible config/URL/auth/labels, wrapper lifecycle owner без второго mint, no hidden direct fallback; deterministic local contract fixture. Enriched accounting/live stand — отдельно | 005,011,037; расширенные 038–039 |
| W18 | Итоговая интеграция | Все обязательные W01–W17 на одной immutable сборке, полный gate report и limitations; optional claims только с evidence | 043–047 |

У каждого W-ID в implementation acceptance manifest: case IDs, owning card/lane, command argv, expected observable behavior, actual status, source/toolchain/policy hashes, evidence links. Фраза агента «готово» не check. `not_due` допустимо только до целевого milestone; у обязательных W01–W17 в engineering_ready нет skipped/unavailable/not_due.

## 3. CLI и короткий путь

Целевые команды, ещё требующие реализации:

```text
raider repl --profile dev --workspace .
raider run --agent worker --input "Исправь regression и запусти проверки" \
  --profile dev --policy coding --out artifacts/raider

raider compile workflows.raider.scala --output pipelines.jar
raider run --bundle pipelines.jar --workflow repair --input-json task.json \
  --ci generic --context ci-context.json --policy ci-patch --out artifacts/raider
```

`--agent` выбирает registered built-in/custom Agent и mutually exclusive с `--bundle/--workflow`. Он использует тот же Program/Runner, input codec, policy и output artifacts. `coding` — явно настроенный trusted local policy, `ci-patch` — ограниченный CI workspace profile; разрешения не выводятся из имени строки. Setup объясняет missing credentials/model/permissions до dispatch; doctor по умолчанию не тратит model tokens.

## 4. DSL golden scenarios

Новые macros/псевдо-Scala parser не нужны. Primary verbs остаются `ask/start/await`, для композиции `andThen/all/batch`, для сложной координации `scope/fork/join`. Все fixtures идут через настоящий compiler; presets получают profile/workspace/policy до запуска.

```scala
scout.ask("Найди точку входа и объясни запуск тестов")

val task = scout("Проверь обработку конфигурации")
task                           // preview, без model/tool calls
task.run()

val job = worker.start("Исправь fixture bug и запусти проверки")
job.send("Сохрани публичную сигнатуру")
val result = job.await
val sameResult = job.await      // не повторяет работу

val implement = (scout andThen planner andThen worker).named("implement")
implement.ask("Добавь поддержку нового параметра")

val (facts, review) = all(scout, reviewer).ask("Проверь parser")
val (size, critique) = all(
  scout("Объясни parser").map(_.length),
  reviewer("Проверь parser")
).run()

val results = batch(List("parser", "config", "cli"), parallelism = 2)(
  name => scout(s"Исследуй $name")
).run()

val chat = openSession(worker)
chat.ask("Изучи parser")
chat.ask("Теперь исправь обнаруженный edge case")
chat.close()
```

Дополнительные cases: explicit child `fork/join`; typed pipeline с case classes/codecs; несовместимый `andThen` не компилируется; `all` fail-fast отменяет siblings; batchCollect не глотает внешнюю отмену; compile error сохраняет handles; await/watch Ctrl+C прекращает observer; foreground Ctrl+C отменяет owned subtree. Один файл definitions проходит REPL `:load` и compiled CLI.

Не менее одного coding example проверяет реальное изменение нескольких файлов и failing→passing checks. Не подменять worker строковым echo или заранее готовым patch в production path. Scripted backend допустим в deterministic tests с явной маркировкой.

## 5. Как перестроить исполнение старого backlog

Номера 001–053 сохранены. Последовательность определяется зависимостями интерфейсов и working acceptance; выполнение всего M1/P2 подряд не является условием полезного продукта.

1. **F0 — доверие к foundation.** Закрыть findings spike, проверить toolchain/JDK, уточнить ADR-015. Build/contracts/runner и minimal developer CI в foundation. Специально не переносить custom POM resolver или ThreadDeath как production solution без новых проверок.
2. **F1 — первые native end-to-end задачи.** После frozen ModelBackend/AgentBackend/Task/Tool contracts параллельно: runtime; два protocol adapters; filesystem/process tools. Один backend/tool round проходит через реальный CLI skeleton и local HTTP fixture.
3. **F2 — product surfaces.** После contracts: DSL/native integration; реальный REPL; headless CI/artifacts. Общий Runner единственный. После wave один definition запускается обоими путями.
4. **F3 — ежедневный coding workflow.** Journal/instructions/context/sessions, typed outputs и minimal SPI, package/help/examples. Это обязательная часть working-coding-agent, даже если первоначально обозначена beta.
5. **F4 — приёмка одной сборки.** W01–W17, regression corpus, soak/cancel/resource checks, clean installation. Затем разрешённые live smoke и сравнение с OpenCode. Базовая поддержка optional Yabanin profile обязательна offline; расширенный gateway/importers/bridges получают отдельные capabilities; readiness standalone не ждёт companion Go PR.

Для разблокировки настоящей параллельности root создаёт узкие child cards с `implementationDependencies` и `integrationDependencies`. Например provider serialization/SSE зависит от frozen ModelBackend и testkit; полная integration acceptance зависит от native loop. Это не разрешает объявить parent card accepted раньше полного integration gate. Root/reviewer фиксируют dependency refinement в board/assignment и compatibility report.

Minimal developer CI — ранняя выделенная `RAI-045.a` после foundation; её dependency не наследует packaging/full-MVP parent. Родитель RAI-045 включает её результат и позднюю release/platform matrix. IDs новых child cards регистрируются при назначении, с own prerequisites/allowed paths/acceptance; child не зависит от accepted parent, если parent ждёт child.

## 6. Что исправить по результатам аудита до наращивания функций

- Shell entrypoint возвращает код упавшего producer даже при логировании через tee; fault injection обязан это доказать.
- Spike evidence имеет actual source hash, environment, полный список checks и truthful exit; original artifacts сохраняются отдельно.
- Headless failure/report-write failure не дают 0; проверяются actual descendants, а не единственный sleep PID.
- HTTP cancellation проверяется до headers и на stalled body; blocked read не занимает compute pool, UTF-8 decoder incremental.
- PTY setup изолирует user.home/history и задаёт воспроизводимый TERM; arbitrary REPL hard interruption не считается доказательством безопасной отмены agent runtime.
- Background rendering, JDK21/Linux и все непокрытые clauses явно остаются обязательствами соответствующих milestones.
- Gate activation учитывает этап: [правила](quality-gates.md). Full/milestone acceptance никогда не заменяется суммой локальных зелёных отчётов.

## 7. Model-quality comparison

Сначала pinned OpenCode version/config и Raider build hash. Минимум шесть W16 task fixtures, одинаковый provider/model, task instructions, tool permissions, context/output limits, wall-time/token/monetary caps; isolated copies одного baseline. Минимум три repetitions на task/tool; недоступная seed capability отмечается, не эмулируется. Result artifacts/diff/hidden tests оцениваются одинаковым verifier.

Report: task pass rate, protocol/safety failures, wall time, attempts, tokens, observed/unknown cost и breakdown по task. Недостаточная выборка не доказывает статистическое превосходство. Не выбирать лучший run задним числом; provider outages и tool mismatches сохраняются в результатах. Если сравнение обнаружило слабость Raider, сделать reproduction card, исправить и повторить затронутые cases; не менять verifier ради совпадения.

Live tests требуют явно заданного endpoint/account/cap. Пока их нет, coding-model сессия разработки работает в своём выбранном владельцем режиме, а продуктовые gates используют local/mock fixtures. Итог честно различает executable capability и качество настоящей модели.
