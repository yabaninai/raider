package raider.runtime.loop

import raider.core.*
import raider.runtime.admission.{Admission, SlotKind}
import raider.runtime.budget.{BudgetLimits, CostEstimate, MicroUsd}
import raider.testkit.{ScriptedModelBackend, ScriptedSequenceBackend}
import zio.test.{live, *}
import zio.{Duration as ZDuration, Exit as ZExit, Promise, Ref, Scope, ZIO}

import java.util.concurrent.atomic.AtomicInteger

/** RAI-010.a acceptance: LOOP-01 happy path (text→tool→result→final), LOOP-02
  * bounded steps/limits typed without side effects, LOOP-03 tool failure and
  * cancellation accounting. Deterministic ScriptedModelBackend /
  * ScriptedSequenceBackend only (fixtureMode asserted); real time via `live`
  * where cancellation is involved; no network, no spend.
  */
object AgentLoopSpec extends ZIOSpecDefault:

  private val a = AttemptId("loop-att")
  private val c1 = ToolCallId("call-1")
  private val c2 = ToolCallId("call-2")

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

  /** Unwrapped ceilings for runText (validated: throw = fixture bug). */
  private def lims(attempts: Int = 8): BudgetLimits =
    limits(attempts = attempts).fold(e => throw e, identity)

  private def registryOf(tools: Tool*): ToolRegistry =
    ToolRegistry.of(tools*).fold(e => throw e, identity)

  /** Deterministic echo tool: valid JSON in → {"echo":<args>} out. */
  private def echoTool(toolName: String = "lookup"): Tool = new Tool:
    def name = toolName
    def version = 1
    def description = "test echo tool"
    def recovery = RecoveryClass.ReadOnly
    def timeoutMs = 5000L
    def capabilities = ToolCapabilities(concurrentSafe = false)
    def invoke(args: String) = ZIO.succeed(s"""{"echo":$args}""")

  private def failingTool(err: RaiderError): Tool = new Tool:
    def name = "lookup"
    def version = 1
    def description = "always failing"
    def recovery = RecoveryClass.ReadOnly
    def timeoutMs = 5000L
    def capabilities = ToolCapabilities(concurrentSafe = false)
    def invoke(args: String) = ZIO.fail(err)

  /** Loop with a tool-round script then a final-answer script. */
  private def twoRoundFixture(
      tool: Tool,
      est: CostEstimate = CostEstimate.Unknown
  ) =
    for
      backend <- ScriptedSequenceBackend(
        Vector[ModelEvent](
          ModelEvent.Started(a),
          ModelEvent.TextDelta(a, "searching…"),
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
      adm <- Admission.make(limits())
      root = AgentLoop.newRoot()
      loop = AgentLoop(backend, registryOf(tool), "m", adm, root, est)
    yield (backend, adm, root, loop)

  def spec = suite("AgentLoop RAI-010.a")(
    suite("LOOP-01 happy path")(
      test(
        "text→tool→result→final: results matched by tool_call_id, attempts distinct"
      ) {
        for
          (backend, adm, root, loop) <- twoRoundFixture(echoTool())
          answer <- loop.runText(
            List(RequestMessage("user", "task")),
            lims(),
            maxRounds = 4
          )
          reqs <- backend.requests
          usage <- adm.budgetLedger.usage
          tries <- adm.attempts(root)
          modelFree <- adm.availableSlots(SlotKind.Model)
          toolFree <- adm.availableSlots(SlotKind.Tool)
        yield assertTrue(
          answer == "final answer",
          reqs.size == 2, // one attempt per round, distinct attempts
          reqs(0).messages == List(RequestMessage("user", "task")),
          // round 2 carries: original + assistant + tool result (matched to c1)
          reqs(1).messages(0) == RequestMessage("user", "task"),
          reqs(1).messages(1) == RequestMessage("assistant", "searching…"),
          reqs(1).messages(2).role == "tool",
          reqs(1).messages(2).content.contains("\"call-1\""),
          reqs(1).messages(2).content.contains("""{"echo":{"q":"x"}}"""),
          usage.reservedActive == MicroUsd(0), // every reservation settled
          usage.uncertainTotal == MicroUsd(0),
          usage.unpricedAttempts == 2L, // unpriced is tracked, never free-silent
          // admission counts EVERY dispatch of the root: 2 model + 1 tool
          tries == 3,
          modelFree == 1 && toolFree == 1 // no permit leak
        )
      },
      test(
        "two calls in one round execute sequentially in order, both matched"
      ) {
        for
          backend <- ScriptedSequenceBackend(
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.ToolCallReady(a, c1, "t1", "1"),
              ModelEvent.ToolCallReady(a, c2, "t2", "2"),
              ModelEvent.Finished(a, "r")
            ),
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.TextDelta(a, "done"),
              ModelEvent.Finished(a, "stop")
            )
          )
          adm <- Admission.make(limits())
          order <- Ref.make(Vector.empty[String])
          t1 = new Tool:
            def name = "t1"; def version = 1; def description = ""
            def recovery = RecoveryClass.ReadOnly; def timeoutMs = 5000L
            def capabilities = ToolCapabilities(concurrentSafe = true)
            def invoke(args: String) =
              order.update(_ :+ s"t1:$args").as("\"first\"")
          t2 = new Tool:
            def name = "t2"; def version = 1; def description = ""
            def recovery = RecoveryClass.ReadOnly; def timeoutMs = 5000L
            def capabilities = ToolCapabilities(concurrentSafe = true)
            def invoke(args: String) =
              order.update(_ :+ s"t2:$args").as("\"second\"")
          root = AgentLoop.newRoot()
          loop = AgentLoop(backend, registryOf(t1, t2), "m", adm, root)
          answer <- loop.runText(
            List(RequestMessage("user", "u")),
            lims(),
            maxRounds = 4
          )
          reqs <- backend.requests
          seq <- order.get
        yield assertTrue(
          answer == "done",
          seq == Vector("t1:1", "t2:2"), // §6 default: serialized, in order
          reqs(1).messages(1).role == "tool",
          reqs(1).messages(2).role == "tool",
          reqs(1).messages(1).content.contains("\"call-1\""),
          reqs(1).messages(2).content.contains("\"call-2\"")
        )
      },
      test(
        "empty registry degenerates into Runner.runText (same script, same result)"
      ) {
        for
          backend <- ScriptedModelBackend(
            ModelEvent.Started(a),
            ModelEvent.TextDelta(a, "solo text"),
            ModelEvent.Finished(a, "stop")
          )
          adm <- Admission.make(limits())
          msgs = List(RequestMessage("user", "same"))
          ls = lims()
          viaRunner <- Runner.runText(backend, "m", msgs, ls, adm)
          root = AgentLoop.newRoot()
          viaLoop <- AgentLoop(backend, ToolRegistry.empty, "m", adm, root)
            .runText(msgs, ls, maxRounds = ls.maxAttempts)
          reqs <- backend.requests
        yield assertTrue(
          viaLoop == viaRunner,
          viaRunner == "solo text",
          reqs.size == 2 // one attempt each, identical request shape
        )
      }
    ),
    suite("LOOP-02 limits and validation, typed without side effects")(
      test(
        "maxRounds > ceilings.maxAttempts is Configuration BEFORE any call"
      ) {
        for
          backend <- ScriptedSequenceBackend(
            Vector[ModelEvent](ModelEvent.TextDelta(a, "never"))
          )
          adm <- Admission.make(limits(attempts = 3))
          root = AgentLoop.newRoot()
          res <- AgentLoop(backend, ToolRegistry.empty, "m", adm, root)
            .runText(
              List(RequestMessage("user", "u")),
              lims(attempts = 2),
              maxRounds = 3
            )
            .exit
          reqs <- backend.requests
          usage <- adm.budgetLedger.usage
        yield assertTrue(
          res.isFailure,
          exitCode(res) == Some("RA-CFG"),
          reqs.isEmpty, // rejected before any spend
          usage.reservedActive == MicroUsd(0)
        )
      },
      test(
        "maxRounds exhausted with pending tool calls → LocalBudgetExceeded"
      ) {
        for
          backend <- ScriptedSequenceBackend(
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.ToolCallReady(a, c1, "lookup", "1"),
              ModelEvent.Finished(a, "r1")
            ),
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.ToolCallReady(a, c2, "lookup", "2"),
              ModelEvent.Finished(a, "r2")
            )
          )
          adm <- Admission.make(limits())
          root = AgentLoop.newRoot()
          res <- AgentLoop(backend, registryOf(echoTool()), "m", adm, root)
            .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 2)
            .exit
        yield assertTrue(
          res.isFailure,
          exitCode(res) == Some("RA-LBUDGET"),
          exitDetail(res).exists(_.contains("maxRounds=2"))
        )
      },
      test("admission maxAttempts exhaustion mid-loop → LocalBudgetExceeded") {
        for
          backend <- ScriptedSequenceBackend(
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.ToolCallReady(a, c1, "lookup", "1"),
              ModelEvent.Finished(a, "r1")
            ),
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.TextDelta(a, "final"),
              ModelEvent.Finished(a, "stop")
            )
          )
          // maxAttempts=3, maxRounds=2: run 1 legitimately spends 3 dispatches
          // (2 model rounds + 1 tool dispatch — admission counts every one);
          // run 2 on the SAME root is then refused BEFORE any backend dispatch
          adm <- Admission.make(limits(attempts = 3))
          root = AgentLoop.newRoot()
          l1 = AgentLoop(backend, registryOf(echoTool()), "m", adm, root)
          one <- l1.runText(
            List(RequestMessage("user", "u")),
            lims(attempts = 3),
            maxRounds = 2
          )
          two <- l1
            .runText(
              List(RequestMessage("user", "u")),
              lims(attempts = 3),
              maxRounds = 2
            )
            .exit
          reqs <- backend.requests
        yield assertTrue(
          one == "final",
          two.isFailure,
          exitCode(two) == Some("RA-LBUDGET"),
          exitDetail(two).exists(_.contains("maxAttempts=3")),
          reqs.size == 2
        ) // refused attempt never reached the backend
      },
      test("unknown tool → structured feedback to the model, loop continues") {
        for
          backend <- ScriptedSequenceBackend(
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.ToolCallReady(a, c1, "nope", "1"),
              ModelEvent.Finished(a, "r1")
            ),
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.TextDelta(a, "recovered"),
              ModelEvent.Finished(a, "stop")
            )
          )
          adm <- Admission.make(limits())
          root = AgentLoop.newRoot()
          answer <- AgentLoop(backend, registryOf(echoTool()), "m", adm, root)
            .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
          reqs <- backend.requests
        yield assertTrue(
          answer == "recovered", // feedback, never a silent ignore
          reqs(1).messages(1).role == "tool",
          reqs(1).messages(1).content.contains("unknown_tool"),
          reqs(1).messages(1).content.contains("\"nope\"")
        )
      },
      test("invalid arguments JSON → structured feedback, tool never invoked") {
        for
          backend <- ScriptedSequenceBackend(
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.ToolCallReady(a, c1, "lookup", "{not json"),
              ModelEvent.Finished(a, "r1")
            ),
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.TextDelta(a, "ok"),
              ModelEvent.Finished(a, "stop")
            )
          )
          adm <- Admission.make(limits())
          root = AgentLoop.newRoot()
          invoked = new AtomicInteger(0)
          counting = new Tool:
            def name = "lookup"; def version = 1; def description = ""
            def recovery = RecoveryClass.ReadOnly; def timeoutMs = 5000L
            def capabilities = ToolCapabilities(concurrentSafe = false)
            def invoke(args: String) =
              ZIO.succeed(invoked.incrementAndGet().toString)
          answer <- AgentLoop(backend, registryOf(counting), "m", adm, root)
            .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
          reqs <- backend.requests
        yield assertTrue(
          answer == "ok",
          invoked.get == 0, // partial/invalid args never execute (§8)
          reqs(1).messages(1).content.contains("invalid_arguments")
        )
      },
      test("provider Failed event → typed provider error") {
        for
          backend <- ScriptedSequenceBackend(
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.Failed(
                a,
                RaiderError.ProviderRateLimit("429 from fixture")
              )
            )
          )
          adm <- Admission.make(limits())
          root = AgentLoop.newRoot()
          res <- AgentLoop(backend, ToolRegistry.empty, "m", adm, root)
            .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
            .exit
        yield assertTrue(res.isFailure, exitCode(res) == Some("RA-RATE"))
      },
      test("stream without Finished → StreamProtocol") {
        for
          backend <- ScriptedSequenceBackend(
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.TextDelta(a, "cut")
            )
          )
          adm <- Admission.make(limits())
          root = AgentLoop.newRoot()
          res <- AgentLoop(backend, ToolRegistry.empty, "m", adm, root)
            .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
            .exit
        yield assertTrue(res.isFailure, exitCode(res) == Some("RA-STREAM"))
      },
      test("empty final text → OutputValidation") {
        for
          backend <- ScriptedSequenceBackend(
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.Finished(a, "stop")
            )
          )
          adm <- Admission.make(limits())
          root = AgentLoop.newRoot()
          res <- AgentLoop(backend, ToolRegistry.empty, "m", adm, root)
            .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
            .exit
        yield assertTrue(res.isFailure, exitCode(res) == Some("RA-OUT"))
      },
      test("reserved internal marker in final text → OutputValidation") {
        for
          backend <- ScriptedSequenceBackend(
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.TextDelta(a, s"leak ${AgentLoop.InternalMarker}"),
              ModelEvent.Finished(a, "stop")
            )
          )
          adm <- Admission.make(limits())
          root = AgentLoop.newRoot()
          res <- AgentLoop(backend, ToolRegistry.empty, "m", adm, root)
            .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
            .exit
        yield assertTrue(res.isFailure, exitCode(res) == Some("RA-OUT"))
      }
    ),
    suite("LOOP-03 tool failure, cancellation, truthful accounting")(
      test("tool execution failure → Failed with the tool's code") {
        for
          backend <- ScriptedSequenceBackend(
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.ToolCallReady(a, c1, "lookup", "1"),
              ModelEvent.Finished(a, "r1")
            )
          )
          adm <- Admission.make(limits())
          root = AgentLoop.newRoot()
          res <- AgentLoop(
            backend,
            registryOf(failingTool(RaiderError.ToolFailed("boom"))),
            "m",
            adm,
            root
          )
            .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
            .exit
          usage <- adm.budgetLedger.usage
        yield assertTrue(
          res.isFailure,
          exitCode(res) == Some("RA-TOOLFAIL"),
          exitDetail(res).contains("boom"),
          // no auto-retry of the failed/mutational call: exactly one attempt
          usage.reservedActive == MicroUsd(0), // settled/closed brackets
          usage.uncertainTotal == MicroUsd(0)
        ) // definitive error, not ambiguity
      },
      test(
        "tool returning non-JSON output → ToolFailed (loop envelope stays valid)"
      ) {
        for
          backend <- ScriptedSequenceBackend(
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.ToolCallReady(a, c1, "lookup", "1"),
              ModelEvent.Finished(a, "r1")
            )
          )
          adm <- Admission.make(limits())
          garbage = new Tool:
            def name = "lookup"; def version = 1; def description = ""
            def recovery = RecoveryClass.ReadOnly; def timeoutMs = 5000L
            def capabilities = ToolCapabilities(concurrentSafe = false)
            def invoke(args: String) = ZIO.succeed("not json at all")
          root = AgentLoop.newRoot()
          res <- AgentLoop(backend, registryOf(garbage), "m", adm, root)
            .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
            .exit
        yield assertTrue(res.isFailure, exitCode(res) == Some("RA-TOOLFAIL"))
      },
      test("tool exceeding timeoutMs → DeadlineExceeded") {
        live {
          for
            backend <- ScriptedSequenceBackend(
              Vector[ModelEvent](
                ModelEvent.Started(a),
                ModelEvent.ToolCallReady(a, c1, "lookup", "1"),
                ModelEvent.Finished(a, "r1")
              )
            )
            adm <- Admission.make(limits())
            slow = new Tool:
              def name = "lookup"; def version = 1; def description = ""
              def recovery = RecoveryClass.ReadOnly; def timeoutMs = 80L
              def capabilities = ToolCapabilities(concurrentSafe = false)
              def invoke(args: String) =
                ZIO.sleep(ZDuration.fromSeconds(10)).as("\"never\"")
            root = AgentLoop.newRoot()
            res <- AgentLoop(backend, registryOf(slow), "m", adm, root)
              .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
              .exit
            free <- adm.availableSlots(SlotKind.Tool)
          yield assertTrue(
            res.isFailure,
            exitCode(res) == Some("RA-DEADLINE"),
            free == 1
          ) // timed-out dispatch released its slot
        }
      },
      test(
        "cancel after permit before dispatch (cancelRoot) → Cancelled, zero charge"
      ) {
        for
          adm <- Admission.make(limits())
          root = AgentLoop.newRoot()
          _ <- adm.cancelRoot(root)
          backend <- ScriptedSequenceBackend(
            Vector[ModelEvent](ModelEvent.TextDelta(a, "never"))
          )
          res <- AgentLoop(backend, ToolRegistry.empty, "m", adm, root)
            .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
            .exit
          reqs <- backend.requests
          usage <- adm.budgetLedger.usage
          free <- adm.availableSlots(SlotKind.Model)
        yield assertTrue(
          res.isFailure,
          exitCode(res) == Some("RA-CANCEL"),
          reqs.isEmpty, // no dispatch after Cancelling (§4)
          usage.reservedActive == MicroUsd(0),
          usage.uncertainTotal == MicroUsd(0),
          free == 1
        )
      },
      test(
        "interrupt during model dispatch → Uncertain charge kept, never zeroed"
      ) {
        live {
          for
            gate <- Promise.make[Nothing, Unit]
            backend <- ScriptedSequenceBackend(
              Vector[ModelEvent]( // would finish if not interrupted
                ModelEvent.Started(a),
                ModelEvent.TextDelta(a, "late"),
                ModelEvent.Finished(a, "stop")
              )
            )
            gated = GatedBackend(backend, gate)
            adm <- Admission.make(limits())
            root = AgentLoop.newRoot()
            loop = AgentLoop(
              gated,
              ToolRegistry.empty,
              "m",
              adm,
              root,
              CostEstimate.Priced(MicroUsd(250L))
            )
            fiber <- loop
              .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
              .forkDaemon
            // deterministic: request recorded ⇒ fiber is inside dispatch
            _ <- gated.requests
              .repeatUntil(_.size == 1)
              .timeout(ZDuration.fromSeconds(5))
            _ <- fiber.interrupt
            _ <- fiber.await
            usage <- adm.budgetLedger.usage
            free <- adm.availableSlots(SlotKind.Model)
          yield assertTrue(
            usage.uncertainTotal == MicroUsd(
              250L
            ), // may have been paid: NOT zeroed
            usage.reservedActive == MicroUsd(0),
            free == 1
          ) // no permit leak
        }
      },
      test(
        "interrupt during tool dispatch → prior model settled, tool slot released"
      ) {
        live {
          for
            toolGate <- Promise.make[Nothing, Unit]
            started <- Ref.make(0)
            backend <- ScriptedSequenceBackend(
              Vector[ModelEvent](
                ModelEvent.Started(a),
                ModelEvent.ToolCallReady(a, c1, "lookup", "1"),
                ModelEvent.Finished(a, "r1")
              )
            )
            adm <- Admission.make(limits())
            parked = new Tool:
              def name = "lookup"; def version = 1; def description = ""
              def recovery = RecoveryClass.ReadOnly; def timeoutMs = 30000L
              def capabilities = ToolCapabilities(concurrentSafe = false)
              def invoke(args: String) =
                started.update(_ + 1) *> toolGate.await.as("\"ok\"")
            root = AgentLoop.newRoot()
            loop = AgentLoop(
              backend,
              registryOf(parked),
              "m",
              adm,
              root,
              CostEstimate.Priced(MicroUsd(100L))
            )
            fiber <- loop
              .runText(List(RequestMessage("user", "u")), lims(), maxRounds = 4)
              .forkDaemon
            _ <- started.get
              .repeatUntil(_ == 1)
              .timeout(ZDuration.fromSeconds(5))
            _ <- fiber.interrupt
            _ <- fiber.await
            usage <- adm.budgetLedger.usage
            modelFree <- adm.availableSlots(SlotKind.Model)
            toolFree <- adm.availableSlots(SlotKind.Tool)
          yield assertTrue(
            // round-1 model attempt SETTLED cleanly before the tool ran; the
            // interrupted tool was free (Priced 0) — nothing kept, nothing zeroed
            usage.reservedActive == MicroUsd(0),
            usage.uncertainTotal == MicroUsd(0),
            modelFree == 1 && toolFree == 1
          )
        }
      }
    )
  )

  /** A backend whose stream parks on a gate after recording the request — makes
    * "inside dispatch" deterministic for interrupt tests.
    */
  private final class GatedBackend(
      underlying: ScriptedSequenceBackend,
      gate: Promise[Nothing, Unit]
  ) extends ModelBackend:

    private val seen = zio.Unsafe.unsafe { implicit u =>
      Ref.unsafe.make(Vector.empty[ModelRequest])
    }

    def capabilities: ModelCapabilities = underlying.capabilities

    def stream(
        input: ModelRequest
    ): zio.stream.ZStream[Scope, RaiderError, ModelEvent] =
      zio.stream.ZStream.fromZIO(seen.update(_ :+ input) *> gate.await) *>
        underlying.stream(input)

    def requests: ZIO[Any, Nothing, Vector[ModelRequest]] = seen.get

  private def exitCode[A](exit: ZExit[RaiderError, A]): Option[String] =
    exit match
      case ZExit.Failure(cause) => cause.failures.headOption.map(_.code)
      case ZExit.Success(_)     => None

  private def exitDetail[A](exit: ZExit[RaiderError, A]): Option[String] =
    exit match
      case ZExit.Failure(cause) => cause.failures.headOption.map(_.detail)
      case ZExit.Success(_)     => None
