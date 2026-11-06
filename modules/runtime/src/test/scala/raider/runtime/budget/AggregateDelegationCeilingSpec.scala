package raider.runtime.budget

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.AggregateRunCeiling.CeilingTrip
import raider.runtime.delegate.DelegationToolset
import raider.runtime.jobs.{JobManager, JobStatus}
import raider.runtime.loop.AgentLoop
import zio.stream.ZStream
import zio.test.{TestAspect, *}
import zio.{Duration as ZDuration, Promise, Scope, ZIO}

import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

/** RAI-258 acceptance, JOINT root+child level (the component unit level is
  * [[AggregateRunCeilingSpec]]): the aggregate ceiling binds the parent loop
  * and every delegated child of ONE run. The backend is a DETERMINISTIC
  * content-routed turn simulator (RAI-092 STAB-01 — routes by message
  * content/roles/tool_call_id, never by call index, because parent and child
  * dispatch race for the model slots under load).
  *
  * Card cases covered:
  *   - two-sided token exhaustion: the child's spend trips the aggregate
  *     ceiling mid-run, the parent's next dispatch is denied typed
  *     (RA-PBUDGET), the toolset records a terminal cancellation outcome for
  *     the child, and accounting is EXACT (child usage counted once, never
  *     double-counted into the root entry, never lost).
  *   - aggregate deadline expiry: a parked (mid-dispatch) child is cancelled
  *     out-of-band, classified, and the parent's next dispatch is denied typed
  *     (RA-DEADLINE).
  *   - interleaved reservations: concurrent children plus the parent race their
  *     admissions, yet exactly the capped number of dispatches is granted — the
  *     surplus is refused typed, and every spawned child ends with a recorded
  *     terminal cancellation outcome.
  */
