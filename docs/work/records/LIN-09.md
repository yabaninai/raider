# LIN-09: work record

Status: done (с одним зафиксированным frozen-layer gap — см. Limitations). Date: 2026-10-12. Baseline commit: 831dabf (ветка lane/lin-09, чистый worktree).

## Resulting behavior

Settled headless-ран теперь пишет два lineage-артефакта в СВОЙ каталог рана
(`work-<uuid>/`, рядом с events.jsonl/summary.md):

- `lineage-data.json` — frozen schemaVersion-1 документ (ADR-027 §3),
  собранный ТОЛЬКО из редуцированного графа рана и его же экспортированных
  envelopes: узлы графа verbatim (id/parent/inputs/seq/atMs/dataRef), data-ресурс
  связывает wire-резюме (ToolStarted.argsSummary, ModelCallFinished usage,
  outcome, recovery-класс). Пишется через принятый atomic store
  `LineageDataStore` (tmp+ATOMIC_MOVE, single-writer на путь).
- `lineage.scala` — детерминированная проекция через frozen-пайплайн
  `SessionGraphView` → `LineageRenderer`; пишется тем же atomic-паттерном
  (tmp+ATOMIC_MOVE). Рендер выводится VERBATIM — писатель не редактирует
  вывод рендерера.

Свойства:

- Изоляция параллельных ранов структурная (LIN9-03): у каждого рана свой
  `work-<uuid>`, пересечений путей нет; tmp-файлы уникальны в каталоге рана и
  не остаются после записи.
- Сбой проекции/записи — typed `ArtifactError` (View/Render/Store/Io),
  рендерится одной строкой `- lineage artifacts: unavailable (<typed>)` в
  summary.md и НИКОГДА не меняет exit-код рана (LIN9-02). Писатель тотален
  (NonFatal → Io), новых crash-путей в writeArtifacts нет.
- Артефакты пишутся ТОЛЬКО при reducible-кампании (`Some(Right(graph))`):
  preflight-отказы и нередуцируемые кампании не пишут ничего и не добавляют
  строку (byte-совместимость summary с pre-LIN-03B формой сохранена).
- На success: `- lineage artifacts: lineage.scala, lineage-data.json (schemaVersion 1)`.
- Честность содержимого: frozen headless-wire (ADR-024 §5) не экспортирует
  тексты prompt/response и хвосты результатов — `ModelCallData.prompt/response`
  пишутся пустыми строками, `resultTail` — null (единственная schema-легальная
  форма «не зафиксировано»); wire-резюме (argsSummary/usage/outcome/recovery)
  записываются как есть. Usage из реального ledger (unknown не коэрсится в 0).
- Delegation-узлы без dataRef (wave-1 модель, contract SessionGraphView) —
  без data-записи; Phase-графы отвергаются typed (у wave-1 Phase нет atMs,
  headless-редуктор Phase не создаёт — ветвь defensive).

Ничего из frozen-слоёв (contracts schema, LineageProjection, LineageDataStore,
события, run.json/events.jsonl/exit-коды 18 семейств) не изменено. CliArgs не
тронут (LIN-10).

## Changed paths

- `modules/cli/src/main/scala/raider/cli/run/RunLineageArtifacts.scala` (новый)
- `modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala` (точка
  записи в writeArtifacts; envelopes проброшены в деливерабл executeAsJob;
  scaladoc LIN-09)
- `modules/cli/src/test/scala/raider/cli/run/LineageArtifactsSpec.scala` (новый)

Публичный API/schema-мены: нет. Новые зависимости: нет.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LIN9-01 (валидные артефакты, frozen schema) | pass | `LineageArtifactsSpec`: документ декодируется frozen-стором (`LineageDataStore.read`) и валидатором (`LineageDataFile.read`: schemaVersion 1 + ref integrity); canonical generator-блок; canonical ключи modelCallData; explicit-null parentId; projection == точный вывод `SessionGraphView`→`LineageRenderer` по СВОЕМУ events.jsonl (checkpoint-консистентность); pinned dotc 3.9.0 собирает ПРОДУЦИРОВАННЫЙ файл (delegation-граф, exit 0) |
| LIN9-02 (сбой проекции → typed диагностика, штатный exit) | pass | bundle-ран (nodeless-кампания) → `- lineage artifacts: unavailable (Render(Empty))` в summary.md, exit 0, файлы не созданы; подтверждено РЕАЛЬНЫМ cli-smoke раном (`work-4fac0caf/summary.md`, packaged-процесс) |
| LIN9-03 (параллельные раны изолированы) | pass | 6 параллельных mock-ранов в один `--out`: свой workDir/артефакты у каждого, документ==свой checkpoint-рендер, 0 tmp-остатков, все exit 0 |
| Regression: preflight-отказ | pass | без lineage-строк и артефактов, exit 21 (byte-совместимость) |

