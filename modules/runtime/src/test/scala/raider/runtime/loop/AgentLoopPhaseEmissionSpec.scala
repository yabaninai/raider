package raider.runtime.loop

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.events.{EventEnvelope, EventHub, EventPayload}
import raider.runtime.lineage.model.RecoveryClass
import zio.test.*
import zio.{Duration as ZDuration, Fiber, Promise, Ref, Scope, UIO, ZIO}
import zio.stream.ZStream

import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

/** RAI-153 acceptance (PHE-01 loop level, PHE-02): the AgentLoop's LIVE phase
  * emission per the ADR-032 contract.
  *
  * What is pinned:
  *   - '''PHE-01''' the ONE phase boundary the runtime genuinely knows — the
  *     plan phase (RAI-107 §2) — emits `phase_started("plan", ReadOnly)` before
  *     the first attempt and `phase_finished("plan")` on EVERY exit (success,
  *     typed rejection, cancellation), with repairs staying INSIDE the one
  *     phase. Ordinary `runText` emits NO phase records (no stage semantics the
  *     runtime tracks are invented). The recording sink shares one strict
  *     timeline with the scripted backend's dispatches, so the enter/exit
  *     positions relative to the real model calls are exact.
  *   - '''PHE-02''' the frozen ADR-024 seam is untouched: during a full plan
  *     run the hub carries ONLY the 13 frozen payload cases — the v2 phase
  *     records never enter the hub, so v1-only consumers see no new cases
  *     (ADR-032 §1: the v2 envelope is a lineage-layer type; the ADR-024 §5
  *     enum stays pinned).
  *
  * Deterministic scripted fixtures only (no network, no spend); cancellation is
  * exercised by interrupting a hanging backend stream (the onExit finalizer is
  * uninterruptible, so the exit record always lands).
  */
