package raider.cli.lineage

import zio.{Scope, ZIO}
import zio.test.*

import raider.core.{
  AttemptId,
  CapabilityStatus,
  ModelEvent,
  ModelRequest,
  RaiderError,
  RequestMessage,
  Usage
}
import raider.dsl.lineage.data.{
  GeneratorInfo,
  LineageDataDocument,
  NodeData,
  Usage as LineageUsage
}
import raider.dsl.lineage.replay.RecordedSource

/** LIN6-A acceptance (LIN-06A, ADR-027 §2 — recorded answers as ModelBackend):
  *
  *   - LIN6-A-01 — three recorded answers replay in full via three sequential
  *     stream calls: ORDINAL association over the sorted, model-call-filtered
  *     dataRefs (insertion order is deliberately shuffled in the fixture to pin
  *     the rule), synthetic single-chunk text, usage from the record, and a
  *     repeat replay with a fresh backend over the same record is identical;
  *   - LIN6-A-02 — the 4th call beyond 3 records fails typed
  *     `ReplayBackendError.Overrun(4, 3)` over the frozen RaiderError channel;
  *   - LIN6-A-03 — a source with no model-call data (phase entry only, or an
  *     empty document) fails `ReplayBackendError.Exhausted` immediately;
  *   - LIN6-A-04 — capabilities are honest: streaming Supported (synthetic
  *     single chunk), tools Unsupported for a PLAIN-TEXT record (no tool_use),
  *     cancellationAck Unknown.
  *
  * LIN-06B-1 additions (packet L48, ADR-027 §2 — tool rounds for the end-to-end
  * replay harness):
  *   - LIN6-B-11 — a recorded response carrying the `tool_calls` envelope
  *     replays as Started → ToolCallReady(s) → UsageObserved? →
  *     Finished("tool_calls"), repeat-identical, and makes `tools` Supported;
  *   - LIN6-B-12 — ordinary JSON responses stay plain-text rounds (only the
  *     frozen envelope triggers translation), and a MIXED record plays
  *     ordinally: tool round, then text round.
  */
