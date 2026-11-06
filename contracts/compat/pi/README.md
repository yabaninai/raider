# pi compatibility contracts (`contracts/compat/pi/`)

This directory holds the Raider compatibility contract for the vendored
[pi](https://github.com/earendil-works/pi) subagent corpus (card RAI-032):

- `pi-import.schema.json` — the JSON Schema (draft 2020-12) for the canonical
  import report emitted by `modules/compat-pi`
  (`raider.compat.pi.PiImportReport.renderCanonicalJson`).
- `golden/pi-import-golden.json` — the pinned canonical report for the pinned
  corpus (`vendor/pi-profiles/`, see `vendor/pi-profiles/PROVENANCE.md`).

## What the importer does (and does not do)

The importer is a **bounded, byte-faithful compatibility port**, not a
re-implementation of pi's agent runtime:

- It parses the frontmatter of `vendor/pi-profiles/upstream/agents/*.md` and
  `vendor/pi-profiles/upstream/prompts/*.md` with a parser that mirrors the
  semantics of upstream `agents.ts` (YAML subset: scalar keys, inline
  `[a, b]` arrays, block `- item` lists; no anchors, no multi-line scalars).
- It maps pi tool names onto Raider tools using the fixed table below and
  records every mapping decision in the report.
- The markdown body of an agent profile is imported verbatim as the Raider
  profile system prompt; the markdown body of a prompt is imported verbatim
  as prompt template text. No prose is rewritten or dropped silently.
- Anything it cannot represent is **explicitly recorded** as `degraded`,
  `rejected`, `ignored`, or a `parityGap` entry — never silently dropped and
  never approximated.

## Tool mapping table (fixed in `PiToolMap`)

| pi tool | Raider tool | degraded | note (summary) |
| ------- | ----------- | -------- | -------------- |
| `read`  | `fs_read`   | no       | bounded ranged workspace read with sha256 provenance |
| `grep`  | `fs_search` | no       | bounded workspace content search, no external grep process |
| `find`  | `fs_tree`   | no       | bounded tree listing with a file-name glob filter |
| `ls`    | `fs_tree`   | no       | same Raider listing capability as pi `ls` |
| `bash`  | `proc_run`  | **yes**  | argv-only, no shell, empty environment + explicit allowlist; pi shell strings are not replayable verbatim |
| `edit`  | `fs_edit`   | no       | structured single-file edit gated on expected sha256 provenance |
| `write` | —           | —        | **rejected**: no Raider single-call file-create tool; the nearest `fs_edit` requires an existing file's sha256 |
| `mcp__<server>__<tool>` | — | — | rejected: extension-provided tools have no Raider equivalent |

Profiles without a `tools` declaration get pi's default child toolset
(`read, bash, edit, write`); their policy `kind` is `piDefault` and the
unrepresentable defaults are rejected, not silently widened.

## Report semantics

- `counts.supported` = number of `field` entries across all
  `profiles[].supported` + `profiles[].toolPolicy.mapped` +
  `workflowPrompts[].supported` (43 for the pinned corpus).
- `counts.ignored` = `workflowPrompts[].ignored` entries + `referenceFiles`
  entries (9 for the pinned corpus).
- `counts.rejected` = `profiles[].toolPolicy.unsupported` entries +
  `rejectedFiles` entries (1 for the pinned corpus).
- `snapshotId` = SHA-256 over the UTF-8 lines `commit=<upstreamCommit>\n`
  followed by `file <path> <sha256>\n` for every `corpusFiles` entry sorted by
  path. It is independent of the report body: any corpus change yields a new
  snapshotId, so stale reports cannot be mistaken for current ones.
- Canonical form: object keys sorted alphabetically, 2-space indent, trailing
  newline. Byte-stable across runs and machines (no timestamps, no map
  iteration order, no locale-dependent numbers).

## Honesty rules enforced by the specs

- A degraded mapping (e.g. `bash` → `proc_run`) must appear in both the
  mapped entry (`degraded: true`) and the top-level `degraded` list.
- An unsupported tool must appear in `toolPolicy.unsupported` with a typed
  `reason`; the profile is still imported, and the file is NOT counted as
  rejected.
- A malformed profile file is rejected as a whole with a typed reason
  (`rejectedFiles`), never partially imported.
- A `tools` declaration with an unknown tool name yields an `ignored`-class
  entry for the unknown name plus a `rejected` entry for the declaration
  shape; importing never widens permissions beyond what Raider can enforce.
- Re-loading the corpus from a mutated copy must produce a different
  `snapshotId` — the pin travels with the bytes.

## Spec entry points (standalone, no sbt)

The specs are plain `main` programs in `modules/compat-pi/src/test/` (the
module is not yet registered in `build.sbt`; registration is a coordinator
action). Until then they compile and run with the pinned jar closure under
`artifacts/tools/m2`:

- `raider.compat.pi.PiImportGoldenSpec` (PIIMP-01) — 28 checks against the
  pinned corpus and this golden; `--update` re-pins the golden and requires
  review of the full diff.
- `raider.compat.pi.PiImportAdversarialSpec` (PIIMP-02/03) — 12 checks:
  unknown/malformed/missing fields, inline arrays, mutation → new snapshotId,
  missing corpus → typed `PiImportError.CorpusMissing`.

A re-pin of `golden/pi-import-golden.json` MUST be accompanied by: a corpus
update note in `vendor/pi-profiles/PROVENANCE.md` (new commit, new fetch date,
new hashes), a review of the semantic diff, and refreshed gate evidence.
