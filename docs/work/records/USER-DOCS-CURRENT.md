# USER-DOCS-CURRENT: work record

Status: done (documentation and eight runnable examples).
Date: 2026-10-12 UTC / 2026-10-13 Europe/Moscow.
Initial main baseline: `7702fd0`, clean. Assignment commit: `073d617`.
Coordinator integration worktree: `wt/user-docs-current`, branch
`docs/current-user-examples`. Luna worker: `wt/user-docs-luna`, final commit
`e25052e71c87f567d7721a4fb589b95c71b61e86`, reviewed merge `c4dab06`.

## Resulting behavior

README и новые user guides описывают actual code и текущие entrypoints.
Убраны придуманные LLM/tool outputs, устаревшие counters и несуществующие команды.
Целевой DSL contract сохранён, а intro отделяет его от проверенных инструкций.
Selfdev prompts ведут к runnable coding fixture. API/version support не расширены.

Галерея: real Scala values/case class/multiline; lazy/map; all/bounded ordered
batch; background start/await/jobs; in-memory session history; typed Program
compiled bundle (normal и invalid input); native coding tools на disposable
workspace; real artifacts и компиляция Scala lineage projection.
Каждый пример имеет executable file/command и assertions; новый standalone runner
возвращает nonzero на missing marker/error/timeout/wrong exit и отказывается от -O.

CodingStand — Scala loopback HTTP fixture, tool choices scripted. Реальные
fs_read/search/proc_run/edit исполняет native CLI: cmp exit 1 до изменения,
SHA-256 edit, cmp exit 0 после. Проверяются содержимое, provenance, search match,
old/new hashes и trace. Это механизм, не доказательство reasoning LLM.
Pure TaskSummary не вызывает модель. Реальных платных endpoint calls не было.

## Changed paths

- `README.md`, `docs/user-guide.md`, `docs/user-guide-repl.md`:
  setup, gallery, current APIs, defaults и ограничения.
- `docs/dsl-experience.md` intro, `docs/selfdev-prompts.md`:
  корректный вход к actual examples, design obligations сохраняются.
- `examples/user-guide/repl/*.repl`, `bundle/**`, `fixture/**`, `stand/**`:
  воспроизводимые source и данные, Scala HTTP stand.
- `scripts/build/user-examples.sh`: pinned build/compile/JAR packaging glue.
- `scripts/quality/user_examples.py`: standalone explicit verifier; не новый
  scheduler и не замена protected gates.
- `docs/tasks/user-docs-current.md`, board, этот record.

Protected classifier correction и найденный SSE defect оформлены отдельно:
VERIFIER-README-CLASSIFICATION и TRANSPORT-SSE-IDLE. Их изменения не скрыты
в documentation card; public APIs и verifier bounds не меняются.

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

Actual pre-record commands in coordinator worktree:

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
in final receipt. Эти результаты относятся к собственным pre-record snapshots.
Earlier full gallery `verification-qo7r8vwi` была до SSE merge и укрепления verifier;
последняя `verification-4q3_k78b` проверяет объединённый runtime и final assertions.
После merge в main без изменения records выполняются full gallery, changed,
execute и docs/security; exact argv/exits/logs/manifest hashes сохраняются вне
product inputs в `artifacts/user-docs-final/receipt.json`. Только passing fresh
root receipt подтверждает окончательные inputs; lane green не подменяет его.

## Limitations / blockers

Initial classifier refused README.md (unmapped); separate reviewed correction.
Первый broad fast/docs/security run failed на existing SSE idle timeout,
повторён отдельно с exit 1 на unchanged runtime. Original failure evidence:
`wt/user-docs-current/artifacts/quality/20261013-012310-88826-0001/manifest.json`,
`wt/user-docs-current/artifacts/user-examples/sse-isolated.log`.
Ни один failed run не переписан как pass. Defect получил отдельную correction card.

Live model quality, paid endpoints, arbitrary replay/crash resume, published host
SDK и autonomous forge SDLC не проверяются этой gallery. CLI mock не вызывает
tools; REPL fixture не доказывает понимание контекста. Scala/repo code trusted,
tool policy не sandbox. TokenRouter и remote publication не менялись.

## Review and next task

Luna writer и отдельный Luna reviewer работали visible, disjoint worktrees;
coordinator проверил и интегрировал writer diff. Final docs/verifier review approve.
Main final evidence после всех merges и records обязательно, source после него
не меняется. Следующий SDK packet — RAI-116 (workflow/launch ADR); current TUI
wave остаётся в своём board/карточках, эта работа не объявляет M3/M4 readiness.
