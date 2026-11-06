package raider.tools.files.edit

import raider.core.RaiderError
import raider.tools.files.read.Workspace
import zio.ZIO

import java.nio.file.{Files, LinkOption}

/** Single-file delete (RAI-260): the structured counterpart to fs_edit — the
  * SAME safety contract: expected-SHA provenance (a stale delete is refused,
  * the file survives), workspace containment (realpath, no escapes), bounded
  * size. EXACTLY ONE regular file per call: directories are refused as typed
  * model-correctable feedback (batch deletion goes through fs_patch_multi
  * `deleted file mode` sections, which keep the all-or-nothing guarantee).
  *
  * RAI-276 hardening: an EXPLICIT `Files.isRegularFile` guard with NOFOLLOW
  * semantics runs inside the same staged apply, after the directory check
  * (directories keep their exact refusal) and BEFORE any byte read or unlink.
  * Without it a workspace-local special file (fifo/socket/device) passed
  * exists/isDirectory/size (a fifo reports size 0 without blocking) and reached
  * `Files.readAllBytes`: with no writer the staged apply blocked indefinitely,
  * and with a cooperating writer the empty-content sha matched and the special
  * file was unlinked as if regular — both reproduced in the RAI-276 red specs.
  * NOFOLLOW (not follow-semantics) is the policy-consistent choice: the
  * deny-by-default symlink policy resolves links itself and re-checks
  * containment, so the guard must verify the FINAL resolved path node itself is
  * a regular file, refusing a symlink swapped in for `real` rather than
  * following it. In-workspace symlinks are unaffected — the policy already
  * resolved them to a real target before this check.
  *
  * Missing-file handling (RAI-260 acceptance): a typed InputValidation names
  * the path — nothing is deleted and the model can correct itself.
  *
  * The unlink itself is atomic (POSIX): the file is either fully present or
  * fully gone; the provenance check + delete sequence is serialized by the
  * coding toolset's mutation lock, so a concurrent structured mutation cannot
  * interleave between check and delete.
  */
final class DeleteTool(workspace: Workspace):

  private val editor = new EditTool(workspace)

  def delete(
      relativePath: String,
      expectedSha256: String
  ): ZIO[Any, RaiderError, DeleteResult] =
    if relativePath.trim.isEmpty then
      ZIO.fail(RaiderError.InputValidation("path must be non-empty"))
    else if expectedSha256.trim.isEmpty then
      ZIO.fail(
        RaiderError.InputValidation(
          "delete requires expected_sha256 of the current file (fs_read provides it)"
        )
      )
    else
      editor.resolveSafe(relativePath).flatMap { real =>
        editor
          .attemptR {
            if !Files.exists(real) then
              // Model-correctable domain condition (ADR-026): structured
              // feedback, nothing deleted
              Left(
                RaiderError.InputValidation(
                  s"file not found: $relativePath (nothing deleted)"
                )
              )
            else if Files.isDirectory(real) then
              Left(
                RaiderError.InputValidation(
                  s"refusing to delete a directory: $relativePath " +
                    "(fs_delete removes exactly one regular file)"
                )
              )
            else if !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS) then
              // RAI-276: the remaining non-regular shapes (fifo/socket/device,
              // or a symlink swapped in for the resolved path) are refused as
              // typed model-correctable feedback BEFORE any byte read or
              // unlink; the same staged apply, no new interleaving.
              Left(
                RaiderError.InputValidation(
                  s"refusing to delete a non-regular file: $relativePath " +
                    "(fs_delete removes exactly one regular file)"
                )
              )
            else if Files.size(real) > EditTool.MaxFileBytes then
              Left(
                RaiderError.InputValidation(
                  s"file too large: ${Files.size(real)} > ${EditTool.MaxFileBytes}"
                )
              )
            else
              val bytes = Files.readAllBytes(real)
              val currentSha = editor.sha256(bytes)
              if currentSha != expectedSha256.trim then
                Left(
                  RaiderError.ToolDenied(
                    s"STALE DELETE: expected sha256=$expectedSha256 but file " +
                      s"has $currentSha. Re-read the file and retry with the " +
                      "current sha."
                  )
                )
              else
                Files.delete(real)
                Right(
                  DeleteResult(relativePath, currentSha, bytes.length.toLong)
                )
          }
          .flatMap(_.fold(ZIO.fail, ZIO.succeed))
      }

/** Result of a successful fs_delete: provenance of what was removed (the sha256
  * the deletion was validated against and the removed byte count).
  */
final case class DeleteResult(
    path: String,
    oldSha256: String,
    bytes: Long
)
