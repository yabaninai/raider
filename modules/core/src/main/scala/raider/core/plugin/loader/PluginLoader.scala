package raider.core.plugin.loader

import raider.core.{Tool, ToolRegistry}
import raider.core.plugin.{
  PluginDescriptor,
  PluginError,
  PluginManifest,
  Preflight,
  RaiderPlugin
}

import zio.json.DecoderOps

import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.{ServiceLoader, ServiceConfigurationError}
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** P4.B plugin loader slice (RAI-075), implementing the controlled classloader
  * contract of ADR-025 §2.4 on top of the accepted P4.A model/preflight
  * skeleton (`raider.core.plugin`).
  *
  * Load order (every deciding step BEFORE any class is defined):
  *   1. read `META-INF/raider-plugin.json` and validate it
  *      (`Preflight.manifest`);
  *   2. verify the pinned sha256 against the jar PAYLOAD (D2: the digest covers
  *      every entry except the manifest itself — a whole-file pin would be a
  *      self-reference fixed point); a mismatch is a typed failure;
  *   3. compatibility preflight against the caller-supplied `apiVersion`;
  *   4. duplicate-name preflight of declared tool capabilities vs the base
  *      `ToolRegistry` (the shared immutable namespace);
  *   5. only then open the classloader and instantiate the SPI entry + declared
  *      tools.
  *
  * Discovery (D4): the jar declares its providers with standard
  * `META-INF/services` entries — exactly one `RaiderPlugin` whose descriptor
  * must equal the manifest, and a `Tool` set that must EQUAL the declared tool
  * capabilities (missing and undeclared tools are typed failures). Hooks and
  * profiles are preflight-validated but deliberately NOT loaded in this slice
  * (own P4.B/C card).
  *
  * Honesty boundaries (ADR-025 §1/§2.4): the parent-delegate URLClassLoader is
  * a COMPATIBILITY boundary, not a security sandbox — child-first loading is
  * deliberately not implemented, plugin code runs with process authority
  * (`System.exit`/native crash are not interceptable). Linkage failures are
  * never swallowed: `LinkageError` and `ServiceConfigurationError` are caught
  * explicitly and converted to typed `PluginError.Malformed` naming the failing
  * class. Tool timeouts stay the Tool contract's business (RAI-002.b) — the
  * loader only validates registry-shape invariants via `ToolRegistry.build`.
  */
