// RAI-003: canonical pinned multi-project build.
// Product modules are Scala 3 + ZIO only (language policy, 2026-10-03).
ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "dev.raider"
ThisBuild / version := "0.1.0-SNAPSHOT"

// Formatting is an explicit gate (scalafmt-check), never a silent compile hook.
ThisBuild / scalafmtOnCompile := false

val zioVersion = "2.1.26"
val zioJsonVersion = "0.10.0"

// Determinism of compiler-heavy tests (repl-engine forks real dotty JVMs):
// cap concurrent test TASKS across modules so suites don't starve each other.
// This caps resource contention only — no gate, check or threshold is relaxed.
Global / concurrentRestrictions += Tags.limit(Tags.Test, 2)

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Werror" // owned code compiles warning-free (static gate, RAI-004)
  ),
  Compile / compile / compileOrder := CompileOrder.ScalaThenJava
)

lazy val raiderCore = (project in file("modules/core"))
  .settings(
    commonSettings,
    testSettings,
    // RAI-096: protected plugin-SDK fixture spec lives in contracts/plugins
    Test / unmanagedSourceDirectories += baseDirectory.value / ".." / ".." / "contracts" / "plugins",
    name := "raider-core",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-json" % zioJsonVersion
    )
  )

val testSettings = Seq(
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio-test" % zioVersion % Test,
    "dev.zio" %% "zio-test-sbt" % zioVersion % Test
  ),
  testFrameworks += new TestFramework("zio.test.ZIOFramework"),
  // RAI-266 (routing RAI-247 review P3-1), re-evaluated by RAI-274 from the
  // RAI-272 clean baseline: suites inside one module run strictly serially.
  // RAI-272 removed the shared fixed-path hazard (per-suite
  // suite-work/<Suite>/ directories), so serial execution is now defense in
  // depth, not a workaround. The RAI-274 marker probe measured the flip:
  // in in-process modules it creates real suite overlap (raiderCore sibling
  // markers -1 ms apart), while in forked modules (cli/repl/repl-engine,
  // Test / fork := true) suites stay sequential inside the fork regardless,
  // so flipping buys nothing. Measured full `sbt --batch test` on the same
  // tree: 595 s serial vs 637 s flipped (1824 tests, both green) — suite
  // concurrency adds contention, not throughput. Module-level overlap stays
  // capped at 2 by the Tags.Test restriction above. No gate, check or
  // threshold is relaxed.
  Test / parallelExecution := false
)

lazy val raiderRuntime = (project in file("modules/runtime"))
  .settings(commonSettings, testSettings, name := "raider-runtime")
  .settings(libraryDependencies += "dev.zio" %% "zio-streams" % zioVersion)
  .dependsOn(raiderCore % "compile->compile;test->test")
  // loop acceptance fixtures (RAI-010 LOOP-01..03) drive the scripted testkit
  // backends; Test scope only — testkit never enters the production classpath.
  .dependsOn(raiderTestkit % Test)

lazy val raiderDsl = (project in file("modules/dsl"))
  .settings(commonSettings, testSettings, name := "raider-dsl")
  .dependsOn(raiderCore % "compile->compile;test->test")

lazy val raiderTestkit = (project in file("modules/testkit"))
  .settings(commonSettings, testSettings, name := "raider-testkit")
  .dependsOn(raiderCore % "compile->compile;test->test")

// RAI-057 (P2.A): tab/session state over the ADR-024 event seam. Controller
// layer only — no terminal rendering here (P2.B/C, ADR-016 stays authoritative).
// RAI-061 (P2.B): JLine 4 inline shell (same pinned JLine as repl-engine).
lazy val raiderUi = (project in file("modules/ui"))
  .settings(commonSettings, testSettings, name := "raider-ui")
  .settings(
    libraryDependencies ++= Seq(
      "org.jline" % "jline-terminal" % "4.0.14",
      "org.jline" % "jline-reader" % "4.0.14",
      "org.jline" % "jline-terminal-jni" % "4.0.14"
    )
  )
  .dependsOn(raiderRuntime % "compile->compile;test->test")

