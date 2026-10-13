# RAI-004 (MVP slice): work record

Status: done (review pending; RAI-004 as a whole remains open for stage-aware
full/milestone semantics, coverage, and scanner). Date: 2026-10-03 (UTC).

## Resulting behavior

Quality runner MVP: `scripts/quality/quality.py` (Python, an allowed area) with
protected registry `scripts/quality-policy/registry.json`:
- `changed [--base-manifest|QUALITY_BASE_MANIFEST]` — classify changes
  (added/modified/deleted/mode) using a path→profiles matrix; empty baseline → fast;
- `execute [--profiles]` — component-stage gates (self/static/unit/contracts/docs/
  fast), honest exit codes, timeout, logs+sha256, evidence manifest (schema §5:
  source fingerprint, policy_sha256, toolchain, gates, test_summary);
- `verify MANIFEST --require P --fresh` — statuses, log hashes, policy hash,
  fingerprint freshness; fabricated manifests are rejected;
- `self-check` (registry integrity + verify-negative) and
  `self_test_fault_injection.sh` (fake sbt/python3 → execute FAIL).
Makefile targets: quality-changed / quality-changed-execute / quality-profile
PROFILES=... / quality-verify MANIFEST= PROFILES= / quality-inventory.

## Commands (actual)

| argv | Exit |
| --- | --- |
| `python3 scripts/quality/quality.py self-check` | 0 |
| `python3 scripts/quality/quality.py execute` | 0 (5/5 gates passed; artifacts/quality/20261003-022434/) |
| `python3 scripts/quality/quality.py verify <manifest> --require fast` (freshness is the default) | 0 |
| `sh scripts/quality/self_test_fault_injection.sh` | 0 (artifact: artifacts/quality-fault-injection.log) |
| Classification: docs-only → [docs]; modules → [contracts,static,unit] | verified |

## Limitations

- full/milestone profiles are not defined yet (they will appear at their stages;
  the runner already rejects a non-component profile as not-executable, not skip);
- static gate = sbt compile with -Werror; scalafmt/scalafix/scanner are the next
  RAI-004 iteration after versions are selected;
- verify --fresh recalculates the fingerprint over the whole tree (slow on large
  repositories — acceptable for the MVP);
- coverage/JUnit parsing/test_summary from machine-readable reports is a stub
  (counts gates, not tests) until ZIO Test (RAI-006).

## Round-2 independent review (read-only explore) — closed

11 findings were found (1 HIGH, 2 MED-HIGH/MEDIUM critical to false-green). All
were fixed and rechecked on the final tree:

| Finding | Fix | Check |
| --- | --- | --- |
| `make quality-changed QUALITY_EXECUTE=1` was a green no-op (HIGH) | Makefile: QUALITY_EXECUTE=1 invokes execute | EXECUTE=0, 5/5 gates |
| Dead `__unmapped__` guard: unmapped files silently escaped gating | In diff mode, execute fails and lists unmapped files | `execute-diff-unmapped=1`, message lists files |
| Fingerprint included `modules/*/target/**` (494/567 entries) | Segment-based excludes at any depth | 0 target files, 75 files in manifest |
| `verify` without `--fresh` accepted stale evidence | Freshness is default; `--allow-stale` is explicit historical mode | stale→1, allow-stale→0 (on a passing manifest) |
| Overclaim “verify rejects fabricated” | Wording changed to honest tamper-evidence | self-check output updated |
| Boundary: `modulesX` matched `^modules/` | Segment boundary `alt + "/"` | modulesX → unmapped → execute FAIL |
| 27/27 vs actual 26/26; .PHONY; PROFILES usage; timestamps/mode/dead code/timeout decode; weak python3 fault-injection mode | Records corrected to 26; .PHONY extended; usage comma+space; started≠finished; mode from env; dead code removed; TimeoutExpired is str-safe; second fake `sh` mode (compile-fixtures gate) | QFI: sbt+sh both fail correctly |

Final evidence: `make quality-changed QUALITY_EXECUTE=1` → passed 5/5
(artifacts/quality/20261003-030303/), `verify --require fast` → fingerprint
fresh; path to the last passing manifest: artifacts/quality/LAST-PASSING.

## Review and next task

Round-2 review: findings closed (table above); fix round recorded. RAI-004 remains
open (full/milestone profiles, scanner, coverage, JDK policy). Next: F1 — RAI-006
testkit (ZIO Test + scripted ModelBackend) and RAI-007 jobs.
