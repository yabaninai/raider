# RAI-155.b: work record — persist exportable chat turns and explicit task selection

Status: done on lane branch `lane/rai-155b` (implementation complete; independent
review pending). Date: 2026-10-14 UTC. Baseline: `b9f9c8f` (clean worktree
`wt/rai-155b`, branch `lane/rai-155b`, matching main HEAD at start). Worker:
visible lane spawn. Contract: [ADR-033](../../adr/ADR-033-chat-workflow-export.md)
§4 (STS), §5 (capture/turn ledger/session schema v3/compaction survival),
§6+§8 (typed structure + refusal discipline — the .c renderer is NOT
implemented here), ADR-029 §3.1 (selection metadata is reference-only; no
launch capability is constructed or preempted).

## Resulting behavior

### Session schema v3 (additive, `Session.scala`)

- `SavedSession.SchemaVersion = 3`; two new fields: `turns: List[TurnRecord]`
  (the exportable turn ledger) and `checks: List[NamedCheck]` (user-registered
  named trusted checks). Writes always emit v3; v1/v2 documents load unchanged
  with the new fields empty (read-only — the existing typed version gate is
  preserved; a future v4+ document is still a typed error, never silent-read).
- A v3 document with a present-but-malformed ledger (wrong JSON type, invalid
  `kind`/`status`, empty ids) is a typed corrupt-document `InputValidation` —
  never silently emptied (B02).
- Atomic save discipline unchanged (temp + rename): a crash mid-save never
  exposes a half-written document, so an in-flight turn persists only as the
  consistent typed-`Unknown` record from the previous complete write or not at
  all (never a torn "completed").

### Turn ledger (`TurnLedger.scala`, new)

Per turn (ADR-033 §5.1): stable `turnId`, `kind` (`Intent`/`Refinement`/
`Ordinary`), bounded `request` (4000 chars), `jobId`, `status`
(`Completed`/`Failed`/`Interrupted`/`Unknown`), bounded `toolSummary`
(tool name + ok/refused/failed class + optional recovery class — NO payloads),
non-secret `configRefs` (workspace path, provider, model names), `atMs`/
`finishedAtMs` (recorded, never used to infer structure). Append-ordered;
ordering is the turn sequence. `NamedCheck` validates argv shape (1..16
non-empty bounded tokens, no control characters — the same argv shape the
`:fix` loop uses; model text can never add or alter a check).

Capture path (`ChatLoop.processMessage`): a turn begins INSIDE the job body
with status `Unknown` (torn/pending state, §5.1) and receives its terminal
status only when the job settles — typed `RaiderError` failure → `Failed`;
defect → `Interrupted`; external interruption/crash before the terminal write
leaves the record typed-`Unknown`. Success → `Completed`. The per-tool summary
is extracted from the trace events recorded during the turn (bounded, names +
outcome classes only). The job's own success/failure is re-raised unchanged —
capture is observation, never control flow. Compaction
(`compactHistory` → `historyRef.set(List(sysMsg) ++ recent)`, the exact
provenance-destroying rewrite named by ADR-033 §5.2) touches only the message
list; the ledger lives in a separate `Ref` and survives compaction and history
truncation untouched.

### Explicit task selection (`TaskCommands.scala`, new; dispatch in `ChatLoop`)

- `:task list` — the ledger with kinds/statuses.
- `:task mark <n> <intent|refinement|ordinary>` — metadata only (1-based
  ledger order); STS validity enforced at select time. A mark invalidates any
  stored selection (explicit re-select required, never a silent mutation).
- `:task check <name> <argv...>` — register/replace a named trusted check
  (validated argv); persisted in v3.
- `:task select <n>` — selects the LAST `n` turns as one bounded contiguous
  span and validates the Supported Task Specification at select time (ADR-033
  §4.1): exactly ONE `Intent` turn, ordered refinements (ledger order),
  at least one named trusted check, ALL turns `Completed`, no credential-shaped
  material. Result: an immutable `TaskSelection` snapshot stored in the
  session's `selectionRef` — the typed structure the RAI-155.c renderer
  consumes. `maxRoundsBound` is the captured bound reference; the launcher
  binds the effective policy at execution time (§4.1; no ADR-029 §3.1
  preemption). Typed refusals ride `InputValidation` with the structured
  `CWF-R…` detail prefix (ADR-033 §8): `CWF-R2` empty/legacy ledger with the
  remedy ("export cannot invent absent execution records — re-run the task and
  mark it"), `CWF-R1` unsupported selection (span bounds, no/duplicate intent,
  no checks — names the supported shape), `CWF-R3` non-Completed turns (lists
  the offending `turnId[status]`), `CWF-R4` credential-shaped material
  (location + class only, NEVER the value; high-confidence shapes: AWS access
  key ids, OpenAI-style keys, GitHub tokens, key assignments with 16+ chars of
  key-like material — over-refusal is the safe direction).
