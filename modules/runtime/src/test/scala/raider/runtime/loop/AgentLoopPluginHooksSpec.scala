package raider.runtime.loop

import raider.core.*
import raider.core.plugin.{
  CapabilityRef,
  HookRunner,
  PluginDescriptor,
  PluginHost,
  PluginManifest,
  PluginVersion,
  RaiderHook,
  RaiderPlugin
}
import raider.core.plugin.loader.{FixtureJars, LoadedPlugin, PluginLoader}
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.events.{EventEnvelope, EventHub, EventPayload}
import raider.testkit.ScriptedSequenceBackend
import zio.json.EncoderOps
import zio.test.*
import zio.{Duration as ZDuration, Ref, ZIO}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** RAI-095 acceptance (PH-01..03, ADR-025 §2.5): AgentLoop lifecycle plugin
  * hooks over REAL plugin jars (built at test time, loaded by the real
  * [[PluginLoader]] — the same fixture discipline as the loader spec).
  *
  * PH-01: declared runStart/runFinish hooks are invoked exactly once per run in
  * program order (start after RunStarted, finish before RunFinished) and each
  * outcome is logged as one bounded Diagnostics line; the run result is
  * unaffected. PH-02: a throwing runStart and a hung runFinish become typed
  * Diagnostics outcomes while the run still SUCCEEDS — hooks never influence
  * the result; an undeclared hook provider is never dispatched. PH-03: with
  * `plugins = None` (the default) the emission sequence is exactly the
  * pre-existing baseline — zero overhead, baseline parity.
  */