// raider-tools: fs/search/edit/process primitives (runtime-contracts.md §2, §10).
// Registered by root coordinator (build.sbt is a shared/owned file). Lane slices
// (RAI-014.a read/search, RAI-016.a process) add only their own subpackages.
lazy val raiderTools = (project in file("modules/tools"))
  .settings(commonSettings, testSettings, name := "raider-tools")
  // RAI-094 (ratified by coordinator 2026-10-09): CodingToolset consumes the
  // runtime StateStore SPI for opt-in state tools; no cycle (runtime does not
  // depend on tools).
  .dependsOn(raiderRuntime % "compile->compile;test->test")
  .dependsOn(raiderCore % "compile->compile;test->test")

// raider-repl-engine: in-process scala3-repl (pinned 3.9.0) behind ReplEngine.
// Registered by root coordinator (fast-REPL slice); coordinator owns build.sbt.
lazy val raiderBackendPi = (project in file("modules/backend-pi"))
  .settings(
    commonSettings,
    testSettings,
    name := "raider-backend-pi"
  )
  .settings(libraryDependencies += "dev.zio" %% "zio-streams" % zioVersion)
  .dependsOn(raiderCore)

// raider-backend-opencode: OpenCode server HTTP adapter (RAI-035), standalone
// (zio only, no raiderCore dependency). Registered by root coordinator per
// the RAI-035 work record; coordinator owns build.sbt.
lazy val raiderBackendOpencode = (project in file("modules/backend-opencode"))
  .settings(
    commonSettings,
    testSettings,
    name := "raider-backend-opencode"
  )
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-json" % zioJsonVersion,
      "dev.zio" %% "zio-streams" % zioVersion
    )
  )

// raider-eval-export: eval-run export bundle (RAI-040) against the
// contracts/eval-export schemas + pinned goldens. Standalone (zio + zio-json
// only, no raiderCore/testkit edge). Lane test entry: TestMain via
// modules/eval-export/run-tests.sh; the same zio-test suites are
// sbt-discoverable. Registered by root coordinator (coordinator-owned file).
lazy val raiderEvalExport = (project in file("modules/eval-export"))
  .settings(
    commonSettings,
    testSettings,
    name := "raider-eval-export"
  )
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-json" % zioJsonVersion
    )
  )

// raider-yabanin: optional Yabanin profile lane (RAI-037) against the
// contracts/yabanin schemas + pinned goldens. Standalone (zio + zio-json
// only, no raiderCore/testkit edge). Lane test entry: TestMain via
// modules/yabanin/run-tests.sh; the same zio-test suites are
// sbt-discoverable. Registered by root coordinator (coordinator-owned file).
lazy val raiderYabanin = (project in file("modules/yabanin"))
  .settings(
    commonSettings,
    testSettings,
    name := "raider-yabanin"
  )
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-json" % zioJsonVersion
    )
  )

lazy val raiderCompatPi = (project in file("modules/compat-pi"))
  .settings(
    commonSettings,
    testSettings,
    name := "raider-compat-pi"
  )
  .dependsOn(raiderCore % "compile->compile;test->test")

lazy val raiderCompatOpencode = (project in file("modules/compat-opencode"))
  .settings(
    commonSettings,
    testSettings,
    name := "raider-compat-opencode"
  )
  .dependsOn(raiderCore % "compile->compile;test->test")

lazy val raiderReplEngine = (project in file("modules/repl-engine"))
  .settings(
    commonSettings,
    testSettings,
    name := "raider-repl-engine",
    libraryDependencies ++= Seq(
      "org.scala-lang" %% "scala3-repl" % "3.9.0",
      "org.jline" % "jline-terminal" % "4.0.14",
      "org.jline" % "jline-reader" % "4.0.14",
      "org.jline" % "jline-terminal-jni" % "4.0.14"
    ),
    // The in-process compiler needs the REAL java.class.path (-usejavacp);
    // inside sbt's own JVM it is just the launcher jar, so fork test runs.
    Test / fork := true
  )
  .dependsOn(raiderCore % "compile->compile;test->test")

