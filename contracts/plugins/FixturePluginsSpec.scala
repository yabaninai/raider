package raider.plugins

import raider.core.{
  RaiderError,
  RecoveryClass,
  Tool,
  ToolCapabilities,
  ToolRegistry
}
import raider.core.plugin.{
  ApiVersion,
  CapabilityRef,
  HookOutcome,
  HookRunner,
  PluginDescriptor,
  PluginError,
  PluginManifest,
  PluginVersion,
  Preflight,
  RaiderHook,
  RaiderPlugin
}
import raider.core.plugin.loader.{LoadedPlugin, PluginLoader}

import zio.Scope
import zio.ZIO
import zio.json.{DecoderOps, EncoderOps}
import zio.test._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.ServiceLoader
import java.util.jar.{JarEntry, JarFile, JarOutputStream}

import scala.jdk.CollectionConverters.*

// ---------------------------------------------------------------------------
// Test-scope fixture classes — the executable twins of examples/plugins/
// (hello-tool, scoped-search, audit-hook, RAI-096). They live on the TEST
// classpath; fixture jars embed their .class files, so the loader's
// parent-delegate classloader resolves them from the parent (same identity,
// child-first off — the same proof pattern as modules/core PluginLoaderSpec).
// ---------------------------------------------------------------------------

class HelloGreetTool extends Tool:
  def name: String = "hello.greet"
  def version: Int = 1
  def description: String = "stateless greeting (hello-tool fixture)"
  def recovery: RecoveryClass = RecoveryClass.ReadOnly
  def timeoutMs: Long = 1000L
  def capabilities: ToolCapabilities = ToolCapabilities(concurrentSafe = true)

  def invoke(argumentsJson: String): ZIO[Scope, RaiderError, String] =
    argumentsJson.fromJson[Map[String, String]] match
      case Left(err) =>
        ZIO.fail(
          RaiderError.InputValidation(s"hello.greet args: ${err.take(120)}")
        )
      case Right(args) =>
        val who = args.getOrElse("name", "").trim
        val message = if who.isEmpty then "hello" else s"hello, $who"
        ZIO.succeed(Map("message" -> message).toJson)

class HelloToolPlugin extends RaiderPlugin:

  def descriptor: PluginDescriptor = PluginDescriptor(
    name = "hello-tool",
    version = PluginVersion(1, 0, 0),
    minApiVersion = 1,
    maxApiVersion = 1,
    capabilities = List(CapabilityRef("tool", "hello.greet"))
  )

final case class ScopedSearchOut(
    root: String,
    query: String,
    matches: List[String],
    truncated: Boolean
)

object ScopedSearchOut:

  given zio.json.JsonEncoder[ScopedSearchOut] =
    zio.json.DeriveJsonEncoder.gen[ScopedSearchOut]

class ScopedSearchTool extends Tool:
  val MaxEntries: Int = 500
  def name: String = "scoped.search"
  def version: Int = 1

  def description: String =
    "bounded literal file-name search inside one given directory (scoped-search fixture)"

  def recovery: RecoveryClass = RecoveryClass.ReadOnly
  def timeoutMs: Long = 5000L
  def capabilities: ToolCapabilities = ToolCapabilities(concurrentSafe = true)

  def invoke(argumentsJson: String): ZIO[Scope, RaiderError, String] =
    argumentsJson.fromJson[Map[String, String]] match
      case Left(err) =>
        ZIO.fail(
          RaiderError.InputValidation(s"scoped.search args: ${err.take(120)}")
        )
      case Right(args) =>
        val root = args.getOrElse("root", "").trim
        val query = args.getOrElse("query", "")
        if root.isEmpty then
          ZIO.fail(
            RaiderError.InputValidation("scoped.search: 'root' is required")
          )
        else if query.isEmpty then
          ZIO.fail(
            RaiderError.InputValidation("scoped.search: 'query' is required")
          )
        else
          val dir = java.nio.file.Paths.get(root)
          if !Files.isDirectory(dir) then
            ZIO.fail(
              RaiderError.InputValidation(
                s"scoped.search: not a directory: $root"
              )
            )
          else ZIO.attempt(search(dir, query)).orDie

  private def search(dir: java.nio.file.Path, query: String): String =
    val raw =
      val stream = Files.walk(dir)
      try
        stream.limit(MaxEntries.toLong + 1).iterator().asScala.drop(1).toList
      finally stream.close()
    val truncated = raw.size > MaxEntries
    val scanned = if truncated then raw.dropRight(1) else raw
    val matches = scanned
      .filter(p => Files.isRegularFile(p))
      .map(p => p.getFileName.toString)
      .filter(_.contains(query))
      .sorted
    ScopedSearchOut(dir.toString, query, matches, truncated).toJson

