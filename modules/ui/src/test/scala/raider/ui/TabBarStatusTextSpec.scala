package raider.ui

import raider.core.{JobId, RootId}
import raider.runtime.events.{
  EventHub,
  EventPayload,
  SessionReducer,
  SessionSnapshot,
  StatusText
}
import raider.runtime.jobs.{JobStatus, StatusLedger}
import zio.test.{live, *}
import zio.{Duration, ZIO}

/** RAI-097 remainder (UNI-R): the tab bar renders the session status through
  * the ONE canonical [[StatusText]] — the ui keeps NO local duplicate format.
  *
  *   - UNI-R-01 the SAME frozen fixture as the CLI StatusTextSpec renders the
  *     SAME literal line in the bar row (`queued | cost unknown` — compared
  *     literally in both specs); the registry-driven row renders the tab's
  *     authoritative snapshot through the same function;
  *   - UNI-R-02 unknown stays honest (StatusText «?»-semantics): an unreported
  *     wait reason renders `waiting(unknown)`, a nobody-observed cost renders
  *     `cost unknown` — never `$0`, never an invented reason;
  *   - UNI-R-03 the bar stays a pure function of the read model: no new
  *     IO/clock in the render, repeated renders are identical (the existing ui
  *     suites stay the regression net for the rest).
  *
  * Determinism rules (same discipline as SessionRegistrySpec): real `EventHub`
  * + real `SessionRegistry` — no mocks; coordination only via `eventually` on
  * logically guaranteed conditions.
  */
object TabBarStatusTextSpec extends ZIOSpecDefault:

  private val root = RootId("uni-root")
  private val sid = StatusLedger.sessionId
  private val jobId = JobId("uni-j")

  /** The SAME frozen fixture as the CLI StatusTextSpec (UNI-01 basis): the
    * pristine reducer snapshot.
    */
  private val fixture: SessionSnapshot =
    SessionReducer.initial(jobId, root, sid)

  /** A tab view whose status line is the canonical render of `s` (the same
    * derivation the registry read model performs over the authoritative
    * snapshot).
    */
  private def viewOf(s: SessionSnapshot): TabView =
    TabView(
      TabId("t1"),
      "build",
      s.jobId,
      s.rootId,
      status = s.status,
      statusText = StatusText.render(s),
      unreadCount = 0L,
      lastSeq = s.lastSeq
    )

  private val sec = Duration.fromSeconds(30)

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

  def spec = suite("TabBar status over StatusText (RAI-097 remainder)")(
    test(
      "UNI-R-01: same snapshot as the CLI fixture → the identical literal line in the bar"
    ) {
      val cliLine = StatusText.render(fixture)
      val row =
        TabBarRenderer.tabBar(List(viewOf(fixture)), Some(TabId("t1")))(0)
      val again =
        TabBarRenderer.tabBar(List(viewOf(fixture)), Some(TabId("t1")))(0)
      assertTrue(
        cliLine == "queued | cost unknown",
        row == "*1. build [queued | cost unknown]",
        row.contains(cliLine),
        row == again
      )
    },
    test(
      "UNI-R-02: unknown stays honest — unreported reason → waiting(unknown), unobserved cost → cost unknown, never $0"
    ) {
      val waiting = fixture.copy(status = JobStatus.Waiting, waitReason = None)
      val row =
        TabBarRenderer.tabBar(List(viewOf(waiting)), Some(TabId("t1")))(0)
      assertTrue(
        row == "*1. build [waiting(unknown) | cost unknown]",
        row.contains("cost unknown"),
        !row.contains("$0")
      )
    },
    test(
      "UNI-R-03: registry-driven bar row — the tab's authoritative snapshot renders through StatusText (pure, deterministic)"
    ) {
      live {
        for
          hub <- EventHub.make()
          reg <- SessionRegistry.make(hub)
          tabId <- reg.create(jobId, root, "build")
          // nothing published yet: the pristine initial snapshot line
          views0 <- reg.tabs
          row0 = TabBarRenderer.tabBar(views0, Some(tabId))(0)
          // an honest unknown: JobStatusChanged to Waiting carries NO reason
          _ <- hub.publish(
            jobId,
            root,
            sid,
            EventPayload.JobStatusChanged(
              JobStatus.Queued,
              JobStatus.Waiting,
              None
            )
          )
          _ <- eventually(sec)(
            reg.tabs.map(_.exists(_.status == JobStatus.Waiting))
          )
          views <- reg.tabs
          row = TabBarRenderer.tabBar(views, Some(tabId))(0)
          again = TabBarRenderer.tabBar(views, Some(tabId))(0)
        yield assertTrue(
          row0 == "*1. build [queued | cost unknown]",
          row == "*1. build [waiting(unknown) | cost unknown]",
          row == again
        )
      }
    }
  )

end TabBarStatusTextSpec
