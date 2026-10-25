# Raider Execution Board

## 2026-10-25 — RAI-186 + RAI-195 + RAI-207 + RAI-203 accepted (independent reviews approve)

- **Integration:** verified root baseline `10c966f` clean on `main` (0 ahead of `origin/main`); merged, in order, `lane/rai-186` (tip `ec387ae`) as `010770e`, `lane/rai-207` (tip `f938837`) as `bc37e0b`, and `lane/rai-203` (tip `ebbd91d`) as `b35047b` — all clean `--no-ff` merges, zero conflicts (disjoint surfaces; 186/207 had already merged `origin/main` at `35052d0`). All four lane worktrees were clean at their reviewed tips before merging.
- **RAI-186 landed:** First CI report/checks/exit contract closure — real-child check evidence (the model cannot manufacture check success), verdict/report precedence for write-failure/deadline/policy/cancellation (pairwise), JUnit+summary escaping, SIGTERM finalization; the interrupted-wave lane finished with the latent stale-label red fixed at `77de742`. Review: approve. New `contracts/ci/trusted-check-policy.schema.json`; `CiReport.scala` + `HeadlessRunner`/`WorkflowRun` closure; `FirstCiProcessAcceptanceSpec` + `WorkflowLaunchSpec` process acceptance.
- **RAI-195 landed:** check-policy contract — the advisory Boolean is frozen on the exported-workflow trustedChecks surface with immutable provenance binding; advisory failure records success-with-advisory (exit 0), blocking failure stays task-failed; missing/unknown/mismatched check IDs are blocking fail-safe. Review: approve.
- **RAI-207 landed:** protected CWF-E02 bogus-success expectation migrated under the RAI-195 ruling — now asserts task-failed verdict + check evidence (`run.json`) + RA-OUT on stderr; digest anchors migrated to `31de00b5`; surgical protected diff with changed-verifier-input intact; mandatory independent review approve (MIG-03).
- **RAI-203 landed:** portable chat export + recipe pair — `PortableChatExport` (portable v1: staged atomic publication, closed-schema sha256 manifest `chat-portable-export-manifest-v1.schema.json`, names-only pinned registry), `--format scala|portable` at the chat command with the honest CWF-R3-by-construction refusal on the portable path, and the checked-repair + extraction-classification mock-mode recipes with fixtures. Review: approve (0 blocking, 5 follow-ups recorded below).
- **Statuses/dependencies:** RAI-186, RAI-195, RAI-207, RAI-203 flipped to accepted in `docs/tasks/backlog.json`, task Markdown (`m0.md`/`m1.md`), and this index (`docs/tasks/README.md`). Dependency closure recomputed: **RAI-187 → ready** (deps RAI-185 ✓, RAI-186 ✓). No other card became ready: RAI-201 stays todo (RAI-200 ✓ but RAI-187 only ready, not accepted), RAI-205 stays todo (RAI-203 ✓ but RAI-201 not accepted).
- **Gates (integrated candidate at `b35047b`; lane evidence does not substitute):** `make quality-changed` exited 0 (clean-tree minimum `fast`). `make quality-changed QUALITY_EXECUTE=1` exited 0 — all 10 fast gates passed on the first run with no timeout, so the MJE-03c authorized rerun was not triggered (`sbt-test` 527.5 s, `self-registry-check`, `sbt-compile`, `scalafmt-check`, `scalafix-check`, `forbidden-apis`, `license-audit`, `core-contracts-spec`, `compile-fixtures`, `plan-validate`); manifest `artifacts/quality/20261025-152333-22486-0001/manifest.json` (SHA-256 `e6506ead9cb5e734f742b8360182249a4054d122ca4b9d286be41faf9895ffa2`, test summary 10/0/0). `make quality-profile PROFILES="static unit contracts runtime ci docs tools security eval"` exited 0 — all 9 gates passed, no timeout (`sbt-test` 481.7 s, `cli-smoke`, `core-contracts-spec`, `compile-fixtures`, `plan-validate`, `eval-corpus`, `security-secrets-scan`, `sbt-compile`, `scalafmt-check`); manifest `artifacts/quality/20261025-153325-26033-0001/manifest.json` (SHA-256 `42acf9b19c3e8d3468942f07ac5d58c30d86c76ce80f3b42b4ba86389ea58ed5`, test summary 9/0/0). `make quality-verify` on the profile manifest: OK — policy/toolchain/log hashes checked, fingerprint fresh.
- **Non-blocking follow-ups (recorded):**
  1. FCI-REPORT-02 post-run mutation fixture → register follow-up card (from the RAI-186 stack review).
  2. `m0.md` RAI-186 acceptance-vs-deliverables bullet mismatch (FCI-REPORT-01..05 vs 01..04) + older `backlog.json` FCI mapping — reconcile card.
  3. Assert `checkPolicyDigest` on the `WorkflowLaunchSpec` path.
  4. Summary prints `checks passed:` on success-with-advisory (cosmetic).
  5. Generated runner `reader.join()` unbounded — liveness card candidate.
  6. RAI-203 staging-race Scaladoc overstates serialization (fail-fast exclusion; distinguish `FileAlreadyExists` in `writeStaging`).
  7. No true concurrent-export race test (RAI-203).
  8. Launcher-side manifest digest verification — card candidate (RAI-203 review).
  9. Record lists `PortableTrustedRegistry` as new API (pre-existing RAI-199 surface) — clarify in records.
  10. Contract-adjacent card candidate: map chat named checks matching launcher pins onto trustedCheck steps so `--format portable` can succeed from chat.
- **Records:** [RAI-186 work record](records/RAI-186.md); [RAI-195 work record](records/RAI-195.md); [RAI-207 work record](records/RAI-207.md); [RAI-203 work record](records/RAI-203.md).

## 2026-10-25 — RAI-192 + RAI-199 accepted (reviews approve + compliance fix-ups)

- **Integration:** verified root baseline `c092441` clean on `main` (0 ahead/behind `origin/main`); merged `lane/rai-192` (tip `64a8755`) as `fe8c285` and `lane/rai-199` (tip `3802c1d`) as `7948f0a`, both clean `--no-ff` merges with no conflicts (disjoint surfaces: repl/ui/tools vs cli/portable + contracts fixtures; shared only through unrelated record files). This is the second integration attempt: the first (merge commits `53f52b2`/`3d440d1`) was STOPPED on a genuine forbidden-apis RED and rolled back unpushed; both lanes then received compliance fix-ups AFTER their review approvals, verified green on the final tips before this integration.
- **RAI-192 landed:** TUI-X.C1 complete — per-hunk diff review (`[n:state]` markers, `:hunk <n> accept|reject`, `:hunk apply`) applying ACCEPTED hunks only through the production `fs_patch` policy (`PatchTool.applySelected`: containment, size bound, sha256 provenance anchored at first decision; stale target refused typed; double-apply sealed), `:model <mock|openai|anthropic>` provider switch rebuilding the session at the existing env policy (fail-closed sanitized refusals; credential redaction verified across all raw PTY transcripts), lineage tab reachable via `:aux`. Both routed RAI-191 hardening conditions closed with adversarial fixtures: ESC neutralization in hunk rows and SGR-aware frame clipping that keeps the trailing reset. Real defect fixed: bare `:model` never emitted its description. Review: approve; compliance fix-up `64a8755` shortened two fixture secret literals to the scanner's ≤15-char allowance (redaction assertions and semantics unchanged).
- **RAI-199 landed:** portable CLI execution + Scala parity under ADR-036 — opt-in `raider workflow check|explain|run --doc` at the existing launch boundary; launcher-pinned registry snapshots (SHA-256, drift refuses); launch-layer limit intersection (`min` for attempts and deadline) plus per-attempt token ceiling via `TokenBudgetBackend` (RA-PBUDGET pre-wire, honest in-flight completion); a declared positive `deadlineMs` now binds (RA-DEADLINE 24, previously parsed but ignored); parity proven on normalized evidence/effects/errors/accounting (not run IDs); SIGTERM 143 + truthful `Interrupted` artifact. Review: approve; compliance fix-up `3802c1d` removed the `System.exit` from `PortableRun.command` — `raider.cli.Main` owns the process exit boundary, shutdown hook still runs at Main's exit.
- **Statuses/dependencies:** RAI-192 and RAI-199 flipped to accepted in `docs/tasks/backlog.json`, `docs/tasks/m2-p2.md` / `docs/tasks/m1.md`, and the `docs/tasks/README.md` index. Dependency closure recomputed: RAI-205 is the only card listing RAI-199 as a dependency and remains todo (still gated on todo RAI-201/RAI-203), so no additional card became ready; RAI-187 remains an open todo dependency recorded on RAI-199 (consistent with the RAI-184 ready-with-pending-dep precedent).
- **Gates:** `make quality-changed` exited 0 (clean-tree minimum `fast`). First `make quality-changed QUALITY_EXECUTE=1` attempt was terminated at a 900 s tool-side timeout while `sbt-test` was still running — a host-side kill, not a red gate; two stale sbt orphan processes were found and terminated first (a 12-day-old `raiderRuntime/test` JVM pinning 100 % CPU in an abandoned temp clone, and an orphaned fork in `wt/rai-199`). The authorized contention rerun passed all 10 fast gates, exit 0, no timeout: `forbidden-apis` PASS (454 files, 0 violations, 7 allowlist skips — Main ×6, WorkflowRun ×1), `sbt-test` 527 s, manifest `artifacts/quality/20261025-082141-95656-0001/manifest.json` (SHA-256 `414eaf39093e9142bcfffd18441fcf25d628995b235bb58fee641c1c4f3b25ed`). `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` exited 0; all 8 selected gates passed (`sbt-test` 535.6 s, `cli-smoke`, `core-contracts-spec`, `compile-fixtures`, `plan-validate`, `repl-smoke`, `sbt-compile`, `scalafmt-check`), no timeout, manifest `artifacts/quality/20261025-083157-4141-0001/manifest.json` (SHA-256 `a4c71c66aabbcaf6dc41d944158b8932db581da2409fdfe4247917745781202e`). MJE-03c never went red — its mentions in the sbt logs are green coverage-summary lines, so the watched rerun was not triggered.
- **Plan validation:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0; 228 cards. Post-status docs gate: `make quality-changed` classified the task/board edits as `docs`; `make quality-changed QUALITY_EXECUTE=1` exited 0, `plan-validate` passed, no timeout; manifest `artifacts/quality/20261025-084620-22672-0001/manifest.json`.
- **Records:** [RAI-192 work record](records/RAI-192.md); [RAI-199 work record](records/RAI-199.md).
- **Non-blocking follow-ups (recorded):**
  1. PortableRun sub-second `deadlineMs` floor — pass the ms Duration directly (one-line hardening; rerun affected gates when taken).
  2. `verdict.from` agent-step → no taskVerdict claim (permissive RAI-198 lowerer semantics) — hardening-card candidate.
  3. argv `--doc`-in-value corner.
  4. `sbt-test` 900 s cap vs ~557–568 s standalone full-suite time on this host — infra decision needed before the next big integration (dedicated gate host or timeout revision; gate policy untouched).
  5. `scripts/bootstrap.sh` stock-macOS CA issue (works only with `SSL_CERT_FILE` pointed at certifi) — infra-card candidate.
- **Process note:** the stopped first integration exposed that lanes committed violating content AFTER their last passing gate runs (RAI-192: two fixture secrets; RAI-199: one `System.exit`). Lane handoff must include gate runs on the FINAL tip fingerprint — both lanes now do (RAI-192: standalone forbidden-apis + 16/0 + profiles on `64a8755`; RAI-199: standalone forbidden-apis + 33/0 + fast-profile 10/10 on `3802c1d`). Keep the rule.

## 2026-10-25 — RAI-184 accepted (live-wire complete; review approve)

- **Integration:** verified root baseline `3bf378b` clean on `main`; merged `lane/rai-184` (tip `65146ef`) as `29331e7`, clean (no conflicts; all six lane files disjoint from everything main touched since the `7063a50` merge-base). Landed: additive `LiveWireSpendGuard` (+7 deterministic unit cases), the 2026-10-24 partial artifacts (`matrix.json`, spend-ledger entries 1–2), and the 2026-10-25 resume record + `matrix-resume.json` + spend-ledger entries 3–17. Review: approve. Closure notes for the reviewer's two record-hardening items landed as `6ce6219` (acceptance-evidence table refreshed to the 2026-10-25 resume reality; Anthropic leg explicitly pending; continue-after-entry-#3 reviewer observation recorded).
- **Credential provenance and hygiene:** the demo stand key was located read-only in the neighboring `yabanin-infra` repo under `.local/demo-20261024/opencode-api-key` (owner-directed scout), used at runtime only — never printed, logged, persisted, or passed through argv; masked tail `715` is the only trace. Secret hygiene re-verified at integration: no key material anywhere in the merged diff (only token counts and the path-only reference).
- **Matrix results (endpoint `https://demo.yabanin.ai/gw/v1`, OpenAI-compatible), with honest negatives:** authenticated probe HTTP 200 on `glm-5.3-flash`; direct chat HTTP 200 with full usage but reasoning-only finals at `max_tokens<=50` (content empty, also with `thinking:{"type":"disabled"}` — model-behavior finding, not a wire failure); streaming HTTP 200 (9 fragmented deltas + usage chunk); complete tool round HTTP 200 (`tool_calls` → fixture 2+3→5 → `stop`, `cached_tokens=128`); cancellation mid-stream closed after the first content chunk with final usage never received — charge UNKNOWN, $0.10 reservation retained, guard stopped; usage/cost recorded per response with placeholder-rate estimates only (actual gateway pricing unknown). The Anthropic-compatible leg remains **PENDING** — no owner-configured endpoint; nothing claims it passed.
- **Spend vs cap:** 17/100 inference requests since authorization; estimated generation spend ≈ $0.0069, conservatively committed/reserved $0.2069 against the $5.00 cap (two $0.10 reservations retained: pre-generation 400 #3 and cancellation #17); 83 requests remain; guard stopped per its uncertain-charge semantics; no post-stop dispatch or retries.
- **Reviewer notes:** continuing after entry #3 was deliberate diagnostics of a self-inflicted harness bug (zero/duplicate Host headers → bare 400), all counted against both caps, with the $0.10 reservation for #3 conservatively retained as UNKNOWN; the record's stale "next card: RAI-185" line was corrected (RAI-185 is already accepted).
- **Statuses/dependencies:** RAI-184 flipped to accepted in `docs/tasks/backlog.json`, `docs/tasks/m1.md`, and the `docs/tasks/README.md` index. Dependency closure recomputed: no card lists RAI-184 as a dependency (directly or transitively), RAI-185 is already accepted, so no additional card became ready; RAI-186 stays ready on RAI-131+RAI-185 (both accepted), RAI-187 stays gated on RAI-186.
- **Gates:** `make quality-changed` exited 0 (clean-tree minimum `fast`); `make quality-changed QUALITY_EXECUTE=1` exited 0, all 10 fast gates passed, no timeout, manifest `artifacts/quality/20261025-065315-64976-0001/manifest.json` (SHA-256 `5a672c8a92ad1148c0a3c1b4647eeade05f91af66c955c0a09dde7e4b8ff2b5b`). The lane's own required profiles were verified on the lane tree and are referenced, not rerun: fast `artifacts/quality/20261025-031542-55347-0001/manifest.json`, static/unit/contracts/transport `artifacts/quality/20261025-032258-58899-0001/manifest.json`, plus fast verification rerun `artifacts/quality/20261025-033819-65672-0001/manifest.json` (all status passed).
- **Plan validation:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0; 228 cards; report status `passed`.
- **Record:** [RAI-184 work record](records/RAI-184.md).

## 2026-10-24 — RAI-191 + RAI-198 accepted (reviews approve)

- **RAI-191 integration:** verified root baseline `652f7f1` clean on `main`; merged `lane/rai-191` (tip `388e974`) as `d0a26b9`. Review: approve, with two hardening notes routed to RAI-192 as binding acceptance conditions. Landed: additive renderer-side `DiffHunk` model with the caller-side `UnifiedDiff` seam (parsing stays caller-side; no module-graph change), streaming-safe per-delta re-parse in `ChatLayoutSink`, and the combined markdown+code+diff PTY in the RAI-188 shell-suite family. The merge kept BOTH sides of the RAI-208/RAI-191 overlap in `Main.scala`/`ChatLayoutPtySpec` (208's live plan-fixture prelude and three B3B PTY tests + 191's `raider.chat.testAnswer` seam, two-delta streaming shape, and combined PTY case; the plan-fixture JSON stays a single pure-JSON delta because `PlanProposal.parse` decodes the whole candidate). Post-resolution PTY rerun once: `ChatLayoutPtySpec` + `MainTabsPtySpec` + `ToolApprovalChatPtySpec` = 15/15 passed, exit 0.
- **RAI-191 routed hardening (recorded on RAI-192):** (1) `renderHunk` must neutralize raw ESC bytes in hunk content like the inline path does; (2) frame-level clip counts SGR bytes as width — trailing reset loss on colored rows. RAI-192 flipped to ready (dep 191 ✓).
- **RAI-198 integration:** merged `lane/rai-198` (tip `c875d4d`) as `5db5e61`, clean (no conflicts; disjoint from the repl/ui surface). Review ruling executed: XE-LOWER-01/02/03 closed per the coordinator decisions — 63-row frozen-field conformance table with exact typed refusals; repair = declared-safe-operation repeats under the declared `maxAttempts` bound, typed exhaustion (`LocalBudgetExceeded`), uncertain never repeated; then/else type check tightened. RAI-199 flipped to ready (dep 198 ✓; RAI-187 remains its open second dependency, consistent with the RAI-184 ready-with-pending-dep precedent).
- **Integrated gates:** `make quality-changed` exited 0 (clean-tree minimum `fast`). First `make quality-changed QUALITY_EXECUTE=1` failed only at `scalafmt-check` on the merge-resolved `Main.scala` (integration-only reflow, fixed in `a2fa16c`; not the MJE-03c watch, which stayed green); the rerun passed all 10 fast gates, exit 0, manifest `artifacts/quality/20261025-004619-81981-0001/manifest.json`. `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` exited 0; all 8 selected gates passed (`sbt-test`, `cli-smoke`, `core-contracts-spec`, `compile-fixtures`, `plan-validate`, `repl-smoke`, `sbt-compile`, `scalafmt-check`), test summary 8/0/0, no timeout, manifest `artifacts/quality/20261025-005620-1800-0001/manifest.json`.
- **Plan validation:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0; 228 cards.
- **Records:** [RAI-191 work record](records/RAI-191.md); [RAI-198 work record](records/RAI-198.md).

## 2026-10-24 — RAI-208 accepted (round-2 approve)

- **Integration and review:** verified root baseline `d75f395` clean on `main`; merged `lane/rai-208` (tip `99bcc3e`) as `17e3e23`. Round-2 review: approve, no findings. The one-hub interpreter-world design landed: ReplSession constructs the session EventHub and hands the SAME instance to BudgetLedger; ADR-024 events and PlanSession snapshots cross the bridge serialized, with no synthetic values. The restored approval PTY exposed a REAL effect-execution bug — `:approve`/`:deny` returned an unexecuted `UIO[Boolean]`; fixed narrowly via `ReplCommands.resolveToolApproval` (unsafeRun pattern); RA-TOOLDENY preserved; combined PTY 3/3. TUI-X.B3 fully closed. The merge needed three conflict resolutions in `Main.scala` (the lane predated the RAI-189/190 landings): the scripted prelude keeps the lane's parametrized plan-fixture backend plus main's required `__model` binding, and the approval decisions take the lane's `ReplCommands.resolveToolApproval` (semantically identical to main's inline unsafeRun on the same sink).
- **Statuses/dependencies:** RAI-208 accepted; TUI-X.B3 fully closed. Dependency closure recomputed after the flip: no card depends on RAI-208 and no additional card became ready — RAI-192 still waits on RAI-191 (in flight), and RAI-123 stays todo because it is trigger-gated conditional, not dependency-gated. Task Markdown, index, and backlog are synchronized.
- **Integrated gates:** `make quality-changed` exited 0 (clean-tree minimum `fast`). `make quality-changed QUALITY_EXECUTE=1` exited 0; all 10 fast gates passed on the first run, no timeout, so MJE-03c's authorized rerun was not triggered; manifest `artifacts/quality/20261024-231715-17155-0001/manifest.json`. `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` exited 0; all 8 selected gates passed (including `sbt-test` with 199 tests/0 failed covering the merged `ChatLayoutPtySpec` and `ToolApprovalChatPtySpec`, `cli-smoke`, `repl-smoke`), no timeout; manifest `artifacts/quality/20261024-232651-26644-0001/manifest.json`.
- **Plan validation:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0; 228 cards. Post-status docs gate: `make quality-changed` classified the task/board edits as `docs`; `make quality-changed QUALITY_EXECUTE=1` exited 0, `plan-validate` passed, no timeout; manifest `artifacts/quality/20261024-234039-35236-0001/manifest.json`.
- **Record:** [RAI-208 work record](records/RAI-208.md).

## 2026-10-24 — RAI-190 slice accepted; RAI-208 lane review-ready