class ScopedSearchPlugin extends RaiderPlugin:

  def descriptor: PluginDescriptor = PluginDescriptor(
    name = "scoped-search",
    version = PluginVersion(1, 0, 0),
    minApiVersion = 1,
    maxApiVersion = 1,
    capabilities = List(CapabilityRef("tool", "scoped.search"))
  )

class AuditRunStartHook extends RaiderHook:
  def name: String = "audit.runStart"

  def apply(inputJson: String): String =
    "{\"hook\":\"audit.runStart\",\"event\":\"runStart\",\"input\":" +
      inputJson + "}"

class AuditHookPlugin extends RaiderPlugin:

  def descriptor: PluginDescriptor = PluginDescriptor(
    name = "audit-hook",
    version = PluginVersion(1, 0, 0),
    minApiVersion = 1,
    maxApiVersion = 1,
    capabilities = List(CapabilityRef("hook", "audit.runStart"))
  )

/** Broken variant: a SECOND plugin claiming the same TOOL name — must fail
  * typed (SDK-02), both at co-install preflight and at loader level vs a base
  * registry that already carries hello.greet.
  */
class DupGreetTool extends Tool:
  def name: String = "hello.greet"
  def version: Int = 1
  def description: String = "duplicate hello.greet claim (broken fixture)"
  def recovery: RecoveryClass = RecoveryClass.ReadOnly
  def timeoutMs: Long = 1000L
  def capabilities: ToolCapabilities = ToolCapabilities(concurrentSafe = true)

  def invoke(argumentsJson: String): ZIO[Scope, RaiderError, String] =
    ZIO.succeed(argumentsJson)

class DupToolPlugin extends RaiderPlugin:

  def descriptor: PluginDescriptor = PluginDescriptor(
    name = "hello-tool-dup",
    version = PluginVersion(1, 0, 0),
    minApiVersion = 1,
    maxApiVersion = 1,
    capabilities = List(CapabilityRef("tool", "hello.greet"))
  )

/** RAI-096 contract spec (SDK-01..03): the three fixture plugins are REAL jars
  * assembled at test time (class entries + META-INF/services +
  * META-INF/raider-plugin.json pinning the canonical payload sha256 — the same
  * two-pass pattern as modules/core FixtureJars) and exercised through the
  * PRODUCTION loader/CLI-side functions only.
  *
  * SDK-01: all three load via PluginLoader.load against the empty registry;
  * tools land in a NEW immutable overlay registry and hello.greet actually
  * answers; the audit-hook jar loads with zero tools (v1 loader validates hook
  * capabilities, instantiation is host-side) and its RaiderHook provider is
  * discoverable + dispatches under its declared name via HookRunner.
  *
  * SDK-02: broken variants are typed — duplicate tool name vs a base registry
  * and across a co-install set (PluginError.DuplicateName, RA-INP), and a
  * payload tampered after packaging (PluginError.Malformed, sha256 mismatch).
  *
  * SDK-03: the fixture build is deterministic and network-free — two
  * independent builds of the same fixture classes produce the SAME canonical
  * payload digest, and each manifest pin equals a fresh recomputation over the
  * exact jar bytes.
  */
