<!-- Translated from Russian original. Key terms preserved as-is. -->

# Raider in CI: primary product contract

All APIs and commands below are designed, not implemented. This document clarifies the [DSL](product-repl.md) and takes precedence for headless invocation. All three scenarios are in scope: PR/MR review, preparing a patch with checks, and arbitrary research/generation/check workflows.

## 1. Boundary with the orchestrator

Raider runs one agent workflow inside one CI job. GitHub/GitLab/another orchestrator manages the DAG between jobs, runner allocation, secrets, matrix, manual approvals, artifact upload, and job retries. Raider manages children within its process, tools, deadline, and local root budget. A second GitLab/GitHub scheduler is not needed.

One Program runs from the REPL and from a compiled bundle. `pipeline[I,O]` is a Workflow factory with versioned input/output codecs, required checks, and a declared success policy; it is not a separate runtime. It is available in the MVP together with `workflow`; typed agents with schema repair are added in beta. In M0, schema decoding of input/output and deterministic checks are already required.

## 2. Convenient DSL

In a definitions file:

```scala
import raider.dsl.*

val inspect = (agents.scout andThen agents.reviewer).named("inspect")
register(inspect)

val audit = pipeline[String, String]("audit"): request =>
  for
    observations <- all(agents.scout(request), agents.reviewer(request))
    report <- agents.writer(s"$request\n${observations._1}\n${observations._2}")
    _ <- verify("report-format", report)
  yield report
register(audit)
```

`pipeline` requires implicit `Codec[I]`/`Codec[O]` and an immutable `DefinitionContext`. `verify(name, output):Task[Unit]` calls a trusted registered verifier with its declared input codec; failure returns structured `CheckFailed`, with no success string from the model. The runtime stores check evidence outside the mutable agent workspace. `check(name):Task[CheckResult]` runs a pre-registered deterministic workspace check; an arbitrary command string from the model is forbidden here. If a check failure is not converted by an explicit advisory policy, the root fails. The registry is frozen before dispatch.

Locally:

```scala
audit.ask("Review the change")
val job = audit.start("Review the change")
job.await
```

In a library/CI, `audit(input):Task[String]` runs through the Runner; the blocking REPL facade is not imported. Composition, child ownership, tools, policy, and output codec are the same.

Review receives a ChangeSet/diff context and returns findings with severity/path/evidence. A patch workflow receives a Spec and returns a patch artifact and actual checks. Research receives a Question and returns an output artifact and source references. Domain codecs and examples are added in separate cards; the sketch below assumes the domain types have been created:

```scala
val repair = pipeline[Spec, Patch]("repair"): spec =>
  for
    plan <- planner(spec.prompt)
    patch <- patcher(plan)
    _ <- verify("allowed-diff", patch)
    tests <- check("regression")
  yield patch
```

`tests` is evidence of a real check. In the MVP, user-facing string presets remain available for convenience; a domain `patcher:Program[String,Patch]` is created with an output codec; typed model repair is RAI-028. `.ask/.start`, `andThen/all/batch`, and `scope/fork/join` remain the only execution/composition verbs. Do not introduce a second stage/job/YAML language for internal agents.

## 3. CLI and packaging

For an ordinary task, `raider run --agent worker --input "..."` is also provided through the shared Runner; it is mutually exclusive with the bundle/workflow path. For UX and required readiness details, see [working product](working-product.md).

```text
raider compile workflows.raider.scala --output pipelines.jar

raider run --bundle pipelines.jar --workflow audit \
  --input "Review the project" --ci generic --context ci-context.json \
  --profile yabanin --policy ci-readonly --out artifacts/raider

raider run --bundle pipelines.jar --workflow review \
  --input-json input.json --ci auto --policy ci-readonly --out artifacts/raider
```

`--input` is only for a string codec; `--input-json` reads bounded versioned JSON. Mutual exclusivity is checked before a provider call. Do not read stdin by default. `--mock` replaces the backend with fixtures and is explicitly recorded in artifacts. `--out` is required in CI and has a documented default outside CI. Do not guess the workflow when multiple workflows are registered.