object AggregateDelegationCeilingSpec extends ZIOSpecDefault:

  private val a = AttemptId("agg-dlg-att")

  /** Bounded readiness poll (200 × 50ms = 10s): a lost signal surfaces as a
    * FALSE assertion below, never as an infinite suite hang under load.
    */
  private def poll(
      check: ZIO[Any, Nothing, Boolean],
      tries: Int = 200
  ): ZIO[Any, Nothing, Boolean] =
    if tries <= 0 then ZIO.succeed(false)
    else
      check.flatMap: ok =>
        if ok then ZIO.succeed(true)
        else ZIO.sleep(ZDuration.fromMillis(50)) *> poll(check, tries - 1)

  /** Content-routed turn backend (STAB-01). */
  private final class TurnBackend(route: ModelRequest => Vector[ModelEvent])
      extends ModelBackend:
    private val recorded = new CopyOnWriteArrayList[ModelRequest]()

    def capabilities: ModelCapabilities = ModelCapabilities(
      CapabilityStatus.Supported,
      CapabilityStatus.Supported,
      CapabilityStatus.Unknown
    )

    def requests: List[ModelRequest] = recorded.asScala.toList

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap:
        ZIO.succeed:
          recorded.add(input)
          ZStream(ModelEvent.Started(a)) ++
            ZStream.fromIterable(route(input))

  /** Extract the child_id out of the recorded delegate/await feedback. */
  private def childIdOf(req: ModelRequest): String =
    val content = req.messages.collect { case RequestMessage("tool", c) =>
      c
    }.mkString
    val r = """"child_id":"(j_\d+)"""".r
    r.findFirstMatchIn(content).map(_.group(1)).getOrElse("j_missing")

  private def lims(
      llm: Int = 2,
      tools: Int = 4,
      attempts: Int = 16,
      children: Int = 4
  ): BudgetLimits =
    BudgetLimits
      .make(
        maxConcurrentLlm = llm,
        maxConcurrentTools = tools,
        maxAttempts = attempts,
        maxChildren = children
      )
      .fold(e => throw e, identity)

  private def terminal(s: JobStatus): Boolean = s match
    case _: JobStatus.Terminal => true
    case _                     => false

  def spec = suite("aggregate ceilings across root and children (RAI-258)")(
    test(
      "joint token ceiling: child spend trips it mid-run; parent denied typed; exact accounting, terminal child outcome"
    ) {
      val parentDelegate = Vector(
        ModelEvent.ToolCallReady(
          a,
          ToolCallId("call-1"),
          "delegate",
          """{"agent":"scout","input":"dig"}"""
        ),
        ModelEvent.Finished(a, "r")
      )
      val childDig = Vector(
        ModelEvent.TextDelta(a, "digging"),
        // 40 + 25 = 65 > the 60-token ceiling, observed mid-child-stream
        ModelEvent.UsageObserved(a, Usage(40L, 25L, None), false),
        ModelEvent.Finished(a, "stop")
      )
      val parentAwait = (req: ModelRequest) =>
        Vector(
          ModelEvent.ToolCallReady(
            a,
            ToolCallId("call-2"),
            "await_agent",
            s"""{"child_id":"${childIdOf(req)}"}"""
          ),
          ModelEvent.Finished(a, "r")
        )
      val parentFinal = Vector(
        ModelEvent.TextDelta(a, "never reached"),
        ModelEvent.Finished(a, "stop")
      )
      val route = (input: ModelRequest) =>
        val single = input.messages.size == 1
        val head = input.messages.headOption.map(_.content).getOrElse("")
        val delegateDone = input.messages.exists(m =>
          m.role == "tool" && m.content.contains("call-1")
        )
        val awaitDone = input.messages.exists(m =>
          m.role == "tool" && m.content.contains("call-2")
        )
        if single && head == "u" then parentDelegate
        else if single && head == "dig" then childDig
        else if awaitDone then parentFinal
        else parentAwait(input)
      val ceiling = AggregateRunCeiling
        .make(None, Some(60L), None)
        .fold(e => throw e, identity)
      for
        jobs <- JobManager.make()
        adm <- Admission.make(Right(lims()))
        backend = new TurnBackend(route)
        root = AgentLoop.newRoot()
        toolset <- ZIO.fromEither(
          DelegationToolset.make(
            jobs,
            backend,
            adm,
            lims(),
            ToolRegistry.empty,
            Set("scout"),
            root,
            aggregate = Some(ceiling)
          )
        )
        registry = toolset
          .augment(ToolRegistry.empty)
          .fold(e => throw e, identity)
        // the ROOT loop dispatches under the same ceiling (actor "root")
        loop = AgentLoop(
          ceiling.wired(backend, "root"),
          registry,
          "parent",
          adm,
          root,
          CostEstimate.Unknown
        )
        outcome <- loop
          .runText(List(RequestMessage("user", "u")), lims(), 8)
          .either
          .timeout(ZDuration.fromSeconds(30))
        childId = toolset.childIds.headOption
        snap <- ceiling.snapshot
        recordedOutcome <- childId match
          case Some(id) =>
            poll(toolset.ceilingCancellationOutcome(id).map(_.isDefined)) *>
              toolset.ceilingCancellationOutcome(id)
          case None => ZIO.none
        jobTerminal <- childId match
          case Some(id) =>
            poll(
              jobs
                .lookup(JobId(id))
                .map(_.exists(h => terminal(h.status)))
            ) *> jobs.lookup(JobId(id))
          case None => ZIO.none
      yield assertTrue(
        // the run fails TYPED at the aggregate ceiling, not silently
        outcome.exists(
          _.swap.exists(e =>
            e.code == "RA-PBUDGET" &&
              e.detail.contains("aggregate token ceiling of 60")
          )
        ),
        toolset.childIds.size == 1,
        ceiling.trippedCeiling == Some(CeilingTrip.TokenCap(60L, 65L)),
        // EXACT joint accounting: the child's usage counted once — present
        // in the totals, attributed to the CHILD entry, never re-added to
        // the root entry, and never lost to the cancellation race
        snap.totalTokens == Usage(40L, 25L, None),
        snap.byActor
          .find(_.actor == "scout#1")
          .exists(u => u.inputTokens == 40L && u.outputTokens == 25L),
        snap.byActor
          .find(_.actor == "root")
          .exists(u => u.inputTokens == 0L && u.outputTokens == 0L),
        // the toolset recorded a TERMINAL cancellation outcome for the child
        recordedOutcome.isDefined,
        // the child job reached a terminal status (Succeeded or Cancelled —
        // which one depends on whether its stream finished before the
        // out-of-band cancellation landed)
        jobTerminal.exists(h => terminal(h.status))
      )
    },
    test(
      "aggregate deadline expiry: parked child cancelled out-of-band, parent denied typed RA-DEADLINE"
    ) {
      val parentDelegate = Vector(
        ModelEvent.ToolCallReady(
          a,
          ToolCallId("call-1"),
          "delegate",
          """{"agent":"scout","input":"park"}"""
        ),
        ModelEvent.Finished(a, "r")
      )
      val parentAwait = (req: ModelRequest) =>
        Vector(
          ModelEvent.ToolCallReady(
            a,
            ToolCallId("call-2"),
            "await_agent",
            s"""{"child_id":"${childIdOf(req)}"}"""
          ),
          ModelEvent.Finished(a, "r")
        )
      val parentFinal = Vector(
        ModelEvent.TextDelta(a, "never reached"),
        ModelEvent.Finished(a, "stop")
      )
      val ceiling = AggregateRunCeiling
        .make(None, None, Some(ZDuration.fromSeconds(1)))
        .fold(e => throw e, identity)
      for
        jobs <- JobManager.make()
        adm <- Admission.make(Right(lims()))
        gate <- Promise.make[Nothing, Unit]
        // the child's dispatch PARKS inside the stream: it is mid-dispatch
        // (admitted, no events yet) when the 1s deadline trips
        backend = new ModelBackend:
          val capabilities: ModelCapabilities = ModelCapabilities(
            CapabilityStatus.Supported,
            CapabilityStatus.Supported,
            CapabilityStatus.Unknown
          )
          def stream(
              input: ModelRequest
          ): ZStream[Scope, RaiderError, ModelEvent] =
            ZStream.unwrap:
              ZIO.succeed:
                val single = input.messages.size == 1
                val head =
                  input.messages.headOption.map(_.content).getOrElse("")
                val isChild = single && head == "park"
                val parkStream: ZStream[Any, Nothing, Nothing] =
                  if isChild then ZStream.fromZIO(gate.await).drain
                  else ZStream.empty
                val body: Vector[ModelEvent] =
                  if single && head == "u" then parentDelegate
                  else if awaitDone(input) then parentFinal
                  else parentAwait(input)
                parkStream ++ ZStream.fromIterable(
                  ModelEvent.Started(a) +: body
                )
          private def awaitDone(input: ModelRequest): Boolean =
            input.messages.exists(m =>
              m.role == "tool" && m.content.contains("call-2")
            )
        root = AgentLoop.newRoot()
        toolset <- ZIO.fromEither(
          DelegationToolset.make(
            jobs,
            backend,
            adm,
            lims(),
            ToolRegistry.empty,
            Set("scout"),
            root,
            aggregate = Some(ceiling)
          )
        )
        registry = toolset
          .augment(ToolRegistry.empty)
          .fold(e => throw e, identity)
        loop = AgentLoop(
          ceiling.wired(backend, "root"),
          registry,
          "parent",
          adm,
          root,
          CostEstimate.Unknown
        )
        outcome <- loop
          .runText(List(RequestMessage("user", "u")), lims(), 8)
          .either
          .timeout(ZDuration.fromSeconds(30))
        childId = toolset.childIds.headOption
        snap <- ceiling.snapshot
        recordedOutcome <- childId match
          case Some(id) =>
            poll(toolset.ceilingCancellationOutcome(id).map(_.isDefined)) *>
              toolset.ceilingCancellationOutcome(id)
          case None => ZIO.none
        jobTerminal <- childId match
          case Some(id) =>
            poll(
              jobs
                .lookup(JobId(id))
                .map(_.exists(h => terminal(h.status)))
            ) *> jobs.lookup(JobId(id))
          case None => ZIO.none
      yield assertTrue(
        outcome.exists(
          _.swap.exists(e =>
            e.code == "RA-DEADLINE" &&
              e.detail.contains("aggregate run deadline of 1s elapsed")
          )
        ),
        toolset.childIds.size == 1,
        ceiling.trippedCeiling.exists {
          case CeilingTrip.Deadline(_) => true
          case _                       => false
        },
        // all three pre-trip dispatches admitted (root u, child park, root
        // await); nothing phantom after the trip
        snap.dispatches == 3,
        // the parked child produced no usage — no phantom accounting
        snap.totalTokens == Usage(0L, 0L, None),
        recordedOutcome.isDefined,
        // the parked child was cancelled out-of-band, not left Running
        jobTerminal.exists(h => h.status == JobStatus.Cancelled)
      )
    },
    test(
      "interleaved reservations: concurrent children cannot race past the dispatch cap; every child gets a terminal outcome"
    ) {
      val parentDelegate2 = Vector(
        ModelEvent.ToolCallReady(
          a,
          ToolCallId("call-1"),
          "delegate",
          """{"agent":"scout","input":"d1"}"""
        ),
        ModelEvent.ToolCallReady(
          a,
          ToolCallId("call-2"),
          "delegate",
          """{"agent":"scout","input":"d2"}"""
        ),
        ModelEvent.Finished(a, "r")
      )
      val childTurn = Vector(
        ModelEvent.TextDelta(a, "done"),
        ModelEvent.Finished(a, "stop")
      )
      val parentAwaitBoth = (req: ModelRequest) =>
        Vector(
          ModelEvent.ToolCallReady(
            a,
            ToolCallId("call-3"),
            "await_agent",
            s"""{"child_id":"${childIdOf(req)}"}"""
          ),
          ModelEvent.Finished(a, "r")
        )
      val parentFinal = Vector(
        ModelEvent.TextDelta(a, "never reached"),
        ModelEvent.Finished(a, "stop")
      )
      val route = (input: ModelRequest) =>
        val single = input.messages.size == 1
        val head = input.messages.headOption.map(_.content).getOrElse("")
        val toolMsgs =
          input.messages.filter(_.role == "tool").map(_.content).mkString
        val delegatesDone =
          toolMsgs.contains("call-1") && toolMsgs.contains("call-2")
        val awaitDone = toolMsgs.contains("call-3")
        if single && head == "u" then parentDelegate2
        else if single && (head == "d1" || head == "d2") then childTurn
        else if delegatesDone then parentAwaitBoth(input)
        else if awaitDone then parentFinal
        else parentFinal
      // cap = 3: root start + the two children — the parent's await round
      // (or the second child, whichever admission arrives last) is refused
      val ceiling = AggregateRunCeiling
        .make(Some(3), None, None)
        .fold(e => throw e, identity)
      for
        jobs <- JobManager.make()
        adm <- Admission.make(Right(lims()))
        backend = new TurnBackend(route)
        root = AgentLoop.newRoot()
        toolset <- ZIO.fromEither(
          DelegationToolset.make(
            jobs,
            backend,
            adm,
            lims(),
            ToolRegistry.empty,
            Set("scout"),
            root,
            aggregate = Some(ceiling)
          )
        )
        registry = toolset
          .augment(ToolRegistry.empty)
          .fold(e => throw e, identity)
        loop = AgentLoop(
          ceiling.wired(backend, "root"),
          registry,
          "parent",
          adm,
          root,
          CostEstimate.Unknown
        )
        outcome <- loop
          .runText(List(RequestMessage("user", "u")), lims(), 8)
          .either
          .timeout(ZDuration.fromSeconds(30))
        snap <- ceiling.snapshot
        recorded <- poll(
          toolset.ceilingCancellationOutcomes.map(_.size == 2)
        ) *> toolset.ceilingCancellationOutcomes
        kids <- ZIO.foreach(toolset.childIds) { id =>
          poll(
            jobs.lookup(JobId(id)).map(_.exists(h => terminal(h.status)))
          ) *> jobs.lookup(JobId(id))
        }
      yield assertTrue(
        outcome.exists(
          _.swap.exists(e =>
            e.code == "RA-PBUDGET" &&
              e.detail.contains("aggregate dispatch cap of 3")
          )
        ),
        toolset.childIds.size == 2,
        // EXACTLY the capped number of dispatches was granted across the
        // racing root+children admissions — the surplus was refused
        snap.dispatches == 3,
        ceiling.trippedCeiling == Some(CeilingTrip.DispatchCap(3)),
        // both spawned children carry a recorded terminal cancellation
        // outcome (Settled / AlreadyTerminal / Uncertain — the tripped
        // ceiling cancels them out-of-band whatever their phase)
        recorded.size == 2,
        kids.flatten.nonEmpty && kids.flatten.forall(h => terminal(h.status))
      )
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential

end AggregateDelegationCeilingSpec
