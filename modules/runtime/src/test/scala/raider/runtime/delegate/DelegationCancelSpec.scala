package raider.runtime.delegate

import raider.core.*
import raider.runtime.admission.{Admission, SlotKind}
import raider.runtime.budget.{BudgetLimits, CostEstimate, MicroUsd}
import raider.runtime.jobs.{JobManager, JobStatus}
import raider.runtime.loop.AgentLoop
import zio.stream.ZStream
import zio.test.{live, TestAspect, assertTrue, test, ZIOSpecDefault, suite}
import zio.duration2DurationOps
import zio.{Duration as ZDuration, Promise, Scope, ZIO}

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** RAI-063 acceptance (CANCEL-01..03): out-of-band subtree cancel, child
  * deadlines and the stuck-child fix. Deterministic throughout: parks are
  * gate/promise-based, waits are bounded wall-clock CAPS only where a real-time
  * claim is the assertion (CANCEL-01 ≤5s), never sleeps for synchronization.
  * RAI-092 STAB-03: every await/cancel coordination is deterministic — a
  * terminal-status snapshot is always read after a settle await, never in
  * parallel with an in-flight interrupt (cancel is a request; Cancelling is not
  * terminal).
  */
object DelegationCancelSpec extends ZIOSpecDefault:

  private val a = AttemptId("cxl-att")
  private val c1 = ToolCallId("call-1")
  private val c2 = ToolCallId("call-2")

  private def lims(
      llm: Int = 1,
      tools: Int = 2,
      attempts: Int = 16,
      children: Int = 4
  ): Either[RaiderError, BudgetLimits] =
    BudgetLimits.make(
      maxConcurrentLlm = llm,
      maxConcurrentTools = tools,
      maxAttempts = attempts,
      maxChildren = children
    )

  private def limsV(
      llm: Int = 1,
      tools: Int = 2,
      attempts: Int = 16,
      children: Int = 4
  ): BudgetLimits =
    lims(llm, tools, attempts, children).fold(e => throw e, identity)

  /** Extract the child_id out of recorded delegate feedback. */
  private def childIdOf(req: ModelRequest): String =
    val content = req.messages.collect { case RequestMessage("tool", c) =>
      c
    }.mkString
    val r = """"child_id":"(j_\d+)"""".r
    r.findFirstMatchIn(content).map(_.group(1)).getOrElse("j_missing")

  private def childIdOfFeedback(json: String): String =
    val r = """"child_id":"(j_\d+)"""".r
    r.findFirstMatchIn(json).map(_.group(1)).getOrElse("j_missing")

  private val fiveSec = ZDuration.fromSeconds(5)

  /** Content-routed deterministic backend (same discipline as
    * AgentLoopDelegationSpec): a registered child input PARKS on its gate — the
    * child holds the model slot while parked. `uninterruptible` parks model an
    * uninterruptible region (deadline Uncertain path).
    */
  private final class ParkDef(
      val gate: Promise[Nothing, Unit],
      val parked: Promise[Nothing, Unit],
      val uninterruptible: Boolean
  )

  private final class RouterBackend(childInput: String) extends ModelBackend:
    private val parks = new ConcurrentHashMap[String, ParkDef]()

    def parkOn(
        input: String,
        gate: Promise[Nothing, Unit],
        parked: Promise[Nothing, Unit],
        uninterruptible: Boolean = false
    ): Unit =
      val _ = parks.put(input, ParkDef(gate, parked, uninterruptible))

    def capabilities: ModelCapabilities = ModelCapabilities(
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
          val pd = if single then Option(parks.get(head)) else None
          val delegateDone = input.messages.exists(m =>
            m.role == "tool" && m.content.contains("call-1")
          )
          val awaitDone = input.messages.exists(m =>
            m.role == "tool" && m.content.contains("call-2")
          )
          val parentDelegate = Vector(
            ModelEvent.ToolCallReady(
              a,
              c1,
              "delegate",
              s"""{"agent":"scout","input":"$childInput"}"""
            ),
            ModelEvent.Finished(a, "r")
          )
          val parentTurn2: Vector[ModelEvent] = Vector(
            ModelEvent.ToolCallReady(
              a,
              c2,
              "await_agent",
              s"""{"child_id":"${childIdOf(input)}"}"""
            ),
            ModelEvent.Finished(a, "r")
          )
          val parentFinal = Vector(
            ModelEvent.TextDelta(a, "done"),
            ModelEvent.Finished(a, "stop")
          )
          val childFinish = Vector(
            ModelEvent.TextDelta(a, "never"),
            ModelEvent.Finished(a, "stop")
          )
          val parkStream: ZStream[Any, Nothing, Nothing] = pd match
            case None => ZStream.empty
            case Some(p) =>
              val pre = ZStream.fromZIO(p.parked.succeed(()).unit)
              val wait =
                if p.uninterruptible
                then ZStream.fromZIO(ZIO.uninterruptible(p.gate.await))
                else ZStream.fromZIO(p.gate.await)
              (pre ++ wait).drain
          val body: Vector[ModelEvent] =
            if single && head == "u" then parentDelegate
            else if pd.isDefined then childFinish
            else if awaitDone then parentFinal
            else if delegateDone then parentTurn2
            else Vector(ModelEvent.Finished(a, "stop"))
          parkStream ++ ZStream.fromIterable(
            ModelEvent.Started(a) +: body
          )

  /** Owner-side delegate invocation through the REAL tool surface (the same
    * argumentsJson the model would send); returns the child_id.
    */
  private def delegateViaTool(
      toolset: DelegationToolset,
      registry: ToolRegistry,
      input: String
  ): ZIO[Scope, RaiderError, String] =
    registry.lookup("delegate") match
      case None => ZIO.fail(RaiderError.Configuration("no delegate tool"))
      case Some(t) =>
        t.invoke(s"""{"agent":"scout","input":"$input"}""")
          .map(childIdOfFeedback)

  /** One CANCEL-01 repeat: deterministic stuck-child scenario at maxLLM=1. The
    * parent loop competes for the SAME single slot: once the child parks, the
    * parent can never reach a model round to issue the model-tool cancel_agent
    * \u2014 the OOB channel must still work. The \u22645s bound IS the claim
    * (measured wall clock of OOB cancel + settle).
    */
  private def cancel01Once(
      i: Int
  ): ZIO[Any, Throwable, (Boolean, Boolean, Boolean, Boolean)] =
    for
      jobs <- JobManager.make()
      adm <- Admission.make(lims(llm = 1))
      gate <- Promise.make[Nothing, Unit]
      parked <- Promise.make[Nothing, Unit]
      backend = RouterBackend("slow")
      _ = backend.parkOn("slow", gate, parked)
      root = AgentLoop.newRoot()
      toolset <- ZIO.fromEither(
        DelegationToolset.make(
          jobs,
          backend,
          adm,
          limsV(llm = 1),
          ToolRegistry.empty,
          Set("scout"),
          root
        )
      )
      registry = toolset
        .augment(ToolRegistry.empty)
        .fold(e => throw e, identity)
      loop = AgentLoop(
        backend,
        registry,
        "parent",
        adm,
        root,
        CostEstimate.Priced(MicroUsd(50L))
      )
      parentF <- loop
        .runText(List(RequestMessage("user", "u")), limsV(llm = 1), 8)
        .forkDaemon
      _ <- parked.await.timeout(fiveSec).someOrFailException
      kids = toolset.childIds
      _ <- ZIO.when(kids.size != 1)(
        ZIO.fail(RaiderError.OutputValidation(s"iter $i: kids=$kids"))
      )
      res <- (toolset.cancelOutOfBand(kids.head) *>
        toolset.awaitSettled(kids.head))
        .timeout(fiveSec)
        .someOrFailException
        .timed
      elapsed = res._1
      snap <- jobs.lookup(JobId(kids.head))
      answer <- parentF.join.timeout(ZDuration.fromSeconds(10))
      modelFree <- adm.availableSlots(SlotKind.Model)
    yield (
      elapsed < fiveSec,
      snap.exists(_.status == JobStatus.Cancelled),
      answer.contains("done"),
      modelFree == 1
    )

  def spec = suite("RAI-063: OOB cancel, deadlines, stuck-child")(
    // CANCEL-01: parked child at maxLLM=1 holds the ONLY model slot — the
    // parent is stuck and could never issue the model-tool cancel_agent
    // (finding AgentLoopDelegationSpec:389-393). cancelOutOfBand reaches
    // the child OUTSIDE model admission: no second slot, ≤5s, ×20.
    test(
      "CANCEL-01: OOB cancel frees a parked child at maxLLM=1 (\u00d720, \u22645s)"
    ) {
      live {
        for rows <- ZIO.foreach(1 to 20)(cancel01Once)
        yield assertTrue(
          rows.size == 20,
          rows.forall(_._1), // every repeat \u22645s
          rows.forall(_._2), // every child Cancelled
          rows.forall(_._3), // every parent unblocked and finished
          rows.forall(_._4) // every cancelled child released the model slot
        )
      }
    },
    // CANCEL-02a: deadline on a RUNNING child (parked mid-model call)
    test("CANCEL-02: deadline cancels a running child; outcome Settled") {
      live {
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims(llm = 2))
          gate <- Promise.make[Nothing, Unit]
          parked <- Promise.make[Nothing, Unit]
          backend = RouterBackend("slow")
          _ = backend.parkOn("slow", gate, parked)
          root = AgentLoop.newRoot()
          toolset <- ZIO.fromEither(
            DelegationToolset.make(
              jobs,
              backend,
              adm,
              limsV(llm = 2),
              ToolRegistry.empty,
              Set("scout"),
              root
            )
          )
          registry = toolset
            .augment(ToolRegistry.empty)
            .fold(e => throw e, identity)
          _ <- ZIO.scoped(
            delegateViaTool(toolset, registry, "slow")
          )
          _ <- parked.await.timeout(fiveSec).someOrFailException
          id = toolset.childIds.head
          accepted <- toolset.setChildDeadline(id, ZDuration.fromMillis(200))
          outcome <- toolset
            .awaitDeadlineOutcome(id)
            .timeout(fiveSec)
            .someOrFailException
          snap <- jobs.lookup(JobId(id))
          kidsAfter <- adm.availableSlots(SlotKind.Model)
        yield assertTrue(
          accepted,
          outcome == Some(DelegationToolset.DeadlineOutcome.Settled),
          snap.exists(_.status == JobStatus.Cancelled),
          kidsAfter == 2 // cancelled child released its model slot
        )
      }
    },
    // CANCEL-02b: deadline on a QUEUED child (blocked BEFORE its first model
    // call: child1 holds the only slot; child2 is cancelled while queued)
    test("CANCEL-02: deadline cancels a queued child; parked sibling intact") {
      live {
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims(llm = 1))
          gate1 <- Promise.make[Nothing, Unit]
          parked1 <- Promise.make[Nothing, Unit]
          backend = RouterBackend("slow-1")
          _ = backend.parkOn("slow-1", gate1, parked1)
          root = AgentLoop.newRoot()
          toolset <- ZIO.fromEither(
            DelegationToolset.make(
              jobs,
              backend,
              adm,
              limsV(llm = 1),
              ToolRegistry.empty,
              Set("scout"),
              root
            )
          )
          registry = toolset
            .augment(ToolRegistry.empty)
            .fold(e => throw e, identity)
          _ <- ZIO.scoped(
            delegateViaTool(toolset, registry, "slow-1")
          )
          _ <- parked1.await.timeout(fiveSec).someOrFailException
          // child1 holds the only model slot NOW: child2 below is queued
          _ <- ZIO.scoped(
            delegateViaTool(toolset, registry, "slow-2")
          )
          kids = toolset.childIds.sorted
          _ <- ZIO.when(kids.size != 2)(
            ZIO.fail(RaiderError.OutputValidation(s"kids=$kids"))
          )
          queuedId = kids(1) // spawn order: j_1 runs (parked), j_2 queued
          accepted <- toolset.setChildDeadline(
            queuedId,
            ZDuration.fromMillis(200)
          )
          outcome <- toolset
            .awaitDeadlineOutcome(queuedId)
            .timeout(fiveSec)
            .someOrFailException
          queuedSnap <- jobs.lookup(JobId(queuedId))
          runnerId = kids(0)
          runnerSnap <- jobs.lookup(JobId(runnerId))
          // cleanup: release the parked sibling and let it settle
          _ <- gate1.succeed(()).unit
          _ <- toolset.awaitSettled(runnerId).timeout(fiveSec)
          runnerAfter <- jobs.lookup(JobId(runnerId))
        yield assertTrue(
          accepted,
          outcome == Some(DelegationToolset.DeadlineOutcome.Settled),
          queuedSnap.exists(_.status == JobStatus.Cancelled),
          runnerSnap.exists(_.status == JobStatus.Running),
          runnerAfter.exists(_.status == JobStatus.Succeeded)
        )
      }
    },
    // CANCEL-02c: an UNINTERRUPTIBLE child cannot settle within the grace:
    // the deadline reports honest Uncertain (never a fake Cancelled)
    test("CANCEL-02: unobservable cleanup → honest Uncertain") {
      live {
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims(llm = 1))
          gate <- Promise.make[Nothing, Unit]
          parked <- Promise.make[Nothing, Unit]
          backend = RouterBackend("stuck")
          _ = backend.parkOn("stuck", gate, parked, uninterruptible = true)
          root = AgentLoop.newRoot()
          toolset <- ZIO.fromEither(
            DelegationToolset.make(
              jobs,
              backend,
              adm,
              limsV(llm = 1),
              ToolRegistry.empty,
              Set("scout"),
              root,
              defaultChildDeadline = None,
              deadlineGrace = ZDuration.fromMillis(300)
            )
          )
          registry = toolset
            .augment(ToolRegistry.empty)
            .fold(e => throw e, identity)
          _ <- ZIO.scoped(
            delegateViaTool(toolset, registry, "stuck")
          )
          _ <- parked.await.timeout(fiveSec).someOrFailException
          id = toolset.childIds.head
          accepted <- toolset.setChildDeadline(id, ZDuration.fromMillis(100))
          outcome <- toolset
            .awaitDeadlineOutcome(id)
            .timeout(fiveSec)
            .someOrFailException
          // gate still CLOSED: the child CANNOT be terminal here
          during <- jobs.lookup(JobId(id))
          _ <- gate.succeed(()).unit
          _ <- toolset.awaitSettled(id).timeout(fiveSec)
          after <- jobs.lookup(JobId(id))
        yield assertTrue(
          accepted,
          outcome == Some(DelegationToolset.DeadlineOutcome.Uncertain),
          during.exists(_.status == JobStatus.Cancelling),
          after.exists(_.status == JobStatus.Cancelled)
        )
      }
    },
    // CANCEL-02d: deadline on unknown child refused; terminal child reports
    // AlreadyTerminal
    test("CANCEL-02: unknown child refused; terminal child AlreadyTerminal") {
      live {
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims(llm = 2))
          backend = RouterBackend("slow")
          root = AgentLoop.newRoot()
          toolset <- ZIO.fromEither(
            DelegationToolset.make(
              jobs,
              backend,
              adm,
              limsV(llm = 2),
              ToolRegistry.empty,
              Set("scout"),
              root
            )
          )
          refused <- toolset.setChildDeadline(
            "j_absent",
            ZDuration.fromMillis(50)
          )
          registry = toolset
            .augment(ToolRegistry.empty)
            .fold(e => throw e, identity)
          _ <- ZIO.scoped(
            delegateViaTool(toolset, registry, "quick-finish")
          )
          id = toolset.childIds.head
          _ <- toolset.awaitSettled(id).timeout(fiveSec)
          accepted <- toolset.setChildDeadline(id, ZDuration.fromMillis(50))
          outcome <- toolset
            .awaitDeadlineOutcome(id)
            .timeout(fiveSec)
            .someOrFailException
        yield assertTrue(
          !refused,
          accepted,
          outcome == Some(DelegationToolset.DeadlineOutcome.AlreadyTerminal)
        )
      }
    },
    // CANCEL-03a: the OWNER channel cancels; the OBSERVER does not exist as
    // a cancellation source — a dead parent loop leaves the parked child
    // running; the child then completes its work normally
    test(
      "CANCEL-03: parent (observer) interruption keeps the child running"
    ) {
      live {
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims(llm = 1))
          gate <- Promise.make[Nothing, Unit]
          parked <- Promise.make[Nothing, Unit]
          backend = RouterBackend("slow")
          _ = backend.parkOn("slow", gate, parked)
          root = AgentLoop.newRoot()
          toolset <- ZIO.fromEither(
            DelegationToolset.make(
              jobs,
              backend,
              adm,
              limsV(llm = 1),
              ToolRegistry.empty,
              Set("scout"),
              root
            )
          )
          registry = toolset
            .augment(ToolRegistry.empty)
            .fold(e => throw e, identity)
          loop = AgentLoop(backend, registry, "parent", adm, root)
          parentF <- loop
            .runText(List(RequestMessage("user", "u")), limsV(llm = 1), 8)
            .forkDaemon
          _ <- parked.await.timeout(fiveSec).someOrFailException
          id = toolset.childIds.head
          // OBSERVER interrupt: the parent dies mid-run; the child is
          // daemon-owned by the JobManager — its death must NOT cancel it
          _ <- parentF.interrupt
          during <- jobs.lookup(JobId(id))
          heldSlots <- adm.availableSlots(SlotKind.Model)
          // the parked child SURVIVES and finishes its work normally
          _ <- gate.succeed(()).unit
          _ <- toolset.awaitSettled(id).timeout(fiveSec)
          after <- jobs.lookup(JobId(id))
          // ...while the OWNER channel still cancels an orphaned child
          gate2 <- Promise.make[Nothing, Unit]
          parked2 <- Promise.make[Nothing, Unit]
          _ = backend.parkOn("orphan", gate2, parked2)
          _ <- ZIO.scoped(
            delegateViaTool(toolset, registry, "orphan")
          )
          _ <- parked2.await.timeout(fiveSec).someOrFailException
          orphanId <- ZIO
            .succeed(toolset.childIds.filterNot(_ == id))
            .map(_.head)
          cancelled <- toolset.cancelOutOfBand(orphanId)
          _ <- toolset.awaitSettled(orphanId).timeout(fiveSec)
          orphanSnap <- jobs.lookup(JobId(orphanId))
        yield assertTrue(
          during.exists(_.status == JobStatus.Running), // NOT cancelled
          heldSlots == 0, // child still HOLDS the only model slot
          after.exists(_.status == JobStatus.Succeeded), // finished its work
          cancelled,
          orphanSnap.exists(_.status == JobStatus.Cancelled)
        )
      }
    },
    // CANCEL-03b: the pure observer lock — interrupting a fiber that merely
    // WAITS on a child (await/awaitSettled watcher) never cancels the child;
    // owner cancellation does
    test("CANCEL-03: watcher interrupt ≠ cancel; owner cancel cancels") {
      live {
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims(llm = 2))
          backend = RouterBackend("slow")
          root = AgentLoop.newRoot()
          handle <- jobs.start(
            root,
            "plain",
            raider.core.Task(ZIO.never: ZIO[Any, RaiderError, Nothing])
          )
          watcher <- jobs.awaitSettled(handle).forkDaemon
          _ <- watcher.interrupt
          during <- jobs.snapshot(handle)
          cancelled <- jobs.cancelAny(handle)
          _ <- jobs.awaitSettled(handle).timeout(fiveSec)
          after <- jobs.snapshot(handle)
        yield assertTrue(
          during.status == JobStatus.Running,
          cancelled,
          after.status == JobStatus.Cancelled
        )
      }
    },
    // OOB channel edge semantics
    test("cancelOutOfBand unknown child → false; cancelAllOutOfBand counts") {
      live {
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims(llm = 2))
          gate <- Promise.make[Nothing, Unit]
          parked <- Promise.make[Nothing, Unit]
          backend = RouterBackend("slow")
          _ = backend.parkOn("slow", gate, parked)
          root = AgentLoop.newRoot()
          toolset <- ZIO.fromEither(
            DelegationToolset.make(
              jobs,
              backend,
              adm,
              limsV(llm = 2),
              ToolRegistry.empty,
              Set("scout"),
              root
            )
          )
          registry = toolset
            .augment(ToolRegistry.empty)
            .fold(e => throw e, identity)
          missing <- toolset.cancelOutOfBand("j_absent")
          _ <- ZIO.scoped(
            delegateViaTool(toolset, registry, "slow")
          )
          _ <- parked.await.timeout(fiveSec).someOrFailException
          id = toolset.childIds.head
          // a SECOND child that already finished: cancelAll must skip it
          doneId <- ZIO.scoped(
            delegateViaTool(toolset, registry, "quick")
          )
          _ <- toolset.awaitSettled(doneId).timeout(fiveSec)
          cancelledAll <- toolset.cancelAllOutOfBand
          // RAI-092 STAB-03: cancelAll only REQUESTS cancellation (status
          // Cancelling + interrupt in flight); the snapshot must be read
          // AFTER the settle, never concurrently with it — a load-delayed
          // interrupt otherwise races this lookup and the parked child is
          // observed mid-Cancelling (nightly finding: Cancelling under load).
          // Deterministic await, not wall-clock: awaitSettled returns at once
          // for an already-terminal job, so the settled doneId is safe.
          _ <- toolset.awaitSettled(id).timeout(fiveSec)
          parkedSnap <- jobs.lookup(JobId(id))
        yield assertTrue(
          !missing,
          cancelledAll.contains(id),
          !cancelledAll.contains(doneId),
          parkedSnap.exists(_.status == JobStatus.Cancelled)
        )
      }
    },
    // default deadline wiring: constructor default applies to spawned children
    test("defaultChildDeadline wires every spawned child") {
      live {
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims(llm = 2))
          gate <- Promise.make[Nothing, Unit]
          parked <- Promise.make[Nothing, Unit]
          backend = RouterBackend("slow")
          _ = backend.parkOn("slow", gate, parked)
          root = AgentLoop.newRoot()
          toolset <- ZIO.fromEither(
            DelegationToolset.make(
              jobs,
              backend,
              adm,
              limsV(llm = 2),
              ToolRegistry.empty,
              Set("scout"),
              root,
              defaultChildDeadline = Some(ZDuration.fromMillis(200))
            )
          )
          registry = toolset
            .augment(ToolRegistry.empty)
            .fold(e => throw e, identity)
          _ <- ZIO.scoped(
            delegateViaTool(toolset, registry, "slow")
          )
          _ <- parked.await.timeout(fiveSec).someOrFailException
          id = toolset.childIds.head
          outcome <- toolset
            .awaitDeadlineOutcome(id)
            .timeout(fiveSec)
            .someOrFailException
          snap <- jobs.lookup(JobId(id))
          // no deadline was set by hand — the constructor default did it
          explicit <- toolset.setChildDeadline("j_absent", fiveSec)
        yield assertTrue(
          outcome == Some(DelegationToolset.DeadlineOutcome.Settled),
          snap.exists(_.status == JobStatus.Cancelled),
          !explicit
        )
      }
    },
    test("deadlineGrace must be positive (Configuration)") {
      for
        jobs <- JobManager.make()
        adm <- Admission.make(lims())
        bad = DelegationToolset.make(
          jobs,
          RouterBackend("slow"),
          adm,
          limsV(),
          ToolRegistry.empty,
          Set("scout"),
          AgentLoop.newRoot(),
          defaultChildDeadline = None,
          deadlineGrace = ZDuration.Zero
        )
      yield assertTrue(bad.swap.exists(_.code == "RA-CFG"))
    }
  ) @@ TestAspect.sequential

end DelegationCancelSpec
