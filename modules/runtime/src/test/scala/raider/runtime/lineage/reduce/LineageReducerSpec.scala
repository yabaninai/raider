package raider.runtime.lineage.reduce

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.budget.MicroUsd
import raider.runtime.events.{
  EventEnvelope,
  EventPayload,
  ToolOutcome,
  UsageSnapshot
}
import raider.runtime.jobs.JobStatus
import raider.runtime.lineage.corpus.LineageCorpus
import raider.runtime.lineage.io.LineageEventReader
import raider.runtime.lineage.model.{
  DataRef,
  GraphHash,
  NodeId,
  RecoveryClass,
  SessionGraph,
  SessionNode
}
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** LIN-01C acceptance tests (docs/tasks/lineage.md, packet L11, ADR-027):
  * [[LineageReducer.reduce]] over the frozen 13-case `EventPayload` stream.
  *
  * Corpus linkage (packet L11: "minimal link to the corpus"):
  *   - LIN1-C-01 reduces `LineageCorpus.campaign(seed)` twice — identical
  *     graphs and identical [[GraphHash]];
  *   - LIN1-C-02 feeds the corpus adversarial variants to the reducer:
  *     `withSeqGap` decodes via the corpus codecs and must fail with the exact
  *     `SeqGap`; the `corruptedTail` damage class at the reducer layer is the
  *     torn FINAL record (a real crash mid-append loses the last envelope),
  *     read back through `LineageEventReader` — the honest tail marker plus the
  *     truncated prefix must fail with the exact `MissingTerminal` (the
  *     corpus's literal `corruptedTail` helper appends a torn DUPLICATE after
  *     an intact last line, so its damage is absorbed at the decode layer and
  *     is asserted there);
  *   - LIN1-C-03 checks the parallel-children subtrees of the campaign via
  *     `childrenOf`.
  *
  * No live services, no wall clock: hand-built streams use fixed ids and
  * per-job seqs; file fixtures live in a per-suite temp directory.
  */
