package raider.repl

import raider.core.{JobId, SessionId}
import raider.runtime.events.{EventError, EventHub, EventPayload}
import raider.runtime.jobs.JobStatus
import raider.ui.TabView
import zio.{Duration, Ref, Scope, ZIO}

/** RAI-108: the mock provider for the tab stream — a SCRIPTED FIXTURE stream
  * source for the product tabs entry (`raider repl --tabs` under the
  * scripted/mock backend, Main's `preludePlan.isScripted`). For every tab the
  * user opens with `:new` it publishes one synthetic job stream (Running + N
  * diagnostics lines) through the REAL [[EventHub]] — the exact ADR-024 seam
  * real runtime jobs publish on — so tab switching, unread badges and
  * scrollback on the product entry behave against live events with zero network
  * and zero paid calls.
  *
  * Honest rules:
  *   - it runs ONLY in scripted/mock mode (the caller gates on
  *     `PreludePlan.isScripted`); a live provider never receives synthetic
  *     events — no fake live data, ever;
  *   - it is a publisher on the event seam and nothing more: it never writes to
  *     the terminal (the ONE writer is the shell, RAI-061), never cancels and
  *     never mutates the registry read model (ADR-024 §2.5);
  *   - diagnostics delivery is at-most-once (the hub's bounded diagnostics
  *     buffer may drop; `hub.droppedDiagnostics` counts drops, never silent) —
  *     no exactly-once claim is made here.
  *
  * Pattern parity: the accepted RAI-102 harness feeder
  * (`raider.repl.TabShellHostPtyApp`, test scope) drives the same
  * registry-watch/emitter shape; this is the product-side, mode-gated
  * equivalent so the REAL entry point streams (card RAI-108 acceptance runs the
  * child JVM on `raider.repl.Main`, not on a harness).
  */
object ScriptedTabStream:

  /** Diagnostics lines streamed per opened tab (the burst/async event load of
    * P2E-03; same volume as the accepted RAI-102 PTY acceptance).
    */
  val EventsPerTab: Int = 220

  /** Inter-event delay inside one stream (the burst density; 220 × 15ms ≈ 3.3s
    * of live rendering per tab, enough for typing to genuinely overlap the
    * burst on the product entry).
    */
  val DelayMs: Int = 15

  /** Diagnostics payload — byte-exact scrollback material. */
  val Payload: String = "x" * 32

  private def eventLine(label: String, i: Int): String =
    s"ev $label $i payload $Payload"

  /** Watch the registry; start ONE emitter per unseen tab job. Scoped: the
    * watcher and every emitter die with the shell's scope.
    */
  def run(wiring: TabShellHost.Wiring): ZIO[Scope, Nothing, Unit] =
    Ref
      .make(Map.empty[JobId, Boolean])
      .flatMap(fed => tick(wiring, fed).forever)

  private def tick(
      wiring: TabShellHost.Wiring,
      fed: Ref[Map[JobId, Boolean]]
  ): ZIO[Scope, Nothing, Unit] =
    for
      views <- wiring.registry.tabs
      fresh <- ZIO.foldLeft(views)(List.empty[TabView]) { (acc, v) =>
        fed
          .modify { m =>
            if m.contains(v.jobId) then (None, m)
            else (Some(v), m + (v.jobId -> false))
          }
          .map(_.fold(acc)(v => acc :+ v))
      }
      _ <- ZIO.foreachDiscard(fresh) { v =>
        emitter(wiring.hub, v)
          .catchAll(_ => ZIO.unit) // the hub drop counter is the honest report
          .ensuring(fed.update(m => m.updated(v.jobId, true)))
          .fork
      }
      _ <- ZIO.sleep(Duration.fromMillis(150))
    yield ()

  /** One synthetic stream for one tab: Running + N Diagnostics lines (never a
    * Terminal — the tab stays Running, like a live job would).
    */
  private def emitter(
      hub: EventHub,
      view: TabView
  ): ZIO[Any, EventError, Unit] =
    val sessionId = SessionId("sess-" + view.jobId.value)
    hub
      .publish(
        view.jobId,
        view.rootId,
        sessionId,
        EventPayload.JobStatusChanged(
          JobStatus.Queued,
          JobStatus.Running,
          None
        )
      ) *>
      ZIO.foreachDiscard(1 to EventsPerTab) { i =>
        hub
          .publish(
            view.jobId,
            view.rootId,
            sessionId,
            EventPayload.Diagnostics(eventLine(view.label, i))
          ) <* ZIO.sleep(Duration.fromMillis(DelayMs.toLong))
      }

end ScriptedTabStream
