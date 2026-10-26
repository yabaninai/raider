package raider.cli.run

import raider.core.result.{ArtifactEntry, ExitCodes}

import zio.ZIO
import zio.json.DecoderOps

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest

/** RAI-210 (FCI-REPORT-02 deferred acceptance): the product
  * `raider verify-artifacts <run-dir>` command — the post-run artifact
  * integrity CONSUMER. The runner ([[HeadlessRunner]], RAI-186) already writes
  * the integrity manifest `artifacts.json` (ci-runtime §7: sha256, mediaType,
  * size and relative path of every completed regular output file except the
  * manifest itself, written atomically last); until this command NOTHING in
  * main could verify it — only the RAI-185 test asserted the re-hash. This
  * command closes that gap: it re-reads the manifest of a PAST run and
  * re-hashes the generated set, refusing truthfully when the evidence no longer
  * matches.
  *
  * Verified, per manifest entry (deterministic order — sorted by path):
  *   - the entry path is a safe relative path ([[CiReport.safeRelativePath]] —
  *     no absolute paths, no `.`/`..` segments, no drive prefixes): a manifest
  *     that would point OUTSIDE the run directory is a typed refusal, the
  *     consumer never reads beyond `<run-dir>`;
  *   - the file exists as a regular file (a listed-but-removed file — the
  *     card's "removed required file" case — is a typed refusal);
  *   - the byte length matches `sizeBytes`;
  *   - the SHA-256 over the bytes actually on disk matches `sha256` (the card's
  *     "partial byte mutation" and "swapped digest" cases);
  *   - the documented required artifact set is COVERED by the manifest
  *     (run.json, result.json, events.jsonl, checks.junit.xml, summary.md —
  *     ci-runtime §7 artifacts table; artifacts.json is the manifest itself and
  *     is self-excluded by the writer's contract): a manifest whose required
  *     entries were stripped can never pass.
  * A missing, unreadable or UNDECODABLE manifest is refused too: a torn or
  * half-written `artifacts.json` is exactly what an interrupted finalization
  * (mutation racing finalization) looks like — the consumer refuses instead of
  * fabricating a passed check, and the run-side exit precedence for that race
  * stays owned by the existing RAI-185/RAI-186 evidence (write failure 27 beats
  * uncertainty 26). Files present in the run dir but NOT listed are not policed
  * (additions do not corrupt the evidenced set — the manifest is the anchor,
  * not the directory); this scope line is deliberate and part of the card's
  * non-goals.
  *
  * Exit codes reuse the frozen ci-runtime §8 mapping only — no invented codes
  * (ReplayCli/ResumeCommand discipline):
  *   - 0 — verified: every listed file re-hashed to its recorded digest and
  *     size, and the required set is covered;
  *   - 27 — any typed integrity refusal ([[ExitCodes.ArtifactWriteFailure]]): a
  *     post-run evidence failure IS an artifact/evidence failure of the run
  *     being verified (§8 precedence: "artifact failure 27 takes precedence";
  *     the run-side required-artifact wiring landed on the same code in
  *     RAI-186) — never green without artifacts;
  *   - 21 — usage error (ReplayCli/ResumeCommand precedent for malformed input:
  *     missing or extra argv, unknown flags).
  *
  * Surface choice (card contract, precedent-based): a run-module CLI
  * subcommand, exactly like RAI-109 wired `raider replay` and RAI-129 wired
  * `raider resume` — the verdict is console-only
  * ([[HeadlessRunner.Outcome.result = None]]: a verification of a PAST run
  * never creates a new invocation root), and the run dir is NEVER written
  * (read-only consumer, like resume). Determinism: the pipeline is pure (no
  * wall clock, no RNG, ordered iteration), so repeated verifications of the
  * same run dir render byte-identical reports.
  *
  * Non-goals honored (card): no new report format (plain console text, the
  * resume precedent), no change to the manifest schema or its writer, no
  * gate-policy or frozen-fixture change, no scheduling of re-runs.
  */
