package raider.runtime.mailbox

import raider.core.{JobId, MessageId}
import raider.core.RaiderError
import raider.runtime.jobs.JobStatus
import zio.test.{live, *}
import zio.{Chunk, Duration, Fiber, Promise, Ref, UIO, ZIO}

import scala.util.Try

/** RAI-065 acceptance SEND-01..03 (m1.md, bounded mailbox for send).
  *
  * Determinism rules:
  *   - the turn boundary is simulated deterministically by calling
  *     `drainOnTurnBoundary` / `endTurn` directly; the `boundary` helper is one
  *     full boundary (take all pending, close the Draining window);
  *   - concurrency coordinates via Promise/Ref latches only — the turn-runner
  *     fiber signals its first completed boundary before senders start, so
  *     overlap is guaranteed without sleeping until a race resolves;
  *   - real durations appear only as the runner's scheduling relief and as the
  *     generous 120s wall guard that converts a (defective) hang into a typed
  *     failure — no assertion inspects a timing;
  *   - race outcomes are asserted as exact conservation invariants (a message
  *     racing a boundary is in this turn or the next, exactly once), never as a
  *     particular interleaving.
  */
object MailboxSpec extends ZIOSpecDefault:

  private val job = JobId("mbx-job")

  private def msg(n: Int): Message = Message(MessageId(s"m-$n"), s"steer-$n")

  /** One complete turn boundary: take all pending messages, then close the
    * Draining window. Messages sent while the window was open stay pending and
    * surface at the NEXT boundary.
    */
  private def boundary(mbx: Mailbox): UIO[Chunk[Message]] =
    mbx.drainOnTurnBoundary.flatMap(taken => mbx.endTurn.as(taken))

  /** Wall-clock guard around a concurrent test body (live clock, generous outer
    * bound): a runaway test fails typed instead of hanging the suite.
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

  /** Turn-runner cycle used by SEND-03: take one boundary, bank the batch. */
  private def bankCycle(
      mbx: Mailbox,
      into: Ref[Chunk[Message]]
  ): UIO[Unit] =
    boundary(mbx).flatMap(batch => into.update(_ ++ batch))

  /** A turn-runner fiber: signals its first completed boundary (deterministic
    * overlap with the senders), then keeps cycling with small sleeps — the
    * sleeps are scheduling relief only, never an awaited race.
    */
  private def turnRunner(
      mbx: Mailbox,
      into: Ref[Chunk[Message]]
  ): UIO[(Promise[Nothing, Unit], Fiber.Runtime[Nothing, Unit])] =
    for
      first <- Promise.make[Nothing, Unit]
      fiber <- (first.succeed(()) *> {
        def loop: UIO[Unit] =
          ZIO.uninterruptible(bankCycle(mbx, into)) *> ZIO.sleep(
            Duration.fromMillis(1)
          ) *> loop
        loop
      }).fork
    yield (first, fiber)

  /** Drain everything still pending after all fibers joined; also closes a
    * Draining window an interrupted runner may have left open. Post-join there
    * are no new sends, so the loop terminates (every cycle on an Accepting or
    * Closed mailbox takes ALL pending).
    */
  private def flush(mbx: Mailbox, into: Ref[Chunk[Message]]): UIO[Unit] =
    mbx.pendingCount.flatMap {
      case 0 => ZIO.unit
      case _ => ZIO.uninterruptible(bankCycle(mbx, into)) *> flush(mbx, into)
    }

  def spec = suite("Mailbox (RAI-065, SEND-01..03)")(
    test(
      "construction: make with capacity 0 / -1 throws IllegalArgumentException eagerly"
    ) {
      assertTrue(
        Try(Mailbox.make(job, 0)).failed.toOption
          .exists(_.isInstanceOf[IllegalArgumentException]),
        Try(Mailbox.make(job, -1)).failed.toOption
          .exists(_.isInstanceOf[IllegalArgumentException])
      )
    },
    test(
      "SEND-01: message delivered exactly once per id, acked at enqueue, drained in arrival order at the turn boundary"
    ) {
      for
        mbx <- Mailbox.make(job, 8)
        ack1 <- mbx.send(msg(1)) // ack precedes any drain — enqueue is done
        dup <- mbx.send(msg(1)) // same id: idempotent ack, no second message
        _ <- mbx.send(msg(2))
        _ <- mbx.send(msg(3))
        batch <- boundary(mbx)
        // a re-send of an already-DRAINED id is still an idempotent ack and
        // never re-delivers (exactly-once for the mailbox lifetime)
        dupAfterDrain <- mbx.send(msg(1))
        _ <- mbx.send(msg(4))
        nextTurn <- boundary(mbx)
        empty <- boundary(mbx)
      yield assertTrue(
        ack1 == Ack(MessageId("m-1"), duplicate = false),
        dup == Ack(MessageId("m-1"), duplicate = true),
        batch == Chunk(msg(1), msg(2), msg(3)),
        dupAfterDrain == Ack(MessageId("m-1"), duplicate = true),
        nextTurn == Chunk(msg(4)),
        empty.isEmpty
      )
    },
    test(
      "turn boundary: Accepting -> Draining -> Accepting -> Closed; sends during Draining queue for the next turn; a second drain inside the window takes nothing"
    ) {
      for
        mbx <- Mailbox.make(job, 4)
        s0 <- mbx.state
        _ <- mbx.send(msg(1))
        _ <- mbx.drainOnTurnBoundary // opens the window, takes m-1
        s1 <- mbx.state
        _ <- mbx.send(msg(2)) // window open: queued for the NEXT turn
        _ <- mbx.send(msg(3))
        pendingInWindow <- mbx.pendingCount
        stolen <- mbx.drainOnTurnBoundary // second drain in the same window
        sStill <- mbx.state
        _ <- mbx.endTurn
        s2 <- mbx.state
        next <- boundary(mbx)
        _ <- mbx.close(JobStatus.Cancelled)
        s3 <- mbx.state
      yield assertTrue(
        s0 == MailboxState.Accepting,
        s1 == MailboxState.Draining,
        pendingInWindow == 2,
        stolen.isEmpty,
        sStill == MailboxState.Draining,
        s2 == MailboxState.Accepting,
        next == Chunk(msg(2), msg(3)),
        s3 == MailboxState.Closed
      )
    },
    test(
      "SEND-02: overflow is a typed Full refusal; an idempotent retry of an accepted id still acks at capacity"
    ) {
      for
        mbx <- Mailbox.make(job, 2)
        _ <- mbx.send(msg(1))
        _ <- mbx.send(msg(2))
        refused <- mbx.send(msg(3)).either
        dupAtFull <- mbx.send(msg(1)).either
      yield assertTrue(
        refused == Left(MailboxError.Full(job, 2, 2)),
        dupAtFull == Right(Ack(MessageId("m-1"), duplicate = true))
      )
    },
    test(
      "SEND-02: capacity still applies while Draining; queued next-turn messages are not lost"
    ) {
      for
        mbx <- Mailbox.make(job, 2)
        _ <- mbx.send(msg(1))
        _ <- mbx.send(msg(2))
        held <- mbx.drainOnTurnBoundary
        _ <- mbx.send(msg(3)) // window open: next turn, still bounded
        _ <- mbx.send(msg(4))
        fullInWindow <- mbx.send(msg(5)).either
        _ <- mbx.endTurn
        next <- boundary(mbx)
      yield assertTrue(
        held == Chunk(msg(1), msg(2)),
        fullInWindow == Left(MailboxError.Full(job, 2, 2)),
        next == Chunk(msg(3), msg(4))
      )
    },
    test(
      "SEND-02: send after terminal is a typed TerminalJob refusal; close is idempotent (first status wins); acked messages remain drainable (final drain)"
    ) {
      for
        mbx <- Mailbox.make(job, 4)
        _ <- mbx.send(msg(1))
        _ <- mbx.close(JobStatus.Succeeded)
        st <- mbx.state
        refused <- mbx.send(msg(2)).either
        _ <- mbx.close(JobStatus.Failed) // must not overwrite the first status
        refused2 <- mbx.send(msg(3)).either
        finalBatch <- mbx.drainOnTurnBoundary
        empty <- mbx.drainOnTurnBoundary
      yield assertTrue(
        st == MailboxState.Closed,
        refused == Left(MailboxError.TerminalJob(job, JobStatus.Succeeded)),
        refused2 == Left(MailboxError.TerminalJob(job, JobStatus.Succeeded)),
        finalBatch == Chunk(msg(1)),
        empty.isEmpty
      )
    },
    test(
      "MailboxError maps to RaiderError codes explicitly; the terminal refusal suggests a new turn"
    ) {
      val full = MailboxError.Full(job, 2, 2).toRaiderError
      val terminal =
        MailboxError.TerminalJob(job, JobStatus.Failed).toRaiderError
      val unknown = MailboxError.UnknownJob(job).toRaiderError
      assertTrue(
        full.code == "RA-CAP",
        full.retryable == false,
        terminal.code == "RA-CAP",
        terminal.retryable == false,
        terminal.detail.contains("terminal"),
        terminal.detail.contains("new turn"),
        unknown.code == "RA-INP",
        unknown.detail.contains("unknown job")
      )
    },
    test(
      "SEND-03: 4 fibers x 50 sends with concurrent turn-boundary drains — every message delivered exactly once, per-fiber send order preserved"
    ) {
      live {
        guarded {
          for
            mbx <- Mailbox.make(job, 256)
            delivered <- Ref.make(Chunk.empty[Message])
            failures <- Ref.make(Chunk.empty[MailboxError])
            (first, runner) <- turnRunner(mbx, delivered)
            _ <- first.await
            senders <- ZIO.foreach(1 to 4) { f =>
              ZIO
                .foreachDiscard(1 to 50) { n =>
                  mbx
                    .send(Message(MessageId(s"f$f-$n"), s"payload-$f-$n"))
                    .foldZIO(
                      e => failures.update(_ :+ e),
                      _ => ZIO.unit
                    )
                }
                .as(f)
                .fork
            }
            _ <- ZIO.foreach(senders)(_.await)
            _ <- runner.interrupt
            _ <- flush(mbx, delivered)
            out <- delivered.get
            fails <- failures.get
            ids = out.map(_.id.value)
            expected = (for
              f <- 1 to 4
              n <- 1 to 50
            yield s"f$f-$n").toSet
          yield assertTrue(
            fails.isEmpty,
            out.size == 200,
            ids.distinct.size == 200,
            ids.toSet == expected,
            // per-fiber program order: each fiber's ids appear drained in
            // strictly increasing n order
            (1 to 4).forall { f =>
              out.collect {
                case m if m.id.value.startsWith(s"f$f-") =>
                  m.id.value.drop(3).toInt
              } == (1 to 50).toVector
            }
          )
        }
      }
    },
    test(
      "SEND-03: boundary race — a message racing the drain lands in this turn or the next, exactly once, never lost"
    ) {
      live {
        guarded {
          ZIO
            .foreach(1 to 200) { i =>
              for
                mbx <- Mailbox.make(job, 1)
                m = Message(MessageId(s"race-$i"), s"payload-$i")
                sender <- mbx.send(m).either.fork
                drainer <- boundary(mbx).fork
                acked <- sender.join
                turn1 <- drainer.join
                turn2 <- boundary(mbx)
              yield (acked, (turn1 ++ turn2).map(_.id.value))
            }
            .map { results =>
              val deliveries = results.map(_._2)
              assertTrue(
                results.forall { case (acked, _) => acked.isRight },
                deliveries.forall(_.distinct.size == 1),
                deliveries.map(_.head).toSet ==
                  (1 to 200).map(i => s"race-$i").toSet
              )
            }
        }
      }
    },
    test(
      "SEND-03: 4 fibers x 10 sends racing a concurrent close — every send is acked-and-delivered exactly once or typed-refused (TerminalJob, never Full)"
    ) {
      live {
        guarded {
          ZIO
            .foreach(1 to 25) { i =>
              for
                mbx <- Mailbox.make(job, 64)
                delivered <- Ref.make(Chunk.empty[Message])
                acked <- Ref.make(Set.empty[MessageId])
                refused <- Ref.make(Chunk.empty[MailboxError])
                (first, runner) <- turnRunner(mbx, delivered)
                _ <- first.await
                senders <- ZIO.foreach(1 to 4) { f =>
                  ZIO
                    .foreachDiscard(1 to 10) { n =>
                      val m = Message(MessageId(s"c$i-f$f-$n"), s"p-$f-$n")
                      mbx
                        .send(m)
                        .foldZIO(
                          e => refused.update(_ :+ e),
                          ack => acked.update(_ + ack.id)
                        )
                    }
                    .fork
                }
                closer <- mbx.close(JobStatus.Cancelled).fork
                _ <- ZIO.foreach(senders)(_.await)
                _ <- closer.await
                _ <- runner.interrupt
                _ <- flush(mbx, delivered)
                out <- delivered.get
                aks <- acked.get
                refs <- refused.get
                deliveredIds = out.map(_.id)
              yield assertTrue(
                refs.collect { case f: MailboxError.Full => f }.isEmpty,
                refs.forall(_.isInstanceOf[MailboxError.TerminalJob]),
                aks.size + refs.size == 40,
                deliveredIds.toSet == aks,
                out.size == aks.size,
                out.map(_.content).distinct.size == out.size
              )
            }
            .map(results => results.foldLeft(assertTrue(true))(_ && _))
        }
      }
    }
  )
