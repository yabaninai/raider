package raider.testkit

import raider.core.*
import zio.{Scope, ZIO}
import zio.test.Assertion.*
import zio.test._

/** ScriptedSequenceBackend (RAI-010.a testkit extension): one script per
  * stream() call; extra calls fail typed; everything is recorded.
  */
object ScriptedSequenceBackendSpec extends ZIOSpecDefault:

  private val a = AttemptId("seq-att")

  def spec = suite("ScriptedSequenceBackend")(
    test("k-th stream() call plays k-th script and records every request") {
      for
        backend <- ScriptedSequenceBackend(
          Vector[ModelEvent](
            ModelEvent.Started(a),
            ModelEvent.ToolCallReady(
              a,
              ToolCallId("call-1"),
              "lookup",
              """{"q":"x"}"""
            ),
            ModelEvent.Finished(a, "tool_round")
          ),
          Vector[ModelEvent](
            ModelEvent.Started(a),
            ModelEvent.TextDelta(a, "final"),
            ModelEvent.Finished(a, "stop")
          )
        )
        r1 = ModelRequest("m", List(RequestMessage("user", "round1")), 16)
        r2 = ModelRequest("m", List(RequestMessage("user", "round2")), 16)
        e1 <- ZIO.scoped(backend.stream(r1).runCollect)
        e2 <- ZIO.scoped(backend.stream(r2).runCollect)
        recorded <- backend.requests
      yield assertTrue(
        backend.fixtureMode,
        e1.toList == backend.plannedScripts(0).toList,
        e2.toList == backend.plannedScripts(1).toList,
        recorded.toList == List(r1, r2)
      )
    },
    test("extra call beyond the sequence is a typed StreamProtocol failure") {
      for
        backend <- ScriptedSequenceBackend(
          Vector[ModelEvent](ModelEvent.TextDelta(a, "only"))
        )
        first <- ZIO.scoped(
          backend.stream(ModelRequest("m", Nil, 8)).runCollect.either
        )
        second <- ZIO.scoped(
          backend.stream(ModelRequest("m", Nil, 8)).runCollect.either
        )
      yield assertTrue(
        first.isRight,
        second.isLeft,
        second.fold(_.code == "RA-STREAM", _ => false)
      )
    }
  )
