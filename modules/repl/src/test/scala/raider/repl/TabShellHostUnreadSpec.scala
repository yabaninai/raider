package raider.repl

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.events.EventPayload
import raider.ui.{
  CloseChoiceResolver,
  RegistryError,
  ShellCommand,
  ShellExecutor,
  ShellFeedback,
  TabBarRenderer,
  TabCloseChoice,
  TabId
}
import zio._
import zio.test._

/** RAI-105 / P2U — feature-specific behavioral fixtures of the unread-badge
  * mechanics ON THE PRODUCT HOST wiring (raider.repl.TabShellHost), the same
  * discipline as SessionRegistrySpec (RAI-057): a real hub, scripted publishes,
  * coordination only via `eventually` on logically guaranteed conditions, no
  * assertion inspects a timing.
  *
  * The PTY fixtures (TabShellUnreadPtySpec) cover the rendered surface; these
  * unit fixtures pin the typed seam underneath it:
  *   - P2U-02 (unit): an unknown tab through the PRODUCT executor surfaces as
  *     the typed [[RegistryError.UnknownTab]] ([[ShellFeedback.Failure]],
  *     rendered "error: unknown tab ..."), and events for jobs with no open tab
  *     grow no phantom tabs.
  *   - P2U-01 (unit): through the product wiring, background deliveries bump
  *     the [[TabView]] counter, the accepted RAI-097 renderer shows "(N
  *     unread)" for the background row, focus resets the counter and the badge
  *     is suppressed at 0.
  *   - P2U-03 (unit): concurrent bursts into several background tabs keep the
  *     counters consistent (each delivered envelope counted exactly once; the
  *     focused tab stays at 0).
  */
