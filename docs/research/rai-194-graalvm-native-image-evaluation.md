# RAI-194: GraalVM Native Image feasibility evaluation

Date: 2026-10-23. Scope: evaluation only; this document does not claim native support.

## Finding and recommendation

**Recommendation: NO-GO for implementing or distributing a native Raider CLI on the current product contract.** Keep the RAI-193 JVM distribution as the supported path. A later ADR may reopen the decision only after the headless runner no longer needs to load arbitrary workflow classes from external bundle JARs, or after an explicitly reviewed product contract limits native mode to a closed, build-time-known workflow set. This evaluation did not build a native image and did not measure native or JVM startup/size.

This is not a finding that Scala 3 or ZIO cannot run under GraalVM Native Image. Native Image accepts JVM bytecode and uses static reachability analysis; Scala 3/ZIO therefore have a plausible technical route, but project compatibility must be demonstrated against this exact dependency graph and behavior. The decisive product mismatch is the existing bundle API: `modules/cli/src/main/scala/raider/cli/run/BundleLoader.scala` creates a `URLClassLoader` for a user-supplied JAR and loads manifest-provided class names using `Class.forName(..., loader)`. Native Image's closed-world analysis cannot generally include arbitrary bytecode that is supplied only after image build. Resource metadata or reflection registrations do not make unknown classes available. Supporting this in native mode would require a separate contract or product redesign, not just metadata.

## Tooling and environment verdict

| Probe | Result |
| --- | --- |
| `native-image --version` | Exit 127; `native-image` is not on PATH. |
| `command -v native-image` | No executable found. |
| GraalVM manager/tool (`gu`, `sdk`) | Neither found. |
| `brew list --versions graalvm-jdk graalvm-jdk@21 graalvm-community-jdk graalvm-community-jdk@21` | No installed matching formula was listed. |
| Current shell Java | Oracle Java 21.0.12.1, HotSpot; not a GraalVM. |
| Native build prerequisites | macOS arm64, Apple Command Line Tools at `/Library/Developer/CommandLineTools`; `clang` is available. |
| Obtainability | Official GraalVM downloads exist, but no locally installed native-image toolchain was available. This run did not download or install one. A downloadable, different JDK line is not treated as the pinned project toolchain or as a measured feasibility result. |

No GraalVM build was attempted: there is no native-image executable, no RAI-193 fat JAR or release directory in this worktree, and the source-level attempt to resolve the CLI classpath failed first at an unrelated existing compile error described below. This is an unavailable probe, not a pass or evidence of native incompatibility.

## Pins, artifact and classpath boundary

- Build pins in this baseline: Scala 3.9.0, ZIO 2.1.26, zio-json 0.10.0 (`build.sbt`). RAI-193 records the local v0.1.1 release fat JAR SHA-256 as `5eb416a6a3eb0f354c4a38d2aa7053e8ec7207c1d012cc6e936dc2efdb9810a0`.
- The RAI-193 release artifact directory is not present in this lane (`artifacts/release/0.1.1` does not exist); no release manifest or dependency inventory is checked into the current worktree. The RAI-193 work record lists the six released filenames/hashes, but not the assembled JAR's dependency contents. Therefore the v0.1.1 JAR's exact dependency set could not be verified from its release manifest here.
- Source-level dependency graph verification: `raiderCli` depends on core, DSL, runtime, tools, provider-chat and provider-anthropic. `raiderCliAssemblySettings` names `raider.cli.Main`. The CLI build comment and RAI-022 inventory rule require excluding the Scala compiler and JLine from headless distribution. `raiderReplEngine` separately depends on `scala3-repl` and JLine 4.0.14; `raiderUi` has JLine 4.0.14 including `jline-terminal-jni`, but the CLI does not depend on those projects in `build.sbt`.
- Attempted exact source classpath query: `sbt --batch "show raiderCli/Compile/dependencyClasspath"`. It exited 1 after compilation stopped at `modules/runtime/src/main/scala/raider/runtime/loop/AgentLoop.scala:772`: `tc.id` is not a member of `raider.core.ToolCall`. Thus no resolved classpath or compiled JAR was produced. This failure is recorded as a blocker; it is not proof of the release JAR's inventory.
- JNI/JLine risk for the target headless CLI is lower than for the REPL: build graph isolation excludes the REPL/UI JLine graph. The exact release artifact still needs inventory validation; if the runtime CLI graph gains JLine/JNI transitively, native metadata and terminal behavior must be investigated. Native support for the REPL is explicitly out of scope here.

## Reachability and compatibility inventory

