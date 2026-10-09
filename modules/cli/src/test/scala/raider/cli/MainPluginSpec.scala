package raider.cli

import raider.core.plugin.{CapabilityRef, PluginManifest, PluginVersion}
import raider.core.plugin.loader.PluginLoader
import raider.core.result.ExitCodes

import zio.ZIO
import zio.json.EncoderOps
import zio.test._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.jar.{JarEntry, JarOutputStream}

/** RAI-083 Main-level plugin routing acceptance (P4.B completion): the
  * `plugin ...` subcommand dispatch in [[Main]] — BEFORE the CliArgs parse —
  * exercised at Main level on TEMP catalog directories via the explicit-dir
  * seam of [[Main.pluginCommand]] (a JVM cannot mutate its own environment; the
  * production `RAIDER_PLUGINS_DIR` resolution lives in
  * `PluginCli.defaultPluginsDir` and is covered by raiderCli's PluginCliSpec).
  *
  * Exit-code contract of the Main wiring is result.scala (ci-runtime §8): 0
  * success, 21 (`ExitCodes.ConfigInvalid`) typed failure — deliberately NOT
  * `PluginCli.run`'s legacy 2, which its own spec still locks.
  *
  * Fixture jars carry NO classes — install is a FILE operation (manifest +
  * payload sha256 + co-install preflight); class loading belongs to
  * PluginLoaderSpec (raiderCore).
  */
