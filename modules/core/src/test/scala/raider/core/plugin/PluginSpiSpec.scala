package raider.core.plugin

import raider.core.RaiderError

import zio.json.{DecoderOps, EncoderOps}
import zio.test._

import java.nio.charset.StandardCharsets
import java.nio.file.Paths

/** RAI-066 acceptance (PLUG-01..03). PLUG-01: manifest roundtrip + a valid SPI
  * descriptor compiles. PLUG-02: duplicate names / invalid versions / unknown
  * capability / API mismatch are typed preflight errors. PLUG-03: the skeleton
  * has no side effects BY DESIGN — the compiled main classes must not even
  * reference IO/loader/classloader types in their constant pools (stronger than
  * "not called"), and every preflight signature is effect-free.
  */
object PluginSpiSpec extends ZIOSpecDefault:

  private val descriptor = PluginDescriptor(
    name = "fixture-tools",
    version = PluginVersion(1, 4, 2),
    minApiVersion = 1,
    maxApiVersion = 1,
    capabilities = List(
      CapabilityRef("tool", "fixture.echo"),
      CapabilityRef("profile", "fixture.coder"),
      CapabilityRef("hook", "fixture.audit")
    )
  )

  private val manifest = PluginManifest(
    schemaVersion = 1,
    name = "fixture-tools",
    version = PluginVersion(1, 4, 2),
    artifact = "plugins/fixture-tools-1.4.2.jar",
    sha256 = "a" * 64,
    minApiVersion = 1,
    maxApiVersion = 1,
    capabilities = descriptor.capabilities
  )

  /** PLUG-01 compile proof: implementing the frozen SPI and returning a valid
    * descriptor compiles; instantiating it still loads/dispatches nothing.
    */
  private class FixturePlugin extends RaiderPlugin:
    def descriptor: PluginDescriptor = PluginSpiSpec.descriptor

  private val otherDescriptor = PluginDescriptor(
    name = "other-tools",
    version = PluginVersion(0, 0, 1),
    minApiVersion = 1,
    maxApiVersion = ApiVersion.current,
    capabilities = List(CapabilityRef("tool", "other.ping"))
  )

  def spec = suite("RAI-066 plugin SPI skeleton")(
    suite("PLUG-01 manifest + valid SPI")(
      test("valid manifest roundtrips via zio-json") {
        val json = manifest.toJson
        assertTrue(
          json.fromJson[PluginManifest] == Right(manifest),
          json.contains(""""schemaVersion":1"""),
          json.contains(""""sha256":"aaaaaaaa""")
        )
      },
      test("plugin version encodes as the dotted string wire") {
        assertTrue(
          PluginVersion(0, 1, 0).toJson == "\"0.1.0\"",
          "\"1.2.3\"".fromJson[PluginVersion] == Right(PluginVersion(1, 2, 3))
        )
      },
      test("valid descriptor is valid and compatible; SPI class compiles") {
        val plugin = new FixturePlugin
        assertTrue(
          Preflight.descriptor(descriptor).isRight,
          Preflight.compatibility(descriptor).isRight,
          Preflight.manifest(manifest).isRight,
          Preflight.install(manifest).isRight,
          Preflight.coinstall(List(descriptor, otherDescriptor)).isRight,
          plugin.isInstanceOf[RaiderPlugin],
          plugin.descriptor == descriptor
        )
      },
      test("install reuses manifest validation: same negatives, same shape") {
        assertTrue(
          Preflight.install(manifest.copy(sha256 = "xyz")).swap.exists {
            case PluginError.Malformed(what) => what.contains("sha256")
            case _                           => false
          }
        )
      }
    ),
    suite("PLUG-02 typed preflight errors")(
      test("duplicate plugin name across a co-install set") {
        val dup = descriptor.copy(version = PluginVersion(2, 0, 0))
        val res = Preflight.coinstall(List(descriptor, dup))
        assertTrue(
          res.swap.exists(
            _ == PluginError.DuplicateName("plugin", "fixture-tools")
          ),
          res.swap.exists(_.toRaiderError.code == "RA-INP")
        )
      },
      test("duplicate tool name across plugins and within one descriptor") {
        val clash = otherDescriptor.copy(
          capabilities = List(CapabilityRef("tool", "fixture.echo"))
        )
        val selfDup = otherDescriptor.copy(capabilities =
          List(CapabilityRef("tool", "a"), CapabilityRef("tool", "a"))
        )
        assertTrue(
          Preflight
            .coinstall(List(descriptor, clash))
            .swap
            .exists(
              _ == PluginError.DuplicateName("tool", "fixture.echo")
            ),
          Preflight
            .coinstall(List(selfDup))
            .swap
            .exists(
              _ == PluginError.DuplicateName("tool", "a")
            )
        )
      },
      test(
        "invalid versions: parse rejects malformed strings, preflight rejects negative components"
      ) {
        val parseNegatives =
          List("1.2", "01.2.3", "1.2.3.4", "v1.2.3", "1.2.3-rc1", "", "1.-2.3")
        assertTrue(
          parseNegatives.forall(PluginVersion.parse(_).isLeft),
          PluginVersion.parse("1.2.3").isRight,
          PluginVersion.parse("0.0.0").isRight,
          Preflight
            .descriptor(descriptor.copy(version = PluginVersion(-1, 0, 0)))
            .swap
            .exists {
              case PluginError.InvalidVersion(v, _) => v == "-1.0.0"
              case _                                => false
            },
          Preflight
            .descriptor(descriptor.copy(minApiVersion = 0))
            .swap
            .exists(_.isInstanceOf[PluginError.Malformed]),
          Preflight
            .descriptor(descriptor.copy(minApiVersion = 2, maxApiVersion = 1))
            .swap
            .exists(_.isInstanceOf[PluginError.Malformed])
        )
      },
      test("unknown capability kind is a typed error naming the known set") {
        val bad =
          descriptor.copy(capabilities = List(CapabilityRef("mcp-server", "x")))
        val res = Preflight.descriptor(bad)
        assertTrue(
          res.swap.exists(_ == PluginError.UnknownCapability("mcp-server")),
          res.swap.exists { e =>
            e.toRaiderError.code == "RA-INP" &&
            e.toRaiderError.detail.contains("known: tool, profile, hook")
          }
        )
      },
      test("API range not covering the current host is CapabilityUnsupported") {
        val above = descriptor.copy(minApiVersion = 2, maxApiVersion = 3)
        val below = otherDescriptor.copy(maxApiVersion = 0)
        assertTrue(
          Preflight.compatibility(above).swap.exists {
            case PluginError.IncompatibleApi(_, 2, 3, cur) =>
              cur == ApiVersion.current
            case _ => false
          },
          Preflight
            .compatibility(below)
            .swap
            .exists(
              _.toRaiderError.code == "RA-CAP"
            ),
          Preflight
            .install(manifest.copy(minApiVersion = 2, maxApiVersion = 3))
            .swap
            .exists(_.isInstanceOf[PluginError.IncompatibleApi]),
          Preflight
            .coinstall(
              List(otherDescriptor.copy(minApiVersion = 2, maxApiVersion = 2))
            )
            .swap
            .exists(_.isInstanceOf[PluginError.IncompatibleApi])
        )
      },
      test(
        "malformed manifests: schemaVersion, empty name/artifact, bad sha256"
      ) {
        val bads = List(
          manifest.copy(schemaVersion = 2),
          manifest.copy(name = "  "),
          manifest.copy(artifact = ""),
          manifest.copy(sha256 = "XYZ"),
          manifest.copy(sha256 = "A" * 64),
          manifest.copy(capabilities = List(CapabilityRef("tool", " ")))
        )
        assertTrue(
          bads.forall(b =>
            Preflight
              .manifest(b)
              .swap
              .exists(_.isInstanceOf[PluginError.Malformed])
          ),
          Preflight
            .manifest(manifest.copy(schemaVersion = 2))
            .swap
            .exists(_.detail.contains("schemaVersion"))
        )
      },
      test(
        "errors are data: distinct typed cases, explicit RaiderError mapping"
      ) {
        val errs: List[PluginError] = List(
          PluginError.Malformed("x"),
          PluginError.InvalidVersion("1", "y"),
          PluginError.UnknownCapability("z"),
          PluginError.DuplicateName("tool", "t"),
          PluginError.IncompatibleApi("p", 1, 1, 2)
        )
        assertTrue(
          errs.map(_.toRaiderError.code).toSet == Set("RA-INP", "RA-CAP"),
          errs(4).toRaiderError.isInstanceOf[RaiderError.CapabilityUnsupported],
          errs.map(_.detail).distinct.size == errs.size
        )
      }
    ),
    suite("PLUG-03 no side effects by design")(
      test("no preflight signature mentions an effect type") {
        val effectTypes = List("zio.ZIO", "zio.Fiber", "zio.stream", "zio.stm")
        val modules = List(
          Preflight.getClass,
          PluginVersion.getClass,
          PluginDescriptor.getClass,
          PluginManifest.getClass,
          CapabilityRef.getClass,
          PluginError.getClass
        )
        val offenders = for
          cls <- modules
          method <- cls.getDeclaredMethods.toList
          t = method.getReturnType.getName +: method.getParameterTypes.map(
            _.getName
          )
          name <- t
          effect <- effectTypes
          if name.startsWith(effect)
        yield s"${cls.getName}.${method.getName} -> $name"
        assertTrue(offenders.isEmpty)
      },
      test(
        "compiled skeleton classes reference no IO/loader/classloader types"
      ) {
        // IO-capability types only: java/io/Serializable also matches the
        // "java/io/" prefix but is a pure marker interface (case classes
        // implement it automatically) — it grants nothing and stays allowed.
        val forbidden = List(
          "java/io/File",
          "java/io/InputStream",
          "java/io/OutputStream",
          "java/io/Reader",
          "java/io/Writer",
          "java/io/PrintStream",
          "java/io/Console",
          "java/io/RandomAccessFile",
          "java/nio/file",
          "java/net/",
          "ServiceLoader",
          "URLClassLoader",
          "ProcessBuilder",
          "java/lang/Process",
          "java/lang/Runtime",
          "zio/ZIO",
          "zio/Fiber"
        )
        // Scan the MAIN classes output only: RaiderPlugin itself is a product
        // class, so its code source is the main classes directory (test
        // classpath order would otherwise resolve the package resource to
        // test-classes first).
        val codeSource = classOf[RaiderPlugin].getProtectionDomain.getCodeSource
        val dir = Paths
          .get(codeSource.getLocation.toURI)
          .resolve("raider/core/plugin")
          .toFile
        val classFiles = dir
          .listFiles()
          .toList
          .filter(_.getName.endsWith(".class"))
        val violations = for
          f <- classFiles
          bytes = new String(
            java.nio.file.Files.readAllBytes(f.toPath),
            StandardCharsets.ISO_8859_1
          )
          needle <- forbidden
          if bytes.contains(needle)
        yield s"${f.getName}: $needle"
        assertTrue(
          classFiles.nonEmpty,
          dir.getPath.contains("classes"),
          !dir.getPath.contains("test-classes"),
          violations.isEmpty
        )
      }
    )
  )
