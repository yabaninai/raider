package raider.testkit

import raider.core.*
import zio.test._
import zio.{Scope, ZIO}

/** MOCK-02: Error/adversarial cases — invalid fixture, unexpected call,
  * overread, output bound never turn into success. A loop (or consumer) that
  * exceeds what the fixture planned must observe a typed failure, never a
  * silent success.
  */
object Mock02Spec extends ZIOSpecDefault:

  private val a = AttemptId("mock02-att")

  def spec = suite("MOCK-02: adversarial fixtures")(
    test(
      "unexpected call beyond planned rounds fails typed (sequence backend)"
    ) {
      for
        backend <- ScriptedSequenceBackend(
          Vector[ModelEvent](ModelEvent.TextDelta(a, "only round"))
        )
        req = ModelRequest("m", Nil, 8)
        first <- ZIO.scoped(backend.stream(req).runCollect)
        extra <- backend.stream(req).runCollect.either
      yield assertTrue(
        backend.fixtureMode,
        first.toList == List(ModelEvent.TextDelta(a, "only round")),
        extra.isLeft,
        extra.fold(_.code == "RA-STREAM", _ => false)
      )
    },
    test("overread of ScriptedSequence exhausts and fails extra calls") {
      for
        backend <- ScriptedSequenceBackend(
          Vector[ModelEvent](ModelEvent.TextDelta(a, "round1")),
          Vector[ModelEvent](ModelEvent.TextDelta(a, "round2"))
        )
        r1 = ModelRequest("m", Nil, 16)
        r2 = ModelRequest("m", Nil, 16)
        round1 <- ZIO.scoped(backend.stream(r1).runCollect)
        round2 <- ZIO.scoped(backend.stream(r2).runCollect)
        extra <- backend.stream(r1).runCollect.either
      yield assertTrue(
        backend.fixtureMode,
        round1.toList == List(ModelEvent.TextDelta(a, "round1")),
        round2.toList == List(ModelEvent.TextDelta(a, "round2")),
        extra.isLeft,
        extra.fold(_.code == "RA-STREAM", _ => false)
      )
    },
    test(
      "invalid fixture: negative token usage is not normalized into success"
    ) {
      for
        backend <- ScriptedModelBackend(
          ModelEvent.UsageObserved(
            a,
            Usage(inputTokens = -5, outputTokens = 3, currency = None),
            priceKnown = false
          ),
          ModelEvent.Finished(a, "stop")
        )
        events <- ZIO.scoped(
          backend.stream(ModelRequest("m", Nil, 8)).runCollect
        )
        // The fixture emits what it is given verbatim; adversarial data stays
        // visible to the consumer (it can never be folded into a clean bill).
        usage = events.collectFirst { case ModelEvent.UsageObserved(_, u, _) =>
          u
        }
      yield assertTrue(
        backend.fixtureMode,
        usage.contains(Usage(-5, 3, None)),
        // sanity: token sums with negative inputs never look like free money
        !Usage(-5, 3, None).plus(Usage(5, 3, None)).inputTokens.>(0)
      )
    },
    test("output bound: request bound is recorded, mock never inflates it") {
      for
        backend <- ScriptedModelBackend(
          ModelEvent.TextDelta(a, "x" * 1000),
          ModelEvent.Finished(a, "length")
        )
        req = ModelRequest("m", Nil, maxOutputTokens = 16)
        events <- ZIO.scoped(backend.stream(req).runCollect)
        recorded <- backend.requests
      yield assertTrue(
        backend.fixtureMode,
        recorded.head.maxOutputTokens == 16,
        recorded.head.maxOutputTokens < 1000,
        events
          .collectFirst { case ModelEvent.Finished(_, r) => r }
          .contains("length")
      )
    }
  )