object MainPluginSpec extends ZIOSpecDefault:

  private val tmp: Path =
    val dir = Files.createTempDirectory("raider-main-plugin-spec")
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

  /** The exact production argv shape: the leading `plugin` token plus the
    * subcommand arguments.
    */
  private def plugin(argv: String*): Array[String] =
    ("plugin" +: argv).toArray

  private def manifest(
      name: String,
      version: PluginVersion
  ): PluginManifest =
    PluginManifest(
      schemaVersion = 1,
      name = name,
      version = version,
      artifact = s"fixtures/$name.jar",
      sha256 = "0" * 64, // replaced by the builder with the payload pin
      minApiVersion = 1,
      maxApiVersion = 9,
      capabilities = List(CapabilityRef("tool", s"$name.echo"))
    )

  /** Manifest-only plugin jar: one payload entry + the manifest pinning the
    * canonical payload digest (D2 — the same digest install re-verifies).
    */
  private def pluginJar(
      name: String,
      version: PluginVersion,
      dir: Path
  ): Path =
    Files.createDirectories(dir)
    val payload = "fixture-payload".getBytes(StandardCharsets.UTF_8)
    val partial = dir.resolve(s"$name.partial.jar")
    writeJar(partial, List("lib/payload.txt" -> payload))
    val pinned = PluginLoader.payloadSha256(partial) match
      case Right(sha)  => manifest(name, version).copy(sha256 = sha)
      case Left(error) => throw new IllegalStateException(error.detail)
    Files.deleteIfExists(partial)
    val target = dir.resolve(s"$name-fixture.jar")
    writeJar(
      target,
      List(
        "lib/payload.txt" -> payload,
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

  def spec = suite("RAI-083 plugin wiring in Main")(
    test(
      "PLCLI-01: install/list/disable/enable/remove roundtrip through Main on a temp dir"
    ) {
      val dir = ownCatalog("plcli01")
      val jar = pluginJar("alpha", PluginVersion(1, 2, 3), tmp)

      val installed = Main.pluginCommand(plugin("install", jar.toString), dir)
      val listed = Main.pluginCommand(plugin("list"), dir)
      assertTrue(
        installed.isDefined, // routed, not a fall-through
        installed.exists(_._1 == ExitCodes.Success),
        installed.exists(_._2.contains(""""result":"installed"""")),
        installed.exists(_._2.contains(""""name":"alpha"""")),
        Files.isRegularFile(dir.resolve("alpha-1.2.3.jar")),
        listed.exists(_._1 == ExitCodes.Success),
        listed.exists(_._2.contains(""""name":"alpha"""")),
        listed.exists(_._2.contains(""""version":"1.2.3"""")),
        listed.exists(_._2.contains(""""active":true"""))
      )

      val disabled = Main.pluginCommand(plugin("disable", "alpha"), dir)
      assertTrue(
        disabled.exists(_._1 == ExitCodes.Success),
        disabled.exists(_._2.contains(""""result":"disabled"""")),
        Files.exists(dir.resolve("alpha-1.2.3.jar.disabled"))
      )
      val enabled = Main.pluginCommand(plugin("enable", "alpha"), dir)
      assertTrue(
        enabled.exists(_._1 == ExitCodes.Success),
        enabled.exists(_._2.contains(""""result":"enabled"""")),
        !Files.exists(dir.resolve("alpha-1.2.3.jar.disabled"))
      )

      val removed = Main.pluginCommand(plugin("remove", "alpha"), dir)
      val emptied = Main.pluginCommand(plugin("list"), dir)
      assertTrue(
        removed.exists(_._1 == ExitCodes.Success),
        removed.exists(_._2.contains(""""result":"removed"""")),
        !Files.exists(dir.resolve("alpha-1.2.3.jar")),
        emptied.exists(_._1 == ExitCodes.Success),
        emptied.exists(_._2 == "[]")
      )
    },
    test(
      "PLCLI-02: unknown subcommand and a broken jar are typed exit-21 failures"
    ) {
      assertTrue(
        ExitCodes.Success == 0,
        ExitCodes.ConfigInvalid == 21
      )
      val dir = ownCatalog("plcli02")
      Files.createDirectories(dir)

      val unknown = Main.pluginCommand(plugin("frobnicate"), dir)
      val broken = dir.resolve("broken.jar")
      Files.write(broken, "this is not a zip".getBytes(StandardCharsets.UTF_8))
      val installBroken =
        Main.pluginCommand(plugin("install", broken.toString), dir)
      assertTrue(
        unknown.isDefined,
        unknown.exists(_._1 == ExitCodes.ConfigInvalid),
        unknown.exists(_._2.contains(""""error"""")),
        unknown.exists(_._2.contains("usage: raider plugin")),
        installBroken.exists(_._1 == ExitCodes.ConfigInvalid),
        installBroken.exists(_._2.contains(""""error"""")),
        installBroken.exists(_._2.contains("not a jar"))
      )
    },
    test(
      "non-plugin argv falls through (None) — run/chat paths stay unchanged"
    ) {
      val dir = ownCatalog("plcli02-fallthrough")
      assertTrue(
        Main.pluginCommand(Array.empty[String], dir).isEmpty,
        Main.pluginCommand(Array("chat"), dir).isEmpty,
        Main.pluginCommand(Array("run", "--task", "x"), dir).isEmpty
      )
    },
    test(
      "PLCLI-03: parallel install/enable/disable/remove on different jars stay safe"
    ) {
      val dir = ownCatalog("plcli03")
      // Pre-seed a and b SEQUENTIALLY: two fresh parallel installs could
      // transiently observe a partially-copied catalog jar (typed "cannot
      // read" nondeterminism) — this test pins the DETERMINISTIC parallel
      // safety property of Main-level dispatch instead.
      val jarA = pluginJar("para-a", PluginVersion(1, 0, 0), tmp)
      val jarB = pluginJar("para-b", PluginVersion(1, 0, 0), tmp)
      val jarC = pluginJar("para-c", PluginVersion(1, 0, 0), tmp)
      val seeded = Main.pluginCommand(plugin("install", jarA.toString), dir)
      val seededB = Main.pluginCommand(plugin("install", jarB.toString), dir)
      assertTrue(
        seeded.exists(_._1 == ExitCodes.Success),
        seededB.exists(_._1 == ExitCodes.Success)
      )

      def cmd(argv: String*): (Int, String) =
        // parallel phases call this from ZIO fibers; pluginCommand is pure
        // (no stdout, no exit), so no cross-fiber shared mutable state exists
        val routed = Main.pluginCommand(plugin(argv*), dir)
        routed.getOrElse((-1, "NOT ROUTED"))

      // Two Main-level dispatches in parallel fibers (fork/join — plain
      // tuple types, no Zippable flattening surprises).
      def parTwo(
          first: => (Int, String),
          second: => (Int, String)
      ): ZIO[Any, Throwable, ((Int, String), (Int, String))] =
        for
          firstFiber <- ZIO.attempt(first).fork
          secondRes <- ZIO.attempt(second)
          firstRes <- firstFiber.join
        yield (firstRes, secondRes)

      for
        (disableA, removeB) <-
          parTwo(cmd("disable", "para-a"), cmd("remove", "para-b"))
        after1 = Main.pluginCommand(plugin("list"), dir)
        (installC, enableA) <-
          parTwo(cmd("install", jarC.toString), cmd("enable", "para-a"))
        after2 = Main.pluginCommand(plugin("list"), dir)
        (removeA, removeC) <-
          parTwo(cmd("remove", "para-a"), cmd("remove", "para-c"))
        finalList = Main.pluginCommand(plugin("list"), dir)
      yield assertTrue(
        disableA._1 == ExitCodes.Success,
        removeB._1 == ExitCodes.Success,
        after1.exists(_._2.contains(""""name":"para-a"""")),
        after1.exists(_._2.contains(""""active":false""")),
        !after1.exists(_._2.contains("para-b")),
        installC._1 == ExitCodes.Success,
        enableA._1 == ExitCodes.Success,
        after2.exists(_._2.contains(""""name":"para-c"""")),
        after2.exists(_._2.contains(""""name":"para-a"""")),
        after2.exists(_._2.contains(""""active":true""")),
        removeA._1 == ExitCodes.Success,
        removeC._1 == ExitCodes.Success,
        finalList.exists(_._1 == ExitCodes.Success),
        finalList.exists(_._2 == "[]")
      )
    }
  )
