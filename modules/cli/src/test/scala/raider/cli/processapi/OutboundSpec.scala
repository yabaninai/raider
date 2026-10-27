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

  private def pollUntil(check: UIO[Boolean]): UIO[Unit] =
    check.flatMap(done =>
      if done then ZIO.unit else ZIO.sleep(50.millis) *> pollUntil(check)
    )

  // Live clock: these tests exercise real concurrent timing (backpressure
  // sleeps, stall bounds, timeout joins), not TestClock-controlled effects.
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
          written <- Ref.make(Vector.empty[String])
          out <- Outbound.make(
            json => release.await *> written.update(_ :+ typeOf(json)),
            Duration.ofSeconds(60)
          )
          fiber <- out.writer.fork
          // The first write blocks on the gated sink, so the queue stays full
          // and the surplus receipts must suspend as waiters.
          _ <- ZIO.foreachDiscard(1 to expected) { i =>
            out.receipt(receipt(i)).forkDaemon
          }
          _ <- ZIO.sleep(300.millis)
          before <- out.queueDepth
          _ <- release.succeed(())
          _ <- pollUntil(written.get.map(_.count(_ == "accepted") == expected))
          got <- written.get
        yield assertTrue(
          before <= Outbound.MaxOrdinaryFrames,
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
          written <- Ref.make(Vector.empty[Json])
          out <- Outbound.make(
            json => release.await *> written.update(_ :+ json),
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
          // Only now start the writer and let the queue drain.
          fiber <- out.writer.fork
          _ <- release.succeed(())
          _ <- out.closeInput()
          _ <- out.flushGap("req")
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
