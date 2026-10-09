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

  // RAI-101 helpers: byte-level identity checks for the update/rollback
  // acceptance (the prev sidecar must preserve the displaced jar EXACTLY).
  private def sha256Of(bytes: Array[Byte]): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map("%02x".format(_))
      .mkString

  private def sha256(path: Path): String = sha256Of(Files.readAllBytes(path))

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
    },
    // -------------------------------------------------------------------------
    // RAI-101: plugin update/rollback lifecycle (UPL-01..03)
    // -------------------------------------------------------------------------
    test(
      "UPL-01: update swaps in the new version, list shows it, prev is preserved"
    ) {
      val dir = ownCatalog("kappa")
      val v1 = pluginJar(
        "kappa",
        PluginVersion(1, 0, 0),
        tmp.resolve("kappa-v1"),
        payloadBody = "kap-v1"
      )
      val v2 = pluginJar(
        "kappa",
        PluginVersion(2, 0, 0),
        tmp.resolve("kappa-v2"),
        payloadBody = "kap-v2"
      )
      PluginCli.execute(List("install", v1.toString), dir)
      val live = dir.resolve("kappa-1.0.0.jar")
      val v1Sha = sha256(live)
      val updated = PluginCli.execute(List("update", "kappa", v2.toString), dir)
      val listed = PluginCli.execute(List("list"), dir)
      // a DIFFERENT plugin must still co-install: the .prev sidecar is
      // invisible to list and never enters co-install preflight
      val other = pluginJar("kappa2", PluginVersion(1, 0, 0), tmp)
      val second = PluginCli.execute(List("install", other.toString), dir)
      assertTrue(
        updated.isRight,
        updated.exists(_.contains(""""result":"updated"""")),
        updated.exists(_.contains(""""version":"2.0.0"""")),
        Files.isRegularFile(dir.resolve("kappa-2.0.0.jar")),
        !Files.exists(live),
        Files.isRegularFile(dir.resolve("kappa.prev.jar")),
        sha256(dir.resolve("kappa.prev.jar")) == v1Sha,
        listed.isRight,
        listed.exists(_.contains(""""version":"2.0.0"""")),
        !listed.exists(_.contains("1.0.0")),
        !listed.exists(_.contains("prev")),
        second.isRight
      )
    },
    test(
      "UPL-02: rollback restores the previous jar; a second rollback swaps back"
    ) {
      val dir = ownCatalog("lambda")
      val v1 = pluginJar(
        "lambda",
        PluginVersion(1, 0, 0),
        tmp.resolve("lambda-v1"),
        payloadBody = "lam-v1"
      )
      val v2 = pluginJar(
        "lambda",
        PluginVersion(2, 0, 0),
        tmp.resolve("lambda-v2"),
        payloadBody = "lam-v2"
      )
      PluginCli.execute(List("install", v1.toString), dir)
      val v1Sha = sha256(dir.resolve("lambda-1.0.0.jar"))
      PluginCli.execute(List("update", "lambda", v2.toString), dir)
      val rolled = PluginCli.execute(List("rollback", "lambda"), dir)
      val listed = PluginCli.execute(List("list"), dir)
      assertTrue(
        rolled.isRight,
        rolled.exists(_.contains(""""result":"rolled_back"""")),
        rolled.exists(_.contains(""""version":"1.0.0"""")),
        Files.isRegularFile(dir.resolve("lambda-1.0.0.jar")),
        sha256(dir.resolve("lambda-1.0.0.jar")) == v1Sha,
        Files.isRegularFile(dir.resolve("lambda.prev.jar")),
        listed.isRight,
        listed.exists(_.contains(""""version":"1.0.0""""))
      )
      // swap semantics: the displaced current becomes the new prev, so
      // rolling back again returns to the updated version (and the ORIGINAL
      // v1 bytes keep circulating unchanged)
      val rolledAgain = PluginCli.execute(List("rollback", "lambda"), dir)
      assertTrue(
        rolledAgain.isRight,
        rolledAgain.exists(_.contains(""""version":"2.0.0"""")),
        Files.isRegularFile(dir.resolve("lambda-2.0.0.jar")),
        sha256(dir.resolve("lambda.prev.jar")) == v1Sha
      )
    },
    test(
      "UPL-02: a corrupt update is typed and the installed plugin stays intact"
    ) {
      val dir = ownCatalog("mu")
      val src = tmp.resolve("mu-src")
      val v1 =
        pluginJar("mu", PluginVersion(1, 0, 0), tmp, payloadBody = "mu-v1")
      val v2 =
        pluginJar("mu", PluginVersion(2, 0, 0), src, payloadBody = "mu-v2")
      PluginCli.execute(List("install", v1.toString), dir)
      val live = dir.resolve("mu-1.0.0.jar")
      val v1Sha = sha256(live)
      // Rewrite the v2 payload WITHOUT recomputing the pin: the jar's own
      // payload no longer matches its manifest sha256 — the exact
      // corrupt-update shape. Preflight must refuse it BEFORE the catalog is
      // touched: no prev created, no file replaced, no temp litter left.
      val in = new java.util.jar.JarFile(v2.toFile)
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
          name -> "TAMPERED".getBytes(StandardCharsets.UTF_8)
        else name -> bytes
      }
      val tampered = src.resolve("mu-tampered.jar")
      writeJar(tampered, swapped)
      val res = PluginCli.execute(List("update", "mu", tampered.toString), dir)
      val plain = tmp.resolve("mu-plain.txt")
      Files.write(plain, "not a jar".getBytes(StandardCharsets.UTF_8))
      val notJar = PluginCli.execute(List("update", "mu", plain.toString), dir)
      val listed = PluginCli.execute(List("list"), dir)
      assertTrue(
        res.isLeft,
        res.swap.exists(_.contains("sha256 mismatch")),
        notJar.isLeft,
        notJar.swap.exists(_.contains("not a jar")),
        Files.isRegularFile(live),
        sha256(live) == v1Sha,
        !Files.exists(dir.resolve("mu.prev.jar")),
        listed.exists(_.contains(""""version":"1.0.0""""))
      )
    },
    test(
      "UPL-03: update touches catalog files only — an open artifact keeps its bytes"
    ) {
      val dir = ownCatalog("nu")
      val v1 = pluginJar(
        "nu",
        PluginVersion(1, 0, 0),
        tmp.resolve("nu-v1"),
        payloadBody = "nu-v1"
      )
      val v2 = pluginJar(
        "nu",
        PluginVersion(2, 0, 0),
        tmp.resolve("nu-v2"),
        payloadBody = "nu-v2"
      )
      PluginCli.execute(List("install", v1.toString), dir)
      val live = dir.resolve("nu-1.0.0.jar")
      val before = Files.readAllBytes(live)
      // A running session holds the OLD artifact open (the jar its
      // classloader was built from). Update is a rename-based swap: the open
      // handle keeps reading exactly the old bytes — no truncation, no
      // rewrite, NO hot-unload. The new version applies to NEW sessions only
      // (ADR-025 §2.5 drain/restart semantics; documented in PluginCli).
      val session = Files.newInputStream(live)
      val updated = PluginCli.execute(List("update", "nu", v2.toString), dir)
      val readBack = session.readAllBytes()
      session.close()
      assertTrue(
        updated.isRight,
        updated.exists(_.contains(""""result":"updated"""")),
        readBack.toSeq == before.toSeq,
        Files.isRegularFile(dir.resolve("nu-2.0.0.jar")),
        sha256(dir.resolve("nu.prev.jar")) == sha256Of(before)
      )
    },
    test(
      "UPL-03: the disabled state follows the jar across update and rollback"
    ) {
      val dir = ownCatalog("xi")
      val v1 = pluginJar(
        "xi",
        PluginVersion(1, 0, 0),
        tmp.resolve("xi-v1"),
        payloadBody = "xi-v1"
      )
      val v2 = pluginJar(
        "xi",
        PluginVersion(2, 0, 0),
        tmp.resolve("xi-v2"),
        payloadBody = "xi-v2"
      )
      PluginCli.execute(List("install", v1.toString), dir)
      PluginCli.execute(List("disable", "xi"), dir)
      // update carries the plugin's active/disabled state over to the new
      // version (activation is owner-explicit — never flipped by file surgery)
      val updated = PluginCli.execute(List("update", "xi", v2.toString), dir)
      assertTrue(
        updated.isRight,
        updated.exists(_.contains(""""active":false""")),
        Files.exists(dir.resolve("xi-2.0.0.jar.disabled"))
      )
      // rollback restores each version with its OWN sidecar state
      val rolled = PluginCli.execute(List("rollback", "xi"), dir)
      assertTrue(
        rolled.isRight,
        rolled.exists(_.contains(""""active":false""")),
        Files.exists(dir.resolve("xi-1.0.0.jar.disabled"))
      )
    },
    test("rollback without a saved prev is typed; the plugin stays installed") {
      val dir = ownCatalog("omicron")
      val jar = pluginJar("omicron", PluginVersion(1, 0, 0), tmp)
      PluginCli.execute(List("install", jar.toString), dir)
      val res = PluginCli.execute(List("rollback", "omicron"), dir)
      val listed = PluginCli.execute(List("list"), dir)
      assertTrue(
        res.isLeft,
        res.swap.exists(_.contains("no previous version")),
        listed.exists(_.contains("omicron"))
      )
    },
    test("update refuses a name mismatch and an uninstalled target (typed)") {
      val dir = ownCatalog("pi")
      val rho = pluginJar("rho", PluginVersion(1, 0, 0), tmp)
      val sigma = pluginJar("sigma", PluginVersion(1, 0, 0), tmp)
      PluginCli.execute(List("install", rho.toString), dir)
      val mismatch =
        PluginCli.execute(List("update", "rho", sigma.toString), dir)
      val missing =
        PluginCli.execute(List("update", "tau", rho.toString), dir)
      assertTrue(
        mismatch.isLeft,
        mismatch.swap.exists(_.contains("manifest declares 'sigma'")),
        missing.isLeft,
        missing.swap.exists(_.contains("not installed"))
      )
    },
    test("remove drops the prev sidecar; a fresh install cannot roll back") {
      val dir = ownCatalog("tau")
      val v1 = pluginJar(
        "tau",
        PluginVersion(1, 0, 0),
        tmp.resolve("tau-v1"),
        payloadBody = "tau-v1"
      )
      val v2 = pluginJar(
        "tau",
        PluginVersion(2, 0, 0),
        tmp.resolve("tau-v2"),
        payloadBody = "tau-v2"
      )
      PluginCli.execute(List("install", v1.toString), dir)
      PluginCli.execute(List("update", "tau", v2.toString), dir)
      val removed = PluginCli.execute(List("remove", "tau"), dir)
      assertTrue(
        removed.isRight,
        !Files.exists(dir.resolve("tau.prev.jar")),
        !Files.exists(dir.resolve("tau-2.0.0.jar"))
      )
      val fresh = PluginCli.execute(List("install", v1.toString), dir)
      val rolled = PluginCli.execute(List("rollback", "tau"), dir)
      assertTrue(
        fresh.isRight,
        rolled.isLeft,
        rolled.swap.exists(_.contains("no previous version"))
      )
    }
  )
