package raider.cli.release

import zio.*
import zio.test.*
import raider.core.Task

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

  private def hex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString

end ReleaseStageSpec
