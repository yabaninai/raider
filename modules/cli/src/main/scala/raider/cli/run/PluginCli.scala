package raider.cli.run

import raider.core.plugin.{PluginDescriptor, PluginManifest, Preflight}
import raider.core.plugin.loader.PluginLoader

import zio.json.{DeriveJsonEncoder, EncoderOps, JsonEncoder}

import java.nio.file.{Files, Path, Paths, StandardCopyOption}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Plugin catalog CLI (RAI-075, P4.B slice; RAI-101 adds update/rollback):
  * `plugin list|install <jar>|remove <name>|enable <name>|disable <name>|
  * update <name> <jar>|rollback <name>` over the catalog directory
  * `~/.raider/plugins` (override: env `RAIDER_PLUGINS_DIR`).
  *
  * Catalog layout: one jar per installed plugin, named `<name>-<version>.jar`;
  * disable state is a sidecar marker `<jar>.disabled` (empty file) that BELONGS
  * TO THE FILE and follows it through update/rollback (an update never silently
  * re-activates a disabled plugin — ADR-025 §2.5 keeps activation
  * owner-explicit). RAI-101 adds a single-level backup sidecar
  * `<name>.prev.jar`: the jar exactly as it was before the last update.
  * Sidecars never appear in `list` and never enter co-install preflight;
  * `remove` drops them together with the plugin. install = manifest check +
  * payload sha256 verify (the same canonical digest the loader checks, so the
  * pin survives the copy) + co-install preflight of the resulting descriptor
  * set (the P4.A pure functions; ADR-025 §4 forbids a second validation logic)
  * + a byte copy that refuses to overwrite. list reports name/version/active
  * per jar; an unreadable jar is reported WITH an `error` field — never
  * silently skipped.
  *
  * update/rollback protocol (RAI-101): every decision runs BEFORE the catalog
  * is touched — manifest validity, payload sha256 pin, name match with the
  * installed plugin, co-install preflight of the resulting descriptor set. The
  * new jar is then staged inside the catalog dir under a hidden
  * `<name>.update-*.tmp` name (never matching `*.jar`, so no partial plugin is
  * ever observable) and byte-verified there; the swap itself is two
  * `ATOMIC_MOVE` renames: current → `<name>.prev.jar`, staged →
  * `<name>-<version>.jar`. Portable NIO has no two-file atomic swap: a failure
  * between the renames triggers a BEST-EFFORT restore of the displaced jar and
  * the error says so honestly — this is not a filesystem transaction (ADR-025
  * non-goals). rollback re-verifies the prev sidecar (manifest + pinned
  * payload) before swapping, and rolling back twice is a true swap: current and
  * prev exchange places each time.
  *
  * SESSION SEMANTICS (UPL-03; ADR-025 §2.5 — drain/restart, honestly): these
  * commands manage CATALOG FILES ONLY. A session activates plugins by loading
  * the catalog when it starts; once loaded, it holds its own open jar handle
  * and classloader, so catalog mutations NEVER reach a running session. The
  * displaced jar is preserved byte-for-byte, so an artifact handle opened
  * before the swap keeps reading exactly the old bytes — running sessions
  * finish their own lifecycle undisturbed. NO hot-unload of loaded JVM classes
  * is promised, ever: a new or rolled-back version applies to NEW sessions only
  * (drain/restart — restart a session to pick the version up).
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
  * Wiring into the top-level argv dispatch is `Main.pluginCommand` (RAI-083);
  * this object stays Main-agnostic and dispatch-complete.
  */
