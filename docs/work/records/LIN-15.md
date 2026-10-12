# LIN-15: work record

Status: done (lane; pending integration review). Date: 2026-10-12 (lane
worktree `wt/lin-15`, branch `lane/lin-15`). Baseline commit/source: `4b46dc7`
(«plan: волна 4 — регистрация LIN-13/14/15 + RAI-113/114»; сверён `git
status --short` — чисто, `git log --oneline -1` — совпадает с packet).

## Resulting behavior

Deterministic offline (scripted) acceptance LIN-06 — LIN6-01/02/03 — исполнен
как behavioral-фикстуры поверх готовой механики IsolatedRerun /
WorkspaceIsolation / RunDiffReport / PhaseReplay / ReplayHarness / Admission.
Scripted backend: единственная «модель» — запись (ReplayModelBackend, ordinal
playback); живой модели, сети и платных вызовов нет; спека маркирована
`Scripted` в имени и скаладоке, кейсы — суффикс `-SC`.

Acceptance-кейсы (новая спека `LiveRerunScriptedAcceptanceSpec`):

- **LIN6-01-SC**: isolated rerun пофазовой записи (Phase-узел «read-notes»,
  оборачивающий round-1 subtree) завершается; diff-отчёт = цельнографовый
  вердикт RunDiffReport + пофазовая секция PhaseReplay (identity, slice-hash,
  subtree-метка, по-child строки resolved/obligation/unresolved, deferred-хвост).
  Честная семантика зафиксирована ассертом: live-граф не несёт фазовой
  группировки (нет wire-кейса, docs/lineage.md §8), поэтому first-divergence —
  `missing-node` на фазовом узле (`job-1:n2`), а фазовая структура отчётается
  записанной стороной. Детерминизм: повторный rerun по свежей изолированной
  копии — отчёт байт-в-байт идентичен; оригинал нетронут (verifyUntouched).
- **LIN6-02-SC budget**: потолки `maxAttempts=1, maxRounds=1` через новые
  параметры `IsolatedRerun.rerun(limits, maxRounds)` → round-1 model call
  consumes attempt 1, dispatch fs_edit отказывается Admission'ом
  (`LocalBudgetExceeded("maxAttempts=1 exceeded …")`) ДО исполнения тула →
  `IsolatedRerun.rerun` возвращает `Left(RerunError.Replay(RaiderError.
  LocalBudgetExceeded))`; изолированная копия без dirt-файла (тул не
  исполнялся), оригинал нетронут.
- **LIN6-02-SC guard**: продуктовый путь (не тест-хелпер):
  `IsolatedRerun.mutatingTools(recorded)` извлекает mutating-имена из записанных
  RESPONSE-конвертов `tool_calls` (конвенция ReplayModelBackend — то, что цикл
  реально исполняет) через статическую таксономию ADR-027 §6.2 + из
  `ToolCallData.recovery == "Mutating"` (вердикт записи); dirty-запись даёт
  ровно `Vector("fs_edit")`, хотя аннотация записи врёт («fs_read/ReadOnly»).
  `IsolatedRerun.requireIsolatedTarget(recorded, original, target)`:
  in-place (target == original) → `Left(RerunError.MutatingUnisolated)`;
  верная изолированная копия (байт-равная snapshot оригинала, другой путь) →
  `Right(())`; чужой каталог → отказ. Guard ничего не создаёт и не исполняет:
  после отказа байты оригинала равны, parent пуст. ReadOnly-запись изоляции не
  требует (`Right(())` без проверок).
- **LIN6-03-SC**: interrupt, выданный ПОСРЕДИ rerun (форк + детерминированный
  маркер «dirt приземлился в изолированной копии» + `fiber.interrupt`):
  оригинал нетронут (verifyUntouched до и после, dirt в оригинале отсутствует
  на всём протяжении); копии остаются классифицированными/консистентными
  (dirt-файл полный «dirt», fixture-файл байт-равен исходному); повторный
  rerun ПОСЛЕ отмены — консистентный вердикт (`match=false`, тот же
  first-divergence `field-mismatch(tool)` на `job-1:n7`), отчёты rerun#2 и
  rerun#3 байт-идентичны, ровно один dirt на каждую изолированную копию —
  дублей побочных эффектов нет. Честная рамка: ZIO-интеррапт синхронной
  области наблюдается на границе (брошенный поток дорабатывает в своей
  throwaway-копии или бросается); все ассерты — на наблюдаемом состоянии
  после отмены, что и требует LIN6-03.