object TabShellHostUnreadSpec extends ZIOSpecDefault:

  private val jobA = JobId("rai105-unit-job-a")
  private val jobB = JobId("rai105-unit-job-b")
  private val jobT1 = JobId("rai105-unit-burst-1")
  private val jobT2 = JobId("rai105-unit-burst-2")
  private val jobT3 = JobId("rai105-unit-burst-3")
  private val jobPhantom = JobId("rai105-unit-phantom")
  private val rootR = RootId("rai105-unit-root")

  /** The executor's close dialogue is out of scope here; any resolution is a
    * report-only choice.
    */
  private val stubResolver: CloseChoiceResolver = _ =>
    ZIO.succeed(TabCloseChoice.CancelRequested)

  /** Await a logically guaranteed condition, polling with real sleeps under a
    * generous outer bound (the bound never asserts, only fails hangs typed).
    */
  private def eventually(
      max: Duration
  )(cond: ZIO[Any, Any, Boolean]): ZIO[Any, Any, Unit] =
    ZIO.suspendSucceed {
      val deadline = java.lang.System.nanoTime() + max.toNanos
      def loop: ZIO[Any, Any, Unit] =
        cond.flatMap {
          case true => ZIO.unit
          case false =>
            if java.lang.System.nanoTime() >= deadline then
              ZIO.fail("condition not met within the generous outer bound")
            else ZIO.sleep(Duration.fromMillis(20)) *> loop
        }
      loop
    }

  private def diag(text: String): EventPayload = EventPayload.Diagnostics(text)

  private def publish(
      wiring: TabShellHost.Wiring,
      jobId: JobId,
      payload: EventPayload
  ): ZIO[Any, Any, Unit] =
    wiring.hub
      .publish(jobId, rootR, SessionId("sess-" + jobId.value), payload)
      .catchAll(_ => ZIO.unit)

  def spec =
    suite("TabShellHostUnreadSpec (RAI-105: unread badges, product wiring)")(
      test(
        "P2U-02 (unit): unknown tab through the product executor — typed RegistryError.UnknownTab, rendered as error, no tabs opened"
      ) {
        live {
          for
            wiring <- TabShellHost.wiring
            fb <- ShellExecutor.run(
              ShellCommand.Switch(TabId("ghost")),
              wiring.registry,
              stubResolver
            )
            tabs <- wiring.registry.tabs
          yield assertTrue(
            fb == ShellFeedback.Failure(
              RegistryError.UnknownTab(TabId("ghost"))
            )
          ) &&
            assertTrue(
              ShellFeedback.render(fb) == Vector("error: unknown tab ghost")
            ) &&
            assertTrue(tabs.isEmpty)
        }
      },
      test(
        "P2U-02 (unit): events for a job with no open tab — hub folds them, the registry grows no phantom tab"
      ) {
        live {
          for
            wiring <- TabShellHost.wiring
            _ <- publish(wiring, jobPhantom, diag("late-1"))
            _ <- publish(wiring, jobPhantom, diag("late-2"))
            // The hub folds every published envelope (its own state), but the
            // tab registry — the UI read model — must stay empty.
            _ <- eventually(15.seconds)(
              wiring.hub.snapshot(jobPhantom).map(_.exists(_.lastSeq == 2L))
            )
            tabs <- wiring.registry.tabs
          yield assertTrue(tabs.isEmpty)
        }
      },
      test(
        "P2U-01 (unit): background deliveries bump through the product wiring; renderer shows the badge; focus resets; 0 suppressed"
      ) {
        live {
          for
            wiring <- TabShellHost.wiring
            _ <- wiring.registry.create(jobA, rootR, "alpha")
            b <- wiring.registry.create(jobB, rootR, "beta") // beta focused
            _ <- publish(wiring, jobA, diag("bg-1"))
            _ <- publish(wiring, jobA, diag("bg-2"))
            _ <- eventually(15.seconds) {
              wiring.registry.tabs.map(
                _.find(_.label == "alpha").exists(_.unreadCount == 2L)
              )
            }
            views <- wiring.registry.tabs
            focused <- wiring.registry.focused
            bar = TabBarRenderer.tabBar(views, focused)
            _ <- wiring.registry.focus(b) // already focused: no-op reset
            _ <- publish(wiring, jobA, diag("bg-3"))
            _ <- eventually(15.seconds) {
              wiring.registry.tabs.map(
                _.find(_.label == "alpha").exists(_.unreadCount == 3L)
              )
            }
            // Focus alpha: the counter resets to 0 (UI state only).
            alphaId <- ZIO
              .fromOption(views.find(_.label == "alpha").map(_.id))
              .orElseFail("alpha view missing")
            _ <- wiring.registry.focus(alphaId)
            viewsAfter <- wiring.registry.tabs
            focusedAfter <- wiring.registry.focused
            barAfter = TabBarRenderer.tabBar(viewsAfter, focusedAfter)
          yield
            val alphaRow = bar.find(_.contains("1. alpha"))
            val betaRow = bar.find(_.contains("2. beta"))
            assertTrue(alphaRow.exists(_.contains("(2 unread)"))) &&
            assertTrue(betaRow.exists(l => !l.contains("unread"))) &&
            assertTrue(bar.forall(!_.contains("(0 unread)"))) &&
            assertTrue(
              viewsAfter.find(_.label == "alpha").exists(_.unreadCount == 0L)
            ) &&
            assertTrue(barAfter.forall(!_.contains("unread")))
        }
      },
      test(
        "P2U-03 (unit): concurrent bursts into several background tabs — counters consistent, focused tab untouched"
      ) {
        live {
          for
            wiring <- TabShellHost.wiring
            _ <- wiring.registry.create(jobT1, rootR, "t1")
            _ <- wiring.registry.create(jobT2, rootR, "t2")
            _ <- wiring.registry.create(jobT3, rootR, "t3") // t3 focused
            tabs <- wiring.registry.tabs
            t1 = tabs.find(_.label == "t1").get.id
            _ <- wiring.registry.focus(t1) // t1 focused, t2/t3 background
            burst = (jobId: JobId) =>
              ZIO.foreachDiscard(1 to 100)(i =>
                publish(wiring, jobId, diag(s"burst-$i"))
              )
            _ <- burst(jobT2) zipPar burst(jobT3)
            _ <- eventually(30.seconds) {
              wiring.registry.tabs.map { vs =>
                val u2 = vs.find(_.label == "t2").exists(_.unreadCount == 100L)
                val u3 = vs.find(_.label == "t3").exists(_.unreadCount == 100L)
                val u1 = vs.find(_.label == "t1").exists(_.unreadCount == 0L)
                u2 && u3 && u1
              }
            }
            views <- wiring.registry.tabs
            dropped <- wiring.hub.droppedDiagnostics
          yield
            val total = views.map(_.unreadCount).sum
            assertTrue(total == 200L) &&
            assertTrue(dropped == 0L)
        }
      }
    )

end TabShellHostUnreadSpec
