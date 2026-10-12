# LIN-13: work record — docs/lineage.md §9.2 sync (честность статусов реализации)

Status: done. Date: 2026-10-12. Baseline commit: `4b46dc7` (ветка `lane/lin-13`,
чистый worktree на старте; lane-коммит `ebc9e99`).

## Resulting behavior

§9.2 «Чего ещё нет» в `docs/lineage.md` приведён в соответствие
интегрированному состоянию на baseline `4b46dc7`; каждая строка таблицы несёт
evidence-ссылку (record path или file:line), верифицированную скриптом на
baseline (17/17 OK):

- **TabShell-wiring в product REPL — ЗАКРЫТО (RAI-108).** Продуктовый вход
  `--tabs` поднимает TabShell: флаг и диспетчер `runTabs` в `main`
  (`modules/repl/src/main/scala/raider/repl/Main.scala:230-242,280-288`),
  `TabShellHost.wiring`/`TabShellHost.run`
  (`modules/repl/src/main/scala/raider/repl/TabShellHost.scala:31`); legacy-цикл
  без флага не изменён; PTY-acceptance P2E-01..03 —
  `docs/work/records/RAI-108.md`. Устаревшее упоминание
  `modules/cli/chat` убрано.
- **Запись артефактов в каталог рана — ЗАКРЫТО (LIN-09).** Settled-ран пишет
  `lineage.scala` + `lineage-data.json` в свой каталог рана рядом с
  `events.jsonl`/`summary.md`
  (`modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala:665-677`;
  писатель `RunLineageArtifacts` —
  `modules/cli/src/main/scala/raider/cli/run/RunLineageArtifacts.scala:52-55`);
  сбой — typed `unavailable`-строка, exit-код не меняется —
  `docs/work/records/LIN-09.md`.
- **`raider replay` (RAI-109) — сверено, закрыто.** Факт уже был в тексте;
  добавлены evidence: `ReplayCommand.scala:37` (делегирование `ReplayCli.exec`),
  `docs/work/records/RAI-109.md`.
- **Открытые пункты остались честно открытыми** (DOC-13-02):
  LIN-06 live re-run (карточка LIN-06 в `docs/tasks/lineage.md`, status: todo;
  механика — `WorkspaceIsolation`, `Admission.make` —
  `ReplayHarness.scala:298`; live-провайдера нет —
  `ReplayModelBackend.scala:75`); live-wire verification (owner, не
  проводилась; проверяемый факт отсутствия live-провайдера — та же строка
  кода); multi-job export (owner `cli/run/**`, ADR-027 §6.4;
  per-job evidence — `EventExport.scala:21`); phase/recovery schema-card
  (wire frozen 13 cases —
  `modules/runtime/src/main/scala/raider/runtime/events/events.scala:26`);
  LIN2-03 частично — **in-flight (карточка LIN-14)**; `LineageCatalog` без
  persistence (`LineageCatalog.scala:87`) — **in-flight (карточка LIN-12)**.
  Ничего не заявлено закрытым сверх интегрированного состояния.
- **Заключительный абзац §9.2:** из перечня открытых milestone-обязательств
  убран «product wiring» (закрыт RAI-108), зафиксированы закрытия RAI-108/LIN-09.
- **§10 (сопутствующая согласованность того же файла):** строка
  `artifacts/<run>/lineage.*` («обязательство LIN-03/08») переписана на
  фактическую запись LIN-09 в `<out>/work-<uuid>/`; комментарий примера
  «(после P-LIN)» заменён на фактическое поведение. Основание — те же факты
  LIN-09 (`HeadlessRunner.scala:665-677`, record LIN-09.md); без этой правки
  §10 прямо противоречил бы обновлённому §9.2 внутри одного файла. Выходит за
  букву «§9.2», в пределах allowed path `docs/lineage.md` и цели карточки
  (честность статусов); помечено здесь для review.

## Changed paths

- `docs/lineage.md` (+14/−12): §9.2 таблица (7 строк), заключительный абзац
  §9.2, §10 строка lineage-артефактов, комментарий примера §10.
- `docs/work/records/LIN-13.md` — этот record (draft в worktree; operational
  файл вне allowed paths карточки, создаётся по обязательному требованию
  work record и доставляется coordinator'ом).

## Acceptance evidence

| Criterion | Actual result | Evidence |
| --- | --- | --- |
| DOC-13-01: каждая строка §9.2 несёт evidence-ссылку, проверяемую на baseline | 17/17 ссылок OK (file:line grep-проверка на `4b46dc7`) | вывод проверки в lane-отчёте; ссылки в самом diff |
| DOC-13-02: ни один in-flight/owner-gated пункт не помечен закрытым | LIN-06/live-wire/multi-job/schema-card — открыты; LIN2-03 и catalog persistence — помечены in-flight с карточками LIN-14/LIN-12 | diff §9.2 (`git show ebc9e99`) |
| DOC-13-03: docs-профиль зелёный, якоря/ссылки валидны, diff только в docs/lineage.md | quality passed, profiles=['docs'], mode=git-status (изменён только `docs/lineage.md`) | manifests ниже |

## Commands

| command | Exit | Log / manifest |
| --- | --- | --- |
| `make quality-changed` | 0 | changed=[docs/lineage.md], profiles=[docs] |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | `artifacts/quality/20261012-175654-72619-0001/manifest.json` — status=passed, plan-validate passed |
| `make quality-profile PROFILES="docs"` | 0 | `artifacts/quality/20261012-175659-72726-0001/manifest.json` — status=passed, plan-validate passed |

## Limitations / blockers

- Правки §10 (2 места) — сопутствующая согласованность сверх буквального
  «§9.2»; reviewer может откатить только их, §9.2 самодостаточен.
- file:line-ссылки на код валидны на baseline `4b46dc7`; сдвиг строк возможен
  после merge параллельных lane — при интеграции сверить ссылки (не блокер:
  record-ссылки стабильны).
- Номера строк `docs/tasks/lineage.md` не использовались как evidence (только
  заголовки карточек) — устойчиво к сдвигам.

## Review and next task

Review не проводился (lane-воркер; независимый review — на coordinator).
Следующие ready-карточки lineage-трека: LIN-14 (LIN2-03 coverage), LIN-12
(catalog persistence), LIN-06 (live re-run scripted acceptance — RAI-113/114
по board волны 4).
