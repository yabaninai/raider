# AUDIT-20261101 acceptance lane

Status: partial (read-only acceptance audit complete; no gate was executed). Date: 2026-11-01 UTC. Baseline commit: `04f74780891e0e7032bf98b3d1dccac279f32354`. Lane HEAD before this record: `935cc2ff0e3498ff1080aad4d53e9b6ca157a405`; branch `audit/20261101-acceptance`.

## Resulting behavior

No product or gate behavior changed. This record audits the quality runner, registry, Makefile, acceptance requirements, and historical manifests. The inspected implementation does not support an `engineering_ready` claim: component-level green runs and isolated milestone profile records do not establish W01–W17 on one immutable build, and the executable runner has no working-product acceptance command or stage manifest.

## Changed paths

- `docs/work/records/AUDIT-20261101-acceptance.md` (this record only).

## Findings

### P1 — There is no executable working-product or release acceptance stage

`docs/quality-gates.md:15-27` defines distinct component, milestone, working-product, and release criteria and says `make quality-full` is a strict overall milestone check. The same file says the `QUALITY_STAGE`/`make acceptance-working` interface is a target not implemented yet (`:31`). The current `scripts/quality-policy/registry.json:3-6,7-105` has only component profiles; each profile has only `stage` and gate IDs. It has no per-gate activation stage, prerequisites, scope, `required_by`, case IDs, or remaining-obligation manifest fields described at `docs/quality-gates.md:23`.

`Makefile:77-83` shows that `quality-full` runs `sbt test`, formatter, forbidden-API self-test, `quality-changed QUALITY_EXECUTE=1`, and two smoke scripts. The runner selects `fast` only when the tree is clean (`scripts/quality/quality.py:470-478`); with changes, it selects only the changed-path profile union. Thus a dirty-tree `quality-full` can run less than the full suite, and a clean-tree run is the `fast` component profile plus two smokes. No `quality-integration`, `quality-release`, `quality-fast`, or `acceptance-working` Make target exists (`Makefile:10-11,29-83`). The make target is therefore not the strict milestone gate promised by the docs.

The `engineering_ready` criterion requires W01–W17 acceptance, all required statuses, and one integrated W18 build (`docs/working-product.md:13-17,42-44`). No per-W-ID acceptance manifest was found in the inspected registry/runner or acceptance paths. The historic M1 record explicitly says milestone profiles on the integrated candidate were not run and remain an obligation (`docs/work/records/M1-ACCEPTANCE.md`, §3). This is a confirmed unsupported claim, not a claim that no implementation exists.

### P1 — Manifest verification does not prove profile gate completeness

`cmd_verify` checks the manifest's claimed status and whether each requested profile name is present, then checks only the gates that happen to be listed (`scripts/quality/quality.py:529-580`). It does not resolve requested profiles through the current registry and require the complete expected gate-ID set, reject duplicate/unknown gate IDs, verify `argv` against registry commands, or validate test-summary counts. Consequently, a fabricated/tampered manifest that claims `passed`, names a requested profile, and omits required gates can evade the completeness check if its remaining fields/fingerprints are made consistent. The self-check negative fixture (`quality.py:846-860`) rejects a manifest because it includes a failed gate and bad hashes; it does not exercise a passed manifest with missing required gates. This conflicts with the promised required-gate/completeness check in `docs/quality-gates.md` §5.

The code records `toolchain_snapshot()` in each manifest (`quality.py:503-517`) but `cmd_verify` does not compare it with the current toolchain; its success message nevertheless says “toolchain ... hashes checked” (`:576-579`). The snapshot currently stores version strings, not a separately hashed toolchain/dependency inventory (`:380-387`). A historical report can record the toolchain used, but verification does not establish that the current environment matches it.

### P1 — Several component profiles are aliases for a broad test command

