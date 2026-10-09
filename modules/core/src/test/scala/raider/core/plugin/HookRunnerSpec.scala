package raider.core.plugin

import zio.test._

import scala.jdk.CollectionConverters.*

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** RAI-084 acceptance (HOOK-01..03).
  *
  * HOOK-01: a declared hook is invoked with the exact input; the result is
  * typed. HOOK-02: timeout and throw are typed failures (no exception ever
  * escapes `runHook`), isolation of the caller. HOOK-03: three hooks run in
  * parallel — per-invocation outcomes stay independent (error isolation).
  */
object HookRunnerSpec extends ZIOSpecDefault:

  /** Records every input it is called with (thread-safe: HOOK-03 shares one).
    */
  private final class RecordingHook(
      val name: String,
      reply: String => String
  ) extends RaiderHook:
    private val seen = new AtomicReference(List.empty[String])

    def apply(inputJson: String): String =
      seen.updateAndGet(inputJson :: _)
      reply(inputJson)

    def inputs: List[String] = seen.get.reverse

  private final class SleepingHook(val name: String, millis: Long)
      extends RaiderHook:

    def apply(inputJson: String): String =
      Thread.sleep(millis)
      inputJson

  private final class ThrowingHook(val name: String, thrown: Throwable)
      extends RaiderHook:
    def apply(inputJson: String): String = throw thrown

  private def recording(name: String): RecordingHook =
    RecordingHook(name, in => s"""{"echo":$in}""")

  def spec = suite("RAI-084 hook runner")(
    suite("HOOK-01 declared hook runs and types the result")(
      test(
        "declared hook is invoked with the input; output is typed Completed"
      ) {
        val hook = recording("fixture.audit")
        val out = HookRunner().runHook(hook, "fixture.audit", """{"a":1}""")
        assertTrue(
          out == HookOutcome
            .Completed("fixture.audit", """{"echo":{"a":1}}"""),
          hook.inputs == List("""{"a":1}"""),
          out.hook == "fixture.audit"
        )
      },
      test("default bound is 2000ms and validates > 0 at construction") {
        val ctorZero =
          try
            HookRunner(0L)
            None
          catch case e: IllegalArgumentException => Some(e.getMessage)
        val ctorNegative =
          try
            HookRunner(-1L)
            None
          catch case e: IllegalArgumentException => Some(e.getMessage)
        assertTrue(
          HookRunner().defaultTimeout == 2000L,
          HookRunner(50L).defaultTimeout == 50L,
          ctorZero.exists(_.contains("must be > 0")),
          ctorNegative.exists(_.contains("must be > 0"))
        )
      },
      test(
        "dispatch under another name is a typed Undeclared refusal; hook NOT invoked"
      ) {
        val hook = recording("fixture.audit")
        val out = HookRunner().runHook(hook, "other.hook", "{}")
        assertTrue(
          out == HookOutcome
            .Failed("other.hook", HookFailure.Undeclared("fixture.audit")),
          hook.inputs.isEmpty
        )
      },
      test("a non-RaiderHook instance is a typed NotAHook refusal") {
        val out = HookRunner().runHook(new Object, "fixture.audit", "{}")
        assertTrue(
          out == HookOutcome
            .Failed("fixture.audit", HookFailure.NotAHook("java.lang.Object"))
        )
      },
      test("a per-call bound <= 0 is a typed InvalidTimeout refusal") {
        val hook = recording("fixture.audit")
        val zero = HookRunner().runHook(hook, "fixture.audit", "{}", 0L)
        val negative = HookRunner().runHook(hook, "fixture.audit", "{}", -5L)
        assertTrue(
          zero == HookOutcome
            .Failed("fixture.audit", HookFailure.InvalidTimeout(0L)),
          negative == HookOutcome
            .Failed("fixture.audit", HookFailure.InvalidTimeout(-5L)),
          hook.inputs.isEmpty
        )
      }
    ),
    suite("HOOK-02 timeout and throw are typed")(
      test(
        "a hook exceeding its bound is a typed TimedOut and returns promptly"
      ) {
        val runner = HookRunner()
        val slow = SleepingHook("fixture.slow", 2500L)
        val started = System.nanoTime()
        val out = runner.runHook(slow, "fixture.slow", "{}", 50L)
        val elapsedMs = (System.nanoTime() - started) / 1000000L
        assertTrue(
          out == HookOutcome.Failed("fixture.slow", HookFailure.TimedOut(50L)),
          elapsedMs < 2000L // bounded: the runner never waits out the hook
        )
      },
      test("a thrown exception is a typed Threw carrying type and message") {
        val boom =
          ThrowingHook("fixture.boom", IllegalStateException("kaboom"))
        val out = HookRunner().runHook(boom, "fixture.boom", "{}")
        assertTrue(
          out == HookOutcome.Failed(
            "fixture.boom",
            HookFailure.Threw(
              "java.lang.IllegalStateException",
              Some("kaboom")
            )
          )
        )
      },
      test("an Error thrown by the hook is typed too, never rethrown") {
        val bad = ThrowingHook("fixture.bad", StackOverflowError("deep"))
        val out = HookRunner().runHook(bad, "fixture.bad", "{}")
        assertTrue(
          out == HookOutcome.Failed(
            "fixture.bad",
            HookFailure.Threw("java.lang.StackOverflowError", Some("deep"))
          )
        )
      },
      test("no exception ever escapes runHook (caller stays isolated)") {
        val runner = HookRunner()
        val victims = List(
          ThrowingHook("h1", RuntimeException("x")),
          ThrowingHook("h2", StackOverflowError("y")),
          SleepingHook("h3", 2500L) // times out at the 50ms bound
        )
        val outs = victims.map(v => runner.runHook(v, v.name, "{}", 50L))
        assertTrue(
          outs.forall(_.isInstanceOf[HookOutcome]),
          outs.collect { case f: HookOutcome.Failed => f }.size == 3
        )
      },
      test(
        "failures project into the frozen PluginError channel without extending it"
      ) {
        val failed: HookOutcome =
          HookOutcome.Failed("fixture.slow", HookFailure.TimedOut(5L))
        assertTrue(
          failed.toPluginError.exists { case PluginError.Malformed(what) =>
            what.contains("hook 'fixture.slow'") && what.contains("5ms")
          },
          HookOutcome.Completed("h", "{}").toPluginError.isEmpty
        )
      }
    ),
    suite("HOOK-03 parallel hooks stay isolated")(
      test(
        "3 hooks in parallel: one completes, one throws, one times out — outcomes independent"
      ) {
        val runner = HookRunner()
        val good = recording("fixture.good")
        val slow = SleepingHook("fixture.slow", 2500L)
        val boom =
          ThrowingHook("fixture.boom", IllegalStateException("parallel-boom"))
        val jobs: List[(String, () => HookOutcome)] = List(
          (
            "fixture.good",
            () => runner.runHook(good, "fixture.good", """{"i":1}""")
          ),
          (
            "fixture.slow",
            () => runner.runHook(slow, "fixture.slow", "{}", 50L)
          ),
          (
            "fixture.boom",
            () => runner.runHook(boom, "fixture.boom", "{}")
          )
        )
        val results = new ConcurrentHashMap[String, HookOutcome]
        val threads = jobs.map { (name, job) =>
          new Thread(
            () => { results.put(name, job()); () },
            s"hook-spec-$name"
          )
        }
        threads.foreach(_.start())
        threads.foreach(_.join(10000L))
        val goodOut = Option(results.get("fixture.good"))
        val slowOut = Option(results.get("fixture.slow"))
        val boomOut = Option(results.get("fixture.boom"))
        assertTrue(
          threads.forall(!_.isAlive),
          goodOut.contains(
            HookOutcome.Completed("fixture.good", """{"echo":{"i":1}}""")
          ),
          slowOut.contains(
            HookOutcome.Failed("fixture.slow", HookFailure.TimedOut(50L))
          ),
          boomOut.contains(
            HookOutcome.Failed(
              "fixture.boom",
              HookFailure.Threw(
                "java.lang.IllegalStateException",
                Some("parallel-boom")
              )
            )
          ),
          good.inputs == List(
            """{"i":1}"""
          ) // exactly one dispatch, no cross-talk
        )
      },
      test(
        "the same hook instance serves 3 parallel calls without corruption"
      ) {
        val runner = HookRunner()
        val shared = recording("fixture.shared")
        val results = new ConcurrentHashMap[Integer, HookOutcome]
        val threads = (1 to 3).map { i =>
          new Thread(
            () => {
              results.put(
                i,
                runner.runHook(shared, "fixture.shared", s"""{"n":$i}""")
              )
              ()
            },
            s"hook-spec-shared-$i"
          )
        }
        threads.foreach(_.start())
        threads.foreach(_.join(10000L))
        assertTrue(
          threads.forall(!_.isAlive),
          results.values.size == 3,
          results.values.asScala.forall(_.isInstanceOf[HookOutcome.Completed]),
          shared.inputs.size == 3,
          shared.inputs.toSet.size == 3 // every input delivered exactly once
        )
      }
    )
  )
