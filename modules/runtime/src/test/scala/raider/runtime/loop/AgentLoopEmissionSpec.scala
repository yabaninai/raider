package raider.runtime.loop

import raider.core.*
import raider.runtime.admission.{Admission, SlotKind}
import raider.runtime.budget.{BudgetLimits, MicroUsd}
import raider.runtime.events.{
  EventEnvelope,
  EventError,
  EventHub,
  EventPayload,
  SessionReducer,
  SessionSnapshot,
  ToolOutcome
}
import raider.runtime.jobs.JobStatus
import raider.testkit.ScriptedSequenceBackend
import zio.test.{live, *}
import zio.stream.ZStream
import zio.{Duration as ZDuration, Exit as ZExit, Fiber, Promise, Ref, ZIO}

/** RAI-058 acceptance (EMIT-01..03, ADR-024 §2.3/§6): AgentLoop emission into
  * the REAL EventHub.
  *
  * Determinism rules (same discipline as EventHubSpec):
  *   - all fixtures are deterministic (ScriptedSequenceBackend); the only real
  *     sleeps are generous outer bounds around logically guaranteed conditions
  *     (`eventually`) or the tools' own real timeouts;
  *   - per-job seq order == program order of a single publishing fiber (the
  *     hub's per-job publish lock), so payload sequences are asserted exactly,
  *     while subscriber arrival order is normalized by sorting on `seq`;
  *   - every fixture runs with maxConcurrentLlm = 1, so each green test also
  *     re-proves the maxLLM=1 serialization regression under emission.
  */
