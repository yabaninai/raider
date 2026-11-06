package raider.cli.release

import raider.core.RaiderError.InputValidation
import raider.core.Task
import raider.core.sdlc.{SdlcEvidence, SdlcStages}
import raider.runtime.sdlc.approval.{ApprovalArtifacts, CheckpointDispatch}
import zio.*
import zio.json.ast.Json

import scala.jdk.CollectionConverters.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.time.Instant

/** One-shot local-owner driver for the staged JVM distribution.
  *
  * RAI-279: besides the headless package, the driver stages the RAI-262 REPL
  * distribution (launcher directory + release zip) as distribution artifacts
  * and, through the [[ReleaseStage.Request]] materialization hook, names both
  * distributions in the staged release.json, folds the unreleased changelog
  * section into the dated section produced by [[ReleaseStage]], and rewrites
  * the top-level SHA256SUMS so it covers every staged file.
  */
object LocalReleaseDriver:
  private val VersionPlaceholder = "VERSION"
  private val JarHashPlaceholder = "REPLACE_WITH_RELEASE_SHA256"

  /** Payload file names inside the unpacked REPL distribution directory
    * (modules/repl/dist/ReplDistribution.scala layout).
    */
  private val ReplPayloadNames = List(
    "raider-repl",
    "raider-repl.jar",
    "README.txt",
    "SHA256SUMS",
    "version"
  )

  def main(args: Array[String]): Unit =
    require(args.length == 2, "usage: LocalReleaseDriver <repo> <version>")
    val repo = Paths.get(args(0)).toAbsolutePath.normalize
    val version = args(1)
    require(
      version.nonEmpty && version.forall(c => c.isLetterOrDigit || c == '.' || c == '-' || c == '+'),
      s"invalid release version: $version"
    )
    val jar = repo.resolve("modules/cli/target/scala-3.9.0/raider-cli.jar")
    require(Files.isRegularFile(jar), s"built assembly is missing: $jar")

    // RAI-279: the REPL distribution must already be built with the release
    // version (sbt raiderReplDist under the pinned version); the wrapper
    // script performs that step and fails fast if it did not produce the
    // expected artifact set.
    val replDistRoot = repo.resolve("modules/repl/target/raider-repl-dist")
    val replDir = replDistRoot.resolve(s"raider-repl-$version")
    val replZip = replDistRoot.resolve(s"raider-repl-$version.zip")
    val replPayload = ReplPayloadNames.map(replDir.resolve)
    val replSources = replPayload :+ replZip
    replSources.foreach { path =>
      require(
        Files.isRegularFile(path),
        s"REPL distribution artifact is missing: $path (run sbt raiderReplDist first)"
      )
    }
    // Rendering guard: the release stage renders any distribution artifact
    // whose bytes contain the placeholders, which would corrupt binary REPL
    // payload on a freak byte match. No REPL artifact legitimately contains
    // them (packaging files use lowercase `version`), so fail before any
    // mutation happens.
    replSources.foreach { path =>
      val text = new String(Files.readAllBytes(path), UTF_8)
      require(
        !text.contains(VersionPlaceholder) && !text.contains(JarHashPlaceholder),
        s"REPL distribution artifact contains a release placeholder: $path"
      )
    }

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

    // Release notes mirror the unreleased [0.2.0] CHANGELOG section verbatim
    // (RAI-279): the release stage renders them as the dated section bullets,
    // and the materialization hook then folds the now-duplicate unreleased
    // section away in the staged copy. The wrapper script applies the same
    // fold to the repository CHANGELOG.md after the run.
    val releaseNotes =
      """- Added an installed `raider --help` entrypoint that exits 0 with an accurate command list (RAI-261).
        |- Added a separate installed REPL distribution: launcher `raider-repl`, release archive, and version metadata, staged alongside the headless package in local releases (RAI-262; release wiring under RAI-279).
        |- Reconciled the chat session close lifecycle (`chat.close()`) with the REPL facade so close settles without hanging the terminal (RAI-263).
        |- Delegated child agents now run under the parent's configured provider model unless delegation arguments explicitly override it; the scripted backend keeps the historical `scripted:<agent>` label (RAI-257).
        |- The coder base prompt now names the full current tool surface, including the all-or-nothing multi-file patch tool and the irreversible delete tool (RAI-270).""".stripMargin

    val distributionArtifacts = List(
      ReleaseStage.DistributionArtifact(jar, "raider-cli.jar"),
      ReleaseStage.DistributionArtifact(repo.resolve("packaging/raider"), "packaging/raider"),
      ReleaseStage.DistributionArtifact(repo.resolve("packaging/homebrew/raider.rb"), "packaging/homebrew/raider.rb"),
      ReleaseStage.DistributionArtifact(repo.resolve("packaging/scoop/raider.json"), "packaging/scoop/raider.json")
    ) ++
      replPayload.map { source =>
        ReleaseStage.DistributionArtifact(
          source,
          s"repl/raider-repl-$version/${source.getFileName.toString}"
        )
      } :+ ReleaseStage.DistributionArtifact(replZip, s"repl/raider-repl-$version.zip")

    val handler = LocalReleaseHandler(
      _ => ReleaseStage.Request(
        repo,
        version,
        releaseNotes,
        ReleaseStage.AcceptanceEvidence(head, "local-check", "local-owner", "local-worktree"),
        // The wrapper script already built the headless package and the REPL
        // distribution before running this driver, so the build step is a
        // no-op here.
        build = _ => Task.succeed(()),
        afterStagedArtifacts = () => materializationHook(repo, version),
        distributionArtifacts = distributionArtifacts
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
    println(s"repl=$directory/repl/raider-repl-$version")
    println(s"replArchive=$directory/repl/raider-repl-$version.zip")

  /** RAI-279 materialization hook: runs after the release stage has written
    * the staged artifact set, changelog, release.json, and top-level
    * SHA256SUMS into a `.release-*` staging directory, and before that
    * directory is atomically moved to its final release location.
    *
    * The hook (1) adds the `distributions` naming object to the staged
    * release.json, (2) folds the unreleased changelog section into the dated
    * section the release stage just appended, and (3) rewrites the top-level
    * SHA256SUMS so every staged file (headless package + repl/ tree) is
    * covered by the manifest that ships with the release.
    */
  private def materializationHook(repo: Path, version: String): Task[Unit] =
    Task.apply(
      ZIO
        .attempt {
          val releaseRoot = repo.resolve("artifacts/release")
          val staging = stagingDirectories(releaseRoot)
          if staging.size != 1 then
            throw new IllegalStateException(
              s"expected exactly one release staging directory, found ${staging.size}: " +
                staging.map(_.toString).mkString(", ")
            )
          val root = staging.head
          applyDistributionNaming(root, version)
          foldUnreleasedChangelog(root, version)
          rewriteChecksums(root)
        }
        .mapError(cause => InputValidation(s"release naming failed: $cause"))
    )

  private def stagingDirectories(releaseRoot: Path): List[Path] =
    if !Files.isDirectory(releaseRoot) then Nil
    else
      val stream = Files.newDirectoryStream(releaseRoot, ".release-*")
      try
        stream.iterator().asScala
          .filter(Files.isDirectory(_))
          .toList
          .sortBy(_.toString)
      finally stream.close()

  /** Adds `"distributions": {"headless": ..., "repl": ..., "replArchive": ...}`
    * to the staged release.json. The manifest schema itself is fixed by the
    * release stage, so the naming object is appended at the top level with
    * the zio-json AST instead (contract change would need a separate card).
    */
  private def applyDistributionNaming(root: Path, version: String): Unit =
    val provenance = root.resolve("release.json")
    val text = Files.readString(provenance, UTF_8)
    val named = Json.decoder.decodeJson(text) match
      case Right(obj: Json.Obj) =>
        obj.add(
          "distributions",
          Json.Obj(
            "headless" -> Json.Str("raider-cli.jar"),
            "repl" -> Json.Str(s"repl/raider-repl-$version"),
            "replArchive" -> Json.Str(s"repl/raider-repl-$version.zip")
          )
        )
      case Right(_) =>
        throw new IllegalStateException("staged release.json is not a JSON object")
      case Left(error) =>
        throw new IllegalStateException(s"cannot parse staged release.json: $error")
    Files.writeString(
      provenance,
      Json.encoder.encodeJson(named, None).toString + "\n",
      UTF_8
    )

  /** Removes the `## [<version>] - unreleased` section when the release stage
    * has rendered a dated `## [<version>] - YYYY-MM-DD` section for the same
    * version, and moves that dated section directly below the file intro (the
    * stage appends it at the end of the file, which breaks the
    * newest-first order).
    */
  private def foldUnreleasedChangelog(root: Path, version: String): Unit =
    val changelog = root.resolve("CHANGELOG.md")
    val lines = Files.readString(changelog, UTF_8).split("\n", -1).toList
    val firstSection = lines.indexWhere(_.startsWith("## ["))
    if firstSection >= 0 then
      val intro = lines.take(firstSection)
      val headingIndexes =
        lines.zipWithIndex.collect {
          case (line, index) if index >= firstSection && line.startsWith("## [") => index
        }
      val sections = headingIndexes.zipWithIndex.map { case (start, order) =>
        val end = headingIndexes.lift(order + 1).getOrElse(lines.length)
        lines.slice(start, end)
      }.toList
      val unreleasedHeading = s"## [$version] - unreleased"
      val datedPrefix = s"## [$version] - "
      val dated = sections.find(section =>
        section.head.startsWith(datedPrefix) && isDatedSuffix(section.head.drop(datedPrefix.length))
      )
      dated match
        case Some(datedSection) =>
          // Keep one blank line between the moved dated section and the next
          // section: when the stage appends the dated section at the end of
          // the file, its block carries no trailing blank (the separator
          // blank belongs to the preceding section's block).
          val rest = sections.filterNot(section =>
            section.head.startsWith(unreleasedHeading) || (section eq datedSection)
          )
          val datedBlock =
            if datedSection.last.nonEmpty then datedSection :+ "" else datedSection
          val ordered = intro ++ (datedBlock +: rest)
          val folded = ordered.mkString("\n")
          if folded != Files.readString(changelog, UTF_8) then
            Files.writeString(changelog, folded, UTF_8)
        case None =>
        // No dated section (nothing was cut for this version): keep the
        // unreleased section untouched.

  private def isDatedSuffix(suffix: String): Boolean =
    suffix.length == 10 && (0 until 10).forall { index =>
      if index == 4 || index == 7 then suffix.charAt(index) == '-'
      else suffix.charAt(index).isDigit
    }

  /** Rewrites the top-level SHA256SUMS so it lists every regular file in the
    * staged tree except the manifest itself (including the nested
    * repl/.../SHA256SUMS, which stays internally consistent because no REPL
    * payload file is placeholder-rendered). Entries are path-sorted in the
    * `sha256sum -c` format with forward slashes.
    */
  private def rewriteChecksums(root: Path): Unit =
    val checksums = root.resolve("SHA256SUMS")
    val stream = Files.walk(root)
    val files =
      try
        stream.iterator().asScala
          .filter(Files.isRegularFile(_))
          .filterNot(_ == checksums)
          .toList
      finally stream.close()
    val listed = files
      .map(path => root.relativize(path).toString.replace('\\', '/'))
      .sorted
      .map(relative => s"${sha256(Files.readAllBytes(root.resolve(relative)))}  $relative")
      .mkString("\n") + "\n"
    Files.writeString(checksums, listed, UTF_8)

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
