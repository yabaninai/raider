package raider.cli.lineage

import raider.core.{JobId, RaiderError, RequestMessage, RootId, SessionId, Task}
import raider.dsl.lineage.data.{
  DataNode,
  GeneratorInfo,
  LineageDataDocument,
  NodeData,
  NodeKind,
  Usage as LineageUsage
}
import raider.dsl.lineage.replay.RecordedSource
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.events.{EventEnvelope, EventHub, ToolOutcome}
import raider.runtime.jobs.{JobManager, StatusLedger}
import raider.runtime.lineage.model.{
  DataRef,
  NodeId,
  RecoveryClass,
  SessionGraph,
  SessionNode
}
import raider.runtime.lineage.reduce.{LineageReducer, ReduceError}
import raider.runtime.lineage.replay.{GraphComparator, GraphDiff}
import raider.runtime.loop.{AgentLoop, LoopEvents}
import raider.tools.CodingToolset
import zio.{Duration as ZDuration, Ref, ZIO}

import java.nio.file.Path

/** End-to-end replay harness (LIN-06B-1, packet L48, ADR-027 §2): LIN5-01
  * executed FOR REAL — a recorded lineage-data document is replayed through an
  * UNMODIFIED [[AgentLoop]] ([[ReplayModelBackend]] as the backend, the real
  * coding toolset against an isolated fixture workspace, the real
  * [[EventHub]]/[[JobManager]]/[[StatusLedger]] emission stack), the emitted
  * envelopes are reduced by the real [[LineageReducer]], and the replayed graph
  * is compared against the graph recorded in the SAME document with
  * [[GraphComparator.compareNormalized]].
  *
  * Fixture campaign (frozen here so every lane/test replays the same record):
  * two model rounds over one root job —
  *   1. a tool_use round: the recorded response carries the `tool_calls`
  *      envelope for `fs_read {"path":"notes.txt"}` (the ReplayModelBackend
  *      LIN-06B translation serves it as `ToolCallReady`);
  *   2. a text round: the final answer, served as the synthetic single chunk.
  * The document carries FULL prompt/response strings and the three nodes
  * (round-1 model call, the fs_read tool call, round-2 model call).
  *
  * Seq layout: the envelope seq of a node-defining event must equal the
  * recorded node's `seq`. With the job layer ([[JobManager]] +
  * [[StatusLedger]]) the campaign publishes exactly: 1
  * `JobStatusChanged(Queued→Running)`, 2 `Diagnostics(round 1)`, 3 `Waiting`, 4
  * `ModelCallStarted(1)` → node n4, 5 `ModelCallFinished`, 6 `Waiting`, 7
  * `ToolStarted` , 8 `ToolFinished` → node n7 (the START's seq), 9
  * `Diagnostics(round 2)`, 10 `Waiting`, 11 `ModelCallStarted(2)` → node n11,
  * 12 `ModelCallFinished`, 13 `Diagnostics(round 2 text)`, 14
  * `Terminal(Succeeded)`. The recorded nodes pin seqs 4/7/11 accordingly. If
  * the loop's emission seams ever change, the reducer/comparison diverges
  * LOUDLY — the layout is asserted, never hand-waved.
  *
  * Determinism (H-3): every replay run uses a fresh backend/hub/admission/
  * manager over the SAME document; the run-varying identity (job `j_1` vs the
  * recorded job, root/session ids, wall-clock `atMs`) is neutralized by
  * `compareNormalized`, so two runs and the recording share one normalized
  * hash.
  */