- `:task status` — export-readiness query (ready / not-ready + what is
  missing / empty-ledger legacy notice). No generation happens in this card.
- `:help`/banner updated; `:save` persists turns + checks
  ("saved N messages, M turn(s), K check(s)"); `:load` and `--resume` restore
  them and print reopen notices (ADR-033 §5.3): a conversation-only session is
  named explicitly with the remedy, and saved-vs-active config mismatch
  (workspace/provider/model) is displayed before work continues — reopening
  never restores authority. Existing chat behavior (`:pipe`/`:delegate` route
  through the same capture; `:clear` still resets only context/trace) is
  unchanged beyond the additive capture.

## Changed paths

- `modules/cli/src/main/scala/raider/cli/chat/TurnLedger.scala` (added)
- `modules/cli/src/main/scala/raider/cli/chat/TaskCommands.scala` (added)
- `modules/cli/src/main/scala/raider/cli/chat/ChatLoop.scala` (capture wiring,
  `:task` dispatch, save/load/resume carrying the ledger, help/banner;
  `RunCtx`/`processMessage` widened to `private[chat]` for same-package tests,
  following the existing `runJob`/`makeBackendSimple` precedent)
- `modules/cli/src/main/scala/raider/cli/chat/Session.scala` (schema v3
  additive fields + decode-time ledger validation)
- `modules/cli/src/test/scala/raider/cli/chat/TurnLedgerSpec.scala` (added)
- `modules/cli/src/test/scala/raider/cli/chat/TaskCommandsSpec.scala` (added)
- `modules/cli/src/test/scala/raider/cli/chat/SessionV3Spec.scala` (added)
- `modules/cli/src/test/scala/raider/cli/chat/TaskCaptureSpec.scala` (added —
  REAL ChatLoop capture path over a live JobManager/EventHub/Admission/
  AgentLoop with a scripted backend)
- `modules/cli/src/test/scala/raider/cli/chat/SessionMigrationSpec.scala`
  (version-bump updates: current=3, future-version fixture now 4)
- `docs/work/records/RAI-155b.md` (this record; draft kept in the lane
  worktree per the lane-worker contract)

All within the card's allowed paths plus the lane record. Renderer/generator
(RAI-155.c), workflow compile/run (RAI-155.d), wire protocols, the frozen v1/v2
read paths' semantics (still load unchanged), and shared files untouched.
`Main.scala` (allowed) needed no change.

## Acceptance evidence

| Criterion | Actual result | Evidence |
| --- | --- | --- |
| CWF-B01 (turn ledger persists through save/load + compaction with stable selection/ordering; legacy refusal typed) | `TaskCaptureSpec` B01: 3 turns (one driving a REAL loop-dispatched tool round captured as `fs_edit=refused`) → marks → `:task select` snapshot → seeded 35-message history triggers the REAL automatic compaction through `processMessage` (history rewritten to sys+20+2, head=system) while the ledger keeps stable ids/kinds → save/load roundtrip identity on turns+checks. Legacy: v1 fixture loads with empty ledger; selection over it refuses `CWF-R2` (both in `TaskCaptureSpec` B02-legacy and `TurnLedgerSpec`). | `TaskCaptureSpec`, `SessionV3Spec`, `TurnLedgerSpec` (all green in the gate-embedded `sbt-test`) |
| CWF-B02 (selection semantics + validation typed; missing/corrupt/mismatch/secret diagnostics; interrupted preserved) | `TurnLedgerSpec`: R1 (span bounds / no intent / duplicate intents / no checks), R2 exact message, R3 with `turnId[status]` list, R4 (typed, value never echoed); `NamedCheck` argv validation; decode-time corrupt-ledger typed errors (`SessionV3Spec`); config-mismatch + legacy reopen notices (`TaskCommandsSpec.restoreNotices`, and the real `--resume`/`:load` path in `ChatLoop`). | `TurnLedgerSpec`, `SessionV3Spec`, `TaskCommandsSpec` |
| CWF-B03 (persistence roundtrip + pending/interrupted preserved; snapshot boundary; no redispatch on restart) | Die-mid-job turn persists `Interrupted` with its pending request, is refused in selection (`CWF-R3` naming the id), and survives save/load; fiber-interrupted turn keeps typed-`Unknown` + request (no terminal write); reopen-style restore changes nothing (no redispatch); selection snapshot is immutable — a concurrent append (even marked) stays entirely outside it and only a NEW explicit select includes it. | `TaskCaptureSpec` (B02/B03 tests, all green) |

Source hashes (this lane snapshot, sha256):

