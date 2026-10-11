package raider.ui

import raider.core.{JobId, RootId}
import raider.runtime.events.EventHub
import zio.test.{live, *}
import zio.{Duration, Queue, Ref, Task, UIO, ZIO}

/** LIN-04B (slice): the Lineage auxiliary tab over the ONE `:aux` seam of
  * LIN-04A — acceptance LIN4-B-01..03.
  *
  * Determinism rules (same discipline as AuxTabSpec):
  *   - real `EventHub` + real `SessionRegistry` — no mocks, no second runtime;
  *   - JLine is isolated behind `ShellTerminal`: tests script its input queue
  *     and collect its output (ONE writer), asserting exact rendered lines;
  *   - coordination only via `eventually` on logically guaranteed conditions
  *     (generous outer bounds never assert on timing);
  *   - the LineageTab data seam is injected as PURE closures (the provider
  *     performs no IO by contract), so the projection text is fixed and renders
  *     are byte-for-byte comparable.
  *
  * The full LIN-04 acceptance (live graph from the session, the cli adapter,
  * PTY) is a separate card after LIN-03 full — not exercised here.
  */
object LineageTabSpec extends ZIOSpecDefault:

  /** Await a logically guaranteed condition, polling with real sleeps under a
    * generous outer bound (the bound never asserts, only fails hangs typed).
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

  /** Wall-clock guard around a whole test body (live clock, generous outer
    * bound): a runaway test fails typed instead of hanging the suite.
    */
  private def guarded(
      zio: ZIO[Any, Any, TestResult]
  ): ZIO[Any, Any, TestResult] =
    zio.timeout(Duration.fromSeconds(120)).flatMap {
      case Some(result) => ZIO.succeed(result)
      case None =>
        ZIO.fail(
          "test exceeded the 120s wall guard — hang or deadlock, not a timing assertion"
        )
    }

  private def fixed(choice: TabCloseChoice): CloseChoiceResolver =
    _ => ZIO.succeed(choice)

  /** Honest on-demand call counters for the two pure readers — the provider
    * must consult `connected` once per `:aux` and `projection` only when
    * available (never speculatively, never at all while unavailable).
    */
  private final class Counters:
    var connected: Int = 0
    var render: Int = 0

  /** The fixed, deterministic projection text (unicode + trailing newline — the
    * trailing newline is a terminator per the scrollback line semantics).
    */
  private val projectionText = "root\n└─ build [ok]\n   └─ test [queued]\n"

  private val lineageBlock =
    Vector(
      "aux tab [Lineage]:",
      "root",
      "└─ build [ok]",
      "   └─ test [queued]"
    )

  /** The settled bar block after `:new build` (focused, nothing published, no
    * unread badge). The status is the canonical StatusText line (RAI-097).
    */
  private val buildBarBlock =
    Vector("*1. build [queued | cost unknown]", TabBarRenderer.hintBar)

  private val sec15 = Duration.fromSeconds(15)

  /** Scripted terminal: input lines from a queue (FIFO), all printAbove output
    * into a queue — the ONE writer, captured for exact-line assertions.
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

  /** Run the real shell loop over the scripted terminal with the ONE auxiliary
    * tab registered; the body interacts by offering input lines and collecting
    * output. `:quit` is sent and the loop joined before the scope closes.
    */
  private def withShell(
      hub: EventHub,
      registry: SessionRegistry,
      resolver: CloseChoiceResolver,
      aux: Option[AuxTabProvider] = None
  )(
      body: (Queue[String], Queue[String]) => ZIO[Any, Any, TestResult]
  ): ZIO[Any, Any, TestResult] =
    ZIO.scoped {
      for
        in <- Queue.unbounded[String]
        out <- Queue.unbounded[String]
        terminal = ScriptedTerminal(in, out)
        fiber <- TabShell
          .run(
            hub,
            registry,
            terminal,
            resolver,
            TabShell.Config(),
            ShellViews.detached,
            aux
          )
          .fork
        result <- body(in, out)
        _ <- in.offer(":quit")
        _ <- fiber.join
      yield result
    }

  /** Drain rendered output into `seen` until the predicate holds. On timeout
    * the failure carries the captured output tail (diagnosis, not an assertion
    * on timing).
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
                "collectUntil bound exceeded; captured output tail: " +
                  v.takeRight(60).mkString(" || ")
              )
            else ZIO.sleep(Duration.fromMillis(20)) *> loop
        yield ()
      loop
    }

  def spec = suite("LineageTab (LIN-04B, LIN4-B-01..03)")(
    suite("executor (:aux)")(
      test(
        "LIN4-B-01: the provider satisfies AuxTabProvider and runs through ShellExecutor.run(:aux) like the others — typed AuxTab feedback, ONE availability check and ONE render per command, registry untouched"
      ) {
        live {
          guarded {
            val counters = Counters()
            for
              hub <- EventHub.make()
              reg <- SessionRegistry.make(hub)
              tab <- reg.create(JobId("linb-ex"), RootId("linb-root"), "build")
              before <- reg.tabs
              focusedBefore <- reg.focused
              p = LineageTab.provider(
                () => { counters.connected += 1; true },
                () => { counters.render += 1; projectionText }
              )
              shown <- ShellExecutor.run(
                ShellCommand.Aux,
                reg,
                fixed(TabCloseChoice.Hide),
                ShellViews.detached,
                Some(p)
              )
              after <- reg.tabs
              focusedAfter <- reg.focused
            yield assertTrue(
              p.title == "Lineage",
              shown == ShellFeedback.AuxTab("Lineage", projectionText),
              ShellFeedback.render(shown) == lineageBlock,
              counters.connected == 1,
              counters.render == 1,
              before == after,
              focusedBefore == focusedAfter,
              focusedAfter.contains(tab)
            )
          }
        }
      },
      test(
        "LIN4-B-02: an unavailable source is AuxTabUnavailable semantics — the typed hint, the projection is never consulted"
      ) {
        live {
          guarded {
            val counters = Counters()
            for
              hub <- EventHub.make()
              reg <- SessionRegistry.make(hub)
              p = LineageTab.provider(
                () => { counters.connected += 1; false },
                () => { counters.render += 1; projectionText }
              )
              shown <- ShellExecutor.run(
                ShellCommand.Aux,
                reg,
                fixed(TabCloseChoice.Hide),
                ShellViews.detached,
                Some(p)
              )
            yield assertTrue(
              shown == ShellFeedback.AuxTabUnavailable("Lineage"),
              ShellFeedback.render(shown) ==
                Vector("hint: auxiliary tab [Lineage] is not available"),
              counters.connected == 1,
              counters.render == 0
            )
          }
        }
      },
      test(
        "LIN4-B-02 (honest empty): a connected source with an empty or whitespace projection renders the typed empty-state line — never a blank screen"
      ) {
        val empty = LineageTab.provider(() => true, () => "")
        val blank =
          LineageTab.provider(() => true, () => "  \n\t")
        assertTrue(
          LineageTab.title == "Lineage",
          LineageTab.emptyProjection.nonEmpty,
          empty.available,
          empty.render == LineageTab.emptyProjection,
          blank.render == LineageTab.emptyProjection,
          ShellFeedback.render(
            ShellFeedback.AuxTab(empty.title, empty.render)
          ) ==
            Vector("aux tab [Lineage]:", LineageTab.emptyProjection)
        )
      },
      test(
        "LIN4-B-03: render is deterministic — repeated renders are byte-for-byte identical and repeated executor runs give identical feedback"
      ) {
        live {
          guarded {
            for
              hub <- EventHub.make()
              reg <- SessionRegistry.make(hub)
              p = LineageTab.provider(() => true, () => projectionText)
              first = p.render
              second = p.render
              run1 <- ShellExecutor.run(
                ShellCommand.Aux,
                reg,
                fixed(TabCloseChoice.Hide),
                ShellViews.detached,
                Some(p)
              )
              run2 <- ShellExecutor.run(
                ShellCommand.Aux,
                reg,
                fixed(TabCloseChoice.Hide),
                ShellViews.detached,
                Some(p)
              )
            yield assertTrue(
              first == second,
              first.getBytes("UTF-8").sameElements(second.getBytes("UTF-8")),
              first == projectionText,
              run1 == run2,
              run1 == ShellFeedback.AuxTab("Lineage", projectionText)
            )
          }
        }
      }
    ),
    test(
      "LIN4-B-01 (shell): the Lineage tab switches through the real shell loop like the other aux tabs — the provider is consulted on demand, the session ownership is untouched by the round trip"
    ) {
      live {
        guarded {
          val counters = Counters()
          for
            hub <- EventHub.make()
            reg <- SessionRegistry.make(hub)
            p = LineageTab.provider(
              () => { counters.connected += 1; true },
              () => { counters.render += 1; projectionText }
            )
            result <- withShell(hub, reg, fixed(TabCloseChoice.Hide), Some(p)) {
              (in, out) =>
                for
                  seen <- Ref.make(Vector.empty[String])
                  _ <- in.offer(":new build")
                  _ <- eventually(sec15)(reg.tabs.map(_.size == 1))
                  build <- reg.tabs.map(_.head)
                  focusedBefore <- reg.focused
                  tabsBefore <- reg.tabs
                  _ <- in.offer(":aux")
                  _ <- collectUntil(out, seen, sec15)(
                    _.containsSlice(lineageBlock)
                  )
                  focusedMid <- reg.focused
                  tabsMid <- reg.tabs
                  // switch back to the session view — ownership unchanged
                  _ <- in.offer(":tabs")
                  _ <- collectUntil(out, seen, sec15)(
                    _.containsSlice(buildBarBlock)
                  )
                  focusedAfter <- reg.focused
                  seenAll <- seen.get
                yield assertTrue(
                  seenAll.containsSlice(lineageBlock),
                  seenAll.count(_ == "aux tab [Lineage]:") == 1,
                  counters.render == 1,
                  focusedBefore == focusedMid,
                  focusedMid == focusedAfter,
                  focusedAfter.contains(build.id),
                  tabsBefore == tabsMid
                )
            }
          yield result
        }
      }
    }
  )

end LineageTabSpec
