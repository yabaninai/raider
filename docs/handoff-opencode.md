# Довести Raider до рабочего состояния через OpenCode

## Запуск

В корне репозитория запустить `opencode`, затем:

```text
/raider-finish
```

Команда [.opencode/commands/raider-finish.md](../.opencode/commands/raider-finish.md) выбирает Build и содержит полный [finish prompt](prompts/opencode-finish.txt). Старое имя `/raider-build` сохранено как идентичный alias. Vendor/model не зашиты: используется owner-selected coding profile. Формат project commands подтверждён [OpenCode docs](https://opencode.ai/docs/commands/); на момент review установлен OpenCode 1.18.34. Перед запуском исполнитель проверяет свою фактическую версию и доступные subagent tools.

Короткий prompt для ручной вставки в Build:

```text
Доведи Raider до рабочего coding agent по @docs/working-product.md.
Выполни @docs/prompts/opencode-finish.txt и AGENTS.md.
Учти текущий код и @docs/reviews/2026-10-03-readiness.md; не начинай заново.
Параллельная разработка разрешена: coordinator + до 3 workers
в изолированных worktrees/snapshot copies по docs/parallel-development.md.
Исправляй defects, интегрируй, запускай проверки и продолжай до W01–W17.
Покажи установленный CLI, настоящий REPL/DSL, coding workflow и CI evidence.
Live/model comparison отражай отдельно; не выдавай mock success за parity.
```

## Что уже есть и откуда продолжать

Есть спецификация, `docs/work/board.md`, records и реальные REPL/headless и OpenAI-compatible feasibility spikes. [Независимый review](reviews/2026-10-03-readiness.md) нашёл false-green в обоих launchers, неполную воспроизводимость harness/evidence и непокрытые production guarantees. Продуктовые build/runtime/DSL/provider/tools/CLI/gates ещё предстоит реализовать. Следующая сессия сначала сверяет actual repository: статус может измениться.

Первый результат нового запуска — закрытие актуальных review findings и foundation prerequisites, затем параллельные runtime/providers/tools slices. Старые «32/32» и «6/6» не закрывают полную приёмку: нужно подтвердить scope и надёжность checks. Исходный evidence сохраняется, новый прогон получает отдельные hash/run identity.

`python3 scripts/plan/validate.py` проверяет только план/links/backlog/prompts. Его успешный exit не доказывает выполнение Scala или готовность coding agent. Product gates создавать и выполнять по [stage contract](quality-gates.md).

## Полномочия coordinator

Владелец разрешил parallel development. Coordinator назначает ready child cards, отдельные каталоги, path ownership и independent reviewer без повторного вопроса на каждую задачу. Он один интегрирует изменения, контролирует shared APIs/build/quality registry и reruns affected shared tests. Worktree не появляется от факта spawn subagent; механизм изоляции проверяется до coding.

Git сейчас unborn/untracked: до появления пригодного commit использовать immutable source snapshot copies с включением нужных untracked files и исключением secrets/.git/outputs. Не создавать commit/stash/reset из чужой работы ради удобства. Каждый worker получает absolute path/baseline/точные files/signatures/tests; передаёт patch+hashes/evidence. [Подробный runbook](parallel-development.md).

Использовать узкие packets для простых моделей. Reviewer свежим контекстом читает actual diff и tests/logs. Большие contracts разбиваются до implementation; новая архитектура не возникает незаметно внутри worker diff. Future stage checks остаются pending, active failing tests не отключаются.

## Когда работа закончена

[W01–W17](working-product.md) требуют не только M0 runtime, но и нормальные файловые tools, test-driven coding loop, sessions/history/context/compaction, typed DSL, real REPL, installable package и CI. Один бинарник/исходный snapshot проходит всю общую приёмку. Базовый Yabanin contract W17 обязателен offline; запуск через gateway, enriched integration и внешние backends опциональны и не заменяют native loop.

Отдельно: engineering_ready, live_verified, opencode_comparison. Live smoke/comparison выполняется с configured endpoint и явным budget; без него результат помечается pending. Сама coding-модель OpenCode относится к выбранной владельцем development session, не к продуктовым paid evals.

## Продолжение после остановки сессии

```text
Продолжи выполнение docs/prompts/opencode-finish.txt.
Сверь git status, board/records, active lane paths/baselines/session IDs.
Проверь живых workers и applied patches, не дублируй их.
Возобнови nearest ready action, интегрируй и повтори affected gates.
Заверши W01–W17; внешние blockers перечисли точно, остальную работу продолжай.
```

На каждый handoff: active task, source baseline, actual changed paths, exact commands/exits/log hashes, reviewer verdict, оставшиеся obligations и next executable action. Перечень файлов или запущенных workers не закрывает milestone.
