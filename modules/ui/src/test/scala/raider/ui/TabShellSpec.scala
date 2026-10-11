package raider.ui

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.events.{
  EventError,
  EventHub,
  EventPayload,
  LineTail,
  SessionReducer,
  StatusText,
  WaitReason
}
import raider.runtime.jobs.JobStatus
import zio.test.{live, *}
import zio.{Duration, IO, Queue, Ref, Task, UIO, ZIO}

/** RAI-061: inline tab shell acceptance SHELL-01..03 (m2-p2.md RAI-061,
  * ADR-016/ADR-024), plus direct router/renderer/executor/resolver suites.
  *
  * Determinism rules (same discipline as SessionRegistrySpec):
  *   - real `EventHub` + real `SessionRegistry` — no mocks, no second runtime;
  *   - JLine is isolated behind `ShellTerminal`: tests script its input queue
  *     and collect its output (ONE writer), asserting exact rendered lines;
  *   - coordination only via `eventually` on logically guaranteed conditions
  *     (generous outer bounds never assert on timing);
  *   - focus switching is ONE executor call per command, concurrently with
  *     publisher fibers in SHELL-03.
  */
object TabShellSpec extends ZIOSpecDefault:

  private val jobA = JobId("shell-job-a")

  /** Await a logically guaranteed condition, polling with real sleeps under a
    * generous outer bound (the bound never asserts, only fails hangs typed).
    */
  private def eventually(
      max: Duration
  )(cond: ZIO[Any, Any, Boolean]): ZIO[Any, Any, Unit] =
    eventuallyL("unlabeled", max)(cond)

  private def eventuallyL(
      label: String,
      max: Duration
  )(cond: ZIO[Any, Any, Boolean]): ZIO[Any, Any, Unit] =
    ZIO.suspendSucceed {
      val deadline = System.nanoTime() + max.toNanos
      def loop: ZIO[Any, Any, Unit] =
        cond.flatMap {
          case true => ZIO.unit
          case false =>
            if System.nanoTime() >= deadline then
              ZIO.fail(s"condition not met within the bound: $label")
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

  /** Run the real shell loop over the scripted terminal; the body interacts by
    * offering input lines and collecting output. `:quit` is sent and the loop
    * joined before the scope closes (which also interrupts the bar pump).
    */
  private def withShell(
      hub: EventHub,
      registry: SessionRegistry,
      resolver: CloseChoiceResolver,
      views: ShellViews = ShellViews.detached
  )(
      body: (Queue[String], Queue[String]) => ZIO[Any, Any, TestResult]
  ): ZIO[Any, Any, TestResult] =
    ZIO.scoped {
      for
        in <- Queue.unbounded[String]
        out <- Queue.unbounded[String]
        terminal = ScriptedTerminal(in, out)
        fiber <- TabShell
          .run(hub, registry, terminal, resolver, TabShell.Config(), views)
          .fork
        result <- body(in, out)
        _ <- in.offer(":quit")
        _ <- fiber.join
      yield result
    }

  /** Drain rendered output into `seen` until the predicate holds. */
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

  private def view(
      id: String,
      label: String,
      status: JobStatus,
      unread: Long
  ): TabView =
    // The status line is the ONE canonical StatusText render (RAI-097) over a
    // synthetic snapshot carrying the requested status — never a local string.
    val snap = SessionReducer
      .initial(JobId(s"job-$id"), RootId(s"root-$id"), SessionId(""))
      .copy(status = status)
    TabView(
      TabId(id),
      label,
      JobId(s"job-$id"),
      RootId(s"root-$id"),
      status,
      StatusText.render(snap),
      unread,
      0L
    )

  def spec = suite("TabShell (RAI-061, SHELL-01..03)")(
    suite("router (pure)")(
      test(
        "unknown command / missing argument / no tabs are typed errors with hints; non-command input is a Draft"
      ) {
        val tabs = List(
          view("t1", "job-a", JobStatus.Queued, 0L),
          view("t2", "job-b", JobStatus.Running, 3L)
        )
        val focused = Some(TabId("t1"))
        val unknown = CommandRouter.parse(":bogus arg", tabs, focused)
        val noArgs = CommandRouter.parse(":new", tabs, focused)
        val noSwitchArg = CommandRouter.parse(":switch", tabs, focused)
        val draft = CommandRouter.parse("hello world", tabs, focused)
        val draftSpaced = CommandRouter.parse("hello world ", tabs, focused)
        val blank = CommandRouter.parse("   ", tabs, focused)
        assertTrue(
          unknown == Left(
            ShellInputError.UnknownCommand(":bogus")
          ),
          unknown.left.toOption.get.hints.nonEmpty,
          noArgs == Left(
            ShellInputError.MissingArgument(":new", ":new <label>")
          ),
          noSwitchArg == Left(
            ShellInputError.MissingArgument(":switch", ":switch <n|label>")
          ),
          draft == Right(ShellCommand.Draft("hello world")),
          blank == Right(ShellCommand.Draft("   ")),
          draftSpaced == Right(ShellCommand.Draft("hello world "))
        )
      },
      test(
        ":switch resolves 1-based number, exact label and unique label prefix; bad/ambiguous/unknown refs are typed errors listing alternatives"
      ) {
        val tabs = List(
          view("t1", "job-a", JobStatus.Queued, 0L),
          view("t2", "job-b", JobStatus.Running, 0L)
        )
        val lineage = List(
          view("t1", "job-a", JobStatus.Queued, 0L),
          view("t2", "job-b", JobStatus.Running, 0L)
        )
        val focused = Some(TabId("t1"))
        assertTrue(
          CommandRouter.parse(":switch 2", tabs, focused) ==
            Right(ShellCommand.Switch(TabId("t2"))),
          CommandRouter.parse(":switch job-b", tabs, focused) ==
            Right(ShellCommand.Switch(TabId("t2"))),
          CommandRouter.parse(":switch job-b", lineage, focused) ==
            Right(ShellCommand.Switch(TabId("t2"))),
          CommandRouter.parse(":switch 0", tabs, focused) ==
            Left(ShellInputError.BadTabIndex("0", 2)),
          CommandRouter.parse(":switch 3", tabs, focused) ==
            Left(ShellInputError.BadTabIndex("3", 2)),
          CommandRouter.parse(":switch job", tabs, focused) ==
            Left(
              ShellInputError.AmbiguousTabLabel("job", Vector("job-a", "job-b"))
            ),
          CommandRouter.parse(":switch nope", tabs, focused) ==
            Left(
              ShellInputError.UnknownTabLabel("nope", Vector("job-a", "job-b"))
            )
        )
      },
      test(
        ":close resolves the focused tab; :rename needs a label and open tabs; every command without tabs is typed"
      ) {
        val tabs = List(view("t1", "job-a", JobStatus.Queued, 0L))
        assertTrue(
          CommandRouter.parse(":close", tabs, Some(TabId("t1"))) ==
            Right(ShellCommand.Close(TabId("t1"))),
          CommandRouter.parse(":close", Nil, None) ==
            Left(ShellInputError.NoOpenTabs(":close")),
          CommandRouter.parse(":close", tabs, None) ==
            Left(ShellInputError.NoOpenTabs(":close")),
          CommandRouter.parse(":rename x", Nil, None) ==
            Left(ShellInputError.NoOpenTabs(":rename")),
          CommandRouter.parse(":rename", tabs, Some(TabId("t1"))) ==
            Left(ShellInputError.MissingArgument(":rename", ":rename <label>")),
          CommandRouter.parse(":next", Nil, None) == Right(ShellCommand.Next),
          CommandRouter.parse(":prev", Nil, None) == Right(ShellCommand.Prev),
          CommandRouter.parse(":tabs", Nil, None) == Right(ShellCommand.Tabs),
          CommandRouter.parse(":help", Nil, None) == Right(ShellCommand.Help),
          CommandRouter.parse(":quit", Nil, None) == Right(ShellCommand.Quit)
        )
      }
    ),
    suite("renderer (pure)")(
      test(
        "tab bar: creation order, focus marker, status, unread badge (hidden at 0), hint bar; empty registry renders one honest line"
      ) {
        val bar = TabBarRenderer.tabBar(
          List(
            view("t1", "job-a", JobStatus.Queued, 0L),
            view("t2", "job-b", JobStatus.Running, 3L)
          ),
          Some(TabId("t2"))
        )
        assertTrue(
          TabBarRenderer.tabBar(Nil, None) ==
            Vector("no open tabs (start one with :new <label>)"),
          // the status line is the canonical StatusText render (RAI-097) —
          // the same literal fixture line as the CLI StatusTextSpec (UNI-01)
          bar(0) == " 1. job-a [queued | cost unknown]",
          bar(1) == "*2. job-b [running | cost unknown] (3 unread)",
          bar(2) == TabBarRenderer.hintBar
        )
      },
      test(
        "feedback rendering: hints, typed errors, cancel report (never self-cancelled), draft echo"
      ) {
        val hint = ShellFeedback.render(
          ShellFeedback.Hint(ShellInputError.UnknownCommand(":x"))
        )
        val failure =
          ShellFeedback.render(ShellFeedback.Failure(RegistryError.NoTabs))
        val cancel = ShellFeedback.render(
          ShellFeedback.CancelReported(
            TabId("t1"),
            "build",
            List((TabId("t2"), "child"))
          )
        )
        val echo = ShellFeedback.render(
          ShellFeedback.DraftEchoed(TabId("t1"), "build", "hello", "hello")
        )
        assertTrue(
          hint.head == "hint: unknown command: :x",
          hint.tail.head.startsWith("  available commands: "),
          failure == Vector("error: no tabs are open"),
          cancel.head.contains("cancel requested for: build"),
          cancel.head.contains("NOT self-cancelled"),
          cancel.exists(_ == "affected same-root tabs:"),
          cancel.exists(_.startsWith("  - child (")),
          echo == Vector("echo: hello", "draft [build]: hello"),
          ShellFeedback.render(ShellFeedback.Quit).isEmpty
        )
      },
      test(":help lists the whole palette (SHELL-02 palette search)") {
        val body = TabBarRenderer.helpLines.mkString("\n")
        assertTrue(
          CommandRouter.commands.forall(c => body.contains(c)),
          body.contains(":new <label>"),
          body.contains("RAI-062")
        )
      }
    ),
    suite("executor (typed failures)")(
      test(
        "registry failures never escape: they surface as typed Failure feedback; unknown tab close/rename are typed"
      ) {
        live {
          guarded {
            for
              hub <- EventHub.make()
              reg <- SessionRegistry.make(hub)
              draftNoTabs <- ShellExecutor.run(
                ShellCommand.Draft("x"),
                reg,
                fixed(TabCloseChoice.Hide)
              )
              renameNoTabs <- ShellExecutor.run(
                ShellCommand.Rename("x"),
                reg,
                fixed(TabCloseChoice.Hide)
              )
              a <- reg.create(jobA, RootId("solo"), "a")
              _ <- reg.hide(a)
              closeStale <- ShellExecutor.run(
                ShellCommand.Close(a),
                reg,
                fixed(TabCloseChoice.Hide)
              )
              renameUnknown <- reg.rename(TabId("missing"), "x").either
              quit <- ShellExecutor.run(
                ShellCommand.Quit,
                reg,
                fixed(TabCloseChoice.Hide)
              )
              help <- ShellExecutor.run(
                ShellCommand.Help,
                reg,
                fixed(TabCloseChoice.Hide)
              )
            yield assertTrue(
              draftNoTabs == ShellFeedback.Failure(RegistryError.NoTabs),
              renameNoTabs == ShellFeedback.Failure(RegistryError.NoTabs),
              closeStale == ShellFeedback.Failure(RegistryError.UnknownTab(a)),
              renameUnknown == Left(RegistryError.UnknownTab(TabId("missing"))),
              quit == ShellFeedback.Quit,
              help == ShellFeedback.Help(TabBarRenderer.helpLines)
            )
          }
        }
      }
    ),
    suite("close dialogue resolver")(
      test(
        "h/hide → Hide, c/cancel → CancelRequested; invalid answers re-asked, then the safe default (cancel-request, report only)"
      ) {
        live {
          for
            in <- Queue.unbounded[String]
            out <- Queue.unbounded[String]
            terminal = ScriptedTerminal(in, out)
            resolver =
              CloseChoiceResolver.fromTerminal(terminal, "choose? ")
            running: CloseDecision.RunningChoice = CloseDecision.RunningChoice(
              Nil
            )
            _ <- in.offer("c")
            r1 <- resolver.resolve(running)
            _ <- in.offer("h")
            r2 <- resolver.resolve(running)
            _ <- in.offerAll(Vector("junk", "junk", "junk"))
            r3 <- resolver.resolve(running)
            hint <- out.takeAll
          yield assertTrue(
            r1 == TabCloseChoice.CancelRequested,
            r2 == TabCloseChoice.Hide,
            r3 == TabCloseChoice.CancelRequested,
            hint.exists(_.contains("defaulting to cancel-request"))
          )
        }
      }
    ),
    suite("screens (RAI-088, PSH-01..03)")(
      test(
        "PSH-01: :plugins routes without tabs or arguments; the render is a name/version/active table, empty list renders (no plugins); unknown near-misses stay typed"
      ) {
        val filled = ShellFeedback.render(
          ShellFeedback.Plugins(
            List(
              PluginView("build", "1.2.0", true),
              PluginView("repl-tools", "0.3.1", false)
            )
          )
        )
        val unknown = CommandRouter.parse(":pluginz", Nil, None)
        val hints = unknown.left.toOption.get.hints.mkString(" ")
        assertTrue(
          CommandRouter.parse(":plugins", Nil, None) ==
            Right(ShellCommand.Plugins),
          CommandRouter.parse(":plugins extra", Nil, None) ==
            Right(ShellCommand.Plugins),
          filled == Vector(
            "plugins (2):",
            "plugin      version  active",
            "build       1.2.0    yes",
            "repl-tools  0.3.1    no"
          ),
          ShellFeedback.render(ShellFeedback.Plugins(Nil)) ==
            Vector("(no plugins)"),
          unknown == Left(ShellInputError.UnknownCommand(":pluginz")),
          hints.contains(":plugins") && hints.contains(":state")
        )
      },
      test(
        "PSH-02: :state needs a scope (typed usage); attached store renders usage/quota lines; a missing store is the typed 'state store not attached' hint — executor-mapped from the provider"
      ) {
        live {
          guarded {
            for
              hub <- EventHub.make()
              reg <- SessionRegistry.make(hub)
              views = ShellViews(
                () => Nil,
                {
                  case "main" => Some(StateView(5L, 1024L, 100L, 65536L))
                  case _      => None
                }
              )
              resolver = fixed(TabCloseChoice.Hide)
              state <- ShellExecutor.run(
                ShellCommand.State("main"),
                reg,
                resolver,
                views
              )
              otherScope <- ShellExecutor.run(
                ShellCommand.State("other"),
                reg,
                resolver,
                views
              )
              detached <- ShellExecutor.run(
                ShellCommand.State("main"),
                reg,
                resolver,
                ShellViews.detached
              )
            yield assertTrue(
              CommandRouter.parse(":state", Nil, None) ==
                Left(
                  ShellInputError.MissingArgument(":state", ":state <scope>")
                ),
              CommandRouter.parse(":state main", Nil, None) ==
                Right(ShellCommand.State("main")),
              state == ShellFeedback.State(
                "main",
                StateView(5L, 1024L, 100L, 65536L)
              ),
              ShellFeedback.render(state) == Vector(
                "state [main]:",
                "  usage: 5 keys, 1024 bytes",
                "  quota: 100 keys, 65536 bytes"
              ),
              otherScope == ShellFeedback.StateStoreMissing("other"),
              ShellFeedback.render(otherScope) == Vector(
                "hint: state store not attached (scope: other)"
              ),
              detached == ShellFeedback.StateStoreMissing("main")
            )
          }
        }
      },
      test(
        "PSH-03: :plugins/:state rendered while a real hub burst is being delivered stay whole — each screen arrives as ONE printAbove block (atomic lines, ONE writer), the burst itself is fully delivered"
      ) {
        live {
          guarded {
            for
              hub <- EventHub.make()
              reg <- SessionRegistry.make(hub)
              views = ShellViews(
                () =>
                  List(
                    PluginView("build", "1.2.0", true),
                    PluginView("repl-tools", "0.3.1", false)
                  ),
                {
                  case "main" => Some(StateView(5L, 1024L, 100L, 65536L))
                  case _      => None
                }
              )
              pluginBlock = ShellScreens.plugins(views.plugins())
              stateBlock = ShellScreens.state(
                "main",
                StateView(5L, 1024L, 100L, 65536L)
              )
              result <- withShell(
                hub,
                reg,
                fixed(TabCloseChoice.Hide),
                views
              ) { (in, out) =>
                for
                  seen <- Ref.make(Vector.empty[String])
                  _ <- in.offer(":new build")
                  _ <- eventually(sec15)(reg.tabs.map(_.size == 1))
                  buildView <- reg.tabs.map(_.head)
                  _ <- collectUntil(out, seen, sec15)(
                    _.exists(l =>
                      l.startsWith("1. build") || l.startsWith("*1. build")
                    )
                  )
                  // 100 events delivered while the screens render (the pump
                  // re-renders the bar per envelope — concurrent ONE-writer
                  // traffic); sequential per job so delivery is deterministic
                  publisher <- ZIO
                    .foreachDiscard(1 to 100) { i =>
                      publish(
                        hub,
                        buildView.jobId,
                        if i % 2 == 0 then diag(s"burst line $i")
                        else EventPayload.Waiting(WaitReason.Slot)
                      )
                    }
                    .fork
                  _ <- in.offerAll(Vector(":plugins", ":state main"))
                  _ <- collectUntil(out, seen, sec15)(v =>
                    v.containsSlice(pluginBlock) && v.containsSlice(stateBlock)
                  )
                  _ <- publisher.join
                  // delivery to the registry subscription settles after join
                  _ <- eventually(sec30)(
                    reg
                      .history(buildView.id)
                      .map(h => h.entries.length == 101 && h.evicted == 0L)
                  )
                  h <- reg.history(buildView.id)
                  seenAll <- seen.get
                yield assertTrue(
                  seenAll.containsSlice(pluginBlock),
                  seenAll.containsSlice(stateBlock),
                  // the command ran ONCE — no duplicated/torn screen header
                  seenAll.count(_ == "plugins (2):") == 1,
                  seenAll.count(_ == "state [main]:") == 1,
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
    ),
    test(
      "SHELL-01: :new/:next/:prev through the shell — draft/history/scrollback byte-identical across switches; unread grows while unfocused and resets on focus; the bar shows label+status+unread"
    ) {
      live {
        guarded {
          for
            hub <- EventHub.make()
            reg <- SessionRegistry.make(hub)
            result <- withShell(hub, reg, fixed(TabCloseChoice.Hide)) {
              (in, out) =>
                for
                  seen <- Ref.make(Vector.empty[String])
                  _ <- in.offerAll(Vector(":new build", ":new repl"))
                  _ <- eventuallyL("tabs-size-2", sec15)(
                    reg.tabs.map(_.size == 2)
                  )
                  views <- reg.tabs
                  build = views.find(_.label == "build").get
                  repl = views.find(_.label == "repl").get
                  focusedAfterNew <- reg.focused
                  _ <- collectUntil(out, seen, sec15)(
                    _.exists(_.startsWith("tab created: build"))
                  )
                  // draft input goes to the focused tab (repl — created last)
                  _ <- in.offerAll(Vector("hello ", "world"))
                  _ <- eventuallyL("draft-hello-world", sec15)(
                    reg.draftGet(repl.id).map(_ == "hello world")
                  )
                  // 4 events for build while it is NOT focused (mixed classes)
                  _ <- publish(hub, build.jobId, diag("line-1"))
                  _ <- publish(hub, build.jobId, diag("line-2"))
                  _ <- publish(
                    hub,
                    build.jobId,
                    EventPayload.ToolProgress(
                      "c1",
                      "bash",
                      LineTail(Vector("p1", "p2"), 0, 0L)
                    )
                  )
                  _ <- publish(
                    hub,
                    build.jobId,
                    EventPayload.Waiting(WaitReason.Slot)
                  )
                  _ <- eventuallyL("build-lastseq-4", sec15)(
                    reg.snapshot(build.id).map(_.lastSeq == 4L)
                  )
                  // unread settles in the registry (all 4 bumps applied)
                  _ <- eventuallyL("build-unread-4", sec15)(
                    reg.tabs.map(
                      _.find(_.id == build.id).exists(_.unreadCount == 4L)
                    )
                  )
                  // the pump rendered the bar on hub events (label rows only
                  // come from bar renders — ONE writer)
                  _ <- collectUntil(out, seen, sec15)(
                    _.exists(_.contains("1. build"))
                  )
                  // deterministic settled-state render through :tabs
                  _ <- in.offer(":tabs")
                  _ <- collectUntil(out, seen, sec15)(
                    _.exists(l =>
                      l.trim.startsWith("1. build") && l.contains("(4 unread)")
                    )
                  )
                  // byte-identical capture BEFORE the switch
                  draftBefore <- reg.draftGet(repl.id)
                  histBefore <- reg.history(build.id)
                  scrBefore <- reg.scrollback(build.id)
                  // ONE command switch: :next from repl wraps to build
                  _ <- in.offer(":next")
                  _ <- eventuallyL("focused-build-after-next", sec15)(
                    reg.focused.map(_.contains(build.id))
                  )
                  draftAfter <- reg.draftGet(repl.id)
                  histAfter <- reg.history(build.id)
                  scrAfter <- reg.scrollback(build.id)
                  tabsMid <- reg.tabs
                  // settled-state bar: focus marker moved, Waiting status, no
                  // badge (unread was reset by the focus)
                  _ <- in.offer(":tabs")
                  _ <- collectUntil(out, seen, sec15)(
                    _.exists(l =>
                      l.startsWith("*1. build") && l.contains(
                        "[waiting(slot) | cost unknown]"
                      )
                    )
                  )
                  // :prev returns to repl — still ONE command
                  _ <- in.offer(":prev")
                  _ <- eventuallyL("focused-repl-after-prev", sec15)(
                    reg.focused.map(_.contains(repl.id))
                  )
                  draftReplFinal <- reg.draftGet(repl.id)
                  _ <- in.offer(":tabs")
                  _ <- collectUntil(out, seen, sec15)(
                    _.exists(l =>
                      l.startsWith("*2. repl") && l.endsWith(
                        "[queued | cost unknown]"
                      )
                    )
                  )
                  seenAll <- seen.get
                yield assertTrue(
                  focusedAfterNew == Some(repl.id),
                  draftBefore == "hello world",
                  draftAfter == draftBefore,
                  draftReplFinal == draftBefore,
                  histBefore == histAfter,
                  scrBefore == scrAfter,
                  scrAfter.lines == Vector("line-1", "line-2", "p1", "p2"),
                  tabsMid.find(_.id == build.id).get.unreadCount == 0L,
                  tabsMid
                    .find(_.id == build.id)
                    .get
                    .status == JobStatus.Waiting,
                  tabsMid.find(_.id == repl.id).get.unreadCount == 0L,
                  seenAll.exists(_.startsWith("tabs: :tabs"))
                )
            }
          yield result
        }
      }
    },
    test(
      "SHELL-02: :help lists the palette; unknown command/tab → typed hint; typed error line when a command hits no tabs"
    ) {
      live {
        guarded {
          for
            hub <- EventHub.make()
            reg <- SessionRegistry.make(hub)
            result <- withShell(hub, reg, fixed(TabCloseChoice.Hide)) {
              (in, out) =>
                for
                  seen <- Ref.make(Vector.empty[String])
                  // draft BEFORE any tab: typed registry failure rendered
                  _ <- in.offer("hello")
                  _ <- collectUntil(out, seen, sec15)(
                    _.exists(_ == "error: no tabs are open")
                  )
                  _ <- in.offer(":help")
                  _ <- collectUntil(out, seen, sec15)(v =>
                    CommandRouter.commands.forall(c => v.exists(_.contains(c)))
                  )
                  _ <- in.offer(":bogus arg")
                  _ <- collectUntil(out, seen, sec15)(
                    _.exists(_ == "hint: unknown command: :bogus")
                  )
                  _ <- in.offer(":new build")
                  _ <- eventually(sec15)(reg.tabs.map(_.size == 1))
                  _ <- in.offer(":switch nosuch")
                  _ <- collectUntil(out, seen, sec15)(
                    _.exists(_ == "hint: unknown tab: nosuch")
                  )
                  _ <- in.offer(":rename renamed")
                  _ <- eventually(sec15)(
                    reg.tabs.map(_.headOption.exists(_.label == "renamed"))
                  )
                  _ <- collectUntil(out, seen, sec15)(
                    _.exists(_ == "renamed to: renamed")
                  )
                  _ <- in.offer(":rename")
                  _ <- collectUntil(out, seen, sec15)(
                    _.exists(_ == "hint: missing argument for :rename")
                  )
                  seenAll <- seen.get
                yield assertTrue(
                  seenAll.exists(_.contains("available commands: ")),
                  seenAll.exists(_ == "  open tabs: build")
                )
            }
          yield result
        }
      }
    },
    test(
      "SHELL-03: focus-switch during a hub burst loses nothing; close running → CancelRequested reported, never self-cancelled; Hide executes; close root lists affected children"
    ) {
      live {
        guarded {
          for
            hub <- EventHub.make()
            reg <- SessionRegistry.make(hub)
            choice <- Ref.make[TabCloseChoice](TabCloseChoice.CancelRequested)
            resolver: CloseChoiceResolver = _ => choice.get
            result <- withShell(hub, reg, resolver) { (in, out) =>
              for
                seen <- Ref.make(Vector.empty[String])
                _ <- in.offerAll(Vector(":new j1", ":new j2", ":new j3"))
                _ <- eventually(sec15)(reg.tabs.map(_.size == 3))
                views <- reg.tabs
                j1v = views.find(_.label == "j1").get
                j2v = views.find(_.label == "j2").get
                j3v = views.find(_.label == "j3").get
                // burst on j1/j2 (alternating delivery classes) while the
                // shell processes switches and a draft concurrently
                publishers <- ZIO
                  .foreachParDiscard(1 to 2) { j =>
                    ZIO.foreachDiscard(1 to 50) { i =>
                      publish(
                        hub,
                        if j == 1 then j1v.jobId else j2v.jobId,
                        if i % 2 == 0 then diag(s"j$j line $i")
                        else EventPayload.Waiting(WaitReason.Slot)
                      )
                    }
                  }
                  .fork
                _ <- in.offerAll(
                  Vector.fill(10)(":next") ++ Vector.fill(10)(":prev")
                    :+ "burst-draft"
                )
                _ <- publishers.join
                _ <- eventually(sec30)(
                  for
                    h1 <- reg.history(j1v.id)
                    h2 <- reg.history(j2v.id)
                  yield h1.entries.map(_.lastSeq) == (0L to 50L).toVector &&
                    h2.entries.map(_.lastSeq) == (0L to 50L).toVector
                )
                h1 <- reg.history(j1v.id)
                h2 <- reg.history(j2v.id)
                scr1 <- reg.scrollback(j1v.id)
                _ <- eventually(sec15)(
                  reg.tabs
                    .flatMap(views =>
                      ZIO.foreach(views)(v => reg.draftGet(v.id))
                    )
                    .map(_.exists(_ == "burst-draft"))
                )
                // j3 goes Running
                _ <- publish(
                  hub,
                  j3v.jobId,
                  EventPayload.JobStatusChanged(
                    JobStatus.Queued,
                    JobStatus.Running,
                    None
                  )
                )
                _ <- eventually(sec15)(
                  hub
                    .snapshot(j3v.jobId)
                    .map(_.exists(_.status == JobStatus.Running))
                )
                _ <- in.offer(":switch j3")
                _ <- eventually(sec15)(reg.focused.map(_.contains(j3v.id)))
                // close RUNNING, resolver says CancelRequested: REPORTED only
                _ <- in.offer(":close")
                _ <- collectUntil(out, seen, sec15)(
                  _.exists(_.startsWith("cancel requested for: j3"))
                )
                tabsAfterCancel <- reg.tabs
                j3AfterCancel <- hub.snapshot(j3v.jobId)
                // same tab, resolver now says Hide: the tab leaves the UI,
                // the runtime state is untouched
                _ <- choice.set(TabCloseChoice.Hide)
                _ <- in.offer(":close")
                _ <- collectUntil(out, seen, sec15)(
                  _.exists(_.startsWith("hidden: j3"))
                )
                tabsAfterHide <- reg.tabs
                j3AfterHide <- hub.snapshot(j3v.jobId)
                seenAll <- seen.get
              yield assertTrue(
                h1.evicted == 0L,
                h2.evicted == 0L,
                h1.entries.length == 51,
                h2.entries.length == 51,
                scr1.lines.length == 25,
                tabsAfterCancel.map(_.label) == Vector("j1", "j2", "j3"),
                j3AfterCancel.exists(_.status == JobStatus.Running),
                tabsAfterHide.map(_.label) == Vector("j1", "j2"),
                j3AfterHide.exists(_.status == JobStatus.Running),
                seenAll.exists(_.endsWith(": burst-draft")),
                seenAll.exists(_.contains("NOT self-cancelled"))
              )
            }
            // root close with affected children, through the same executor
            rootT <- reg.create(
              JobId("shell-03-root"),
              RootId("shell-03-root-id"),
              "root"
            )
            childT <- reg.create(
              JobId("shell-03-child"),
              RootId("shell-03-root-id"),
              "child"
            )
            _ <- publish(
              hub,
              JobId("shell-03-root"),
              EventPayload.Terminal(JobStatus.Succeeded)
            )
            _ <- eventually(sec15)(
              hub
                .snapshot(JobId("shell-03-root"))
                .map(_.exists(_.status == JobStatus.Succeeded))
            )
            fb <- ShellExecutor.run(ShellCommand.Close(rootT), reg, resolver)
            lines = ShellFeedback.render(fb)
            tabsFinal <- reg.tabs
          yield result && assertTrue(
            fb == ShellFeedback.ClosedTerminal(
              rootT,
              "root",
              List((childT, "child"))
            ),
            lines.exists(_ == "affected same-root tabs:"),
            lines.exists(_.startsWith("  - child (")),
            tabsFinal.map(_.label).sorted == Vector("child", "j1", "j2")
          )
        }
      }
    }
  )
