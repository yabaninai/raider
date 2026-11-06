package raider.repl.dist

import java.nio.charset.StandardCharsets
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** RAI-262 staging library for the installed REPL launcher distribution.
  *
  * Pure JDK file and zip operations only: the sbt task (raiderReplDist) and the
  * tests run this on the REPL runtime classpath without spawning a REPL. The
  * staged layout is:
  *
  * {{{
  * <out>/<name>/raider-repl        launcher script (executable)
  * <out>/<name>/raider-repl.jar    fat jar, main raider.repl.Main
  * <out>/<name>/version            distribution version, one line
  * <out>/<name>/README.txt         install/run notes
  * <out>/<name>/SHA256SUMS         sha256 of the payload files
  * <out>/<name>.zip                the archive form of the same tree
  * }}}
  *
  * Staging refuses (typed Left, surfaced by the sbt task as a failed build) a
  * jar whose central directory lacks the scala3-repl compiler entry: that
  * refusal is the packaging-side counterpart of the launcher and JVM
  * preflights. Zip entry timestamps are fixed so restaging is deterministic; no
  * byte-level reproducibility is claimed.
  */
final case class StagedDistribution(distDir: Path, archive: Path)

object ReplDistribution:

  final case class Layout(
      jar: Path,
      launcher: Path,
      readme: Path,
      version: String,
      outDir: Path,
      name: String
  )

  /** Central-directory marker proving the jar carries the in-process compiler
    * (scala3-repl 3.9.0).
    */
  val CompilerEntryMarker: String = "dotty/tools/repl/ReplDriver.class"

  private val FixedZipTimeMillis = 1262304000000L // 2010-01-01T00:00:00Z

  private val PayloadNames =
    Seq("README.txt", "raider-repl", "raider-repl.jar", "version")

  /** Stages the distribution tree and its zip archive, replacing any previous
    * staging of the same name (idempotent, stale files removed).
    */
  def stage(layout: Layout): Either[String, StagedDistribution] =
    for
      _ <- validateInputs(layout)
      _ <- validateCompilerEntry(layout.jar)
    yield
      val distDir = layout.outDir.resolve(layout.name)
      removeTree(distDir)
      Files.createDirectories(distDir)
      val jarTarget = distDir.resolve("raider-repl.jar")
      val launcherTarget = distDir.resolve("raider-repl")
      val readmeTarget = distDir.resolve("README.txt")
      val versionTarget = distDir.resolve("version")
      Files.copy(layout.jar, jarTarget, StandardCopyOption.REPLACE_EXISTING)
      Files.copy(
        layout.launcher,
        launcherTarget,
        StandardCopyOption.REPLACE_EXISTING
      )
      Files.setPosixFilePermissions(
        launcherTarget,
        PosixFilePermissions.fromString("rwxr-xr-x")
      )
      Files.copy(
        layout.readme,
        readmeTarget,
        StandardCopyOption.REPLACE_EXISTING
      )
      Files.writeString(
        versionTarget,
        layout.version + "\n",
        StandardCharsets.UTF_8
      )
      Files.writeString(
        distDir.resolve("SHA256SUMS"),
        checksums(distDir),
        StandardCharsets.UTF_8
      )
      val archive = layout.outDir.resolve(layout.name + ".zip")
      Files.deleteIfExists(archive)
      zipTree(distDir, layout.name, archive)
      StagedDistribution(distDir, archive)

  /** Refuses a jar whose central directory lacks the compiler entry
    * (packaging-side preflight; mirrors the launcher and JVM checks).
    */
  def validateCompilerEntry(jar: Path): Either[String, Unit] =
    if !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) then
      Left(
        s"refusing to stage: distribution jar is missing or unreadable: $jar"
      )
    else
      val hasEntry = Using.resource(new ZipFile(jar.toFile)) { zf =>
        Option(zf.getEntry(CompilerEntryMarker)).isDefined
      }
      if hasEntry then Right(())
      else
        Left(
          s"refusing to stage: jar lacks the in-process Scala compiler " +
            s"entry ($CompilerEntryMarker): $jar"
        )

  /** sha256 of a file as lowercase hex (shared with the acceptance specs so the
    * staged SHA256SUMS is verified with the same digest routine).
    */
  def sha256Hex(path: Path): String =
    val md = MessageDigest.getInstance("SHA-256")
    Using.resource(Files.newInputStream(path)) { in =>
      val buf = new Array[Byte](64 * 1024)
      var n = in.read(buf)
      while n >= 0 do
        if n > 0 then md.update(buf, 0, n)
        n = in.read(buf)
    }
    md.digest().map(b => f"${b & 0xff}%02x").mkString

  private def checksums(distDir: Path): String =
    PayloadNames.sorted
      .map(name => s"${sha256Hex(distDir.resolve(name))}  $name")
      .mkString("", "\n", "\n")

  private def validateInputs(layout: Layout): Either[String, Unit] =
    def readable(path: Path, what: String): Either[String, Unit] =
      if Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files
          .isReadable(path)
      then Right(())
      else Left(s"refusing to stage: $what is missing or unreadable: $path")
    for
      _ <- readable(layout.jar, "distribution jar")
      _ <- readable(layout.launcher, "launcher script")
      _ <- readable(layout.readme, "README file")
    yield ()

  private def removeTree(dir: Path): Unit =
    if Files.exists(dir) then
      val walk = Files.walk(dir)
      try
        walk.sorted(java.util.Comparator.reverseOrder[Path]()).forEach { p =>
          Files.deleteIfExists(p): Unit
        }
      finally walk.close()

  private def zipTree(dir: Path, rootName: String, archive: Path): Unit =
    val payload =
      val walk = Files.walk(dir)
      try
        walk
          .iterator()
          .asScala
          .filter(Files.isRegularFile(_))
          .map(p => dir.relativize(p).iterator().asScala.mkString("/"))
          .toVector
          .sorted
      finally walk.close()
    Using.resource(Files.newOutputStream(archive)) { out =>
      Using.resource(new ZipOutputStream(out, StandardCharsets.UTF_8)) { zip =>
        def put(name: String, bytes: Array[Byte]): Unit =
          val entry = new ZipEntry(name)
          entry.setTime(FixedZipTimeMillis)
          zip.putNextEntry(entry)
          zip.write(bytes)
          zip.closeEntry()
        put(s"$rootName/", Array.emptyByteArray)
        payload.foreach { rel =>
          put(s"$rootName/$rel", Files.readAllBytes(dir.resolve(rel)))
        }
      }
    }

