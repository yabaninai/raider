# LIN-14: work record — LIN2-03 remainder: конкурентное чтение проекции — snapshot-покрытие

Status: done. Date: 2026-10-12T15:23:48Z. Baseline commit/source: `4b46dc7` (branch `lane/lin-14`, worktree `wt/lin-14`, status чистый на старте).

## Resulting behavior

Тестовое покрытие LIN2-03 закрыто одним новым fixture-файлом `LineageSnapshotRaceSpec` (ZIO Test, dsl test area):

- **LIN2-03-01** — property «конкурентное чтение — снапшот»: 100 детерминированных seed-эпизодов; в каждом — прерайт `oldDoc`, затем 4 reader-волокна (8 фиксированных чтений + ограниченный spin до завершения писателя, ≤64) конкурентно с одним писателем (24 чередующиеся целые записи `newDoc`/`oldDoc` через `LineageDataStore.write` — atomic tmp+ATOMIC_MOVE, single-writer). Инвариант каждого чтения: `Right(doc)` с ПОЛНЫМ равенством `oldDoc` либо `newDoc`, `schemaVersion == 1`, все `dataRef` разрешаются; ЛЮБОЙ `StoreError.Missing/Corrupt/Io` — типизированная violation. ≥3200 наблюдаемых чтений за прогон (≫ требуемых 100 итераций). Liveness-пин: обе генерации документа реально наблюдались целыми по всей кампании.
- **LIN2-03-02** — детерминизм/типизация: идентичный seed-эпизод повторён 3× внутри спеки — все зелёные; каждая violation печатается типизированно (case StoreError + store detail) → вскрытый дефект стал бы reproducer'ом, ассерты не ослаблены. Gate-уровень: 3 полных прогона `make quality-changed QUALITY_EXECUTE=1` — зелёные (flake=0, см. Commands).
- **LIN2-03-03** — байт-идентичность проекции: конкурентные read→view→`LineageRenderer.render` раунды (6 волокон × 12 раундов) по НЕизменному документу дают байт-идентичные исходники; последовательный re-read ×2 / re-render ×2 байт-идентичны; канонические байты на диске == `doc.toJson`; пин нетривиальности — old/new документы проецируются в байт-РАЗНЫЕ исходники.

Детерминизм: документы (payload/структура) генерируются локальным LCG с фиксированными seed (20261012L-серия); недетерминирован только интерливинг — ассерты сформулированы как инварианты любого интерливинга. Temp-файлы только под `modules/dsl/target` (packet rule). Продуктовый код не менялся.

## Changed paths

- `modules/dsl/src/test/scala/raider/dsl/lineage/store/LineageSnapshotRaceSpec.scala` — НОВЫЙ (единственный файл; 5 тестов). Только тесты, внутри allowed paths. CLI-тестовая область не затронута: тестируемая проекция (`LineageProjection`/`LineageRenderer`) живёт в dsl; public API/schema — без изменений.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LIN2-03-01 (≥100 итераций concurrent read/write, 0 частичных чтений) | passed: 100 эпизодов, ≥3200 чтений, 0 violations, 0 маскировок Missing/Corrupt/Io, fromOld>0 и fromNew>0 | manifest `artifacts/quality/20261012-181720-91844-0001/manifest.json` (sha256 d9bd538a…956702), sbt-test.log строки 260–264 («100 seeded episodes …» зелёный) |
| LIN2-03-02 (3 повтора зелёные; дефект — typed reproducer) | passed: in-spec 3×3 повторов зелёные; gate-уровень: 3 прогона quality-changed execute — exit 0 каждый; flake лин-спеки = 0 | manifests 20261012-180413-74141-0001 / 20261012-180717-75634-0001 / 20261012-180959-77440-0001 (passed) + sbt-test.log строка 265–267 |
| LIN2-03-03 (байт-идентичность проекции; новые файлы только в allowed paths) | passed: 72 конкурентных render-раунда идентичны; sequential ×2 идентичны; old/new проекции различны; изменён/добавлен 1 файл в allowed paths | тот же manifest 20261012-181720-91844-0001, sbt-test.log строки 261–264; `git status --short` — только spec-файл |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `sbt --batch "raiderDsl/testOnly raider.dsl.lineage.store.LineageSnapshotRaceSpec"` | 0 (5 tests passed, 0 failed, 782 ms) | вывод сессии; спека зелёная на точечном прогоне |
| `raiderDsl/scalafmtOnly src/test/scala/raider/dsl/lineage/store/LineageSnapshotRaceSpec.scala` | 0 (Reformatted 1 source) | — |
| `make quality-changed` | 0 (classify: 1 changed file → profiles contracts/static/unit) | вывод сессии |
| `make quality-changed QUALITY_EXECUTE=1` (повтор 1/3) | 0 — все гейты passed | manifest 20261012-180413-74141-0001, sha256 968bf74224df24d1210278245647eab9927a3f17ea78161ba218d861c6444753 |
| `make quality-changed QUALITY_EXECUTE=1` (повтор 2/3) | 0 | manifest 20261012-180717-75634-0001, sha256 c70f6f9c7d7534d4a8dc69d141094ef1ae0c6d139d6c93a1b984154320ef0e58 |
| `make quality-changed QUALITY_EXECUTE=1` (повтор 3/3) | 0 | manifest 20261012-180959-77440-0001, sha256 eb0ccba46dd2923ee1b8f00ab476bd05453f904b97f0c591881025af8a4afdac |
| `make quality-profile PROFILES="static unit contracts"` (прогон 1) | 2 — sbt-test failed: ЧУЖОЙ flake `raider.repl.PlanPanelSpec` (RAI-110, repl-модуль, вне allowed paths LIN-14) | manifest 20261012-181314-79709-0001 (sha256 8e0c69e8…72238d), sbt-test.log: «:reject cancels the LIVE plan…» — ready2=false, labels List("plan","plan") без "plan-exec" |
| `sbt --batch "raiderRepl/testOnly raider.repl.PlanPanelSpec"` (диагностика flake) | 0 (8 tests passed) | вывод сессии — flake не воспроизводится точечно |
| `make quality-profile PROFILES="static unit contracts"` (прогон 2) | 0 — все гейты passed | manifest 20261012-181720-91844-0001, sha256 d9bd538ad9357890de4fa579b83f7ffcc08ace287ab4ce231b619bffbd956702 |
| `make quality-changed QUALITY_EXECUTE=1` (финальный, на закоммиченном дереве 2fbcd2d; без uncommitted-изменений → full-tree fast-minimum) | 2 — sbt-test passed (полный suite, LIN2-03 спека зелёная на финальном дереве), plan-validate/core-contracts/compile-fixtures/forbidden-apis/license-audit passed; scalafix-check failed на ЧУЖОМ предсуществующем файле (см. Limitations) | manifest 20261012-182518-98496-0001, scalafix-check.log: `PlanTabsPtyApp.scala:173:12: '<-' expected but '=' found` |