Two distributions: `raider-run` contains core/runtime/headless CLI; `raider-dev` adds compiler/REPL/JLine. The production runner does not include the compiler and does not resolve dependencies from the network at runtime. The source shorthand `raider run workflows.raider.scala ...` is allowed only in the dev distribution and explicitly reports the compilation phase.

Bundle manifest: schema, Raider API/Scala/JDK compatibility, registered names/codecs, source/dependency/toolchain fingerprint, and profile/verifier declarations. The JAR SHA-256 is checked before loading. A bundle is executable, trusted JVM code; the hash confirms integrity relative to a trusted expected hash, not trust in an unknown source. Pin the artifact digest and provenance in pipeline config; an artifact from a fork does not become a trusted bundle.

Preflight order: arguments → bundle/context/input schema → trust/policy/capabilities → output-directory writable/space probe → provider profile/credentials → root creation/dispatch. An unsupported required capability blocks the run before spend. The reporter records preflight failure where possible; absence of a root is represented as null, not as a fabricated job.

Invalid output-path syntax/config — 21; filesystem permission/space/write failure for required artifacts — 27, including the preflight write probe. The probe does not guarantee that the disk remains available; final writes are checked too. `--profile` selects named role bindings/defaults; resolution follows the [provider contract](provider-protocols.md). An explicit Agent provider/model pair is preserved; roles are resolved in the selected profile, and each resolved provider is fixed before dispatch. A mixed-provider workflow does not silently change endpoints after launch.

## 4. Normalized CI context

Versioned schema: `schemaVersion`, `system`, `instanceId`, `repositoryId`, `repositoryUrl`, `pipelineId`, `jobId`, `jobName`, `matrixKey`, `attempt`, `eventType`, `headSha`, `baseSha`, `ref`, `changeRequestId`, `runnerIdentity`, `declaredTrustLevel`. Required generic minimum: system/instance/repository/pipeline/job/attempt/trust; optional values are nullable, not fabricated non-empty IDs. Identity fields are bounded/validated; labels are handled separately as a subset.

`--ci auto` selects a platform adapter from the environment and requires an explicit choice in case of conflict. Environment detection does not raise trust. `declaredTrustLevel` is set by trusted owner policy; repository input cannot declare itself protected. SHA/diff are obtained from the verified checkout/context and retain provenance; never interpolate a PR title/ref blindly into a shell.

Each invocation receives a new RootId. The logical key `(instance, repository, pipeline, job, matrix, attempt)` links analytics. `GITHUB_JOB` is insufficient to distinguish matrix entries; `matrixKey` must be passed explicitly. A retry creates a new execution and incurs new spend; the logical key is not an exactly-once token.

## 5. Policy and execution of repository code

`ci-readonly` restricts model-directed writes/publish/network/tools. For a patch scenario, the owner selects `ci-patch` with workspace/diff bounds. Commit/push/PR-comment/deploy are not part of the baseline; a publisher is separate, with credentials and approved actions from CI configuration.

The pipeline bundle, policy, and verifiers come from a protected/pinned source, not from a branch workspace that can be rewritten. Agent output/diff and repository instructions are untrusted data. The model cannot change the success policy, artifacts writer, verifier executable, or budget ceiling.

Running repository tests executes its code. Untrusted code must run in a credential-free isolated worker/container without access to runner/gateway secrets, host mounts, or privileged sockets. A stripped environment helps but is not a host sandbox. The process tool without sandbox capability is not allowed for a required-untrusted-execution profile. A JVM plugin/Scala bundle has the permissions of the Raider process: do not assume that tools policy constrains arbitrary Scala code.

Fork PR pipelines do not receive provider/publish secrets. A mock is available for a free deterministic preview. Live review of fork data is allowed only through a separate owner-controlled service/job with trusted code and an isolated data path. Do not run fork scripts beside privileged `pull_request_target` credentials. See the [GitHub security guide](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target).

## 6. No terminal, cancellation, and deadlines

Headless mode does not ask questions, require a TTY, or block on stdin. Policy `ask` becomes NeedsApproval; the owner can arrange a manual gate externally and start a new invocation with a narrowed approval capability. The result of waiting does not automatically authorize the action.