- `TurnLedger.scala` `7ce1f95fbe40e46e1cccda19191a244a15865e3579bcfb090d78411911c5a1d7`
- `TaskCommands.scala` `3802ac6ed826c484ed2bc58cb012b4b62c362f76ee44f98b3ce7370f11203b63`
- `ChatLoop.scala` `7d31fbd8282abc10fb31bcd92431e4be8461fae5a53cae18313f4de3b48384c1`
- `Session.scala` `7568c02c5febd48c155b5a025c459e7613a5395e71983d4a1ed9137c70a0c981`
- `TaskCaptureSpec.scala` `5caa8715c6d746e651e79eb0808df32fdc41d3baf520de5703f623e082482a3f`
- `TurnLedgerSpec.scala` `b0fd175c094a46d04d9617abc99f0379852f942eb06c768411d8cc2291f2d693`
- `SessionV3Spec.scala` `4ba50dd2081293dcac87769988c49c19833b43cf37769807743203f87c84fb0b`
- `TaskCommandsSpec.scala` `fce91e5f6545c9703162fb294b89e9ff60f3aef8500fa1bcb1a72a6725b52a1e`
- `SessionMigrationSpec.scala` `b1d27bb769020ea55d09fa95fdb3918c473df1770c01404d78551282c42f785c`

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `git status --short` + `git log --oneline -1` (pre-work) | 0, clean @ `b9f9c8f` | baseline = main HEAD |
| `sbt raiderCli/test` (full module suite, before formatting) | 0 | 389 tests passed, 0 failed, 0 ignored |
| `make quality-changed` (classify, final tree) | 0 | changed-set classified → profiles [contracts, static, unit] |
| `make quality-changed QUALITY_EXECUTE=1` (1st) | 1 (FAIL) | `.coursier-cache/` sbt scratch inside the worktree mapped to no profile — runner refused (correct); scratch moved out of the worktree, rerun |
| `make quality-changed QUALITY_EXECUTE=1` (2nd) | 1 (FAIL) | `scalafmt-check` failed (test sources unformatted) — fixed with `sbt scalafmtAll`, rerun |
| `make quality-changed QUALITY_EXECUTE=1` (3rd) | 0 | status=passed; gates core-contracts-spec / compile-fixtures / sbt-compile / scalafmt-check / sbt-test all exit=0; `artifacts/quality/20261014-025450-36444-0001/manifest.json` |
| `make quality-profile PROFILES="static unit contracts ci"` | 0 | status=passed; gates sbt-test / cli-smoke / core-contracts-spec / compile-fixtures / sbt-compile / scalafmt-check all exit=0; `artifacts/quality/20261014-025945-41359-0001/manifest.json` (policy_sha256 `ab6d5caa7930352e7755c14a28f775b80f66d43b5fa6960deaa7a2f3ef7e102f`) |

Both intermediate gate failures were honestly failing runs that were CORRECTED
(never skipped or lowered); the passing manifests apply to the final source
fingerprint listed above.

## Limitations / blockers

- No renderer/generation here: `:task status` and the `TaskSelection` snapshot
  are the .c entry point; `:export-workflow` (ADR-033 §11.1 proposal) is NOT
  implemented in this card.
- The selection snapshot is session-authoring state and is NOT persisted
  (marks + checks + ledger are); after reopen the user re-selects explicitly —
  deliberate, never silently mutated.
- The persisted turn request text is bounded to 4000 chars and tool summaries
  to 32 entries (ADR-033 "bounded" fields); bulky payloads stay out by design.
- The credential-shape scan is high-confidence/narrow (4 shape families); it is
  a diagnostic at select time. The authoritative pre-write refusal belongs to
  the .c export path (§6.5).
- zio-test's default clock is frozen (`TestClock`): the capture fixtures are
  deliberately clock-free (bounded cooperative polling); the fiber-interruption
  test exercises external interruption through the real `runJob` await path.
- Runtime/concurrency acceptance beyond the in-process fiber interruption
  (SIGTERM of the whole chat process, non-TTY) is out of this card's slice
  surface (chat REPL is TTY-interactive; headless discipline belongs to the
  .d launcher) — the persistence/capture invariants those cases rely on are
  covered here.
- No paid calls: all fixtures run on scripted backends / the mock stand.

## Review and next task

Next: independent review of this lane against ADR-033 §5/§4.1/§8 and the card
acceptance CWF-B01..B03, then coordinator merge. Ready next card:
RAI-155.c (deterministic renderer consuming the `TaskSelection` +
`SavedSession` v3 structures — `raider.cli.chat.TaskSelection`,
`raider.cli.chat.TurnRecord`, `raider.cli.chat.NamedCheck` are its typed
inputs), which can proceed in parallel with the .d planning decision
(ADR-029 PKT-1 launch capability vs CLI-internal assembly).
