# RAI-155.e: work record

Status: partial — re-gates green; acceptance completeness requires reviewer confirmation. Date: 2026-10-17. Lane: `lane/rai-155e`. Pre-merge HEAD: `1598861f827dbcb6c4bb12fca7e2b2e2d1070e62`; requested `main`: `46dc1401596e9fb8b3b1a3b331aedee93c9bbb55`.

## Resulting behavior

- Merged `main` with a clean fast-forward (no conflicts). This brought RAI-162's WorkflowRun SIGTERM cleanup, updated E01/E02/E03 expectations/specs, the tracked verifier corpus, and RAI-137/161 quality runner changes. No product files were changed by this re-gate.
- E01, E02, E03, and corpus tests pass; E03 passed in the complete verifier run and in three further consecutive standalone executions.
- All requested quality commands pass. Neither documented exception (MJE-03c or TAB-02) failed in the profile run; no exception was needed. There were no skips or timeouts.
- No paid provider calls. E01 uses the explicitly local scripted OpenAI-compatible stand.

## Changed paths

- `docs/work/records/RAI-155e.md` (this evidence and completeness record only).
- Product and verifier changes are from the fast-forward merge of `main`, not edits in this lane.

## Acceptance evidence and completeness matrix

| Card / roadmap obligation | Current coverage and result | Remaining issue |
| --- | --- | --- |
| CWF-E01: real chat capture, explicit selected multi-turn task, export, manual source edit, pinned compile | Green. Four captured turns traverse the local Scala SSE stand; the three-turn selection excludes the ordinary turn. Export and compile make zero stand requests. Export re-exports byte-identically. Both prompt token and trusted check argv are edited. | None observed in the frozen CWF-E01 assertions. |
| CWF-E01: independent failure before agent work / success after repair; real effect | Green. Independent check fails before agent work and passes after; scripted stand causes actual tool effects. | None observed. |
| CWF-E01: four-cell 2-input × 2-launcher matrix on fresh copies | Green. Two inputs and `local-main` / `raider-workflow-run`, four fresh workspaces; normalized outcomes match per input and inputs differ observably. Same edited workflow/build identity is checked. | None observed. |
| CWF-E01: fifth unedited control | Green. Fifth fresh workspace runs unedited source for input 1; both runs succeed and report/effect/source identity distinguish the manual edit. | None observed. |
| CWF-E02: negative corpus | Green for frozen corpus cases: absent/lost selection context, secrets at select/export, unsafe export paths/targets/remnants, invalid edited source and input, symlink workspace, missing credentials, bogus success/failed check on both launchers, hostile prompt content inert as data, stale source/build identity, corrupted classes. | **Card's “changed verifier/test” negative is not independently established by the visible CWF-E02 case: the stale-build test establishes edited workflow source/build mismatch, not an explicit mutation/change to a verifier/test input.** Requires independent reviewer disposition or a scoped verifier addition; expectations have not been weakened. |
| CWF-E03: interrupted capture/export snapshot/concurrent export/no retry | Green. In-flight turn is Unknown and retained, completed prefix is selectable, no redispatch; streaming export matches quiescent snapshot; concurrent distinct-target exports match and staging remnants are refused. | None observed. |
| CWF-E03: fresh-process SIGTERM, bounded truthful artifact, exit 143, no success/retry; deterministic repeat | Green. Expected exit, incomplete artifact/interrupted state, never Succeeded, no auto retry; deterministic run/export comparison. Full verifier plus three additional passes. | None observed in test runs. |
| Corpus integrity | Green; exactly frozen expected files, documented normalization/prerequisites, E02 negative corpus declaration, all expectation IDs parse. | None observed. |
| Independent review / integrated acceptance | Not performed by this worker. | Required before acceptance. Lane evidence is not integrated-candidate evidence. |

Roadmap §6d's explicit demonstration elements are covered by E01: genuine chat entrypoints/local stand, four fresh matrix copies, fifth unedited control, independent pre-repair failure, post-repair passing checks, and same edited program on local and non-TTY CI launchers. CWF-E02 and E03 add the negative and cancellation/concurrency checks. The only identified acceptance subcase gap is explicit changed-verifier/test detection (distinct from stale edited-source detection); CWF-E01..E03 are otherwise green. RAI-155.f walkthrough remains downstream and is not this card's acceptance.

## Commands

| argv / command | Exit | Log / manifest |
| --- | ---: | --- |
| `git merge main` | 0 | Fast-forward `1598861` → `46dc140`; no conflicts. |
| `sbt --batch "raiderCli/testOnly raider.cli.chat.ChatWorkflowVerifierE01Spec raider.cli.chat.ChatWorkflowVerifierE02Spec raider.cli.chat.ChatWorkflowVerifierE03Spec" "raiderDsl/testOnly raider.dsl.workflow.ChatWorkflowVerifierCorpusSpec"` | 0 | E01/E02/E03: 6 tests passed, 0 failed; corpus: 4 passed, 0 failed. |
| `sbt --batch "raiderCli/testOnly raider.cli.chat.ChatWorkflowVerifierE03Spec"` (run 1) | 0 | 2 tests passed, 0 failed. |
| Same standalone E03 command (run 2) | 0 | 2 tests passed, 0 failed. |
| Same standalone E03 command (run 3) | 0 | 2 tests passed, 0 failed. |
| `make quality-changed` | 0 | Clean tree vs HEAD; minimum `fast` selected. |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | `artifacts/quality/20261017-193605-94770-0001/manifest.json`; status passed, 10 passed/0 failed/0 skipped. SHA-256 `d551bd19fef275df0560050f397bd973e91e9999892b2ee41d9a5788b513d72e`. |
| `make quality-profile PROFILES="static unit contracts ci"` | 0 | `artifacts/quality/20261017-194401-4214-0001/manifest.json`; status passed, 6 passed/0 failed/0 skipped. SHA-256 `6c5ef1925769eecab4c6985bcce3153bce0272ebd989b5800fcc454bf1f4a9a6`. |

