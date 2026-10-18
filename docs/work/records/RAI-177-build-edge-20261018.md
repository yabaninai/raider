# RAI-177 coordinator build edge: work record

Status: done. Date: 2026-10-18. Baseline commit/source: 4698a83.

## Resulting behavior

The REPL project now compiles against raiderCli so the RAI-177 lane can consume the accepted CLI lineage snapshot pointer reader and live projection adapter. The dependency direction is CLI → no REPL dependency; the RAI-102 direct module-edge idiom is followed. The pointer-protocol-only read rule is unchanged. This is a build prerequisite, not implementation or acceptance of RAI-177.

## Changed paths

- `build.sbt`: added `raiderCli % "compile->compile;test->test"` to `raiderRepl` dependencies.
- `docs/tasks/lineage.md`: recorded the authorized RAI-102 precedent and unchanged pointer protocol.
- No public signatures or schemas changed.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| RAI-102 build-edge idiom | Passed by inspection; direct project dependency pattern matches | `git show 71278c1 -- build.sbt` |
| CLI/Repl dependency cycle | No cycle; both requested compiles passed | Commands below |
| Changed-source quality gates | Passed: contracts, docs, static | `artifacts/quality/20261018-135920-76175-0001/manifest.json` |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `sbt --batch raiderRepl/compile` | 0 | Terminal output; succeeded 2026-10-18 13:58:05 local |
| `sbt --batch raiderCli/compile` | 0 | Terminal output; succeeded 2026-10-18 13:58:12 local |
| `make quality-changed` | 0 | Classified `build.sbt`, `docs/tasks/lineage.md`; profiles contracts, docs, static |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | `artifacts/quality/20261018-135920-76175-0001/manifest.json` |

## Limitations / blockers

The quality runner classified the build and lineage task scope note (the plan registration is in the preceding coordinator commit). This edge only exposes existing CLI interfaces; the RAI-177 live-lineage adapter and real PTY acceptance remain pending. The exact quality report is the evidence for its checked source fingerprint; later operational-record edits do not rebind it.

## Review and next task

Independent review is pending. Next available task: continue RAI-177 implementation in its registered allowed paths, consuming the accepted lineage interfaces without changing pointer-protocol reads.

Product-input hash and operational-record hash differ under the reviewed policy. Changes to code/spec/build/fixtures require affected reruns; updating a board/record line does not rebind an old manifest to the new source tree. A lane report applies to its snapshot; final acceptance applies to the integrated candidate.
