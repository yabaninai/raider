package raider.repl.facade

import raider.core.*
import raider.runtime.budget.BudgetLimits
import raider.runtime.jobs.{JobHandle, JobStatus}
import raider.runtime.mailbox.MailboxState
import zio.stream.ZStream
import zio.test.{TestAspect, assertTrue, test, ZIOSpecDefault, suite}
import zio.{Duration as ZDuration, Promise, Ref, UIO, ZIO, Scope}

import java.util.concurrent.atomic.AtomicInteger

/** RAI-132 behavioral fixtures for the REPL facade steering surface (STR-01,
  * STR-02) on deterministic scripted backends — no network, no live providers,
  * no real spend.
  *
  * Determinism rules (the DelegationSteeringSpec discipline, RAI-074):
  *   - the turn boundary is REAL but CONTROLLED: the scripted backend parks on
  *     a promise inside turn 1's model call, so the facade sends happen at a
  *     known phase (Draining window open, job mid-turn) without sleeps;
  *   - `SteerChannel.sealedP` is the deterministic post-terminal barrier (no
  *     polling): after it resolves, the mailbox is Closed and every receipt is
  *     resolved (applied or expired);
  *   - the facade runs exactly as the product does — blocking extension methods
  *     on `using ReplSession` — safe here because the scripted wire is gated by
  *     the test, never actually waited blindly.
  *
  * The fixtures consume the frozen runtime mailbox semantics (RAI-065/074);
  * they pin the FACADE wiring: queued→applied receipts, the `owner steering:`
  * section in the next model-turn input, and the typed refusals.
  */
