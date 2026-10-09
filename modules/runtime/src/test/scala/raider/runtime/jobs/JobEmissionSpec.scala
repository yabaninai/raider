package raider.runtime.jobs

import raider.core.*
import raider.runtime.events.{
  EventEnvelope,
  EventError,
  EventHub,
  EventPayload,
  SessionReducer,
  SessionSnapshot
}
import zio.test.{live, *}
import zio.stream.ZStream
import zio.{Duration as ZDuration, Exit as ZExit, Fiber, Promise, Ref, ZIO}

/** RAI-059 acceptance (EMJ-01..03, ADR-024 §2.6/§6): JobManager transition
  * emission through the [[StatusLedger]] adapter into the REAL EventHub.
  *
  * Determinism rules (same discipline as EventHubSpec/AgentLoopEmissionSpec):
  *   - the manager's hook fires SYNCHRONOUSLY after each committed transition,
  *     and the next transition of one job cannot commit before the previous
  *     hook returned (handle published after the Running hook; the cancel
  *     interrupt issued after the Cancelling hook; the watcher settle only
  *     after the interrupt) — so per-job emission order equals commit order,
  *     and payload sequences are asserted exactly;
  *   - subscriber arrival order is normalized by sorting on `seq` (the hub
  *     relays reliable envelopes by one FIFO forwarder, so they also arrive in
  *     seq order);
  *   - the only real waits are `eventually` outer bounds around logically
  *     guaranteed delivery conditions.
  */
