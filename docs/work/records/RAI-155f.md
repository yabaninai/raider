# RAI-155.f: work record

Status: completed in lane; integrated-candidate acceptance pending. Date: 2026-10-17. Lane: `lane/rai-155f`. Baseline commit: `149f07da32236465a513469caa5f352c9eff4453`.

## Resulting behavior

- Added a user-oriented walkthrough with the full generated and edited Scala files, exact behavioral diff, chat/task/export commands, compile/run command forms, result matrix, authoring-vs-execution lineage explanation, failure recovery, and explicit trust/model/resume limitations.
- Executed the accepted E01 real-entrypoint verifier against its local scripted OpenAI-compatible stand. The verifier exercised `ChatLoop.processMessage`, `TaskCommands` mark/check/select, the real `:export-workflow` path, pinned compile, the generated local main, and fresh non-TTY `raider.cli.Main workflow run` child processes. No paid/live provider calls.
- Four-turn capture comprised context-only turn 1 and task turns 2–4. The export was deterministic across two outputs. Provenance session/turns are `loop_1` / `t-2-b08b8e05`, `t-3-7b043f40`, `t-4-20d7b2ef`; the export manifest's pre-edit source identity is `sha256-0a749727e10ac4213ab3c119026f59e569745c5e0e82e1d5cecbe39cb3d2a68e`.
- Made the demonstrated edit in both the prompt-template target and the trusted check: `marker.txt` → `marker2.txt`. Pinned Scala 3.9.0 compile passed; edited source identity is `2090e877480196a533c257b9b8d126d3e279cbea547e53cde9aba80b88dc089a`.
- Matrix: fresh-copy local input 1 PASS/exit 0; fresh-copy CI input 1 PASS/exit 0; fresh-copy local input 2 PASS/exit 0; fresh-copy CI input 2 PASS/exit 0; fresh-copy unedited control input 1 PASS/exit 0. Same-input normalized launcher results matched; distinct inputs differed. Edited source published `marker2.txt`; control source published `marker.txt`.
- Independent checks failed before agent work for both `marker.txt` and `marker2.txt`. After edited run, `marker2.txt` passed and `marker.txt` still failed. This verifies the repair effect rather than trusting model prose.
- E02 refusal/negative verifier passed (4 tests); E03 cancellation/concurrency verifier passed (2 tests), including truthful interruption and no automatic retry. Those suites are supporting error/cancellation evidence; the walkthrough demo itself is E01.

## Changed paths

- `docs/examples/chat-workflow/README.md`
- `docs/examples/chat-workflow/cwf_e01.generated.scala`
- `docs/examples/chat-workflow/cwf_e01.edited.scala`
- `docs/examples/chat-workflow/cwf_e01.edit.diff`
- `docs/work/records/RAI-155f.md`
- Runtime evidence captured under ignored worktree path `artifacts/rai-155f/` (E01 export, manifest, edited/control builds, CI run artifacts and logs; plus E01/E02/E03 verifier logs). Evidence is not substituted for a new integrated-candidate gate.

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| CWF-F01 chat → select → export → meaningful edit → compile → four-launcher matrix + unedited control | Passed, E01: 1 verifier test, 0 failed | `artifacts/rai-155f/e01.log`; copied actual outputs `artifacts/rai-155f/e01/`; walkthrough `docs/examples/chat-workflow/README.md` |
| E01 export provenance/source identity | Passed; pre-edit digest differs from edited compile digest | `artifacts/rai-155f/e01/cwf-e01-a/cwf_e01-manifest.json`; `artifacts/rai-155f/e01/build-edited/build-identity.json` |
| E01 edited generic CI result | Passed, `wf_955e397c`, exit 0, `Succeeded`, `trusted-checks-passed` | `artifacts/rai-155f/e01/ci-out-1/work-a9c65549/{run.json,summary.md,events.jsonl}` |
| E01 unedited fifth control | Passed, `wf_9ac3e838`, exit 0; report publishes `marker.txt` rather than edited `marker2.txt` | `artifacts/rai-155f/e01/ci-out-control/work-325b6127/` |
| E01 pre-repair check and post-repair check | Both target checks nonzero before work; edited target passes after work and old target remains nonzero | E01 verifier assertions in `ChatWorkflowVerifierE01Spec`; E01 captured log |
| CWF-F02 unsupported/refusal, invalid source/input/check and hostile text | E02 passed: 4 tests, 0 failed | `artifacts/rai-155f/e02.log` |
| CWF-F03 interrupt/unknown and explicit rerun semantics | E03 passed: 2 tests, 0 failed | `artifacts/rai-155f/e03.log` |
| Documentation/static quality | Preliminary checks passed; final required sequence is rerun after this record is finalized | Preliminary manifests: `artifacts/quality/20261017-234843-56297-0001/manifest.json` and `artifacts/quality/20261017-234844-56510-0001/manifest.json`; final manifests and verification are reported in the completion handoff |

## Commands

Commands already executed before this record was finalized:

| argv / command | Exit | Log / manifest |
| --- | ---: | --- |
| `sbt --batch "raiderCli/testOnly raider.cli.chat.ChatWorkflowVerifierE01Spec"` | 0 | `artifacts/rai-155f/e01.log`; 1 passed, 0 failed |
| `sbt --batch "raiderCli/testOnly raider.cli.chat.ChatWorkflowVerifierE02Spec"` | 0 | `artifacts/rai-155f/e02.log`; 4 passed, 0 failed |
| `sbt --batch "raiderCli/testOnly raider.cli.chat.ChatWorkflowVerifierE03Spec"` | 0 | `artifacts/rai-155f/e03.log`; 2 passed, 0 failed |
| `make raider-jar` | 0 | Packaged CLI at `modules/cli/target/scala-3.9.0/raider-cli.jar`; hash `7044355f11e688d1261400a887560ef2502aa6c0` |
| `make quality-changed` | 0 | Classified the added walkthrough files; docs fast check passed; manifest `artifacts/quality/20261017-234843-56297-0001/manifest.json` |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | Static+docs passed; manifest `artifacts/quality/20261017-234844-56510-0001/manifest.json` |
| `make quality-profile PROFILES="static docs"` | 0 | Static+docs passed in same manifest above |

This record is finalized before the final required gate sequence. The final
`make quality-changed`, `make quality-changed QUALITY_EXECUTE=1`, static/docs
profile, and `quality.py verify <final manifest>` commands must be executed
without editing this record afterward. Final manifests and exact verify output
are in the completion handoff.

## Limitations / blockers

- Model responses were scripted through a local OpenAI-compatible stand, not generated by a live model. This demonstrates workflow behavior, not model quality. No paid calls were made.
- The product local launcher was exercised as a fresh Java subprocess inside the E01 verifier's test runtime classpath. The generic launcher was exercised as a fresh non-TTY product `workflow run` subprocess. The guide distinguishes this captured test execution from endpoint-specific user setup.
- Arbitrary exported Scala is trusted and not sandboxed. There is no durable resume or exactly-once claim for arbitrary Scala effects.
- Lane verification is not integrated-candidate acceptance. Independent review and coordinator integration remain pending; no push or merge to `main` was performed.

## Review and next task

Independent review is pending. After coordinator review/integration and integrated-candidate gates, the next available work is the remaining RAI-155 parent acceptance/related queued work; this lane does not change board status. No push or merge to `main` is authorized.

Product-input hash and operational-record hash differ under the reviewed policy. Changes to code/spec/build/fixtures require affected reruns; updating a board/record line does not rebind an old manifest to the new source tree. A lane report applies to its snapshot; final acceptance applies to the integrated candidate.