- **Integration and review:** verified root baseline `e83973f` clean on `main`; merged `lane/rai-190` (tip `b423cab`) as `1ef4595`. Coordinator slice review approved. RAI-190 is accepted on the session-switcher/footer/tasks-panel skeleton basis; live plan/budget wiring remains explicitly with RAI-208. The one-hub interpreter-world design landed on the RAI-208 lane; its acceptance requires a real `BudgetChanged` footer and populated plans PTY. RAI-198's remaining work is exhaustive conformance and repair composition.
- **Statuses/dependencies:** RAI-190 accepted; RAI-208 remains ready (depends on RAI-190 + RAI-188). RAI-191 is ready with dependencies RAI-188 + RAI-190, both accepted. Task Markdown, index, and backlog are synchronized.
- **Integrated gates:** `make quality-changed` exited 0 (clean-tree minimum `fast`). `make quality-changed QUALITY_EXECUTE=1` exited 0; all 10 fast gates passed, including `sbt-test`, no timeout; manifest `artifacts/quality/20261024-192634-52152-0001/manifest.json`. MJE-03c was green on the first run, so its authorized rerun was not triggered. `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` exited 0; all 8 selected gates passed, including `sbt-test`, `cli-smoke`, and `repl-smoke`, no timeout; manifest `artifacts/quality/20261024-193808-2677-0001/manifest.json`.
- **Plan validation:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0; 228 cards. RAI-190/191 status and RAI-191 dependency consistency validated.
- **RAI-198 scope decisions:** compatibility is limited to ADR-036 v1's declared types and resolvable pointers, with consumer-input typing routed to a future reviewed ADR-036 amendment (potentially motivated by RAI-199); repair repeats only a declared safe operation within the existing bounded-attempt discipline, while RAI-164 stage-evidence composition is routed to RAI-199's launch path.
- **Post-status docs gate:** `make quality-changed` classified task/board docs as `docs`; `make quality-changed QUALITY_EXECUTE=1` exited 0, `plan-validate` passed, no timeout; manifest `artifacts/quality/20261024-195008-36601-0001/manifest.json`.
- **Record:** [RAI-190 work record](records/RAI-190.md).

## 2026-10-24 — RAI-189 accepted (B2); RAI-208 registered (190.b split)

- **RAI-189 integration and status:** `lane/rai-189` tip `e965b86` merged into main. Coordinator review approved; approval/deny/read-only and pending-Unicode-draft PTY cases passed ×3 each, including actual approved-file creation and denied-file absence. RAI-189 is accepted in `docs/tasks/m2-p2.md` and `docs/tasks/backlog.json`.
- **RAI-190 split / RAI-208:** RAI-208 was registered as the 190.b follow-up for the remaining live plan/budget bridge. The split is justified by two honest implementation stops at the interpreter/shell classloader boundary; preserve the one interpreter-world session hub and serialized ADR-024 event/PlanSession snapshot bridge, with no synthetic values. RAI-208 remains ready and depends on RAI-190 and accepted RAI-188.
- **RAI-198 ruling remainder:** the existing card retains full type validation, root-limit composition at the launch layer, RAI-164 bounded repair composition via descriptor reuse, and XE-LOWER runtime acceptance as its remaining work; no RAI-198 acceptance is claimed here.
- **Integrated gates:** `make quality-changed` exited 0 and selected minimum `fast`. The first `make quality-changed QUALITY_EXECUTE=1` run failed only at watched MJE-03c (`sbt-test`, exit 1; no timeout; manifest `artifacts/quality/20261024-184430-27852-0001/manifest.json`); the one authorized rerun passed all 10 fast gates, exit 0 (`artifacts/quality/20261024-185539-47236-0001/manifest.json`). `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` passed all 8 listed gates (`artifacts/quality/20261024-190548-56409-0001/manifest.json`); the additional card-required `security` profile passed `security-secrets-scan` (`artifacts/quality/20261024-191559-80860-0001/manifest.json`).
- **Plan/status validation:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0; 228 cards. After status synchronization, `make quality-changed` selected `docs` and `make quality-changed QUALITY_EXECUTE=1` passed `plan-validate`, exit 0, no timeout (`artifacts/quality/20261024-191758-96216-0001/manifest.json`).
- **Record:** [RAI-189 work record](records/RAI-189.md).

## 2026-10-24 — RAI-190 live plan/budget wiring split to RAI-208

- **Split decision:** RAI-190's delivered session/footer/panel slice is its acceptance basis. Register RAI-208 for the remaining interpreter-world hub construction, serialized ADR-024 event and PlanSession snapshot bridge, real task/budget population, and PTY ×3 (populated tasks, real budget transition, session-switch regression). The split follows two honest implementation stops at classloader-boundary design depth; preserve the existing one-hub/no-synthetic-values mandate. RAI-208 depends on RAI-190 and accepted RAI-188 and is ready.

## 2026-10-24 — RAI-189 shared approval hook integrated (partial)

- **Coordinator mandate:** committed as `e4d7a44`, authorizing the typed approval hook move to `modules/runtime` for both REPL and CLI consumers while freezing RA-TOOLDENY/no-auto-approve behavior. RAI-190's EventHub/plan-binding injection mandate is recorded in its card for its lane.
- **RAI-189 implementation:** lane `lane/rai-189` commit `6b38c11` merged as `da6088f`; runtime owns `raider.runtime.tools.approval.ToolApproval`, consumed by `raider-repl` and `raider-cli`. CLI uses ChatLoop's shared `BufferedReader` for explicit approval input. No module cycle.
- **Lane evidence:** `sbt --batch raiderCli/compile raiderRepl/compile` and `sbt --batch 'raiderRepl/testOnly raider.repl.ToolApprovalSpec'` exited 0 (3/3 tests). `make quality-changed` classified contracts/static/unit; execution manifest `artifacts/quality/20261024-084759-15927-0001/manifest.json` passed. `make quality-profile PROFILES="static unit contracts runtime repl"` manifest `artifacts/quality/20261024-085548-28941-0001/manifest.json` passed, and all five profile verifications passed.
- **Integrated root evidence:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0; 227 cards. First root fast execution found a Scalafix parse error for a for-comprehension value binding; coordinator moved it to a local `val`. Corrected `make quality-changed QUALITY_EXECUTE=1` passed (`artifacts/quality/20261024-091457-52003-0001/manifest.json`); `make quality-profile PROFILES="static unit contracts runtime repl"` passed (`artifacts/quality/20261024-092258-55938-0001/manifest.json`), with all five manifest verifies passing.
- **Post-record verification:** after board/work-record updates, `make quality-changed QUALITY_EXECUTE=1` passed with contracts/docs/static/unit (`artifacts/quality/20261024-093223-68499-0001/manifest.json`); docs/contracts/static/unit manifest verification passed.
- **Full card profiles:** `make quality-profile PROFILES="static unit contracts runtime repl security"` passed all seven gates including `security-secrets-scan` (`artifacts/quality/20261024-094138-74006-0001/manifest.json`); all six profile verifications passed.
- **Status/remaining acceptance:** RAI-189 remains partial: approval/denial/read-only PTY transcripts ×3 and P2W invariants while approval is pending were not run; independent review remains pending. See [work record](records/RAI-189.md). Spawned agent executors were unavailable; execution followed the runbook's sequential fallback.

## 2026-10-23 — RAI-194 accepted: native NO-GO (JVM distribution confirmed)

- **Integration and verdict:** `lane/rai-194` source `b1acc5b` merged to main as `fb84dfd`. Coordinator review approved: the closed-world claim was verified in `BundleLoader`/`HeadlessRunner`, scoping is proper, and unavailable metrics remain honestly unmeasured. Native Image is a no-go under the current dynamic workflow-JAR contract; RAI-193's JVM distribution remains supported. See [work record](records/RAI-194.md).
- **Dependency flip:** RAI-194 accepted; RAI-193 remains accepted. The RAI-194 dependency is satisfied.
- **Current cycle state:** RAI-198 partial (validator 17/17 + 10/10, all-10 structural lowering; remaining: full type validation, root-limit composition, RAI-164 repair integration, XE-LOWER runtime acceptance); RAI-189 partial (approval gate live in the REPL facade with RA-TOOLDENY defaults + `:approve`/`:deny`; remaining: CLI-path wiring + 3 PTY transcripts); RAI-190 partial (sessions + footer done; tasks-panel/budget wiring needs the plan-loop + budget-hub connections in the chat session).
- **Integrated gates:** `make quality-changed` exited 0 (clean-tree minimum `fast` classification). `make quality-changed QUALITY_EXECUTE=1` exited 0; all 10 fast gates passed, timeout false (`artifacts/quality/20261023-201122-78135-0001/manifest.json`). `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` exited 0; all 8 gates passed, including `sbt-test`, `cli-smoke`, and `repl-smoke`, timeout false (`artifacts/quality/20261023-201856-85473-0001/manifest.json`). MJE-03c was not red, so the authorized single rerun was not triggered.
- **Post-status verification:** `make quality-changed` selected `docs`; `make quality-changed QUALITY_EXECUTE=1` exited 0 with `plan-validate` passed, timeout false (`artifacts/quality/20261023-202827-92397-0001/manifest.json`). `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0; 227 cards. RAI-194 is synchronized as accepted in the task Markdown and backlog.

## 2026-10-23 — RAI-197 accepted (portable contract frozen)

- **Integration and verdict:** `lane/rai-197` source `7b37d80` merged to main as `29088e3`. Round 1's two contract gaps were closed; round 2's byte-limit fixture finding was closed with reproducible, schema-valid 262145-byte ASCII and wide-Unicode artifacts. Approve-on-closure: both rounds approved, ADR-036 Accepted, RAI-197 accepted. The byte cap is a RAI-198 preflight check (`UTF-8 byte length > 262144` → `document-too-large` at root pointer `""`); no runtime validator is claimed in RAI-197. See [work record](records/RAI-197.md).
- **Dependency flips:** RAI-197 accepted; RAI-198 ready. Backlog dependency check: RAI-198 depends only on RAI-197, now accepted.
- **Fixtures and lane evidence:** positive fixtures 10/10; refusal declarations 17/17; both over-limit artifacts 262145 bytes and schema-valid. Lane `make quality-changed`, `make quality-changed QUALITY_EXECUTE=1`, and `make quality-profile PROFILES="docs contracts"` exited 0; manifests are recorded in the work record.
- **Integrated candidate gates:** `make quality-changed` exited 0 and selected docs. `make quality-changed QUALITY_EXECUTE=1` exited 0; `plan-validate` passed, timeout false (`artifacts/quality/20261023-152330-54292-0001/manifest.json`). `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` exited 0; all 8 gates passed including `sbt-test`, `cli-smoke`, and `repl-smoke`, with no timeout (`artifacts/quality/20261023-152352-57988-0001/manifest.json`). MJE-03c was not red, so its authorized rerun was not triggered.
- **Plan validation:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0; 227 cards.

## 2026-10-23 — RAI-200/202/204 accepted

- **Integrated merges:** RAI-200 from `lane/rai-200` merged as `eeae3e9`; the process protocol ADR was then renumbered ADR-036 → ADR-037 in `7831c06`, preserving ADR-036 for RAI-197. RAI-202 merged as `c6dbcbc`; RAI-204 merged as `f347828`.
- **Independent review recording:** lane records honestly retain pending review at their lane tips. Following the established integration pattern, the coordinator appended the independent-review verdicts to each record on main: RAI-200 round-1 approve (Unicode reconstruction, cancel acknowledgement, death without terminal spot-checks; contract complete against plan §4); RAI-202 round-1 changes-required for the absolute “encoder sends only” claim, closed by `80d3245`, substance approved; RAI-204 round-1 changes-required for unsupported JVM-distribution `--help` examples, closed by `aaccb1c`, substance approved.
- **Statuses and dependency ripple:** RAI-200/202/204 accepted in task Markdown and backlog. RAI-198 remains blocked on RAI-197; RAI-201 remains blocked on RAI-187. No other ripple is ready. RAI-197 round-1 findings remain in the fix flight. RAI-202 boundaries stay explicit: native constrained output is unsupported by the typed contract; the escape hatch is untyped/no-evidence; fallback is explicit and budgeted; unknown cost remains unknown. RAI-204 human ten-minute usability remains pending.
- **Integrated gates:** `make quality-changed` exited 0 and selected `docs`. `make quality-changed QUALITY_EXECUTE=1` exited 0; `plan-validate` passed, timeout false (`artifacts/quality/20261023-145058-4227-0001/manifest.json`). `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` exited 0; all 8 listed gates passed, including `sbt-test`, timeout false (`artifacts/quality/20261023-145118-7110-0001/manifest.json`). No MJE-03c red was observed, so no rerun was needed.
- **Plan validation:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0; 227 cards. Backlog/task Markdown status consistency was checked for RAI-197/198/200/201/202/204.

## 2026-10-23 — RAI-196 accepted: cross-ecosystem program open

- **RAI-196:** ADR-035 remains Proposed on main; independent review round 1 approved the substance. The reuse audit distinguishes exists/adapt/new, keeps `SdlcStages` scoped to SDLC rather than a general graph runtime, and marks mockup usability pending. The integrated work record is [RAI-196](records/RAI-196.md).
- **Dependency flips:** RAI-197, RAI-200, and RAI-202 are ready; each backlog dependency is RAI-196, now accepted. These three contract cards run in parallel next with exclusive concrete file ownership.
- **Other active work:** RAI-188 accepted; RAI-189/190 ready (TUI-X B2/B3). RAI-204 remains partial/in progress: the TabShell blocker is cleared and completion is next; its missing `release` profile/applicability gap remains routed. RAI-186/195 fixtures are done and await RAI-207 (protected CWF-E02 migration) for the final green.
- **Integrated gates:** `make quality-changed` exited 0 (clean-tree minimum `fast` classification); `make quality-changed QUALITY_EXECUTE=1` exited 0 with all 10 fast gates passed, timeout false (`artifacts/quality/20261023-072616-54063-0001/manifest.json`); `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` exited 0 with all 8 selected gates passed, timeout false (`artifacts/quality/20261023-073508-82305-0001/manifest.json`). Fast EXECUTE review evidence `20261023-071719` was also verified before integration.
- **Exceptions/watch:** TAB-02 remains a conditional exception and was not observed red. MJE-03c remains a historical watch and was not observed red.
- **Post-status-sync verification:** `make quality-changed QUALITY_EXECUTE=1` and `make quality-profile PROFILES="docs"` both exited 0; `plan-validate` passed with timeout false (`artifacts/quality/20261023-074510-16209-0001/manifest.json`, `artifacts/quality/20261023-074510-16210-0001/manifest.json`). `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0; 227 cards. The first validator run caught a Markdown dependency annotation mismatch; corrected before the passing reruns.

## 2026-10-23 — TabShell scalafix fix; RAI-188 accepted; RAI-196/204 lanes; CWF-E02 conflict routed

- **Parser fix:** `TabShell.scala` moves `chatAction = ...` out of a Scala 3 `for`-comprehension into a normal local `val`; chat dispatch behavior is unchanged and the source now parses under scalafix.
- **RAI-188 and dependency flips:** RAI-188 accepted after integrated gates and independent review approval; RAI-189 and RAI-190 ready (each depends only on accepted RAI-188). RAI-196 moved to review. RAI-204 is in progress with onboarding work partial; its requested `release` profile is absent from the current gate registry, so the profile applicability/registration gap is routed as an explicit follow-up policy question. RAI-204 is not accepted.
- **Protected expectation conflict:** RAI-186's old CWF-E02 bogus-success expectation (verdict omitted and legacy error-detail wording) conflicts with RAI-195's task-failed verdict plus executed-check evidence contract. Registered RAI-207, dependent on RAI-195; the protected expectation/digest migration is expressly deferred until RAI-195 acceptance and requires independent review.
- **Focused verification:** `sbt --batch raiderUi/scalafixAll raiderUi/Test/compile 'raiderRepl/testOnly raider.repl.MainTabsPtySpec raider.repl.ChatLayoutPtySpec'` exited 0; 8 PTY tests passed, 0 failed/ignored.
- **Integrated gates:** `make quality-changed` exited 0; selected `contracts`, `static`, `unit`. `make quality-changed QUALITY_EXECUTE=1` exited 0; all 5 gates passed, timeout false; manifest `artifacts/quality/20261023-063518-83301-0001/manifest.json`. `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` exited 0; all 8 gates passed, timeout false; manifest `artifacts/quality/20261023-064307-8972-0001/manifest.json`.
- **Post-status docs gates:** after the card/index/board synchronization, `make quality-changed` selected `contracts`, `docs`, `static`, `unit`; `make quality-changed QUALITY_EXECUTE=1` exited 0 with all 6 gates passed, timeout false; manifest `artifacts/quality/20261023-070459-91326-0001/manifest.json`. `make quality-profile PROFILES="docs"` exited 0; `plan-validate` passed, timeout false; manifest `artifacts/quality/20261023-070459-91327-0001/manifest.json`.
- **Exceptions/watch:** TAB-02 (RAI-172) remains a conditional exception and was not observed red. MJE-03c remains a historical intermittent watch; it was not observed red in these runs.
- **Plan validation:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0; 227 cards.

## 2026-10-23 — Cross-ecosystem competitive plan registered

- **Owner request:** study LLM4S and LangChain4j in depth and update OpenCode's plan for use beyond Scala, including evaluating an ecosystem-independent DSL.
- **Scope:** [research](../research/llm4s-langchain4j-2026-10-23.md) and [delivery plan](../cross-ecosystem-plan.md). Proposed schema-backed portable workflows and an opt-in local process adapter share the existing Scala/ZIO runtime. New public contracts require separate ADR cards. No grammar, foreign-language SDK, runtime implementation, paid calls, outreach or publication was performed by this planning task.
- **Registration:** RAI-196..206 added with mirrored cards/index/dependencies; 196 and 204 ready, remaining cards todo. RAI-206 also needs a real JVM consumer request. Preserve existing First CI 186/195/187, live-wire 184 and TUI-X 188..192; do not reopen accepted 155/160/193.
- **Estimate:** 444–888 additional provisional engineering hours for 196..205; optional 206 adds 16–32. Existing-scope reuse and allocation are audited before implementation. Capability implementation gaps receive separate cards/estimates; old envelopes are not silently enlarged.
- **Execution:** coordinator-only documentation writes; visible read-only competitor research and independent review. Baseline `a351d3c`; planning card `docs/tasks/roadmap-cross-ecosystem.md`. Independent review approved after estimate arithmetic correction. Plan validation passed (226 cards); quality-changed and its execution plus explicit docs profile exited 0. [Work record](records/ROADMAP-CROSS-ECOSYSTEM.md) contains exact manifests and scope limits.

## 2026-10-23 — RAI-193 accepted: first distribution (v0.1.1 local)

- **Release:** RAI-193 is accepted after the integrated JVM distribution merge and round-3 review approval. Local releases `v0.1.0` and `v0.1.1` were produced through Raider's SDLC release pipeline; `v0.1.1` includes the Coursier 2.1.26 and Java fallback launcher smokes, fat JAR, rendered Homebrew/Scoop formulas, checksums, typed negative-install refusals (21), and SIGTERM cleanup (143 without partial promotion). Tags/artifacts are local; no hosted publication is claimed. Self-authorization basis is recorded in `docs/work/records/RAI-193.md`.
- **Statuses and dependencies:** RAI-193 accepted; RAI-194 ready with its RAI-193 prerequisite satisfied. RAI-195 is in flight: contract and classifier are done; end-to-end evidence is routed through the RAI-186 return. RAI-188's machinery decision remains pending the TabShell-extension mandate.
- **Registry:** `packaging/` maps additively to `static` + `contracts`; independent registry mapping review is complete, with no new gates or threshold changes.
- **Integrated gates:** `make quality-changed` exited 0 and classified the clean tree as minimum `fast`; `make quality-changed QUALITY_EXECUTE=1` exited 0 with all 10 gates passed and timeout false (`artifacts/quality/20261023-034241-73526-0001/manifest.json`); `make quality-profile PROFILES="static unit contracts runtime ci docs security"` exited 0 with all 8 gates passed and timeout false (`artifacts/quality/20261023-035006-82495-0001/manifest.json`). No retry was needed.
- **Exceptions/watch:** TAB-02 (RAI-172) remains the documented conditional exception and was not observed red. MJE-03c remains a RAI-157 historical intermittent watch; it was not observed red in these gate runs.
- **Plan validation:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0 after status synchronization; 215 cards.
- **Post-status docs gate:** `make quality-changed` selected `docs`; `make quality-changed QUALITY_EXECUTE=1` exited 0 with `plan-validate` passed and timeout false (`artifacts/quality/20261023-035911-8491-0001/manifest.json`).
- **Record:** `docs/work/records/RAI-193.md`.

## 2026-10-23 — RAI-195 registered from the RAI-186 proposal

- **Contract card:** RAI-195 is registered ready for the trusted-workflow/bundle `trustedChecks` advisory/blocking declaration, frozen serialization/provenance, and fail-safe blocking defaults. RAI-186 completes FCI-REPORT-01 under this contract; threshold semantics are out of v1.