SIGINT/SIGTERM stops admission and cancels the owned subtree, HTTP streams, and process groups. Cleanup has a bounded grace period; interrupted outcome/accounting are saved on a best-effort basis. Launcher/container forwarding is checked with a real process. The internal deadline is shorter than the CI timeout, leaving time for cleanup/artifact upload. Grace/deadline parameters are validated before dispatch.

SIGKILL, OOM, and runner removal make final artifacts impossible to guarantee. Periodic journal/progress is useful, but absence of a complete result always means incomplete. CI retries only when permitted by task policy: a mutation/Uncertain outcome is not replayed automatically. A shared authority is required for a common cap across matrix jobs; a local root limit does not provide a pipeline-wide hard cap.

## 7. Artifacts

| File | Contract |
| --- | --- |
| `run.json` | schema/RootId/context/bundle-policy hashes/execution/verdict/accounting/timestamps/errors |
| `result.json` | Validated output or structured failure; output codec/version; model output ≠ verifier verdict |
| `events.jsonl` | sanitized bounded versioned lifecycle/tool/check events; stream writer |
| `checks.junit.xml` | Actual executed checks: pass/fail/error/skipped, stable IDs, and escaping |
| `summary.md` | Compact human report: outcome/findings/patch/checks/usage uncertainty |
| `artifacts.json` | Output files manifest: sha256/mediaType/size/provenance; the manifest file itself is excluded from its self-hash |

Optional: `patch.diff`, SARIF schema-validated findings, `usage.json`, and an agent-eval directory. An empty JUnit suite does not pretend to verify task correctness; a minimal schema check is represented as a real check, and skipped checks are explicitly marked. A patch is applied only in an explicit downstream step after checking hash/baseSha.

Use atomic temp+rename for complete JSON; document recovery of an interrupted JSONL tail. The reporter has size/path bounds, XML/Markdown sanitization, secret redaction, and does not execute output strings. Do not include raw env, tokens, full prompts, or tool args by default. Event truncation/overflow is reflected in evidence; required checks must not be silently lost. Disk failure → nonzero, never green without artifacts.

`executionStatus`, `taskVerdict`, `safetyVerdict`, `protocolVerdict`, and `accountingStatus` are separate fields. Exit is determined by the declared pipeline policy: an advisory review may successfully produce a report with findings, while a blocking review ends as task-failed if a threshold is exceeded. The policy and threshold are frozen and their provenance is recorded. Never use “the agent said done → exit 0.”

## 8. Stable exit codes

| Code | Reason |
| --- | --- |
| 0 | Declared success policy satisfied, cleanup and required artifacts complete |
| 10 | Task/verifier/check rejection |
| 20 | Runtime/provider/protocol failure |
| 21 | Config/input/bundle/compilation/preflight-invalid |
| 22 | Policy/capability denial |
| 23 | Budget exhausted/required hard cap unavailable |
| 24 | Internal deadline exceeded |
| 25 | NeedsApproval in noninteractive mode |
| 26 | Side effect/cancellation outcome uncertain |
| 27 | Artifact/evidence write failure |
| 130 / 143 | SIGINT / SIGTERM handling |

When there are multiple causes, record primary and secondary errors. Preserve the signal code on signal shutdown; otherwise, artifact failure 27 takes precedence, followed by uncertainty 26, then the reason the root stopped. The wrapper must preserve the child code unless a separate accounting policy is documented. No catch-all exit 0.

## 9. Thin platform adapters

GitHub: normalize context; write a safe summary to `GITHUB_STEP_SUMMARY`; write only allowlisted scalar outputs through `GITHUB_OUTPUT` with correct framing. Do not turn raw model text into workflow commands `::...`. Default permissions are read-only; SARIF upload/PR publishing use separate optional credentials/capabilities. A reusable workflow/action is a wrapper around the CLI; pin dependencies/actions by SHA. The summary/output mechanism is described in the [official documentation](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-commands).

