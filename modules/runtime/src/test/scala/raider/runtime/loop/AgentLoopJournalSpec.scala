package raider.runtime.loop

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.events.{
  EventEnvelope,
  EventHub,
  EventPayload,
  ToolOutcome
}
import raider.runtime.journal.JobJournal
import raider.runtime.jobs.JobStatus
import raider.testkit.ScriptedSequenceBackend
import zio.test.*
import zio.{Duration as ZDuration, Promise, Ref, UIO, ZIO}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

/** RAI-093 acceptance (JW-01..03): Journal -> AgentLoop wiring over the REAL
  * EventHub and the REAL filesystem journal.
  *
  * Determinism rules (same discipline as AgentLoopEmissionSpec and
  * JobJournalSpec):
  *   - all fixtures are deterministic (ScriptedSequenceBackend); the only real
  *     sleeps are generous outer bounds around logically guaranteed conditions
  *     (`eventually`), used only where a forked fiber must be observed;
  *   - journal appends are awaited INLINE by LoopEvents, so after a completed
  *     or interrupted-and-awaited run every append has already landed — no
  *     eventually is needed for journal assertions;
  *   - crash remnants are simulated by direct external byte writes (a torn line
  *     without a newline), exactly the on-disk shape a kill -9 mid-append
  *     leaves behind (JobJournalSpec JRNL-03 fixture discipline);
  *   - file fixtures use per-test temp dirs.
  */
