# W22-REVIEW — review of the W20–W21 tail (lane W22-R1, 2026-10-12)

Reviewer: lane-worker W22-R1. Mode: READ-ONLY review, WORKTREE
(`raider-wt-1`, branch `lane/w22-r1`, base `94e9f5b`). The reviewed code was
not changed; after all checks, `git status --short` was empty (clean working
tree; `artifacts/` and `RECORD.md` are gitignored). Lane artifacts: this file
(`reviews/W22-review.md`) and `RECORD.md` at the worktree root.

Verdict: **CHANGES-REQUIRED** — one finding **F-W22R1-1** (object 1, a
leftover debug `println` in product diagnostics). Object 1, as it concerns
RunDiffReport — ACCEPT; objects 2 and 3 — ACCEPT.

---

## Object 1 — WorkspaceIsolation + RunDiffReport + specs (L52, LIN-06C)

`modules/cli/src/main/scala/raider/cli/lineage/WorkspaceIsolation.scala`
`modules/cli/src/main/scala/raider/cli/lineage/RunDiffReport.scala`
`modules/cli/src/test/scala/raider/cli/lineage/WorkspaceIsolationSpec.scala`
`modules/cli/src/test/scala/raider/cli/lineage/RunDiffReportSpec.scala`

Verified and green:

- **The isolation mechanism is honest**: staging in a temporary directory +
  `ATOMIC_MOVE` (rename(2) on the same FS) means the `parent` observer cannot
  see a partially copied tree; the no-fsync caveat is documented in scaladoc
  as ordering visibility, not durability (WorkspaceIsolation.scala:36-38).
  Symlinks/special entries are REJECTED (`CopyFailed`/`IllegalStateException`)
  rather than silently copied (41, 160-171); the snapshot is bounded by file
  count and FAILS with a typed error instead of being truncated — a truncated
  snapshot would falsify a clean `verifyUntouched` result (47-51, 126-130).
  This is the right honesty discipline.
- **`verifyUntouched` has complete semantics**: modifications/deletions/additions
  are detected (`Arrays.equals` byte comparison + a second traversal), and the
  first offender is in deterministic order of sorted relative paths (197-223);
  added empty directories are ignored, in accordance with the contract
  “guarantee over file BYTES” (56-57). IO errors beyond the three typed cases
  are NOT masked as “untouched” (59-60). `Tampered` carries a
  workspace-RELATIVE path.
- **The LIN6-C spec is real, not a tautology**: real temp-dir fixtures on every
  run with recursive cleanup; mutations are injected ONLY into the copy
  (rewrite+delete+add), and the original is verified byte-for-byte (Spec
  79-96); `Tampered` assertions are exact down to the path (`sub/b.txt`,
  `a.txt`, `new.txt`); a unique target per `isolate` call is pinned
  (copy2 != copy); the zio-test detail (lazy `assertTrue` after finalizers) is
  honestly documented and worked around with hoisting (Spec 26-29).
- **RunDiffReport — ACCEPT without caveats**: `render` is a pure function of
  its arguments, and byte stability is pinned by rendering again (Spec 81-97,
  exactly 8 lines); the divergence path is rendered using NORMALIZED ids
  (`root -> job-1:n4`) — consistent with `compareNormalized`; `fromGraphs` on
  matching graphs returns `Left` (“Match is not a report”) with a shared
  normalized hash; expected/actual counters are exact (2/1 in the missing-node
  case). It reads nothing except `GraphDiff`/`Meta` — deterministic by
  construction.

### F-W22R1-1: debug `println` left in `verifyUntouched` — stdout noise and absolute-path leakage into diagnostics

- **Where:** WorkspaceIsolation.scala:145-148.
- **Mechanism.** On EVERY call, `verifyUntouched` unconditionally prints to
  stdout `DEBUG-verify original=<absolute path> isDirectory=… exists=… cwd=…`.
  This is a forgotten debug trace from lane L52:
  1. the module promises diagnostics without absolute environment paths
     (“workspace-RELATIVE path … no absolute environment paths leak into
     diagnostics”, WorkspaceIsolation.scala:19-21) — the scaladoc directly
     contradicts the adjacent code;
  2. `println` in library machinery called by the live re-run harness pollutes
     product stdout/CLI output and any report capture channel;
  3. `scalafmt`/`scalafix` do not catch this output (verified: current gates
     are green with this `println`) — without review, the finding would have
     reached main.
- **Reproducer (observed, no code change).** Any call prints a line; this is
  visible in the spec on LIN6-C-01/02 (4 calls = 4 DEBUG lines in the test
  output from the `raiderCli/test` run; the captured output from the actual
  review session run includes `DEBUG-verify original=/var/folders/...` lines).
  The Verdict channel is NOT corrupted (Right/Left are correct) — this is a
  diagnostics hygiene finding, not a correctness issue.
- **Required change (minimal, in a separate fix card or as part of the next
  card owned by the component owner):** delete lines 145-148 entirely (the
  `println(...)` declaration); `verifyUntouched` behavior remains unchanged —
  all 4 LIN6-C tests remain green. Optionally, pin a “no stdout noise” test
  (capture `System.out` on one call) to prevent regression.