object LineageReducerSpec extends ZIOSpecDefault:

  private val campaign1 = LineageCorpus.campaign(1)
  private val child1 = JobId("job-1-child-1")
  private val child2 = JobId("job-1-child-2")
  private val root1 = JobId("job-1-root")

  private val usage =
    UsageSnapshot(MicroUsd(1000L), MicroUsd(900L), unknown = false)

  /** Hand-built envelope with explicit per-job seq (adversarial streams need
    * deliberately wrong seqs, so no auto-counter here).
    */
  private def env(seq: Long, job: JobId, payload: EventPayload): EventEnvelope =
    EventEnvelope(
      1,
      seq,
      job,
      RootId("root-x"),
      SessionId("sess-x"),
      1760000000000L + seq * 250L,
      payload
    )

  // ------------------------------------------------------------------
  // Fixture helpers: files live only in a temp directory, cleaned on exit
  // ------------------------------------------------------------------

  private val tempDir: Path =
    val dir = Files.createTempDirectory("lineage-reducer-spec")
    Runtime.getRuntime.addShutdownHook(
      new Thread(() => deleteRecursively(dir))
    )
    dir

  private def deleteRecursively(p: Path): Unit =
    if Files.isDirectory(p) then
      val children = Files.newDirectoryStream(p)
      try children.forEach((c: Path) => deleteRecursively(c))
      finally children.close()
    Files.deleteIfExists(p)
    ()

  private def delegationOf(
      graph: SessionGraph,
      child: JobId
  ): Option[SessionNode.Delegation] =
    graph.nodes.collectFirst {
      case d: SessionNode.Delegation if d.childJobId == child => d
    }

  /** Unwraps a Right or fails the test loudly: a fixture that must reduce but
    * does not is a fixture bug, never a silent pass.
    */
  private def rightOf(
      reduced: Either[ReduceError, SessionGraph]
  ): SessionGraph =
    reduced match
      case Right(graph) => graph
      case Left(err) =>
        throw new IllegalStateException(s"fixture must reduce, got: $err")

  /** Stream prefix of `envelopes` up to and INCLUDING the first envelope of
    * `job` whose payload satisfies `p` — the crash-cut points of the LIN5-03
    * fixtures.
    */
  private def prefixThrough(
      envelopes: Vector[EventEnvelope],
      job: JobId,
      p: EventPayload => Boolean
  ): Vector[EventEnvelope] =
    val idx = envelopes.indexWhere(e => e.jobId == job && p(e.payload))
    if idx < 0 then
      throw new IllegalStateException("fixture cut point not found")
    envelopes.take(idx + 1)

  /** Per-job `seq` of the first envelope of `job` whose payload satisfies `p`.
    */
  private def seqOf(
      envelopes: Vector[EventEnvelope],
      job: JobId,
      p: EventPayload => Boolean
  ): Long =
    envelopes.find(e => e.jobId == job && p(e.payload)) match
      case Some(e) => e.seq
      case None => throw new IllegalStateException("fixture envelope not found")

  private def toolStarted(callId: String): EventPayload => Boolean = p =>
    p match
      case EventPayload.ToolStarted(id, _, _) => id == callId
      case _                                  => false

  private def modelStarted(round: Int): EventPayload => Boolean = p =>
    p match
      case EventPayload.ModelCallStarted(r, _, _) => r == round
      case _                                      => false

  /** The node id the reducer derives for the node defined at `job`'s `seq`. */
  private def nodeIdAt(job: JobId, seq: Long): NodeId =
    NodeId(s"${job.value}:n$seq")

  // Hand-built minimal streams ------------------------------------------------

  private val jobX = JobId("job-x")
  private val jobY = JobId("job-y")
  private val childY = JobId("job-y-child")

  private val minimal: Vector[EventEnvelope] = Vector(
    env(
      1,
      jobX,
      EventPayload.JobStatusChanged(JobStatus.Queued, JobStatus.Running, None)
    ),
    env(2, jobX, EventPayload.ModelCallStarted(1, "m", None)),
    env(3, jobX, EventPayload.ModelCallFinished(1, 10L, usage)),
    env(4, jobX, EventPayload.Terminal(JobStatus.Succeeded))
  )

  private val withChild: Vector[EventEnvelope] = Vector(
    env(1, jobY, EventPayload.ModelCallStarted(1, "m", None)),
    env(2, jobY, EventPayload.ModelCallFinished(1, 10L, usage)),
    env(3, jobY, EventPayload.Delegated(childY, "worker")),
    env(
      1,
      childY,
      EventPayload.JobStatusChanged(JobStatus.Queued, JobStatus.Running, None)
    ),
    env(2, childY, EventPayload.ModelCallStarted(1, "m2", None)),
    env(3, childY, EventPayload.ModelCallFinished(1, 5L, usage)),
    env(4, childY, EventPayload.Terminal(JobStatus.Succeeded)),
    env(4, jobY, EventPayload.Terminal(JobStatus.Succeeded))
  )

  def spec = suite("LineageReducer (LIN-01C)")(
    // --------------------------------------------------------------
    // LIN1-C-01: deterministic reduction, identical GraphHash on replay
    // --------------------------------------------------------------
    suite("LIN1-C-01 determinism")(
      test("reduce(campaign) twice -> identical graph, identical GraphHash") {
        val a = LineageReducer.reduce(campaign1)
        val b = LineageReducer.reduce(campaign1)
        assertTrue(
          a.isRight,
          a == b,
          a.map(GraphHash.apply) == b.map(GraphHash.apply)
        )
      },
      test("reduced campaign graph passes SessionGraph.validated") {
        val graph = rightOf(LineageReducer.reduce(campaign1))
        assertTrue(graph.validated.isRight, GraphHash(graph).length == 64)
      },
      test(
        "full file pipeline (corpus JSONL -> reader -> reduce) is identical"
      ) {
        val path = LineageCorpus.writeJsonl(
          campaign1,
          tempDir.resolve("campaign-1.jsonl")
        )
        LineageEventReader.read(path) match
          case Right(parsed) =>
            assertTrue(
              parsed.truncatedTail.isEmpty,
              parsed.envelopes == campaign1,
              LineageReducer.reduce(parsed.envelopes) ==
                LineageReducer.reduce(campaign1)
            )
          case Left(err) =>
            throw new IllegalStateException(s"corpus fixture must read: $err")
      },
      test("different seed -> different graph") {
        val g1 = LineageReducer.reduce(campaign1)
        val g2 = LineageReducer.reduce(LineageCorpus.campaign(2))
        assertTrue(g1.isRight, g2.isRight, g1 != g2)
      },
      test(
        "node census: 6 model calls, 6 tool calls, 2 delegations, 0 phases"
      ) {
        val graph = rightOf(LineageReducer.reduce(campaign1))
        val counts = graph.nodes
          .groupBy {
            case _: SessionNode.ModelCall  => "model"
            case _: SessionNode.ToolCall   => "tool"
            case _: SessionNode.Delegation => "delegation"
            case _: SessionNode.Phase      => "phase"
          }
          .withDefaultValue(Vector.empty)
        // Schema freeze: the reducer must NOT invent Phase nodes (no phase
        // case on the wire until the wave-2 schema card).
        assertTrue(
          counts("model").size == 6,
          counts("tool").size == 6,
          counts("delegation").size == 2,
          counts("phase").isEmpty
        )
      },
      test("minimal single-job stream reduces to one model-call node") {
        val graph = rightOf(LineageReducer.reduce(minimal))
        assertTrue(
          graph.nodes.size == 1,
          graph.roots.map(_.id) == Vector(NodeId("job-x:n2")),
          graph.roots.collectFirst { case m: SessionNode.ModelCall =>
            m.dataRef
          } == Some(DataRef("job-x/round-1"))
        )
      }
    ),
    // --------------------------------------------------------------
    // LIN1-C-02: adversarial variants -> typed ReduceError, exact constructor
    // --------------------------------------------------------------
    suite("LIN1-C-02 typed errors")(
      test("empty stream -> Empty") {
        assertTrue(
          LineageReducer.reduce(Vector.empty) == Left(ReduceError.Empty)
        )
      },
      test("corpus wrongSchemaVersion -> SchemaVersionUnsupported(999)") {
        LineageCorpus.parseJsonl(
          LineageCorpus.wrongSchemaVersion(campaign1)
        ) match
          case Right(envelopes) =>
            assertTrue(
              LineageReducer.reduce(envelopes) ==
                Left(ReduceError.SchemaVersionUnsupported(999))
            )
          case Left(err) =>
            throw new IllegalStateException(s"fixture must decode: $err")
      },
      test("corpus withSeqGap -> SeqGap(job-1-root, expected 1, found 2)") {
        LineageCorpus.parseJsonl(LineageCorpus.withSeqGap(campaign1)) match
          case Right(envelopes) =>
            assertTrue(
              LineageReducer.reduce(envelopes) ==
                Left(ReduceError.SeqGap("job-1-root", 1L, 2L))
            )
          case Left(err) =>
            throw new IllegalStateException(s"fixture must decode: $err")
      },
      test("duplicate seq -> SeqGap(expected 3, found 2)") {
        val dup = Vector(
          env(
            1,
            jobX,
            EventPayload.JobStatusChanged(
              JobStatus.Queued,
              JobStatus.Running,
              None
            )
          ),
          env(2, jobX, EventPayload.ModelCallStarted(1, "m", None)),
          env(2, jobX, EventPayload.ModelCallFinished(1, 10L, usage))
        )
        assertTrue(
          LineageReducer.reduce(dup) ==
            Left(ReduceError.SeqGap("job-x", 3L, 2L))
        )
      },
      test("event of an un-introduced job -> UnknownParent") {
        val ghost = Vector(
          env(1, jobX, EventPayload.ModelCallStarted(1, "m", None)),
          env(
            1,
            JobId("job-ghost"),
            EventPayload.ModelCallStarted(1, "m", None)
          )
        )
        assertTrue(
          LineageReducer.reduce(ghost) ==
            Left(ReduceError.UnknownParent("job-ghost", 1L))
        )
      },
      test("double and self delegation -> DuplicateDelegation") {
        val doubled = Vector(
          env(1, jobY, EventPayload.ModelCallStarted(1, "m", None)),
          env(2, jobY, EventPayload.ModelCallFinished(1, 10L, usage)),
          env(3, jobY, EventPayload.Delegated(childY, "worker")),
          env(4, jobY, EventPayload.Delegated(childY, "worker"))
        )
        val self = Vector(
          env(1, jobY, EventPayload.ModelCallStarted(1, "m", None)),
          env(2, jobY, EventPayload.Delegated(jobY, "worker"))
        )
        assertTrue(
          LineageReducer.reduce(doubled) ==
            Left(ReduceError.DuplicateDelegation("job-y-child", 4L)),
          LineageReducer.reduce(self) ==
            Left(ReduceError.DuplicateDelegation("job-y", 2L))
        )
      },
      test(
        "event after Terminal (Diagnostics and second Terminal) -> AfterTerminal"
      ) {
        val late = Vector(
          env(1, jobX, EventPayload.ModelCallStarted(1, "m", None)),
          env(2, jobX, EventPayload.ModelCallFinished(1, 10L, usage)),
          env(3, jobX, EventPayload.Terminal(JobStatus.Succeeded)),
          env(4, jobX, EventPayload.Diagnostics("late"))
        )
        val twice = Vector(
          env(1, jobX, EventPayload.ModelCallStarted(1, "m", None)),
          env(2, jobX, EventPayload.Terminal(JobStatus.Succeeded)),
          env(3, jobX, EventPayload.Terminal(JobStatus.Succeeded))
        )
        assertTrue(
          LineageReducer.reduce(late) ==
            Left(ReduceError.AfterTerminal("job-x", 4L)),
          LineageReducer.reduce(twice) ==
            Left(ReduceError.AfterTerminal("job-x", 3L))
        )
      },
      test(
        "ToolFinished / ModelCallFinished without open start -> UnmatchedFinish"
      ) {
        val orphanTool = Vector(
          env(
            1,
            jobX,
            EventPayload.ToolFinished("call-9", "bash", ToolOutcome.Succeeded)
          )
        )
        val orphanRound = Vector(
          env(1, jobX, EventPayload.ModelCallFinished(2, 10L, usage))
        )
        assertTrue(
          LineageReducer.reduce(orphanTool) ==
            Left(
              ReduceError.UnmatchedFinish(
                "job-x",
                1L,
                "ToolFinished(callId=call-9)"
              )
            ),
          LineageReducer.reduce(orphanRound) ==
            Left(
              ReduceError.UnmatchedFinish(
                "job-x",
                1L,
                "ModelCallFinished(round=2)"
              )
            )
        )
      },
      test(
        "campaign without the final Terminal -> MissingTerminal(job-1-root)"
      ) {
        assertTrue(
          LineageReducer.reduce(campaign1.dropRight(1)) ==
            Left(ReduceError.MissingTerminal("job-1-root"))
        )
      },
      test(
        "torn final record (corruptedTail damage class): reader marks tail, reducer -> MissingTerminal"
      ) {
        // Real crash shape: the FINAL record itself is torn (strict prefix of
        // the last line), so the last envelope is unrecoverable and the
        // prefix is an incomplete campaign.
        val lines = LineageCorpus.jsonlLines(campaign1)
        val tornFinal = lines.dropRight(1) :+ lines.last.take(
          lines.last.length / 2
        )
        val path =
          LineageCorpus.writeLines(
            tornFinal,
            tempDir.resolve("torn-final.jsonl")
          )
        LineageEventReader.read(path) match
          case Right(parsed) =>
            assertTrue(
              parsed.truncatedTail.isDefined,
              parsed.envelopes.size == campaign1.size - 1,
              LineageReducer.reduce(parsed.envelopes) ==
                Left(ReduceError.MissingTerminal("job-1-root"))
            )
          case Left(err) =>
            throw new IllegalStateException(s"torn fixture must read: $err")
      },
      test(
        "corpus literal corruptedTail (torn duplicate) is rejected at the decode layer"
      ) {
        // The corpus's corruptedTail appends a torn DUPLICATE after an intact
        // last line: the reader would report it as an honest tail marker over
        // a complete campaign, and the strict JSONL decode refuses it.
        assertTrue(
          LineageCorpus
            .parseJsonl(LineageCorpus.corruptedTail(campaign1))
            .isLeft
        )
      }
    ),
    // --------------------------------------------------------------
    // LIN1-C-03: parallel-children subtrees are correct
    // --------------------------------------------------------------
    suite("LIN1-C-03 children subtrees")(
      test(
        "root round 1 fans out two delegations and two tools, in emission order"
      ) {
        val graph = rightOf(LineageReducer.reduce(campaign1))
        graph.roots.collectFirst {
          case m: SessionNode.ModelCall if m.round == 1 => m
        } match
          case Some(mc1) =>
            val kids = graph.childrenOf(mc1.id.value)
            val dels = kids.collect { case d: SessionNode.Delegation => d }
            val toolIds = kids.collect { case t: SessionNode.ToolCall =>
              t.callId
            }
            assertTrue(
              dels.map(_.childJobId) == Vector(child1, child2),
              dels.map(_.agent) == Vector("worker-a", "worker-b"),
              toolIds == Vector("call-root-1", "call-root-2"),
              dels.forall(_.parent == Some(mc1.id)),
              // dataflow chains per job: del1 inputs the round, del2 inputs del1
              dels(0).inputs == Vector(mc1.id),
              dels(1).inputs == Vector(dels(0).id)
            )
          case None =>
            throw new IllegalStateException("root round 1 must exist")
      },
      test(
        "child-1 subtree: two rounds under its delegation, both tools under round 1"
      ) {
        val graph = rightOf(LineageReducer.reduce(campaign1))
        delegationOf(graph, child1) match
          case Some(del) =>
            val rounds = graph.childrenOf(del.id.value).collect {
              case m: SessionNode.ModelCall => m
            }
            val tools = rounds.headOption
              .map(r => graph.childrenOf(r.id.value))
              .getOrElse(Vector.empty)
              .collect { case t: SessionNode.ToolCall => t }
            assertTrue(
              rounds.map(_.round) == Vector(1, 2),
              rounds.headOption.map(_.parent) == Some(Some(del.id)),
              rounds.headOption.map(_.inputs) == Some(Vector(del.id)),
              tools.map(_.callId) ==
                Vector("call-child1-1", "call-child1-2"),
              tools.map(_.outcome) ==
                Vector(Some(ToolOutcome.Succeeded), Some(ToolOutcome.Timeout)),
              tools.map(_.recovery) ==
                Vector(RecoveryClass.Mutating, RecoveryClass.Mutating)
            )
          case None =>
            throw new IllegalStateException(s"delegation of $child1 must exist")
      },
      test("child-2 subtree: separate delegation, cancelled search tool") {
        val graph = rightOf(LineageReducer.reduce(campaign1))
        delegationOf(graph, child2) match
          case Some(del) =>
            val rounds = graph.childrenOf(del.id.value).collect {
              case m: SessionNode.ModelCall => m
            }
            val tools = rounds.headOption
              .map(r => graph.childrenOf(r.id.value))
              .getOrElse(Vector.empty)
              .collect { case t: SessionNode.ToolCall => t }
            assertTrue(
              rounds.map(_.round) == Vector(1),
              tools.map(_.callId) == Vector("call-child2-1"),
              tools.map(_.outcome) == Vector(Some(ToolOutcome.Cancelled)),
              tools.map(_.recovery) == Vector(RecoveryClass.ReadOnly)
            )
          case None =>
            throw new IllegalStateException(s"delegation of $child2 must exist")
      },
      test(
        "roots are the root job's three rounds; taxonomy bash/edit -> Mutating, search -> ReadOnly"
      ) {
        val graph = rightOf(LineageReducer.reduce(campaign1))
        val rootRounds = graph.roots.collect { case m: SessionNode.ModelCall =>
          m.round
        }
        val recovery = graph.nodes.collect { case t: SessionNode.ToolCall =>
          t.tool -> t.recovery
        }.toMap
        assertTrue(
          rootRounds == Vector(1, 2, 3),
          recovery("bash") == RecoveryClass.Mutating,
          recovery("edit") == RecoveryClass.Mutating,
          recovery("search") == RecoveryClass.ReadOnly
        )
      },
      test(
        "TAX-01 (RAI-148): fs_patch/proc_run reduce to Mutating tool nodes; unknown fs_read stays ReadOnly"
      ) {
        val job = JobId("job-tax")
        val events = Vector(
          env(1, job, EventPayload.ModelCallStarted(1, "m", None)),
          env(2, job, EventPayload.ModelCallFinished(1, 5L, usage)),
          env(
            3,
            job,
            EventPayload.ToolStarted("call-fp", "fs_patch", "path=App.scala")
          ),
          env(
            4,
            job,
            EventPayload.ToolFinished(
              "call-fp",
              "fs_patch",
              ToolOutcome.Succeeded
            )
          ),
          env(
            5,
            job,
            EventPayload.ToolStarted("call-pr", "proc_run", "argv=make")
          ),
          env(
            6,
            job,
            EventPayload.ToolFinished(
              "call-pr",
              "proc_run",
              ToolOutcome.Succeeded
            )
          ),
          env(
            7,
            job,
            EventPayload.ToolStarted("call-rd", "fs_read", "path=App.scala")
          ),
          env(
            8,
            job,
            EventPayload.ToolFinished(
              "call-rd",
              "fs_read",
              ToolOutcome.Succeeded
            )
          ),
          env(9, job, EventPayload.Terminal(JobStatus.Succeeded))
        )
        val graph = rightOf(LineageReducer.reduce(events))
        val recovery = graph.nodes.collect { case t: SessionNode.ToolCall =>
          t.tool -> t.recovery
        }.toMap
        assertTrue(
          recovery("fs_patch") == RecoveryClass.Mutating,
          recovery("proc_run") == RecoveryClass.Mutating,
          recovery("fs_read") == RecoveryClass.ReadOnly
        )
      },
      test(
        "delegated child attaches under its delegation node (control + dataflow)"
      ) {
        val graph = rightOf(LineageReducer.reduce(withChild))
        delegationOf(graph, childY) match
          case Some(del) =>
            val childRound = graph.childrenOf(del.id.value).collectFirst {
              case m: SessionNode.ModelCall => m
            }
            assertTrue(
              graph.nodes.size == 3,
              graph.roots.map(_.id) == Vector(NodeId("job-y:n1")),
              del.id == NodeId("job-y:n3"),
              del.parent.map(_.value) == Some("job-y:n1"),
              childRound.map(_.parent) == Some(Some(del.id)),
              childRound.map(_.inputs) == Some(Vector(del.id))
            )
          case None =>
            throw new IllegalStateException(s"delegation of $childY must exist")
      }
    ),
    // --------------------------------------------------------------
    // LIN5-03 (F-L28-3): partial-campaign reduce — a campaign tolerates
    // missing Terminal per job; only COMPLETED nodes survive, no half-nodes;
    // every other ReduceError stays strict. Batch reduce is NOT changed.
    // --------------------------------------------------------------
    suite("LIN5-03 partial campaign reduce (reducePartial)")(
      test("intact campaign: reducePartial == batch reduce") {
        val batch = LineageReducer.reduce(campaign1)
        val partial = LineageReducer.reducePartial(campaign1)
        assertTrue(
          batch.isRight,
          partial == batch,
          partial.map(GraphHash.apply) == batch.map(GraphHash.apply)
        )
      },
      test(
        "torn-Terminal prefix: strict reduce refuses, reducePartial keeps every completed node"
      ) {
        // REAL crash shape (W10 F-L28-3): the root finishes LAST, so the torn
        // final line is the root's Terminal — the strict reducer's
        // MissingTerminal, the partial reducer's honest prefix.
        val prefix = campaign1.dropRight(1)
        assertTrue(
          LineageReducer.reduce(prefix) ==
            Left(ReduceError.MissingTerminal("job-1-root"))
        )
        val a = LineageReducer.reducePartial(prefix)
        val b = LineageReducer.reducePartial(prefix)
        val full = rightOf(LineageReducer.reduce(campaign1))
        assertTrue(
          a.isRight,
          a == b, // deterministic: pure fold, same input -> same graph
          a.map(GraphHash.apply) == Right(GraphHash(full)),
          a.map(_.nodes.length) == Right(full.nodes.length)
        )
      },
      test(
        "root's tool open on the slice: no node for the open tool, prior nodes kept"
      ) {
        val cut = prefixThrough(campaign1, root1, toolStarted("call-root-3"))
        val graph = rightOf(LineageReducer.reducePartial(cut))
        val tool3 =
          nodeIdAt(root1, seqOf(campaign1, root1, toolStarted("call-root-3")))
        val round3 = nodeIdAt(root1, seqOf(campaign1, root1, modelStarted(3)))
        assertTrue(
          graph.validated.isRight,
          !graph.nodes.exists(_.id == tool3), // started, never finished
          !graph.nodes.exists(_.id == round3), // round 3 never even started
          graph.nodes.size == rightOf(
            LineageReducer.reduce(campaign1)
          ).nodes.size - 2
        )
      },
      test(
        "root's round open on the slice: the started-but-unfinished round contributes NO node"
      ) {
        // The key no-half-nodes case: batch reduce creates the ModelCall node
        // at ModelCallStarted; reducePartial must drop it because the round
        // never finished, while the tool that DID finish inside the slice
        // stays.
        val cut = prefixThrough(campaign1, root1, modelStarted(3))
        val graph = rightOf(LineageReducer.reducePartial(cut))
        val tool3 =
          nodeIdAt(root1, seqOf(campaign1, root1, toolStarted("call-root-3")))
        val round3 = nodeIdAt(root1, seqOf(campaign1, root1, modelStarted(3)))
        assertTrue(
          graph.validated.isRight,
          !graph.nodes.exists(_.id == round3),
          graph.nodes.exists(_.id == tool3),
          graph.nodes.size == rightOf(
            LineageReducer.reduce(campaign1)
          ).nodes.size - 1
        )
      },
      test(
        "sawtooth crash: per-job cuts are independent (child open, root mid-campaign, child-2 terminal)"
      ) {
        val cut = prefixThrough(campaign1, child1, toolStarted("call-child1-2"))
        val graph = rightOf(LineageReducer.reducePartial(cut))
        val full = rightOf(LineageReducer.reduce(campaign1))
        val childTool2 =
          nodeIdAt(
            child1,
            seqOf(campaign1, child1, toolStarted("call-child1-2"))
          )
        val childRound2 =
          nodeIdAt(child1, seqOf(campaign1, child1, modelStarted(2)))
        val rootRound2 =
          nodeIdAt(root1, seqOf(campaign1, root1, modelStarted(2)))
        assertTrue(
          graph.validated.isRight,
          !graph.nodes.exists(_.id == childTool2), // child-1's open tool
          !graph.nodes.exists(
            _.id == childRound2
          ), // child-1's round 2: not yet emitted
          graph.nodes.exists(_.id == rootRound2), // root's closed round kept
          // missing vs full: child-1's open tool (no node yet), child-1's
          // round 2, root's tool-3 + round-3 (not yet emitted) — 4 of the 14
          graph.nodes.size == full.nodes.size - 4
        )
      },
      test(
        "single open round, nothing else: empty partial graph, still valid"
      ) {
        val lonely = Vector(
          env(1, jobX, EventPayload.ModelCallStarted(1, "m", None))
        )
        assertTrue(
          LineageReducer.reduce(lonely) ==
            Left(ReduceError.MissingTerminal("job-x")),
          LineageReducer.reducePartial(lonely).map(_.nodes.size) == Right(0),
          LineageReducer.reducePartial(lonely).flatMap(_.validated).isRight
        )
      },
      test("strict errors stay strict under reducePartial") {
        val gap = Vector(
          env(1, jobX, EventPayload.ModelCallStarted(1, "m", None)),
          env(3, jobX, EventPayload.ModelCallFinished(1, 10L, usage))
        )
        val ghost = Vector(
          env(1, jobX, EventPayload.ModelCallStarted(1, "m", None)),
          env(
            1,
            JobId("job-ghost"),
            EventPayload.ModelCallStarted(1, "m", None)
          )
        )
        val afterTerm = Vector(
          env(1, jobX, EventPayload.Terminal(JobStatus.Succeeded)),
          env(2, jobX, EventPayload.Terminal(JobStatus.Succeeded))
        )
        val orphan = Vector(
          env(
            1,
            jobX,
            EventPayload.ToolFinished("call-9", "bash", ToolOutcome.Succeeded)
          )
        )
        assertTrue(
          LineageReducer.reducePartial(Vector.empty) == Left(ReduceError.Empty),
          LineageReducer.reducePartial(gap) ==
            Left(ReduceError.SeqGap("job-x", 2L, 3L)),
          LineageReducer.reducePartial(ghost) ==
            Left(ReduceError.UnknownParent("job-ghost", 1L)),
          LineageReducer.reducePartial(afterTerm) ==
            Left(ReduceError.AfterTerminal("job-x", 2L)),
          LineageReducer.reducePartial(orphan) ==
            Left(
              ReduceError.UnmatchedFinish(
                "job-x",
                1L,
                "ToolFinished(callId=call-9)"
              )
            )
        )
      },
      test(
        "property: EVERY prefix of a valid campaign partially reduces to a valid graph"
      ) {
        check(Gen.int(1, campaign1.length)) { k =>
          val reduced = LineageReducer.reducePartial(campaign1.take(k))
          assertTrue(
            reduced.isRight, // MissingTerminal is never a partial failure
            reduced.flatMap(_.validated).isRight
          )
        }
      }
    )
  )
