<!-- Translated from Russian original. Key terms preserved as-is. -->

# Параллельная разработка Raider

Владелец явно разрешил параллельную разработку 2026-10-03. Этот runbook применяется к агентам, разрабатывающим Raider. Product subagents Raider проверяются отдельно по runtime scopes/workspaces. Goal: [working product](working-product.md).

## 1. Роли и ограничение concurrency

По умолчанию один coordinator/integrator и до трёх активных исполнителей суммарно. Reviewer занимает свободный слот; не добавлять четвёртого writer поверх лимита. Число workers уменьшается при недостатке RAM/CPU, свободных задач или действительно изолированных каталогов. Большая карточка делится до coding.

Coordinator единолично владеет интеграционным checkout, shared `build.sbt`/`project/**`, public core/DSL signatures, gate registry/thresholds, dependency graph, board и финальной acceptance. Изменять эти файлы может назначенный отдельный owner, пока coordinator удерживает запрет на competing edits. Worker получает feature module/конкретные файлы и tests. Reviewer проверяет другой diff в свежем контексте; не принимает свой implementation.

## 1a. Worktree-режим (решение владельца 2026-10-12: агрессивный параллелизм, минимум 8 потоков)

Владелец явно авторизовал 8+ параллельных lanes через **git worktrees**
(вместо snapshot-копий). Worktree-режим — primary; snapshot-режим из §2 —
fallback, когда Git недоступен.

- Подготовка: coordinator коммитит чистое дерево в `main`; на каждый поток:
  `git worktree add <WT-ROOT>/raider-wt-<N> -b lane/wXX-r<N> main`. Worktree
  имеет собственный `.git` (файл-ссылку), собственный `target/`, отдельную
  ветку. `artifacts/tools/m2` копируется в worktree (untracked;
  bootstrap-SSL окружения — pre-existing).
