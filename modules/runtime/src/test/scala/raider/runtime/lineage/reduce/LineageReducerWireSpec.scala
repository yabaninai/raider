package raider.runtime.lineage.reduce

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.budget.MicroUsd
import raider.runtime.events.{
  EventEnvelope,
  EventPayload,
  ToolOutcome,
  UsageSnapshot,
  WaitReason
}
import raider.runtime.jobs.JobStatus
import raider.runtime.lineage.corpus.LineageCorpus
import raider.runtime.lineage.model.{
  DataRef,
  GraphHash,
  NodeId,
  RecoveryClass,
  SessionGraph,
  SessionNode
}
import raider.runtime.lineage.wire.{
  ArtifactPointer,
  LineageEnvelope,
  LineagePayload,
  LineageWire
}
import zio.test.*

/** SCH-02/SCH-03 acceptance (card RAI-143, ADR-032): the reducer maps the v2
  * payloads to graph nodes per the pinned hash policy (new metadata EXCLUDED
  * from the canonical form — GraphHash byte-for-byte unchanged) and refuses
  * malformed v2 streams with typed errors. The frozen v1 entry points keep
  * their pinned behavior.
  */
object LineageReducerWireSpec extends ZIOSpecDefault:

  private val root = JobId("job-1")
  private val child = JobId("job-1-child")
  private val rootId = RootId("root-1")
  private val sessionId = SessionId("sess-1")

  private def env(
      seq: Long,
      payload: LineagePayload,
      job: JobId = root,
      schemaVersion: Int = 2,
      invocationId: Option[String] = None
  ): LineageEnvelope =
    LineageEnvelope(
      schemaVersion,
      seq,
      job,
      rootId,
      sessionId,
      1760000000000L + seq * 250L,
      payload,
      invocationId
    )

  private def frozen(seq: Long, p: EventPayload, job: JobId = root) =
    env(
      seq,
      LineagePayload.Frozen(p),
      job = job,
      schemaVersion = 1
    )

  private val provenanceRecord =
    LineagePayload.RunProvenance(
      Some("run-parent-9"),
      Vector(ArtifactPointer("work_dir", "/tmp/run-9"))
    )

  /** Happy v2 campaign: phase round-trip, a child job with correlation, one
    * provenance record, exactly one Terminal per job.
    */
  private val happy: Vector[LineageEnvelope] = Vector(
    env(
      1L,
      LineagePayload.PhaseStarted("baseline", RecoveryClass.ReadOnly, None)
    ),
    frozen(2L, EventPayload.ModelCallStarted(1, "model-a", Some(1))),
    frozen(
      3L,
      EventPayload.ModelCallFinished(
        1,
        100L,
        UsageSnapshot(MicroUsd(10L), MicroUsd(9L), false)
      )
    ),
    env(4L, LineagePayload.PhaseFinished("baseline")),
    frozen(5L, EventPayload.Delegated(child, "worker-2")),
    env(6L, provenanceRecord),
    frozen(7L, EventPayload.Terminal(JobStatus.Succeeded)),
    env(
      1L,
      LineagePayload.Frozen(EventPayload.ModelCallStarted(1, "model-b", None)),
      job = child,
      invocationId = Some("call-9")
    ),
    env(
      2L,
      LineagePayload.Frozen(EventPayload.ToolStarted("call-1", "bash", "ls")),
      job = child,
      invocationId = Some("call-9")
    ),
    env(
      3L,
      LineagePayload.Frozen(
        EventPayload.ToolFinished("call-1", "bash", ToolOutcome.Succeeded)
      ),
      job = child,
      invocationId = Some("call-9")
    ),
    env(
      4L,
      LineagePayload.Frozen(EventPayload.Terminal(JobStatus.Succeeded)),
      job = child,
      invocationId = Some("call-9")
    )
  )

  private def rightOf(
      reduced: Either[ReduceError, LineageReduction]
  ): LineageReduction =
    reduced match
      case Right(r)    => r
      case Left(error) => throw new IllegalStateException(s"got: $error")

  def spec = suite("LineageReducerWire (SCH-02/SCH-03, ADR-032)")(
    // --------------------------------------------------------------
    // SCH-02: new payloads map to graph nodes per the hash policy
    // --------------------------------------------------------------
    test(
      "SCH-02 phase payloads create wire-native Phase nodes with v1 parent/input wiring"
    ) {
      val r = rightOf(LineageReducer.reduceWire(happy))
      val g = r.graph
      val byId = g.nodes.map(n => n.id.value -> n).toMap
      val phase = byId("job-1:n1")
      val round = byId("job-1:n2")
      val delegation = byId("job-1:n5")
      assertTrue(
        phase.isInstanceOf[SessionNode.Phase],
        phase.parent.isEmpty,
        phase.inputs.isEmpty,
        phase.asInstanceOf[SessionNode.Phase].name == "baseline",
        round.isInstanceOf[SessionNode.ModelCall],
        round.parent == Some(phase.id),
        round.inputs == Vector(phase.id),
        delegation.isInstanceOf[SessionNode.Delegation],
        // frozen v1 rule: delegation parents to the round last seen in the job
        delegation.parent == Some(round.id)
      )
    },
    test(
      "SCH-02 the child subtree joins under the Delegation node with correlation"
    ) {
      val r = rightOf(LineageReducer.reduceWire(happy))
      val childRound =
        r.graph.nodes.find(_.id.value == "job-1-child:n1").get
      val tool = r.graph.nodes.find(_.id.value == "job-1-child:n2").get
      val delegation = r.graph.nodes.find(_.id.value == "job-1:n5").get
      assertTrue(
        childRound.parent == Some(delegation.id),
        childRound.inputs == Vector(delegation.id),
        tool.parent == Some(childRound.id),
        r.provenance == Some(provenanceRecord)
      )
    },
    test(
      "SCH-02 hash policy: the graph hashes exactly like the equivalent v1-built graph"
    ) {
      val r = rightOf(LineageReducer.reduceWire(happy))
      val handBuilt = SessionGraph(
        rootId,
        sessionId,
        Vector(
          SessionNode.Phase(NodeId("job-1:n1"), None, 1L, "baseline", None),
          SessionNode.ModelCall(
            NodeId("job-1:n2"),
            Some(NodeId("job-1:n1")),
            2L,
            1760000000500L,
            1,
            "model-a",
            Vector(NodeId("job-1:n1")),
            DataRef("job-1/round-1")
          ),
          SessionNode.Delegation(
            NodeId("job-1:n5"),
            Some(NodeId("job-1:n2")),
            5L,
            1760000001250L,
            child,
            "worker-2",
            Vector(NodeId("job-1:n2"))
          ),
          SessionNode.ModelCall(
            NodeId("job-1-child:n1"),
            Some(NodeId("job-1:n5")),
            1L,
            1760000000250L,
            1,
            "model-b",
            Vector(NodeId("job-1:n5")),
            DataRef("job-1-child/round-1")
          ),
          SessionNode.ToolCall(
            NodeId("job-1-child:n2"),
            Some(NodeId("job-1-child:n1")),
            2L,
            1760000000500L,
            "call-1",
            "bash",
            RecoveryClass.Mutating,
            Some(ToolOutcome.Succeeded),
            Vector(NodeId("job-1-child:n1")),
            Some(DataRef("job-1-child/call-call-1"))
          )
        )
      )
      assertTrue(
        r.graph == handBuilt,
        GraphHash(r.graph) == GraphHash(handBuilt),
        r.graph.validated.isRight
      )
    },
    test(
      "SCH-02 hash policy: provenance and other v2 metadata NEVER enter the canonical form"
    ) {
      // Same node vector, provenance present vs absent -> identical GraphHash.
      val withProvenance = rightOf(LineageReducer.reduceWire(happy))
      val swapped = happy.map(e =>
        if e.seq == 6L then
          env(
            6L,
            LineagePayload.Frozen(
              EventPayload.Diagnostics("provenance-shaped metadata")
            )
          )
        else e
      )
      val noProvenance = rightOf(LineageReducer.reduceWire(swapped))
      assertTrue(
        withProvenance.provenance.isDefined,
        noProvenance.provenance.isEmpty,
        GraphHash(withProvenance.graph) == GraphHash(noProvenance.graph),
        withProvenance.graph == noProvenance.graph
      )
    },
    test(
      "SCH-02 nested phases: LIFO exit closes the innermost match; parents nest"
    ) {
      val stream = Vector(
        env(
          1L,
          LineagePayload.PhaseStarted("outer", RecoveryClass.ReadOnly, None)
        ),
        env(
          2L,
          LineagePayload
            .PhaseStarted("inner", RecoveryClass.Mutating, Some("note"))
        ),
        env(3L, LineagePayload.PhaseFinished("inner")),
        env(4L, LineagePayload.PhaseFinished("outer")),
        frozen(5L, EventPayload.Terminal(JobStatus.Succeeded))
      )
      val r = rightOf(LineageReducer.reduceWire(stream))
      val bySeq = r.graph.nodes.map(n => n.seq -> n).toMap
      val outer = bySeq(1L).asInstanceOf[SessionNode.Phase]
      val inner = bySeq(2L).asInstanceOf[SessionNode.Phase]
      assertTrue(
        outer.parent.isEmpty,
        outer.note.isEmpty,
        inner.parent == Some(outer.id),
        inner.note == Some("note"),
        r.graph.validated.isRight
      )
    },
    test("SCH-02 a phase open at Terminal is legal: the node stands") {
      val stream = Vector(
        env(
          1L,
          LineagePayload
            .PhaseStarted("open-ended", RecoveryClass.ReadOnly, None)
        ),
        frozen(2L, EventPayload.Terminal(JobStatus.Succeeded))
      )
      val r = rightOf(LineageReducer.reduceWire(stream))
      assertTrue(
        r.graph.nodes.size == 1,
        r.graph.nodes.head.isInstanceOf[SessionNode.Phase]
      )
    },
    test(
      "SCH-02 v1 regression: reduceWire over wrapped frozen envelopes == reduce"
    ) {
      val campaign = LineageCorpus.campaign(1)
      val viaV1 = LineageReducer.reduce(campaign)
      val viaWire = LineageReducer.reduceWire(campaign.map(LineageWire.wrap))
      assertTrue(
        viaV1 == viaWire.map(_.graph),
        viaV1.isRight,
        viaWire.map(_.provenance.isEmpty) == Right(true)
      )
    },

    // --------------------------------------------------------------
    // SCH-03: malformed v2 -> typed (reducer layer)
    // --------------------------------------------------------------
    test("SCH-03 phase exit without open phase -> PhaseMismatch") {
      val stream = Vector(
        env(1L, LineagePayload.PhaseFinished("ghost")),
        frozen(2L, EventPayload.Terminal(JobStatus.Succeeded))
      )
      val crossName = Vector(
        env(
          1L,
          LineagePayload.PhaseStarted("a", RecoveryClass.ReadOnly, None)
        ),
        env(2L, LineagePayload.PhaseFinished("b")),
        frozen(3L, EventPayload.Terminal(JobStatus.Succeeded))
      )
      assertTrue(
        LineageReducer.reduceWire(stream) ==
          Left(
            ReduceError.PhaseMismatch("job-1", 1L, "PhaseFinished(name=ghost)")
          ),
        LineageReducer.reduceWire(crossName) ==
          Left(ReduceError.PhaseMismatch("job-1", 2L, "PhaseFinished(name=b)"))
      )
    },
    test("SCH-03 second provenance record -> DuplicateProvenance") {
      val stream = Vector(
        env(1L, LineagePayload.RunProvenance(None, Vector.empty)),
        env(2L, LineagePayload.RunProvenance(Some("other"), Vector.empty)),
        frozen(3L, EventPayload.Terminal(JobStatus.Succeeded))
      )
      assertTrue(
        LineageReducer.reduceWire(stream) ==
          Left(ReduceError.DuplicateProvenance(2L))
      )
    },
    test(
      "SCH-03 v2 child envelope without invocationId -> MissingCorrelation"
    ) {
      val stream = Vector(
        frozen(1L, EventPayload.Delegated(child, "worker-2")),
        env(
          1L,
          LineagePayload.Frozen(EventPayload.ModelCallStarted(1, "m", None)),
          job = child,
          invocationId = None
        )
      )
      assertTrue(
        LineageReducer.reduceWire(stream) ==
          Left(ReduceError.MissingCorrelation("job-1-child", 1L))
      )
    },
    test("SCH-03 v1 child envelopes keep the optional-correlation rule") {
      val stream = Vector(
        frozen(1L, EventPayload.Delegated(child, "worker-2")),
        frozen(1L, EventPayload.ModelCallStarted(1, "m", None), job = child),
        frozen(
          2L,
          EventPayload.Terminal(JobStatus.Succeeded),
          job = child
        ),
        frozen(2L, EventPayload.Terminal(JobStatus.Succeeded))
      )
      assertTrue(LineageReducer.reduceWire(stream).isRight)
    },
    test(
      "SCH-03 unknown future version via reduceWire -> SchemaVersionUnsupported(3)"
    ) {
      val stream = Vector(
        env(1L, LineagePayload.PhaseFinished("x"), schemaVersion = 3)
      )
      assertTrue(
        LineageReducer.reduceWire(stream) ==
          Left(ReduceError.SchemaVersionUnsupported(3)),
        LineageReducer.reducePartialWire(stream) ==
          Left(ReduceError.SchemaVersionUnsupported(3))
      )
    },
    test(
      "SCH-03 v1 pin: the frozen reduce entries still refuse schemaVersion 2 envelopes"
    ) {
      val v2Tagged = EventEnvelope(
        2,
        1L,
        root,
        rootId,
        sessionId,
        1L,
        EventPayload.Terminal(JobStatus.Succeeded)
      )
      assertTrue(
        LineageReducer.reduce(Vector(v2Tagged)) ==
          Left(ReduceError.SchemaVersionUnsupported(2)),
        LineageReducer.reducePartial(Vector(v2Tagged)) ==
          Left(ReduceError.SchemaVersionUnsupported(2))
      )
    },
    test(
      "SCH-03 first violation wins: unknown parent reported before missing correlation"
    ) {
      // The child's envelope is v2 WITHOUT invocationId AND arrives before
      // the parent's Delegated: the unknown-parent check (rule 2) must fire
      // before the correlation check (rule 3) — first violation in stream
      // order wins.
      val stream = Vector(
        frozen(1L, EventPayload.Waiting(WaitReason.Slot)),
        env(
          1L,
          LineagePayload.Frozen(EventPayload.ModelCallStarted(1, "m", None)),
          job = child,
          invocationId = None
        )
      )
      assertTrue(
        LineageReducer.reduceWire(stream) ==
          Left(ReduceError.UnknownParent("job-1-child", 1L))
      )
    },

    // --------------------------------------------------------------
    // Partial reduction of the v2 stream (LIN5-03 discipline)
    // --------------------------------------------------------------
    test("SCH-02 partial: an open phase at the crash cut drops with the cut") {
      val prefix = Vector(
        env(
          1L,
          LineagePayload.PhaseStarted("torn", RecoveryClass.ReadOnly, None)
        ),
        frozen(2L, EventPayload.ModelCallStarted(1, "model-a", None))
      )
      val r = rightOf(LineageReducer.reducePartialWire(prefix))
      assertTrue(
        r.graph.nodes.isEmpty,
        r.provenance.isEmpty
      )
    },
    test("SCH-02 partial: a finished phase is a completed node and survives") {
      val prefix = Vector(
        env(
          1L,
          LineagePayload.PhaseStarted("done", RecoveryClass.ReadOnly, None)
        ),
        env(2L, LineagePayload.PhaseFinished("done")),
        frozen(3L, EventPayload.ModelCallStarted(1, "model-a", None))
      )
      val r = rightOf(LineageReducer.reducePartialWire(prefix))
      assertTrue(
        r.graph.nodes.map(_.seq) == Vector(1L),
        r.graph.nodes.head.isInstanceOf[SessionNode.Phase]
      )
    }
  )

end LineageReducerWireSpec
