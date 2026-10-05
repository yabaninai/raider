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
    "-deprecation", "-feature", "-unchecked",
    "-Werror" // owned code compiles warning-free (static gate, RAI-004)
  ),
  Compile / compile / compileOrder := CompileOrder.ScalaThenJava
)

lazy val raiderCore = (project in file("modules/core"))
  .settings(
    commonSettings,
    testSettings,
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
  testFrameworks += new TestFramework("zio.test.ZIOFramework")
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

// raider-tools: fs/search/edit/process primitives (runtime-contracts.md §2, §10).
// Registered by root coordinator (build.sbt is a shared/owned file). Lane slices
// (RAI-014.a read/search, RAI-016.a process) add only their own subpackages.
lazy val raiderTools = (project in file("modules/tools"))
  .settings(commonSettings, testSettings, name := "raider-tools")
  .dependsOn(raiderCore % "compile->compile;test->test")

// raider-repl-engine: in-process scala3-repl (pinned 3.9.0) behind ReplEngine.
// Registered by root coordinator (fast-REPL slice); coordinator owns build.sbt.
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
  .dependsOn(
    raiderCore % "compile->compile;test->test",
    raiderRuntime,
    raiderDsl,
    raiderTestkit,
    raiderReplEngine,
    raiderTools,
    raiderProviderChat
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
lazy val raiderProviderAnthropic = (project in file("modules/provider-anthropic"))
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
  .dependsOn(raiderCore, raiderDsl, raiderRuntime, raiderTools,
             raiderProviderChat)
  .dependsOn(raiderCliFixtures % Test)

// compiled fixture bundle workflow for the cli tests (jarred at test time)
lazy val raiderCliFixtures = (project in file("modules/cli-fixtures"))
  .settings(commonSettings, name := "raider-cli-fixtures", publish / skip := true)
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
    case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".SF")) =>
      MergeStrategy.discard
    case "module-info.class" => MergeStrategy.discard
    case x =>
      val old = (assembly / assemblyMergeStrategy).value
      old(x)
  }
)

lazy val root = (project in file("."))
  .aggregate(raiderCore, raiderRuntime, raiderDsl, raiderTestkit, raiderTools,
             raiderReplEngine, raiderRepl, raiderProviderChat,
             raiderProviderAnthropic, raiderCliFixtures, raiderCli)
  .dependsOn(raiderCore, raiderRuntime, raiderDsl, raiderTestkit, raiderTools,
             raiderReplEngine, raiderRepl, raiderProviderChat,
             raiderProviderAnthropic, raiderCliFixtures, raiderCli)
  .settings(
    name := "raider",
    publish / skip := true
  )