## 2026-10-22 — RAI-185 accepted (First CI process level); live-wire blocked on gateway

- **Integrated merge:** RAI-185 from `lane/rai-185` merged to main as `e2626871389dffd0f63766416dee431705ad3fc3`. Three review rounds closed; round 3 approved. FCI-CLI-01/02 and FCI-CTX-01/02 are accepted on the real CLI child-process fixture, including distinct `result.json`/`artifacts.json` surfaces, manifest set-equality, zero temporary files, SHA-256 integrity, non-TTY behavior, adapter environment parity/redaction, and malformed-input pre-dispatch refusal (exit 21, before ledger admission).
- **Statuses:** RAI-185 accepted; RAI-186 ready (RAI-131 and RAI-185 are accepted prerequisites). RAI-184 remains open pending gateway availability or an alternative endpoint from the owner.
- **MJE-03c:** recurrence investigation did not reproduce the failure: five isolated runs passed 8/8 each and the full module suite passed 491/0 in 228 seconds. Load sensitivity remains an unproven-causal hypothesis; the watch remains intact.
- **Live-wire blocker:** zai models were identified through the Yabanin gateway at `yabanin.ai/gw/v1` (`glm-5.3` / `glm-5.3-flash`); the credential bridge worked (masked). One dispatch attempt ended in a transport error. Coordinator probes without a proxy returned HTTP 502 from both `/models` and `/chat/completions` in 0.3 seconds, indicating a server-side outage. The lane stopped honestly; the $0.10 reservation is retained and no retries were made.
- **Integrated gates:** `make quality-changed` exited 0 (clean-tree minimum `fast` classification). `make quality-changed QUALITY_EXECUTE=1` exited 0; all 10/10 fast gates passed, timeout false; manifest `artifacts/quality/20261022-180230-91662-0001/manifest.json`. `make quality-profile PROFILES="static unit contracts runtime ci security docs"` exited 0; all 8/8 registered gates passed, timeout false; manifest `artifacts/quality/20261022-181143-98346-0001/manifest.json`.
- **Documented exception:** TAB-02 (RAI-172) remains conditional and distinct from these passing gates; it was not observed red.
- **Plan validation:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0; 214 cards. After status synchronization, `make quality-changed` selected `docs` and the `QUALITY_EXECUTE=1` rerun exited 0; `plan-validate` passed, timeout false; manifest `artifacts/quality/20261022-182307-11200-0001/manifest.json`.
- **Record:** `docs/work/records/RAI-185.md`.

## 2026-10-22 — Live-wire owner config (zai, models, $0.10 reservation); scope notes; packaging registry mapping

- **Live-wire configuration:** zai leg selected for this execution, with `glm-5.3-flash` requested and `glm-5.3` observed; reserve $0.10 per response. Anthropic is deferred. This records configuration only, not a live execution or spend claim.
- **Scope notes:** RAI-188 authorizes the model-delta bridge into the ADR-024 renderer seam while retaining existing terminal-output paths as fallback. RAI-193 authorizes additive LocalRelease staging of fat-jar/launcher/formulas atomically into the checksummed artifact set.
- **Registry path mapping:** `packaging/` maps to existing `static` + `contracts` profiles; additive classification only, with no new gates or threshold changes. **Independent review required per RAI-135 precedent (pending).** No RAI-193 lane files or work record were present in the root snapshot when checked.
- **Work record:** [coordinator execution evidence](records/COORD-RAI-188-193-REGISTRY-20261022.md).

## 2026-10-22 — TUI-X program registered (owner decisions)

- **Owner decisions:** Evolve Raider's own ANSI renderer under ADR-016 and the MIT-only policy, with no new runtime dependencies; phase distribution JVM first, Native only after evaluation; sequence TUI work B1 → B2/B3 → A1 → C1, with JVM distribution D1 parallel to B and native-image evaluation D2 after D1.
- **Program:** RAI-188..194 register the chat-centric layout/streaming pane, inline-approval tool cards, sessions/tasks/status footer and palette, own-renderer markdown/highlighting/diff, diff review/model-provider switching, JVM distribution, and native-image feasibility spike.
- **Estimate and scope:** Approximately 1,150–1,550 gross engineering hours, beyond the closed 13.1k-hour envelope as a new owner-authorized program. Honest target: a Codex/OpenCode-like agent TUI with convenient distribution; this is not a claim of complete Codex/OpenCode ecosystem parity.
- **Status/dependencies:** RAI-188 and RAI-193 are ready with no card dependencies; RAI-189/190/191 follow RAI-188; RAI-192 follows RAI-189 and RAI-191; RAI-194 follows RAI-193. The registered phase order is B1 → B2/B3 → A1 → C1, with D1 in parallel and D2 afterward.

## 2026-10-22 — RAI-176 accepted (takeover complete); CI program registered

- **Takeover arc:** External-session RAI-176 work was preserved as the lane baseline, completed with the streaming tool-definition regression fix and dynamic-port scripted local probe (real file creation ×3), independently reviewed and approved, then integrated; probe finding closed. RAI-176 is accepted in task Markdown and backlog.
- **CI program:** RAI-184..187 were registered at `c60aecf`; live-wire execution and the First CI acceptance program are now queued as the next program work.
- **Integrated merge:** `lane/rai-176` merged to main as `f69f2b0` (`RAI-176 streaming tool definitions preserved`).
- **Integrated gates:** `make quality-changed` exited 0 and selected minimum `fast` on the clean tree. `make quality-changed QUALITY_EXECUTE=1` exited 0; all 10/10 fast gates passed, timeout false; manifest `artifacts/quality/20261022-032229-22687-0001/manifest.json`. `make quality-profile PROFILES="static unit contracts transport ci docs"` exited 0; all 7/7 listed gates passed, timeout false; manifest `artifacts/quality/20261022-033002-36824-0001/manifest.json`. No retry was needed.
- **Documented exception:** TAB-02 (RAI-172) remains conditional and distinct from these passing gates; it was not observed red.
- **Post-status verification:** `make quality-changed` selected `docs`; `make quality-changed QUALITY_EXECUTE=1` exited 0 with `plan-validate` passed, timeout false; manifest `artifacts/quality/20261022-033803-49744-0001/manifest.json`. The requested static/unit/contracts/transport/CI/docs profiles were rerun on the accepted-status candidate and passed all 7/7 listed gates, manifest `artifacts/quality/20261022-033810-50154-0001/manifest.json`.
- **Plan validation:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0; 207 cards.
- **Record:** `docs/work/records/RAI-176.md`.

## 2026-10-21 — Owner decisions: live-wire $5 cap (execution authorized); CI program next; RAI-176 takeover

- **Live-wire (RAI-171):** In progress; execution authorized only for openai-compatible + anthropic-compatible, $5.00 total and at most 100 requests, with runner-enforced per-call hard-stop, no retries beyond cap, environment-only credentials, per-call budget checks, a spend log, and no credential/secret-value leakage.
- **TokenRouter packet:** Discovery started read-only on the sibling checkout; the owner chose “give now,” but checkout path and first target were not received, so the exact approved command list remains owed.
- **Next program:** CI adapters full (RAI-023-full/024/026), toward PLAN.md’s First CI Acceptance; gap assessment is in flight.
- **RAI-176 takeover:** External session’s uncommitted `OpenAIChatStreamSpec` diff in `wt/rai-176` is handed to the coordinator and preserved unchanged as the baseline for completion, review, and merge under the parallel-development runbook.

## 2026-10-21 — RAI-136 accepted; hygiene round

- **Integrated merge:** RAI-136 from `lane/rai-136` merged on main as `93abb140f9542cb97b2704e9d495045eb679a9cd`; review approved. SOAK-01 sustained storm completed with three full `raiderRepl/test` runs (186 passed / 0 failed or ignored each, sampled load 60–145), SOAK-02 completed 18/18 focused PlanPanel attempts without `plan-exec` recurrence (RAI-123 stays conditional; no fix without a reproducer), and SOAK-03's two prior full-suite passes were verified. Evidence: `docs/work/records/RAI-136.md` and lane logs under `artifacts/rai-136/`.
- **Statuses:** RAI-136 accepted in `docs/tasks/backlog.json` and `docs/tasks/m2-p2.md`. RAI-173/174 drift flips to accepted are in hygiene commit `8c1362f`. Hygiene removed 12 merged worktrees and branches; superseded lane tips (LIN-06 blocker record and original RAI-168 service) were backed up to the temporary archive.
- **Integrated gates:** `make quality-changed` exited 0 (clean-tree minimum `fast`). `make quality-changed QUALITY_EXECUTE=1` exited 0; all 10 fast gates passed, timeout false; manifest `artifacts/quality/20261021-175246-82810-0001/manifest.json`. `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` exited 0; all 8 registered gates passed, timeout false; manifest `artifacts/quality/20261021-175938-87513-0001/manifest.json`. No timeout, retry, or real red occurred.
- **Documented exceptions and open items:** TAB-02 (RAI-172) remains conditional and is distinct from passing integrated gates; RAI-123 remains conditional because no reproducer was captured. Remaining open items: live-wire cap and TokenRouter packet (owner).
- **Post-status documentation gate:** after synchronizing RAI-136 status, `make quality-changed` selected `docs`; `make quality-changed QUALITY_EXECUTE=1` exited 0 with `plan-validate` passed, timeout false; manifest `artifacts/quality/20261021-180810-98667-0001/manifest.json`.
- **Plan validation:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0; 203 cards.
- **Push:** `git push origin main` succeeded; main advanced from `8c1362f` to `0b742aa` (merge, lane record, and status/board commit).

## 2026-10-20 — P-LIN COMPLETE (LIN-01..08)

- **Program arc:** P-LIN advanced from the session graph, through a valid Scala projection and live incremental wiring, into the snapshot pointer protocol, full live rerun, partial replay, and finally scopes/pins/catalog with producer origins.
- **Integrated merge:** RAI-183 from `lane/rai-183` merged on main as `c8438df695574cbebdb4e4dda32af2b5bdf2b340`; the reviewed producer-origin and toolchain-pin work completes the LIN-08 remainder. Review approved; the nodeless campaign fixture limitation is accepted because it correctly creates no fabricated lineage pointer.
- **Statuses:** RAI-183 accepted; LIN-08 accepted. P-LIN LIN-01..08 is complete.
- **Integrated gates on the merged candidate:** `make quality-changed` exited 0 and selected minimum `fast` for the clean tree. `make quality-changed QUALITY_EXECUTE=1` exited 0; all 10 fast gates passed, timeout false; manifest `artifacts/quality/20261020-205305-56902-0001/manifest.json`. `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` exited 0; all 8 registered gates passed, timeout false; manifest `artifacts/quality/20261020-210022-60024-0001/manifest.json`. No timeout or retry was needed.
- **Post-status documentation gate:** after synchronizing the accepted statuses, `make quality-changed` selected `docs`; `make quality-changed QUALITY_EXECUTE=1` exited 0 with `plan-validate` passed, timeout false; manifest `artifacts/quality/20261020-210919-62197-0001/manifest.json`.
- **Plan validation:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0; 203 cards.
- **Remaining open items:** live-wire cap (owner); TokenRouter packet (owner); TAB-02 (RAI-172 conditional follow-up).
- **Documented exception:** TAB-02 (RAI-172) remains conditional and distinct from passing integrated gates; it was not observed red in these runs.

## 2026-10-20 — LIN-07 accepted; LIN-08 slice merged (producer card RAI-183 registered)

- **Integrated merges:** LIN-07 from `lane/lin-07` merged as `2ad46a8433162b5ffaf8ba41a131573b57777106`; LIN-08 slice from `lane/lin-08` merged as `076e3d3f9e6233874b4f60373cf8d8bb88d263`. LIN-07's selected-subtree live execution, pointer-resolved inputs, typed budget refusal, and cancellation cleanup are integrated. LIN-08 delivers scope-aware catalog/pins and ReplayCli environment-pin consumption; its producer-origin and toolchain-pin remainder is RAI-183.
- **Statuses:** LIN-01..07 accepted. LIN-08 remains in progress (slice integrated; RAI-183 pending). RAI-183 is registered and ready for producer-origin scopes and toolchain-pin production.
- **Scope note:** The 2026-10-20 LIN-07 note authorizes `PartialLiveRerun.scala` and its test plus `ReplayHarness.scala` and `IsolatedRerun.scala` for live composition, `RerunRequest`, and live routing; round-2 review confirmed execution path and acceptance.
- **Integrated gates on current task metadata:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0 (203 cards). `make quality-changed` exited 0 and selected `docs`; `make quality-changed QUALITY_EXECUTE=1` exited 0, `plan-validate` passed, timeout false, manifest `artifacts/quality/20261020-111819-16013-0001/manifest.json`. `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` exited 0; all 8 gates passed (`sbt-test`, `cli-smoke`, `core-contracts-spec`, `compile-fixtures`, `plan-validate`, `repl-smoke`, `sbt-compile`, `scalafmt-check`), timeout false; manifest `artifacts/quality/20261020-111836-16307-0001/manifest.json`. An earlier required `quality-changed QUALITY_EXECUTE=1` run also passed at `20261020-110819-96303-0001`; no timeout or retry was needed.
- **Documented exception:** TAB-02 (RAI-172) remains a tracked conditional follow-up, distinct from these passing gates; it was not observed red in these runs.

## 2026-10-20 — ADR-030 SDLC implementation line COMPLETE

- **Integrated merges:** RAI-170 from `lane/rai-170` merged as `dbb1e71ce2b055ed65c45bf68801ddd6d6fbf8e7`; RAI-182 from `lane/rai-182` merged as `2469e04ca6e2c695c29c874494e725f036b5e433`. RAI-170's independent local-release verification and RAI-182's authorized LIN-06 live-rerun wiring are integrated.
- **Statuses and dependency ripple:** RAI-164/165/166/167/168/169/170/174/179/180/181 are accepted; RAI-182 is accepted. LIN-06 is accepted on integrated evidence from LIN-15 scripted acceptance, RAI-182 live-wiring acceptance, and the protocol/regression suites. LIN-07 remains ready (LIN-05 accepted); LIN-08 remains ready (LIN-03 and LIN-05 accepted). These are readiness statuses, not acceptance claims.
- **LIN-06 acceptance:** `sbt --batch 'raiderCli/testOnly raider.cli.lineage.LiveRerunAcceptanceSpec'` exited 0 (3 passed); `sbt --batch 'raiderCli/testOnly raider.cli.lineage.LiveRerunScriptedAcceptanceSpec raider.cli.lineage.IsolatedRerunSpec raider.cli.lineage.ReplayHarnessSpec raider.cli.lineage.WorkspaceIsolationSpec'` exited 0 (16 passed). Live fixtures use a local scripted backend; no real-provider call or paid call is claimed.
- **Integrated gates:** `make quality-changed` exited 0 (clean-tree minimum `fast` classification); `make quality-changed QUALITY_EXECUTE=1` exited 0, all 10 fast gates passed, timeout false, manifest `artifacts/quality/20261020-063032-24528-0001/manifest.json`; `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` exited 0, all 8 listed gates passed, timeout false, manifest `artifacts/quality/20261020-063731-29297-0001/manifest.json`; `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0, 202 cards. After task-status synchronization, changed classification selected `docs`, and the execute rerun passed `plan-validate`, exit 0, timeout false; manifest `artifacts/quality/20261020-064718-41662-0001/manifest.json`. No retry was needed; no timeout or real red occurred. The `release` profile is not present in the registry and is not claimed passed.
- **Scope honesty:** verification used local stands/disposable repositories only; no hosted-forge claim, push/publish/deploy release action, or real provider execution is made. TokenRouter consumer recheck remains pending its packet, and the live-wire cap remains awaited from the owner.
- **Documented exception:** TAB-02 (RAI-172) remains conditional and distinct from the passing integrated gates; it was not observed red.

## 2026-10-20 — SDLC release chain complete before RAI-170 verification

- **Integrated merge:** RAI-181 from `lane/rai-181` merged as `84d895faca61ed5ed4994275e1728ff52a8d7832`.
- **Ruling and statuses:** Round-2 review approved. RAI-181 closes RAI-168's remaining LocalRelease stage/evidence work; RAI-168 is accepted per reviewer ruling for SDLC-REL-01..03, and RAI-181 is accepted. Chain status: 164/165/166/167/168/169/174/179/180/181 done — ONLY RAI-170 (independent release verification) remains to close ADR-030's implementation line. RAI-182 is registered (LIN-06.b).
- **Integrated gates:** `make quality-changed` exited 0 (clean-tree minimum `fast` classification). `make quality-changed QUALITY_EXECUTE=1` exited 0; all 10 fast gates passed, timeout false; manifest `artifacts/quality/20261020-033013-27442-0001/manifest.json`. `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` exited 0; all 8 listed gate entries passed, timeout false; manifest `artifacts/quality/20261020-033847-49382-0001/manifest.json`. After status/board edits, `make quality-changed` selected `docs`; the execute rerun exited 0 with `plan-validate` passed and no timeout, manifest `artifacts/quality/20261020-034755-54870-0001/manifest.json`. Plan validator exited 0 with 202 cards.
- **Documented exception:** TAB-02 (RAI-172) remains conditional and distinct from the passing gates; it was not observed as a red.

## 2026-10-19 — SDLC-01..04 verified (RAI-169); clock contract (RAI-180)

- **Integrated merges:** RAI-169 from `lane/rai-169` merged as `7bbb265ddb82c0abd14432c1013e951d91063626`; RAI-180 from `lane/rai-180` merged as `8265689c047359791c197f8559fa2e94c46b3a4e`.
- **Verifier finding and correction:** RAI-169's round-1 verifier caught `TimedOutUncertain` being collapsed into `Unknown`; lane/RAI-167 restored distinct typed evidence while keeping the refusal carrier stable, with RED→GREEN 5/5 ×3 confirmation and matrices 5/5, 9/9, 16/16, 15/15 ×3.
- **Clock contract:** RAI-180 makes EventExport, MultiJobExport, and ReplayHarness poll sleeps use `withClock(ClockLive)` so wall-clock deadlines remain reachable under a frozen ambient clock; §15.1 and EPOCH-set/no-virtual-time-advance fixtures cover the contract.
- **Statuses and chain:** RAI-169 and RAI-180 accepted; RAI-170 ready after verifying dependencies RAI-117/164/165/166/167/169 in the backlog. RAI-181 is registered; RAI-168 completes under RAI-181, then RAI-170 closes the SDLC chain.
- **Integrated gates:** `make quality-changed` exit 0 (clean tree, minimum `fast` classification); `make quality-changed QUALITY_EXECUTE=1` exit 0, all 10 fast gates passed, timeout false; manifest `artifacts/quality/20261019-235825-32795-0001/manifest.json`. `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` exit 0; all 8 listed gates passed, timeout false; manifest `artifacts/quality/20261020-000551-44853-0001/manifest.json`. No EXECUTE timeout occurred, so RAI-180's owed integrated EXECUTE rerun is repaid by the successful merged-candidate run.
- **Documented exception:** TAB-02 (RAI-172) remains conditional and is distinct from these passing gates; no recurrence/red was observed in these runs.

## 2026-10-19 — RAI-167 + RAI-175 accepted

- **Integrated merges:** RAI-167 from `lane/rai-167` merged as `4e917ad597bb81f4d5decd06c36c3140cf926dfe`; RAI-175 from `lane/rai-175` merged as `10c0ca452749761b124e3af5db9b07c2cef3f2d9`. RAI-167 supplies bounded commit/PR/status/merge stages with workflow-config preconditions, candidate-bound checks/review/capabilities, read-only reconcile before mutation, typed refusals and no retry for uncertain outcomes, per-instance serialization, and RAI-174 evidence records. Independent RAI-169 still owes LocalForgeStand integration verification.
- **PLANUI-02 disposition:** RAI-175 captured settlement, approval, `outcomeRef`, and `plan-exec` in structured fixture output. Final matrices were isolated 20/20 and loaded 20/20, with load >50 in 17/20. All captures were healthy and the integrated-only red was not reproduced; this is exoneration evidence for the observed pathway, not proof the rare race is impossible. Assertions are unchanged. PLANUI-02 remains a conditional watch; no fix without a reproducer.
- **Statuses and dependency ripple:** RAI-166's existing accepted task/board status was synchronized from stale `todo` in `backlog.json`. RAI-167 and RAI-175 are accepted. After verifying dependencies in the backlog, RAI-168 and RAI-169 are ready (all listed dependencies accepted). SDLC chain status: 164/165/166/167/174/179 done; next: 168 release and 169/170 verifiers. RAI-180 is registered. The live-wire request document awaits the owner's cap value.
- **Integrated gates:** `make quality-changed` exited 0 and selected minimum `fast` on the clean post-merge tree. `make quality-changed QUALITY_EXECUTE=1` exited 0; all 10 fast gates passed, timeout false; manifest `artifacts/quality/20261019-213449-13578-0001/manifest.json`. `make quality-profile PROFILES="static unit contracts runtime ci docs"` exited 0; all 7 gates passed, timeout false; manifest `artifacts/quality/20261019-214157-43406-0001/manifest.json`. TAB-02 (RAI-172) remains a conditional exception; PLANUI-02 (RAI-175) is exonerated in these probe matrices but remains a conditional watch. Neither is a red in these runs.

## 2026-10-19 — RAI-179 accepted; RAI-157 closed; MJE-03c known-red CLOSED

- **Integrated merges:** RAI-179 from `lane/rai-179` merged as `212db5b6b90e7befada9ff94d18a9afc2c215e76`; RAI-157 matrix record from `lane/rai-157-m` merged as `d9f4aad4a2fc09a181fe4a101cdb0621673d5bb2`. RAI-179's launcher-policy declaration (`--checkpoint`, `--approval-request`, `--decision-file`) writes the request artifact and exits 25 before dispatch; a fresh invocation rechecks expiry, workspace, and state before dispatch. Its real-launcher fixtures cover pause/request, valid fresh decision, workspace drift, and expiry. Round-1 implementation review approved and freshness manifest `20261019-193636` verified. RAI-165's dispatch-seam remainder is complete; independent RAI-169 verification remains separate.
- **RAI-157 / MJE-03c closure:** the adjudication condition is met: on the integrated candidate after the settle fix `03fba34` and suite healing `9763ad1`, `MultiJobExportSpec` passed ×10 with strict MJE-03c green in all runs, and `raiderCli/test` passed ×3 at 450/0 each. Historical reds remain in `docs/work/records/RAI-157.md`; they are not erased. See `docs/work/records/RAI-157-matrix.md`.
- **Statuses:** RAI-157, RAI-165 (subsystem slice plus RAI-179 seam), RAI-179, and RAI-178 are accepted. MJE-03c is CLOSED per the adjudication. Remaining conditional exceptions are TAB-02 (RAI-172, conditional) and PLANUI-02 (RAI-175, probe pending); neither is a current red in these runs.
- **SDLC chain:** RAI-165 + RAI-179 accepted means approval plus dispatch are complete. Next: RAI-167 (integration verbs), RAI-168 (release), and RAI-169/170 (independent verifiers).
- **Integrated gates on main after both merges:** `make quality-changed` exit 0 (clean-tree minimum `fast` classification); `make quality-changed QUALITY_EXECUTE=1` exit 0, all 10 fast gates passed, manifest `artifacts/quality/20261019-194500-28803-0001/manifest.json`. After status/task metadata updates, `make quality-changed` selected `docs`; the final `QUALITY_EXECUTE=1` run passed (`plan-validate`, manifest `artifacts/quality/20261019-200751-34525-0001/manifest.json`); the requested full profile was rerun on the updated task metadata and passed all 8 listed gates, manifest `artifacts/quality/20261019-200004-32790-0001/manifest.json`. `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` passed, 199 cards. No timeout or gate failure occurred. The only open conditional items are TAB-02 and PLANUI-02 as noted above.

## 2026-10-19 — RAI-178 merged: suite healed

- **RAI-178:** merged `lane/rai-178` as `9763ad18eadd63ce982583a03e2378d383627748`; root cause was export/readiness `ZIO.sleep` polling on frozen zio-test TestClock suspending forever; fixture-only remedy adds the established `withLiveClock` aspect to 21 specs, with no assertion, budget, threshold, skip, or gate-policy changes. Fiber-dump diagnosis and lane evidence are in `docs/work/records/RAI-178.md`.
- **Integrated gates:** `make quality-changed` exit 0 (clean tree, minimum `fast`); `make quality-changed QUALITY_EXECUTE=1` exit 0, including **`sbt-test` passed, exit 0, timeout false** (manifest `artifacts/quality/20261019-181232-76504-0001/manifest.json`); `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` exit 0, including `sbt-test` passed, exit 0, timeout false (manifest `artifacts/quality/20261019-181951-86045-0001/manifest.json`); `python3 scripts/quality/quality.py self-check` exit 0. Plan validation passed, 199 cards.
- **Exceptions remain distinct and uncleared:** TAB-02 (RAI-172), PLANUI-02 (RAI-175), and MJE-03c (RAI-157). MJE-03c's matrix is pending; the suite fix does not itself close that item.
- **Cascade unblocked:** RAI-179 integrated gates rerun and RAI-157 matrix (MJE-03c closure path). **Deferred:** register a separate clock-contract card for main-source TestClock-hostile polls; that source change is outside RAI-178 scope.

## 2026-10-18 — Owner decision: checkpoint declaration = launcher policy

Owner decision: declare SDLC checkpoints through launch-time policy, not workflows; workflows remain neutral because this keeps authority owner-granted and checkpoints can only narrow or pause it. This unblocks RAI-179 seam design → RAI-165 completion → RAI-167/168.

## 2026-10-18 — Merge-all before cleanup

- **RAI-157 slice:** merged `lane/rai-157` as `03fba346dab1e636541d1bb503f6834f59c25944`; round-2 review verdict: approve. MultiJobExport settle window and six-site `ZIO.sleep` poll sweep are integrated; strict `MissingTerminal` remains unchanged. Card stays OPEN: the full-suite matrix is with RAI-178; MJE-03c remains a tracked known-red, not cleared by this merge.
- **RAI-165 slice:** merged `lane/rai-165` as `a27d9eb1480a5ddf8464efd32596b62c16856153`; round-2 review verdict: approve. The v2 request carrier, decision validation, and fresh-invocation recheck/refusal fixtures are integrated. Card stays OPEN: CLI dispatch seam remains RAI-179 under the launcher-policy decision.
- **RAI-136 records:** merged `lane/rai-136` as `ad14743eeb552087c543340b21431b72ecd9d61c`; records only, no acceptance claim. Includes storm-soak evidence and a load-deferred remainder note.
- **LIN-04 records:** merged `lane/lin-04` as `965d4e49ac6466bd032ef673c9299cd8b362cd69`; records only, no new acceptance claim. Parent acceptance is recorded separately at `2379fc5` / `1f35fb8`.
- **Scratch cleanup:** deleted `scratch/rai157-sw01-red` (superseded RED-proof branch).
- **Integrated gates:** `make quality-changed` exited 0 and classified a clean tree for minimum `fast`. `make quality-changed QUALITY_EXECUTE=1` exited 2 (`sbt-test` timed out; manifest `artifacts/quality/20261018-160651-7375-0001/manifest.json`). One long-budget retry, `sbt --batch test`, reported timeouts in MultiJobExportSpec PHE-01/LIN3-01, CWF-E03 SIGTERM, WIR-01, and two CWF-E02 cases; the run was terminated by the 2,400,000 ms command limit and is not a pass. `make quality-profile PROFILES="static unit contracts runtime repl ci docs"` exited 2; `sbt-test` timed out, while `cli-smoke`, `core-contracts-spec`, `compile-fixtures`, `plan-validate`, `repl-smoke`, `sbt-compile`, and `scalafmt-check` passed; manifest `artifacts/quality/20261018-170404-81322-0001/manifest.json`. No gate suite is claimed green.
- **Exceptions remain distinct:** TAB-02 (RAI-172) and PLANUI-02 (RAI-175) remain tracked follow-ups; MJE-03c remains RAI-157/178's known-red. The current run additionally has test timeouts; these are not converted into passes or relabeled as those exceptions.
- **Post-record documentation gate:** after this board/record update, `make quality-changed` classified `docs`; `make quality-changed QUALITY_EXECUTE=1` passed, exit 0, manifest `artifacts/quality/20261018-172221-96190-0001/manifest.json` (`plan-validate` passed, timeout false).
- **Plan validation:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` passed, exit 0; 199 cards. Next available cards: RAI-178 (full-suite stability investigation) and RAI-179 (launcher-policy dispatch seam); RAI-157/165 remain open for their stated integration obligations.