object AgentLoopEmissionSpec extends ZIOSpecDefault:

  private val job = JobId("emit-job")
  private val sess = SessionId("emit-sess")
  private val a = AttemptId("emit-att")
  private val c1 = ToolCallId("call-1")

  private def limits(
      attempts: Int = 8,
      llm: Int = 1,
      tools: Int = 1
  ): Either[RaiderError, BudgetLimits] =
    BudgetLimits.make(
      maxAttempts = attempts,
      maxConcurrentLlm = llm,
      maxConcurrentTools = tools
    )

  private def lims(attempts: Int = 8): BudgetLimits =
    limits(attempts = attempts).fold(e => throw e, identity)

  private def registryOf(tools: Tool*): ToolRegistry =
    ToolRegistry.of(tools*).fold(e => throw e, identity)

  private def echoTool: Tool = new Tool:
    def name = "lookup"
    def version = 1
    def description = "test echo tool"
    def recovery = RecoveryClass.ReadOnly
    def timeoutMs = 5000L
    def capabilities = ToolCapabilities(concurrentSafe = false)
    def invoke(args: String) = ZIO.succeed(s"""{"echo":$args}""")

  private def failingTool(err: RaiderError): Tool = new Tool:
    def name = "lookup"
    def version = 1
    def description = "always failing"
    def recovery = RecoveryClass.ReadOnly
    def timeoutMs = 5000L
    def capabilities = ToolCapabilities(concurrentSafe = false)
    def invoke(args: String) = ZIO.fail(err)

  /** Two-round script: a tool round, then the final answer. */
  private def twoRoundScript: Vector[Vector[ModelEvent]] =
    Vector(
      Vector[ModelEvent](
        ModelEvent.Started(a),
        ModelEvent.TextDelta(a, "searching…"),
        ModelEvent.ToolCallReady(a, c1, "lookup", """{"q":"x"}"""),
        ModelEvent.Finished(a, "tool_round")
      ),
      Vector[ModelEvent](
        ModelEvent.Started(a),
        ModelEvent.TextDelta(a, "final "),
        ModelEvent.TextDelta(a, "answer"),
        ModelEvent.Finished(a, "stop")
      )
    )

  /** One tool-call script (no final answer: the run is expected to fail or be
    * cancelled at the tool).
    */
  private def oneToolScript(toolName: String): Vector[ModelEvent] =
    Vector(
      ModelEvent.Started(a),
      ModelEvent.ToolCallReady(a, c1, toolName, "1"),
      ModelEvent.Finished(a, "r1")
    )

  private def loopWithEvents(
      backend: ModelBackend,
      registry: ToolRegistry,
      adm: Admission,
      root: RootId,
      hub: EventHub
  ): AgentLoop =
    AgentLoop(
      backend,
      registry,
      "m",
      adm,
      root,
      events = Some(LoopEvents(hub, job, root, sess))
    )

  /** Await a guaranteed-reachable condition; the real duration is only a
    * generous outer bound converting a (defective) hang into a failure.
    */
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

  /** Subscribe BEFORE the run (no loss window) and drain eagerly. */
  private def subscribeDrain(
      hub: EventHub
  ): ZIO[
    Any,
    Nothing,
    (SessionSnapshot, Ref[Vector[EventEnvelope]], Fiber.Runtime[Nothing, Unit])
  ] =
    for
      (snap, stream) <- hub.subscribe(job)
      sink <- Ref.make(Vector.empty[EventEnvelope])
      fiber <- stream.runForeach(e => sink.update(_ :+ e)).fork
    yield (snap, sink, fiber)

  /** Reducer fold over `es` ordered by per-job seq — the only order the reducer
    * contract speaks about.
    */
  private def foldFrom(
      start: SessionSnapshot,
      es: Vector[EventEnvelope]
  ): ZIO[Any, EventError, SessionSnapshot] =
    es
      .sortBy(_.seq)
      .foldLeft(ZIO.succeed(start): ZIO[Any, EventError, SessionSnapshot])(
        (acc, e) => acc.flatMap(SessionReducer.reduce(_, e))
      )

  private def tag(p: EventPayload): String = p match
    case _: EventPayload.Waiting           => "Waiting"
    case _: EventPayload.ModelCallStarted  => "ModelCallStarted"
    case _: EventPayload.ModelCallFinished => "ModelCallFinished"
    case _: EventPayload.ToolStarted       => "ToolStarted"
    case _: EventPayload.ToolFinished      => "ToolFinished"
    case _: EventPayload.Diagnostics       => "Diagnostics"
    case other                             => other.getClass.getSimpleName

  private def exitCode[A](exit: ZExit[RaiderError, A]): Option[String] =
    exit match
      case ZExit.Failure(cause) => cause.failures.headOption.map(_.code)
      case ZExit.Success(_)     => None

  private val tenSec = ZDuration.fromSeconds(10)

  def spec = suite("AgentLoop emission RAI-058")(
    test(
      "EMIT-01 scripted round: consistent events, REAL rounds, fold == hub snapshot"
    ) {
      for
        backend <- ScriptedSequenceBackend(twoRoundScript*)
        adm <- Admission.make(limits())
        hub <- EventHub.make()
        root = AgentLoop.newRoot()
        loop = loopWithEvents(backend, registryOf(echoTool), adm, root, hub)
        (snap0, sink, fiber) <- subscribeDrain(hub)
        answer <- loop.runText(
          List(RequestMessage("user", "task")),
          lims(),
          maxRounds = 4
        )
        _ <- eventually(tenSec)(sink.get.map(_.size == 12))
        envs <- sink.get
        ordered = envs.sortBy(_.seq)
        _ <- fiber.interrupt
        snap <- hub.snapshot(job)
        folded <- foldFrom(SessionReducer.initial(job, root, sess), ordered)
        dropped <- hub.droppedDiagnostics
      yield
        val types = ordered.map(e => tag(e.payload))
        val mcs = ordered.collect {
          case EventEnvelope(
                _,
                _,
                _,
                _,
                _,
                _,
                EventPayload.ModelCallStarted(r, m, mc)
              ) =>
            (r, m, mc)
        }
        val mcf = ordered.collect {
          case EventEnvelope(
                _,
                _,
                _,
                _,
                _,
                _,
                EventPayload.ModelCallFinished(r, d, u)
              ) =>
            (r, d, u)
        }
        val ts = ordered.collect {
          case EventEnvelope(
                _,
                _,
                _,
                _,
                _,
                _,
                EventPayload.ToolStarted(cid, t, s)
              ) =>
            (cid, t, s)
        }
        val tf = ordered.collect {
          case EventEnvelope(
                _,
                _,
                _,
                _,
                _,
                _,
                EventPayload.ToolFinished(cid, t, o)
              ) =>
            (cid, t, o)
        }
        assertTrue(
          answer == "final answer",
          // subscribed before the run: empty watermark
          snap0.lastSeq == 0L,
          // exact per-round sequence (seq == program order of one fiber)
          types == Vector(
            "Diagnostics", // "round 1 started" (RoundStarted equivalent)
            "Waiting", // Model slot, RELIABLE
            "ModelCallStarted",
            "ModelCallFinished",
            "Waiting", // Tool slot, RELIABLE
            "ToolStarted",
            "ToolFinished",
            "Diagnostics", // "round 2 started"
            "Waiting",
            "ModelCallStarted",
            "ModelCallFinished",
            "Diagnostics" // "round 2 text: final answer"
          ),
          // REAL rounds (never -1), both directions
          mcs.map(_._1) == Vector(1, 2),
          mcf.map(_._1) == Vector(1, 2),
          mcs.map(_._3) == Vector(
            Some(1),
            Some(3)
          ), // conversation size per round
          mcf.map(_._2).forall(_ >= 0L), // observed duration, honest
          // usage is unknown everywhere — never coerced into 0/false
          mcf
            .map(_._3)
            .forall(u =>
              u.unknown && u.reserved == MicroUsd(0L) && u.observed == MicroUsd(
                0L
              )
            ),
          // tool seam: argsSummary is the real (bounded) args, Succeeded outcome
          ts == Vector(("call-1", "lookup", """{"q":"x"}""")),
          ts.map(_._3).forall(_.length <= LoopEvents.maxArgsSummaryChars),
          tf == Vector(("call-1", "lookup", ToolOutcome.Succeeded)),
          // snapshot == fold of the delivered stream (source of truth)
          snap.map(_.lastSeq) == Some(12L),
          folded == snap.getOrElse(
            throw new AssertionError("hub snapshot missing after 12 publishes")
          ),
          // nothing dropped at these buffer sizes
          dropped == 0L
        )
    } @@ TestAspect.withLiveClock,
    suite("EMIT-02 failure paths are emitted too")(
      test("tool failure → ToolFinished(Failed(reason)); run fails typed") {
        for
          backend <- ScriptedSequenceBackend(oneToolScript("lookup"))
          adm <- Admission.make(limits())
          hub <- EventHub.make()
          root = AgentLoop.newRoot()
          loop = loopWithEvents(
            backend,
            registryOf(failingTool(RaiderError.ToolFailed("boom"))),
            adm,
            root,
            hub
          )
          (_, sink, fiber) <- subscribeDrain(hub)
          res <- loop
            .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
            .exit
          _ <- eventually(tenSec)(
            sink.get.map(
              _.exists(
                _.payload == EventPayload.ToolFinished(
                  "call-1",
                  "lookup",
                  ToolOutcome.Failed("boom")
                )
              )
            )
          )
          envs <- sink.get
          _ <- fiber.interrupt
        yield assertTrue(
          res.isFailure,
          exitCode(res) == Some("RA-TOOLFAIL"),
          // the failure path emits (today's trace emits nothing here)
          envs.exists(
            _.payload == EventPayload.ToolFinished(
              "call-1",
              "lookup",
              ToolOutcome.Failed("boom")
            )
          ),
          // ModelCallFinished was emitted for the failed round too (usage unknown)
          envs.exists {
            case EventEnvelope(
                  _,
                  _,
                  _,
                  _,
                  _,
                  _,
                  EventPayload.ModelCallFinished(1, _, u)
                ) =>
              u.unknown
            case _ => false
          }
        )
      } @@ TestAspect.withLiveClock,
      test("tool timeout → ToolFinished(Timeout); run fails RA-DEADLINE") {
        live {
          for
            backend <- ScriptedSequenceBackend(oneToolScript("lookup"))
            adm <- Admission.make(limits())
            hub <- EventHub.make()
            slow = new Tool:
              def name = "lookup"; def version = 1; def description = ""
              def recovery = RecoveryClass.ReadOnly; def timeoutMs = 80L
              def capabilities = ToolCapabilities(concurrentSafe = false)
              def invoke(args: String) =
                ZIO.sleep(ZDuration.fromSeconds(10)).as("\"never\"")
            root = AgentLoop.newRoot()
            loop = loopWithEvents(backend, registryOf(slow), adm, root, hub)
            (_, sink, fiber) <- subscribeDrain(hub)
            res <- loop
              .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
              .exit
            _ <- eventually(tenSec)(
              sink.get.map(
                _.exists(
                  _.payload == EventPayload.ToolFinished(
                    "call-1",
                    "lookup",
                    ToolOutcome.Timeout
                  )
                )
              )
            )
            envs <- sink.get
            _ <- fiber.interrupt
            free <- adm.availableSlots(SlotKind.Tool)
          yield assertTrue(
            res.isFailure,
            exitCode(res) == Some("RA-DEADLINE"),
            free == 1, // timed-out dispatch released its slot
            envs.exists(
              _.payload == EventPayload.ToolFinished(
                "call-1",
                "lookup",
                ToolOutcome.Timeout
              )
            )
          )
        }
      },
      test(
        "unknown tool → documented ToolFinished(Failed(\"refused: unknown tool\")), no ToolStarted"
      ) {
        for
          backend <- ScriptedSequenceBackend(
            oneToolScript("nope"),
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.TextDelta(a, "recovered"),
              ModelEvent.Finished(a, "stop")
            )
          )
          adm <- Admission.make(limits())
          hub <- EventHub.make()
          root = AgentLoop.newRoot()
          loop = loopWithEvents(backend, registryOf(echoTool), adm, root, hub)
          (_, sink, fiber) <- subscribeDrain(hub)
          answer <- loop
            .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
          _ <- eventually(tenSec)(
            sink.get.map(
              _.exists(
                _.payload == EventPayload.ToolFinished(
                  "call-1",
                  "nope",
                  ToolOutcome.Failed(LoopEvents.refusedUnknownTool)
                )
              )
            )
          )
          envs <- sink.get
          _ <- fiber.interrupt
        yield assertTrue(
          answer == "recovered", // typed feedback, loop continues
          // documented refusal outcome (choice: ToolFinished, not Diagnostics)
          envs.exists(
            _.payload == EventPayload.ToolFinished(
              "call-1",
              "nope",
              ToolOutcome.Failed(LoopEvents.refusedUnknownTool)
            )
          ),
          // the tool never started: no ToolStarted for the refusal
          !envs.exists {
            case EventEnvelope(
                  _,
                  _,
                  _,
                  _,
                  _,
                  _,
                  EventPayload.ToolStarted(_, t, _)
                ) =>
              t == "nope"
            case _ => false
          }
        )
      } @@ TestAspect.withLiveClock
    ),
    suite("EMIT-03 cancellation, slow consumer, bounded emission")(
      test(
        "cancel mid-emission: clean termination, Cancelled outcome, no lost Terminal after"
      ) {
        for
          toolGate <- Promise.make[Nothing, Unit]
          started <- Ref.make(0)
          backend <- ScriptedSequenceBackend(oneToolScript("lookup"))
          adm <- Admission.make(limits())
          hub <- EventHub.make()
          parked = new Tool:
            def name = "lookup"; def version = 1; def description = ""
            def recovery = RecoveryClass.ReadOnly; def timeoutMs = 30000L
            def capabilities = ToolCapabilities(concurrentSafe = false)
            def invoke(args: String) =
              started.update(_ + 1) *> toolGate.await.as("\"ok\"")
          root = AgentLoop.newRoot()
          loop = loopWithEvents(backend, registryOf(parked), adm, root, hub)
          (_, sink, fiber) <- subscribeDrain(hub)
          runF <- loop
            .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
            .forkDaemon
          // deterministic: the tool is inside dispatch (after ToolStarted)
          _ <- eventually(tenSec)(started.get.map(_ == 1))
          _ <- runF.interrupt
          exit <- runF.await
          // job-layer equivalent AFTER the cancel: the Terminal must survive
          _ <- hub.publish(
            job,
            root,
            sess,
            EventPayload.Terminal(JobStatus.Interrupted)
          )
          _ <- eventually(tenSec)(
            sink.get.map(
              _.exists(
                _.payload == EventPayload.Terminal(JobStatus.Interrupted)
              )
            )
          )
          envs <- sink.get
          ordered = envs.sortBy(_.seq)
          _ <- fiber.interrupt
          snap <- hub.snapshot(job)
          modelFree <- adm.availableSlots(SlotKind.Model)
          toolFree <- adm.availableSlots(SlotKind.Tool)
        yield assertTrue(
          exit.isInterrupted, // clean termination, not a hang or a typed error
          modelFree == 1 && toolFree == 1, // no permit leak under cancel
          // honest Cancelled outcome was emitted before termination
          ordered.exists(
            _.payload == EventPayload.ToolFinished(
              "call-1",
              "lookup",
              ToolOutcome.Cancelled
            )
          ),
          // exactly one Terminal, LAST by seq; snapshot is terminal and
          // consistent with the delivered stream
          ordered.count(_.payload.isInstanceOf[EventPayload.Terminal]) == 1,
          ordered.last.payload == EventPayload.Terminal(JobStatus.Interrupted),
          snap.map(_.status) == Some(JobStatus.Interrupted),
          snap.exists(_.lastSeq == ordered.last.seq)
        )
      } @@ TestAspect.withLiveClock,
      test(
        "stalled consumer: diagnostics path never blocks; drops bounded + counted"
      ) {
        for
          backend <- ScriptedSequenceBackend(twoRoundScript*)
          adm <- Admission.make(limits())
          // minimal buffers: overflow is reachable, still above the loop's
          // per-run event count on the reliable path (3 Waitings < cap 8)
          hub <- EventHub.make(reliableCapacity = 8, diagnosticsCapacity = 4)
          root = AgentLoop.newRoot()
          loop = loopWithEvents(backend, registryOf(echoTool), adm, root, hub)
          (_, stream) <- hub.subscribe(job)
          sink <- Ref.make(Vector.empty[EventEnvelope])
          resume <- Promise.make[Nothing, Unit]
          // deterministic slow consumer: records the first envelope, then
          // stalls until interrupted
          consumer <- stream.runForeach { e =>
            sink.update(_ :+ e) *>
              sink.get.flatMap { v =>
                if v.length == 1 then resume.await
                else ZIO.unit
              }
          }.fork
          t0 = System.currentTimeMillis()
          // the run must complete while the consumer stalls after its first
          // envelope — a slow consumer never blocks the loop (P1.C invariant)
          answer <- loop
            .runText(
              List(RequestMessage("user", "task")),
              lims(),
              maxRounds = 4
            )
            .timeout(ZDuration.fromSeconds(30)) // hang → typed failure
          elapsed = System.currentTimeMillis() - t0
          // deterministic afterwards: the first envelope arrived and the
          // consumer is stalled (stuck on resume.await) for the flood below
          _ <- eventually(tenSec)(sink.get.map(_.length >= 1))
          // diagnostics overflow mechanics stay bounded: the flood below is
          // non-blocking by construction; a hang would blow the bound
          floodDone <- ZIO
            .foreachDiscard(1 to 3000)(i =>
              hub.publish(
                job,
                root,
                sess,
                EventPayload.Diagnostics(s"flood $i")
              )
            )
            .timeout(ZDuration.fromSeconds(10))
          dropped <- hub.droppedDiagnostics
          _ <- consumer.interrupt
        yield assertTrue(
          answer.contains(
            "final answer"
          ), // slow consumer did not block the run
          elapsed < 20000L, // generous bound: no wait even approaches it
          floodDone.isDefined, // 3000 diagnostics publishes are bounded
          dropped > 0L // overflow is counted, never silent
        )
      } @@ TestAspect.withLiveClock,
      test(
        "reliable Backpressure on publish never breaks the run (failure absorbed)"
      ) {
        // worst case for the RELIABLE seams: every publish fails with the
        // typed Backpressure outcome of the bounded wait
        val failing = new EventHub:
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
          def snapshot(
              jobId: JobId
          ): ZIO[Any, Nothing, Option[SessionSnapshot]] = ZIO.succeed(None)
          def droppedDiagnostics: ZIO[Any, Nothing, Long] = ZIO.succeed(0L)
        for
          backend <- ScriptedSequenceBackend(twoRoundScript*)
          adm <- Admission.make(limits())
          root = AgentLoop.newRoot()
          loop = AgentLoop(
            backend,
            registryOf(echoTool),
            "m",
            adm,
            root,
            events = Some(LoopEvents(failing, job, root, sess))
          )
          answer <- loop.runText(
            List(RequestMessage("user", "task")),
            lims(),
            maxRounds = 4
          )
        yield assertTrue(answer == "final answer")
      }
    )
  )

end AgentLoopEmissionSpec