Дополнительная сверка вне тестовой JVM: реальный `sbt raiderCli/run run --agent
scout --input task-42 --mock` → work-4c1ed495: lineage-data.json
(schemaVersion 1, generator raider/0.1.0-SNAPSHOT/3.9.0/jdk 27, canonical
ключи, usage {reserved 0, observed 0, unknown: true} — honest), lineage.scala
`object lineage_cli_637a41be` + `// model("builtin:scout", round = 1) // data:
j_1/round-1`.

sha256 исходников (evidence-fingerprint):

- HeadlessRunner.scala `526bb1421a40534ba05fbbd9bcf805b3bcbb228cdb84f7e28c3414790356e5e0`
- RunLineageArtifacts.scala `c514d5535ce7e63adb27a6620138fe812b8647c0004d0f663e0f45db1bc18630`
- LineageArtifactsSpec.scala `ca1332411094c2c5062c7cf3b0001501c4a86b29e2643ad45a021875bf69087c`

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `make quality-changed` (git-mode классификация) | 0 | profiles = contracts, static, unit (3 changed files) |
| `make quality-changed QUALITY_EXECUTE=1` | 0 (passed) | `artifacts/quality/20261012-035653-90012-0001/manifest.json` sha256 `b89d8ee5…013ab5`; все 5 гейтов passed |
| `make quality-profile PROFILES="static,unit,contracts,ci"` | 0 (passed) | `artifacts/quality/20261012-035813-91206-0001/manifest.json` sha256 `2dc1cdb9…f51cd`; sbt-test/cli-smoke/core-contracts-spec/compile-fixtures/sbt-compile/scalafmt-check — все passed |
| `make quality-verify MANIFEST=artifacts/quality/20261012-035813-91206-0001/manifest.json PROFILES=static,unit,contracts,ci` | 0 | `[verify] OK … (policy/toolchain/logs hashes checked, fingerprint fresh)` |
| `sbt raiderCli/testOnly raider.cli.run.LineageArtifactsSpec` | 0 | 6 tests passed, 0 failed |
| `sh scripts/quality/cli_smoke.sh` | 0 | реальный ран: typed `unavailable (Render(Empty))`, exit 0 |

Примечание: в worktree на момент старта отсутствовал untracked
`artifacts/tools/m2` (pinned dotc) — скопирован из root-checkout по
docs/parallel-development.md §1a (runbook-подготовка lane, запись только в
свой worktree). Без него compile-fixtures/compile-gate-тесты честно падали бы
unavailable. Также сброшен унаследованный stale `modules/{core,runtime}/target`
(фантомная ошибка компиляции `ToolCall.id` — стейл-classfiles, не sources).

## Limitations / blockers

1. **Frozen-renderer gap (contract-change request, вне границ карточки).**
   `LineageRenderer` (dsl, frozen) для графов без named-def корней (только
   ModelCall/ToolCall) рендерит тело объекта ИЗ ОДНИХ комментариев — dotc 3.9.0
   отвергает такое тело («indented definitions expected, eof found»),
   воспроизведено напрямую. Обычный headless-ран (scout/coder) даёт ровно такой
   граф: Phase редуктор не создаёт (frozen wire), Delegation-тулсет в headless
   не подключён. Поэтому `lineage.scala` ОБЫЧНОГО продового рана существует,
   валиден как проекция (точный вывод frozen-пайплайна, который compile-gate
   компилирует на goldens), но dotc его НЕ соберёт, пока рендерер не начнёт
   закрывать comment-only тело (например, `()` — проверено: закомментированное
   тело + `()` компилируется, exit 0). Правка принадлежит dsl-владельцу;
   предлагаемая семантика: рендереру эмитить `()` для внешней области при
   отсутствии named-def statements (однострочное изменение frozen-слоя, отдельный review). Карточное acceptance LIN9-01 «compile-gate зелёный на
   артефактах» доказано на produced-артефакте для delegation-формы (реальный
   dotc, exit 0); для plain-ранов — заблокировано этим gap'ом.
2. lineage-data.json plain-ранов несёт пустые prompt/response и null
   resultTail — это честная форма «wire не экспортирует тексты», не дефект
   записи. Полные payload'ы требуют wire-расширения (schema-card), не карточки.
3. jdkVersion генератора берётся из JVM рана (`java.specification.version`) —
   валиден схемой (`^[0-9]+$`), но меняется между окружениями (21/27
   наблюдались) — ожидаемое поведение pinned-environment поля.
4. `modules/cli/target/demo-bundle.jar` для cli_smoke создаёт
   HeadlessRunnerSpec (existing); мой спек собирает свой jar во временном
   каталоге без побочной записи в target.

## Review and next task

Lane-вердикт: готово к review координатором; merge — только координатором.
Contract-change request по п.1 ограничений — на решение владельца (dsl owner).
Следующие готовые карточки по board: LIN-10 (CLI replay-поверхность,
потребляет артефакты этой карточки), остатки P2/P1/P3 по ревизии roadmap
2026-10-12.