## 2026-10-18 — RAI-177 merged; LIN-04 track closed

- **RAI-177:** merged `lane/rai-177` as `2379fc5` (lane source tip `a9064da`); round-2 review verdict: approve. Integrated behavior consumes only RAI-173 pointer-resolved snapshots, displays typed unavailable/invalid statuses, and passes real PTY live-render/switching/invalid-state acceptance (`LIN4B-01..03`; record `docs/work/records/RAI-177.md`). The LIN-04 parent is accepted: its LIN4-01/02/03 acceptance is covered by the accepted LIN-04A `:aux` + LIN-04B provider slices together with RAI-177's real product-PTY checks. Statuses are synchronized in `docs/tasks/lineage.md` and `backlog.json`.
- **Dependency ripple:** LIN-05 was already `accepted` in the backlog registry (task-card status synchronized here); with LIN-04 and RAI-177 accepted, LIN-04 is closed. LIN-06, LIN-07, and LIN-08 become `ready` because their dependencies are now all accepted (LIN-03/LIN-05 as applicable). LIN-10 was already ready. No acceptance is claimed for these follow-on cards; live rerun and phase replay remain unimplemented.
- **RAI-179:** registered and still `ready`/partial: dispatch core is done, but the launcher-seam design question remains OPEN for the owner—where checkpoints are DECLARED (workflow DSL versus launcher policy). Do not treat registration or the dispatch core as completion.
- **RAI-178:** registered `ready`; WIR-01 full-suite stability investigation remains the next relevant available card.
- **Integrated gates on this merge:** `make quality-changed` exit 0 (clean tree; minimum `fast`); `make quality-changed QUALITY_EXECUTE=1` exit 0, manifest `artifacts/quality/20261018-150215-55245-0001/manifest.json`; `make quality-profile PROFILES="static unit contracts runtime repl docs"` exit 0, manifest `artifacts/quality/20261018-151028-63570-0001/manifest.json`. Manifest verification via `python3 scripts/quality/quality.py verify ... --require <profile>` passed separately for static, unit, contracts, runtime, repl, and docs. All executed gates reported passed, exit 0, timeout false. The documented exceptions remain distinct and uncleared: TAB-02 (RAI-172), PLANUI-02 (RAI-175), and MJE-03c (RAI-157/178 investigation); none is a red result from these gate runs.
- **Post-status documentation gate:** after the status and board update, `make quality-changed` selected `docs`; `make quality-changed QUALITY_EXECUTE=1` passed, exit 0, timeout false, manifest `artifacts/quality/20261018-152410-90601-0001/manifest.json`.
- **Plan validation:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` passed, exit 0; 199 cards. Next: LIN-06/07/08 and RAI-178 are ready.

## 2026-10-18 — SDLC chain progress; RAI-166 accepted; RAI-157 suite investigation

- **RAI-166:** merged `lane/rai-166` (merge commit recorded below in Git); round-2 review approved. Delivered the full D3 fault-mode surface, including distinct `UnknownResult`, alongside typed commit/PR/merge verbs, stale-ref/conflict/duplicate/`TimedOutUncertain` behavior, blind-retry refusal, read-only reconcile, disposable local-Git exercise, virtual-clock async timeout window, and competing-owner coverage. SDLC-FORGE-01..03 are implemented. RAI-167 enforcement and RAI-169 independent verification remain separate.
- **RAI-165:** partial; approval artifacts and recheck complete, including the refusal matrix. The CLI dispatch seam remains owed and is sized with RAI-167.
- **RAI-177:** registered for the REPL live-lineage adapter and real PTY acceptance, the LIN-04 completion path. External RAI-176 is noted for streaming tool-request regression; owner-side registration exists and is untouched here.
- **RAI-157 investigation:** root cause class is blocking polls on ZIO workers (drain and readiness), replaced with `ZIO.sleep`; the focused spec passed ×10. A committed sweep converted six sites and documents the left-outs. The full `raiderCli/test` still times out (WIR-01 internal five-minute timeout; suite exceeds the 2700-second/45-minute budget). **RAI-178 is registered ready** to capture the WIR-01 trace/timeline and per-suite timings, adjudicate fixture/load/blocking-wait causes, and choose a minimal reviewed fixture correction and/or measured `sbt-test` budget calibration using `e846d84` as precedent without changing assertions or the acceptance bar. MJE-03c remains known-red until its matrix completes; the green integrated run below does not clear it.
- **Integrated gates on this merge:** `make quality-changed` exit 0 (clean tree; minimum `fast`); `make quality-changed QUALITY_EXECUTE=1` exit 0, manifest `artifacts/quality/20261018-113150-28302-0001/manifest.json`; `make quality-profile PROFILES="static unit contracts runtime ci docs"` exit 0, manifest `artifacts/quality/20261018-113855-32140-0001/manifest.json`. After the status/board edits, `make quality-changed` selected `docs`, its `QUALITY_EXECUTE=1` rerun passed, manifest `artifacts/quality/20261018-114751-39755-0001/manifest.json`; plan validation exited 0 with 197 cards. Executed manifests report all listed gates passed with no timeout. Tracked exceptions remain distinct: TAB-02 (RAI-172 conditional follow-up), PLANUI-02 (RAI-175 probe), and MJE-03c (RAI-157 matrix incomplete/known-red).

## 2026-10-18 — RAI-176 local interactive tool regression

RAI-176 is in progress after an owner-reported local Qwen chat returned XML as text. The streaming request encoder omits tool definitions; a bounded direct request to the same local llama-server returned structured tool calls. One visible isolated worker and independent reviewer use Codex agent tools in place of the OpenCode task tool; coordinator owns integration and metadata. No paid providers or neighboring repositories are involved.

## 2026-10-18 — Owner decisions: LIN-03 acceptance basis; RAI-175 registered

Owner decision: LIN-03 acceptance is based on live fold-error evidence (invalid projection reported, session exit unchanged, and prior committed snapshot readable through the pointer); live compile-failure validation transfers to the pinned-dotc validation mechanism in RAI-173 and is not a LIN-03 acceptance condition.

RAI-175 is registered as ready to instrument PLANUI-02 structured capture of plan-2 settlement and approval state, then run authorized isolated and loaded probes until recurrence or exoneration. The card makes no product fix authorized without a captured reproducer; RAI-123 remains conditional because its mechanism is unestablished.

## 2026-10-18 — RAI-164 + LIN-03 accepted

- **RAI-164:** merged `lane/rai-164` as `c041662` (source tip `b019883`); completion review approved. Stage runtime and the four typed evidence records persist through the RAI-174 carrier into `run.json`/`events.jsonl`, retaining v1 bytes when SDLC evidence is absent. Unblocks RAI-165/166/167.
- **LIN-03:** merged `lane/lin-03` as `37645f2` (source tip `f29c41f`); completion review approved under the 2026-10-18 owner scope basis. The 200-round production-path campaign, live fold-error reporting with unchanged session exit/prior pointer snapshot readable, and interrupted-publication checkpoint all passed. Live pinned-dotc compile-failure validation remains with RAI-173 and is not claimed here. LIN-04 is ready; its only declared dependency is LIN-03, now accepted.
- **Integrated gates:** `make quality-changed` exit 0 (clean tree, minimum `fast`); `make quality-changed QUALITY_EXECUTE=1` exit 0, manifest `artifacts/quality/20261018-055427-30454-0001/manifest.json`; `make quality-profile PROFILES="static unit contracts runtime ci docs"` exit 0, manifest `artifacts/quality/20261018-060138-46106-0001/manifest.json`; `make quality-profile PROFILES="repl"` exit 0, manifest `artifacts/quality/20261018-060851-57143-0001/manifest.json`. After status metadata updates, `make quality-changed` selected `docs`, and its `QUALITY_EXECUTE=1` rerun passed with manifest `artifacts/quality/20261018-061801-71830-0001/manifest.json`. No timeout or real red occurred in these requested runs.
- **Tracked exceptions, not cleared by these runs:** MJE-03c is RAI-157's known-red and its 10-run matrix remains load-held; TAB-02 is RAI-172's conditional determinism follow-up; PLANUI-02 still awaits the RAI-175 reproducer probe. No exception is represented as fixed or as a failed result in the requested gate runs.
- **Plan validation:** `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json` exited 0; 195 cards. **Queue:** RAI-165 lane next; LIN-04 lane next; 157×10/136 storm and RAI-175 probe remain load-held.

## 2026-10-18 — PLANUI-02 integrated red diagnostic

- Focused `PlanPanelSpec` suite filtered with `-t "PLANUI-02"` passed 10/10
  isolated and 10/10 under load; each run verified exactly five PLANUI-02 tests
  executed (not the other PlanPanelSpec suites). Loaded 18-CPU host reached
  sampled 1-minute load 48.8–114.9 with ambient build activity and 40 temporary
  CPU burners. Logs, matrices, analysis, and source hashes:
  `artifacts/planui02-diag/` (`REPORT.md`).
- Verdict: **ambiguous**, not cleared as a flake. The watcher outcomeRef race
  could explain stale `ready2Fresh`, but cannot by itself explain the missing
  `plan-exec`; the test did not capture plan-2 settlement/approval outcome.
  RAI-123's per-job outcomeRef trigger is not established; do not start that
  fix card from this evidence. No bisect was triggered because focused runs
  remained green; `wt/diag-pre` was not created. Recommended next step is an
  authorized reproducer capturing plan settlement and approval outcome.

## 2026-10-18 — RAI-173 merged; LIN-03 integration staged

- **RAI-173 merged** from `lane/rai-173` as
  `8aa1c11488fd55cf9f4b9f95512869559e9b96a0` (merge commit; source
  `cd32cff`), round-2 review approved. The lineage snapshot protocol pins the
  data/source digests behind `lineage-commit.json` and atomically replaces the
  pointer. The 8-reader × 120-update × 3 repetitions proof seeds pre-state and
  uses strict Missing discipline; the prior race-masking fixture is closed by
  reviewer-verified handling.
- **LIN-03 wiring merged** from `lane/lin-03` as
  `23c06924fe6e95981790dae6dad691b18e9c2a39` (merge commit; source
  `a25641f`). Ordered export batches feed `LiveLineageProjection` under the
  export lock; HeadlessRunner consumes the incremental projection after the
  drain barrier. LIN3-01 wiring and LIN3-02/03 protocol are integrated;
  **LIN-03 completion review is pending** and no LIN-03 acceptance is claimed.
- **Integrated verification on the merged candidate:** `make quality-changed`
  exited 0 (clean tree; minimum `fast` classification). `make quality-changed
  QUALITY_EXECUTE=1` exited 0; all fast gates passed, no timeout, manifest
  `artifacts/quality/20261018-022755-87361-0001/manifest.json`.
  `make quality-profile PROFILES="static unit contracts runtime"` exited 1
  (not a timeout): `sbt-test` failed with 183 passed / 1 failed in
  `PlanPanelSpec` (`PLANUI-02`, `modules/repl/.../PlanPanelSpec.scala:280–282`;
  `ready2Fresh` and `approved2` were false, and `plan-exec` was absent from
  `List("plan", "plan", "plan-exec")`). The exact failing run is
  `artifacts/quality/20261018-023805-12527-0001/sbt-test.log` (SHA-256
  `0ed83dd5fd0d324b9106464b75df3693a2ff72fc3d6704aacd0b360088590345`); this
  is a real observed red, not a timeout. In that run MJE-03c passed (its
  historical RAI-157 known-red remains documented, not cleared) and TAB-02
  passed (its RAI-172 load-race diagnosis remains documented, not fixed).
- **LIN-03 acceptance suites:** the requested `sbt --batch 'raiderCli/testOnly
  raider.cli.run.LiveLineageProjectionSpec
  raider.cli.lineage.LineageSnapshotProtocolSpec
  raider.cli.lineage.ReplayCliSpec raider.cli.lineage.ReplayProductCliSpec
  raider.cli.run.LineageArtifactsSpec'` exited 0: 30 passed, 0 failed.
  This component-suite result does not substitute for completion review or
  erase the integrated profile red.
- **Planning validation:** `python3 scripts/plan/validate.py --out
  artifacts/plan-review/report.json` exited 0; 194 cards.
- RAI-164 remains partial (stage runtime + digests; persistence waits for
  RAI-174). The RAI-174 fix round is in flight (payload-interior boundary +
  unknown-kind refusal).
- **Queue:** LIN-03 completion review → LIN-04; RAI-164 completion after
  RAI-174; RAI-157 ×10 / RAI-136 storm remain load-held.

## 2026-10-16 — Capacity revision: 20 spawned Luna agents

Owner decision (interactive session, 2026-10-16, refined the same day):
execution capacity is the **primary coordinator session plus up to 20
spawned Luna agents** (the primary session is the coordinator/integrator and
sole owner of the root checkout). Reason: remove the provider rate-limit
bottleneck that killed
parallel spawns twice after work had started (wave 3 incident; RAI-121) and
pipeline independent review instead of queueing it. The build ceiling is
unchanged: at most 8–10 concurrent heavy (sbt-running) lanes on 18 cores /
128 GB with load-calibrated gate timeouts; additional capacity goes to light
lanes (docs, contracts, research, fixture preparation, board/backlog/worktree
hygiene) and immediate per-lane reviewers. Mechanics updated this date in
parallel-development.md §1/§1a/§1b, roadmap §5, and the raider-wave command.
No gate thresholds, protected verifiers, or acceptance rules changed.

