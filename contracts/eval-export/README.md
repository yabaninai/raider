# Agent-eval export contracts (`contracts/eval-export/`)

This directory holds the pinned export contract for agent-evaluation runs
(card RAI-040, module `modules/eval-export`). The module is a PURE transform:
a validated in-memory `EvalRunInput` (assembled by the caller from journal /
trace / verifier evidence) becomes six deterministic JSON documents that a
Yabanin-compatible run-directory consumer can read back standalone — no
server, no database, no compile-time dependency on other Raider modules.

- `schemas/` — six JSON Schema (draft 2020-12) files, one per export
  document, named `<document>.schema.json`:
  - `manifest.json.schema.json` — identity, lineage, coverage, section
    integrity digests.
  - `episodes.jsonl.schema.json` — one record per episode: completion,
    verifier state, the four canonical outcomes (execution, task, safety,
    protocol).
  - `calls.jsonl.schema.json` — one record per model call with usage
    (unknown prices are explicit nulls).
  - `tools.jsonl.schema.json` — one record per tool call: outcome,
    argument digests / pinned redacted-safe args, env NAME references,
    artifact hashes.
  - `verdicts.jsonl.schema.json` — one record per actual verifier verdict.
  - `reconciliation.jsonl.schema.json` — append-only, self-chained late-usage
    corrections.
- `expected/` — pinned byte-exact golden run directories produced by the
  deterministic fixtures in `modules/eval-export/src/test/` (Fixtures.scala):
  - `normal-run/` — complete two-episode run: root→child job lineage, two
    verdicts, three model calls (one with a null reserved price), four tool
    calls covering succeeded/failed/denied results, digested raw args, one
    explicitly pinned redacted-safe args object, env NAME references, one
    artifact hash.
  - `cancelled-run/` — partial run: one completed episode with a verdict, one
    cancelled episode whose declared verifier produced nothing (`pending`),
    one interrupted episode with no declared verifier (`absent`); no
    `finishedAtMs`; `runStatus: partial`.
  - `unknown-price/` — honestly unknown usage prices (explicit nulls plus
    `unknown: true`), never zeros.
  - `late-usage/` — `cancelled-run` after two versioned late-usage appends;
    every document except `reconciliation.jsonl` is byte-identical to
    `cancelled-run`.

## File shapes

- `manifest.json` is a single JSON object; the `*.jsonl` files carry exactly
  one record per line, UTF-8, LF-terminated. An empty section is a
  zero-byte file (never an omitted file: a run directory always contains all
  six documents).
- Every record carries `schemaVersion: 1`. A shape change is a new
  schemaVersion plus a revision of this contract — never a silent edit.
- Optional fields are written as EXPLICIT `null` (never omitted): the
  difference between "no evidence" and "value zero" is visible in the JSON
  AST of every document.

## Digest rules

All hashing uses JDK `java.security.MessageDigest` SHA-256, lowercase hex,
64 characters — the same convention as the rest of the repository
(modules/core provenance hashing, lineage GraphHash, modules/compat-pi
Hashing). No second hashing scheme, no new dependencies.

- Tool arguments: the raw argument text as received from the model is
  digested (`argsDigest` = sha256 over its UTF-8 bytes) and NEVER exported.
  Verbatim argument text travels only as `argsRedactedSafe` when the caller
  explicitly pinned the args redacted-safe (a deliberate, reviewable act);
  the digest is then computed over the same pinned text, so both views agree.
- Verifier evidence: each verdict pins `evidenceSha256` over the evidence
  artifact (e.g. `verify.sh` stdout). A produced task outcome repeats the
  same digest; safety/protocol outcomes carry their own evidence digest when
  evidence exists.
- Artifacts: referenced by `sha256` + `mediaType` + `sizeBytes`; content
  never travels in the export.
- Section integrity: the manifest pins `episodesSha256`, `callsSha256`,
  `toolsSha256`, `verdictsSha256` over the exact file bytes.
  `reconciliation.jsonl` is deliberately NOT pinned in the manifest: it is
  append-only and self-chained via `prevFileSha256` (sha256 of the file
  content before each append; the sha256 of the empty byte string
  `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` anchors
  the first record).

## Honesty rules (enforced by code, not only prose)

- Verdicts exist ONLY from actual verifier results. An episode whose declared
  verifier produced nothing exports verifier state `pending` with the
  canonical reason `verifierExpectedButMissing`; an episode without a
  declared verifier exports `absent` with `noVerifierDeclared`. Neither is
  ever upgraded to a verdict. A missing verifier cannot fabricate a verdict —
  the exporter rejects an input that contains a verdict for an episode which
  did not expect one (`VerifierMismatch`), and `verdicts.jsonl` stays empty.