The registry maps `unit`, `runtime`, `tools`, and `transport` to `sbt-test` alone (`scripts/quality-policy/registry.json:21-26,56-72`). `repl` and `ci` add one smoke each (`:74-86`). Profile-level success therefore does not independently establish the profile's named requirements in `docs/quality-gates.md:56-71`; the manifest has no selected case IDs or applicability map. In particular, the current REPL smoke is explicitly non-TTY (`scripts/quality/repl_smoke.sh:1-4`) while working REPL acceptance requires PTY/compiler interactions (`docs/quality-gates.md:63, docs/working-product.md:34, section 8`). The CLI smoke exercises a mock bundle and selected run artifacts (`scripts/quality/cli_smoke.sh:1-4,14-25`), not the full CI clauses including SIGTERM, artifact-failure precedence, bundle trust, JUnit, and adapters (`docs/quality-gates.md:62,240`).

The `docs` profile is just `plan-validate` (`registry.json:35-39`), so it does not currently run the documented link, example, API, or command-freshness checks (`docs/quality-gates.md:65`). `examples/` maps only to `docs` (`registry.json:240-255`), even though the working product requires compiled/run golden scenarios and a live custom-tool example (`working-product.md:35,37`). The contract compile script now includes positive/negative fixtures and `examples/plugins` (`scripts/build/compile.sh:38-56`), but those only run when a contracts profile is selected, not for changes under examples. This is a classification/coverage omission, not a failure of that script.

### P1 — `.opencode` prompts are excluded from the source fingerprint

The product-input fingerprint exclusion list includes `.opencode` (`scripts/quality-policy/registry.json:258-271`), and inventory skips any matching path segment (`scripts/quality/quality.py:109-128`). This excludes the checked-in `.opencode/agents/*.md` and `.opencode/commands/*.md` files, which are active contributor/worker instructions (also referenced by `docs/parallel-development.md:69-70`). The required-input policy says prompts/build/specification/fixtures remain fingerprint inputs (`AGENTS.md` contributor evidence rules and `docs/quality-gates.md:130,152`). A change to those prompts can therefore leave the product fingerprint unchanged. The exact allowlist for non-input operational material is records/board/assignments, not all `.opencode` content.

### P1 — Current gate documentation contradicts the implemented project

`docs/quality-gates.md:7` says the package has no Makefile, sbt build, or implemented gates and labels all commands as future requirements. The inspected branch has a Makefile, sbt build, registry, and runner. The owner instructions explicitly say to check actual code rather than treat old status text as permanent. This stale preamble should be replaced with a dated status note, since it conflicts with the operational stage/registry guidance later in the document.

### P2 — Profile helper contract and execution provenance are weaker than documentation claims

`docs/quality-gates.md:46` says self is mandatory for `quality-profile`, but `quality-profile` passes only the caller's explicit `PROFILES` to `execute` (`Makefile:46-48`); the runner does not add `self` (`quality.py:479-499`). For example, selecting only `security` executes only its gate and records only that profile. This is useful for scoped work but is not the mandatory-self behavior described by the docs.

Manifests carry source file hashes/modes, policy-file SHA-256, argv, gate logs/hashes, and version strings (`quality.py:503-517`). This is useful integrity evidence. It does not authenticate who ran the command or provide an immutable signed attestation; the runner itself notes that runtime logs are not proof of execution provenance (`quality.py:868-870`). Schema v2 also omits a source commit field. Historical artifacts must be described by their recorded fingerprint and date, not treated as a current candidate check or signed build attestation.

## Historical evidence inspected

I read (without modifying or re-verifying) root `artifacts/quality` manifests. On 2026-11-01, the following recorded manifests report all their listed gates with exit 0:

