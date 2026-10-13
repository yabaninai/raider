# Coding and audit task prompts

The current headless `coder` has native workspace and process tools. Work on a disposable copy, use actual check results, and cite source evidence. A prompt cannot change repository policy or make trusted code safe to execute.

## Billing boundary regression

> In `src/main/scala/billing/Discount.scala`, fix the boundary so a subtotal of exactly 10,000 cents receives the documented discount. First compile and run `DiscountSpec` and report the failing assertion. Make the smallest safe change, then compile and run all boundary cases again. Summarize the changed file and actual check exits.

`python3 scripts/quality/user_examples.py --only coding` runs the same workflow with a deterministic scripted stand to verify native tool behavior. The stand selects fixed tool calls from observed results; it is not a model and does not demonstrate model reasoning. The `--live` option requires explicit coordinator approval and configured endpoint, model, and budget.

## Repository configuration audit

> Audit the supplied checkout for plaintext payment transport fallback. Read and search the relevant config, then write `AUDIT.json` with a schema version, status, severity, exact file and line evidence, impact, and a concrete remediation. Do not edit the configuration. Keep verified findings separate from assumptions.

`python3 scripts/quality/user_examples.py --only audit` runs an offline scripted sequence against a disposable fixture. The verifier checks the source hash, native `fs_tree`/`fs_read`/`fs_search` results, the generated structured JSON report, and that the configuration remains unchanged. Its finding is fixture-defined, not model-generated. Use `--live` only with explicit approval and an established budget; the verifier makes one call and does not replay after an ambiguous result.

## Release readiness

The typed compiled workflow requires `tests`, `migration`, and `rollback` results. It rejects duplicates, unknown names, invalid statuses and missing checks. It reports a readiness decision and a concrete action for pending checks. See the [typed workflow example](user-guide.md#2-typed-release-readiness-workflow).
