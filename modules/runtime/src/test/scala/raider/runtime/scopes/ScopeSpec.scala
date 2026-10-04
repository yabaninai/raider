package raider.runtime.scopes

import raider.core.*
import raider.runtime.jobs.{JobManager, JobStatus}
import zio.test.{live, *}
import zio.{Exit as ZExit, Promise, ZIO, Duration as ZDuration}

import java.util.concurrent.atomic.AtomicInteger

object ScopeSpec extends ZIOSpecDefault:

  private val grace = ZDuration.fromMillis(800)

  def spec = suite("RaiderScope RAI-008")(
    test("SCOPE-01: scoped exit cancels unjoined children; joined completes; root completes after finalizers") {
      live {
        for
          manager      <- JobManager.make()
          finCount     = new AtomicInteger(0)
          unjoinedGate <- Promise.make[Nothing, Unit]
          result <- RaiderScope.scoped("s1", manager, grace) { scope =>
                      for
                        joined <- scope.fork("joined", Task.succeed("done"))
                        v      <- scope.join(joined)
                        _      <- scope.fork("unjoined", Task(unjoinedGate.await.as("never")))
                        _      <- scope.addFinalizer(ZIO.succeed(finCount.incrementAndGet()))
                      yield v
                    }.exit
          admin        <- manager.adminView
          unjoinedSnap = admin.find(_.label == "unjoined")
          joinedSnap   = admin.find(_.label == "joined")
          _            <- unjoinedGate.succeed(())
        yield assertTrue(
          result == ZExit.succeed("done"),
          unjoinedSnap.exists(_.status == JobStatus.Cancelled),
          joinedSnap.exists(_.status == JobStatus.Succeeded),
          finCount.get == 1
        )
      }
    },

    test("SCOPE-01: finalizers run in reverse (LIFO) order") {
      live {
        for
          manager <- JobManager.make()
          order   <- zio.Ref.make(List.empty[Int])
          _ <- RaiderScope.scoped("lifo", manager, grace) { scope =>
                 for
                   _ <- scope.addFinalizer(order.update(_ :+ 1))
                   _ <- scope.addFinalizer(order.update(_ :+ 2))
                   _ <- scope.addFinalizer(order.update(_ :+ 3))
                 yield ()
               }.exit
          o <- order.get
        yield assertTrue(o == List(3, 2, 1))
      }
    },

    test("SCOPE-02: grace exhaustion reports Uncertain children, not fake Cancelled") {
      live {
        for
          manager <- JobManager.make(maxRetained = 16)
          result <- RaiderScope.scoped("grace", manager, ZDuration.fromMillis(150)) { scope =>
                      for
                        _ <- scope.fork("stubborn", Task(ZIO.uninterruptible(ZIO.never)))
                        _ <- ZIO.yieldNow
                      yield "body-ok"
                    }.exit
        yield assertTrue(result match
          case ZExit.Failure(cause) =>
            cause.failures.headOption.exists(_.code == "RA-TOOLUNCERT")
          case ZExit.Success(_) => false
        )
      }
    },

    test("SCOPE-02: finalizer failure surfaces, body success is not faked") {
      live {
        for
          manager <- JobManager.make()
          result <- RaiderScope.scoped("failing-fin", manager, grace) { scope =>
                      for _ <- scope.addFinalizer(ZIO.die(new RuntimeException("cleanup boom")))
                             yield "body-ok"
                    }.exit
        yield assertTrue(result match
          // §5/SCOPE-02: a failed finalizer makes the scope outcome Uncertain
          // (evidence recorded), never a fake success nor a swallowed defect
          case ZExit.Failure(cause) =>
            cause.failures.exists(_.code == "RA-TOOLUNCERT") || cause.defects.nonEmpty
          case ZExit.Success(_) => false
        )
      }
    },

    test("SCOPE-03: interrupting the forking parent leaves no orphan jobs (50 iterations)") {
      def once(i: Int): ZIO[Any, Nothing, Boolean] =
        for
          manager <- JobManager.make(maxRetained = 64)
          scope   <- RaiderScope.make(s"race-$i", manager)
          parent  <- scope
                       .fork("child", Task(
                         ZIO.sleep(ZDuration.fromMillis(5)) *> ZIO.succeed(s"v$i")))
                       .fork
          _       <- ZIO.yieldNow
          _       <- parent.interrupt.fork
          _       <- parent.await.exit
          report  <- scope.close(grace)
          admin   <- manager.adminView
          live0    = admin.filterNot(s => JobStatus.terminal(s.status))
        yield report.uncertainChildren.isEmpty && live0.isEmpty

      def loop(i: Int): ZIO[Any, Nothing, Boolean] =
        if i >= 50 then ZIO.succeed(true)
        else once(i).flatMap(ok => if ok then loop(i + 1) else ZIO.succeed(false))

      live(loop(0)).map(ok => assertTrue(ok))
    },

    test("closing scope refuses new forks") {
      live {
        for
          manager <- JobManager.make()
          scope   <- RaiderScope.make("closed", manager)
          _       <- scope.close(grace)
          refused <- scope.fork("late", Task.succeed(1)).exit
        yield assertTrue(refused match
          case ZExit.Failure(cause) => cause.failures.exists(_.code == "RA-CANCEL")
          case ZExit.Success(_)     => false
        )
      }
    }
  )
