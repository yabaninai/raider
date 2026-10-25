# Quality runner (`scripts/quality/quality.py`)

Single source of truth for the `make quality-*` targets. Policy (profiles,
gates, path patterns, fingerprint excludes) lives in the protected
`scripts/quality-policy/registry.json`; changing it needs a separate card and
independent review.

## Change sources (docs/quality-gates.md §2)

| Mode | Trigger | Changes come from |
| --- | --- | --- |
| manifest | `--base-manifest P` or `QUALITY_BASE_MANIFEST` | diff vs the immutable inventory: added/modified/deleted (hash, mode, symlink target) |
| git | no base | `git status --porcelain -z --untracked-files=all` in the repo root: staged, unstaged, untracked, deleted, renamed |
| clean tree | git reports no changes | empty → minimum `fast` profile |
| snapshot lane (no `.git`) | no base AND no `.git` / no git / git error | hard error `Git unavailable: isolated snapshot` — never a silent fast run; use manifest mode |

- `inventory --out P` writes the base manifest and needs **no git**
  (snapshot-lane bootstrap). `verify` also uses the tree inventory only, so
  both work in every mode.
- `--untracked-files=all` expands untracked directories to per-file records;
  default porcelain mode collapses them to `?? dir/`, which would break
  path-based classification.
- Change paths are filtered through the same `fingerprint_excludes` as the
  inventory, in both modes.
- A missing or corrupt baseline manifest is an explicit error, not a pass.
- Git mode reports exactly the git-visible changes vs HEAD; git-ignored files
  (`*.log`, `/https/`, `.env*`) are invisible to it, while manifest mode
  would still see them on the filesystem and classify them through
  `path_profiles` (unmapped → hard refusal) — the fail-safe direction.
- The manifest diff compares hash, mode and symlink target (spec §2); an
  untouched tree yields an empty diff, which executes the minimum `fast`.

## Rename handling (documented choice)

A git rename (`R`) is expanded as `deleted(old) + added(new)` — the treatment
§2 sanctions for manifests ("rename may be represented as delete+add").
Classification takes the union of both locations, which is conservative:
moving a file to a lower-risk directory does not drop the origin's profiles.

## RAI-054 defect history (fixed 2026-10-06)

1. `classify()` returned a profile-keyed dict `{profile: []}` while its
   annotation and `cmd_execute` expected file keys. `cmd_execute` looked a
   file path up in the profile-keyed dict, always missed, flagged **every**
   changed file as `__unmapped__` and exited 1 before running any gate — for
   any non-empty diff, git or manifest mode. `classify()` now returns
   `{path: [profiles]}` (sorted; `["__unmapped__"]` when no pattern matches).
2. `fingerprint(None)` filled `changes` only in manifest mode; without a base
   manifest it always returned an empty list, so a dirty tree looked clean
   ("no changes vs base", minimum fast) and git was never consulted. It now
   runs git mode as described above and hard-fails when git is unavailable
   without a manifest.

Consequence: `cmd_execute` computes the per-file classification once, refuses
only truly unmapped files (listing them), and runs the union of per-file
profiles. `cmd_changed` prints that union (`__unmapped__` appears as a
pseudo-profile when present). The manifest `mode` field now records
`git-status` for the no-base run (was mislabelled `full-tree`).

### Round 2 (independent reviewer findings, fixed 2026-10-06)

1. Manifest-mode self-test was missing (card behavior requires self-test
   including manifest mode, git mode and unmapped refusal). Added
   `_self_test_manifest_fingerprint`: temp tree, base inventory via the
   production `inventory()`, then modify/delete/add + symlink re-point →
   `fingerprint()` must yield exactly added/modified/deleted for exactly
   those paths. This also exposed and fixed a real gap: the diff compared
   only sha256+mode, so a symlink TARGET change (spec §2 requires it) was
   silent — `_manifest_diff` now compares `target` as well.
2. `run_id` was second-granularity: two parallel executes in the same second
   shared one run dir and clobbered logs/manifest (QUALITYRUN-03). Now
   `<YYYYmmdd-HHMMSS>-<pid>-<seq>` (per-process monotonic seq under a lock);
   self-check asserts two successive calls differ and are filesystem-safe
   (proves in-process uniqueness; cross-process same-second = distinct PIDs).
3. `git status` output was parsed with `text=True` (universal-newline
   translation could mangle a path containing CR; fail-closed before). Now
   parsed as bytes: split on `b"\0"`, decode `utf-8/surrogateescape`.
4. `git_changes` docstring overclaimed "empty list ONLY for a clean tree"
   w.r.t. git-ignored files; reworded to state the git-visible guarantee and
   the fail-safe direction (mirrored above in the change-sources list).

## Self-tests (`self-check`)

- registry integrity: every component-profile gate exists;
- classify: docs file → `["docs"]`; modules/ Scala file →
  `["contracts", "static", "unit"]`; unknown path → `["__unmapped__"]`;
- git-mode fingerprint on a **temp git repo** (system temp dir only, never the
  repo tree): clean tree → empty changes; dirty tree → modified/deleted/
  untracked/renamed kinds correct (rename as delete+add);
- manifest-mode fingerprint on a **temp tree without git** (system temp dir
  only; the run also proves manifest mode never needs git): base inventory
  written with the production `inventory()`, then modify/delete/add +
  symlink re-point → `fingerprint()` yields exactly added/modified/deleted
  kinds for exactly those paths; untouched tree → empty;
- execute decision (no gate execution): docs-only change selects exactly
  `["docs"]`; an unmapped file fails the decision;
- run-id uniqueness: two immediately successive calls in one process differ
  and match the filesystem-safe charset. This proves in-process uniqueness
  only; cross-process same-second uniqueness rests on distinct PIDs
  (documented limit of `_next_run_id`, no full parallel gate run here);
- gate budgets frozen (RAI-209): every gate's `timeout_s` in the registry is
  pinned against an in-test map (`sbt-test` calibrated to 1800 s, owner
  decision 2026-10-26 — board 2026-10-25 follow-up #4; contention
  calibration, identical assertions); gate-set drift fails the check;
  an over-budget probe run through the production `run_gate` classifies as
  `timed_out` + `failed` (never skip/pass) while a fast control probe passes,
  proving the timeout classification is real, not vacuous;
- verify-negative (pre-existing): a fabricated/tampered manifest must be
  rejected.

The verify-negative fixture writes to `artifacts/quality/self-negative/`
(runtime evidence, excluded from the fingerprint) — same as before RAI-054.

## Evidence binding

`execute` writes `artifacts/quality/<run-id>/manifest.json` plus one log per
gate. `run-id` is `<YYYYmmdd-HHMMSS>-<pid>-<seq>` with a per-process monotonic
`seq`, so parallel executes can never share a run dir and clobber each
other's logs/manifest (the round-1 second-granularity collision,
QUALITYRUN-03, is fixed). The manifest binds: full source file inventory,
its sha256 fingerprint, policy sha256, toolchain snapshot, selected profiles,
and each gate's argv/exit code/log hash. `verify` re-checks all of it; a changed
source fingerprint, policy, or log makes the evidence stale
(`--allow-stale` marks it historical). Evidence is only valid for the exact
fingerprint it was taken from — a different tree needs rerun gates.
