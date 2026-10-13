# DOCS-EN: English documentation and current useful examples

Date: 2026-10-13. Baseline: `95cb8e9` on `main`, plus the existing uncommitted RAI-127/128/129 backlog entries. Concurrent main wave `0e119da` was later preserved, including the `:type` implementation, reviewed PTY-probe fixes, new RAI-124/126 records, and RAI-130. Coordinator: owner session; visible Luna translation, example, audit, and independent review workers used separate Git branches/worktrees under `../raider-english/`.

Status: integrated candidate. Final acceptance is bound to `artifacts/docs-english-final/receipt.json` in the main checkout; an absent, failed, or stale receipt is not a pass. Integration uses the reviewed final tree, preserving the existing card metadata.

## Result

- Repository documentation is English, including contributor instructions, plans, contracts, task prose, research, work records, and OpenCode prompts. The permanent English documentation policy is in `AGENTS.md`.
- Canonical task prose and Markdown mirrors are synchronized. All non-prose fields of the original 142 cards and existing RAI-127/128/129 entries are preserved; RAI-130 metadata is also preserved from the concurrent main wave. The final validator checks all 146 cards. Historical Unicode fixtures are described with explicit code-point/escape notation; their original test data is unchanged.
- README exposes runnable billing repair, payment configuration audit, parallel Scala review/test planning, and typed release-readiness examples. User guides distinguish current implementation, scripted verification, live results, and planned SDK/SDLC features.
- Billing repair compiles Scala, observes the failing boundary test, edits the implementation with a hash precondition, then passes the pinned tests. Live verification correlates actual checker argv with tool call IDs and independently compiles/runs the unchanged tests.
- Audit creates `AUDIT.json` with stable finding ID, severity, exact source path/line/text, impact, and remediation while preserving the configuration. The verifier checks equivalent descriptions of plaintext transport risk rather than one required English word.
- The lazy-map example exercises actual `:type` output and confirms type queries do not execute deferred effects. The gallery terminates its owned coding stand before starting the audit stand.
- Parallel REPL review uses lazy asks, `map`, and `all(...).run()`. Live verification rejects fixture fallback/tool requests and checks the JSON test plan against the stated policy at 9999/10000/10001 cents.
- The typed bundle computes READY/BLOCKED as result data; BLOCKED does not itself make the runner exit nonzero. Missing, invalid, or duplicate inputs produce exit 21. It makes no model call.
- Existing API/onboarding guides now describe actual interpreter-local wiring, direct Java REPL launch, testkit dependencies, and the open Anthropic tool-definition gap (RAI-127). Model-specific old demos are marked historical.

## Changed paths

`AGENTS.md`, `README.md`, documentation under `docs/**`, `.opencode/**`, plugin/spike README files, `reviews/W22-review.md`, and `scripts/quality/README-quality-runner.md`; `examples/user-guide/**`, `scripts/build/user-examples.sh`, `scripts/quality/user_examples.py`. The separate reviewed verifier change in `scripts/plan/validate.py` changes only the English empty-dependency display label. No runtime/public API, dependency, gate registry, or neighboring repository changes were authored in this documentation revision. Concurrent main runtime/probe changes are preserved verbatim and receive fresh integrated checks.

## Executed candidate checks

All paths below refer to the coordinator checkout unless stated otherwise. Its ignored evidence is preserved and copied to the main checkout for final handoff.

