package raider.runtime.lineage.replay

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.events.{EventEnvelope, EventPayload}
import raider.runtime.lineage.corpus.LineageCorpus
import raider.runtime.lineage.model.{
  DataRef,
  GraphHash,
  NodeId,
  RecoveryClass,
  SessionGraph,
  SessionNode
}
import raider.runtime.lineage.reduce.LineageReducer
import zio.test.*

/** LIN-05B acceptance for the replay graph comparator (packet L25, ADR-027):
  *   - LIN5-B-02 (comparator half): a structural mutation yields `Left(
  *     GraphDiff)` whose `firstDivergence` is the FIRST diverging node in
  *     canonical `(seq, id)` order with the correct path (rootId→nodeId) and
  *     reason (MissingNode/ExtraNode/KindMismatch/FieldMismatch, both sides
  *     rendered);
  *   - LIN5-B-03 (invariant): structure equal ⇒ hash equal — `compare` returns
  *     `Right(hash)` and that hash is exactly `GraphHash` of both graphs, on
  *     corpus-reduced graphs across seeds (property) and on hand-built graphs.
  *
  * There is deliberately NO HashMismatch divergence reason: if the structural
  * comparison finds no divergence the canonical forms are equal, so the hashes
  * are equal by construction — LIN5-B-03 pins that invariant; a comparator that
  * could report HashMismatch on equal structure would contradict it.
  */
