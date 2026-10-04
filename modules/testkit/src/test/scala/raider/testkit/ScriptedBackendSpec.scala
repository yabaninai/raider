package raider.testkit

import raider.core.*
import zio.{Scope, ZIO}
import zio.test.Assertion.*
import zio.test._

import scala.collection.mutable

object ScriptedBackendSpec extends ZIOSpecDefault:

  private val attempt = AttemptId("att-1")

  def spec = suite("ScriptedModelBackend")(
    test("emits the exact scripted event sequence and records the request") {
      for
        backend <- ScriptedModelBackend(
          ModelEvent.Started(attempt),
          ModelEvent.TextDelta(attempt, "func "),
          ModelEvent.TextDelta(attempt, "✓"),
          ModelEvent
            .UsageObserved(attempt, Usage(3, 2, None), priceKnown = false),
          ModelEvent.Finished(attempt, "stop")
        )
        req = ModelRequest("fast", List(RequestMessage("user", "verify")), 64)
        events <- ZIO.scoped(backend.stream(req).runCollect)
        recorded <- backend.requests
      yield assertTrue(
        events.toList == backend.plannedEvents.toList,
        recorded.toList == List(req),
        backend.fixtureMode
      )
    },
    test("two runs are deterministic and both recorded") {
      for
        backend <- ScriptedModelBackend(ModelEvent.TextDelta(attempt, "same"))
        req1 = ModelRequest("fast", Nil, 8)
        req2 = ModelRequest("fast", Nil, 8)
        e1 <- ZIO.scoped(backend.stream(req1).runCollect)
        e2 <- ZIO.scoped(backend.stream(req2).runCollect)
        recorded <- backend.requests
      yield assertTrue(e1 == e2, recorded.size == 2)
    },
    test("failing backend yields a typed Failed terminal event") {
      for
        backend <- ScriptedModelBackend.failing(
          RaiderError.ProviderRateLimit("429")
        )
        events <- ZIO.scoped(
          backend.stream(ModelRequest("fast", Nil, 8)).runCollect
        )
      yield assertTrue(
        events.toList == List(
          ModelEvent.Failed(
            AttemptId("scripted-1"),
            RaiderError.ProviderRateLimit("429")
          )
        )
      )
    }
  )
