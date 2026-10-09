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
import scala.jdk.CollectionConverters.*

/** §11 acceptance: delegate/await_agent/cancel_agent/list_agents over the SAME
  * JobManager/root budget; maxLLM=1 without deadlock; no recursive delegation;
  * unknown names give structured feedback. The backend is a DETERMINISTIC turn
  * simulator that routes by request CONTENT (roles/message texts — RAI-092
  * STAB-01), and turn functions may read the request (so the parent's
  * await_agent can reference the REAL child id from the delegate feedback).
  * Content routing is load-order-independent: parent and child dispatch
  * concurrently, so a call-INDEXED simulator (k-th call replays k-th turn)
  * raced under load — the parent's next round could claim the child's turn
  * (answer mismatch, tries != 6; nightly finding 2026-10-05, A/B proven).
  */
object AgentLoopDelegationSpec extends ZIOSpecDefault:

  private val a = AttemptId("dlg-att")
  private val c1 = ToolCallId("call-1")
  private val c2 = ToolCallId("call-2")
  private val c3 = ToolCallId("call-3")

  /** Content-routed turn backend (RAI-092 STAB-01): the route function picks
    * the turn from the request CONTENT (message count / role / text /
    * tool_call_id), never from a call index — the parent/child dispatch order
    * is not deterministic under load, so only content coordination keeps the
    * scenario load-order-independent (same discipline as the anonymous backends
    * in the max_children and cancel_agent tests below).
    */
  private final class TurnBackend(route: ModelRequest => Vector[ModelEvent])
      extends ModelBackend:
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
          recorded.add(input)
          ZStream(ModelEvent.Started(a)) ++
            ZStream.fromIterable(route(input))

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
        // scripted turns (content-routed below; hoisted vals — a leading
        // `=` lambda binding is not parseable in a for-comprehension)
        val parentDelegate = Vector( // parent turn 1: delegate
          ModelEvent.ToolCallReady(
            a,
            c1,
            "delegate",
            """{"agent":"scout","input":"find x"}"""
          ),
          ModelEvent.Finished(a, "r")
        )
        val childAnswer = Vector( // child scout: plain answer
          ModelEvent.TextDelta(a, "found x"),
          ModelEvent.Finished(a, "stop")
        )
        val parentAwait = (req: ModelRequest) =>
          Vector( // parent turn 2: await the REAL child id
            ModelEvent.ToolCallReady(
              a,
              c2,
              "await_agent",
              s"""{"child_id":"${childIdOf(req)}"}"""
            ),
            ModelEvent.Finished(a, "r")
          )
        val parentFinal = Vector( // parent turn 3: final
          ModelEvent.TextDelta(a, "child said: found x"),
          ModelEvent.Finished(a, "stop")
        )
        // ROUTING BY CONTENT, not by call index: the parent's next round and
        // the child's first round race for the same maxLLM=1 slot, so the
        // arrival order is not deterministic under load (RAI-092 STAB-01;
        // nightly finding 2026-10-05). Roles/texts distinguish every caller:
        // parent start "u", child input "find x", delegate feedback carries
        // tool_call_id call-1, await feedback carries call-2.
        val delegationRoute = (input: ModelRequest) =>
          val single = input.messages.size == 1
          val head =
            input.messages.headOption.map(_.content).getOrElse("")
          val delegateDone = input.messages.exists(m =>
            m.role == "tool" && m.content.contains("call-1")
          )
          val awaitDone = input.messages.exists(m =>
            m.role == "tool" && m.content.contains("call-2")
          )
          if single && head == "u" then parentDelegate
          else if single && head == "find x" then childAnswer
          else if awaitDone then parentFinal
          else parentAwait(input)
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims())
          backend = new TurnBackend(delegationRoute)
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
        // (route hoisted: a leading `=` lambda binding is not parseable in a
        // for-comprehension)
        val unknownAgentRoute = (input: ModelRequest) =>
          if input.messages.size == 1 then
            Vector(
              ModelEvent.ToolCallReady(
                a,
                c1,
                "delegate",
                """{"agent":"nope","input":"x"}"""
              ),
              ModelEvent.Finished(a, "r")
            )
          else
            Vector(
              ModelEvent.TextDelta(a, "recovered"),
              ModelEvent.Finished(a, "stop")
            )
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims())
          // Content-routed (STAB-01): round 1 = single "u" (delegate to an
          // unknown agent); the refusal spawns NO child, so the next parent
          // request is any multi-message one — deterministic either way.
          backend = new TurnBackend(unknownAgentRoute)
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
        // ROUTING BY CONTENT/tool_call_id, not call index: the child spawns
        // asynchronously, so under load its call can land after a parent turn
        // and shift the index (nightly finding 2026-10-05, same race as the
        // parked-child test)
        val parentDelegate1 = Vector(
          ModelEvent.ToolCallReady(
            a,
            c1,
            "delegate",
            """{"agent":"scout","input":"1"}"""
          ),
          ModelEvent.Finished(a, "r")
        )
        val childTurn = Vector(
          ModelEvent.TextDelta(a, "c1"),
          ModelEvent.Finished(a, "stop")
        )
        val parentDelegate2 = Vector(
          ModelEvent.ToolCallReady(
            a,
            c2,
            "delegate",
            """{"agent":"scout","input":"2"}"""
          ),
          ModelEvent.Finished(a, "r")
        )
        val parentFinal = Vector(
          ModelEvent.TextDelta(a, "done"),
          ModelEvent.Finished(a, "stop")
        )
        // recorder declared OUTSIDE the for: the comprehension widens the
        // backend val to ModelBackend (the refined type would be lost)
        val recorded =
          java.util.concurrent.CopyOnWriteArrayList[ModelRequest]()
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims())
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
                  recorded.add(input)
                  val single = input.messages.size == 1
                  val head =
                    input.messages.headOption.map(_.content).getOrElse("")
                  val refuseDone = input.messages.exists(m =>
                    m.role == "tool" && m.content.contains("call-2")
                  )
                  val firstDelegateDone = input.messages.exists(m =>
                    m.role == "tool" && m.content.contains("call-1")
                  )
                  val body: Vector[ModelEvent] =
                    if refuseDone then parentFinal
                    else if firstDelegateDone then parentDelegate2
                    else if single && head == "1" then childTurn
                    else parentDelegate1
                  ZStream.fromIterable(ModelEvent.Started(a) +: body)
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
          reqs = recorded.asScala.toList
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
        // Content-routed (STAB-01): round 1 = single "u" (await an unknown
        // child); the structured refusal spawns NO child, so the next parent
        // request is any multi-message one. No concurrent caller exists.
        val unknownChildRoute = (input: ModelRequest) =>
          if input.messages.size == 1 then
            Vector(
              ModelEvent.ToolCallReady(
                a,
                c1,
                "await_agent",
                """{"child_id":"j_9999"}"""
              ),
              ModelEvent.Finished(a, "r")
            )
          else
            Vector(
              ModelEvent.TextDelta(a, "fine"),
              ModelEvent.Finished(a, "stop")
            )
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims())
          backend = new TurnBackend(unknownChildRoute)
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
        //
        // ROUTING BY CONTENT, not by call index: parent and child dispatch
        // concurrently under llm=2, so arrival order is not deterministic
        // under load (nightly finding 2026-10-05 — the old call-index turns
        // raced and could hand the parent the child's turn).
        // scripted turns (content-routed below; hoisted vals — a leading
        // `=` lambda binding is not parseable in a for-comprehension)
        val parentDelegate = Vector(
          ModelEvent.ToolCallReady(
            a,
            c1,
            "delegate",
            """{"agent":"scout","input":"slow"}"""
          ),
          ModelEvent.Finished(a, "r")
        )
        val childTurn = Vector(
          ModelEvent.TextDelta(a, "never"),
          ModelEvent.Finished(a, "stop")
        )
        val parentCancelReq = (req: ModelRequest) =>
          Vector(
            ModelEvent.ToolCallReady(
              a,
              c2,
              "cancel_agent",
              s"""{"child_id":"${childIdOf(req)}"}"""
            ),
            ModelEvent.Finished(a, "r")
          )
        val parentFinal = Vector(
          ModelEvent.TextDelta(a, "canceled it"),
          ModelEvent.Finished(a, "stop")
        )
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims(llm = 2))
          gate <- Promise.make[Nothing, Unit]
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
                  val isChild = single && head.contains("slow")
                  val isParentStart = single && head == "u"
                  // route by tool_call_id (c1 = ToolCallId("call-1"),
                  // c2 = ToolCallId("call-2")): "delegate" the literal is NOT
                  // in the delegate result: {"child_id":…,"state":…}
                  val delegateDone = input.messages.exists(m =>
                    m.role == "tool" && m.content.contains("call-1")
                  )
                  val cancelDone = input.messages.exists(m =>
                    m.role == "tool" && m.content.contains("call-2")
                  )
                  val parkStream: ZStream[Any, Nothing, Nothing] =
                    if isChild then ZStream.fromZIO(gate.await).drain
                    else ZStream.empty
                  val body: Vector[ModelEvent] =
                    if isParentStart then parentDelegate
                    else if isChild then childTurn
                    else if delegateDone && !cancelDone then
                      parentCancelReq(input)
                    else parentFinal
                  parkStream ++ ZStream.fromIterable(
                    ModelEvent.Started(a) +: body
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
            case List(id) =>
              // bounded settle wait: a lost settle signal must surface as a
              // test FAILURE (assertions below), never an infinite suite hang
              // under machine load (nightly finding, 2026-10-05)
              toolset.awaitSettled(id).timeout(ZDuration.fromSeconds(30))
            case _ => ZIO.unit
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
          TurnBackend(_ => Vector(ModelEvent.Finished(a, "stop"))),
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
          TurnBackend(_ => Vector(ModelEvent.Finished(a, "stop"))),
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
            TurnBackend(_ => Vector(ModelEvent.Finished(a, "stop"))),
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
