package raider.tools.files.edit

import raider.core.RaiderError
import raider.tools.files.read.Workspace
import zio.ZIO

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** Diff-based file editing (nightly Phase 2.4): apply a unified diff to an
  * existing workspace file with the SAME safety contract as fs_edit —
  * expected-SHA preconditions (stale patch refused), workspace containment
  * (realpath, no escapes), bounded file size, atomic temp+rename replace.
  *
  * `expected_sha256` is REQUIRED (a patch without provenance would silently
  * depend on unknown file state).
  *
  * RAI-067 adds [[applyAll]]: multi-file git-style diffs with an all-or-nothing
  * commit. The single-file [[apply]] path and its API are unchanged.
  */
final class PatchTool(workspace: Workspace):

  private val editor = new EditTool(workspace)

  def apply(
      relativePath: String,
      diff: String,
      expectedSha256: String
  ): ZIO[Any, RaiderError, EditResult] =
    if relativePath.trim.isEmpty then
      ZIO.fail(RaiderError.InputValidation("path must be non-empty"))
    else if expectedSha256.trim.isEmpty then
      ZIO.fail(
        RaiderError.InputValidation(
          "patch requires expected_sha256 of the current file (fs_read provides it)"
        )
      )
    else if diff.trim.isEmpty then
      ZIO.fail(RaiderError.InputValidation("diff must be non-empty"))
    else
      editor.resolveSafe(relativePath).flatMap { real =>
        editor
          .attemptR {
            if !java.nio.file.Files.exists(real) then
              Left(
                RaiderError.ToolFailed(
                  s"file not found: $relativePath (patches apply to existing files; use fs_edit to create)"
                )
              )
            else if java.nio.file.Files.size(real) > EditTool.MaxFileBytes then
              Left(
                RaiderError.InputValidation(
                  s"file too large: ${java.nio.file.Files.size(real)} > ${EditTool.MaxFileBytes}"
                )
              )
            else
              val currentBytes =
                java.nio.file.Files.readAllBytes(real)
              val currentSha = editor.sha256(currentBytes)
              if currentSha != expectedSha256.trim then
                Left(
                  RaiderError.ToolDenied(
                    s"STALE PATCH: expected sha256=$expectedSha256 but file has $currentSha. " +
                      "Re-read the file and retry with the current sha."
                  )
                )
              else
                val content = new String(currentBytes, UTF_8)
                val trailingNewline = content.endsWith("\n")
                val src =
                  if content.isEmpty then Nil
                  else content.stripSuffix("\n").linesIterator.toList
                UnifiedDiff
                  .parse(diff)
                  .left
                  .map(err => RaiderError.InputValidation(s"diff: $err"))
                  .flatMap: hunks =>
                    if hunks.isEmpty then
                      Left(
                        RaiderError.InputValidation("diff contains no hunks")
                      )
                    else
                      UnifiedDiff
                        .applyTo(src, hunks)
                        .left
                        .map(err =>
                          RaiderError.InputValidation(s"patch failed: $err")
                        )
                        .map: patchedLines =>
                          val out = patchedLines.mkString(
                            "",
                            "\n",
                            if trailingNewline then "\n" else ""
                          )
                          val bytes = out.getBytes(UTF_8)
                          editor.atomicWrite(real, bytes)
                          EditResult(
                            relativePath,
                            currentSha,
                            editor.sha256(bytes),
                            bytes.length,
                            created = false
                          )
          }
          .flatMap(_.fold(ZIO.fail, ZIO.succeed))
      }

  /** Multi-file apply (RAI-067): validate EVERY section first (workspace
    * containment, provenance, hunks — all in memory), then commit all or
    * nothing.
    *
    * Semantics (the partial-failure contract):
    *   - All-or-nothing by construction: hunks are applied to in-memory
    *     content; the disk is only touched in the commit phase after EVERY file
    *     validated. A validation failure on ANY file means nothing is written.
    *   - Result is a value, not an error: per-file problems (missing file,
    *     stale/missing sha, broken hunks, unsupported sections) are returned in
    *     `failed` with their typed [[RaiderError]]; `applied` stays empty and
    *     `rolledBack` is true (nothing was ever written).
    *   - `staged` names the files that WOULD have been applied (fully computed,
    *     then discarded) — the honest "almost applied" report; the disk was not
    *     touched.
    *   - A commit-phase I/O failure (rare: disk full, permissions) triggers a
    *     best-effort restore of already-written files from in-memory originals.
    *     Full restore → `rolledBack = true` with a ToolFailed entry. Incomplete
    *     restore → `rolledBack = false` with a ToolOutcomeUncertain entry
    *     naming the unrestored files: the caller MUST inspect the workspace.
    *   - Rollback is best-effort, not transactional: a crash mid-commit can
    *     leave partial state; there is no journal. Disjoint concurrent applies
    *     are safe; concurrent applies touching the same file are not (same
    *     TOCTOU window as the single-file path).
    *
    * Provenance: `expectedSha256` maps target path → current sha256. REQUIRED
    * for modified and deleted files; forbidden for created files; extra entries
    * are ignored. Sections with `new file mode`/`deleted file mode`
    * create/delete files; binary/rename/copy sections and symlink/gitlink modes
    * are typed per-file failures.
    */
  def applyAll(
      diff: String,
      expectedSha256: Map[String, String] = Map.empty
  ): ZIO[Any, RaiderError, MultiPatchResult] =
    if diff.trim.isEmpty then
      ZIO.fail(RaiderError.InputValidation("diff must be non-empty"))
    else
      MultiFileDiff.parseSections(diff) match
        case Left(err) =>
          ZIO.fail(RaiderError.InputValidation(s"diff: $err"))
        case Right(Nil) =>
          ZIO.fail(
            RaiderError.InputValidation("diff contains no file sections")
          )
        case Right(sections) =>
          stageAll(sections, expectedSha256).flatMap { (staged, failures) =>
            if failures.nonEmpty then
              ZIO.succeed(
                MultiPatchResult(
                  applied = Nil,
                  failed = failures,
                  rolledBack = true,
                  staged = staged.map(_.relativePath)
                )
              )
            else commit(staged)
          }

  /** Read files, validate every section, compute new contents — ALL in memory,
    * zero writes. Returns staged changes plus per-file failures in section
    * order.
    */
  private def stageAll(
      sections: List[MultiFileDiff.FileSection],
      expectedSha256: Map[String, String]
  ): ZIO[Any, RaiderError, (List[Staged], List[FileFailure])] =
    ZIO
      .foldLeft(sections)((List.empty[Staged], List.empty[FileFailure])) {
        (acc, s) =>
          stageOne(s, expectedSha256).map {
            case Right(st)  => (acc._1 :+ st, acc._2)
            case Left(fail) => (acc._1, acc._2 :+ fail)
          }
      }

  private def stageOne(
      s: MultiFileDiff.FileSection,
      expectedSha256: Map[String, String]
  ): ZIO[Any, Nothing, Either[FileFailure, Staged]] =
    def perFile(e: RaiderError): FileFailure =
      FileFailure(s.target.getOrElse("<unattributable section>"), e)
    s.kind match
      case MultiFileDiff.Kind.Unsupported(reason) =>
        ZIO.succeed(Left(perFile(RaiderError.InputValidation(reason))))
      case _ =>
        val target = s.target.getOrElse("")
        if target.trim.isEmpty then
          ZIO.succeed(
            Left(
              FileFailure(
                target,
                RaiderError.InputValidation("empty target path")
              )
            )
          )
        else
          editor.resolveSafe(target).either.flatMap {
            case Left(e) => ZIO.succeed(Left(FileFailure(target, e)))
            case Right(real) =>
              editor
                .attemptR(stageFile(s, target, real, expectedSha256))
                .either
                .map {
                  case Left(e)              => Left(FileFailure(target, e))
                  case Right(Left(e))       => Left(FileFailure(target, e))
                  case Right(Right(staged)) => Right(staged)
                }
          }

  /** Blocking validation + in-memory application for one already-resolved
    * target. Typed failures are returned; unexpected I/O faults propagate to
    * attemptR.
    */
  private def stageFile(
      s: MultiFileDiff.FileSection,
      target: String,
      real: Path,
      expectedSha256: Map[String, String]
  ): Either[RaiderError, Staged] =
    val badMode = s.modes.find(m => m == "120000" || m == "160000")
    badMode match
      case Some(m) =>
        Left(
          RaiderError.InputValidation(
            s"unsupported file mode $m (symlink/gitlink): $target"
          )
        )
      case None =>
        UnifiedDiff.parse(s.body.mkString("\n")) match
          case Left(err) =>
            Left(
              RaiderError.InputValidation(
                s"malformed section for $target: $err"
              )
            )
          case Right(Nil) =>
            Left(
              RaiderError.InputValidation(
                s"section for $target has no hunks " +
                  "(mode-only changes are not supported)"
              )
            )
          case Right(hunks) =>
            s.kind match
              case MultiFileDiff.Kind.Create =>
                stageCreate(s, target, real, hunks, expectedSha256)
              case MultiFileDiff.Kind.Modify =>
                stageModify(s, target, real, hunks, expectedSha256)
              case MultiFileDiff.Kind.Delete =>
                stageDelete(s, target, real, hunks, expectedSha256)
              case MultiFileDiff.Kind.Unsupported(_) =>
                Left(RaiderError.InputValidation("unsupported section"))

  private def checkProvenance(
      target: String,
      real: Path,
      currentSha: String,
      expectedSha256: Map[String, String]
  ): Option[RaiderError] =
    expectedSha256.get(target) match
      case None =>
        Some(
          RaiderError.InputValidation(
            s"patch requires expected_sha256 for $target (fs_read provides it)"
          )
        )
      case Some(exp) if currentSha != exp.trim =>
        Some(
          RaiderError.ToolDenied(
            s"STALE PATCH: $target expected sha256=$exp but file has $currentSha. " +
              "Re-read the file and retry with the current sha."
          )
        )
      case _ => None

  private def readExisting(
      target: String,
      real: Path
  ): Either[RaiderError, (Array[Byte], String, Boolean, List[String])] =
    if !Files.exists(real) then
      Left(
        RaiderError.ToolFailed(
          s"file not found: $target (patches apply to existing files)"
        )
      )
    else if Files.isDirectory(real) then
      Left(RaiderError.ToolFailed(s"target is a directory: $target"))
    else if Files.size(real) > EditTool.MaxFileBytes then
      Left(
        RaiderError.InputValidation(
          s"file too large: ${Files.size(real)} > ${EditTool.MaxFileBytes}"
        )
      )
    else
      val bytes = Files.readAllBytes(real)
      val content = new String(bytes, UTF_8)
      val trailing = content.endsWith("\n")
      val lines =
        if content.isEmpty then Nil
        else content.stripSuffix("\n").linesIterator.toList
      Right((bytes, editor.sha256(bytes), trailing, lines))

  private def stageCreate(
      s: MultiFileDiff.FileSection,
      target: String,
      real: Path,
      hunks: List[UnifiedDiff.Hunk],
      expectedSha256: Map[String, String]
  ): Either[RaiderError, Staged] =
    if Files.exists(real) then
      Left(
        RaiderError.ToolDenied(
          s"file already exists: $target (new-file patch refused)"
        )
      )
    else if expectedSha256.contains(target) then
      Left(
        RaiderError.InputValidation(
          s"expected_sha256 is not applicable to a created file: $target"
        )
      )
    else
      hunks.find(_.expected.nonEmpty) match
        case Some(h) =>
          Left(
            RaiderError.InputValidation(
              s"new-file hunk for $target must not contain " +
                s"context/deletion lines: ${h.header}"
            )
          )
        case None =>
          val lines = hunks.flatMap(_.replacement)
          val out =
            lines.mkString(
              "",
              "\n",
              if s.endsWithoutNewline || lines.isEmpty then "" else "\n"
            )
          val bytes = out.getBytes(UTF_8)
          if bytes.length > EditTool.MaxFileBytes then
            Left(
              RaiderError.InputValidation(
                s"patched content too large: $target ${bytes.length} > ${EditTool.MaxFileBytes}"
              )
            )
          else Right(Staged.Create(real, target, bytes))

  private def stageModify(
      s: MultiFileDiff.FileSection,
      target: String,
      real: Path,
      hunks: List[UnifiedDiff.Hunk],
      expectedSha256: Map[String, String]
  ): Either[RaiderError, Staged] =
    readExisting(target, real).flatMap {
      (currentBytes, currentSha, trailing, src) =>
        checkProvenance(target, real, currentSha, expectedSha256)
          .toLeft(())
          .flatMap { _ =>
            UnifiedDiff
              .applyTo(src, hunks)
              .left
              .map(err =>
                RaiderError.InputValidation(s"patch failed for $target: $err")
              )
              .map: patched =>
                val out =
                  patched.mkString("", "\n", if trailing then "\n" else "")
                Staged.Modify(
                  real,
                  target,
                  currentBytes,
                  out.getBytes(UTF_8)
                )
          }
    }

  private def stageDelete(
      s: MultiFileDiff.FileSection,
      target: String,
      real: Path,
      hunks: List[UnifiedDiff.Hunk],
      expectedSha256: Map[String, String]
  ): Either[RaiderError, Staged] =
    readExisting(target, real).flatMap { (currentBytes, currentSha, _, src) =>
      checkProvenance(target, real, currentSha, expectedSha256)
        .toLeft(())
        .flatMap { _ =>
          UnifiedDiff
            .applyTo(src, hunks)
            .left
            .map(err =>
              RaiderError.InputValidation(s"patch failed for $target: $err")
            )
            .flatMap: patched =>
              if patched.nonEmpty then
                Left(
                  RaiderError.InputValidation(
                    s"delete patch for $target leaves ${patched.size} line(s); " +
                      "refusing partial deletion"
                  )
                )
              else Right(Staged.Delete(real, target, currentBytes))
        }
    }

  /** Write phase: every file already validated. Individual write/delete
    * failures trigger best-effort restore of the already-applied files from the
    * in-memory originals; the outcome is reported honestly in the result
    * (restored → rolledBack=true, unrestored → ToolOutcomeUncertain).
    */
  private def commit(
      staged: List[Staged]
  ): ZIO[Any, RaiderError, MultiPatchResult] =
    ZIO
      .attemptBlocking {
        var done = List.empty[Staged]
        var failed: Option[(Staged, Throwable)] = None
        val it = staged.iterator
        while it.hasNext && failed.isEmpty do
          val s = it.next()
          try
            s match
              case m: Staged.Modify =>
                Files.createDirectories(m.real.getParent)
                editor.atomicWrite(m.real, m.updated)
              case c: Staged.Create =>
                Files.createDirectories(c.real.getParent)
                editor.atomicWrite(c.real, c.updated)
              case d: Staged.Delete =>
                Files.delete(d.real)
            done = s :: done
          catch case t: Throwable => failed = Some((s, t))
        failed match
          case None =>
            Right(
              MultiPatchResult(
                applied = done.reverse.map(toResult),
                failed = Nil,
                rolledBack = false,
                staged = Nil
              )
            )
          case Some((s, cause)) =>
            val leftovers = restore(done)
            val detail =
              s"commit failed for ${s.relativePath}: ${Option(cause.getMessage).getOrElse(cause.getClass.getSimpleName).take(120)}"
            if leftovers.isEmpty then
              Right(
                MultiPatchResult(
                  applied = Nil,
                  failed = List(
                    FileFailure(
                      s.relativePath,
                      RaiderError.ToolFailed(
                        detail + "; all applied changes were restored"
                      )
                    )
                  ),
                  rolledBack = true,
                  staged = staged.map(_.relativePath)
                )
              )
            else
              Right(
                MultiPatchResult(
                  applied = Nil,
                  failed = List(
                    FileFailure(
                      s.relativePath,
                      RaiderError.ToolOutcomeUncertain(
                        detail + "; rollback incomplete for: " +
                          leftovers.mkString(", ") + " — inspect the workspace"
                      )
                    )
                  ),
                  rolledBack = false,
                  staged = Nil
                )
              )
      }
      .mapError(e =>
        RaiderError.ToolFailed(s"commit failed: ${e.getMessage.take(120)}")
      )
      .flatMap(_.fold(ZIO.fail, ZIO.succeed))

  /** Best-effort, newest-first restore of already-applied changes. Returns the
    * relative paths that could NOT be restored.
    */
  private def restore(done: List[Staged]): List[String] =
    done
      .map: s =>
        try
          s match
            case m: Staged.Modify =>
              Files.createDirectories(m.real.getParent)
              editor.atomicWrite(m.real, m.original)
            case c: Staged.Create =>
              Files.deleteIfExists(c.real)
              ()
            case d: Staged.Delete =>
              Files.createDirectories(d.real.getParent)
              editor.atomicWrite(d.real, d.original)
          None
        catch
          case t: Throwable =>
            Some(
              s"${s.relativePath} (${Option(t.getMessage).getOrElse(t.getClass.getSimpleName).take(60)})"
            )
      .flatten

  private def toResult(s: Staged): EditResult = s match
    case m: Staged.Modify =>
      EditResult(
        m.relativePath,
        editor.sha256(m.original),
        editor.sha256(m.updated),
        m.updated.length,
        created = false
      )
    case c: Staged.Create =>
      EditResult(
        c.relativePath,
        "",
        editor.sha256(c.updated),
        c.updated.length,
        created = true
      )
    case d: Staged.Delete =>
      EditResult(
        d.relativePath,
        editor.sha256(d.original),
        "",
        0,
        created = false
      )

  private sealed trait Staged:
    def real: Path
    def relativePath: String

  private object Staged:

    final case class Modify(
        real: Path,
        relativePath: String,
        original: Array[Byte],
        updated: Array[Byte]
    ) extends Staged

    final case class Create(
        real: Path,
        relativePath: String,
        updated: Array[Byte]
    ) extends Staged

    final case class Delete(
        real: Path,
        relativePath: String,
        original: Array[Byte]
    ) extends Staged

/** Per-file failure of a multi-file apply: the typed error IS the reason
  * (`reason` = detail, `code` = stable machine code for the JSON contract).
  */
final case class FileFailure(file: String, error: RaiderError):
  def code: String = error.code
  def reason: String = error.detail

/** Honest partial-failure report (RAI-067).
  *
  *   - Success: `applied` lists every written file, `failed` empty,
  *     `rolledBack` false, `staged` empty.
  *   - Validation failure on ANY file: `applied` empty (nothing was written —
  *     all-or-nothing), `failed` carries file+typed reason, `rolledBack` true,
  *     `staged` names the files that were fully computed but discarded.
  *   - Commit-phase failure, fully restored: `applied` empty, `rolledBack`
  *     true, `failed` explains the I/O fault.
  *   - Commit-phase failure, restore incomplete: `applied` empty, `rolledBack`
  *     false with a ToolOutcomeUncertain entry — the workspace state is NOT
  *     guaranteed; inspect before retrying. `rolledBack = false` with an empty
  *     `applied` therefore means "unknown, not verified restored".
  */
final case class MultiPatchResult(
    applied: List[EditResult],
    failed: List[FileFailure],
    rolledBack: Boolean,
    staged: List[String]
)