// raider-repl: REPL facade + console entrypoint (facade extensions live in
// raider.repl per runtime-contracts §3; core never depends on them).
lazy val raiderRepl = (project in file("modules/repl"))
  .settings(commonSettings, testSettings, name := "raider-repl")
  .settings(raiderReplAssemblySettings)
  .dependsOn(
    raiderCore % "compile->compile;test->test",
    raiderRuntime,
    raiderDsl,
    raiderTestkit,
    raiderReplEngine,
    raiderTools,
    raiderProviderChat,
    raiderProviderAnthropic,
    // RAI-177 (coordinator, 2026-10-18): expose accepted CLI lineage
    // pointer-reader/live-projection interfaces to the REPL host. CLI does
    // not depend on REPL, so this preserves the headless/compiler boundary.
    raiderCli % "compile->compile;test->test",
    // RAI-102 contract change (ratified by coordinator 2026-10-12): the
    // product REPL hosts the accepted raider.ui TabShell (RAI-061). No cycle
    // (ui depends only on runtime); same pinned JLine 4.0.14. The headless
    // raiderCli stays JLine-free (quality-gates §12 package smoke unchanged).
    raiderUi % "compile->compile;test->test"
  )
  .settings(
    run / fork := true,
    run / connectInput := true,
    // engine-backed tests need the real java.class.path (-usejavacp)
    Test / fork := true
  )

// raider-provider-chat: OpenAI-compatible Chat transport slice (RAI-011
// feasibility slice): JDK HttpClient + normalized ModelEvents; non-streaming
// only (SSE = RAI-012). Registered by root coordinator (coordinator-owned file).
lazy val raiderProviderChat = (project in file("modules/provider-chat"))
  .settings(commonSettings, testSettings, name := "raider-provider-chat")
  .settings(libraryDependencies += "dev.zio" %% "zio-streams" % zioVersion)
  .dependsOn(raiderCore)
  // capstone fixture: AgentLoop over the real HTTP stand (test-only)
  .dependsOn(raiderRuntime % "test->test", raiderTestkit % Test)

// raider-provider-anthropic: Anthropic-compatible Messages wire (RAI-013
// slice): non-streaming + SSE streaming. Reuses SseFramer from
// raider-provider-chat (provider-protocols §4 sanctions reusable framing);
// the semantic layer is wire-specific. Registered by root coordinator.
lazy val raiderProviderAnthropic =
  (project in file("modules/provider-anthropic"))
    .settings(commonSettings, testSettings, name := "raider-provider-anthropic")
    .settings(libraryDependencies += "dev.zio" %% "zio-streams" % zioVersion)
    .dependsOn(raiderCore, raiderProviderChat)
    // capstone fixture: AgentLoop over the real HTTP stand (test-only)
    .dependsOn(raiderRuntime % "test->test", raiderTestkit % Test)

// raider-cli: headless runner (RAI-022 slice) — bundle loading + artifacts +
// stable exit codes; NO compiler/JLine (headless distribution rule).
lazy val raiderCli = (project in file("modules/cli"))
  .settings(commonSettings, testSettings, name := "raider-cli")
  .settings(raiderCliAssemblySettings)
  .settings(libraryDependencies += "dev.zio" %% "zio-streams" % zioVersion)
  .dependsOn(
    raiderCore,
    raiderDsl,
    raiderRuntime,
    raiderTools,
    raiderProviderChat,
    raiderProviderAnthropic
  )
  .dependsOn(raiderCliFixtures % Test)
  .settings(
    // RAI-109 (coordinator): process-level acceptance (`raider replay ...`
    // through raider.cli.Main) spawns a child JVM whose classpath comes from
    // java.class.path — without forking that is sbt's own launcher path and
    // the child cannot see raider classes. Forking gives the test JVM the
    // real module test classpath (same pattern as raiderRepl).
    Test / fork := true,
    // keep the pre-fork CWD: HeadlessRunnerSpec/LineageArtifactsSpec drop
    // demo-bundle.jar into modules/cli/target relative to the repo root and
    // scripts/quality/cli_smoke.sh consumes it from there.
    Test / forkOptions := (Test / forkOptions).value
      .withWorkingDirectory((ThisBuild / baseDirectory).value)
  )

// compiled fixture bundle workflow for the cli tests (jarred at test time)
lazy val raiderCliFixtures = (project in file("modules/cli-fixtures"))
  .settings(
    commonSettings,
    name := "raider-cli-fixtures",
    publish / skip := true
  )
  .dependsOn(raiderCore)

