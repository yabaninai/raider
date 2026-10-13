package raider.ui

import raider.core.{JobId, RootId}
import raider.runtime.events.EventHub
import raider.runtime.state.{Scope, StateStore}
import zio.test.*
import zio.test.live
import zio.{Duration, Queue, Ref, Task, UIO, ZIO}

/** RAI-094 (STW-02/STW-03): the `:state` provider fed by a REAL StateStore. The
  * adapter serves live usage (keys/UTF-8 bytes) at every call; through the real
  * shell loop `:state <scope>` shows what the store REALLY holds — including
  * data written after the shell started. Determinism rules are the TabShellSpec
  * ones: real hub/registry/store (no mocks), JLine isolated behind
  * ShellTerminal, coordination via eventually only.
  */
object ShellViewsStateSpec extends ZIOSpecDefault:

  private val job = JobId("stw-ui")
  private val sec15 = Duration.fromSeconds(15)

  /** Await a logically guaranteed condition (generous outer bound, real sleeps;
    * the bound never asserts, only fails hangs typed).
    */
  private def eventually(
      max: Duration
  )(cond: ZIO[Any, Any, Boolean]): ZIO[Any, Any, Unit] =
    ZIO.suspendSucceed {
      val deadline = System.nanoTime() + max.toNanos
      def loop: ZIO[Any, Any, Unit] =
        cond.flatMap {
          case true => ZIO.unit
          case false =>
            if System.nanoTime() >= deadline then
              ZIO.fail("condition not met within the bound")
            else ZIO.sleep(Duration.fromMillis(20)) *> loop
        }
      loop
    }

  /** Scripted terminal: input lines from a queue (FIFO), all printAbove output
    * into a queue — the ONE writer, captured for line assertions.
    */
  private final class ScriptedTerminal(
      in: Queue[String],
      out: Queue[String]
  ) extends ShellTerminal:

    def printAbove(lines: Vector[String]): UIO[Unit] =
      out.offerAll(lines).unit

    def readLine(prompt: String): Task[Option[String]] =
      in.take.map(Some(_)).onInterrupt(ZIO.succeed(None))

    def close: UIO[Unit] = ZIO.unit

  /** Run the real shell loop over the scripted terminal; the body interacts by
    * offering input lines and collecting output; `:quit` is sent and the loop
    * joined before the scope closes.
    */
  private def withShell(
      views: ShellViews
  )(
      body: (Queue[String], Queue[String]) => ZIO[Any, Any, TestResult]
  ): ZIO[Any, Any, TestResult] =
    ZIO.scoped {
      for
        hub <- EventHub.make()
        registry <- raider.ui.SessionRegistry.make(hub)
        in <- Queue.unbounded[String]
        out <- Queue.unbounded[String]
        terminal = ScriptedTerminal(in, out)
        fiber <- TabShell
          .run(
            hub,
            registry,
            terminal,
            _ => ZIO.succeed(TabCloseChoice.Hide),
            TabShell.Config(),
            views
          )
          .fork
        result <- body(in, out)
        _ <- in.offer(":quit")
        _ <- fiber.join
      yield result
    }

  /** Drain rendered output until the predicate holds; on timeout the failure
    * carries the captured output tail (diagnosis, not a timing assertion).
    */
  private def collectUntil(
      out: Queue[String],
      seen: Ref[Vector[String]],
      max: Duration
  )(pred: Vector[String] => Boolean): ZIO[Any, Any, Unit] =
    ZIO.suspendSucceed {
      val deadline = System.nanoTime() + max.toNanos
      def loop: ZIO[Any, Any, Unit] =
        for
          chunk <- out.takeAll
          _ <- seen.update(_ ++ chunk)
          v <- seen.get
          _ <-
            if pred(v) then ZIO.unit
            else if System.nanoTime() >= deadline then
              ZIO.fail(
                "collectUntil bound exceeded; tail: " + v
                  .takeRight(40)
                  .mkString(" || ")
              )
            else ZIO.sleep(Duration.fromMillis(20)) *> loop
        yield ()
      loop
    }

  def spec = suite("ShellViews state provider (RAI-094, STW-02/03)")(
    test(
      "STW-02: the adapter serves REAL live usage from the store (UTF-8 byte accounting, not a frozen snapshot)"
    ) {
      for
        store <- StateStore.memory()
        _ <- store.put(Scope.Agent(job), "alpha", "hello") // 5 bytes
        _ <- store.put(Scope.Agent(job), "beta", "é") // 2 bytes UTF-8
        provider = ShellViews.fromStore(
          store,
          StateStore.Quotas.default,
          job
        )
        first = provider("agent")
        _ <- store.delete(Scope.Agent(job), "alpha")
        second = provider("agent")
      yield assertTrue(
        first == Some(StateView(2L, 7L, 1024L, 1048576L)),
        second == Some(StateView(1L, 2L, 1024L, 1048576L))
      )
    },
    test(
      "STW-02: root:/global scopes are explicit-only and served; unknown/empty-id scope strings are None; detached default untouched"
    ) {
      for
        store <- StateStore.memory()
        _ <- store.put(Scope.Root(RootId("run-1")), "plan", "steps") // 5
        _ <- store.put(Scope.Global, "theme", "dark") // 4
        provider = ShellViews.fromStore(
          store,
          StateStore.Quotas.default,
          job
        )
      yield assertTrue(
        provider("root:run-1") == Some(StateView(1L, 5L, 1024L, 1048576L)),
        provider("global") == Some(StateView(1L, 4L, 1024L, 1048576L)),
        provider("bogus") == None,
        provider("root:") == None,
        ShellViews.detached.state("agent") == None,
        ShellViews.detached.state("global") == None,
        ShellViews.detached.state("root:run-1") == None
      )
    },
    test(
      "STW-02: TabShell :state renders the REAL store usage through the shell loop — including a key written after the shell started"
    ) {
      for
        store <- StateStore.memory()
        _ <- store.put(Scope.Agent(job), "alpha", "hello") // 5 bytes
        _ <- store.put(Scope.Agent(job), "beta", "é") // 2 bytes
        views = ShellViews(
          () => Nil,
          ShellViews.fromStore(store, StateStore.Quotas.default, job)
        )
        result <- live(withShell(views) { (in, out) =>
          for
            seen <- Ref.make(Vector.empty[String])
            _ <- in.offer(":state agent")
            _ <- collectUntil(out, seen, sec15)(
              _.contains("state [agent]:")
            )
            _ <- in.offer(":state bogus")
            _ <- collectUntil(out, seen, sec15)(
              _.contains("hint: state store not attached (scope: bogus)")
            )
            // written AFTER the shell started: the next :state must see it
            _ <- store.put(Scope.Agent(job), "gamma", "12345") // 5 bytes
            _ <- in.offer(":state agent")
            _ <- collectUntil(out, seen, sec15)(v =>
              v.contains("  usage: 3 keys, 12 bytes")
            )
            seenAll <- seen.get
          yield assertTrue(
            seenAll.containsSlice(
              Vector(
                "state [agent]:",
                "  usage: 2 keys, 7 bytes",
                "  quota: 1024 keys, 1048576 bytes"
              )
            ),
            seenAll.contains("hint: state store not attached (scope: bogus)"),
            seenAll.containsSlice(
              Vector(
                "state [agent]:",
                "  usage: 3 keys, 12 bytes",
                "  quota: 1024 keys, 1048576 bytes"
              )
            )
          )
        })
      yield result
    },
    test(
      "STW-03: detached shell — :state stays the typed not-attached hint (provider never attached)"
    ) {
      live(withShell(ShellViews.detached) { (in, out) =>
        for
          seen <- Ref.make(Vector.empty[String])
          _ <- in.offer(":state agent")
          _ <- collectUntil(out, seen, sec15)(
            _.contains("hint: state store not attached (scope: agent)")
          )
          seenAll <- seen.get
        yield assertTrue(
          !seenAll.exists(_.contains("usage:")),
          seenAll.count(_ == "hint: state store not attached (scope: agent)") ==
            1
        )
      })
    }
  )
