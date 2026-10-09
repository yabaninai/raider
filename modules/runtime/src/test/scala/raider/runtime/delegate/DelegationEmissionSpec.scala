package raider.runtime.delegate

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.{BudgetLimits, MicroUsd}
import raider.runtime.events.{
  EventEnvelope,
  EventError,
  EventHub,
  EventPayload,
  SessionReducer,
  SessionSnapshot
}
import raider.runtime.jobs.{JobManager, JobStatus, StatusLedger}
import zio.test.*
import zio.stream.ZStream
import zio.{Duration as ZDuration, Fiber, Promise, Ref, Scope, UIO, ZIO}

/** RAI-059 acceptance (EMJ-03, ADR-024 §2.6/§6): DelegationToolset emission
  * into the REAL EventHub — `Delegated(childJobId, agent)` on fork and the
  * ChildAwaited-equivalent `Diagnostics` on observed child completion.
  *
  * The JobManager carries the StatusLedger listener as well, so the CHILD's
  * per-job stream ends at its Terminal (the hub reducer refuses any event after
  * a terminal snapshot — `await_agent` observes completion only after that),
  * while the ChildAwaited Diagnostics is routed to the synthetic per-root
  * lifecycle pool `JobId(rootId.value)` (the BudgetLedger convention, see
  * [[DelegationEvents]]):
  *
  * child stream (childJobId): root lifecycle pool (JobId(root.value)): seq 1
  * JobStatusChanged seq 1 Diagnostics (Queued→Running) ("child … completed:
  * Succeeded") seq 2 Delegated(childJobId, agent) seq 3 Terminal(Succeeded)
  *
  * The child subscription happens BETWEEN `delegate` and `await_agent`, so seq
  * 1-2 are in the subscribe snapshot and seq 3 arrives on the stream — the
  * loss-window-free boundary (ADR-024 §2.2) is exercised for real. Tools are
  * invoked DIRECTLY through the augmented registry (the same `invoke` the
  * parent loop would call); maxConcurrentLlm = 1 throughout (BUD-03).
  */
