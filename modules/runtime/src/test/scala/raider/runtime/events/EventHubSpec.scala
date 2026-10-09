package raider.runtime.events

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.budget.MicroUsd
import raider.runtime.jobs.JobStatus
import zio.test.{live, *}
import zio.stream.ZStream
import zio.{Duration, Fiber, IO, Promise, Ref, UIO, ZIO}

import scala.util.Try

/** RAI-055.b-ii: EventHub acceptance EVT-01..04 (ADR-024 §2.3/§6, m1.md
  * RAI-055).
  *
  * Determinism rules:
  *   - coordination only via Promise/Ref primitives (latch-style), never by
  *     sleeping until a race resolves;
  *   - real durations appear only as generous outer bounds of logically
  *     guaranteed conditions (`eventually`, flood budgets) or as the hub's own
  *     real behavior (the 5s reliable bounded wait behind
  *     `EventError.Backpressure`) — no assertion inspects a timing;
  *   - drop/delivery accounting asserts conservation (`published = delivered +
  *     dropped + buffered`), which guarantees drops whenever the flood exceeds
  *     the total absorbable capacity (subscriber queue 1024 + class buffer),
  *     independent of scheduler speed;
  *   - cross-class delivery order at a subscriber is deliberately unspecified
  *     (ADR-024 §2.3 orders per class), so stream folds sort by `seq` and
  *     compare content sets, never arrival order across classes.
  */