/** sbt-side driver for the raiderReplDist task. Argument errors and staging
  * refusals terminate via a thrown exception (the sbt runner fails the task);
  * System.exit stays allowlisted to raider.cli.Main by gate policy.
  */
object ReplDistributionMain:

  private val Usage =
    "usage: ReplDistributionMain --jar <raider-repl.jar> --launcher <script> " +
      "--readme <file> --version <v> --out <dir> [--name <dist-name>]"

  def main(args: Array[String]): Unit =
    def value(flag: String): Option[String] =
      val i = args.indexOf(flag)
      if i >= 0 && i + 1 < args.length then Some(args(i + 1)) else None
    val layout = for
      jar <- value("--jar")
      launcher <- value("--launcher")
      readme <- value("--readme")
      version <- value("--version")
      out <- value("--out")
    yield ReplDistribution.Layout(
      Path.of(jar),
      Path.of(launcher),
      Path.of(readme),
      version,
      Path.of(out),
      value("--name").getOrElse("raider-repl-dist")
    )
    layout match
      case None =>
        System.err.println(Usage)
        throw new IllegalArgumentException("missing distribution arguments")
      case Some(l) =>
        ReplDistribution.stage(l) match
          case Right(staged) =>
            println(s"[raider-repl-dist] staged: ${staged.distDir}")
            println(s"[raider-repl-dist] archive: ${staged.archive}")
          case Left(refusal) =>
            System.err.println(s"[raider-repl-dist] $refusal")
            throw new IllegalStateException(refusal)
