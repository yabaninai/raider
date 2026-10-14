package raider.cli.workflow

import raider.core.RaiderError
import zio.json.{DeriveJsonCodec, JsonCodec}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

/** Build identity of a compiled chat-workflow launcher artifact (RAI-155.d,
  * ADR-033 §6.4/§7). This is the record that makes "a manual edit invalidates
  * earlier result claims" (CWF-D02) checkable:
  *
  *   - `sourceDigest` — sha256 of the EDITED source bytes that were compiled.
  *     `raider workflow run --source <file>` re-hashes the current source and
  *     refuses a stale build (source changed after compile).
  *   - `classDigest` — sha256 over the compiled class files (sorted relative
  *     path + bytes). Recomputed on EVERY run: a corrupted or partially written
  *     classes directory is a typed refusal, never a launch.
  *
  * The document is deterministic (no timestamps, no absolute paths beyond the
  * recorded file NAME) and describes the build; it is never executed and
  * carries no authority (the launcher binds credentials/policy at run time).
  */
final case class WorkflowBuildIdentity(
    schemaVersion: Int,
    kind: String,
    sourceFileName: String,
    mainClass: String,
    moduleClass: String,
    sourceDigest: String,
    classDigest: String,
    toolchain: WorkflowBuildIdentity.Toolchain
)

object WorkflowBuildIdentity:

  val SchemaVersion: Int = 1
  val Kind: String = "chat-workflow-build"

  final case class Toolchain(scalaVersion: String, compiler: String)

  object Toolchain:
    given JsonCodec[Toolchain] = DeriveJsonCodec.gen[Toolchain]

  given JsonCodec[WorkflowBuildIdentity] = DeriveJsonCodec.gen

  /** Pinned toolchain of ADR-029 D5 / scripts/build/compile.sh. */
  val PinnedToolchain: Toolchain = Toolchain("3.9.0", "dotty.tools.dotc.Main")

  def sha256Hex(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  def sha256Hex(text: String): String =
    sha256Hex(text.getBytes(StandardCharsets.UTF_8))

  /** Digest over a classes directory: every regular file, sorted by relative
    * path, hashed as `relPath\n<byteLength>\n<bytes>`. Deterministic across
    * machines for the same compiler output; detects any corruption, deletion or
    * partial write.
    */
  def classDigestOf(classesDir: Path): Either[RaiderError.Compilation, String] =
    try
      if !Files.isDirectory(classesDir) then
        Left(
          RaiderError.Compilation(
            s"classes directory missing after compile: $classesDir"
          )
        )
      else
        val files = sortedClassFiles(classesDir)
        if files.isEmpty then
          Left(
            RaiderError.Compilation(
              s"no class files produced under $classesDir"
            )
          )
        else
          val digest = MessageDigest.getInstance("SHA-256")
          files.foreach { p =>
            val rel = classesDir.relativize(p).toString
            val bytes = Files.readAllBytes(p)
            digest.update((rel + "\n").getBytes(StandardCharsets.UTF_8))
            digest.update(
              (bytes.length.toString + "\n").getBytes(StandardCharsets.UTF_8)
            )
            digest.update(bytes)
          }
          Right(digest.digest().map(b => f"${b & 0xff}%02x").mkString)
    catch
      case t: Throwable =>
        Left(
          RaiderError.Compilation(
            s"cannot digest classes under $classesDir: ${String.valueOf(t)}"
          )
        )

  private def sortedClassFiles(classesDir: Path): Vector[Path] =
    val stream = Files.walk(classesDir)
    try
      stream
        .iterator()
        .asScala
        .toVector
        .filter(p => Files.isRegularFile(p))
        .sortBy(p => classesDir.relativize(p).toString)
    finally stream.close()

  /** Loads and structurally validates `<classesDir>/../build-identity.json`
    * (the identity sits NEXT to the classes directory inside the build dir).
    */
  def load(buildDir: Path): Either[RaiderError, WorkflowBuildIdentity] =
    val file = buildDir.resolve("build-identity.json")
    if !Files.isRegularFile(file) then
      Left(
        RaiderError.InputValidation(
          s"build identity missing: $file does not exist (is this a raider compile output directory?)"
        )
      )
    else
      try
        val text = Files.readString(file, StandardCharsets.UTF_8)
        zio.json
          .JsonDecoder[WorkflowBuildIdentity]
          .decodeJson(text) match
          case Right(id) =>
            if id.schemaVersion != SchemaVersion then
              Left(
                RaiderError.InputValidation(
                  s"unsupported build identity schemaVersion ${id.schemaVersion} (expected $SchemaVersion) in $file"
                )
              )
            else if id.kind != Kind then
              Left(
                RaiderError.InputValidation(
                  s"not a chat-workflow build directory (kind '${id.kind}' in $file)"
                )
              )
            else Right(id)
          case Left(err) =>
            Left(
              RaiderError.InputValidation(
                s"invalid build identity JSON in $file: $err"
              )
            )
      catch
        case t: Throwable =>
          Left(
            RaiderError.InputValidation(
              s"cannot read build identity $file: ${String.valueOf(t)}"
            )
          )

  /** Verifies the classes directory still matches the recorded `classDigest`.
    */
  def verifyClasses(
      id: WorkflowBuildIdentity,
      buildDir: Path
  ): Either[RaiderError, Unit] =
    classDigestOf(buildDir.resolve("classes")).flatMap { actual =>
      if actual == id.classDigest then Right(())
      else
        Left(
          RaiderError.InputValidation(
            s"stale or corrupted build: class digest mismatch in $buildDir (identity records sha256-${id.classDigest}, classes hash to sha256-$actual); recompile with 'raider compile'"
          )
        )
    }

  /** Derives the JVM class names the renderer's own capitalize/decapitalize
    * rules produce for a `@main def <stem>` + `object <Capitalized>` pair: the @main
    * class is the lower-first stem, the module class is the upper-first stem
    * with `$`.
    */
  def deriveClassNames(sourceFileName: String): Option[(String, String)] =
    val stem =
      if sourceFileName.endsWith(".scala") then
        sourceFileName.dropRight(".scala".length)
      else sourceFileName
    if stem.isEmpty || !stem.head.isLetter then None
    else
      val main = stem.head.toLower.toString + stem.tail
      val module = stem.head.toUpper.toString + stem.tail
      Some((main, module + "$"))

end WorkflowBuildIdentity
