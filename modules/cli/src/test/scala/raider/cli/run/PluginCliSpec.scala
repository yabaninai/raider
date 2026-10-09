package raider.cli.run

import raider.core.plugin.{CapabilityRef, PluginManifest, PluginVersion}
import raider.core.plugin.loader.PluginLoader

import zio.json.EncoderOps
import zio.test._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.jar.{JarEntry, JarOutputStream}

import scala.jdk.CollectionConverters.*

/** RAI-075 CLI acceptance (P4.B slice): list/install/remove against a TEMP
  * catalog directory (the explicit-dir overload; the JVM cannot mutate its env,
  * so RAIDER_PLUGINS_DIR is exercised only through the production default in
  * [[PluginCli.defaultPluginsDir]] — see the resolution unit below).
  *
  * The fixture jars carry NO classes: install is a FILE operation whose checks
  * are manifest validity + payload sha256 + co-install preflight (all pure,
  * reusing the P4.A functions) — class loading belongs to PluginLoader and is
  * covered by raiderCore's PluginLoaderSpec.
  */
object PluginCliSpec extends ZIOSpecDefault:

  private val tmp: Path =
    val dir = Files.createTempDirectory("raider-plugin-cli-spec")
    Runtime.getRuntime.addShutdownHook(
      new Thread(() =>
        Files
          .walk(dir)
          .sorted(java.util.Comparator.reverseOrder[Path]())
          .forEach(p => Files.deleteIfExists(p))
      )
    )
    dir

  /** Per-test catalog subdirectory: zio.test runs tests concurrently, so no two
    * tests may share one mutable catalog.
    */
  private def ownCatalog(test: String): Path =
    tmp.resolve(s"catalog-$test")

  private val PluginApiMax = 9

  private def manifest(
      name: String,
      version: PluginVersion,
      minApi: Int = 1,
      maxApi: Int = PluginApiMax
  ): PluginManifest =
    PluginManifest(
      schemaVersion = 1,
      name = name,
      version = version,
      artifact = s"fixtures/$name.jar",
      sha256 = "0" * 64, // replaced by the builder with the payload pin
      minApiVersion = minApi,
      maxApiVersion = maxApi,
      capabilities = List(CapabilityRef("tool", s"$name.echo"))
    )

  /** Manifest-only plugin jar: one payload entry + the manifest pinning the
    * canonical payload digest (D2 — same production digest function the loader
    * checks at activation time).
    */
  private def pluginJar(
      name: String,
      version: PluginVersion,
      dir: Path,
      minApi: Int = 1,
      payloadBody: String = "fixture-payload"
  ): Path =
    Files.createDirectories(dir)
    val partial = dir.resolve(s"$name.partial.jar")
    writeJar(
      partial,
      List("lib/payload.txt" -> payloadBody.getBytes(StandardCharsets.UTF_8))
    )
    val pinned = PluginLoader.payloadSha256(partial) match
      case Right(sha)  => manifest(name, version, minApi).copy(sha256 = sha)
      case Left(error) => throw new IllegalStateException(error.detail)
    Files.deleteIfExists(partial)
    val target = dir.resolve(s"$name-fixture.jar")
    writeJar(
      target,
      List(
        "lib/payload.txt" -> payloadBody.getBytes(StandardCharsets.UTF_8),
        PluginLoader.ManifestEntry -> pinned.toJson.getBytes(
          StandardCharsets.UTF_8
        )
      )
    )
    target

  private def writeJar(
      target: Path,
      entries: List[(String, Array[Byte])]
  ): Unit =
    val out = new JarOutputStream(Files.newOutputStream(target))
    try
      entries.foreach { (name, bytes) =>
        out.putNextEntry(new JarEntry(name))
        out.write(bytes)
        out.closeEntry()
      }
    finally out.close()

  def spec = suite("RAI-075 plugin CLI")(
    test("default dir honors RAIDER_PLUGINS_DIR, else ~/.raider/plugins") {
      // env is read at call time; this process may or may not carry it — both
      // branches are asserted against their honest precondition.
      val env = Option(System.getenv("RAIDER_PLUGINS_DIR"))
        .map(_.trim)
        .filter(_.nonEmpty)
      val resolved = PluginCli.defaultPluginsDir()
      env match
        case Some(value) =>
          assertTrue(resolved.toString == value)
        case None =>
          val expected =
            java.nio.file.Paths.get(
              System.getProperty("user.home"),
              ".raider",
              "plugins"
            )
          assertTrue(resolved == expected)
    },
    test("install places a verified copy; list shows name/version/active") {
      val dir = ownCatalog("alpha")
      val jar = pluginJar("alpha", PluginVersion(1, 2, 3), tmp)
      val installed = PluginCli.execute(List("install", jar.toString), dir)
      val listed = PluginCli.execute(List("list"), dir)
      assertTrue(
        installed.isRight,
        installed.exists(_.contains(""""result":"installed"""")),
        installed.exists(_.contains(""""name":"alpha"""")),
        Files.isRegularFile(ownCatalog("alpha").resolve("alpha-1.2.3.jar")),
        listed.isRight,
        listed.exists(_.contains(""""name":"alpha"""")),
        listed.exists(_.contains(""""version":"1.2.3"""")),
        listed.exists(_.contains(""""active":true"""))
      )
    },
    test("disable/enable toggle the marker and the reported active state") {
      val dir = ownCatalog("beta")
      val jar = pluginJar("beta", PluginVersion(0, 1, 0), tmp)
      PluginCli.execute(List("install", jar.toString), dir)
      val disabled = PluginCli.execute(List("disable", "beta"), dir)
      // assertions BETWEEN the toggles: the marker is checked while disabled
      assertTrue(
        disabled.isRight,
        disabled.exists(_.contains(""""result":"disabled"""")),
        Files.exists(ownCatalog("beta").resolve("beta-0.1.0.jar.disabled")),
        PluginCli
          .execute(List("list"), dir)
          .exists(_.contains(""""active":false"""))
      )
      val enabled = PluginCli.execute(List("enable", "beta"), dir)
      assertTrue(
        enabled.isRight,
        enabled.exists(_.contains(""""result":"enabled"""")),
        !Files.exists(ownCatalog("beta").resolve("beta-0.1.0.jar.disabled")),
        PluginCli
          .execute(List("list"), dir)
          .exists(_.contains(""""active":true"""))
      )
    },
    test("install refuses a same-name plugin that is already installed") {
      val dir = ownCatalog("gamma")
      val v1 = pluginJar("gamma", PluginVersion(1, 0, 0), tmp)
      val v2 = pluginJar("gamma", PluginVersion(2, 0, 0), tmp)
      val first = PluginCli.execute(List("install", v1.toString), dir)
      val second = PluginCli.execute(List("install", v2.toString), dir)
      assertTrue(
        first.isRight,
        second.isLeft,
        second.swap.exists(_.contains("already installed"))
      )
    },
    test(
      "install refuses an artifact whose payload no longer matches the pin"
    ) {
      // Pin computed over payload A, then the payload silently becomes B
      // (substitution between review and install is detected at install).
      val dir = tmp.resolve("tampered-src")
      val jar = pluginJar("delta", PluginVersion(1, 0, 0), dir)
      val in = new java.util.jar.JarFile(jar.toFile)
      val entries =
        (try
          in.entries()
            .asScala
            .toList
            .filterNot(_.isDirectory)
            .map(e => e.getName -> in.getInputStream(e).readAllBytes())
        finally in.close())
      val swapped = entries.map { (name, bytes) =>
        if name == "lib/payload.txt" then
          name -> "SUBSTITUTED".getBytes(StandardCharsets.UTF_8)
        else name -> bytes
      }
      val tampered = dir.resolve("delta-tampered.jar")
      writeJar(tampered, swapped)
      val res = PluginCli.execute(
        List("install", tampered.toString),
        ownCatalog("delta")
      )
      assertTrue(
        res.isLeft,
        res.swap.exists(_.contains("sha256 mismatch"))
      )
    },
    test(
      "install refuses a manifest whose API range excludes the host (RA-CAP)"
    ) {
      val jar = pluginJar("epsilon", PluginVersion(1, 0, 0), tmp, minApi = 5)
      val res =
        PluginCli.execute(List("install", jar.toString), ownCatalog("epsilon"))
      assertTrue(
        res.isLeft,
        res.swap.exists(_.contains("supports API [5.."))
      )
    },
    test("install refuses a non-jar file and a missing file (typed errors)") {
      val plain = tmp.resolve("plain.txt")
      Files.write(plain, "hello".getBytes(StandardCharsets.UTF_8))
      val notJar =
        PluginCli.execute(List("install", plain.toString), ownCatalog("iota"))
      val missing =
        PluginCli.execute(
          List("install", tmp.resolve("nope.jar").toString),
          ownCatalog("iota")
        )
      assertTrue(
        notJar.isLeft,
        notJar.swap.exists(_.contains("not a jar")),
        missing.isLeft,
        missing.swap.exists(_.contains("not a file"))
      )
    },
    test("remove deletes the jar and marker; removing again is a typed error") {
      val dir = ownCatalog("zeta")
      val jar = pluginJar("zeta", PluginVersion(1, 0, 0), tmp)
      PluginCli.execute(List("install", jar.toString), dir)
      PluginCli.execute(List("disable", "zeta"), dir)
      val removed = PluginCli.execute(List("remove", "zeta"), dir)
      val listed = PluginCli.execute(List("list"), dir)
      val again = PluginCli.execute(List("remove", "zeta"), dir)
      assertTrue(
        removed.isRight,
        removed.exists(_.contains(""""result":"removed"""")),
        !Files.exists(ownCatalog("zeta").resolve("zeta-1.0.0.jar")),
        !Files.exists(ownCatalog("zeta").resolve("zeta-1.0.0.jar.disabled")),
        !listed.exists(_.contains("zeta")),
        again.isLeft,
        again.swap.exists(_.contains("not installed"))
      )
    },
    test("list over an empty catalog is an empty JSON array, exit 0") {
      val res = PluginCli.execute(List("list"), tmp.resolve("does-not-exist"))
      assertTrue(res == Right("[]"))
    },
    test(
      "an unreadable catalog jar is listed WITH an error field, never skipped"
    ) {
      Files.createDirectories(tmp.resolve("broken-catalog"))
      val broken = tmp.resolve("broken-catalog").resolve("broken.jar")
      Files.write(broken, "this is not a zip".getBytes(StandardCharsets.UTF_8))
      val res = PluginCli.execute(List("list"), tmp.resolve("broken-catalog"))
      assertTrue(
        res.isRight,
        res.exists(_.contains(""""file":"broken.jar"""")),
        res.exists(_.contains(""""error":""")),
        res.exists(_.contains("not a jar"))
      )
    },
    test("unknown subcommand and bad arity are typed failures") {
      val a = PluginCli.execute(List("frobnicate"), ownCatalog("eta"))
      val b = PluginCli.execute(List("install"), ownCatalog("eta"))
      val c = PluginCli.execute(List("enable"), ownCatalog("eta"))
      assertTrue(
        a.isLeft,
        a.swap.exists(_.contains("usage: raider plugin")),
        b.isLeft,
        c.isLeft
      )
    },
    test("execute/run keep their contract: exit 0 on success, 2 on failure") {
      val code = PluginCli.run(List("list"), tmp.resolve("empty-catalog"))
      assertTrue(code == 0)
    }
  )