## Fingerprint and gate hashes

- Both quality manifests record the same product-input fingerprint: `86f5f04449098ccca74de692854fbadb6754e91378643dd490e5b4076b2a4483`.
- Gate policy SHA-256: `9122e5ad9c8abcc021c49df1905c34f9c7508c66cad93fc41f6d72dd7e20c775`.
- Execute-fast manifest gate log SHA-256s: self-registry `cf0ce866958eb6147d1a4892b071f5480bacfa0f9a7998de69e1555635c59afa`; compile `1dff74a02137e467ba55b6a2bc74a4b20852774f5b6f107d0e753e793825b484`; formatter `b5ed0f5203a34bc4e93917a5cd7529a5cef41ce83ed131e55457b6775231a290`; scalafix `02b7e8433550fed617c52559cc5665c130bd78dc1167baee5d9a2aea6ea0c5e3`; forbidden APIs `ec7800dcf7787193885fbaeb11904947eb943d85d3056d1683ad2a473c2665`; license `63ff8c55793c0f21f6a90b807a904c5181009d370ba8286d70386d70ad1b84df`; core contracts `b52122ecec408e645e8c3fc76cf51b1a58d400e3facb59976b8698f541c72f38`; compile fixtures `ec0ed8968c74df8169e9257b47406cf26decf8e0436f12ded8f3779f3fbabd42`; plan validation `ff0807c1d15997221ccf0f8b4a45aaf510abb05d0d279215b0ae5a266194be69`; tests `db7b92b5520860cd7e671117a07ab181d899bb3b07b6498d1e377be32816d394`.
- Static/unit/contracts/ci profile log SHA-256s: tests `1c169a812666a04d3827c61f696b6e36b2e54cbc9d598ddbfab98cae70bfa64f`; CLI smoke `f29248cbceed28199489537a717a32008dd0919b46fe126f93731ec373be17c1`; core contracts `b47e122c3521f657ad9b9d7e004ce621670b3230e6e41d9716bbdd18369f5d`; compile fixtures `ec0ed8968c74df8169e9257b47406cf26decf8e0436f12ded8f3779f3fbabd42`; compile `8c58886c0c0528082d8cd7821448b0a4c81e96b82baf72656d0df8d996968fe3`; formatter `9135107ae365a87a5ca86c13b50b3bb187d667acb9ed020c2179d304897205ad`.
- Included verifier/corpus SHA-256s: E01 expectations `b992824261bb5d130f769cbe3f7318c4ccb042ddede9751d30afda55477fa470`; E02 expectations `38b80225699cdd20b0682d3122b71131db726ef0c032f67989d08ac2d479e0b9`; E03 expectations `8ef121409a757a24a76f23d156d803ce2aa4154568f34a33b36302a8d0098611`; E01 spec `c71b02e018be64cb74cc63dd468a283130dd812e87e1a8b4700a5dd540fdcdf1`; E02 spec `844c94e5fc2e580f7b2c8b7cd529b0edadbbcde51a5e50213e5bd0228e04184c`; E03 spec `533d8a23bbcbd584767745e1f77049d737ab9f9c3d2551879658404e4f67b6a9`; harness `f5570e707b39309a26f9382d34fde516d053a07bc53ece2b3737c1c055bcd29b`; corpus spec `86587329db96c11ee3a97e04e54feca0cfedf4970108290919f928f8d5fc9551`.

## Limitations / blockers

- No test failed, skipped, or timed out. MJE-03c/TAB-02 documented exceptions did not occur; no checks were waived.
- Explicit changed-verifier/test tamper detection is not shown by the corpus case inventory (stale workflow source/build identity is tested). This is the concrete acceptance gap pending reviewer disposition or a separate authorized verifier change.
- Fresh independent review is still required. These manifests belong to the lane snapshot, not the eventual integrated candidate.

## Review and next task

Verdict: **gaps remain** — explicit changed-verifier/test detection and independent review are outstanding. Next: reviewer should disposition the E02 scope gap; if confirmed, register/authorize a narrow verifier case change (do not change expectations without evidence), then rerun affected verifier and gates. After RAI-155.e is independently accepted, RAI-155.f is the next chain card.

Operational record is excluded from the product-input fingerprint per the reviewed allowlist; the record itself has changed, so its audit hash is recorded by Git at commit. Product code, acceptance fixtures, and gate policy were not edited during this re-gate. Any subsequent verifier/corpus change requires fresh affected evidence. No push or merge to `main` performed.
