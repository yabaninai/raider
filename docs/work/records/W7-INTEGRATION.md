# W7-INTEGRATION: интеграция волны-1 P-LIN (work record)

Status: done. Date: 2026-10-10 (UTC morning). Coordinator: ночной opencode
session (продолжение W7-PARALLEL). Baseline: root dirty tree после W6
(board-accepted), snapshot 4075 файлов (`raider-night/baseline-manifest.sha256.txt`).

## Resulting behavior

Интегрированы 8 результатов волны-1 (10 lanes: 7 done, 1 blocked→ре-скоп,
1 умер без изменений → ре-диспатч, 1 review):

1. **LIN-01A** (L1): `raider.runtime.lineage.model` — SessionGraph (sealed
   SessionNode + 4 kinds, validated/childrenOf/roots, duplicates/parent/dataflow
   проверки), GraphHash (SHA-256 канонической формы, siblings нормализованы);
   11 тестов.
2. **LIN-01B** (L2): `raider.runtime.lineage.io.LineageEventReader` — чтение
   events.jsonl, ParsedEvents/TruncatedTail/LineageError.
3. **LIN-02A** (L3): `contracts/lineage/**` — lineage-data.schema.json
   (schemaVersion=1), golden tiny-graph/deep-tree, README.
4. **LIN-04A** (L4): ui `:aux` — AuxTab/aux-провайдер, CommandRouter/ShellCommand/
   ShellExecutor/ShellFeedback/TabBarRenderer/TabShell; registry не затрагивается;
   AuxTabSpec.
5. **LIN-05A** (L6, ре-скоп в runtime test-scope): LineageCorpus —
   детерминированный генератор кампаний (campaign(seed), corruptedTail,
   withSeqGap, wrongSchemaVersion) + spec. Первая попытка в testkit была
   честно blocked (testkit↛runtime, build.sbt frozen) — coordinator ре-скопил
   в runtime test-scope, код доставлен повторным запуском.
6. **LIN-02B** (L7): `raider.dsl.lineage.data.LineageData` — типизированная
   модель + zio-json кодеки LineageDataDocument.
7. **LIN-03A-prep** (L10): HeadlessRunner стримит events.jsonl во время рана
   (EventsJsonlWriter, append+flush per line), старт-переход — первая строка,
   Terminal последняя; EventsJsonlWriterSpec + HeadlessRunnerCrashSpec.
8. **LIN-08A** (L5): `docs/lineage.md` — пользовательский spec session lineage.

L9 (review): baseline ACCEPT, contracts CHANGES-REQUIRED → ADR-027 §6
addendum (phase/recovery — schema-card волны-2; multi-job export — cli wave-2;
recording store — slot-0 волны-2). L8 (LIN-02C renderer): агент умер до работы,
0 изменений — ре-диспатч в W8 (L12).

Метод: побайтовая сверка каждого lane-артефакта с источником (cmp/diff -r),
secrets-скан (чисто), проверка allowed-paths по диффам, последовательное
копирование, полные root-гейты после интеграции.

## Commands / exits

| Command | Exit | Evidence |
| --- | --- | --- |
| `make quality-changed` (после L1+L5) | 0 | artifacts/quality/20261010-060836-57784-0001 |
| `make quality-changed QUALITY_EXECUTE=1` (после L1+L5) | 0 | 7/7 passed, manifest 20261010-060836-57784-0001 |
| `make quality-changed` (полная интеграция 8 lanes) | 0 | artifacts/quality/20261010-124556-37841-0001 |
| `make quality-changed QUALITY_EXECUTE=1` (полная интеграция) | 0 | 7/7 passed (core-contracts-spec, compile-fixtures, plan-validate, self-registry-check, sbt-compile, scalafmt-check, sbt-test), manifest 20261010-124556-37841-0001 |
| `sbt --batch scalafmtAll` | 0 | reformatted 5 новых файлов |

Инцидент интеграции: ошибочный `cp -R lane-03/contracts/lineage/ contracts/`
положил файлы уровнем выше; исправлено перемещением в `contracts/lineage/`,
сверка `diff -r` с lane-03 — IDENTICAL; tracked-файлы contracts/ не пострадали
(git status: только ?? additions).

## Limitations / blockers

- Lane L2/L3/L4 завершились без RECORD.md (процессы умерли после записи кода);
  их evidence — lane test-reports и полный root `sbt-test` 7/7. Независимое
  ревью интегрированного дерева назначено (W8 L14).
- `contracts/README.md`/`contracts/golden/` уровня root не существовало
  (проверено `git ls-tree HEAD`) — потерь tracked-контента нет.
- LIN-02C (renderer) не реализован — ре-диспатч L12; view-adapter и LIN-03B
  ждут его/reducer (wave W8+).

## Next

W8 запущена: L11 (LIN-01C reducer), L12 (LIN-02C), L13 (LIN-02D store),
L14 (wave-1 review), L15 (RAI-072 docs), L16 (RAI-080 onboarding) —
`raider-night/W7-registry.md` §Wave W8.
