<!-- Translated from Russian original. Key terms preserved as-is. -->

# Разработка простыми моделями

## 1. Что делает качество воспроизводимым

Основная единица работы — маленькая карточка с фиксированным входом, allowed paths, контрактом и executable acceptance. Большая модель нужна для решения архитектурной неопределённости; реализацию узких контрактов можно вести более простыми моделями. Название модели не является доказательством качества.

Все обязательные знания хранятся в git. Нельзя требовать, чтобы следующий исполнитель помнил чат, предыдущую сессию или имел определённый MCP.

## 2. Роли

| Роль | Объём | Выход |
| --- | --- | --- |
| Owner | Приоритеты, продуктовые/лицензионные решения, риск/бюджет | Выбранные карточки и acceptance |
| Planner | Разбить неопределённость, зафиксировать интерфейс | ADR + маленькие карточки |
| Implementer | Одна карточка | Diff, tests, work record |
| Reviewer | Проверить контракт/diff/evidence | Findings и verdict |
| Integrator | Собрать accepted cards, проверить downstream | Final full/acceptance evidence |

Одна модель может исполнять разные роли в разных чистых контекстах. Independent review означает fresh context и самостоятельное чтение diff/tests, а не обязательно другой vendor. Автор не утверждает себе изменения protected verifier/quality policy.

## 3. Размер карточки

Практический ориентир: 1–3 production files, 1 interface или один observable behavior, обычно 100–400 строк diff плюс нужные tests. Это не жёсткая норма: task splitting определяется dependency и пониманием, не искусственным LOC limit.

Если карточка требует новых публичных API, redesign state machine и изменения пяти модулей одновременно, сначала design/contract card, затем реализация. Одна «сделай весь REPL» карточка запрещена этой программой.

## 4. Входной packet

Для каждой карточки:

1. `AGENTS.md`.
2. Текст карточки и только relevant specification sections.
3. Exact allowed files/symbols и принятую API signature.
4. Existing tests/fixtures и evidence baseline.
5. Dependencies и их accepted work records.
6. Required test commands/gate profiles.
7. Non-goals и критерии остановки/escalation.

Не отправлять всю программу на 50 страниц в каждую coding task. Full index помогает найти нужные разделы, work packet содержит конкретику. Обязательный invariant не вырезать ради короткого контекста.

## 5. Рабочий цикл

```text
read card → inspect code/status → identify contract tests
→ implement smallest change → targeted check → repair
→ required gates → fresh review → accepted → next dependent card
```

При исправлении bug: сначала reproduction, потом fix. При новой функции: acceptance fixture из контракта, затем реализация. Не создавать tests, которые лишь подтверждают текущий случайный implementation.

После двух неудачных содержательных попыток по одной причине прекратить одинаковое самоповторение: подготовить precise blocker/reproducer и design packet. Простая missing import/format issue исправляется локально и не требует escalation. Нет права «временно» отключить failing test.

Если нужен change публичного API: представить minimal diff contract/ADR, обновить designated fixtures и dependent cards. Runtime implementer не меняет naming потому, что ему привычнее другой DSL.

## 6. Готовый prompt исполнителю

```text
Работаешь в репозитории raider. Выполни только карточку <ID>.
Прочитай AGENTS.md, текст карточки, указанные спецификации и текущий code path.
Проверь git status (или manifest baseline для snapshot без Git) и dependencies; сохрани чужие изменения.
Реализуй контракт с минимальным diff в allowed paths. Сначала подготовь
acceptance/reproduction tests. Не меняй protected gates/fixtures/thresholds.
Запусти targeted tests и все required profiles; failed/skipped/stale не passed.
Подготовь work record до финальных gates. Передай exact commands, exit codes,
evidence path, limitations и следующую доступную карточку.
Если контракт неоднозначен, подготовь конкретный design decision, продолжи
независимую разрешённую работу, не изобретай несовместимый API молча.
```

## 7. Готовый prompt reviewer

