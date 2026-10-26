package raider.cli.run

import raider.core.result.{ArtifactEntry, RunResult}
import zio.json.DecoderOps
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.util.jar.{JarEntry, JarOutputStream, Manifest}
import scala.jdk.CollectionConverters.*

/** RAI-185 process-boundary acceptance for the documented First CI command. */
object FirstCiProcessAcceptanceSpec extends ZIOSpecDefault:

  private final case class Child(exit: Int, stdout: String, stderr: String)

  private def tempDir(): Path = Files.createTempDirectory("rai185-ci-")

  private def fixtureClassesDir: Path =
    val location = Path.of(
      classOf[
        raider.fixtures.DemoWorkflow
      ].getProtectionDomain.getCodeSource.getLocation.toURI
    )
    if Files.isDirectory(location) then location
    else
      throw new IllegalStateException(
        s"fixture classes are not a directory: $location"
      )

  private def makeBundle(dir: Path): Path =
    val jar = dir.resolve("pipelines.jar")
    val manifest = new Manifest()
    manifest.getMainAttributes.putValue("Manifest-Version", "1.0")
    val output = new JarOutputStream(Files.newOutputStream(jar), manifest)
    try
      val bundleManifest =
        """{"schemaVersion":1,"bundleId":"first-ci","entries":[{"name":"inspect","mainClass":"raider.fixtures.DemoWorkflow","version":1}],"declaredTrustLevel":"trusted-local"}"""
      output.putNextEntry(new JarEntry(BundleLoader.ManifestEntry))
      output.write(bundleManifest.getBytes(UTF_8))
      output.closeEntry()
      val root = fixtureClassesDir
      val stream = Files.walk(root)
      try
        stream.iterator().asScala.filter(Files.isRegularFile(_)).foreach {
          file =>
            val name = root.relativize(file).toString.replace('\\', '/')
            output.putNextEntry(new JarEntry(name))
            Files.copy(file, output)
            output.closeEntry()
        }
      finally stream.close()
    finally output.close()
    jar

  private def contextFile(dir: Path): Path =
    val file = dir.resolve("ci-context.json")
    Files.writeString(
      file,
      """{"ci":"generic","repo":"org/repo","ref":"refs/heads/main","sha":"deadbeef","pipeline":"pipeline-1","job":"inspect","trusted":false}""",
      UTF_8
    )
    file

  /** RAI-187: the directory holding THIS spec's compiled test classes, which
    * contain the lifecycle fixture workflows (FirstCiLifecycleFixtures.scala).
    */
  private def testClassesDir: Path =
    val location = Path.of(
      getClass.getProtectionDomain.getCodeSource.getLocation.toURI
    )
    if Files.isDirectory(location) then location
    else
      throw new IllegalStateException(
        s"test classes are not a directory: $location"
      )

  /** RAI-187 FCI-LIFE-01/02: a bundle carrying the lifecycle fixture entries
    * (long-sleep, spawn-sleep), jarred through the same ordinary path as
    * [[makeBundle]]. Only the two fixture class files are packed so the bundle
    * is deterministic.
    */
  private def makeLifecycleBundle(dir: Path): Path =
    val jar = dir.resolve("lifecycle.jar")
    val manifest = new Manifest()
    manifest.getMainAttributes.putValue("Manifest-Version", "1.0")
    val output = new JarOutputStream(Files.newOutputStream(jar), manifest)
    try
      val bundleManifest =
        """{"schemaVersion":1,"bundleId":"first-ci-lifecycle","entries":[{"name":"long-sleep","mainClass":"raider.cli.run.LongSleepWorkflow","version":1},{"name":"spawn-sleep","mainClass":"raider.cli.run.SpawnSleepWorkflow","version":1}],"declaredTrustLevel":"trusted-local"}"""
      output.putNextEntry(new JarEntry(BundleLoader.ManifestEntry))
      output.write(bundleManifest.getBytes(UTF_8))
      output.closeEntry()
      val root = testClassesDir
      Vector(
        "raider/cli/run/LongSleepWorkflow.class",
        "raider/cli/run/SpawnSleepWorkflow.class"
      ).foreach { name =>
        output.putNextEntry(new JarEntry(name))
        Files.copy(root.resolve(name), output)
        output.closeEntry()
      }
    finally output.close()
    jar

  private def child(
      argv: List[String],
      out: Path,
      env: Map[String, String] = Map.empty
  ): Child =
    val stdoutFile = out.resolveSibling(out.getFileName.toString + ".stdout")
    val stderrFile = out.resolveSibling(out.getFileName.toString + ".stderr")
    val java = Path.of(System.getProperty("java.home"), "bin", "java")
    val classpath = System.getProperty("java.class.path")
    val processBuilder = new ProcessBuilder(
      (java.toString :: "-cp" :: classpath :: "raider.cli.Main" :: argv)*
    )
    processBuilder.redirectOutput(stdoutFile.toFile)
    processBuilder.redirectError(stderrFile.toFile)
    val processEnv = processBuilder.environment()
    processEnv
      .keySet()
      .asScala
      .toList
      .filter(k =>
        k.startsWith("GITHUB_") || k.startsWith("CI_") || k.startsWith(
          "GITLAB_"
        )
      )
      .foreach(processEnv.remove)
    env.foreach((key, value) => processEnv.put(key, value))
    val process = processBuilder.start()
    process.getOutputStream.close()
    if !process.waitFor(90, TimeUnit.SECONDS) then
      process.destroyForcibly()
      throw new IllegalStateException(
        "raider child did not exit within 90 seconds"
      )
    val result = Child(
      process.exitValue(),
      Files.readString(stdoutFile, UTF_8),
      Files.readString(stderrFile, UTF_8)
    )
    Files.deleteIfExists(stdoutFile)
    Files.deleteIfExists(stderrFile)
    result

  /** RAI-187 FCI-LIFE-02: start the real CLI child WITHOUT waiting — the test
    * stays the parent and delivers the OS signal itself. Same launch discipline
    * as [[child]] (fresh JVM, closed stdin, separated stdout/stderr capture,
    * inherited CI variables stripped).
    */
  private def startChild(
      argv: List[String],
      out: Path,
      env: Map[String, String] = Map.empty
  ): java.lang.Process =
    val stdoutFile = out.resolveSibling(out.getFileName.toString + ".stdout")
    val stderrFile = out.resolveSibling(out.getFileName.toString + ".stderr")
    val java = Path.of(System.getProperty("java.home"), "bin", "java")
    val classpath = System.getProperty("java.class.path")
    val processBuilder = new ProcessBuilder(
      (java.toString :: "-cp" :: classpath :: "raider.cli.Main" :: argv)*
    )
    processBuilder.redirectOutput(stdoutFile.toFile)
    processBuilder.redirectError(stderrFile.toFile)
    val processEnv = processBuilder.environment()
    processEnv
      .keySet()
      .asScala
      .toList
      .filter(k =>
        k.startsWith("GITHUB_") || k.startsWith("CI_") || k.startsWith(
          "GITLAB_"
        )
      )
      .foreach(processEnv.remove)
    env.foreach((key, value) => processEnv.put(key, value))
    val process = processBuilder.start()
    process.getOutputStream.close()
    process

  /** Captured child output files (deleted by [[child]]; kept for the SIGTERM
    * case where the parent reads them after the signal death).
    */
  private def capturedOut(out: Path): (Path, Path) =
    (
      out.resolveSibling(out.getFileName.toString + ".stdout"),
      out.resolveSibling(out.getFileName.toString + ".stderr")
    )

  /** Bounded poll: does the process handle EXIT within `seconds`? */
  private def exitsWithin(handle: ProcessHandle, seconds: Long): Boolean =
    val deadline = System.currentTimeMillis() + seconds * 1000L
    while handle.isAlive && System.currentTimeMillis() < deadline do
      Thread.sleep(100)
    !handle.isAlive

  private def baseArgs(bundle: Path, context: Path, out: Path): List[String] =
    List(
      "run",
      "--bundle",
      bundle.toString,
      "--workflow",
      "inspect",
      "--ci",
      "generic",
      "--context",
      context.toString,
      "--input",
      "inspect the project",
      "--mock",
      "--out",
      out.toString
    )

  private def withoutContext(args: List[String]): List[String] =
    val index = args.indexOf("--context")
    if index < 0 then args else args.take(index) ++ args.drop(index + 2)

  /** RAI-187: First CI invocation against a lifecycle fixture workflow entry.
    */
  private def lifecycleArgs(
      bundle: Path,
      context: Path,
      out: Path,
      workflow: String,
      input: String
  ): List[String] =
    List(
      "run",
      "--bundle",
      bundle.toString,
      "--workflow",
      workflow,
      "--ci",
      "generic",
      "--context",
      context.toString,
      "--input",
      input,
      "--mock",
      "--out",
      out.toString
    )

  private def withCi(args: List[String], ci: String): List[String] =
    val index = args.indexOf("--ci")
    args.updated(index + 1, ci)

  private def files(root: Path): Vector[Path] =
    if !Files.exists(root) then return Vector.empty
    val stream = Files.walk(root)
    try stream.iterator().asScala.filter(Files.isRegularFile(_)).toVector
    finally stream.close()

  private def artifacts(out: Path): Vector[Path] =
    files(out)

  /** RAI-187: immediate `work-<uuid>` run directories under an output root. */
  private def runDirsOf(out: Path): Vector[Path] =
    if !Files.exists(out) then Vector.empty
    else
      val stream = Files.list(out)
      try
        stream
          .iterator()
          .asScala
          .filter(p =>
            Files.isDirectory(p) &&
              p.getFileName.toString.startsWith("work-")
          )
          .toVector
      finally stream.close()

  private def noTemporaryFiles(runDir: Path): Boolean =
    val stream = Files.walk(runDir)
    try
      !stream.iterator().asScala.exists { path =>
        Files.isRegularFile(path) && path.getFileName.toString.endsWith(".tmp")
      }
    finally stream.close()

  private def validateArtifacts(out: Path): Boolean =
    val all = artifacts(out)
    val run = all.find(_.getFileName.toString == "run.json")
    val result = all.find(_.getFileName.toString == "result.json")
    val manifestFile = all.find(_.getFileName.toString == "artifacts.json")
    val names = all.map(_.getFileName.toString).toSet
    val required = Set(
      "run.json",
      "result.json",
      "events.jsonl",
      "checks.junit.xml",
      "summary.md",
      "artifacts.json"
    )
    (required subsetOf names) && run.exists { file =>
      Files.readString(file, UTF_8).fromJson[RunResult].isRight
    } && result.exists { file =>
      val raw = Files.readString(file, UTF_8)
      // RAI-187: result.json carries the codec-identified OutputPayload on a
      // successful run, and the typed error verdict document on a failed run
      // (FCI-LIFE-01 deadline path) — both are honest complete reports.
      raw.nonEmpty && (
        raw.fromJson[raider.core.result.OutputPayload].isRight || {
          val decoded = raw.fromJson[zio.json.ast.Json]
          decoded.isRight && raw.contains("\"code\"") && raw.contains(
            "\"taskVerdict\""
          )
        }
      )
    } && manifestFile.exists { file =>
      val raw = Files.readString(file, UTF_8)
      raw.fromJson[List[ArtifactEntry]].exists { entries =>
        val listedPaths = entries.map(_.path).toSet
        val completedFiles = files(file.getParent)
          .filterNot(
            _.toAbsolutePath.normalize == file.toAbsolutePath.normalize
          )
        val completedPaths = completedFiles
          .map(path =>
            file.getParent.relativize(path).toString.replace('\\', '/')
          )
          .toSet
        noTemporaryFiles(file.getParent) && entries.nonEmpty && entries.forall {
          entry =>
            val artifact = file.getParent.resolve(entry.path)
            Files.isRegularFile(artifact) &&
            Files.size(artifact) == entry.sizeBytes &&
            sha256(Files.readAllBytes(artifact)) == entry.sha256
        } && listedPaths == completedPaths &&
        !entries.exists(_.path.endsWith("artifacts.json"))
      }
    }

  private def sentinelAbsentFromArtifacts(out: Path): Boolean =
    artifacts(out).forall { file =>
      val text = Files.readString(file, UTF_8)
      !text.contains("fixture-secret-must-not-leak") &&
      !text.contains("CI_TOKEN_SENTINEL_7d59")
    }

  private def sha256(bytes: Array[Byte]): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"$b%02x")
      .mkString

  private def ciEnv(ci: String): Map[String, String] =
    if ci == "github" then
      Map(
        "GITHUB_REPOSITORY" -> "org/repo",
        "GITHUB_REF" -> "refs/heads/main",
        "GITHUB_SHA" -> "deadbeef",
        "GITHUB_WORKFLOW" -> "pipeline-1",
        "GITHUB_JOB" -> "inspect",
        "GITHUB_TOKEN" -> "fixture-secret-must-not-leak"
      )
    else
      Map(
        "CI_PROJECT_PATH" -> "org/repo",
        "CI_COMMIT_REF_NAME" -> "refs/heads/main",
        "CI_COMMIT_SHA" -> "deadbeef",
        "CI_PIPELINE_ID" -> "pipeline-1",
        "CI_JOB_NAME" -> "inspect",
        "CI_JOB_TOKEN" -> "fixture-secret-must-not-leak"
      )

  def spec = suite("RAI-185 First CI real-process acceptance")(
    test(
      "FCI-REPORT-01/03/04: check evidence, exit precedence, and hostile report values"
    ) {
      import CiReport.{Failure, TaskVerdict}
      import raider.core.result.{CheckOutcome, CheckResult, ExitCodes}
      val blocking = CheckResult("compile", CheckOutcome.Failed, "check failed")
      val advisory = CheckResult("review", CheckOutcome.Errored, "finding")
      val advisoryPolicy = List(
        CiReport.CheckPolicy("compile"),
        CiReport.CheckPolicy("review", advisory = true)
      )
      val policyCases = List(
        (
          List(CheckResult("compile", CheckOutcome.Passed, "exit 0"), advisory),
          advisoryPolicy,
          TaskVerdict.Advisory
        ),
        (
          List(blocking),
          List(CiReport.CheckPolicy("compile")),
          TaskVerdict.Blocking
        ),
        (
          List(advisory),
          List(CiReport.CheckPolicy("review")),
          TaskVerdict.Blocking
        ),
        (
          List(CheckResult("review", CheckOutcome.Skipped, "not run")),
          List(CiReport.CheckPolicy("review", advisory = true)),
          TaskVerdict.Blocking
        ),
        (List(advisory), Nil, TaskVerdict.Blocking),
        (
          List(
            advisory,
            CheckResult("unregistered", CheckOutcome.Passed, "exit 0")
          ),
          advisoryPolicy,
          TaskVerdict.Blocking
        )
      )
      val legacyDeclarationDefaultsBlocking =
        """{"name":"legacy","argv":["true"]}"""
          .fromJson[raider.cli.chat.NamedCheck]
          .exists(check => !check.advisory)
      val policyDecisions =
        policyCases.map { case (evidence, policy, expected) =>
          val decision = CiReport.classify(evidence, policy)
          decision.verdict == expected && decision.policyDigest.matches(
            "[0-9a-f]{64}"
          )
        }
      val table = List(
        (Set(Failure.Success), ExitCodes.Success),
        (Set(Failure.BlockingCheck), ExitCodes.TaskRejected),
        (Set(Failure.Deadline), ExitCodes.DeadlineExceeded),
        (Set(Failure.PolicyDenial), ExitCodes.PolicyDenied),
        (Set(Failure.Uncertainty), ExitCodes.Uncertain),
        (Set(Failure.WriteFailure), ExitCodes.ArtifactWriteFailure),
        (
          Set(Failure.WriteFailure, Failure.Uncertainty),
          ExitCodes.ArtifactWriteFailure
        ),
        (
          Set(Failure.WriteFailure, Failure.BlockingCheck),
          ExitCodes.ArtifactWriteFailure
        ),
        (Set(Failure.Uncertainty, Failure.Deadline), ExitCodes.Uncertain),
        (
          Set(Failure.PolicyDenial, Failure.Deadline),
          ExitCodes.DeadlineExceeded
        ),
        (
          Set(Failure.WriteFailure, Failure.CancellationSignal(143)),
          ExitCodes.SigTerm
        ),
        (Set(Failure.Preflight(21), Failure.BlockingCheck), 21)
      )
      assertTrue(
        policyDecisions.forall(identity),
        legacyDeclarationDefaultsBlocking,
        CiReport.exitCode(TaskVerdict.Advisory) == ExitCodes.Success,
        CiReport.exitCode(TaskVerdict.Blocking) == ExitCodes.TaskRejected,
        CiReport
          .classify(
            List(advisory),
            List(CiReport.CheckPolicy("review", advisory = true))
          )
          .policyDigest != CiReport.policyDigest(
          List(CiReport.CheckPolicy("review"))
        ),
        CiReport
          .taskVerdict(List(blocking), Set("review")) == TaskVerdict.Blocking,
        CiReport
          .taskVerdict(List(advisory), Set("review")) == TaskVerdict.Advisory,
        CiReport.taskVerdict(Nil, Set("review")) == TaskVerdict.Passed,
        table.forall((causes, expected) =>
          CiReport.exitCode(causes) == expected
        ),
        CiReport.xmlEscape("<&\"'\u0001") == "&lt;&amp;&quot;'�", {
          val hostile = "<&>\"' ::error::test\nnext"
          val junit = CiReport.junit(
            List(
              CheckResult(
                "check<&>\"'",
                CheckOutcome.Failed,
                hostile
              )
            )
          )
          junit.contains("name=\"check&lt;&amp;&gt;&quot;'\"") &&
          junit.contains(
            "&lt;&amp;&gt;&quot;' ::error::test\nnext"
          ) &&
          !junit.contains("<failure message=\"<&>")
        },
        CiReport.neutralizeWorkflowCommands(
          "::error::x%\n"
        ) == "%3A%3Aerror%3A%3Ax%25%0A",
        CiReport.safeRelativePath("checks.junit.xml"),
        !CiReport.safeRelativePath("../outside"),
        !CiReport.safeRelativePath("/absolute/path"),
        !CiReport.safeRelativePath("C:\\outside")
      )
    },
    test(
      "FCI-CLI-01/02 and FCI-CTX-01: real CLI, artifact integrity, and environment parity"
    ) {
      val work = tempDir()
      val bundle = makeBundle(work)
      val context = contextFile(work)
      val genericOut = work.resolve("artifacts-generic")
      val generic = child(baseArgs(bundle, context, genericOut), genericOut)
      val githubOut = work.resolve("artifacts-github")
      val githubArgs = withCi(
        withoutContext(baseArgs(bundle, context, githubOut)),
        "github"
      )
      val github = child(githubArgs, githubOut, ciEnv("github"))
      val gitlabOut = work.resolve("artifacts-gitlab")
      val gitlab = child(
        withCi(withoutContext(baseArgs(bundle, context, gitlabOut)), "gitlab"),
        gitlabOut,
        ciEnv("gitlab")
      )
      val childResults = Vector(generic, github, gitlab)
      val resultDocs = Vector(genericOut, githubOut, gitlabOut).map { out =>
        val file =
          artifacts(out).find(_.getFileName.toString == "result.json").get
        Files.readString(file, UTF_8)
      }
      val successfulVerdict = resultDocs.forall { raw =>
        raw.contains("\"taskVerdict\":\"trusted-checks-passed\"") &&
        raw.contains("\"checkPolicyDigest\":\"sha256-") &&
        raw.contains(
          "\"checkEvidence\":[\"bundle-preflight\",\"run-outcome\",\"run-result-schema\"]"
        )
      }
      val outputsArePlain = childResults.forall { result =>
        !result.stdout.exists(c => c == '\u001b' || c == '\u009b') &&
        !result.stderr.exists(c => c == '\u001b' || c == '\u009b') &&
        !result.stdout.toLowerCase.contains("prompt") &&
        !result.stderr.toLowerCase.contains("prompt") &&
        !result.stdout.contains("fixture-secret-must-not-leak") &&
        !result.stderr.contains("fixture-secret-must-not-leak")
      }
      val parallelOutA = work.resolve("artifacts-parallel-a")
      val parallelOutB = work.resolve("artifacts-parallel-b")
      val parallelA = java.util.concurrent.CompletableFuture.supplyAsync(() =>
        child(baseArgs(bundle, context, parallelOutA), parallelOutA)
      )
      val parallelB = java.util.concurrent.CompletableFuture.supplyAsync(() =>
        child(baseArgs(bundle, context, parallelOutB), parallelOutB)
      )
      val parallelResults = Vector(
        parallelA.get(90, TimeUnit.SECONDS),
        parallelB.get(90, TimeUnit.SECONDS)
      )
      assertTrue(
        childResults.forall(_.exit == 0),
        childResults.forall(_.stdout.contains("\"exitCode\":0")),
        outputsArePlain,
        successfulVerdict,
        resultDocs.distinct.size == 1,
        validateArtifacts(genericOut),
        validateArtifacts(githubOut),
        validateArtifacts(gitlabOut),
        sentinelAbsentFromArtifacts(githubOut),
        sentinelAbsentFromArtifacts(gitlabOut),
        Vector(genericOut, githubOut, gitlabOut).forall { out =>
          val raw = Files.readString(
            artifacts(out).find(_.getFileName.toString == "run.json").get,
            UTF_8
          )
          raw.contains("\"ci\"") && raw.contains("\"repo\":\"<redacted>\"")
        },
        parallelResults.forall(_.exit == 0),
        validateArtifacts(parallelOutA),
        validateArtifacts(parallelOutB),
        artifacts(parallelOutA)
          .map(_.toAbsolutePath.normalize)
          .toSet
          .intersect(
            artifacts(parallelOutB).map(_.toAbsolutePath.normalize).toSet
          )
          .isEmpty
      )
    },
    test(
      "FCI-CTX-02: malformed context, bundle, and missing input fail before dispatch"
    ) {
      val work = tempDir()
      val bundle = makeBundle(work)
      val goodContext = contextFile(work)
      val badContext = work.resolve("bad-context.json")
      Files.writeString(badContext, "{ malformed", UTF_8)
      val contextOut = work.resolve("bad-context-out")
      val contextResult = child(
        baseArgs(bundle, badContext, contextOut),
        contextOut
      )
      val badBundle = work.resolve("bad-bundle.jar")
      Files.writeString(badBundle, "not a jar", UTF_8)
      val bundleOut = work.resolve("bad-bundle-out")
      val bundleResult = child(
        baseArgs(badBundle, goodContext, bundleOut),
        bundleOut
      )
      val inputOut = work.resolve("missing-input-out")
      val missingInputArgs = baseArgs(bundle, goodContext, inputOut)
        .filterNot(_ == "inspect the project")
        .filterNot(_ == "--input")
      val inputResult = child(missingInputArgs, inputOut)
      def noLedger(out: Path): Boolean =
        artifacts(out).find(_.getFileName.toString == "run.json").exists {
          file =>
            !Files.readString(file, UTF_8).contains("\"accounting\"")
        }
      def blockingVerdict(out: Path): Boolean =
        artifacts(out)
          .find(_.getFileName.toString == "result.json")
          .exists(file =>
            Files
              .readString(file, UTF_8)
              .contains("\"taskVerdict\":\"task-failed\"") &&
              Files
                .readString(file, UTF_8)
                .contains(
                  "\"checkEvidence\":[\"bundle-preflight\",\"run-outcome\",\"run-result-schema\"]"
                )
          )
      assertTrue(
        contextResult.exit == 21 && noLedger(contextOut) &&
          blockingVerdict(contextOut),
        bundleResult.exit == 21 && noLedger(bundleOut),
        inputResult.exit == 21 && noLedger(inputOut),
        contextResult.stdout + contextResult.stderr != "",
        bundleResult.stdout + bundleResult.stderr != ""
      )
    },
    test(
      "FCI-REPORT-04: hostile workflow output and JUnit check evidence are escaped"
    ) {
      val work = tempDir()
      val bundle = makeBundle(work)
      val context = contextFile(work)
      val out = work.resolve("hostile-output")
      val hostile = "<&>\"' ::error::workflow\n::warning::next"
      val args = baseArgs(bundle, context, out)
      val inputIndex = args.indexOf("inspect the project")
      val hostileArgs = args.updated(inputIndex, hostile)
      val result = child(hostileArgs, out, ciEnv("github"))
      val summary = Files.readString(
        artifacts(out).find(_.getFileName.toString == "summary.md").get,
        UTF_8
      )
      val junit = Files.readString(
        artifacts(out).find(_.getFileName.toString == "checks.junit.xml").get,
        UTF_8
      )
      assertTrue(
        result.exit == 0,
        summary.contains(
          "&lt;&amp;&gt;&quot;' %3A%3Aerror%3A%3Aworkflow %3A%3Awarning%3A%3Anext"
        ),
        !summary.contains("::error::"),
        !summary.contains("::warning::"),
        junit.contains("<testsuite"),
        sentinelAbsentFromArtifacts(out),
        validateArtifacts(out)
      )
    },
    test(
      "FCI-REPORT-03: a real artifact preflight write failure cannot be green"
    ) {
      val work = tempDir()
      val bundle = makeBundle(work)
      val context = contextFile(work)
      val out = work.resolve("output-is-a-file")
      Files.writeString(out, "not a directory", UTF_8)
      val result = child(baseArgs(bundle, context, out), out)
      assertTrue(
        result.exit == 27,
        result.stdout.isEmpty,
        result.stderr.contains("no complete report is available"),
        Files.isRegularFile(out)
      )
    },
    test(
      "FCI-LIFE-01: a real deadline run exits 24 with truthful deadline artifacts and no fabricated success"
    ) {
      val work = tempDir()
      val bundle = makeLifecycleBundle(work)
      val context = contextFile(work)
      val out = work.resolve("deadline-out")
      // The fixture workflow would sleep 120000 ms; --deadline 1 interrupts it
      // in ~1 s inside the admitted slot, so the exit is observed at the REAL
      // process boundary (java -cp ... raider.cli.Main run ...).
      val args =
        lifecycleArgs(bundle, context, out, "long-sleep", "120000") ++
          List("--deadline", "1")
      val result = child(args, out, ciEnv("generic"))
      val runRaw = Files.readString(
        artifacts(out).find(_.getFileName.toString == "run.json").get,
        UTF_8
      )
      val resultRaw = Files.readString(
        artifacts(out).find(_.getFileName.toString == "result.json").get,
        UTF_8
      )
      val summary = Files.readString(
        artifacts(out).find(_.getFileName.toString == "summary.md").get,
        UTF_8
      )
      assertTrue(
        result.exit == 24,
        result.stdout.contains("\"exitCode\":24"),
        !result.stdout.contains("\"exitCode\":0"),
        runRaw.contains("\"exitCode\":24"),
        runRaw.toLowerCase.contains("\"status\":\"failed\""),
        runRaw.contains("RA-DEADLINE"),
        // the real ledger snapshot reached run.json despite (because of) the
        // failed admitted slot
        runRaw.contains("accounting"),
        !resultRaw.contains("trusted-checks-passed"),
        summary.contains("FAILED"),
        summary.contains("RA-DEADLINE"),
        validateArtifacts(out),
        runDirsOf(out).forall(noTemporaryFiles),
        sentinelAbsentFromArtifacts(out)
      )
    },
    test(
      "FCI-LIFE-01: invalid --deadline values are typed parse-time refusals before any dispatch"
    ) {
      val work = tempDir()
      val bundle = makeLifecycleBundle(work)
      val context = contextFile(work)
      val invalid = Vector("0", "-3", "abc", "1.5")
      val results = invalid.map { raw =>
        val out = work.resolve(s"deadline-invalid-$raw")
        val args =
          lifecycleArgs(bundle, context, out, "inspect", "hello") ++
            List("--deadline", raw)
        (child(args, out, ciEnv("generic")), out)
      }
      // --deadline bounds the BUNDLE program only; combining it with --agent
      // is a typed refusal, never a silently ignored flag.
      val agentOut = work.resolve("deadline-agent")
      val agentChild = child(
        List(
          "run",
          "--agent",
          "scout",
          "--deadline",
          "5",
          "--mock",
          "--out",
          agentOut.toString
        ),
        agentOut,
        ciEnv("generic")
      )
      assertTrue(
        results.forall { case (result, out) =>
          result.exit == 21 &&
          result.stderr.contains("RA-CFG") &&
          result.stderr.contains("--deadline") &&
          result.stdout.isEmpty &&
          // refused BEFORE dispatch: no artifact tree was even created
          artifacts(out).isEmpty
        },
        agentChild.exit == 21,
        agentChild.stderr.contains("RA-CFG"),
        agentChild.stderr.contains("--agent"),
        artifacts(agentOut).isEmpty
      )
    },
    test(
      "FCI-LIFE-02: parent-delivered SIGTERM stops the real child with bounded cleanup, exit 143, honest partial artifacts, and no orphan"
    ) {
      val work = tempDir()
      val bundle = makeLifecycleBundle(work)
      val context = contextFile(work)
      val out = work.resolve("sigterm-out")
      val marker = work.resolve("grandchild.pid")
      val args = lifecycleArgs(
        bundle,
        context,
        out,
        "spawn-sleep",
        s"$marker|300"
      )
      val process = startChild(args, out, ciEnv("generic"))
      // Wait until the fixture recorded the grandchild pid: the run is then
      // executing REAL work and owns a live process to clean up.
      var pid = Option.empty[Long]
      val pidDeadline = System.currentTimeMillis() + 30000L
      while pid.isEmpty && System.currentTimeMillis() < pidDeadline do
        if Files.exists(marker) then
          pid = Files
            .readString(marker, UTF_8)
            .stripPrefix("pid=")
            .trim
            .toLongOption
        if pid.isEmpty then Thread.sleep(100)
      val grandchildHandle = pid
        .flatMap { value =>
          val handle = ProcessHandle.of(value)
          if handle.isPresent then Some(handle.get) else None
        }
        .getOrElse(
          throw new IllegalStateException(
            "spawn-sleep did not record a grandchild pid within 30 seconds"
          )
        )
      val grandchildAliveAtStart = grandchildHandle.isAlive
      // PARENT-DELIVERED OS SIGNAL: Process.destroy sends SIGTERM on POSIX.
      process.destroy()
      val exited = process.waitFor(30, TimeUnit.SECONDS)
      val exitValue = if exited then process.exitValue() else -1
      // Bounded owned-child cleanup: the grandchild is destroyed by the
      // runner's interrupt within the stop window (no orphan survives the CLI).
      val grandchildGone = !exitsWithin(grandchildHandle, 10)
      val (stdoutFile, stderrFile) = capturedOut(out)
      val stdout =
        if Files.exists(stdoutFile) then Files.readString(stdoutFile, UTF_8)
        else ""
      val stderr =
        if Files.exists(stderrFile) then Files.readString(stderrFile, UTF_8)
        else ""
      Files.deleteIfExists(stdoutFile)
      Files.deleteIfExists(stderrFile)
      // Honest partial artifacts: the streamed events tail persists, but NO
      // fabricated completion record (run.json/result.json) may exist.
      val dirs = runDirsOf(out)
      val eventsPresent = dirs.nonEmpty && dirs.forall { dir =>
        val events = dir.resolve("events.jsonl")
        Files.exists(events) && Files.size(events) > 0
      }
      val hasRunJson =
        artifacts(out).exists(_.getFileName.toString == "run.json")
      val hasResultJson =
        artifacts(out).exists(_.getFileName.toString == "result.json")
      assertTrue(
        grandchildAliveAtStart,
        exited,
        exitValue == 143,
        grandchildGone,
        eventsPresent,
        !hasRunJson,
        !hasResultJson,
        !stdout.contains("exitCode"),
        !stdout.contains("trusted-checks-passed"),
        !stderr.contains("trusted-checks-passed")
      )
    }
  ) @@ TestAspect.sequential

end FirstCiProcessAcceptanceSpec
