# CI-EXAMPLE: Scala local CI and captured native lineage

Date: 2026-10-13. Product baseline: `1fd8636`; card baseline: `8ad73b0`.
Eight visible Luna lanes used isolated `docs/ci-example-*` worktrees. The
coordinator integrated in `../raider-english/ci-coordinator`, avoiding the
other session's mutable sbt build in the main checkout.

## Delivered example

`examples/user-guide/ci/BillingCi.scala` composes a native billing repair with
typed source verification, independent compiler/tests, two parallel reviews,
and three ordered boundary reviews with concurrency two. The decision accepts
only validated results. `scripts/build/user-ci.sh` compiles the example against
the pinned checkout runtime.

Each invocation has its own native root and artifacts. The application-owned
pipeline index links them and records stage status and native exit separately.
No unified native phase graph, shared budget, sandbox, durable resume, forge
integration, deployment, or SDK release is claimed. Reviewer no-tools prompts
and postcondition checks do not remove the coder's existing tool privileges.
The selected profile requires trusted local inputs.

README and the user, REPL, CI, and lineage guides show the composition, generic
CI context, execution commands, output schema, and captured graphs. The small
English comment correction in `ResumeCommand.scala` restores the existing
English-language rule for a newly integrated comment; executable code is unchanged.

## Commands and observed results

Only building and executing this example were authorized. No general tests or
quality gates ran in this task.

| Command | Result | Evidence in the integration checkout |
| --- | --- | --- |
| `JAVA_HOME=<JDK21> sh scripts/build/user-ci.sh`, first build | exit 1; nine Scala compile errors | `artifacts/ci-example/build-first-failed.log` |
| Same builder, corrected candidate | exit 0 | `artifacts/ci-example/build-candidate.log` |
| `java -cp <example-classpath> demo.BillingCi <runtime-classpath> <out> <fixture>` | first execution settled `BLOCKED`; JVM remained alive and was stopped with SIGKILL, actual subprocess exit -9 | `artifacts/ci-example/receipt.json`, `first-exit-stop.json` |
| Independent baseline checker within the example | expected exit 1, only `FAIL exact-threshold` | `artifacts/user-ci/run/baseline-check.log` |
| Independent final checker within the example | exit 0, `DiscountSpec: PASS 3 boundaries` | `artifacts/user-ci/run/final-independent-check.log` |
| Credential-value scan of the run outputs | no leaked key values found | `artifacts/ci-example/receipt.json` |

The actual first execution used application SHA-256
`53d766719d28ecc3ffd49de4ab54c0d3e1ec1c84baffc976815b4e6000abc9c7`
at `8a7fc88`. Six native invocations completed with exit 0, but the first
application decoder rejected fenced JSON answers. The resulting decision had
one accepted general review and one accepted boundary review. The original
index also lost two failed-stage references and mislabeled reviewer B; the
complete native directories retain all six graphs.

The sanitized first capture preserves the original outcome and index, plus six
validated native graph excerpts: 23 nodes total (repair 18, five reviews one
node each). It omits arguments, private classpaths, and full transcripts and is
explicitly not replay input. The full originals remain in ignored output.

## Corrected candidate and review

The candidate moves JVM exit after the managed ZIO effect completes, gives
parallel boundary tasks distinct context paths, fixes the reviewer B key, and
preserves native references through semantic failures. A complete single JSON
code fence may be unwrapped before the full JSON decoder; exact field and
policy checks remain intact. General review prompts have different focuses.

Corrected application SHA-256:
`4d604012ef6c816e8d8eacc76fae351f4f8eecf23d5612e8ff69ab01c35d40ff`.
It was built successfully and independently reviewed. A fresh execution of this
revision awaits the owner's response to the additional one-run request. The
first failed execution is not proof of the corrected revision's runtime behavior.

Independent read-only records: `artifacts/ci-example/source-review.json` and
`capture-review.json`. The API audit records current limitations, including the
missing CLI `--ci` plumbing, inverted native preflight-check outcome, independent
per-run admission, fixed 400-attempt limit, and unpriced accounting.

## Provider and trust

The owner authorized ample coding-plan requests through Yabanin. The authenticated
model registry identified requested `glm-5.3-flash` as `zai-coding` before the
run. Credentials came from the private ignored `.env.yabanin` file and were
passed only through process environment; no key values were placed in source,
argv, captures, or reports. No direct fallback, push of a release tag, production
deployment, or retry of the first mutating run occurred.

## Concurrent main reconciliation

Main advanced to `bd1f94b` during this task. Its task registrations, eval gate,
quality policy, work record, and statuses were preserved. No executable runtime
API used by this example changed in that delta. The incoming wave-4d board prose
was translated under the existing DOCS-EN policy. The API, architecture, and
onboarding statements about absent Anthropic definitions were updated against
the implemented `encodeTools`; structured tool-result mapping remains open.
These edits claim no additional provider verification or quality-gate execution.

Final static review approved the reconciled documentation. A tracked-file
inventory covered 643 files and found no Cyrillic text. `git diff --check` exited
0. These are source/document checks, not execution of the runtime gates.
