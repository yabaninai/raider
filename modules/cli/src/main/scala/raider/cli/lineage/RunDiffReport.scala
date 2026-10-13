package raider.cli.lineage

import raider.runtime.lineage.model.SessionGraph
import raider.runtime.lineage.replay.{
  Divergence,
  DivergenceReason,
  GraphComparator,
  GraphDiff
}

/** Header metadata of a run diff report (LIN-06C, packet L52, ADR-027 §2): the
  * run label plus the node counters of the two compared graphs — exactly the
  * render inputs that are not already inside [[GraphDiff]]. Plain data, no
  * clocks, no environment reads.
  */
final case class RunDiffMeta(
    runId: String,
    expectedNodes: Int,
    actualNodes: Int
)

/** Deterministic text report of one normalized replay divergence (LIN-06C,
  * packet L52, ADR-027 §2 "live re-run: ... diff report"): the live re-run
  * replayed the recorded campaign against an isolated workspace copy
  * ([[WorkspaceIsolation]]) and the replayed graph diverged from the recording
  * — this renders WHY, once, byte-stably.
  *
  * Determinism (LIN6-C-03): [[render]] is a pure function of its arguments — no
  * wall clock, no network, no model, no heap-order iteration — so repeated
  * renders of the same [[GraphDiff]]/[[RunDiffMeta]] are byte-identical. The
  * hashes are the canonical normalized hashes carried by [[GraphDiff]]
  * (`compareNormalized` neutralizes run-varying identity: job ids, root/
  * session, `atMs`), so the same structural drift renders the same text on
  * every lane and every rerun.
  */
final case class RunDiffReport(diff: GraphDiff, meta: RunDiffMeta):

  /** The deterministic text — see [[RunDiffReport.render]]. */
  def render: String = RunDiffReport.render(diff, meta)

object RunDiffReport:

  /** `runId` for callers that have no run label (rendered verbatim). */
  val DefaultRunId = "unlabeled"

  private val Absent = "-"
  private val GraphHeader = "(graph-header)"

  /** Builds the report through
    * [[raider.runtime.lineage.replay.GraphComparator.compareNormalized]]:
    *   - `Left(message)` — the graphs AGREE (a Match is not a report; the
    *     message carries the shared normalized hash for the success log);
    *   - `Right(report)` — the first normalized divergence plus both node
    *     counters and the run label, ready for [[render]].
    */
  def fromGraphs(
      expected: SessionGraph,
      actual: SessionGraph,
      runId: String = DefaultRunId
  ): Either[String, RunDiffReport] =
    GraphComparator.compareNormalized(expected, actual) match
      case Left(diff) =>
        Right(
          RunDiffReport(
            diff,
            RunDiffMeta(
              runId = runId,
              expectedNodes = expected.nodes.size,
              actualNodes = actual.nodes.size
            )
          )
        )
      case Right(hash) =>
        Left(s"graphs match: no divergence to report (normalized hash $hash)")

  /** The deterministic report text (see the object scaladoc), one line per
    * fact: the header (`runId`, expected/actual normalized hashes), the first
    * divergence (path root→node, reason, BOTH sides) and the node counters. A
    * divergence located on the graph-header fields (no node) renders the
    * `(graph-header)` path label.
    */
  def render(diff: GraphDiff, meta: RunDiffMeta): String =
    val divergence = diff.firstDivergence
    val (reason, expectedSide, actualSide) = describe(divergence)
    List(
      s"run-diff-report ${meta.runId}",
      s"expected-hash: ${diff.expectedHash}",
      s"actual-hash: ${diff.actualHash}",
      s"first-divergence: $reason",
      s"path: ${divergence.rootId.value} -> " +
        divergence.nodeId.fold(GraphHeader)(_.value),
      s"expected: $expectedSide",
      s"actual: $actualSide",
      s"nodes: expected=${meta.expectedNodes} actual=${meta.actualNodes}"
    ).mkString("\n")

  /** `(reason, expected side, actual side)` of one divergence — the canonical
    * VALUE convention (`-` for the absent side), unescaped, diagnostic-only
    * (the hash itself stays the GraphHash canonical form).
    */
  private def describe(d: Divergence): (String, String, String) =
    val node = d.nodeId.fold(GraphHeader)(_.value)
    d.reason match
      case DivergenceReason.MissingNode =>
        ("missing-node", node, Absent)
      case DivergenceReason.ExtraNode =>
        ("extra-node", Absent, node)
      case DivergenceReason.KindMismatch(expectedKind, actualKind) =>
        ("kind-mismatch", s"kind=$expectedKind", s"kind=$actualKind")
      case DivergenceReason.FieldMismatch(field, expected, actual) =>
        (s"field-mismatch($field)", expected, actual)

end RunDiffReport
