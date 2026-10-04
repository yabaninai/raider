<!-- Work record. Original in Russian; key results in English below. -->

# RAI-014.a: Bounded read/search с workspace containment — record

Дата: 2026-10-03. Writer: coordinator (root; lane-путь отменён по escalation).
Deps: от FROZEN raider-core (RaiderError) + локальные типы; raiderTools
зарегистрирован coordinator'ом в build.sbt (это же — prerequisite lane B/C).

## Deliverable

Production (2 файла):

- `modules/tools/src/main/scala/raider/tools/files/read/ReadTool.scala`
  - `Workspace.make` — корень через `toRealPath()`; containment = realpath
    внутри root (path prefix НЕ достаточен, §10);
  - `ReadTool.read(rel, offsetLine, maxLines)` — ranged read: UTF-8 текст;
    NUL-байт → declared binary failure (без dump); `maxLines` ограничен
    `HardMaxLines=50000` (превышение → typed RA-INP, без silent clamp);
    truncation declared (`truncated`, `totalLines`); пустой файл — определённый
    результат; provenance: полный SHA-256 + bytes;
  - traversal (`a/../../x` после normalize вне root) → RA-TOOLDENY; symlink
    escape (`toRealPath` наружу) → RA-TOOLDENY; внутренний symlink → разрешён;
    deleted file → typed failure (никакого fake-успеха).
- `modules/tools/src/main/scala/raider/tools/search/SearchTool.scala`
  - чистый java.nio+ZIO обход (БЕЗ shell/внешнего grep); glob-фильтр через
    PathMatcher; NUL-файлы пропускаются; bounds: `HardMaxMatches=10000`,
    `HardMaxFiles=50000`, `HardMaxScanned=20000`, 2MiB/файл, snippet ≤500 —
    всё с declared truncation; пустой запрос → определённый пустой результат
    (документировано, не ошибка).

Tests (13): `ReadToolSpec` (8) — ranged window+provenance, empty file,
traversal deny, symlink escape/inner-allow, binary declared, truncation
declared, hard-bound typed, deleted typed. `SearchToolSpec` (5) — walk-order
matches с file/line/snippet, empty query defined, glob, truncation+binary-skip,
hard-bound typed.

## Проверки (exact)

- `sbt --batch "raiderTools/test"` → exit 0, 13 passed / 0 failed.
- `make quality-changed QUALITY_EXECUTE=1` → exit 0, 6/6 gates
  (artifacts/quality/20261003-142239/manifest.json; sbt-test прогнал все
  модули: core 13+ContractsSpec, runtime 33, dsl 10, testkit 3, tools 13).

## COMPAT-заметка

Модуль использует нативный `ZIO[Any, RaiderError, *]` (не обёртку core.Task) —
это граница Tool-контракта (RAI-002.b `Tool.invoke` — ZIO), второй семантики
нет.

## Limitations (честно)

- READ-03 «cancellation останавливает долгий search» — покрыто косвенно:
  search жёстко bounded (maxFiles/maxScanned), отдельного interrupt-теста
  длинного обхода нет; obligation для RAI-016/022 (при реальном long-walk).
- Atomicity «changed file во время read» — provenance снимается ПОСЛЕ чтения
  (sha256 тех же bytes); TOCTOU-окно между stat и read не закрывается — это
  честно за пределами MVP-контракта, зафиксировано.