object EventHubSpec extends ZIOSpecDefault:

  private val job = JobId("evt-job")
  private val root = RootId("evt-root")
  private val sess = SessionId("evt-sess")

  private val flood = 5000

  /** Await a logical condition that is guaranteed reachable, polling with real
    * sleeps under a generous outer bound; the bound only converts a (defective)
    * hang into a typed failure.
    */
  private def eventually(
      max: Duration
  )(cond: UIO[Boolean]): ZIO[Any, String, Unit] =
    ZIO.suspendSucceed {
      val deadline = System.nanoTime() + max.toNanos
      def loop: ZIO[Any, String, Unit] =
        cond.flatMap {
          case true => ZIO.unit
          case false =>
            if System.nanoTime() >= deadline then
              ZIO.fail("condition not met within the generous outer bound")
            else ZIO.sleep(Duration.fromMillis(20)) *> loop
        }
      loop
    }

  private def publish(
      hub: EventHub,
      payload: EventPayload,
      jid: JobId = job
  ): IO[EventError, Unit] =
    hub.publish(jid, root, sess, payload)

  private def diag(text: String): EventPayload = EventPayload.Diagnostics(text)

  private def collector: UIO[Ref[Vector[EventEnvelope]]] =
    Ref.make(Vector.empty[EventEnvelope])

  private def drainInto(
      stream: ZStream[Any, Nothing, EventEnvelope],
      ref: Ref[Vector[EventEnvelope]]
  ): UIO[Fiber.Runtime[Nothing, Unit]] =
    stream.runForeach(e => ref.update(_ :+ e)).fork

  /** Consumer that records events, signals after taking the first one and then
    * stalls until `resume` is completed — a slow consumer, deterministically.
    */
  private def stallAfterFirst(
      stream: ZStream[Any, Nothing, EventEnvelope],
      ref: Ref[Vector[EventEnvelope]],
      firstTaken: Promise[Nothing, Unit],
      resume: Promise[Nothing, Unit]
  ): UIO[Fiber.Runtime[Nothing, Unit]] =
    stream.runForeach { e =>
      ref.update(_ :+ e) *>
        ref.get.flatMap { v =>
          if v.length == 1 then firstTaken.succeed(()) *> resume.await
          else ZIO.unit
        }
    }.fork

  private def isDiagnostics(e: EventEnvelope): Boolean = e.payload match
    case _: EventPayload.Diagnostics => true
    case _                           => false

  private def isReliable(e: EventEnvelope): Boolean = e.payload match
    case _: EventPayload.Terminal | _: EventPayload.Waiting |
        _: EventPayload.JobStatusChanged | _: EventPayload.BudgetChanged |
        _: EventPayload.RetryScheduled | _: EventPayload.Delegated =>
      true
    case _ => false

  private def isTerminal(e: EventEnvelope): Boolean =
    e.payload.isInstanceOf[EventPayload.Terminal]

  /** Reducer fold over the envelopes ordered by per-job seq (the only order the
    * reducer contract speaks about).
    */
  private def foldEnvelopes(
      es: Vector[EventEnvelope]
  ): IO[EventError, SessionSnapshot] =
    es.sortBy(_.seq)
      .foldLeft(
        ZIO.succeed(SessionReducer.initial(job, root, sess)): IO[
          EventError,
          SessionSnapshot
        ]
      )((acc, e) => acc.flatMap(SessionReducer.reduce(_, e)))

  private def untilOneTo(n: Long): Vector[Long] = (1L to n).toVector

  /** Wall-clock guard around a whole test body (live clock, generous outer
    * bound): a runaway test fails typed instead of hanging the suite. It never
    * asserts anything about timing of passing runs.
    */
  private def guarded(
      zio: ZIO[Any, Any, TestResult]
  ): ZIO[Any, Any, TestResult] =
    zio.timeout(Duration.fromSeconds(120)).flatMap {
      case Some(result) => ZIO.succeed(result)
      case None =>
        ZIO.fail(
          "test exceeded the 120s wall guard — hang or deadlock, not a timing assertion"
        )
    }

  /** EVT-01 scripted stream (m1.md RAI-055): JobStatusChanged → Waiting →
    * ToolStarted → ToolProgress → ModelCallFinished → Terminal. Mixed delivery
    * classes on purpose (status events are RELIABLE, tool/model events are
    * DIAGNOSTICS).
    */
  private val script: Vector[EventPayload] = Vector(
    EventPayload.JobStatusChanged(JobStatus.Queued, JobStatus.Running, None),
    EventPayload.Waiting(WaitReason.Slot),
    EventPayload.ToolStarted("c1", "bash", "echo hi"),
    EventPayload.ToolProgress(
      "c1",
      "bash",
      LineTail(Vector("line-1", "line-2"), 0, 0L)
    ),
    EventPayload.ModelCallFinished(
      1,
      100L,
      UsageSnapshot(MicroUsd(700000L), MicroUsd(250000L), unknown = false)
    ),
    EventPayload.Terminal(JobStatus.Succeeded)
  )

  def spec = suite("EventHub (RAI-055.b-ii, EVT-01..04)")(
    test(
      "construction: make(0, 10) and make(10, 0) throw IllegalArgumentException eagerly"
    ) {
      assertTrue(
        Try(EventHub.make(0, 10)).failed.toOption
          .exists(_.isInstanceOf[IllegalArgumentException]),
        Try(EventHub.make(10, 0)).failed.toOption
          .exists(_.isInstanceOf[IllegalArgumentException])
      )
    },
    test(
      "EVT-01: scripted publishes — live subscriber (subscribe before) and snapshot subscriber (subscribe after) both end consistent with the reducer fold"
    ) {
      live {
        guarded {
          for
            hub <- EventHub.make()
            (initial, liveStream) <- hub.subscribe(job)
            liveRef <- collector
            liveFiber <- drainInto(liveStream, liveRef)
            _ <- ZIO.foreachDiscard(script)(p => publish(hub, p))
            _ <- eventually(Duration.fromSeconds(15))(
              liveRef.get.map(_.length == script.length)
            )
            liveEnvelopes <- liveRef.get
            _ <- liveFiber.interrupt
            // snapshot path: subscribe only after everything was published
            (afterSnap, afterStream) <- hub.subscribe(job)
            afterFiber <- afterStream.runDrain.fork
            _ <- afterFiber.interrupt
            folded <- foldEnvelopes(liveEnvelopes).either
            hubSnap <- hub.snapshot(job)
            seqs = liveEnvelopes.map(_.seq).sorted
            result = assertTrue(
              initial.lastSeq == 0L,
              initial.status == JobStatus.Queued,
              seqs == untilOneTo(script.length.toLong),
              liveEnvelopes.forall(e =>
                e.schemaVersion == 1 && e.jobId == job && e.rootId == root && e.sessionId == sess
              ),
              folded.toOption.exists(_ == afterSnap),
              hubSnap == Some(afterSnap),
              afterSnap.status == JobStatus.Succeeded,
              afterSnap.lastSeq == script.length.toLong,
              afterSnap.waitReason.isEmpty,
              // the script has no ToolFinished, so the in-flight tool remains
              // active in the folded state (reducer semantics)
              afterSnap.activeTool == Some(("bash", "c1")),
              afterSnap.progressTail.lines == Vector("line-1", "line-2"),
              afterSnap.usage == UsageSnapshot(
                MicroUsd(700000L),
                MicroUsd(250000L),
                unknown = false
              )
            )
          yield result
        }
      }
    },
    test(
      "EVT-02: 3 subscribers all receive every reliable event; a latch-stalled slow subscriber under a diagnostics flood forces honest drops (droppedDiagnostics > 0)"
    ) {
      live {
        guarded {
          for
            hub <- EventHub.make(64, 4)
            subs <- ZIO.foreach(1 to 3)(_ => hub.subscribe(job))
            refs <- ZIO.foreach(1 to 3)(_ => collector)
            firstTaken <- Promise.make[Nothing, Unit]
            resume <- Promise.make[Nothing, Unit]
            _ <- drainInto(subs(0)._2, refs(0))
            _ <- drainInto(subs(1)._2, refs(1))
            slow <- stallAfterFirst(subs(2)._2, refs(2), firstTaken, resume)
            _ <- publish(hub, diag("warmup"))
            _ <-
              firstTaken.await // slow subscriber is now deterministically stalled
            _ <- ZIO.foreachDiscard(1 to 19)(_ =>
              publish(
                hub,
                EventPayload.JobStatusChanged(
                  JobStatus.Running,
                  JobStatus.Waiting,
                  Some(WaitReason.Slot)
                )
              )
            )
            _ <- ZIO.foreachDiscard(1 to flood)(i =>
              publish(hub, diag(s"flood-$i"))
            )
            _ <- eventually(Duration.fromSeconds(15))(
              hub.droppedDiagnostics.map(_ > 0L)
            )
            _ <- resume.succeed(())
            _ <- publish(hub, EventPayload.Terminal(JobStatus.Succeeded))
            // quiescence: every subscriber drained everything relayed to it
            _ <- ZIO.foreach(refs) { r =>
              eventually(Duration.fromSeconds(20)) {
                for
                  d <- hub.droppedDiagnostics
                  v <- r.get
                yield v.count(isReliable) == 20 &&
                  v.count(isDiagnostics) + d == (flood + 1).toLong
              }
            }
            all <- ZIO.foreach(refs)(_.get)
            dropped <- hub.droppedDiagnostics
            finalSnap <- hub.snapshot(job)
            relVectors = all.map(_.filter(isReliable))
            diagSeqs = all.map(_.filter(isDiagnostics).map(_.seq))
            acceptedTotal = (flood + 1 - dropped) + 20L
            result = assertTrue(
              dropped > 0L,
              relVectors(0) == relVectors(1),
              relVectors(1) == relVectors(2),
              relVectors(0).length == 20,
              relVectors(0).lastOption.exists(isTerminal),
              diagSeqs(0) == diagSeqs(1),
              diagSeqs(1) == diagSeqs(2),
              diagSeqs(0).length + dropped == (flood + 1).toLong,
              finalSnap.exists(s =>
                s.status == JobStatus.Succeeded && s.lastSeq == acceptedTotal
              ),
              all(0).map(_.seq).sorted == untilOneTo(acceptedTotal)
            )
            _ <- slow.interrupt
          yield result
        }
      }
    },
    test(
      "EVT-03: 5000-diagnostics flood onto a stalled consumer — publisher completes fast (generous real bound), droppedDiagnostics == published − delivered, accepted seqs contiguous"
    ) {
      live {
        guarded {
          for
            hub <- EventHub.make(16, 32)
            (_, stream) <- hub.subscribe(job)
            ref <- collector
            firstTaken <- Promise.make[Nothing, Unit]
            resume <- Promise.make[Nothing, Unit]
            fiber <- stallAfterFirst(stream, ref, firstTaken, resume)
            _ <- publish(hub, diag("warmup"))
            _ <- firstTaken.await
            t0 <- ZIO.succeed(System.nanoTime())
            _ <- ZIO.foreachDiscard(1 to flood)(i =>
              publish(hub, diag(s"f-$i"))
            )
            t1 <- ZIO.succeed(System.nanoTime())
            elapsedMs = (t1 - t0) / 1000000L
            _ <- eventually(Duration.fromSeconds(15))(
              hub.droppedDiagnostics.map(_ > 0L)
            )
            _ <- resume.succeed(())
            _ <- eventually(Duration.fromSeconds(20)) {
              for
                d <- hub.droppedDiagnostics
                v <- ref.get
              yield v.length + d == (flood + 1).toLong
            }
            envelopes <- ref.get
            dropped <- hub.droppedDiagnostics
            last <- hub.snapshot(job).map(_.map(_.lastSeq))
            seqs = envelopes.map(_.seq)
            _ <- fiber.interrupt
            result = assertTrue(
              elapsedMs < 5000L,
              dropped > 0L,
              dropped == (flood + 1).toLong - envelopes.length,
              seqs.sorted == untilOneTo(flood + 1L - dropped),
              last == Some(flood + 1L - dropped)
            )
          yield result
        }
      }
    },
    test(
      "EVT-03: full reliable buffer → next reliable publish fails EventError.Backpressure; after the consumer is released publish succeeds again with the next sequence (no gap from the timed-out publish)"
    ) {
      live {
        guarded {
          for
            hub <- EventHub.make(2, 8)
            (_, stream) <- hub.subscribe(job)
            ref <- collector
            firstTaken <- Promise.make[Nothing, Unit]
            resume <- Promise.make[Nothing, Unit]
            fiber <- stallAfterFirst(stream, ref, firstTaken, resume)
            _ <- publish(hub, diag("warmup"))
            _ <- firstTaken.await
            maybeBp <- ZIO
              .iterate(0)(_ < 1200) { n =>
                publish(hub, EventPayload.Waiting(WaitReason.Budget)).as(n + 1)
              }
              .foldZIO(
                err => ZIO.succeed(Some(err)),
                _ => ZIO.succeed(Option.empty[EventError])
              )
            lastAtBp <- hub.snapshot(job).map(_.map(_.lastSeq))
            accepted = lastAtBp.getOrElse(0L) - 1L // warmup consumed seq 1
            _ <- resume.succeed(())
            _ <- eventually(Duration.fromSeconds(20))(
              ref.get.map(_.length == accepted + 1)
            )
            _ <- publish(hub, EventPayload.Waiting(WaitReason.Slot))
            _ <- eventually(Duration.fromSeconds(15))(
              ref.get.map(_.length == accepted + 2)
            )
            envelopes <- ref.get
            last <- hub.snapshot(job).map(_.map(_.lastSeq))
            seqs = envelopes.map(_.seq).sorted
            _ <- fiber.interrupt
          yield assertTrue(
            accepted > 0L,
            maybeBp == Some(EventError.Backpressure(job, 2)),
            seqs == untilOneTo(accepted + 2L),
            last == Some(accepted + 2L)
          )
        }
      }
    },
    test(
      "EVT-04: 4 fibers × 25 publishes of one job — union of received seqs is exactly 1..100, each once"
    ) {
      live {
        guarded {
          for
            hub <- EventHub.make(64, 256)
            (_, stream) <- hub.subscribe(job)
            ref <- collector
            fiber <- drainInto(stream, ref)
            _ <- ZIO.foreachParDiscard(1 to 4) { f =>
              ZIO.foreachDiscard(1 to 25)(i => publish(hub, diag(s"f$f-i$i")))
            }
            _ <- eventually(Duration.fromSeconds(15))(
              ref.get.map(_.length == 100)
            )
            envelopes <- ref.get
            last <- hub.snapshot(job).map(_.map(_.lastSeq))
            seqs = envelopes.map(_.seq).sorted
            payloads = envelopes.map(_.payload)
            _ <- fiber.interrupt
          yield assertTrue(
            seqs == untilOneTo(100L),
            payloads.distinct.length == 100,
            payloads.forall {
              case _: EventPayload.Diagnostics => true
              case _                           => false
            },
            last == Some(100L)
          )
        }
      }
    },
    test(
      "EVT-04: subscriber cancels mid-stream while Terminal publishes — a NEW subscribe yields the snapshot with terminal status"
    ) {
      live {
        guarded {
          for
            hub <- EventHub.make(64, 64)
            (_, stream) <- hub.subscribe(job)
            ref <- collector
            firstTaken <- Promise.make[Nothing, Unit]
            resume <- Promise.make[Nothing, Unit]
            fiber <- stallAfterFirst(stream, ref, firstTaken, resume)
            _ <- ZIO.foreachDiscard(1 to 3)(i => publish(hub, diag(s"pre-$i")))
            _ <- firstTaken.await
            _ <- ZIO.foreachDiscard(1 to 2)(i => publish(hub, diag(s"mid-$i")))
            _ <- publish(hub, EventPayload.Terminal(JobStatus.Succeeded))
            _ <-
              fiber.interrupt // cancel mid-stream, after Terminal was published
            (snap, nextStream) <- hub.subscribe(job)
            nextFiber <- nextStream.runDrain.fork
            _ <- nextFiber.interrupt
          yield assertTrue(
            snap.status == JobStatus.Succeeded,
            snap.waitReason.isEmpty,
            snap.lastSeq == 6L
          )
        }
      }
    },
    test(
      "EVT-04: diagnostics publishes with a stuck consumer complete within a 2s real bound and reliable publishes still succeed — publishers are never blocked by slow consumers"
    ) {
      live {
        guarded {
          for
            hub <- EventHub.make(8, 4)
            (_, stream) <- hub.subscribe(job)
            ref <- collector
            firstTaken <- Promise.make[Nothing, Unit]
            resume <- Promise.make[Nothing, Unit]
            fiber <- stallAfterFirst(stream, ref, firstTaken, resume)
            _ <- publish(hub, diag("warmup"))
            _ <- firstTaken.await
            t0 <- ZIO.succeed(System.nanoTime())
            _ <- ZIO.foreachDiscard(1 to 3000)(i =>
              publish(hub, diag(s"flood-$i"))
            )
            _ <- ZIO.foreachDiscard(1 to 5)(i =>
              publish(
                hub,
                EventPayload.BudgetChanged(
                  MicroUsd(100L * i),
                  MicroUsd(i.toLong),
                  0L
                )
              )
            )
            t1 <- ZIO.succeed(System.nanoTime())
            elapsedMs = (t1 - t0) / 1000000L
            droppedNow <- hub.droppedDiagnostics
            _ <- resume.succeed(())
            _ <- eventually(Duration.fromSeconds(20)) {
              for
                d <- hub.droppedDiagnostics
                v <- ref.get
              yield v.count(isDiagnostics) + d == 3001L &&
                v.count(isReliable) == 5
            }
            envelopes <- ref.get
            dropped <- hub.droppedDiagnostics
            last <- hub.snapshot(job).map(_.map(_.lastSeq))
            seqs = envelopes.map(_.seq).sorted
            _ <- fiber.interrupt
          yield assertTrue(
            elapsedMs < 2000L,
            droppedNow > 0L,
            dropped == droppedNow,
            envelopes.count(isReliable) == 5,
            envelopes.count(isDiagnostics) == 3001L - dropped,
            last == Some(3006L - dropped),
            seqs == untilOneTo(last.getOrElse(0L))
          )
        }
      }
    }
  )
