package raider.cli.processapi

import zio._
import zio.json.ast.Json
import zio.test._

import java.time.Duration

/** RAI-201 outbound channel unit coverage (ADR-037 §5; fixture `slow-reader`):
  * ordered drain with terminal-last, bounded backpressure with NO lost waiters,
  * observation coalescing with gap flush, and dead-channel settlement. The
  * waiter-wakeup case pins the historical bug where a suspended receipt was
  * forgotten by the queue picker and never resumed.
  */
object OutboundSpec extends ZIOSpecDefault:

  private def typeOf(json: Json): String =
    json match
      case obj: Json.Obj =>
        obj.fields.toMap
          .get("type")
          .collect { case Json.Str(s) => s }
          .getOrElse("?")
      case _ => "?"

  private def skippedOf(json: Json): Option[(Long, Long)] =
    json match
      case obj: Json.Obj =>
        obj.fields.toMap.get("skipped").flatMap {
          case skip: Json.Obj =>
            for
              from <- skip.fields.toMap.get("fromSequence").collect {
                case Json.Num(v) => scala.math.BigDecimal(v).toLongExact
              }
              count <- skip.fields.toMap.get("count").collect {
                case Json.Num(v) => scala.math.BigDecimal(v).toLongExact
              }
            yield (from, count)
          case _ => None
        }
      case _ => None

  private def receipt(i: Int): Json =
    Protocol.accepted(s"r$i", s"root-$i")

  private def terminalBody: Protocol.TerminalBody =
    Protocol.TerminalBody(
      executionStatus = "completed",
      taskVerdict = "success",
      safetyVerdict = "passed",
      protocolVerdict = "passed",
      accountingStatus = "unavailable",
      cleanupStatus = "complete",
      exitCode = 0,
      artifacts = Vector.empty,
      primaryError = None,
      secondaryError = None
    )

  // Live clock: these tests exercise real concurrent timing (backpressure
  // waits, stall bounds, timeout joins), not TestClock-controlled effects.
  // All waits below are event-driven (promise awaits / joins) — the file is
  // free of timed idle calls (RAI-230); every wait is bounded by a disclosed
  // timeout (the 60 s TestAspect.timeout on the waiter-wakeup case, the stall
  // bounds passed to Outbound.make, and the per-call 30 s bounds in the
  // dead-channel case).
  def spec = suite("RAI-201 process-api outbound channel")(
    test("writer drains receipts in order and writes the terminal last") {
      ZIO.scoped {
        for
          written <- Ref.make(Vector.empty[String])
          out <- Outbound.make(
            json => written.update(_ :+ typeOf(json)),
            Duration.ofSeconds(10)
          )
          fiber <- out.writer.fork
          _ <- out.receipt(receipt(1))
          _ <- out.receipt(receipt(2))
          _ <- out.terminal(Protocol.terminal("r1", "root-1", terminalBody))
          _ <- out.closeInput()
          terminalLast <- out.terminalWrittenOrDone
          got <- written.get
          _ <- fiber.join
          depth <- out.queueDepth
        yield assertTrue(
          terminalLast,
          got == Vector("accepted", "accepted", "terminal"),
          depth == 0
        )
      }
    },
    test("receipts suspended by a full queue are never lost (waiter wakeup)") {
      ZIO.scoped {
        val expected = Outbound.MaxOrdinaryFrames + 8
        for
          release <- Promise.make[Nothing, Unit]
          allWritten <- Promise.make[Nothing, Unit]
          written <- Ref.make(Vector.empty[String])
          out <- Outbound.make(
            json =>
              release.await *>
                written.updateAndGet(_ :+ typeOf(json)).flatMap { v =>
                  ZIO
                    .when(v.length == expected)(allWritten.succeed(()).unit)
                    .unit
                },
            Duration.ofSeconds(60)
          )
          // Deterministic saturation: sequential receipts never suspend below
          // the capacity bound, so the queue ends up exactly full (the same
          // pattern as the coalescing test — no forks, no probes, no timing).
          _ <- ZIO.foreachDiscard(1 to Outbound.MaxOrdinaryFrames) { i =>
            out.receipt(receipt(i))
          }
          full <- out.queueDepth
          // Surplus receipts can only suspend as waiters: the queue is exactly
          // full and cannot drain (the writer does not exist yet), so every
          // surplus admission attempt registers a waiter instead of enqueueing.
          // The wakeup itself is proven by allWritten: a lost wakeup leaves the
          // suspended receipt unwritten and the promise pending until the
          // disclosed 60 s bound (the historical pinned bug — a forgotten
          // waiter that is never resumed).
          surplus <- ZIO.foreach(Outbound.MaxOrdinaryFrames + 1 to expected) {
            i => out.receipt(receipt(i)).forkDaemon
          }
          _ <- out.writer.fork
          bounded <- out.queueDepth
          _ <- release.succeed(())
          // Event-driven drain wait (no polling, no timed idling): completes
          // exactly when the expected number of accepted receipts is on the
          // wire. Bound: the 60 s TestAspect.timeout below; the writer's stall
          // bound passed to Outbound.make mirrors it.
          _ <- allWritten.await
          _ <- ZIO.foreach(surplus)(_.join)
          got <- written.get
        yield assertTrue(
          full == Outbound.MaxOrdinaryFrames,
          bounded <= Outbound.MaxOrdinaryFrames,
          got.count(_ == "accepted") == expected,
          got.headOption.contains("accepted")
        )
      }
    } @@ TestAspect.timeout(zio.Duration.fromSeconds(60)),
    test(
      "observations coalesce under a saturated queue and the gap is flushed"
    ) {
      ZIO.scoped {
        for
          release <- Promise.make[Nothing, Unit]
          flushArmed <- Promise.make[Nothing, Unit]
          written <- Ref.make(Vector.empty[Json])
          out <- Outbound.make(
            json =>
              flushArmed.succeed(()).unit *>
                release.await *>
                written.update(_ :+ json),
            Duration.ofSeconds(60)
          )
          // The queue is saturated BEFORE the writer fiber exists: sequential
          // receipts only ever suspend past the capacity bound, so after this
          // loop the queue holds exactly MaxOrdinaryFrames frames — no forks,
          // no depth probes, no scheduling races (a writer forked earlier
          // would keep one frame in flight and permanently free one slot,
          // letting an observation slip in as an ordinary frame).
          _ <- ZIO.foreachDiscard(1 to Outbound.MaxOrdinaryFrames) { i =>
            out.receipt(receipt(i))
          }
          // The full queue cannot fit the observations: they must coalesce.
          _ <- out.observation("req", 65L, "progress", "one")
          _ <- out.observation("req", 66L, "progress", "two")
          _ <- out.observation("req", 67L, "progress", "three")
          gapBeforeFlush <- out.openGap
          // Start the writer and wait until it has picked the first frame (the
          // sink signals before its release gate). The queue now has exactly
          // one free slot and the writer is frozen mid-write, so flushGap's
          // admission is a single non-suspending modify that cannot race a
          // drain. Without this arm, under heavy machine load the writer fiber
          // can stay unscheduled past flushGap; a flush admitted after the
          // writer finished would strand the coalesced frame unwritten (an
          // Outbound.scala late-admission race — separate card, not this one).
          fiber <- out.writer.fork
          _ <- flushArmed.await
          _ <- out.flushGap("req")
          // The terminal frame carries its own input-close (atomic state
          // transition), so the writer can never observe a closed empty queue
          // without a terminal: it drains r2..r64 plus the flushed frame and
          // writes the terminal last. release before terminal keeps the
          // writer's Idle path (input still open) as the only parking state.
          _ <- release.succeed(())
          _ <- out.terminal(Protocol.terminal("req", "root", terminalBody))
          _ <- out.terminalWrittenOrDone
          _ <- fiber.join
          got <- written.get
          gapFrames = got.flatMap(skippedOf(_))
        yield assertTrue(
          gapBeforeFlush.contains((65L, 3L)),
          gapFrames.size == 1,
          gapFrames.headOption.contains((65L, 3L))
        )
      }
    },
    test(
      "dead channel settles the writer, surfaces the reason and refuses receipts"
    ) {
      ZIO.scoped {
        for
          out <- Outbound.make(
            _ => ZIO.fail(Outbound.ChannelDead("peer vanished")),
            Duration.ofSeconds(10)
          )
          fiber <- out.writer.fork
          // Enqueue first: the channel only dies when the writer attempts
          // (and here, fails) an actual write.
          _ <- out.receipt(receipt(1))
          death <- out.channelDeath.timeout(Duration.ofSeconds(30))
          done <- out.terminalWrittenOrDone
          _ <- fiber.join
          refused <- out
            .receipt(receipt(99))
            .either
            .timeout(Duration.ofSeconds(30))
        yield assertTrue(
          death.exists(_.reason == "peer vanished"),
          !done,
          refused.exists(_.left.exists(dead => dead.reason == "peer vanished"))
        )
      }
    },
    test(
      "clean input close without a terminal completes without terminal write"
    ) {
      ZIO.scoped {
        for
          written <- Ref.make(Vector.empty[String])
          out <- Outbound.make(
            json => written.update(_ :+ typeOf(json)),
            Duration.ofSeconds(10)
          )
          fiber <- out.writer.fork
          _ <- out.receipt(receipt(1))
          _ <- out.closeInput()
          terminalLast <- out.terminalWrittenOrDone
          _ <- fiber.join
          got <- written.get
        yield assertTrue(!terminalLast, got == Vector("accepted"))
      }
    }
  ) @@ TestAspect.withLiveClock

end OutboundSpec
