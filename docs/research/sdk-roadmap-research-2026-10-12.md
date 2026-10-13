# Raider SDK: research into a Scala DSL for AI-native SDLC

Date: 2026-10-12. Raider baseline: `3a60f93` (product inputs inherit from
`4b46dc7`). This is planning evidence, not API implementation, comparative
benchmark, or release.

## 1. Owner decisions and research question

The owner confirmed in an interactive session:

- Raider + SDK, fully OSS; current wave continues; gateway is P11.
- First success: write a Scala workflow and run it locally and in CI.
- First CI is a local generic runner; system not selected, not GitHub Actions.
  Forge is also unselected: first a general contract and local Scala stand.
- Main differentiator: a convenient DSL for AI-native software development life cycle.
- First project: `../tokenrouter` (Yabanin), specifically development of its
  code; using it as an LLM gateway remains a separate, later integration.
- Target cycle includes PR creation, automatic commit/merge, version/changelog,
  tag, and building release artifacts. Production deploy is out of scope; building
  artifacts does not imply package/container publishing.
- Release tag is created locally only, without push; publishing the tag is a
  separate later step.
- The human sets checkpoints in the Scala workflow; one mandatory universal
  approval mode is not prescribed.
- Preview may ship before M4 with an honest acceptance scope. Scala 3 + ZIO and
  current pinned toolchain; API may change between preview releases with migration.
- Crash resume is not needed for the first SDK: interrupted/unknown and an
  explicit new run.
- Detailed-record policy is deferred; preview is for trusted local data.

Method: read actual code and contracts; independent visible spawn audit/review;
official docs/repos for competitors and platforms. Competitors' libraries were
not run; no paid calls. External sources checked 2026-10-12; their current docs
do not prove that every feature is available in a released artifact.

## 2. What the nearest alternatives offer