| Command/check | Actual result | Evidence |
| --- | --- | --- |
| `sbt --batch clean compile` with JDK 21.0.12.1 | exit 0 | `artifacts/docs-english/clean-compile.log`, `offline-receipt.json` |
| `sh scripts/build/user-examples.sh` | exit 0 | `artifacts/docs-english/examples-build.log` |
| `python3 scripts/quality/user_examples.py --skip-build` | all 10 cases passed, exit 0; READY and invalid-input cases included | `artifacts/user-examples/verification-z5cswhvd/report.json` |
| `python3 scripts/quality/user_examples.py --skip-build --only coding --live` | exit 0; observed regression and pinned checker provenance, independent compile/test passed | `artifacts/user-examples/verification-9sj11s4c/report.json` |
| `python3 scripts/quality/user_examples.py --skip-build --only audit --live` after synonym correction | exit 0; new disposable workspace | `artifacts/user-examples/verification-nuuwdb3y/report.json` |
| `python3 scripts/quality/user_examples.py --skip-build --only repl-review --live` | exit 0; returned boundary plan `[[9999,0],[10000,1000],[10001,1000]]` | `artifacts/user-examples/verification-i1vbc683/report.json` |
| `python3 scripts/plan/validate.py --out artifacts/docs-english/plan-146.json` | exit 0, 146 cards after concurrent main integration | `artifacts/docs-english/plan-146.json` |
| Rebuilt examples and targeted `--only lazy-map` verification on the integrated `:type` implementation | exit 0; actual type output checked, deferred side effects remain unexecuted | `artifacts/docs-english/latest-type-receipt.json` |
| Documentation language inventory | 204 files checked at that candidate; no remaining Cyrillic documentation | `artifacts/docs-english/language-report.json`; final receipt rescans after records |
| Card metadata preservation | all original 142 + initial existing 3 entries preserved; final main check also covers RAI-130 | `artifacts/docs-english/card-metadata-preservation.json` |
| `python3 scripts/quality/license_audit.py --self-test` | exit 0, 25 runtime artifacts, 0 problems | `artifacts/license-audit/report-20261013-021212.json` |
| Independent verifier review | approved; English empty dependency passes, wrong label fails | `artifacts/docs-english/verifier-review.json` |
| Independent example review | initial changes required; provenance/ID corrections approved; semantic synonym correction separately approved | `artifacts/docs-english/final-review.json`, `final-review-v2.json`, owner-session review |
| `python3 -m py_compile scripts/quality/user_examples.py`, `sh -n scripts/build/user-examples.sh`, `git diff --check` | exit 0 | worker/coordinator command reports; repeated in final main receipt |

Mandatory `make quality-changed`, `make quality-changed QUALITY_EXECUTE=1`, explicit documentation/security profiles, immutable evidence verification, and the integrated main gallery are recorded in the final receipt. Candidate checks above do not substitute for those checks.

## Failed attempts retained

The previous main receipt `artifacts/user-docs-final/receipt.json` failed: the license export failed, and new backlog IDs lacked Markdown/index mirrors. It is not relabeled. The clean-build ToolCall accessor issue was transient; successful clean builds and 53 runtime tests are recorded in [BUILD-TOOLCALL](BUILD-TOOLCALL.md), with no runtime fix claimed.

The first live REPL run completed but proposed an incorrect threshold test. Its original runtime receipt remains intact; `artifacts/docs-english/repl-first-semantic-review.json` records the semantic failure. The new case validates policy values. A later audit run completed correctly but failed the overly literal impact predicate (`unencrypted` versus `plaintext`); `verification-yawnrt7v/report.json` remains failed. Independent review approved equivalent wording, and a fresh run passed. Early offline stand/schema failures also remain in the worker checkout.

## Live configuration and limits

The owner authorized ample requests through the coding plan only. The authenticated gateway registry identifies requested `glm-5.3-flash` as `zai-coding`; a connection smoke returned model `glm-5.3`, recorded separately. Runtime evidence records the requested model, not an invented upstream identity or price. Credentials remain in the existing private, ignored `.env.yabanin` env/helper configuration, with file mode 0600; no secret values are in Git, argv, reports, or tool output.

These are small trusted local demonstrations, not an OpenCode comparison, general model-quality benchmark, production security assessment, published SDK, durable resume, or automatic forge/release implementation. No commits were pushed and no releases deployed. Source/records added after a check require affected final checks; only the final main receipt accepts the delivered tree.

Next available implementation card: RAI-127 (Anthropic tool definitions on the wire), with RAI-128/129 preserved as existing separate obligations.