GitLab: normalize `CI_*`; provide a template with `artifacts:when:always`, paths, and `reports:junit`. Protected/masked variables are the credentials channel; never put secrets in dotenv artifacts. `retry`, `interruptible`, and `resource_group` are orchestrator parameters, not Raider APIs. Tier-specific MR widgets are optional; generic files/JUnit remain core. Supported report types are documented in [GitLab docs](https://docs.gitlab.com/ci/yaml/artifacts_reports/).

Other pipelines: explicit generic context JSON/CLI, output directory, and exit code. No hardcoded runner paths or dependency on Docker-in-Docker, GitHub API, or a paid GitLab tier. MVP portability targets Linux runners; macOS has the REPL/package contract; Windows is supported only after the suite passes.

## 10. Optional Yabanin wrapper-first integration

```text
yb ci exec --session=on -- raider run --bundle pipelines.jar \
  --workflow audit --ci auto --profile yabanin --policy ci-readonly \
  --input "Review the project" --out artifacts/raider
```

Raider works directly with OpenAI/Anthropic-compatible APIs without a wrapper/gateway: [provider contract](provider-protocols.md). If Yabanin is selected, use child-only `YABANIN_GATEWAY_URL`, `YABANIN_API_KEY`, and `YABANIN_LABELS`, as confirmed by the local Yabanin CI contract. With an inherited session key, Raider does not mint a second root key; the wrapper owns finish/accounting, and Raider retains local attempts/root labels. In standalone mode, Raider can own session mint/finish. There is one session lifecycle owner per root, stated explicitly in `run.json`.

The Yabanin accounting report does not replace the Raider task verdict. `yb ci report --fail-over-cost` is a post-hoc check, not hard admission. See the [integration contract](yabanin-integration.md) for details and actual gaps.

## 11. Required CI acceptance

1. The same bundle/input/backend fixture produces the same semantic result in generic/GitHub/GitLab contexts.
2. Closed stdin/noTTY/noANSI; no prompts or auto-allow.
3. Malformed context/bundle/input and policy denial do not make a model call.
4. Matrix/retry attribution is distinguishable; secrets are not exported in the environment.
5. SIGTERM from the parent cancels the subtree/process groups; partial artifacts/code are truthful.
6. Disk-full/permission failures in the reporter never return exit 0.
7. Fork fixtures cannot read provider credentials; protected verifiers are outside the workspace.
8. XML/Markdown/output command/path injection fixtures are serialized safely.
9. All exits and precedence pass table-driven tests.
10. An uncertain mutation is not replayed on restart/retry.
11. Headless packaged classpath/launch does not contain compiler/REPL/JLine and does not resolve dependencies from the network.
12. Real template jobs are checked on a clean runner; the presence of YAML alone does not count as acceptance.

M0 proves all contracts with the local/mock suite; actual hosted template readiness requires a separate run by the owner/on a stand and evidence. Ordinary development gates do not require a paid provider.

## 12. Chat-exported launcher artifacts (ADR-033 — designed, not implemented)

[ADR-033](adr/ADR-033-chat-workflow-export.md) (card RAI-155.a; proposal — no
export/compile/run command exists today) refines §3 for the RAI-155 chain:
the non-TTY launcher for a chat-exported workflow is the **launcher artifact
itself** (Option L) — an ordinary compiled Scala `main` that constructs the
ADR-029 §3.1 launch capability explicitly — not a v1 bundle entry
(`BundleLoader`/v1 SPI stay frozen and untouched; a v1 program cannot host
agent work). Consequently, when RAI-155.d implements it, the §3 sketches
`raider compile ... --output pipelines.jar` and `--bundle` execution are
refined as follows, and exact syntax is fixed by the implementing card:

- Same discipline as §3: pinned-toolchain compile, preflight order
  (arguments → context/input schema → trust/policy → output-dir probe →
  provider credentials → root creation/dispatch), required `--out`,
  explicit input file (`--input-json` semantics, versioned validated JSON
  decoded inside the program), mock determinism for free previews.
- One root/admission/ledger per invocation (runtime-contracts §16/§17);
  current credentials/policy bound at execution — never inherited from the
  chat session; policy-ask in non-TTY mode is a typed NeedsApproval
  refusal (exit 25).
- Artifacts and exit codes are exactly §7/§8 (unchanged); `run.json` also
  records source+input digests so a manual edit invalidates earlier result
  claims. Cost fields report unpriced/unknown until usage settlement is
  corrected (runtime-contracts §16) — never zero presented as observed.
- SIGTERM/SIGINT own and terminate launched work with bounded grace;
  incomplete outcomes are truthful (§6).