object AgentLoopPhaseEmissionSpec extends ZIOSpecDefault:

  private val job = JobId("phase-job")
  private val sess = SessionId("phase-sess")
  private val a = AttemptId("phase-att")

  private def limits(): Either[RaiderError, BudgetLimits] =
    BudgetLimits.make(maxAttempts = 8)

  private def lims(): BudgetLimits =
    limits().fold(e => throw e, identity)

  private val validPlanJson =
    """{"title":"Ship it","steps":["read the file","write the test"]}"""

  private val invalidPlan = "not json at all"

  private def planEvent(json: String) = Vector[ModelEvent](
    ModelEvent.Started(a),
    ModelEvent.TextDelta(a, json),
    ModelEvent.Finished(a, "stop")
  )

  private def twoRoundScript(c1: ToolCallId) = Vector(
    Vector[ModelEvent](
      ModelEvent.Started(a),
      ModelEvent.ToolCallReady(a, c1, "lookup", """{"q":"x"}"""),
      ModelEvent.Finished(a, "tool_round")
    ),
    Vector[ModelEvent](
      ModelEvent.Started(a),
      ModelEvent.TextDelta(a, "final answer"),
      ModelEvent.Finished(a, "stop")
    )
  )

  /** Shared strict timeline of one fiber's emissions: the loop emits phase
    * records and backend dispatches SEQUENTIALLY on its own fiber, so the
    * counter order is the program order of the emitting fiber.
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
    * scripted answer per call (an exhausted script fails the run loudly).
    */
  private final class TimelineBackend(
      tl: Timeline,
      scripts: Vector[Vector[ModelEvent]]
  ) extends ModelBackend:
    private val i = AtomicInteger(0)

    def capabilities: ModelCapabilities = ModelCapabilities(
      CapabilityStatus.Supported,
      CapabilityStatus.Supported,
      CapabilityStatus.Supported
    )

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      val k = i.getAndIncrement()
      ZStream.unwrap:
        tl.record(s"dispatch:${k + 1}")
          .as:
            ZStream.fromIterable(
              scripts
                .lift(k)
                .getOrElse(
                  Vector[ModelEvent](
                    ModelEvent
                      .Failed(a, RaiderError.StreamProtocol("script exhausted"))
                  )
                )
            )

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
        // hangs INSIDE the stream pass until the gate opens (never in these
        // fixtures) — the run stays inside the model call; the dispatch is
        // recorded first so the test can wait for the reached boundary
        ZStream.unwrap:
          tl.record("dispatch:1")
            .as:
              ZStream.fromZIO(gate.await).drain

  /** The recording PhaseSink — the same seam the exporter implements; here it
    * only appends to the shared timeline.
    */
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
      maxRepair: Int,
      tl: Timeline,
      hub: EventHub
  ): AgentLoop =
    AgentLoop(
      backend,
      ToolRegistry.empty,
      "m",
      adm,
      root,
      planMode = Some(PlanMode.of(ToolRegistry.empty, dir, maxRepair)),
      events = Some(
        LoopEvents(hub, job, root, sess, phases = Some(sinkOf(tl)))
      )
    )

  private def textLoop(
      backend: ModelBackend,
      adm: Admission,
      root: RootId,
      tl: Timeline,
      hub: EventHub
  ): AgentLoop =
    AgentLoop(
      backend,
      ToolRegistry.empty,
      "m",
      adm,
      root,
      events = Some(
        LoopEvents(hub, job, root, sess, phases = Some(sinkOf(tl)))
      )
    )

  /** Subscribe BEFORE the run (no loss window) and drain eagerly. */
  private def subscribeDrain(
      hub: EventHub
  ): ZIO[
    Any,
    Nothing,
    (Ref[Vector[EventEnvelope]], Fiber.Runtime[Nothing, Unit])
  ] =
    for
      (snap, stream) <- hub.subscribe(job)
      sink <- Ref.make(Vector.empty[EventEnvelope])
      fiber <- stream.runForeach(e => sink.update(_ :+ e)).fork
    yield (sink, fiber)

  /** The frozen 13-case family, exhaustively — a v2 phase payload (or ANY new
    * case) reaching the hub makes this false.
    */
  private def isFrozenPayload(p: EventPayload): Boolean = p match
    case _: EventPayload.JobStatusChanged     => true
    case _: EventPayload.Waiting              => true
    case _: EventPayload.ModelCallStarted     => true
    case _: EventPayload.ModelCallFinished    => true
    case _: EventPayload.ToolStarted          => true
    case _: EventPayload.ToolProgress         => true
    case _: EventPayload.ToolFinished         => true
    case _: EventPayload.RetryScheduled       => true
    case _: EventPayload.BudgetChanged        => true
    case _: EventPayload.Delegated            => true
    case _: EventPayload.Diagnostics          => true
    case _: EventPayload.DiagnosticsCoalesced => true
    case _: EventPayload.Terminal             => true

  private def waitFor(
      cond: ZIO[Any, Nothing, Boolean]
  ): ZIO[Any, Nothing, Unit] =
    cond.repeatUntil(identity).timeout(ZDuration.fromSeconds(10)).unit

  def spec = suite("AgentLoop live phase emission RAI-153 (ADR-032)")(
    test(
      "PHE-01: runPlan emits phase_started(plan, ReadOnly) before the first attempt and phase_finished(plan) on success"
    ) {
      live {
        for
          dir <- ZIO.attempt(Files.createTempDirectory("rai-153-plan"))
          tl = new Timeline
          backend = new TimelineBackend(tl, Vector(planEvent(validPlanJson)))
          adm <- Admission.make(limits())
          hub <- EventHub.make()
          root = AgentLoop.newRoot()
          (sink, fiber) <- subscribeDrain(hub)
          loop = planLoop(backend, adm, root, dir, maxRepair = 2, tl, hub)
          proposal <- loop.runPlan(
            List(RequestMessage("user", "plan something")),
            lims(),
            4
          )
          _ <- waitFor(
            sink.get.map(
              _.exists(_.payload.isInstanceOf[EventPayload.ModelCallStarted])
            )
          )
          envelopes <- sink.get
          _ <- fiber.interrupt
          tags = tl.tags
        yield
          val phaseTags = tags.filter(_.startsWith("phase_"))
          assertTrue(
            proposal.title == "Ship it",
            // enter BEFORE the first real dispatch, exit after the last one
            phaseTags == Vector(
              "phase_started:plan:ReadOnly",
              "phase_finished:plan"
            ),
            tags.indexOf("phase_started:plan:ReadOnly") <
              tags.indexOf("dispatch:1"),
            tags.indexOf("dispatch:1") <
              tags.indexOf("phase_finished:plan"),
            // exactly one plan phase for the whole run
            tags.count(_ == "phase_started:plan:ReadOnly") == 1,
            tags.count(_ == "phase_finished:plan") == 1,
            // PHE-02: the hub never saw a phase payload
            envelopes.nonEmpty,
            envelopes.forall(e => isFrozenPayload(e.payload))
          )
      }
    },
    test(
      "PHE-01: the bounded repair path stays INSIDE one plan phase; the typed rejection still closes it"
    ) {
      for
        dir <- ZIO.attempt(Files.createTempDirectory("rai-153-plan-reject"))
        tl = new Timeline
        backend = new TimelineBackend(
          tl,
          Vector(planEvent(invalidPlan), planEvent(invalidPlan))
        )
        adm <- Admission.make(limits())
        hub <- EventHub.make()
        root = AgentLoop.newRoot()
        loop = planLoop(backend, adm, root, dir, maxRepair = 1, tl, hub)
        result <- loop
          .runPlan(
            List(RequestMessage("user", "plan something")),
            lims(),
            4
          )
          .either
        tags = tl.tags
      yield
        val rejected = result.left.toOption
        assertTrue(
          rejected.exists(_.isInstanceOf[PlanPhaseError.PlanRejected]),
          // one phase, opened before dispatch:1 and closed after the last
          // repair attempt's dispatch:2 — the repair stayed INSIDE the phase
          tags.filter(_.startsWith("phase_")) == Vector(
            "phase_started:plan:ReadOnly",
            "phase_finished:plan"
          ),
          tags.indexOf("phase_started:plan:ReadOnly") <
            tags.indexOf("dispatch:1"),
          tags.indexOf("dispatch:2") < tags.indexOf("phase_finished:plan")
        )
    },
    test(
      "PHE-01: ordinary runText emits NO phase records — no stage semantics are invented for the execution mode"
    ) {
      for
        tl = new Timeline
        backend = new TimelineBackend(tl, twoRoundScript(ToolCallId("call-1")))
        adm <- Admission.make(limits())
        hub <- EventHub.make()
        root = AgentLoop.newRoot()
        loop = textLoop(backend, adm, root, tl, hub)
        answer <- loop.runText(
          List(RequestMessage("user", "do it")),
          lims(),
          4
        )
        tags = tl.tags
      yield assertTrue(
        answer == "final answer",
        tags.exists(_.startsWith("dispatch:")),
        !tags.exists(_.startsWith("phase_"))
      )
    },
    test(
      "PHE-02: during a full plan run the hub stream carries ONLY the frozen 13 payload cases (v1-only consumers see no new cases)"
    ) {
      live {
        for
          dir <- ZIO.attempt(Files.createTempDirectory("rai-153-frozen"))
          tl = new Timeline
          backend = new TimelineBackend(tl, Vector(planEvent(validPlanJson)))
          adm <- Admission.make(limits())
          hub <- EventHub.make()
          root = AgentLoop.newRoot()
          (sink, fiber) <- subscribeDrain(hub)
          loop = planLoop(backend, adm, root, dir, maxRepair = 2, tl, hub)
          _ <- loop
            .runPlan(
              List(RequestMessage("user", "plan something")),
              lims(),
              4
            )
            .forkDaemon
          _ <- waitFor(
            sink.get.map(es =>
              es.exists(_.payload.isInstanceOf[EventPayload.Waiting]) &&
                es.exists(
                  _.payload.isInstanceOf[EventPayload.ModelCallFinished]
                )
            )
          )
          envelopes <- sink.get
          _ <- fiber.interrupt
        yield assertTrue(
          envelopes.forall(e => isFrozenPayload(e.payload)),
          envelopes.nonEmpty
        )
      }
    },
    test(
      "cancellation: an interrupted plan phase still emits phase_finished (uninterruptible onExit finalizer)"
    ) {
      live {
        for
          dir <- ZIO.attempt(Files.createTempDirectory("rai-153-cancel"))
          tl = new Timeline
          gate <- Promise.make[Nothing, Unit]
          backend = hangingBackend(tl, gate)
          adm <- Admission.make(limits())
          hub <- EventHub.make()
          root = AgentLoop.newRoot()
          loop = planLoop(backend, adm, root, dir, maxRepair = 2, tl, hub)
          fiber <- loop
            .runPlan(
              List(RequestMessage("user", "plan something")),
              lims(),
              4
            )
            .forkDaemon
          // deterministic: the dispatch is recorded ⇒ the run is inside the
          // model call, waiting on the gate
          _ <- waitFor(ZIO.succeed(tl.tags.nonEmpty))
          exit <- fiber.interrupt
          tags = tl.tags
        yield assertTrue(
          !exit.isSuccess,
          tags.take(1) == Vector("phase_started:plan:ReadOnly"),
          tags.last == "phase_finished:plan"
        )
      }
    },
    test(
      "seam hygiene: empty names are dropped; a sink-less context is a literal no-op"
    ) {
      for
        hub <- EventHub.make()
        root = AgentLoop.newRoot()
        recorded <- Ref.make(Vector.empty[String])
        recording = new LoopEvents.PhaseSink:
          def phaseStarted(
              n: String,
              r: RecoveryClass,
              no: Option[String],
              r1: RootId,
              s: SessionId,
              at: Long
          ): UIO[Unit] = recorded.update(_ :+ s"started:$n")
          def phaseFinished(
              n: String,
              r1: RootId,
              s: SessionId,
              at: Long
          ): UIO[Unit] = recorded.update(_ :+ s"finished:$n")
        le = LoopEvents(hub, job, root, sess, phases = Some(recording))
        bare = LoopEvents(hub, job, root, sess)
        _ <- le.phaseStarted("", RecoveryClass.ReadOnly)
        _ <- le.phaseFinished("")
        _ <- bare.phaseStarted("plan", RecoveryClass.ReadOnly)
        _ <- bare.phaseFinished("plan")
        seen <- recorded.get
      yield assertTrue(seen.isEmpty)
    }
  ) @@ TestAspect.timeout(ZDuration.fromSeconds(120))

end AgentLoopPhaseEmissionSpec
