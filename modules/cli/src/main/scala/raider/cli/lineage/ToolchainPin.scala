package raider.cli.lineage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.util.control.NonFatal
import zio.json.EncoderOps

/** Writes the ReplayCli environment pin into the run directory. */
object ToolchainPin:
  final val FileName = "toolchain.json"

  private final case class Pin(
      scala: String,
      jdk: String,
      raider: String,
      model: Option[String]
  )

  private given zio.json.JsonEncoder[Pin] = zio.json.DeriveJsonEncoder.gen[Pin]

  /** Publish a complete pin atomically. Unknown model identity is represented
    * by an absent optional field.
    */
  def write(runDirectory: Path, model: Option[String]): Either[String, Path] =
    val target = runDirectory.resolve(FileName)
    val env = EnvPinning.currentEnv()
    val knownModel = model.filter(_.trim.nonEmpty)
    val content = Pin(
      env.scalaVersion,
      env.jdkVersion,
      env.raiderVersion,
      knownModel
    ).toJson
    try
      Files.createDirectories(runDirectory)
      val tmp = Files.createTempFile(runDirectory, ".toolchain-", ".tmp")
      try
        Files.writeString(tmp, content, UTF_8)
        Files.move(
          tmp,
          target,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE
        )
      finally Files.deleteIfExists(tmp)
      Right(target)
    catch case NonFatal(error) => Left(String.valueOf(error.getMessage))

end ToolchainPin
