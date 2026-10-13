# DOCS-HANDBOOK-REFRESH: practical user documentation

Date: 2026-10-13. Baseline: main `e8ec85e`.
Card: `docs/tasks/handbook-refresh.md`.

## Result

The README now offers a task selection table and a deterministic first result.
Onboarding starts with a typed release-readiness bundle, then introduces the
REPL, offline coding/audit, and optional provider configuration. The handbook
connects the billing defect, configuration finding, parallel asks, readiness
report, and local CI run to concrete inputs, commands, outputs, and artifacts.

The Scala guide explains lazy asks, typed composition, batches, jobs, and
sessions using current APIs. The CI and lineage walkthroughs distinguish the
historical six-root live run from the newer unified DSL artifact generated from
the offline acceptance corpus. The new `native-projections.md` reproduces all
six full historical projections, including original data references and the
misleading historical recovery annotation, with an explicit explanation.

The changes correct the replay instructions, artifact/workspace locations,
summary payload description, classpath setup, and navigation. Legacy anchors
keep existing links to the renamed sections valid. RAI-153/154 wiring limits
remain explicit. No API, runtime, build, protected fixture, or gate-policy file
changed. All prose is in English.

## Execution and review

Eight visible Luna agents worked in isolated `wt/handbook-refresh-1..8`
worktrees, each on its own branch. Five writers owned disjoint user guides;
three readers audited commands, newcomer navigation, and projection semantics.
The coordinator owned `wt/handbook-integration`, README, the full historical
projection page, task card, and this record. Lane commits were merged one by
one into the integration branch. Main remained available to other work.

The owner's no-general-checks instruction remains in force for this
publication-only follow-up. No build, runtime test, replay, or paid provider
call was executed. Historical live/offline results retain their original scope
and are not presented as fresh verification of the current checkout.

Static checks in the integration checkout:

| Command or comparison | Result | Evidence |
| --- | --- | --- |
| `python3 artifacts/handbook/check-docs.py` | exit 0; relative links/anchors valid, tracked text contains no Cyrillic, six projection strings match the capture exactly | `artifacts/handbook/static-review.json` |
| `git diff --check` | exit 0 | integration command output |
| Source comparison of commands, snippets, and expected outputs | reviewed against build scripts, verifier selectors, bundle implementation, CLI/REPL APIs, projection/replay sources | `artifacts/handbook/command-review.json` |
| Independent semantics review | current projection versus old live capture and offline corpus distinguished; pending wiring disclosed | `artifacts/handbook/review.json` |
| Newcomer reading review | task selection and result inspection path reviewed; clarity recommendations incorporated | `artifacts/handbook/reader-review.json` |

The new historical projection page is derived solely from the checked-in
`successful-run.json`. The complete unified artifact retains SHA-256
`77339a8b649e4d7731db79536dcde6de70331f2f1668b41c7dfc0fc0801120cc`.
Both earlier live captures and the protected unified corpus/artifact remain
byte-for-byte unchanged from the baseline.

## Limits and next work

This is a documentation improvement, not a new runtime acceptance claim.
RAI-153 (live phase emission) and RAI-154 (headless delegation and unified
artifact wiring) are the registered next implementation work relevant to
these examples. Revisit the guides after those cards land and produce new
execution evidence; preserve the existing historical captures.
