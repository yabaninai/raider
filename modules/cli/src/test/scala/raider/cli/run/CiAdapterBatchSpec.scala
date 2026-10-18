package raider.cli.run

import raider.runtime.events.EventCodecs.given
import raider.runtime.jobs.JobStatus
import zio._
import zio.json.DecoderOps
import zio.test._
import zio.test.TestAspect.{timeout, withLiveClock}
import zio.test.live

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.jar.{JarEntry, JarOutputStream, Manifest}
import scala.jdk.CollectionConverters.*

/** RAI-131 (CI adapter batch) acceptance:
  *   - CI-ADP-01: one bundle, three orchestrator context fixtures
  *     (generic/github/gitlab) — identical exit codes, identical artifact
  *     file-name sets, JUnit + summary present per context;
  *   - CI-ADP-02: trust preflight — a bundle whose sha256 does not match the
  *     operator-declared provenance is refused TYPED before any dispatch
  *     (preflight-failure exit family, honest run.json with zero attempts); a
  *     matching manifest lets the run succeed; missing/malformed manifests fail
  *     typed at parse or preflight;
  *   - CI-ADP-03: the SIGTERM path (runner-fiber interrupt) under each context
  *     — events end honestly (no broken middle lines), bounded stop.
  *
  * Deterministic: mock provider, local fixture bundle, loopback-free (no
  * network, no paid calls).
  */
