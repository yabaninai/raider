package raider.cli.release

import zio.*
import zio.test.*
import raider.core.Task
import raider.core.sdlc.{SdlcEvidence, SdlcStages}
import raider.cli.run.SdlcArtifactSchema
import raider.runtime.sdlc.approval.{ApprovalArtifacts, CheckpointDispatch}

import java.nio.file.Files
import java.security.MessageDigest

object ReleaseStageSpec extends ZIOSpecDefault:

  def spec = suite("SDLC local release")(
    test(
      "SDLC-REL-01 creates a local tag and checksummed provenance artifacts"
    ) {
      withRepo { repo =>
        for
          head <- ReleaseStage.git(repo, List("rev-parse", "HEAD")).map(_.trim)
          evidence = ReleaseStage.AcceptanceEvidence(
            head,
            "passed-checks",
            "accepted-review",
            "merged"
          )
          result <- ReleaseStage.release(
            ReleaseStage.Request(
              repo,
              "1.2.3",
              "Release notes",
              evidence,
              _ => Task.succeed(())
            )
          )
          sum <- ZIO.attempt(
            Files.readString(result.directory.resolve("SHA256SUMS"))
          )
          changelogHash <- ZIO.attempt(
            hex(
              MessageDigest
                .getInstance("SHA-256")
                .digest(
                  Files.readAllBytes(result.directory.resolve("CHANGELOG.md"))
                )
            )
          )
          verify <- ReleaseStage.git(repo, List("tag", "--list", "v1.2.3"))
        yield assertTrue(
          result.tag == "v1.2.3",
          sum.contains(s"$changelogHash  CHANGELOG.md"),
          verify.trim == "v1.2.3"
        )
      }
    },
    test(
      "SDLC-REL-02 refuses malformed and duplicate versions without overwriting"
    ) {
      withRepo { repo =>
        for
          head <- ReleaseStage.git(repo, List("rev-parse", "HEAD")).map(_.trim)
          evidence = ReleaseStage.AcceptanceEvidence(
            head,
            "checks",
            "review",
            "integration"
          )
          bad <- ReleaseStage
            .release(
              ReleaseStage.Request(
                repo,
                "01.2.3",
                "notes",
                evidence,
                _ => Task.succeed(())
              )
            )
            .either
          badPrerelease <- ReleaseStage
            .release(
              ReleaseStage.Request(
                repo,
                "1.2.3-01",
                "notes",
                evidence,
                _ => Task.succeed(())
              )
            )
            .either
          first <- ReleaseStage
            .release(
              ReleaseStage.Request(
                repo,
                "1.2.3",
                "notes",
                evidence,
                _ => Task.succeed(())
              )
            )
            .either
          duplicate <- ReleaseStage
            .release(
              ReleaseStage.Request(
                repo,
                "1.2.3",
                "changed",
                evidence,
                _ => Task.succeed(())
              )
            )
            .either
          content <- ZIO.attempt(
            Files.readString(
              repo.resolve("artifacts/release/1.2.3/CHANGELOG.md")
            )
          )
        yield assertTrue(
          bad.isLeft,
          badPrerelease.isLeft,
          first.isRight,
          duplicate.isLeft,
          content.contains("notes"),
          !content.contains("changed")
        )
      }
    },
    test("SDLC-REL-03 cancellation leaves no release directory") {
      withRepo { repo =>
        for
          head <- ReleaseStage.git(repo, List("rev-parse", "HEAD")).map(_.trim)
          evidence = ReleaseStage.AcceptanceEvidence(
            head,
            "checks",
            "review",
            "integration"
          )
          entered <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          fiber <- ReleaseStage
            .release(
              ReleaseStage.Request(
                repo,
                "1.2.3",
                "notes",
                evidence,
                _ => Task(entered.succeed(()).unit *> release.await)
              )
            )
            .fork
          _ <- entered.await
          _ <- fiber.interrupt
          exists <- ZIO.attempt(
            Files.exists(repo.resolve("artifacts/release/1.2.3"))
          )
        yield assertTrue(!exists)
      }
    },
    test(
      "SDLC-REL-STAGE-03 materialization interruption reconciles staged artifacts without a tag"
    ) {
      withRepo { repo =>
        for
          head <- ReleaseStage.git(repo, List("rev-parse", "HEAD")).map(_.trim)
          entered <- Promise.make[Nothing, Unit]
          continue <- Promise.make[Nothing, Unit]
          fiber <- ReleaseStage
            .release(
              ReleaseStage.Request(
                repo,
                "1.2.4",
                "Interrupted materialization",
                ReleaseStage
                  .AcceptanceEvidence(head, "checks", "review", "integration"),
                _ => Task.succeed(()),
                () => Task(entered.succeed(()).unit *> continue.await)
              )
            )
            .fork
          _ <- entered.await
          exit <- fiber.interrupt
          tags <- ReleaseStage.git(repo, List("tag", "--list", "v1.2.4"))
          directoryExists <- ZIO.attempt(
            Files.exists(repo.resolve("artifacts/release/1.2.4"))
          )
          interrupted = exit.causeOption.flatMap(_.failureOption).collect {
            case value: ReleaseStage.Interrupted => value
          }
        yield assertTrue(
          interrupted.exists(
            _.reconciliation.artifacts.sorted == List(
              "CHANGELOG.md",
              "SHA256SUMS",
              "release.json"
            )
          ),
          interrupted.exists(!_.reconciliation.tagPresent),
          tags.trim.isEmpty,
          !directoryExists
        )
      }
    },
    test(
      "SDLC-REL-STAGE-01 adapter releases the accepted candidate and persists typed result"
    ) {
      withRepo { repo =>
        import SdlcEvidence.*
        import SdlcStages.*
        for
          head <- ReleaseStage.git(repo, List("rev-parse", "HEAD")).map(_.trim)
          candidate = CandidateIdentity(
            repo.toString,
            repo.toString,
            Some(head),
            Some(head),
            Some(head),
            "accepted-tree",
            "task",
            "plan",
            "policy",
            "config"
          )
          evidence = List(
            Record.Check(
              CheckResult(
                "checks",
                "definition",
                "argv",
                CheckOutcome.Passed,
                Some(0),
                "toolchain",
                "verifier",
                candidate,
                "output-digest",
                candidate.policyDigest
              )
            ),
            Record.Review(
              ReviewVerdict(
                "reviewer",
                ReviewDecision.Acceptable,
                "findings-digest",
                candidate.taskDigest,
                candidate.planDigest,
                candidate,
                Nil,
                candidate.policyDigest
              )
            ),
            Record.Integration(
              IntegrationResult(
                "operation",
                "local",
                candidate,
                "target",
                "preconditions",
                Some("observed"),
                IntegrationOutcome.Applied,
                Nil,
                candidate.policyDigest
              )
            )
          )
          context = Context(
            candidate,
            typedEvidence = evidence
          )
          dispatch <- approvedDispatch(repo, candidate, Set("release"))
          handler = LocalReleaseHandler(
            _ =>
              ReleaseStage.Request(
                repo,
                "1.2.3",
                "Adapter release fixture",
                ReleaseStage.AcceptanceEvidence(
                  head,
                  "checks",
                  "review",
                  "integration"
                ),
                _ => Task.succeed(())
              ),
            dispatch
          )
          result <- Task.zio(handler(context))
          directory = repo.resolve("artifacts/release/1.2.3")
          tag <- ReleaseStage.git(repo, List("tag", "--list", "v1.2.3"))
          records <- ZIO
            .fromEither(
              SdlcArtifactSchema.fromStageResults(List(result))
            )
            .mapError(error => new IllegalStateException(error.toString))
          run = SdlcArtifactSchema.addToRunJson(
            "{\"schemaVersion\":1,\"status\":\"succeeded\",\"checks\":[]}",
            records
          )
          _ <- ZIO.attempt(Files.writeString(repo.resolve("run.json"), run))
          persistedRun <- ZIO.attempt(
            Files.readString(repo.resolve("run.json"))
          )
          writer <- raider.cli.run.EventsJsonlWriter.open(
            repo.resolve("events.jsonl")
          )
          _ <- ZIO.foreachDiscard(records)(writer.appendSdlc)
          _ <- writer.close
          decodedRun = SdlcArtifactSchema.decodeRunJson(persistedRun)
          eventLines <- ZIO.attempt(
            Files
              .readAllLines(repo.resolve("events.jsonl"))
              .toArray
              .toVector
              .map(_.toString)
          )
          decodedEvents = SdlcArtifactSchema.decodeMixed(eventLines)
          releaseRecord = records.find(
            _.kind == SdlcArtifactSchema.Kind.LocalReleaseResult
          )
          roundtrip = releaseRecord.flatMap(record =>
            SdlcArtifactSchema
              .decode(SdlcArtifactSchema.encode(record))
              .toOption
          )
          changelogExists <- ZIO.attempt(
            Files.isRegularFile(directory.resolve("CHANGELOG.md"))
          )
          manifestExists <- ZIO.attempt(
            Files.isRegularFile(directory.resolve("release.json"))
          )
          sumsExists <- ZIO.attempt(
            Files.isRegularFile(directory.resolve("SHA256SUMS"))
          )
        yield assertTrue(
          result.successful,
          changelogExists,
          manifestExists,
          sumsExists,
          tag.trim == "v1.2.3",
          releaseRecord.nonEmpty,
          roundtrip == releaseRecord,
          decodedRun.exists(_.contains(releaseRecord.get)),
          decodedEvents.toOption.exists(_.exists {
            case Right(record) => releaseRecord.contains(record)
            case Left(_)       => false
          })
        )
      }
    },
    test(
      "SDLC-REL-STAGE-02 capability and checkpoint refusals have no release effects"
    ) {
      withRepo { repo =>
        import SdlcEvidence.*
        import SdlcStages.*
        for
          head <- ReleaseStage.git(repo, List("rev-parse", "HEAD")).map(_.trim)
          candidate = CandidateIdentity(
            repo.toString,
            repo.toString,
            Some(head),
            Some(head),
            Some(head),
            "accepted-tree",
            "task",
            "plan",
            "policy",
            "config"
          )
          evidence = List(
            Record.Check(
              CheckResult(
                "checks",
                "definition",
                "argv",
                CheckOutcome.Passed,
                Some(0),
                "toolchain",
                "verifier",
                candidate,
                "output",
                candidate.policyDigest
              )
            ),
            Record.Review(
              ReviewVerdict(
                "reviewer",
                ReviewDecision.Acceptable,
                "findings",
                candidate.taskDigest,
                candidate.planDigest,
                candidate,
                Nil,
                candidate.policyDigest
              )
            ),
            Record.Integration(
              IntegrationResult(
                "operation",
                "local",
                candidate,
                "target",
                "preconditions",
                Some("observed"),
                IntegrationOutcome.Applied,
                Nil,
                candidate.policyDigest
              )
            )
          )
          base = Context(candidate, typedEvidence = evidence)
          missingDecision = checkpointDispatch(
            repo,
            candidate,
            None,
            Set("release")
          )
          handler = LocalReleaseHandler(
            _ =>
              ReleaseStage.Request(
                repo,
                "2.0.0",
                "Refusal fixture",
                ReleaseStage
                  .AcceptanceEvidence(head, "checks", "review", "integration"),
                _ => Task.succeed(())
              ),
            missingDecision
          )
          noCheckpoint <- Task.zio(handler(base))
          noCapabilityDispatch <- approvedDispatch(repo, candidate, Set.empty)
          noCapabilityHandler = LocalReleaseHandler(
            _ =>
              ReleaseStage.Request(
                repo,
                "2.0.2",
                "Refusal fixture",
                ReleaseStage
                  .AcceptanceEvidence(head, "checks", "review", "integration"),
                _ => Task.succeed(())
              ),
            noCapabilityDispatch
          )
          noCapability <- Task.zio(noCapabilityHandler(base))
          artifacts <- ZIO.attempt(
            Files.exists(repo.resolve("artifacts/release/2.0.0"))
          )
          tags <- ReleaseStage.git(repo, List("tag", "--list", "v2.0.0"))
        yield assertTrue(
          !noCapability.successful,
          !noCheckpoint.successful,
          noCapability.detail == "release refused: CapabilityMissing",
          noCheckpoint.detail == "release refused: MissingDecision",
          !artifacts,
          tags.trim.isEmpty
        )
      }
    },
    test(
      "SDLC-REL-02 refuses unaccepted revisions, failed builds, and pre-existing local tags"
    ) {
      withRepo { repo =>
        for
          head <- ReleaseStage.git(repo, List("rev-parse", "HEAD")).map(_.trim)
          rejectedEvidence = ReleaseStage.AcceptanceEvidence(
            "not-the-current-revision",
            "checks",
            "review",
            "integration"
          )
          currentEvidence = ReleaseStage.AcceptanceEvidence(
            head,
            "checks",
            "review",
            "integration"
          )
          stale <- ReleaseStage
            .release(
              ReleaseStage.Request(
                repo,
                "2.0.0",
                "notes",
                rejectedEvidence,
                _ => Task.succeed(())
              )
            )
            .either
          failedBuild <- ReleaseStage
            .release(
              ReleaseStage.Request(
                repo,
                "2.0.0",
                "notes",
                currentEvidence,
                _ =>
                  Task.fail(
                    raider.core.RaiderError.Compilation("fixture build failure")
                  )
              )
            )
            .either
          _ <- ReleaseStage.git(repo, List("tag", "v2.0.1", head))
          existingTag <- ReleaseStage
            .release(
              ReleaseStage.Request(
                repo,
                "2.0.1",
                "notes",
                currentEvidence,
                _ => Task.succeed(())
              )
            )
            .either
          artifacts <- ZIO.attempt(
            Files.exists(repo.resolve("artifacts/release/2.0.0"))
          )
        yield assertTrue(
          stale.isLeft,
          failedBuild.isLeft,
          existingTag.isLeft,
          !artifacts
        )
      }
    }
  )

  private def withRepo[A](
      f: java.nio.file.Path => ZIO[Any, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt(Files.createTempDirectory("raider-release-"))
    )(p =>
      ZIO.attempt {
        val stream = Files.walk(p)
        try
          stream
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(Files.delete(_))
        finally stream.close()
      }.orDie
    )(p =>
      for
        _ <- ReleaseStage.git(p, List("init", "-q"))
        _ <- ReleaseStage.git(
          p,
          List("config", "user.email", "release@test.invalid")
        )
        _ <- ReleaseStage.git(p, List("config", "user.name", "Release Fixture"))
        _ <- ZIO.attempt(Files.writeString(p.resolve("README.md"), "fixture\n"))
        _ <- ReleaseStage.git(p, List("add", "README.md"))
        _ <- ReleaseStage.git(p, List("commit", "-qm", "accepted baseline"))
        value <- f(p)
      yield value
    )

  private def approvedDispatch(
      repo: java.nio.file.Path,
      candidate: SdlcEvidence.CandidateIdentity,
      capabilities: Set[String]
  ): ZIO[Any, Throwable, CheckpointDispatch] =
    val expiry = java.time.Instant.now().plusSeconds(3600)
    val requestPath = repo.resolve(".git/approval-release.json")
    val decisionPath = repo.resolve(".git/decision-release.json")
    for
      request <- ZIO.fromEither(
        ApprovalArtifacts
          .request("local-release", candidate, expiry)
          .left
          .map(error => new IllegalStateException(error.toString))
      )
      _ <- ZIO.fromEither(
        ApprovalArtifacts
          .writeRequest(requestPath, request)
          .left
          .map(error => new IllegalStateException(error.toString))
      )
      _ <- ZIO.attempt(
        Files.writeString(
          decisionPath,
          ApprovalArtifacts.decisionJson(
            ApprovalArtifacts.Decision(
              ApprovalArtifacts.SchemaVersion,
              request.checkpointId,
              request.candidateDigest,
              request.expiresAt,
              approved = true
            )
          )
        )
      )
    yield checkpointDispatch(
      repo,
      candidate,
      Some(decisionPath),
      capabilities,
      Some(requestPath)
    )

  private def checkpointDispatch(
      repo: java.nio.file.Path,
      candidate: SdlcEvidence.CandidateIdentity,
      decision: Option[java.nio.file.Path],
      capabilities: Set[String],
      requestPath: Option[java.nio.file.Path] = None
  ): CheckpointDispatch =
    val request =
      requestPath.getOrElse(repo.resolve(".git/approval-release.json"))
    CheckpointDispatch(
      "local-release",
      candidate,
      java.time.Instant.now().plusSeconds(3600),
      request,
      decision,
      "release",
      () =>
        Right(
          ApprovalArtifacts.CurrentState(
            candidate,
            candidate.policyDigest,
            candidate.treeDigest,
            candidate.taskDigest,
            candidate.baseRevision,
            List("check", "review", "integration"),
            SdlcEvidence.CandidateIdentity.digest(candidate),
            capabilities
          )
        )
    )

  private def hex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString

end ReleaseStageSpec