| Project / primary source | Documented surfaces | Implication for Raider |
| --- | --- | --- |
| [LLM4S ZIO](https://llm4s.org/guide/zio), [agents](https://llm4s.org/guide/agents/) | ZIO wrappers/streams, cancellation, agents/tools, and structured workflows | A Scala/ZIO facade is no longer sufficient differentiation |
| [LLM4S migration](https://llm4s.org/reference/migration.html) | Durable/graph runtime described in current development; some changes explicitly marked unreleased | Do not conflate development API and released capability in comparisons |
| [llm4zio](https://github.com/riccardomerolla/llm4zio) | ZIO embedding, scala-cli flows, plan/implement/review, Git/CI helpers, events, and resumable plans | Closest overlap with SDLC DSL; evaluate ergonomics for a concrete workflow |
| [LangChain4j agents](https://docs.langchain4j.dev/tutorials/agents/) | Experimental agentic module, workflow patterns, AgenticScopeStore, checkpoints, and planner-position recovery | Workflow recovery exists in alternatives; source-tree inspection does not exhaust their capabilities |
| [Koog](https://docs.koog.ai/), [persistence](https://docs.koog.ai/features/agent-persistence/) | Kotlin strategies/tools, structured output, checkpoints, and recovery | JVM/typed workflows and persistence are not unique on their own |
| [LangGraph persistence](https://docs.langchain.com/oss/python/langgraph/persistence), [interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts) | Checkpointers, stores, and human waiting; resuming a node re-runs code up to the interrupt | Explain clearly the difference between checkpoints, replay, and repeated side effects |

The matrix reflects documentation, not quality rankings. A feature not mentioned
here is not necessarily absent. In particular, LangChain4j documents workflow
recovery; that does not imply deterministic replay or exactly-once guarantees for
external mutations. Competitor dependency versions are not pinned for execution;
execution comparisons need a separate pinned protocol.

Working positioning hypothesis: **Raider lets users express their own AI-native
SDLC in ordinary Scala, debug that same workflow locally, and run it in CI with
verifiable transitions between plan, code, checks, review, and integration.**
Runtime ownership/budgets/cancellation support this promise. Its value will be
tested on TokenRouter; superiority over alternatives is not yet proven.

## 3. Technical findings that affect work order

Full source inventory and the ADR packet are the result of RAI-115,
`docs/research/sdk-embedding-audit.md`. Key baseline points:

| Fact | Source | Planning decision |
| --- | --- | --- |
| `Task[A]` contains `ZIO[Any, RaiderError, A]`; `Program[I,O]` is typed | `modules/core/.../task.scala`, `program.scala` | Do not promise generic ZIO environment/error types; choose host-service passing in an ADR |
| Convenient facade blocks via `Runtime.default` and contains registries | `modules/repl/.../facade/FacadeOps.scala`, `ReplSession.scala` | Do not export the whole REPL as SDK; separate lifecycle/config/launch boundary as needed |
| Bundle v1 accepts `Program[String,String]` | `modules/core/.../bundle.scala`, `modules/cli/.../run/BundleLoader.scala` | Be honest that preview has a string external boundary; typed internals are not a typed wire; codecs/version change are separate contract scope |
| Bundle path admits model externally; native loop admits internally | `modules/cli/.../run/HeadlessRunner.scala` (`execute`, admission note) | Shared workflow needs one root/ledger check without double admission, especially maxLLM=1 |
| By default, `AgentLoop` gets Unknown estimate and settles to `MicroUsd(0)` in current path | `modules/runtime/.../loop/AgentLoop.scala` (`attemptEstimate`, `runRound`) | A ledger does not prove provider monetary accounting; verify claimed caps/unknown/refunds separately |
| Whole project build pins Scala 3.9.0 | `build.sbt` | First preview stays on current toolchain by owner decision; do not claim support for older Scala 3 |
| Scala lineage projection and replay have different implementations | `modules/dsl/.../LineageProjection.scala`, `modules/cli/.../lineage/*` | Compiling a graph does not replace running a newly authored workflow |

Scala TASTy backward compatibility means a library built with a newer minor
compiler is not automatically available to a user on an older compiler. This is
a constraint on the first support matrix, not a reason to urgently change the
REPL compiler. [Scala compatibility](https://docs.scala-lang.org/scala3/reference/language-versions/binary-compatibility.html).

A Maven artifact requires more than a JAR: resolvable dependencies, metadata,
sources/docs, and signing for the selected public registry. Validate through an
isolated local repository before public publishing; namespace/credentials/release
owner are not yet defined. [Central requirements](https://central.sonatype.org/publish/requirements/).

## 4. Target workflow-author experience

First, the author describes one scenario using ordinary `Program`, `andThen`,
`all`, `batch`, and Scala functions; SDLC helpers appear only around repeated
domain steps. No new parser/macros or second graph scheduler is needed.

Proposed process shape (not an implemented API signature):

```text
task + repo baseline
  → requirements / plan
  → configured human checkpoint (if enabled)
  → implementation in isolated workspace
  → trusted checks → bounded repair
  → independent review → bounded rework
  → commit + PR → integration checks on candidate
  → configured merge policy → merge
  → version / changelog / tag / release artifacts
```

Each transition receives actual input and a typed result, including
failure/needs-approval/uncertain. Review does not replace deterministic checks.
Repair/review loops have attempt/deadline bounds and a shared root budget.
Commit/merge/release are allowed by explicit policy referring to a verified
candidate, not a model's “done” text. These are requirements for future ADRs and
acceptance, not a new DSL surface.

Ergonomic criteria: author can replace a stage, add a tool, configure a
checkpoint, bound a repair loop, inspect intermediate results, and run the same
file locally/in CI without duplicating orchestration. Measure independent
completion of these actions and clarity of errors; line count alone is not a gate.

## 5. Automatic integration and release

Automatic commit/merge is a confirmed goal. Acceptance should verify:

- Link between task/run, source revision, candidate tree, checks/review, and commit SHA.
- One integration owner; target/head movement, conflict, and changed candidate
  require an appropriate new check; an old pass is not attached to another tree.
- PR/status/merge adapter respects actual permissions and branch rules. GitHub
  allows an expected head SHA at merge, but that does not pin the base by itself;
  an integrated candidate/merge queue or equivalent check is needed separately.
  [GitHub merge API](https://docs.github.com/en/rest/pulls/pulls),
  [GitLab merge API](https://docs.gitlab.com/api/merge_requests/).
- After timeout following external mutation, first reconcile observed state
  read-only. Do not blindly retry commit/PR/merge/tag or promise exactly-once.
- Version/changelog/tag and release artifact are tied to accepted commit and
  verified revision. Version/tag conflict, failed build, and cancellation have
  explicit outcomes; a new run does not overwrite an existing release.
  Publishing/deploy are out of scope.

Git/forge/release steps are domain tools/adapters over the same runtime; external
CI orchestrator continues to manage jobs, approvals, secrets, and artifact
publishing. Do not create a second CI scheduler. Initial acceptance uses
disposable local Git repos and local Scala HTTP forge stand; real TokenRouter is
changed only under its own card and rules.

Workflow/tool policy is not a sandbox for Scala or repository code being run. A
secret-bearing job must not run untrusted fork code; integration permissions and
checks are separated through CI environment. This is a specific CI-scenario
boundary, not a promise of complete isolation inside the SDK.
[GitHub trust guidance](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target).

## 6. Human checkpoints without durable resume

Checkpoint configuration belongs to the Scala workflow author. A local process
may wait for input with deadline/cancel. Headless path must emit a versioned
needs-approval artifact and clear exit, or receive a pre-authorized decision
from trusted invocation context. Long human waits do not require a live JVM.

After process completion this is a **new invocation**, checking task/base/
candidate and input artifacts, with a new root ID linked to the prior run; no
promise is made to continue an arbitrary Scala fiber. Read-only preparation may
be repeated; before mutations, check postconditions and known/unknown outcome.
Exact approval artifact form and invalidation belong in an ADR, not an existing
contract.

AWS AI-DLC describes phased work and human validation as a methodology. We use
it as input for stages, not to impose mandatory AWS approvals on the configurable
model selected by the owner.
[AWS AI-DLC](https://aws.amazon.com/blogs/devops/ai-driven-development-life-cycle/),
[adaptive workflows](https://aws.amazon.com/blogs/devops/open-sourcing-adaptive-workflows-for-ai-driven-development-life-cycle-ai-dlc/).

## 7. Delivery, measurement, and estimate limits

First delivery is an experimental **workflow-authoring preview**: source → local
run → compiled bundle → headless CI on one native runtime. Clean host consumer
SDK-01/02 remains a separate P4 outcome; plugin UI/M2 and full M4 do not block
feedback on the accepted narrow preview. Preview does not complete a milestone.

Full SDLC is added in sequential accepted slices: plan/code/checks/review, then
commit/PR/merge, then release artifacts. Owner's end goal remains; the reduced
preview is not called a complete cycle. Detailed guides remain later work, but an
example and one reproducible command are required immediately.

The first evaluation has a concrete participant/project: owner and TokenRouter.
Measure setup failures, Raider author assistance, time to useful change, trusted
checks/review verdict, repair attempts, known/unknown cost, human wait time,
merge/release outcome, and repeat use. External OSS users are evaluated
separately; dogfooding does not prove external demand.

A useful stop/review trigger before expanding SDK surface: after one onboarding
pass nobody completes the target task independently, or a successful first run
does not lead to a new task — investigate causes before expanding API. This is a
product decision point, not a way to call engineering tests green.

13,100 h remains a historical envelope. A fuller SDLC with forge/release adds
work to the original “patch + checks”; it cannot be treated as free work absorbed
by P7/P10. RAI-115 packages/estimates are the initial SDK estimate; a new SDLC
contract packet will separately estimate integration/release, fixtures/review,
and maintenance. Accepted plugin/runtime slices are excluded from remaining
estimate. Team, actual used/reserved hours, and calendar remain unknown; no new
totals are approved.

## 8. Next decisions and cards

1. RAI-115 source audit accepted after independent review (`c156f40`, merge `e2deeb3`).
2. RAI-116: separate workflow-authoring/launch-boundary ADR (P0.B), independent
   of the previously accepted UI seam. Include string bundle/typed internals,
   host scope, single admission/budget, error/cancel, consumer classpath, and
   preview compatibility.
3. RAI-117: separate SDLC contract/acceptance packet for TokenRouter: stages,
   configurable checkpoints, checks/review, commit/PR/merge, and release
   artifacts; platform/repository rules, effects reconciliation, separate estimate.
4. Before RAI-117 acceptance, coordinator registers case→phase→implementation/
   verifier card IDs with owners, dependencies, exact allowed paths, and estimates;
   implementation is split into 8–24 h packages. M3 requires WF-01/02 +
   SDLC-01–04 on local stand; M4 also requires SDLC-05; real forge PR/merge
   remains pending until an adapter is selected and accepted. Build/API/gates
   have one owner; protected verifier changes get their own card and review.
   Current wave continues.

Do not invent these unresolved details: selected forge/CI engine, current remote
branch rules, release namespace/maintainer, available engineering budget,
supported headless approval artifact schema. Unselected CI/forge do not block
the contract or local stand, but do not support claims of real PR/merge.

## 9. Read-only discovery of TokenRouter as SDLC consumer

Path: `../tokenrouter`. HEAD `91e6ce0fd293f69bdea9f9a8b9876f32da35451f`,
branch `master`; checkout was dirty before research. No files or branches were
changed; no builds/tests were run. This is code-development discovery, not
gateway API/P11. Current working versions of the following files were examined;
only two of these seven differ from HEAD (other dirty project files were also
preserved):

| Input | SHA-256 working bytes | Dirty |
| --- | --- | --- |
| `AGENTS.md` | `44a7366a481373ce22f30d123e7b66271ce39fe5def21167ceb903eff5f8ff18` | yes |
| `Makefile` | `a93bb42cd6044ee4f5e103c77c11f585c006f6cea819d9a94e6b27f378d0a7b7` | yes |
| `CONTRIBUTING.md` | `13229874ceb09a0d6cc1324ecf73527c3b5007f495c988f4ce2be957b9dfe78f` | no |
| `go.mod` | `255281ad1036687834921de8608bf0f137634b76970fba0c2377fc776f016e56` | no |
| `.github/workflows/ci.yml` | `d7261ab80698ff0314f6d3659705b327c149adab926461ec4931a145be767f02` | no |
| `.github/workflows/release.yml` | `c736428be63fece3171b79251bfeea31ca449bab6dff912899df2afbc3e96d2e` | no |
| `scripts/release/release-artifacts.sh` | `80be562a18d6d35a65097c18d79e6c1ee34751c01ae5c16ede052643d0bbb967` | no |

Observations for a future adapter, not commands executed in this audit:

- Project is primarily Go, with Python/Node SDKs and Playwright; Raider DSL
  remains Scala/ZIO. TokenRouter changes must follow its own language rules.
- `AGENTS.md:12` and `Makefile:253,420`: first run `make quality-changed`, then
  `make quality-changed QUALITY_EXECUTE=1`; for committed work pass original
  task base as `QUALITY_BASE=<base>`. Evidence is
  `artifacts/quality/<run>/manifest.json`, checked with
  `python3 scripts/quality/quality.py evidence <manifest> --require changed`
  (and `--base <base>` for committed work). This differs from Raider runner API.
- `CONTRIBUTING.md:5` requires lint/test and review of dependency/security
  changes. DCO procedure is marked DRAFT, while an owner decision on sufficient
  sign-off is recorded separately; integration contract must clarify current
  identity/sign-off requirements, not invent contributor consent.
- `.github/workflows/ci.yml` contains Go/race/lint/security, SDK/docs/Helm, and
  integration jobs. Their presence does not select GitHub Actions for Raider:
  owner selected local CI.
- `Makefile:68` / `release-artifacts.sh:15`: four binaries, SHA256SUMS, SPDX via
  Syft or go build manifests, optional signing, checksum/build-info smoke.
  VERSION comes from `git describe --tags --always --dirty`, commit from HEAD.
  Command cleans part of output directory; use separate `RELEASE_DIR` for
  candidate. It does not create version/changelog/tag itself.
- `.github/workflows/release.yml:1,18,134`: pushing `v*` publishes GHCR images
  and starts staging deploy. After this finding, owner selected **local tag only**.
- Current target rules prohibit push/publication; future approved PR/merge
  capabilities need a separate TokenRouter card with exact scope. Raider work
  does not authorize changing those files now.
- Remote rules/required checks were not verified: `gh` is unauthenticated. This
  is unavailable discovery, not evidence that branch protection is absent.
  GitHub origin does not select the first forge; its adapter is deferred.

This map of requirements supports designing a generic task/check/artifact
protocol now. An actual target run needs a new immutable working copy, fresh
reading of repo rules, and actual gates; the current dirty checkout cannot be
used as a clean fixture.
