package raider.runtime.delegate

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
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
import zio.{Duration as ZDuration, Fiber, Ref, Scope, UIO, ZIO}

/** RAI-073 acceptance (DR-01..03): the Delegated/Terminal emission race.
  *
  * `jobs.start` forks the child before the delegate tool learns its job id, so
  * an instantly-completing child used to be able to settle — the JobManager's
  * StatusLedger listener publishing `Terminal` under the child's own id —
  * BEFORE the post-start `Delegated` emission; the hub reducer silently refuses
  * any event after a terminal snapshot (Malformed, and a refused publish
  * consumes NO sequence). The fix parks the child task on a per-fork gate and
  * releases it only after the `Delegated` publish attempt.
  *
  * Deterministic discriminator throughout: the child's committed history is
  * `JobStatusChanged(Queued→Running) = seq 1`, `Delegated = seq 2`,
  * `Terminal = seq 3` — a final `lastSeq == 3` is only reachable when the
  * Delegated was ACCEPTED (consumed seq 2) before the Terminal (seq 3); the
  * lost race leaves `lastSeq == 2`. No sleeps, no eventually-polling: the
  * subscribe-snapshot + stream union is the hub's loss-window-free delivery
  * (ADR-024 §2.2), the same convention as DelegationEmissionSpec.
  */
