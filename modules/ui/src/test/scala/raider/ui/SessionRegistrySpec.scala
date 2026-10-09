package raider.ui

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.events.{
  EventError,
  EventHub,
  EventPayload,
  LineTail,
  SessionSnapshot,
  WaitReason
}
import raider.runtime.jobs.JobStatus
import zio.test.{live, *}
import zio.{Duration, IO, UIO, ZIO}

/** RAI-057: SessionRegistry acceptance TAB-01..03 + focus invariant (m1.md
  * RAI-057, ADR-024 §2.5/§4).
  *
  * Determinism rules (same discipline as EventHubSpec):
  *   - coordination only via `eventually` on logically guaranteed conditions
  *     (the real hub delivers every event in range; the bound only converts a
  *     defective hang into a typed failure);
  *   - no assertion inspects a timing;
  *   - buffer/eviction accounting asserts exact conservation (`published = kept
  *     + evicted`);
  *   - real `EventHub` + scripted publishes only — no mock interpreter, no
  *     second runtime.
  */
object SessionRegistrySpec extends ZIOSpecDefault:

  private val jobA = JobId("tab-job-a")
  private val jobB = JobId("tab-job-b")
  private val job1 = JobId("tab-burst-1")
  private val job2 = JobId("tab-burst-2")
  private val job3 = JobId("tab-burst-3")
  private val jobRoot = JobId("tab-lineage-root")
  private val jobChild = JobId("tab-lineage-child")
  private val jobOther = JobId("tab-lineage-other")

  private val rootR = RootId("tab-lineage-root-id")
  private val rootX = RootId("tab-lineage-other-id")

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
              ZIO.fail("condition not met within the generous outer bound")
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

  /** Observable hub side: diagnostics drops + per-job folded snapshots. Focus
    * must never move any of it (ADR-024 §2.5).
    */
  private final case class HubState(
      dropped: Long,
      a: Option[SessionSnapshot],
      b: Option[SessionSnapshot]
  )

  private def hubState(
      hub: EventHub,
      ja: JobId,
      jb: JobId
  ): UIO[HubState] =
    for
      d <- hub.droppedDiagnostics
      sa <- hub.snapshot(ja)
      sb <- hub.snapshot(jb)
    yield HubState(d, sa, sb)

  def spec = suite("SessionRegistry (RAI-057, TAB-01..03)")(
    test(
      "TAB-01: create → type draft → focusNext → focusBack: draft/history/scrollback byte-for-byte; unread resets on focus; unread accumulated while unfocused"
    ) {
      live {
        guarded {
          for
            hub <- EventHub.make()
            reg <- SessionRegistry.make(hub)
            a <- reg.create(jobA, RootId("shared-root"), "tab-a")
            b <- reg.create(jobB, RootId("shared-root"), "tab-b") // b focused
            _ <- reg.draftAppend(b, "hello ")
            _ <- reg.draftAppend(b, "world")
            // 4 events for A while A is NOT focused
            _ <- ZIO.foreachDiscard(1 to 3)(i =>
              publish(hub, jobA, diag(s"line-$i"))
            )
            _ <- publish(
              hub,
              jobA,
              EventPayload.ToolProgress(
                "c1",
                "bash",
                LineTail(Vector("p1", "p2"), 0, 0L)
              )
            )
            // all 4 delivered AND all 4 counted as unread (A was unfocused)
            _ <- eventually(Duration.fromSeconds(15)) {
              for
                s <- reg.snapshot(a)
                tbs <- reg.tabs
              yield s.lastSeq == 4L &&
                tbs.find(_.id == a).exists(_.unreadCount == 4L)
            }
            // state of A before the focus round-trip
            histA1 <- reg.history(a)
            scrA1 <- reg.scrollback(a)
            draftB1 <- reg.draftGet(b)
            // focusNext from b wraps to a (ONE call)
            next <- reg.focusNext()
            histA2 <- reg.history(a)
            scrA2 <- reg.scrollback(a)
            draftB2 <- reg.draftGet(b)
            tabsMid <- reg.tabs
            // focusBack: focusPrev returns to b (ONE call)
            prev <- reg.focusPrev()
            draftB3 <- reg.draftGet(b)
            focusedEnd <- reg.focused
            _ <- reg.draftClear(b)
            cleared <- reg.draftGet(b)
            aMid = tabsMid.find(_.id == a).get
            bMid = tabsMid.find(_.id == b).get
          yield assertTrue(
            next == a,
            prev == b,
            focusedEnd == Some(b),
            // draft preserved byte-for-byte across the round-trip
            draftB1 == "hello world",
            draftB2 == "hello world",
            draftB3 == "hello world",
            cleared == "",
            // history + scrollback preserved byte-for-byte across the switch
            histA1 == histA2,
            scrA1 == scrA2,
            // exact folded content of A: initial state + 4 events
            histA2.entries.map(_.lastSeq) == Vector(0L, 1L, 2L, 3L, 4L),
            histA2.evicted == 0L,
            scrA2.lines == Vector("line-1", "line-2", "line-3", "p1", "p2"),
            scrA2.evicted == 0L,
            aMid.lastSeq == 4L,
            aMid.status == JobStatus.Queued,
            // unread: accumulated while unfocused (4), reset to 0 on focus
            aMid.unreadCount == 0L,
            bMid.unreadCount == 0L
          )
        }
      }
    },
    test(
      "TAB-02: scrollback/history overflow — honest eviction counters grow, Terminal status survives eviction; unknown tab ids and empty navigation are typed errors"
    ) {
      live {
        guarded {
          for
            hub <- EventHub.make()
            reg <- SessionRegistry.make(hub)
            a <- reg.create(jobA, RootId("solo-root"), "tab-a")
            // 300 × 2 lines = 600 lines, then 150 × 1 line = 150 lines:
            // 750 total > 500 scrollback cap; 451 events > 200 history cap
            _ <- ZIO.foreachDiscard(1 to 300)(i =>
              publish(hub, jobA, diag(s"L-$i-a\nL-$i-b"))
            )
            _ <- ZIO.foreachDiscard(1 to 150)(i =>
              publish(hub, jobA, diag(s"X-$i"))
            )
            _ <- publish(hub, jobA, EventPayload.Terminal(JobStatus.Succeeded))
            _ <- eventually(Duration.fromSeconds(30))(
              reg.snapshot(a).map(_.lastSeq == 451L)
            )
            hist <- reg.history(a)
            scr <- reg.scrollback(a)
            snap <- reg.snapshot(a)
            dropped <- hub.droppedDiagnostics
            // terminal hub status ⇒ close is allowed
            dec <- reg.close(a)
            tabsAfter <- reg.tabs
            unknownClose <- reg.draftGet(a).either
            unknownFocus <- reg.focus(TabId("missing")).either
            emptyNav <- reg.focusNext().either
          yield assertTrue(
            // nothing was lost at the hub: all 450 diagnostics were in range
            dropped == 0L,
            // history: 452 folded snapshots (initial + 451), cap 200
            hist.entries.length == 200,
            hist.evicted == 252L,
            hist.entries.map(_.lastSeq) == (252L to 451L).toVector,
            // scrollback: 750 lines, cap 500, oldest 250 evicted
            scr.lines.length == 500,
            scr.evicted == 250L,
            scr.lines.head == "L-126-a",
            scr.lines.last == "X-150",
            // Terminal is intact in the folded snapshot despite evictions
            snap.status == JobStatus.Succeeded,
            snap.lastSeq == 451L,
            dec == CloseDecision.Closed(Nil),
            tabsAfter.isEmpty,
            // typed errors, never silent
            unknownClose == Left(RegistryError.UnknownTab(a)),
            unknownFocus == Left(RegistryError.UnknownTab(TabId("missing"))),
            emptyNav == Left(RegistryError.NoTabs)
          )
        }
      }
    },
    test(
      "TAB-03: 3 jobs × 50 events via 3 parallel fibers — strict per-tab seq order (cross-class reorder) and zero loss under focus switching during the burst"
    ) {
      live {
        guarded {
          for
            hub <- EventHub.make()
            reg <- SessionRegistry.make(hub)
            t1 <- reg.create(job1, RootId("r1"), "j1")
            t2 <- reg.create(job2, RootId("r2"), "j2")
            t3 <- reg.create(job3, RootId("r3"), "j3")
            // focus switching runs concurrently with the burst (200 ONE-call
            // switches over the 3 tabs, wrapping)
            focusFiber <-
              ZIO.foreachDiscard(1 to 200)(_ => reg.focusNext()).fork
            // 3 publisher fibers, 50 events per job; even seq → diagnostics
            // class, odd seq → reliable class, so cross-class reordering at the
            // tab consumer is actually exercised
            _ <- ZIO.foreachParDiscard(1 to 3) { j =>
              ZIO.foreachDiscard(1 to 50) { i =>
                publish(
                  hub,
                  if j == 1 then job1 else if j == 2 then job2 else job3,
                  if i % 2 == 0 then diag(s"j$j line $i")
                  else EventPayload.Waiting(WaitReason.Slot)
                )
              }
            }
            _ <- focusFiber.join
            _ <- eventually(Duration.fromSeconds(30)) {
              for
                s1 <- reg.snapshot(t1)
                s2 <- reg.snapshot(t2)
                s3 <- reg.snapshot(t3)
              yield s1.lastSeq == 50L && s2.lastSeq == 50L && s3.lastSeq == 50L
            }
            h1 <- reg.history(t1)
            h2 <- reg.history(t2)
            h3 <- reg.history(t3)
            tabs <- reg.tabs
          yield assertTrue(
            // strict per-tab order by seq: initial state + 1..50, no gaps,
            // no duplicates, nothing reordered (a regression fold would have
            // been refused and the sequence would show a hole)
            h1.entries.map(_.lastSeq) == (0L to 50L).toVector,
            h2.entries.map(_.lastSeq) == (0L to 50L).toVector,
            h3.entries.map(_.lastSeq) == (0L to 50L).toVector,
            h1.evicted == 0L,
            h2.evicted == 0L,
            h3.evicted == 0L,
            // no loss: exactly 50 events per tab reached the buffers
            h1.entries.length == 51,
            h2.entries.length == 51,
            h3.entries.length == 51,
            // hub side agrees: 50 per job, last reliable payload was Waiting
            tabs.map(_.status) ==
              Vector(JobStatus.Waiting, JobStatus.Waiting, JobStatus.Waiting),
            tabs.map(_.lastSeq) == Vector(50L, 50L, 50L)
          )
        }
      }
    },
    test(
      "focus invariant: focus/focusNext/focusPrev never publish, cancel or mutate the hub — droppedDiagnostics and per-job snapshots are unchanged, while the focus itself does move"
    ) {
      live {
        guarded {
          for
            hub <- EventHub.make()
            reg <- SessionRegistry.make(hub)
            a <- reg.create(jobA, RootId("inv-root-a"), "a")
            b <- reg.create(jobB, RootId("inv-root-b"), "b")
            _ <- publish(hub, jobA, diag("x"))
            _ <- publish(
              hub,
              jobB,
              EventPayload.JobStatusChanged(
                JobStatus.Queued,
                JobStatus.Running,
                None
              )
            )
            _ <- eventually(Duration.fromSeconds(15)) {
              for
                sa <- hub.snapshot(jobA)
                sb <- hub.snapshot(jobB)
              yield sa.exists(_.lastSeq == 1L) && sb.exists(_.lastSeq == 1L)
            }
            _ <- eventually(Duration.fromSeconds(15))(
              reg.snapshot(a).map(_.lastSeq == 1L)
            )
            before <- hubState(hub, jobA, jobB)
            localBefore <- reg.tabs.map(_.map(t => (t.id, t.lastSeq, t.status)))
            // 10 ONE-call switches: odd = next+prev (net zero), even = focus(a)
            _ <- ZIO.foreachDiscard(1 to 10)(i =>
              if i % 2 == 0 then reg.focus(a)
              else reg.focusNext() *> reg.focusPrev()
            )
            focusedAfter <- reg.focused
            after <- hubState(hub, jobA, jobB)
            localAfter <- reg.tabs.map(_.map(t => (t.id, t.lastSeq, t.status)))
          yield assertTrue(
            // focus moved, as a UI operation…
            focusedAfter == Some(a),
            // …and touched nothing on the hub side (no publish, no cancel,
            // no diagnostics movement, no status change)
            before == after,
            localBefore == localAfter
          )
        }
      }
    },
    test(
      "close semantics: running tab → RunningChoice (nothing cancelled, tab stays; Hide executor works); terminal root close → Closed reporting the same-root children; unrelated roots untouched"
    ) {
      live {
        guarded {
          for
            hub <- EventHub.make()
            reg <- SessionRegistry.make(hub)
            root <- reg.create(jobRoot, rootR, "root")
            child <- reg.create(jobChild, rootR, "child")
            other <- reg.create(jobOther, rootX, "other")
            // root job goes Running
            _ <- publish(
              hub,
              jobRoot,
              EventPayload.JobStatusChanged(
                JobStatus.Queued,
                JobStatus.Running,
                None
              )
            )
            _ <- eventually(Duration.fromSeconds(15))(
              hub.snapshot(jobRoot).map(_.exists(_.status == JobStatus.Running))
            )
            // running: typed choice with the lineage set, nothing removed,
            // nothing cancelled
            dec1 <- reg.close(root)
            still <- reg.tabs.map(_.map(_.id))
            stillRunning <-
              hub.snapshot(jobRoot).map(_.exists(_.status == JobStatus.Running))
            // the caller acts on the typed choice: CancelRequested — the
            // CANCEL itself happens in the runtime (here scripted as the real
            // terminal event), never by the registry
            _ <- publish(
              hub,
              jobRoot,
              EventPayload.Terminal(JobStatus.Cancelled)
            )
            _ <- eventually(Duration.fromSeconds(15))(
              hub
                .snapshot(jobRoot)
                .map(_.exists(_.status == JobStatus.Cancelled))
            )
            // terminal root close: removed, affected = open same-root children
            dec2 <- reg.close(root)
            after2 <- reg.tabs.map(_.map(_.id))
            // the Hide executor: child goes terminal, then is hidden (job
            // state stays terminal — hide never mutates the runtime)
            _ <- publish(
              hub,
              jobChild,
              EventPayload.Terminal(JobStatus.Succeeded)
            )
            _ <- eventually(Duration.fromSeconds(15))(
              hub
                .snapshot(jobChild)
                .map(_.exists(_.status == JobStatus.Succeeded))
            )
            _ <- reg.hide(child)
            afterHide <- reg.tabs.map(_.map(_.id))
            childFinal <-
              hub
                .snapshot(jobChild)
                .map(_.exists(_.status == JobStatus.Succeeded))
            gone <- reg.draftGet(root).either
          yield assertTrue(
            dec1 == CloseDecision.RunningChoice(List(child)),
            still == Vector(root, child, other),
            stillRunning,
            dec2 == CloseDecision.Closed(List(child)),
            after2 == Vector(child, other),
            afterHide == Vector(other),
            childFinal,
            gone == Left(RegistryError.UnknownTab(root))
          )
        }
      }
    }
  )