First wave under the new capacity (composition corrected 2026-10-16 after the
read-only research lanes L1–L4; each lane opens only after its dependencies
are verified against accepted main and a clean main commit): heavy —
RAI-155.e (critical path: .a/.b/.c/.d and RAI-158/159/160 are merged with
approved reviews on main; RESUME the existing dirty worktree wt/rai-155e —
uncommitted verifier files E01–E03/Harness/CorpusSpec — per
parallel-development §8, do not duplicate it), RAI-156, RAI-157, RAI-137,
RAI-136, LIN-03 (dependencies LIN-01/LIN-02 close via the reconciliation
below); light — RAI-134 (guide refresh), RAI-117 SDLC ADR draft (RAI-116
accepted; outline prepared by research lane L4). Coordinator-executed
hygiene: backlog reconciliation with per-card merge-commit evidence — the
drift is larger than the L1 first pass reported (verified examples merged
with approved reviews but still ready/todo in backlog.json: RAI-112,
RAI-119–122, RAI-124, RAI-126–130, RAI-131–133, RAI-135, RAI-140–154,
LIN-01/02, RAI-155.a–d, RAI-158–160); merged-worktree cleanup under wt/
(112 clean removable worktrees inventoried; keep 55 unmerged branches and 12
dirty worktrees); RAI-155.f walkthrough preparation is parked read-only
until .e is accepted. RAI-155.f, RAI-123 (conditional), LIN-04, and 129.b/c
registration queue behind their dependencies. Merges land in batches of 3–5
by dependency cluster; full main gates run once per batch.

## 2026-10-16 — Wave 1 under the new capacity (8 Luna lanes)

Spawned per the capacity revision above; all lanes are Luna task-tool workers
with §3 assignment packets; baseline cd7032f (post-reconciliation). Status
after the first integration batch (main 0870045):

- Merged with review approve: RAI-117 (ADR-030 SDLC draft, Proposed), RAI-134
  (guide refresh; round-2 approve after scope reverts), RAI-156
  (eventuallyTerminal hub-watermark drain; EXECUTE 0, replay ×3, full sbt
  427+9; lane includes merge 5527a70 of integration fix c04aae9).
- Integration fix on main: c04aae9 — synthetic EW token literal split in
  ChatExportChainGateSpec (pattern of 54b54f2); the forbidden-apis clean-tree
  full scan had been failing ON MAIN since RAI-160 and blocked lane EXECUTE
  gates (RAI-156/136).
- Pending lanes: RAI-137 (round-2 finding: transcripts/classpath mkdir sites
  still outside the exit-2 path; final fix round in flight); RAI-157 (phantom
  ToolCall.id compile error reproduces after clean ONLY in its worktree —
  sources identical to compiling main; dedicated investigation queued);
  RAI-155.e (partial WIP 4704dea: E01/E02/corpus green in targeted runs;
  real-stand capture, E03 SIGTERM case, legacy-spec mismatch remain);
  RAI-136 (storm soak green 184/0 + 18× PlanPanel clean, RAI-123 NOT
  triggered — stays conditional; sustained soak + 2 full sbt passes owed);
  LIN-03 (incremental projection fold + tests green; live-stream wiring owed).
- Reviews: 117 approve; 134 approve (round 2); 156 approve + gates-complete;
  137 changes-required (round 2, one finding); 136/155.e/LIN-03/157 not yet
  reviewed (lanes incomplete).
- Integrated gates on main 0870045: `make quality-changed` exit 0 (classification
  only; no manifest); first `make quality-changed QUALITY_EXECUTE=1` attempt
  timed out at 120s (terminated, not a pass), retry exit 0, passed; manifest
  `artifacts/quality/20261016-072042-40503-0001/manifest.json` SHA-256
  `b6eaeec13be587d327030605bf188427ef63e8865f215913ee1432ce7795a3d9`.
  `make quality-profile PROFILES="static unit contracts docs"` exit 1,
  manifest `artifacts/quality/20261016-072639-53185-0001/manifest.json` SHA-256
  `b4dd36d0e45172057e54ae79f35b38ec38b2a67bdfe6bc283b040cc2f72a6b3e`; sbt-test
  failed in `raider.cli.run.MultiJobExportSpec` (MJE-03c, detached child with no
  Terminal was reduced successfully by the strict entry instead of the expected
  typed refusal). `make quality-profile PROFILES="repl runtime"` exit 0,
  manifest `artifacts/quality/20261016-073347-65305-0001/manifest.json` SHA-256
  `fb2078f1c6e077f5f595b565b63a4ecaff8aa0d2460cd71bc7aa852b3936557c`.

## 2026-10-16 — ADR-030 owner decisions; MJE-03c known-red

- **ADR-030 accepted (owner mandate, interactive session 2026-10-16; contract
  only):**
  - D1: `NeedsApproval` writes an approval request (checkpoint ID, candidate
    digest, expiry); human decision is separate; a fresh invocation rereads
    policy/state/digest, with no resumed fiber.
  - D2: Scala workflow config is authoritative for required checks/review and
    target branch; read-only forge reconcile precedes each mutation, with typed
    refusal on divergence.
  - D3: Full generic forge stand protocol includes commit, PR status, merge,
    stale-ref, conflict, duplicate, and unknown-outcome; real forges are
    separately accepted adapters.
  - D4: Typed Scala schemas in `contracts/` for check-result, review-verdict,
    candidate-identity, integration-result, represented as JSON in run artifacts.
  - D5: SemVer + Keep a Changelog, local `v<semver>` tag, checksummed
    `artifacts/release/<version>/`; strictly local, with no push/publish/deploy.
  - D6 (**explicit non-default owner budget decision**): SDLC hours expand
    beyond the 13,100h envelope; refine the 128–200h estimate per implementation
    packet before spend, and record the budget decision on the board.
  - D7 (**explicit non-default owner choice**): public effects-API, with ADR-029
    H8 design and consumer migration discipline in the first implementation
    card; no signature freeze before review.
  - Coordinator registers implementation cards after the .e/.f critical path.
- **MJE-03c adjudication (fresh independent reviewer, read-only):** scope is
  RAI-157 — the captured-but-unconnected introduction is silently lost at
  `MultiJobExport.scala:63–65/435–448`; this is **not** an RAI-156 regression
  (`0870045` changes ReplayHarness replay drain only). MJE-03c expectation stays
  unchanged; RAI-156 stays. Main carries a recorded **INTERMITTENT KNOWN-RED**
  on the static/unit/contracts/docs profile until the RAI-157 fix merges and
  passes integrated verification. Failed manifest
  `20261016-072639-53185-0001` is retained; the successful targeted rerun is
  noted separately and does not clear the integrated known-red.
- **RAI-157 lane note:** original `wt/rai-157` had corrupt build state (phantom
  `ToolCall.id` compile error; a fresh worktree of the same branch compiles).
  The lane continues in `wt/rai-157-fresh`; the original is detached and
  retained for forensics.

## 2026-10-17 — RAI-161 merged; SDLC cards registered; phantom-compile root cause; cleanup

- **RAI-161 merged** on main as `ee2186f` (round-2 gate-policy review:
  approve). Schema-v2 manifests retain gate-time audit metadata for
  `docs/work/records/**`; tampering fails verification, post-gate additions are
  listed without changing the fingerprint, and v1 legacy manifests remain
  supported.
- **Integrated gates on main:** `make quality-changed` exit 0 (classification
  only; the pre-existing untracked `contracts/chat-workflow/` files classify as
  `contracts`, `static`, `unit`; no manifest). First
  `make quality-changed QUALITY_EXECUTE=1` attempt timed out at 120s (terminated,
  not a pass); retry exit 0, passed. Manifest
  `artifacts/quality/20261017-161805-70800-0001/manifest.json`, SHA-256
  `aa1ecbca498f129831623b240b5c0794d0cbfdde0cf23e5bdeffd4ba4b2fd37b`.
  `make quality-profile PROFILES="static unit contracts docs"` exit 1,
  manifest `artifacts/quality/20261017-162616-83255-0001/manifest.json`,
  SHA-256 `d06451bd393482e12aff1993f8c835ad492f718f827a760987234948696277a2`;
  its `sbt-test.log` SHA-256 is
  `8cd808c9c5c2850d1a6c06185855ecc1226b7911fb31b6e910cf5f2dd00bbe36`.
  The failure is a **real red**, not the documented MJE-03c known-red:
  `SessionRegistry ... TAB-02` reported `hist.evicted == 252L`, actual
  `.evicted = 251` (38 passed, 1 failed). MJE-03c itself passed in this run.
  `python3 scripts/quality/quality.py self-check` exit 0; it verified the
  tampered-record failure, post-gate record addition with unchanged fingerprint,
  registry/classification/manifest checks. Self-check does not emit a manifest.
- **SDLC implementation package registered** at baseline `ff29b01` (plan
  validator: 191 cards): RAI-163..170 implement ADR-030 D1–D7, with RAI-163
  effects-API design first and H8 discipline; RAI-171 prepares live-wire work.
  No paid execution without a separate owner budget.
- **Phantom `ToolCall.id` compile error — root cause:** corrupt zinc/build state
  is created when a worktree's first compile runs under heavy parallel load;
  zinc then reuses stale partial core analysis and reports `value id is not a
  member` against a class missing the `def`. Remedy: purge affected modules'
  `target/` (and `.bsp`) and recompile; verified green in `wt/rai-162` with a
  26s clean compile. Cases: `wt/rai-157` was superseded by
  `wt/rai-157-fresh`, with the original retained detached for forensics;
  `wt/rai-162` was purged and compiled green. Runbook implication: on phantom
  member errors under load, purge targets before diagnosing sources.
- **External worktree cleanup executed** per owner decision: 91 → 79 registered
  worktrees. Merged/clean external worktrees under `../raider-english` and
  `../raider-wt` were removed with per-entry re-verification; unmerged English,
  handbook, and docs branches were preserved.
- **Queue:** RAI-162 lane verification (environment fixed); RAI-137 round-3 after
  this merge (freshness gap closed by RAI-161); RAI-157 full-spec ×10 matrix and
  RAI-136 final storm deferred while machine load is high; LIN-03 LIN3-02/03
  pending.

## 2026-10-17 — TAB-02 eviction-count race diagnosis (integrated-gate red explained)

- The `20261017-162616` profile red (`SessionRegistrySpec` TAB-02: 252 evictions
  expected, 251 actual) is a **load-sensitive test race**, not a product
  regression. `SessionRegistry.scala:453–466` publishes the folded snapshot
  before `appendHistory` updates the history vector/eviction counter
  (`:470–483`). TAB-02 waits for `lastSeq == 451`
  (`SessionRegistrySpec.scala:205–210`), which does not imply history-accounting
  quiescence; history can be one event behind.
- Reproduction attempts: 10/10 isolated and 10/10 under system load 47–76 were
  green, with the correctly filtered `-t TAB-02`; logs are in
  `artifacts/tab02-diag/`. The failure was not reproduced; bisect is not
  indicated.
- Disposition: conditional determinism follow-up (RAI-123 pattern)—make the test
  wait for the history/accounting condition; do **not** weaken the exact eviction
  assertion. Register the conditional card when convenient; no product change
  now.
- Concurrent context: the same integrated run passed MJE-03c; its known-red is
  intermittent and remains RAI-157-owned.

## 2026-10-17 — Batch merge: RAI-137 + RAI-162; E03 unblocked on main

- **RAI-137 merged** as `d7318f9` (round-3 approve): honest `ux-baseline` exit
  semantics; additive `perf`/`ux-baseline`; freshness through RAI-161 schema-v2.
- **RAI-162 merged** as `b3dda3f`: marker bound to invocation `workDir`; E03
  RED-first, 3/3, with negative sibling coverage; review-fix round closed. The
  RAI-155.e verifier corpus (E01/E02/E03, Harness, corpus spec, and records) is
  now on main. E03 green there makes the .e lane re-gate a formality away:
  merge main into `lane/rai-155e`, rerun EXECUTE, and review.
- **Untracked-collision resolution:** the stale root copy of
  `contracts/chat-workflow/` (old README and superseded
  `verifier-expected.json`) was backed up to
  `/var/folders/2q/nmsh46097bv_82prfpbwh22c0000gn/T/opencode/chat-workflow-untracked-backup-20261017`
  and superseded by the tracked merge; nothing was lost.
- **Integrated gates on main `b3dda3f`:** `make quality-changed` exit 0
  (clean-tree classification: minimum `fast`); `make quality-changed
  QUALITY_EXECUTE=1` exit 0, passed on first attempt (no timeout);
  `make quality-profile PROFILES="static unit contracts ci"` exit 0,
  passed; `make quality-profile PROFILES="repl runtime"` exit 0, passed;
  `python3 scripts/quality/quality.py self-check` exit 0; plan validator exit 0
  (191 cards). Executed-run manifests share input fingerprint
  `87aa17ee285bc555af8e6a98124046ab0c5bce5db6b410766ba0b73b67f09546` and
  policy hash `9122e5ad9c8abcc021c49df1905c34f9c7508c66cad93fc41f6d72dd7e20c775`:
  - EXECUTE fast: `artifacts/quality/20261017-181902-39730-0001/manifest.json`,
    SHA-256 `5f08191bfb922f014aaba42703f074274863f7e21c053df809b29fb4dc56c5ab`.
  - static/unit/contracts/ci: `artifacts/quality/20261017-183239-46718-0001/manifest.json`,
    SHA-256 `2daeacc7318064ea290d8c9b5edda45ba26ed43ceb04fbac5627b706ba301697`.
  - repl/runtime: `artifacts/quality/20261017-184250-52167-0001/manifest.json`,
    SHA-256 `f6b5afbc7c206d7e30ab644c9516217be1fbc2c7d7ca25bdab5cc6bf0d353be7`.
  MJE-03c (intermittent KNOWN-RED, RAI-157-owned) and TAB-02 (diagnosed
  load-race; conditional determinism card) were **not observed** in these runs;
  both requested profiles passed without failures. This is not a claim that
  either historical issue is fixed.
- **Queue:** RAI-155.e re-gate + review (critical path to .f); RAI-157
  full-spec ×10 matrix (wait for lower load; load 76 today); RAI-136 final storm
  (same); LIN-03 LIN3-02/03; TAB-02 conditional determinism card; SDLC
  RAI-163..171; live-wire RAI-171 (owner budget pending).

## 2026-10-17 — ADR-034 merged (Proposed); RAI-155.e round-2; LIN-03 blocker

- **RAI-163 merged** as `d7dbd4d` (round-2 review approve). ADR-034 is a
  **proposed** effects-API design under ADR-030 D7: typed effect descriptions
  are separate from executors; capability and reconcile boundaries, D1 approval
  artifacts, typed refusals, D4 evidence schemas, H8 consumer migration, and
  the §3 environment/helper-only credential boundary are documented. No public
  signature freeze or API acceptance is claimed; H8 consumer migration is
  required before any future freeze.
- **Integrated RAI-163 gates on main:** `make quality-changed` exit 0
  (clean-tree classification; minimum `fast`); `make quality-changed
  QUALITY_EXECUTE=1` timed out and was terminated on both attempts (first at
  120s, retry at 600s; neither is a pass); `make quality-profile
  PROFILES="docs"` exit 0 (docs profile manifest passed). `python3
  scripts/plan/validate.py --out
  artifacts/plan-review/report.json` exit 0 (192 cards). The execute timeout
  did not yield an adjudicable test result: neither MJE-03c nor TAB-02 can be
  reported as observed or cleared by this integration run. The previous
  recorded states remain: MJE-03c known-red (RAI-157-owned); TAB-02 diagnosed
  load-race with conditional RAI-172 follow-up.
- **RAI-155.e re-gate:** verifiers E01–E03 and corpus are green; EXECUTE and
  required profiles are green (post-gate verification passes are recorded in
  the .e work record). Review round 1 is closed after the changed-verifier-input
  case and digest anchor. Round 2 found that the digest check is not wired into
  `ChatWorkflowVerifierHarness.loadE02` (standalone proof only), plus stale
  record lines. Final fix round is in flight; .f remains blocked until round-3
  approval.
- **LIN-03 blocker:** pair-snapshot consistency requires readers to honor a
  commit pointer, but `ReplayCli` resolves `lineage-data.json` directly, outside
  LIN-03's allowed paths. A follow-up must authorize the reader protocol across
  `ReplayCli`, `HeadlessRunner`, and export. Live pinned-compiler validation
  also needs an authorized mechanism. Coordinator registers LIN-03.b next cycle.
- **RAI-172** (conditional TAB-02 determinism follow-up) is registered at
  baseline `eb4d3a4`.

## 2026-10-17 — RAI-155.e ACCEPTED; critical path open to .f

- **RAI-155.e accepted and merged** as `37911dc` (merge commit). Three
  independent review rounds closed: round 3 confirmed code and coverage closed;
  the only remaining freshness blocker was resolved by fresh verified manifest
  `20261017-214247-62757` (record remained untouched after its gate). Lesson:
  verifier output must not be written into the record after verification; keep
  evidence records frozen and run gates LAST to avoid verify-output-in-record
  self-reference.
- **RAI-155.f is UNBLOCKED** and is next on the owner's critical path: user
  walkthrough from chat through export/edit to local execution and generic CI.
- **Integrated gates on `37911dc`:** `make quality-changed` exit 0 (clean-tree
  classification, minimum fast); `make quality-changed QUALITY_EXECUTE=1`
  first attempt was terminated by the 1200-second outer runner timeout (not a
  gate result), then the single longer-budget retry passed exit 0 with all 10
  checks green and no gate timeout. Manifest
  `artifacts/quality/20261017-230452-73967-0001/manifest.json`, SHA-256
  `b5467cc9282cf9c4b4a5399f8cc2a57044018d5e999be8e5e942defc29096be2`,
  fingerprint `9101b38dfde32d119097d5de68684befa386341133078fbaf7b9d0d3beac42c1`,
  policy `9122e5ad9c8abcc021c49df1905c34f9c7508c66cad93fc41f6d72dd7e20c775`.
  `make quality-profile PROFILES="static unit contracts ci"` passed (manifest
  `20261017-231849-25048-0001`, SHA-256
  `a428050a99e334426d4224b73a4df3ade7bfeb0f8bb296ce637b4a63cf64a788`);
  `make quality-profile PROFILES="docs repl runtime"` passed (manifest
  `20261017-233235-34465-0001`, SHA-256
  `39d6d082a904bbfed4d3289a7a6db588ef1062f28afa5aae358ec426e26ec8c0`).
  Both profile manifests use the same product fingerprint and policy above.
  MJE-03c passed in the successful runs and was not observed red; its historical
  RAI-157-owned known-red status is not claimed fixed by this. TAB-02 passed and
  was not observed red; its diagnosed load race and conditional RAI-172 follow-up
  remain, not reclassified as fixed. The ADR-034 EXECUTE timeout debt is repaid
  by the successful retry; the first timeout remains recorded as a non-pass.

## 2026-10-18 — RAI-155.f accepted; RAI-155 parent accepted

- **RAI-155.f merged and accepted** from `lane/rai-155f` after approved review.
  The English walkthrough is at `docs/examples/chat-workflow/README.md`; lane
  evidence reports the §6d five-run fresh-copy matrix (two inputs × two
  launchers plus the unedited control), with checks failing before repair and
  passing after. Its recorded real run IDs are `wf_955e397c`, `wf_4236a29f`,
  and `wf_9ac3e838`.
- **Artifact transfer fix:** copied and force-staged the complete lane evidence
  tree `wt/rai-155f/artifacts/rai-155f/` into `artifacts/rai-155f/` after
  scanning content for credentials. The walkthrough's five previously missing
  references now resolve: `e01.log`, `e02.log`, `e03.log`,
  `e01/cwf-e01-a/cwf_e01-manifest.json`, and
  `e01/cwf-e01-a/cwf_e01.input.json`.
- **Integrated gates on root:** `python3 scripts/plan/validate.py --out
  artifacts/plan-review/report.json` exited 0 twice (193 cards, zero missing
  links; first before gates, second after the parent status flip).
  `make quality-changed` exited 0 (clean-tree minimum-fast classification);
  `make quality-changed QUALITY_EXECUTE=1` exited 0 with manifest
  `artifacts/quality/20261018-005950-74492-0001/manifest.json` (all fast gates
  passed); `make quality-profile PROFILES="static unit contracts ci docs"`
  exited 0 with manifest
  `artifacts/quality/20261018-010710-83008-0001/manifest.json` (all listed
  profiles passed). No timeout or real red remained. MJE-03c (RAI-157 known-red)
  and TAB-02 (diagnosed load-race) remain documented exceptions, not failures
  observed in this gate run and not claimed fixed.
- **Integrated parent verifier:** the exact requested `sbt --batch
  'raiderCli/testOnly raider.cli.chat.ChatWorkflowVerifierE01Spec
  raider.cli.chat.ChatWorkflowVerifierE02Spec
  raider.cli.chat.ChatWorkflowVerifierE03Spec'
  'raiderDsl/testOnly raider.dsl.workflow.ChatWorkflowVerifierCorpusSpec'`
  exited 0. CLI verifiers: 7 tests passed, 0 failed; DSL corpus: 5 passed,
  0 failed.
