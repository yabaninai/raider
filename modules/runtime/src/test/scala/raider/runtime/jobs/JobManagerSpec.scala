package raider.runtime.jobs

import raider.core.*
import zio.{Exit as ZExit, ZIO}
import zio.test.Assertion.*
import zio.test.*

import java.util.concurrent.atomic.AtomicInteger

object JobManagerSpec extends ZIOSpecDefault:

  private val root = RootId("r-1")

  def spec = suite("JobManager RAI-007")(
    test("JOB-01: start/read/await terminal outcome; two awaits do not re-run") {
      for
        manager <- JobManager.make()
        runs     = new AtomicInteger(0)
        task     = Task(ZIO.succeed(runs.incrementAndGet()).as("done-42"))
        job     <- manager.start(root, "counted", task)
        v1      <- manager.await(job)
        v2      <- manager.await(job)
        s1      <- manager.snapshot(job)
      yield assertTrue(
        v1 == "done-42", v2 == "done-42",
        runs.get == 1,
        s1.status == JobStatus.Succeeded
      )
    },

    test("JOB-01: several roots are independent; cancel one does not touch the other") {
      for
        manager <- JobManager.make()
        gate    <- zio.Promise.make[Nothing, Unit]
        longTask = Task(gate.await *> ZIO.succeed("long"))
        a       <- manager.start(RootId("r-a"), "long-a", longTask)
        b       <- manager.start(RootId("r-b"), "quick-b", Task.succeed("quick"))
        bv      <- manager.await(b)
        stopped <- manager.cancel(a)
        av      <- manager.await(a).exit
        _       <- gate.succeed(())
        sa      <- manager.snapshot(a)
        sb      <- manager.snapshot(b)
      yield assertTrue(
        bv == "quick",
        stopped,
        av.isFailure,
        sa.status == JobStatus.Cancelled,
        sb.status == JobStatus.Succeeded
      )
    },

    test("JOB-02: unknown job id is a typed error (snapshotById)") {
      for
        manager <- JobManager.make()
        opt     <- manager.lookup(JobId("nope"))
        typed   <- manager.snapshotById(JobId("nope")).exit
        errCode  = typed match
          case ZExit.Failure(_) => "typed-error"
          case ZExit.Success(_) => "no-error"
      yield assertTrue(opt.isEmpty && errCode == "typed-error")
    },

    test("F-1 regression: cancel-wins race still resolves every await (production settle)") {
      // deterministic reproduction of the interleaving: Cancelling committed
      // while the task fiber completed with Success before the interrupt landed
      for
        manager <- JobManager.make()
        gate    <- zio.Promise.make[Nothing, Unit]
        job     <- manager.start(root, "cancel-wins", Task(gate.await.as("late-success")))
        _       <- manager.cancel(job)                 // commits Cancelling, interrupt pending
        _       <- manager.settleForTest(job, ZExit.succeed("late-success")) // production path
        awaited <- manager.await(job).exit              // MUST resolve (F-1 fix)
        snap    <- manager.snapshot(job)
      yield assertTrue(
        awaited.isFailure,
        snap.status == JobStatus.Cancelled,
        // at least our synthetic completion is late; the real watcher's settle
        // of the interrupted fiber may add one more — both are late observations
        snap.lateObservations >= 1
      )
    },

    test("JOB-03 concurrent race: await always resolves within a bound (100 iterations)") {
      def loop(i: Int): ZIO[Any, Any, Int] =
        if i >= 100 then ZIO.succeed(100)
        else
          (for
            manager <- JobManager.make()
            // completes after a tiny yield; cancel races from another fiber
            job     <- manager.start(root, s"race-$i",
                        Task(ZIO.yieldNow *> ZIO.succeed(s"v$i")))
            _       <- manager.cancel(job).fork
            // the invariant is RESOLUTION (success or typed Cancelled), never a hang
            res     <- manager.await(job).exit.timeout(zio.Duration.fromSeconds(5))
            snap    <- manager.snapshot(job)
          yield (res, snap)).flatMap { (res, snap) =>
            res match
              case None =>
                ZIO.die(new RuntimeException(
                  s"lost waiter at iteration $i (status=${snap.status})"))
              case Some(exit) =>
                // outcome must be consistent with the terminal status
                val consistent = exit match
                  case ZExit.Success(_) => snap.status == JobStatus.Succeeded
                  case ZExit.Failure(_) => snap.status == JobStatus.Cancelled ||
                                          snap.status == JobStatus.Failed
                if !consistent then
                  ZIO.die(new RuntimeException(
                    s"inconsistent outcome at $i: exit=$exit status=${snap.status}"))
                else loop(i + 1)
          }
      loop(0).map(n => assertTrue(n == 100))
    },

    test("JOB-02: invalid transition rejected; duplicate terminal is a late observation") {
      for
        manager <- JobManager.make()
        job     <- manager.start(root, "edge", Task.succeed(7))
        _       <- manager.await(job)
        bad     <- manager.debugTransition(job, JobStatus.Running).exit // Succeeded -> Running
        dup     <- manager.settleTwiceForTest(job).exit // second completion attempt
        snap    <- manager.snapshot(job)
      yield assertTrue(
        bad.isFailure,
        dup.isFailure,
        snap.status == JobStatus.Succeeded,
        snap.lateObservations == 1
      )
    },

    test("JOB-03: many waiters see one consistent outcome (success)") {
      for
        manager <- JobManager.make()
        job     <- manager.start(root, "fanout", Task.succeed("one"))
        waits   <- ZIO.forkAll(List.fill(50)(manager.await(job).exit))
        outs    <- waits.join
      yield assertTrue(outs.forall(_.isSuccess) && outs.size == 50)
    },

    test("JOB-03: cancel-vs-success race yields exactly one terminal outcome") {
      for
        manager <- JobManager.make()
        // deterministic both directions:
        // a) success already happened -> cancel reports false, outcome stays success
        done      <- manager.start(root, "done-first", Task.succeed("v"))
        dv        <- manager.await(done)
        cancelLate = manager.cancel(done)
        cl        <- cancelLate
        doneSnap  <- manager.snapshot(done)
        // b) cancel first -> await fails with Cancelled, status Cancelled
        gate      <- zio.Promise.make[Nothing, Unit]
        blocked   <- manager.start(root, "blocked", Task(gate.await.as("never")))
        c2        <- manager.cancel(blocked)
        awaitRes  <- manager.await(blocked).exit
        blockedSn <- manager.snapshot(blocked)
        _         <- gate.succeed(())
        cancelCode = awaitRes match
          case ZExit.Failure(cause) => cause.failures.headOption.map(_.code)
          case ZExit.Success(_)     => None
      yield assertTrue(
        dv == "v", cl == false, doneSnap.status == JobStatus.Succeeded,
        c2 == true,
        cancelCode == Some("RA-CANCEL"),
        blockedSn.status == JobStatus.Cancelled
      )
    },

    test("failed task maps to Failed status and typed error") {
      for
        manager <- JobManager.make()
        job     <- manager.start(root, "boom",
                    Task.fail(RaiderError.ProviderAuth("401")))
        res     <- manager.await(job).exit
        snap    <- manager.snapshot(job)
        authCode = res match
          case ZExit.Failure(cause) => cause.failures.headOption.map(_.code)
          case ZExit.Success(_)     => None
      yield assertTrue(
        authCode == Some("RA-AUTH"),
        snap.status == JobStatus.Failed
      )
    },

    test("admin view lists jobs sorted by id") {
      for
        manager <- JobManager.make()
        _       <- manager.start(root, "x1", Task.succeed(1))
        _       <- manager.start(root, "x2", Task.succeed(2))
        view    <- manager.adminView
      yield assertTrue(
        view.map(_.id.value) == view.map(_.id.value).sorted,
        view.map(_.label).toSet == Set("x1", "x2"),
        view.size == 2,
        view.forall(v => v.root == root)
      )
    }
  )
