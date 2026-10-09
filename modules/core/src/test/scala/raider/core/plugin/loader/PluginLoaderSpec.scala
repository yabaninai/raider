package raider.core.plugin.loader

import raider.core.{
  Tool,
  ToolCapabilities,
  ToolRegistry,
  RaiderError,
  RecoveryClass
}
import raider.core.plugin.{CapabilityRef, PluginError}

import zio.test._
import zio.Scope

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.jar.JarFile

import scala.jdk.CollectionConverters.*

/** RAI-075 loader acceptance (P4.B slice).
  *
  * PL-B-01: roundtrip — a fixture jar assembled at test time (real .class
  * entries + META-INF/raider-plugin.json + META-INF/services) loads, and the
  * parent-delegate contract is proven by CLASS IDENTITY: the loaded plugin and
  * tools are the SAME Class instances as the test classpath's (child-first
  * would define a second copy — forbidden by ADR-025 §2.4).
  *
  * PL-B-02: preflight before load — duplicate names vs the base registry,
  * API-range mismatch and payload sha256 mismatch are typed failures with no
  * classloader opened (pure steps only).
  *
  * PL-B-03: no silent linkage errors — a services entry naming a missing class
  * becomes a typed Malformed that names the failing provider.
  */
object PluginLoaderSpec extends ZIOSpecDefault:

  private val tmp: Path =
    val dir = Files.createTempDirectory("raider-plugin-loader-spec")
    Runtime.getRuntime.addShutdownHook(
      new Thread(() =>
        Files
          .walk(dir)
          .sorted(java.util.Comparator.reverseOrder[Path]())
          .forEach(p => Files.deleteIfExists(p))
      )
    )
    dir

  private val fixtureClasses = List(
    FixtureJars.classEntry(classOf[FixturePlugin]),
    FixtureJars.classEntry(classOf[FixtureEchoTool])
  )

  private val goodJar = FixtureJars.pluginJar(
    tmp,
    "good",
    fixtureClasses,
    pluginProviders = List(classOf[FixturePlugin].getName),
    toolProviders = List(classOf[FixtureEchoTool].getName),
    manifest = FixtureJars.fixtureManifest("0" * 64) // pinned by the builder
  )

  /** PL-B-02: a payload tampered AFTER packaging no longer matches the pinned
    * sha256 (silent substitution between review and install is detected).
    */
  private val tamperedJar: Path =
    val jarFile = new JarFile(goodJar.toFile)
    try
      val entries = jarFile
        .entries()
        .asScala
        .toList
        .filterNot(_.isDirectory)
        .map(e => e.getName -> jarFile.getInputStream(e).readAllBytes())
      val mutated = entries.map { (name, bytes) =>
        if name.endsWith(".class") then name -> (bytes :+ 1.toByte)
        else name -> bytes
      }
      val target = tmp.resolve("tampered.jar")
      FixtureJars.writeJar(target, mutated)
      target
    finally jarFile.close()

  private def baseWith(names: String*): ToolRegistry =
    val tools = names.toList.map(n =>
      new Tool:
        def name: String = n
        def version: Int = 1
        def description: String = "base registry tool"
        def recovery: RecoveryClass = RecoveryClass.ReadOnly
        def timeoutMs: Long = 1000L
        def capabilities: ToolCapabilities =
          ToolCapabilities(concurrentSafe = true)
        def invoke(argumentsJson: String): zio.ZIO[Scope, RaiderError, String] =
          zio.ZIO.succeed(argumentsJson)
    )
    ToolRegistry
      .build(tools)
      .fold(
        e => throw new IllegalStateException(e.detail),
        identity
      )

  private def payloadOnlyJar(name: String): Path =
    val target = tmp.resolve(name)
    FixtureJars.writeJar(
      target,
      List("README.txt" -> "not a plugin".getBytes(StandardCharsets.UTF_8))
    )
    target

  def spec = suite("RAI-075 plugin loader")(
    suite("PL-B-01 roundtrip")(
      test(
        "fixture jar loads; declared tool instantiated; SPI entry resolved"
      ) {
        val res = PluginLoader.load(goodJar, ToolRegistry.empty, 1)
        res.foreach(_.close())
        assertTrue(
          res.exists(_.manifest.name == "fixture"),
          res.exists(_.plugin.descriptor.name == "fixture"),
          res.exists(_.tools.map(_.name) == List("fixture.echo")),
          res.exists(_.tools.head.timeoutMs == 1000L)
        )
      },
      test(
        "parent-delegate proven by identity: loaded classes ARE the parent's (child-first off)"
      ) {
        val res = PluginLoader.load(goodJar, ToolRegistry.empty, 1)
        res.foreach(_.close())
        assertTrue(
          res.exists(l => l.plugin.getClass eq classOf[FixturePlugin]),
          res.exists(l => l.tools.head.getClass eq classOf[FixtureEchoTool]),
          res.exists(l =>
            l.classLoader.getParent eq classOf[FixturePlugin].getClassLoader
          )
        )
      },
      test(
        "payload digest ignores the manifest entry but reacts to payload bytes"
      ) {
        val remanifested = FixtureJars.pluginJar(
          tmp,
          "remanifested",
          fixtureClasses,
          pluginProviders = List(classOf[FixturePlugin].getName),
          toolProviders = List(classOf[FixtureEchoTool].getName),
          manifest =
            FixtureJars.fixtureManifest("0" * 64).copy(name = "renamed")
        )
        val good = PluginLoader.payloadSha256(goodJar)
        val samePayload = PluginLoader.payloadSha256(remanifested)
        val tampered = PluginLoader.payloadSha256(tamperedJar)
        assertTrue(
          good.isRight,
          good == samePayload, // manifest entry is excluded from the digest
          good != tampered // payload byte change is detected
        )
      }
    ),
    suite("PL-B-02 preflight before load")(
      test("duplicate tool name vs base registry is a typed DuplicateName") {
        val res = PluginLoader.load(goodJar, baseWith("fixture.echo"), 1)
        assertTrue(
          res.swap.exists(
            _ == PluginError.DuplicateName("tool", "fixture.echo")
          ),
          res.swap.exists(_.toRaiderError.code == "RA-INP")
        )
      },
      test("API range not covering the host is IncompatibleApi (RA-CAP)") {
        val res = PluginLoader.load(goodJar, ToolRegistry.empty, 2)
        assertTrue(
          res.swap.exists(_.isInstanceOf[PluginError.IncompatibleApi]),
          res.swap.exists(_.toRaiderError.code == "RA-CAP")
        )
      },
      test("payload tampering after packaging is a typed sha256 mismatch") {
        val res = PluginLoader.load(tamperedJar, ToolRegistry.empty, 1)
        assertTrue(
          res.swap.exists {
            case m: PluginError.Malformed => m.what.contains("sha256 mismatch")
            case _                        => false
          }
        )
      },
      test("a file that is not a jar is a typed Malformed") {
        val plain = tmp.resolve("plain.bin")
        Files.write(plain, "definitely not a zip".getBytes)
        assertTrue(
          PluginLoader.load(plain, ToolRegistry.empty, 1).swap.exists {
            case m: PluginError.Malformed => m.what.contains("not a jar")
            case _                        => false
          }
        )
      },
      test("a jar without META-INF/raider-plugin.json is a typed Malformed") {
        val res = PluginLoader.load(
          payloadOnlyJar("no-manifest.jar"),
          ToolRegistry.empty,
          1
        )
        assertTrue(
          res.swap.exists {
            case m: PluginError.Malformed =>
              m.what.contains("raider-plugin.json")
            case _ => false
          }
        )
      }
    ),
    suite("PL-B-03 provider instantiation")(
      test(
        "undeclared tool provider is rejected (set must equal declarations)"
      ) {
        val jar = FixtureJars.pluginJar(
          tmp,
          "undeclared",
          fixtureClasses,
          pluginProviders = List(classOf[FixturePlugin].getName),
          toolProviders = List(
            classOf[FixtureEchoTool].getName,
            classOf[FixtureExtraTool].getName
          ),
          manifest = FixtureJars.fixtureManifest("0" * 64)
        )
        assertTrue(
          PluginLoader.load(jar, ToolRegistry.empty, 1).swap.exists {
            case m: PluginError.Malformed => m.what.contains("not declared")
            case _                        => false
          }
        )
      },
      test("declared tool without a provider is rejected") {
        val jar = FixtureJars.pluginJar(
          tmp,
          "missing-provider",
          fixtureClasses,
          pluginProviders = List(classOf[FixturePlugin].getName),
          toolProviders = Nil,
          manifest = FixtureJars.fixtureManifest("0" * 64)
        )
        assertTrue(
          PluginLoader.load(jar, ToolRegistry.empty, 1).swap.exists {
            case m: PluginError.Malformed => m.what.contains("not provided")
            case _                        => false
          }
        )
      },
      test(
        "registry-shape violations are caught (timeoutMs > 0 via ToolRegistry.build)"
      ) {
        val manifest = FixtureJars
          .fixtureManifest("0" * 64)
          .copy(capabilities = List(CapabilityRef("tool", "fixture.bad")))
        val jar = FixtureJars.pluginJar(
          tmp,
          "bad-timeout",
          List(
            FixtureJars.classEntry(classOf[FixtureBadPlugin]),
            FixtureJars.classEntry(classOf[FixtureBadTimeoutTool])
          ),
          pluginProviders = List(classOf[FixtureBadPlugin].getName),
          toolProviders = List(classOf[FixtureBadTimeoutTool].getName),
          manifest = manifest
        )
        assertTrue(
          PluginLoader.load(jar, ToolRegistry.empty, 1).swap.exists {
            case m: PluginError.Malformed => m.what.contains("timeoutMs")
            case _                        => false
          }
        )
      },
      test(
        "missing provider class: linkage failure becomes a typed error naming it"
      ) {
        val bogus = "raider.core.plugin.loader.NoSuchFixtureClass"
        val jar = FixtureJars.pluginJar(
          tmp,
          "bad-service",
          fixtureClasses,
          pluginProviders = List(bogus),
          toolProviders = List(classOf[FixtureEchoTool].getName),
          manifest = FixtureJars.fixtureManifest("0" * 64)
        )
        assertTrue(
          PluginLoader.load(jar, ToolRegistry.empty, 1).swap.exists {
            case m: PluginError.Malformed =>
              m.what.contains("provider failure") && m.what.contains(bogus)
            case _ => false
          }
        )
      },
      test("SPI descriptor contradicting the manifest is rejected") {
        val jar = FixtureJars.pluginJar(
          tmp,
          "mismatch",
          List(
            FixtureJars.classEntry(classOf[FixtureMismatchPlugin]),
            FixtureJars.classEntry(classOf[FixtureEchoTool])
          ),
          pluginProviders = List(classOf[FixtureMismatchPlugin].getName),
          toolProviders = List(classOf[FixtureEchoTool].getName),
          manifest = FixtureJars.fixtureManifest("0" * 64)
        )
        assertTrue(
          PluginLoader.load(jar, ToolRegistry.empty, 1).swap.exists {
            case m: PluginError.Malformed =>
              m.what.contains("does not match manifest")
            case _ => false
          }
        )
      }
    )
  )
