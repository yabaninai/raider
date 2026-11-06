package raider.cli.processapi

import zio._
import zio.json._
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

  // Escape hatch for the strand probe's bounded terminal-write burn: the
  // accumulated value must be observable, otherwise the JIT eliminates the
  // busy loop and the burn never materializes.
  private val burnSink = new java.util.concurrent.atomic.AtomicLong(0)

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
    },
    test(
      "a receipt suspended on a full queue is drained before the terminal (RAI-232 strand)"
    ) {
      ZIO.scoped {
        // Exact-cap ordinary frame: alone it saturates MaxOrdinaryBytes, so
        // the frame-count bound is not the binding constraint here and the
        // queue can hold exactly one drainable frame while the byte budget
        // is exhausted.
        val base =
          Protocol
            .accepted("rhuge", "")
            .toJson
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
            .length
        val huge =
          Protocol.accepted(
            "rhuge",
            "a" * (Outbound.MaxOrdinaryBytes - base)
          )
        for
          latch <- Promise.make[Nothing, Unit]
          started <- Promise.make[Nothing, Unit]
          driverGo <- Promise.make[Nothing, Unit]
          written <- Ref.make(Vector.empty[Json])
          out <- Outbound.make(
            json =>
              started.succeed(()).unit *> latch.await *>
                (if typeOf(json) == "terminal" then
                   // The terminal write occupies the writer for a bounded
                   // stretch: the waiter woken by the last ordinary write
                   // gets its retry serviced during this window and enqueues
                   // into the already-picked-past queue. Without the RAI-232
                   // fix the writer never re-picks after WriteTerminal and
                   // the receipt strands; with the fix the writer cannot
                   // reach the terminal pick while a waiter is admitted.
                   ZIO.succeed {
                     var i = 0L
                     var acc = 0L
                     while i < 8000000 do
                       acc += i
                       i += 1
                     burnSink.addAndGet(acc)
                     ()
                   } *> written.update(_ :+ json)
                 else written.update(_ :+ json)),
            Duration.ofSeconds(60)
          )
          // First exact-cap frame: admitted directly (1 < 64 frames and
          // bytes == cap). The writer holds it frozen inside its sink.
          _ <- out.receipt(huge)
          fiber <- out.writer.fork
          _ <- started.await
          // The queue is empty and the byte budget is free again: the
          // second exact-cap frame is admitted directly and re-saturates
          // the byte budget, so the queue holds exactly one drainable
          // frame (no latch-dependent refill, no probe of the depth).
          _ <- out.receipt(huge)
          // The driver un-gates the writer and installs the terminal. The
          // driver signal is issued immediately before the surplus
          // admission below, and the admission modify runs without an
          // intervening scheduling point on this fiber, so the surplus
          // registers on the waiter list while the queue is pinned
          // bytes-full.
          driver <- (driverGo.await *> latch.succeed(()).unit *> out.terminal(
            Protocol.terminal("req", "root", terminalBody)
          )).fork
          _ <- driverGo.succeed(())
          _ <- out.receipt(receipt(Outbound.MaxOrdinaryFrames + 2))
          // Without the RAI-232 fix the writer can drain the queued frame,
          // pick and write the terminal, and exit before the woken
          // admission lands, stranding the receipt unwritten (queue never
          // drains to 0, surplus count short); with the fix the writer
          // holds the terminal pick until every admitted receipt has
          // landed and is written before the terminal.
          _ <- fiber.join
          _ <- driver.join
          got <- written.get
          depth <- out.queueDepth
          types = got.map(typeOf)
        yield assertTrue(
          depth == 0,
          types.count(_ == "accepted") == 3,
          types.count(_ == "terminal") == 1,
          types.lastOption.contains("terminal")
        )
      }
    } @@ TestAspect.timeout(zio.Duration.fromSeconds(60)),
    test(
      "channel death fails a suspended surplus receipt instead of stranding it"
    ) {
      ZIO.scoped {
        for
          written <- Ref.make(Vector.empty[String])
          out <- Outbound.make(
            json => written.update(_ :+ typeOf(json)),
            Duration.ofSeconds(10)
          )
          // Saturate, then attempt one surplus admission: with no writer the
          // queue cannot drain, so the attempt registers as a waiter instead
          // of enqueueing.
          _ <- ZIO.foreachDiscard(1 to Outbound.MaxOrdinaryFrames) { i =>
            out.receipt(receipt(i))
          }
          surplus <- out
            .receipt(receipt(Outbound.MaxOrdinaryFrames + 1))
            .forkDaemon
          // Death must reach the suspended receipt: it completes (bounded
          // join) with the death reason — never a silent strand.
          _ <- out.failChannelForTest("peer stalled")
          outcome <- surplus.join.either.timeout(Duration.ofSeconds(30))
          depth <- out.queueDepth
        yield assertTrue(
          outcome.exists(_.left.exists(_.reason == "peer stalled")),
          depth == Outbound.MaxOrdinaryFrames
        )
      }
    },
    test(
      "RAI-246 deterministic trigger: the terminal pick never overtakes a woken waiter's landing"
    ) {
      ZIO.scoped {
        for
          // RAI-246 seam latches: writer arrival/hold at the terminal pick,
          // waiter registration signal, waiter landing hold. Without the
          // seam armed the writer/landing interleaving below is impossible
          // to pin; with it, the base implementation deterministically
          // enters the window (terminal picked while a registered waiter is
          // still pending), and the fixed implementation deterministically
          // refuses it via the empty-point gate.
          writerAtPick <- Promise.make[Nothing, Unit]
          writerHold <- Promise.make[Nothing, Unit]
          waiterRegistered <- Promise.make[Nothing, Unit]
          waiterHold <- Promise.make[Nothing, Unit]
          sinkGo <- Promise.make[Nothing, Unit]
          written <- Ref.make(Vector.empty[Json])
          out <- Outbound.make(
            json => sinkGo.await *> written.update(_ :+ json),
            Duration.ofSeconds(30)
          )
          // Arm the per-instance test seam.
          _ <- ZIO.succeed {
            out.probeSeam.writerAtPick.set(Some(writerAtPick))
            out.probeSeam.writerHold.set(Some(writerHold))
            out.probeSeam.waiterRegistered.set(Some(waiterRegistered))
            out.probeSeam.waiterHold.set(Some(waiterHold))
          }
          // Deterministic saturation before the writer exists (same pattern
          // as the coalescing case): exactly MaxOrdinaryFrames queued.
          _ <- ZIO.foreachDiscard(1 to Outbound.MaxOrdinaryFrames) { i =>
            out.receipt(receipt(i))
          }
          full <- out.queueDepth
          // The surplus admission runs against a writer-less saturated
          // queue, so it can only park as a waiter (no writer pick can free
          // a slot ahead of it); the registration signal is the
          // deterministic barrier proving it is registered before anything
          // can drain.
          surplus <- out
            .receipt(receipt(Outbound.MaxOrdinaryFrames + 1))
            .fork
          registered <- waiterRegistered.await
            .timeout(Duration.ofSeconds(10))
          // Only now fork the writer: its first pick holds one frame
          // in-flight and the write freezes on sinkGo, pinning the queue
          // (the freed slot stays free but the surplus is already a parked
          // waiter, not a re-run admission).
          fiber <- out.writer.fork
          // Terminal install closes input and issues the single wake that
          // moves the waiter from its parked await to the landing hold. On
          // the fixed implementation it remains a registered admission
          // (landing-tracked) until it actually lands.
          _ <- out.terminal(Protocol.terminal("req", "root", terminalBody))
          // Release the drain: the writer writes every queued frame and
          // reaches the terminal pick. On the base implementation the pick
          // fires the arrival signal and the writer parks post-pick; with
          // the fix the empty-point gate refuses the pick while the surplus
          // is still registered and the signal stays pending for the bounded
          // observation below (no assertion either way — the invariants at
          // the end decide; the fix pays this deadline once per run).
          _ <- sinkGo.succeed(())
          arrived <- writerAtPick.await.timeout(Duration.ofSeconds(10))
          // Release the woken waiter's landing modify: on the base this
          // lands strictly after the terminal pick (window entered, frame
          // stranded behind it); with the fix it lands into the parked
          // writer's queue and the gate opens only after it drains.
          _ <- waiterHold.succeed(())
          landed <- surplus.join.either.timeout(Duration.ofSeconds(30))
          // Release the writer: on the base this un-parks the post-pick
          // suspension (terminal written after the landing — the stranded
          // window); with the fix the writer already drained the landed
          // receipt and passes the (already-released) hold on its way to
          // the gate-opened terminal pick.
          _ <- writerHold.succeed(())
          writerDone <- fiber.join.timeout(Duration.ofSeconds(30))
          got <- written.get
          depth <- out.queueDepth
          types = got.map(typeOf)
        yield assertTrue(
          full == Outbound.MaxOrdinaryFrames,
          registered.isDefined,
          landed.exists(_.isRight),
          writerDone.isDefined,
          depth == 0,
          types.count(_ == "accepted") == Outbound.MaxOrdinaryFrames + 1,
          types.count(_ == "terminal") == 1,
          types.lastOption.contains("terminal")
        )
      }
    } @@ TestAspect.timeout(zio.Duration.fromSeconds(60))
  ) @@ TestAspect.withLiveClock

end OutboundSpec
