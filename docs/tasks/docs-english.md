# DOCS-EN: English documentation and useful current examples

Owner request: 2026-10-13. All documentation must be in English. Replace the toy gallery with complete useful scenarios supported by current `main`, verify every advertised example, and merge the result into `main`.

## Scope and ownership

The coordinator owns integration, `AGENTS.md`, canonical task JSON reconciliation, cross-document links, and final evidence. Translation lanes `docs/english-1` through `docs/english-12` own the disjoint file inventories in their task packets. Card groups translate their Markdown and matching JSON prose together; identifiers, dependencies, statuses, counts, hashes, paths, and acceptance strength remain intact. Subworkers use separate worktrees and branches.

The `docs/useful-examples` lane owns README, user guides, `examples/user-guide/**`, `scripts/build/user-examples.sh`, and `scripts/quality/user_examples.py`. Examples must demonstrate real project work with inputs, actual checks, results, and artifacts. Scripted fixtures must be distinguished from live model verification.

## Acceptance

- English prose throughout documentation, including historical records and contributor prompts; identifiers and immutable evidence values remain intact.
- Existing plan/card consistency checks remain effective. Any verifier adaptation uses the separate verifier card and independent review.
- No planned API is presented as implemented. Each showcased command and example is executed against the integrated source.
- Real provider checks use only the owner's configured endpoint/model with an explicit request budget. Secrets remain outside Git, argv, reports, and tool output.
- Existing user changes are preserved; no neighboring repository changes.
- Required quality checks, language inventory, independent review, and a post-execution work record before completion. Failures remain failures.

## Non-goals

No new runtime features, provider fallback, deployment, forge integration, or API stability promises. Translation does not change historical evidence or roadmap scope.
