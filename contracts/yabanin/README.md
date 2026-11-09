# Yabanin contracts (optional profile: labels, wrapper environment, capability doctor)

Scope: card RAI-037. This directory is the contract surface for the optional
Yabanin wrapper profile that Raider core/CLI recognizes when it runs inside a
Yabanin-managed CI wrapper. It contains:

- `labels.schema.json` — canonical label-set document (accepted/dropped/header/limits).
- `wrapper-env.schema.json` — effective-context record produced from wrapper
  environment recognition.
- `doctor-report.schema.json` — capability doctor report document.
- `expected/pathescape-golden.json` — byte-exact value escaping pin.
- `expected/labels-golden.json` — canonicalization, merge, and parsing pins.
- `expected/doctor-golden.json` — capability doctor scenario pins.

The implementing module lives under `modules/yabanin/`; its contract checks run
via `modules/yabanin/run-tests.sh`. These contracts describe wire and document
behavior only; they do not introduce a scheduler, lifecycle mint, or fallback
route. Session-key minting lifecycle belongs to a separate card (RAI-038); this
contract only requires the doctor to *observe* whether the gateway exposes the
session-keys capability.

Sources of truth (behavior must stay consistent with these):

- `docs/providers/gateway-baseline.md` — wrapper environment variable names.
- `docs/ci-runtime.md` — child-only environment policy.
- `docs/research/yabanin-state-discovery.md` — label limits, escaping, provenance.
- `docs/yabanin-integration.md` — integration posture, doctor, no-fallback rule.

## 1. Label header contract

### 1.1 Canonical form

A label set is a collection of `key=value` pairs, each with a provenance:

- `wrapper` — provided by the Yabanin CI wrapper through `YABANIN_LABELS`.
- `client` — provided by Raider configuration.

The rendered header (`X-Yabanin-Labels` wire value) is a single comma-separated
string of `key=escapedValue` pairs, sorted lexicographically by key. Values are
escaped byte-wise exactly as Go `net/url` `PathEscape` escapes them; see
`expected/pathescape-golden.json` for the byte-exact pin and its re-pin rule.
Escaping is byte-wise UTF-8 with uppercase hex: ASCII alphanumerics and `-_.~`
pass through, among the reserved characters `$&+,:;=?@` only `/` `;` `,` `?` are
escaped, everything else becomes `%XX`. A canonical header can be split on `,`
and on the first `=` of each segment without ambiguity: `,` never appears raw
in canonical values, and keys never contain `=` (the key charset is
`[A-Za-z0-9._-]`). Note that `=` itself is in the PathEscape keep set and may
appear raw inside values (the golden pins `a=b` → `a=b`).

### 1.2 Limits (pinned in `expected/labels-golden.json`)

| limit | value | note |
|---|---|---|
| max per source | 20 | applies separately to wrapper and client provenance |
| max effective | 32 | applies to the merged header |
| max value length | 128 bytes | UTF-8 byte length, matching Go `len()` on the wire; not characters |
| reserved key prefix | `yabanin.` | wrapper-provided namespace; client labels starting with it are dropped |
| client key max length | 64 chars | client-side guard; key charset `[A-Za-z0-9._-]` |

### 1.3 Canonicalization and drop reasons

Canonicalization of one source processes pairs in insertion order with the
following check order: key shape (`malformed_key`), reserved prefix
(`reserved_key_prefix`), value byte length (`value_too_long`), duplicate key
from the same source, first value wins (`duplicate_key`), per-source cap
(`per_source_overflow`). Every rejection is recorded as a visible drop with a
reason; nothing is silently discarded. The reserved-prefix check is
provenance-conditional: the `yabanin.` namespace belongs to wrapper-provided
labels (§1.2), so wrapper pairs carrying the prefix are admitted while client
pairs carrying it are dropped.

Merge: wrapper labels are admitted first and are protected — a client label
whose key collides with a wrapper key is dropped with `protected_wrapper_key`.
Client labels are then appended in insertion order until the effective cap;
the excess is dropped with `effective_cap_overflow`.

### 1.4 Parsing (strict canonical decoder)

`parseRaw` accepts only canonical header text. Its typed errors carry a
character position and a reason: `missing_separator`, `empty_key`,
`malformed_key`, `invalid_escape`, `invalid_utf8`, `non_canonical_byte`
(an escaped byte that `PathEscape` would have kept raw, or a raw character that
it would have escaped). Round-tripping is guaranteed: `unescape(escape(v)) == v`
and `escape(unescape(h)) == h` for canonical `h`.

