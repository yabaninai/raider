# LIN-10: work record

Status: done (lane lane/lin-10, worktree wt/lin-10). Date: 2026-10-12.
Baseline commit/source: 831dabf (чистый статус на входе, сверен при старте
lane). Evidence run: quality run_id `20261012-035305-84550-0001`
(manifest sha256 `9d0292586c0624f4c69f8c7c166f69d2a8206017e778455dfbc7a6ec9cff48ee`,
fingerprint fresh — `quality-verify ... OK`).

## Resulting behavior

Standalone CLI-вход replay-цепочки (LIN-10, ADR-027 §4): команда
`ReplayCli [replay] <run-dir> --workspace <dir> [--data <file>]` в
`raider.cli.lineage`:

- читает `lineage-data.json` из каталога рана (или `--data`) через frozen
  `LineageDataStore.read` (строгая single-value декодировка);
- изолирует workspace: одноразовая копия через `WorkspaceIsolation.isolate`
  в уникальном staging-каталоге на вызов (UUID — параллельные вызовы не имеют
  общего write-target), байт-снапшот оригинала до прогона,
  `verifyUntouched` после — нарушение изоляции перекрывает любой вердикт
  typed-ошибкой;
- проигрывает запись через frozen `ReplayHarness` (recorded-only:
  `ReplayModelBackend` играет ответы из записи, модельных/платных вызовов нет);
- сравнивает графы через `GraphComparator.compareNormalized`:
  - `verdict: match` + нормализованный graph-hash + число узлов (stdout);
  - `verdict: mismatch` + детерминированный `RunDiffReport` (первый
    расходящийся узел в нормализованных id, оба hash, счётчики узлов);
- typed-ошибки (закрытый набор `ReplayCli.Failure` над frozen типами
  `StoreError` / `IsolationError` / `RaiderError` / `ReduceError`) — строка
  `typed-error: …` на stderr; схема-valid, но нетранслируемый документ
  (`recordedGraph` отказал) — typed `Record`, не дефект;
- exit-коды — только frozen ci-runtime §8 (`raider.core.result.ExitCodes`):
  0 = Match, 10 = Mismatch (TaskRejected), 21 = битый/отсутствующий data.json,
  misuse argv, отказанный shape workspace, нетранслируемый документ;
  20 = runtime/IO/reduce/replay-loop; 22/23/24 — маппинг typed `RaiderError`;
  26 = Tampered (исход подменён во время replay — вердикт недоверенный).
- собственный argv-парсер (опциональный литерал `replay`, позиционный
  `<run-dir>`, обязательный `--workspace`, опциональный `--data`); общий
  `raider.cli.run.CliArgs` и `run/**` (lane LIN-09) не тронуты — вписывание в
  продукт-команду `raider` отдельная карточка coordinator'а.

## Changed paths

- `modules/cli/src/main/scala/raider/cli/lineage/ReplayCli.scala` (новый —
  entry, парсер, пайплайн, рендер, exit-коды);
- `modules/cli/src/test/scala/raider/cli/lineage/ReplayCliSpec.scala` (новый —
  7 behavioral-тестов LIN10-01/02/03 + parse/exitCode);
- `docs/lineage.md` — §9.1 (+строка LIN-10), §9.2 (честный статус standalone
  входа), §10 (фактическая команда, формат вывода, exit-коды, пример).

