package raider.repl

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.events.{EventError, EventHub, EventPayload}
import raider.runtime.jobs.JobStatus
import raider.ui.{RegistryError, SessionRegistry, ShellTerminal, TabId}
import zio._
import zio.test._

/** RAI-113 / P2SR — the interactive `:scrollback [tabId]` command on the
  * product tabs surface, in-process: the freshness registry, the typed-error
  * contract and the command-surface render on the product wiring
  * (`TabShellHost.wiring`: real EventHub + SessionRegistry, RAI-057 mechanics)
  * through the interceptor terminal exactly as the product entry installs it
  * (`TabShellHost.run(wiring, ScrollbackCommands(registry))`). The PTY side of
  * the same surface is MainScrollbackPtySpec (real terminal input).
  *
  *   - FRESHNESS (RAI-111 precedent): `ScrollbackCommands.names` is the one
  *     source of truth — the parser matches exactly the registry, the nearest-
  *     hint catalog derives it, TAB completion stays router-only (pinned).
  *   - P2SR-01: after a 10k-line Diagnostics burst through the real hub, the
  *     COMMAND surface (interceptor → dispatch → product
  *     `TabShellScrollback.screen`/`render`) prints the exact eviction-counter
  *     header and the byte-exact last-500 window.
  *   - P2SR-02: unknown/missing tab id is the TYPED `RegistryError.UnknownTab`
  *     in the output; no phantom tabs; the command works again immediately
  *     after an error.
  *   - Pass-through: non-scrollback lines cross the decorator byte-exact and
  *     print nothing (the frozen router keeps its surface).
  */
