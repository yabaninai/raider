package raider.runtime.loop

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.events.EventHub
import raider.runtime.lineage.model.RecoveryClass
import zio.test.*
import zio.{Cause, Duration, FiberId, Promise, Scope, UIO, Unsafe, ZIO}
import zio.stream.ZStream

import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/** RAI-247: deterministic probe for the AgentLoop late-admission window between
  * the plan-phase `phaseStarted` emission and the
  * `planAttempt(...).onExit(...)` arming (RAI-234 bounded the window as
  * source-plausible but could never enter it: 32 loaded runs, no repro).
  *
  * The test-only injection seam [[AgentLoop.planWindowSuspension]] parks the
  * run INSIDE the window (a `Promise.await`), which makes three things
  * checkable deterministically instead of probabilistically:
  *
  *   - '''the revert probe''' an interrupt signal delivered while the run is
  *     parked in the window must still end with `phase_finished` (the exit
  *     finalizer is armed before the interruption is delivered). Red on the
  *     unmasked base (the fiber dies in the window and the finalizer never arms
  *     — the pairing invariant `started => finished` is broken), green once the
  *     emission->arming span is covered by one uninterruptible region
  *     (defense-in-depth; the run still FAILS with the interruption — the mask
  *     delays the signal past the arming, it does not swallow it).
  *   - '''post-arming observability''' an interrupt delivered well beyond the
  *     span (inside the model call) must still kill the run promptly — the mask
  *     must not extend unobservability past the emission->arming span.
  *   - '''inertness''' with no trigger installed (the only production state)
  *     the seam is a literal no-op and the phase pairing is unchanged.
  *
  * Deterministic scripted fixtures only (no network, no spend). The interrupt
  * signal is enqueued with `Fiber.Runtime.tellInterrupt` (synchronous enqueue,
  * no await) because on a masked tree the fiber is parked inside an
  * uninterruptible region and a plain `fiber.interrupt` would deadlock the test
  * before the seam release; the release happens strictly AFTER the signal is
  * enqueued, so no executor interleaving can move the arming ahead of the
  * interruption.
  */
