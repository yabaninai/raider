package raider.tools.files.edit

import raider.core.RaiderError
import raider.tools.files.read.Workspace
import zio.ZIO

import java.nio.charset.StandardCharsets.UTF_8

/** Diff-based file editing (nightly Phase 2.4): apply a unified diff to an
  * existing workspace file with the SAME safety contract as fs_edit —
  * expected-SHA preconditions (stale patch refused), workspace containment
  * (realpath, no escapes), bounded file size, atomic temp+rename replace.
  *
  * `expected_sha256` is REQUIRED (a patch without provenance would silently
  * depend on unknown file state).
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
