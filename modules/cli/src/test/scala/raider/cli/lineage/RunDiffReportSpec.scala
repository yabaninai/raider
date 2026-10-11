package raider.cli.lineage

import raider.core.{RootId, SessionId}
import raider.runtime.lineage.model.{
  DataRef,
  NodeId,
  RecoveryClass,
  SessionGraph,
  SessionNode
}
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8

/** LIN-06C acceptance (packet L52, ADR-027 §2): the deterministic diff-report
  * renderer for the live re-run is pinned against the REAL
  * [[raider.runtime.lineage.replay.GraphComparator.compareNormalized]]:
  *   - LIN6-C-03 (MAIN): `render` of one GraphDiff is byte-identical on repeat;
  *     the header (runId + both normalized hashes), the first divergence (path
  *     root→node, reason, BOTH sides) and the node counters are exact;
  *   - a missing-node divergence renders the same way with the expected-side
  *     node id and the exact expected/actual counters;
  *   - `fromGraphs` over two agreeing graphs yields Left (a Match is not a
  *     report) with the normalized hash.
  *
  * The fixtures are minimal validated [[SessionGraph]]s; `compareNormalized`
  * rewrites the run-varying identity (root `root-l52` → `root`, job `j1` →
  * `job-1`), which the expected rendered paths pin.
  */
object RunDiffReportSpec extends ZIOSpecDefault:

  /** Fixture graph: one model round (id `j1:n4`) plus an optional child tool
    * call (id `j1:n7`); `model` is the knob that produces a field divergence.
    */
  private def graph(model: String, withTool: Boolean): SessionGraph =
    val modelCall = SessionNode.ModelCall(
      NodeId("j1:n4"),
      None,
      4L,
      0L,
      1,
      model,
      Vector.empty,
      DataRef("j1/round-1")
    )
    val nodes =
      if withTool then
        Vector(
          modelCall,
          SessionNode.ToolCall(
            NodeId("j1:n7"),
            Some(NodeId("j1:n4")),
            7L,
            0L,
            "call-1",
            "fs_read",
            RecoveryClass.ReadOnly,
            None,
            Vector(NodeId("j1:n4")),
            None
          )
        )
      else Vector(modelCall)
    SessionGraph(RootId("root-l52"), SessionId("sess-l52"), nodes).validated
      .fold(
        violation => sys.error(s"fixture bug: graph invalid: $violation"),
        identity
      )

  private def linesOf(text: String): Vector[String] =
    text.split("\n").toVector

  def spec: Spec[TestEnvironment, Any] = suite("RunDiffReport")(
    test(
      "LIN6-C-03: render is byte-identical on repeat; header, first divergence (path/reason/both sides) and counters are exact"
    ) {
      val expected = graph("model-a", withTool = true)
      val actual = graph("model-b", withTool = true)
      RunDiffReport.fromGraphs(expected, actual, "run-l52") match
        case Right(report) =>
          val once = report.render
          val again = RunDiffReport.render(report.diff, report.meta)
          val lines = linesOf(once)
          assertTrue(
            java.util.Arrays
              .equals(once.getBytes(UTF_8), again.getBytes(UTF_8)),
            once == again,
            lines.length == 8,
            lines(0) == "run-diff-report run-l52",
            lines(1) == s"expected-hash: ${report.diff.expectedHash}",
            lines(2) == s"actual-hash: ${report.diff.actualHash}",
            lines(3) == "first-divergence: field-mismatch(model)",
            lines(4) == "path: root -> job-1:n4",
            lines(5) == "expected: model-a",
            lines(6) == "actual: model-b",
            lines(7) == "nodes: expected=2 actual=2"
          )
        case Left(notReported) =>
          throw new IllegalStateException(
            s"expected a diff report, got Left: $notReported"
          )
    },
    test(
      "LIN6-C-03b: a missing-node divergence renders the normalized path and the exact expected/actual counters"
    ) {
      val expected = graph("model-a", withTool = true) // 2 nodes
      val actual = graph("model-a", withTool = false) // 1 node
      RunDiffReport.fromGraphs(expected, actual) match
        case Right(report) =>
          val lines = linesOf(report.render)
          assertTrue(
            lines(0) == "run-diff-report unlabeled",
            lines(3) == "first-divergence: missing-node",
            lines(4) == "path: root -> job-1:n7",
            lines(5) == "expected: job-1:n7",
            lines(6) == "actual: -",
            lines(7) == "nodes: expected=2 actual=1"
          )
        case Left(notReported) =>
          throw new IllegalStateException(
            s"expected a diff report, got Left: $notReported"
          )
    },
    test(
      "fromGraphs over agreeing graphs is Left with the normalized hash (a Match is not a report)"
    ) {
      val same = graph("model-a", withTool = true)
      RunDiffReport.fromGraphs(same, same, "run-same") match
        case Left(message) =>
          assertTrue(message.contains("graphs match"), message.nonEmpty)
        case Right(report) =>
          throw new IllegalStateException(
            s"expected Left (no divergence), got $report"
          )
    }
  )

end RunDiffReportSpec