Спека-файл sha256: `adddefcf8edb417791fd777be397addaa50dbfc861c41f5f30f2353f0785c745`. Весь sbt-test (полный `sbt --batch test`) исполнил LIN2-03 спеку ≥5 раз за сессию — все зелёные.

## Limitations / blockers

- **External flake (не LIN-14, для coordinator/reviewer):** `PlanPanelSpec` (RAI-110, `modules/repl/src/test/scala/raider/repl/PlanPanelSpec.scala:259-261`, кейс «:reject cancels the LIVE plan; approve-after-cancel is typed Cancelled; a repeated plan reaches approval») упал 1 раз из 4 полных прогонов full-suite; подпись: после reject повторный plan не дошёл до approval/exec (labels без "plan-exec"), ready2/approved2=false. Точечный повтор — 8/8 зелёный. Вне allowed paths LIN-14 — не чинится в этой карточке; кандидат во flake-карточку по RAI-110/план-tab lifecycle (вероятна гонка retry-цикла в тесте).
- **Baseline scalafix-дефект полной проверке дерева (не LIN-14, для coordinator):** full-tree fast-minimum на HEAD `2fbcd2d` (равно как и на baseline `4b46dc7`) падает на `scalafix-check`: `modules/repl/src/test/scala/raider/repl/PlanTabsPtyApp.scala:173` — parse error (`for snap = panel.snapshot`, файл из чужого lane-коммита `e1a8c9f`, RAI-110). Файл вне allowed paths LIN-14 — не чинится здесь; все change-scoped прогоны обязательных гейтов карточки (static/unit/contracts) зелёные, sbt-test full-suite на финальном дереве зелёный. Fix — отдельная карточка/point-fix владельцу repl-области (например `snap <- panel.snapshot` или val-вынесение, на усмотрение review).
- Демон детерминизма: интерливинг волокон не детерминирован по определению гонки; детерминированы seed'ы/документы и инвариантные ассерты — это соответствует контракту карточки («детерминированный seed»).
- Спека пиннит atomicity/whole-document visibility, НЕ durability (no-fsync контракт store не менялся) и НЕ multi-writer семантику (harness использует ровно одного писателя на target — по writer-модели store; single-writer refusal уже покрыт LIN2-D-03).
- Реальные платные вызовы: отсутствуют; сеть/порты не использовались; temp только под target/.

## Review and next task

Lane-отчёт передан coordinator'у: merge-интеграция — на его стороне (push/merge запрещены lane). Следующая доступная карточка — по board (например LIN-15 scripted acceptance, зависимости LIN-06; или flake-карточка на PlanPanelSpec по факту этого наблюдения).
