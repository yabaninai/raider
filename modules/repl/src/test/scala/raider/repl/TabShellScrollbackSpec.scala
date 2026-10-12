package raider.repl

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.events.{EventError, EventPayload, EventHub}
import raider.runtime.jobs.JobStatus
import raider.ui.{RegistryError, SessionRegistry, TabId}
import zio._
import zio.test._

/** RAI-104 / P2S — bounded scrollback on the PRODUCT REPL surface.
  *
  * Drives exactly the product wiring (`TabShellHost.wiring`: the real EventHub
  * + SessionRegistry, RAI-057 mechanics) through the product scrollback surface
  * (`raider.repl.TabShellScrollback`). The terminal-atomicity side of the same
  * shell is already PTY-proven for this host (RAI-102 P2W-01..03, incl.
  * byte-exact 220-line scrollback) and is not duplicated here; this spec owns
  * the card's feature-specific scale: 10k lines, the visible eviction counter,
  * cross-tab parallel bursts.
  *
  *   - P2S-01: after a 10k-line Diagnostics burst a tab holds exactly the LAST
  *     `ScrollbackCap` lines byte-exact; the eviction counter is honest
  *     (`evicted + window == received`) and VISIBLE in the screen render.
  *   - P2S-02: scrollback of an unknown tab is the TYPED
  *     `RegistryError.UnknownTab` through the product surface (both the raw
  *     registry read and the screen read); a lookup creates no phantom tabs.
  *   - P2S-03: parallel bursts into different tabs — bounds hold per tab;
  *     windows stay per-tab exact (no losses: `evicted + window == received`
  *     per tab; no duplicates/cross-tab bleed: every window equals its
  *     tab-labeled expectation, hub drops == 0).
  *
  * Burst pacing (publish chunks of 100 with a 10ms yield between chunks) keeps
  * the frozen hub's bounded diagnostics buffers ahead of the test's consumer
  * speeds so `droppedDiagnostics` stays 0; a drop would surface as an HONEST
  * failure (the evicted+window identity would no longer equal the published
  * total). The bound itself (ScrollbackCap = 500) is what makes 10k lines a
  * bounded-memory window, and it is asserted exactly.
  */
