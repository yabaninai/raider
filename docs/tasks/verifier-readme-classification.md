# VERIFIER-README-CLASSIFICATION: точный путь README.md в docs classifier

Status: done (reviewed correction; final main receipt required).
Date: 2026-10-13. Baseline: `073d617` + USER-DOCS-CURRENT candidate.
Coordinator-owned separate verifier card, discovered during required docs checks.

## Problem and scope

Registry path patterns use segment-prefix matching (`classify` in quality.py).
The existing `^README` pattern covers a bare README, not `README.md`; a README.md
edit is unmapped and execute refuses before running gates. Add the exact path
alternative `^README.md` to the existing docs classification, preserving all
old patterns. This follows the existing docs policy and fixes a file-name mismatch.
No changes to gates, stages, thresholds, hidden fixtures or classifier code.

## Allowed paths / owner

Coordinator exclusively owns `scripts/quality-policy/registry.json`, this task,
`docs/work/records/VERIFIER-README-CLASSIFICATION.md`, board status.
This is separate from the product/user-docs card, whose protected-policy non-goal
still applies. Independent reviewer read-only; policy edit after explicit review.

## Acceptance

- VREADME-01: README.md maps to docs, README still maps to docs, a similarly named
  README-other.md remains unmapped. Exact alternatives preserve existing behavior.
- VREADME-02: gate definitions/profiles/stage policy/excludes byte-content semantics
  unchanged; only the exact matching alternative added. Independent review.
- VREADME-03: self/static/contracts/security active coverage (conservative fast +
  docs/security) and integrated changed/execute pass. Final fingerprints bind policy.

## Non-goals

Broadening ignore rules, suppressing unknown paths, making empty suites pass,
changing verifiers, removing tests or reclassifying product inputs as docs.
Work record after actual checks; this correction does not accept user examples.