object FixturePluginsSpec extends ZIOSpecDefault:

  private val tmp: Path =
    val dir = Files.createTempDirectory("raider-fixture-plugins-spec")
    Runtime.getRuntime.addShutdownHook(
      new Thread(() =>
        Files
          .walk(dir)
          .sorted(java.util.Comparator.reverseOrder[Path]())
          .forEach(p => Files.deleteIfExists(p))
      )
    )
    dir

  // ---------------------------------------------------------------------------
  // jar assembly (mirrors modules/core FixtureJars; self-contained on purpose)
  // ---------------------------------------------------------------------------

  // Service-file names MUST be the JVM BINARY names: Tool is nested in
  // `object tool`, so its name is "raider.core.tool$Tool" (with a dollar) —
  // spelled via classOf[...].getName, never by hand.
  private val PluginService: String =
    "META-INF/services/" + classOf[RaiderPlugin].getName

  private val ToolService: String =
    "META-INF/services/" + classOf[Tool].getName

  private val HookService: String =
    "META-INF/services/" + classOf[RaiderHook].getName

  private def classEntry(cl: Class[?]): (String, Array[Byte]) =
    val entryName = cl.getName.replace('.', '/') + ".class"
    val location = cl.getProtectionDomain.getCodeSource.getLocation
    val bytes =
      if location.getFile.endsWith(".jar") then
        val jar = new JarFile(location.getFile)
        try
          Option(jar.getJarEntry(entryName)) match
            case Some(entry) => jar.getInputStream(entry).readAllBytes()
            case None =>
              throw new IllegalStateException(s"no $entryName in $location")
        finally jar.close()
      else
        Files.readAllBytes(
          java.nio.file.Paths.get(location.toURI).resolve(entryName)
        )
    entryName -> bytes

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

  /** Two-pass build (D2 sha256 semantics): pass 1 writes the payload-only jar,
    * the canonical payload digest is computed with the production
    * PluginLoader.payloadSha256, pass 2 rewrites the final jar with
    * META-INF/raider-plugin.json pinning that digest. Pure local bytes — no
    * network anywhere (SDK-03).
    */
  private def pluginJar(
      baseName: String,
      classEntries: List[(String, Array[Byte])],
      pluginProviders: List[String],
      toolProviders: List[String],
      hookProviders: List[String],
      manifest: PluginManifest
  ): Path =
    val serviceLines: List[String] => Array[Byte] = providers =>
      providers.mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8)
    val payload: List[(String, Array[Byte])] =
      classEntries ++
        (if pluginProviders.isEmpty then Nil
         else List(PluginService -> serviceLines(pluginProviders))) ++
        (if toolProviders.isEmpty then Nil
         else List(ToolService -> serviceLines(toolProviders))) ++
        (if hookProviders.isEmpty then Nil
         else List(HookService -> serviceLines(hookProviders)))
    Files.createDirectories(tmp)
    val partial = tmp.resolve(s"$baseName.partial.jar")
    writeJar(partial, payload)
    val pinned = PluginLoader.payloadSha256(partial) match
      case Right(sha)  => manifest.copy(sha256 = sha)
      case Left(error) => throw new IllegalStateException(error.detail)
    println(
      s"[fixture] $baseName payload sha256=${pinned.sha256}"
    )
    Files.deleteIfExists(partial)
    val finalJar = tmp.resolve(s"$baseName.jar")
    writeJar(
      finalJar,
      payload :+ (PluginLoader.ManifestEntry -> pinned.toJson.getBytes(
        StandardCharsets.UTF_8
      ))
    )
    finalJar

  private def manifestOf(
      name: String,
      capabilities: List[CapabilityRef]
  ): PluginManifest =
    PluginManifest(
      schemaVersion = 1,
      name = name,
      version = PluginVersion(1, 0, 0),
      artifact = s"$name-1.0.0.jar",
      sha256 = "0" * 64, // replaced by the two-pass builder with the real pin
      minApiVersion = 1,
      maxApiVersion = 1,
      capabilities = capabilities
    )

  private val helloClasses = List(
    classEntry(classOf[HelloToolPlugin]),
    classEntry(classOf[HelloGreetTool])
  )

  private val scopedClasses = List(
    classEntry(classOf[ScopedSearchPlugin]),
    classEntry(classOf[ScopedSearchTool])
  )

  private val auditClasses = List(
    classEntry(classOf[AuditHookPlugin]),
    classEntry(classOf[AuditRunStartHook])
  )

  private val dupClasses = List(
    classEntry(classOf[DupToolPlugin]),
    classEntry(classOf[DupGreetTool])
  )

  private val helloJar = pluginJar(
    "hello-tool-1.0.0",
    helloClasses,
    pluginProviders = List(classOf[HelloToolPlugin].getName),
    toolProviders = List(classOf[HelloGreetTool].getName),
    hookProviders = Nil,
    manifest = manifestOf(
      "hello-tool",
      List(CapabilityRef("tool", "hello.greet"))
    )
  )

  private val scopedJar = pluginJar(
    "scoped-search-1.0.0",
    scopedClasses,
    pluginProviders = List(classOf[ScopedSearchPlugin].getName),
    toolProviders = List(classOf[ScopedSearchTool].getName),
    hookProviders = Nil,
    manifest = manifestOf(
      "scoped-search",
      List(CapabilityRef("tool", "scoped.search"))
    )
  )

  private val auditJar = pluginJar(
    "audit-hook-1.0.0",
    auditClasses,
    pluginProviders = List(classOf[AuditHookPlugin].getName),
    toolProviders = Nil,
    hookProviders = List(classOf[AuditRunStartHook].getName),
    manifest = manifestOf(
      "audit-hook",
      List(CapabilityRef("hook", "audit.runStart"))
    )
  )

  private val dupJar = pluginJar(
    "hello-tool-dup-1.0.0",
    dupClasses,
    pluginProviders = List(classOf[DupToolPlugin].getName),
    toolProviders = List(classOf[DupGreetTool].getName),
    hookProviders = Nil,
    manifest = manifestOf(
      "hello-tool-dup",
      List(CapabilityRef("tool", "hello.greet"))
    )
  )

  /** SDK-02: a payload tampered AFTER packaging no longer matches the pin. */
  private val tamperedHelloJar: Path =
    val jarFile = new JarFile(helloJar.toFile)
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
      val target = tmp.resolve("hello-tool-tampered.jar")
      writeJar(target, mutated)
      target
    finally jarFile.close()

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  /** The loader result in the ZIO error channel (typed PluginError). */
  private def loadM(
      jar: Path,
      base: ToolRegistry
  ): ZIO[Any, PluginError, LoadedPlugin] =
    ZIO.absolve(
      ZIO.succeed(PluginLoader.load(jar, base, ApiVersion.current))
    )

  /** The overlay: a NEW immutable registry built over the base plus the loaded
    * tools — the existing registry never mutates (ADR-025 §4).
    */
  private def overlayOf(
      base: ToolRegistry,
      loaded: List[LoadedPlugin]
  ): Either[RaiderError, ToolRegistry] =
    ToolRegistry.build(
      base.names.toList.flatMap(base.lookup) ++ loaded.flatMap(_.tools)
    )

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
        def invoke(argumentsJson: String): ZIO[Scope, RaiderError, String] =
          ZIO.succeed(argumentsJson)
    )
    ToolRegistry
      .build(tools)
      .fold(e => throw new IllegalStateException(e.detail), identity)

  private def searchDir(fixture: String): Path =
    val dir = tmp.resolve(s"tree-$fixture")
    Files.createDirectories(dir)
    Files.write(dir.resolve("alpha.txt"), "a".getBytes)
    Files.write(dir.resolve("beta.md"), "b".getBytes)
    Files.createDirectories(dir.resolve("sub"))
    Files.write(dir.resolve("sub").resolve("gamma.txt"), "g".getBytes)
    dir

  def spec = suite("RAI-096 plugin SDK fixtures")(
    suite("SDK-01 fixture plugins load")(
      test(
        "hello-tool: jar loads, tool lands in the overlay and answers; parent-delegate identity"
      ) {
        for
          loaded <- loadM(helloJar, ToolRegistry.empty)
          overlay = overlayOf(ToolRegistry.empty, List(loaded)).fold(
            e => throw new IllegalStateException(e.detail),
            identity
          )
          tool = overlay
            .lookup("hello.greet")
            .getOrElse(
              throw new IllegalStateException(
                "hello.greet missing from overlay"
              )
            )
          out <- ZIO
            .scoped(tool.invoke("{}"))
            .ensuring(ZIO.succeed(loaded.close()))
        yield assertTrue(
          loaded.manifest.name == "hello-tool",
          loaded.plugin.descriptor == PluginDescriptor(
            "hello-tool",
            PluginVersion(1, 0, 0),
            1,
            1,
            List(CapabilityRef("tool", "hello.greet"))
          ),
          loaded.tools.map(_.name) == List("hello.greet"),
          (loaded.plugin.getClass eq classOf[HelloToolPlugin]),
          out == "{\"message\":\"hello\"}",
          helloJar.getFileName.toString == "hello-tool-1.0.0.jar"
        )
      },
      test(
        "hello-tool: manifest JSON wire shape (schemaVersion/version/kind)"
      ) {
        val res = PluginLoader.readManifest(helloJar)
        val json = res.map(_.toJson).getOrElse("")
        assertTrue(
          res.isRight,
          json.contains("\"schemaVersion\":1"),
          json.contains("\"version\":\"1.0.0\""),
          json.contains("\"kind\":\"tool\""),
          json.contains("\"name\":\"hello.greet\""),
          res.exists(m => m.sha256.matches("[0-9a-f]{64}"))
        )
      },
      test(
        "scoped-search: jar loads; bounded name search inside the given subdir; typed input failures"
      ) {
        val dir = searchDir("scoped")
        for
          loaded <- loadM(scopedJar, ToolRegistry.empty)
          overlay = overlayOf(ToolRegistry.empty, List(loaded)).fold(
            e => throw new IllegalStateException(e.detail),
            identity
          )
          tool = overlay
            .lookup("scoped.search")
            .getOrElse(
              throw new IllegalStateException(
                "scoped.search missing from overlay"
              )
            )
          hit <- ZIO.scoped(
            tool.invoke(
              s"""{"root":"$dir","query":"txt"}"""
            )
          )
          miss <- ZIO.scoped(
            tool.invoke(
              s"""{"root":"$dir","query":"absent"}"""
            )
          )
          _ <- ZIO.succeed(loaded.close())
          typed <- ZIO
            .scoped(
              tool.invoke(s"""{"root":"$dir/nowhere","query":"x"}""")
            )
            .either
        yield assertTrue(
          loaded.tools.map(_.name) == List("scoped.search"),
          hit.contains("\"matches\":[\"alpha.txt\",\"gamma.txt\"]"),
          hit.contains("\"truncated\":false"),
          miss.contains("\"matches\":[]"),
          typed.swap.exists {
            case r: RaiderError.InputValidation =>
              r.detail.contains("not a directory")
            case _ => false
          }
        )
      },
      test(
        "audit-hook: jar loads with hook capability (zero tools instantiated); RaiderHook provider dispatches runStart via HookRunner"
      ) {
        val input = "{\"runId\":\"r-1\"}"
        val expected =
          "{\"hook\":\"audit.runStart\",\"event\":\"runStart\",\"input\":{\"runId\":\"r-1\"}}"
        for
          loaded <- loadM(auditJar, ToolRegistry.empty)
          pair = {
            val hooks = ServiceLoader
              .load(classOf[RaiderHook], loaded.classLoader)
              .asScala
              .toList
            val outcome =
              new HookRunner().runHook(hooks.head, "audit.runStart", input)
            (hooks, outcome)
          }
          _ <- ZIO.succeed(loaded.close())
        yield assertTrue(
          loaded.manifest.name == "audit-hook",
          loaded.tools.isEmpty, // v1 loader: hook capabilities are validated, not instantiated
          pair._1.map(_.name) == List("audit.runStart"),
          pair._2 == HookOutcome.Completed("audit.runStart", expected)
        )
      },
      test("co-install: both tool plugins share one overlay registry") {
        for
          hello <- loadM(helloJar, ToolRegistry.empty)
          scoped <- loadM(scopedJar, ToolRegistry.empty)
          overlay = overlayOf(ToolRegistry.empty, List(hello, scoped)).fold(
            e => throw new IllegalStateException(e.detail),
            identity
          )
          out <- ZIO
            .scoped(
              overlay.lookup("hello.greet").get.invoke("{\"name\":\"Ada\"}")
            )
            .ensuring(ZIO.succeed(hello.close()) *> ZIO.succeed(scoped.close()))
        yield assertTrue(
          overlay.names.sorted == List("hello.greet", "scoped.search"),
          out == "{\"message\":\"hello, Ada\"}"
        )
      }
    ),
    suite("SDK-02 broken fixtures are typed")(
      test(
        "duplicate tool name vs the base registry: typed DuplicateName (RA-INP), no classloader opened"
      ) {
        val res = PluginLoader.load(helloJar, baseWith("hello.greet"), 1)
        assertTrue(
          res.swap.exists(
            _ == PluginError.DuplicateName("tool", "hello.greet")
          ),
          res.swap.exists(_.toRaiderError.code == "RA-INP")
        )
      },
      test(
        "duplicate tool name across a co-install set: typed DuplicateName at preflight"
      ) {
        val helloDescriptor = PluginLoader.descriptorOf(
          PluginLoader
            .readManifest(helloJar)
            .fold(e => throw new IllegalStateException(e.detail), identity)
        )
        val dupDescriptor = PluginLoader.descriptorOf(
          PluginLoader
            .readManifest(dupJar)
            .fold(e => throw new IllegalStateException(e.detail), identity)
        )
        assertTrue(
          Preflight
            .coinstall(List(helloDescriptor, dupDescriptor))
            .swap
            .exists(_ == PluginError.DuplicateName("tool", "hello.greet")),
          // and at loader level: the dup jar vs an overlay that already has hello.greet
          PluginLoader
            .load(
              dupJar,
              baseWith("hello.greet"),
              ApiVersion.current
            )
            .swap
            .exists(_ == PluginError.DuplicateName("tool", "hello.greet"))
        )
      },
      test("payload tampered after packaging: typed sha256 mismatch") {
        val res = PluginLoader.load(tamperedHelloJar, ToolRegistry.empty, 1)
        assertTrue(
          res.swap.exists {
            case m: PluginError.Malformed => m.what.contains("sha256 mismatch")
            case _                        => false
          },
          res.swap.exists(_.toRaiderError.code == "RA-INP")
        )
      }
    ),
    suite("SDK-03 deterministic, network-free build")(
      test(
        "two independent builds of the same fixture produce the same payload pin; the pin covers exactly these bytes"
      ) {
        val again = pluginJar(
          "hello-tool-again-1.0.0",
          helloClasses,
          pluginProviders = List(classOf[HelloToolPlugin].getName),
          toolProviders = List(classOf[HelloGreetTool].getName),
          hookProviders = Nil,
          manifest = manifestOf(
            "hello-tool",
            List(CapabilityRef("tool", "hello.greet"))
          )
        )
        val first = PluginLoader.payloadSha256(helloJar)
        val second = PluginLoader.payloadSha256(again)
        val pinned = PluginLoader.readManifest(helloJar).map(_.sha256)
        assertTrue(
          first.isRight,
          first == second,
          pinned == first,
          again.getFileName.toString != helloJar.getFileName.toString
        )
      }
    )
  )

end FixturePluginsSpec
