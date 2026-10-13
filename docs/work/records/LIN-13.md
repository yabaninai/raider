# LIN-13: work record — docs/lineage.md §9.2 sync (truthful implementation statuses)

Status: done. Date: 2026-10-12. Baseline commit: `4b46dc7` (branch `lane/lin-13`,
clean worktree at start; lane commit `ebc9e99`).

## Resulting behavior

§9.2 “What is still missing” in `docs/lineage.md` was aligned with
the integrated state at baseline `4b46dc7`; each table row carries
an evidence link (record path or file:line), verified by a script against
baseline (17/17 OK):

- **TabShell wiring in the product REPL — CLOSED (RAI-108).** Product entry
  `--tabs` starts TabShell: the flag and dispatcher `runTabs` in `main`
  (`modules/repl/src/main/scala/raider/repl/Main.scala:230-242,280-288`),
  `TabShellHost.wiring`/`TabShellHost.run`
  (`modules/repl/src/main/scala/raider/repl/TabShellHost.scala:31`); legacy loop
  without the flag is unchanged; PTY acceptance P2E-01..03 —
  `docs/work/records/RAI-108.md`. The outdated reference
  `modules/cli/chat` was removed.
- **Writing artifacts to the run directory — CLOSED (LIN-09).** A settled run writes
  `lineage.scala` + `lineage-data.json` to its run directory beside
  `events.jsonl`/`summary.md`
  (`modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala:665-677`;
  writer `RunLineageArtifacts` —
  `modules/cli/src/main/scala/raider/cli/run/RunLineageArtifacts.scala:52-55`);
  on failure, a typed `unavailable` line is written and the exit code stays unchanged —
  `docs/work/records/LIN-09.md`.
- **`raider replay` (RAI-109) — verified, closed.** The fact was already in the text;
  evidence was added: `ReplayCommand.scala:37` (delegation `ReplayCli.exec`),
  `docs/work/records/RAI-109.md`.
- **Open items remain truthfully open** (DOC-13-02):
  LIN-06 live re-run (LIN-06 card in `docs/tasks/lineage.md`, status: todo;
  mechanism — `WorkspaceIsolation`, `Admission.make` —
  `ReplayHarness.scala:298`; there is no live provider —
  `ReplayModelBackend.scala:75`); live-wire verification (owner, not
  performed; the verifiable fact that no live provider exists is in the same line
  code); multi-job export (owner `cli/run/**`, ADR-027 §6.4;
  per-job evidence — `EventExport.scala:21`); phase/recovery schema-card
  (wire frozen 13 cases —
  `modules/runtime/src/main/scala/raider/runtime/events/events.scala:26`);
  LIN2-03 partially — **in flight (card LIN-14)**; `LineageCatalog` without
  persistence (`LineageCatalog.scala:87`) — **in-flight (card LIN-12)**.
  Nothing is claimed closed beyond the integrated state.
- **Closing paragraph §9.2:** from the list of open milestone obligations
  “product wiring” was removed (closed by RAI-108), closures were recorded for RAI-108/LIN-09.
- **§10 (consistency adjustment in the same file):** the line
  `artifacts/<run>/lineage.*` (“LIN-03/08 obligation”) was changed to reflect
  the actual LIN-09 write LIN-09 write in `<out>/work-<uuid>/`; example comment
  “(after P-LIN)” was changed to reflect actual behavior. Basis: the same facts
  LIN-09 (`HeadlessRunner.scala:665-677`, record LIN-09.md); without this edit
  §10 would directly contradict the updated §9.2 within the same file. This goes beyond
  the literal scope of “§9.2”, within the allowed path `docs/lineage.md` and the card’s goal
  (truthful statuses); noted here for review.

## Changed paths

- `docs/lineage.md` (+14/−12): §9.2 table (7 rows), closing paragraph
  §9.2, §10 lineage artifact line, example comment §10.
- `docs/work/records/LIN-13.md` — this record (draft in the worktree; operational
  file outside the card’s allowed paths, created as required by the
  work record requirement and delivered by the coordinator).

## Acceptance evidence

| Criterion | Actual result | Evidence |
| --- | --- | --- |
| DOC-13-01: each §9.2 row contains an evidence link verifiable against the baseline | 17/17 links OK (file:line grep check against `4b46dc7`) | check output in lane report; links in the diff |
| DOC-13-02: no in-flight/owner-gated item is marked closed | LIN-06/live-wire/multi-job/schema-card — remain open; LIN2-03 and catalog persistence are marked in flight with cards LIN-14/LIN-12 | diff §9.2 (`git show ebc9e99`) |
| DOC-13-03: docs profile green, anchors/links valid, diff only in docs/lineage.md | quality passed, profiles=['docs'], mode=git-status (only `docs/lineage.md` changed) | manifests below |

## Commands

| command | Exit | Log / manifest |
| --- | --- | --- |
| `make quality-changed` | 0 | changed=[docs/lineage.md], profiles=[docs] |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | `artifacts/quality/20261012-175654-72619-0001/manifest.json` — status=passed, plan-validate passed |
| `make quality-profile PROFILES="docs"` | 0 | `artifacts/quality/20261012-175659-72726-0001/manifest.json` — status=passed, plan-validate passed |

## Limitations / blockers

- Edits to §10 (two locations) are a consistency adjustment beyond the literal
  “§9.2”; reviewer may revert only those; §9.2 is self-contained.
- file:line code links are valid at baseline `4b46dc7`; line shifts are possible
  after parallel lane merges — verify the links during integration (not a blocker:
  record links are stable).
- Line numbers `docs/tasks/lineage.md` were not used as evidence (only
  card headings were used), so shifts do not affect them.

## Review and next task

Review was not performed (lane worker; independent review is for the coordinator).
Next ready cards in the lineage track: LIN-14 (LIN2-03 coverage), LIN-12
(catalog persistence), LIN-06 (live re-run scripted acceptance — RAI-113/114
per wave 4 of the board).
