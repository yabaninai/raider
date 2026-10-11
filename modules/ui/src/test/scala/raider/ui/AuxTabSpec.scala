package raider.ui

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.events.{EventError, EventHub, EventPayload, WaitReason}
import zio.test.{live, *}
import zio.{Duration, IO, Queue, Ref, Task, UIO, ZIO}

/** LIN-04A: the minimal tab-host seam for the ONE auxiliary tab (ADR-027 §3.3)
  * — acceptance LIN4-A-01..03.
  *
  * Determinism rules (same discipline as TabShellSpec):
  *   - real `EventHub` + real `SessionRegistry` — no mocks, no second runtime;
  *   - JLine is isolated behind `ShellTerminal`: tests script its input queue
  *     and collect its output (ONE writer), asserting exact rendered lines;
  *   - coordination only via `eventually` on logically guaranteed conditions
  *     (generous outer bounds never assert on timing);
  *   - LIN4-A-03 repeats the PSH-03 pattern: the aux screen must arrive as ONE
  *     printAbove block while a real hub burst is being delivered.
  */
object AuxTabSpec extends ZIOSpecDefault:

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

  private def publish(
      hub: EventHub,
      jobId: JobId,
      payload: EventPayload
  ): IO[EventError, Unit] =
    hub.publish(
      jobId,
      RootId(s"root-of-${jobId.value}"),
      SessionId(s"sess-of-${jobId.value}"),
      payload
    )

  private def diag(text: String): EventPayload = EventPayload.Diagnostics(text)

  private val sec15 = Duration.fromSeconds(15)
  private val sec30 = Duration.fromSeconds(30)

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

  /** Run the real shell loop over the scripted terminal with ONE auxiliary tab
    * registered; the body interacts by offering input lines and collecting
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

  private def fixed(choice: TabCloseChoice): CloseChoiceResolver =
    _ => ZIO.succeed(choice)

  /** Provider fixture: fixed answers plus an honest on-demand call counter —
    * `render` must be invoked once per switch, never speculatively, and never
    * at all while `available` is false.
    */
  private final class CountingProvider(
      val title: String,
      val available: Boolean,
      content: String
  ) extends AuxTabProvider:
    var calls: Int = 0

    def render: String =
      calls = calls + 1
      content

  private val lineageContent = "node a\nnode b\n"

  private val lineageBlock =
    Vector("aux tab [lineage]:", "node a", "node b")

  /** The settled bar block after `:new build` (focused, nothing published, no
    * unread badge). The status is the canonical StatusText line (RAI-097).
    */
  private val buildBarBlock =
    Vector("*1. build [queued | cost unknown]", TabBarRenderer.hintBar)

  def spec = suite("AuxTab (LIN-04A, LIN4-A-01..03)")(
    suite("router/renderer/feedback (pure)")(
      test(
        ":aux routes with and without open tabs and tolerates extra input; a near-miss is a typed unknown-command error whose hints list :aux"
      ) {
        val unknown = CommandRouter.parse(":auxx", Nil, None)
        val hints = unknown.left.toOption.get.hints.mkString(" ")
        assertTrue(
          CommandRouter.parse(":aux", Nil, None) == Right(ShellCommand.Aux),
          CommandRouter.parse(":aux", Nil, Some(TabId("t1"))) ==
            Right(ShellCommand.Aux),
          CommandRouter.parse(":aux extra", Nil, None) ==
            Right(ShellCommand.Aux),
          unknown == Left(ShellInputError.UnknownCommand(":auxx")),
          hints.contains(":aux")
        )
      },
      test(
        "line splitting: LF, CRLF normalized, trailing newline is a terminator; the screen is header + content lines"
      ) {
        assertTrue(
          AuxTabRenderer.lines("a\nb") == Vector("a", "b"),
          AuxTabRenderer.lines("a\r\nb") == Vector("a", "b"),
          AuxTabRenderer.lines("a\n") == Vector("a"),
          AuxTabRenderer.lines("") == Vector(""),
          AuxTabRenderer.screen("lineage", lineageContent) == lineageBlock
        )
      },
      test(
        "feedback rendering: the aux screen, the unregistered tab and the unavailable tab are typed — never an empty screen"
      ) {
        val missing = ShellFeedback.render(ShellFeedback.AuxTabMissing)
        val unavailable =
          ShellFeedback.render(ShellFeedback.AuxTabUnavailable("lineage"))
        assertTrue(
          ShellFeedback
            .render(ShellFeedback.AuxTab("lineage", lineageContent)) ==
            lineageBlock,
          missing == Vector("hint: no auxiliary tab is registered"),
          unavailable == Vector(
            "hint: auxiliary tab [lineage] is not available"
          ),
          missing.nonEmpty && unavailable.nonEmpty
        )
      },
      test(
        ":help and the hint bar list :aux (palette discoverability)"
      ) {
        assertTrue(
          TabBarRenderer.helpLines.exists(_.contains(":aux")),
          TabBarRenderer.hintBar.contains(":aux")
        )
      },
      test(
        "executor: Aux never touches the registry (no focus/unread/draft change, no hub call); provider data only"
      ) {
        live {
          guarded {
            for
              hub <- EventHub.make()
              reg <- SessionRegistry.make(hub)
              tab <- reg.create(JobId("aux-ex"), RootId("aux-root"), "build")
              before <- reg.tabs
              focusedBefore <- reg.focused
              p = CountingProvider("lineage", true, lineageContent)
              shown <- ShellExecutor.run(
                ShellCommand.Aux,
                reg,
                fixed(TabCloseChoice.Hide),
                ShellViews.detached,
                Some(p)
              )
              missing <- ShellExecutor.run(
                ShellCommand.Aux,
                reg,
                fixed(TabCloseChoice.Hide)
              )
              after <- reg.tabs
              focusedAfter <- reg.focused
            yield assertTrue(
              shown == ShellFeedback.AuxTab("lineage", lineageContent),
              p.calls == 1,
              missing == ShellFeedback.AuxTabMissing,
              before == after,
              focusedBefore == focusedAfter,
              focusedAfter.contains(tab)
            )
          }
        }
      }
    ),
    test(
      "LIN4-A-01: registration + switching + render through the shell — the provider is consulted on demand, the session ownership is untouched by the round trip"
    ) {
      live {
        guarded {
          for
            hub <- EventHub.make()
            reg <- SessionRegistry.make(hub)
            p = CountingProvider("lineage", true, lineageContent)
            result <- withShell(hub, reg, fixed(TabCloseChoice.Hide), Some(p)) {
              (in, out) =>
                for
                  seen <- Ref.make(Vector.empty[String])
                  _ <- in.offer(":new build")
                  _ <- eventually(sec15)(reg.tabs.map(_.size == 1))
                  build <- reg.tabs.map(_.head)
                  _ <- in.offer("hello")
                  _ <- eventually(sec15)(
                    reg.draftGet(build.id).map(_ == "hello")
                  )
                  focusedBefore <- reg.focused
                  tabsBefore <- reg.tabs
                  unreadBefore <- reg.tabs.map(
                    _.find(_.id == build.id).get.unreadCount
                  )
                  _ <- in.offer(":aux")
                  _ <- collectUntil(out, seen, sec15)(
                    _.containsSlice(lineageBlock)
                  )
                  focusedMid <- reg.focused
                  tabsMid <- reg.tabs
                  unreadMid <- reg.tabs.map(
                    _.find(_.id == build.id).get.unreadCount
                  )
                  draftMid <- reg.draftGet(build.id)
                  // switch back to the session view — ownership unchanged
                  _ <- in.offer(":tabs")
                  _ <- collectUntil(out, seen, sec15)(
                    _.containsSlice(buildBarBlock)
                  )
                  focusedAfter <- reg.focused
                  // input routing unchanged: drafts still go to the focused
                  // session tab after the aux round trip
                  _ <- in.offer(" world")
                  _ <- eventually(sec15)(
                    reg.draftGet(build.id).map(_ == "hello world")
                  )
                  seenAll <- seen.get
                yield assertTrue(
                  seenAll.containsSlice(lineageBlock),
                  seenAll.count(_ == "aux tab [lineage]:") == 1,
                  p.calls == 1,
                  focusedBefore == focusedMid,
                  focusedMid == focusedAfter,
                  focusedAfter.contains(build.id),
                  tabsBefore == tabsMid,
                  unreadBefore == unreadMid,
                  draftMid == "hello",
                  seenAll.containsSlice(buildBarBlock)
                )
            }
          yield result
        }
      }
    },
    test(
      "LIN4-A-02: unavailable provider → the typed 'not available' hint (render never consulted); no provider registered → the typed 'not registered' hint — session untouched in both"
    ) {
      live {
        guarded {
          for
            hub <- EventHub.make()
            reg <- SessionRegistry.make(hub)
            p = CountingProvider("lineage", false, "must not be read")
            resultUnavailable <- withShell(
              hub,
              reg,
              fixed(TabCloseChoice.Hide),
              Some(p)
            ) { (in, out) =>
              for
                seen <- Ref.make(Vector.empty[String])
                _ <- in.offer(":new build")
                _ <- eventually(sec15)(reg.tabs.map(_.size == 1))
                build <- reg.tabs.map(_.head)
                focusedBefore <- reg.focused
                _ <- in.offer(":aux")
                _ <- collectUntil(out, seen, sec15)(
                  _.exists(
                    _ == "hint: auxiliary tab [lineage] is not available"
                  )
                )
                focusedAfter <- reg.focused
                seenAll <- seen.get
              yield assertTrue(
                seenAll.exists(
                  _ == "hint: auxiliary tab [lineage] is not available"
                ),
                seenAll.count(
                  _ == "hint: auxiliary tab [lineage] is not available"
                ) == 1,
                p.calls == 0,
                focusedBefore == focusedAfter,
                focusedAfter.contains(build.id)
              )
            }
            resultMissing <- withShell(hub, reg, fixed(TabCloseChoice.Hide)) {
              (in, out) =>
                for
                  seen <- Ref.make(Vector.empty[String])
                  _ <- in.offer(":aux")
                  _ <- collectUntil(out, seen, sec15)(
                    _.exists(_ == "hint: no auxiliary tab is registered")
                  )
                  seenAll <- seen.get
                yield assertTrue(
                  seenAll.exists(_ == "hint: no auxiliary tab is registered"),
                  seenAll.count(
                    _ == "hint: no auxiliary tab is registered"
                  ) == 1
                )
            }
          yield resultUnavailable && resultMissing
        }
      }
    },
    test(
      "LIN4-A-03: the aux screen rendered while a real hub burst is being delivered stays whole — ONE printAbove block (atomic lines, ONE writer), the burst itself is fully delivered"
    ) {
      live {
        guarded {
          for
            hub <- EventHub.make()
            reg <- SessionRegistry.make(hub)
            p = CountingProvider("lineage", true, lineageContent)
            result <- withShell(hub, reg, fixed(TabCloseChoice.Hide), Some(p)) {
              (in, out) =>
                for
                  seen <- Ref.make(Vector.empty[String])
                  _ <- in.offer(":new build")
                  _ <- eventually(sec15)(reg.tabs.map(_.size == 1))
                  build <- reg.tabs.map(_.head)
                  _ <- collectUntil(out, seen, sec15)(v =>
                    v.containsSlice(buildBarBlock)
                  )
                  // 100 events delivered while the aux screen renders (the
                  // pump re-renders the bar per envelope — concurrent
                  // ONE-writer traffic); sequential per job so delivery is
                  // deterministic
                  publisher <- ZIO
                    .foreachDiscard(1 to 100) { i =>
                      publish(
                        hub,
                        build.jobId,
                        if i % 2 == 0 then diag(s"burst line $i")
                        else EventPayload.Waiting(WaitReason.Slot)
                      )
                    }
                    .fork
                  _ <- in.offer(":aux")
                  _ <- collectUntil(out, seen, sec15)(
                    _.containsSlice(lineageBlock)
                  )
                  _ <- publisher.join
                  // delivery to the registry subscription settles after join
                  _ <- eventually(sec30)(
                    reg
                      .history(build.id)
                      .map(h => h.entries.length == 101 && h.evicted == 0L)
                  )
                  h <- reg.history(build.id)
                  seenAll <- seen.get
                yield assertTrue(
                  seenAll.containsSlice(lineageBlock),
                  // the command ran ONCE — no duplicated/torn screen header
                  seenAll.count(_ == "aux tab [lineage]:") == 1,
                  p.calls == 1,
                  // the burst was fully delivered through the real hub
                  h.evicted == 0L,
                  h.entries.length == 101,
                  h.entries.last.lastSeq == 100L
                )
            }
          yield result
        }
      }
    }
  )

end AuxTabSpec
