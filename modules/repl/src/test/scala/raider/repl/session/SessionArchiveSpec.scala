package raider.repl.session

import raider.core.{AttemptId, ModelEvent, RaiderError, RequestMessage}
import raider.repl.facade.{AgentRef, ReplSession}
import raider.runtime.budget.BudgetLimits
import raider.runtime.conversation.{Conversation, ConversationBounds, TurnId}
import raider.testkit.ScriptedSequenceBackend
import zio.{Duration, ZIO}
import zio.test._

import java.nio.file.{Files, Path}
import scala.util.Try

/** REPL bridge tests for conversation sessions (RAI-036, SESSION-01/02/03):
  * `ConversationRef`/`SessionArchive` must ride the ONE facade agent path
  * (same AgentLoop as ask/openSession — no second runtime), keep multi-turn
  * continuity, and honor the archive honesty rules (reopen executes nothing,
  * oversized transcripts refuse typed before any turn).
  *
  * Deterministic: ScriptedSequenceBackend (fixtureMode), temp dirs, blocking
  * calls are safe because the scripted wire settles instantly (same
  * discipline as FacadeOpsSpec).
  */
object SessionArchiveSpec extends ZIOSpecDefault:

  /** One scripted provider turn: Started → TextDelta(reply) → Finished. */
  private def turn(attempt: String, reply: String): Vector[ModelEvent] =
    Vector(
      ModelEvent.Started(AttemptId(attempt)),
      ModelEvent.TextDelta(AttemptId(attempt), reply),
      ModelEvent.Finished(AttemptId(attempt), "stop")
    )

  private def rig(
      scripts: Vector[ModelEvent]*
  ): ZIO[Any, RaiderError, (ScriptedSequenceBackend, ReplSession)] =
    for
      backend <- ScriptedSequenceBackend(scripts*)
      session <- ReplSession.make(backend, BudgetLimits.make())
    yield (backend, session)

  private def withTempDir[A](f: Path => ZIO[Any, Throwable, A]): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt(Files.createTempDirectory("raider-repl-sess"))
    )(p =>
      ZIO
        .attempt {
          import scala.jdk.CollectionConverters.*
          Files
            .walk(p)
            .sorted(java.util.Comparator.reverseOrder[Path]())
            .iterator()
            .asScala
            .foreach(Files.delete(_))
        }
        .orDie
    )(f)

  private val user = Conversation.UserRole
  private val assistant = Conversation.AssistantRole

  def spec = suite("ConversationRef and SessionArchive (RAI-036 REPL bridge)")(

    test("open dispatches nothing; turns ride the one facade path with continuity") {
      for
        (backend, session) <- rig(turn("a1", "first reply"), turn("a2", "second reply"))
        ref = ConversationRef.open(AgentRef("demo"))(using session)
        reqs0 <- backend.requests
        reply1 = ref.ask("question one")
        reqs1 <- backend.requests
        reply2 = ref.ask("question two")
        reqs2 <- backend.requests
        messages = ref.messages
      yield assertTrue(
        reqs0.isEmpty,
        reply1 == "first reply",
        reply2 == "second reply",
        reqs1.size == 1,
        reqs2.size == 2,
        // the one facade path rewrites model "scripted" per agent (RAI-010.a)
        reqs1(0).model == "scripted:demo",
        // SESSION-01 continuity: the second turn sees the first turn's pair
        reqs2(1).messages.takeRight(3) == List(
          RequestMessage(user, "question one"),
          RequestMessage(assistant, "first reply"),
          RequestMessage(user, "question two")
        ),
        messages.map(_.content) == List(
          "question one",
          "first reply",
          "question two",
          "second reply"
        )
      )
    },

    test("start returns a receipt immediately; await blocks for the reply") {
      for
        (backend, session) <- rig(turn("b1", "background reply"))
        ref = ConversationRef.open(AgentRef("demo"))(using session)
        started = ref.start("background question")
        reply = started.await()
        unknownCancel = ref.cancel(TurnId("nope-1"))
      yield assertTrue(
        started.id.value.nonEmpty,
        reply == "background reply",
        !unknownCancel
      )
    },

    test("archive persists the transcript; reopen seeds history without executing") {
      withTempDir { base =>
        for
          (backend, session) <- rig(
            turn("c1", "alpha"),
            turn("c2", "beta"),
            turn("c3", "gamma")
          )
          ref = ConversationRef.open(AgentRef("demo"), label = Some("study"))(using session)
          _ = ref.ask("one")
          _ = ref.ask("two")
          path = ref.archive(base)
          listed = SessionArchive.list(base).map(_.value)
          loaded = SessionArchive.load(ref.conversation.sessionId.value, base)
          reopened = SessionArchive.reopen(
            ref.conversation.sessionId.value,
            AgentRef("demo2"),
            base = base
          )(using session)
          reqsBefore <- backend.requests
          reply3 = reopened.ask("three")
          reqsAfter <- backend.requests
          messages = reopened.messages
        yield assertTrue(
          path.getFileName.toString == s"${ref.conversation.sessionId.value}.json",
          listed.contains(ref.conversation.sessionId.value),
          loaded.model == "scripted",
          loaded.label == Some("study"),
          loaded.turns.map(_.prompt) == List("one", "two"),
          loaded.turns.map(_.reply) == List("alpha", "beta"),
          // SESSION-02: reopen executes NOTHING until the next explicit turn
          reqsBefore.size == 2,
          // durable identity preserved, root NEVER reused (fresh root)
          reopened.conversation.sessionId == ref.conversation.sessionId,
          reopened.conversation.root != ref.conversation.root,
          reply3 == "gamma",
          // same session/loop, but the reopened agent rewrites the model name
          reqsAfter.size == 3,
          reqsAfter(2).model == "scripted:demo2",
          reqsAfter(2).messages.takeRight(5) == List(
            RequestMessage(user, "one"),
            RequestMessage(assistant, "alpha"),
            RequestMessage(user, "two"),
            RequestMessage(assistant, "beta"),
            RequestMessage(user, "three")
          ),
          messages.size == 6
        )
      }
    },

    test("reopen with tight bounds refuses the oversized seed typed (RA-LBUDGET)") {
      withTempDir { base =>
        for
          (backend, session) <- rig(turn("d1", "a reply long enough to matter"))
          ref = ConversationRef.open(AgentRef("demo"))(using session)
          _ = ref.ask("a reasonably long prompt that will exceed the tiny bound")
          _ = ref.archive(base)
          tight = ConversationBounds
            .make(maxTurns = 1, maxHistoryChars = 8, queueCapacity = 1)
            .toOption
            .get
          outcome = Try(
            SessionArchive.reopen(
              ref.conversation.sessionId.value,
              AgentRef("demo"),
              base = base,
              bounds = tight
            )(using session)
          ).toEither
          reqs <- backend.requests
        yield assertTrue(
          outcome.isLeft,
          outcome.swap.exists(_.isInstanceOf[RaiderError.LocalBudgetExceeded]),
          // the refusal happens BEFORE any provider turn (no spend, no restart)
          reqs.size == 1
        )
      }
    },

    test("close seals the conversation; later asks are refused typed (RA-INP)") {
      for
        (backend, session) <- rig(turn("e1", "only reply"))
        ref = ConversationRef.open(AgentRef("demo"))(using session)
        reply = ref.ask("final question")
        _ = ref.close()
        refused = Try(ref.ask("after close")).toEither
        _ = ref.close() // idempotent
      yield assertTrue(
        reply == "only reply",
        refused.isLeft,
        refused.swap.exists(_.isInstanceOf[RaiderError.InputValidation])
      )
    }
  ) @@ TestAspect.timeout(Duration.fromSeconds(30))
