package raider.cli.lineage

import raider.dsl.lineage.data.LineageDataDocument
import raider.dsl.lineage.store.{LineageDataStore, StoreError}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.util.UUID
import scala.util.control.NonFatal
import zio.json.*

/** Immutable generation files plus an atomically replaced commit pointer.
  * Readers must use this API; the legacy fixed-name artifacts are not a commit.
  */
object LineageSnapshot:
  final val CommitName = "lineage-commit.json"
  final val DataName = "lineage-data.json"
  final val SourceName = "lineage.scala"

  private final case class Commit(
      protocol: String,
      generation: String,
      dataFile: String,
      dataSha256: String,
      sourceFile: String,
      sourceSha256: String
  )

  private given JsonEncoder[Commit] = DeriveJsonEncoder.gen[Commit]
  private given JsonDecoder[Commit] = DeriveJsonDecoder.gen[Commit]

  final case class Snapshot(
      generation: String,
      dataPath: Path,
      sourcePath: Path,
      document: LineageDataDocument
  )

  enum Error:
    case Missing(path: Path)
    case Invalid(path: Path, detail: String)
    case Data(error: StoreError)
    case Io(path: Path, detail: String)

  /** Publish immutable pair files first, then rename the commit manifest last.
    */
  def publish(
      directory: Path,
      dataBytes: Array[Byte],
      sourceBytes: Array[Byte]
  ): Either[Error, Unit] =
    publishAfterGeneration(directory, dataBytes, sourceBytes)(())

  /** Test seam for the publication crash window. The hook runs only after both
    * immutable generation files are complete and before the pointer temp file
    * is created/replaced. Production callers use [[publish]].
    */
  private[cli] def publishAfterGeneration(
      directory: Path,
      dataBytes: Array[Byte],
      sourceBytes: Array[Byte]
  )(afterGeneration: => Unit): Either[Error, Unit] =
    val generation = UUID.randomUUID().toString
    val dataName = s"lineage-$generation-data.json"
    val sourceName = s"lineage-$generation.scala"
    try
      Files.createDirectories(directory)
      Files.write(directory.resolve(dataName), dataBytes)
      Files.write(directory.resolve(sourceName), sourceBytes)
      afterGeneration
      val manifest = Commit(
        "raider-lineage-snapshot-v1",
        generation,
        dataName,
        sha256(dataBytes),
        sourceName,
        sha256(sourceBytes)
      ).toJson
      val tmp = Files.createTempFile(directory, ".lineage-commit-", ".tmp")
      try
        Files.writeString(tmp, manifest, UTF_8)
        Files.move(
          tmp,
          directory.resolve(CommitName),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE
        )
      finally Files.deleteIfExists(tmp)
      Right(())
    catch
      case NonFatal(t) =>
        Left(Error.Io(directory, String.valueOf(t.getMessage)))

  def read(directory: Path): Either[Error, Snapshot] =
    val pointer = directory.resolve(CommitName)
    if !Files.isRegularFile(pointer) then Left(Error.Missing(pointer))
    else
      try
        Files.readString(pointer, UTF_8).fromJson[Commit] match
          case Left(detail) =>
            Left(Error.Invalid(pointer, s"malformed commit manifest: $detail"))
          case Right(commit)
              if commit.protocol != "raider-lineage-snapshot-v1" ||
                !commit.generation.matches("[0-9a-f-]{36}") ||
                commit.dataFile != s"lineage-${commit.generation}-data.json" ||
                commit.sourceFile != s"lineage-${commit.generation}.scala" ||
                !commit.dataSha256.matches("[0-9a-f]{64}") ||
                !commit.sourceSha256.matches("[0-9a-f]{64}") =>
            Left(
              Error.Invalid(
                pointer,
                "incomplete or inconsistent commit metadata"
              )
            )
          case Right(commit) =>
            val data = directory.resolve(commit.dataFile)
            val source = directory.resolve(commit.sourceFile)
            if !Files.isRegularFile(data) || !Files.isRegularFile(source) then
              Left(Error.Invalid(pointer, "committed generation is incomplete"))
            else
              val dataBytes = Files.readAllBytes(data)
              val sourceBytes = Files.readAllBytes(source)
              if sha256(dataBytes) != commit.dataSha256 || sha256(
                  sourceBytes
                ) != commit.sourceSha256
              then
                Left(
                  Error.Invalid(pointer, "committed generation digest mismatch")
                )
              else
                LineageDataStore
                  .read(data)
                  .left
                  .map(Error.Data(_))
                  .map(doc => Snapshot(commit.generation, data, source, doc))
      catch
        case NonFatal(t) =>
          Left(Error.Io(pointer, String.valueOf(t.getMessage)))

  def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"${b & 0xff}%02x")
      .mkString

end LineageSnapshot
