# NIGHTLY-PHASE-5: work record

Status: done. Date: 2026-10-05T14:36Z. Baseline commit/source: 6f277d7 (feat(phase-4): error recovery).

## Resulting behavior

Packaging & CI (master prompt Phase 5, tasks 5.1–5.3):

- **5.1 GitHub Actions CI**: new `.github/workflows/ci.yml` — two jobs on push/PR:
  - `test`: temurin 21 + coursier cache → `sbt --batch compile` (-Werror), `sbt --batch test`, `sbt --batch scalafmtCheckAll`, `sbt --batch "scalafixAll --check"` (quoted single-command form, same as the gate), forbidden-API scan with self-test.
  - `quality`: runs `make quality-changed QUALITY_EXECUTE=1` (full-tree fast profile — 9 gates, executed, never skipped).
- **5.2 Fat JAR**: sbt-assembly 2.1.5 plugin; `raiderCli/assembly` produces standalone `modules/cli/target/scala-3.9.0/raider-cli.jar` (main `raider.cli.Main`, tests excluded). Merge rules: discard foreign MANIFEST.MF + .SF signatures and `module-info.class`; assembly defaults otherwise. Verified standalone: `java -jar raider-cli.jar chat --provider mock` runs the streaming chat loop (banner, tools incl. fs_patch/fs_tree, mock deltas) and exits 0; jar is 21.9 MB, no runtime compiler (headless distribution rule kept).
- **5.3 Makefile targets**: `raider-jar` (assembly), `raider-chat` (java -jar chat), `raider-run` (java -jar run --agent coder, `INPUT` var), `quality-full` (test + scalafmt + forbidden-apis + executed gates + repl/cli smokes). All respect `COURSIER_CACHE` (default /tmp/cc-master).

## Changed paths

- `.github/workflows/ci.yml` (new)
- `project/plugins.sbt` (sbt-assembly 2.1.5 — build-tooling plugin, sanctioned by the master prompt)
- `build.sbt` (`raiderCliAssemblySettings` on raiderCli)
- `Makefile` (4 new targets)

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| `sbt raiderCli/assembly` builds raider-cli.jar | exit 0, jar hash cb8c0497…, 21.9 MB | build log |
| `java -jar raider-cli.jar chat --provider mock` (piped) | banner + mock delta stream + clean exit 0 | manual transcript |
| ci.yml parses as YAML | ok | python yaml.safe_load |
| `make -n raider-jar / raider-chat / raider-run / quality-full` | all resolve | make dry-run |
| sbt compile exit 0 (-Werror) | 0 | build log |
| sbt full test | 10 suites, 0 failures | /tmp/test-p5.txt |
| make quality-changed QUALITY_EXECUTE=1 | 9/9 gates | artifacts/quality/20261005-143348/manifest.json |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch raiderCli/assembly` | 0 | build log |
| `printf 'hello\n:quit\n' | java -jar modules/cli/target/scala-3.9.0/raider-cli.jar chat --provider mock` | 0 | manual transcript |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch compile` | 0 | build log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 | /tmp/test-p5.txt |
| `COURSIER_CACHE=/tmp/cc-master make quality-changed QUALITY_EXECUTE=1` | 0 | artifacts/quality/20261005-143348/manifest.json |

## Limitations / blockers

- The GitHub Actions workflow itself is NOT executed here (no GitHub remote run in this environment); its steps are exactly the locally verified commands. First push will prove the runner environment (ubuntu-latest has python3; java 21 temurin pinned).
- `raider-chat`/`raider-run` Make targets point at a live OpenAI-compatible endpoint (127.0.0.1:8081 by CLI default) — they are operator entrypoints, not gates; `quality-full` uses only mock/replay paths.
- Fat-JAR excludes the REPL module (no compiler by design); `raider repl` from the jar is intentionally unavailable.

## Review and next task

Self-executed nightly run; no reviewer assigned. Next: Phase 6 (documentation: API docs, architecture guide, contributor guide).
