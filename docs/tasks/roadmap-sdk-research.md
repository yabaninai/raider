# ROADMAP-SDK-RESEARCH: углубление плана после SDK-ревизии

Date: 2026-10-12. Baseline: `3a60f93`. Coordinator planning/research card.
Основание: владелец попросил максимально проработать план, провести дополнительное
исследование и задать вопросы. Предыдущие решения: OSS Raider + SDK, CI первым,
без остановки текущей волны; подробные пользовательские руководства позже.
Уточнение владельца в этой сессии: первый успех — написать Scala workflow и
запускать его локально и в CI. Встраивание в host остаётся дополнительной поверхностью.
Финальные ответы: AI-native SDLC DSL для кода ../tokenrouter; generic local CI,
forge contract/stand первым, adapter позже; automatic PR/commit/merge,
version/changelog/local tag/artifacts, без push release tag/publish/deploy;
Scala-configured human checkpoints; experimental pinned preview до M4;
API breaks с migration допустимы; no durable resume; trusted local trace data.

## Результат

Исследование первичных источников и actual code, исполнимый dependency packet,
разделение ранней обратной связи и milestone acceptance, актуализированный
roadmap. RAI-115 исполняется отдельно видимым worker в worktree; данный результат
использует его reviewed audit, не подменяя приёмку кодом/макетами.

## Allowed paths

Coordinator: `docs/research/sdk-roadmap-research-2026-10-12.md`,
`docs/roadmap-12k.md`, `docs/PLAN.md`, `docs/prompts/opencode-12k-parallel.txt`,
`docs/tasks/backlog.json`, `docs/tasks/m1.md`, `docs/tasks/README.md`, эта карточка,
`docs/work/board.md`, `docs/work/records/ROADMAP-SDK-RESEARCH.md`,
`docs/work/records/RAI-115.md`. RAI-115 worker единолично владеет
`docs/research/sdk-embedding-audit.md` в своей ветке; coordinator мержит после review.
Остальные spawn lanes read-only: alternatives и dependency/acceptance review.

## Acceptance

- Факты конкурентов имеют primary URL и дату; current docs/design/release
  различаются, отсутствие упоминания не выдаётся за отсутствие возможности.
- Исходники имеют baseline/symbol references; архитектурные предложения не
  становятся принятым API без отдельной ADR-карточки.
- Workflow local/CI — первый пользовательский путь; общие SDK cases сохраняются.
  Пакеты реализации имеют конкретный результат, prerequisites и owner границ.
- Текущая волна/гейты/лицензия не ослабляются; дополнительные часы — estimates,
  остаток unknown до reconciliation; новые решения владельца явно записаны.
- `make quality-changed`, execute и plan validator проходят на final tree;
  независимый review, work records и final receipt относятся к проверенным inputs.

## Non-goals

Продуктовая реализация, изменение frozen API/registry/verifiers, публикация,
paid calls, контакты с третьими лицами и новая монетизация. Коммит плановых
изменений продолжает ранее авторизованную работу; публичная публикация отдельно.
