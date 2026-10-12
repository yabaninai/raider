# LIN-12: work record

Status: done. Date: 2026-10-12 (UTC). Baseline commit/source: 4b46dc7 (lane/lin-12, git worktree wt/lin-12); head after work: f42fdae.

## Resulting behavior

`LineageCatalog` получает persistence (LIN-12, LIN-08 remainder): новый `CatalogStore` (modules/cli/.../lineage/CatalogStore.scala) сохраняет scan-записи каталога кампаний (run-dir, rootId/sessionId, nodeCount, graph-hash, verifyPartial-вердикт complete/tail/nodeCount, mtime, pinned-env сводка scala/jdk/raider/model) атомарно в файл `<campaignsRoot>/lineage-catalog.json` по дисциплине LineageDataStore: канонические байты → уникальный tmp-файл в целевом каталоге → ATOMIC_MOVE; per-target in-process lock; no-fsync честно задокументирован (visibility, не durability).

- **Append-модель** (намеренное отличие от `LineageDataStore.write`): append — read-modify-write upsert по ключу run-dir; конкурентные appends к одной цели **становятся в очередь** под per-path lock (отказ терял бы записи проигравшего — противоречит LINC-03 «0 потерь»); порядок чтения детерминирован (mtime DESC, path ASC — порядок каталога). Cross-process writers — вне scope, как в LineageDataStore.
- **Типизированные ошибки** (таксономия Missing/Corrupt/Io как у LineageDataStore): битый persisted-файл → `CatalogStoreError.Corrupt(path, record, detail)`, где `record` НАЗЫВАЕТ повреждённую запись (`#<index> (path: …)`) или документ целиком (`CatalogStore.DocumentLevel`); strict single-value decode (обёртка в one-element array — валидный префикс не маскирует битый хвост); NO auto-repair, файл не переписывается.
- **`CatalogStore.refresh(runsRoot)`** — scan+persist roundtrip: повреждённый контент persisted-каталога отказывает TYPED ДО публикации свежего scan (LINC-02 «scan не падает молча»); Missing — нормальный первый запуск; повреждения дерева — `RefreshError.Scan(CatalogError)`; файловые ошибки — `RefreshError.Store(CatalogStoreError)`. Каждый refresh штампует записи pinned-env сводкой `EnvPinning.currentEnv()`. Повторный refresh неизменённого дерева — байт-идентичен.
- **chat==headless**: одна форма `CatalogRecord` без дискриминатора режима; отсутствие sessionId честно опускает member (не изобретается).
- `LineageCatalog` остаётся чистым scan (правка scaladoc-указателя на CatalogStore, поведение не менялось). Каталог читается replay-CLI ПОВЕРХ: persist трогает только файл каталога, run-dir и его артефакты не изменяются (зафиксировано behavioral-фикстурой).

Публичный API-контракт: новые типы `CatalogRecord`/`TailInfo`/`PinnedSummary`/`CatalogStoreError`/`RefreshError`, методы `CatalogStore.{catalogFile,recordsOf,load,append,refresh}`. Frozen-схемы (lineage-data, events) не затронуты.

## Changed paths

- `modules/cli/src/main/scala/raider/cli/lineage/CatalogStore.scala` — НОВЫЙ: persistence каталога (549 строк).
- `modules/cli/src/main/scala/raider/cli/lineage/LineageCatalog.scala` — только scaladoc (1 строка: «Persistence is the NEXT card» → указатель на CatalogStore).
- `modules/cli/src/test/scala/raider/cli/lineage/CatalogStoreSpec.scala` — НОВЫЙ behavioral spec (803 строки, 14 тестов).

