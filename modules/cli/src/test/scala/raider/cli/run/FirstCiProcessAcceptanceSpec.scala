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
      raw.fromJson[raider.core.result.OutputPayload].isRight && raw.nonEmpty
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
    }
  ) @@ TestAspect.sequential

end FirstCiProcessAcceptanceSpec
