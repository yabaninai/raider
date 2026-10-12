# ROADMAP-SDK-RESEARCH: work record

Status: done (research and roadmap revision). Date: 2026-10-12 UTC.
Initial baseline: `3a60f93a2197a0097e4a9f326260dd9fd7844578`, root initially clean.
Integrated audit baseline: `e2deeb30738cbd0beb3316f4e354d3416ac3adf4`.
Scope: [planning card](../../tasks/roadmap-sdk-research.md).

## Resulting behavior

Raider + OSS SDK встроен в текущий roadmap с первым пользовательским результатом:
написать Scala workflow и выполнить локально/в generic local CI. Основной сценарий
— AI-native SDLC кода TokenRouter; gateway остаётся P11. Scala3/ZIO pinned,
experimental API с migration, ранний preview до M4, trusted local trace data,
без durable crash resume. Пользователь подтвердил automatic commit/PR/merge,
Scala-configured checkpoints и release version/changelog/local tag/artifacts,
без push release tag, package/image publication или deploy.

WF-01/02 описывают отдельный preview; SDK-01–05 сохраняют host embedding proof.
M3 явно требует WF-01/02 + SDLC-01–04, M4 также SDLC-05 и SDK-01–05.
PR/merge engineering проверяется generic contract на Scala stand/disposable Git;
реальный forge pending до выбора adapter и его acceptance. RAI-115 принят,
RAI-116 ready для workflow/launch ADR, RAI-117 todo для SDLC contract.
До acceptance RAI-117 coordinator регистрирует per-case implementation/verifier
IDs, owners, dependencies, exact allowed paths и estimates. Реализация ждёт
reviewed contract, estimate и verifier packets. Текущая wave не переставлена.

Исследование primary sources сопоставляет Scala/JVM agent libraries, зрелость
описанных API, checkpoints/recovery и реальное отличие SDLC authoring. Статический
source audit выявил launch/lifecycle/admission/string bundle/accounting gaps.
TokenRouter прочитан без изменений; dirty input hashes сохранены отдельно от HEAD.
Существующий release workflow на pushed v* публикует образы и staging deploy;
после этого finding владелец выбрал local-only tag. Полный SDLC kit/remote adapter
и оставшийся бюджет unknown, 13 100 h не выдаются за подтверждённый остаток.

## Changed paths

- `docs/research/sdk-roadmap-research-2026-10-12.md`: решения, primary sources,
  positioning hypothesis, SDLC requirements, delivery/measurement и target inventory.
- `docs/research/sdk-embedding-audit.md`: отдельная reviewed RAI-115 lane, merged.
- `docs/roadmap-12k.md`, `docs/PLAN.md`,
  `docs/prompts/opencode-12k-parallel.txt`: фазы, зависимости, preview и milestones.
- `docs/tasks/backlog.json`, `docs/tasks/m1.md`, `docs/tasks/README.md`:
  RAI-115 accepted, RAI-116 ready, RAI-117 todo; 135 карточек всего.
- `docs/tasks/roadmap-sdk-research.md`: scope/acceptance текущей planning card.
- `docs/work/board.md`, `docs/work/records/RAI-115.md`, этот record.

Public API/schema, runtime, build, protected fixtures и registry не менялись.

## Acceptance evidence

| Criterion | Actual result | Evidence |
| --- | --- | --- |
| Owner decisions | ответы по scope/поставке/автономности/forge учтены | research §1 и roadmap §6b/§6c |
| Primary-source research | consulted 2026-10-12; docs/development/released distinctions сохранены | research §2–6, primary URLs |
| Actual code audit | independently reviewed and merged | RAI-115 record и `c156f40` / `e2deeb3` |
| TokenRouter read-only inventory | HEAD и 7 working hashes, 2 inspected dirty files явно отмечены | research §9 |
| Planning validator | passed, 135 cards | `artifacts/plan-review/sdk-research-precheck.json` |
| Integrated changed/docs execution | passed; docs selected | manifests below |
| Independent planning review | approve to commit after fixes | visible spawn `/root/plan_dependencies`, final owner-session verdict |

## Commands

Фактически выполненные проверки до добавления records и последних wording fixes:

| Command | Exit | Log / manifest |
| --- | --- | --- |
| `git diff --check` | 0 | coordinator и independent reviewer session output |
| `python3 scripts/plan/validate.py --out artifacts/plan-review/sdk-research-precheck.json` | 0 | 135 cards |
| `make quality-changed QUALITY_BASE=3a60f93` | 0 | `artifacts/plan-review/sdk-research-pre-record-0.log`, docs |
| `make quality-changed QUALITY_EXECUTE=1 QUALITY_BASE=3a60f93` | 0 | `artifacts/quality/20261012-194329-79713-0001/manifest.json` |
| `make quality-profile PROFILES=docs` | 0 | `artifacts/quality/20261012-194343-79788-0001/manifest.json` |

Эти manifests относятся к pre-record snapshot, не final tree. Фактический runner
хэширует board/records и nested worktree inputs; описанный в spec operational
allowlist не переопределялся в этой задаче. После записи records повторяются
diff check, validator, changed, execute и docs profile; commands/exits, logs,
manifest paths и hashes фиксируются вне product inputs в
`artifacts/plan-review/sdk-research-final.json`. Только passing final receipt
подтверждает окончательное дерево; при отсутствии/ошибке это pending.
После финального прогона эти records не меняются. Коммит не заменяет gate evidence.

## Limitations / blockers

Принят план и аудит, не SDK/runtime release. Product tests, сторонние библиотеки,
TokenRouter build/tests и live/paid model calls не запускались. Remote branch rules
не проверены, forge/CI system не выбраны; неизвестное не объявлено passed.
Detailed user guides отложены. Preview не обещает старый Scala compiler, durable
resume, sandbox или exactly-once внешних эффектов. External OSS demand/pilots,
engineering hours remaining и public registry ownership пока не подтверждены.
Никаких target edits, push, публикации или сообщений третьим лицам не выполнялось.

## Review and next task

Coordinator единственный writer shared plan/board; audit writer работал в своей
worktree. Остальные lanes — read-only primary-source research, target discovery,
dependency/acceptance review. Замечания reviewer о M3/M4 obligations, verifier
ownership, SDK/UI ADR dependencies, stale statuses и P11 scope устранены;
окончательный verdict `/root/plan_dependencies`: approve к commit, без блокеров.

Следующая SDK-карточка — RAI-116; затем RAI-117. Текущие LIN-13/14/15 и
RAI-113/114 продолжаются. Публичные signatures и downstream implementation
принимаются будущими карточками с отдельными evidence и review.
