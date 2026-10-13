# P0-WAVE0: Work Record

Status: done (within the completed cards; roadmap P0/P1 continues).
Date: 2026-10-06 UTC. Baseline commit: `7ee6c0c` + uncommitted planning
documents (see [ROADMAP-12K](ROADMAP-12K.md)); the final tree is the integrated
candidate for this record. Execution: coordinator + at most 2 parallel writers
in isolated snapshot lanes (without `.git`); reviewers were independent
read-only agents; no self-acceptance.

## Resulting behavior

1. **P0.A** — actual source/board/evidence matrix for W01–W18 and UX01–08:
   [docs/reviews/2026-10-05-p0a-gap-matrix.md](../../reviews/2026-10-05-p0a-gap-matrix.md).
   Two quality runner defects were confirmed and a secret-in-argv defect was
   found (`run/CliArgs.scala:67 --api-key`); send/watch/cancel obligations were
   recorded as P3 (not a regression). The pre-existing validator blocker
   (broken ADR links in untracked research) was closed with a targeted link
   fix that preserved the contents.
2. **P0.B** — [ADR-024](../../adr/ADR-024-runtime-ui-event-seam.md): frozen
   runtime→UI event seam (envelopes, two delivery classes, subscribe without a
   loss window, progress/honest-unknown, focus rules). After independent review
   and fixes, frozen for P1/P2; 2 review rounds, ACCEPTED after resolving the
   only residual (CJK typo).
3. **RAI-054** (M0) — quality runner fix: `classify` file-keyed; git-mode
   fingerprint (staged/unstaged/untracked/deleted/renamed; rename = delete+add);
   loud error “Git unavailable: isolated snapshot” instead of silently using
   fast mode; manifest diff includes symlink target; run_id is unique
   (timestamp-pid-seq); self-check covers classify/git/manifest/decision/run-id.
   First honest green `make quality-changed QUALITY_EXECUTE=1` on a dirty tree.
4. **RAI-055** (M1, P1.A slice) — `raider.runtime.events` package: 13 payload cases
   per ADR-024 §5, JSON codec (roundtrip for every case + unknown-discriminator
   rejection), EventError sealed family, pure SessionReducer (dup-seq is
   idempotent, regression/post-Terminal → Malformed, LineTail 8×2000), EventHub
   (reliable 256 / diagnostics 1024, no gaps in per-job seq, subscribe without
   loss window, typed Backpressure, diagnostics drop+counter). A real defect
   was found and fixed: in zio 2.1.26, `Queue.bounded.offer` suspends, so the drop path was
   dead code; replaced with STM try-acquire. Also fix F1 (fold-commit before
   offer closes the loss-window race). All 99 module tests pass; EventHubSpec
   stable ×3.
5. **RAI-056** (M0, P0.C) — inline renderer PTY spike + probe: JLine printAbove
   preserves the draft across 200 background writes (Cyrillic/emoji/CJK),
   TERM=dumb plain fallback, 3×67 overlapping bursts — 201/201 atomic lines,
   0 lost; baseline p50≈0.11–0.15 ms / p95≈0.22–0.29 ms (machine-clock
   baseline, NOT UX thresholds). WCWidth table: a=1, U+044F=1, 漢=2, 😀=2,
   combining=1, ZWSP=0. Probe writes evidence into the repository
   (`artifacts/renderer-spike/`).

Public API/schemas: new package `raider.runtime.events` (ADR-024); no other
public contract changes; TraceEvent/JobManager/AgentLoop were unchanged.

## Changed paths

- `docs/reviews/2026-10-05-p0a-gap-matrix.md` (new), `docs/adr/ADR-024-runtime-ui-event-seam.md` (new)
- `docs/research/yabanin-state-discovery.md` (targeted fix for 2 broken links)
- `docs/tasks/backlog.json`, `docs/tasks/m0.md`, `docs/tasks/m1.md`, `docs/tasks/README.md` (RAI-054..057 cards and statuses)
- `scripts/quality/quality.py`, `scripts/quality/README-quality-runner.md` (RAI-054)
- `modules/runtime/src/main/scala/raider/runtime/events/{events,errors,codec,reducer,hub}.scala` (RAI-055, new)
- `modules/runtime/src/test/scala/raider/runtime/events/{EventCodecSpec,SessionReducerSpec,EventHubSpec}.scala` (RAI-055, new)
- `modules/repl/src/test/scala/raider/repl/spike/InlineRendererSpike.scala` (RAI-056, new)
- `scripts/quality/renderer_pty_probe.py` (RAI-056, new)
- `docs/work/board.md`, this record

Key sha256 hashes of integrated artifacts: quality.py `60b6dc74…`, README-runner
`857faaa1…`, hub.scala `b057f5a3…`, events.scala `8f0f427f…`, codec.scala
`f36a75c2…`, errors.scala `01ddcf53…`, reducer.scala `b5c61797…`, spike
`6cb137c8…`, probe `2d5e92fe…`.

