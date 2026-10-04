package raider.tools.files.read

import raider.core.RaiderError
import zio.ZIO

import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Workspace containment (RAI-014, runtime-contracts §10): a path PREFIX is
  * never sufficient. Every access canonicalizes the real path (following
  * symlinks) and requires it to stay inside the workspace root; traversal
  * (`..`) and symlink escapes are denied as typed errors.
  */
final case class Workspace(rootReal: Path):
  def root: Path = rootReal

object Workspace:

  def make(rootPath: String): ZIO[Any, RaiderError, Workspace] =
    ZIO
      .attemptBlocking {
        val p = Paths.get(rootPath)
        if !Files.isDirectory(p) then
          Left(
            RaiderError.InputValidation(
              s"workspace root is not a directory: $rootPath"
            )
          )
        else Right(Workspace(p.toRealPath()))
      }
      .mapError(e =>
        RaiderError.ToolFailed(s"workspace init failed: ${e.getMessage}")
      )
      .flatMap {
        case Left(err) => ZIO.fail(err)
        case Right(ws) => ZIO.succeed(ws)
      }

/** Bounded ranged read with provenance (READ-01..03).
  *
  *   - UTF-8 text only; a NUL byte marks the file binary → declared typed
  *     failure (no silent dump of raw bytes).
  *   - `maxLines` is bounded by `HardMaxLines`; requesting more is a typed
  *     validation error, not a silent clamp.
  *   - Truncation is declared in the result (`truncated`, `totalLines`), never
  *     hidden.
  *   - Provenance: full-file SHA-256 + byte size accompany every read.
  */
final class ReadTool(workspace: Workspace):

  def read(
      relativePath: String,
      offsetLine: Int = 0,
      maxLines: Int = 1000
  ): ZIO[Any, RaiderError, ReadResult] =
    if offsetLine < 0 then
      ZIO.fail(
        RaiderError.InputValidation(s"offsetLine must be >= 0, got $offsetLine")
      )
    else if maxLines < 1 then
      ZIO.fail(
        RaiderError.InputValidation(s"maxLines must be >= 1, got $maxLines")
      )
    else if maxLines > ReadTool.HardMaxLines then
      ZIO.fail(
        RaiderError.InputValidation(
          s"maxLines must be <= ${ReadTool.HardMaxLines}, got $maxLines"
        )
      )
    else
      resolveSafe(relativePath).flatMap { real =>
        ZIO
          .attemptBlocking {
            val bytes = Files.readAllBytes(real)
            if bytes.contains(0.toByte) then
              Left(
                RaiderError.ToolFailed(
                  s"binary file (NUL byte) is not read as text: $relativePath"
                )
              )
            else
              val text =
                new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
              val lines =
                if text.isEmpty then Vector.empty
                else text.split("\n", -1).toVector.map(_.stripSuffix("\r"))
              val total = lines.length
              val from = math.min(offsetLine, total)
              val until = math.min(from + maxLines, total)
              Right(
                ReadResult(
                  content = lines.slice(from, until).toList,
                  totalLines = total,
                  truncated = until < total,
                  sha256 = ReadTool.sha256Hex(bytes),
                  bytes = bytes.length
                )
              )
          }
          .mapError(e =>
            RaiderError.ToolFailed(
              s"read failed for '$relativePath': ${e.getMessage}"
            )
          )
          .flatMap {
            case Left(err)  => ZIO.fail(err)
            case Right(res) => ZIO.succeed(res)
          }
      }

  /** Deleted/changed mid-read: Files.readAllBytes on a vanished file is a typed
    * failure via the mapError path (READ-03 provenance rule: never fake a
    * successful read of a file that no longer exists).
    */
  private def resolveSafe(relativePath: String): ZIO[Any, RaiderError, Path] =
    ZIO
      .attemptBlocking {
        if relativePath.isBlank then
          Left(RaiderError.InputValidation("relative path must be non-empty"))
        else if relativePath.contains("\u0000") then
          Left(RaiderError.InputValidation("relative path contains NUL"))
        else
          val raw = workspace.rootReal.resolve(relativePath).normalize()
          if !raw.startsWith(workspace.rootReal) then
            Left(
              RaiderError.ToolDenied(
                "path escapes workspace (traversal); diagnostics keep the relative form"
              )
            )
          else if !Files.exists(raw) then
            Left(RaiderError.ToolFailed(s"file not found: $relativePath"))
          else
            val real = raw.toRealPath()
            if !real.startsWith(workspace.rootReal) then
              Left(
                RaiderError.ToolDenied(
                  "symlink escapes workspace; access denied"
                )
              )
            else Right(real)
      }
      .mapError(e =>
        RaiderError.ToolFailed(
          s"resolve failed for '$relativePath': ${e.getMessage}"
        )
      )
      .flatMap {
        case Left(err) => ZIO.fail(err)
        case Right(p)  => ZIO.succeed(p)
      }

object ReadTool:
  val HardMaxLines: Int = 50_000

  def sha256Hex(bytes: Array[Byte]): String =
    val d = MessageDigest.getInstance("SHA-256").digest(bytes)
    d.map(b => f"${b & 0xff}%02x").mkString

final case class ReadResult(
    content: List[String],
    totalLines: Int,
    truncated: Boolean,
    sha256: String,
    bytes: Long
)