object ScrollbackCommandsSpec extends ZIOSpecDefault:

  private val BurstLines: Int = 10000
  private val Cap: Int = SessionRegistry.ScrollbackCap
  private val ChunkSize: Int = 100
  private val PaceMs: Long = 10L

  private type TestE = EventError | RegistryError

  private def labeledLine(tab: String, i: Int): String = s"$tab ev-$i"

  /** The interceptor terminal the product entry installs, over a scripted
    * terminal: queued lines, EOF (None) after the queue drains, printed blocks
    * captured in `printed`. `reads` = how many readLine calls to make: ONE call
    * consumes consecutive queued scrollback lines (printing each outcome) and
    * returns only the next pass-through line or EOF.
    */
  private def intercepted(
      registry: SessionRegistry,
      lines: Seq[String],
      reads: Int = 1
  ): ZIO[Scope, Nothing, (Vector[String], Vector[Option[String]])] =
    for
      printed <- Ref.make(Vector.empty[String])
      queue <- Queue.unbounded[String]
      _ <- ZIO.foreachDiscard(lines)(queue.offer)
      base = new ShellTerminal:
        def printAbove(l: Vector[String]): UIO[Unit] =
          printed.update(_ ++ l)
        def readLine(prompt: String): Task[Option[String]] =
          queue.poll.flatMap {
            case Some(line) => ZIO.succeed(Some(line))
            case None       => ZIO.succeed(None)
          }
        def close: UIO[Unit] = ZIO.unit
      terminal = ScrollbackCommands(registry).commandTerminal(base)
      read <- ZIO
        .foreach((1 to reads).toVector)(_ => terminal.readLine("raider> "))
        .orDie
      out <- printed.get
    yield (out, read)

  /** One tab's paced 10k-line Diagnostics burst through the REAL product hub
    * (same seam as the live runtime; RAI-104 pacing keeps hub drops at 0).
    */
  private def burst(
      wiring: TabShellHost.Wiring,
      tab: String,
      job: JobId,
      root: RootId
  ): ZIO[Any, EventError, Unit] =
    val sess = SessionId(s"sess-$tab")
    wiring.hub
      .publish(
        job,
        root,
        sess,
        EventPayload.JobStatusChanged(JobStatus.Queued, JobStatus.Running, None)
      ) *>
      ZIO.foreachDiscard((1 to BurstLines).grouped(ChunkSize).toVector) {
        chunk =>
          TabShellScrollback
            .publishLines(
              wiring.hub,
              job,
              root,
              sess,
              chunk.toVector.map(labeledLine(tab, _))
            )
            .when(chunk.nonEmpty) <* ZIO.sleep(PaceMs.millis)
      }

  /** Settle: the tab's watermark reached `1 (Running) + BurstLines`. */
  private def waitSettled(
      registry: SessionRegistry,
      tabId: TabId,
      expectedSeq: Long
  ): ZIO[Any, RegistryError, Unit] =
    registry.snapshot(tabId).flatMap { snap =>
      if snap.lastSeq >= expectedSeq then ZIO.unit
      else ZIO.sleep(50.millis) *> waitSettled(registry, tabId, expectedSeq)
    }

  private val SettledSeq: Long = 1L + BurstLines

  // ----- freshness: registry ↔ parser ↔ hint catalog (RAI-111 precedent) ----

  private val freshness = suite("freshness registry (RAI-111 precedent)")(
    test("the registry is the scrollback surface, exactly") {
      assertTrue(ScrollbackCommands.names == Vector(":scrollback"))
    },
    test("the parser consumes exactly the registry names") {
      val parsedAll = ScrollbackCommands.names.forall {
        ScrollbackCommands.parse(_) match
          case ScrollbackMatch.Parsed(None) => true
          case _                            => false
      }
      val nearMisses =
        (
          ScrollbackCommands.parse(":scrollbackx"),
          ScrollbackCommands.parse(":scroll"),
          ScrollbackCommands.parse("hello world")
        ) match
          case (
                ScrollbackMatch.NotScrollback,
                ScrollbackMatch.NotScrollback,
                ScrollbackMatch.NotScrollback
              ) =>
            true
          case _ => false
      assertTrue(parsedAll) &&
      assertTrue(nearMisses) &&
      assertTrue(
        ScrollbackCommands.parse("  :SCROLLBACK  ghost tab ") ==
          ScrollbackMatch.Parsed(Some("ghost tab"))
      )
    },
    test(
      "the hint catalog derives the registry; TAB completion stays router-only"
    ) {
      assertTrue(
        ScrollbackCommands.names.forall(ShellPalette.allCommands.contains)
      ) &&
      assertTrue(ShellPalette.nearest(":scrollbak").contains(":scrollback")) &&
      assertTrue(ShellPalette.nearest(":scrollback").isEmpty) &&
      assertTrue(ShellPalette.suggestionLine(":scrollback").isEmpty) &&
      assertTrue(ShellPalette.complete(":scr").isEmpty) &&
      assertTrue(ShellPalette.complete(":sc") == Vector.empty)
    }
  )

  // ----- token resolution over the public registry read model ----

  private val resolution = suite("tab token resolution")(
    test("index, exact id, exact label, unique prefix resolve; rest is typed") {
      ZIO.scoped {
        for
          wiring <- TabShellHost.wiring
          alpha <- wiring.registry
            .create(JobId("j-a"), RootId("r-a"), "alpha")
          beta <- wiring.registry
            .create(JobId("j-b"), RootId("r-b"), "beta")
          _ <- wiring.registry.create(JobId("j-d"), RootId("r-d"), "dup")
          _ <- wiring.registry.create(JobId("j-e"), RootId("r-e"), "dup")
          tabs <- wiring.registry.tabs
          pick = (token: String) => ScrollbackCommands.resolve(token, tabs)
        yield assertTrue(pick("2") == Right(beta)) &&
          assertTrue(pick("1") == Right(alpha)) &&
          assertTrue(pick(alpha.value) == Right(alpha)) &&
          assertTrue(pick("beta") == Right(beta)) &&
          assertTrue(pick("be") == Right(beta)) && // unique prefix
          assertTrue(
            pick("d") == Left(RegistryError.UnknownTab(TabId("d")))
          ) && // ambiguous
          assertTrue(
            pick("99") == Left(RegistryError.UnknownTab(TabId("99")))
          ) &&
          assertTrue(
            pick("nosuch") == Left(RegistryError.UnknownTab(TabId("nosuch")))
          ) &&
          assertTrue(pick("").isLeft) // never the all-labels prefix match
      }
    }
  )

  // ----- P2SR-01: the command surface renders the product screen ----

  private def p2sr01: ZIO[Scope, TestE, TestResult] =
    for
      wiring <- TabShellHost.wiring
      job = JobId("s01")
      root = RootId("s01-root")
      tabId <- wiring.registry.create(job, root, "alpha")
      _ <- burst(wiring, "alpha", job, root)
      _ <- waitSettled(wiring.registry, tabId, SettledSeq)
      (printed, read) <- intercepted(wiring.registry, Seq(":scrollback 1"))
      screen <- TabShellScrollback.screen(wiring.registry, tabId)
      dropped <- wiring.hub.droppedDiagnostics
      expectedWindow = (1 to BurstLines).toVector
        .map(labeledLine("alpha", _))
        .takeRight(Cap)
      expectedHeader =
        s"scrollback [alpha]: last $Cap of $BurstLines lines " +
          s"(${BurstLines - Cap} evicted, cap $Cap)"
    yield assertTrue(
      read == Vector(None)
    ) && // the line was consumed (not passed)
      assertTrue(printed == screen.render) && // the PRODUCT render, exactly
      assertTrue(printed.head == expectedHeader) &&
      assertTrue(printed.head.contains("9500 evicted")) &&
      assertTrue(printed.tail == expectedWindow) && // byte-exact window
      assertTrue(dropped == 0L)

  // ----- P2SR-02: typed unknown/missing; repeat after error works ----

  private def p2sr02: ZIO[Scope, TestE, TestResult] =
    for
      wiring <- TabShellHost.wiring
      before <- wiring.registry.tabs
      missing <- ScrollbackCommands.run(wiring.registry, None).either
      // unknown tab through the INTERCEPTOR on the empty registry
      (printedGhost, _) <- intercepted(
        wiring.registry,
        Seq(":scrollback ghost")
      )
      afterGhost <- wiring.registry.tabs
      // the shell stays alive: the next command works (repeat after error)
      _ <- wiring.registry.create(JobId("j-solo"), RootId("r-solo"), "solo")
      (printedBoth, _) <- intercepted(
        wiring.registry,
        Seq(":scrollback ghost", ":scrollback 1")
      )
      emptyHeader =
        s"scrollback [solo]: last 0 of 0 lines (0 evicted, cap $Cap)"
    yield
      val ghost = RegistryError.UnknownTab(TabId("ghost"))
      assertTrue(before.isEmpty) &&
      assertTrue(missing == Left(ScrollbackCommands.missingTab)) &&
      assertTrue(
        ScrollbackCommands.missingTab == RegistryError.UnknownTab(TabId(""))
      ) &&
      assertTrue(
        printedGhost == Vector(s"error: ${ghost.message}")
      ) && // "error: unknown tab ghost"
      assertTrue(afterGhost.isEmpty) && // no phantom tabs
      assertTrue(printedBoth.size == 1 + 1) &&
      assertTrue(printedBoth.head == s"error: unknown tab ghost") &&
      assertTrue(printedBoth.last == emptyHeader)

  // ----- pass-through: the frozen router keeps its surface byte-exact ----

  private def passthrough: ZIO[Scope, TestE, TestResult] =
    for
      wiring <- TabShellHost.wiring
      lines = Seq("hello world", ":help", ":tabs", ":quit", "")
      (printed, read) <- intercepted(
        wiring.registry,
        lines,
        reads = lines.size + 1
      )
    yield assertTrue(read == (lines.map(Some(_)) :+ None).toVector) &&
      assertTrue(printed.isEmpty) // the interceptor printed nothing

  def spec =
    suite(
      "ScrollbackCommandsSpec (RAI-113: :scrollback consumer step, in-process)"
    )(
      freshness,
      resolution,
      test(
        "P2SR-01: 10k burst — command surface prints the exact header + byte-exact window"
      )(p2sr01),
      test(
        "P2SR-02: unknown/missing tab — typed UnknownTab in the output; repeat works"
      )(p2sr02),
      test("non-scrollback lines pass through byte-exact, printing nothing")(
        passthrough
      )
    ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect
      .timeout(300.seconds)

end ScrollbackCommandsSpec