object GraphComparatorSpec extends ZIOSpecDefault:

  private val rootId = RootId("root-cmp")
  private val sessionId = SessionId("sess-cmp")

  private def graph(nodes: SessionNode*) =
    SessionGraph(rootId, sessionId, nodes.toVector)

  private def modelCall(
      id: String,
      seq: Long,
      round: Int = 1,
      model: String = "m",
      inputs: Vector[NodeId] = Vector.empty
  ) =
    SessionNode.ModelCall(
      NodeId(id),
      None,
      seq,
      10L,
      round,
      model,
      inputs,
      DataRef(s"$id-ref")
    )

  private def toolCall(
      id: String,
      seq: Long,
      callId: String = "c1",
      tool: String = "bash"
  ) =
    SessionNode.ToolCall(
      NodeId(id),
      None,
      seq,
      10L,
      callId,
      tool,
      RecoveryClass.ReadOnly,
      None,
      Vector.empty,
      None
    )

  /** Reduces a corpus campaign; the corpus is deterministic per seed, so this
    * always succeeds for a full campaign.
    */
  private def reduced(seed: Int): SessionGraph =
    LineageReducer.reduce(LineageCorpus.campaign(seed)) match
      case Right(g) => g
      case Left(err) =>
        throw new IllegalStateException(s"corpus seed $seed failed: $err")

  def spec = suite("GraphComparator")(
    suite("LIN5-B-03 structure-equal ⇒ hash-equal invariant")(
      test("identical graphs: Right(hash) equal to GraphHash of both") {
        val g = graph(modelCall("n1", 1L), toolCall("n2", 2L))
        val other = graph(modelCall("n1", 1L), toolCall("n2", 2L))
        assertTrue(
          GraphComparator.compare(g, other) == Right(GraphHash(g)),
          GraphComparator.compare(g, other) == Right(GraphHash(other))
        )
      },
      test("emission order is not structure: reordered nodes still match") {
        val g = graph(modelCall("n1", 1L), toolCall("n2", 2L))
        val reordered = SessionGraph(
          rootId,
          sessionId,
          g.nodes.reverse // GraphHash canonicalizes by (seq, id); so must compare
        )
        assertTrue(
          GraphComparator.compare(g, reordered) == Right(GraphHash(g)),
          GraphHash(reordered) == GraphHash(g)
        )
      },
      test("corpus property: jsonl round-trip replay matches for every seed") {
        check(Gen.int(1, 20)) { seed =>
          val expected = reduced(seed)
          // round-trip through the frozen codecs: the replayed stream is the
          // same envelopes, so the structure is equal and the hash must be too
          val replayed = LineageCorpus
            .parseJsonl(LineageCorpus.jsonlLines(LineageCorpus.campaign(seed)))
          val actual = replayed.fold(
            err => throw new IllegalStateException(s"seed $seed: $err"),
            LineageReducer.reduce
          )
          actual match
            case Right(g2) =>
              assertTrue(
                GraphComparator.compare(expected, g2) == Right(
                  GraphHash(expected)
                ),
                GraphHash(expected) == GraphHash(g2)
              )
            case Left(err) =>
              throw new IllegalStateException(s"seed $seed reduce: $err")
        }
      }
    ),
    suite("LIN5-B-02 first-divergence reporting")(
      test("missing node: path, reason and both hashes in the diff") {
        val expected = graph(modelCall("n1", 1L), toolCall("n2", 2L))
        val actual = graph(modelCall("n1", 1L))
        GraphComparator.compare(expected, actual) match
          case Left(diff) =>
            assertTrue(
              diff.firstDivergence.rootId == rootId,
              diff.firstDivergence.nodeId == Some(NodeId("n2")),
              diff.firstDivergence.reason == DivergenceReason.MissingNode,
              diff.expectedHash == GraphHash(expected),
              diff.actualHash == GraphHash(actual),
              diff.expectedHash != diff.actualHash
            )
          case Right(hash) =>
            throw new IllegalStateException(s"expected a diff, got $hash")
      },
      test("extra node is reported on the actual side") {
        val expected = graph(modelCall("n1", 1L))
        val actual = graph(modelCall("n1", 1L), toolCall("n2", 2L))
        GraphComparator.compare(expected, actual) match
          case Left(diff) =>
            assertTrue(
              diff.firstDivergence.nodeId == Some(NodeId("n2")),
              diff.firstDivergence.reason == DivergenceReason.ExtraNode
            )
          case Right(hash) =>
            throw new IllegalStateException(s"expected a diff, got $hash")
      },
      test("same id, different kind: KindMismatch with canonical kind names") {
        val expected = graph(modelCall("n1", 1L))
        val actual = graph(toolCall("n1", 1L))
        GraphComparator.compare(expected, actual) match
          case Left(diff) =>
            assertTrue(
              diff.firstDivergence.reason ==
                DivergenceReason.KindMismatch("model_call", "tool_call")
            )
          case Right(hash) =>
            throw new IllegalStateException(s"expected a diff, got $hash")
      },
      test("field mismatch is the FIRST differing canonical field") {
        // canonical ModelCall field order: parent|seq|atMs|round|model|inputs|
        // dataRef — seq differs before model, so "seq" must win
        val expected = graph(modelCall("n1", 1L, model = "m1"))
        val actual = graph(modelCall("n1", 2L, model = "m2"))
        GraphComparator.compare(expected, actual) match
          case Left(diff) =>
            assertTrue(
              diff.firstDivergence.reason ==
                DivergenceReason.FieldMismatch("seq", "1", "2")
            )
          case Right(hash) =>
            throw new IllegalStateException(s"expected a diff, got $hash")
      },
      test("inputs and parent render both sides ('-' for none/empty)") {
        val expected = graph(modelCall("n1", 1L))
        val actual =
          graph(modelCall("n1", 1L, inputs = Vector(NodeId("n0"))))
        GraphComparator.compare(expected, actual) match
          case Left(diff) =>
            assertTrue(
              diff.firstDivergence.reason ==
                DivergenceReason.FieldMismatch("inputs", "-", "n0")
            )
          case Right(hash) =>
            throw new IllegalStateException(s"expected a diff, got $hash")
      },
      test("graph header divergence: rootId reported before sessionId") {
        val expected = graph(modelCall("n1", 1L))
        val otherRoot =
          SessionGraph(RootId("root-other"), sessionId, expected.nodes)
        val otherSession =
          SessionGraph(rootId, SessionId("sess-other"), expected.nodes)
        GraphComparator.compare(expected, otherRoot) match
          case Left(diff) =>
            assertTrue(
              diff.firstDivergence.nodeId.isEmpty,
              diff.firstDivergence.reason ==
                DivergenceReason.FieldMismatch(
                  "rootId",
                  "root-cmp",
                  "root-other"
                )
            )
          case Right(hash) =>
            throw new IllegalStateException(s"expected a diff, got $hash")
        GraphComparator.compare(expected, otherSession) match
          case Left(diff) =>
            assertTrue(
              diff.firstDivergence.reason ==
                DivergenceReason
                  .FieldMismatch("sessionId", "sess-cmp", "sess-other")
            )
          case Right(hash) =>
            throw new IllegalStateException(s"expected a diff, got $hash")
      },
      test(
        "two divergences: the FIRST in canonical (seq, id) order wins, not the second (F-L28-4)"
      ) {
        // Both n2 (seq 2) and n3 (seq 3) diverge; canonical order visits seq 2
        // before seq 3, so the reported divergence must be n2's.
        val expected = graph(
          modelCall("n1", 1L),
          modelCall("n2", 2L),
          modelCall("n3", 3L)
        )
        val actual = graph(
          modelCall("n1", 1L),
          modelCall("n2", 2L, model = "mut-a"),
          modelCall("n3", 3L, model = "mut-b")
        )
        GraphComparator.compare(expected, actual) match
          case Left(diff) =>
            assertTrue(
              diff.firstDivergence.nodeId == Some(NodeId("n2")),
              diff.firstDivergence.reason ==
                DivergenceReason.FieldMismatch("model", "m", "mut-a")
            )
          case Right(hash) =>
            throw new IllegalStateException(s"expected a diff, got $hash")
      },
      test(
        "two divergences at the same seq: the smaller node id wins the (seq, id) tie-break (F-L28-4)"
      ) {
        val expected =
          graph(modelCall("n-b", 2L), modelCall("n-a", 2L))
        val actual = graph(
          modelCall("n-b", 2L, model = "mut-b"),
          modelCall("n-a", 2L, model = "mut-a")
        )
        GraphComparator.compare(expected, actual) match
          case Left(diff) =>
            assertTrue(
              diff.firstDivergence.nodeId == Some(NodeId("n-a")),
              diff.firstDivergence.reason ==
                DivergenceReason.FieldMismatch("model", "m", "mut-a")
            )
          case Right(hash) =>
            throw new IllegalStateException(s"expected a diff, got $hash")
      },
      test(
        "corpus mutation (tool renamed mid-campaign): one node, first divergence"
      ) {
        val seed = 1
        val expected = reduced(seed)
        val callId = "call-child2-1" // child-2's search call
        val mutated = LineageCorpus
          .campaign(seed)
          .map { env =>
            env.payload match
              case p: EventPayload.ToolStarted if p.callId == callId =>
                env.copy(payload = p.copy(tool = "bash"))
              case _ => env
          }
        val actual = LineageReducer.reduce(mutated) match
          case Right(g)  => g
          case Left(err) => throw new IllegalStateException(s"mutated: $err")
        // the reducer derives the node id as `<job>:n<seq>` from the START event
        val divergentNode = LineageCorpus
          .campaign(seed)
          .collectFirst {
            case env
                if env.payload.isInstanceOf[EventPayload.ToolStarted] &&
                  env.payload
                    .asInstanceOf[EventPayload.ToolStarted]
                    .callId == callId =>
              NodeId(s"${env.jobId.value}:n${env.seq}")
          }
          .getOrElse(throw new IllegalStateException("call not found"))
        GraphComparator.compare(expected, actual) match
          case Left(diff) =>
            assertTrue(
              diff.firstDivergence.rootId == RootId(s"root-$seed"),
              diff.firstDivergence.nodeId == Some(divergentNode),
              // canonical tool_call order: …|callId|tool|recovery|… — "tool" wins
              diff.firstDivergence.reason ==
                DivergenceReason.FieldMismatch("tool", "search", "bash"),
              diff.expectedHash == GraphHash(expected),
              diff.actualHash == GraphHash(actual)
            )
          case Right(hash) =>
            throw new IllegalStateException(s"expected a diff, got $hash")
      }
    ),
    suite("LIN-06B-1 compareNormalized (packet L48, ADR-027 §2)")(
      test(
        "LIN6B1-01: identical graphs with DIFFERENT jobIds normalize to the same hash"
      ) {
        // Same structure, every identity field different: job names in node
        // ids/refs, root/session headers, wall-clock atMs.
        def graph(job: String, root: String, sess: String, atMs: Long) =
          SessionGraph(
            RootId(root),
            SessionId(sess),
            Vector(
              SessionNode.ModelCall(
                NodeId(s"$job:n1"),
                None,
                1L,
                atMs,
                1,
                "m",
                Vector.empty,
                DataRef(s"$job/round-1")
              ),
              SessionNode.ToolCall(
                NodeId(s"$job:n2"),
                Some(NodeId(s"$job:n1")),
                2L,
                atMs + 5,
                "call-1",
                "fs_read",
                RecoveryClass.ReadOnly,
                None,
                Vector(NodeId(s"$job:n1")),
                Some(DataRef(s"$job/call-call-1"))
              )
            )
          )
        val recorded = graph("job-rec", "root-rec", "sess-rec", 1000L)
        val replayed = graph("j_1", "loop_42", "emit-sess", 1760000000000L)
        // sanity: raw compare treats the identity drift as divergence
        assertTrue(GraphComparator.compare(recorded, replayed).isLeft)
        GraphComparator.compareNormalized(recorded, replayed) match
          case Right(hash) =>
            assertTrue(
              hash == GraphComparator
                .compareNormalized(replayed, recorded)
                .toOption
                .getOrElse("?"),
              GraphComparator.normalize(recorded) == GraphComparator
                .normalize(replayed)
            )
          case Left(diff) =>
            throw new IllegalStateException(
              s"expected a normalized match, got $diff"
            )
      },
      test(
        "normalization stays SENSITIVE: a real field drift still diverges after normalization"
      ) {
        val expected = graph(modelCall("alpha:n1", 1L, model = "m"))
        val actual = graph(modelCall("beta:n1", 1L, model = "mut"))
        GraphComparator.compareNormalized(expected, actual) match
          case Left(diff) =>
            // the divergence path is reported in NORMALIZED ids
            assertTrue(
              diff.firstDivergence.nodeId == Some(NodeId("job-1:n1")),
              diff.firstDivergence.reason ==
                DivergenceReason.FieldMismatch("model", "m", "mut"),
              diff.expectedHash != diff.actualHash
            )
          case Right(hash) =>
            throw new IllegalStateException(s"expected a diff, got $hash")
      },
      test(
        "independent roots without delegations fall back to first-seen (seq, id) numbering (degenerate shape)"
      ) {
        // NOT a reducer-shaped graph: two jobs, NO Delegation between them
        // (the second job's node merely inputs the first's). With no tree to
        // key the numbering off, such roots keep the first-seen canonical
        // (seq, id) fallback — deterministic, and alphabet-free here only
        // because seq 1 and seq 2 do not collide; reducer-SHAPED graphs are
        // numbered by delegation order instead (F-L51-1 suite below).
        // Node ids AND dataRefs follow the reducer conventions
        // (`<job>:n<seq>`, `<job>/round-<n>`).
        def call(id: String, seq: Long, inputs: Vector[NodeId], ref: String) =
          SessionNode.ModelCall(
            NodeId(id),
            None,
            seq,
            10L,
            1,
            "m",
            inputs,
            DataRef(ref)
          )
        val recorded = SessionGraph(
          RootId("root-1"),
          SessionId("sess-1"),
          Vector(
            call("a:n1", 1L, Vector.empty, "a/round-1"),
            call("b:n2", 2L, Vector(NodeId("a:n1")), "b/round-2")
          )
        )
        val replayed = SessionGraph(
          RootId("root-2"),
          SessionId("sess-2"),
          Vector(
            call("y:n1", 1L, Vector.empty, "y/round-1"),
            call("x:n2", 2L, Vector(NodeId("y:n1")), "x/round-2")
          )
        )
        GraphComparator.compareNormalized(recorded, replayed) match
          case Right(hash) =>
            val norm = GraphComparator.normalize(recorded)
            assertTrue(
              hash == GraphComparator
                .compareNormalized(replayed, recorded)
                .toOption
                .getOrElse("?"),
              norm.nodes.map(_.id.value) ==
                Vector("job-1:n1", "job-2:n2"),
              norm.nodes(1).inputs == Vector(NodeId("job-1:n1")),
              norm.nodes(1) match
                case m: SessionNode.ModelCall =>
                  m.id == NodeId("job-2:n2") &&
                  m.dataRef == DataRef("job-2/round-2")
                case _ => false
            )
          case Left(diff) =>
            throw new IllegalStateException(
              s"expected a normalized match, got $diff"
            )
      },
      test(
        "corpus property: adversarially renamed jobs (alphabet order REVERSED, F-L51-1) match normalized for every seed"
      ) {
        check(Gen.int(1, 20)) { seed =>
          val expected = reduced(seed)
          // Adversarial rename (F-L51-1): the recorded jobs first compete at
          // seq 1 in alphabetical order child-1 < child-2 < root; the rename
          // REVERSES that relation (root first, children swapped), so any
          // numbering derived from raw names/ids maps the same structure to
          // different labels. Rename + fresh root + frozen atMs: identity
          // drift only — the reducer must produce the same STRUCTURE.
          def rj(j: JobId): JobId =
            if j.value == s"job-$seed-root" then JobId(s"aaa-$seed-root")
            else if j.value == s"job-$seed-child-1" then
              JobId(s"ccc-$seed-child-1")
            else JobId(s"bbb-$seed-child-2")
          val renamedEvs = LineageCorpus
            .campaign(seed)
            .map { env =>
              val payload = env.payload match
                case d: EventPayload.Delegated =>
                  d.copy(childJobId = rj(d.childJobId))
                case other => other
              env.copy(
                jobId = rj(env.jobId),
                rootId = RootId(s"ren-$seed"),
                atMs = 0L,
                payload = payload
              )
            }
          val actual = LineageReducer.reduce(renamedEvs) match
            case Right(g) => g
            case Left(err) =>
              throw new IllegalStateException(s"renamed seed $seed: $err")
          // sanity: raw compare DOES see the identity drift
          assertTrue(GraphComparator.compare(expected, actual).isLeft)
          GraphComparator.compareNormalized(expected, actual) match
            case Right(hash) =>
              assertTrue(
                hash == GraphHash(GraphComparator.normalize(expected)),
                hash == GraphHash(GraphComparator.normalize(actual))
              )
            case Left(diff) =>
              throw new IllegalStateException(
                s"normalized seed $seed: $diff"
              )
        }
      }
    ),
    suite(
      "F-L51-1 rename-invariant canonical job map (packet L54, W20 review)"
    )(
      test(
        "FL51-01: review reproducer — renaming jobs that swaps the equal-seq tie-break still matches"
      ) {
        // G1: root job "a", Delegation a:n1 (seq 1, child "b"); child "b":
        // ModelCall b:n1 (seq 1), b:n2 (seq 2). G2: the same structure with
        // the jobs renamed y (root) / x (child) — names are the ONLY
        // difference. a < b but x < y: the OLD first-seen numbering over the
        // (seq, id) traversal tie-broke equal seqs by raw id (job-name
        // alphabet), so the rename swapped the canonical numbers of
        // structurally identical graphs → false divergence (W20 review
        // F-L51-1). Delegation-order numbering must match them.
        def repro(
            rootJob: String,
            childJob: String,
            root: String,
            sess: String
        ) =
          val base = NodeId(s"$rootJob:n1")
          SessionGraph(
            RootId(root),
            SessionId(sess),
            Vector(
              SessionNode.Delegation(
                base,
                None,
                1L,
                10L,
                JobId(childJob),
                "sub",
                Vector.empty
              ),
              SessionNode.ModelCall(
                NodeId(s"$childJob:n1"),
                Some(base),
                1L,
                11L,
                1,
                "m",
                Vector(base),
                DataRef(s"$childJob/round-1")
              ),
              SessionNode.ModelCall(
                NodeId(s"$childJob:n2"),
                Some(base),
                2L,
                12L,
                2,
                "m",
                Vector(NodeId(s"$childJob:n1")),
                DataRef(s"$childJob/round-2")
              )
            )
          )
        val g1 = repro("a", "b", "root-1", "sess-1")
        val g2 = repro("y", "x", "root-2", "sess-2")
        assertTrue(
          g1.validated.isRight,
          g2.validated.isRight,
          // job NAMES are structure keys of the raw compare — the identity
          // drift must stay visible there
          GraphComparator.compare(g1, g2).isLeft
        )
        GraphComparator.compareNormalized(g1, g2) match
          case Right(hash) =>
            val n1 = GraphComparator.normalize(g1)
            assertTrue(
              hash == GraphComparator
                .compareNormalized(g2, g1)
                .toOption
                .getOrElse("?"),
              n1 == GraphComparator.normalize(g2),
              // root job first (job-1), the child numbered by its Delegation
              // node (job-2); emission order of the vector is preserved
              n1.nodes.map(_.id.value) ==
                Vector("job-1:n1", "job-2:n1", "job-2:n2"),
              n1.nodes.collectFirst { case d: SessionNode.Delegation =>
                d.childJobId
              } == Some(JobId("job-2")),
              hash == GraphHash(n1)
            )
          case Left(diff) =>
            throw new IllegalStateException(
              s"expected a normalized match, got $diff"
            )
      },
      test(
        "FL51-01b: children delegated in one order but renamed into the other — delegation order wins, not alphabet"
      ) {
        // Recorded: root "m" delegates C1="aa" at seq 2, C2="ab" at seq 4 and
        // a SILENT child "mm" at seq 6; all three named jobs hold a seq-1
        // node. Renamed: root "z", C1'="yb", C2'="ya", silent "ghost" — the
        // rename REVERSES the alphabetical relation of the two children
        // ("aa" < "ab" becomes "ya" < "yb"), so the old name-driven numbering
        // swapped them; the FIRST-DELEGATED child must be job-2 on both
        // sides, and the empty subtree still takes the next label.
        def g(
            rootJob: String,
            c1: String,
            c2: String,
            silent: String,
            root: String,
            sess: String
        ) =
          val base = NodeId(s"$rootJob:n1")
          def deleg(id: String, seq: Long, child: String) =
            SessionNode.Delegation(
              NodeId(id),
              Some(base),
              seq,
              10L,
              JobId(child),
              "sub",
              Vector(base)
            )
          SessionGraph(
            RootId(root),
            SessionId(sess),
            Vector(
              SessionNode.ModelCall(
                base,
                None,
                1L,
                9L,
                1,
                "m",
                Vector.empty,
                DataRef(s"$rootJob/round-1")
              ),
              deleg(s"$rootJob:n2", 2L, c1),
              SessionNode.ModelCall(
                NodeId(s"$c1:n1"),
                Some(base),
                1L,
                11L,
                1,
                "m",
                Vector(base),
                DataRef(s"$c1/round-1")
              ),
              deleg(s"$rootJob:n4", 4L, c2),
              SessionNode.ModelCall(
                NodeId(s"$c2:n1"),
                Some(NodeId(s"$rootJob:n4")),
                1L,
                11L,
                1,
                "m",
                Vector(NodeId(s"$rootJob:n4")),
                DataRef(s"$c2/round-1")
              ),
              deleg(s"$rootJob:n6", 6L, silent)
            )
          )
        val g1 = g("m", "aa", "ab", "mm", "root-1", "sess-1")
        val g2 = g("z", "yb", "ya", "ghost", "root-2", "sess-2")
        assertTrue(
          g1.validated.isRight,
          g2.validated.isRight,
          GraphComparator.compare(g1, g2).isLeft
        )
        GraphComparator.compareNormalized(g1, g2) match
          case Right(hash) =>
            val n1 = GraphComparator.normalize(g1)
            val n2 = GraphComparator.normalize(g2)
            assertTrue(
              n1 == n2,
              // C1' ("yb", delegated FIRST) = job-2 even though C2' ("ya")
              // sorts before it; the silent child takes the next label anyway
              n2.nodes.collect { case d: SessionNode.Delegation =>
                d.childJobId.value
              } == Vector("job-2", "job-3", "job-4"),
              n1.nodes.map(_.id.value) == Vector(
                "job-1:n1",
                "job-1:n2",
                "job-2:n1",
                "job-1:n4",
                "job-3:n1",
                "job-1:n6"
              ),
              hash == GraphHash(n1)
            )
          case Left(diff) =>
            throw new IllegalStateException(
              s"expected a normalized match, got $diff"
            )
      },
      test(
        "FL51-01c: three-level tree, equal seqs among neighbors — numbering is a pure function of the delegation tree"
      ) {
        // Root "p" delegates "q" (seq 1) and "r" (seq 2); "q" delegates "s"
        // at its seq 1, "r" delegates "t" at its seq 1 — delegation nodes of
        // FOUR jobs collide at seq 1. Renamed with the alphabet fully
        // reversed (p→e, q→d, r→c, s→b, t→a): every name order flips, the
        // tree order does not — the breadth walk numbers the children of
        // job-1 before the children of job-2 regardless of names.
        def g(
            rootJob: String,
            q: String,
            r: String,
            s: String,
            t: String,
            root: String,
            sess: String
        ) =
          val pq = NodeId(s"$rootJob:n1")
          val pr = NodeId(s"$rootJob:n2")
          SessionGraph(
            RootId(root),
            SessionId(sess),
            Vector(
              SessionNode.Delegation(
                pq,
                None,
                1L,
                10L,
                JobId(q),
                "sub",
                Vector.empty
              ),
              SessionNode.Delegation(
                pr,
                None,
                2L,
                10L,
                JobId(r),
                "sub",
                Vector.empty
              ),
              SessionNode.Delegation(
                NodeId(s"$q:n1"),
                Some(pq),
                1L,
                10L,
                JobId(s),
                "sub",
                Vector(pq)
              ),
              SessionNode.Delegation(
                NodeId(s"$r:n1"),
                Some(pr),
                1L,
                10L,
                JobId(t),
                "sub",
                Vector(pr)
              ),
              SessionNode.ModelCall(
                NodeId(s"$s:n1"),
                Some(NodeId(s"$q:n1")),
                1L,
                11L,
                1,
                "m",
                Vector(NodeId(s"$q:n1")),
                DataRef(s"$s/round-1")
              ),
              SessionNode.ModelCall(
                NodeId(s"$t:n1"),
                Some(NodeId(s"$r:n1")),
                1L,
                11L,
                1,
                "m",
                Vector(NodeId(s"$r:n1")),
                DataRef(s"$t/round-1")
              ),
              SessionNode.ModelCall(
                NodeId(s"$rootJob:n3"),
                None,
                3L,
                12L,
                2,
                "m",
                Vector(pr),
                DataRef(s"$rootJob/round-2")
              )
            )
          )
        val g1 = g("p", "q", "r", "s", "t", "root-1", "sess-1")
        val g2 = g("e", "d", "c", "b", "a", "root-2", "sess-2")
        assertTrue(
          g1.validated.isRight,
          g2.validated.isRight,
          GraphComparator.compare(g1, g2).isLeft
        )
        GraphComparator.compareNormalized(g1, g2) match
          case Right(hash) =>
            val n1 = GraphComparator.normalize(g1)
            val n2 = GraphComparator.normalize(g2)
            assertTrue(
              n1 == n2,
              n1.nodes.map(_.id.value) == Vector(
                "job-1:n1",
                "job-1:n2",
                "job-2:n1",
                "job-3:n1",
                "job-4:n1",
                "job-5:n1",
                "job-1:n3"
              ),
              n1.nodes.collect { case d: SessionNode.Delegation =>
                d.childJobId.value
              } == Vector("job-2", "job-3", "job-4", "job-5"),
              hash == GraphHash(n1)
            )
          case Left(diff) =>
            throw new IllegalStateException(
              s"expected a normalized match, got $diff"
            )
      }
    )
  )

end GraphComparatorSpec