object TabShellScrollbackSpec extends ZIOSpecDefault:

  private val BurstLines: Int = 10000
  private val Cap: Int = SessionRegistry.ScrollbackCap
  private val ChunkSize: Int = 100
  private val PaceMs: Long = 10L

  private type TestE = EventError | RegistryError

  private def labeledLine(tab: String, i: Int): String = s"$tab ev-$i"

  /** One tab's synthetic runtime stream through the REAL product hub: one
    * JobStatusChanged → Running, then the paced 10k-line Diagnostics burst.
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
        EventPayload.JobStatusChanged(
          JobStatus.Queued,
          JobStatus.Running,
          None
        )
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

  // ----- P2S-01: 10k lines — last-N window + visible eviction counter -----

  private def p2s01: ZIO[Scope, TestE, TestResult] =
    for
      wiring <- TabShellHost.wiring
      job = JobId("s01")
      root = RootId("s01-root")
      tabId <- wiring.registry.create(job, root, "alpha")
      _ <- burst(wiring, "alpha", job, root)
      _ <- waitSettled(wiring.registry, tabId, SettledSeq)
      scr <- wiring.registry.scrollback(tabId)
      screen <- TabShellScrollback.screen(wiring.registry, tabId)
      dropped <- wiring.hub.droppedDiagnostics
      view <- wiring.registry.tab(tabId)
      expected = (1 to BurstLines).toVector
        .map(labeledLine("alpha", _))
        .takeRight(Cap)
      rendered = screen.render
    yield
      val expectedHeader =
        s"scrollback [alpha]: last $Cap of $BurstLines lines " +
          s"(${BurstLines - Cap} evicted, cap $Cap)"
      assertTrue(scr.lines.size == Cap) &&
      assertTrue(scr.evicted == (BurstLines - Cap).toLong) &&
      assertTrue(scr.evicted + scr.lines.size == BurstLines.toLong) &&
      assertTrue(scr.lines == expected) &&
      assertTrue(scr.lines.distinct.size == Cap) &&
      assertTrue(dropped == 0L) &&
      assertTrue(view.status == JobStatus.Running) &&
      assertTrue(rendered.head == expectedHeader) &&
      assertTrue(rendered.tail == expected) &&
      assertTrue(rendered.head.contains("9500 evicted"))

  // ----- P2S-02: unknown tab — typed error, no phantom tabs -----

  private def p2s02: ZIO[Scope, TestE, TestResult] =
    for
      wiring <- TabShellHost.wiring
      ghost = TabId("ghost")
      before <- wiring.registry.tabs
      rawErr <- wiring.registry.scrollback(ghost).either
      screenErr <- TabShellScrollback
        .screen(wiring.registry, ghost)
        .either
      after <- wiring.registry.tabs
    yield
      val typed = RegistryError.UnknownTab(ghost)
      assertTrue(before.isEmpty) &&
      assertTrue(rawErr == Left(typed)) &&
      assertTrue(screenErr == Left(typed)) &&
      assertTrue(
        rawErr.left.toOption
          .map(_.message)
          .contains(
            "unknown tab ghost"
          )
      ) &&
      assertTrue(after.isEmpty) // the lookups created no phantom tabs

  // ----- P2S-03: parallel bursts into different tabs — bounds hold -----

  private def p2s03: ZIO[Scope, TestE, TestResult] =
    for
      wiring <- TabShellHost.wiring
      labels = Seq("t1", "t2", "t3")
      tabs <- ZIO.foreach(labels) { lbl =>
        val job = JobId(s"s03-$lbl")
        val root = RootId(s"s03-root-$lbl")
        wiring.registry.create(job, root, lbl).map(id => (lbl, job, root, id))
      }
      _ <- ZIO.foreachParDiscard(tabs)((t: (String, JobId, RootId, TabId)) =>
        burst(wiring, t._1, t._2, t._3)
      )
      _ <- ZIO.foreachDiscard(tabs)(t =>
        waitSettled(wiring.registry, t._4, SettledSeq)
      )
      views <- ZIO.foreach(tabs)(t =>
        wiring.registry.scrollback(t._4).map(scr => (t._1, scr))
      )
      dropped <- wiring.hub.droppedDiagnostics
      open <- wiring.registry.tabs
    yield
      val perTab = views.map { (lbl, scr) =>
        val expected =
          (1 to BurstLines).toVector.map(labeledLine(lbl, _)).takeRight(Cap)
        val noLoss = scr.evicted + scr.lines.size == BurstLines.toLong
        val bounded = scr.lines.size == Cap &&
          scr.evicted == (BurstLines - Cap).toLong
        val exact = scr.lines == expected
        val noDup = scr.lines.distinct.size == Cap
        bounded && noLoss && exact && noDup
      }
      val crossBleed = views.map(_._2.lines).distinct.size == labels.size
      assertTrue(perTab.forall(identity)) &&
      assertTrue(crossBleed) &&
      assertTrue(dropped == 0L) &&
      assertTrue(open.size == labels.size)

  def spec =
    suite(
      "TabShellScrollbackSpec (RAI-104: product surface bounded scrollback)"
    )(
      test(
        "P2S-01: 10k lines — last-N window byte-exact, eviction counter honest and visible"
      )(p2s01),
      test(
        "P2S-02: unknown tab — typed RegistryError, no phantom tabs"
      )(p2s02),
      test(
        "P2S-03: parallel bursts into different tabs — bounds hold, no losses/duplicates"
      )(p2s03)
    ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect
      .timeout(300.seconds)

end TabShellScrollbackSpec
