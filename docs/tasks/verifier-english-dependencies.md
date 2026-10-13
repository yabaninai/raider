# VERIFIER-EN: English empty dependency label

Separate verifier maintenance card for the owner-requested English documentation migration (2026-10-13).

Allowed paths: `scripts/plan/validate.py`, this card, and its post-execution work record.

Change only the expected display token for an empty dependency list from the former Russian word to `none`. Keep all card-ID, dependency, mirror-field, count, link, and prompt consistency checks unchanged. Task Markdown is translated by its own documentation lanes.

Acceptance: independently review the exact diff; show that the English empty list passes and a wrong dependency label still fails, and run the integrated plan validator. No threshold, coverage, or protected fixture reductions.

## Follow-up: English throughout tracked source (2026-10-13)

The owner expanded the language requirement to every tracked file and explicitly
requested no builds, tests, or quality gates for this follow-up. The isolated
`docs/all-files-8` lane translates protected verifier comments and test strings.
Allowed paths: `scripts/quality/{quality.py,tabs_pty_probe.py,renderer_pty_probe.py,ux_baseline.py}`
and `contracts/eval/tasks/review-planted-bug/hidden-tests/check.sh`.

Preserve verifier logic, thresholds, assertions, Unicode editing coverage, and
recognition of existing answer variants. The hidden evaluator's compatibility
patterns may use byte escapes to preserve matching without embedding Russian
prose in source. A separate reviewer inspects the exact diff before integration;
static review is not reported as an executed acceptance check.
