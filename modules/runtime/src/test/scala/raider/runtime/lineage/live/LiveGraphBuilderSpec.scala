package raider.runtime.lineage.live

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.events.{EventEnvelope, EventPayload, ToolOutcome}
import raider.runtime.jobs.JobStatus
import raider.runtime.lineage.corpus.LineageCorpus
import raider.runtime.lineage.model.{GraphHash, SessionNode}
import raider.runtime.lineage.reduce.{LineageReducer, ReduceError}
import zio.test.*

/** LIN-03A acceptance tests (packet L21, ADR-027): [[LiveGraphBuilder]] — live
  * incremental graph building with the exact semantics of the batch
  * [[LineageReducer]].
  *
  *   - LIN3-A-01: the fold of one-event applications over a campaign stream
  *     equals the batch reduce of the same stream — same node vector, same
  *     [[GraphHash]] — across 40 seeds (240 model rounds / ~2200 applied
  *     envelopes, the >=200-rounds budget of the parent card, >=3 seeds);
  *     end-of-stream (`finish`) mirrors the batch tail, including
  *     `MissingTerminal` and `Empty`.
  *   - LIN3-A-02: one broken event yields the exact typed [[ReduceError]] and
  *     leaves the committed state alive — the stream continues from it and
  *     still reaches the batch graph of the clean stream.
  *   - LIN3-A-03: crash-consistency at the node boundary — a tool node is
  *     observed either whole or absent; the state committed at ANY event
  *     boundary is a valid prefix artifact and resuming from it reaches the
  *     batch graph; a job's `Terminal` flushes open tools as complete nodes
  *     atomically.
  *
  * No live services, no wall clock: the corpus generator and hand-built streams
  * are pure data.
  */