object PluginLoader:

  val ManifestEntry: String = "META-INF/raider-plugin.json"

  private val Utf8 = StandardCharsets.UTF_8

  /** The plugin descriptor implied by a manifest (same projection the P4.A
    * preflight uses internally).
    */
  def descriptorOf(m: PluginManifest): PluginDescriptor =
    PluginDescriptor(
      m.name,
      m.version,
      m.minApiVersion,
      m.maxApiVersion,
      m.capabilities
    )

  /** Read + validate the manifest from the jar (preflight step 1). All failures
    * — unreadable file, not a jar, missing/unparsable/invalid manifest — are
    * typed `PluginError`, never exceptions.
    */
  def readManifest(jar: Path): Either[PluginError, PluginManifest] =
    try
      val jarFile = JarFile(jar.toFile)
      try
        Option(jarFile.getEntry(ManifestEntry)) match
          case None =>
            Left(
              PluginError.Malformed(
                s"jar has no $ManifestEntry (not a raider plugin)"
              )
            )
          case Some(entry) =>
            val text =
              new String(jarFile.getInputStream(entry).readAllBytes(), Utf8)
            text.fromJson[PluginManifest] match
              case Left(err) =>
                Left(
                  PluginError.Malformed(
                    s"unparsable plugin manifest: ${err.take(140)}"
                  )
                )
              case Right(m) =>
                Preflight.manifest(m).map(_ => m)
      finally jarFile.close()
    catch
      case e: java.util.zip.ZipException =>
        Left(
          PluginError.Malformed(s"not a jar: ${Option(e.getMessage)
              .getOrElse(e.getClass.getSimpleName)
              .take(80)}")
        )
      case NonFatal(e) =>
        Left(
          PluginError.Malformed(
            s"plugin manifest read failure: ${e.getClass.getSimpleName}"
          )
        )

  /** Canonical payload digest (D2): sha256 over all non-directory entries
    * except [[ManifestEntry]], sorted by entry name, each digested as
    * `name ++ 0x00 ++ size ++ 0x00 ++ content`. This is the pin the embedded
    * manifest's `sha256` field refers to; it is integrity/reproducibility, NOT
    * trust.
    */
  def payloadSha256(jar: Path): Either[PluginError, String] =
    try
      val jarFile = JarFile(jar.toFile)
      try
        val entries = jarFile
          .entries()
          .asScala
          .toList
          .filterNot(_.isDirectory)
          .filterNot(_.getName == ManifestEntry)
          .sortBy(_.getName)
        val digest = MessageDigest.getInstance("SHA-256")
        entries.foreach { entry =>
          val bytes = jarFile.getInputStream(entry).readAllBytes()
          digest.update(entry.getName.getBytes(Utf8))
          digest.update(0.toByte)
          digest.update(bytes.length.toString.getBytes(Utf8))
          digest.update(0.toByte)
          digest.update(bytes)
        }
        Right(digest.digest().map("%02x".format(_)).mkString)
      finally jarFile.close()
    catch
      case e: java.util.zip.ZipException =>
        Left(
          PluginError.Malformed(
            s"not a jar: ${Option(e.getMessage)
                .getOrElse(e.getClass.getSimpleName)
                .take(80)}"
          )
        )
      case NonFatal(e) =>
        Left(
          PluginError.Malformed(
            s"payload digest failure: ${e.getClass.getSimpleName}"
          )
        )

  /** Load a plugin jar against `base` for host API `apiVersion` (ADR-025 §2.4).
    *
    * Preflight (manifest, sha256, compatibility, duplicates vs `base`) runs
    * before the classloader is created; any preflight failure returns a typed
    * error WITHOUT opening the jar for class loading. All decisions reuse the
    * P4.A pure functions — no second validation of the same facts elsewhere
    * (ADR-025 §4).
    */
  def load(
      jar: Path,
      base: ToolRegistry,
      apiVersion: Int
  ): Either[PluginError, LoadedPlugin] =
    for
      manifest <- readManifest(jar)
      actual <- payloadSha256(jar)
      _ <- compareSha(manifest, actual)
      _ <- Preflight.compatibility(descriptorOf(manifest), apiVersion)
      _ <- duplicateVsBase(manifest, base)
      loaded <- instantiate(jar, manifest)
    yield loaded

  private def compareSha(
      manifest: PluginManifest,
      actual: String
  ): Either[PluginError, Unit] =
    if actual == manifest.sha256 then Right(())
    else
      Left(
        PluginError.Malformed(
          s"plugin '${manifest.name}': artifact sha256 mismatch " +
            s"(pinned ${manifest.sha256.take(12)}…, actual ${actual.take(12)}…)"
        )
      )

  private def duplicateVsBase(
      m: PluginManifest,
      base: ToolRegistry
  ): Either[PluginError, Unit] =
    declaredTools(m).find(name => base.lookup(name).isDefined) match
      case Some(clash) => Left(PluginError.DuplicateName("tool", clash))
      case None        => Right(())

  private def declaredTools(m: PluginManifest): List[String] =
    m.capabilities.filter(_.kind == "tool").map(_.name)

  /** Open the classloader and instantiate the SPI entry + declared tools.
    * Parent-delegate ONLY: `URLClassLoader` default semantics, parent = the
    * host loader that carries raider-core/zio — child-first is FORBIDDEN
    * (ADR-025 §2.4), so host classes keep one identity across the boundary. On
    * any failure the opened loader is closed before the typed error is returned
    * (best-effort; class unloading itself is never promised, §2.5).
    */
  private def instantiate(
      jar: Path,
      manifest: PluginManifest
  ): Either[PluginError, LoadedPlugin] =
    val opened =
      try Right(new URLClassLoader(Array(jar.toUri.toURL), hostLoader))
      catch
        case NonFatal(e) =>
          Left(
            PluginError.Malformed(
              s"plugin '${manifest.name}': cannot open classloader " +
                s"(${e.getClass.getSimpleName})"
            )
          )
    opened.flatMap { loader =>
      val result =
        try
          for
            plugin <- singleProvider(loader, classOf[RaiderPlugin])
            _ <- descriptorMatches(plugin, manifest)
            tools <- instantiateTools(loader, manifest)
          yield LoadedPlugin(manifest, plugin, tools, loader)
        catch
          // NOT swallowed: converted to a typed error naming the failing class.
          case e: LinkageError =>
            Left(PluginError.Malformed(linkageDetail(manifest, e)))
          case e: ServiceConfigurationError =>
            Left(PluginError.Malformed(linkageDetail(manifest, e)))
          case NonFatal(e) =>
            Left(
              PluginError.Malformed(
                s"plugin '${manifest.name}': load failure " +
                  s"(${e.getClass.getSimpleName}: ${Option(e.getMessage)
                      .getOrElse("")
                      .take(120)})"
              )
            )
      result.left.foreach(_ => closeQuietly(loader))
      result
    }

  private def hostLoader: ClassLoader = classOf[RaiderPlugin].getClassLoader

  private def linkageDetail(
      manifest: PluginManifest,
      e: Error
  ): String =
    s"plugin '${manifest.name}': provider failure " +
      s"(${e.getClass.getSimpleName}: ${Option(e.getMessage)
          .getOrElse("")
          .take(160)})"

  private def closeQuietly(loader: URLClassLoader): Unit =
    try loader.close()
    catch {
      case NonFatal(_) => ()
    } // best-effort release; never masks the error

  /** Exactly one SPI entry provider, or a typed failure. */
  private def singleProvider[T](
      loader: ClassLoader,
      svc: Class[T]
  ): Either[PluginError, T] =
    val providers =
      ServiceLoader.load(svc, loader).asScala.toList
    providers match
      case single :: Nil => Right(single)
      case Nil =>
        Left(
          PluginError.Malformed(
            s"jar declares no ${svc.getName} provider " +
              s"(missing META-INF/services entry?)"
          )
        )
      case many =>
        Left(
          PluginError.Malformed(
            s"expected exactly one ${svc.getName} provider, found ${many.size}"
          )
        )

  private def descriptorMatches(
      plugin: RaiderPlugin,
      manifest: PluginManifest
  ): Either[PluginError, Unit] =
    val expected = descriptorOf(manifest)
    if plugin.descriptor == expected then Right(())
    else
      Left(
        PluginError.Malformed(
          s"plugin '${manifest.name}': SPI descriptor " +
            s"(${plugin.descriptor.name}/${plugin.descriptor.version}) does not " +
            s"match manifest (${expected.name}/${expected.version})"
        )
      )

  /** Instantiate declared tools: the provided set must EQUAL the declared tool
    * capabilities — duplicates, missing and undeclared tools are typed failures
    * BEFORE any dispatch is possible. Registry-shape invariants (version >= 1,
    * timeout > 0) are validated via the frozen `ToolRegistry.build`; actual
    * timeout enforcement stays with the Tool contract (RAI-002.b), not the
    * loader.
    */
  private def instantiateTools(
      loader: ClassLoader,
      manifest: PluginManifest
  ): Either[PluginError, List[Tool]] =
    val provided =
      ServiceLoader.load(classOf[Tool], loader).asScala.toList
    val names = provided.map(_.name)
    firstDup(names) match
      case Some(dup) => Left(PluginError.DuplicateName("tool", dup))
      case None =>
        val declared = declaredTools(manifest).toSet
        val missing = declared.diff(names.toSet).toList.sorted
        val extra = names.toSet.diff(declared).toList.sorted
        if missing.nonEmpty then
          Left(
            PluginError.Malformed(
              s"plugin '${manifest.name}': declared tool(s) not provided: " +
                missing.mkString(", ")
            )
          )
        else if extra.nonEmpty then
          Left(
            PluginError.Malformed(
              s"plugin '${manifest.name}': tool provider(s) not declared in " +
                s"the manifest: " + extra.mkString(", ")
            )
          )
        else
          // Pure shape validation of the instantiated tools (no dispatch).
          ToolRegistry
            .build(provided)
            .left
            .map { raiderError =>
              PluginError.Malformed(
                s"plugin '${manifest.name}': ${raiderError.detail}"
              )
            }
            .map(_ => provided)

  private def firstDup(as: List[String]): Option[String] =
    as.groupBy(identity)
      .collect { case (a, group) if group.size > 1 => a }
      .toList
      .sorted
      .headOption

end PluginLoader

/** A successfully loaded plugin: the validated manifest, the SPI entry, the
  * instantiated declared tools and the classloader they live in.
  *
  * `close` releases the classloader handle best-effort; JVM hot-unload of the
  * loaded classes is explicitly NOT guaranteed (ADR-025 §2.5) — old instances
  * finish their own lifecycle.
  */
final case class LoadedPlugin(
    manifest: PluginManifest,
    plugin: RaiderPlugin,
    tools: List[Tool],
    classLoader: URLClassLoader
):

  def close(): Unit =
    try classLoader.close()
    catch { case NonFatal(_) => () } // best-effort, documented §2.5
