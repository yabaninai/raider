# LIN-11: work record

Status: done. Date: 2026-10-12 (UTC). Baseline commit/source: 71278c1 (lane/lin-11, git worktree wt/lin-11); head after work: c78fadd.

## Resulting behavior

`LineageRenderer.render` (modules/dsl/.../proj/LineageProjection.scala) теперь закрывает внешнюю область проекции легальным statement: когда в отрендеренном дереве нет ни одного named def (Delegation/Phase — обычный headless plain-run scout/coder), после comment-only тела объекта добавляется строка `  ()` (children width; region binds at the closer line itself). До фикса такая проекция была `object X:` + indented `// ...` и dotc 3.9.0 отвергал её с «indented definitions expected, eof found» (reproducer lane LIN-09). Если named def есть ХОТЯ бы один в любой точке дерева — его def-line открывает body region, object-level closer НЕ добавляется, и вывод байт-идентичен прежнему формату (LIN2-01 golden-инвариант: golden tiny-graph/deep-tree не изменились, frozen LineageCompileGateSpec остался зелёным без правок).

## Changed paths

- `modules/dsl/src/main/scala/raider/dsl/lineage/proj/LineageProjection.scala` — фикс: `hasNamedDef` scan + object-level `()` closer в `render` (public API не менялся: `render`/`LineageView`/`NodeView`/`RenderError` сигнатуры прежние).
- `modules/dsl/src/test/scala/raider/dsl/lineage/proj/LineageRendererSpec.scala` — +3 теста LIN11-03; ожидания W9F4-01 (def-free shape) дополнены закрывающим `  ()` (линий 4→5).
- `modules/cli/src/test/scala/raider/cli/lineage/LineagePlainRunCompileGateSpec.scala` — НОВЫЙ behavioral compile-gate (pinned dotc 3.9.0): LIN11-01/LIN11-02.

Нет изменений: contracts/** (schema/golden frozen, не тронуты), LineageCompileGateSpec (frozen), GraphHash/компаратор, build.sbt/project/**, scripts/**, docs/** (кроме этого untracked record), modules/runtime/**, modules/cli/src/main/**.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| Reproducer (LIN-09 defect) | dotc 3.9.0: comment-only тело → exit 1 «indented definitions expected, eof found»; с `()` → exit 0 | temp-компиляция broken.scala/fixed.scala (pinned dotc, jar'ы artifacts/tools/m2) до правки |
| LIN11-01 (normal) | PASS: plain-run проекция (2 model roots + tool children, без named-def) компилируется pinned dotc 3.9.0 (exit 0); byte-identical repeat ре-компилируется (exit 0) | raider.cli.lineage.LineagePlainRunCompileGateSpec: «LIN11-01: plain-run projection …» 3/3 tests passed; artifacts компиляций modules/cli/target/lineage-compile/plain-run*.scala + *.dotc.log |
| LIN11-02 (error/adversarial) | PASS, оба негатива честно НЕ компилируются: (a) pre-fix artifact (object closer dropped — точная форма дефекта LIN-09) exit != 0; (b) torn object header exit != 0; timeout/IO не засчитывается как non-compilation evidence | тот же spec, тесты «LIN11-02: pre-fix artifact …» и «LIN11-02: corrupted projection …»; логи plain-run-defect.dotc.log / plain-run-torn.dotc.log |
| LIN11-03 (repeat/golden invariance) | PASS: plain-run и nested-def проекции байт-идентичны на повторе; golden tiny-graph/deep-tree байт-инвариантны (explicit byte-seq assertions + строковые goldens) | LineageRendererSpec 12/12 passed (3 новых LIN11-03 + LIN2-C-01/L34-01 goldens unchanged) |
| LIN2-01 (frozen gate не сломан) | PASS 4/4: golden tiny-graph/deep-tree компилируются pinned dotc, repeat byte-identical + re-compile, corrupted header — негатив | raider.cli.lineage.LineageCompileGateSpec (frozen, без правок) |
| Полная sbt-сьюта | 915 passed / 0 failed / 0 skipped (sbt-test log, обе подсьюты) | artifacts/quality/20261012-041446-6694-0001/sbt-test.log |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git status --short` / `git log --oneline -1` (baseline check) | 0 (clean; 71278c1) | — |
| `sbt --batch "raiderDsl/testOnly raider.dsl.lineage.proj.LineageRendererSpec"` | 0, 12/12 passed | консоль |
| `sbt --batch "raiderCli/testOnly raider.cli.lineage.LineagePlainRunCompileGateSpec"` | 0, 3/3 passed | консоль + lineage-compile логи |
| `sbt --batch "raiderCli/testOnly raider.cli.lineage.LineageCompileGateSpec"` | 0, 4/4 passed | консоль |
| `make quality-changed` (git-mode classify) | 0; changed=3 paths → profiles contracts/static/unit | консоль |
| `make quality-changed QUALITY_EXECUTE=1` | 0; status=passed, 5/5 gates (core-contracts-spec, compile-fixtures, sbt-compile, scalafmt-check, sbt-test), timed_out=False везде | artifacts/quality/20261012-041328-5486-0001/manifest.json |
| `make quality-profile PROFILES="static,unit,contracts"` | 0; status=passed, те же 5/5 gates | artifacts/quality/20261012-041446-6694-0001/manifest.json |
| `git commit` ×2 в lane/lin-11 | 0 | 396a260 (fix+dsl tests), c78fadd (cli compile-gate) |

Toolchain: JDK 21.0.12.1, sbt 1.11.4, Scala 3.9.0 pinned, ZIO 2.1.26 pinned. PATH=/opt/homebrew/bin добавлялся. artifacts/tools/m2 скопирован untracked из root (77M) по parallel-development §1a; artifacts/ в .gitignore — в коммиты не попал.

## Limitations / blockers

- Гейты выполнены ДО коммитов, но ПОСЛЕ последней правки исходников (scalafmtAll → гейты → коммиты без изменений контента); evidence привязан к дереву коммитов 396a260/c78fadd. Lane evidence — не замена проверке интегрированного candidate.
- LIN11-02(a) — негатив «dropped closer» пиннится к pinned dotc 3.9.0: гипотетическое будущее смягчение парсера сделает этот тест честно красным (сигнал об изменении компилятора), замена негатива тогда потребует отдельного решения.
- LineagePlainRunCompileGateSpec дублирует private plumbing frozen LineageCompileGateSpec (тот members не экспортирует; модифицировать его запрещено) — осознанное дублирование ~120 строк тестовой обвязки.
- Purity-warning dotc «A pure expression does nothing in statement position» на закрывающем `()` — ожидаемое поведение, gate считает exit code, не warning'и (тот же паттерн в существующих closers named-def).

## Review and next task

Card LIN-11 closed в lane: фикс минимальный (1 условие + 1 helper в proj/**), формат всех проекций с named-def не изменился, frozen contracts/golden/compile-gate не тронуты. Push/merge не выполнялись (запрещены) — интеграция coordinator'ом. Следующий ready-шаг: merge lane/lin-11 в интегрированное дерево + повтор затронутых гейтов на candidate; далее — остатки P-LIN по board.
