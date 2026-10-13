package raider.cli.run

import raider.cli.lineage.SessionGraphView
import raider.dsl.lineage.data.{
  DataNode,
  GeneratorInfo,
  LineageDataDocument,
  NodeData,
  NodeKind,
  ProvenanceArtifact,
  ProvenanceRecord,
  Usage
}
import raider.dsl.lineage.proj.LineageRenderer
import raider.dsl.lineage.store.LineageDataStore
import raider.runtime.events.{EventPayload, ToolOutcome, UsageSnapshot}
import raider.runtime.lineage.model.{SessionGraph, SessionNode}
import raider.runtime.lineage.wire.{LineageEnvelope, LineagePayload}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.util.control.NonFatal

/** LIN-09 (ADR-027 §2/§6.5, docs/lineage.md §9.2 "Writing artifacts to the run
  * directory"): writes the settled run's lineage artifacts NEXT TO the run's
  * own events.jsonl/summary.md — `lineage-data.json` (written through the
  * accepted atomic store [[LineageDataStore]]; schemaVersion 1 as frozen, or
  * the ADR-032 v2 shape when the run exported joined child segments) and
  * `lineage.scala` (the deterministic [[SessionGraphView]] →
  * [[LineageRenderer]] projection, written with the SAME atomic tmp+ATOMIC_MOVE
  * pattern). The run directory is per-invocation (`work-<uuid>`), so parallel
  * runs never share a target path — isolation is structural (LIN9-03), not
  * locking.
  *
  * Total by contract: every failure is a typed [[ArtifactError]], never an
  * exception, so a projection/store failure can never change the run's exit
  * code — the caller renders it as the honest summary line (LIN9-02).
  *
  * Content honesty: the artifact is built ONLY from the settled
  * [[SessionGraph]] and the run's own exported envelopes. The frozen headless
  * wire (ADR-024 §5) carries no model prompt/response text and no tool result
  * tail, so `ModelCallData.prompt/response` record the empty string (the
  * schema's only "nothing captured" value for a required string) and
  * `ToolCallData.resultTail` records null — the wire summaries that DO exist
  * (`argsSummary`, usage snapshot, outcome, recovery class) are recorded for
  * real. Nothing is invented; events.jsonl stays the full per-run record.
  * Delegation nodes carry no `dataRef` (wave-1 model: their payload is not
  * addressable — the documented [[SessionGraphView]] mapping), so they get no
  * data entry.
  */
