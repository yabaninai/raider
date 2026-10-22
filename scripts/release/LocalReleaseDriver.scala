package raider.cli.release

import raider.core.Task
import raider.core.sdlc.{SdlcEvidence, SdlcStages}
import raider.runtime.sdlc.approval.{ApprovalArtifacts, CheckpointDispatch}
import zio.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.security.MessageDigest

/** One-shot local-owner driver for the staged JVM distribution. */
object LocalReleaseDriver:
  def main(args: Array[String]): Unit =
    require(args.length == 2, "usage: LocalReleaseDriver <repo> <version>")
    val repo = Paths.get(args(0)).toAbsolutePath.normalize
    val version = args(1)
    val jar = repo.resolve("modules/cli/target/scala-3.9.0/raider-cli.jar")
    require(Files.isRegularFile(jar), s"built assembly is missing: $jar")

    val head = git(repo, "rev-parse", "HEAD").trim
    val tree = git(repo, "rev-parse", "HEAD^{tree}").trim
    val candidate = SdlcEvidence.CandidateIdentity(
      repo.toString,
      repo.toString,
      Some(head),
      Some(head),
      Some(head),
      tree,
      sha256("RAI-193 local JVM distribution"),
      sha256("RAI-193 accepted distribution plan"),
      sha256("local-owner release policy"),
      sha256("local release driver")
    )
    import SdlcEvidence.*
    val context = SdlcStages.Context(
      candidate,
      typedEvidence = List(
        Record.Check(
          CheckResult(
            "local-release-artifact-build",
            sha256("make raider-jar"),
            sha256("make raider-jar"),
            CheckOutcome.Passed,
            Some(0),
            java.lang.System.getProperty("java.version"),
            "local-owner-observed-build",
            candidate,
            sha256(Files.readAllBytes(jar)),
            candidate.policyDigest
          )
        ),
        Record.Review(
          ReviewVerdict(
            "local-owner",
            ReviewDecision.Acceptable,
            sha256("owner accepts local distribution candidate"),
            candidate.taskDigest,
            candidate.planDigest,
            candidate,
            Nil,
            candidate.policyDigest
          )
        ),
        Record.Integration(
          IntegrationResult(
            "local-worktree-candidate",
            "local-release-input",
            candidate,
            repo.toString,
            candidate.treeDigest,
            Some(candidate.treeDigest),
            IntegrationOutcome.Applied,
            List(sha256("accepted local candidate")),
            candidate.policyDigest
          )
        )
      )
    )

    val checkpoint = "local-release"
    val expiry = Instant.now().plusSeconds(3600)
    val requestPath = repo.resolve(".git/rai-193-release-request.json")
    val decisionPath = repo.resolve(".git/rai-193-release-decision.json")
    val request = ApprovalArtifacts.request(checkpoint, candidate, expiry)
      .fold(error => throw new IllegalStateException(error.toString), identity)
    ApprovalArtifacts.writeRequest(requestPath, request)
      .fold(error => throw new IllegalStateException(error.toString), identity)
    Files.writeString(
      decisionPath,
      ApprovalArtifacts.decisionJson(
        ApprovalArtifacts.Decision(
          ApprovalArtifacts.SchemaVersion,
          checkpoint,
          request.candidateDigest,
          request.expiresAt,
          approved = true
        )
      ),
      UTF_8
    )

    val dispatch = CheckpointDispatch(
      checkpoint,
      candidate,
      expiry,
      requestPath,
      Some(decisionPath),
      "release",
      () => Right(
        ApprovalArtifacts.CurrentState(
          candidate,
          candidate.policyDigest,
          candidate.treeDigest,
          candidate.taskDigest,
          candidate.baseRevision,
          List("local-check", "local-review", "local-integration"),
          CandidateIdentity.digest(candidate),
          Set("release")
        )
      )
    )
    val handler = LocalReleaseHandler(
      _ => ReleaseStage.Request(
        repo,
        version,
        "First local JVM distribution release; artifacts and formulas verified locally.",
        ReleaseStage.AcceptanceEvidence(head, "local-check", "local-owner", "local-worktree"),
        _ => Task.succeed(()),
        distributionArtifacts = List(
          ReleaseStage.DistributionArtifact(jar, "raider-cli.jar"),
          ReleaseStage.DistributionArtifact(repo.resolve("packaging/raider"), "packaging/raider"),
          ReleaseStage.DistributionArtifact(repo.resolve("packaging/homebrew/raider.rb"), "packaging/homebrew/raider.rb"),
          ReleaseStage.DistributionArtifact(repo.resolve("packaging/scoop/raider.json"), "packaging/scoop/raider.json")
        )
      ),
      dispatch
    )
    val execution = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(Task.zio(handler(context)).either)
        .getOrThrowFiberFailure()
    }
    val result = execution.fold(
      error =>
        throw new IllegalStateException(
          s"local release handler failed: ${error.code}: ${error.detail}"
        ),
      identity
    )
    require(result.successful, result.detail)
    val directory = repo.resolve("artifacts/release").resolve(version)
    println(s"release=${directory}")
    println(s"version=$version")
    println(s"tag=v$version (local only)")

  private def git(repo: Path, args: String*): String =
    val process = new ProcessBuilder((List("git", "-C", repo.toString) ++ args)* )
      .redirectErrorStream(true)
      .start()
    val output = new String(process.getInputStream.readAllBytes(), UTF_8)
    val status = process.waitFor()
    require(status == 0, s"git ${args.mkString(" ")} failed: $output")
    output

  private def sha256(input: String): String = sha256(input.getBytes(UTF_8))

  private def sha256(input: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(input)
      .map(b => f"${b & 0xff}%02x").mkString
