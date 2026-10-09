package raider.core.plugin.loader

import raider.core.{RaiderError, RecoveryClass, Tool, ToolCapabilities}
import raider.core.plugin.{
  CapabilityRef,
  PluginDescriptor,
  PluginManifest,
  PluginVersion,
  RaiderPlugin
}

import zio.Scope
import zio.ZIO
import zio.json.EncoderOps

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.jar.{JarEntry, JarOutputStream}

/** Test-scope plugin SPI fixtures (RAI-075). These classes live on the TEST
  * classpath; the fixture jars embed their .class files, so the loader's
  * parent-delegate classloader resolves them from the parent — the spec proves
  * that identity (same Class instance), i.e. child-first is OFF.
  */
class FixtureEchoTool extends Tool:
  def name: String = "fixture.echo"
  def version: Int = 1
  def description: String = "fixture echo tool"
  def recovery: RecoveryClass = RecoveryClass.ReadOnly
  def timeoutMs: Long = 1000L
  def capabilities: ToolCapabilities = ToolCapabilities(concurrentSafe = true)

  def invoke(argumentsJson: String): ZIO[Scope, RaiderError, String] =
    ZIO.succeed(argumentsJson)

class FixtureExtraTool extends Tool:
  def name: String = "fixture.extra"
  def version: Int = 1
  def description: String = "undeclared extra fixture tool"
  def recovery: RecoveryClass = RecoveryClass.ReadOnly
  def timeoutMs: Long = 1000L
  def capabilities: ToolCapabilities = ToolCapabilities(concurrentSafe = true)

  def invoke(argumentsJson: String): ZIO[Scope, RaiderError, String] =
    ZIO.succeed(argumentsJson)

/** Registry-shape violation: timeoutMs must be > 0 (RAI-002.b). */
class FixtureBadTimeoutTool extends Tool:
  def name: String = "fixture.bad"
  def version: Int = 1
  def description: String = "fixture tool with invalid timeout"
  def recovery: RecoveryClass = RecoveryClass.ReadOnly
  def timeoutMs: Long = 0L
  def capabilities: ToolCapabilities = ToolCapabilities(concurrentSafe = true)

  def invoke(argumentsJson: String): ZIO[Scope, RaiderError, String] =
    ZIO.succeed(argumentsJson)

class FixturePlugin extends RaiderPlugin:

  def descriptor: PluginDescriptor = PluginDescriptor(
    name = "fixture",
    version = PluginVersion(1, 0, 0),
    minApiVersion = 1,
    maxApiVersion = 1,
    capabilities = List(CapabilityRef("tool", "fixture.echo"))
  )

/** SPI entry matching a manifest that declares fixture.bad (registry-shape
  * violation lives in the tool, not the descriptor).
  */
class FixtureBadPlugin extends RaiderPlugin:

  def descriptor: PluginDescriptor = PluginDescriptor(
    name = "fixture",
    version = PluginVersion(1, 0, 0),
    minApiVersion = 1,
    maxApiVersion = 1,
    capabilities = List(CapabilityRef("tool", "fixture.bad"))
  )

/** SPI entry whose descriptor disagrees with the manifest it ships in. */
class FixtureMismatchPlugin extends RaiderPlugin:

  def descriptor: PluginDescriptor = PluginDescriptor(
    name = "fixture-mismatch",
    version = PluginVersion(1, 0, 0),
    minApiVersion = 1,
    maxApiVersion = 1,
    capabilities = List(CapabilityRef("tool", "fixture.echo"))
  )

/** Builds real plugin jars for the loader slice.
  *
  * D2 sha256 semantics: pass 1 writes the payload-only jar, the canonical
  * payload digest is computed with the production `PluginLoader.payloadSha256`,
  * pass 2 rewrites the final jar with `META-INF/raider-plugin.json` pinning
  * that digest.
  */
object FixtureJars:

  // Service-file names MUST be the JVM BINARY names: Tool is nested in
  // `object tool`, so its name is "raider.core.tool$Tool" (with a dollar) —
  // spelled via classOf[...].getName, never by hand.
  val PluginService: String =
    "META-INF/services/" + classOf[RaiderPlugin].getName

  val ToolService: String = "META-INF/services/" + classOf[Tool].getName

  def fixtureManifest(sha256: String): PluginManifest = PluginManifest(
    schemaVersion = 1,
    name = "fixture",
    version = PluginVersion(1, 0, 0),
    artifact = "fixtures/fixture-1.0.0.jar",
    sha256 = sha256,
    minApiVersion = 1,
    maxApiVersion = 1,
    capabilities = List(CapabilityRef("tool", "fixture.echo"))
  )

  /** .class bytes of a test-scope class, keyed by its JVM jar entry name. Works
    * for BOTH code-source shapes sbt uses: the test-classes directory and the
    * packaged `raider-core-tests.jar`.
    */
  def classEntry(cl: Class[?]): (String, Array[Byte]) =
    val entryName = cl.getName.replace('.', '/') + ".class"
    val location = cl.getProtectionDomain.getCodeSource.getLocation
    val bytes =
      if location.getFile.endsWith(".jar") then
        val jar = new java.util.jar.JarFile(location.getFile)
        try
          Option(jar.getJarEntry(entryName)) match
            case Some(entry) => jar.getInputStream(entry).readAllBytes()
            case None =>
              throw new IllegalStateException(s"no $entryName in $location")
        finally jar.close()
      else Files.readAllBytes(Paths.get(location.toURI).resolve(entryName))
    entryName -> bytes

  /** Write a jar at `dir/baseName`: payload entries + services files + a
    * manifest whose sha256 pins the payload.
    */
  def pluginJar(
      dir: Path,
      baseName: String,
      classEntries: List[(String, Array[Byte])],
      pluginProviders: List[String],
      toolProviders: List[String],
      manifest: PluginManifest
  ): Path =
    val serviceLines: List[String] => Array[Byte] = providers =>
      providers.mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8)
    val payload: List[(String, Array[Byte])] =
      classEntries ++
        (if pluginProviders.isEmpty then Nil
         else List(PluginService -> serviceLines(pluginProviders))) ++
        (if toolProviders.isEmpty then Nil
         else List(ToolService -> serviceLines(toolProviders)))
    Files.createDirectories(dir)
    val partial = dir.resolve(s"$baseName.partial.jar")
    writeJar(partial, payload)
    val pinned = PluginLoader.payloadSha256(partial) match
      case Right(sha)  => manifest.copy(sha256 = sha)
      case Left(error) => throw new IllegalStateException(error.detail)
    Files.deleteIfExists(partial)
    val finalJar = dir.resolve(s"$baseName.jar")
    val manifestBytes =
      pinned.toJson.getBytes(StandardCharsets.UTF_8)
    writeJar(finalJar, payload :+ (PluginLoader.ManifestEntry -> manifestBytes))
    finalJar

  def writeJar(target: Path, entries: List[(String, Array[Byte])]): Unit =
    val out = new JarOutputStream(Files.newOutputStream(target))
    try
      entries.foreach { (name, bytes) =>
        out.putNextEntry(new JarEntry(name))
        out.write(bytes)
        out.closeEntry()
      }
    finally out.close()

end FixtureJars