object SteeringSpec extends ZIOSpecDefault:

  /** Deterministic multi-turn scripted backend: model call 1 PARKS on `gate`
    * (the test owns the turn boundary), later calls answer immediately. Every
    * ModelRequest is recorded, indexed by call number; the final text of call n
    * is `<answer>-n`. `fixtureMode` is a first-class marker — scripted output
    * is never mistaken for a live model answer.
    */
  private final class ParkTurnBackend(
      answer: String,
      gate: Promise[Nothing, Unit],
      parked: Promise[Nothing, Unit],
      requests: Ref[List[ModelRequest]]
  ) extends ModelBackend:

    val fixtureMode: Boolean = true

    private val calls = AtomicInteger(0)

    def capabilities: ModelCapabilities =
      ModelCapabilities(
        CapabilityStatus.Supported,
        CapabilityStatus.Supported,
        CapabilityStatus.Unknown
      )

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap {
        requests.update(_ :+ input) *>
          ZIO.succeed(calls.incrementAndGet()).map { n =>
            val park: ZStream[Any, Nothing, Unit] =
              if n == 1 then
                ZStream.fromZIO(parked.succeed(()).unit) ++
                  ZStream.fromZIO(gate.await)
              else ZStream.empty
            park.drain ++ ZStream(
              ModelEvent.Started(AttemptId("str")),
              ModelEvent.TextDelta(AttemptId("str"), s"$answer-$n"),
              ModelEvent.Finished(AttemptId("str"), "stop")
            )
          }
      }

  private final class Wired(
      val backend: ParkTurnBackend,
      val session: ReplSession,
      val releaseTurn1: UIO[Unit],
      val turn1Parked: UIO[Unit],
      val requests: UIO[List[ModelRequest]]
  )

  private def wire(answer: String): ZIO[Scope, RaiderError, Wired] =
    for
      gate <- Promise.make[Nothing, Unit]
      parked <- Promise.make[Nothing, Unit]
      requests <- Ref.make(List.empty[ModelRequest])
      backend = ParkTurnBackend(answer, gate, parked, requests)
      session <- ReplSession.make(backend, BudgetLimits.make())
    yield Wired(
      backend,
      session,
      gate.succeed(()).unit,
      parked.await.unit,
      requests.get
    )

  private def channelOf(handle: JobHandle[?]): SteerChannel =
    Option(FacadeOps.steer.get(handle.id)).getOrElse(
      throw new AssertionError(s"no steering channel for ${handle.id.value}")
    )

  /** Steering ids applied by the job in `owner steering:` sections (with
    * multiplicity — a duplicate application would show up here).
    */
  private def appliedSteeringSections(
      reqs: List[ModelRequest]
  ): List[String] =
    reqs.flatMap(
      _.messages.collect {
        case RequestMessage("user", content)
            if content.startsWith("owner steering:") =>
          content
      }
    )

  def spec = suite("SteeringSpec (RAI-132: facade steering over the mailbox)")(
    test(
      "STR-01: start → steer mid-run → queued then applied → the steering text is in the NEXT model-turn input → await reflects the steered answer (pinned)"
    ) {
      ZIO.scoped {
        for
          w <- wire("found")
          handle = AgentRef("scout").start("find x")(using w.session)
          _ <- w.turn1Parked // deterministically INSIDE turn 1
          receipt = handle.send("also check y")(using w.session)
          queued = receipt match
            case q: SteerReceipt.Queued => Some(q)
            case _                      => None
          midState <- channelOf(handle).mailboxP.await.flatMap(_.state)
          _ <- w.releaseTurn1
          settled <- queued match
            case Some(q) => q.settled.await
            case None    => ZIO.succeed(None)
          _ <- channelOf(handle).sealedP.await // terminal watcher done
          reqs <- w.requests
          state <- channelOf(handle).mailboxP.await.flatMap(_.state)
          pending <- channelOf(handle).mailboxP.await.flatMap(_.pendingCount)
          result = handle.await()(using w.session)
        yield assertTrue(
          w.backend.fixtureMode,
          queued.isDefined,
          queued.exists(q => q.text == "also check y" && !q.duplicate),
          queued.exists(_.jobId == handle.id),
          queued.exists(_.msgId.value.startsWith("st-")),
          // parked mid-turn ⇒ the Draining window was open: the send queued
          // for the NEXT turn (never mid-run, never into the in-flight call)
          midState == MailboxState.Draining,
          // the QUEUED receipt became APPLIED at the turn boundary
          settled.contains(
            SteerReceipt.Applied(
              handle.id,
              queued.get.msgId,
              "also check y"
            )
          ),
          // turn 1 input is exactly the initial prompt (nothing sent yet)
          reqs.size == 2,
          reqs.head.messages.last == RequestMessage("user", "find x"),
          reqs.head.messages.count(_.content.contains("owner steering:")) == 0,
          // turn 2 carries the conversation forward and appends ONE user
          // message with the owner steering section, id visible (pinned form)
          reqs(1).messages.contains(RequestMessage("assistant", "found-1")),
          appliedSteeringSections(reqs) ==
            List(
              s"owner steering:\n- [${queued.get.msgId.value}] also check y"
            ),
          // the job settles on the first empty boundary; the mailbox is sealed
          state == MailboxState.Closed,
          pending == 0,
          // the awaiting caller observes the POST-steering answer
          result == "found-2"
        )
      }
    } @@ TestAspect.withLiveClock,
    test(
      "STR-01 (no steering): an unsteered job behaves exactly as before — one model call, no steering section, receipts stay absent"
    ) {
      ZIO.scoped {
        for
          w <- wire("plain")
          handle = AgentRef("worker").start("bg")(using w.session)
          _ <- w.turn1Parked
          _ <- w.releaseTurn1
          _ = handle.await()(using w.session)
          _ <- channelOf(handle).sealedP.await
          reqs <- w.requests
        yield assertTrue(
          reqs.size == 1,
          reqs.head.messages.last == RequestMessage("user", "bg"),
          appliedSteeringSections(reqs).isEmpty
        )
      }
    } @@ TestAspect.withLiveClock,
    test(
      "STR-02: send to a FINISHED job — typed TerminalJob(Succeeded) receipt, facade call is total"
    ) {
      ZIO.scoped {
        for
          w <- wire("done")
          handle = AgentRef("scout").start("bg")(using w.session)
          _ <- w.turn1Parked
          _ <- w.releaseTurn1
          _ = handle.await()(using w.session)
          receipt = handle.send("late")(using w.session)
        yield assertTrue(
          receipt.text == "late",
          receipt match
            case SteerReceipt.Rejected(
                  id,
                  _,
                  _,
                  raider.runtime.mailbox.MailboxError.TerminalJob(jid, status)
                ) =>
              id == handle.id && jid == handle.id &&
              status == JobStatus.Succeeded
            case _ => false
        )
      }
    } @@ TestAspect.withLiveClock,
    test(
      "STR-02: send to a CANCELLED job — typed TerminalJob(Cancelled) receipt; after the seal the mailbox refuses too"
    ) {
      ZIO.scoped {
        for
          w <- wire("never")
          handle = AgentRef("scout").start("bg")(using w.session)
          _ <- w.turn1Parked
          cancelled = handle.cancel()(using w.session)
          _ <- w.session.jobs.awaitSettled(handle)
          receipt = handle.send("too late")(using w.session)
          _ <- channelOf(handle).sealedP.await
          afterSeal = handle.send("still too late")(using w.session)
        yield assertTrue(
          cancelled,
          receipt match
            case SteerReceipt.Rejected(
                  _,
                  _,
                  _,
                  raider.runtime.mailbox.MailboxError.TerminalJob(_, status)
                ) =>
              status == JobStatus.Cancelled
            case _ => false,
          afterSeal match
            case _: SteerReceipt.Rejected => true
            case _                        => false
        )
      }
    } @@ TestAspect.withLiveClock,
    test(
      "STR-02: send to an UNKNOWN job — typed UnknownJob receipt (no dispatch)"
    ) {
      for receipt <- FacadeOps.sendToJob(JobId("j_absent"), "x")
      yield assertTrue(
        receipt match
          case SteerReceipt.Rejected(
                id,
                _,
                text,
                raider.runtime.mailbox.MailboxError.UnknownJob(unknown)
              ) =>
            id.value == "j_absent" && unknown.value == "j_absent" &&
            text == "x"
          case _ => false
      )
    },
    test(
      "STR-02: overflow is the typed Full refusal (bounded mailbox, parity with the frozen runtime default)"
    ) {
      ZIO.scoped {
        for
          w <- wire("slow")
          handle = AgentRef("scout").start("bg")(using w.session)
          _ <- w.turn1Parked
          acked = (0 until SteerChannel.Capacity)
            .map(i => handle.send(s"m$i")(using w.session))
          overflow = handle.send("one too many")(using w.session)
          _ <- w.releaseTurn1
          _ = handle.await()(using w.session)
          _ <- channelOf(handle).sealedP.await
        yield assertTrue(
          acked.forall(_.isInstanceOf[SteerReceipt.Queued]),
          acked.forall(_.msgId.value.startsWith("st-")),
          overflow match
            case SteerReceipt.Rejected(
                  _,
                  _,
                  _,
                  raider.runtime.mailbox.MailboxError
                    .Full(jid, capacity, pendingAtRefusal)
                ) =>
              jid == handle.id && capacity == SteerChannel.Capacity &&
              pendingAtRefusal == SteerChannel.Capacity
            case _ => false
        )
      }
    } @@ TestAspect.withLiveClock,
    test(
      ":jobs marker: isSteerable is true while the job runs, false once terminal"
    ) {
      ZIO.scoped {
        for
          w <- wire("mark")
          handle = AgentRef("scout").start("bg")(using w.session)
          _ <- w.turn1Parked
          running = FacadeOps.isSteerable(handle.id, JobStatus.Running)
          _ <- w.releaseTurn1
          _ = handle.await()(using w.session)
          done = FacadeOps.isSteerable(handle.id, JobStatus.Succeeded)
        yield assertTrue(running, !done)
      }
    } @@ TestAspect.withLiveClock,
    test(
      "receipt rendering: queued/applied/rejected lines are honest one-liners (pinned shapes)"
    ) {
      ZIO.scoped {
        for
          w <- wire("render")
          handle = AgentRef("scout").start("bg")(using w.session)
          _ <- w.turn1Parked
          receipt = handle.send("steer me")(using w.session)
          queuedRender = SteerReceipt.render(receipt)
          unknown <- FacadeOps.sendToJob(JobId("j_absent"), "x")
          unknownRender = SteerReceipt.render(unknown)
          _ <- w.releaseTurn1
          _ = handle.await()(using w.session)
          _ <- channelOf(handle).sealedP.await
          appliedRender = receipt match
            case q: SteerReceipt.Queued =>
              SteerReceipt.render(
                SteerReceipt.Applied(handle.id, q.msgId, q.text)
              )
            case other => SteerReceipt.render(other)
        yield assertTrue(
          queuedRender.startsWith("queued "),
          queuedRender.contains("steer me"),
          queuedRender.contains("applies at the next model turn"),
          unknownRender.startsWith("rejected "),
          unknownRender.contains("unknown job j_absent"),
          appliedRender.startsWith("applied "),
          appliedRender.contains("included in the model input")
        )
      }
    } @@ TestAspect.withLiveClock
  ) @@ TestAspect.sequential

end SteeringSpec
