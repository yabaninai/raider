package raider.runtime.budget

import raider.core.*
import raider.runtime.admission.{Admission, SlotKind}
import raider.runtime.jobs.{JobManager, JobStatus}
import zio.test.{live, *}
import zio.{Duration as ZDuration, Exit as ZExit, Promise, ZIO}

import java.util.concurrent.atomic.AtomicInteger

/** RAI-009 acceptance: BUD-01 bounded concurrent admission + exact money,
  * BUD-02 unknown-price/negative-config refusal, BUD-03 maxLLM=1 no-deadlock
  * and cancel-release at every acquisition boundary. Real time via `live`. */
object BudgetAdmissionSpec extends ZIOSpecDefault:

  private val root = RootId("r-bud")

  private def codeOf[A](exit: ZExit[RaiderError, A]): Option[String] = exit match
    case ZExit.Failure(cause) => cause.failures.headOption.map(_.code)
    case ZExit.Success(_)     => None

  private def limits(
    cap: Option[MicroUsd] = None,
    llm: Int = 1,
    tools: Int = 1,
    children: Int = 64,
    attempts: Int = 8,
    queue: Int = 256
  ): Either[RaiderError, BudgetLimits] =
    BudgetLimits.make(hardUsdCap = cap, maxConcurrentLlm = llm,
      maxConcurrentTools = tools, maxChildren = children,
      maxAttempts = attempts, queueCapacity = queue)

  def spec = suite("BudgetLedger + Admission RAI-009")(
    suite("BUD-01 bounded admission + exact money")(
      test("concurrent children stay within slot bound; observed sums are exact") {
        live {
          for
            adm   <- Admission.make(limits(cap = Some(MicroUsd(900_000L)), llm = 2))
            peak   = new AtomicInteger(0)
            now    = new AtomicInteger(0)
            child =
              adm.withSlot(SlotKind.Model, root, CostEstimate.Priced(MicroUsd(300_000L)),
                None) {
                  val concurrent = now.incrementAndGet()
                  peak.accumulateAndGet(concurrent, math.max)
                  ZIO.sleep(ZDuration.fromMillis(30)) *>
                    ZIO.succeed((concurrent, MicroUsd(100_000L)))
                }
            results <- ZIO.foreachPar(1 to 5)(_ => child)
                         .timeout(ZDuration.fromSeconds(15))
            usage   <- adm.budgetLedger.usage
          yield assertTrue(
            results.isDefined, // resolved: no hang under the slot bound
            results.get.size == 5,
            peak.get <= 2, // declared admission bound held
            usage.observedTotal == MicroUsd(500_000L), // 5 x 100_000 exact, no drift
            usage.reservedActive == MicroUsd(0), // every reservation settled
            usage.uncertainTotal == MicroUsd(0),
            usage.estimateViolations == 0
          )
        }
      },
      test("micro-USD sums are exact across mixed amounts (no Double)") {
        for
          adm <- Admission.make(limits())
          run = (est: Long, obs: Long) =>
            adm.withSlot(SlotKind.Model, root, CostEstimate.Priced(MicroUsd(est)), None)(
              ZIO.succeed(((), MicroUsd(obs))))
          _   <- run(100L, 100L)
          _   <- run(200L, 137L)
          _   <- run(1L, 1L)
          usage <- adm.budgetLedger.usage
        yield assertTrue(
          usage.observedTotal == MicroUsd(238L), // 100 + 137 + 1
          usage.reservedActive == MicroUsd(0),
          usage.estimateViolations == 0 // 137 <= 200, no violation
        )
      },
      test("reservation overflow is a typed error, never a wrapped Long") {
        for
          adm <- Admission.make(limits())
          ledger = adm.budgetLedger
          big   <- ledger.reserve(root, CostEstimate.Priced(MicroUsd(Long.MaxValue - 5L)))
          second <- ledger.reserve(root, CostEstimate.Priced(MicroUsd(10L))).exit
          usage <- ledger.usage
        yield assertTrue(
          second.isFailure,
          big.amount == MicroUsd(Long.MaxValue - 5L),
          usage.reservedActive == MicroUsd(Long.MaxValue - 5L) // unchanged, no wrap
        )
      },
      test("hard cap refuses admission when reservations would exceed it (BUD-01 sum)") {
        for
          adm <- Admission.make(limits(cap = Some(MicroUsd(500_000L)), llm = 4))
          ledger = adm.budgetLedger
          r1 <- ledger.reserve(root, CostEstimate.Priced(MicroUsd(300_000L)))
          r2 <- ledger.reserve(root, CostEstimate.Priced(MicroUsd(300_000L))).exit
          r3 <- ledger.reserve(root, CostEstimate.Priced(MicroUsd(200_000L)))
          _  <- ledger.release(r1)
          r4 <- ledger.reserve(root, CostEstimate.Priced(MicroUsd(300_000L)))
          usage <- ledger.usage
        yield assertTrue(
          r2.isFailure, // 300k+300k > 500k cap
          usage.reservedActive == MicroUsd(500_000L), // 200k + 300k after refund
          r3.amount == MicroUsd(200_000L),
          r4.amount == MicroUsd(300_000L)
        )
      },
      test("queue backpressure refuses instead of unbounded wait") {
        live {
          for
            adm  <- Admission.make(limits(queue = 1))
            holder <- adm.withSlotFree(SlotKind.Model, root, None)(
                        Promise.make[Nothing, Unit].flatMap(_.await)).forkDaemon
            _    <- ZIO.sleep(ZDuration.fromMillis(50))
            q1   <- adm.queued
            second <- adm.withSlotFree(SlotKind.Model, root, None)(ZIO.succeed(())).exit
            q2   <- adm.queued
            _    <- holder.interrupt
          yield assertTrue(
            q1 == 1,
            second.isFailure,
            q2 == 1 // refused before queueing; holder still the only queued one
          )
        }
      }
    ),
    suite("BUD-02 unknown price + invalid config")(
      test("unknown price under required hard USD cap is refused before dispatch") {
        for
          adm <- Admission.make(limits(cap = Some(MicroUsd(1_000_000L))))
          ran  = new AtomicInteger(0)
          res <- adm.withSlot(SlotKind.Model, root, CostEstimate.Unknown, None) {
                   ZIO.succeed((ran.incrementAndGet(), MicroUsd(0L)))
                 }.exit
          usage <- adm.budgetLedger.usage
        yield assertTrue(
          res.isFailure,
          codeOf(res) == Some("RA-PBUDGET"),
          ran.get == 0, // dispatch never happened
          usage.reservedActive == MicroUsd(0)
        )
      },
      test("unknown price without cap runs, but is tracked as unpriced (never free)") {
        for
          adm <- Admission.make(limits())
          res <- adm.withSlot(SlotKind.Model, root, CostEstimate.Unknown, None) {
                   ZIO.succeed(("ok", MicroUsd(1234L)))
                 }
          usage <- adm.budgetLedger.usage
        yield assertTrue(
          res == "ok",
          usage.unpricedAttempts == 1L,
          // review MED-2: positive observed cost of an unpriced attempt is
          // recorded truthfully — never dropped, never treated as free
          usage.observedTotal == MicroUsd(1234L),
          usage.reservedActive == MicroUsd(0L)
        )
      },
      test("negative/overflow config never launches (typed Configuration)") {
        val bad1 = BudgetLimits.make(hardUsdCap = Some(MicroUsd(-1L)))
        val bad2 = BudgetLimits.make(maxConcurrentLlm = 0)
        val bad3 = BudgetLimits.make(maxAttempts = -3)
        val good = BudgetLimits.make(hardUsdCap = Some(MicroUsd(0L))) // zero cap is legal
        for
          rejected <- Admission
                        .make(Left(RaiderError.Configuration("pre-validated bad"))).exit
          adm <- Admission.make(good)
          negEst <- adm.budgetLedger
                      .reserve(root, CostEstimate.Priced(MicroUsd(-5L))).exit
        yield assertTrue(
          bad1.isLeft, bad2.isLeft, bad3.isLeft, good.isRight,
          rejected.isFailure, // invalid limits are a typed refusal, not a launch
          codeOf(rejected) == Some("RA-CFG"),
          codeOf(negEst) == Some("RA-CFG")
        )
      }
    ),
    suite("BUD-03 no-deadlock + cancel at every acquisition boundary")(
      test("maxConcurrentLlm=1: parent delegates, joins child, no deadlock") {
        live {
          for
            manager <- JobManager.make()
            adm     <- Admission.make(limits(llm = 1))
            // parent: one model call, release, then fork a child job that needs the
            // SAME single slot while the parent waits on join (holding nothing)
            childBody =
              adm.withSlot(SlotKind.Model, root, CostEstimate.Priced(MicroUsd(50L)), None) {
                ZIO.sleep(ZDuration.fromMillis(30)) *>
                  ZIO.succeed(("child-done", MicroUsd(50L)))
              }
            parent =
              for
                _ <- adm.withSlot(SlotKind.Model, root,
                       CostEstimate.Priced(MicroUsd(10L)), None)(
                       ZIO.succeed(((), MicroUsd(10L))))
                child <- manager.start(root, "child", Task(childBody))
                value <- manager.await(child) // parent holds NO permit here
              yield value
            out   <- parent.timeout(ZDuration.fromSeconds(10))
            usage <- adm.budgetLedger.usage
            snaps <- manager.adminView
          yield assertTrue(
            out == Some("child-done"), // resolved => single slot shared, no deadlock
            usage.observedTotal == MicroUsd(60L),
            usage.reservedActive == MicroUsd(0),
            snaps.forall(_.status == JobStatus.Succeeded)
          )
        }
      },
      test("cancel while WAITING for a permit charges nothing and leaks no slot") {
        live {
          for
            adm <- Admission.make(limits(llm = 1))
            holderGate <- Promise.make[Nothing, Unit]
            holder <- adm.withSlot(SlotKind.Model, root,
                       CostEstimate.Priced(MicroUsd(100L)), None) {
                       holderGate.await.as(((), MicroUsd(0L)))
                     }.forkDaemon
            // deterministic: the holder HAS the slot and the reservation before
            // the waiter even exists (availableSlots==0 + reservedActive==100) —
            // under full-tree parallel load a fixed sleep cannot order this
            _ <- adm.availableSlots(SlotKind.Model)
                  .repeatUntil(_ == 0).timeout(ZDuration.fromSeconds(5))
            _ <- adm.budgetLedger.usage
                  .repeatUntil(_.reservedActive == MicroUsd(100L))
                  .timeout(ZDuration.fromSeconds(5))
            waiter <- adm.withSlot(SlotKind.Model, root,
                       CostEstimate.Priced(MicroUsd(500L)),
                       Some(ZDuration.fromMillis(150))) {
                       ZIO.succeed(((), MicroUsd(0L)))
                     }.exit
            usageWhileHeld <- adm.budgetLedger.usage
            _ <- holder.interrupt // holder interrupted during dispatch -> Uncertain
            _ <- holder.await
            usageAfter <- adm.budgetLedger.usage
            free       <- adm.availableSlots(SlotKind.Model)
          yield assertTrue(
            waiter.isFailure, // deadline hit: queued job cancelled, not stuck
            codeOf(waiter) == Some("RA-DEADLINE"),
            usageWhileHeld.reservedActive == MicroUsd(100L), // only holder reserved
            usageAfter.reservedActive == MicroUsd(0), // holder released on interrupt
            usageAfter.uncertainTotal == MicroUsd(100L), // may have been paid: honest
            free == 1 // no permit leak
          )
        }
      },
      test("cancel after permit, before dispatch: permit released, zero charge") {
        for
          adm <- Admission.make(limits(llm = 1))
          _   <- adm.cancelRoot(root)
          ran  = new AtomicInteger(0)
          res <- adm.withSlot(SlotKind.Model, root,
                   CostEstimate.Priced(MicroUsd(700L)), None) {
                   ZIO.succeed((ran.incrementAndGet(), MicroUsd(0L)))
                 }.exit
          usage <- adm.budgetLedger.usage
          kids  <- adm.activeChildren(root)
          tries <- adm.attempts(root)
          free  <- adm.availableSlots(SlotKind.Model)
        yield assertTrue(
          res.isFailure,
          codeOf(res) == Some("RA-CANCEL"),
          ran.get == 0, // no dispatch after Cancelling
          usage.reservedActive == MicroUsd(0), // reservation never made
          kids == 0, tries == 0, // accounting brackets unwound
          free == 1 // permit released
        )
      },
      test("direct interrupt of a fiber parked on Queue.take leaks no permit") {
        live {
          for
            adm <- Admission.make(limits(llm = 1))
            holderGate <- Promise.make[Nothing, Unit]
            holder <- adm.withSlot(SlotKind.Model, root,
                       CostEstimate.Priced(MicroUsd(100L)), None) {
                       holderGate.await.as(((), MicroUsd(0L)))
                     }.forkDaemon
            // deterministic: holder HAS the unit and the reservation before the
            // waiter even exists (availableSlots==0 + reservedActive==100)
            _      <- adm.availableSlots(SlotKind.Model)
                        .repeatUntil(_ == 0).timeout(ZDuration.fromSeconds(5))
            _      <- adm.budgetLedger.usage
                        .repeatUntil(_.reservedActive == MicroUsd(100L))
                        .timeout(ZDuration.fromSeconds(5))
            // waiter with NO deadline: parks on Queue.take until interrupted
            waiter <- adm.withSlot(SlotKind.Model, root,
                       CostEstimate.Priced(MicroUsd(500L)), None) {
                       ZIO.succeed(((), MicroUsd(0L)))
                     }.forkDaemon
            _      <- adm.queued.repeatUntil(_ == 2)
                        .timeout(ZDuration.fromSeconds(5))
            _      <- waiter.interrupt
            _      <- waiter.await
            afterWaiter <- adm.budgetLedger.usage
            q       <- adm.queued
            slotsDuring <- adm.availableSlots(SlotKind.Model) // 0: holder keeps it,
                          // interrupted take must NOT have consumed a phantom unit
            _      <- holder.interrupt
            _      <- holder.await
            afterHolder <- adm.budgetLedger.usage
            free   <- adm.availableSlots(SlotKind.Model)
          yield assertTrue(
            afterWaiter.reservedActive == MicroUsd(100L), // waiter charged nothing
            q == 1, // leaveQueue ran for the interrupted waiter
            slotsDuring == 0,
            afterHolder.reservedActive == MicroUsd(0L),
            afterHolder.uncertainTotal == MicroUsd(100L),
            free == 1 // queue untouched by the interrupted take: no phantom loss
          )
        }
      },
      test("cancel during dispatch marks Uncertain, keeps charge, frees permit") {
        live {
          for
            adm <- Admission.make(limits(llm = 1))
            flow <- adm.withSlot(SlotKind.Model, root,
                      CostEstimate.Priced(MicroUsd(250L)), None) {
                      ZIO.sleep(ZDuration.fromSeconds(5)).as(((), MicroUsd(0L)))
                    }.forkDaemon
            // deterministic: reservation exists ⇒ dispatch is in progress
            _ <- adm.budgetLedger.usage
                  .repeatUntil(_.reservedActive == MicroUsd(250L))
                  .timeout(ZDuration.fromSeconds(5))
            _ <- flow.interrupt
            _ <- flow.await
            usage <- adm.budgetLedger.usage
            free  <- adm.availableSlots(SlotKind.Model)
          yield assertTrue(
            usage.uncertainTotal == MicroUsd(250L), // may have been paid: not zeroed
            usage.reservedActive == MicroUsd(0),
            free == 1
          )
        }
      },
      test("maxChildren and maxDepth ceilings are enforced (typed, unwound)") {
        live {
          for
            // two slots: §6 order grants the permit BEFORE child registration,
            // so the second op must be able to take a slot to reach the
            // maxChildren check (with llm=1 it would queue on the slot instead)
            adm <- Admission.make(limits(children = 1, queue = 10, llm = 2))
            holder <- adm.withSlotFree(SlotKind.Model, root, None)(
                        Promise.make[Nothing, Unit].flatMap(_.await)).forkDaemon
            _ <- ZIO.sleep(ZDuration.fromMillis(50))
            second <- adm.withSlotFree(SlotKind.Model, root, None)(ZIO.succeed(())).exit
            kids <- adm.activeChildren(root)
            depth = adm.checkDepth(2)
            deep  = adm.checkDepth(999).left.toOption.map(_.code)
            _ <- holder.interrupt; _ <- holder.await
          yield assertTrue(
            second.isFailure,
            codeOf(second) == Some("RA-LBUDGET"),
            kids == 1, // only the holder counts; second never registered
            depth.isRight,
            deep == Some("RA-LBUDGET")
          )
        }
      }
    )
  )
