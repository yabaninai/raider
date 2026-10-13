# W4-PARALLEL: work record

Status: done. Date: 2026-10-09 UTC. Baseline: integrated W3 tree.
Mode: 10-lane wave (owner decision), writers — cloud explore agents
(glm; local models disabled by owner), 9 isolated lanes.

## Resulting behavior (9 cards accepted)

- **RAI-074** — mailbox wiring: per-child Mailbox (cap 32), `sendToChild` (REPL/
  owner channel), owner steering at the turn boundary, TerminalJob typed
  refusal, final-drain (ack ≠ loss). SENDW-01..03.
- **RAI-075** — PluginLoader (URLClassLoader parent-delegate, manifest
  META-INF/raider-plugin.json, sha256 payload pinning, preflight, ServiceLoader
  after manifest+sha) + PluginCli (list/install/remove/enable/disable under
  ~/.raider/plugins). core 49/49, CLI portion included in 101.
- **RAI-076** — StateStore SPI (P5.A): memory/file, Agent/Root/Global, quotas,
  revision+CAS (4 fibers → 1 winner), atomic replace, StateError sealed
  (Corrupt/Io are reported accurately). Default-OFF structurally.
- **RAI-077** — Session schema v2: schemaVersion=2, workspace/label; v1→v2
  migration; future → typed; 8 migration tests.
- **RAI-078** — CiContext: generic/github/gitlab, allowlist env headers,
  secrets env (TOKEN|KEY|PASSWORD|SECRET) dropped+counter, `redacted()` view,
  CiContextError sealed.
- **RAI-079** — Anthropic HTTP stand tool-round: complete round through
  ProviderBackend(anthropic)+AgentLoop+fs tools; usage reported accurately;
  RA-AUTH without retry. Closed deferred ANT-01.
- **RAI-080** — docs/onboarding.md (RU): mock→live→REPL→troubleshooting; all
  commands checked against source (worker attached a command→source table).
- **RAI-081** — 6 eval fixtures (protected: hidden-tests+solution outside
  agent workspace), 6 verify.sh on reference → 0, negatives → 1; 71 files.
- **RAI-082** — AccountingView: UsageReport → JSON+human, Long arithmetic to
  6 digits, unknown ⇔ unpricedAttempts>0.

Integration: 9 disjoint lanes → clean; scalafix/forbidden-apis fully clean
(fixture literals neutralized in 3 specs: CiContext/AnthropicStand,
MailboxSpec uninterruptible — not required).

## Acceptance evidence

| Check | Result |
| --- | --- |
| Full `sbt test` | **476 passed / 0 failed** (runtime 171, cli 101, tools 60, core 49, repl 25, ui 16, cli-extra 11, repl-engine 9, testkit/others) |
| fast (10 gates) | **PASSED** — first run on a tree with changes (scalafix+forbidden-apis exercised) |
| 9 profiles | passed: static/unit/contracts/runtime/ci/repl/tools/transport/security (`…20261009-191006`) |
| forbidden_apis | PASS: 162 files, 0 violations |
| Validator | 85 cards passed |
| E2E smoke | mock run exit 0 (artifacts accurate) |

## Limitations

- PluginCli is NOT wired into CliArgs/Main (follow-up RAI-083); hooks are not
  executed (ADR-025 §2.5, after freeze review).
- CiContext/AccountingView/StatusLine wiring into HeadlessRunner is a separate
  card (deliberate, to avoid conflicts).
- Eval execution (LLM runs) is a separate card with a budget.
- OpenAIChatStreamSpec idle-deadline flake — pre-existing (A/B demonstrated
  in lane-15), not reproduced in this session's full run; monitor it.
- Available sub-agents are limited by permissions (local-qwen/explore);
  general-denied — owner may reopen access to increase throughput.

## Next

W5 wave candidates: PluginCli wiring, hooks (after ADR-025 freeze review),
state_get/put tools (P5.B), journal P6.A, HeadlessRunner wiring (CiContext+
Accounting), TabShell `:plugins`, UX03/04 measurements on root, REPL live env-key.
