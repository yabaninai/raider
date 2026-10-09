# Task: structured-extraction (RAI-081, EVAL-01)

Workspace: the `fixture/` copy you receive. It contains HTTP access logs of a
small service in `logs/server-{a,b,c}.log`. Do NOT modify any file.

Extract every log line whose level is `WARN` or `ERROR` into a single
`events.json` in the workspace root. Lines with level `DEBUG` or `INFO`, and
lines without an `id=` field, are ignored.

## Log line format

Whitespace-separated fields:

```
<timestamp-ISO8601-UTC> <LEVEL> req id=<ID> status=<INT> ms=<INT>
```

## Output schema

`events.json` — one JSON object with key `events`, a JSON array sorted by `id`
in ascending lexicographic order. Each element has exactly these keys:

- `id` (string, without the `id=` prefix)
- `ts` (string, the ISO8601 timestamp, unchanged)
- `level` (string: `WARN` or `ERROR`)
- `status` (integer)
- `ms` (integer)

## Grading

A checker outside the workspace parses `events.json` and compares it with the
ground truth (schema and values). Formatting/indentation of the JSON file does
not matter. The logs themselves must remain byte-identical.