object ArtifactIntegrityCommand:

  /** File name of the integrity manifest inside a run directory (ci-runtime §7;
    * written by [[HeadlessRunner]] with an atomic temp+rename).
    */
  val ManifestFileName = "artifacts.json"

  /** Documented required artifact set (ci-runtime §7 artifacts table;
    * FCI-REPORT-02's "required artifact set is present"). Fixed order — part of
    * the deterministic report. The manifest itself is excluded: the §7 contract
    * keeps `artifacts.json` out of its own hash set.
    */
  val RequiredArtifactNames: Vector[String] = Vector(
    "run.json",
    "result.json",
    "events.jsonl",
    "checks.junit.xml",
    "summary.md"
  )

  /** Closed typed-problem set of the verification (ReplayCli discipline: one
    * case per failure source, no stringly-typed catch-all). Every case is a
    * truthful, named reason the evidence set cannot be trusted.
    */
  enum Problem:

    /** No `artifacts.json` in the run directory — a removed manifest, or an
      * interrupted finalization that never completed its atomic move.
      */
    case ManifestMissing(runDir: Path, manifest: Path)

    /** The manifest exists but cannot be read from disk (I/O failure). */
    case ManifestUnreadable(manifest: Path, reason: String)

    /** The manifest exists but is not a decodable artifact manifest — the
      * torn-write shape of a mutation racing finalization.
      */
    case ManifestMalformed(manifest: Path, detail: String)

    /** The manifest does not cover the documented required set — its entries
      * were stripped or it lists something else entirely.
      */
    case RequiredAbsent(names: List[String])

    /** The same path is listed more than once. */
    case DuplicateEntry(path: String)

    /** A manifest entry would read outside the run directory (absolute path,
      * `..` segment, drive prefix) — refused before any read happens.
      */
    case PathEscape(path: String)

    /** The file is listed but no longer there (removed required file). */
    case EntryMissing(path: String)

    /** The file is there but its byte length differs from the manifest. */
    case SizeMismatch(path: String, expected: Long, actual: Long)

    /** The file is there but its SHA-256 differs from the manifest — partial
      * byte mutation or a swapped digest entry.
      */
    case DigestMismatch(path: String, expected: String, actual: String)

    /** The file is listed and present but cannot be read (I/O failure). */
    case EntryUnreadable(path: String, reason: String)
  end Problem

  /** The verified state: pure data, named fields. `bytes` is the total size
    * actually re-read during verification (equal to the manifest's recorded
    * sizes precisely when the set is intact).
    */
  final case class Verified(
      runDir: Path,
      manifest: Path,
      entries: Int,
      bytes: Long
  )

  /** The command outcome: verified evidence or the typed problems found. */
  type Verdict = Either[Vector[Problem], Verified]

  /** Parses the raw verify-artifacts argv (the `verify-artifacts` token
    * included, same rule as [[ResumeCommand.parse]]): exactly one positional
    * run directory, no flags — the minimal surface. Anything else is a usage
    * error rendered with the usage text.
    */
  def parse(argv: List[String]): Either[String, Path] =
    val args = argv.toList match
      case "verify-artifacts" :: tail => tail
      case other                      => other
    args match
      case Nil => Left(s"missing <run-dir>\n${Usage}")
      case runDir :: Nil =>
        if runDir.startsWith("--") then Left(s"unknown flag: $runDir\n${Usage}")
        else Right(Path.of(runDir))
      case _ => Left(s"unexpected extra arguments\n${Usage}")
  end parse

  /** Verifies the artifact set of `runDir` — pure, total over its typed error
    * channel: manifest missing → [[Problem.ManifestMissing]]; unreadable →
    * [[Problem.ManifestUnreadable]]; undecodable →
    * [[Problem.ManifestMalformed]]; otherwise every entry is re-hashed in
    * sorted path order and the required set must be covered. All problems are
    * reported (not just the first); the run directory is never written.
    */
  def verify(runDir: Path): Verdict =
    val manifest = runDir.resolve(ManifestFileName)
    if !Files.isRegularFile(manifest) then
      Left(Vector(Problem.ManifestMissing(runDir, manifest)))
    else
      val decoded =
        try
          Right(Files.readString(manifest, UTF_8).fromJson[List[ArtifactEntry]])
        catch
          case e: Exception =>
            Left(
              Vector(
                Problem.ManifestUnreadable(manifest, e.getClass.getSimpleName)
              )
            )
      decoded match
        case Left(problems) => Left(problems)
        case Right(Left(detail)) =>
          Left(Vector(Problem.ManifestMalformed(manifest, detail)))
        case Right(Right(entries)) =>
          val problems = Vector.newBuilder[Problem]
          val listedPaths = entries.map(_.path)
          val absent = RequiredArtifactNames.filterNot(listedPaths.contains)
          if absent.nonEmpty then
            problems += Problem.RequiredAbsent(absent.toList)
          listedPaths
            .groupBy(identity)
            .collect { case (path, seen) if seen.sizeIs > 1 => path }
            .toVector
            .sorted
            .foreach(path => problems += Problem.DuplicateEntry(path))
          // Per-entry checks in sorted path order — deterministic report.
          // One problem per entry: the first failing check names the file
          // (escape > missing > size > digest; a digest re-check after a
          // size mismatch would only repeat the same refusal).
          var verifiedBytes = 0L
          entries.sortBy(_.path).foreach { entry =>
            val relative = entry.path
            if !CiReport.safeRelativePath(relative) then
              problems += Problem.PathEscape(relative)
            else
              val file = runDir.resolve(relative)
              if !Files.isRegularFile(file) then
                problems += Problem.EntryMissing(relative)
              else
                val checked =
                  try
                    val bytes = Files.readAllBytes(file)
                    if bytes.length.toLong != entry.sizeBytes then
                      Some(
                        Problem.SizeMismatch(
                          relative,
                          entry.sizeBytes,
                          bytes.length.toLong
                        )
                      )
                    else
                      val actual = sha256Hex(bytes)
                      if actual != entry.sha256 then
                        Some(
                          Problem.DigestMismatch(
                            relative,
                            entry.sha256,
                            actual
                          )
                        )
                      else
                        verifiedBytes += bytes.length.toLong
                        None
                  catch
                    case e: Exception =>
                      Some(
                        Problem.EntryUnreadable(
                          relative,
                          e.getClass.getSimpleName
                        )
                      )
                checked.foreach(problems += _)
          }
          val found = problems.result()
          if found.isEmpty then
            Right(
              Verified(
                runDir = runDir,
                manifest = manifest,
                entries = entries.size,
                bytes = verifiedBytes
              )
            )
          else Left(found)
  end verify

  /** SHA-256 over the bytes actually on disk — the writer's exact algorithm
    * ([[HeadlessRunner]] lowercase hex, `f"$b%02x"`).
    */
  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"$b%02x")
      .mkString

  // ---------------------------------------------------------------------------
  // Rendering and exit codes (ReplayCli conventions: report on stdout, typed
  // problems on stderr, frozen §8 mapping only)
  // ---------------------------------------------------------------------------

  private val Usage =
    s"""usage: raider verify-artifacts <run-dir>
       |
       |Verifies the integrity of a PAST run's artifacts
       |(<run-dir>/$ManifestFileName): re-reads the integrity manifest and
       |re-hashes every listed file (missing file, size or sha256 mismatch is
       |a typed refusal), and requires the documented artifact set
       |(${RequiredArtifactNames.mkString(", ")}) to be covered. A torn or
       |missing manifest (interrupted finalization) is refused, never
       |fabricated into a pass. The run directory is never written.
       |
       |Exit codes (ci-runtime §8): 0 = verified (re-hash over the generated
       |set passed), 27 = artifact/evidence integrity failure (any typed
       |refusal), 21 = usage error.""".stripMargin

  /** The deterministic report text (stdout). Named fields only. */
  def render(v: Verified): String =
    s"""artifact-integrity: verified ${v.runDir}
       |entries: ${v.entries} (sha256 re-hash over the manifest set passed)
       |bytes: ${v.bytes}
       |""".stripMargin

  private def renderProblem(p: Problem): String = p match
    case Problem.ManifestMissing(runDir, manifest) =>
      s"artifact manifest missing: $manifest (no integrity manifest in $runDir)"
    case Problem.ManifestUnreadable(manifest, reason) =>
      s"artifact manifest unreadable at $manifest: $reason"
    case Problem.ManifestMalformed(manifest, detail) =>
      s"artifact manifest malformed at $manifest: $detail"
    case Problem.RequiredAbsent(names) =>
      s"required artifact(s) absent from the manifest: ${names.mkString(", ")}"
    case Problem.DuplicateEntry(path) =>
      s"duplicate manifest entry: $path"
    case Problem.PathEscape(path) =>
      s"manifest entry escapes the run directory: $path"
    case Problem.EntryMissing(path) =>
      s"missing artifact file: $path"
    case Problem.SizeMismatch(path, expected, actual) =>
      s"size mismatch: $path (manifest $expected B, actual $actual B)"
    case Problem.DigestMismatch(path, expected, actual) =>
      s"sha256 mismatch: $path (manifest $expected, actual $actual)"
    case Problem.EntryUnreadable(path, reason) =>
      s"artifact unreadable at $path: $reason"

  /** Exit code of a verdict — the frozen ci-runtime §8 mapping only: verified →
    * 0; ANY typed integrity refusal → 27 ([[ExitCodes.ArtifactWriteFailure]] —
    * the artifact/evidence failure code; §8 precedence already treats an
    * artifact failure as stronger than uncertainty, and the run-side required
    * artifact wiring (RAI-186) landed on the same code, so the post-run
    * consumer refuses the same class of evidence failure with the same code).
    * Usage errors are config-invalid (21) in [[exec]] — the ReplayCli /
    * ResumeCommand precedent for malformed input.
    */
  def exitCode(verdict: Verdict): Int = verdict match
    case Left(_)  => ExitCodes.ArtifactWriteFailure
    case Right(_) => ExitCodes.Success
  end exitCode

  /** Exec body: verdict report on stdout, typed problems and usage on stderr
    * (HeadlessRunner convention), then the mapped exit code as the RETURN value
    * — the process exit belongs to `raider.cli.Main`. Read-only: repeated
    * invocations on the same run dir render byte-identical reports and leave
    * the directory byte-identical.
    */
  def exec(argv: List[String]): Int =
    parse(argv) match
      case Left(usageError) =>
        System.err.println(usageError)
        System.err.flush()
        ExitCodes.ConfigInvalid
      case Right(runDir) =>
        verify(runDir) match
          case Left(problems) =>
            val code = exitCode(Left(problems))
            problems.foreach(p =>
              System.err.println(s"typed-error: ${renderProblem(p)}")
            )
            System.err.println(s"exit $code: artifact-write-failure")
            System.err.flush()
            code
          case Right(verified) =>
            System.out.print(render(verified))
            System.out.flush()
            exitCode(Right(verified))
  end exec

  /** The [[HeadlessRunner]] bridge (RAI-210): the product `raider
    * verify-artifacts` dispatch — full delegation to [[exec]], mirroring
    * [[ResumeCommand.outcome]]. The outcome carries `result = None` on purpose:
    * the verification report goes to the console, NOT through the run.json
    * artifact surface — a verification never creates a new invocation root.
    * `workDir` is the caller's placeholder path and is never read for a
    * verify-artifacts outcome.
    */
  def outcome(
      argv: List[String],
      workDir: Path
  ): ZIO[Any, Nothing, HeadlessRunner.Outcome] =
    ZIO.succeed {
      val code = exec(argv)
      HeadlessRunner.Outcome(code, workDir, None)
    }

end ArtifactIntegrityCommand