// raiderCli fat-JAR packaging (nightly Phase 5.2): standalone raider-cli.jar —
// main raider.cli.Main, no runtime compiler (headless distribution rule).
// Merge rules: discard foreign MANIFESTs and module-info stubs; everything
// else uses the assembly defaults (service files concat, refs dedup).
lazy val raiderCliAssemblySettings = Seq(
  assembly / mainClass := Some("raider.cli.Main"),
  assembly / assemblyJarName := "raider-cli.jar",
  assembly / test := {},
  assembly / assemblyMergeStrategy := {
    case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
    case PathList("META-INF", xs @ _*)
        if xs.lastOption.exists(_.endsWith(".SF")) =>
      MergeStrategy.discard
    case "module-info.class" => MergeStrategy.discard
    case x =>
      val old = (assembly / assemblyMergeStrategy).value
      old(x)
  }
)

// RAI-262: installed REPL launcher packaging (raider-repl.jar). The REPL fat
// JAR legitimately ships the in-process scala3-repl compiler and JLine — the
// headless distribution rule constrains raider-cli.jar, which this card leaves
// untouched (golden pins bind its bytes). Merge rules mirror
// raiderCliAssemblySettings; the 3.9.0 library artifacts have disjoint entry
// sets (scala3-library_3 3.9.0 is a manifest-only stub; the full standard
// library rides in scala-library), so no duplicate-class conflicts arise.
lazy val raiderReplAssemblySettings = Seq(
  assembly / mainClass := Some("raider.repl.Main"),
  assembly / assemblyJarName := "raider-repl.jar",
  assembly / test := {},
  assembly / assemblyMergeStrategy := {
    case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
    case PathList("META-INF", xs @ _*)
        if xs.lastOption.exists(_.endsWith(".SF")) =>
      MergeStrategy.discard
    case "module-info.class" => MergeStrategy.discard
    // version-marker properties file shipped by both scala-collection-compat
    // and io.get-coursier:interface; runtime-inert, keep the first occurrence
    case "scala-collection-compat.properties" => MergeStrategy.first
    case x =>
      val old = (assembly / assemblyMergeStrategy).value
      old(x)
  }
)

// RAI-262: build the installed distribution — assemble raider-repl.jar and
// stage the launcher layout + zip via the tested raider.repl.dist library
// (pure file operations on the REPL runtime classpath; no REPL spawned).
val raiderReplDist = taskKey[File](
  "assemble raider-repl.jar and stage the installed REPL distribution (RAI-262)"
)

lazy val root = (project in file("."))
  .aggregate(
    raiderCore,
    raiderRuntime,
    raiderDsl,
    raiderTestkit,
    raiderTools,
    raiderReplEngine,
    raiderRepl,
    raiderProviderChat,
    raiderProviderAnthropic,
    raiderCliFixtures,
    raiderCli,
    raiderUi,
    raiderEvalExport,
    raiderYabanin
  )
  .dependsOn(
    raiderCore,
    raiderRuntime,
    raiderDsl,
    raiderTestkit,
    raiderTools,
    raiderReplEngine,
    raiderRepl,
    raiderProviderChat,
    raiderProviderAnthropic,
    raiderCliFixtures,
    raiderCli,
    raiderUi,
    raiderEvalExport,
    raiderYabanin
  )
  .settings(
    name := "raider",
    publish / skip := true,
    raiderReplDist := {
      val log = streams.value.log
      val jar = (raiderRepl / assembly).value
      val replBase = (raiderRepl / baseDirectory).value
      val outDir = (raiderRepl / target).value / "raider-repl-dist"
      val cp = (raiderRepl / Runtime / fullClasspath).value.map(_.data)
      val taskRunner = (raiderRepl / Compile / run / runner).value
      taskRunner
        .run(
          "raider.repl.dist.ReplDistributionMain",
          cp,
          Seq(
            "--jar",
            jar.getPath,
            "--launcher",
            (replBase / "packaging" / "raider-repl").getPath,
            "--readme",
            (replBase / "packaging" / "README.txt").getPath,
            "--version",
            version.value,
            "--out",
            outDir.getPath,
            "--name",
            s"raider-repl-${version.value}"
          ),
          log
        )
        .get
      outDir
    }
  )
