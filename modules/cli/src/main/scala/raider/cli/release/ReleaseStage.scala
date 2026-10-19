package raider.cli.release

import zio.*
import zio.json.*
import raider.core.{Task as RaiderTask}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

/** Strictly local release stage. Git is invoked with fixed argument vectors;
  * there is deliberately no remote operation in this API.
  */
object ReleaseStage:

  final case class AcceptanceEvidence(
      acceptedRevision: String,
      checks: String,
      review: String,
      integration: String
  ) derives JsonCodec

  final case class Manifest(
      schemaVersion: Int,
      version: String,
      revision: String,
      tag: String,
      localOnly: Boolean,
      acceptance: AcceptanceEvidence
  ) derives JsonCodec

  final case class Request(
      repository: Path,
      version: String,
      notes: String,
      acceptance: AcceptanceEvidence,
      build: Path => RaiderTask[Unit]
  )

  final case class Result(
      version: String,
      tag: String,
      revision: String,
      directory: Path
  )

  private val semVer =
    "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$".r

  def release(request: Request): Task[Result] =
    val repo = request.repository.toAbsolutePath.normalize
    for
      _ <- ZIO
        .fail(new Refusal("invalid SemVer"))
        .unless(semVer.pattern.matcher(request.version).matches())
      _ <- validateRequest(repo, request)
      revision <- git(repo, List("rev-parse", "HEAD")).map(_.trim)
      _ <- ZIO
        .fail(new Refusal("current revision is not the accepted revision"))
        .unless(revision == request.acceptance.acceptedRevision)
      status <- git(repo, List("status", "--porcelain"))
      _ <- ZIO
        .fail(new Refusal("release requires a clean accepted revision"))
        .unless(status.trim.isEmpty)
      tag = "v" + request.version
      tagExists <- git(repo, List("tag", "--list", tag)).map(_.trim.nonEmpty)
      destination = repo.resolve("artifacts/release").resolve(request.version)
      versionExists <- ZIO.attempt(Files.exists(destination)).orDie
      _ <- ZIO
        .fail(new Refusal("release version or local tag already exists"))
        .when(tagExists || versionExists)
      _ <- raider.core.Task
        .zio(request.build(repo))
        .mapError(e => new Refusal("release build failed: " + e.detail))
      result <- ZIO.acquireReleaseWith(
        ZIO
          .attempt {
            val parent = destination.getParent
            Files.createDirectories(parent)
            val staging = Files.createTempDirectory(parent, ".release-")
            staging
          }
          .mapError(e =>
            new Refusal(
              "cannot prepare local release artifacts: " + e.getMessage
            )
          )
      )(staging => ZIO.attempt(deleteTree(staging)).orDie)(staging =>
        ZIO.uninterruptible(
          materialize(repo, staging, destination, request, revision, tag)
        )
      )
    yield result

  private def validateRequest(repo: Path, request: Request): Task[Unit] =
    val a = request.acceptance
    for
      _ <- ZIO
        .fail(
          new Refusal(
            "release requires accepted checks, review, and integration evidence"
          )
        )
        .unless(
          List(a.acceptedRevision, a.checks, a.review, a.integration)
            .forall(_.trim.nonEmpty)
        )
      _ <- ZIO
        .fail(new Refusal("release notes must be non-empty"))
        .unless(request.notes.trim.nonEmpty)
      inside <- git(repo, List("rev-parse", "--is-inside-work-tree")).map(
        _.trim
      )
      _ <- ZIO
        .fail(new Refusal("release path is not inside a Git repository"))
        .unless(inside == "true")
      _ <- ZIO
        .fail(new Refusal("release requires a local Git repository"))
        .unless(Files.isDirectory(repo.resolve(".git")))
    yield ()

  private def materialize(
      repo: Path,
      staging: Path,
      destination: Path,
      request: Request,
      revision: String,
      tag: String
  ): Task[Result] =
    val changelog = repo.resolve("CHANGELOG.md")
    val existed = Files.isRegularFile(changelog)
    val old = if existed then Files.readString(changelog, UTF_8) else ""
    val updated = updateChangelog(old, request.version, request.notes)
    val changelogCopy = staging.resolve("CHANGELOG.md")
    val provenance = staging.resolve("release.json")
    val checksums = staging.resolve("SHA256SUMS")
    val evidence = request.acceptance
    val provenanceJson =
      Manifest(1, request.version, revision, tag, true, evidence).toJson
    val install = for
      _ <- ZIO
        .attempt {
          Files.writeString(changelogCopy, updated, UTF_8)
          Files.writeString(provenance, provenanceJson + "\n", UTF_8)
          val listed = List(changelogCopy, provenance)
            .map(p => s"${sha256(Files.readAllBytes(p))}  ${p.getFileName}")
            .mkString("\n") + "\n"
          Files.writeString(checksums, listed, UTF_8)
        }
        .mapError(e =>
          new Refusal("cannot write staged release artifacts: " + e.getMessage)
        )
      _ <- ZIO
        .attempt {
          Files.createDirectories(changelog.getParent)
          writeAtomic(changelog, updated)
          Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
        }
        .mapError(e =>
          new Refusal("cannot install local release artifacts: " + e.getMessage)
        )
      // A lightweight local tag has no signing semantics, matching the D5 local-only contract.
      _ <- git(repo, List("tag", tag, revision))
        .mapError(e =>
          new Refusal("local tag creation failed: " + e.getMessage)
        )
    yield Result(request.version, tag, revision, destination)
    install.catchAll(error =>
      ZIO.attempt {
        deleteTree(destination)
        if existed then writeAtomic(changelog, old)
        else Files.deleteIfExists(changelog)
      }.orDie *> ZIO.fail(error)
    )

  private def updateChangelog(
      old: String,
      version: String,
      notes: String
  ): String =
    val normalized = notes.linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(s => if s.startsWith("-") then s else "- " + s)
      .mkString("\n")
    val heading =
      s"## [$version] - ${java.time.LocalDate.now()}\n\n$normalized\n"
    if old.isEmpty then
      s"# Changelog\n\nAll notable changes to this project will be documented in this file.\n\n$heading"
    else if old.startsWith("# Changelog") then
      val afterHeading = old.indexOf('\n') match
        case -1    => "\n\n"
        case index => old.substring(index + 1)
      "# Changelog\n" + (if afterHeading.startsWith("\n## ") then
                           "\n" + heading + afterHeading.drop(1)
                         else afterHeading + "\n" + heading)
    else
      throw new IllegalArgumentException(
        "CHANGELOG.md must start with '# Changelog'"
      )

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def deleteTree(root: Path): Unit =
    if Files.exists(root) then
      val stream = Files.walk(root)
      try
        stream
          .iterator()
          .asScala
          .toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(Files.deleteIfExists(_))
      finally stream.close()

  private def writeAtomic(path: Path, content: String): Unit =
    val temporary =
      path.resolveSibling(path.getFileName.toString + ".release-tmp")
    Files.writeString(temporary, content, UTF_8)
    try
      Files.move(
        temporary,
        path,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
    catch
      case error: Throwable =>
        Files.deleteIfExists(temporary)
        throw error

  private[release] def git(repo: Path, args: List[String]): zio.Task[String] =
    ZIO
      .attempt {
        val process = new ProcessBuilder(
          (List("git", "-C", repo.toString) ++ args)*
        ).redirectErrorStream(true).start()
        val output = new String(process.getInputStream.readAllBytes(), UTF_8)
        val exit = process.waitFor()
        if exit != 0 then throw new IllegalStateException(output.trim)
        output
      }
      .mapError(e => new Refusal("local Git command failed: " + e.getMessage))

  final class Refusal(message: String) extends RuntimeException(message)
end ReleaseStage
