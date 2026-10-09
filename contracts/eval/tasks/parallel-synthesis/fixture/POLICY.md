# Synthesis policy

Merge the per-agent reports in `reports/` into one `synthesis.json`.

1. Per module, the report with the LATEST `generated_at` (UTC, ISO8601) wins.
   Both `status` and `items` come from the winning report.
2. `total_items` is the sum of `items` over all modules after the merge.
3. `conflicts` lists module names (alphabetically) where at least two reports
   disagree about `status`.

Timestamps across the reports are unique, so no tie-breaking is needed.