## 2. Wrapper environment contract

The wrapper environment consists of exactly three recognized variables:

| variable | meaning |
|---|---|
| `YABANIN_GATEWAY_URL` | absolute base URL of the Yabanin gateway (http/https, host required, no query, no fragment, no user info) |
| `YABANIN_API_KEY` | NAME of the environment variable reference for the gateway credential; the value itself is never copied into reports or logs |
| `YABANIN_LABELS` | raw canonical label header text attributed to provenance `wrapper` |

Recognition semantics:

- A wrapper owner is recognized when any of the three variables is present in
  the environment; otherwise the lifecycle owner is `standalone`.
- Bad values become visible problems, never silent fakes: a blank or
  unusable `YABANIN_GATEWAY_URL` yields `invalid_gateway_url` (without echoing
  the raw value); a blank `YABANIN_API_KEY` yields `blank_api_key_ref`;
  unparsable `YABANIN_LABELS` yields `wrapper_labels_unparsable` while the
  rest of the profile still resolves.
- Precedence: an explicitly configured base URL wins over
  `YABANIN_GATEWAY_URL`; wrapper labels merge with client labels per §1.3.
- No usable base URL from either source yields `no_base_url`.

Child-only environment (see `docs/ci-runtime.md`): the child provider call
receives a minimized environment built from an explicit allowlist of parent
names. The credential value flows to the child only through an allowlisted
environment variable name (typically `YABANIN_API_KEY` itself). The builder
also adds `RAIDER_PROVIDER=openai`, `RAIDER_BASE_URL=<gateway url>`, and
`RAIDER_OPENAI_EXTRA_HEADERS=X-Yabanin-Labels: <rendered header>` (the last
only when the header is non-empty). Parent values replaced by an entry are
recorded as visible overrides; allowlisted names with blank or missing parent
values are recorded as visible omissions. Non-allowlisted parent variables
never reach the child.

## 3. Capability doctor contract

The doctor answers one question: which Yabanin gateway capabilities does this
environment actually expose, and is the wrapper profile usable? It issues
exactly two requests, in order:

1. `GET {base}/models`
2. `GET {base}/yabanin/session-keys`

The base URL is used verbatim (trailing slashes trimmed, `/models` and
`/yabanin/session-keys` appended). Both are plain GETs. The doctor never spends
tokens, never completes a session-key mint, and never retries or falls back.

Classification per endpoint:

| observed | classification |
|---|---|
| 200 with docs_version matching the pinned expectation | `ok` |
| 401 | `auth_denied` |
| 403 | `forbidden` |
| 501, or 404/405 (endpoint not exposed) | `capability_unsupported` |
| 200 with docs_version missing or not matching the pin | `stale_models_docs` (model ids still recorded) |
| 400 with body error code `label_overflow` | `label_overflow` |
| anything else, or call-level failure | `transport_failure` |

`fallback_attempted` is structurally `false`: an auth or policy refusal is a
terminal capability fact, and there is no hidden direct route to any other
provider after a gateway refusal (see `docs/providers/gateway-baseline.md`).

The gateway decision header (`X-Yabanin-Decision`) is recorded verbatim —
value plus the URL path of the response it was observed on — as routing
evidence only. The first observation wins (models, then session keys). The
doctor does not interpret or act on the decision.

## 4. Goldens, schemas, re-pinning

- Schemas use JSON Schema draft 2020-12. Cross-references use sibling `$ref`
  within this directory.
- Goldens are immutable inputs: product-input changes (including goldens)
  require new affected checks. Escape expectations are re-derived only by
  running `PathEscape` from a supported Go toolchain (see the re-pin note in
  `expected/pathescape-golden.json`). The doctor `expected_models_docs_version`
  pin moves only through a separate contract card.
- The implementation defaults must equal the limits pinned in
  `expected/labels-golden.json`; the contract checks assert this equality.

## 5. Security notes

- Credential values must never appear in Git, snapshots, reports, logs, or
  examples. This contract only ever names the environment variable that holds
  the credential.
- The label header never carries the credential.
- Base URLs containing user info are rejected rather than parsed.
- Doctor reports are safe to publish: they contain no credential material and
  no authorization header values.
