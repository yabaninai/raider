package raider.tools.files.edit

import raider.core.RaiderError
import raider.tools.files.read.Workspace
import zio.ZIO

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest

/** Safe file mutation (RAI-015): expected-SHA preconditions, atomic replace
  * (temp+rename), workspace containment (realpath, no escapes).
  */
final class EditTool(workspace: Workspace):

  def write(
      relativePath: String,
      newContent: String,
      expectedSha256: String
  ): ZIO[Any, RaiderError, EditResult] =
    if relativePath.trim.isEmpty then
      ZIO.fail(RaiderError.InputValidation("path must be non-empty"))
    else if expectedSha256.isEmpty then createNew(relativePath, newContent)
    else modifyExisting(relativePath, newContent, expectedSha256)

  private def createNew(
      relativePath: String,
      newContent: String
  ): ZIO[Any, RaiderError, EditResult] =
    resolveSafe(relativePath).flatMap { real =>
      attemptR {
        if Files.exists(real) then
          Left(
            RaiderError.ToolDenied(
              s"file already exists: $relativePath (use expectedSha256 to modify)"
            )
          )
        else
          val bytes = newContent.getBytes(UTF_8)
          atomicWrite(real, bytes)
          Right(EditResult(relativePath, "", sha256(bytes), bytes.length, true))
      }.flatMap(fromEither)
    }

  private def modifyExisting(
      relativePath: String,
      newContent: String,
      expectedSha: String
  ): ZIO[Any, RaiderError, EditResult] =
    resolveSafe(relativePath).flatMap { real =>
      attemptR {
        if !Files.exists(real) then
          Left(
            RaiderError.ToolFailed(
              s"file not found: $relativePath (use expectedSha256=\"\" to create)"
            )
          )
        else if Files.size(real) > EditTool.MaxFileBytes then
          Left(
            RaiderError.InputValidation(
              s"file too large: ${Files.size(real)} > ${EditTool.MaxFileBytes}"
            )
          )
        else
          val currentBytes = Files.readAllBytes(real)
          val currentSha = sha256(currentBytes)
          if currentSha != expectedSha then
            Left(
              RaiderError.ToolDenied(
                s"STALE WRITE: expected sha256=$expectedSha but file has $currentSha. " +
                  "Re-read the file and retry with the current sha."
              )
            )
          else
            val bytes = newContent.getBytes(UTF_8)
            atomicWrite(real, bytes)
            Right(
              EditResult(
                relativePath,
                currentSha,
                sha256(bytes),
                bytes.length,
                false
              )
            )
      }.flatMap(fromEither)
    }

  private def fromEither(
      e: Either[RaiderError, EditResult]
  ): ZIO[Any, RaiderError, EditResult] =
    e.fold(ZIO.fail, ZIO.succeed)

  private def resolveSafe(relativePath: String): ZIO[Any, RaiderError, Path] =
    attemptR {
      val raw = workspace.root.resolve(relativePath).normalize()
      if !raw.startsWith(workspace.root) then
        Left(RaiderError.ToolDenied(s"path escapes workspace: $relativePath"))
      else
        val real =
          if Files.exists(raw) then raw.toRealPath() else raw.normalize()
        if !real.startsWith(workspace.root) then
          Left(
            RaiderError.ToolDenied(s"symlink escapes workspace: $relativePath")
          )
        else Right(real)
    }.flatMap(_.fold(ZIO.fail, ZIO.succeed))

  private def atomicWrite(target: Path, bytes: Array[Byte]): Unit =
    val tmp = target.resolveSibling(
      target.getFileName.toString + ".raider-tmp-" +
        java.util.UUID.randomUUID().toString.take(8)
    )
    Files.write(tmp, bytes)
    Files.move(
      tmp,
      target,
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE
    )

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def attemptR[A](f: => A): ZIO[Any, RaiderError, A] =
    ZIO
      .attemptBlocking(f)
      .mapError(e => RaiderError.ToolFailed(e.getMessage.take(120)))

object EditTool:
  val MaxFileBytes: Long = 4 * 1024 * 1024

  def sha256OfFile(path: Path): Either[RaiderError, String] =
    try
      val bytes = Files.readAllBytes(path)
      Right(
        MessageDigest
          .getInstance("SHA-256")
          .digest(bytes)
          .map(b => f"${b & 0xff}%02x")
          .mkString
      )
    catch
      case e: Exception =>
        Left(RaiderError.ToolFailed(s"sha256: ${e.getMessage.take(80)}"))

end EditTool

final case class EditResult(
    path: String,
    oldSha256: String,
    newSha256: String,
    bytes: Long,
    created: Boolean
)
