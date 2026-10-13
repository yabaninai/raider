package raider.runtime.fairness

import raider.core.{RaiderError, RootId}
import raider.runtime.admission.{Admission, SlotKind}
import raider.runtime.budget.{BudgetLimits, CostEstimate, MicroUsd, UsageReport}
import zio.test.{live, *}
import zio.{Duration as ZDuration, Exit as ZExit, Fiber, Promise, Ref, UIO, ZIO}

import java.util.concurrent.atomic.AtomicInteger

/** RAI-128 acceptance: cross-root fairness of the shared Model-slot pool
  * (runtime-contracts §6).
  *
  * FAIR-01: two roots × N requests contend for one Model slot. Requests are
  * forked so that at every release moment two waiters stand in a known park
  * order: the surviving cross-root request (parked first) and the freshly
  * forked request (parked second). A fair FIFO must therefore grant exactly
  * A1,B1,A2,B2,...: no root is starved (each request is granted within ≤ 2
  * queue positions), the declared pool bound is never exceeded, and the run is
  * fully deterministic (ordering links are established by polled observables
  * plus settle sleeps, never by wall-clock guesses; runs ×3 identical).
  *
  * FAIR-02: (a) negative control — the SAME scenario and the SAME pure checker
  * are run against an intentionally unfair test-helper pool (root-priority
  * dispatch); the checker must catch it, proving the acceptance is not a
  * tautology. (b) per-root budget independence — exhausting root A's
  * maxAttempts refuses only A's dispatch (typed RA-LBUDGET) and never blocks
  * root B on the same pool.
  *
  * FAIR-03: both roots admit in full parallel — 0 lost/duplicated tokens,
  * exact ledger sums, no slot leak; the pre-existing BudgetAdmissionSpec stays
  * green (run ×3 alongside this spec).
  */