Object verdict: WorkspaceIsolation — CHANGES-REQUIRED (F-W22R1-1, one-line
fix; mechanism and spec are sound); RunDiffReport — ACCEPT.

---

## Object 2 — PhaseReplay + spec (L53, LIN-07-full) — ACCEPT

`modules/cli/src/main/scala/raider/cli/lineage/PhaseReplay.scala`
`modules/cli/src/test/scala/raider/cli/lineage/PhaseReplaySpec.scala`

- **Phase order is deterministic and honest**: BFS roots→children, each level
  sorted by `(seq, id)`, visited guard by row id — termination on malformed
  graphs is guaranteed (PhaseReplay.scala:73-88); pinned by LIN7-F-01 (both
  phases in seq order 1, 4) and a defensive test: a phase inside a parent cycle
  is unreachable from roots, and `phases` does not hang (Spec 479-502).
- **`NoPhases` is typed and checked BEFORE lookup** — exactly as the card
  requires: an empty phase list yields `Left(NoPhases)` even for an EXISTING
  non-phase id (PhaseReplay.scala:102, LIN7-F-02 test on `m1`).
- **`NodeNotFound` names the REQUESTED id** in both cases (no node / node is
  not a phase) — no synthetic verdicts are produced (121, typed-errors test).
- **Independent verdicts are truly independent**: every phase replay
  synthesizes its slice anew through `SubtreeReplay.subtree` and verifies it
  through `SubtreeReplay.verify` (no reuse of other verdicts); LIN7-F-01 proves
  it: p1 is `PartiallyResolved` (exact `Unresolved("m1", "d-missing")` +
  tool obligation), while p2 is fully `Resolved`, and
  `replayPhase(p2 alone) == replayAll-row` (Spec 395-408). The LIN-7
  “partial” guarantee is confirmed.
- **Child rows have a single source of truth**: rows are derived from THAT SAME
  subtree verdict (`failures`/`tools` map), one row per DIRECT child in
  `(seq, id)` order; grandchild failures remain in the whole-slice outcome,
  while a child is judged by its own dataRef (154-182) — consistent with
  scaladoc and pinned by exact vectors (`Obligation("dg1", None)` for
  delegation, `Resolved` for model).
- **Golden integration is real**: frozen `contracts/lineage/golden/deep-tree`
  is parsed from disk by production codecs (no reconstruction in code, Spec
  79-97); a slice for the only phase === the whole tree,
  `graphHash == GraphHash(graph)`, and sha256-hex form are checked (Spec
  436-465).
- **Determinism**: repeated runs produce identical verdicts (LIN7-F-03); no
  clocks, randomness, or IO on the main path.
- **`replayAll` is honestly partial**: a phase in a malformed cycle has no row;
  the reason is available as a typed result through `replayPhase` — documented
  in scaladoc (126-127) and pinned by the defensive test. Observation (not a
  blocker): `replayPhase` inside `replayAll` traverses `phases(graph)` again
  each time for the NoPhases gate — O(phases×graph); acceptable for
  fixture-sized graphs with bounds similar to `SnapshotMaxFiles`, and a
  candidate for local memoization if scaling (owned by the component owner,
  not this card).

The spec is not a tautology: exact verdict vectors, exact hashes, a negative
control (`isSha256Hex`), and a real freeze parsed from disk.

---

## Object 3 — GraphComparator: rename-invariant canonical job map (L54, F-L51-1) — ACCEPT

`modules/runtime/src/main/scala/raider/runtime/lineage/replay/GraphComparator.scala`
`modules/runtime/src/test/scala/raider/runtime/lineage/replay/GraphComparatorSpec.scala`

### Reproducer W20 — reproduced, now GREEN

Reproducer F-L51-1 from W20-review was run as part of the committed spec:
`FL51-01` constructs the same G1/G2 (root `a` + Delegation→`b`; versus root
`y` + Delegation→`x`, equal seq) and asserts
`compareNormalized(g1, g2) == Right`, `normalize(g1) == normalize(g2)`, exactly
the numbering `Vector(job-1:n1, job-2:n1, job-2:n2)`, and `childJobId =
job-2` (Spec 500-584). Run:
`sbt --batch "raiderRuntime/testOnly raider.runtime.lineage.replay.GraphComparatorSpec"`
→ **19 passed / 0 failed** (previously: the W20 reproducer failed with
KindMismatch — a false divergence on structurally identical graphs).

### scaladoc == code (checked line by line)

- “root job → job-1; every other job is numbered by the FIRST Delegation
  leading to it; breadth walk over the delegation tree; nodes within each job
  in per-job `seq` order” — code: roots = jobs with no incoming
  `Delegation.childJobId` (211-245), BFS queue, per-job `.sortBy(_.seq)` with
  NO id tie-breaker — the reducer's SeqGap invariant guarantees unique seq
  within each job (216-217, 248-260). Raw ids do not participate in this
  numbering.
