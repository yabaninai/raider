package raider.repl.facade

import raider.core.{AttemptId, ModelEvent, RaiderError, RequestMessage,
                    RecoveryClass, Tool, ToolCallId, ToolCapabilities,
                    ToolRegistry}
import raider.runtime.budget.BudgetLimits
import raider.runtime.jobs.JobStatus
import raider.testkit.{ScriptedModelBackend, ScriptedSequenceBackend}
import zio.ZIO
import zio.test._

/** Behavioral fixtures for the REPL agent facade (runtime-contracts §3) on the
  * deterministic ScriptedModelBackend (RAI-006): every scenario is instant and
  * synchronous — no network, no live endpoints, no real spend. `fixtureMode`
  * is asserted so scripted output is never mistaken for a live model answer.
  *
  * Facade ops run exactly as the product does — blocking extension methods on
  * `using ReplSession` — which is safe here: the scripted wire finishes
  * immediately, so the blocking boundary never actually waits.
  */
object FacadeOpsSpec extends ZIOSpecDefault:

  private val attempt = AttemptId("scripted-1")

  private val Head = "[scripted-fixture] answer-part "
  private val Tail = "complete"

  /** Standard instant script: Started → TextDelta → TextDelta → Finished.
    * Finished is mandatory — without it Runner fails the attempt with a
    * StreamProtocol error ("stream ended without Finished"). */
  private def scripted(first: String, second: String)
      : ZIO[Any, RaiderError, (ScriptedModelBackend, ReplSession)] =
    for
      backend <- ScriptedModelBackend(
        ModelEvent.Started(attempt),
        ModelEvent.TextDelta(attempt, first),
        ModelEvent.TextDelta(attempt, second),
        ModelEvent.Finished(attempt, "stop"))
      session <- ReplSession.make(backend, BudgetLimits.make())
    yield (backend, session)

  /** The facade registry (FacadeOps.started) is a process-global map keyed by
    * JobId; JobManager id counters restart per fresh manager, so concurrent
    * test fibers could collide on j_1 across scenarios. Every scenario here
    * is instant, so sequential execution costs nothing and makes the shared
    * registry deterministic. */
  def spec = suite("FacadeOps scripted fixtures (repl facade)")(

    test("ask: foreground ask returns the concatenated scripted text") {
      for
        (backend, session) <- scripted(Head, Tail)
        answer = AgentRef("scout").ask("ping")(using session)
        recorded <- backend.requests
      yield assertTrue(
        backend.fixtureMode,
        answer == Head + Tail,
        recorded.size == 1, // one attempt, one recorded request
        recorded.head.model == "scripted:scout",
        recorded.head.messages == List(RequestMessage("user", "ping")))
    },

    test("start→await: two awaits give the same text and never re-run the job") {
      for
        (backend, session) <- scripted("[scripted-fixture] bg ", "answer")
        handle = AgentRef("worker").start("bg")(using session)
        first  = handle.await()(using session)
        afterFirst <- backend.requests
        second = handle.await()(using session)
        afterSecond <- backend.requests
      yield assertTrue(
        first == "[scripted-fixture] bg answer",
        second == first,       // idempotent await (JOB-03), same outcome
        afterFirst.size == 1,  // exactly one backend request per start
        afterSecond.size == afterFirst.size) // second await does not re-run
    },

    test("registration: started handle is found for :jobs/:cancel and settles Succeeded") {
      for
        (_, session) <- scripted(Head, Tail)
        handle = AgentRef("scout").start("bg")(using session)
        _      = handle.await()(using session)
        byId   = FacadeOps.handleById(handle.id) // backs :jobs / :cancel <id>
        snap  <- session.jobs.snapshotAny(handle)
      yield assertTrue(
        byId.isDefined && byId.contains(handle),
        snap.status == JobStatus.Succeeded)
    },

    test("cancel on an already terminal job is false") {
      for
        (_, session) <- scripted(Head, Tail)
        handle = AgentRef("worker").start("bg")(using session)
        _      = handle.await()(using session) // Succeeded — already terminal
        cancelled = handle.cancel()(using session)
      yield assertTrue(!cancelled)
    },

    test("send is a typed refusal: RaiderError.CapabilityUnsupported (RA-CAP)") {
      for
        (_, session) <- scripted(Head, Tail)
        handle = AgentRef("scout").start("bg")(using session)
        refusal <- ZIO.attempt(handle.send("steer")(using session)).either
        capability = refusal match
          case Left(_: RaiderError.CapabilityUnsupported) => true
          case _ => false
        code = refusal match
          case Left(e: RaiderError) => e.code
          case _                    => ""
        detail = refusal match
          case Left(e: RaiderError) => e.detail
          case _                    => ""
      yield assertTrue(capability, code == "RA-CAP", detail.contains("send"))
    },

    test("two background starts: distinct JobIds, both Succeeded in adminView") {
      for
        (_, session) <- scripted(Head, Tail)
        a = AgentRef("worker-a").start("bg-1")(using session)
        b = AgentRef("worker-b").start("bg-2")(using session)
        ra = a.await()(using session)
        rb = b.await()(using session)
        sa <- session.jobs.snapshotAny(a)
        sb <- session.jobs.snapshotAny(b)
        view <- session.jobs.adminView
      yield assertTrue(
        a.id != b.id,
        ra == Head + Tail,
        rb == Head + Tail,
        sa.status == JobStatus.Succeeded,
        sb.status == JobStatus.Succeeded,
        view.map(_.id).contains(a.id) && view.map(_.id).contains(b.id))
    },

    test("tools-in-loop: session-frozen registry serves a scripted tool round") {
      val replTool = new Tool:
        def name = "repltool"; def version = 1; def description = ""
        def recovery = RecoveryClass.ReadOnly; def timeoutMs = 5000L
        def capabilities = ToolCapabilities(concurrentSafe = false)
        def invoke(args: String) = ZIO.succeed("""{"ok":true}""")
      for
        backend <- ScriptedSequenceBackend(
          Vector[ModelEvent](
            ModelEvent.Started(attempt),
            ModelEvent.ToolCallReady(attempt, ToolCallId("c-1"), "repltool", "1"),
            ModelEvent.Finished(attempt, "round")),
          Vector[ModelEvent](
            ModelEvent.Started(attempt),
            ModelEvent.TextDelta(attempt, "tool "),
            ModelEvent.TextDelta(attempt, "answered"),
            ModelEvent.Finished(attempt, "stop")))
        registry = ToolRegistry.of(replTool).fold(e => throw e, identity)
        session <- ReplSession.make(backend, BudgetLimits.make(), registry)
        answer = AgentRef("scout").ask("use the tool")(using session)
        reqs <- backend.requests
      yield assertTrue(
        backend.fixtureMode,
        answer == "tool answered",
        reqs.size == 2, // tool round + final round through the ONE loop
        reqs(1).messages(1).role == "tool",
        reqs(1).messages(1).content.contains("\"c-1\""))
    }
  ) @@ TestAspect.sequential
