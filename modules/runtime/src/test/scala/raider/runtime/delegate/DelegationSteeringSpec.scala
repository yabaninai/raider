package raider.runtime.delegate

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.jobs.{JobManager, JobStatus}
import raider.runtime.mailbox.{Ack, MailboxError, MailboxState}
import zio.stream.ZStream
import zio.test.{TestAspect, assertTrue, test, ZIOSpecDefault, suite}
import zio.{Duration as ZDuration, Promise, Ref, Scope, UIO, ZIO}

import java.util.concurrent.atomic.AtomicInteger
import scala.util.matching.Regex

/** RAI-074 acceptance (SENDW-01..03): the Mailbox wiring into DelegationToolset
  * — per-child steering mailbox on fork, the owner/REPL channel `sendToChild`,
  * and turn-boundary application.
  *
  * Determinism rules (same discipline as MailboxSpec/DelegationEmissionSpec):
  *   - the turn boundary is REAL but CONTROLLED: the scripted child parks on a
  *     promise inside turn 1's model call, so the owner's sends happen at a
  *     known phase (Draining window open, child mid-turn) without sleeps;
  *   - `awaitChildMailboxSealed` is the deterministic post-terminal barrier (no
  *     polling): after it resolves, sends are TerminalJob refusals and a final
  *     drain is stable;
  *   - SENDW-03 asserts interleaving-agnostic CONSERVATION invariants (every
  *     msgId is typed-acked or typed-refused; every acked id is applied or
  *     final-drained exactly once), never a particular interleaving;
  *   - real durations appear only as generous wall caps (15s invocations, live
  *     clock) that convert a defective hang into a typed failure.
  */
