package raider.runtime.loop

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.{BudgetLimits, MicroUsd}
import raider.runtime.jobs.JobManager
import raider.runtime.budget.CostEstimate
import zio.test.{live, *}
import zio.stream.ZStream
import zio.{Duration as ZDuration, Promise, Scope, ZIO}

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** §11 acceptance: delegate/await_agent/cancel_agent/list_agents over the SAME
  * JobManager/root budget; maxLLM=1 without deadlock; no recursive delegation;
  * unknown names give structured feedback. The backend is a DETERMINISTIC turn
  * simulator: k-th stream() call replays k-th turn, and turn functions may read
  * the recorded request (so the parent's await_agent can reference the REAL
  * child id from the delegate feedback).
  */
object AgentLoopDelegationSpec extends ZIOSpecDefault:

  private val a = AttemptId("dlg-att")
  private val c1 = ToolCallId("call-1")
  private val c2 = ToolCallId("call-2")
  private val c3 = ToolCallId("call-3")

  /** Turn-simulating backend: k-th call replays k-th turn with the recorded
    * request in scope; `parkIf` parks a matching call on the gate — used to
    * hold the child mid-run for cancel tests.
    */
  private final class TurnBackend(
      turns: Vector[ModelRequest => Vector[ModelEvent]],
      parkIf: Option[ModelRequest => Boolean] = None,
      gate: Option[Promise[Nothing, Unit]] = None
  ) extends ModelBackend:
    private val counter = AtomicInteger(0)
    private val recorded = new CopyOnWriteArrayList[ModelRequest]()

    def capabilities: ModelCapabilities =
      ModelCapabilities(
        CapabilityStatus.Supported,
        CapabilityStatus.Supported,
        CapabilityStatus.Unknown
      )

    def requests: List[ModelRequest] =
      recorded.asScala.toList

    def stream(input: ModelRequest): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap:
        ZIO.succeed:
          val k = counter.getAndIncrement()
          recorded.add(input)
          // DETERMINISTIC park: by request CONTENT, not call index — the
          // parent/child fork order is not deterministic under load.
          // NOTE: `empty *> s` would END the combined stream without
          // pulling s (zip semantics) — park must be `++` + drain.
          val parkStream: ZStream[Any, Nothing, Nothing] =
            (parkIf, gate) match
              case (Some(p), Some(g)) if p(input) =>
                ZStream.fromZIO(g.await).drain
              case _ => ZStream.empty
          val body: ZStream[Scope, RaiderError, ModelEvent] =
            parkStream ++ ZStream.fromIterable(
              if k < turns.length then turns(k)(input)
              else Vector(ModelEvent.Finished(AttemptId(s"t$k"), "stop"))
            )
          ZStream(ModelEvent.Started(AttemptId(s"t$k"))) ++ body

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

  /** Extract the child_id out of the recorded delegate feedback. */
  private def childIdOf(req: ModelRequest): String =
    val content = req.messages.collect { case RequestMessage("tool", c) =>
      c
    }.mkString
    val r = """"child_id":"(j_\d+)"""".r
    r.findFirstMatchIn(content).map(_.group(1)).getOrElse("j_missing")

  def spec = suite("§11 child delegation (model-callable tools)")(
    test(
      "delegate → await_agent → final: one root budget, maxLLM=1, no deadlock"
    ) {
      live {
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims())
          backend = new TurnBackend(
            Vector(
              _ =>
                Vector( // parent turn 1: delegate
                  ModelEvent.ToolCallReady(
                    a,
                    c1,
                    "delegate",
                    """{"agent":"scout","input":"find x"}"""
                  ),
                  ModelEvent.Finished(a, "r")
                ),
              _ =>
                Vector( // child scout: plain answer
                  ModelEvent.TextDelta(a, "found x"),
                  ModelEvent.Finished(a, "stop")
                ),
              req =>
                Vector( // parent turn 2: await the REAL child id
                  ModelEvent.ToolCallReady(
                    a,
                    c2,
                    "await_agent",
                    s"""{"child_id":"${childIdOf(req)}"}"""
                  ),
                  ModelEvent.Finished(a, "r")
                ),
              _ =>
                Vector( // parent turn 3: final
                  ModelEvent.TextDelta(a, "child said: found x"),
                  ModelEvent.Finished(a, "stop")
                )
            )
          )
          root = AgentLoop.newRoot()
          toolset <- ZIO.fromEither(
            raider.runtime.delegate.DelegationToolset.make(
              jobs,
              backend,
              adm,
              limsV(),
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
          answer <- loop
            .runText(List(RequestMessage("user", "u")), limsV(), 8)
            .timeout(ZDuration.fromSeconds(15))
          usage <- adm.budgetLedger.usage
          kids <- toolset.childIds match
            case List(id) => jobs.lookup(JobId(id))
            case other    => ZIO.none
          tries <- adm.attempts(root)
        yield assertTrue(
          answer.contains("child said: found x"),
          toolset.childIds.size == 1, // exactly one child spawned
          kids.isDefined,
          // ONE root ledger: 3 parent model rounds + 1 child model round
          // + 2 tool dispatches — the child spends the SAME root budget
          tries == 6,
          usage.reservedActive == MicroUsd(0)
        )
      }
    },
    test(
      "unknown agent → structured feedback, loop continues to a final text"
    ) {
      live {
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims())
          backend = new TurnBackend(
            Vector(
              _ =>
                Vector(
                  ModelEvent.ToolCallReady(
                    a,
                    c1,
                    "delegate",
                    """{"agent":"nope","input":"x"}"""
                  ),
                  ModelEvent.Finished(a, "r")
                ),
              _ =>
                Vector(
                  ModelEvent.TextDelta(a, "recovered"),
                  ModelEvent.Finished(a, "stop")
                )
            )
          )
          root = AgentLoop.newRoot()
          toolset <- ZIO.fromEither(
            raider.runtime.delegate.DelegationToolset.make(
              jobs,
              backend,
              adm,
              limsV(),
              ToolRegistry.empty,
              Set("scout"),
              root
            )
          )
          registry = toolset
            .augment(ToolRegistry.empty)
            .fold(e => throw e, identity)
          loop = AgentLoop(backend, registry, "parent", adm, root)
          answer <- loop
            .runText(List(RequestMessage("user", "u")), limsV(), 4)
            .timeout(ZDuration.fromSeconds(15))
          reqs = backend.requests
        yield assertTrue(
          answer.contains("recovered"),
          reqs(1).messages(1).content.contains("unknown_agent"),
          reqs(1).messages(1).content.contains("\"nope\""),
          toolset.childIds.isEmpty
        ) // nothing spawned
      }
    },
    test("max_children exceeded → structured feedback, no extra spawn") {
      live {
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims())
          backend = new TurnBackend(
            Vector(
              _ =>
                Vector( // parent: delegate #1 (ok)
                  ModelEvent.ToolCallReady(
                    a,
                    c1,
                    "delegate",
                    """{"agent":"scout","input":"1"}"""
                  ),
                  ModelEvent.Finished(a, "r")
                ),
              _ =>
                Vector( // child
                  ModelEvent.TextDelta(a, "c1"),
                  ModelEvent.Finished(a, "stop")
                ),
              _ =>
                Vector( // parent: delegate #2 → max_children feedback
                  ModelEvent.ToolCallReady(
                    a,
                    c2,
                    "delegate",
                    """{"agent":"scout","input":"2"}"""
                  ),
                  ModelEvent.Finished(a, "r")
                ),
              _ =>
                Vector( // parent final
                  ModelEvent.TextDelta(a, "done"),
                  ModelEvent.Finished(a, "stop")
                )
            )
          )
          root = AgentLoop.newRoot()
          toolset <- ZIO.fromEither(
            raider.runtime.delegate.DelegationToolset.make(
              jobs,
              backend,
              adm,
              limsV(children = 1),
              ToolRegistry.empty,
              Set("scout"),
              root
            )
          )
          registry = toolset
            .augment(ToolRegistry.empty)
            .fold(e => throw e, identity)
          loop = AgentLoop(backend, registry, "parent", adm, root)
          answer <- loop
            .runText(List(RequestMessage("user", "u")), limsV(), 8)
            .timeout(ZDuration.fromSeconds(15))
          reqs = backend.requests
        yield assertTrue(
          answer.contains("done"),
          toolset.childIds.size == 1, // second delegate refused
          reqs.exists(
            _.messages.exists(m =>
              m.role == "tool" && m.content.contains("max_children")
            )
          )
        )
      }
    },
    test("await_agent on an unknown child → structured feedback") {
      live {
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims())
          backend = new TurnBackend(
            Vector(
              _ =>
                Vector(
                  ModelEvent.ToolCallReady(
                    a,
                    c1,
                    "await_agent",
                    """{"child_id":"j_9999"}"""
                  ),
                  ModelEvent.Finished(a, "r")
                ),
              _ =>
                Vector(
                  ModelEvent.TextDelta(a, "fine"),
                  ModelEvent.Finished(a, "stop")
                )
            )
          )
          root = AgentLoop.newRoot()
          toolset <- ZIO.fromEither(
            raider.runtime.delegate.DelegationToolset.make(
              jobs,
              backend,
              adm,
              limsV(),
              ToolRegistry.empty,
              Set("scout"),
              root
            )
          )
          registry = toolset
            .augment(ToolRegistry.empty)
            .fold(e => throw e, identity)
          loop = AgentLoop(backend, registry, "parent", adm, root)
          answer <- loop
            .runText(List(RequestMessage("user", "u")), limsV(), 4)
            .timeout(ZDuration.fromSeconds(15))
          reqs = backend.requests
        yield assertTrue(
          answer.contains("fine"),
          reqs(1).messages(1).content.contains("unknown_child")
        )
      }
    },
    test("cancel_agent stops a parked child; list_agents shows Cancelled") {
      live {
        // llm=2 ON PURPOSE: with maxLLM=1 a PARKED child holds the only
        // model slot while the parent needs its own slot to even issue
        // cancel_agent — a genuine design finding (see record): cancellation
        // of a stuck child needs either a second slot, child deadlines, or
        // an out-of-band cancel channel (obligation).
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims(llm = 2))
          gate <- Promise.make[Nothing, Unit]
          backend = new TurnBackend(
            Vector(
              _ =>
                Vector( // parent: delegate (child parks mid-run)
                  ModelEvent.ToolCallReady(
                    a,
                    c1,
                    "delegate",
                    """{"agent":"scout","input":"slow"}"""
                  ),
                  ModelEvent.Finished(a, "r")
                ),
              _ =>
                Vector( // child would answer — but it is parked
                  ModelEvent.TextDelta(a, "never"),
                  ModelEvent.Finished(a, "stop")
                ),
              req =>
                Vector( // parent: cancel the parked child
                  ModelEvent.ToolCallReady(
                    a,
                    c2,
                    "cancel_agent",
                    s"""{"child_id":"${childIdOf(req)}"}"""
                  ),
                  ModelEvent.Finished(a, "r")
                ),
              _ =>
                Vector(
                  ModelEvent.TextDelta(a, "canceled it"),
                  ModelEvent.Finished(a, "stop")
                )
            ),
            parkIf = Some(req =>
              // the CHILD's first call: single plain user message "slow"
              req.messages.size == 1 &&
                req.messages.head.content.contains("slow")
            ),
            gate = Some(gate)
          )
          root = AgentLoop.newRoot()
          toolset <- ZIO.fromEither(
            raider.runtime.delegate.DelegationToolset.make(
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
          loop = AgentLoop(backend, registry, "parent", adm, root)
          answer <- loop
            .runText(
              List(RequestMessage("user", "u")),
              lims(llm = 2).fold(e => throw e, identity),
              8
            )
            .timeout(ZDuration.fromSeconds(15))
          _ <- toolset.childIds match
            case List(id) => toolset.awaitSettled(id)
            case _        => ZIO.unit
          snaps <- ZIO.foreach(toolset.childIds)(id =>
            jobs.lookup(raider.core.JobId(id))
          )
          modelFree <- adm.availableSlots(
            raider.runtime.admission.SlotKind.Model
          )
        yield assertTrue(
          answer.contains("canceled it"),
          snaps.flatten.exists(
            _.status == raider.runtime.jobs.JobStatus.Cancelled
          ),
          modelFree == 2
        ) // cancelled child released its model slot
      }
    },
    test(
      "construction: maxConcurrentTools<2 and shadowed names are Configuration"
    ) {
      for
        jobs <- JobManager.make()
        adm <- Admission.make(lims(tools = 1))
        bad1 = raider.runtime.delegate.DelegationToolset.make(
          jobs,
          TurnBackend(Vector.empty),
          adm,
          lims(tools = 1).fold(e => throw e, identity),
          ToolRegistry.empty,
          Set("scout"),
          AgentLoop.newRoot()
        )
        shadowRegistry = ToolRegistry
          .of(new Tool:
            def name = "delegate"; def version = 1; def description = ""
            def recovery = RecoveryClass.ReadOnly; def timeoutMs = 1000L
            def capabilities = ToolCapabilities(concurrentSafe = false)
            def invoke(args: String) = ZIO.succeed("{}"))
          .fold(e => throw e, identity)
        bad2 = raider.runtime.delegate.DelegationToolset.make(
          jobs,
          TurnBackend(Vector.empty),
          adm,
          lims(tools = 2).fold(e => throw e, identity),
          shadowRegistry,
          Set("scout"),
          AgentLoop.newRoot()
        )
      yield assertTrue(
        bad1.swap.exists(_.code == "RA-CFG"),
        bad2.swap.exists(_.detail.contains("must not define"))
      )
    },
    test("no recursive delegation: children get the base registry only") {
      for
        jobs <- JobManager.make()
        adm <- Admission.make(lims())
        base = ToolRegistry
          .of(new Tool:
            def name = "lookup"; def version = 1; def description = ""
            def recovery = RecoveryClass.ReadOnly; def timeoutMs = 1000L
            def capabilities = ToolCapabilities(concurrentSafe = false)
            def invoke(args: String) = ZIO.succeed("\"ok\""))
          .fold(e => throw e, identity)
        toolset = raider.runtime.delegate.DelegationToolset
          .make(
            jobs,
            TurnBackend(Vector.empty),
            adm,
            limsV(),
            base,
            Set("scout"),
            AgentLoop.newRoot()
          )
          .fold(e => throw e, identity)
        parentRegistry = toolset.augment(base).fold(e => throw e, identity)
      yield assertTrue(
        parentRegistry.lookup("delegate").isDefined,
        parentRegistry.lookup("await_agent").isDefined,
        // §11: the child cannot re-delegate — its registry is the base only
        base.lookup("delegate").isEmpty,
        base.lookup("lookup").isDefined
      )
    }
  ) @@ TestAspect.sequential
  // sequential: delegation flows share fair queues; parallel tests only add
  // queue contention (each test keeps its own admission/jobs), and the

  // >1-minute runaway warnings under parallel load hid real failures.
