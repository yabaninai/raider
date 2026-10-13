# BUILD-TOOLCALL: work record

Status: investigation complete; source failure not reproduced. Date:
2026-10-13. Baseline source commit: `95cb8e9f1b27c91a427463ead66b649e058cdfcf`
(lane assignment commit `e248158ee2f5366fff38deffd8c7c3b7a94c9a51`).

## Diagnostic result

No runtime change was made. The reported `tc.id` E008 was observed in the
`useful-examples` lane, but repeated fresh builds from identical source and
build inputs in this lane succeeded. The successful clean builds compiled the
core and runtime sources from scratch, including all 36 runtime sources.

The failing and passing lanes have identical SHA-256 values for the relevant
source/build inputs:

| File | SHA-256 |
| --- | --- |
| `modules/runtime/src/main/scala/raider/runtime/loop/AgentLoop.scala` | `8f9797508df13d0e0febd182bb4344160fda457c553b63d0868f0d15f463cb3b` |
| `modules/core/src/main/scala/raider/core/tool.scala` | `dccc3d934c369906377cf4507271dca78a10948d0f303b863ea5691896a32737` |
| `build.sbt` | `5d15fea5b753ca23769bb328e76c27a100841eca527bd7aa247653c180c8609e` |
| `project/build.properties` | `78532dcecb8277f1e43184a3df224027afda08a45938087b1f0412db32575966` |
| `project/plugins.sbt` | `9aa085dc09c69d5972a4a64487c2cd81948f14841f8140172ce101a32b4f5890` |

The failing log `/tmp/useful-examples-runtime-classpath.log` records Scala
3.9.0 E008 at `AgentLoop.scala:751` (`value id is not a member of
raider.core.ToolCall`) during runtime compilation. It also includes the
exported runtime classpath, with `modules/core/target/scala-3.9.0/classes` on
the classpath. The log reports Oracle JDK 21.0.12.1. The first failed
`sbt --batch clean compile` in that lane was reported as exit 1, but its raw
stdout was not retained as a file. The lane's later full clean compile
`/tmp/useful-examples-clean-compile.log` succeeded without a source change.

After a fresh clean build here, `javap` on the newly compiled
`raider.core.tool$ToolCall` showed both `call()` and `id()` accessors. A stale
core compiler artifact is one possible explanation for the transient failure,
but this was not proven; the final integrated clean build must decide. The
public core contract, provider wires, admission, cancellation, and tool
semantics are unchanged.

## Changed paths

- `docs/work/records/BUILD-TOOLCALL.md` (this record only)
- No runtime source, test, public API, or schema changes.

## Acceptance evidence

| Criterion | Actual result | Evidence |
| --- | --- | --- |
| Reported clean-build failure | Observed in sibling lane; not reproduced on identical source/build hashes | `/tmp/useful-examples-runtime-classpath.log`; sibling lane reports the initial `clean compile` exit 1 without a retained raw log |
| JDK 21 core-only clean compile | PASS, exit 0 | `/tmp/clean-toolcall-core-compile.log` |
| Fresh full clean compile, initial | PASS, exit 0; core and runtime sources compiled | `/tmp/clean-toolcall-initial.log` |
| Fresh full clean compile after core-only compile | PASS, exit 0; runtime 36 sources compiled | `/tmp/clean-toolcall-full-retry.log` |
| Runtime budget/cancellation/ownership tests | 53 passed, 0 failed, exit 0 | `/tmp/clean-toolcall-runtime-tests.log` |
| Exported `ToolCall` bytecode accessor inspection | `call()` and `id()` both present after clean compilation | `javap -classpath modules/core/target/scala-3.9.0/classes raider.core.tool$ToolCall` output in lane session |

## Commands

All commands below ran in the absolute `clean-toolcall` worktree with
`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`,
`JAVACMD=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java`,
and `COURSIER_CACHE=/tmp/cc-master` unless otherwise stated.

| Command | Exit | Log |
| --- | --- | --- |
| `sbt --batch clean compile` (baseline, before any source edit) | 0 | `/tmp/clean-toolcall-initial.log` |
| `sbt --batch raiderCore/clean raiderCore/compile` | 0 | `/tmp/clean-toolcall-core-compile.log` |
| `sbt --batch clean compile` (after core-only compile) | 0 | `/tmp/clean-toolcall-full-retry.log` |
| `sbt --batch raiderRuntime/testOnly raider.runtime.budget.BudgetAdmissionSpec raider.runtime.budget.BudgetEmissionSpec raider.runtime.delegate.DelegationCancelSpec raider.runtime.scopes.ScopeSpec raider.runtime.loop.AgentLoopSpec` | 0 (53 passed) | `/tmp/clean-toolcall-runtime-tests.log` |
| `sbt --batch 'export root/runtime:fullClasspath'` (sibling lane, failed E008) | 1 | `/tmp/useful-examples-runtime-classpath.log` |
| `sbt --batch clean compile` (sibling lane, later retry) | 0 | `/tmp/useful-examples-clean-compile.log` |

## Limitations / blockers

- The initial sibling-lane clean-build stdout was not retained to a file; the
  preserved classpath-export log contains the same E008, and the sibling
  executor confirmed the initial clean-build exit 1.
- No source-level cause for the transient compile failure was established, so
  this card does not claim a fix.
- The example build/real tool round, changed-quality gates, and independent
  review are tracked separately by the coordinator. Final acceptance requires
  a clean build on the integrated candidate.

## Review and next task

Independent review pending coordinator arrangement. Next: coordinator retries
the final clean integrated build after the example inputs are frozen; if E008
reappears, preserve the complete failure log and resume diagnosis before any
runtime change.