Продуктовые правки (минимальные typed-фиксы, каждый — acceptance-driven, с
reproducer'ом):

1. `ReplayHarness.recordedGraph` — ветка `NodeKind.Phase` + `PhaseData` →
   `SessionNode.Phase`. Reproducer: документ с phase-узлом попадал в
   `sys.error("fixture bug: node … kind Phase …")` — LIN6-01(sc) был
   невозможен. Семантика сравнения не тронута: редуктор фазы не создаёт,
   пофазовая запись честно расходится на фазовом узле (задокументировано в
   скаладоке и ассертах).
2. `ReplayHarness.replay` / `IsolatedRerun.rerun` — аддитивные параметры
   `limits: BudgetLimits = FixtureLimits`, `maxRounds: Int = FixtureMaxRounds`
   (замороженные дефолты вынесены в `ReplayHarness.FixtureLimits` /
   `FixtureMaxRounds`; поведение существующих вызовов не меняется). Reproducer:
   исчерпание бюджета через продуктовый путь rerun было невозможно — потолки
   были зашиты в `ReplayHarness.replay` (4 attempts на 2-раундовой записи не
   исчерпываются).
3. `IsolatedRerun` — LIN6-02-политика: `mutatingTools`, `requireIsolatedTarget`,
   новый кейс `RerunError.MutatingUnisolated(tools: Vector[String])`, отчёт
   rerun дополнен пофазовой секцией (`phasedSection`: PhaseReplay.replayAll по
   doc-графу + RecordedSource; для записей без фаз секция ПУСТАЯ — отчёты
   IR-01/IR-02 остаются байт-идентичными, существующие спеки не ослаблены).
   Reproducer: «mutating вне изоляции — typed отказ ДО исполнения» не имел
   продуктового пути (ReplayHarness.replay изоляционно-слеп; guard обязан
   быть у вызывающего слоя).

Публичный контрак фрозен-семейств не расширялся: RaiderError/wire-события/
schemaVersion не тронуты; новый кейс — в lane-owned (LIN-06D, packet R4)
`RerunError` внутри allowed paths; потребители (IsolatedRerunSpec IR-03 —
`case other`) не ломаются.

## Changed paths

- `modules/cli/src/main/scala/raider/cli/lineage/IsolatedRerun.scala` —
  modified (+211/−20 в сумме с форматированием)
- `modules/cli/src/main/scala/raider/cli/lineage/ReplayHarness.scala` — modified
- `modules/cli/src/test/scala/raider/cli/lineage/LiveRerunScriptedAcceptanceSpec.scala` — added

Вне allowed paths изменений нет (docs/, build.sbt, scripts/, board — не
трогались). Порты не используются; TempDir-префиксы уникальны
(`raider-lin15sc-*`); cleanup фикстур — acquireRelease + deleteRecursively.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LIN6-01(sc) — isolated rerun завершается; diff-отчёт поfazовый; детерминизм байт-в-байт | passed: `LiveRerunScriptedAcceptanceSpec` «LIN6-01-SC …» (match=false honest на фазовом узле; пофазовая секция; report#1 == report#2; verifyUntouched Right) | sbt-лог gate-прогона 20261012-183207 / 20261012-183708; локальный testOnly-прогон (15 tests passed) |
| LIN6-02(sc) — бюджет исчерпан → typed остановка (RerunError.Replay поверх Admission) до выхода за бюджет | passed: «LIN6-02-SC budget …» — `Left(RerunError.Replay(LocalBudgetExceeded("maxAttempts=1 …")))`; dirt в копиях отсутствует; оригинал нетронут | тот же прогон |
| LIN6-02(sc) — mutating вне изоляции → typed отказ ДО исполнения (продуктовый путь) | passed: «LIN6-02-SC guard …» — `mutatingTools == Vector("fs_edit")` (из RESPONSE-конверта); in-place/foreign → `MutatingUnisolated`; faithful copy → Right; ReadOnly in-place → Right; guard ничего не создаёт (parent пуст, байты равны) | тот же прогон |
| LIN6-03(sc) — interrupt посреди rerun; копия классифицирована/консистентна; повторный rerun без дублей | passed: «LIN6-03-SC …» — verifyUntouched до/после; dirt полный; fixture-файл цел; report#2 == report#3; dirt: 0 в оригинале, 1 на копию | тот же прогон |
| Флейк-дисциплина | 4 подряд зелёных прогона спеки (1 первый + 2 flake-check + 1 финальный на отформатированных исходниках), flake=0 | sbt-логи сессии lane |
| Регрессия/неослабление | raiderCli/test — 262 tests passed / 0 failed; полный `sbt test` (все модули) — 0 failed в гейт-прогоне | gate manifest 20261012-183207-3502-0001 |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `make quality-changed` | 0 (changed: 3 файла; profiles: contracts/static/unit) | stdout классификация (сессия lane) |
| `make quality-changed QUALITY_EXECUTE=1` (1-й прогон) | **1** — scalafmt-check failed (мои файлы не отформатированы) + sbt-test failed (`raider.repl.TabShellUnreadPtySpec`, PTY-флейк чужого модуля `modules/repl`, моей диффы не касаются) | artifacts/quality/20261012-181641-89313-0001/manifest.json, sha256 `52e2b04f…dc5412` |
| `sbt scalafmtAll` + `sbt scalafmtCheckAll` | 0 | — |
| `make quality-changed QUALITY_EXECUTE=1` (повтор) | 0, status=passed (core-contracts-spec / compile-fixtures / sbt-compile / scalafmt-check / sbt-test — все passed, exit=0, без timeout/skip) | artifacts/quality/20261012-183207-3502-0001/manifest.json, sha256 `a5f2aa95…02b3080` |
| `make quality-profile PROFILES="static unit contracts runtime"` | 0, status=passed | artifacts/quality/20261012-183708-7494-0001/manifest.json, sha256 `cfde3c68…7fd6ca5` |
| `sbt raiderCli/test` | 0 — 262 passed / 0 failed | sbt-лог |
| `sbt raiderCli/testOnly raider.cli.lineage.{LiveRerunScriptedAcceptanceSpec,IsolatedRerunSpec,ReplayHarnessSpec,WorkspaceIsolationSpec}` (финальные исходники) | 0 — 15 passed / 0 failed | sbt-лог |
| `git status --short` / `git log --oneline -1` перед стартом | baseline `4b46dc7`, дерево чистое | — |

## Limitations / blockers

- **В первом гейт-прогоне** `TabShellUnreadPtySpec` (RAI-105, `modules/repl`,
  PTY) упал по таймингу; модуль моей карточкой не затрагивается; повторный
  полный прогон зелёный. Зафиксировано честно (об манифест 52e2b04f…) как
  flake вне области карточки — не «passed» для того прогона, passed — повтор.
- **Phase-aware сравнение live-vs-recorded**: whole-graph вердикт пофазовой
  записи честно расходится на фазовом узле (редуктор не создаёт фаз — нет
  wire-кейса; менять компаратор/редуктор — вне allowed paths и вне non-goals
  карточки). Поfazовая секция отчёта строится записанной стороной
  (PhaseReplay). Phase-маркеры на wire остаются schema-card волны-2.
- **Дублирование статической таксономии §6.2**: `IsolatedRerun` держит копию
  exec/write-множеств (оригинал — private в `LineageReducer`, runtime-файлы вне
  allowed paths). Обе копии цитируют ADR-027 §6.2; унификация — schema-card.
- **LIN6-03 interrupt — ZIO-рамка**: интеррапт синхронной области
  наблюдается на границе; фикстура доказывает наблюдаемые инварианты состояния
  (оригинал/копии/повтор), а не остановку потока. Процессный kill -9 — вне
  unit-фикстуры.
- `requireIsolatedTarget` — guard для вызывающего слоя; продуктового CLI-входа
  live-rerun пока нет (LIN-06 live-wire — отдельная owner-карточка), поэтому
  «вызывающий слой» в acceptance — продуктовый API-уровень `IsolatedRerun`
  (что и оговорено контрактом карточки).
- Live-модель/платные вызовы: нет (scripted only). Секретов в коде/логах нет.

## Review and next task

Lane-статус: готово к ревью coordinator'ом; merge — на стороне coordinator
(push/merge lane'ом запрещены). Draft record — этот файл (перенести в root
после merge). Следующие ready-карточки по карточке-родителю: LIN-12 (каталог
persistence), LIN-14 (LIN2-03 concurrent snapshot), RAI-113/114 — на усмотрение
coordinator'а.

Product-input hash и operational record hash различаются по reviewed policy;
lane evidence относится к снапшоту lane (final acceptance — к интегрированному
candidate).