object ReplayHarness:

  /** Recorded-side job name used in the fixture document's node ids/refs. */
  val RecordJob = "job-rec"

  /** The replay-side job: a FRESH JobManager's first job is deterministically
    * `j_1` (the delegation specs parse the same `j_<n>` convention), so the
    * harness can subscribe BEFORE `manager.start` and lose no envelope.
    * Re-checked eagerly after start — a broken assumption fails typed.
    */
  val ReplayJob = JobId("j_1")

  val ReplayRoot = RootId("root-l48")
  val RecordedRoot = RootId("root-rec")
  val RecordedSession = SessionId("sess-rec")

  val ModelName = "recorded-model"
  val ToolName = "fs_read"
  val CallId = "call-1"
  val FixtureFile = "notes.txt"
  val FixtureFileContent = "hello from the fixture workspace\n"
  val UserMessage = "Read notes.txt and tell me what it says."

  /** Recorded round-1 response: the `tool_calls` envelope (the LIN-06B record
    * convention for a tool round, mirroring the OpenAI-compatible wire).
    */
  val Round1ToolResponse =
    """{"tool_calls":[{"id":"call-1","name":"fs_read",""" +
      """"arguments":{"path":"notes.txt"}}]}"""

  val Round2FinalResponse =
    "The file says: hello from the fixture workspace"

  // Full recorded prompts (lineage-data keeps prompt/response as strings).
  private val Round1Prompt = UserMessage

  private val Round2Prompt =
    s"""$UserMessage
       |[assistant] $Round1ToolResponse
       |[tool call-1 result] {"totalLines":1,"truncated":false,"content":"hello from the fixture workspace\\n"}""".stripMargin

  val Round1Ref = s"$RecordJob/round-1"
  val Round2Ref = s"$RecordJob/round-2"
  val ToolRef = s"$RecordJob/call-$CallId"

  /** The fixture document: full prompt/response + the three recorded nodes at
    * seqs 4/7/11 (see the object scaladoc). `atMs` are FIXED sample values —
    * the replay's wall-clock values differ, and `compareNormalized` must not
    * care.
    */
  def document: LineageDataDocument = LineageDataDocument(
    schemaVersion = 1,
    generator = GeneratorInfo("replay-harness-l48", "0", "3.9.0", "21"),
    runId = "run-l48",
    nodes = Vector(
      DataNode(
        id = s"$RecordJob:n4",
        kind = NodeKind.ModelCall,
        parentId = None,
        seq = 4L,
        atMs = 1000L,
        inputs = Vector.empty,
        dataRef = Some(Round1Ref)
      ),
      DataNode(
        id = s"$RecordJob:n7",
        kind = NodeKind.ToolCall,
        parentId = Some(s"$RecordJob:n4"),
        seq = 7L,
        atMs = 2000L,
        inputs = Vector(s"$RecordJob:n4"),
        dataRef = Some(ToolRef)
      ),
      DataNode(
        id = s"$RecordJob:n11",
        kind = NodeKind.ModelCall,
        parentId = None,
        seq = 11L,
        atMs = 3000L,
        inputs = Vector(s"$RecordJob:n7"),
        dataRef = Some(Round2Ref)
      )
    ),
    data = Map(
      Round1Ref -> NodeData.ModelCallData(
        model = ModelName,
        round = 1,
        prompt = Round1Prompt,
        response = Round1ToolResponse,
        usage = Some(LineageUsage(None, Some(1200L), false))
      ),
      ToolRef -> NodeData.ToolCallData(
        tool = ToolName,
        callId = CallId,
        recovery = "ReadOnly",
        outcome = Some("Succeeded"),
        argsSummary = "path=notes.txt",
        resultTail = Some("hello from the fixture workspace")
      ),
      Round2Ref -> NodeData.ModelCallData(
        model = ModelName,
        round = 2,
        prompt = Round2Prompt,
        response = Round2FinalResponse,
        usage = Some(LineageUsage(None, Some(900L), false))
      )
    )
  )

  /** A copy of `doc` with ONE recorded response swapped (H-2): the nodes are
    * untouched, only the data resource changes — the replay of the mutated
    * record must diverge from the recording where the swapped round's effects
    * begin.
    */
  def withResponse(
      doc: LineageDataDocument,
      dataRef: String,
      response: String
  ): LineageDataDocument =
    doc.data.get(dataRef) match
      case Some(m: NodeData.ModelCallData) =>
        doc.copy(
          data = doc.data.updated(
            dataRef,
            m.copy(response = response)
          )
        )
      case _ =>
        sys.error(s"fixture bug: '$dataRef' is not a ModelCallData ref")

  /** The graph recorded in `doc`: the node table translated through the data
    * resource (model/round from `ModelCallData`; callId/tool/recovery/outcome
    * from `ToolCallData`), validated like every SessionGraph. Construction is
    * total over the frozen fixture — a violated assumption is a fixture bug and
    * fails as one.
    */
  def recordedGraph(doc: LineageDataDocument): SessionGraph =
    def dataOf(ref: String): NodeData =
      doc.data.getOrElse(
        ref,
        sys.error(s"fixture bug: missing data entry '$ref'")
      )
    def recoveryOf(raw: String): RecoveryClass =
      try RecoveryClass.valueOf(raw)
      catch
        case _: IllegalArgumentException =>
          sys.error(s"fixture bug: unknown recovery '$raw'")
    def outcomeOf(raw: Option[String]): Option[ToolOutcome] = raw.map {
      case "Succeeded" => ToolOutcome.Succeeded
      case "Timeout"   => ToolOutcome.Timeout
      case "Cancelled" => ToolOutcome.Cancelled
      case other =>
        sys.error(s"fixture bug: unsupported recorded outcome '$other'")
    }
    val nodes = doc.nodes.map { n =>
      val ref = n.dataRef.getOrElse(
        sys.error(s"fixture bug: node ${n.id} has no dataRef")
      )
      val id = NodeId(n.id)
      val parent = n.parentId.map(NodeId(_))
      val inputs = n.inputs.map(NodeId(_))
      (n.kind, dataOf(ref)) match
        case (NodeKind.ModelCall, m: NodeData.ModelCallData) =>
          SessionNode.ModelCall(
            id,
            parent,
            n.seq,
            n.atMs,
            m.round,
            m.model,
            inputs,
            DataRef(ref)
          )
        case (NodeKind.ToolCall, t: NodeData.ToolCallData) =>
          SessionNode.ToolCall(
            id,
            parent,
            n.seq,
            n.atMs,
            t.callId,
            t.tool,
            recoveryOf(t.recovery),
            outcomeOf(t.outcome),
            inputs,
            Some(DataRef(ref))
          )
        case _ =>
          sys.error(
            s"fixture bug: node ${n.id} kind ${n.kind} vs data under '$ref'"
          )
    }
    SessionGraph(RecordedRoot, RecordedSession, nodes).validated.fold(
      violation =>
        sys.error(s"fixture bug: recorded graph invalid: $violation"),
      identity
    )

  /** One replay run over `doc` against `workspace`: everything real — the
    * coding toolset's `fs_read` (isolated workspace containment), the loop, the
    * hub, the job layer, the reducer, the normalized comparator.
    *
    * The typed error channel carries LOOP failures only (budget/admission/
    * model/tool): a replay that cannot complete is a failure, never a silent
    * mismatch. Reducer/comparison outcomes are DATA on `ReplayRun`.
    */
  final case class ReplayRun(
      answer: String,
      envelopeCount: Int,
      droppedDiagnostics: Long,
      graph: Either[ReduceError, SessionGraph],
      comparison: Option[Either[GraphDiff, String]]
  )

  /** Runs the campaign: fixture → ReplayModelBackend → real loop over the REAL
    * `fs_read` tool → hub → reducer → compareNormalized against the graph
    * recorded in `doc`.
    */
  def replay(
      workspace: Path,
      doc: LineageDataDocument = document
  ): ZIO[Any, RaiderError, ReplayRun] =
    for
      toolset <- CodingToolset.make(workspace.toString)
      registry <- ZIO.fromEither(toolset.registry)
      hub <- EventHub.make()
      limits = BudgetLimits.make(
        maxAttempts = 4,
        maxConcurrentLlm = 1,
        maxConcurrentTools = 1
      )
      adm <- Admission.make(limits)
      lims = limits.fold(e => sys.error(s"fixture bug: limits: $e"), identity)
      backend = ReplayModelBackend(RecordedSource(doc), doc)
      // Subscribe BEFORE the campaign starts (no loss window): the fresh
      // manager's first job is deterministically `j_1` (re-checked below).
      (_, stream) <- hub.subscribe(ReplayJob)
      sink <- Ref.make(Vector.empty[EventEnvelope])
      fiber <- stream.runForeach(e => sink.update(_ :+ e)).fork
      jobs <- JobManager.make(
        onTransition = Some(StatusLedger.onTransition(hub, ReplayRoot))
      )
      loop = AgentLoop(
        backend,
        registry,
        ModelName,
        adm,
        ReplayRoot,
        events = Some(
          LoopEvents(hub, ReplayJob, ReplayRoot, StatusLedger.sessionId)
        )
      )
      job <- jobs.start(
        ReplayRoot,
        "replay-l48",
        Task(
          loop.runText(
            List(RequestMessage("user", UserMessage)),
            lims,
            maxRounds = 2
          )
        )
      )
      _ <- ZIO.unless(job.id == ReplayJob)(
        ZIO.fail(
          RaiderError.InputValidation(
            s"fixture assumption broken: first job is ${job.id.value}, expected ${ReplayJob.value}"
          )
        )
      )
      answer <- jobs.await(job)
      _ <- eventuallyTerminal(sink).mapError(RaiderError.InputValidation(_))
      envs <- sink.get
      _ <- fiber.interrupt
      dropped <- hub.droppedDiagnostics
      ordered = envs.sortBy(_.seq)
      graph = LineageReducer.reduce(ordered)
      comparison = graph.toOption.map(g =>
        GraphComparator.compareNormalized(recordedGraph(doc), g)
      )
    yield ReplayRun(
      answer = answer,
      envelopeCount = ordered.size,
      droppedDiagnostics = dropped,
      graph = graph,
      comparison = comparison
    )

  /** Generous outer bound around a GUARANTEED condition: the Terminal is
    * committed to the hub state before `jobs.await` returns; only the
    * forwarder's relay to the subscriber queue is asynchronous. The real
    * duration is scheduling noise; the bound only converts a defective hang
    * into a failure (same discipline as the emission specs).
    */
  private def eventuallyTerminal(
      sink: Ref[Vector[EventEnvelope]]
  ): ZIO[Any, String, Unit] =
    val deadline = System.nanoTime() + ZDuration.fromSeconds(10).toNanos
    def loop: ZIO[Any, String, Unit] =
      sink.get.flatMap { envs =>
        if envs.exists(
            _.payload.isInstanceOf[raider.runtime.events.EventPayload.Terminal]
          )
        then ZIO.unit
        else if System.nanoTime() >= deadline then
          ZIO.fail("terminal envelope not delivered within the outer bound")
        else ZIO.sleep(ZDuration.fromMillis(20)) *> loop
      }
    loop

end ReplayHarness