```text
Проверь карточку <ID> по actual diff и текущим исходникам. Summary автора
используй только как навигацию. Прочитай contract и required gates.
Проверь normal/error/cancellation/race cases, ownership, budgets, secrets,
backward compatibility и неослабленные verifiers. Проверь source/log/policy
hashes manifest. При необходимости воспроизведи конкретный сценарий.
Дай accepted / changes-required / blocked с фактами, file:line, severity и
минимальной корректировкой. Не добавляй соседние features в эту карточку.
```

Reviewer не доверяет fabricated evidence пути: файл должен существовать и быть проверен runner. Нет требования согласиться с автором. На выбранные risk boundaries ревью включает adversarial check, а не простое перефразирование diff.

## 8. Управление board

Состояния карточек: todo → ready → in-progress → review → accepted. `blocked` хранит dependency/cause и не превращается в accepted по истечении времени.

`docs/tasks/README.md` — static backlog; текущее исполнение можно вести в issue tracker или отдельном board file. Work records создавать только для выполненных карточек. В work record сохранять task ID, source baseline, actual changed paths, acceptance IDs, commands/exit codes, evidence и ограничения.

Владелец разрешил parallel development 2026-10-03. [Runbook](parallel-development.md): coordinator + до трёх workers, isolated worktrees/snapshot copies, frozen signatures/allowed paths, automatic fresh review и последовательная интеграция. Один writer **на checkout**, независимые каталоги могут собираться параллельно. Implementation dependencies отличаются от final integration dependencies: компонент можно реализовать против accepted interface/testkit, parent принимает фактическую общую работу. Refinement фиксируется до coding и проходит review; «почти готово» не accepted prerequisite.

Finish target — [working coding agent](working-product.md), включая sessions/context/package/fixtures. Milestone M0 не финал продукта. Stage-aware gates не требуют будущий runtime от первого compile-only контракта; все итоговые obligations остаются mandatory.

## 9. Контроль scope и качества

- Реальные платные calls не требуются для повседневной разработки.
- Mock mode виден пользователю и в artifacts; fixture output нельзя показывать как ответ живой модели.
- API examples компилируются/исполняются, устаревший пример блокирует docs gate.
- Dependency addition требует объяснить существующий gap и compatibility/license result.
- Нет speculative framework/macro, автоматического исправления всех lint findings чужого модуля, массового рефакторинга соседнего кода.
- Flaky test не принимается как «особенность асинхронности»: fix virtual-time/coordination или documented platform cause.
- `run` succeeded и task verifier accepted — отдельные факты.

## 10. Z.ai для разработки и для продукта

Исполнитель может использовать доступный z.ai coding tool и любую назначенную модель. Raider не должен зависеть от имени этой модели или её subscriptions.

Продукт не требует Yabanin. OpenAI-compatible/Anthropic-compatible profiles first-class; Yabanin — optional gateway/accounting. Direct Z.ai profile использует explicit base URL/account configuration и собственную capability проверку. General API и Coding Plan endpoint отличаются; не подставлять один вместо другого автоматически. Поддержку custom harness/account подтверждают актуальные docs и owner account, не наличие похожего URL.

Коммерческие limits/model IDs не фиксировать навсегда в AGENTS.md. Work records/eval сохраняют фактически использованные IDs и gateway actual model. Route simple worker/review tasks через роли; повышать модель для конкретной доказанной сложности.

## 11. Escalation packet

Если простая модель не решает задачу:

```text
Card ID / contract clause:
Smallest reproducer:
Current relevant symbols/files:
Expected / actual:
Attempts already made and why they failed:
Exact command / exit / logs:
Two bounded implementation options:
Required decision / missing external dependency:
```

Так owner/более сильная модель принимает конкретное решение. Нельзя просить «переделай всё качественно» без reproducer.

## 12. Приёмка владельцем без чтения всего кода

Для каждого milestone — одна команда demo acceptance, одна команда gate verification, краткая таблица результатов и known limitations. Owner видит реальные terminal transcripts, root/job trees, cleanup, расходы/uncertainty и actual diff. Большой narrative report не заменяет эти артефакты.
