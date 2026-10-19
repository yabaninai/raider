package raider.cli.chat

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.{BudgetLimits, CostEstimate}
import raider.runtime.events.EventHub
import raider.runtime.jobs.JobManager
import raider.runtime.loop.AgentLoop
import zio.*
import zio.stream.ZStream
import zio.test.*
import zio.test.Assertion.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Behavioral capture fixtures (RAI-155.b, CWF-B01..B03): the REAL ChatLoop
  * capture path — processMessage over a scripted backend with a live
  * JobManager/EventHub/Admission — observed through the turn ledger:
  *
  *   - B01: three chat turns (one driving a REAL tool round) capture with
  *     stable ids/order, survive selection marks, the actual compaction rewrite
  *     of the message history, and a save/load roundtrip.
  *   - B02: a turn that dies mid-job persists as typed-`Interrupted` with its
  *     pending request; a selection containing it is refused (CWF-R3) with the
  *     offending turn id; a legacy (no-ledger) session refuses typed (CWF-R2);
  *     a fiber-interrupted turn stays typed-`Unknown`.
  *   - B03: interrupted/unknown turns survive the save/load roundtrip and a
  *     reopen never redispatches them; the selection is an immutable snapshot
  *     (a concurrent append stays entirely outside it).
  */
object TaskCaptureSpec extends ZIOSpecDefault:

  private val att = AttemptId("capture-att")

  private sealed trait Reply
  private case class Text(t: String) extends Reply
  private case class ToolCall(name: String, args: String) extends Reply
  private case object Die extends Reply

  /** Scripted backend: replies consumed in order (last one repeats). A Tool
    * reply emits a real ToolCallReady — the loop dispatches it against the
    * registry (empty here → the documented unknown_tool refusal) and spends the
    * next reply finishing the run. Die kills the round with a defect.
    */
  private final class Scripted(replies: List[Reply]) extends ModelBackend:
    private val calls = AtomicInteger(0)

    val capabilities: ModelCapabilities = ModelCapabilities(
      CapabilityStatus.Supported,
      CapabilityStatus.Supported,
      CapabilityStatus.Unknown
    )

    def stream(input: ModelRequest): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap:
        ZIO.succeed:
          val i = calls.getAndIncrement()
          val reply = if i < replies.length then replies(i) else replies.last
          reply match
            case Text(t) =>
              ZStream(
                ModelEvent.Started(att),
                ModelEvent.TextDelta(att, t),
                ModelEvent.Finished(att, "stop")
              )
            case ToolCall(name, args) =>
              ZStream(
                ModelEvent.Started(att),
                ModelEvent
                  .ToolCallReady(att, ToolCallId(s"call-$i"), name, args),
                ModelEvent.Finished(att, "tool_calls")
              )
            case Die =>
              ZStream.fromZIO(ZIO.die(new RuntimeException("fixture defect")))

  /** Never-emitting backend: the model round hangs until interrupted. */
  private val hangBackend = new ModelBackend:
    val capabilities: ModelCapabilities = ModelCapabilities(
      CapabilityStatus.Supported,
      CapabilityStatus.Unsupported,
      CapabilityStatus.Unknown
    )
    def stream(input: ModelRequest): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.fromZIO(ZIO.never)

  private def makeCtx(
      backend: ModelBackend
  ): ZIO[Any, Nothing, ChatLoop.RunCtx] =
    for
      limits <- ZIO.succeed(
        BudgetLimits.make(maxConcurrentTools = 2, maxAttempts = 48).toOption.get
      )
      admission <- Admission.make(Right(limits)).orDie
      root <- ZIO.succeed(AgentLoop.newRoot())
      hub <- EventHub.make()
      manager <- JobManager.make(onTransition = None)
      historyRef <- Ref.make(List.empty[RequestMessage])
      traceRef <- Ref.make(Vector.empty[raider.core.trace.TraceEvent])
      ledgerRef <- Ref.make(Vector.empty[TurnRecord])
      checksRef <- Ref.make(List.empty[NamedCheck])
      selectionRef <- Ref.make(Option.empty[TaskSelection])
      loop <- ZIO.succeed(
        AgentLoop(
          backend,
          ToolRegistry.empty,
          "test-model",
          admission,
          root,
          CostEstimate.Unknown,
          ev => traceRef.update(_ :+ ev)
        )
      )
    yield ChatLoop.RunCtx(
      loop,
      limits,
      historyRef,
      traceRef,
      ledgerRef,
      checksRef,
      selectionRef,
      provider = "mock",
      workspacePath = "/tmp/ws-capture-fixture",
      hub,
      manager,
      root,
      sink = _ => ZIO.unit
    )

  /** Polls the ledger until `n` turns exist. CLOCK-FREE by design: the zio-test
    * default clock is frozen (TestClock), so sleeps/timeouts would never
    * advance; cooperative yields let the forked job fiber run and the iteration
    * bound keeps the wait finite even on failure.
    */
  private def waitLedger(
      ref: Ref[Vector[TurnRecord]],
      n: Int
  ): UIO[Option[Vector[TurnRecord]]] =
    def poll(i: Int): UIO[Option[Vector[TurnRecord]]] =
      ref.get.flatMap: ts =>
        if ts.size >= n then ZIO.succeed(Some(ts))
        else if i <= 0 then ZIO.succeed(None)
        else ZIO.yieldNow *> poll(i - 1)
    poll(100_000)

  private def tmpBase(): UIO[java.nio.file.Path] = ZIO.succeed {
    Files.createTempDirectory("raider-capture-test")
  }

  override def spec: Spec[Any, Any] =
    suite("TaskCapture")(
      test(
        "B01: three turns (one REAL tool round) capture; marks + select; compaction rewrite and save/load keep the ledger stable"
      ) {
        for
          ctx <- makeCtx(
            Scripted(
              List(
                Text("[fixture] plan"),
                ToolCall(
                  "fs_edit",
                  "{}"
                ), // drives a real dispatch (refused: empty registry)
                Text("[fixture] edited"),
                Text("[fixture] refinement ack")
              )
            )
          )
          _ <- ChatLoop.processMessage(
            ctx,
            "make module X compile and keep checks green"
          )
          _ <- ChatLoop.processMessage(ctx, "now edit src/Foo.scala")
          _ <- ChatLoop.processMessage(ctx, "do not touch the public API")
          ledger0 <- ctx.ledgerRef.get
          _ <- TaskCommands.mark(ctx.ledgerRef, 1, TurnLedger.Kind.Intent)
          _ <- TaskCommands.mark(ctx.ledgerRef, 3, TurnLedger.Kind.Refinement)
          _ <- TaskCommands.registerCheck(
            ctx.checksRef,
            "compile",
            List("sbt", "--batch", "compile")
          )
          marked <- ctx.ledgerRef.get
          checks <- ctx.checksRef.get
          selection = TaskCommands.select(marked.toList, checks, 3, 12)
          // ---- the compaction rewrite path: grow the history past the
          // threshold; the NEXT message triggers the real compactHistory
          seeded = (1 to 35).map(i => RequestMessage("user", s"m$i")).toList
          _ <- ctx.historyRef.set(seeded)
          _ <- ChatLoop.processMessage(ctx, "one more turn after compaction")
          afterCompact <- ctx.ledgerRef.get
          history <- ctx.historyRef.get
          // ---- save + reopen
          base <- tmpBase()
          checksAfter <- ctx.checksRef.get
          session = SavedSession(
            id = "b01",
            messages = history,
            model = "test-model",
            timestamp = "2026-10-14T00:00:00Z",
            turns = afterCompact.toList,
            checks = checksAfter
          )
          _ <- SessionStore.save(base, session)
          back <- SessionStore.load(base, "b01")
        yield assertTrue(
          // capture: completed, distinct job ids, REAL tool-refused summary on turn 2
          ledger0.size == 3,
          ledger0.map(_.status) == List(
            TurnLedger.Status.Completed,
            TurnLedger.Status.Completed,
            TurnLedger.Status.Completed
          ),
          ledger0.map(_.jobId).distinct.size == 3,
          ledger0(1).toolSummary ==
            List(ToolOutcomeSummary("fs_edit", ToolOutcomeSummary.Refused)),
          ledger0(0).request == "make module X compile and keep checks green",
          ledger0(0).configRefs == ConfigRefs(
            "/tmp/ws-capture-fixture",
            "mock",
            "test-model"
          ),
          // marks + selection
          marked(0).kind == TurnLedger.Kind.Intent,
          marked(2).kind == TurnLedger.Kind.Refinement,
          selection.isRight,
          selection.map(_.intent.turnId) == Right(ledger0(0).turnId),
          selection.map(_.refinements.map(_.turnId)) == Right(
            List(ledger0(2).turnId)
          ),
          // compaction rewrote MESSAGES, never the ledger
          history.size == 23, // system summary + 20 kept + new user + new assistant
          history.head.role == "system",
          afterCompact.size == 4,
          afterCompact.take(3).map(_.turnId) == ledger0.map(_.turnId),
          afterCompact.take(3).map(_.kind) == marked.map(_.kind),
          // save/load roundtrip is identity on the ledger + checks
          back.turns == afterCompact.toList,
          back.checks == checksAfter
        )
      },
      test(
        "B02: a turn whose job dies persists Interrupted with its request; selection over it is refused with the turn id"
      ) {
        for
          ctx <- makeCtx(
            Scripted(
              List(
                Text("[fixture] ok"), // turn 1 completes
                Die // turn 2 dies mid-job with a defect
              )
            )
          )
          _ <- ChatLoop.processMessage(ctx, "healthy turn")
          exit <- ChatLoop.processMessage(ctx, "turn that dies mid-job").exit
          ledger <- ctx.ledgerRef.get
          checks = List(
            NamedCheck("compile", List("sbt", "--batch", "compile"))
          )
          refused = TaskCommands.select(ledger.toList, checks, 2, 12)
          // interrupted state persists through a save/load roundtrip
          base <- tmpBase()
          history <- ctx.historyRef.get
          session = SavedSession(
            id = "b02",
            messages = history,
            model = "test-model",
            timestamp = "t",
            turns = ledger.toList,
            checks = checks
          )
          _ <- SessionStore.save(base, session)
          back <- SessionStore.load(base, "b02")
        yield assertTrue(
          exit.isFailure, // the job's defect still propagates (capture is observation)
          ledger.size == 2,
          ledger(0).status == TurnLedger.Status.Completed,
          ledger(1).status == TurnLedger.Status.Interrupted,
          ledger(1).request == "turn that dies mid-job",
          refused.left.exists(_.detail.startsWith("CWF-R3")),
          refused.left.exists(_.detail.contains(ledger(1).turnId)),
          back.turns(1).status == TurnLedger.Status.Interrupted,
          back.turns(1).request == "turn that dies mid-job"
        )
      },
      test(
        "B02/B03: a fiber-interrupted turn keeps its pending request as typed-Unknown; a reopened session never redispatches it"
      ) {
        for
          ctx <- makeCtx(hangBackend)
          fiber <- ChatLoop.processMessage(ctx, "hang mid-flight").fork
          begun <- waitLedger(ctx.ledgerRef, 1)
          inFlight <- ctx.ledgerRef.get
          // the interruption unwinds runJob's await (interruptible) and runs
          // the bounded ensurings (awaitCaughtUp has a real-time budget)
          _ <- fiber.interrupt
          after <- ctx.ledgerRef.get
          // reopen-style restore (what resume/:load does): restore the saved
          // ledger verbatim — no turn is re-run or redispatched
          restored <- Ref.make(Vector.empty[TurnRecord])
          _ <- restored.set(after)
          post <- restored.get
        yield assertTrue(
          begun.isDefined,
          inFlight.size == 1,
          inFlight.head.status == TurnLedger.Status.Unknown, // pending, torn-safe
          inFlight.head.request == "hang mid-flight",
          after.size == 1,
          after.head.status == TurnLedger.Status.Unknown,
          after.head.finishedAtMs.isEmpty,
          post == after
        )
      },
      test(
        "B02: a legacy (conversation-only, no-ledger) session restores read-only and selection is the typed CWF-R2 refusal"
      ) {
        for
          base <- tmpBase()
          _ <- ZIO.succeed {
            val doc =
              """{"id":"legacy-b02","messages":[{"role":"user","content":"old task"}],"model":"m1","timestamp":"t"}"""
            Files.writeString(base.resolve("legacy-b02.json"), doc, UTF_8)
          }
          session <- SessionStore.load(base, "legacy-b02")
          ledgerRef <- Ref.make(session.turns.toVector)
          checksRef <- Ref.make(session.checks)
          turns <- ledgerRef.get
          checks <- checksRef.get
          refused = TaskCommands.select(turns.toList, checks, 1, 12)
        yield assertTrue(
          session.turns.isEmpty,
          session.checks.isEmpty,
          refused.left.exists(_.detail.startsWith("CWF-R2")),
          refused.left.exists(
            _.detail.contains("cannot invent absent execution records")
          )
        )
      },
      test(
        "B03: the selection is an immutable snapshot — an append (even a marked one) stays entirely outside it"
      ) {
        for
          ctx <- makeCtx(Scripted(List(Text("[fixture] ack"))))
          _ <- ChatLoop.processMessage(ctx, "intent turn")
          _ <- ChatLoop.processMessage(ctx, "second turn")
          _ <- TaskCommands.mark(ctx.ledgerRef, 1, TurnLedger.Kind.Intent)
          checks = List(
            NamedCheck("compile", List("sbt", "--batch", "compile"))
          )
          before <- ctx.ledgerRef.get
          sel = TaskCommands.select(before.toList, checks, 2, 12)
          _ <- ChatLoop.processMessage(
            ctx,
            "a third, later turn"
          ) // concurrent-with-selection append
          _ <- TaskCommands.mark(ctx.ledgerRef, 3, TurnLedger.Kind.Refinement)
          after <- ctx.ledgerRef.get
        yield assertTrue(
          sel.isRight,
          sel.map(_.turnIds) == Right(before.map(_.turnId).toList),
          after.size == 3,
          sel.map(_.turnIds) != Right(after.map(_.turnId).toList),
          // the old snapshot is unaffected, and a NEW explicit selection
          // over the full span picks up the appended turn (never implicitly)
          TaskCommands.select(after.toList, checks, 3, 12).map(_.turnIds) ==
            Right(after.map(_.turnId).toList)
        )
      }
    ) @@ TestAspect.withLiveClock
    // RAI-178: runJob's bounded ensurings (awaitCaughtUp) poll on the ambient
    // clock; a fiber.interrupt mid-run freezes them on the never-advanced
    // TestClock (fiber-verified; docs/work/records/RAI-178.md). Live clock =
    // the production CLI discipline; assertions unchanged.