- **RAI-155 parent accepted** with basis: accepted children a `23bbc36`, b
  `fee77dd`, c `29ad1b4`, d `3a4e91d`, e `37911dc`, and f `088ee3d`; integrated
  verifier green; integrated gates green. Scope remains the first-slice path
  with explicit sequential task selection; SDLC/PR/merge/release remain in the
  RAI-163..170/173 chain. No live-model quality claim is made.
- **Parallel lanes / next work:** RAI-164 is partial (stages, typed evidence,
  SDLC-STG-01..04 green; artifact-writer wiring owed). RAI-173 is partial
  (reader protocol and 0-torn-reads ×3; `sbt-test` gate red with truncated
  diagnostic, which is owed). RAI-157 matrix and RAI-136 storm remain held
  until load is below 10 (today: 16–37). Next: complete LIN-03 through the
  RAI-173 merge after its gate diagnostic; continue RAI-165+ chain; resume
  RAI-157/136 under the load threshold.

## 2026-10-14 — Chat to editable Scala workflow priority

Registered **RAI-155**, with delivery cards **RAI-155.a–f**, all `todo`.
The requested chat → export Scala → edit → local/generic CI path is not yet
implemented end to end. Current lineage projections remain historical dry runs.
The canonical backlog now contains 175 entries (166 Raider root cards, three
companion root cards, six RAI-155 child cards).

Order: accepted RAI-116 and RAI-140 → RAI-155.a → b → c → d → e → f,
then parent integration. Check actual acceptance before opening a dependent lane.
RAI-116 remains the next prerequisite if still unaccepted. Existing lineage/TUI
lanes continue; RAI-153/154 only gate unified phase/delegation claims.
The coordinator owns registry and hour allocation; verifier ownership is separate.

Scope, acceptance and phase allocation: [roadmap §6d](../roadmap-12k.md#6d-chat-to-editable-scala-workflow-rai-155).
Execution: [OpenCode prompt](../prompts/opencode-chat-to-workflow.txt).
This registration is planning only; it does not claim runtime checks or release readiness.

## 2026-10-13 — English documentation and verified current examples

DOCS-EN translation and gallery are integrated through visible Luna lanes; all task metadata and existing RAI-127/128/129 entries are preserved. The permanent documentation language is English. Current examples cover real Scala regression repair, evidence-based payment configuration audit, parallel REPL review with a checked test plan, and deterministic typed release readiness.

Candidate checks: clean compile; ten offline cases; live billing/audit/REPL scenarios through requested `glm-5.3-flash` in the Yabanin coding-plan registry. Initial model/verifier failures are retained and described in [DOCS-EN record](records/DOCS-EN.md). No runtime API, gate registry, dependency, neighboring repository, push, or deploy changes.

Final acceptance requires a passing, fresh `artifacts/docs-english-final/receipt.json` on the integrated `main` tree. Candidate evidence does not substitute for that receipt. Next available implementation card: RAI-127; existing RAI-128/129 remain separate.


## Current user documentation / examples 2026-10-13

Owner requested actual-main user docs, verified useful examples and merge to main.
Visible Luna writer + independent Luna reviewer; coordinator owns integration.
[User guide](../user-guide.md) and [REPL/bundle gallery](../user-guide-repl.md)
cover eight runnable examples. Single-command runner:
`python3 scripts/quality/user_examples.py`.
Pre-record integrated gallery: eight cases passed, real coding check 1→edit→0,
lineage Scala compiled; current limitations and scripted markers are explicit.
[Documentation record](records/USER-DOCS-CURRENT.md).

Separate reviewed corrections discovered by checks:
[README classifier](records/VERIFIER-README-CLASSIFICATION.md) adds only exact
README.md docs mapping; [SSE idle read](records/TRANSPORT-SSE-IDLE.md), worker
`96f02ed`, fixes cancelable owned body read. Existing deadline test reproduced
twice before fix; final targeted 12/12 and full lane sbt-test passed without
changed tests/bounds. Earlier failures retained in records.

Final main acceptance after merges/records is the external receipt
`artifacts/user-docs-final/receipt.json`; no lane-only freshness claim.
These component results do not announce M3/M4, live model quality, public SDK
or completed forge SDLC. Next SDK contract remains RAI-116.

## SDK research / owner decisions 2026-10-12

Priority clarified: **convenient Scala DSL for AI-native SDLC**, one workflow
locally and in a generic **local CI runner**. The first consumer is code
development `../tokenrouter` (Yabanin); gateway integration remains P11. Target
discovery was read-only; the originally dirty tree was not changed. Sources,
baseline, and hashes:
[research](../research/sdk-roadmap-research-2026-10-12.md).

Confirmed: full SDLC, automatic commit/PR/merge, Scala-configured human
checkpoints; release = version/changelog/**local tag**/local artifacts, without
pushing the tag, publishing a package/image, or deploying. CI engine/forge not
selected; first, a generic contract and local Scala stand; GitHub Actions is not
the first adapter. Preview is allowed before M4: current pinned Scala/ZIO,
experimental API with migration, no durable resume; trusted local data. Full
milestone acceptance remains in force.

**RAI-115 accepted (source audit)**: lane `lane/rai-115-sdk-audit`, final `c156f40`,
independent reviewer approved; [audit](../research/sdk-embedding-audit.md).
Lane docs validator / changed / execute / docs profile — exit 0;
manifest `wt/rai-115-sdk-audit/artifacts/quality/20261012-193634-72162-0001/manifest.json`.
This is not acceptance of the SDK implementation. Findings: String bundle
boundary, launch/lifecycle/admission parity, ignored UsageObserved/zero
settlement on the current AgentLoop success path. The accounting claim requires
a separate reproducer/fix gate.

**RAI-116 ready** for a separate workflow/launch ADR; **RAI-117 todo**, depends
on it, describes SDLC effects/checkpoints/forge/release and revisits prior
non-goals. Implementation cards will be assigned after the contracts and
remaining-work estimate; the current LIN-13/14/15 + RAI-113/114 wave continues.
RAI-115 budget, 120–240 h, is a gross estimate before deduplication; SDLC kit,
remote adapter, and remaining work are unknown. It is not a newly approved
budget or hours actually spent.

Planning scope: [ROADMAP-SDK-RESEARCH](../tasks/roadmap-sdk-research.md).
Result/review/evidence: [research record](records/ROADMAP-SDK-RESEARCH.md),
[RAI-115 record](records/RAI-115.md).
Workflow preview WF-01/02 is independent of host embedding/plugin UI/M2/M4;
SDLC-01–05 and SDK-01–05 remain explicit milestone obligations.

## Previous planning revision 2026-10-12: embedding SDK

Historical snapshot before the additional research above; current status:
RAI-115 is accepted, and the next ready SDK card is RAI-116.

At the owner's request, the SDK is embedded in existing P0/P4.C/P7/P10;
SDK-01–05 criteria and P0/P7/P9 pilots are described in the
[roadmap](../roadmap-12k.md). Scope of current planning work:
[ROADMAP-SDK](../tasks/roadmap-sdk-integration.md). Result/review/evidence:
[work record](records/ROADMAP-SDK.md). Raider remains a standalone coding
product; Scala/ZIO embedding is an additional user path, with no new top-level
phase. Detailed user documentation is deferred. The 13,100 h budget is the
current envelope; estimate the remaining work and record the SDK reallocation
before implementation. No new features have been accepted.

**Ready at this revision: RAI-115** — P0.A audit of the public SDK boundary
and packet for a separate ADR. Allowed output:
`docs/research/sdk-embedding-audit.md`; gates: docs. It can run in parallel with
LIN-13/14/15 and RAI-113/114 without shared code/build edits. New implementation
cards are registered after the audit/ADR; existing cards are not reopened or
given someone else's scope. The coordinator owns planning files; a visible,
read-only spawned reviewer independently checks this revision.

Owner confirmed: Raider + SDK, first scenario is CI, without stopping the
current wave; the project is fully open source (existing MIT/license policy).
Pilot users, timelines, and demand have not yet been specified; clarifying them
does not block the independent audit. A paid edition/closed SDK features are
out of scope.

## Previous execution status

Current improvement program (2026-10-05): [12,000-hour roadmap](../roadmap-12k.md)
and [parallel OpenCode prompt](../prompts/opencode-12k-parallel.txt).
Tabs/statuses/plugins first; all Yabanin work is P11 after accepted standalone M4.
P0 wave executed (gap matrix, ADR-024 frozen, runner fix, event seam, renderer
spike): [P0-WAVE0 record](records/P0-WAVE0.md). This is not runtime acceptance.

Last passing run (2026-10-09, W6×10): full sbt 569/0; fast PASSED; 9 profiles passed; ADR-025 FROZEN (R1-R8); M1-ACCEPTANCE compiled; delegation flakes 0 (×10); plugin update/rollback; JobJournal; state tools; CI wrappers (artifacts/quality/20261009-223155|223336|223502). Previous: W5×10: full sbt 545/0; fast PASSED; 9 profiles passed; UX03/04 MEASURED (p50 53.5/p95 55.5ms); delegation flakes stabilized (×10 + negative control); plugin hooks + state tools + journal + TabShell :plugins/:state + plugin wiring. (artifacts/quality/20261009-203453|203554, artifacts/ux-baseline-root.json). Previous: W4×10: full sbt 476/0; EXECUTE + 9 profiles (including security/transport/tools) passed; fast (10 gates) PASSED for the first time on a tree with changes (artifacts/quality/20261009-172910|185843|185942|191006). M1-DEMO PTY all_pass. Previous: W3×10: full sbt 406/0; EXECUTE + 8 profiles (including new security) passed; M1-DEMO PTY all_pass, 5 tabs/1105 events/0 lost (artifacts/quality/20261009-172428|172520, artifacts/tabs-pty). Previous: 2026-10-09 W2: EXECUTE 7/7 + profiles static/unit/contracts/runtime/ci on merged W2 tree; runtime 137/137, ui 16/16 (artifacts/quality/20261009-100041-*). Previous: 2026-10-08 W1: quality-changed EXECUTE 7/7 + profiles static/unit/contracts/runtime/ci (cli-smoke) on integrated W1 tree (artifacts/quality/20261008-001211-*, 20261008-001358-*); mock run + RA-CFG preflight demo. Previous: 2026-10-06 P0-WAVE0: quality-changed EXECUTE 7/7 on changed
tree (first honest non-clean-tree green after RAI-054), profiles
static/unit/contracts/runtime 5/5, module runtime 99/99 tests, PTY probe 4/4
(artifacts/quality/20261006-120717-*, 20261007-005445-*, artifacts/renderer-spike).
Earlier: quality fast 6/6 full-tree (artifacts/quality/20261004-181210), full sbt
test 188 green, coder agent with REAL fs_read/fs_search/proc_run + live openai
provider — record: [SELFDEV](records/SELFDEV.md).

## Night campaign 2026-10-10 (P-LIN waves W7–W13, parallel lanes)

**Owner decision (2026-10-12):** further work will use only **visible spawned
subagents** in the owner's interactive session (`/raider-wave`; agents
`raider-worker`/`raider-reviewer` in `.opencode/agents/`; mechanics in
parallel-development.md §1b). The background bash loop for nightly runs
(opencode-1k-continuation.txt) is **deprecated**; headless night mode requires a
separate explicit owner decision. Parallel lanes for remaining TUI/P1/P3 work
are open at the same time (roadmap revision 2026-10-12); close W13-review in
the first spawn-mode wave.

Owner authorized parallel night development; worker provider
zai/glm-5.3-flash (later zai-coding-plan, direct, without yabanin). Full
mechanics/statuses: `raider-night` registry (temp) + records W7-INTEGRATION,
W8-W9-INTEGRATION (addendum W10–W11). Integrated and run through full root
gates (EXECUTE 7/7, manifests 20261010-060836…224104):

- LIN-01A/B/C + GraphHash fixes (W9-F1, F-L28-1/2; BREAKING canonicalization
  documented), LIN-02A/B/C/D + renderer dotc fix, view-adapter
  (cli/lineage), LIN-02 LIN2-01 compile-gate (golden→pinned dotc GREEN),
  LIN-03A live builder + LIN-03B HeadlessRunner summary lineage hash,
  LIN-04A `:aux` + LIN-04B LineageTab provider, LIN-05A corpus + LIN-05B
  comparator/verifier + LIN5-03 partial replay (reducePartial),
  LIN-05D RecordedSource (W13, review approve 2026-10-12), LIN-08A docs/lineage.md.
- RAI-072 (events/tabs/cancel docs), RAI-079 (anthropic stand tool-round),
  RAI-080 (onboarding), RAI-096 (plugins SDK + 3 fixtures; examples are now
  in compile.sh), RAI-097 (unified StatusText: cli-chat + tab bar).
- Review cycles: W8-review (6/7→fixes closed), W9-review (6/7→closed),
  W10-review (3 Major→closed in W11), W12-review (5/6→R1/R2 closed by the
  coordinator, self-fixes reviewed in W13), W13-review: approve (2026-10-12,
  first spawn mode: independent raider-reviewer on wt/w13-review;
  RecordedSourceSpec 11/11 reproduced, evidence binding checked).

Open (after the night run): LIN-05 full interpreter (replay-recorded LLM from a
record; RecordedSource is a prerequisite), LIN-06/07 (live re-run, partial phase
replay), TabShell→REPL wiring (adjacent to RAI-062), multi-job export (owner
decision), M1-evidence card RAI-099 (coordinator), phase/recovery schema card
(wave 3, owner), live-wire verification (owner: endpoint/budget).

## Spawn campaign 2026-10-12 (visible agents, §1b; coordinator)

Wave 1 (spawn mode, 4 lanes): **W13-review LIN-05D approve** (independent
reviewer, RecordedSourceSpec 11/11 reproduced); **LIN-09** lineage run
artifacts (RunLineageArtifacts + 6 tests; reviewer approve; contract change →
LIN-11); **LIN-10** standalone replay CLI (ReplayCli; reviewer approve;
integrated gate caught forbidden System.exit → integration fix 60bccc5:
sanctioned ZIOAppDefault entry `ReplayCliMain`, process smoke exit 21
reproduced); **RAI-102** TabShell in the product REPL (contract change
implemented: build.sbt edge raiderRepl→raiderUi; TabShellHost + real PTY
P2W-01..03; reviewer approve); **LIN-11** LineageRenderer `()` closer for
def-free graphs (reviewer approve). Integration: main 60bccc5, merges
978194a/3f050e0/af4ff7f/56f244e. Integrated gates on candidate 150700f:
quality-full passed 10/10 (manifest 20261012-050132-69438-0001) + profiles
static/unit/contracts/ci/docs/runtime/repl/security passed 9/9
(manifest 20261012-050347-70594-0001). Open (owner): live-wire, multi-job export,
phase/recovery schema card; follow-up: wire TabShellHost into Main.ttyLoop,
wire ReplayCli into the product `raider` command. Wave 2: RAI-103..106
(remaining P2 work: drafts/scrollback/unread/palette-help on the product surface).

Wave 2 (spawn mode, 4 lanes; integration main f893d48): **RAI-103** drafts
(P2D-01..03, reviewer approve; no product changes — feature-specific PTY
specs), **RAI-104** scrollback bounds (P2S-01..03, approve; product
TabShellScrollback render), **RAI-105** unread badges (P2U-01..03; blocker
lane → coordinator determinism fix 71d0aa3: quiescence drain before :quit in
the P2W-01 driver, since the badge marker was nondeterministic), **RAI-106**
palette/help (P2P-01..03, approve; derived catalog from frozen CommandRouter,
TabShellHost paletteTerminal hook). Records: docs/work/records/RAI-103..106.md.
Integrated gates on f893d48: quality-full 10/10 (manifest
20261012-071006-49099-0001) + profiles static/unit/contracts/ci/docs/runtime/
repl/security 9/9 (manifest 20261012-071243-51219-0001). **RAI-107 Plan mode
(ADR-028) — mandatory owner card (2026-10-12): accepted** — lane rai-107 with
partial handoff (a dead rate-limit spawn was verified by a second worker and
preserved); ADR-028 Accepted (owner mandate); PLAN-01..03 green (scripted, 11
tests); review: code approved, 1 MAJOR evidence finding (record after gates
broke its own fingerprint) closed by rerunning on main; lane manifests marked
stale in the record (docs/work/records/RAI-107.md). Determinism fixes during
integration (determinism, not weaker assertions):
71d0aa3 quiescence drain P2W-01; 91a6575 :tabs-render retry P2U-03.
**Wave 3 (owner decisions 2026-10-12):** composition = wiring trio
(RAI-108/109/110) + RAI-111 (palette) + LIN-12 (catalog persistence) +
RAI-112 (M1-evidence refresh); approval UX = TUI plan screen; 4 lanes;
worktree cleanup deferred. RAI-108..112+LIN-12 registered (validate passed 127
cards, b75c2ec). INCIDENT: all 4 spawns in batch 1 failed due to provider
rate limits AFTER work had started — uncommitted partial work remained in all
4 worktrees. Fallback §1b(1): workers run sequentially in the coordinator's
primary session (reason recorded here); review is by spawned agents when the
provider recovers; self-approval is prohibited, merges only after independent
review.
Final integrated gates (main 91a6575): quality-full 10/10 (manifest
20261012-105946-2326-0001) + ci/contracts/docs/repl/runtime/security/static/unit
profiles 9/9 (manifest 20261012-110432-24403-0001); validate.py passed
(121 cards). Follow-ups: RAI-068 chat commands in palette; approval channel/UI
for plan mode; TabShellHost in Main.ttyLoop; ReplayCli in product command
`raider`; P2S render consumer step; plan-mode eval profile (milestone).

**Wave 4 (spawn mode §1b, 7 lanes + integration; base 4b46dc7 → main
10ea71c):** LIN-13/14/15 + RAI-113/114 registered (4b46dc7, validate 132
cards). Lanes and verdicts: **W22-R8-review** (W20–W21 tail: F-W22R1-1 the
only finding, closed in 5ff5318) → **W22-R8** sweep (R1 MAJOR:
`Gen.int(1,50)` was random sampling instead of a sweep; fixes 1cc02b3:
`Gen.fromIterable(1 to 50)` + 7 separate assertions; re-review approve,
determinism: 50 seed files identical ×3); **LIN-12** CatalogStore persistence
(append queue/per-path lock/upsert run-dir; LINC-01..03; approve; minor points:
no cross-process support, key path normalization); **LIN-13** lineage §9.2 sync
(17/17 evidence links; approve; related §10 edits marked); **LIN-14** LIN2-03
race spec (4 readers vs writer, 0 partial reads ×3; approve; exposed:
PlanPanelSpec flake 1/4, baseline scalafix ParseError at PlanTabsPtyApp:173
from e1a8c9f); **LIN-15** LIN-06 scripted acceptance LIN6-01/02/03 (typed
fixes in IsolatedRerun/ReplayHarness with reproducers; approve; minor: alias
hole in guard — follow-up RAI-117); **RAI-113** :scrollback consumer
(registry+PTY P2SR-01..03; approve; minor: precedence vs :switch — follow-up
RAI-118); **RAI-114** plan-mode eval corpus, 6 scenarios (PEVAL-01..03 ×3;
approve). PlanTabsPtyApp conflict resolved in favor of RAI-114's superset fix.
Integration: merges c98fb77…29d770b + records (W22-R8.md assembled; LIN-12/
RAI-113 transferred from worktrees).
Integrated gates on candidate 10ea71c: quality-full (full sbt test + repl/cli
smokes) — the first sbt-test attempt honestly failed on the known
TabShellUnreadPtySpec PTY flake under load from 8 lane JVMs (isolated run 3/3
in 40s, flake is not a regression; honest failed manifest
20261012-200222-93693-0001), then passed on retry; quality-changed EXECUTE 10/10
(manifest 20261012-200841-98234-0001); static/unit/contracts/ci/docs/runtime/
repl/security profiles 9/9 (manifest 20261012-201326-4234-0001).
Follow-ups registered: RAI-116 (PTY determinism under load), RAI-117
(canonical-path guard), RAI-118 (:scrollback/:switch precedence).
The SDK research track also landed independently on main (3a60f93…318fcf4,
RAI-115) — retained; no conflicts with wave 4.

