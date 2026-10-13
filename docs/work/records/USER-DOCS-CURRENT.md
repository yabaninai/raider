# USER-DOCS-CURRENT: work record

Historical record of the first eight-example gallery at the commits named below. Its `cmp` checks describe the former greeting fixture, not the current billing checker. The active gallery and live evidence are in [DOCS-EN](DOCS-EN.md). The previous main final receipt failed and is retained; it does not accept the new source tree.

Status: done (documentation and eight runnable examples).
Date: 2026-10-12 UTC / 2026-10-13 Europe/Moscow.
Initial main baseline: `7702fd0`, clean. Assignment commit: `073d617`.
Coordinator integration worktree: `wt/user-docs-current`, branch
`docs/current-user-examples`. Luna worker: `wt/user-docs-luna`, final commit
`e25052e71c87f567d7721a4fb589b95c71b61e86`, reviewed merge `c4dab06`.

## Resulting behavior

README and the new user guides describe the actual code and current entrypoints.
Invented LLM/tool outputs, outdated counters, and nonexistent commands were
removed. The target DSL contract was retained, and the introduction separates it
from verified instructions. Self-development prompts lead to a runnable coding
fixture. API/version support was not expanded.

Gallery: real Scala values/case class/multiline; lazy/map; all/bounded ordered
batch; background start/await/jobs; in-memory session history; typed Program
compiled bundle (normal and invalid input); native coding tools on a disposable
workspace; real artifacts and compilation of a Scala lineage projection.
Each example has an executable file/command and assertions; the new standalone
runner returns nonzero on a missing marker/error/timeout/wrong exit and refuses
`-O`.

CodingStand is a Scala loopback HTTP fixture with scripted tool choices. The
native CLI runs real fs_read/search/proc_run/edit operations: cmp exit 1 before
the change, SHA-256 edit, cmp exit 0 after. Content, provenance, search match,
old/new hashes, and trace are checked. This is a mechanism, not proof of LLM
reasoning. Pure TaskSummary does not call a model. No real paid endpoint calls
were made.

## Changed paths

- `README.md`, `docs/user-guide.md`, `docs/user-guide-repl.md`:
  setup, gallery, current APIs, defaults, and limitations.
- `docs/dsl-experience.md` intro, `docs/selfdev-prompts.md`:
  correct entrypoint to actual examples; design obligations retained.
- `examples/user-guide/repl/*.repl`, `bundle/**`, `fixture/**`, `stand/**`:
  reproducible source and data, Scala HTTP stand.
- `scripts/build/user-examples.sh`: pinned build/compile/JAR packaging glue.
- `scripts/quality/user_examples.py`: standalone explicit verifier; not a new
  scheduler or a replacement for protected gates.
- `docs/tasks/user-docs-current.md`, board, this record.

The protected classifier correction and discovered SSE defect were handled
separately as VERIFIER-README-CLASSIFICATION and TRANSPORT-SSE-IDLE. Their
changes are not hidden in the documentation card; public APIs and verifier
bounds are unchanged.

## Acceptance evidence

| Criterion | Actual result | Evidence |
| --- | --- | --- |
| DOCS-01 actual setup/API | Source audit + independent review approve | Main/Commands/FacadeOps/CliArgs/MockBackend/HeadlessRunner, visible Luna sessions |
| DOCS-02 all eight examples | passed in one run after merged SSE fix and final provenance assertions; exit 0 | `wt/user-docs-current/artifacts/user-examples/verification-4q3_k78b/report.json` |
| Invalid bundle input | expected and actual exit 21 | same report, bundle-empty command |
| DOCS-03 coding loop | real tool sequence and check exit 1→0, fixture unchanged outside disposable copy | same report + transcript/run.json |
| Artifacts | files/schema read, actual lineage.scala compiles with pinned dotc | same report, lineage-compile exit 0 |
| DOCS-04 scope/review | final docs/verifier approve | visible `/root/user_docs_review_luna`, source-only read review |
| Required changed pair | passed after separate classifier correction | `wt/user-docs-current/artifacts/quality/20261013-013224-92854-0001/manifest.json` |

## Commands

Actual pre-record commands in the coordinator worktree:

| Command | Exit | Evidence |
| --- | --- | --- |
| `sh scripts/build/user-examples.sh` | 0 | setup logs and compiled-input hashes in example report |
| `python3 scripts/quality/user_examples.py` | 0 | full-gallery-integrated.log, eight-case report above; stand exit 143 after SIGTERM cleanup |
| `python3 scripts/quality/user_examples.py --only artifacts` | 0 | verification-ewv8emp7/report.json (coding prerequisite + artifacts) |
| `python3 scripts/plan/validate.py --out artifacts/plan-review/user-docs-reviewed.json` | 0 | 142 backlog cards |
| `git diff --check` | 0 | coordinator session |
| `make quality-changed` | 0 | quality-changed-reviewed.log |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | docs/self/static manifest above |

Quality commands use JAVA_HOME JDK21 and COURSIER_CACHE=/tmp/cc-master, recorded
in the final receipt. These results are from their own pre-record snapshots.
Earlier full gallery `verification-qo7r8vwi` was before the SSE merge and
verifier strengthening; the latest `verification-4q3_k78b` checks the merged
runtime and final assertions. After merging to main, without changing records,
run the full gallery, changed, execute, and docs/security checks; exact
argv/exits/logs/manifest hashes are stored outside product inputs in
`artifacts/user-docs-final/receipt.json`. Only a passing fresh root receipt
confirms the final inputs; green lane results do not replace it.

## Limitations / blockers

The initial classifier refused README.md (unmapped); a separate reviewed
correction was made. The first broad fast/docs/security run failed on an
existing SSE idle timeout and was repeated separately with exit 1 on unchanged
runtime. Original failure evidence:
`wt/user-docs-current/artifacts/quality/20261013-012310-88826-0001/manifest.json`,
`wt/user-docs-current/artifacts/user-examples/sse-isolated.log`.
No failed run was rewritten as a pass. The defect received a separate
correction card.

Live model quality, paid endpoints, arbitrary replay/crash resume, a published
host SDK, and autonomous forge SDLC are not checked by this gallery. The CLI
mock does not call tools; the REPL fixture does not prove context understanding.
Scala/repo code is trusted; tool policy is not a sandbox. TokenRouter and remote
publication were not changed.

## Review and next task

The Luna writer and a separate Luna reviewer worked in visible, disjoint
worktrees; the coordinator checked and integrated the writer diff. Final
docs/verifier review approve. Final main evidence after all merges and records
is required; source must not change after that. The next SDK packet is RAI-116
(workflow/launch ADR); the current TUI wave remains in its own board/cards, and
this work does not declare M3/M4 readiness.