object PluginCli:

  val Usage: String =
    "usage: raider plugin list | install <jar> | remove <name> | " +
      "enable <name> | disable <name> | update <name> <jar> | rollback <name>"

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
      case "update" :: name :: jar :: Nil =>
        update(name, Paths.get(jar), pluginsDir)
      case "update" :: _ =>
        Left("update requires exactly <name> and <jar> arguments")
      case "rollback" :: name :: Nil => rollback(name, pluginsDir)
      case "rollback" :: _ =>
        Left("rollback requires exactly one <name> argument")
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
        // RAI-101: the backup sidecar belongs to the plugin, not to one
        // version file — remove drops it too (a stale prev must never be
        // restorable for a plugin that no longer exists).
        val prev = pluginsDir.resolve(s"$name.prev.jar")
        Files.deleteIfExists(prev)
        Files.deleteIfExists(marker(prev))
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
  // update / rollback (RAI-101, UPL-01..03)
  // ---------------------------------------------------------------------------

  /** `plugin update <name> <jar>`: replace the installed jar of `name` with
    * `source` after FULL preflight — manifest validity, payload sha256 pin,
    * name match with the installed plugin, co-install preflight of the new
    * descriptor against the REST of the catalog (a tool-namespace clash fails
    * before any file is touched). The displaced jar is preserved byte-for-byte
    * as `<name>.prev.jar` (single-level undo: an earlier backup is replaced);
    * the new jar lands as `<name>-<version>.jar` via two `ATOMIC_MOVE` renames
    * (see the swap protocol in the type doc). The disable state the plugin had
    * at update time carries over to the new version. Same-version updates are
    * allowed (a repair path); the pinned bytes still decide.
    */
  private def update(
      name: String,
      source: Path,
      pluginsDir: Path
  ): Either[String, String] =
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
              s"(pinned ${manifest.sha256.take(12)}…, " +
              s"actual ${actual.take(12)}…) — refusing the artifact"
          )
      current <- findByName(name, pluginsDir)
      _ <-
        if manifest.name == name then Right(())
        else
          Left(
            s"update targets plugin '$name' but the jar manifest " +
              s"declares '${manifest.name}'"
          )
      jars <- installedJars(pluginsDir)
      others <- descriptorsOf(jars.filterNot(_ == current))
      _ <- Preflight
        .coinstall(others :+ PluginLoader.descriptorOf(manifest))
        .left
        .map(_.detail)
      staged <- stageVerifiedCopy(source, name, manifest, pluginsDir)
      prevPath = pluginsDir.resolve(s"$name.prev.jar")
      target = pluginsDir.resolve(s"${manifest.name}-${manifest.version}.jar")
      wasDisabled = Files.exists(marker(current))
      _ <- swapPreserving("update", current, prevPath, target, staged)
      _ <- setMarkerState("update", target, disabled = wasDisabled)
    yield OpOut(
      result = "updated",
      name = name,
      version = manifest.version.toString,
      file = target.getFileName.toString,
      active = Some(!wasDisabled)
    ).toJson

  /** `plugin rollback <name>`: swap the installed jar back to the preserved
    * `<name>.prev.jar` bytes; the displaced current jar becomes the new prev (a
    * true swap — rolling back twice alternates the two versions). The restored
    * jar lands as `<name>-<version>.jar`, version taken from the prev sidecar's
    * OWN manifest, which is re-verified against its pinned payload first. A
    * missing, unreadable or mistyped sidecar is a typed failure; the installed
    * jar is untouched in every failure case.
    */
  private def rollback(name: String, pluginsDir: Path): Either[String, String] =
    for
      current <- findByName(name, pluginsDir)
      prevPath = pluginsDir.resolve(s"$name.prev.jar")
      _ <-
        if Files.isRegularFile(prevPath) then Right(())
        else
          Left(
            s"plugin '$name': no previous version saved " +
              "(update first; nothing to roll back)"
          )
      prevManifest <-
        PluginLoader.readManifest(prevPath).left.map { err =>
          s"${prevPath.getFileName}: ${err.detail}"
        }
      _ <-
        if prevManifest.name == name then Right(())
        else
          Left(
            s"plugin '$name': prev sidecar manifest declares " +
              s"'${prevManifest.name}' — refusing the swap"
          )
      prevSha <- PluginLoader.payloadSha256(prevPath).left.map(_.detail)
      _ <-
        if prevSha == prevManifest.sha256 then Right(())
        else
          Left(
            s"plugin '$name': previous artifact sha256 mismatch " +
              s"(pinned ${prevManifest.sha256.take(12)}…, " +
              s"actual ${prevSha.take(12)}…) — refusing the swap"
          )
      staged <- stageRenamed(prevPath, name, pluginsDir)
      target =
        pluginsDir.resolve(s"${prevManifest.name}-${prevManifest.version}.jar")
      _ <-
        swapPreserving(
          "rollback",
          current,
          prevPath,
          target,
          staged,
          keepStagedOnFailure = true
        )
    yield OpOut(
      result = "rolled_back",
      name = name,
      version = prevManifest.version.toString,
      file = target.getFileName.toString,
      active = Some(!Files.exists(marker(target)))
    ).toJson

  /** The swap: `staged` lands at `target` while the displaced `current` is
    * preserved at `prevPath`, both as same-directory `ATOMIC_MOVE` renames,
    * each jar moving together with its disable sidecar. A failure between the
    * two renames triggers a BEST-EFFORT restore of the displaced jar; when the
    * restore itself fails the error names the mid-swap state honestly.
    * `keepStagedOnFailure` is true for rollback, whose staged bytes ARE the
    * (irreplaceable) prev artifact — they are never deleted on failure.
    */
  private def swapPreserving(
      op: String,
      current: Path,
      prevPath: Path,
      target: Path,
      staged: Path,
      keepStagedOnFailure: Boolean = false
  ): Either[String, Unit] =
    try
      Files.createDirectories(current.getParent)
      moveWithMarker(current, prevPath) // displaced jar becomes the backup
      moveWithMarker(staged, target) // staged bytes land in place
      Right(())
    catch
      case NonFatal(e) =>
        val restored =
          try
            if !Files.exists(current) && Files.exists(prevPath) then
              moveWithMarker(prevPath, current)
              true
            else false
          catch case NonFatal(_) => false // never masks the primary failure
        if !keepStagedOnFailure then cleanupPartial(staged)
        if restored then
          Left(
            s"$op failed (${e.getClass.getSimpleName}); previous version " +
              s"restored: ${current.getFileName}"
          )
        else
          Left(
            s"$op failed (${e.getClass.getSimpleName}); catalog may be " +
              s"mid-swap (current=${current.getFileName}, " +
              s"prev=${prevPath.getFileName}" +
              (if keepStagedOnFailure then
                 s", staged bytes preserved at ${staged.getFileName})"
               else ")")
          )

  /** Copy `source` into the catalog dir under a hidden temp name (never
    * matching `*.jar`, so list/co-install never observe a partial plugin) and
    * re-verify the copied bytes against the pin — the pin is only honest if it
    * is checked against what actually landed (same rule as install).
    */
  private def stageVerifiedCopy(
      source: Path,
      name: String,
      manifest: PluginManifest,
      pluginsDir: Path
  ): Either[String, Path] =
    try
      Files.createDirectories(pluginsDir)
      val staged = Files.createTempFile(pluginsDir, s".$name-update-", ".tmp")
      try
        Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING)
        PluginLoader.payloadSha256(staged) match
          case Right(sha) if sha == manifest.sha256 => Right(staged)
          case Right(_) =>
            cleanupPartial(staged)
            Left(s"copied bytes differ from source: $source")
          case Left(err) =>
            cleanupPartial(staged)
            Left(err.detail)
      catch
        case NonFatal(e) =>
          cleanupPartial(staged)
          Left(s"update copy failed (${e.getClass.getSimpleName}): $source")
    catch
      case NonFatal(e) =>
        Left(s"update copy failed (${e.getClass.getSimpleName}): $pluginsDir")

  /** Stage the prev bytes for a rollback swap: a plain same-directory rename to
    * a hidden temp name (no byte copy — the bytes were just verified), which
    * frees the `.prev` slot for the displaced current jar. The disable sidecar
    * moves along (moveWithMarker), so the restored version keeps its own
    * active/disabled state.
    */
  private def stageRenamed(
      prev: Path,
      name: String,
      pluginsDir: Path
  ): Either[String, Path] =
    try
      Files.createDirectories(pluginsDir)
      val staged = Files.createTempFile(pluginsDir, s".$name-prev-", ".tmp")
      moveWithMarker(prev, staged)
      Right(staged)
    catch
      case NonFatal(e) =>
        Left(s"rollback failed (${e.getClass.getSimpleName}): $prev")

  /** Same-directory rename, atomic when the filesystem supports it: readers
    * observe either the old or the new complete file, never a partial one.
    * Falls back to a plain `REPLACE_EXISTING` move where `ATOMIC_MOVE` is
    * unsupported (same-directory moves stay rename-based there too).
    */
  private def moveAtomic(from: Path, to: Path): Unit =
    try
      Files.move(
        from,
        to,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
    catch
      case _: java.nio.file.AtomicMoveNotSupportedException =>
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)

  /** Move a jar together with its `<jar>.disabled` sidecar: the disable state
    * belongs to the FILE, so it follows through update/rollback. A stale
    * destination marker without a source marker is cleared.
    */
  private def moveWithMarker(from: Path, to: Path): Unit =
    moveAtomic(from, to)
    val m = marker(from)
    if Files.exists(m) then moveAtomic(m, marker(to))
    else Files.deleteIfExists(marker(to))

  /** Force the disable sidecar into the given state (update carries the
    * plugin's pre-swap state over to the new version).
    */
  private def setMarkerState(
      op: String,
      jar: Path,
      disabled: Boolean
  ): Either[String, Unit] =
    try
      if disabled then
        if !Files.exists(marker(jar)) then
          Files.write(marker(jar), Array.emptyByteArray)
      else Files.deleteIfExists(marker(jar))
      Right(())
    catch
      case NonFatal(e) =>
        Left(s"$op failed (${e.getClass.getSimpleName}): ${marker(jar)}")

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
              // RAI-101: `<name>.prev.jar` is the backup sidecar, not an
              // installed plugin — never listed, never a co-install input.
              .filterNot(p => p.getFileName.toString.endsWith(".prev.jar"))
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