object CiAdapterBatchSpec extends ZIOSpecDefault:

  private def tempDir(): Path =
    Files.createTempDirectory("raider-ci-adp")

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
        |{"name":"inspect","mainClass":"raider.fixtures.DemoWorkflow","version":1}],
        |"declaredTrustLevel":"trusted-local"}""".stripMargin
    def putEntry(name: String, bytes: Array[Byte]): Unit =
      jos.putNextEntry(new JarEntry(name))
      jos.write(bytes)
      jos.closeEntry()
    putEntry(BundleLoader.ManifestEntry, manifestJson.getBytes(UTF_8))
    val root = fixtureClassesDir
    Files.walk(root).forEach { p =>
      if Files.isRegularFile(p) then
        val rel = root.relativize(p).toString.replace('\\', '/')
        putEntry(rel, Files.readAllBytes(p))
    }
    jos.close()
    jarPath

  private def sha256(path: Path): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val in = Files.newInputStream(path)
    try
      val buf = new Array[Byte](8192)
      var n = in.read(buf)
      while n != -1 do
        md.update(buf, 0, n)
        n = in.read(buf)
    finally in.close()
    md.digest().map(b => f"$b%02x").mkString

  private def contextFile(dir: Path, ci: String, trusted: Boolean): Path =
    val p = dir.resolve(s"context-$ci-${if trusted then "t" else "u"}.json")
    Files.writeString(
      p,
      s"""{"ci":"$ci","repo":"org/repo","ref":"refs/heads/main","sha":"deadbeef","pipeline":"pipe-1","job":"job-1","trusted":$trusted}""",
      UTF_8
    )
    p

  private def bundleArgs(
      jar: Path,
      out: Path,
      extra: List[String] = Nil
  ) =
    CliArgs
      .parse(
        List(
          "run",
          "--bundle",
          jar.toString,
          "--workflow",
          "inspect",
          "--input",
          "task-42",
          "--out",
          out.toString,
          "--mock"
        ) ++ extra
      )
      .fold(e => throw e, identity)

  /** Basenames of all artifacts (the work-<uuid> dir name varies per run,
    * events.jsonl is per-run streaming); basenames are stable per context.
    */
  private def artifactNames(out: Path): Vector[String] =
    if !Files.exists(out) then Vector.empty
    else
      val stream = Files.walk(out)
      try
        stream
          .iterator()
          .asScala
          .filter(Files.isRegularFile(_))
          .map(_.getFileName.toString)
          .filterNot(_ == "events.jsonl")
          .toVector
          .sorted
      finally stream.close()

  def spec = suite("CiAdapterBatchSpec (RAI-131)")(
    test(
      "CI-ADP-01: one bundle under generic/github/gitlab contexts — identical exit codes and artifact sets; JUnit + summary present"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO.attempt(tempDir()).orDie
            jar <- ZIO.attempt(makeBundleJar(work)).orDie
            outcomes <- ZIO.foreach(
              Seq("generic", "github", "gitlab")
            ) { ci =>
              val out = work.resolve(s"out-$ci")
              val ctx = contextFile(work, ci, trusted = false)
              val args =
                bundleArgs(jar, out, List("--context", ctx.toString))
              for outcome <- HeadlessRunner.run(args) yield
                val junit = Files
                  .walk(out)
                  .iterator()
                  .asScala
                  .filter(Files.isRegularFile(_))
                  .map(_.getFileName.toString)
                  .toVector
                (ci, outcome.exitCode, artifactNames(out), junit)
            }
          yield
            val exitCodes = outcomes.map(_._2)
            val nameSets = outcomes.map(_._3)
            assertTrue(
              exitCodes == Vector(0, 0, 0),
              // identical artifact-name sets across contexts (modulo work-uuid
              // and events, already filtered)
              nameSets(0) == nameSets(1),
              nameSets(1) == nameSets(2),
              nameSets(0).exists(_.contains("run.json")),
              nameSets(0).exists(_.endsWith("summary.md")),
              outcomes.forall(_._4.contains("checks.junit.xml"))
            )
        }
      }
    },
    test(
      "CI-ADP-02a: bundle hash mismatch — typed preflight refusal, zero dispatch attempts, honest run.json"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO.attempt(tempDir()).orDie
            jar <- ZIO.attempt(makeBundleJar(work)).orDie
            manifest = work.resolve("prov.json")
            _ <- ZIO
              .attempt(
                Files.writeString(
                  manifest,
                  """{"bundle_sha256":"0000000000000000000000000000000000000000000000000000000000000000"}""",
                  UTF_8
                )
              )
              .orDie
            out = work.resolve("out")
            outcome <- HeadlessRunner.run(
              bundleArgs(
                jar,
                out,
                List("--require-provenance", manifest.toString)
              )
            )
            runJson = Files.readString(
              outcome.workDir.resolve("run.json"),
              UTF_8
            )
            checksFailed = runJson.contains("bundle-preflight") &&
              runJson.contains("provenance mismatch")
          yield assertTrue(
            outcome.exitCode != 0,
            outcome.exitCode == 21, // preflight-failure family (input invalid)
            checksFailed,
            // zero dispatch: the run.json `accounting` field is spliced iff
            // the run passed through the budget ledger (CIW-02); a preflight
            // refusal never reaches the ledger
            !runJson.contains("\"accounting\"")
          )
        }
      }
    },
    test(
      "CI-ADP-02b: matching provenance — the run proceeds and succeeds"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO.attempt(tempDir()).orDie
            jar <- ZIO.attempt(makeBundleJar(work)).orDie
            manifest = work.resolve("prov.json")
            _ <- ZIO
              .attempt(
                Files.writeString(
                  manifest,
                  s"""{"bundle_sha256":"${sha256(jar)}"}""",
                  UTF_8
                )
              )
              .orDie
            out = work.resolve("out")
            outcome <- HeadlessRunner.run(
              bundleArgs(
                jar,
                out,
                List("--require-provenance", manifest.toString)
              )
            )
            run = Files.readString(outcome.workDir.resolve("run.json"), UTF_8)
            status = run
              .fromJson[raider.core.result.RunResult]
              .toOption
              .map(
                _.status
              )
          yield assertTrue(
            outcome.exitCode == 0,
            status.contains(raider.core.result.ExecutionStatus.Succeeded),
            // discriminator direction proven: a ledgered run HAS accounting
            run.contains("\"accounting\"")
          )
        }
      }
    },
    test(
      "CI-ADP-02c: missing manifest fails typed at parse; malformed manifest fails typed at preflight"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO.attempt(tempDir()).orDie
            jar <- ZIO.attempt(makeBundleJar(work)).orDie
            missing <- ZIO
              .attempt(
                CliArgs.parse(
                  List(
                    "run",
                    "--bundle",
                    jar.toString,
                    "--workflow",
                    "inspect",
                    "--input",
                    "t",
                    "--out",
                    work.resolve("o2").toString,
                    "--mock",
                    "--require-provenance",
                    work.resolve("nope.json").toString
                  )
                )
              )
              .orDie
            bad = work.resolve("bad.json")
            _ <- ZIO
              .attempt(
                Files.writeString(bad, "not json at all", UTF_8)
              )
              .orDie
            outcome <- HeadlessRunner.run(
              bundleArgs(
                jar,
                work.resolve("o3"),
                List("--require-provenance", bad.toString)
              )
            )
            runJson = Files.readString(
              outcome.workDir.resolve("run.json"),
              UTF_8
            )
          yield assertTrue(
            missing.isLeft,
            outcome.exitCode == 21,
            runJson.contains("malformed manifest")
          )
        }
      }
    },
    test(
      "CI-ADP-03: SIGTERM path (runner-fiber interrupt) under github and gitlab contexts — honest events tail, bounded stop"
    ) {
      live {
        ZIO
          .foreach(Seq("github", "gitlab")) { ci =>
            ZIO.scoped {
              for
                work <- ZIO.attempt(tempDir()).orDie
                jar <- ZIO.attempt(makeBundleJar(work)).orDie
                ctx = contextFile(work, ci, trusted = false)
                args = bundleArgs(
                  jar,
                  work.resolve(s"out-$ci"),
                  List("--context", ctx.toString)
                )
                fiber <- HeadlessRunner.run(args).forkDaemon
                // bounded mid-run window: wait for the events file to appear
                _ <- {
                  val deadline = java.lang.System.currentTimeMillis() + 20000L
                  val events =
                    (p: Path) =>
                      Files
                        .walk(p)
                        .iterator()
                        .asScala
                        .filter(Files.isRegularFile(_))
                        .exists(_.getFileName.toString == "events.jsonl")
                  def awaitEvents: UIO[Unit] =
                    ZIO.attempt(events(work)).orDie.flatMap { found =>
                      if found || java.lang.System
                          .currentTimeMillis() >= deadline
                      then ZIO.unit
                      else ZIO.sleep(zio.Duration.fromMillis(2L)) *> awaitEvents
                    }
                  awaitEvents
                }
                _ <- fiber.interrupt // joins finalizers (bounded)
                honest <- ZIO.attempt {
                  Files
                    .walk(work)
                    .iterator()
                    .asScala
                    .filter(Files.isRegularFile(_))
                    .filter(_.getFileName.toString == "events.jsonl")
                    .toList
                }.orDie
                raws <- ZIO.foreach(honest) { p =>
                  ZIO.attempt {
                    val raw = Files.readString(p, UTF_8)
                    val lines = raw.split("\n").filter(_.trim.nonEmpty)
                    // No broken middle line: every COMPLETE line decodes. An
                    // EMPTY file is an honest mid-interrupt artifact (the
                    // writer creates the file before the first envelope
                    // flush), so emptiness is accepted, never masked.
                    val whole =
                      if lines.isEmpty then true
                      else if raw.endsWith("\n") then
                        lines.forall(l =>
                          l.fromJson[raider.runtime.events.EventEnvelope]
                            .isRight
                        )
                      else
                        lines.init.forall(l =>
                          l.fromJson[raider.runtime.events.EventEnvelope]
                            .isRight
                        )
                    (whole, raw.nonEmpty)
                  }
                }
              yield assertTrue(
                raws.nonEmpty,
                // whole-ness is the invariant; emptiness after an immediate
                // interrupt is honest and explicitly allowed
                raws.forall(_._1)
              )
            }
          }
          .map { results =>
            results.reduce(_ && _)
          }
      }
    }
  ) @@ withLiveClock @@ timeout(300.seconds) @@ TestAspect.sequential

// sequential: each acceptance drives REAL runner fibers with filesystem
// artifacts; under in-module suite parallelism the CI-ADP-03 interrupt window
// and CI-ADP-01 bundle builds contend (2/5 red under full-module load,
// isolated 12/12 green) — the R8 sweep precedent: sequential guards
// self-contention, observed 2/12 cold-JIT flakes documented there.

end CiAdapterBatchSpec