Публичный API/схемы lineage не менялись: ReplayHarness, ReplayModelBackend,
RecordedSource, GraphComparator/ReplayVerifier, WorkspaceIsolation,
lineage-data.schema.json — потреблены как есть (frozen).

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LIN10-01 (Match + graph-hash) | passed: replay документа из run-каталога → Match, hash совпал с `compareNormalized(recorded, recorded)`, exit 0 | `ReplayCliSpec` «LIN10-01: … Match» в `sbt-test.log` (manifest 20261012-035305-84550-0001, стр. ~679); процессный smoke 1: `verdict: match`, `graph-hash: be568cfb…de59`, exit 0 |
| LIN10-01 (Mismatch + первый расходящийся узел) | passed: мутация round-1 ответа → Mismatch, первый divergence = `job-1:n7` (missing-node, нормализованный id), hashes различны, exit 10 | `ReplayCliSpec` «LIN10-01: … Mismatch»; процессный smoke 2: `first-divergence: missing-node`, `path: root -> job-1:n7`, exit 10 |
| LIN10-02 (битый/отсутствующий data.json) | passed: `StoreError.Missing` / `StoreError.Corrupt` → typed-error, exit 21 (nonzero) | `ReplayCliSpec` «LIN10-02: missing/corrupt …»; процессные smoke 3/5 (exit 21, `typed-error: lineage-data …`) |
| LIN10-03 (параллельные replay, verifyUntouched) | passed: два параллельных replay одного рана над одним оригиналом — оба Match, оба прошли внутренний verifyUntouched, тестовый verify оригинала против pre-snapshot Right | `ReplayCliSpec` «LIN10-03: parallel replays …»; процессный smoke 6: два concurrent `java`-процесса, оба exit 0, один hash, `ws/notes.txt` байт-цел |
| Gates (static/unit/contracts/docs) | passed, verify OK (fingerprint fresh) | `artifacts/quality/20261012-035305-84550-0001/manifest.json` |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `make quality-changed` | 0 (classification: contracts/docs/static/unit) | stdout-JSON, этом же дереве |
| `make quality-changed QUALITY_EXECUTE=1` | 0 (passed) | `artifacts/quality/20261012-035305-84550-0001/manifest.json` |
| `make quality-verify MANIFEST=artifacts/quality/20261012-035305-84550-0001/manifest.json PROFILES=static,unit,contracts,docs` | 0 — «satisfies … (policy/toolchain/logs hashes checked, fingerprint fresh)» | verify-вывод |
| `sbt 'raiderCli/testOnly raider.cli.lineage.ReplayCliSpec'` | 0 — 7 tests passed, 0 failed | до gate-прогона; финальные тесты — в `sbt-test.log` гейта |
| `java -cp <cp> raider.cli.lineage.ReplayCli <run-1> --workspace <ws>` (smoke Match) | 0 | stdout: verdict/graph-hash/nodes |
| `… ReplayCli <run-2> --workspace <ws>` (smoke Mismatch) | 10 | stdout: RunDiffReport (job-1:n7) |
| `… ReplayCli <run-missing> --workspace <ws>` | 21 | stderr: typed StoreError.Missing |
| `… ReplayCli <run-corrupt> --workspace <ws>` | 21 | stderr: typed StoreError.Corrupt |
| `… ReplayCli` (без аргументов) | 21 | stderr: usage |
| `… ReplayCli <run-1> --workspace <ws>` × 2 параллельно | 0, 0 | оба Match, оригинал нетронут |

Замечание к evidence: промежуточный гейт-прогон 20261012-034923-76295-0001 —
failed (scalafmt-check: файлы не отформатированы; compile-fixtures: отсутствовал
pre-existing `artifacts/tools/m2` в worktree — докопирован по
parallel-development §1a, untracked). После форматирования — финальный passed
прогон 20261012-035305-84550-0001; исходники после него не менялись.

## Limitations / blockers

- ReplayHarness — frozen fixture-campaign контракт: пользовательское
  сообщение, maxRounds и emission-seq layout зафиксированы (см. scaladoc
  `ReplayHarness`). Replay произвольного рана честно матчится только при
  записи через тот же loop-layout; иная форма расходится LOUDLY на первом
  поле (компаратор работает как задуман). Генерализация входа — не в карточке
  (non-goal: изменение ReplayHarness-контракта).
- Продуктовая команда `raider` не расширялась: standalone-вход
  (`java -cp … raider.cli.lineage.ReplayCli`), вписывание флагов в общий CLI —
  отдельная карточка coordinator'а (`run/**` — lane LIN-09).
- `-cp` для смоуков — `sbt 'export raiderCli/Compile/fullClasspath'`; packaged
  jar/assembly для replay-команды не собирался (не требуется карточкой).
- Live-модель за replay (LIN-06), каталог кампаний (LIN-08 persistence) — вне
  scope.

## Review and next task

- Reviewer: назначается coordinator'ом (lane-отчёт = финальное сообщение
  субагента; независимый review — по процессу §1a/§1b).
- Следующие ready-шаги по карточкам P-LIN: вписывание replay-команды в общий
  CLI после LIN-09 (координация с `run/**`), каталог кампаний LIN-08
  (scan+persist поверх вердиктов ReplayCli), partial-replay поверхность
  (LIN-07) поверх той же командной механики.
