# LIN-11: work record

Status: done. Date: 2026-10-12 (UTC). Baseline commit/source: 71278c1 (lane/lin-11, git worktree wt/lin-11); head after work: c78fadd.

## Resulting behavior

`LineageRenderer.render` (modules/dsl/.../proj/LineageProjection.scala) now closes the outer projection region with a legal statement when the rendered tree contains no named definitions (Delegation/Phase is a normal headless plain-run scout/coder): it adds the line `  ()` after the object's comment-only body (children width; the region binds at the closer line itself). Before the fix, this projection was `object X:` plus indented `// ...`, which dotc 3.9.0 rejected with “indented definitions expected, eof found” (reproducer lane LIN-09). If at least one named definition exists anywhere in the tree, its def line opens the body region, no object-level closer is added, and output is byte-identical to the previous format (LIN2-01 golden invariant: golden tiny-graph/deep-tree unchanged; frozen LineageCompileGateSpec remained green without edits).

## Changed paths

- `modules/dsl/src/main/scala/raider/dsl/lineage/proj/LineageProjection.scala` — fix: `hasNamedDef` scan + object-level `()` closer in `render` (public API unchanged: `render`/`LineageView`/`NodeView`/`RenderError` signatures unchanged).
- `modules/dsl/src/test/scala/raider/dsl/lineage/proj/LineageRendererSpec.scala` — +3 LIN11-03 tests; W9F4-01 expectations (def-free shape) supplemented with closing `  ()` (4→5 lines).
- `modules/cli/src/test/scala/raider/cli/lineage/LineagePlainRunCompileGateSpec.scala` — NEW behavioral compile gate (pinned dotc 3.9.0): LIN11-01/LIN11-02.

Unchanged: contracts/** (schema/golden frozen and untouched), LineageCompileGateSpec (frozen), GraphHash/comparator, build.sbt/project/**, scripts/**, docs/** (except this untracked record), modules/runtime/**, modules/cli/src/main/**.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| Reproducer (LIN-09 defect) | dotc 3.9.0: comment-only body → exit 1 “indented definitions expected, eof found”; with `()` → exit 0 | temp compilation of broken.scala/fixed.scala (pinned dotc, artifacts/tools/m2 jars) before the change |
| LIN11-01 (normal) | PASS: plain-run projection (2 model roots + tool children, no named def) compiles with pinned dotc 3.9.0 (exit 0); byte-identical repeat recompiles (exit 0) | raider.cli.lineage.LineagePlainRunCompileGateSpec: “LIN11-01: plain-run projection …” 3/3 tests passed; compilation artifacts modules/cli/target/lineage-compile/plain-run*.scala + *.dotc.log |
| LIN11-02 (error/adversarial) | PASS, both negatives honestly fail to compile: (a) pre-fix artifact (object closer dropped — exact LIN-09 defect form), exit != 0; (b) torn object header, exit != 0; timeout/IO does not count as non-compilation evidence | Same spec, tests “LIN11-02: pre-fix artifact …” and “LIN11-02: corrupted projection …”; logs plain-run-defect.dotc.log / plain-run-torn.dotc.log |
| LIN11-03 (repeat/golden invariance) | PASS: plain-run and nested-def projections are byte-identical on repeat; golden tiny-graph/deep-tree are byte-invariant (explicit byte-sequence assertions + string goldens) | LineageRendererSpec 12/12 passed (3 new LIN11-03 + LIN2-C-01/L34-01 goldens unchanged) |
| LIN2-01 (frozen gate not broken) | PASS 4/4: golden tiny-graph/deep-tree compile with pinned dotc; repeat byte-identical + recompile; corrupted header is negative | raider.cli.lineage.LineageCompileGateSpec (frozen, unmodified) |
| Full sbt suite | 915 passed / 0 failed / 0 skipped (sbt-test log, both sub-suites) | artifacts/quality/20261012-041446-6694-0001/sbt-test.log |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git status --short` / `git log --oneline -1` (baseline check) | 0 (clean; 71278c1) | — |
| `sbt --batch "raiderDsl/testOnly raider.dsl.lineage.proj.LineageRendererSpec"` | 0, 12/12 passed | console |
| `sbt --batch "raiderCli/testOnly raider.cli.lineage.LineagePlainRunCompileGateSpec"` | 0, 3/3 passed | console + lineage-compile logs |
| `sbt --batch "raiderCli/testOnly raider.cli.lineage.LineageCompileGateSpec"` | 0, 4/4 passed | console |
| `make quality-changed` (git-mode classify) | 0; changed=3 paths → profiles contracts/static/unit | console |
| `make quality-changed QUALITY_EXECUTE=1` | 0; status=passed, 5/5 gates (core-contracts-spec, compile-fixtures, sbt-compile, scalafmt-check, sbt-test), timed_out=False throughout | artifacts/quality/20261012-041328-5486-0001/manifest.json |
| `make quality-profile PROFILES="static,unit,contracts"` | 0; status=passed, same 5/5 gates | artifacts/quality/20261012-041446-6694-0001/manifest.json |
| `git commit` ×2 in lane/lin-11 | 0 | 396a260 (fix+dsl tests), c78fadd (cli compile gate) |

Toolchain: JDK 21.0.12.1, sbt 1.11.4, Scala 3.9.0 pinned, ZIO 2.1.26 pinned. PATH=/opt/homebrew/bin was added. artifacts/tools/m2 was copied untracked from root (77M) under parallel-development §1a; artifacts/ is in .gitignore and was not committed.

## Limitations / blockers

- Gates ran BEFORE commits but AFTER the last source edit (scalafmtAll → gates → commits with no content changes); evidence is tied to commits 396a260/c78fadd. Lane evidence does not replace checking the integrated candidate.
- LIN11-02(a), the “dropped closer” negative, is pinned to dotc 3.9.0: a hypothetical future parser relaxation would make this test honestly fail (signal of a compiler change); replacing the negative would then require a separate decision.
- LineagePlainRunCompileGateSpec duplicates private plumbing from frozen LineageCompileGateSpec (that spec does not export its members; modification is forbidden) — a deliberate duplication of ~120 lines of test scaffolding.
- dotc purity warning “A pure expression does nothing in statement position” on the closing `()` is expected; the gate checks exit code, not warnings (same pattern as existing named-def closers).

## Review and next task

Card LIN-11 closed in the lane: the fix is minimal (1 condition + 1 helper in proj/**); the format of all projections with named defs did not change; frozen contracts/golden/compile gate were untouched. No push/merge was performed (prohibited) — integration is by the coordinator. Next ready step: merge lane/lin-11 into the integrated tree and rerun affected gates on the candidate; then continue the remaining P-LIN work on the board.
