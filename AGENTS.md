# Raider: правила для исполнителей

## Статус и источник требований

На 2026-10-03 есть спецификация и feasibility spikes RAI-001/SPIKE-OAI-01; продуктового runtime пока нет. При каждом запуске проверять actual code/board, а не считать этот статус вечным. Целевое применение — CI pipelines и полезный standalone coding workflow с реальным Scala REPL. Обязательный вход: `docs/working-product.md`, `docs/parallel-development.md`, `docs/PLAN.md`, назначенная карточка и applicable contracts. Working acceptance и stage-aware gates уточняют старую milestone очередность. Не выдавать проектируемые API/gates или spike за готовый продукт.

## Работа по карточкам

- Одна задача — одна карточка и один проверяемый результат. Прежде чем менять код, проверить `git status --short` и существующую реализацию; в изолированной копии без `.git` записать этот режим и проверить baseline inventory/current hashes по parallel runbook. Не возвращаться в root checkout ради Git-команды.
- Соблюдать allowed paths, зависимости и non-goals карточки. Изменение публичного контракта требует отдельного ADR и обновления потребителей/примеров.
- Продолжать разрешённую работу без промежуточных запросов подтверждения. Не запрашивать повторно уже полученное разрешение.
- Языковая политика (2026-10-03): продуктовый код — только Scala 3 + ZIO (`modules/**`, `contracts/**`, `examples/**`, tests, HTTP-стенды). Python только в `scripts/plan`, `scripts/quality`, bootstrap glue и замороженных `experiments/**`; в поставку не входит. Для provider-тестов — Scala stand или локально запущенный OpenAI-compatible стенд по явной конфигурации.
- Не добавлять соседние функции, зависимости, daemon, DSL-макросы или новые abstraction layers по инициативе исполнителя.
- Владелец явно разрешил параллельную разработку. Coordinator назначает независимые ready задачи до трёх workers в отдельных worktrees/snapshot copies по `docs/parallel-development.md`. Root checkout/shared contracts/build/gate registry/board имеют единственного назначенного owner. Child session не является изолированным checkout; параллельные mutable builds одного checkout запрещены.
- Не менять соседний Yabanin репозиторий в рамках карточки Raider. Yabanin-изменения получают отдельные карточки и его собственные обязательные правила.

## Проверки и evidence

- Каталог и смысл гейтов: `docs/quality-gates.md`, включая обязательный activation stage. До runner выполнять exact bootstrap checks. Component stage проверяет конкретные реализуемые cases, остальные остаются milestone obligations; full/milestone не имеют green skips. Не требовать packaged REPL/CI от карточки, которая только создаёт его prerequisites.
- После появления runner: перед завершением задачи выполнять `make quality-changed`, затем `make quality-changed QUALITY_EXECUTE=1`; дополнительно выполнить семантически необходимые профили из карточки.
- Изменения runtime/concurrency требуют cancellation/ownership/budget tests. Изменения REPL требуют настоящих compiler/PTY проверок, а не только mock interpreter.
- Core/CLI обязаны работать без Yabanin: OpenAI-compatible и Anthropic-compatible transports — first-class MVP. Нет скрытого direct fallback из gateway при auth/policy failure.
- Изменения headless runner/CI adapter требуют non-TTY, SIGTERM, exit-code, artifact/schema и trust-boundary acceptance. GitHub/GitLab adapters не реализуют второй scheduler/runtime.
- Пропуск, timeout, scanner error, отсутствие инструмента или внешний blocker не являются passed. Нельзя уменьшать пороги или отключать проверки ради зелёного статуса.
- Evidence относится к проверенному fingerprint исходников и gate configuration. Изменение исходников после проверки требует повторения затронутых гейтов.
- После реального выполнения создать work record по `docs/templates/work-record.md`. Не создавать пустые отчёты заранее.
- Evidence привязано к immutable product-input snapshot. Operational board/records могут быть исключены только точным reviewed allowlist с отдельными audit hashes; code/specs/prompts/build/fixtures/gate policy остаются в fingerprint. Изменение product inputs требует новых затронутых проверок; lane evidence не заменяет проверку интегрированного candidate.

## Границы доверия

- REPL и Scala-плагины исполняют доверенный пользовательский код с полномочиями процесса. Tool policy не является sandbox для произвольной Scala.
- Не писать секреты в git, history, snapshots, примеры, argv, отчёты и tool output. Credentials задаются через ссылки на env/helper, значения не логируются.
- Не делать реальные платные вызовы без явно заданного режима и бюджета. Обычные development/CI gates используют mock/replay.
- Не повторять мутационные tool calls автоматически после неоднозначного результата. Не заявлять exactly-once или durable resume для произвольной Scala-лямбды.
- Нельзя менять protected verifiers, скрытые acceptance fixtures и gate policy в карточке реализации продукта. Такие изменения требуют отдельной карточки и независимого рассмотрения.

## Передача результата

Сообщить changed paths, поведение, точные команды/exit codes, evidence path, риски/ограничения и следующую доступную карточку. Не выдумывать выполненные проверки, commit/PR URL, оплаченные результаты и проценты качества.
