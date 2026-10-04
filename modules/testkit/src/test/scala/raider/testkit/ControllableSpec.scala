package raider.testkit

import raider.core.*
import zio.test._
import zio.{Scope, ZIO}
import zio.stream.ZStream

/** RAI-006.b (MOCK-03) primitive fixtures: record-then-park semantics give
  * race-free control points — no sleeps, no live clock. */
object ControllableSpec extends ZIOSpecDefault:

  private val a = AttemptId("gated-att")

  def spec = suite("Controllable primitives")(
    test("GatedModelBackend: request recorded ⇒ parked; open(k) releases exactly that call") {
      for
        backend <- GatedModelBackend(
          Vector[ModelEvent](ModelEvent.TextDelta(a, "first")),
          Vector[ModelEvent](ModelEvent.TextDelta(a, "second")))
        // recorded ⇒ parked: collect does NOT complete until the gate opens
        collect1 <- backend.stream(ModelRequest("m", Nil, 8)).runCollect.forkDaemon
        _        <- backend.requests.repeatUntil(_.size == 1)
        _        <- backend.open(0)
        e1       <- collect1.join
        collect2 <- backend.stream(ModelRequest("m", Nil, 8)).runCollect.forkDaemon
        _        <- backend.requests.repeatUntil(_.size == 2)
        _        <- backend.open(1)
        e2       <- collect2.join
        exhausted <- backend.stream(ModelRequest("m", Nil, 8)).runCollect.either
      yield assertTrue(
        e1.toList == List(ModelEvent.TextDelta(a, "first")),
        e2.toList == List(ModelEvent.TextDelta(a, "second")),
        backend.fixtureMode,
        exhausted.isLeft,
        exhausted.fold(_.code == "RA-STREAM", _ => false))
    },
    test("GatedTool: started ⇒ parked at gate; open releases the invoke") {
      for
        tool <- GatedTool(name = "slowread")
        fiber <- tool.invoke("1").forkDaemon
        calls <- tool.started.repeatUntil(_.nonEmpty)
        _ <- tool.open
        out <- fiber.join
        callsAfter <- tool.started
      yield assertTrue(
        calls == Vector("1"),
        callsAfter == Vector("1"),
        out == """{"ok":true}""")
    }
  )