object DelegationSteeringSpec extends ZIOSpecDefault:

  private val root = RootId("sendw-root")

  private def lims: BudgetLimits =
    BudgetLimits
      .make(
        maxConcurrentLlm = 1,
        maxConcurrentTools = 2,
        maxAttempts = 16,
        maxChildren = 4
      )
      .fold(e => throw e, identity)

  /** Deterministic multi-turn scripted child: model call 1 PARKS on `gate` (the
    * test owns the turn boundary), later calls answer immediately. Every
    * ModelRequest is recorded, indexed by call number; the final text of call n
    * is `<answer>-n`.
    */
  private final class ParkTurnBackend(
      answer: String,
      gate: Promise[Nothing, Unit],
      parked: Promise[Nothing, Unit],
      requests: Ref[List[ModelRequest]]
  ) extends ModelBackend:

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
              ModelEvent.Started(AttemptId("stw")),
              ModelEvent.TextDelta(AttemptId("stw"), s"$answer-$n"),
              ModelEvent.Finished(AttemptId("stw"), "stop")
            )
          }
      }

  private final class Wired(
      val registry: ToolRegistry,
      val toolset: DelegationToolset,
      val releaseTurn1: UIO[Unit],
      val turn1Parked: UIO[Unit],
      val requests: UIO[List[ModelRequest]]
  )

  private def wire(answer: String): ZIO[Any, Any, Wired] =
    for
      jobs <- JobManager.make()
      adm <- Admission.make(Right(lims))
      gate <- Promise.make[Nothing, Unit]
      parked <- Promise.make[Nothing, Unit]
      requests <- Ref.make(List.empty[ModelRequest])
      backend = new ParkTurnBackend(answer, gate, parked, requests)
      toolset <- ZIO.fromEither(
        DelegationToolset.make(
          jobs,
          backend,
          adm,
          lims,
          ToolRegistry.empty,
          Set("scout"),
          root
        )
      )
      registry = toolset
        .augment(ToolRegistry.empty)
        .fold(e => throw e, identity)
    yield new Wired(
      registry,
      toolset,
      gate.succeed(()).unit,
      parked.await.unit,
      requests.get
    )

  private def childIdOf(feedback: String): String =
    """"child_id":"(j_\d+)"""".r
      .findFirstMatchIn(feedback)
      .map(_.group(1))
      .getOrElse("j_missing")

  private def toolOf(w: Wired, name: String): Tool =
    w.registry
      .lookup(name)
      .getOrElse(throw new AssertionError(s"$name tool missing"))

  private def doDelegate(w: Wired): ZIO[Any, Any, (String, String)] =
    for
      feedback <- ZIO
        .scoped(
          toolOf(w, "delegate").invoke(
            """{"agent":"scout","input":"find x"}"""
          )
        )
        .timeout(ZDuration.fromSeconds(15))
      fb = feedback.getOrElse("")
    yield (fb, childIdOf(fb))

  private def doAwait(w: Wired, childId: String): ZIO[Any, Any, String] =
    ZIO
      .scoped(
        toolOf(w, "await_agent").invoke(s"""{"child_id":"$childId"}""")
      )
      .timeout(ZDuration.fromSeconds(15))
      .map(_.getOrElse(""))

  /** msgIds applied by the child in `owner steering:` sections (with
    * multiplicity — a duplicate application must show up here).
    */
  private val steeringId: Regex = """- \[(r-\d+)\]""".r

  private def appliedSteeringIds(reqs: List[ModelRequest]): List[String] =
    reqs.flatMap(
      _.messages
        .collect {
          case RequestMessage("user", content)
              if content.startsWith("owner steering:") =>
            steeringId.findAllMatchIn(content).map(_.group(1)).toList
        }
        .flatten
    )

  def spec = suite("DelegationToolset steering wiring (RAI-074, SENDW-01..03)")(
    test(
      "SENDW-01: send → turn-boundary drain → the text lands in the child's NEXT prompt as the owner steering section"
    ) {
      for
        w <- wire("found")
        (feedback, childId) <- doDelegate(w)
        _ <- w.turn1Parked // child is deterministically INSIDE turn 1
        // parked mid-turn ⇒ Draining window open: the send queues for the
        // NEXT turn (never mid-run, never into the in-flight model call)
        ack <- w.toolset.sendToChild(childId, "s-1", "also check y")
        midState <- w.toolset.childMailbox(childId) match
          case Some(m) => m.state
          case None    => ZIO.fail("child mailbox missing")
        _ <- w.releaseTurn1
        _ <- w.toolset.awaitSettled(childId)
        _ <- w.toolset.awaitChildMailboxSealed(childId)
        reqs <- w.requests
        finalState <- w.toolset.childMailbox(childId) match
          case Some(m) => m.state
          case None    => ZIO.fail("child mailbox missing")
        pending <- w.toolset.childMailbox(childId) match
          case Some(m) => m.pendingCount
          case None    => ZIO.fail("child mailbox missing")
        awaitFeedback <- doAwait(w, childId)
      yield assertTrue(
        feedback.contains(s""""child_id":"$childId""""),
        ack == Ack(MessageId("s-1"), duplicate = false),
        midState == MailboxState.Draining,
        // turn 1 prompt is exactly the delegated input (nothing sent yet)
        reqs.size == 2,
        reqs.head.messages == List(RequestMessage("user", "find x")),
        // turn 2 carries the conversation forward and appends ONE user
        // message with the owner steering section, arrival order, id visible
        reqs(1).messages.contains(RequestMessage("assistant", "found-1")),
        reqs(1).messages.lastOption.exists(m =>
          m.role == "user" &&
            m.content == "owner steering:\n- [s-1] also check y"
        ),
        finalState == MailboxState.Closed,
        pending == 0,
        // the parent observes the POST-steering answer
        awaitFeedback.contains("found-2")
      )
    } @@ TestAspect.withLiveClock,
    test(
      "SENDW-02: overflow is the typed Full refusal (idempotent re-send still acked at capacity); a sealed child refuses TerminalJob; unknown child refuses UnknownJob"
    ) {
      val capacity = DelegationToolset.DefaultMailboxCapacity
      for
        w <- wire("found")
        (feedback, childId) <- doDelegate(w)
        _ <- w.turn1Parked
        acked <- ZIO.foreach(0 until capacity)(i =>
          w.toolset.sendToChild(childId, s"f-$i", s"m$i")
        )
        overflow <- w.toolset
          .sendToChild(childId, "f-x", "one too many")
          .either
        dupAtCapacity <- w.toolset.sendToChild(childId, "f-0", "m0")
        unknown <- w.toolset.sendToChild("j_absent", "u-1", "x").either
        _ <- w.releaseTurn1
        _ <- w.toolset.awaitSettled(childId)
        _ <- w.toolset.awaitChildMailboxSealed(childId)
        terminal <- w.toolset.sendToChild(childId, "t-1", "late").either
        pending <- w.toolset.childMailbox(childId) match
          case Some(m) => m.pendingCount
          case None    => ZIO.fail("child mailbox missing")
      yield assertTrue(
        feedback.contains(s""""child_id":"$childId""""),
        acked.size == capacity,
        acked.forall(ack => !ack.duplicate),
        overflow == Left(
          MailboxError.Full(JobId(childId), capacity, capacity)
        ),
        dupAtCapacity == Ack(MessageId("f-0"), duplicate = true),
        unknown == Left(MailboxError.UnknownJob(JobId("j_absent"))),
        terminal == Left(
          MailboxError.TerminalJob(JobId(childId), JobStatus.Succeeded)
        ),
        pending == 0
      )
    } @@ TestAspect.withLiveClock,
    test(
      "SENDW-03: send/cancel races conserve every msgId — typed ack or typed refusal, acked ids delivered exactly once, never lost, never duplicated"
    ) {
      val n = 64
      for
        w <- wire("found")
        (feedback, childId) <- doDelegate(w)
        // parked mid-turn 1; this gate is NEVER released — the OOB cancel
        // interrupts the child inside its in-flight model call
        _ <- w.turn1Parked
        outcomes <- Ref.make(Map.empty[String, List[Either[MailboxError, Ack]]])
        senders <- ZIO.foreach(0 until n) { i =>
          ZIO
            .foreachDiscard(1 to 3) { _ =>
              w.toolset
                .sendToChild(childId, s"r-$i", s"race-$i")
                .either
                .flatMap(o =>
                  outcomes.update(m =>
                    m.updatedWith(s"r-$i")(l => Some(l.getOrElse(Nil) :+ o))
                  )
                )
            }
            .fork
        }
        _ <- ZIO.yieldNow.repeatN(64) // scheduling relief, not an assertion
        _ <- w.toolset.cancelOutOfBand(childId)
        _ <- ZIO.foreach(senders)(_.join)
        _ <- w.toolset.awaitChildMailboxSealed(childId)
        recorded <- outcomes.get
        reqs <- w.requests
        finalDrained <- w.toolset.childMailbox(childId) match
          case Some(m) => m.drainOnTurnBoundary.map(_.toList)
          case None    => ZIO.fail("child mailbox missing")
        state <- w.toolset.childMailbox(childId) match
          case Some(m) => m.state
          case None    => ZIO.fail("child mailbox missing")
        late <- w.toolset.sendToChild(childId, "late-1", "x").either
        allIds = (0 until n).map(i => s"r-$i").toSet
        ackedIds = recorded.collect {
          case (id, os) if os.exists(_.isRight) =>
            id
        }.toSet
        refusedIds = recorded.collect {
          case (id, os) if os.forall(_.isLeft) =>
            id
        }.toSet
        delivered =
          appliedSteeringIds(reqs) ++ finalDrained.map(_.id.value)
        deliveredCounts = delivered.groupBy(identity).view.mapValues(_.size)
      yield assertTrue(
        feedback.contains(s""""child_id":"$childId""""),
        // every racing send produced a typed outcome, each id attempted 3x
        recorded.keySet == allIds,
        recorded.values.forall(_.size == 3),
        // conservation: acked XOR refused partitions the sent ids
        ackedIds.union(refusedIds) == allIds,
        ackedIds.intersect(refusedIds).isEmpty,
        // every acked id delivered EXACTLY once; nothing delivered un-acked
        deliveredCounts.keySet == ackedIds,
        deliveredCounts.forall { case (_, count) => count == 1 },
        // content integrity of whatever the final drain retrieved
        finalDrained.forall(d =>
          d.content == s"race-${d.id.value.stripPrefix("r-")}"
        ),
        // the sealed mailbox refuses further sends with the REAL status
        state == MailboxState.Closed,
        late == Left(
          MailboxError.TerminalJob(JobId(childId), JobStatus.Cancelled)
        )
      )
    } @@ TestAspect.withLiveClock
  )

end DelegationSteeringSpec
