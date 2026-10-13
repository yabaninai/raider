<!-- Work record. Original in Russian; key results in English below. -->

# RAI-014.a: Bounded read/search with workspace containment — record

Date: 2026-10-03. Writer: coordinator (root; lane path cancelled after escalation).
Dependencies: frozen raider-core (`RaiderError`) + local types; coordinator
registered raiderTools in build.sbt (also a prerequisite for lanes B/C).

## Deliverable

Production (2 files):

- `modules/tools/src/main/scala/raider/tools/files/read/ReadTool.scala`
  - `Workspace.make` resolves the root with `toRealPath()`; containment means
    real path inside root (path prefix is NOT sufficient, §10);
  - `ReadTool.read(rel, offsetLine, maxLines)` — ranged UTF-8 text read; NUL
    byte → declared binary failure (no dump); `maxLines` capped at
    `HardMaxLines=50000` (exceeding it → typed RA-INP, no silent clamp);
    truncation is declared (`truncated`, `totalLines`); empty file has a defined
    result; provenance: full SHA-256 + bytes;
  - traversal (`a/../../x` normalizes outside root) → RA-TOOLDENY; symlink
    escape (`toRealPath` outside) → RA-TOOLDENY; internal symlink → allowed;
    deleted file → typed failure (no fake success).
- `modules/tools/src/main/scala/raider/tools/search/SearchTool.scala`
  - pure java.nio+ZIO traversal (NO shell/external grep); glob filter via
    PathMatcher; NUL files skipped; bounds: `HardMaxMatches=10000`,
    `HardMaxFiles=50000`, `HardMaxScanned=20000`, 2 MiB/file, snippet ≤500 —
    all with declared truncation; empty query → defined empty result
    (documented, not an error).

Tests (13): `ReadToolSpec` (8) — ranged window+provenance, empty file,
traversal denied, symlink escape/internal allowed, declared binary, declared
truncation, typed hard bound, typed deleted-file failure. `SearchToolSpec` (5)
— walk-order matches with file/line/snippet, defined empty query, glob,
truncation+binary skip, typed hard bound.

## Checks (exact)

- `sbt --batch "raiderTools/test"` → exit 0, 13 passed / 0 failed.
- `make quality-changed QUALITY_EXECUTE=1` → exit 0, 6/6 gates
  (artifacts/quality/20261003-142239/manifest.json; sbt-test ran all modules:
  core 13+ContractsSpec, runtime 33, dsl 10, testkit 3, tools 13).

## COMPAT note

The module uses native `ZIO[Any, RaiderError, *]` (not a core.Task wrapper) —
this is the Tool contract boundary (RAI-002.b `Tool.invoke` is ZIO); there is
no second semantics.

## Limitations (honestly)

- READ-03 “cancellation stops a long search” is covered indirectly: search is
  tightly bounded (`maxFiles`/`maxScanned`); no separate interruption test for
  a long traversal exists; obligation for RAI-016/022 (for a real long walk).
- Atomicity for “file changed during read”: provenance is captured AFTER read
  (sha256 of those same bytes); the TOCTOU window between stat and read is not
  closed — honestly outside the MVP contract, recorded here.
