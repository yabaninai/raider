# Task: parallel-synthesis (RAI-081, EVAL-01)

Workspace: the `fixture/` copy you receive. `reports/` contains four status
reports produced by different subagents; `POLICY.md` defines the merge rules.
The same module may appear in several reports with different `status`.

Merge the reports into a single `synthesis.json` in the workspace root by
applying POLICY.md exactly. Do NOT modify any other file.

## Output schema

`synthesis.json` — one JSON object with exactly these keys:

- `modules`: object keyed by module name; each value is an object with
  exactly `status` (string) and `items` (integer) — taken from the winning
  report for that module;
- `total_items`: integer — the sum of `items` over all modules after merge;
- `conflicts`: array of module names (alphabetically sorted, no duplicates)
  where at least two reports disagree about `status`.

## Grading

A protected checker outside the workspace parses `synthesis.json` and
compares it with the ground truth (schema and values, formatting-independent).
The reports and POLICY.md must remain byte-identical.