object ReplayModelBackendSpec extends ZIOSpecDefault:

  private def callData(
      response: String,
      usage: Option[LineageUsage] = None
  ): NodeData.ModelCallData =
    NodeData.ModelCallData(
      model = "recorded-model",
      round = 1,
      prompt = "recorded prompt",
      response = response,
      usage = usage
    )

  private def doc(entries: (String, NodeData)*): LineageDataDocument =
    LineageDataDocument(
      schemaVersion = 1,
      generator = GeneratorInfo("replay-l46", "0", "3.9.0", "21"),
      runId = "run-l46",
      nodes = Vector.empty,
      data = entries.toMap
    )

  // Fixture deliberately inserts refs in NON-sorted order: the deterministic
  // ORDINAL order is RecordedSource.dataRefs (sorted) filtered to model-call
  // data, so playback must be a-ref -> m-ref -> z-ref.
  private def fixtureDoc: LineageDataDocument = doc(
    "z-ref" -> callData("third", Some(LineageUsage(None, None, true))),
    "a-ref" -> callData("first"),
    "m-ref" -> callData(
      "second",
      Some(LineageUsage(Some(1200), Some(900), false))
    )
  )

  private def request(turn: Int): ModelRequest =
    ModelRequest(
      "recorded-model",
      List(RequestMessage("user", s"turn $turn")),
      128,
      Nil
    )

  private def consume(
      backend: ReplayModelBackend,
      turn: Int
  ): ZIO[Scope, RaiderError, Vector[ModelEvent]] =
    backend.stream(request(turn)).runCollect.map(_.toVector)

  private def typed(
      result: Either[RaiderError, Vector[ModelEvent]]
  ): Option[ReplayBackendError] =
    result.fold(ReplayBackendError.fromRaiderError, _ => None)

  private def expected(
      ordinal: Int,
      response: String,
      usage: Option[(Usage, Boolean)] = None
  ): Vector[ModelEvent] =
    val attempt = AttemptId(s"replay-$ordinal")
    Vector(
      ModelEvent.Started(attempt),
      ModelEvent.TextDelta(attempt, response)
    ) ++ usage.map { case (u, priceKnown) =>
      ModelEvent.UsageObserved(attempt, u, priceKnown)
    } :+ ModelEvent.Finished(attempt, "stop")

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ReplayModelBackend")(
      test(
        "LIN6-A-01: three recorded answers replay in full, ordinally, repeat identical"
      ) {
        val source = RecordedSource(fixtureDoc)
        val backend = ReplayModelBackend(source, fixtureDoc)
        val repeat = ReplayModelBackend(source, fixtureDoc)
        for
          run1 <- ZIO.scoped(consume(backend, 1))
          run2 <- ZIO.scoped(consume(backend, 2))
          run3 <- ZIO.scoped(consume(backend, 3))
          again1 <- ZIO.scoped(consume(repeat, 1))
          again2 <- ZIO.scoped(consume(repeat, 2))
          again3 <- ZIO.scoped(consume(repeat, 3))
        yield assertTrue(
          run1 == expected(1, "first"),
          run2 == expected(
            2,
            "second",
            Some((Usage(0, 0, None), true))
          ),
          run3 == expected(3, "third", Some((Usage(0, 0, None), false))),
          again1 == run1,
          again2 == run2,
          again3 == run3
        )
      },
      test("LIN6-A-02: the 4th call beyond 3 records fails typed Overrun") {
        val backend = ReplayModelBackend(RecordedSource(fixtureDoc), fixtureDoc)
        for
          _ <- ZIO.scoped(consume(backend, 1))
          _ <- ZIO.scoped(consume(backend, 2))
          _ <- ZIO.scoped(consume(backend, 3))
          fourth <- ZIO.scoped(consume(backend, 4)).either
        yield assertTrue(
          fourth.isLeft,
          fourth.fold(_.code, _ => "") == "RA-STREAM",
          typed(fourth) == Some(ReplayBackendError.Overrun(4, 3))
        )
      },
      test(
        "LIN6-A-03: a source without model-call data fails Exhausted immediately"
      ) {
        val phaseOnly = ReplayModelBackend(
          RecordedSource(doc("phase-1" -> NodeData.PhaseData("plan", None))),
          doc("phase-1" -> NodeData.PhaseData("plan", None))
        )
        val emptyDoc = ReplayModelBackend(RecordedSource(doc()), doc())
        for
          first <- ZIO.scoped(consume(phaseOnly, 1)).either
          second <- ZIO.scoped(consume(phaseOnly, 2)).either
          empty <- ZIO.scoped(consume(emptyDoc, 1)).either
        yield assertTrue(
          typed(first) == Some(ReplayBackendError.Exhausted),
          typed(second) == Some(ReplayBackendError.Exhausted),
          typed(empty) == Some(ReplayBackendError.Exhausted)
        )
      },
      test("LIN6-A-04: capabilities are honest") {
        val backend =
          ReplayModelBackend(RecordedSource(fixtureDoc), fixtureDoc)
        val caps = backend.capabilities
        assertTrue(
          caps.streaming == CapabilityStatus.Supported,
          caps.tools == CapabilityStatus.Unsupported,
          caps.cancellationAck == CapabilityStatus.Unknown
        )
      },
      test(
        "LIN6-B-11: a tool_calls envelope replays as ToolCallReady + Finished(tool_calls), tools Supported"
      ) {
        val toolResponse =
          """{"tool_calls":[{"id":"call-1","name":"fs_read","arguments":{"path":"notes.txt"}}]}"""
        val toolDoc = doc("r1" -> callData(toolResponse))
        val backend = ReplayModelBackend(RecordedSource(toolDoc), toolDoc)
        val caps = backend.capabilities
        for run <- ZIO.scoped(consume(backend, 1))
        yield
          val attempt = AttemptId("replay-1")
          assertTrue(
            caps.tools == CapabilityStatus.Supported,
            run == Vector(
              ModelEvent.Started(attempt),
              ModelEvent.ToolCallReady(
                attempt,
                raider.core.ToolCallId("call-1"),
                "fs_read",
                """{"path":"notes.txt"}"""
              ),
              ModelEvent.Finished(attempt, "tool_calls")
            )
          )
      },
      test(
        "LIN6-B-12: ordinary JSON stays a text round; a MIXED record plays ordinally (tool round, then text)"
      ) {
        val mixedDoc = doc(
          "r1" -> callData(
            """{"tool_calls":[{"id":"c9","name":"fs_tree","arguments":{}}]}"""
          ),
          "r2" -> callData("""{"answer":1}""")
        )
        val backend = ReplayModelBackend(RecordedSource(mixedDoc), mixedDoc)
        val repeat = ReplayModelBackend(RecordedSource(mixedDoc), mixedDoc)
        for
          first <- ZIO.scoped(consume(backend, 1))
          second <- ZIO.scoped(consume(backend, 2))
          again <- ZIO.scoped(consume(repeat, 1))
        yield
          val a1 = AttemptId("replay-1")
          val a2 = AttemptId("replay-2")
          assertTrue(
            // ordinal 1 = sorted "r1" = the tool round
            first == Vector(
              ModelEvent.Started(a1),
              ModelEvent.ToolCallReady(
                a1,
                raider.core.ToolCallId("c9"),
                "fs_tree",
                "{}"
              ),
              ModelEvent.Finished(a1, "tool_calls")
            ),
            // ordinal 2 = "r2": JSON but NOT the envelope → plain text round
            second == Vector(
              ModelEvent.Started(a2),
              ModelEvent.TextDelta(a2, """{"answer":1}"""),
              ModelEvent.Finished(a2, "stop")
            ),
            again == first
          )
      }
    )

end ReplayModelBackendSpec
