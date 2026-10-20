package raider.cli.release.verification

import raider.cli.release.{LocalReleaseHandler, ReleaseStage}
import raider.cli.run.{EventsJsonlWriter, SdlcArtifactSchema}
import raider.core.Task
import raider.core.sdlc.{SdlcEvidence, SdlcStages}
import raider.runtime.sdlc.approval.{ApprovalArtifacts, CheckpointDispatch}
import zio.*
import zio.test.*

import java.nio.file.{Files, Path}
import java.security.MessageDigest

/** Independent end-to-end acceptance for the local-only release boundary. */
object LocalReleaseAcceptanceSpec extends ZIOSpecDefault:
  import SdlcEvidence.*
  import SdlcStages.*

  def spec = suite("RAI-170 local release acceptance")(
    test("SDLC-VREL-01 materializes and persists one accepted local release") {
      withRepo { repo =>
        for
          candidate <- candidateFor(repo)
          context = acceptedContext(candidate)
          dispatch <- authorized(repo, candidate)
          result <- Task.zio(handler(repo, dispatch, "3.4.5")(context))
          directory = repo.resolve("artifacts/release/3.4.5")
          changelog <- ZIO.attempt(
            Files.readString(directory.resolve("CHANGELOG.md"))
          )
          manifest <- ZIO.attempt(
            Files.readString(directory.resolve("release.json"))
          )
          sums <- ZIO.attempt(Files.readString(directory.resolve("SHA256SUMS")))
          tag <- ReleaseStage.git(repo, List("tag", "--list", "v3.4.5"))
          remoteConfig <- ReleaseStage
            .git(repo, List("config", "--get-regexp", "^remote\\."))
            .either
          checkedSums <- verifyChecksums(directory, sums)
          artifacts <- ZIO
            .fromEither(SdlcArtifactSchema.fromStageResults(List(result)))
            .mapError(error => new IllegalStateException(error.toString))
          record = artifacts.find(
            _.kind == SdlcArtifactSchema.Kind.LocalReleaseResult
          )
          releaseJson = record.map(SdlcArtifactSchema.encode).getOrElse("")
          writer <- EventsJsonlWriter.open(repo.resolve("events.jsonl"))
          _ <- ZIO.foreachDiscard(artifacts)(writer.appendSdlc)
          _ <- writer.close
          run = SdlcArtifactSchema.addToRunJson(
            "{\"schemaVersion\":1,\"status\":\"succeeded\",\"checks\":[]}",
            artifacts
          )
          _ <- ZIO.attempt(Files.writeString(repo.resolve("run.json"), run))
          decodedRun = SdlcArtifactSchema.decodeRunJson(
            Files.readString(repo.resolve("run.json"))
          )
          lines <- ZIO.attempt(Files.readAllLines(repo.resolve("events.jsonl")))
          decodedEvents = SdlcArtifactSchema.decodeMixed(
            lines.toArray.toVector.map(_.toString)
          )
          serialized = record.map(r =>
            SdlcArtifactSchema.decode(SdlcArtifactSchema.encode(r)).toOption
          )
        yield assertTrue(
          result.successful,
          changelog.contains("## [3.4.5]"),
          manifest.contains("\"localOnly\":true"),
          tag.trim == "v3.4.5",
          checkedSums,
          remoteConfig.isLeft,
          record.nonEmpty,
          releaseJson.contains(CandidateIdentity.digest(candidate)),
          serialized.flatten == record,
          decodedRun.exists(_.contains(record.get)),
          decodedEvents.toOption.exists(_.exists {
            case Right(value) => record.contains(value)
            case Left(_)      => false
          })
        )
      }
    },
    test(
      "SDLC-VREL-02 typed evidence and checkpoint refusals leave no release state"
    ) {
      withRepo { repo =>
        for
          candidate <- candidateFor(repo)
          foreignPolicy = candidate.copy(policyDigest = "other-policy")
          foreignContext = acceptedContext(candidate).copy(
            typedEvidence = acceptedContext(candidate).typedEvidence.map {
              case Record.Integration(value) =>
                Record.Integration(
                  value.copy(
                    candidate = foreignPolicy,
                    policyDigest = foreignPolicy.policyDigest
                  )
                )
              case other => other
            }
          )
          digestContext = acceptedContext(candidate).copy(
            typedEvidence = acceptedContext(candidate).typedEvidence.map {
              case Record.Check(value) =>
                Record.Check(
                  value.copy(
                    candidate = candidate.copy(treeDigest = "different-tree")
                  )
                )
              case other => other
            }
          )
          noEvidence = Context(candidate)
          rejectedContext = acceptedContext(candidate).copy(
            typedEvidence = acceptedContext(candidate).typedEvidence.map {
              case Record.Check(value) =>
                Record.Check(value.copy(outcome = CheckOutcome.Failed))
              case other => other
            }
          )
          validDispatch <- authorized(repo, candidate)
          expiredDispatch <- dispatch(
            repo,
            candidate,
            Some(true),
            expires = java.time.Instant.now().minusSeconds(5)
          )
          staleDispatch <- dispatch(
            repo,
            candidate.copy(policyDigest = "stale"),
            Some(true)
          )
          noDecision <- dispatch(repo, candidate, None)
          deniedDispatch <- dispatch(repo, candidate, Some(false))
          cases = List(
            ("3.4.6", foreignContext, validDispatch),
            ("3.4.7", digestContext, validDispatch),
            ("3.4.8", noEvidence, validDispatch),
            ("3.4.9", rejectedContext, validDispatch),
            ("3.4.10", acceptedContext(candidate), expiredDispatch),
            ("3.4.11", acceptedContext(candidate), staleDispatch),
            ("3.4.12", acceptedContext(candidate), noDecision),
            ("3.4.13", acceptedContext(candidate), deniedDispatch)
          )
          outcomes <- ZIO.foreach(cases) { case (version, context, auth) =>
            Task
              .zio(handler(repo, auth, version)(context))
              .map(result => version -> result)
          }
          absent <- ZIO.foreach(cases) { case (version, _, _) =>
            for
              absentDirectory <- ZIO.attempt(
                !Files.exists(repo.resolve(s"artifacts/release/$version"))
              )
              tag <- ReleaseStage.git(repo, List("tag", "--list", s"v$version"))
            yield absentDirectory && tag.trim.isEmpty
          }
          localTags <- ReleaseStage.git(repo, List("tag", "--list", "v3.4.*"))
        yield assertTrue(
          outcomes.forall(!_._2.successful),
          absent.forall(identity),
          localTags.trim.isEmpty
        )
      }
    },
    test("SDLC-VREL-02 duplicate and invalid versions are typed refusals") {
      withRepo { repo =>
        for
          candidate <- candidateFor(repo)
          approved <- authorized(repo, candidate)
          first <- Task.zio(
            handler(repo, approved, "4.0.0")(acceptedContext(candidate))
          )
          duplicate <- Task
            .zio(handler(repo, approved, "4.0.0")(acceptedContext(candidate)))
            .either
          invalid <- Task
            .zio(handler(repo, approved, "04.0.0")(acceptedContext(candidate)))
            .either
          existingTag <- ReleaseStage.git(
            repo,
            List("tag", "v4.0.1", candidate.headRevision.get)
          )
          existingTagResult <- Task
            .zio(handler(repo, approved, "4.0.1")(acceptedContext(candidate)))
            .either
          existingTagList <- ReleaseStage.git(
            repo,
            List("tag", "--list", "v4.0.1")
          )
          invalidArtifacts <- ZIO.attempt(
            !Files.exists(repo.resolve("artifacts/release/04.0.0"))
          )
          unchanged <- ZIO.attempt(
            Files.readString(
              repo.resolve("artifacts/release/4.0.0/CHANGELOG.md")
            )
          )
          duplicateTags <- ReleaseStage.git(
            repo,
            List("tag", "--list", "v4.0.0")
          )
        yield assertTrue(
          first.successful,
          duplicate.isLeft,
          invalid.isLeft,
          existingTagResult.isLeft,
          invalidArtifacts,
          unchanged.contains("release notes"),
          duplicateTags.trim == "v4.0.0",
          existingTag.trim.isEmpty,
          existingTagList.trim == "v4.0.1"
        )
      }
    },
    test(
      "SDLC-VREL-03 interruption reports partial staging without installed release or tag"
    ) {
      withRepo { repo =>
        for
          candidate <- candidateFor(repo)
          entered <- Promise.make[Nothing, Unit]
          hold <- Promise.make[Nothing, Unit]
          fiber <- ReleaseStage
            .release(
              ReleaseStage.Request(
                repo,
                "5.0.0",
                "release notes",
                ReleaseStage.AcceptanceEvidence(
                  candidate.headRevision.get,
                  "checks",
                  "review",
                  "integration"
                ),
                _ => Task.succeed(()),
                () => Task(entered.succeed(()).unit *> hold.await)
              )
            )
            .fork
          _ <- entered.await
          exit <- fiber.interrupt
          tags <- ReleaseStage.git(repo, List("tag", "--list", "v5.0.0"))
          installed <- ZIO.attempt(
            Files.exists(repo.resolve("artifacts/release/5.0.0"))
          )
          state = exit.causeOption.flatMap(_.failureOption).collect {
            case error: ReleaseStage.Interrupted => error.reconciliation
          }
        yield assertTrue(
          state.exists(
            _.artifacts.sorted == List(
              "CHANGELOG.md",
              "SHA256SUMS",
              "release.json"
            )
          ),
          state.exists(!_.tagPresent),
          !installed,
          tags.trim.isEmpty
        )
      }
    }
  )

  private def handler(
      repo: Path,
      auth: CheckpointDispatch,
      version: String,
      after: () => Task[Unit] = () => Task.succeed(())
  ): Handler =
    LocalReleaseHandler(
      context =>
        ReleaseStage.Request(
          repo,
          version,
          "release notes",
          ReleaseStage.AcceptanceEvidence(
            context.candidate.revision
              .orElse(context.candidate.headRevision)
              .getOrElse(""),
            "checks",
            "review",
            "integration"
          ),
          _ => Task.succeed(()),
          after
        ),
      auth
    )

  private def acceptedContext(candidate: CandidateIdentity): Context =
    Context(
      candidate,
      typedEvidence = List(
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
            "check-output",
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
            "integration",
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
    )

  private def candidateFor(repo: Path): ZIO[Any, Throwable, CandidateIdentity] =
    ReleaseStage.git(repo, List("rev-parse", "HEAD")).map(_.trim).map { head =>
      CandidateIdentity(
        repo.toString,
        repo.toString,
        Some(head),
        Some(head),
        Some(head),
        "tree",
        "task",
        "plan",
        "policy",
        "config"
      )
    }

  private def authorized(repo: Path, candidate: CandidateIdentity) =
    dispatch(repo, candidate, Some(true))

  private def dispatch(
      repo: Path,
      candidate: CandidateIdentity,
      approved: Option[Boolean],
      expires: java.time.Instant = java.time.Instant.now().plusSeconds(3600)
  ): ZIO[Any, Throwable, CheckpointDispatch] =
    val requestPath = repo.resolve(".git/release-request.json")
    val decisionPath = repo.resolve(".git/release-decision.json")
    for
      request <- ZIO.fromEither(
        ApprovalArtifacts
          .request("local-release", candidate, expires)
          .left
          .map(error => new IllegalStateException(error.toString))
      )
      _ <- ZIO.fromEither(
        ApprovalArtifacts
          .writeRequest(requestPath, request)
          .left
          .map(error => new IllegalStateException(error.toString))
      )
      _ <- approved match
        case Some(value) =>
          ZIO.attempt(
            Files.writeString(
              decisionPath,
              ApprovalArtifacts.decisionJson(
                ApprovalArtifacts.Decision(
                  ApprovalArtifacts.SchemaVersion,
                  request.checkpointId,
                  request.candidateDigest,
                  request.expiresAt,
                  value
                )
              )
            )
          )
        case None => ZIO.unit
    yield CheckpointDispatch(
      "local-release",
      candidate,
      expires,
      requestPath,
      approved.map(_ => decisionPath),
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
            CandidateIdentity.digest(candidate),
            Set("release")
          )
        )
    )

  private def verifyChecksums(
      directory: Path,
      sums: String
  ): ZIO[Any, Throwable, Boolean] =
    val rows = sums.linesIterator.filter(_.nonEmpty).toList
    ZIO
      .foreach(rows) { row =>
        val Array(expected, name) = row.split("  ", 2): @unchecked
        ZIO.attempt {
          val actual = MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(directory.resolve(name.trim)))
            .map(b => f"${b & 0xff}%02x")
            .mkString
          actual == expected
        }
      }
      .map(values => rows.size == 2 && values.forall(identity))

  private def withRepo[A](
      f: Path => ZIO[Any, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt(Files.createTempDirectory("rai-170-release-"))
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
          List("config", "user.email", "verify@test.invalid")
        )
        _ <- ReleaseStage.git(p, List("config", "user.name", "Verifier"))
        _ <- ZIO.attempt(
          Files.writeString(p.resolve("README.md"), "disposable repository\n")
        )
        _ <- ReleaseStage.git(p, List("add", "README.md"))
        _ <- ReleaseStage.git(p, List("commit", "-qm", "accepted baseline"))
        value <- f(p)
      yield value
    )

end LocalReleaseAcceptanceSpec
