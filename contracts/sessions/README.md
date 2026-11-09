# Session archives (`contracts/sessions/`)

Frozen wire contract for Raider conversation session archives (card RAI-036,
acceptance SESSION-01/02): the durable prompt/reply transcript of one
conversation session, stored as ONE JSON document per session at
`~/.raider/conversations/<sessionId>.json`.

## Schema v1 (current)

Writer: `raider.runtime.conversation.ConversationArchive.encodeDocument`
(manual AST codec — exact field order, compact one-line encoding, `label`
omitted when absent, never null).

Reader: `ConversationArchive.decodeDocument` — the envelope is strict, the
body is forward-compatible:

- The document must be a JSON object with an EXPLICIT integer
  `schemaVersion`. There is no v0 and no migration: a missing field is a
  typed refusal.
- `schemaVersion` greater than the reader's supported version is a typed
  refusal naming the upgrade — a document written by a newer Raider is never
  read silently.
- Unknown EXTRA fields inside the document are IGNORED by the codec
  (forward-compatible reads). This is a documented divergence from
  `session-archive.schema.json`, which is stricter
  (`additionalProperties: false`); the schema is the reviewer-facing spec,
  the codec is the wire behavior.

Field order (pinned by `expected/archive-golden.json`):

```
schemaVersion, sessionId, rootId, model, label?, archivedAtMs, turns
```

- `schemaVersion`: integer, always `1` on write.
- `sessionId`: strict single path segment `[A-Za-z0-9._-]`, no leading dot,
  1..64 chars (enforced at the store boundary, mirrored from the CLI session
  store).
- `rootId`: the conversation root that OWNED the archived turns
  (runtime-contracts §5). A reopened conversation keeps the `sessionId` but
  always gets a FRESH root — an archived root is never reused.
- `model`: the session model recorded at archive time.
- `label`: optional human label; omitted (absent key) when unset.
- `archivedAtMs`: non-negative epoch milliseconds.
- `turns`: strict prompt/reply pairs — pairing IS the schema; there is no
  per-turn role field, and no reply is ever invented for a prompt.

Semantics carried by the archive (SESSION-02): reopening an archive seeds a
new conversation and executes NOTHING until the next explicit ask — no
implicit agent restart, no silent compaction (an oversized transcript is a
typed budget refusal before any turn runs).

## Files

- `session-archive.schema.json` — JSON Schema (draft 2020-12) for v1
  documents.
- `expected/archive-golden.json` — BYTE-PINNED encoding of a fixed archive;
  runtime tests compare `encodeDocument` output to this file byte for byte.
  Re-pin ONLY with a schema-version change or a reviewed codec change, and
  record the re-pin in the work record.
- `expected/archive-rejected.json` — documents the codec must refuse, each
  with the exact prefix of the typed refusal reason. The list covers exactly
  the codec's own refusal set; transport-level corruption (broken JSON) is
  tested by the store suite without a pinned reason (parse messages are not
  stable).
