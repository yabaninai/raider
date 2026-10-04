package raider.dsl.composition

import raider.core.*
import zio.test.{live, *}
import zio.{Duration as ZDuration, Exit as ZExit, Promise, ZIO}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable

object CompositionSpec extends ZIOSpecDefault:

  private def run[A](task: Task[A]): ZIO[Any, RaiderError, A] = Task.zio(task)

  def spec = suite("Composition RAI-017")(
    suite("COMP-01 all: typed tuple, one interpreter")(
      test("all(Task, Task) yields typed tuple in parallel") {
        for
          result <- run(all(Task.succeed(42), Task.succeed("forty-two")))
        yield assertTrue(result == (42, "forty-two"))
      },
      test("all(Program, Program) composes to Program[I,(A,B)]") {
        val double = Program.fromFunction[Int, Int]("double")(i => Task.succeed(i * 2))
        val label  = Program.fromFunction[Int, String]("label")(i => Task.succeed(s"n$i"))
        val both   = all(double, label)
        for result <- run(both.apply(21))
        yield assertTrue(result == (42, "n21"))
      },
      test("andThen chains through the frozen Program contract") {
        val parse  = Program.fromFunction[String, Int]("parse")(s => Task.succeed(s.length))
        val format = Program.fromFunction[Int, String]("format")(n => Task.succeed(s"<$n>"))
        for result <- run(parse.andThen(format).apply("abcd"))
        yield assertTrue(result == "<4>")
      }
    ),
    suite("COMP-02 fail-fast + typed negatives")(
      test("all fail-fast interrupts the sibling") {
        live {
          for
            siblingDone = new AtomicInteger(0)
            sibling <- run(all(
                          Task(ZIO.sleep(ZDuration.fromSeconds(30)) *>
                                 ZIO.succeed(siblingDone.incrementAndGet())),
                          Task.fail(RaiderError.ProviderAuth("401"))))
                       .exit
                         .timeout(ZDuration.fromSeconds(5))
            code = sibling match
              case Some(ZExit.Failure(cause)) => cause.failures.headOption.map(_.code)
              case other => None
          yield assertTrue(
            sibling.isDefined,                       // resolved quickly: fail-fast
            code == Some("RA-AUTH"),
            siblingDone.get == 0                     // sibling was interrupted
          )
        }
      },
      test("invalid parallelism is a typed error, not a clamp") {
        for
          res <- run(batch(List(1, 2), 0)(i => Task.succeed(i))).exit
        yield assertTrue(
          res.isFailure,
          res match
            case ZExit.Failure(cause) =>
              cause.failures.headOption.map(_.code) == Some("RA-INP")
            case ZExit.Success(_) => false
        )
      },
      test("empty batch dispatches nothing") {
        val invoked = new AtomicInteger(0)
        for
          result <- run(batch(List.empty[Int], 4) { i => invoked.incrementAndGet(); Task.succeed(i) })
        yield assertTrue(result == Nil && invoked.get == 0)
      }
    ),
    suite("COMP-01 batch: ordered bounded results")(
      test("results keep input order regardless of completion order") {
        live {
          for
            result <- run(batch(List(3, 1, 2), 3) { i =>
                        Task(ZIO.sleep(ZDuration.fromMillis((4 - i) * 15L)).as(i * 10))
                      })
          yield assertTrue(result == List(30, 10, 20))
        }
      },
      test("parallelism bound is honored (peak concurrent <= parallelism)") {
        live {
          val now  = new AtomicInteger(0)
          val peak = new AtomicInteger(0)
          // effects go INSIDE the ZIO: constructing a Task must stay lazy
          def peakTrack =
            Task(ZIO.succeed {
              val c = now.incrementAndGet()
              peak.accumulateAndGet(c, math.max)
            } *> ZIO.sleep(ZDuration.fromMillis(20)) *>
              ZIO.succeed(now.decrementAndGet()))
          for
            _ <- run(batch(List(1, 2, 3, 4, 5, 6), 2)(_ => peakTrack))
          yield assertTrue(peak.get <= 2, now.get == 0)
        }
      }
    ),
    suite("COMP-05 batchCollect: domain -> Outcome, interruption never swallowed")(
      test("domain failures become Failed outcomes, successes keep order") {
        for
          results <- run(batch.collect(List(1, 2, 3), 2) { i =>
                       if i == 2 then Task.fail(RaiderError.ProviderAuth("401"))
                       else Task.succeed(i * 10)
                     })
        yield assertTrue(
          results == List(BatchOutcome.Succeeded(10),
                          BatchOutcome.Failed(RaiderError.ProviderAuth("401")),
                          BatchOutcome.Succeeded(30))
        )
      },
      test("external interruption is re-raised, not converted to outcomes") {
        live {
          for
            collected <- run(batch.collect(List(1, 2, 3), 2) { _ =>
                           Task(Promise.make[Nothing, Unit].flatMap(_.await.as(1)))
                         }).fork
            _         <- ZIO.sleep(ZDuration.fromMillis(50))
            _         <- collected.interrupt
            exit      <- collected.await // Fiber#await already yields the Exit
          yield assertTrue(exit.isFailure) // interruption propagates as interruption
        }
      }
    )
  )
