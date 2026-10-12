# ROADMAP-SDK: work record

Status: done (planning changes and independent review). Date: 2026-10-12 UTC.
Baseline commit: `4b46dc7`; git checkout, initial `git status --short` empty.

## Resulting behavior

В существующий roadmap встроен embedding SDK для Scala/ZIO: P0 audit/ADR,
P4.C внешний consumer и lifecycle, P7 общий workflow host/REPL/CI и прикладной
сценарий, P10 versioned artifacts/clean consumer acceptance. SDK-01–05 явно
включены в M4 exit. RAI-115 зарегистрирована ready для read-only audit и packet
следующей ADR-карточки; реализация SDK этой работой не выполнена.

Решения владельца: Raider + SDK, первый сценарий CI; текущая волна продолжается;
полностью open-source проект. Подробные руководства отложены; сохраняются
MIT/license policy, W01–W18, текущие lineage/TUI карточки и P11 после M4.
13 100 h — прежний envelope; оценка добавочного труда/остатка нужна до coding.

## Changed paths

- `docs/roadmap-12k.md`: scope, фазы, dependencies и SDK acceptance.
- `docs/PLAN.md`, `docs/prompts/opencode-12k-parallel.txt`: согласованный вход;
  prompt также приведён к действующим 13 100 h и spawn/worktree правилам.
- `docs/tasks/backlog.json`, `docs/tasks/m1.md`, `docs/tasks/README.md`: RAI-115.
- `docs/tasks/roadmap-sdk-integration.md`: scope текущей planning card.
- `docs/work/board.md`: решения владельца и следующая независимая lane.
- Этот record. Runtime, build, gate policy, публичные signatures не менялись.

## Acceptance evidence

| Criterion | Actual result | Evidence |
| --- | --- | --- |
| Planning consistency | passed, 133 cards | `artifacts/plan-review/roadmap-sdk.json` |
| Changed-path classification | docs only, exit 0 | `make quality-changed`, session output |
| Docs gate execution | passed, plan-validate exit 0 | `artifacts/quality/20261012-191140-51789-0001/manifest.json` |
| Independent review | approved after explicit SDK-01–05 added to M4 exit | visible spawn `/root/roadmap_review`, final approval in owner session |
| Scope preservation | existing cards unchanged; one new audit card; no implementation edits | baseline diff vs `4b46dc7` |

## Commands

| Command | Exit | Evidence |
| --- | --- | --- |
| `git diff --check` | 0 | session output |
| `python3 scripts/plan/validate.py --out artifacts/plan-review/roadmap-sdk.json` | 0 | planning report, 133 cards |
| `make quality-changed` | 0 | selected docs |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | manifest above, docs passed |

Эти результаты получены до добавления данного record. Фактический runner
включает board/records в fingerprint, несмотря на описанный в quality spec
operational allowlist; policy не изменялась. Поэтому manifest выше — evidence
своего pre-record snapshot, не final freshness claim. Повторная проверка final
tree после записи record должна сохранить commands/exits/manifest path в
`artifacts/plan-review/roadmap-sdk-final.json`, логи рядом (вне product inputs).
Итоговый отчёт с коммитом опирается на этот финальный receipt; отсутствие receipt
или nonzero exit не означает passed. Record после финального прогона не менять.

## Limitations / blockers

Это приёмка планового изменения. Scala/runtime/live tests не запускались:
изменены только плановые документы и backlog. Пользователи, сроки и состав
команды не заданы; пилоты остаются pending. SDK API/артефакты ещё не приняты.
Старая арифметика/исторические статусы всего roadmap не переаудированы;
13 100 h не выдаются за measured estimate-to-complete. Никакой публикации,
сообщений третьим лицам или платных вызовов не выполнялось.

## Review and next task

Reviewer `/root/roadmap_review` проверил code seams и финальный diff независимо;
замечание о M4 acceptance устранено, финальный verdict approved.
Coordinator — единственный writer; reviewer read-only, общих mutable builds нет.
Следующая SDK-карточка: RAI-115; текущая волна LIN-13/14/15 + RAI-113/114
продолжается. После audit — отдельная ADR-карточка с reviewed public boundary,
затем small implementation/consumer cards по фактическим dependencies.