- “Raw ids — hence job NAMES — never take part in any ordering” — true FOR
  REDUCER-SHAPED graphs: the only remaining `(seq, id)` sort (`known`, 228-230)
  orders the FIRST-SEEN roots, and there is exactly one root in reducer form
  (so that order is unobservable). The scaladoc explicitly carves out the
  degenerate fallback (multiple independent roots): “deterministic, but only
  the delegation-tree shape carries the rename-invariance promise” (163-167,
  197-200) — the promise matches the code; it does NOT exceed the code.
- “An EMPTY child subtree (silent child job) still gets a label from its
  delegation” — register+enqueue at the Delegation node regardless of whether
  it has nodes (254-258); pinned by FL51-01b (`ghost`/`mm` receive labels).
- “Emission order … preserved (parents still precede children, so the
  normalized graph re-validates)” — `normalize` rewrites ids 1:1 without
  re-sorting the vector (272); pinned by the exact id vector in FL51-01c.
- Hash discipline is unchanged: `Left` carries the GraphHash of BOTH
  normalized forms; `Right` carries the same hash; the structure-equal ⇒
  hash-equal invariant LIN5-B-03 has not been weakened (old suites green, full
  raiderRuntime 330/0).

### Strengthening against W20 comments

The W20 comment “corpus property does not sample adversarial renames” is
addressed: the LIN-06B-1 suite adds the property “adversarially renamed jobs
(alphabet order REVERSED) match normalized for every seed” — renaming
`root→aaa-*`, `child-1→ccc-*`, `child-2→bbb-*` (reverse alphabet) across all 20
seeds, with a sanity assertion that raw compare ACTUALLY detects identity drift
(Spec 448-495). In addition, FL51-01b (swap the alphabetical order of two
children while preserving delegation order) and FL51-01c (three-level tree,
four jobs collide at seq 1, complete reversal of the p→e, q→d, r→c, s→b,
t→a alphabet) prove that numbering is purely a function of tree shape.

Observation (not a blocker): a delegation CYCLE (A→B→A — a degenerate form,
outside reducer shape) leaves the map empty, so ids are not rewritten; the
behavior is deterministic and covered by the general preconditions caveat
(“reducer's … shape”, 169-173). No spec addition is required for the card.

---

## Criteria summary

| Criterion | Verdict | Comment |
| --- | --- | --- |
| (a) wire-freeze | OK | No object extends events (13 payload cases) or lineage-data.json v1: read-only; test documents use `schemaVersion = 1`, with no new fields. |
| (b) typed honesty | F-W22R1-1 | All errors are typed, with no masking (snapshot fails rather than truncating; IO is not masked as “untouched”; NoPhases/Cycle/NodeNotFound are honest). The only dishonesty is the debug `println` contradicting its own scaladoc promise for diagnostics. |
| (c) determinism | OK | All traversals are sorted; job numbering is purely a function of tree shape; repeat-render/repeat-replay are pinned; no clocks or randomness. |
| (d) not a tautology | OK | Exact assertions (paths, the exact 8-line report, exact hashes/vectors), real golden fixtures from disk, adversarial rename property; the DEBUG `println` adds output noise, but assertions do not depend on it. |
| (e) secrets | OK | No secrets; no network/paid calls; temp fixtures are cleaned up. Boundary note: F-W22R1-1 prints local absolute paths to stdout — not a secret, but environment leakage in diagnostics (captured in the finding). |
| (f) ADR-027 | OK | Live re-run targets an isolated workspace copy (§2), diff reports divergences deterministically, replay from a record without re-asking, tools remain deferred obligations. |

## Checks (exact commands and results, cwd = worktree)

- `git status --short && git branch --show-current` → clean, `lane/w22-r1`.
- `sbt --batch "raiderRuntime/test" "raiderCli/test"` → exit 0;
  **raiderRuntime: 330 passed / 0 failed; raiderCli: 212 passed / 0 failed**.
- `sbt --batch "raiderRuntime/testOnly raider.runtime.lineage.replay.GraphComparatorSpec"`
  → exit 0; **19 passed / 0 failed**, including FL51-01 (W20 reproducer —
  green), FL51-01b, FL51-01c.
- `sbt --batch scalafmtAll "raiderCli/scalafixAll --check" "raiderRuntime/scalafixAll --check"`
  → exit 0; `git status --short` afterward — empty (tree unchanged).
- `make quality-changed` → exit 0; “no changes vs HEAD (clean tree executes
  minimum fast)”, profiles=[fast].
- `make quality-changed QUALITY_EXECUTE=1` → exit 0; status=passed, 10/10
  checks; evidence manifest:
  `artifacts/quality/20261012-024302-85359-0001/manifest.json`.

## Conclusion

- ACCEPT: object 2 (PhaseReplay, L53), object 3 (GraphComparator fix, L54 —
  W20 reproducer is green, scaladoc == code), and RunDiffReport within object 1.
- CHANGES-REQUIRED: object 1 (WorkspaceIsolation) — F-W22R1-1
  (WorkspaceIsolation.scala:145-148): remove the debug `println`; one-line
  fix, mechanism and all tests remain green. Fix belongs in a separate fix
  card / the next card owned by the component owner, not this review.