| Manifest | Recorded profiles | Recorded source fingerprint | Meaning / limit |
| --- | --- | --- | --- |
| `20261101-001816-49846-0001` | `fast` | `e027e4736ec8561579e55dfca70d5f48347ded190a46f89a51176ec2cc1286dd` | Fast component gates only. |
| `20261101-004029-52635-0001` | `ci, contracts, docs, fast` | same `e027e...` | Adds current component CI smoke, contracts, plan validator. |
| `20261101-010235-55444-0001` | `security` | same `e027e...` | Secrets scanner only. |
| `20261101-031655-55681-0001` | `fast` | `fbc198a53fcb5e0acc3020deb04c6869d5cb0326fa736444aa52e7e0fe9dcabe` | Fast component gates only. |
| `20261101-034259-67460-0001` | `contracts, fast` | same `fbc198...` | Contracts/fast component gates. |
| `20261101-041032-75735-0001` | `eval` | same `fbc198...` | Plan-mode `eval-corpus` only, not the required protected W16 task corpus. |

These are historical and separate runs, not one full/milestone manifest. The schema records a source fingerprint but not a source commit; none of these manifests claims or establishes W01–W17. No current gate was executed in this audit.

## Acceptance evidence and commands

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| Acceptance implementation review | Inspected | `scripts/quality/quality.py`, `scripts/quality-policy/registry.json`, `Makefile`, `docs/quality-gates.md`, `docs/working-product.md`; findings above. |
| Historical evidence review | Inspected read-only | Six root manifests listed above; no log execution or freshness verification performed. |
| Current gates/builds | Not run | Explicit audit restriction: no builds. |
| Worktree boundary | Clean before record; correct branch and baseline | `git status --short --branch`; `git rev-parse HEAD`; `git show -s --format='%H %s' 04f74780891e0e7032bf98b3d1dccac279f32354`. |

| argv / command | Exit | Log / result |
| --- | --- | --- |
| `git status --short --branch && git rev-parse HEAD` | 0 | Clean `audit/20261101-acceptance`; HEAD `935cc2ff0e3498ff1080aad4d53e9b6ca157a405`. |
| `git show -s --format='%H %s' 04f74780891e0e7032bf98b3d1dccac279f32354` | 0 | Confirms assigned baseline commit. |
| `python3 -` (read-only JSON summary of six root manifests) | 0 | Printed recorded status, profiles, source fingerprints, gate argv/status/exit, toolchain strings. |
| `rg -n ... scripts/quality-policy docs scripts Makefile` | 0 | Checked current profile/stage/manifests/claim references; no working acceptance target surfaced. |
| `git diff --check` (before record creation) | 0 | No pre-existing lane diff. |

## Limitations / blockers

- This audit did not execute any build, test, plan validator, docs classification, gate, or manifest verification. It is a source and historical-artifact audit only.
- Historical manifests were not rebound to this lane or the integrated candidate, and log bytes were not independently rechecked.
- The task is not acceptance of an implementation or an integrated product. The issue is that current tooling cannot yet establish the working-product acceptance claim.

## Review and next task

Recommended cards to register, with independent gate-policy review for registry/verification changes:

1. **P1: complete acceptance registry and verifier.** Add explicit gate applicability/required case IDs, milestone/working/release stage manifests and obligations, `acceptance-working`, and verify exact registry-resolved required gate sets, log/schema/count consistency, and current toolchain identity. Add negative cases for omitted gates, unknown/duplicate gates, wrong argv, malformed counts, and toolchain mismatch.
2. **P1: make `quality-full` match its promised semantics.** Route it through the strict full/milestone profile and verify its manifest; keep changed-path execution as its own component command. Reconcile docs with the actual targets and require full on an integrated candidate.
3. **P1: repair product-input fingerprint scope.** Stop excluding active `.opencode` prompt/config inputs (or define a narrow reviewed exclusion), and test a prompt-only edit changes the fingerprint.
4. **P1: register semantic acceptance cases.** Map distinct runtime/provider/CI/REPL/docs/examples requirements to executable cases; include real PTY and required SIGTERM/artifact/trust acceptance before their target stages. Ensure changed examples trigger compilation/run acceptance.

No independent review of this record has been performed. The next available work is registration and implementation of the P1 acceptance/verifier card; do not mark `engineering_ready` until a single-candidate W01–W17 manifest and joint W18 gate report exist.