## Acceptance evidence

| ID | Actual result | Evidence |
| --- | --- | --- |
| RAI-054 QUALITYRUN-01 | docs-only diff → execute exit 0, status=passed (scratch git-repo fixture) | lane-a-evidence/round-2/qr01-manifest.json |
| RAI-054 QUALITYRUN-02 | unmapped → explicit refusal, exit 1 before gate | lane-a-evidence/round-2/qr02-execute.log |
| RAI-054 QUALITYRUN-03 | run_id unique sequentially; cross-process uniqueness is structural (pid) | self-check line; README limitation |
| RAI-055 EVT-01..04 | 99 passed / 0 failed in raiderRuntime module; EventHubSpec stable ×3 | lane-a2-evidence/RAI-055.b-iii-gates.log |
| RAI-056 RENDER-01..04 | 4/4 PASS on integrated tree; baseline recorded | artifacts/renderer-spike/renderer-pty-summary.json |
| Integrated gates | `quality-changed QUALITY_EXECUTE=1` passed 7/7; `quality-profile static unit contracts runtime` passed 5/5 | artifacts/quality/20261006-120717-54115-0001, artifacts/quality/20261007-005445-83640-0001 (+ final run after docs edits — see Commands) |
| Plan validator | passed; 60 cards; markdown/index/backlog aligned | artifacts/plan-review/report-p0a2.json and final run |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `python3 scripts/plan/validate.py --out artifacts/plan-review/report-p0a2.json` | 0 | passed, 60 cards |
| `make quality-changed QUALITY_EXECUTE=1` (after RAI-055+056 integration) | 0 | artifacts/quality/20261006-120717-54115-0001/manifest.json |
| `make quality-profile PROFILES="static unit contracts runtime"` | 0 | artifacts/quality/20261007-005445-83640-0001/manifest.json |
| `make quality-changed QUALITY_EXECUTE=1` (FINAL, after board/record/statuses) | 0 | artifacts/quality/20261007-010036-85447-0001/manifest.json — passed 7/7 |
| `python3 scripts/plan/validate.py --out artifacts/plan-review/report-final.json` | 0 | passed, 60 cards |
| `python3 scripts/quality/renderer_pty_probe.py` (root, without env) | 0 | artifacts/renderer-spike/renderer-pty-summary.json |
| `python3 scripts/quality/quality.py self-check` | 0 | stdout (run-id/git/manifest/decision tests) |
| Lane runs by writers (sbt test/scalafmtCheckAll, PTY probe) | 0 | lane-*-evidence/ (paths above) |

## Limitations / blockers

- **Final docs edits (board/record/statuses) were made after the last execute
  run** — affected docs gates were rerun with the final
  `make quality-changed QUALITY_EXECUTE=1` (see Commands); the manifests above
  each apply to their own fingerprint.
- **RAI-055 is not connected to emitters**: AgentLoop/JobManager emission is
  P1.B/P2.C (separate cards with cancellation/ownership/budget tests). Review
  informational notes (interruption between put and offer; forwarder
  supervision) are recorded as future obligations, not defects in this slice.
- **RAI-054**: cross-process run_id uniqueness is structural (pid); in CI, pid
  reuse in the same second could theoretically collide — documented. The hash
  of the baseline manifest itself is not written to run-manifest (separate
  future card).
- **RAI-056**: baseline numbers are machine-clock measurements; UX01–08
  thresholds are NOT proven; line atomicity is checked on the final cleaned
  stream.
- **Subagent transport infrastructure failures**: 6 “empty” returns during the
  session (SSE timeouts); recovered through retries and package decomposition;
  no work lost (lane manifests checked before/after).
- `--api-key` in argv (W15/UX01) was found and NOT fixed (separate card).
- Human usability (UX01–08 with participants), soak, live verification remain
  pending per roadmap; none are claimed here.

## Review and next task

Independent reviews (all by fresh read-only agents): RAI-054 ACCEPTED (round 2);
RAI-056 ACCEPTED (round 2); ADR-024 ACCEPTED after D3 fix; RAI-055 —
CHANGES-REQUIRED (F1 MEDIUM + 3 LOW) → b-iii fixes → **ACCEPTED** (delta
re-review); probe fix ACCEPTED. Board updated.

Build skeleton `raiderUi` added to build.sbt (minimal diff 8+/2-, compile
green, EXECUTE passed 7/7 — artifacts/quality/20261007-010442-87399-0001);
RAI-057 moved to ready. Next ready (per roadmap-12k §5 DAG): **RAI-057**
SessionRegistry (P2.A) — frozen ADR-024 types (available), skeleton (available).
Then P1.B (admission/model/tool/retry/budget emitters in AgentLoop over hub) and
P2.B/C (inline shell on JLine + PTY integration). Early Yabanin/P11 remains
forbidden until M4.