Нет изменений: modules/runtime/**, modules/dsl/** (LineageDataStore не тронут), build.sbt/project/**, scripts/**, docs/** (кроме этого untracked record), contracts/**.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| LINC-01 (normal): persist+reload roundtrip идентичен | PASS: reload == refresh; `records.map(_.toEntry) == scan` (ПОЛНАЯ структурная идентичность CatalogEntry: graph-hash, вердикты, tail, mtime); повторный refresh — байт-идентичен; pinned-env сводка переживает roundtrip | CatalogStoreSpec: «refresh persists scan records…», «repeat refresh of an unchanged tree is byte-identical» |
| LINC-01: каталог читается replay-CLI поверх | PASS: run-dir с events.jsonl + lineage-data.json — refresh не меняет ни байта в run-dir; `ReplayCli.run` → Match(hash, 3 nodes, exit 0) поверх persisted-каталога; record.complete=true, record.hash == GraphHash кампании (catalog-поверхность), replay-hash == normalized self-hash (replay-поверхность) | CatalogStoreSpec: «persisted catalog coexists with the replay CLI over the same run directory» |
| LINC-01: chat==headless формат | PASS: одна форма записи; key-set'ы записей с/без sessionId различаются ТОЛЬКО опциональным member `sessionId` (дискриминатора режима нет); отсутствие честно опущено | CatalogStoreSpec: «chat==headless: ONE record shape…» |
| LINC-02 (error/adversarial): битый persisted-каталог — typed error с указанием записи | PASS: одна битая запись среди хороших → `Corrupt(path, record≈"…work-good…", detail)`; мусор/обрыв/чужой schemaVersion/без schemaVersion → `Corrupt` уровня документа; NO auto-repair (байты повреждённого файла нетронуты) | CatalogStoreSpec: «one damaged record…», «garbage, truncated and wrong-schemaVersion…» |
| LINC-02: scan не падает молча | PASS: refresh поверх повреждённого persisted-каталога → `Left(RefreshError.Store(Corrupt(path==file,…)))`, байты файла НЕ перезаписаны свежим scan (дерево при этом валидно); повреждения дерева → `Scan(RunUnreadable/NotFound/NotADirectory)` typed, ничего не персистится; append поверх битого каталога → typed отказ, файл нетронут; absent → `Missing`, directory-at-path → `Io` | CatalogStoreSpec: «refresh over a damaged persisted catalog…», «refresh: scan damage refuses typed…», «append over a damaged catalog…», «load: absent catalog is typed Missing; a directory is typed Io» |
| LINC-03 (cancellation/concurrency): параллельные appends — atomic, 0 потерь, детерминированный порядок | PASS ×3 повтора: 8 писателей × 5 записей → ровно 40 записей, `load == Right(ожидаемый сортированный вектор)` вычислен ДО гонки; same-key от 4 писателей → ровно 1 ЦЕЛАЯ запись (ни смешения); читатели во время appends видят только целые документы (Corrupt/Io = падение теста), финальное состояние полное и упорядоченное; пустой append — no-op без создания файла | CatalogStoreSpec: «8 writers x 5 records…», «same run-dir key…», «concurrent reads during appends…», «append of an empty batch…» |
| Регрессии lineage-CLI | PASS: raider.cli.lineage.* — 127/127 | sbt-лог прогона (консоль) |
| Полная сьюта | PASS: 1045 passed / 0 failed / 0 skipped (полный `sbt --batch test` внутри гейта, 349 s) | artifacts/quality/20261012-184128-14887-0001/sbt-test.log |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git status --short` / `git log --oneline -1` (baseline check) | 0 (clean; 4b46dc7, ветка lane/lin-12) | — |
| `sbt --batch "raiderCli/testOnly raider.cli.lineage.CatalogStoreSpec"` | 0, 14/14 passed | /tmp/opencode/lin12-run9.log (консоль) |
| `sbt --batch "raiderCli/testOnly raider.cli.lineage.*"` | 0, 127/127 passed | /tmp/opencode/lin12-lineage-all.log |
| `make quality-changed` (git-mode classify) | 0; changed=3 paths → profiles contracts/static/unit | консоль |
| `make quality-changed QUALITY_EXECUTE=1` (1-й прогон) | **2 — FAILED: scalafmt-check exit=1** (форматирование); остальные 4 гейта passed; пороги не ослаблялись, формат исправлен `sbt raiderCli/scalafmtAll`, гейт повторён | artifacts/quality/20261012-182840-1661-0001/manifest.json |
| `make quality-changed QUALITY_EXECUTE=1` (повтор, после format) | 0; status=passed, 5/5 gates (core-contracts-spec, compile-fixtures, sbt-compile, scalafmt-check, sbt-test), timed_out=False везде; полный sbt-test 214 s | artifacts/quality/20261012-183606-6727-0001/manifest.json |
| `make quality-profile PROFILES="static unit contracts ci"` | 0; status=passed, 6/6 gates (включая cli-smoke), полный sbt-test 349 s, 1045/0 | artifacts/quality/20261012-184128-14887-0001/manifest.json |
| `git commit` ×2 в lane/lin-12 | 0 | 6070b8e (store+scaladoc), f42fdae (spec) |

Toolchain: JDK 21.0.12.1, sbt 1.11.4, Scala 3.9.0 pinned, ZIO 2.1.26 / zio-json 0.10.0 pinned. Bootstrap-окружение (artifacts/tools/m2) предустановлено в worktree.

## Limitations / blockers

- Порядок гейтов: финальные гейты (183606, 184128) выполнены после последней правки исходников (scalafmt) и ДО коммитов; между профилем и коммитами content не менялся — evidence относится к дереву f42fdae. Lane evidence — не замена main-гейтам на интегрированном candidate.
- Первый execute-прогон был красным по scalafmt-check (честно зафиксирован в таблице команд); после переформатирования все гейты повторены с нуля, пороги не ослаблялись.
- Cross-process конкурентные appends к одному файлу вне scope (та же граница, что у LineageDataStore/JobJournal: tmp+move сохраняет каждый наблюдаемый файл целым, last complete write wins). In-process гарантия LINC-03 — очередь под per-path lock.
- No-fsync: атомарность заказа видимости, не durability при потере питания (задокументировано в scaladoc, та же дисциплина LineageDataStore).
- `CatalogStore` не подключён к продукт-CLI (argv-поверхность каталога — отдельная карточка/ lane `raider.cli.run`); в этой карточке acceptance — store-поверхность и behavioral-фикстуры, как в LIN-08B.
- docs/lineage.md §9.2 всё ещё описывает каталог как «scan без persistence» — правка запрещена в этой карточке (forbidden paths); синхронизация §9.2 — карточка LIN-13 (по её контракту строка должна получить evidence-ссылку на этот record/file).
- Тест-фикстура events.jsonl дублирует семантику `LineageCorpus.writeJsonl` test-локально (raiderCli не имеет test→test зависимости от raiderRuntime; build.sbt заморожен для lane) — тот же паттерн, что в LineageCatalogSpec.

## Review and next task

Карточка LIN-12 закрыта в lane: все три acceptance-кейса (LINC-01/02/03) покрыты executable-фикстурами с actual IDs, гейты карточки (static/unit/contracts/ci) зелёные, frozen-схемы и чужие lanes не тронуты. Push/merge не выполнялись (запрещены) — интеграция coordinator'ом в main с последующим повтором затронутых гейтов на candidate. Следующий ready-шаг: merge lane/lin-12 + main-гейты; далее по board — LIN-13 (§9.2 sync, теперь с evidence для строки каталога), LIN-14 (LIN2-03 snapshot-покрытие), LIN-15.
