package raider.cli.run

import raider.core.plugin.{PluginDescriptor, PluginManifest, Preflight}
import raider.core.plugin.loader.PluginLoader

import zio.json.{DeriveJsonEncoder, EncoderOps, JsonEncoder}

import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Plugin catalog CLI (RAI-075, P4.B slice): `plugin list|install <jar>| remove
  * <name>|enable <name>|disable <name>` over the catalog directory
  * `~/.raider/plugins` (override: env `RAIDER_PLUGINS_DIR`).
  *
  * Catalog layout: one jar per installed plugin, named `<name>-<version>.jar`;
  * disable state is a sidecar marker `<jar>.disabled` (empty file). install =
  * manifest check + payload sha256 verify (the same canonical digest the loader
  * checks, so the pin survives the copy) + co-install preflight of the
  * resulting descriptor set (the P4.A pure functions; ADR-025 §4 forbids a
  * second validation logic) + a byte copy that refuses to overwrite. list
  * reports name/version/active per jar; an unreadable jar is reported WITH an
  * `error` field — never silently skipped.
  *
  * Honesty: this manages FILES only — it does not load classes (activation
  * happens when a session runs `PluginLoader.load`); sha256 is integrity, not
  * trust (ADR-025 §2.2); the disabled marker gates activation, not the JVM.
  * Output is plain-text JSON on stdout; exit code 0 = success, 2 = typed
  * failure. Pure `java.nio.file` — no shell, no spawned processes.
  *
  * NOTE (slice boundary): `run(argv, pluginsDir)` takes the directory
  * explicitly so tests can use temp dirs (the JVM cannot mutate its own
  * environment); `RAIDER_PLUGINS_DIR` is honored in [[defaultPluginsDir]].
  * Wiring into the top-level `CliArgs` parser is outside this card's allowed
  * paths — a follow-up card owns the dispatch.
  */
