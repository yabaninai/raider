# VERIFIER-EN: English empty dependency label

Separate verifier maintenance card for the owner-requested English documentation migration (2026-10-13).

Allowed paths: `scripts/plan/validate.py`, this card, and its post-execution work record.

Change only the expected display token for an empty dependency list from the former Russian word to `none`. Keep all card-ID, dependency, mirror-field, count, link, and prompt consistency checks unchanged. Task Markdown is translated by its own documentation lanes.

Acceptance: independently review the exact diff; show that the English empty list passes and a wrong dependency label still fails, and run the integrated plan validator. No threshold, coverage, or protected fixture reductions.
