package raider.repl.facade

import raider.core.{AttemptId, ModelEvent, RaiderError, RequestMessage, Task}
import raider.runtime.budget.BudgetLimits
import raider.runtime.jobs.JobStatus
import raider.testkit.ScriptedModelBackend
import zio.ZIO
import zio.test.*

/** RAI-018 slice: low-verbosity DSL ergonomics over the ONE agent loop — golden
  * scenarios from working-product §4 on the deterministic scripted backend.
  * Laziness (API-03) is asserted: building descriptors dispatches nothing.
  */
object DslErgonomicsSpec extends ZIOSpecDefault:

  private val attempt = AttemptId("erg-1")
  private val Head = "[erg] answer "
  private val Tail = "complete"

  // golden-scenario agent handles (prelude spellings)
  private val scout = AgentRef("scout")
  private val worker = AgentRef("worker")
  private val reviewer = AgentRef("reviewer")

  private def scripted()
      : ZIO[Any, RaiderError, (ScriptedModelBackend, ReplSession)] =
    for
      backend <- ScriptedModelBackend(
        ModelEvent.Started(attempt),
        ModelEvent.TextDelta(attempt, Head),
        ModelEvent.TextDelta(attempt, Tail),
        ModelEvent.Finished(attempt, "stop")
      )
      session <- ReplSession.make(backend, BudgetLimits.make())
    yield (backend, session)

  def spec = suite("DSL ergonomics (RAI-018 slice)")(
    test("scout(\"prompt\") is lazy; .run() dispatches exactly once") {
      for
        (backend, session) <- scripted()
        ask = scout("explore")(using session) // construction: no dispatch
        before <- backend.requests
        answer = ask.run()(using session)
        after <- backend.requests
      yield assertTrue(
        ask.prompt == "explore",
        before.isEmpty, // API-03: preview without dispatch
        after.size == 1,
        answer == Head + Tail
      )
    },
    test(".ask() alias and .map() keep one interpreter and stay typed") {
      for
        (backend, session) <- scripted()
        len = scout("verify")(using session).map(_.length).run()(using session)
        viaAlias = scout("verify")(using session).ask()(using session)
        after <- backend.requests
      yield assertTrue(
        len == (Head + Tail).length,
        viaAlias == Head + Tail,
        after.size == 2
      )
    },
    test(
      ".start() background + paramless await and await() are the same outcome"
    ) {
      for
        (backend, session) <- scripted()
        job = scout("bg")(using session).start()(using session)
        a = job.await(using session)
        b = job.await()(using session)
        snap <- session.jobs.snapshotAny(job)
      yield assertTrue(
        a == Head + Tail,
        b == a, // idempotent await (JOB-03)
        snap.status == JobStatus.Succeeded
      )
    },
    test("all(scout, reviewer).ask(\"p\") — both agents, same prompt") {
      for
        (backend, session) <- scripted()
        (x, y) = all(scout, reviewer).ask("verify")(using session)
        reqs <- backend.requests
      yield assertTrue(
        x == Head + Tail,
        y == Head + Tail,
        reqs.size == 2,
        reqs.forall(_.messages == List(RequestMessage("user", "verify")))
      )
    },
    test("all(askA.map(f), askB).run() — typed tuple composition") {
      for
        (backend, session) <- scripted()
        (n, s) = all(
          scout("a")(using session).map(_.length),
          scout("b")(using session)
        ).run()(using session)
      yield assertTrue(n == (Head + Tail).length, s == Head + Tail)
    },
    test("batch(inputs, parallelism)(f).run() — ordered results, bounded") {
      for
        (backend, session) <- scripted()
        results = batch(List("parser", "config", "cli"), parallelism = 2) {
          name => scout(s"explore $name")(using session)
        }.run()(using session)
        reqs <- backend.requests
      yield assertTrue(
        // batch contract: RESULTS keep input order; dispatch/recording order
        // is concurrent under parallelism > 1 and is NOT part of the contract
        results == List(Head + Tail, Head + Tail, Head + Tail),
        reqs.map(_.messages.head.content).toSet ==
          Set("explore parser", "explore config", "explore cli"),
        reqs.size == 3
      )
    },
    test("batch.collect(): domain failures become outcomes") {
      // a descriptor whose task fails as a DOMAIN failure (typed RaiderError)
      val failing = new AgentAsk[String](
        AgentRef("boom"),
        "x",
        Task(ZIO.fail(RaiderError.ToolFailed("boom")))
      ) // fails when run
      for
        (backend, session) <- scripted()
        outcomes = batch(List("ok", "bad"), parallelism = 2) { name =>
          if name == "ok" then scout("ok")(using session)
          else failing
        }.collect()(using session)
      yield assertTrue(outcomes.size == 2)
    },
    test("openSession: multi-turn continuity — turn 2 sees turn 1 messages") {
      for
        (backend, session) <- scripted()
        chat = openSession(AgentRef("worker"))(using session)
        first = chat.ask("study parser")
        second = chat.ask("now fix the edge case")
        reqs <- backend.requests
      yield assertTrue(
        first == Head + Tail,
        second == Head + Tail,
        // turn-1 continuity: request 2 carries user1, assistant1, user2
        reqs(1).messages == List(
          RequestMessage("user", "study parser"),
          RequestMessage("assistant", Head + Tail),
          RequestMessage("user", "now fix the edge case")
        ),
        chat.messages.size == 4
      )
    },
    test("laziness of all/batch: construction dispatches nothing") {
      for
        (backend, session) <- scripted()
        _ = all(AgentRef("a"), AgentRef("b"))
        _ = all(scout("x")(using session), scout("y")(using session))
        _ = batch(List(1, 2, 3))(i => scout(s"n$i")(using session))
        before <- backend.requests
      yield assertTrue(before.isEmpty)
    }
  ) @@ TestAspect.sequential