object DelegationRaceSpec extends ZIOSpecDefault:

  private val root = RootId("r073")

  private def lims(maxChildren: Int, maxAttempts: Int = 16): BudgetLimits =
    BudgetLimits
      .make(
        maxConcurrentLlm = 1,
        maxConcurrentTools = 2,
        maxAttempts = maxAttempts,
        maxChildren = maxChildren
      )
      .fold(e => throw e, identity)

  /** Instantly-completing child backend: one round, final text, no park — the
    * child task would return (and settle) immediately after the fork.
    */
  private final class InstantBackend(text: String) extends ModelBackend:

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
      ZStream(
        ModelEvent.Started(AttemptId("ans")),
        ModelEvent.TextDelta(AttemptId("ans"), text),
        ModelEvent.Finished(AttemptId("ans"), "stop")
      )

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

  private final class Wired(
      val registry: ToolRegistry,
      val toolset: DelegationToolset
  )

  private def wire(
      hub: EventHub,
      events: Option[DelegationEvents],
      limits: BudgetLimits,
      backend: ModelBackend
  ): ZIO[Any, Any, Wired] =
    for
      jobs <- JobManager.make(
        onTransition = Some(StatusLedger.onTransition(hub, root))
      )
      adm <- Admission.make(Right(limits))
      toolset <- ZIO.fromEither(
        DelegationToolset.make(
          jobs,
          backend,
          adm,
          limits,
          ToolRegistry.empty,
          Set("scout"),
          root,
          delegationEvents = events
        )
      )
      registry = toolset
        .augment(ToolRegistry.empty)
        .fold(e => throw e, identity)
    yield new Wired(registry, toolset)

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

  /** Stream-side discipline: no duplicate seqs, all inside the delivered
    * window, at most one Terminal and, if present, it is the LAST event.
    */
  private def streamDiscipline(
      envs: Vector[EventEnvelope],
      watermark: Long
  ): Boolean =
    envs.map(_.seq).distinct.size == envs.size &&
      envs.forall(e => e.seq > watermark && e.seq <= 3L) &&
      envs.count(_.payload.isInstanceOf[EventPayload.Terminal]) <= 1 &&
      envs.zipWithIndex
        .collect {
          case (e, i) if e.payload.isInstanceOf[EventPayload.Terminal] => i
        }
        .forall(_ == envs.length - 1)

  def spec = suite("DelegationToolset Delegated/Terminal race RAI-073")(
    test(
      "DR-01 instantly-completing child: Delegated delivered, Terminal the single last event by seq"
    ) {
      for
        hub <- EventHub.make()
        w <- wire(
          hub,
          Some(DelegationEvents(hub, root)),
          lims(4),
          new InstantBackend("found x")
        )
        (feedback, childId) <- doDelegate(w)
        childJob = JobId(childId)
        (snapAtSub, sink, fiber) <- subscribeDrain(hub, childJob)
        _ <- w.toolset.awaitSettled(childId)
        envs <- sink.get
        snap <- hub.snapshot(childJob)
        _ <- fiber.interrupt
      yield assertTrue(
        feedback == s"""{"child_id":"$childId","state":"Running"}""",
        // Delegated is committed BEFORE the tool returns, so the subscriber
        // holds it in the snapshot watermark at worst (loss-window-free)
        snapAtSub.lastSeq >= 2L ||
          envs.exists(_.payload.isInstanceOf[EventPayload.Delegated]),
        // seq 1 Running + seq 2 Delegated + seq 3 Terminal: a refused
        // Delegated consumes no sequence, so lastSeq == 3 proves it was
        // ACCEPTED before the Terminal
        snap.exists(_.lastSeq == 3L),
        snap.exists(_.status == JobStatus.Succeeded),
        streamDiscipline(envs, snapAtSub.lastSeq)
      )
    } @@ TestAspect.withLiveClock,
    test(
      "DR-02 a hub rejecting every publish never alters the delegate feedback JSON"
    ) {
      for
        w <- wire(
          failingHub,
          Some(DelegationEvents(failingHub, root)),
          lims(4),
          new InstantBackend("found x")
        )
        (feedback, childId) <- doDelegate(w)
        _ <- w.toolset.awaitSettled(childId)
        awaitFeedback <- doAwait(w, childId)
      yield assertTrue(
        feedback == s"""{"child_id":"$childId","state":"Running"}""",
        awaitFeedback.contains(s""""child_id":"$childId""""),
        awaitFeedback.contains(s""""status":"Succeeded""""),
        awaitFeedback.contains("found x")
      )
    } @@ TestAspect.withLiveClock,
    test(
      "DR-03 50 fast children in a row: 50 Delegated + 50 Terminal, no losses or duplicates, root pool stays Terminal-free"
    ) {
      for
        hub <- EventHub.make()
        w <- wire(
          hub,
          Some(DelegationEvents(hub, root)),
          // attempts are counted PER ROOT (Admission.consumeAttempt), so 50
          // sequential model rounds need a root-wide ceiling of at least 50
          lims(maxChildren = 64, maxAttempts = 64),
          new InstantBackend("done")
        )
        (rootSnap0, rootSink, rootFiber) <- subscribeDrain(
          hub,
          JobId(root.value)
        )
        spawned <- ZIO.foreach((1 to 50).toList) { _ =>
          for
            (feedback, childId) <- doDelegate(w)
            (snapAtSub, sink, fiber) <- subscribeDrain(hub, JobId(childId))
          yield (childId, feedback, snapAtSub, sink, fiber)
        }
        _ <- ZIO.foreach(spawned)(c => w.toolset.awaitSettled(c._1))
        finals <- ZIO.foreach(spawned)(c => hub.snapshot(JobId(c._1)))
        drains <- ZIO.foreach(spawned)(c => c._4.get)
        rootSnap <- hub.snapshot(JobId(root.value))
        rootEnvs <- rootSink.get
        _ <- ZIO.foreach(spawned)(c => c._5.interrupt)
        _ <- rootFiber.interrupt
      yield
        val feedbacksExact = spawned.forall { case (id, fb, _, _, _) =>
          fb == s"""{"child_id":"$id","state":"Running"}"""
        }
        val perChildOk =
          finals.zip(drains).zip(spawned).forall { case ((snap, envs), c) =>
            snap.exists(s =>
              s.lastSeq == 3L && s.status == JobStatus.Succeeded
            ) && streamDiscipline(envs, c._3.lastSeq)
          }
        assertTrue(
          feedbacksExact,
          perChildOk,
          // every one of the 50 children delivered Delegated + exactly one
          // final Terminal (encoded by the committed 3-event history)
          finals.count(_.exists(s => s.lastSeq == 3L)) == 50,
          finals.count(_.exists(_.status == JobStatus.Succeeded)) == 50,
          // the per-root lifecycle pool never received ANY event (in
          // particular no Terminal): nothing published under JobId(root)
          rootSnap.isEmpty,
          rootEnvs.isEmpty,
          rootSnap0.lastSeq == 0L
        )
    } @@ TestAspect.withLiveClock
  )

end DelegationRaceSpec
