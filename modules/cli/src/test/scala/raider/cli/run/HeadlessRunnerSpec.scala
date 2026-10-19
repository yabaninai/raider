package raider.cli.run

import raider.core.RaiderError

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.jar.{JarEntry, JarOutputStream, Manifest}
import java.util.zip.ZipEntry
import zio.test._
import zio.test.live
import zio.ZIO
import zio.json.DecoderOps
import raider.runtime.events.EventCodecs.given

/** RAI-022 slice acceptance:
  *   - RUN-01: jar bundle (fixture classes zipped at test time) executes a mock
  *     program headlessly; artifacts written; closed-stdin safe (no stdin reads
  *     anywhere);
  *   - RUN-02: preflight failures (unknown workflow / not-a-jar / missing input
  *     / no --mock) are typed BEFORE any dispatch;
  *   - RUN-03 (light): concurrent invocations get separate work dirs.
  */
object HeadlessRunnerSpec extends ZIOSpecDefault:
  private val keepLock = new Object

  /** Zip the already-compiled fixture classes into a real jar with a
    * raider-bundle manifest (test-time packaging — no runtime compiler).
    */
  /** Fixture classes dir = wherever the compiled fixtures project landed on our
    * test classpath (classes dir or jar).
    */
  private def fixtureClassesDir: Path =
    val loc = Path.of(
      classOf[
        raider.fixtures.DemoWorkflow
      ].getProtectionDomain.getCodeSource.getLocation.toURI
    )
    if Files.isDirectory(loc) then loc
    else throw new IllegalStateException(s"fixture location is not a dir: $loc")

  private def makeBundleJar(dir: Path): Path =
    val jarPath = dir.resolve("demo-bundle.jar")
    val mf = new Manifest()
    mf.getMainAttributes.putValue("Manifest-Version", "1.0")
    val jos = new JarOutputStream(Files.newOutputStream(jarPath), mf)
    val manifestJson =
      """{"schemaVersion":1,"bundleId":"demo","entries":[
        |{"name":"inspect","mainClass":"raider.fixtures.DemoWorkflow","version":1},
        |{"name":"second","mainClass":"raider.fixtures.SecondWorkflow","version":1}],
        |"declaredTrustLevel":"trusted-local"}""".stripMargin
    def putEntry(name: String, bytes: Array[Byte]): Unit =
      jos.putNextEntry(new JarEntry(name))
      jos.write(bytes)
      jos.closeEntry()
    putEntry(BundleLoader.ManifestEntry, manifestJson.getBytes(UTF_8))
    // walk the fixture classes directory
    val root = fixtureClassesDir
    Files.walk(root).forEach { p =>
      if Files.isRegularFile(p) then
        val rel = root.relativize(p).toString.replace('\\', '/')
        putEntry(rel, Files.readAllBytes(p))
    }
    jos.close()
    // keep the jar for the Main-process smoke (scripts/quality/cli_smoke.sh)
    keepLock.synchronized {
      val keepDir = Path.of("modules", "cli", "target")
      Files.createDirectories(keepDir)
      Files.copy(
        jarPath,
        keepDir.resolve("demo-bundle.jar"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING
      )
    }
    jarPath

  private def tempDir(): Path =
    Files.createTempDirectory("raider-cli-test")

  private def argsFor(
      jar: Path,
      out: Path,
      workflow: String = "inspect",
      extra: List[String] = Nil
  ) =
    CliArgs
      .parse(
        List(
          "run",
          "--bundle",
          jar.toString,
          "--workflow",
          workflow,
          "--input",
          "task-42",
          "--out",
          out.toString,
          "--mock"
        ) ++ extra
      )
      .fold(e => throw e, identity)

  private def agentArgs(out: Path, agent: String = "scout") =
    CliArgs
      .parse(
        List(
          "run",
          "--agent",
          agent,
          "--input",
          "task-42",
          "--out",
          out.toString,
          "--mock"
        )
      )
      .fold(e => throw e, identity)

  private def readRunJson(workDir: Path): raider.core.result.RunResult =
    val text = Files.readString(workDir.resolve("run.json"), UTF_8)
    text.fromJson[raider.core.result.RunResult] match
      case Right(r) => r
      case Left(e)  => throw new IllegalStateException(s"bad run.json: $e")

  import raider.core.result.RunResult
  import zio.json.{DecoderOps, DeriveJsonDecoder}

  def spec = suite("headless runner (RAI-022 slice)")(
    test("args: mutually exclusive inputs and unknown flags are typed") {
      val both = CliArgs.parse(
        List(
          "run",
          "--bundle",
          "b",
          "--workflow",
          "w",
          "--input",
          "a",
          "--input-json",
          "f",
          "--out",
          "o"
        )
      )
      val unknown = CliArgs.parse(List("run", "--frobnicate"))
      val noSub = CliArgs.parse(List("deploy", "--x", "y"))
      val ok = CliArgs.parse(
        List(
          "run",
          "--bundle",
          "b",
          "--workflow",
          "w",
          "--input",
          "t",
          "--out",
          "o",
          "--mock"
        )
      )
      val agentAndBundle = CliArgs.parse(
        List(
          "run",
          "--agent",
          "scout",
          "--bundle",
          "b",
          "--workflow",
          "w",
          "--input",
          "t",
          "--out",
          "o"
        )
      )
      val unknownAgent = CliArgs.parse(
        List("run", "--agent", "nope", "--input", "t", "--out", "o")
      )
      val okAgent = CliArgs.parse(
        List("run", "--agent", "worker", "--input", "t", "--out", "o", "--mock")
      )
      assertTrue(
        both.fold(_.code == "RA-CFG", _ => false),
        unknown.fold(_.code == "RA-CFG", _ => false),
        noSub.fold(_.detail.contains("subcommand"), _ => false),
        ok.isRight,
        agentAndBundle
          .fold(_.detail.contains("mutually exclusive"), _ => false),
        unknownAgent
          .fold(_.detail.contains("unknown built-in agent"), _ => false),
        okAgent.isRight
      )
    },
    test(
      "--agent mode: built-in agent on the mock backend; output carries the mock marker"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO.attempt(tempDir()).orDie
            outcome <- HeadlessRunner.run(agentArgs(work))
            runJson = readRunJson(outcome.workDir)
            summary = Files
              .readString(outcome.workDir.resolve("summary.md"), UTF_8)
          yield assertTrue(
            outcome.exitCode == 0,
            runJson.output.exists(o => o.json.contains("[mock] answer")),
            summary.contains("fixture/mock")
          )
        }
      }
    },
    test("--agent unknown name fails at parse (typed, exit 21)") {
      val parsed = CliArgs.parse(
        List("run", "--agent", "nope", "--input", "t", "--out", "x", "--mock")
      )
      assertTrue(
        parsed.isLeft,
        parsed.fold(_.detail.contains("unknown built-in agent"), _ => false)
      )
    },
    test("RUN-01: jar bundle executes the mock program; artifacts valid") {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          jar <- ZIO.attempt(makeBundleJar(work)).orDie
          args = argsFor(jar, work)
          outcome <- HeadlessRunner.run(args)
          runJson = readRunJson(outcome.workDir)
          summary = Files.readString(
            outcome.workDir.resolve("summary.md"),
            UTF_8
          )
          events = Files.readString(
            outcome.workDir.resolve("events.jsonl"),
            UTF_8
          )
          // RAI-060: events.jsonl holds REAL EventEnvelope lines now —
          // every line parses with the runtime codec and the run settles
          // with a single terminal envelope
          envelopes = events
            .split("\n")
            .filter(_.trim.nonEmpty)
            .toList
            .map(_.fromJson[raider.runtime.events.EventEnvelope])
        yield assertTrue(
          outcome.exitCode == 0,
          runJson.status == raider.core.result.ExecutionStatus.Succeeded,
          runJson.output.exists(o => o.json.contains("inspected: task-42")),
          runJson.checks.exists(c =>
            c.id == "run-result-schema" && c.outcome == raider.core.result.CheckOutcome.Passed
          ),
          summary.contains("fixture/mock"), // mock never passes as live
          envelopes.nonEmpty,
          envelopes.forall(_.isRight),
          envelopes.last.toOption.exists(e =>
            e.payload == raider.runtime.events.EventPayload.Terminal(
              raider.runtime.jobs.JobStatus.Succeeded
            )
          )
        )
      }
    },
    test(
      "RUN-02: unknown workflow fails preflight (exit 21, typed, no output)"
    ) {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          jar <- ZIO.attempt(makeBundleJar(work)).orDie
          args = argsFor(jar, work, workflow = "nope")
          outcome <- HeadlessRunner.run(args)
          runJson = readRunJson(outcome.workDir)
        yield assertTrue(
          outcome.exitCode == 21,
          runJson.status == raider.core.result.ExecutionStatus.Failed,
          runJson.error.exists(_.detail.contains("unknown workflow 'nope'")),
          runJson.output.isEmpty, // no spend, no output
          runJson.checks.exists(c =>
            c.id == "bundle-preflight" && c.outcome == raider.core.result.CheckOutcome.Failed
          )
        )
      }
    },
    test("RUN-02: not-a-jar and missing --mock are typed preflight failures") {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          notAJar = work.resolve("plain.txt")
          _ <- ZIO.attempt(Files.writeString(notAJar, "hello")).orDie
          bad = CliArgs
            .parse(
              List(
                "run",
                "--bundle",
                notAJar.toString,
                "--workflow",
                "inspect",
                "--input",
                "t",
                "--out",
                work.resolve("o1").toString,
                "--mock"
              )
            )
            .fold(e => throw e, identity)
          r1 <- HeadlessRunner.run(bad)
          noMock = CliArgs
            .parse(
              List(
                "run",
                "--bundle",
                notAJar.toString,
                "--workflow",
                "inspect",
                "--input",
                "t",
                "--out",
                work.resolve("o2").toString
              )
            )
            .fold(e => throw e, identity)
          r2 <- HeadlessRunner.run(noMock)
        yield assertTrue(
          r1.exitCode == 21, // not a jar → preflight InputValidation
          r2.exitCode == 21
        ) // live providers not wired without --mock
      }
    },
    test("RUN-03: concurrent invocations get separate work dirs") {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          jar <- ZIO.attempt(makeBundleJar(work)).orDie
          runs <- ZIO.foreachPar(1 to 3)(i =>
            HeadlessRunner.run(argsFor(jar, work))
          )
          dirs = runs.map(_.workDir)
        yield assertTrue(
          runs.forall(_.exitCode == 0),
          dirs.toSet.size == 3
        ) // no shared state directories
      }
    },
    // ===== RAI-064: credentials only via env/helper, no argv secrets =====
    test(
      "SETUP-03: --api-key in argv is a typed error that never echoes the value"
    ) {
      val parsed = CliArgs.parse(
        List(
          "run",
          "--agent",
          "scout",
          "--input",
          "t",
          "--out",
          "o",
          "--api-key",
          "sk-test123"
        )
      )
      assertTrue(
        parsed.isLeft,
        parsed.fold(
          e =>
            e.code == "RA-CFG" &&
              e.detail.contains("RAIDER_API_KEY") &&
              !e.detail.contains("sk-test123"), // redaction: value never echoed
          _ => false
        )
      )
    },
    test("SETUP-02: live provider without a key fails preflight before spend") {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          args = CliArgs
            .parse(
              List(
                "run",
                "--agent",
                "scout",
                "--input",
                "task-42",
                "--out",
                work.toString,
                "--provider",
                "openai",
                "--base-url",
                "not-a-url"
              )
            )
            .fold(e => throw e, identity)
          outcome <- HeadlessRunner.run(args, Map.empty) // no env key at all
          runJson = readRunJson(outcome.workDir)
        yield assertTrue(
          outcome.exitCode == 21, // ConfigInvalid: a typed preflight refusal
          runJson.status == raider.core.result.ExecutionStatus.Failed,
          runJson.output.isEmpty, // no spend, no output produced
          runJson.error.exists(e =>
            e.code == "RA-CFG" &&
              e.detail.contains("RAIDER_API_KEY") &&
              e.detail.contains("missing credentials")
          ),
          runJson.checks.exists(c =>
            c.id == "bundle-preflight" &&
              c.outcome == raider.core.result.CheckOutcome.Failed
          )
        )
      }
    },
    test("SETUP-01: live provider WITH an env key clears the credential gate") {
      // Hermetic (no network): the base URL is invalid, so Config.make fails
      // AFTER the credential preflight — proving the env key was resolved and
      // accepted (the error is about the URL, not about missing credentials).
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          args = CliArgs
            .parse(
              List(
                "run",
                "--agent",
                "scout",
                "--input",
                "task-42",
                "--out",
                work.toString,
                "--provider",
                "openai",
                "--base-url",
                "not-a-url"
              )
            )
            .fold(e => throw e, identity)
          outcome <- HeadlessRunner.run(
            args,
            Map("RAIDER_API_KEY" -> "sk-env-42")
          )
          raw = Files.readString(outcome.workDir.resolve("run.json"), UTF_8)
          runJson = raw.fromJson[RunResult] match
            case Right(r) => r
            case Left(e) =>
              throw new IllegalStateException(s"bad run.json: $e")
        yield assertTrue(
          // the failure is the LATER base-URL validation, not a credential one
          runJson.error.exists(e =>
            e.detail.contains("baseUrl must be an http(s) URL")
          ),
          // redaction: the key never appears in the artifact
          !raw.contains("sk-env-42")
        )
      }
    },
    test("SETUP-01: mock provider without any key still works") {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          outcome <- HeadlessRunner.run(agentArgs(work), Map.empty)
          runJson = readRunJson(outcome.workDir)
        yield assertTrue(
          outcome.exitCode == 0,
          runJson.output.exists(o => o.json.contains("[mock] answer"))
        )
      }
    }
  ) @@ TestAspect.withLiveClock
  // RAI-178: HeadlessRunner.run's ensurings (awaitCaughtUp/drain) poll on the
  // ambient clock and freeze on the TestClock under suite load (fiber-verified

  // family root cause; docs/work/records/RAI-178.md). Assertions unchanged.