| Area | Evidence in current source | Feasibility risk / required probe |
| --- | --- | --- |
| Scala 3 + ZIO runtime | Scala 3.9.0 and ZIO 2.1.26 are pinned. Runtime uses fibers, `forkDaemon`, interruption, scopes, scheduling, and process/thread cleanup through the ZIO/JDK runtime. | No static conclusion that these APIs are incompatible. Build and exercise all supported runner paths under a JDK-21-matched GraalVM, especially fiber shutdown, cancellation, timers, HTTP, process handling, and SIGTERM. Inspect image build diagnostics for initialization and thread substitutions. |
| Dynamic workflow loading | `BundleLoader` opens bundle JARs using `URLClassLoader` and `Class.forName` on manifest-specified main classes, then invokes a constructor. | High / contract blocker: arbitrary post-build class definitions violate the closed-world assumption. The tracing agent can report observed reflection but cannot embed arbitrary future bundle bytecode. A closed-world mode requires product/ADR scope. |
| Static reflection | CLI uses compile-time-derived `zio-json` codecs for product data; CLI also does explicit reflective construction for bundle workflow classes. | Derived codecs are generated at compile time and are lower risk than reflection-driven codecs, but their actual closed-world behavior must be run through full input/output/event fixtures with exact reachability metadata enabled. Bundle class lookup is not solved by codec metadata. |
| Resources/configuration | Bundle manifests are read from supplied JARs; product configuration and local files are read from filesystem paths. | Distinguish classpath resources (must be registered if dynamically discovered) from user data files (remain external filesystem inputs). Verify any library resource/bundle lookups from build reports and resource tracing; do not embed workspace data. |
| JLine / JNI / PTY | Headless CLI is documented and wired separately from REPL/UI; JLine's JNI terminal dependency belongs to the REPL/UI graph, not `raiderCli` dependencies. | Verify actual assembled contents, including merged service descriptors, before relying on this source-level separation. REPL/JLine/JNI Native Image evaluation would be a separate scope. |
| Unsafe and initialization | ZIO/runtime code reaches JVM concurrency/runtime facilities; this scan did not identify application calls to `Unsafe.unsafe` in the CLI runtime path, but dependencies may use low-level runtime mechanisms. | Use Native Image diagnostics and runtime stress tests, not a blanket `--initialize-at-build-time`. Test for build-time initialization capturing environment, files, clocks, or executor state. |

## Measurement status

| Measurement | Result | Reason |
| --- | --- | --- |
| Native-image build success/time | Not measured | `native-image` absent; released JAR absent; source CLI classpath resolution fails before assembly. |
| Native executable size | Not measured | No executable produced. |
| Native cold/warm startup | Not measured | No executable produced. |
| JVM released JAR size | Not measured in this lane | RAI-193 records the JAR SHA-256 but not its byte length; the v0.1.1 artifact is not present here. |
| JVM startup comparisons | Not measured by RAI-193 | Its install-smoke records successful starts, not timed trials or machine/startup protocol. |

The RAI-193 install smoke is a functional JVM baseline only. Its success cannot be converted into time/size data. No native-vs-JVM percentage, build time, binary size, startup latency, or performance conclusion is asserted.

## Reproducible follow-up steps if a reviewed scope permits reopening

1. Start from a clean committed source snapshot and validate the RAI-193 release metadata/checksums. Preserve `raider-cli.jar` SHA-256 and machine-readable dependency inventory. Re-run the RAI-022 headless classpath assertions: no Scala compiler/REPL or JLine artifacts. Resolve the current Scala compilation failure before generating any candidate JAR.
2. Pin and record the exact GraalVM distribution/build for the project's supported JDK line, OS/architecture, `native-image --version`, compiler/linker version, Xcode/Command Line Tools, SDK, and checksums. Keep the download/extracted toolchain outside shipped outputs. Confirm the tool JDK matches the bytecode/class version and release support contract; do not silently change the project JDK pin.
3. Build the official `raiderCli/assembly` output, verify its checksum and dependency contents, then a minimal first probe is `native-image --no-fallback --exact-reachability-metadata -jar modules/cli/target/scala-3.9.0/raider-cli.jar artifacts/native/raider`. Save complete stdout/stderr, exit status, build duration, build report, call tree/analysis diagnostics, and metadata. Do not add blanket reflection or initialization metadata just to get a build through.
4. Run the native executable against the same offline mock/replay command and fixtures used for the JVM release smoke. Include valid and invalid input, bundle manifest decoding, filesystem inputs, HTTP mock transport, process/check execution, cancellation, SIGTERM, and artifact/exit-code acceptance. Specifically test a bundle compiled after image construction; if it cannot load, report the contract limitation rather than claiming a usable general runner.
5. For the comparable startup/size experiment, use one immutable release JAR, one exact JVM (JDK version recorded), one native binary, same machine/power/OS state, identical mock command and output destination per trial. Perform 3 warm-up runs then at least 10 measured fresh-process runs per mode, alternating modes; report every trial plus median/range and `/usr/bin/time` elapsed/user/system/max-RSS. Keep JVM cold-start definition explicit (new process; no claim of cold OS page cache). Record native file size and JAR size in bytes. Do not use model/network calls.
6. Re-run the functional and signal gates on the exact candidate; retain build metadata, source/toolchain/dependency fingerprints, raw trials, and checksums. Any public native capability still requires a separate reviewed ADR before implementation or distribution changes.

## Ecosystem basis and references

GraalVM Native Image is a supported Java bytecode AOT path with a closed-world reachability analysis; dynamic reflection, JNI, resources, serialization and proxies can require reachability metadata. Metadata can register known reflective types, but it cannot turn unknown post-build workflow bytecode into reachable code. This makes Scala 3/ZIO technically plausible for statically known program paths while leaving project-level support unproven and the dynamic bundle contract incompatible without redesign.

References checked 2026-10-23:

- GraalVM Native Image overview and JAR entry points: <https://www.graalvm.org/latest/reference-manual/native-image/>.
- Reachability metadata, reflection/resources/JNI, and dynamic class loading constraints: <https://www.graalvm.org/latest/reference-manual/native-image/metadata/>.
- Native Image optimization and build tradeoffs: <https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/>.
- GraalVM downloads and version/OS distribution options: <https://www.graalvm.org/downloads/>.

These upstream documents establish the general mechanism, not Scala 3.9/ZIO 2.1.26 compatibility or Raider's native-image readiness. No upstream or product benchmark is used as Raider measurement evidence.