object DelegationEmissionSpec extends ZIOSpecDefault:

  private val root = RootId("d-root")

  private val tenSec = ZDuration.fromSeconds(10)

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
            else ZIO.sleep(ZDuration.fromMillis(10)) *> loop
        }
      loop
    }

  private def awaitCount(
      sink: Ref[Vector[EventEnvelope]],
      n: Int
  ): ZIO[Any, String, Unit] =
    eventually(tenSec)(sink.get.map(_.size >= n))

  private def subscribeDrain(
      hub: EventHub,
      jobId: JobId
  ): ZIO[
    Any,
    Nothing,
    (SessionSnapshot, Ref[Vector[EventEnvelope]], Fiber.Runtime[Nothing, Unit])
  ] =
    for
      (snap, stream) <- hub.subscribe(jobId)
      sink <- Ref.make(Vector.empty[EventEnvelope])
      fiber <- stream.runForeach(e => sink.update(_ :+ e)).fork
    yield (snap, sink, fiber)

  private def failingHub: EventHub =
    new EventHub:
      def publish(
          jobId: JobId,
          rootId: RootId,
          sessionId: SessionId,
          payload: EventPayload
      ): ZIO[Any, EventError, Unit] =
        ZIO.fail(EventError.Backpressure(jobId, 1))
      def subscribe(
          jobId: JobId
      ): ZIO[
        Any,
        Nothing,
        (SessionSnapshot, ZStream[Any, Nothing, EventEnvelope])
      ] =
        ZIO.succeed(
          (
            SessionReducer.initial(jobId, RootId(""), SessionId("")),
            ZStream.empty
          )
        )
      def snapshot(jobId: JobId): ZIO[Any, Nothing, Option[SessionSnapshot]] =
        ZIO.succeed(None)
      def droppedDiagnostics: ZIO[Any, Nothing, Long] = ZIO.succeed(0L)

  /** One-shot scripted child: a single round with a final text. The child PARKS
    * on `park` before its model round — the subscribe boundary of the EMJ-03
    * test is deterministic only if the child cannot complete before the test
    * subscribes (completion requires the gate).
    */
  private final class AnswerBackend(
      text: String,
      park: Promise[Nothing, Unit]
  ) extends ModelBackend:

    def capabilities: ModelCapabilities =
      ModelCapabilities(
        CapabilityStatus.Supported,
        CapabilityStatus.Supported,
        CapabilityStatus.Unknown
      )

    def requests: List[ModelRequest] = Nil

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      // park must be `++` + drain, not `*>` (the combined stream would end
      // without pulling the body — TurnBackend lesson)
      ZStream.fromZIO(park.await).drain ++
        ZStream(
          ModelEvent.Started(AttemptId("ans")),
          ModelEvent.TextDelta(AttemptId("ans"), text),
          ModelEvent.Finished(AttemptId("ans"), "stop")
        )

  private def lims: BudgetLimits =
    BudgetLimits
      .make(
        maxConcurrentLlm = 1,
        maxConcurrentTools = 2,
        maxAttempts = 16,
        maxChildren = 4
      )
      .fold(e => throw e, identity)

  /** One wired delegation context: manager (with the StatusLedger listener on
    * `hub`) + admission + toolset (with optional `events`) + augmented registry
    * + the child park-release.
    */
  private final class Wired(
      val registry: ToolRegistry,
      val toolset: DelegationToolset,
      val releaseChild: UIO[Unit]
  )

  private def wire(
      hub: EventHub,
      events: Option[DelegationEvents]
  ): ZIO[Any, Any, Wired] =
    for
      jobs <- JobManager.make(
        onTransition = Some(StatusLedger.onTransition(hub, root))
      )
      adm <- Admission.make(Right(lims))
      park <- Promise.make[Nothing, Unit]
      backend = new AnswerBackend("found x", park)
      toolset <- ZIO.fromEither(
        DelegationToolset.make(
          jobs,
          backend,
          adm,
          lims,
          ToolRegistry.empty,
          Set("scout"),
          root,
          delegationEvents = events
        )
      )
      registry = toolset
        .augment(ToolRegistry.empty)
        .fold(e => throw e, identity)
    yield new Wired(registry, toolset, park.succeed(()).unit)

  private def childIdOf(feedback: String): String =
    """"child_id":"(j_\d+)"""".r
      .findFirstMatchIn(feedback)
      .map(_.group(1))
      .getOrElse("j_missing")

  private def doDelegate(w: Wired): ZIO[Any, Any, (String, String)] =
    for
      tool <- ZIO.succeed(
        w.registry
          .lookup("delegate")
          .getOrElse(
            throw new AssertionError("delegate tool missing from the registry")
          )
      )
      feedback <- ZIO
        .scoped(tool.invoke("""{"agent":"scout","input":"find x"}"""))
        .timeout(ZDuration.fromSeconds(15))
      fb = feedback.getOrElse("")
    yield (fb, childIdOf(fb))

  private def doAwait(w: Wired, childId: String): ZIO[Any, Any, String] =
    for
      tool <- ZIO.succeed(
        w.registry
          .lookup("await_agent")
          .getOrElse(
            throw new AssertionError(
              "await_agent tool missing from the registry"
            )
          )
      )
      feedback <- ZIO
        .scoped(tool.invoke(s"""{"child_id":"$childId"}"""))
        .timeout(ZDuration.fromSeconds(15))
    yield feedback.getOrElse("")

  def spec = suite("DelegationToolset emission RAI-059")(
    test(
      "EMJ-03 Delegated on fork + Diagnostics on completion: child stream ends at Terminal, the ChildAwaited Diagnostics lands on the per-root lifecycle pool"
    ) {
      for
        hub <- EventHub.make()
        w <- wire(hub, Some(DelegationEvents(hub, root)))
        // the per-root lifecycle pool is subscribed BEFORE the fork: its
        // watermark is empty and the Diagnostics is its seq 1
        (rootSnap0, rootSink, rootFiber) <- subscribeDrain(
          hub,
          JobId(root.value)
        )
        (feedback, childId) <- doDelegate(w)
        childJob = JobId(childId)
        // BETWEEN fork and await: seq 1 (Running) + seq 2 (Delegated) are
        // committed, seq 3 (Terminal) is still to come — the parked child
        // cannot complete before this subscription
        (snap0, sink, fiber) <- subscribeDrain(hub, childJob)
        _ <- w.releaseChild
        awaitFeedback <- doAwait(w, childId)
        _ <- awaitCount(sink, 1) // Terminal is committed before await_agent
        // returned; the forwarder delivers it
        _ <- awaitCount(rootSink, 1) // the ChildAwaited Diagnostics on the pool
        envs <- sink.get
        rootEnvs <- rootSink.get
        _ <- fiber.interrupt
        _ <- rootFiber.interrupt
        snap <- hub.snapshot(childJob)
        rootSnap <- hub.snapshot(JobId(root.value))
      yield
        val ordered = envs.sortBy(_.seq)
        val rootOrdered = rootEnvs.sortBy(_.seq)
        assertTrue(
          feedback.contains(s""""child_id":"$childId""""),
          feedback.contains(s""""state":"Running""""),
          snap0.lastSeq == 2L,
          snap0.status == JobStatus.Running,
          // the child's own stream: exactly one Terminal, LAST by seq
          ordered.map(_.payload) == Vector(
            EventPayload.Terminal(JobStatus.Succeeded)
          ),
          ordered.map(_.seq) == Vector(3L),
          // the ChildAwaited equivalent lives on the per-root pool, seq 1
          rootOrdered.map(_.payload) == Vector(
            EventPayload.Diagnostics(s"child $childId completed: Succeeded")
          ),
          rootOrdered.map(_.seq) == Vector(1L),
          // await feedback is intact
          awaitFeedback.contains(s""""child_id":"$childId""""),
          awaitFeedback.contains(s""""status":"Succeeded""""),
          awaitFeedback.contains("found x"),
          snap.map(_.status) == Some(JobStatus.Succeeded),
          snap.exists(_.lastSeq == 3L),
          rootSnap.exists(_.lastSeq == 1L)
        )
    } @@ TestAspect.withLiveClock,
    test(
      "EMJ-03 a failing hub (always Backpressure) never alters the tool feedback JSON"
    ) {
      for
        w <- wire(
          failingHub,
          Some(DelegationEvents(failingHub, root))
        )
        (feedback, childId) <- doDelegate(w)
        _ <- w.releaseChild
        awaitFeedback <- doAwait(w, childId)
      yield assertTrue(
        feedback.contains(s""""child_id":"$childId""""),
        feedback.contains(s""""state":"Running""""),
        awaitFeedback.contains(s""""status":"Succeeded""""),
        awaitFeedback.contains("found x")
      )
    } @@ TestAspect.withLiveClock,
    test(
      "EMJ-02 no events: zero overhead, delegation behaves exactly as before"
    ) {
      // the manager listener still gets a (healthy) hub; the TOOLSET itself
      // gets `None` — the zero-overhead path under test
      for
        hub <- EventHub.make()
        w <- wire(hub, None)
        (feedback, childId) <- doDelegate(w)
        _ <- w.releaseChild
        awaitFeedback <- doAwait(w, childId)
      yield assertTrue(
        feedback.contains(s""""child_id":"$childId""""),
        awaitFeedback.contains(s""""status":"Succeeded""""),
        awaitFeedback.contains("found x")
      )
    } @@ TestAspect.withLiveClock
  )

end DelegationEmissionSpec