object AgentLoopJournalSpec extends ZIOSpecDefault:

  private val job = JobId("journal-job")
  private val sess = SessionId("journal-sess")
  private val a = AttemptId("journal-att")
  private val c1 = ToolCallId("call-1")

  private def limits(
      attempts: Int = 8,
      llm: Int = 1,
      tools: Int = 1
  ): Either[RaiderError, BudgetLimits] =
    BudgetLimits.make(
      maxAttempts = attempts,
      maxConcurrentLlm = llm,
      maxConcurrentTools = tools
    )

  private def lims(attempts: Int = 8): BudgetLimits =
    limits(attempts = attempts).fold(e => throw e, identity)

  private def registryOf(tools: Tool*): ToolRegistry =
    ToolRegistry.of(tools*).fold(e => throw e, identity)

  private def echoTool: Tool = new Tool:
    def name = "lookup"
    def version = 1
    def description = "test echo tool"
    def recovery = RecoveryClass.ReadOnly
    def timeoutMs = 5000L
    def capabilities = ToolCapabilities(concurrentSafe = false)
    def invoke(args: String) = ZIO.succeed(s"""{"echo":$args}""")

  /** Two-round script: a tool round, then the final answer (same shape as the
    * RAI-058 EMIT-01 fixture: exactly 12 emission seams in program order).
    */
  private def twoRoundScript: Vector[Vector[ModelEvent]] =
    Vector(
      Vector[ModelEvent](
        ModelEvent.Started(a),
        ModelEvent.TextDelta(a, "searching..."),
        ModelEvent.ToolCallReady(a, c1, "lookup", """{"q":"x"}"""),
        ModelEvent.Finished(a, "tool_round")
      ),
      Vector[ModelEvent](
        ModelEvent.Started(a),
        ModelEvent.TextDelta(a, "final "),
        ModelEvent.TextDelta(a, "answer"),
        ModelEvent.Finished(a, "stop")
      )
    )

  /** One tool-call script (no final answer: the run parks at the tool and is
    * cancelled there).
    */
  private def oneToolScript: Vector[ModelEvent] =
    Vector(
      ModelEvent.Started(a),
      ModelEvent.ToolCallReady(a, c1, "lookup", "1"),
      ModelEvent.Finished(a, "r1")
    )

  private def loopWithJournal(
      backend: ModelBackend,
      registry: ToolRegistry,
      adm: Admission,
      root: RootId,
      hub: EventHub,
      journal: Option[JobJournal]
  ): (AgentLoop, LoopEvents) =
    val le = LoopEvents(hub, job, root, sess, journal)
    val loop = AgentLoop(backend, registry, "m", adm, root, events = Some(le))
    (loop, le)

  private def tempDir: UIO[Path] =
    ZIO.attempt(Files.createTempDirectory("raider-loop-journal-spec")).orDie

  private def appendBytes(p: Path, text: String): UIO[Unit] =
    ZIO.attempt {
      Files.writeString(
        p,
        text,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
      ()
    }.orDie

  private def readText(p: Path): UIO[String] =
    ZIO.attempt(Files.readString(p, StandardCharsets.UTF_8)).orDie

  private def segment0(dir: Path): Path = dir.resolve("segment-0.jsonl")

  private def tag(p: EventPayload): String = p match
    case _: EventPayload.Waiting           => "Waiting"
    case _: EventPayload.ModelCallStarted  => "ModelCallStarted"
    case _: EventPayload.ModelCallFinished => "ModelCallFinished"
    case _: EventPayload.ToolStarted       => "ToolStarted"
    case _: EventPayload.ToolFinished      => "ToolFinished"
    case _: EventPayload.Diagnostics       => "Diagnostics"
    case _: EventPayload.Terminal          => "Terminal"
    case other                             => other.getClass.getSimpleName

  /** The 12-seam program order of `twoRoundScript` under AgentLoop emission
    * (RAI-058 EMIT-01), used as the expected journal payload order.
    */
  private val twoRoundJournalTypes: Vector[String] = Vector(
    "Diagnostics", // "round 1 started"
    "Waiting", // Model slot
    "ModelCallStarted",
    "ModelCallFinished",
    "Waiting", // Tool slot
    "ToolStarted",
    "ToolFinished",
    "Diagnostics", // "round 2 started"
    "Waiting",
    "ModelCallStarted",
    "ModelCallFinished",
    "Diagnostics" // "round 2 text: final answer"
  )

  private val tenSec = ZDuration.fromSeconds(10)

  /** Await a guaranteed-reachable condition; the real duration is only a
    * generous outer bound converting a (defective) hang into a failure.
    */
  private def eventually(
      max: ZDuration
  )(cond: ZIO[Any, Nothing, Boolean]): ZIO[Any, String, Unit] =
    ZIO.suspendSucceed {
      val deadline = System.nanoTime() + max.toNanos
      def loop: ZIO[Any, String, Unit] =
        cond.flatMap {
          case true => ZIO.unit
          case false =>
            if System.nanoTime() >= deadline then
              ZIO.fail("condition not met within the generous outer bound")
            else ZIO.sleep(ZDuration.fromMillis(20)) *> loop
        }
      loop
    }

  def spec = suite("AgentLoop journal wiring RAI-093")(
    test(
      "JW-01 scripted run: journal holds every emitted envelope, in emission order"
    ) {
      for
        dir <- tempDir
        journal <- JobJournal.open(dir)
        backend <- ScriptedSequenceBackend(twoRoundScript*)
        adm <- Admission.make(limits())
        hub <- EventHub.make()
        root = AgentLoop.newRoot()
        (loop, _) = loopWithJournal(
          backend,
          registryOf(echoTool),
          adm,
          root,
          hub,
          Some(journal)
        )
        answer <- loop.runText(
          List(RequestMessage("user", "task")),
          lims(),
          maxRounds = 4
        )
        // appends are awaited inline by LoopEvents: after the run returns,
        // every emitted envelope has already been journaled — no eventually
        recovery <- JobJournal.replay(dir)
      yield
        val types = recovery.replayed.map(e => tag(e.payload))
        assertTrue(
          answer == "final answer",
          // exactly the 12 emitted seams, in emission order
          types == twoRoundJournalTypes,
          // dense journal-local seqs, no gaps, no extra records
          recovery.replayed.map(_.seq) == (1L to 12L),
          // the loop itself never emits Terminal (job-layer obligation)
          !types.contains("Terminal"),
          !recovery.truncatedTail,
          recovery.uncertainMarker.isEmpty
        )
    },
    test(
      "JW-02 Terminal checkpoints the snapshot; reopen loses nothing"
    ) {
      for
        dir <- tempDir
        journal <- JobJournal.open(dir)
        backend <- ScriptedSequenceBackend(twoRoundScript*)
        adm <- Admission.make(limits())
        hub <- EventHub.make()
        root = AgentLoop.newRoot()
        (loop, le) = loopWithJournal(
          backend,
          registryOf(echoTool),
          adm,
          root,
          hub,
          Some(journal)
        )
        answer <- loop.runText(
          List(RequestMessage("user", "task")),
          lims(),
          maxRounds = 4
        )
        // job-layer-equivalent Terminal through the SAME emission context
        _ <- le.emit(EventPayload.Terminal(JobStatus.Succeeded))
        _ <- journal.close
        checkpoint <- readText(dir.resolve("checkpoint.json"))
        // re-open: a fresh journal accepts the directory that now also holds
        // the non-segment checkpoint file
        _ <- JobJournal.open(dir)
        recovery <- JobJournal.replay(dir)
      yield
        val types = recovery.replayed.map(e => tag(e.payload))
        assertTrue(
          answer == "final answer",
          types.last == "Terminal",
          // 12 run envelopes + the Terminal, dense and complete after reopen
          recovery.replayed.map(_.seq) == (1L to 13L),
          recovery.replayed.last.payload ==
            EventPayload.Terminal(JobStatus.Succeeded),
          // checkpoint carries BOTH seq spaces, named, plus the hub status:
          // 12 loop emissions + the Terminal = hub lastSeq 13
          checkpoint.contains("\"kind\":\"raider.loop.checkpoint\""),
          checkpoint.contains("\"journalLastSeq\":13"),
          checkpoint.contains("\"hubLastSeq\":13"),
          checkpoint.contains("\"hubStatus\":\"Succeeded\""),
          // replay ignores the checkpoint file (non-segment) — clean recovery
          !recovery.truncatedTail,
          recovery.uncertainMarker.isEmpty
        )
    },
    suite("JW-03 cancel/failure never breaks the loop; journal stays whole")(
      test("failing journal sink (append-after-close defect) — loop runs on") {
        for
          dir <- tempDir
          journal <- JobJournal.open(dir)
          _ <- journal.close // every append now dies by contract (defect)
          backend <- ScriptedSequenceBackend(twoRoundScript*)
          adm <- Admission.make(limits())
          hub <- EventHub.make()
          root = AgentLoop.newRoot()
          (loop, _) = loopWithJournal(
            backend,
            registryOf(echoTool),
            adm,
            root,
            hub,
            Some(journal)
          )
          answer <- loop.runText(
            List(RequestMessage("user", "task")),
            lims(),
            maxRounds = 4
          )
          recovery <- JobJournal.replay(dir)
        yield assertTrue(
          // the loop completed end-to-end despite every append dying
          answer == "final answer",
          // nothing was appended after close: the journal file is untouched
          recovery.replayed.isEmpty,
          !recovery.truncatedTail,
          recovery.uncertainMarker.isEmpty
        )
      },
      test("cancel mid-run: clean termination; journal intact (whole lines)") {
        for
          toolGate <- Promise.make[Nothing, Unit]
          started <- Ref.make(0)
          dir <- tempDir
          journal <- JobJournal.open(dir)
          backend <- ScriptedSequenceBackend(oneToolScript)
          adm <- Admission.make(limits())
          hub <- EventHub.make()
          parked = new Tool:
            def name = "lookup"; def version = 1; def description = ""
            def recovery = RecoveryClass.ReadOnly; def timeoutMs = 30000L
            def capabilities = ToolCapabilities(concurrentSafe = false)
            def invoke(args: String) =
              started.update(_ + 1) *> toolGate.await.as("\"ok\"")
          root = AgentLoop.newRoot()
          (loop, _) = loopWithJournal(
            backend,
            registryOf(parked),
            adm,
            root,
            hub,
            Some(journal)
          )
          runF <- loop
            .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
            .forkDaemon
          // deterministic: the tool is inside dispatch (after ToolStarted)
          _ <- eventually(tenSec)(started.get.map(_ == 1))
          _ <- runF.interrupt
          // await returns only after the uninterruptible onInterrupt
          // finalizer has emitted (and journaled) ToolFinished(Cancelled)
          exit <- runF.await
          _ <- journal.close
          recovery <- JobJournal.replay(dir)
        yield
          val types = recovery.replayed.map(e => tag(e.payload))
          assertTrue(
            exit.isInterrupted,
            // journal intact: every line whole, honest clean recovery
            !recovery.truncatedTail,
            recovery.uncertainMarker.isEmpty,
            // records up to the cancel, in emission order, dense seqs
            types == Vector(
              "Diagnostics",
              "Waiting",
              "ModelCallStarted",
              "ModelCallFinished",
              "Waiting",
              "ToolStarted",
              "ToolFinished"
            ),
            recovery.replayed.map(_.seq) == (1L to 7L),
            recovery.replayed.exists(
              _.payload == EventPayload
                .ToolFinished("call-1", "lookup", ToolOutcome.Cancelled)
            ),
            // no Terminal was journaled by the loop itself
            !types.contains("Terminal")
          )
      } @@ TestAspect.withLiveClock,
      test(
        "crash-mid-run: torn tail replays as an honest Uncertain, never auto-applied"
      ) {
        for
          toolGate <- Promise.make[Nothing, Unit]
          started <- Ref.make(0)
          dir <- tempDir
          journal <- JobJournal.open(dir)
          backend <- ScriptedSequenceBackend(oneToolScript)
          adm <- Admission.make(limits())
          hub <- EventHub.make()
          parked = new Tool:
            def name = "lookup"; def version = 1; def description = ""
            def recovery = RecoveryClass.ReadOnly; def timeoutMs = 30000L
            def capabilities = ToolCapabilities(concurrentSafe = false)
            def invoke(args: String) =
              started.update(_ + 1) *> toolGate.await.as("\"ok\"")
          root = AgentLoop.newRoot()
          (loop, _) = loopWithJournal(
            backend,
            registryOf(parked),
            adm,
            root,
            hub,
            Some(journal)
          )
          runF <- loop
            .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
            .forkDaemon
          _ <- eventually(tenSec)(started.get.map(_ == 1))
          // crash-mid-run: the fiber dies mid-run; the journal keeps the
          // complete pre-crash records (each append is flushed whole)
          _ <- runF.interrupt
          _ <- runF.await
          _ <- journal.close
          before <- JobJournal.replay(dir)
          // the crash remnant: a torn append line without a newline — the
          // exact on-disk shape of a kill -9 in the middle of a write
          _ <- appendBytes(
            segment0(dir),
            "{\"schemaVersion\":1,\"seq\":8,\"half"
          )
          after <- JobJournal.replay(dir)
          marker = after.uncertainMarker
        yield assertTrue(
          !before.truncatedTail,
          before.uncertainMarker.isEmpty,
          // honest Uncertain: the good prefix replays, the torn tail is
          // reported — and NOTHING from it is ever re-executed (the wiring
          // contains no replay call at all: the recovered envelopes are
          // inert data for an explicit caller policy)
          after.replayed.map(_.seq) == (1L to 7L),
          after.truncatedTail,
          marker.isDefined,
          marker.get.segment == "segment-0.jsonl",
          marker.get.lineNo == 8L,
          marker.get.reason.nonEmpty
        )
      } @@ TestAspect.withLiveClock
    )
  )

end AgentLoopJournalSpec