**Wave 4b (spawn mode §1b, base 9a43224 → main 6790c6f):** RAI-116..118
renumbered at registration to RAI-119..121 (collision with the SDK track, which
took RAI-116/117). Lanes and verdicts: **RAI-119** PTY determinism (3 test
files; driver infra att1 reproducer: evidence-dir before child starts;
PlanPanel drain+refresh-retry; TabShellUnreadPty waitQuiet-drain + fresh-render
PlanPanel drain+refresh-retry; TabShellUnreadPty waitQuiet-drain + fresh-render
retry run9; PTYD-01/02 10/10 under load 156–164, PTYD-03 raiderRepl ×3 +
full sbt test; review approve; candid note: the wave-4 PlanPanel class was not
reproduced in 18 attempts — the fix is preventive, recurrence → conditional
RAI-123); **RAI-120** IsolatedRerun canonical-path guard (isSameFile before
byte-check, fail-closed on missing/IO; ISO-01..03; review approve; minor: guard
failure typing is too coarse — account for this in the LIN-15 wiring card);
**RAI-121** :scrollback digit-first precedence (checked line by line against
frozen :switch, assertions unchanged — UUID is never all-digit; SCB-01..03;
review approve; dead rate-limit spawn partial verified in lane — second
incident, pattern is reproducible); **RAI-112** M1-evidence refresh — M1R-01
BLOCKED: frozen tabs_pty_probe expects legacy `[Running]`, while the product
renders honest ADR-024 statuses → **RAI-122** (protected verifier: ROW_RE =
StatusText grammar, ok_live → “[running | cost unknown]”, :aux in HINT_BAR,
pump guard before :quit; review approved both scope points, negative control
PROBE-02 holds); M1R-01 repeated on candidate ×3 all_pass → **RAI-112 done**
(addendum in record).
Integration: merges 81dad02/8829796/8809e16/05bbe60/7fd2776; RAI-119 evidence
(artifacts/rai-119) and RAI-122 (artifacts/rai-122-evidence) transferred to
root. Integrated gates on candidate 7fd2776: EXECUTE 10/10 (manifest
20261012-233255-59159-0001, full sbt test without flakes), profiles
static/unit/contracts/ci/docs/runtime/repl/security 9/9 (manifest
20261012-233800-62893-0001); validate 140 cards. Open: RAI-123 (conditional,
on recurrence of the PlanPanel race), LIN-06 live-wire/multi-job/schema card
(owner), P2S rendering — closed by RAI-113; plan-mode eval profile — corpus
ready (RAI-114), profile registration in working-product stage.

**Wave 4c (visible spawn mode §1b, base 7702fd0 → main acca49f) and obligations audit:** RAI-124/126 were registered (7702fd0, validate 142). RAI-124 implements REPL `:type` through native dotty TypeOf and frozen engine.eval WITHOUT execution: no side-effect marker, no resN advance; helpText/catalog freshness; TYPE-01..03, PTY ×3 flake=0; review approve. Minor findings: saturated PTY echo oracles and misleading UX error prefix → RAI-130. RAI-126 applies COURSIER_CACHE setdefault to three PTY-probe sites, including sbt_run :159 outside the original card anchor; the deviation is explicitly documented and approved by review. CC-01..03 numerically verify the caller cache grows from 0 to 180 files; protected verifiers, review approve. Independent read-only Open Obligations audit against main 7702fd0 found 7 of 18 rows CLOSED/STALE (file:line evidence); the key W05 gap is missing Anthropic tool definitions. The table was replaced; RAI-127/128/129 were registered from the audit, RAI-130 from review, validate 146 cards. Merges: 6f4e9e3/acca49f. Integrated gates on acca49f: EXECUTE 10/10 (20261013-023006-78942-0001), profiles 9/9 (20261013-023534-82374-0001). Hygiene: rescope RAI-015 (m0.md) through a separate card. Wave 4d: RAI-127/128/129/130.

**Wave 4d (visible spawn mode §1b, base 0e119da → main 1fd8636; scalafmt-fix
committer follow-up):** Lanes and verdicts: **RAI-127** Anthropic tool
definitions on the wire (encodeTools: input_schema verbatim from
parametersJsonSchema; definition-gated stand round — tool_use only when
definitions are on the wire; empty tools remain byte-for-byte unchanged;
RED→green; review approved; minor findings: schema validated as a JSON object
without deep semantics, tool_result.content remains a string — pre-existing;
do not consider all of W05 closed); **RAI-128** named cross-root fairness test
(FAIR-01..03: bounded FIFO, peak==1 tripwire, per-root exhaustion — typed refusal
only for the exhausted root, negative root-priority control detected; review
approved; minor findings: 5ms settling can only produce a false red,
forkDaemon tails are harmless); **RAI-129** `raider resume <run-dir>` — read-only
recovery from events.jsonl (reducePartial → named fields + SHA-256 references;
typed exits 0/26/21/20; torn tail marked without tracing that tail; run directory
remains byte-identical; review approved; minor findings: exit-20 branch untested
— assigned to 129.b; checkpoint.json is an unused name; documentation must
explain events.jsonl versus segments when consumed); **RAI-130** :type PTY
oracles no longer accept saturated echo (send→echo→answer after the last anchor;
negative control TYPED-01: without send, 12 oracles fail;
compileErrorText(msg, typeQuery) gives accurate diagnostics without
“prelude broken?”; review approved; minor finding: identical repeated query
03c lacks per-step oracle isolation, covered at suite level). Integration:
merges 89888f6/84b3ade/8c83ec1/1fd8636; scalafmt-check correctly failed on the
candidate (6 lane files) → whitespace-only fix → EXECUTE 10/10 (manifest
20261013-042259-77530-0001), profiles
static/unit/contracts/ci/docs/runtime/repl/security 9/9 (manifest
20261013-042817-84409-0001). Open: 129.b (continuation) / 129.c (retention)
after an owner schema card; W05-tool_result content (structured block mapping)
requires a candidate card.

## Night campaign 2026-10-13 (owner decision)

Owner decision (interactive session, 2026-10-13): overnight development
continuation is authorized in the visible spawn-agent mode (parallel-development
§1b) — explicitly NOT the deprecated headless night-loop; combined cards are
allowed so that more functionality ships per lane. Reason: use idle night
capacity to accelerate progress toward the working product. All new records,
cards, and board text follow the English documentation policy (2026-10-13).

Registered night cards (validate passed, 152 cards):

- **RAI-131** — CI adapter batch (combined RAI-023 remainder + RAI-024):
  GitHub/GitLab context fixtures, trust preflight (provenance before spend),
  per-context exit/artifact contracts (W12 core).
- **RAI-132** — Steering surface (P3 send/steering remainder): FacadeOps/job
  send wiring, REPL :send command, typed receipts, golden DSL steering.
- **RAI-133** — W05 completion: structured tool_result content blocks on the
  Anthropic wire + cross-wire parity spec (OpenAI vs Anthropic stands).
- **RAI-134** — User guide refresh for all new surfaces (English).
- **RAI-135** — Registry: additive eval/perf component-stage profiles
  (coordinator-executed gate-policy change; independent review required).
- **RAI-136** — Load soak of the PTY family + RAI-123 trigger probe
  (conditional product fix only with a captured reproducer).

Wave N1 execution fallback (recorded 2026-10-13): mid-wave the environment
permission config restricted task-tool spawns to read-only explore agents only
(worker/reviewer spawns denied). Per parallel-development §1b fallback (1):
night cards execute SEQUENTIALLY in the primary coordinator session
(coordinator-as-worker, wave-3 precedent); each completed card gets an
independent read-only verification pass (explore agent) before merge — no
self-acceptance. Execution order by value density: RAI-135 (coordinator-owned
registry change) -> RAI-133 (W05 completion) -> RAI-131 (CI adapter batch) ->
RAI-132 (steering) -> RAI-134 (guide refresh) -> RAI-136 (soak). Owner-gated
items remain untouched (live-wire, multi-job export, phase/recovery
schema-card, F-14 sign-off).

Night progress (2026-10-13):
- **RAI-135 DONE** (bd1f94b): eval component-stage profile registered
  (eval-corpus gate = deterministic plan-mode corpus, green x3, manifests
  20261013-044405/-044440/-044510); perf deferred to RAI-137 with an honest
  reason (ux_baseline exits 0 on skipped measurements — would violate
  skip-is-never-passed). Explore-agent verification: APPROVE (strictly
  additive, claims supported).
- **RAI-133 DONE** (merge 91b0b49): Anthropic tool_result content is a
  structured text-block array (payload verbatim); TRC-01 exact wire JSON,
  TRC-02 encoder tolerance, TRC-03 cross-wire parity via ONE AgentLoop over
  both local stands (3 repetitions); 2 pre-existing pins updated with
  justification (strength kept); module 25/0; gates EXECUTE 5/5 + profiles
  static/unit/contracts/transport. Explore-agent verification: APPROVE
  (independent re-run 25/0, manifest hashes matched). Integrated gates on
  91b0b49: EXECUTE 10/10 (20261013-053133) + profiles
  ci/contracts/docs/repl/runtime/security/static/transport/unit 9/9
  (20261013-053643). The W05 tool round is now complete on both wires at the
  definitions+results level.
- **RAI-131 DONE** (merges d01ac04 + fixes): trust preflight
  (--require-provenance, typed refusal before spend, ledger-discriminator
  proof), checks.junit.xml artifact contract, CI-ADP-01..03; review round 1
  CHANGES-REQUIRED (vacuous zero-dispatch assert; an incomplete empty-file
  fix caught by verification) -> fixed -> explore re-review APPROVE.
- **Mandate execution** (owner directive section above): RAI-140 DONE (ADR-031
  + projection runtime; review APPROVE) -> RAI-141 DONE (renderer emits REAL
  LineageStep composition — sequencing/inputs-threading/nesting/
  failed-nodes; assertExecutable gate kills comment-only bodies; review
  APPROVE; escape follow-up -> RAI-151) -> RAI-150 DONE (view adapter
  populates inputs/outcome; product adapter == gate adapter; two-half lane,
  outcome half unblocked by the 141 merge). RAI-148 DONE (fs_patch/proc_run
  Mutating; canonical RecoveryTaxonomy — THREE drifted copies deduped;
  review APPROVE; ADR-027 §6.2 updated at merge). RAI-143 DONE (ADR-032
  lineage wire v2: phase/recovery payloads, invocationId correlation,
  run_provenance; v1 pinned bit-for-bit; hash-exclusion policy; review
  APPROVE; merge resolved a scaladoc conflict with 148). RAI-149 DONE
  (CodingToolset stamped ALL tools ReadOnly — plan-phase enforcement REALLY
  applied fs_patch and ran proc_run pre-fix; corrected stamps + real
  reproducer; review APPROVE). Registered follow-ups: RAI-151 (literal
  escaping), RAI-150/149 registered as executed. Integrated gates on
  268dd5a: EXECUTE 10/10 (20261013-112146) + profiles ci/contracts/docs/
  eval/repl/runtime/security/static/transport/unit 10/10 (20261013-112709)
  — first aggregate run with the RAI-135 eval profile.
- **RAI-142 DONE** (review APPROVE): executed projection acceptance — render
  → pinned-dotc subprocess → URLClassLoader → Task.zio on the REAL generated
  vals (no mirrored composition); exact report trees, RA-OUT short-circuit
  with prefix observability, zero-dispatch probe; minors: dead asserts in
  test 6 (enforced elsewhere — ride-along fix with the next acceptance
  card), "same pipeline as gate" wording. **RAI-151 DONE** (review APPROVE):
  \uXXXX literal escaping (dotc-verified \xHH rejection reproduced);
  hostile fixture renders+compiles+executes; W9F4-01 to compiling shape.
  **RAI-144 DONE** (review APPROVE): multi-job export — one serialized sink,
  export-canonical dense per-job seq, GraphHash interleave-invariance tested,
  fork record once in parent stream, run_provenance pointers resolve to real
  child artifacts, InvocationIdOnV1 defense-in-depth landed; honest
  fixture-found fixes (.interruptible, campaign watermark) documented;
  minors: unlinked-segment named fixture → RAI-146 VR-02; v2 write path pin
  → RAI-146. Integrated gates on the merged candidate: EXECUTE 10/10
  (20261013-132823) + profiles 10/10 (20261013-133341); two integrated-gate
  catches fixed en route (scalameta =-binding, scalafix DisableSyntax.null
  on reflective MODULE$ reads — lane gate sets lacked these checks).
- **RAI-145 DONE** (review APPROVE): unified CI pipeline projection — billing
  shape (5 stages, 8 delegated children) through ADR-031 runtime + frozen
  batch; exact 23-report tree pinned, dotc-compiled, executed; UNI-02
  unlinked roots separated/typed; UNI-03 corrupted correlation → typed no-
  codegen, detached child → LIN5-03 partial with typed Incomplete; phase
  stand-ins disclosed (generator emission-agnostic); two minor latent
  findings carried to RAI-146 verifier design (single-element fan scope
  flatten; non-def parent group emitted sequential). Integrated gates on the
  final candidate: EXECUTE 10/10 (20261013-150935) + profiles 10/10; two
  integrated-gate catches fixed en route (scalameta =-bindings in the new
  spec).
- **RAI-146 DONE** (review APPROVE): frozen unified corpus (20 files:
  billing shape, delegation tree with the f2 non-def-parent case, unlinked+
  stray, pure-fan-out roots f1 reproducer; 4 typed negatives with byte-verified
  construction) + 948-line verifier (VR-01..03, ×3); TWO packet-authorized
  generator fixes landed with corpus reproducers (f1 single-element fan
  flatten; f2 member-presence scope matching); honest red trail (2
  pre-existing unrelated full-suite flakes -> RAI-152 registered); erratum:
  corpus README case-1 prose arithmetic (23 = 5+8+9+1 tool), expectations
  correct. **RAI-147 DONE** (review APPROVE): user guide + lineage.md §5.2
  unified section + REAL regenerated lineage artifact (sha == corpus pin;
  verifier 10/10 on the doc fingerprint) + honest shipped-vs-demonstrated
  framing. **P2 mandate chain COMPLETE.**
- **Follow-ups registered from reviews**: RAI-153 (live phase emission —
  replaces stand-ins), RAI-154 (headless coder delegation wiring +
  per-level fork anchoring + unified lineage-data writer), RAI-152
  (full-suite load flakes). Queue behind: RAI-132/134/136/137,
  RAI-123 (conditional), 129.b/c, LIN-03/04 (live projection + TUI).

## Chat-to-workflow campaign (owner directive 2026-10-14, §6d)

Owner priority: RAI-155 chat → editable Scala workflow → local + generic CI
(docs/prompts/opencode-chat-to-workflow.txt). Cards RAI-155.a-f were
registered by the SDK session; RAI-116 gates .a.

Completed before the campaign (night wave, all reviews approve):
- **RAI-153** live phase emission (LoopEvents PhaseSink, plan bracket, export
  wiring; RAI-145 stand-ins retired) — merge f2765aa. **RAI-154** headless
  coder delegation wiring + per-level fork anchoring (RED reproducer first) +
  unified lineage-data writer — merge d23bc90. Joint verification on merged
  main: MultiJobExport/PhaseEmission/LineageArtifacts/CoderDelegation 20/20.
- **RAI-152** full-suite flake determinism (hub relay race mechanism: two
  forwarder fibers, dropped=0 discriminator; test-side masked at 3 sites) —
  merge 50d8b99. **RAI-132** steering surface (typed receipts, :send,
  steering-aware facade turn loop; STR-01..03) — merge b91e678.
- **RAI-146/156 adjudication**: MJE-03c integration red was a FIXTURE
  scheduler race, NOT a 153x154 semantic conflict (fresh-reviewer
  adjudication; Resolution A applied: driveNested + pollUntil connection pin,
  product semantics unchanged). RAI-156 registered (product-side
  eventuallyTerminal drain — the open product race); RAI-157 registered
  (settle-window silent-loss window).
- Integrated gates on the merged candidate: EXECUTE 10/10 (20261014-012827).
- **RAI-116 DONE** (merge; review APPROVE): ADR-029 workflow SDK contract —
  authoring/launch boundary, BundleWorkflow v1 honesty (agent work cannot run
  behind v1), H1-H8, packet proposals; RAI-155.a UNBLOCKED (RAI-116 ✓ +
  RAI-140 ✓). Minors: JDK-27 toolchain pin recommendation; Option L vs B
  decision belongs to .a.

## Owner directive 2026-10-13: LIN-02 completion + unified CI pipeline

Owner mandate (interactive session): (P1) the LIN-02 projection must be REAL
Program/Task composition — compiling comment-only output does not implement
the contract; (P2) unified CI pipeline projection — the previously owner-gated
multi-job lineage export and phase/recovery schema work are AUTHORIZED for
this objective (nothing else); (P3) continue dependency-ready roadmap cards.

Audit findings recorded: renderer emits comments + () bodies; NodeView lacks
inputs; fs_patch/proc_run missing from the static RecoveryClass taxonomy
(ReadOnly despite mutating — the captured BillingCi repair case); taxonomy
duplicated (reducer private + IsolatedRerun copy); BillingCi has six
independent native roots joined by an application-owned index (no native
phase graph); pipeline/parallel/batch syntax is illustrative, not an API.

Registered decomposition (validate passed, 162 cards):

- **P1**: RAI-140 (ADR-031 projection runtime contract — typed dry-run
  description; NodeView inputs; semantics table render/description/replay/
  rerun) -> RAI-141 (renderer emits real composition; goldens regenerated;
  compile gate extended: no comment-only bodies) -> RAI-142 (executed
  projection semantics acceptance; zero-dispatch probe).
- **P2**: RAI-143 (schema card: versioned phase/recovery + cross-run
  provenance; v1 back-compat pinned; hash-version policy; protected, review)
  -> RAI-144 (multi-job export: delegate children join one events stream with
  correlation + provenance pointers; no invented edges) -> RAI-145 (unified
  graph -> DSL projection, BillingCi shape; frozen verbs only) -> RAI-146
  (independent verifier corpus + negatives) -> RAI-147 (guide + complete
  BillingCi example with REAL generated lineage).
- **Taxonomy**: RAI-148 (fs_patch/proc_run -> Mutating; single canonical
  source; isolation guard re-verified).
- **Queue behind the mandate** (dependency-ready, unchanged): RAI-132
  (steering surface), RAI-134 (guide refresh), RAI-136 (soak), RAI-137 (perf
  harness), RAI-123 (conditional), 129.b/c (after RAI-143), LIN-03/04 (live
  projection + TUI) after the projection runtime lands.

Execution: visible spawn workers/reviewers where the permission config allows;
fallback 1b(1) (sequential coordinator-as-worker + explore verification) is
the recorded default since the mid-session restriction. One card = one
reviewable outcome. RAI-131 was accepted this session before the mandate
(trust preflight + checks.junit.xml; EXECUTE 10/10 + profiles 9/9 on d01ac04).

## Module Status