object LiveGraphBuilderSpec extends ZIOSpecDefault:

  private def rightOf[A](e: Either[ReduceError, A]): A = e match
    case Right(a) => a
    case Left(err) =>
      throw new IllegalStateException(s"fixture must hold, got: $err")

  /** Fold of single-event applications — the live usage pattern. */
  private def fold(
      events: Vector[EventEnvelope]
  ): Either[ReduceError, LiveGraphState] =
    events.foldLeft[Either[ReduceError, LiveGraphState]](
      Right(LiveGraphBuilder.initial)
    )((acc, e) => acc.flatMap(LiveGraphBuilder.apply(_, e)))

  /** Resume from a committed (possibly crash-recovered) state. */
  private def foldFrom(
      state: LiveGraphState,
      events: Vector[EventEnvelope]
  ): Either[ReduceError, LiveGraphState] =
    events.foldLeft[Either[ReduceError, LiveGraphState]](Right(state))(
      (acc, e) => acc.flatMap(LiveGraphBuilder.apply(_, e))
    )

  /** Hand-built envelope with explicit per-job seq (adversarial streams need
    * deliberately wrong seqs, so no auto-counter here).
    */
  private def env(
      seq: Long,
      job: JobId,
      payload: EventPayload
  ): EventEnvelope =
    EventEnvelope(
      1,
      seq,
      job,
      RootId("root-x"),
      SessionId("sess-x"),
      1760000000000L + seq * 250L,
      payload
    )

  def spec = suite("LiveGraphBuilder (LIN-03A)")(
    // --------------------------------------------------------------
    // LIN3-A-01: fold of applications == batch reduce (GraphHash equal)
    // --------------------------------------------------------------
    suite("LIN3-A-01 equivalence: fold of applies == batch reduce")(
      test(
        "40 campaign seeds: same nodes, same GraphHash, >=200 model rounds"
      ) {
        val seeds = (1 to 40).toVector
        val streams = seeds.map(seed => (seed, LineageCorpus.campaign(seed)))
        val pairs = streams.map { case (_, events) =>
          val batch = rightOf(LineageReducer.reduce(events))
          val live = rightOf(LiveGraphBuilder.reduce(events))
          (batch, live)
        }
        val nodesEqual = pairs.forall { case (b, l) => b.nodes == l.nodes }
        val hashesEqual = pairs.forall { case (b, l) =>
          GraphHash(b) == GraphHash(l)
        }
        val idsEqual = pairs.forall { case (b, l) =>
          b.rootId == l.rootId && b.sessionId == l.sessionId
        }
        val rounds = pairs.map { case (b, _) =>
          b.nodes.count {
            case _: SessionNode.ModelCall => true
            case _                        => false
          }
        }.sum
        val envelopes = streams.map { case (_, e) => e.size }.sum
        assertTrue(
          pairs.size >= 3,
          nodesEqual,
          hashesEqual,
          idsEqual,
          rounds >= 200,
          envelopes >= 200
        )
      },
      test(
        "per-step snapshots stay valid prefix graphs; nodes only grow at the tail"
      ) {
        val events = LineageCorpus.campaign(3)
        val batchNodes = rightOf(LineageReducer.reduce(events)).nodes
        val folded = events.zipWithIndex.foldLeft(
          (LiveGraphBuilder.initial, Vector.empty[Int])
        ) { case ((prev, bad), (e, i)) =>
          LiveGraphBuilder.apply(prev, e) match
            case Right(next) =>
              val ok =
                next.snapshot.isRight &&
                  next.nodes.take(prev.nodes.length) == prev.nodes &&
                  next.applied == prev.applied + 1
              (next, if ok then bad else bad :+ i)
            case Left(err) =>
              throw new IllegalStateException(
                s"campaign prefix must apply at step $i: $err"
              )
        }
        assertTrue(
          folded._2.isEmpty,
          folded._1.applied == events.length,
          folded._1.nodes == batchNodes
        )
      },
      test(
        "finish mirrors the batch tail: MissingTerminal without the last Terminal"
      ) {
        val events = LineageCorpus.campaign(1)
        val state = rightOf(fold(events.dropRight(1)))
        assertTrue(
          LiveGraphBuilder.finish(state) ==
            Left(ReduceError.MissingTerminal("job-1-root")),
          LineageReducer.reduce(events.dropRight(1)) ==
            Left(ReduceError.MissingTerminal("job-1-root"))
        )
      },
      test(
        "NotStarted: no artifact yet — Empty, mirroring the batch reduce of an empty stream"
      ) {
        assertTrue(
          LiveGraphBuilder.initial == LiveGraphState.NotStarted,
          LiveGraphBuilder.initial.applied == 0,
          LiveGraphBuilder.initial.nodes.isEmpty,
          LiveGraphBuilder.initial.rootJob.isEmpty,
          LiveGraphBuilder.initial.identity.isEmpty,
          LiveGraphBuilder.initial.snapshot == Left(ReduceError.Empty),
          LiveGraphBuilder.finish(LiveGraphBuilder.initial) ==
            Left(ReduceError.Empty),
          LineageReducer.reduce(Vector.empty) == Left(ReduceError.Empty)
        )
      }
    ),
    // --------------------------------------------------------------
    // LIN3-A-02: one broken event -> typed error, committed state alive
    // --------------------------------------------------------------
    suite("LIN3-A-02 one broken event leaves the session alive")(
      test(
        "wrong schemaVersion -> SchemaVersionUnsupported, stream continues"
      ) {
        val events = LineageCorpus.campaign(7)
        val k = 20
        val before = rightOf(fold(events.take(k)))
        val attempt = LiveGraphBuilder.apply(
          before,
          events(k).copy(schemaVersion = LineageCorpus.WrongSchemaVersion)
        )
        val finalGraph = rightOf(
          foldFrom(before, events.drop(k)).flatMap(LiveGraphBuilder.finish)
        )
        val batchGraph = rightOf(LineageReducer.reduce(events))
        assertTrue(
          attempt ==
            Left(
              ReduceError.SchemaVersionUnsupported(
                LineageCorpus.WrongSchemaVersion
              )
            ),
          before.applied == k,
          finalGraph == batchGraph,
          GraphHash(finalGraph) == GraphHash(batchGraph)
        )
      },
      test("unknown parent -> UnknownParent, stream continues") {
        val events = LineageCorpus.campaign(7)
        val k = 21
        val before = rightOf(fold(events.take(k)))
        val ghost = JobId("job-ghost")
        val attempt =
          LiveGraphBuilder.apply(before, events(k).copy(jobId = ghost))
        val finalGraph = rightOf(
          foldFrom(before, events.drop(k)).flatMap(LiveGraphBuilder.finish)
        )
        val batchGraph = rightOf(LineageReducer.reduce(events))
        assertTrue(
          attempt ==
            Left(ReduceError.UnknownParent("job-ghost", events(k).seq)),
          before.applied == k,
          finalGraph == batchGraph,
          GraphHash(finalGraph) == GraphHash(batchGraph)
        )
      },
      test("duplicate seq -> SeqGap, stream continues") {
        val events = LineageCorpus.campaign(11)
        // The campaign opens with two root-job events (seq 1, then 2);
        // replaying seq 1 is the classic duplicate.
        val before = rightOf(fold(events.take(1)))
        val dup = events(1).copy(seq = events(0).seq)
        val attempt = LiveGraphBuilder.apply(before, dup)
        val finalGraph = rightOf(
          foldFrom(before, events.drop(1)).flatMap(LiveGraphBuilder.finish)
        )
        val batchGraph = rightOf(LineageReducer.reduce(events))
        assertTrue(
          attempt == Left(ReduceError.SeqGap("job-11-root", 2L, 1L)),
          before.applied == 1,
          finalGraph == batchGraph,
          GraphHash(finalGraph) == GraphHash(batchGraph)
        )
      },
      test(
        "ToolFinished without an open start -> UnmatchedFinish, stream continues"
      ) {
        val events = LineageCorpus.campaign(5)
        val root = "job-5-root"
        events.indexWhere { e =>
          e.jobId.value == root && (e.payload match
            case p: EventPayload.ToolStarted => p.callId == "call-root-1"
            case _                           => false)
        } match
          case k if k > 0 =>
            val before = rightOf(fold(events.take(k)))
            val broken = events(k).copy(payload =
              EventPayload.ToolFinished(
                "call-ghost",
                "bash",
                ToolOutcome.Succeeded
              )
            )
            val attempt = LiveGraphBuilder.apply(before, broken)
            val finalGraph = rightOf(
              foldFrom(before, events.drop(k)).flatMap(LiveGraphBuilder.finish)
            )
            val batchGraph = rightOf(LineageReducer.reduce(events))
            assertTrue(
              attempt == Left(
                ReduceError.UnmatchedFinish(
                  root,
                  events(k).seq,
                  "ToolFinished(callId=call-ghost)"
                )
              ),
              before.applied == k,
              finalGraph == batchGraph,
              GraphHash(finalGraph) == GraphHash(batchGraph)
            )
          case bad =>
            throw new IllegalStateException(
              s"fixture: root tool start not found ($bad)"
            )
      },
      test("event after Terminal -> AfterTerminal, stream continues") {
        val events = LineageCorpus.campaign(9)
        val child2 = JobId("job-9-child-2")
        events.indexWhere { e =>
          e.jobId == child2 && (e.payload match
            case _: EventPayload.Terminal => true
            case _                        => false)
        } match
          case tIdx if tIdx > 0 =>
            val before = rightOf(fold(events.take(tIdx + 1)))
            // A late control-plane event for the settled child job: the seq
            // matches the next expected value, only the terminal check can
            // catch it.
            val late = events(tIdx).copy(
              seq = events(tIdx).seq + 1L,
              payload = EventPayload.JobStatusChanged(
                JobStatus.Running,
                JobStatus.Running,
                None
              )
            )
            val attempt = LiveGraphBuilder.apply(before, late)
            val finalGraph = rightOf(
              foldFrom(before, events.drop(tIdx + 1))
                .flatMap(LiveGraphBuilder.finish)
            )
            val batchGraph = rightOf(LineageReducer.reduce(events))
            assertTrue(
              attempt == Left(
                ReduceError.AfterTerminal(child2.value, events(tIdx).seq + 1L)
              ),
              before.applied == tIdx + 1,
              finalGraph == batchGraph,
              GraphHash(finalGraph) == GraphHash(batchGraph)
            )
          case bad =>
            throw new IllegalStateException(
              s"fixture: child-2 terminal not found ($bad)"
            )
      }
    ),
    // --------------------------------------------------------------
    // LIN3-A-03: atomicity at the node boundary (crash consistency)
    // --------------------------------------------------------------
    suite("LIN3-A-03 atomicity at the node boundary")(
      test("a tool node is observed either whole or absent, never partial") {
        val events = LineageCorpus.campaign(3)
        val callId = "call-root-1"
        val startIdx = events.indexWhere { e =>
          e.payload match
            case p: EventPayload.ToolStarted => p.callId == callId
            case _                           => false
        }
        val finishIdx = events.indices.find { i =>
          i > startIdx && (events(i).payload match
            case p: EventPayload.ToolFinished => p.callId == callId
            case _                            => false)
        } match
          case Some(i) => i
          case None =>
            throw new IllegalStateException("fixture: tool finish not found")

        val afterStart = rightOf(fold(events.take(startIdx + 1)))
        val afterFinish = rightOf(fold(events.take(finishIdx + 1)))

        // The observer looks at the artifact (validated snapshot).
        def toolNodes(st: LiveGraphState): Vector[SessionNode.ToolCall] =
          rightOf(st.snapshot).nodes.collect {
            case t: SessionNode.ToolCall if t.callId == callId => t
          }

        val batchTool = rightOf(LineageReducer.reduce(events)).nodes.collect {
          case t: SessionNode.ToolCall if t.callId == callId => t
        }

        assertTrue(
          startIdx > 0,
          finishIdx > startIdx,
          // after the START: no node for the call yet — absent, not partial
          toolNodes(afterStart).isEmpty,
          // after the FINISH: exactly one complete node, start's seq/atMs,
          // verbatim outcome
          toolNodes(afterFinish).size == 1,
          toolNodes(afterFinish).head.seq == events(startIdx).seq,
          toolNodes(afterFinish).head.atMs == events(startIdx).atMs,
          toolNodes(afterFinish).head.outcome == Some(ToolOutcome.Succeeded),
          // the batch reducer's node is identical field-for-field
          toolNodes(afterFinish) == batchTool
        )
      },
      test(
        "crash at ANY event boundary: artifact valid, resume reaches the batch graph"
      ) {
        val events = LineageCorpus.campaign(13)
        val batchGraph = rightOf(LineageReducer.reduce(events))
        val boundaries = (0 to events.length).count { k =>
          fold(events.take(k)) match
            case Right(crashed) =>
              // the committed artifact is consistent at the crash point
              val artifactOk =
                crashed.applied == 0 || crashed.snapshot.isRight
              val resumed = foldFrom(crashed, events.drop(k))
              artifactOk && resumed.isRight &&
              rightOf(resumed.flatMap(LiveGraphBuilder.finish)) == batchGraph
            case Left(_) => false
        }
        assertTrue(
          boundaries == events.length + 1,
          batchGraph.nodes.nonEmpty
        )
      },
      test(
        "Terminal flushes open tools atomically as complete nodes (fold == batch on a torn job)"
      ) {
        val job = JobId("job-t")
        val stream = Vector(
          env(1, job, EventPayload.ModelCallStarted(1, "m", None)),
          env(2, job, EventPayload.ToolStarted("call-1", "bash", "ls -la")),
          env(3, job, EventPayload.Terminal(JobStatus.Succeeded))
        )
        val afterStart = rightOf(fold(stream.take(2)))
        val live = rightOf(LiveGraphBuilder.reduce(stream))
        val batch = rightOf(LineageReducer.reduce(stream))
        val flushed = live.nodes.collectFirst { case t: SessionNode.ToolCall =>
          t
        }
        assertTrue(
          // before the Terminal: the started tool contributed NO node
          afterStart.nodes.size == 1,
          afterStart.snapshot.isRight,
          // the Terminal appended the flushed node whole, outcome unknown
          live.nodes.size == 2,
          flushed.map(_.outcome) == Some(None),
          flushed.map(_.tool) == Some("bash"),
          flushed.map(_.seq) == Some(2L),
          // batch semantics preserved exactly
          batch.nodes == live.nodes,
          GraphHash(batch) == GraphHash(live)
        )
      }
    )
  )