object AgentLoopPluginHooksSpec extends ZIOSpecDefault:

  // ---------------------------------------------------------------- fixtures

  /** Cross-classloader recording state: fixture hook classes resolve from the
    * PARENT loader (parent-delegate, proven by the loader spec), so this object
    * is the SAME instance for the host and for the loaded plugin code.
    */
  object PluginHookRecorder:
    private val order = new ConcurrentLinkedQueue[String]()

    def record(entry: String): Unit =
      val _ = order.add(entry)
      ()

    def entries: Vector[String] = order.asScala.toVector

    def clear(): Unit =
      order.clear()
      ()

  /** Plugin SPI entry declaring BOTH lifecycle hooks (descriptor must equal the
    * manifest the loader validates).
    */
  final class FixtureLifecyclePlugin extends RaiderPlugin:

    def descriptor: PluginDescriptor = PluginDescriptor(
      name = "fixture-hooks",
      version = PluginVersion(1, 0, 0),
      minApiVersion = 1,
      maxApiVersion = 1,
      capabilities = List(
        CapabilityRef("hook", PluginHost.StartHook),
        CapabilityRef("hook", PluginHost.FinishHook)
      )
    )

  /** Plugin SPI entry declaring only an `audit` hook: its runStart provider is
    * UNDECLARED and must never dispatch (PH-02 wiring discipline).
    */
  final class FixtureRoguePlugin extends RaiderPlugin:

    def descriptor: PluginDescriptor = PluginDescriptor(
      name = "fixture-rogue",
      version = PluginVersion(1, 0, 0),
      minApiVersion = 1,
      maxApiVersion = 1,
      capabilities = List(CapabilityRef("hook", "audit"))
    )

  final class FixtureStartHook extends RaiderHook:
    def name: String = PluginHost.StartHook

    def apply(inputJson: String): String =
      PluginHookRecorder.record(s"start $inputJson")
      """{"phase":"start"}"""

  final class FixtureFinishHook extends RaiderHook:
    def name: String = PluginHost.FinishHook

    def apply(inputJson: String): String =
      PluginHookRecorder.record(s"finish $inputJson")
      """{"phase":"finish"}"""

  final class FixtureThrowStartHook extends RaiderHook:
    def name: String = PluginHost.StartHook

    def apply(inputJson: String): String =
      throw IllegalStateException("hook kaboom")

  final class FixtureSlowFinishHook extends RaiderHook:
    def name: String = PluginHost.FinishHook

    def apply(inputJson: String): String =
      Thread.sleep(2500L) // far beyond the adversarial bound (80ms)
      inputJson

  final class FixtureRogueStartHook extends RaiderHook:
    def name: String = PluginHost.StartHook // NOT declared by the manifest

    def apply(inputJson: String): String =
      PluginHookRecorder.record("rogue")
      "rogue"

  /** Build a real plugin jar declaring exactly `caps`, providing `spi` and
    * `hooks` (same two-pass sha256 pinning as FixtureJars.pluginJar, plus the
    * RaiderHook service file the lifecycle host discovers).
    */
  private def hookJar(
      dir: Path,
      baseName: String,
      spi: Class[?],
      hooks: List[Class[?]],
      caps: List[CapabilityRef]
  ): Path =
    val lines: List[String] => Array[Byte] = ps =>
      ps.mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8)
    val hookService =
      "META-INF/services/" + classOf[RaiderHook].getName
    val payload: List[(String, Array[Byte])] =
      (spi :: hooks).map(FixtureJars.classEntry) :+
        (FixtureJars.PluginService -> lines(spi.getName :: Nil)) :+
        (hookService -> lines(hooks.map(_.getName)))
    Files.createDirectories(dir)
    val manifest = PluginManifest(
      schemaVersion = 1,
      name = baseName,
      version = PluginVersion(1, 0, 0),
      artifact = s"fixtures/$baseName-1.0.0.jar",
      sha256 = "0" * 64, // replaced by the pinned payload digest
      minApiVersion = 1,
      maxApiVersion = 1,
      capabilities = caps
    )
    val partial = dir.resolve(s"$baseName.partial.jar")
    FixtureJars.writeJar(partial, payload)
    val pinned = PluginLoader
      .payloadSha256(partial)
      .fold(
        e => throw IllegalStateException(e.detail),
        sha => manifest.copy(sha256 = sha)
      )
    Files.deleteIfExists(partial)
    val jar = dir.resolve(s"$baseName.jar")
    FixtureJars.writeJar(
      jar,
      payload :+ (PluginLoader.ManifestEntry -> pinned.toJson
        .getBytes(StandardCharsets.UTF_8))
    )
    jar

  private def loadPlugin(
      dir: Path,
      baseName: String,
      spi: Class[?],
      hooks: List[Class[?]],
      caps: List[CapabilityRef]
  ): LoadedPlugin =
    val jar = hookJar(dir, baseName, spi, hooks, caps)
    PluginLoader
      .load(jar, ToolRegistry.empty, 1)
      .fold(
        e => throw IllegalStateException(e.detail),
        identity
      )

  private def lifecycleCaps: List[CapabilityRef] = List(
    CapabilityRef("hook", PluginHost.StartHook),
    CapabilityRef("hook", PluginHost.FinishHook)
  )

  private def goodHost(): PluginHost =
    val dir = Files.createTempDirectory("raider-hooks-good")
    val lp = loadPlugin(
      dir,
      "fixture-hooks",
      classOf[FixtureLifecyclePlugin],
      List(classOf[FixtureStartHook], classOf[FixtureFinishHook]),
      lifecycleCaps
    )
    PluginHost(List(lp), HookRunner())

  private def adversarialHost(): PluginHost =
    // same plugin NAME as the good jar (the SPI descriptor must equal the
    // manifest); a separate host, so no co-install name clash
    val dir = Files.createTempDirectory("raider-hooks-adv")
    val lp = loadPlugin(
      dir,
      "fixture-hooks",
      classOf[FixtureLifecyclePlugin],
      List(classOf[FixtureThrowStartHook], classOf[FixtureSlowFinishHook]),
      lifecycleCaps
    )
    PluginHost(List(lp), HookRunner(80L))

  private def rogueHost(): PluginHost =
    val dir = Files.createTempDirectory("raider-hooks-rogue")
    val lp = loadPlugin(
      dir,
      "fixture-rogue",
      classOf[FixtureRoguePlugin],
      List(classOf[FixtureRogueStartHook]),
      List(CapabilityRef("hook", "audit"))
    )
    PluginHost(List(lp), HookRunner())

  // ------------------------------------------------- loop/emission machinery

  private val job = JobId("hooks-job")
  private val sess = SessionId("hooks-sess")
  private val a = AttemptId("hooks-att")

  private def limits(): Either[RaiderError, BudgetLimits] =
    BudgetLimits.make(
      maxAttempts = 8,
      maxConcurrentLlm = 1,
      maxConcurrentTools = 1
    )

  private def lims(): BudgetLimits =
    limits().fold(e => throw e, identity)

  private def finalScript(text: String): Vector[ModelEvent] =
    Vector(
      ModelEvent.Started(a),
      ModelEvent.TextDelta(a, text),
      ModelEvent.Finished(a, "stop")
    )

  private def eventually(
      max: ZDuration
  )(cond: ZIO[Any, Nothing, Boolean]): ZIO[Any, String, Unit] =
    ZIO.suspendSucceed {
      val deadline = System.nanoTime() + max.toNanos
      def loop: ZIO[Any, String, Unit] =
        cond.flatMap {
          case true => ZIO.unit
          case false =>
            if System.nanoTime() >= deadline then
              ZIO.fail("condition not met within the generous outer bound")
            else ZIO.sleep(ZDuration.fromMillis(20)) *> loop
        }
      loop
    }

  private def subscribeDrain(
      hub: EventHub
  ): ZIO[
    Any,
    Nothing,
    (Ref[Vector[EventEnvelope]], zio.Fiber.Runtime[Nothing, Unit])
  ] =
    for
      (_, stream) <- hub.subscribe(job)
      sink <- Ref.make(Vector.empty[EventEnvelope])
      fiber <- stream.runForeach(e => sink.update(_ :+ e)).fork
    yield (sink, fiber)

  private def diagnosticsOf(
      envs: Vector[EventEnvelope]
  ): Vector[String] =
    envs.sortBy(_.seq).map(_.payload).collect {
      case EventPayload.Diagnostics(text) => text
    }

  private def tag(p: EventPayload): String = p match
    case _: EventPayload.Waiting           => "Waiting"
    case _: EventPayload.ModelCallStarted  => "ModelCallStarted"
    case _: EventPayload.ModelCallFinished => "ModelCallFinished"
    case _: EventPayload.Diagnostics       => "Diagnostics"
    case other                             => other.getClass.getSimpleName

  private val tenSec = ZDuration.fromSeconds(10)

  private val metaPattern = """\{"model":"m","root":"loop_\d+"\}"""

  // ------------------------------------------------------------------ spec

  def spec = suite("AgentLoop plugin lifecycle hooks RAI-095")(
    test(
      "PH-01: hooks run once each, in program order (start→rounds→finish); " +
        "outcomes logged as Diagnostics; run result unaffected"
    ) {
      for
        _ <- ZIO.succeed(PluginHookRecorder.clear())
        backend <- ScriptedSequenceBackend(finalScript("final answer"))
        adm <- Admission.make(limits())
        hub <- EventHub.make()
        root = AgentLoop.newRoot()
        host <- ZIO.succeed(goodHost())
        (sink, drainer) <- subscribeDrain(hub)
        loop = AgentLoop(
          backend,
          ToolRegistry.empty,
          "m",
          adm,
          root,
          events = Some(LoopEvents(hub, job, root, sess)),
          plugins = Some(host)
        )
        answer <- loop.runText(
          List(RequestMessage("user", "task")),
          lims(),
          maxRounds = 4
        )
        _ <- eventually(tenSec)(sink.get.map(_.size == 7))
        envs <- sink.get
        _ <- drainer.interrupt
        calls = PluginHookRecorder.entries
      yield
        val types = envs.sortBy(_.seq).map(e => tag(e.payload))
        val texts = diagnosticsOf(envs)
        assertTrue(
          answer == "final answer",
          // exactly one dispatch per lifecycle hook, program order
          calls.size == 2,
          calls(0).startsWith("start "),
          calls(1).startsWith("finish "),
          // read-only input: run identity only (model + root), valid JSON
          calls(0).drop(6).matches(metaPattern),
          calls(1).drop(7).matches(metaPattern),
          // full program order: start hook BEFORE the round, finish hook
          // AFTER the last round text (i.e. before the RunFinished trace)
          types == Vector(
            "Diagnostics", // plugin hook 'runStart' completed
            "Diagnostics", // round 1 started
            "Waiting",
            "ModelCallStarted",
            "ModelCallFinished",
            "Diagnostics", // round 1 text
            "Diagnostics" // plugin hook 'runFinish' completed
          ),
          texts(0).startsWith("plugin hook 'runStart' completed: "),
          texts(0).contains("""{"phase":"start"}"""),
          texts.last.startsWith("plugin hook 'runFinish' completed: "),
          texts.last.contains("""{"phase":"finish"}""")
        )
    } @@ TestAspect.withLiveClock,
    test(
      "PH-02a: throwing runStart + hung runFinish → typed Diagnostics, " +
        "run SUCCEEDS (hooks never influence the result)"
    ) {
      for
        _ <- ZIO.succeed(PluginHookRecorder.clear())
        backend <- ScriptedSequenceBackend(finalScript("final answer"))
        adm <- Admission.make(limits())
        hub <- EventHub.make()
        root = AgentLoop.newRoot()
        host <- ZIO.succeed(adversarialHost())
        (sink, drainer) <- subscribeDrain(hub)
        loop = AgentLoop(
          backend,
          ToolRegistry.empty,
          "m",
          adm,
          root,
          events = Some(LoopEvents(hub, job, root, sess)),
          plugins = Some(host)
        )
        answer <- loop.runText(
          List(RequestMessage("user", "task")),
          lims(),
          maxRounds = 4
        )
        _ <- eventually(tenSec)(
          sink.get.map(
            _.count(e =>
              diagnosticsOf(Vector(e)).exists(_.startsWith("plugin hook"))
            ) == 2
          )
        )
        envs <- sink.get
        _ <- drainer.interrupt
        texts = diagnosticsOf(envs)
      yield assertTrue(
        // the run is untouched by hook failures
        answer == "final answer",
        texts.exists(t =>
          t.startsWith("plugin hook 'runStart' failed: ") &&
            t.contains("threw java.lang.IllegalStateException") &&
            t.contains("hook kaboom")
        ),
        texts.exists(t =>
          t.startsWith("plugin hook 'runFinish' failed: ") &&
            t.contains("exceeded the 80ms bound")
        )
      )
    } @@ TestAspect.withLiveClock,
    test(
      "PH-02b: an UNDECLARED hook provider is never dispatched (host wiring)"
    ) {
      val host = rogueHost()
      val outcomesStart = host.runStart("{}")
      val outcomesFinish = host.runFinish("{}")
      assertTrue(
        outcomesStart.isEmpty,
        outcomesFinish.isEmpty,
        host.hookNames.isEmpty,
        PluginHookRecorder.entries.isEmpty
      )
    },
    test(
      "PH-02c: hooks run (and are dropped) even without an events channel; " +
        "result unaffected"
    ) {
      for
        _ <- ZIO.succeed(PluginHookRecorder.clear())
        backend <- ScriptedSequenceBackend(finalScript("final answer"))
        adm <- Admission.make(limits())
        root = AgentLoop.newRoot()
        host <- ZIO.succeed(goodHost())
        loop = AgentLoop(
          backend,
          ToolRegistry.empty,
          "m",
          adm,
          root,
          events = None, // no emission channel at all
          plugins = Some(host)
        )
        answer <- loop.runText(
          List(RequestMessage("user", "task")),
          lims(),
          maxRounds = 4
        )
        calls = PluginHookRecorder.entries
      yield assertTrue(
        answer == "final answer",
        calls.size == 2,
        calls(0).startsWith("start "),
        calls(1).startsWith("finish ")
      )
    } @@ TestAspect.withLiveClock,
    test(
      "PH-03: no plugins (the default) — baseline parity: exact baseline " +
        "emission sequence, no hook lines, no host"
    ) {
      for
        _ <- ZIO.succeed(PluginHookRecorder.clear())
        backend <- ScriptedSequenceBackend(finalScript("final answer"))
        adm <- Admission.make(limits())
        hub <- EventHub.make()
        root = AgentLoop.newRoot()
        (sink, drainer) <- subscribeDrain(hub)
        loop = AgentLoop(
          backend,
          ToolRegistry.empty,
          "m",
          adm,
          root,
          events = Some(LoopEvents(hub, job, root, sess))
          // plugins omitted: the default None
        )
        answer <- loop.runText(
          List(RequestMessage("user", "task")),
          lims(),
          maxRounds = 4
        )
        _ <- eventually(tenSec)(sink.get.map(_.size == 5))
        envs <- sink.get
        _ <- drainer.interrupt
      yield
        val types = envs.sortBy(_.seq).map(e => tag(e.payload))
        val texts = diagnosticsOf(envs)
        assertTrue(
          answer == "final answer",
          loop.plugins.isEmpty,
          // exactly the pre-RAI-095 one-round emission sequence
          types == Vector(
            "Diagnostics", // round 1 started
            "Waiting",
            "ModelCallStarted",
            "ModelCallFinished",
            "Diagnostics" // round 1 text
          ),
          texts.forall(!_.startsWith("plugin hook")),
          PluginHookRecorder.entries.isEmpty
        )
    } @@ TestAspect.withLiveClock,
    test(
      "PH-03b: runFinish runs on the failure path too, BEFORE the run fails " +
        "(typed outcome only, error channel untouched)"
    ) {
      for
        _ <- ZIO.succeed(PluginHookRecorder.clear())
        // a provider failure fails the run — runFinish must still run
        backend <- ScriptedSequenceBackend(
          Vector[ModelEvent](
            ModelEvent.Started(a),
            ModelEvent.Failed(a, RaiderError.ProviderRateLimit("429"))
          )
        )
        adm <- Admission.make(limits())
        root = AgentLoop.newRoot()
        host <- ZIO.succeed(goodHost())
        loop = AgentLoop(
          backend,
          ToolRegistry.empty,
          "m",
          adm,
          root,
          events = None,
          plugins = Some(host)
        )
        res <- loop
          .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
          .exit
        calls = PluginHookRecorder.entries
      yield
        // the run fails typed (RA-PROVIDER-RATELIMIT); the finish hook ran
        assertTrue(
          res.isFailure,
          calls.size == 2,
          calls(0).startsWith("start "),
          calls(1).startsWith("finish ")
        )
    }
  ) @@ TestAspect.sequential // the JVM-global recorder is shared by all tests

end AgentLoopPluginHooksSpec
