package raider.runtime.budget

import raider.core.*
import raider.runtime.budget.AggregateRunCeiling.CeilingTrip
import zio.stream.ZStream
import zio.test.{TestAspect, *}
import zio.{Duration as ZDuration, Scope, ZIO}

import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

/** RAI-258 component acceptance: the aggregate run ceiling binds root and
  * delegated children jointly. Covered here, at the component level: typed
  * make() refusals; dispatch-cap and token-ceiling denials (RA-PBUDGET);
  * deadline denial (RA-DEADLINE); first-trip-wins; idempotent settle;
  * concurrent admissions that cannot oversubscribe the cap; concurrent usage
  * observation with exact (no-lost, no-double) accounting; and the `wired`
  * decorator (passthrough events, per-actor usage, response settle, and denial
  * BEFORE the wire). The joint root+child run behavior through the REAL
  * AgentLoop/DelegationToolset is covered by
  * [[raider.runtime.budget.AggregateDelegationCeilingSpec]].
  */
object AggregateRunCeilingSpec extends ZIOSpecDefault:

  private val att = AttemptId("agg-att")

  private def built(
      dispatches: Option[Int],
      tokens: Option[Long],
      deadline: Option[ZDuration]
  ): AggregateRunCeiling =
    AggregateRunCeiling
      .make(dispatches, tokens, deadline)
      .fold(e => throw e, identity)

  private val req = ModelRequest("m", List(RequestMessage("user", "u")), 256)

  /** Deterministic scripted backend: records requests, emits the same turn
    * every call (usage 7 in / 3 out), and never consults the network.
    */
  private final class ScriptBackend extends ModelBackend:
    private val recorded = new CopyOnWriteArrayList[ModelRequest]()

    def capabilities: ModelCapabilities = ModelCapabilities(
      CapabilityStatus.Supported,
      CapabilityStatus.Supported,
      CapabilityStatus.Unknown
    )

    def requests: List[ModelRequest] = recorded.asScala.toList

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap:
        ZIO.succeed:
          recorded.add(input)
          ZStream(
            ModelEvent.Started(att),
            ModelEvent.TextDelta(att, "answer"),
            ModelEvent.UsageObserved(att, Usage(7L, 3L, None), false),
            ModelEvent.Finished(att, "stop")
          )

  def spec = suite("aggregate run ceiling (RAI-258)")(
    test("make refuses non-positive ceilings with flag-named typed errors") {
      val badDispatch = AggregateRunCeiling.make(Some(0), None, None)
      val badTokens = AggregateRunCeiling.make(None, Some(-5L), None)
      val badDeadline =
        AggregateRunCeiling.make(None, None, Some(ZDuration.Zero))
      val ok = AggregateRunCeiling.make(
        Some(1),
        Some(1L),
        Some(ZDuration.fromSeconds(1))
      )
      assertTrue(
        badDispatch.swap.exists(e =>
          e.code == "RA-CFG" && e.detail.contains("--max-dispatches")
        ),
        badTokens.swap.exists(e =>
          e.code == "RA-CFG" && e.detail.contains("--max-tokens")
        ),
        badDeadline.swap.exists(e =>
          e.code == "RA-CFG" && e.detail.contains("--run-deadline")
        ),
        ok.exists(
          _.runConfig == AggregateRunCeiling.Config(
            Some(1),
            Some(1L),
            Some(ZDuration.fromSeconds(1))
          )
        )
      )
    },
    test(
      "dispatch cap: the N+1-th admission is denied typed and trips the ceiling"
    ) {
      val c = built(Some(3), None, None)
      for
        a1 <- ZIO.succeed(c.admit("root"))
        a2 <- ZIO.succeed(c.admit("scout#1"))
        a3 <- ZIO.succeed(c.admit("scout#2"))
        a4 <- ZIO.succeed(c.admit("scout#3"))
      yield assertTrue(
        a1.isRight,
        a2.isRight,
        a3.isRight,
        a4.swap.exists(_.code == "RA-PBUDGET"),
        a4.swap.exists(_.detail.contains("aggregate dispatch cap of 3")),
        c.trippedCeiling == Some(CeilingTrip.DispatchCap(3))
      )
    },
    test(
      "token ceiling: joint root+child accounting trips once and denies the next dispatch"
    ) {
      val c = built(None, Some(60L), None)
      for
        _ <- ZIO.succeed {
          val r1 = c.admit("root")
          c.observe("root", Usage(10L, 5L, None))
          val r2 = c.admit("scout#1")
          c.observe("scout#1", Usage(20L, 10L, None)) // 45 committed
          val r3 = c.admit("scout#1")
          c.observe("scout#1", Usage(10L, 6L, None)) // 61 > 60 → trip
          assertTrue(r1.isRight, r2.isRight, r3.isRight)
        }
        denied <- ZIO.succeed(c.admit("root"))
        snap <- c.snapshot
      yield assertTrue(
        denied.swap.exists(e =>
          e.code == "RA-PBUDGET" &&
            e.detail.contains("aggregate token ceiling of 60")
        ),
        c.trippedCeiling == Some(CeilingTrip.TokenCap(60L, 61L)),
        // exact joint accounting: no lost and no double-counted spend
        snap.totalTokens == Usage(40L, 21L, None),
        snap.dispatches == 3,
        snap.byActor
          .find(_.actor == "root")
          .exists(u => u.inputTokens == 10L && u.outputTokens == 5L),
        snap.byActor
          .find(_.actor == "scout#1")
          .exists(u => u.inputTokens == 30L && u.outputTokens == 16L)
      )
    },
    test(
      "in-flight honesty: usage past the ceiling still accumulates and settles"
    ) {
      val c = built(None, Some(50L), None)
      for
        _ <- ZIO.succeed {
          val admitted = c.admit("worker#1")
          c.observe("worker#1", Usage(30L, 25L, None)) // 55 > 50 mid-flight
          c.settle("worker#1", "response")
          admitted.isRight
        }
        denied <- ZIO.succeed(c.admit("worker#1"))
        snap <- c.snapshot
      yield assertTrue(
        // the spend that happened is counted, never treated as free
        snap.totalTokens == Usage(30L, 25L, None),
        snap.entries
          .find(_.actor == "worker#1")
          .exists(e => e.settled.contains("response")),
        denied.swap.exists(e =>
          e.code == "RA-PBUDGET" && e.detail.contains("token ceiling of 50")
        )
      )
    },
    test("deadline expiry denies the next admission typed RA-DEADLINE") {
      val c = built(None, None, Some(ZDuration.fromMillis(80)))
      for
        first <- ZIO.succeed(c.admit("root"))
        _ <- ZIO.sleep(ZDuration.fromMillis(150))
        denied <- ZIO.succeed(c.admit("root"))
        snap <- c.snapshot
      yield assertTrue(
        first.isRight,
        denied.swap.exists(e =>
          e.code == "RA-DEADLINE" &&
            e.detail.contains("aggregate run deadline")
        ),
        c.trippedCeiling.exists {
          case CeilingTrip.Deadline(_) => true
          case _                       => false
        },
        // an elapsed deadline reports zero remaining time
        c.remainingDeadline.contains(ZDuration.Zero),
        snap.dispatches == 1
      )
    },
    test("first trip wins; awaitTripped completes with the first trip") {
      val c = built(None, None, None)
      for
        _ <- ZIO.succeed {
          c.stop(CeilingTrip.DispatchCap(2))
          c.stop(CeilingTrip.TokenCap(10L, 11L))
        }
        trip <- c.awaitTripped.timeout(ZDuration.fromSeconds(5))
      yield assertTrue(
        trip.contains(CeilingTrip.DispatchCap(2)),
        c.trippedCeiling.contains(CeilingTrip.DispatchCap(2))
      )
    },
    test("settle is idempotent first-wins; unknown actors are ignored") {
      val c = built(Some(1), None, None)
      for
        admitted <- ZIO.succeed(c.admit("root"))
        _ <- ZIO.succeed {
          c.settle("root", "provider-error")
          c.settle("root", "response") // late normal end must NOT overwrite
          c.settle("ghost", "response") // never admitted → ignored
        }
        snap <- c.snapshot
      yield assertTrue(
        admitted.isRight,
        snap.entries
          .find(_.actor == "root")
          .exists(_.settled.contains("provider-error")),
        snap.entries.find(_.actor == "ghost").isEmpty
      )
    },
    test(
      "concurrent admissions cannot oversubscribe the dispatch cap (interleaved reservations)"
    ) {
      val c = built(Some(4), None, None)
      for granted <-
          ZIO
            .foreachPar((1 to 20).toList)(i =>
              ZIO.succeed(c.admit(s"w$i").isRight)
            )
            .map(_.count(identity))
      yield assertTrue(
        granted == 4,
        c.trippedCeiling == Some(CeilingTrip.DispatchCap(4))
      )
    },
    test("concurrent usage observation sums exactly (no lost accounting)") {
      val c = built(None, Some(1000L), None)
      for
        _ <-
          ZIO.foreachPar(List("a#1", "a#2"))(actor =>
            ZIO.foreachDiscard(1 to 10)(_ =>
              ZIO.succeed(c.observe(actor, Usage(1L, 1L, None)))
            )
          )
        snap <- c.snapshot
      yield assertTrue(
        snap.totalTokens == Usage(20L, 20L, None),
        snap.byActor.forall(u => u.inputTokens == 10L && u.outputTokens == 10L),
        snap.dispatches == 0, // observation alone never consumes dispatches
        c.trippedCeiling.isEmpty
      )
    },
    test(
      "wired: passthrough events, per-actor usage, response settle; denial hits BEFORE the wire"
    ) {
      val under = new ScriptBackend()
      val c = built(Some(1), None, None)
      val wb = c.wired(under, "root")
      for
        events <- ZIO.scoped(wb.stream(req).runCollect)
        denied <- ZIO.scoped(wb.stream(req).runCollect.either)
        snap <- c.snapshot
      yield assertTrue(
        events.collect { case m: ModelEvent.UsageObserved => m }.size == 1,
        events.lastOption.exists {
          case ModelEvent.Finished(_, "stop") => true
          case _                              => false
        },
        under.requests.size == 1, // the denied dispatch never hit the wire
        denied.swap.exists(e =>
          e.code == "RA-PBUDGET" &&
            e.detail.contains("aggregate dispatch cap of 1")
        ),
        snap.dispatches == 1,
        snap.byActor
          .find(_.actor == "root")
          .exists(u => u.inputTokens == 7L && u.outputTokens == 3L),
        snap.entries
          .find(_.actor == "root")
          .exists(_.settled.contains("response"))
      )
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential

end AggregateRunCeilingSpec