object AgentLoopPhaseWindowSeamSpec extends ZIOSpecDefault:

  private def limits(): Either[RaiderError, BudgetLimits] =
    BudgetLimits.make(maxAttempts = 8)

  private def lims(): BudgetLimits =
    limits().fold(e => throw e, identity)

  private val validPlanJson =
    """{"title":"Ship it","steps":["read the file","write the test"]}"""

  private def planEvent(json: String) = Vector[ModelEvent](
    ModelEvent.Started(a),
    ModelEvent.TextDelta(a, json),
    ModelEvent.Finished(a, "stop")
  )

  private val a = AttemptId("rai-247-att")

  /** Shared strict timeline of one fiber's emissions (same discipline as
    * AgentLoopPhaseEmissionSpec).
    */
  private final class Timeline:
    private val q = new ConcurrentLinkedDeque[String]()
    private val n = AtomicLong(0)

    def record(tag: String): UIO[Unit] = ZIO.succeed {
      val k = n.incrementAndGet()
      q.addLast(s"$k:$tag")
      ()
    }

    def tags: Vector[String] =
      val it = q.iterator()
      var b = Vector.empty[String]
      while it.hasNext do b = b :+ it.next()
      b.map(_.dropWhile(_ != ':').drop(1))

  /** Backend that records each dispatch on the shared timeline and plays one
    * scripted answer per call.
    */
  private final class TimelineBackend(
      tl: Timeline,
      scripts: Vector[Vector[ModelEvent]]
  ) extends ModelBackend:

    def capabilities: ModelCapabilities = ModelCapabilities(
      CapabilityStatus.Supported,
      CapabilityStatus.Supported,
      CapabilityStatus.Supported
    )

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap:
        tl.record("dispatch:1")
          .as(ZStream.fromIterable(scripts.headOption.getOrElse(Vector.empty)))

  /** Hangs INSIDE the stream pass until the gate opens (never in these
    * fixtures) — the run stays parked inside the model call.
    */
  private def hangingBackend(tl: Timeline, gate: Promise[Nothing, Unit]) =
    new ModelBackend:
      def capabilities: ModelCapabilities = ModelCapabilities(
        CapabilityStatus.Supported,
        CapabilityStatus.Supported,
        CapabilityStatus.Supported
      )
      def stream(
          input: ModelRequest
      ): ZStream[Scope, RaiderError, ModelEvent] =
        ZStream.unwrap:
          tl.record("dispatch:1")
            .as:
              ZStream.fromZIO(gate.await).drain

  private def sinkOf(tl: Timeline): LoopEvents.PhaseSink =
    new LoopEvents.PhaseSink:
      def phaseStarted(
          name: String,
          recovery: RecoveryClass,
          note: Option[String],
          rootId: RootId,
          sessionId: SessionId,
          atMs: Long
      ): UIO[Unit] = tl.record(s"phase_started:$name:$recovery")
      def phaseFinished(
          name: String,
          rootId: RootId,
          sessionId: SessionId,
          atMs: Long
      ): UIO[Unit] = tl.record(s"phase_finished:$name")

  private def planLoop(
      backend: ModelBackend,
      adm: Admission,
      root: RootId,
      dir: java.nio.file.Path,
      tl: Timeline,
      hub: EventHub
  ): AgentLoop =
    AgentLoop(
      backend,
      ToolRegistry.empty,
      "m",
      adm,
      root,
      planMode = Some(PlanMode.of(ToolRegistry.empty, dir, 2)),
      events = Some(
        LoopEvents(
          hub,
          JobId("rai-247-job"),
          root,
          SessionId("rai-247-sess"),
          phases = Some(sinkOf(tl))
        )
      )
    )

  /** Arms the seam for the duration of `run` — always disarmed once `run`
    * terminates (completed, failed, or interrupted); keeps the effect scoped as
    * UIO (no Scope requirement).
    */
  private def armed[A](seam: UIO[Unit])(
      run: ZIO[Any, Nothing, A]
  ): ZIO[Any, Nothing, A] =
    ZIO
      .succeed(AgentLoop.planWindowSuspension.set(Some(seam)))
      .flatMap(_ => run)
      .onExit(_ => ZIO.succeed(AgentLoop.planWindowSuspension.set(None)))

  /** Fails loudly (as a defect) if `cond` does not become true within 10 s: the
    * wait's own timeout must surface as a test failure, never be swallowed into
    * a silent pass.
    */
  private def waitFor(
      cond: ZIO[Any, Nothing, Boolean]
  ): ZIO[Any, Nothing, Unit] =
    cond
      .repeatUntil(identity)
      .timeoutFail(
        new java.util.concurrent.TimeoutException(
          "waitFor: condition not met within 10 s"
        )
      )(Duration.fromSeconds(10))
      .orDie
      .unit

  def spec = suite("AgentLoop phaseStarted->onExit window probe RAI-247")(
    test(
      "revert probe: an interrupt parked inside the emission->arming window still ends with phase_finished; the run itself still fails with the interruption"
    ) {
      for
        dir <- ZIO.attempt(Files.createTempDirectory("rai-247-window"))
        tl <- ZIO.succeed(new Timeline)
        backend <- ZIO.succeed(
          new TimelineBackend(tl, Vector(planEvent(validPlanJson)))
        )
        seam <- Promise.make[Nothing, Unit]
        adm <- Admission.make(limits())
        hub <- EventHub.make()
        root = AgentLoop.newRoot()
        loop = planLoop(backend, adm, root, dir, tl, hub)
        probed <- armed(seam.await) {
          for
            fiber <- loop
              .runPlan(
                List(RequestMessage("user", "plan something")),
                lims(),
                4
              )
              .fork
            // deterministic window entry: the emission is on the timeline and
            // the run is parked at the seam (the only suspension point between
            // emission and arming — it cannot arm until the release below)
            _ <- waitFor(
              ZIO.succeed(tl.tags.contains("phase_started:plan:ReadOnly"))
            )
            // synchronous signal enqueue (no await — see the class Scaladoc):
            // UnsafeAPI.interrupt is the raw signal primitive (tellInterrupt).
            // Verified bytecode basis of this probe's determinism on the
            // pinned zio_3-2.1.26.jar: FiberRuntime$$anon$1.interrupt ->
            // FiberRuntime.tellInterrupt -> tell(FiberMessage.InterruptSignal)
            // is a pure queue enqueue without await. A ZIO version bump must
            // re-verify this chain before trusting the red probe; a behavior
            // change here degrades the probe to a bounded timeout failure,
            // not a silent pass.
            _ <- ZIO.succeed(
              Unsafe.unsafe { implicit unsafe =>
                fiber.unsafe.interrupt(Cause.interrupt(FiberId.None))
              }
            )
            _ <- seam.succeed(())
            exit <- fiber.await.timeout(Duration.fromSeconds(15))
          yield exit
        }
        tags = tl.tags
        runFailed = probed.exists(e => !e.isSuccess)
        lastTag = tags.lastOption.getOrElse("<none>")
      yield assertTrue(
        probed.isDefined,
        runFailed,
        tags.take(1) == Vector("phase_started:plan:ReadOnly"),
        // the pairing invariant: once started, the phase is finished — on
        // the unmasked base the fiber dies in the window and this tag is
        // missing (red); with the emission->arming mask it is present
        lastTag == "phase_finished:plan"
      )
    },
    test(
      "post-arming observability: an interrupt inside the model call (beyond the emission->arming span) still kills the run promptly"
    ) {
      for
        dir <- ZIO.attempt(Files.createTempDirectory("rai-247-post"))
        tl <- ZIO.succeed(new Timeline)
        gate <- Promise.make[Nothing, Unit]
        backend <- ZIO.succeed(hangingBackend(tl, gate))
        seam <- Promise.make[Nothing, Unit]
        adm <- Admission.make(limits())
        hub <- EventHub.make()
        root = AgentLoop.newRoot()
        loop = planLoop(backend, adm, root, dir, tl, hub)
        probed <- armed(seam.await) {
          for
            fiber <- loop
              .runPlan(
                List(RequestMessage("user", "plan something")),
                lims(),
                4
              )
              .fork
            // release the window WITHOUT an interruption inside it
            _ <- seam.succeed(())
            // parked INSIDE the model call: arming long since done
            _ <- waitFor(ZIO.succeed(tl.tags.contains("dispatch:1")))
            // must die promptly: a mask that wrongly extended past the span
            // would strand the fiber here and trip this timeout
            exit <- fiber.interrupt.timeout(Duration.fromSeconds(15))
          yield exit
        }
        tags = tl.tags
        runKilled = probed.exists(e => !e.isSuccess)
        lastTag = tags.lastOption.getOrElse("<none>")
      yield assertTrue(
        probed.isDefined,
        runKilled,
        tags.take(1) == Vector("phase_started:plan:ReadOnly"),
        lastTag == "phase_finished:plan"
      )
    },
    test(
      "inertness: with no trigger installed the seam is a no-op and the phase pairing is unchanged"
    ) {
      for
        dir <- ZIO.attempt(Files.createTempDirectory("rai-247-inert"))
        tl <- ZIO.succeed(new Timeline)
        backend <- ZIO.succeed(
          new TimelineBackend(tl, Vector(planEvent(validPlanJson)))
        )
        adm <- Admission.make(limits())
        hub <- EventHub.make()
        root = AgentLoop.newRoot()
        loop = planLoop(backend, adm, root, dir, tl, hub)
        seamUnarmed <- ZIO.succeed(AgentLoop.planWindowSuspension.get())
        outcome <- loop
          .runPlan(
            List(RequestMessage("user", "plan something")),
            lims(),
            4
          )
          .either
        tags = tl.tags
      yield assertTrue(
        seamUnarmed.isEmpty,
        outcome.isRight,
        tags.filter(_.startsWith("phase_")) == Vector(
          "phase_started:plan:ReadOnly",
          "phase_finished:plan"
        ),
        tags.count(_ == "dispatch:1") == 1
      )
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(Duration.fromSeconds(120))

end AgentLoopPhaseWindowSeamSpec