object RunLineageArtifacts:

  final val SourceName = "lineage.scala"
  final val DataName = "lineage-data.json"

  /** Typed artifact failure (LIN9-02): which frozen layer refused, with its own
    * typed detail. Rendered verbatim into summary.md.
    */
  enum ArtifactError:
    case View(detail: String)
    case Render(detail: String)
    case Store(detail: String)
    case Io(detail: String)

  final case class Written(source: Path, data: Path)

  /** Builds and atomically writes both artifacts into `workDir`. Order: project
    * (pure) → write lineage-data.json ([[LineageDataStore]]) → write
    * lineage.scala; the data resource lands first because the projection's
    * `// data:` refs resolve into it — a failed source write leaves the
    * persisted document in place for reconciliation (card compatibility note),
    * never the reverse.
    *
    * RAI-144: the input is the run's exported VERSIONED stream (the exact
    * `events.jsonl` lines) and the cross-run provenance record the reduction
    * returned. When the run joined child segments, the document is written as
    * lineage-data SCHEMAVERSION 2 (ADR-032 §4) carrying the top-level
    * `provenance` record; a delegation-free run stays byte-identical to the
    * frozen schemaVersion-1 document. The wave-1 writer convention is kept:
    * delegation nodes carry no `dataRef`, so they get no data entry — the
    * per-segment `invocationId` correlation lives on the joined v2 envelopes of
    * events.jsonl (its durable surface); run-written
    * `delegationData.invocationId` entries would need a dataRef convention of
    * their own and belong to the corpus card (RAI-146), not to this writer.
    */
  def write(
      workDir: Path,
      graph: SessionGraph,
      wire: Vector[LineageEnvelope],
      provenance: Option[LineagePayload.RunProvenance]
  ): Either[ArtifactError, Written] =
    try
      val source = workDir.resolve(SourceName)
      val data = workDir.resolve(DataName)
      for
        _ <- Either.cond(
          !graph.nodes.exists(_.isInstanceOf[SessionNode.Phase]),
          (),
          ArtifactError.View(
            "phase-bearing graph: the wave-1 SessionNode.Phase carries no atMs and the headless reducer never emits Phase nodes"
          )
        )
        view <- SessionGraphView
          .toView(graph)
          .left
          .map(e => ArtifactError.View(String.valueOf(e)))
        rendered <- LineageRenderer
          .render(view)
          .left
          .map(e => ArtifactError.Render(String.valueOf(e)))
        _ <- LineageDataStore
          .write(data, documentOf(graph, wire, provenance))
          .left
          .map(e => ArtifactError.Store(String.valueOf(e)))
        _ <- writeSourceAtomically(source, rendered)
      yield Written(source, data)
    catch case NonFatal(t) => Left(ArtifactError.Io(String.valueOf(t)))

  /** The lineage-data document of the settled campaign: nodes are the graph's
    * own nodes in emission order (ids/parents/inputs/seq/atMs verbatim;
    * dataRefs as the reducer defined them), the data resource binds the
    * wire-exported summaries to those refs. The generator fields mirror the
    * pinned build (`ThisBuild / version`, `ThisBuild / scalaVersion` in
    * build.sbt; the schema const-checks scalaVersion == 3.9.0).
    *
    * RAI-144: the payload index scans the run's VERSIONED stream (the frozen
    * cases inside the v2 envelopes included — the joined child segments bind
    * their summaries exactly like the parent's). The document becomes
    * schemaVersion 2 with the provenance record (ADR-032 §4) exactly when the
    * record is present — a v1 document never carries a v2 field
    * (validator-checked, never silently ignored).
    */
  private def documentOf(
      graph: SessionGraph,
      wire: Vector[LineageEnvelope],
      provenance: Option[LineagePayload.RunProvenance]
  ): LineageDataDocument =
    // One pass over the run's own envelopes (frozen payloads, joined child
    // segments included): the two payload summaries the wire actually exports
    // (ToolStarted.argsSummary by (job, callId); ModelCallFinished usage by
    // (job, round)).
    val args =
      scala.collection.mutable.HashMap.empty[(String, String), String]
    val usage =
      scala.collection.mutable.HashMap.empty[(String, Int), UsageSnapshot]
    wire.foreach { line =>
      line.payload match
        case LineagePayload.Frozen(p) =>
          p match
            case EventPayload.ToolStarted(callId, _, summary) =>
              args((line.jobId.value, callId)) = summary
            case EventPayload.ModelCallFinished(round, _, snapshot) =>
              usage((line.jobId.value, round)) = snapshot
            case _ => ()
        case _ => () // v2 payloads carry no data-entry summaries
    }
    val nodes = Vector.newBuilder[DataNode]
    val data = scala.collection.mutable.HashMap.empty[String, NodeData]
    graph.nodes.foreach {
      case m: SessionNode.ModelCall =>
        nodes += DataNode(
          m.id.value,
          NodeKind.ModelCall,
          m.parent.map(_.value),
          m.seq,
          m.atMs,
          m.inputs.map(_.value),
          Some(m.dataRef.value)
        )
        data(m.dataRef.value) = NodeData.ModelCallData(
          m.model,
          m.round,
          prompt = "",
          response = "",
          usage
            .get((jobOf(m.id.value), m.round))
            .map(s =>
              Usage(
                Some(s.reserved.value),
                Some(s.observed.value),
                s.unknown
              )
            )
        )
      case t: SessionNode.ToolCall =>
        nodes += DataNode(
          t.id.value,
          NodeKind.ToolCall,
          t.parent.map(_.value),
          t.seq,
          t.atMs,
          t.inputs.map(_.value),
          t.dataRef.map(_.value)
        )
        t.dataRef.foreach { ref =>
          data(ref.value) = NodeData.ToolCallData(
            t.tool,
            t.callId,
            SessionGraphView.recoveryName(t.recovery),
            t.outcome.map(outcomeName),
            args.getOrElse((jobOf(t.id.value), t.callId), ""),
            resultTail = None
          )
        }
      case d: SessionNode.Delegation =>
        nodes += DataNode(
          d.id.value,
          NodeKind.Delegation,
          d.parent.map(_.value),
          d.seq,
          d.atMs,
          d.inputs.map(_.value),
          dataRef = None
        )
      case p: SessionNode.Phase =>
        // Unreachable through write(): phase-bearing graphs are refused above
        // (no atMs in the wave-1 model, and the headless reducer never emits
        // Phase). Kept for exhaustiveness — structurally complete, never a
        // silent drop.
        nodes += DataNode(
          p.id.value,
          NodeKind.Phase,
          p.parent.map(_.value),
          p.seq,
          0L,
          p.inputs.map(_.value),
          dataRef = None
        )
    }
    LineageDataDocument(
      schemaVersion = if provenance.isDefined then 2 else 1,
      generator = GeneratorInfo(
        "raider",
        "0.1.0-SNAPSHOT",
        "3.9.0",
        jdkVersion
      ),
      runId = graph.rootId.value,
      nodes = nodes.result(),
      data = data.toMap,
      provenance = provenance.map(r =>
        ProvenanceRecord(
          r.parentRunId,
          r.artifacts.map(a => ProvenanceArtifact(a.kind, a.path))
        )
      )
    )

  /** The reducer's node id convention `<job>:n<seq>` → the job part that keys
    * the envelope payload index.
    */
  private def jobOf(nodeId: String): String =
    val cut = nodeId.lastIndexOf(":n")
    if cut <= 0 then nodeId else nodeId.substring(0, cut)

  /** SessionNode outcome → the frozen lineage-data v1 outcome class strings
    * (the same vocabulary the goldens and the compile-gate fixture accept).
    */
  private def outcomeName(o: ToolOutcome): String = o match
    case ToolOutcome.Succeeded => "Succeeded"
    case ToolOutcome.Failed(_) => "Failed"
    case ToolOutcome.Cancelled => "Cancelled"
    case ToolOutcome.Timeout   => "Timeout"

  /** The pinned JVM major (schema: string-encoded digits, e.g. "21"). */
  private def jdkVersion: String =
    System.getProperty("java.specification.version", "21")

  /** The [[LineageDataStore]] write pattern for the generated source: unique
    * tmp file in the target directory + ATOMIC_MOVE. A reader of the target
    * always sees a whole file; writers to distinct run directories never
    * interact. Best-effort tmp cleanup can never mask the primary result.
    */
  private def writeSourceAtomically(
      path: Path,
      content: String
  ): Either[ArtifactError, Unit] =
    try
      val dir = Option(path.toAbsolutePath.getParent).getOrElse(Path.of("."))
      Files.createDirectories(dir)
      val tmp =
        Files.createTempFile(dir, path.getFileName.toString + ".", ".tmp")
      try
        Files.writeString(tmp, content, UTF_8)
        Files.move(
          tmp,
          path,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE
        )
        Right(())
      finally
        try
          Files.deleteIfExists(tmp)
          ()
        catch case _: java.io.IOException => ()
    catch
      case e: java.io.IOException =>
        Left(ArtifactError.Io(String.valueOf(e.getMessage)))

end RunLineageArtifacts