object JobEmissionSpec extends ZIOSpecDefault:

  private val root = RootId("j-root")

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

  /** Subscribe (no loss window) and drain eagerly into a Ref. */
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

  /** A hub whose publish ALWAYS fails with the typed Backpressure outcome — the
    * worst case for failure absorption (EMIT-03 style, RAI-058).
    */
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

  private val tenSec = ZDuration.fromSeconds(10)

  def spec = suite("JobManager/budget/delegation emission RAI-059")(
    suite("EMJ-01 transitions emit without loss, per-job seq order is correct")(
      test(
        "EMJ-01 cancel path: exact chain Queued→Running→Cancelling→Terminal(Cancelled), exactly one Terminal"
      ) {
        for
          hub <- EventHub.make()
          manager <- JobManager.make(
            onTransition = Some(StatusLedger.onTransition(hub, root))
          )
          gate <- Promise.make[Nothing, Unit]
          job <- manager.start(root, "chain", Task(gate.await.as("v")))
          (snap0, sink, fiber) <- subscribeDrain(hub, job.id)
          c <- manager.cancel(job)
          v <- manager.await(job).exit
          _ <- eventually(tenSec)(
            sink.get.map(
              _.exists(_.payload.isInstanceOf[EventPayload.Terminal])
            )
          )
          envs <- sink.get
          _ <- fiber.interrupt
          snap <- hub.snapshot(job.id)
        yield
          val ordered = envs.sortBy(_.seq)
          assertTrue(
            c,
            v.isFailure,
            // subscribed AFTER start: the Queued→Running publish committed
            // before the handle was published — it is in the snapshot
            snap0.lastSeq == 1L,
            snap0.status == JobStatus.Running,
            ordered.map(_.payload) == Vector(
              EventPayload.JobStatusChanged(
                JobStatus.Running,
                JobStatus.Cancelling,
                None
              ),
              EventPayload.Terminal(JobStatus.Cancelled)
            ),
            ordered.map(_.seq) == Vector(2L, 3L),
            // exactly one Terminal, LAST by seq
            ordered.count(_.payload.isInstanceOf[EventPayload.Terminal]) == 1,
            snap.map(_.status) == Some(JobStatus.Cancelled),
            snap.exists(_.lastSeq == 3L)
          )
      } @@ TestAspect.withLiveClock,
      test(
        "EMJ-01 success path: exact chain Queued→Running→Terminal(Succeeded)"
      ) {
        for
          hub <- EventHub.make()
          manager <- JobManager.make(
            onTransition = Some(StatusLedger.onTransition(hub, root))
          )
          gate <- Promise.make[Nothing, Unit]
          // parked at the gate: at subscribe time the status is provably
          // Running (seq 1), no terminal yet
          job <- manager.start(root, "quick", Task(gate.await.as("done")))
          (snap0, sink, fiber) <- subscribeDrain(hub, job.id)
          _ <- gate.succeed(())
          v <- manager.await(job)
          _ <- eventually(tenSec)(
            sink.get.map(
              _.exists(_.payload.isInstanceOf[EventPayload.Terminal])
            )
          )
          envs <- sink.get
          _ <- fiber.interrupt
          snap <- hub.snapshot(job.id)
        yield
          val ordered = envs.sortBy(_.seq)
          assertTrue(
            v == "done",
            snap0.lastSeq == 1L,
            snap0.status == JobStatus.Running,
            ordered.map(_.payload) == Vector(
              EventPayload.Terminal(JobStatus.Succeeded)
            ),
            ordered.map(_.seq) == Vector(2L),
            ordered.forall(e =>
              e.jobId == job.id && e.rootId == root &&
                e.sessionId == StatusLedger.sessionId
            ),
            snap.map(_.status) == Some(JobStatus.Succeeded),
            snap.exists(_.lastSeq == 2L)
          )
      } @@ TestAspect.withLiveClock,
      test(
        "EMJ-01 cancel/success race: 100 runs — no missing events, seq 1..n gap-free, valid canTransition chain, single last Terminal"
      ) {
        def raceOnce(i: Int): ZIO[Any, String, Unit] =
          for
            hub <- EventHub.make()
            manager <- JobManager.make(
              onTransition = Some(StatusLedger.onTransition(hub, root))
            )
            // parked at the gate: the subscribe boundary is deterministic
            // (Running, seq 1), and ONLY after it is opened do cancel and
            // success genuinely race
            gate <- Promise.make[Nothing, Unit]
            job <- manager.start(
              root,
              s"race-$i",
              Task(gate.await.as(s"v$i"))
            )
            (snap0, sink, fiber) <- subscribeDrain(hub, job.id)
            _ <- manager.cancel(job).fork
            _ <- gate.succeed(()).fork
            v <- manager.await(job).exit.timeout(ZDuration.fromSeconds(5))
            _ <- eventually(tenSec)(
              sink.get.map(
                _.exists(_.payload.isInstanceOf[EventPayload.Terminal])
              )
            )
            envs <- sink.get
            _ <- fiber.interrupt
            snap <- hub.snapshot(job.id)
          yield
            val streamOrdered = envs.sortBy(_.seq)
            // full per-job chain: snapshot seq 1 (Queued→Running) + stream
            val chain: Vector[EventPayload] =
              Vector(
                EventPayload.JobStatusChanged(
                  JobStatus.Queued,
                  JobStatus.Running,
                  None
                )
              ) ++ streamOrdered.map(_.payload)
            val seqs = snap0.lastSeq +: streamOrdered.map(_.seq)
            val n = seqs.size
            val gapFree = seqs == (1L to n).toVector
            val terminals = chain.collect { case t: EventPayload.Terminal =>
              t
            }
            val validChain = chain.zipWithIndex.forall { case (p, idx) =>
              val prevTo: JobStatus =
                if idx == 0 then JobStatus.Queued
                else
                  chain(idx - 1) match
                    case EventPayload.JobStatusChanged(_, t, _) => t
                    case EventPayload.Terminal(t)               => t
                    case _ => JobStatus.Queued
              p match
                case EventPayload.JobStatusChanged(f, t, _) =>
                  f == prevTo && JobStatus.canTransition(f, t)
                case EventPayload.Terminal(t) =>
                  prevTo != JobStatus.Queued &&
                  JobStatus.canTransition(prevTo, t)
                case _ => false
            }
            val terminalLast = chain.last.isInstanceOf[EventPayload.Terminal]
            val outcomeConsistent = v match
              case None => false // lost waiter
              case Some(ZExit.Success(_)) =>
                snap.exists(_.status == JobStatus.Succeeded)
              case Some(ZExit.Failure(cause)) =>
                val code = cause.failures.headOption.map(_.code)
                code match
                  case Some("RA-CANCEL") =>
                    snap.exists(_.status == JobStatus.Cancelled)
                  case Some(_) => snap.exists(_.status == JobStatus.Failed)
                  case None    => false
            if v.isEmpty then ZIO.fail(s"lost waiter at iteration $i")
            else if !gapFree then ZIO.fail(s"seq gap at iteration $i: $seqs")
            else if terminals.size != 1 || !terminalLast then
              ZIO.fail(
                s"Terminal invariant broken at $i: $terminals last=$terminalLast"
              )
            else if !validChain then
              ZIO.fail(s"invalid transition chain at $i: $chain")
            else if !outcomeConsistent then
              ZIO.fail(
                s"inconsistent outcome at $i: v=$v status=${snap.map(_.status)}"
              )
            else ZIO.unit
        (0 until 100)
          .foldLeft(ZIO.succeed(0): ZIO[Any, String, Int]) { (acc, i) =>
            acc.flatMap(_ => raceOnce(i).as(i + 1))
          }
          .map(done => assertTrue(done == 100))
      } @@ TestAspect.withLiveClock
    ),
    suite("EMJ-02 the hook never masks errors, never changes canTransition")(
      test(
        "EMJ-02 hook-absent parity: identical outcomes with and without a hook"
      ) {
        def scenario(
            withHook: Boolean
        ): ZIO[
          Any,
          String,
          (Boolean, Option[String], JobStatus, Int, Boolean)
        ] =
          for
            hub <- EventHub.make()
            manager <-
              if withHook then
                JobManager.make(
                  onTransition = Some(StatusLedger.onTransition(hub, root))
                )
              else JobManager.make()
            gate <- Promise.make[Nothing, Unit]
            job <- manager.start(
              root,
              "parity",
              Task(gate.await.as("late-success"))
            )
            _ <- manager.cancel(job) // commits Cancelling, interrupts the task
            // (cancel awaits the interrupt): deterministically drain the real
            // watcher's settle BEFORE the synthetic one, so the late-observation
            // counter is race-free (the watcher's settle is the Cancelling→
            // Cancelled TRANSITION; the synthetic one is the late observation)
            _ <- eventually(tenSec)(
              manager
                .snapshot(job)
                .map(s => JobStatus.terminal.contains(s.status))
            )
            synthetic <- manager
              .settleForTest(job, ZExit.succeed("late-success"))
              .exit // deterministically a no-op late observation (promise already settled)
            syntheticLate = synthetic.isSuccess
            awaited <- manager.await(job).exit
            snap <- manager.snapshot(job)
          yield
            val code = awaited match
              case ZExit.Failure(cause) => cause.failures.headOption.map(_.code)
              case ZExit.Success(_)     => None
            (
              awaited.isFailure,
              code,
              snap.status,
              snap.lateObservations,
              syntheticLate
            )
        for
          bare <- scenario(withHook = false)
          wired <- scenario(withHook = true)
        yield assertTrue(
          bare == wired,
          wired == ((true, Some("RA-CANCEL"), JobStatus.Cancelled, 1, true))
        )
      } @@ TestAspect.withLiveClock,
      test(
        "EMJ-02 invalid/unregistered keep their typed errors with a hook attached"
      ) {
        for
          hub <- EventHub.make()
          manager <- JobManager.make(
            onTransition = Some(StatusLedger.onTransition(hub, root))
          )
          job <- manager.start(root, "edge", Task.succeed(7))
          _ <- manager.await(job)
          bad <- manager
            .debugTransition(job, JobStatus.Running)
            .exit // Succeeded -> Running: still a typed JobsError
          badTyped = bad match
            case ZExit.Failure(cause) =>
              cause.failures.headOption.exists(
                _.getClass.getSimpleName == "InvalidTransition"
              )
            case ZExit.Success(_) => false
          unknown <- manager.snapshotById(JobId("nope")).exit
          unknownTyped = unknown match
            case ZExit.Failure(_) => true
            case ZExit.Success(_) => false
          // a failing task surfaces its typed error untouched
          boom <- manager.start(
            root,
            "boom",
            Task.fail(RaiderError.ProviderAuth("401"))
          )
          boomRes <- manager.await(boom).exit
          boomCode = boomRes match
            case ZExit.Failure(cause) =>
              cause.failures.headOption.map(_.code)
            case ZExit.Success(_) => None
          boomSnap <- manager.snapshot(boom)
          // canTransition is unchanged: late cancel on a terminal job is still false
          lateCancel <- manager.cancel(job)
        yield assertTrue(
          bad.isFailure && badTyped,
          unknown.isFailure && unknownTyped,
          boomCode == Some("RA-AUTH"),
          boomSnap.status == JobStatus.Failed,
          lateCancel == false
        )
      },
      test(
        "EMJ-02 a defecting hook never masks the typed outcome or the cancel result"
      ) {
        val defecting: OnTransition =
          (_, _, _) => ZIO.dieMessage("listener defect")
        for
          manager <- JobManager.make(onTransition = Some(defecting))
          job <- manager.start(root, "safe", Task.succeed("v"))
          v <- manager.await(job)
          snap <- manager.snapshot(job)
          lateCancel <- manager.cancel(job)
          blockedGate <- Promise.make[Nothing, Unit]
          blocked <- manager.start(
            root,
            "blocked",
            Task(blockedGate.await.as("never"))
          )
          c <- manager.cancel(blocked)
          res <- manager.await(blocked).exit
          code = res match
            case ZExit.Failure(cause) => cause.failures.headOption.map(_.code)
            case ZExit.Success(_)     => None
        yield assertTrue(
          v == "v",
          snap.status == JobStatus.Succeeded,
          lateCancel == false,
          c,
          code == Some("RA-CANCEL")
        )
      } @@ TestAspect.withLiveClock,
      test(
        "EMJ-02 a failing hub (always Backpressure) never breaks the lifecycle"
      ) {
        for
          manager <- JobManager.make(
            onTransition = Some(StatusLedger.onTransition(failingHub, root))
          )
          job <- manager.start(root, "ok", Task.succeed("v"))
          v <- manager.await(job)
          snap <- manager.snapshot(job)
          gate <- Promise.make[Nothing, Unit]
          blocked <- manager.start(root, "blk", Task(gate.await.as("x")))
          c <- manager.cancel(blocked)
          res <- manager.await(blocked).exit
          code = res match
            case ZExit.Failure(cause) => cause.failures.headOption.map(_.code)
            case ZExit.Success(_)     => None
          blockedSnap <- manager.snapshot(blocked)
        yield assertTrue(
          v == "v",
          snap.status == JobStatus.Succeeded,
          c,
          code == Some("RA-CANCEL"),
          blockedSnap.status == JobStatus.Cancelled
        )
      } @@ TestAspect.withLiveClock
    ),
    suite(
      "EMJ-03 cancellation matrix green with a hook; the hook does not block"
    )(
      test(
        "EMJ-03 cancellation matrix (4 scenarios) x 3 runs with a hook attached"
      ) {
        def matrixOnce: ZIO[Any, Any, (Boolean, Boolean, Boolean, Boolean)] =
          for
            hub <- EventHub.make()
            manager <- JobManager.make(
              onTransition = Some(StatusLedger.onTransition(hub, root))
            )
            // a) success already happened -> cancel false, outcome stays
            done <- manager.start(root, "done-first", Task.succeed("v"))
            dv <- manager.await(done)
            cl <- manager.cancel(done)
            doneSnap <- manager.snapshot(done)
            // b) cancel first -> await fails Cancelled
            gate <- Promise.make[Nothing, Unit]
            blocked <- manager.start(
              root,
              "blocked",
              Task(gate.await.as("never"))
            )
            c2 <- manager.cancel(blocked)
            awaitRes <- manager.await(blocked).exit
            blockedSn <- manager.snapshot(blocked)
            _ <- gate.succeed(())
            cancelCode = awaitRes match
              case ZExit.Failure(cause) =>
                cause.failures.headOption.map(_.code)
              case ZExit.Success(_) => None
            // c) cancel-wins deterministic (F-1 style): settle after Cancelling
            gate2 <- Promise.make[Nothing, Unit]
            cw <- manager.start(
              root,
              "cancel-wins",
              Task(gate2.await.as("late-success"))
            )
            _ <- manager.cancel(cw)
            _ <- manager.settleForTest(cw, ZExit.succeed("late-success"))
            cwRes <- manager.await(cw).exit
            cwSnap <- manager.snapshot(cw)
            // d) concurrent race x20: await always resolves, outcome consistent
            raceOk <- (0 until 20).foldLeft(ZIO.succeed(true)) { (acc, i) =>
              acc.flatMap { ok =>
                if !ok then ZIO.succeed(false)
                else
                  (for
                    job <- manager.start(
                      root,
                      s"race-$i",
                      Task(ZIO.yieldNow.as(s"v$i"))
                    )
                    _ <- manager.cancel(job).fork
                    res <- manager
                      .await(job)
                      .exit
                      .timeout(ZDuration.fromSeconds(5))
                    snap <- manager.snapshot(job)
                  yield (res, snap)).map { (res, snap) =>
                    res match
                      case None => false
                      case Some(ZExit.Success(_)) =>
                        snap.status == JobStatus.Succeeded
                      case Some(ZExit.Failure(_)) =>
                        snap.status == JobStatus.Cancelled ||
                        snap.status == JobStatus.Failed
                  }
              }
            }
          yield (
            dv == "v" && !cl && doneSnap.status == JobStatus.Succeeded,
            c2 && cancelCode == Some("RA-CANCEL") &&
              blockedSn.status == JobStatus.Cancelled,
            cwRes.isFailure && cwSnap.status == JobStatus.Cancelled &&
              cwSnap.lateObservations >= 1,
            raceOk
          )
        for
          r1 <- matrixOnce
          r2 <- matrixOnce
          r3 <- matrixOnce
        yield assertTrue(
          r1 == (true, true, true, true),
          r2 == (true, true, true, true),
          r3 == (true, true, true, true)
        )
      } @@ TestAspect.withLiveClock,
      test(
        "EMJ-03 a stalled consumer never blocks settle/await (publishers are not in the forwarder's stall)"
      ) {
        for
          hub <- EventHub.make()
          manager <- JobManager.make(
            onTransition = Some(StatusLedger.onTransition(hub, root))
          )
          gate <- Promise.make[Nothing, Unit]
          job <- manager.start(root, "stall", Task(gate.await.as("x")))
          (_, stream) <- hub.subscribe(job.id)
          sink <- Ref.make(Vector.empty[EventEnvelope])
          resume <- Promise.make[Nothing, Unit]
          consumer <- stream.runForeach { e =>
            sink.update(_ :+ e) *>
              sink.get.flatMap(v =>
                if v.length == 1 then resume.await else ZIO.unit
              )
          }.fork
          t0 = System.currentTimeMillis()
          c <- manager.cancel(job)
          res <- manager
            .await(job)
            .exit
            .timeout(ZDuration.fromSeconds(30)) // hang -> typed failure
          elapsed = System.currentTimeMillis() - t0
          snap <- manager.snapshot(job)
          _ <- consumer.interrupt
        yield assertTrue(
          c,
          res.isDefined,
          res.exists(_.isFailure),
          res.exists {
            case ZExit.Failure(cause) =>
              cause.failures.headOption.exists(_.code == "RA-CANCEL")
            case _ => false
          },
          snap.status == JobStatus.Cancelled,
          elapsed < 20000L // generous bound: no wait approaches it
        )
      } @@ TestAspect.withLiveClock,
      test(
        "EMJ-03 cancel during emission: a slow Terminal publish loses nothing, await resolves after it"
      ) {
        for
          inner <- EventHub.make()
          gate <- Promise.make[Nothing, Unit]
          // Terminal publishes block on the gate; everything else is immediate
          hub = new EventHub:
            def publish(
                jobId: JobId,
                rootId: RootId,
                sessionId: SessionId,
                payload: EventPayload
            ): ZIO[Any, EventError, Unit] =
              payload match
                case EventPayload.Terminal(_) =>
                  gate.await *> inner.publish(jobId, rootId, sessionId, payload)
                case other =>
                  inner.publish(jobId, rootId, sessionId, other)
            def subscribe(jobId: JobId) = inner.subscribe(jobId)
            def snapshot(jobId: JobId) = inner.snapshot(jobId)
            def droppedDiagnostics = inner.droppedDiagnostics
          manager <- JobManager.make(
            onTransition = Some(StatusLedger.onTransition(hub, root))
          )
          taskGate <- Promise.make[Nothing, Unit]
          job <- manager.start(
            root,
            "slow-term",
            Task(taskGate.await.as("x"))
          )
          (_, sink, fiber) <- subscribeDrain(hub, job.id)
          c <- manager.cancel(job) // Cancelling publishes immediately; the
          // watcher's settle then blocks inside the Terminal publish
          _ <- gate.succeed(()) // release the emission
          v <- manager
            .await(job)
            .exit
            .timeout(ZDuration.fromSeconds(30))
          _ <- eventually(tenSec)(
            sink.get.map(
              _.exists(_.payload.isInstanceOf[EventPayload.Terminal])
            )
          )
          envs <- sink.get
          _ <- fiber.interrupt
          snap <- inner.snapshot(job.id)
        yield
          val ordered = envs.sortBy(_.seq)
          val terminals = ordered.collect {
            case e if e.payload.isInstanceOf[EventPayload.Terminal] => e
          }
          assertTrue(
            c,
            v.isDefined,
            v.exists {
              case ZExit.Failure(cause) =>
                cause.failures.headOption.exists(_.code == "RA-CANCEL")
              case _ => false
            },
            // the Terminal survived the slow emission: exactly one, last
            terminals.size == 1,
            ordered.last.payload == EventPayload.Terminal(JobStatus.Cancelled),
            snap.exists(_.status == JobStatus.Cancelled),
            snap.exists(_.lastSeq == ordered.last.seq)
          )
      } @@ TestAspect.withLiveClock
    )
  )

end JobEmissionSpec