object PluginCli:

  val Usage: String =
    "usage: raider plugin list | install <jar> | remove <name> | " +
      "enable <name> | disable <name>"

  /** Catalog directory: `RAIDER_PLUGINS_DIR` when set and non-empty, else
    * `~/.raider/plugins`.
    */
  def defaultPluginsDir(): Path =
    Option(System.getenv("RAIDER_PLUGINS_DIR"))
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .getOrElse(
        Paths.get(System.getProperty("user.home"), ".raider", "plugins")
      )

  /** Pure dispatch core (RAI-075 testability): one `plugin ...` argv
    * (subcommand included) against `pluginsDir` -> the JSON line to print.
    * `Left(detail)` is a typed failure; `Right(json)` is success.
    */
  def execute(argv: List[String], pluginsDir: Path): Either[String, String] =
    argv match
      case "list" :: Nil           => list(pluginsDir)
      case "install" :: jar :: Nil => install(Paths.get(jar), pluginsDir)
      case "install" :: _ => Left("install requires exactly one <jar> argument")
      case "remove" :: name :: Nil => remove(name, pluginsDir)
      case "remove" :: _ => Left("remove requires exactly one <name> argument")
      case "enable" :: name :: Nil =>
        setEnabled(name, pluginsDir, enable = true)
      case "disable" :: name :: Nil =>
        setEnabled(name, pluginsDir, enable = false)
      case ("enable" | "disable") :: _ =>
        Left("enable/disable require exactly one <name> argument")
      case "list" :: _ => Left("list takes no arguments")
      case _           => Left(s"unknown arguments; $Usage")

  /** CLI entry: prints the JSON outcome on stdout and maps it to the process
    * exit code (0 success, 2 typed failure).
    */
  def run(argv: List[String], pluginsDir: Path): Int =
    execute(argv, pluginsDir) match
      case Right(json) =>
        println(json)
        0
      case Left(detail) =>
        println(FailureOut(detail).toJson)
        2

  /** Convenience overload operating on the production catalog directory. */
  def run(argv: List[String]): Int = run(argv, defaultPluginsDir())

  // ---------------------------------------------------------------------------
  // commands
  // ---------------------------------------------------------------------------

  private def list(pluginsDir: Path): Either[String, String] =
    installedJars(pluginsDir).map { jars =>
      val entries = jars.map { jar =>
        PluginLoader.readManifest(jar) match
          case Right(m) =>
            ListEntry(
              name = m.name,
              version = m.version.toString,
              active = !Files.exists(marker(jar)),
              file = jar.getFileName.toString,
              error = None
            )
          case Left(err) =>
            ListEntry(
              name = "",
              version = "",
              active = false,
              file = jar.getFileName.toString,
              error = Some(err.detail)
            )
      }
      entries.toJson
    }

  private def install(source: Path, pluginsDir: Path): Either[String, String] =
    for
      _ <-
        if Files.isRegularFile(source) then Right(())
        else Left(s"not a file: $source")
      manifest <- PluginLoader.readManifest(source).left.map(_.detail)
      actual <- PluginLoader.payloadSha256(source).left.map(_.detail)
      _ <-
        if actual == manifest.sha256 then Right(())
        else
          Left(
            s"plugin '${manifest.name}': payload sha256 mismatch " +
              s"(pinned ${manifest.sha256.take(12)}…, actual ${actual.take(12)}…) " +
              "— refusing the artifact"
          )
      jars <- installedJars(pluginsDir)
      others <- descriptorsOf(jars)
      _ <- others.find(_.name == manifest.name) match
        case Some(existing) =>
          Left(
            s"plugin '${manifest.name}' is already installed " +
              s"(${existing.version}); remove it first"
          )
        case None => Right(())
      // Tool-namespace clashes across the whole catalog fail here (preflight).
      _ <- Preflight
        .coinstall(others :+ PluginLoader.descriptorOf(manifest))
        .left
        .map(_.detail)
      target = pluginsDir.resolve(s"${manifest.name}-${manifest.version}.jar")
      _ <-
        if Files.exists(target) then
          Left(s"target file already exists: $target")
        else Right(())
      _ <- copyVerified(source, target, manifest, pluginsDir)
    yield OpOut(
      result = "installed",
      name = manifest.name,
      version = manifest.version.toString,
      file = target.getFileName.toString,
      active = Some(true)
    ).toJson

  /** Byte copy followed by re-verification of the copied bytes (the pin is only
    * honest if it is checked against what actually landed in the catalog). A
    * failed copy removes the partial target.
    */
  private def copyVerified(
      source: Path,
      target: Path,
      manifest: PluginManifest,
      pluginsDir: Path
  ): Either[String, Unit] =
    try
      Files.createDirectories(pluginsDir)
      Files.copy(source, target)
      PluginLoader.payloadSha256(target) match
        case Right(sha) if sha == manifest.sha256 => Right(())
        case Right(_) =>
          cleanupPartial(target)
          Left(s"copied bytes differ from source: $target")
        case Left(err) =>
          cleanupPartial(target)
          Left(err.detail)
    catch
      case NonFatal(e) =>
        cleanupPartial(target)
        Left(s"install copy failed (${e.getClass.getSimpleName}): $target")

  private def cleanupPartial(target: Path): Unit =
    try Files.deleteIfExists(target)
    catch { case NonFatal(_) => () }

  private def remove(name: String, pluginsDir: Path): Either[String, String] =
    findByName(name, pluginsDir).flatMap { jar =>
      val version =
        PluginLoader.readManifest(jar).map(_.version.toString).getOrElse("")
      try
        Files.deleteIfExists(marker(jar))
        Files.deleteIfExists(jar)
        Right(
          OpOut(
            result = "removed",
            name = name,
            version = version,
            file = jar.getFileName.toString,
            active = None
          ).toJson
        )
      catch
        case NonFatal(e) =>
          Left(s"remove failed (${e.getClass.getSimpleName}): $jar")
    }

  private def setEnabled(
      name: String,
      pluginsDir: Path,
      enable: Boolean
  ): Either[String, String] =
    findByName(name, pluginsDir).flatMap { jar =>
      val disabledMarker = marker(jar)
      try
        if enable then Files.deleteIfExists(disabledMarker)
        else if !Files.exists(disabledMarker) then
          Files.write(disabledMarker, Array.emptyByteArray)
        val version =
          PluginLoader.readManifest(jar).map(_.version.toString).getOrElse("")
        Right(
          OpOut(
            result = if enable then "enabled" else "disabled",
            name = name,
            version = version,
            file = jar.getFileName.toString,
            active = Some(enable)
          ).toJson
        )
      catch
        case NonFatal(e) =>
          Left(
            s"enable/disable failed (${e.getClass.getSimpleName}): $disabledMarker"
          )
    }

  // ---------------------------------------------------------------------------
  // catalog helpers
  // ---------------------------------------------------------------------------

  /** `<jar>.disabled` sidecar next to the jar file. */
  private def marker(jar: Path): Path =
    jar.resolveSibling(jar.getFileName.toString + ".disabled")

  /** Every `*.jar` in the catalog, sorted by file name (deterministic list
    * output). A missing directory is an empty catalog, not an error.
    */
  private def installedJars(pluginsDir: Path): Either[String, List[Path]] =
    try
      if !Files.isDirectory(pluginsDir) then Right(Nil)
      else
        val stream = Files.list(pluginsDir)
        try
          Right(
            stream.toList.asScala.toList
              .filter(p => p.getFileName.toString.endsWith(".jar"))
              .filter(p => Files.isRegularFile(p))
              .sortBy(p => p.getFileName.toString)
          )
        finally stream.close()
    catch
      case NonFatal(e) =>
        Left(s"cannot list $pluginsDir (${e.getClass.getSimpleName})")

  /** Descriptors of every readable catalog jar (the co-install preflight
    * input); an unreadable jar fails WITH its file name attached.
    */
  private def descriptorsOf(
      jars: List[Path]
  ): Either[String, List[PluginDescriptor]] =
    jars.foldLeft[Either[String, List[PluginDescriptor]]](Right(Nil)) {
      (acc, jar) =>
        for
          done <- acc
          manifest <- PluginLoader.readManifest(jar).left.map { err =>
            s"${jar.getFileName}: ${err.detail}"
          }
        yield done :+ PluginLoader.descriptorOf(manifest)
    }

  /** The installed jar whose manifest declares `name`, or a typed failure
    * naming the missing plugin.
    */
  private def findByName(
      name: String,
      pluginsDir: Path
  ): Either[String, Path] =
    for
      jars <- installedJars(pluginsDir)
      found = jars.collectFirst {
        case jar if PluginLoader.readManifest(jar).exists(_.name == name) => jar
      }
      path <- found match
        case Some(jar) => Right(jar)
        case None      => Left(s"plugin '$name' is not installed")
    yield path

  // ---------------------------------------------------------------------------
  // JSON wire shapes (plain-text output contract)
  // ---------------------------------------------------------------------------

  private final case class ListEntry(
      name: String,
      version: String,
      active: Boolean,
      file: String,
      error: Option[String]
  )

  private object ListEntry:
    given JsonEncoder[ListEntry] = DeriveJsonEncoder.gen[ListEntry]
    given JsonEncoder[List[ListEntry]] = JsonEncoder.list[ListEntry]

  private final case class OpOut(
      result: String,
      name: String,
      version: String,
      file: String,
      active: Option[Boolean]
  )

  private object OpOut:
    given JsonEncoder[OpOut] = DeriveJsonEncoder.gen[OpOut]

  private final case class FailureOut(error: String)

  private object FailureOut:
    given JsonEncoder[FailureOut] = DeriveJsonEncoder.gen[FailureOut]

end PluginCli