object CrossRootFairnessSpec extends ZIOSpecDefault:

  private val rootA = RootId("r-fair-a")
  private val rootB = RootId("r-fair-b")

  private def costOf(root: RootId, tag: Int): MicroUsd =
    MicroUsd(tag * 100L + (if root == rootA then 1L else 2L))

  private def limits(
      llm: Int = 1,
      attempts: Int = 16,
      queue: Int = 64
  ): Either[RaiderError, BudgetLimits] =
    BudgetLimits.make(
      maxConcurrentLlm = llm,
      maxConcurrentTools = 1,
      maxChildren = 64,
      maxAttempts = attempts,
      queueCapacity = queue
    )

  private def codeOf[A](exit: ZExit[RaiderError, A]): Option[String] =
    exit match
      case ZExit.Failure(cause) => cause.failures.headOption.map(_.code)
      case ZExit.Success(_)     => None

  // -- shared scenario trace -------------------------------------------------

  private final case class Trace(
      grants: Ref[Vector[(RootId, Int)]],
      peak: AtomicInteger,
      now: AtomicInteger
  )

  private object Trace:
    def make: UIO[Trace] =
      Ref.make(Vector.empty[(RootId, Int)]).map { grants =>
        Trace(grants, new AtomicInteger(0), new AtomicInteger(0))
      }

  /** Recorded as the FIRST effect inside the slot bracket: the grant moment. */
  private def grant(trace: Trace, root: RootId, tag: Int): UIO[Unit] =
    trace.grants.update(_ :+ ((root, tag)))

  private def enterSlot(trace: Trace): UIO[Unit] = ZIO.succeed {
    val c = trace.now.incrementAndGet()
    val _ = trace.peak.accumulateAndGet(c, math.max)
    ()
  }

  /** Runs INSIDE the slot bracket (ensuring/on the slot body) so the in-slot
    * counter can never overlap the next holder's enter.
    */
  private def exitSlot(trace: Trace): UIO[Unit] =
    ZIO.succeed {
      val _ = trace.now.decrementAndGet()
      ()
    }.unit

  // -- pool abstraction: real Admission vs unfair helper ----------------------

  private final case class InFlight(
      root: RootId,
      tag: Int,
      gate: Promise[Nothing, Unit],
      fiber: Fiber[RaiderError, Int]
  )

  /** Minimal dispatch surface the fairness scenario needs; implemented by the
    * REAL Admission pool (FAIR-01/03) and by the intentionally unfair helper
    * (FAIR-02a negative control).
    */
  private trait SlotPool:
    /** Fork one request. On return the request must already be registered as
      * queued, so the scenario can deterministically wait for it.
      */
    def run(root: RootId, tag: Int): ZIO[Any, RaiderError, InFlight]

    /** Wait until `expected` requests are simultaneously inside the admission
      * bracket, then settle so the last entrant reached its park position
      * before the scenario releases the current holder.
      */
    def confirmEntered(expected: Int): ZIO[Any, RaiderError, Unit]

    def usage: UIO[Option[UsageReport]]

  /** The real bounded pool: 1 Model slot, fair FIFO via the bounded ZIO queue
    * (Admission.scala). The body records the grant and then parks on its
    * per-request gate until the scenario opens it (release).
    */
  private final class RealPool(adm: Admission, trace: Trace) extends SlotPool:
    def run(root: RootId, tag: Int): ZIO[Any, RaiderError, InFlight] =
      for
        gate <- Promise.make[Nothing, Unit]
        fiber <- adm
          .withSlot(
            SlotKind.Model,
            root,
            CostEstimate.Priced(costOf(root, tag)),
            None
          ) {
            enterSlot(trace) *> grant(trace, root, tag) *>
              gate.await
                .as((tag, costOf(root, tag)))
                .ensuring(exitSlot(trace))
          }
          .forkDaemon
      yield InFlight(root, tag, gate, fiber)

    def confirmEntered(expected: Int): ZIO[Any, RaiderError, Unit] =
      // `queued` counts in-bracket attempts (holder + parked waiters): when it
      // reaches `expected`, every forked request entered the bracket. The
      // settle sleep then lets the last entrant reach its Queue.take park
      // before any release (repo-standard determinism, cf. BudgetAdmissionSpec).
      adm.queued
        .repeatUntil(_ == expected)
        .timeout(ZDuration.fromSeconds(15))
        .flatMap {
          case Some(_) =>
            ZIO.sleep(ZDuration.fromMillis(5)).unit
          case None =>
            ZIO.fail(
              RaiderError.DeadlineExceeded(
                "FAIR scenario: request never entered the admission bracket"
              )
            )
        }

    def usage: UIO[Option[UsageReport]] =
      adm.budgetLedger.usage.map(Some(_))

  /** FAIR-02a negative control: a bounded single-slot pool with an intentionally
    * UNFAIR dispatch policy — root `priority` always overtakes the FIFO order.
    * Same observable surface as [[RealPool]] so scenario and checker run
    * unchanged. Registration is synchronous inside `run` (policy dispatch needs
    * no park observation), so `confirmEntered` needs no bracket polling.
    */
  private final class UnfairPool private (
      priority: RootId,
      trace: Trace,
      state: Ref[(Boolean, Vector[(RootId, Promise[Nothing, Unit])])]
  ) extends SlotPool:

    /** Grant the single permit: the priority root's earliest waiter if present,
      * otherwise the FIFO head — and only when the permit is free.
      */
    private val dispatch: UIO[Unit] =
      state.modify {
        case (free, waiters) =>
          if !free then (None, (free, waiters))
          else
            waiters.find(_._1 == priority).orElse(waiters.headOption) match
              case None => (None, (true, waiters))
              case Some((_, p)) =>
                (Some(p), (false, waiters.filterNot(_._2 == p)))
      }.flatMap {
        case Some(p) => p.succeed(()).unit
        case None    => ZIO.unit
      }

    private val releasePermit: UIO[Unit] =
      state.update { case (_, waiters) => (true, waiters) } *> dispatch

    def run(root: RootId, tag: Int): ZIO[Any, RaiderError, InFlight] =
      for
        gate <- Promise.make[Nothing, Unit]
        unlock <- Promise.make[Nothing, Unit]
        _ <- state.update { case (free, waiters) =>
          (free, waiters :+ ((root, unlock)))
        }
        _ <- dispatch // synchronous registration + policy dispatch
        fiber <- (unlock.await *>
          enterSlot(trace) *>
          grant(trace, root, tag) *>
          gate.await
            .as(tag)
            .ensuring(exitSlot(trace)) <* releasePermit).forkDaemon
      yield InFlight(root, tag, gate, fiber)

    def confirmEntered(expected: Int): ZIO[Any, RaiderError, Unit] =
      // registration is synchronous in `run` (policy dispatch, no park race):
      // the settle sleep only keeps the timing shape identical to RealPool.
      ZIO.sleep(ZDuration.fromMillis(5)).unit

    def usage: UIO[Option[UsageReport]] = ZIO.succeed(None)

  private object UnfairPool:
    def make(
        priority: RootId,
        trace: Trace
    ): ZIO[Any, RaiderError, UnfairPool] =
      Ref
        .make((true, Vector.empty[(RootId, Promise[Nothing, Unit])]))
        .map(new UnfairPool(priority, trace, _))

  // -- deterministic contention scenario --------------------------------------

  private final case class ScenarioResult(
      grantLog: Vector[(RootId, Int)],
      peakConcurrent: Int,
      usage: Option[UsageReport]
  )

  /** Request number m = (rootOf(m), tagOf(m)): roots alternate per request
    * (A1,B1,A2,B2,...), so under fair FIFO the grant order equals the request
    * order and alternates between the two roots.
    */
  private def rootOf(requestNumber: Int): RootId =
    if requestNumber % 2 == 1 then rootA else rootB

  private def tagOf(requestNumber: Int): Int = (requestNumber + 1) / 2

  private def formatGrant(g: (RootId, Int)): String = s"${g._1.value}#${g._2}"

  private def waitGrants(trace: Trace, n: Int): ZIO[Any, RaiderError, Unit] =
    trace.grants.get
      .repeatUntil(_.size >= n)
      .timeout(ZDuration.fromSeconds(15))
      .flatMap {
        case Some(_) => ZIO.unit
        case None =>
          ZIO.fail(
            RaiderError.DeadlineExceeded(
              "FAIR scenario: grant progress stalled (deadlock or lost grant)"
            )
          )
      }

  private def release(f: InFlight): ZIO[Any, RaiderError, Unit] =
    f.gate.succeed(()).unit *> f.fiber.join.unit

  private def forkNext(
      pool: SlotPool,
      index: Ref[Map[(RootId, Int), InFlight]],
      counter: Ref[Int]
  ): ZIO[Any, RaiderError, Unit] =
    for
      n <- counter.updateAndGet(_ + 1)
      f <- pool.run(rootOf(n), tagOf(n))
      _ <- index.update(_ + ((rootOf(n), tagOf(n)) -> f))
    yield ()

  private def holderOf(
      index: Ref[Map[(RootId, Int), InFlight]],
      g: (RootId, Int)
  ): ZIO[Any, RaiderError, InFlight] =
    index.get.flatMap { m =>
      m.get(g) match
        case Some(f) => ZIO.succeed(f)
        case None =>
          ZIO.fail(
            RaiderError.InputValidation(
              s"FAIR scenario bookkeeping: no fiber for grant ${g._1.value}#${g._2}"
            )
          )
    }

  /** Deterministic contention: 2 roots × nPerRoot requests over one slot,
    * POLICY-AGNOSTIC (works for any dispatcher, fair or not — the checker
    * judges the resulting grant order).
    *
    * Fork schedule: the first three requests stand before the first release;
    * then each release is preceded by forking the next request, so a FIFO pool
    * always has two waiters parked in a known order (older = position 1, fresh
    * = position 2) and the older one must win. Every ordering link is
    * established by a polled observable (+ settle sleep), never by wall-clock
    * guesses about scheduling.
    */
  private def runScenario(
      makePool: Trace => ZIO[Any, RaiderError, SlotPool],
      nPerRoot: Int = 6
  ): ZIO[Any, RaiderError, ScenarioResult] =
    live {
      val total = 2 * nPerRoot
      for
        trace <- Trace.make
        pool <- makePool(trace)
        index <- Ref.make(Map.empty[(RootId, Int), InFlight])
        counter <- Ref.make(0)
        _ <- forkNext(pool, index, counter) // request 1: (A,1) — sole request
        _ <- pool.confirmEntered(1)
        _ <- waitGrants(trace, 1) // deterministically takes the FREE slot
        _ <- forkNext(pool, index, counter) // request 2: parks first
        _ <- pool.confirmEntered(2)
        _ <- forkNext(pool, index, counter) // request 3: parks second
        _ <- pool.confirmEntered(3)
        first <- trace.grants.get.map(_.head)
        firstHolder <- holderOf(index, first)
        _ <- release(firstHolder) // release #1 -> grant 2
        _ <- waitGrants(trace, 2)
        _ <- ZIO.foreachDiscard((2 to (total - 1)).toList) { k =>
          for
            _ <-
              if k <= total - 2 then forkNext(pool, index, counter)
              else ZIO.unit // final release runs on the last parked waiter
            last <- trace.grants.get.map(_.last) // winner of grant k
            holder <- holderOf(index, last)
            _ <- pool.confirmEntered(if k <= total - 2 then 3 else 2)
            _ <- release(holder) // release #k -> grant k+1
            _ <- waitGrants(trace, k + 1)
          yield ()
        }
        finalEntry <- trace.grants.get.map(_.last)
        finalHolder <- holderOf(index, finalEntry)
        _ <- release(finalHolder) // cleanup: the last winner leaves the slot
        grants <- trace.grants.get
        usage <- pool.usage
      yield ScenarioResult(grants, trace.peak.get, usage)
    }

  /** Pure acceptance logic shared by FAIR-01 and the FAIR-02 negative control:
    * the SAME checker judges the real pool and the unfair helper.
    */
  private def fairnessViolations(
      r: ScenarioResult,
      nPerRoot: Int
  ): Vector[String] =
    val total = 2 * nPerRoot
    val expected =
      (1 to total).toVector.map(m => (rootOf(m), tagOf(m)))
    val vs = Vector.newBuilder[String]
    if r.grantLog != expected then
      vs += s"grant order violates bounded cross-root FIFO: got " +
        r.grantLog.map(formatGrant).mkString("[", ",", "]") +
        s", expected ${expected.map(formatGrant).mkString("[", ",", "]")}"
    if r.peakConcurrent != 1 then
      vs += s"pool limit violated: peak concurrent slot holders = " +
        s"${r.peakConcurrent}, expected 1"
    if r.grantLog.size != total then
      vs += s"lost grants: ${r.grantLog.size} of $total"
    if r.grantLog.distinct.size != r.grantLog.size then
      vs += s"duplicated grants: ${r.grantLog.size - r.grantLog.distinct.size}"
    vs.result()

  // -- acceptance --------------------------------------------------------------

  def spec = suite("RAI-128 cross-root fairness (shared Model slot pool)")(
    test(
      "FAIR-01: two roots x N requests on one slot — bounded FIFO, no starvation, pool limit held, deterministic"
    ) {
      live {
        for
          adm <- Admission.make(limits())
          res <- runScenario(t => ZIO.succeed(RealPool(adm, t)))
            .timeout(ZDuration.fromSeconds(60))
          result <- ZIO
            .fromOption(res)
            .orElseFail(
              RaiderError.DeadlineExceeded("FAIR-01 scenario timed out")
            )
          perRootA <- adm.attempts(rootA)
          perRootB <- adm.attempts(rootB)
          expectedSum =
            (1 to 12).toList.map(m => costOf(rootOf(m), tagOf(m)).value).sum
        yield assertTrue(
          fairnessViolations(result, 6).isEmpty,
          result.usage.exists(u =>
            u.observedTotal == MicroUsd(expectedSum.toLong) && // exact, no drift
              u.reservedActive == MicroUsd(0L) && // every reservation settled
              u.uncertainTotal == MicroUsd(0L) &&
              u.unpricedAttempts == 0L
          ),
          perRootA == 6, // both roots completed ALL requests: no starvation
          perRootB == 6
        )
      }
    },
    test(
      "FAIR-02a negative control: root-priority dispatch helper is caught by the same fairness checker"
    ) {
      live {
        for
          res <- runScenario(t => UnfairPool.make(rootA, t))
            .timeout(ZDuration.fromSeconds(60))
          result <- ZIO
            .fromOption(res)
            .orElseFail(
              RaiderError.DeadlineExceeded("FAIR-02a scenario timed out")
            )
          violations = fairnessViolations(result, 6)
          b1 = result.grantLog.indexOf((rootB, 1))
          a2 = result.grantLog.indexOf((rootA, 2))
          // the priority root overtook the earlier-queued cross-root request
          overtake = b1 >= 0 && a2 >= 0 && a2 < b1
        yield assertTrue(
          violations.nonEmpty, // the checker is NOT a tautology: it catches unfairness
          violations.exists(_.contains("grant order")),
          overtake
        )
      }
    },
    test(
      "FAIR-02b: per-root budget exhaustion (maxAttempts) refuses only that root; other roots keep running"
    ) {
      live {
        for
          adm <- Admission.make(limits(attempts = 2))
          ranA = new AtomicInteger(0)
          attemptA = adm.withSlot(
            SlotKind.Model,
            rootA,
            CostEstimate.Priced(MicroUsd(10L)),
            None
          ) {
            ZIO.succeed((ranA.incrementAndGet(), MicroUsd(10L)))
          }
          attemptB = adm.withSlot(
            SlotKind.Model,
            rootB,
            CostEstimate.Priced(MicroUsd(40L)),
            None
          ) {
            ZIO.succeed(((), MicroUsd(40L)))
          }
          _ <- attemptA // root A burns its per-root budget: attempt 1
          _ <- attemptA // attempt 2 (exhausted)
          thirdF <- attemptA.exit.forkDaemon // A's retry — must be refused
          b1 <- attemptB // B is admitted on the SAME exhausted pool
          third <- thirdF.join
          _ <- attemptB // B's budget is independent of A's
          usage <- adm.budgetLedger.usage
          free <- adm.availableSlots(SlotKind.Model)
          attemptsA <- adm.attempts(rootA)
          attemptsB <- adm.attempts(rootB)
        yield assertTrue(
          third.isFailure,
          codeOf(third) == Some("RA-LBUDGET"), // typed per-root refusal
          ranA.get == 2, // refused attempt never dispatched
          usage.observedTotal == MicroUsd(100L), // 2x10 (A) + 2x40 (B) exact
          usage.reservedActive == MicroUsd(0L), // refusal moved no money
          usage.uncertainTotal == MicroUsd(0L),
          attemptsA == 2, // failed reserve rolls back the attempt counter
          attemptsB == 2, // B unaffected by A's exhaustion
          free == 1 // no slot leak from the refused attempt
        )
      }
    },
    test(
      "FAIR-03: parallel admissions from both roots — 0 lost/duplicated tokens, exact ledger, no slot leak"
    ) {
      live {
        for
          adm <- Admission.make(limits())
          trace <- Trace.make
          requests = (1 to 6).toList.flatMap(i =>
            List((rootA, i), (rootB, i))
          ) // fixed interleaved plan
          results <- ZIO.foreachPar(requests) { case (root, tag) =>
            adm.withSlot(
              SlotKind.Model,
              root,
              CostEstimate.Priced(costOf(root, tag)),
              None
            ) {
              enterSlot(trace) *>
                ZIO
                  .succeed(((root, tag), costOf(root, tag)))
                  .ensuring(exitSlot(trace))
            }
          }.timeout(ZDuration.fromSeconds(60))
          usage <- adm.budgetLedger.usage
          free <- adm.availableSlots(SlotKind.Model)
        yield {
          val tokens = results.getOrElse(Vector.empty)
          val expectedSum =
            requests.map { case (r, t) => costOf(r, t).value }.sum
          assertTrue(
            results.isDefined,
            tokens.size == 12, // every request admitted: nothing lost
            tokens.distinct.size == 12, // nothing duplicated
            tokens.toSet == requests.toSet, // exactly the planned set
            trace.peak.get == 1, // single-slot pool bound held under contention
            usage.observedTotal == MicroUsd(expectedSum.toLong),
            usage.reservedActive == MicroUsd(0L),
            usage.uncertainTotal == MicroUsd(0L),
            free == 1
          )
        }
      }
    }
  )