| Card / scope | Status | Evidence / next step |
| --- | --- | --- |
| RAI-001 Feasibility | changes-required | [original record](records/RAI-001.md), independent review, hash manifest |
| SPIKE-OAI-01 Transport feasibility | changes-required | [original record](records/SPIKE-OAI-01.md) |
| RAI-003 Pinned build | todo | preparation allowed in parallel with remediation |
| RAI-004 Quality runner | todo | stage-aware registry + non-git snapshot classifier |
| RAI-007 Jobs registry | accepted | [record](records/RAI-007.md); 10 runtime tests; F-1 regression |
| RAI-008 Scopes | accepted | [record](records/RAI-008.md); SCOPE-01..03 matrix |
| RAI-009 Budget/admission | accepted | [record](records/RAI-009.md); 33/33 tests ×3 |
| RAI-010 AgentLoop | accepted | [record](records/RAI-010-A.md); LOOP-01..03; 18/18 ×3; 188 total |
| RAI-002 API/schemas | accepted (slice) | [record](records/RAI-002-REMAINDER.md); bundle/result/launch schemas; ContractsSpec 35 ALL PASS |
| RAI-005 Config | accepted (slice) | [record](records/RAI-005-SLICE-A.md); core 13/13; CFG-01..03 |
| RAI-006 Testkit | accepted (slice) | [record](records/RAI-006-B.md); controllable primitives, deterministic cancel |
| RAI-009 Budget remainder | accepted (slice) | [record](records/RAI-009-B.md); stress + late-settlement 7/7 ×3 |
| RAI-011 OpenAI transport | accepted (slice) | [record](records/RAI-011-SLICE.md); non-streaming + SSE + tool calls; live-verified |
| RAI-012 SSE streaming | accepted (slice) | [record](records/RAI-012-SLICE.md); SseFramer + ChatChunkParser; 17/17 ×3 |
| RAI-013 Anthropic | accepted (slice) | [record](records/RAI-013-SLICE.md); Messages wire, native tool_use/tool_result; 11/11 ×3 |
| RAI-014 Read/search | accepted (slice) | [record](records/RAI-014-SLICE-A.md); 13/13 |
| RAI-016 Process | accepted (slice) | [record](records/RAI-016-SLICE-A.md); 6/6 |
| RAI-017 Composition | accepted (slice) | [record](records/RAI-017-SLICE-A.md); 10/10 |
| RAI-022 Headless runner | accepted (slice) | [record](records/RAI-022-SLICE.md); jar bundles, chat mode, SIGTERM; 7/7 ×3 |
| REPL-FAST | done | [record](records/REPL-FAST.md); real compiler, 13/13 PTY |
| REPL-LOOP | done | [record](records/REPL-LOOP.md); facade on AgentLoop |
| DSL-ERGO | done | [record](records/DSL-ERGO.md); lazy descriptors, all/batch, openSession |
| DELEGATION | done | [record](records/DELEGATION.md); delegate/await/cancel/list; 7/7 ×3 |
| SELFDEV | done | [record](records/SELFDEV.md); coder agent, live backend, fs tools; 10/10 |
| RAI-054 Quality runner fix | accepted | [record](records/P0-WAVE0.md); classify file-keyed + git mode; EXECUTE green on dirty tree |
| RAI-055 Events (P1.A slice) | accepted | [record](records/P0-WAVE0.md); ADR-024 frozen; raider.runtime.events; 99/99; EVT-01..04 |
| RAI-056 Renderer PTY spike (P0.C) | accepted | [record](records/P0-WAVE0.md); RENDER-01..04 4/4; baseline in artifacts/renderer-spike |
| RAI-057 SessionRegistry (P2.A) | accepted | [W1 record](records/W1-EVENTS-TABS.md); real-hub tabs, TAB-01..03, 5/5 |
| RAI-058 Loop emitters (P1.B-1) | accepted | [W1 record](records/W1-EVENTS-TABS.md); real rounds, outcomes, 109/109 runtime |
| RAI-064 Credentials env-only | accepted | [W1 record](records/W1-EVENTS-TABS.md); --api-key removed, helper bounded+masked; security-profile obligation open |
| RAI-059 Jobs/budget/delegation emitters (P1.B-2) | accepted | [W2 record](records/W2-EMITTERS-CANCEL-SHELL.md); EMJ-01..03, 18 emission tests; Terminal authority for UI |
| REPL-LIVE-BRIDGE | done | live prelude built in interpreter world (MainBridge classloader split deleted); live Qwen round-trip; 21/21 repl, PTY 13/13, EXECUTE 7/7; [record](records/REPL-LIVE-BRIDGE.md) |
| RAI-063 OOB cancel/deadlines (P3.B) | accepted | [W2 record](records/W2-EMITTERS-CANCEL-SHELL.md); CANCEL-01..03 ×20 ≤5s; 119/119 lane |
| RAI-061 Tab shell (P2.B) | accepted | [W2 record](records/W2-EMITTERS-CANCEL-SHELL.md); SHELL-01..03; 16/16 ui |
| RAI-060 Status line (P1.C) | accepted | [W3 record](records/W3-PARALLEL-10.md); integrated, gates green |
| RAI-062 PTY integration (P2.C) | accepted | [RAI-062 record](records/RAI-062.md); PTY-01..03 + M1-DEMO all_pass |
| LIN-12 Catalog persistence | accepted | [record](records/LIN-12.md); LINC-01..03; review approve (minors: cross-process/path-norm) |
| LIN-13 lineage §9.2 sync | accepted | [record](records/LIN-13.md); DOC-13-01..03; review approve |
| LIN-14 LIN2-03 race coverage | accepted | [record](records/LIN-14.md); 0 partial reads ×3; review approve |
| LIN-15 LIN-06 scripted acceptance | accepted | [record](records/LIN-15.md); LIN6-01/02/03(sc); review approve; alias-guard → RAI-117 |
| RAI-113 :scrollback consumer | accepted | [record](records/RAI-113.md); P2SR-01..03 PTY; review approve; precedence → RAI-118 |
| RAI-114 plan-mode eval corpus | accepted | [record](records/RAI-114.md); PEVAL-01..03 ×3; review approve |
| W22-R8 replay sweep | accepted | [record](records/W22-R8.md); 50-seed deterministic ×3; review R1-R3 fixes, re-review approve |
| RAI-112 M1 evidence refresh | accepted | [record + addendum](records/RAI-112.md); M1R-01..03 met on 7fd2776 (probe ×3 all_pass after RAI-122) |
| RAI-119 PTY determinism | accepted | [record](records/RAI-119.md); PTYD-01/02 10/10 under load; review approve; recurrence → RAI-123 |
| RAI-120 IsolatedRerun guard | accepted | [record](records/RAI-120.md); ISO-01..03 fail-closed; review approve |
| RAI-121 :scrollback precedence | accepted | [record](records/RAI-121.md); SCB-01..03, digit-first = :switch; review approve |
| RAI-122 tabs_pty_probe sync | accepted | [record](records/RAI-122.md); PROBE-01..03; protected verifier, review approve |
| RAI-124 REPL :type | accepted | [record](records/RAI-124.md); TYPE-01..03, without execution; review approve; follow-ups → RAI-130 |
| RAI-126 probes COURSIER_CACHE | accepted | [record](records/RAI-126.md); CC-01..03; protected verifiers, review approve |
| RAI-135 eval registry profile | accepted (eval half; perf -> RAI-137) | [record](records/RAI-135.md); REG-01(eval)/02/03; explore verification approve; gate-policy change reviewed |
| RAI-133 structured tool_result + parity | accepted | [record](records/RAI-133.md); TRC-01..03; explore verification approve (independent re-run 25/0) |
| RAI-003/023/027 etc. | todo | prerequisites not met |
| M1 | accepted-pending-owner-note | [M1-ACCEPTANCE.md](records/M1-ACCEPTANCE.md) (evidence compiled) |
| Remaining M1/M2/P2 | todo | per working-product.md acceptance |

## Open Obligations

Historical audit snapshot, verified 2026-10-13 against main `acca49f` (file:line facts in the wave 4c audit record). The OPEN/ready states below describe that source revision. Later Wave 4d and night-campaign entries record later changes and registered work; use those entries and the [canonical backlog](../tasks/backlog.json) for current status. At the audit revision, seven rows were closed/stale and three were owner-gated; the remaining obligations were:

| Obligation | Card/condition | Status |
| --- | --- | --- |
| Live-wire verification (real providers) | OWNER (endpoint/budget) | OWNER-GATED (lineage.md §9.2; ad-hoc live rounds existed, but formal scoped verification is absent) |
| Tool definitions on wire — Anthropic | RAI-127 (ready) | OPEN — AnthropicMessagesBackend does not send tools/input_schema (OpenAI does); W05 gap |
| Journal/artifacts/recovery (§13) | RAI-129 (ready, narrow slice) + owner schema card | OPEN — journal+replay exist; product recovery/resume commands do not; retention/redaction and wire work are separate |
| CI full adapters | RAI-023(full)/024/026 | OPEN — RAI-078/100 accepted; trust preflight/provenance, trusted checks/exit contracts, and MVP acceptance remain |
| Fair admission across DIFFERENT roots | RAI-128 (ready) | OPEN — fair FIFO by construction; no named test with ≥2 roots |
| Performance/eval profile in registry | RAI-004 remainder; working-product stage | OPEN — registry has 12 profiles; eval corpus ready (RAI-114) |
| send_agent (steering) — REPL facade | RAI-018 remainder / RAI-021 | OPEN (narrow) — runtime steering implemented (RAI-065/074), FacadeOps.send unwired |
| Owner sign-off F-14 | OWNER | OWNER-GATED (RAI-007.md:59-61); runtime profile already exists in registry |
| Multi-job export / LIN-06 live rerun / phase-recovery schema card | OWNER + LIN-06 | OWNER-GATED |
| :type PTY oracle desaturation | RAI-130 (ready) | OPEN — RAI-124 echo-saturation findings |
| ~~Edit tool refinements~~ | CLOSED | fs_patch+UnifiedDiff+PatchTool; RAI-067 multi-file accepted; rescope RAI-015 in m0.md (hygiene) |
| ~~Child deadlines / OOB-cancel~~ | CLOSED | RAI-063 accepted |
| ~~Chat memory in CLI~~ | CLOSED | SessionStore, --resume, :compact |
| ~~REPL: :type, completion~~ | CLOSED | completion RAI-068; :type RAI-124 (merged acca49f) |
| ~~Virtual backoff fixture~~ | STALE | RateLimitRetries and two specs exist |
| ~~Event emission into hub~~ | CLOSED | RAI-058/059 accepted |
| ~~Tabs UI on ADR-024~~ | CLOSED | RAI-057/061/062/102/108 + RAI-103..106/113 |
| ~~--api-key in argv~~ | STALE | RAI-064: removed, typed error |
| ~~Probe COURSIER_CACHE setdefault~~ | CLOSED | RAI-126 (merged acca49f) |
| ~~Plugin SPI ADR/design~~ | CLOSED | ADR-025 FROZEN; RAI-066/075/083/096/101 |

## Key Findings (fixed by fixtures)

| Finding | Fix |
| --- | --- |
| zio-streams: `empty *> s` drops `s` (zip semantics) | use `++` + `.drain` |
| Scala 3.9: E046 cyclic on `x.call.y` chains | intermediate vals, no chains |
| Non-daemon fork interrupted by supervisor on parent completion | use `.forkDaemon` |
| System.exit from shutdown hook deadlocks runHooks | hook = interrupt + bounded wait + return |
| System.exit doesn't flush piped stdout | explicit flush |
| Nested admission on maxLLM=1 deadlocks runner+loop | runner not double-admission |
| java glob `**/*.scala` doesn't match root files | use `*.scala` |
| SSE frames lost at chunk boundaries | state in fields, not locals |
| `ArrayBuffer[Byte].indexOf('\n')` — Char never matches Byte | `'\n'.toByte` |
| `readNBytes(8192)` blocks until exactly 8192/EOF | use `read(buf)` for streaming |
| Reasoning models burn tokens on thinking | MaxOutputTokens 4096 |
| Stale --mock check blocks live provider | removed (provider flag governs) |

## Session History

| Date | Event |
| --- | --- |
| 2026-10-09 (W6-PARALLEL) | 9 cards: 083 plugin wiring, 084 HookRunner, 085 StateTools, 086 CI/accounting wiring, 087 JobJournal, 088 :plugins/:state, 090/098 REPL env-key+anthropic live, 091 forbidden-apis allowlist (APPROVE), 092 delegation stabilization ×10, 093 journal wiring, 094 state tools→tools/ui, 095 hooks→loop, 096 P4.C SDK, 097 unified status render, 099 M1-ACCEPTANCE compiled, 100 CI wrappers, 101 update/rollback. ADR-025 FROZEN. Full sbt 569/0. Record: W6-PARALLEL.md |
| 2026-10-09 (W5-PARALLEL) | 9 cards: 083 plugin wiring, 084 HookRunner, 085 StateTools, 086 CI/accounting wiring, 087 JobJournal, 088 :plugins/:state screens, 089 UX baseline MEASURED, 090 REPL env-key, 091 forbidden-apis allowlist (policy APPROVE), 092 delegation test stabilization. Full sbt 545/0. Record: W5-PARALLEL.md |
| 2026-10-09 (W4-PARALLEL) | 9 cards: RAI-074 mailbox wiring (send_to_child, 171/171 runtime), 075 PluginLoader+PluginCli (P4.B core), 076 StateStore SPI (P5.A), 077 Session v2+migrations, 078 CiContext (RAI-023 slice), 079 anthropic stand tool-round, 080 onboarding guide, 081 eval fixtures ×6 (protected), 082 accounting view. Full sbt 476/0; fast PASSED (10 gates). Record: W4-PARALLEL.md |
| 2026-10-09 (W3-PARALLEL-10) | 10 cards in the wave (owner decision 3→10 threads): RAI-062 PTY tabs (M1-DEMO all_pass), 065 mailbox, 066 ADR-025+SPI skeleton, 067 multi-file patch, 068 REPL completions, 069 security profile (gate-policy APPROVE), 070 UX baseline harness, 071 anthropic CLI, 072 docs, 073 Delegated-race. Full sbt 406/0. Record: W3-PARALLEL-10.md |
| 2026-10-09 (W3-PLAN) | RAI-060 accepted (integrated, gates green). OWNER DECISION: parallelism cap raised 3→10 threads (runbook §1 superseded for this wave; recorded here). Registered W3 wave: RAI-062/065..072 (8 lanes + reviewer + coordinator = 10). Validator 75 cards. |
| 2026-10-09 (W2-EMITTERS-CANCEL-SHELL) | RAI-059 emitters + RAI-063 OOB cancel/deadlines + RAI-061 tab shell accepted; DelegationToolset hand-merge verified pure-addition; runtime 137/137, ui 16/16; integrated gates green. Record: W2-EMITTERS-CANCEL-SHELL.md |
| 2026-10-08 (REPL-LIVE-BRIDGE) | Live REPL fixed: phase-3 prelude read console-world MainBridge statics; scala3-repl classloader split made interpreter world see null backend (NPE on every live ask). Prelude now wires streaming backend + coding tools inline; honest banner; MainBridge deleted. raiderRepl 21/21, PTY 13/13, quality-changed EXECUTE 7/7, live Qwen round-trip via local llama.cpp. Record: REPL-LIVE-BRIDGE.md |
| 2026-10-08 (W1-EVENTS-TABS) | RAI-057 SessionRegistry + RAI-058 loop emitters + RAI-064 credentials env-only accepted (3 lanes, independent review, fix rounds). Runtime 109/109, cli 44/44, ui 5/5. Integrated EXECUTE 7/7 + runtime/ci profiles. Record: W1-EVENTS-TABS.md |
| 2026-10-06 (P0-WAVE0) | 12k roadmap first wave: P0.A gap matrix (W01–W18/UX01–08, 2 runner defects + --api-key finding); ADR-024 event seam frozen (2 review rounds); RAI-054 runner fix (first honest dirty-tree EXECUTE green); RAI-055 raider.runtime.events (EVT-01..04, 99/99, zio queue-suspend defect found+fixed); RAI-056 renderer PTY spike (draft survives 200 writes + 3×67 overlap, width table baseline). Isolated snapshot lanes, independent review each. Record: P0-WAVE0.md |
| 2026-10-05 (ROADMAP-V2) | Plan v2 11,700h: optional agent state registry (Phase 3, 800h — scopes/blackboard/opt-in tools/default-off) + Yabanin direct integration slot (Phase 7, 900h, discovery-first per docs/yabanin-integration.md). New launch prompt MASTER-10K-STATE.md; ADR series 017–023; cross-refs renumbered. Gates 10/10. Record: ROADMAP-V2-STATE-YABANIN.md |
| 2026-10-05 (ROADMAP-MIT-START) | MIT-only enforced: license_audit.py gate (fast=10 gates), baseline 25 artifacts 0 problems, ADR-016 accepted (custom ANSI renderer on JLine, Lanterna rejected as LGPL), roadmap+licensing.md amended. Gates 10/10. Record: ROADMAP-MIT-START.md |
| 2026-10-05 (NIGHTLY-FOLLOWUP) | CoderPrompts single-sourced; make quality-full executed end-to-end (exit 0). FIXED: Makefile COURSIER_CACHE inline-default bug (coursier relative cache dumped 223MB https/ into repo — removed, gitignored), SECOND flaky delegation test (max_children, call-index race) content-routed, 6/6 clean. full 0 fail, gates 9/9. Record: NIGHTLY-FOLLOWUP.md |
| 2026-10-05 (NIGHTLY-P6) | Docs: api.md (public API + examples), architecture.md (modules, round data flow, budget/admission), contributing.md (tool/provider/gates/tests). FINAL CHECKLIST 10/10 incl. PTY smoke 13/13 and LIVE self-hosting round (local llama.cpp, Succeeded). Record: NIGHTLY-PHASE-6.md |
| 2026-10-05 (NIGHTLY-P5) | Packaging: GitHub Actions CI (test+quality jobs), raider-cli.jar via sbt-assembly (java -jar smoke PASS), Makefile raider-jar/raider-chat/raider-run/quality-full. full 0 fail, gates 9/9. Record: NIGHTLY-PHASE-5.md |
| 2026-10-05 (NIGHTLY-P4) | Recovery: CompileFixLoop (:fix, errors→model→recompile ≤3), RateLimitRetries (429 backoff 1/2/4s ×3, all 3 construction sites), coder prompts upgraded. FIXED flaky delegation cancel test (turn routing by content, bounded settle) — was hanging suites under load. full 0 fail ×2, gates 9/9. Record: NIGHTLY-PHASE-4.md |
| 2026-10-05 (NIGHTLY-P3) | REPL live: streaming backend via MainBridge, RAIDER_MODEL→session.model, system prompt on every facade call, live echo (deltas stdout, tools stderr). ReplSession +2 defaulted members. repl 21, full 0 fail, gates 9/9, repl_smoke PASS. Record: NIGHTLY-PHASE-3.md |
| 2026-10-05 (NIGHTLY-P2) | Core: CLI streaming (SSE backend + StreamingDisplay), SessionStore (--resume/:save/:sessions/:load), compaction (:compact + auto), fs_patch (unified diff, sha+atomic), fs_tree (bounded, skip-list), proc_run parsed field (sbt compile/test). cli 27, tools 46, full 0 fail, gates 9/9. Record: NIGHTLY-PHASE-2.md |
| 2026-10-05 (NIGHTLY-P1) | Quality infra: scalafmt+scalafix gates, forbidden_apis.py (self-tested), repl/cli smoke gates, profiles tools/transport/repl/ci, fast=9 gates. Null-free rule enabled (15 sites refactored). compile+test 0 fail, fast 9/9, profiles passed. Record: NIGHTLY-PHASE-1.md |
| 2026-10-04 (SELFDEV) | coder agent: CodingToolset (fs_read/fs_search/proc_run with containment and policy-denial=feedback) + --provider openai/--base-url/--model in CLI; selfdev launcher + prompts. cli 10/10, tools 19/19, full 188, gates 6/6. Record: SELFDEV.md |
| 2026-10-04 (RAI-022 remainder) | --agent mode + SIGTERM bounded stop. Caught by probes: non-daemon fork interrupted by supervisor; exit from hook deadlocks; stdout not flushed to pipe; nested admission deadlocks on maxLLM=1. 7/7 ×3; full 185; gates 6/6. RAI-022-SLICE.md updated |
| 2026-10-04 (RAI-022) | Headless runner: cli module (no compiler/JLine), jar-bundle via BundleWorkflow SPI, preflight before spend, artifacts, exit mapping 18 families. 5/5 ×3 + cli_smoke PASS. Record: RAI-022-SLICE.md |
| 2026-10-04 (DELEGATION) | §11: DelegationToolset over JobManager+AgentLoop; one root-ledger (6 dispatches), maxLLM=1 no-deadlock, no recursion. 7/7 ×3; full 178; gates 6/6 ×2. Finding: stuck child at maxLLM=1 blocks parent cancel. Record: DELEGATION.md |
| 2026-10-04 (DSL-ERGO) | Less verbose DSL in REPL facade: AgentAsk (lazy, API-03), all/batch/openSession over composition, @targetName-await, heterogeneous job registry. 9/9 ×3; full 171; smokes green. Record: DSL-ERGO.md |
| 2026-10-04 (RAI-013) | Anthropic Messages slice: provider-anthropic module; non-streaming+SSE, native tool_use/tool_result, cumulative usage. 11/11 ×3; full 162. Record: RAI-013-SLICE.md |
| 2026-10-04 (RAI-012) | SSE streaming slice: SseFramer (byte-level, UTF-8-safe) + ChatChunkParser + streaming backend. Partition property, typed negatives, cancel-mid-stream. 17/17 ×3; full 151. Record: RAI-012-SLICE.md |
| 2026-10-04 (RAI-011) | OpenAI transport slice: provider-chat module, JDK HttpClient, non-streaming + tool_calls. Capstone: AgentLoop over HTTP. 4/4 ×3. Record: RAI-011-SLICE.md |
| 2026-10-03 (40h session final) | Full test 188/0, gates 6/6, all smokes green. Records: RAI-010-A through RAI-011-SLICE |

## 2026-10-18 — RAI-174 merged; PLANUI-02 diagnosed; LIN-03 remainder

- **RAI-174 merged** from `lane/rai-174` as
  `f9ad80e0cbfa1c4bf0a3edf8ad70019cff1a0eef` (merge commit;
  round-3 review approved). The schema-v2 additive carrier is complete for
  card scope; RAI-164 completion is unblocked for its persistence wiring.
- **PLANUI-02:** integrated-only red was diagnosed as ambiguous; 20 focused
  attempts were green (10 isolated, 10 under load). RAI-123's mechanism is
  not established. An authorized reproducer capturing plan settlement and
  approval outcome is recommended before any fix card; artifacts:
  `artifacts/planui02-diag/`.
- **LIN-03 is NOT accepted.** Honest remainder: executed RED-first for the
  live fixture; decide compile-failure ↔ fold-error mapping (card clarification
  or evidence); add the 200-live-round production fixture; and test a crash
  between nodes. The lane iterates next cycle; LIN-04 stays blocked.
- **Integrated gates after merge:** `make quality-changed` exit 0 (docs
  classification); `make quality-changed QUALITY_EXECUTE=1` exit 0, passed,
  no timeout (`artifacts/quality/20261018-040545-93656-0001/manifest.json`);
  `make quality-profile PROFILES="static unit contracts runtime ci docs"`
  exit 0, all listed profiles passed, no timeout
  (`artifacts/quality/20261018-040609-95076-0001/manifest.json`). MJE-03c
  remains RAI-157's documented known-red (lane fix exists; full matrix remains
  load-held); TAB-02 remains RAI-172's conditional determinism follow-up. These
  documented exceptions were not observed as failures in this green run and
  are not claimed fixed. PLANUI-02 is a separate diagnosed integrated-only
  red, not reproduced by the focused matrix; do not infer the RAI-123 cause.
- `python3 scripts/plan/validate.py --out artifacts/plan-review/report.json`
  exit 0; 194 cards. PLANUI-02 diagnostic note retained above.