- Worker работает ТОЛЬКО в своём worktree/ветке. Коммит в СВОЮ ветку разрешён
  и обязателен (деливерабл = ветка + RECORD.md); push запрещён; `main` и
  shared-файлы (build.sbt/project/**, board, docs/tasks/**, docs/adr/**,
  scripts/**, .gitignore) — только coordinator.
- Гейты в worktree идут в git-mode (`make quality-changed` без
  QUALITY_BASE_MANIFEST — классификация vs HEAD ветки). sbtlock не нужен
  (изолированные target/); shared coursier-cache конкурентно-безопасен.
- Интеграция: coordinator мержит lane-ветки в `main` последовательно
  (`git merge --no-ff`), конфликты решает сам, после каждого батча мержей —
  полные гейты на main. Lane-зелёное ≠ финальное: main-гейты обязательны.
- REVIEW-поток занимает один из worktree-слотов (read-only, вердикт-файл).
- Лимит: минимум 8, вверх ограничен только RAM/CPU (18 ядер / 128 GB —
  эмпирически 8-10 sbt-JVM устойчиво) и числом независимых ready-карточек.

## 1b. Видимые spawn-агенты (решение владельца 2026-10-12 — основной режим исполнения)

Доработки запускаются как **видимые spawn-субагенты в интерактивной сессии
владельца**, а не фоновыми процессами. Владелец открывает opencode, говорит
`/raider-wave` (или `/raider-wave <IDs>`) и наблюдает каждую lane как агента
в TUI в реальном времени.

- Coordinator — primary-сессия с владельцем. Workers — субагенты
  `raider-worker`, reviewer — `raider-reviewer` (`.opencode/agents/*.md`);
  диспатч — команда `.opencode/command/raider-wave.md`. Spawn через task tool.
- Изоляция §1a не ослабляется: worker получает в assignment packet ABSOLUTE
  worktree path + ветку и адресует все tool calls этим путём. Worktree по
  умолчанию — `wt/<card-id>` внутри репозитория (gitignored), чтобы spawn-агент
  оставался в пределах project dir и его permissions; внешний `<WT-ROOT>` —
  только если владелец явно настроил external_directory allow.
- Child session сама по себе не worktree — isolation даёт worktree + ветка +
  absolute paths, а не факт спавна. Все правила §1a (branch-per-lane, запрет
  push/merge воркером, sequential merge coordinator'ом, main-гейты после
  батча) действуют без изменений.
- Запрещено по умолчанию: исполнение карточек фоновыми bash-циклами `opencode
  run`/`nohup` (night-loop из docs/prompts/opencode-1k-continuation.txt).
  Ночной headless-режим — только отдельным явным решением владельца,
  фиксируемым в board с причиной; по умолчанию он не используется.
- Отчёт lane = финальное сообщение субагента (changed paths, команды/exits,
  evidence, риски) по формату deliverable агента; coordinator интегрирует и
  пишет records/board. Не требовать от владельца чтения логов, чтобы узнать
  статус lane: статус виден в TUI и в отчёте coordinator'а.
- Fallback (в порядке деградации): (1) task tool недоступен в установленной
  версии → worker'ы исполняются последовательно в primary-сессии, review
  параллельно; (2) явное отдельное решение владельца про night-loop. Причина
  fallback фиксируется в board. Не выдумывать API fields (`cwd` у task и т.п.).

## 2. Сначала доказать изоляцию

Перед запуском writers записать Git state, installed OpenCode version, доступные task/subagent tools, cwd и baseline fingerprint. На момент аудита repository unborn и все исходники untracked; обычный worktree от HEAD сейчас невозможен.

Если существует подходящий immutable commit и Git writable: отдельный worktree/branch на lane. Dirty user files не прятать stash/reset и не включать произвольным initial commit. Если нужный baseline включает uncommitted work, использовать snapshot путь.

Fallback без commits или записи в `.git`: immutable source snapshot и отдельная копия на lane в разрешённом scratch directory. Manifest включает все выбранные source/config/docs/scripts, в том числе untracked, relative paths/modes/hashes/symlink targets. Исключить `.git`, `.env*`/credentials, runtime state, outputs/caches и raw evidence; allowlist config examples явно. Никаких hardlinks на mutable source. Symlink за пределы snapshot не становится способом читать secrets; перечисленные immutable toolchain artifacts можно использовать отдельно read-only.

Snapshot copy без `.git` — полноценный supported lane mode. Worker фиксирует `Git unavailable: isolated snapshot`, сверяет root-provided base manifest и cwd; отсутствие Git не заставляет вернуться в общий checkout. RAI-004 обязан поддержать diff по before/after inventory (path/hash/mode/symlink/add/delete; rename может быть delete+add), например через целевой `QUALITY_BASE_MANIFEST`. До реализации этого режима использовать явные scoped bootstrap commands с тем же manifest. Manifest mode проверяет base/current hashes, active path classification и минимум active fast при отсутствии изменений; не пропускает проверки. Flag сначала реализуется и тестируется, только затем используется в отчётах.

Worktree/copy path абсолютный и передаётся каждому tool call. До работы worker доказывает свой cwd и baseline; coordinator убеждается, что output/build directories различаются. OpenCode child session сама по себе не создаёт worktree. Основной режим привязки lane — видимый spawn-субагент (§1b): isolation дают worktree + ветка + absolute paths в packet. Отдельный OpenCode process с `--dir <lane>` — fallback последней очереди (только при недоступности task tool и с записью причины в board); не выдумывать task API parameter `cwd`, если установленная версия его не имеет.

OpenCode 1.18.34 в аудитируемой среде подтверждён командой `--version`; `run --help` поддерживает `--dir`, `--agent`, `--file`. Перед новым запуском перепроверить версию. Командные/agent schemas могут различаться между версиями. Документация: [CLI](https://opencode.ai/docs/cli/), [agents](https://opencode.ai/docs/agents/).

Если изоляция временно недоступна, writers исполняются по очереди, research/review остаются параллельными; причина видна в board. Не называть одновременную запись нескольких агентов в root checkout параллельной lane работой.

## 3. Assignment packet

Coordinator передаёт каждому worker:

```text
Assignment / parent card / lane:
Absolute workspace / baseline snapshot hash / source inventory:
Implementation prerequisites / later integration prerequisites:
Frozen public symbols + exact signatures + schema versions:
Allowed files and tests; shared files forbidden:
Normal/error/cancel acceptance IDs:
Required active gate cases / milestone obligations still pending:
Expected deliverable (patch + before/after hashes + evidence):
Heartbeat / bounded task size / escalation condition:
```

Не передавать весь многотысячестрочный план в каждую coding task. Обязательные invariants, относящиеся к изменению, входят в packet. Worker не выдаёт отсутствующий signature за согласованный: при расхождении возвращает reproducer и минимальный contract-change request.

## 4. Практические waves

| Wave | Lane A | Lane B | Lane C | Условие интеграции |
| --- | --- | --- | --- | --- |
| F0 | Spike fixes/reproduction | Read-only contracts/gate review | Acceptance fixtures/design | Истинные exits/evidence; frozen bootstrap decisions |
| Foundation | Build/toolchain owner | Public contracts owner после build skeleton | Gate runner/testkit/minimal CI после interface freeze | Shared compile/contract tests, stage registry |
| F1 | Jobs/scopes/budgets/native loop | OpenAI+Anthropic protocol slices | Filesystem/process tools | Один native tool loop через HTTP fixture |
| F2 | DSL/runtime integration | REPL/terminal adapter | Headless CI/reports | Один Program работает в REPL и CI |
| F3 | Sessions/context/journal | Typed tools/SPI/examples | Packaging/task fixtures | Ежедневный coding workflow из installed package |
| F4 | Исправления найденных defects | Independent reviewer/verification | Gateway baseline / optional enriched integration | W01–W17 на одном candidate |

Таблица — lanes, не разрешение начинать зависимые части раньше signatures. Два provider wires можно разделить на двух workers, когда свободен слот; shared HTTP/SSE framing имеет одного владельца. Контракты меняет root или эксклюзивно назначенный owner, затем все затронутые lanes получают новый frozen baseline.

## 5. Что делает worker

Проверяет assignment/cwd/source hashes, пишет behavioral fixture, реализует узкий behavior, запускает локальные gate cases, формирует deliverable. Tools/tests работают внутри lane; ports выделяются динамически, temp paths уникальны. `target/`, compiler state, generated files и reports не общие. Shared dependency download caches только с поддерживаемой concurrency/lock semantics; при сомнении separate cache или предварительный bootstrap.

Worker не редактирует центральный board/другие lanes; отправляет coordinator status и record. Результат содержит base hash, add/modify/delete/rename inventory, patch/binary payload где нужно, resulting source hash, test argv/exits, log hashes, unresolved findings. Не копировать весь lane каталог поверх root.

## 6. Интеграция и конфликт

Coordinator проверяет paths/secret exclusions/base hashes, читает diff, получает independent review, применяет изменение последовательно. Если target path изменился относительно base, выполнить осмысленный merge/rebase и повторить affected tests; запрещено force-overwrite. Изменения пользователя сохраняются.

После каждого integration batch: shared compile/contracts + affected active gates; после каждой wave runnable demo из интегрированного дерева. Lane evidence доказывает её baseline; финальный milestone требует нового общего прогона, а не объединения старых manifests. Для shared contract change dependent lanes приостанавливают несовместимые edits и обновляют snapshots.

Если checks отсутствуют, coordinator не принимает «passed by inspection». Создать scoped fixture/runner task; пока obligation pending. If failure cause повторился дважды, fresh reviewer решает bounded redesign/reproducer, executor не отключает gate.

## 7. Статусы без вечного review-blocker

Card states: todo → ready → in-progress → review → accepted; при defects changes-required; при внешнем impediment blocked с причиной. `ready` означает реально удовлетворённые implementation dependencies; «ready, ждёт dependency» недопустим. У parent separate integration acceptance — parent не accepted от одного компонента.

Coordinator планирует reviewer автоматически, как только diff готов. Нет требования ждать owner confirmation на каждую локальную карточку: запрос владельца уже разрешает implementation/review/integration по этому runbook. Owner нужен для новых продуктов/публикации/live spend/изменения обязательных требований, которые ещё не разрешены контекстом. Pending review не решается самоприёмкой автора.

Operational board/records описывают ход работы. Product evidence привязано к immutable input snapshot; operational files исключаются только по точному protected policy allowlist, а их revision/hashes сохраняются как audit metadata. Acceptance fixtures, specs, prompts, source/build/gate registry из fingerprint не исключаются. Документационные изменения проверяются отдельно, но не заставляют пересобирать бинарник только из-за новой строки статуса.

## 8. Перезапуск и завершение

Before compaction/turn end coordinator сохраняет active assignments, lane paths/baselines, worker session/process IDs, ready queue, last checks, review verdicts и next actions. Новый turn сначала проверяет живые процессы/актуальность hashes; не дублирует worker и не replay product mutation из artifacts.

Если workers продолжают работу, coordinator мониторит их и интегрирует результаты в доступном turn, не объявляет завершение по факту spawn. По исчерпанию session limit оставляет исполнимый handoff; при внешнем blocker закрывает все независимые локальные задачи. Done — [working acceptance](working-product.md), а не «56 карточек упомянуты в отчёте».