- Unknown prices stay explicitly `null` and are marked `unknown: true`;
  `unknown: false` with a null observed price is a typed input error
  (`BadUsage`). No 0-conflation, no fabrication.
- Environment references are NAME-shaped (`^[A-Za-z_][A-Za-z0-9_]*$`); a
  name=value pair is rejected at the input door (`BadEnvRef`). Values never
  travel in any document.
- Interrupted / cancelled runs export consistent PARTIAL episodes with an
  explicit terminal state (`cancelled` / `interrupted` / `uncertain`), a
  non-empty reason, and `runStatus: partial` in the manifest. A cancellation
  is never rewritten into a fake completion.
- Late-arriving usage appends a VERSIONED reconciliation record
  (`rc-<n>`, per-call `reconciliationVersion` 1..n, `previousUsageSha256`
  pinning what it corrects) and leaves every prior document byte-identical —
  the manifest is never rewritten and prior usage numbers remain visible as
  `previousUsage` of each record.
- Every attempt carries exactly one episode record (1:1), so execution
  evidence cannot silently disappear; lineage (root→job→attempt) is
  reconstructible from the export alone via `Lineage.reconstruct`.

## Validation: what is executable here, and honest limitations

The executable check in this environment is NOT a JSON Schema validator run —
no schema-validator library is available to the pinned toolchain, and none
was added (dependency freeze). The executable validation is:

1. zio-json parse of every document,
2. the validating typed decoders in `modules/eval-export` (`wire.*`), which
   enforce closed enums, sha256 shapes, explicit-null usage rules, canonical
   outcome order, and the partial/completed and produced/pending/absent
   conditionals on READ,
3. byte-exact comparison of the four golden directories in `expected/`,
4. manifest section-integrity verification, lineage reconstruction
   (`Lineage.reconstruct`), and the reconciliation chain walk
   (`Reconciliation.verifyChain`).

Known, documented gaps of this arrangement:

- The JSON Schemas carry `additionalProperties: false`, but the zio-json
  decoders ignore unknown fields; unknown-field rejection is therefore pinned
  by the schemas (for external validators) and by the byte-exact goldens, not
  by the decoders.
- The decoders tolerate ABSENT optional fields (treating them as null); the
  schemas require every field with explicit-null types, so the on-disk
  contract is stricter than the read tolerance. Golden byte-comparison keeps
  the on-disk shape pinned.
- Cross-field arithmetic in the manifest (`pending = expected - produced`,
  `absent = episodes - expected`, `rootId = runId`) is expressed in the
  schema descriptions only; it is enforced by the decoders and by
  `Lineage.reconstruct`, which a plain JSON Schema validator could not do
  either.
- ExportSpec checks the schema files are well-formed 2020-12 objects with
  the pinned `$schema`/`$id`/`type` headers; running an actual validator
  against the goldens remains possible for any consumer with a validator and
  is the recommended external check.

## Golden re-pin rules

Goldens are (re)pinned ONLY via:

```
sh modules/eval-export/run-tests.sh --update-goldens
```

which re-exports the deterministic fixtures, writes
`expected/<name>/`, and immediately re-verifies what it wrote (byte compare,
manifest integrity, lineage reconstruction, reconciliation chain). A
committed re-pin MUST be accompanied by: a review of the full semantic diff
(any intended change of an honesty-relevant shape is a schemaVersion
bump + contract revision, not a re-pin), and refreshed gate evidence. A
golden mismatch during a normal test run is a FAILURE, never a silent
update.

## Spec entry points (standalone, no sbt)

The module is not yet registered in `build.sbt` (registration is a
coordinator action). Until then it compiles and runs with the pinned dotc
3.9.0 and the jar closure under `artifacts/tools/m2`:

- `modules/eval-export/run-tests.sh` — compiles main+test sources and runs
  `raider.evalexport.TestMain`: EXPORT-01 golden/structure tests
  (`ExportSpec`), EXPORT-02 honesty tests (`HonestySpec`), EXPORT-03
  partial-run and reconciliation tests (`ReconcileSpec`).
- `modules/eval-export/run-tests.sh --update-goldens` — the re-pin tool
  (`raider.evalexport.GoldenRepin`), with immediate self-verification.

Evidence applies to the checked source fingerprint; source changes after a
run require re-running the suite.
