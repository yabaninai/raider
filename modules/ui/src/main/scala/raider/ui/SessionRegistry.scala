package raider.ui

import raider.core.{JobId, RootId}
import raider.runtime.events.{
  EventEnvelope,
  EventPayload,
  EventHub,
  SessionReducer,
  SessionSnapshot,
  StatusText
}
import raider.runtime.jobs.JobStatus
import zio.stream.ZStream
import zio.{Fiber, IO, Ref, UIO, ZIO}

import java.util.UUID
import scala.collection.mutable

/** Immutable identity of one agent tab (card RAI-057).
  *
  * A tab is a UI-side view of one job: `(jobId, rootId, label)`. The id is
  * assigned once at `create` and never changes; tabs are never mutated in place
  * — their observable state lives in the registry's refs.
  */
final case class TabId(value: String) extends AnyVal

/** Typed registry failures (no silent unknowns — card RAI-057, TAB-02). */
sealed trait RegistryError:
  def message: String

object RegistryError:

  /** Any operation on a tab that is not (or no longer) open. */
  final case class UnknownTab(tabId: TabId) extends RegistryError:
    val message: String = s"unknown tab ${tabId.value}"

  /** Navigation with no open tabs. */
  case object NoTabs extends RegistryError:
    val message: String = "no tabs are open"

/** Bounded per-tab history: the folded [[SessionSnapshot]] after every accepted
  * event (plus the subscribe-time state as the first entry).
  *
  * `evicted` is an honest counter of snapshots dropped by the bound (never
  * hidden, card RAI-057). The newest snapshot — in particular a `Terminal` one
  * — is always the last entry and always also available via
  * `SessionRegistry.snapshot`; buffer eviction can never lose the terminal
  * status.
  */
final case class HistoryView(
    entries: Vector[SessionSnapshot],
    evicted: Long
)

/** Bounded per-tab scrollback: the last `lines` of Diagnostics/ToolProgress
  * text. `evicted` counts the lines pushed out by the bound (honest, never
  * silent).
  */
final case class ScrollbackView(
    lines: Vector[String],
    evicted: Long
)

/** Read model of one tab for the tab bar (card RAI-057: label, jobId, status,
  * unread, lastSeq).
  *
  * `status` is read from the hub's folded snapshot (`EventHub.snapshot`) — the
  * authoritative job state; for a job that has published nothing it falls back
  * to the tab's local initial state (`Queued`). `statusText` is the ONE
  * canonical [[StatusText]] line over that SAME authoritative snapshot
  * (RAI-097): the unified format with the CLI — the tab bar keeps no local
  * duplicate render. `lastSeq` is the tab's own watermark: the last sequence
  * folded into its buffers.
  */
final case class TabView(
    id: TabId,
    label: String,
    jobId: JobId,
    rootId: RootId,
    status: JobStatus,
    statusText: String,
    unreadCount: Long,
    lastSeq: Long
)

/** The caller's decision for a running tab (card RAI-057: the registry returns
  * the typed choice, it NEVER cancels or mutates the runtime itself).
  */
enum TabCloseChoice:

  /** Hide the tab from the UI; the job keeps running in the runtime. Executor:
    * `SessionRegistry.hide`.
    */
  case Hide

  /** Request cancellation through the runtime (the caller performs the cancel
    * with runtime APIs — this registry has none, ADR-024 §2.5), then close the
    * tab once the job reached a terminal status.
    */
  case CancelRequested

/** Result of `SessionRegistry.close` (card RAI-057).
  *
  * `affected` is the lineage set: the other open tabs whose `rootId` matches
  * the closed tab's `rootId` (creation order), for the caller to handle —
  * closing a root session reports its children this way.
  */
enum CloseDecision:

  /** The tab's job has a terminal status (per the hub snapshot): the tab is
    * removed from the registry.
    */
  case Closed(affected: List[TabId])

  /** The tab is still live: the registry changed nothing. The caller must
    * present [[TabCloseChoice]] and act on it (`Hide` → `hide`;
    * `CancelRequested` → runtime cancel, then `close` again).
    */
  case RunningChoice(affected: List[TabId])

/** UI-side session/tab state over the ADR-024 event seam (card RAI-057, roadmap
  * P2.A slice).
  *
  * Contract highlights:
  *   - One hub subscription per tab, started at `create`; envelopes are
  *     deduplicated by `(jobId, seq)` and reordered by `seq` before folding
  *     (the hub relays the two delivery classes by independent forwarders, so
  *     cross-class arrival order is unspecified — ADR-024 §4/`subscribe`).
  *   - Focus is UI state only: it never publishes, never cancels, never touches
  *     jobs/hub/policy (ADR-024 §2.5). A switch is ONE call
  *     (`focus`/`focusNext`/`focusPrev`).
  *   - Drafts are UI-local strings (not events, never published).
  *   - Per-tab buffers are bounded with honest eviction counters; a `Terminal`
  *     event always reaches the folded snapshot.
  *
  * There is no `stop()` in this slice: tab consumer fibers are interrupted by
  * `close`/`hide`; the registry itself is a process-lifetime object like the
  * hub it is built over.
  */
trait SessionRegistry:

  /** Register a tab for `jobId` and start its hub subscription. The new tab
    * becomes focused. Returns the immutable tab id.
    */
  def create(jobId: JobId, rootId: RootId, label: String): UIO[TabId]

  /** All open tabs in creation order. */
  def tabs: UIO[List[TabView]]

  /** One tab; [[RegistryError.UnknownTab]] if not open. */
  def tab(tabId: TabId): IO[RegistryError, TabView]

  /** Close a tab.
    *
    *   - Unknown tab → [[RegistryError.UnknownTab]].
    *   - Tab live (hub status not terminal — including a job that has published
    *     nothing, i.e. initial `Queued`) → [[CloseDecision.RunningChoice]] with
    *     the lineage set; nothing is removed, cancelled or published.
    *   - Tab terminal (hub status terminal) → removed; [[CloseDecision.Closed]]
    *     with the lineage set (other open tabs of the same `rootId`).
    */
  def close(tabId: TabId): IO[RegistryError, CloseDecision]

  /** Remove a tab from the UI without touching the runtime — the executor of
    * [[TabCloseChoice.Hide]].
    */
  def hide(tabId: TabId): IO[RegistryError, Unit]

  /** Rename a tab's display label (card RAI-061 `:rename`). UI-local only:
    * never a runtime, job, owner, budget or policy mutation. Unknown tab →
    * [[RegistryError.UnknownTab]].
    */
  def rename(tabId: TabId, label: String): IO[RegistryError, Unit]

  /** Currently focused tab, if any. */
  def focused: UIO[Option[TabId]]

  /** Focus a tab (ONE call). UI state only: sets the focus and resets that
    * tab's unread counter. Unknown tab → [[RegistryError.UnknownTab]].
    */
  def focus(tabId: TabId): IO[RegistryError, Unit]

  /** Move the focus to the next tab (creation order, wrapping). UI state only;
    * resets the newly focused tab's unread. [[RegistryError.NoTabs]] if no tab
    * is open.
    */
  def focusNext(): IO[RegistryError, TabId]

  /** Move the focus to the previous tab (creation order, wrapping). UI state
    * only; resets the newly focused tab's unread. [[RegistryError.NoTabs]] if
    * no tab is open.
    */
  def focusPrev(): IO[RegistryError, TabId]

  /** Append to the tab's UI-local draft (never an event, never published). */
  def draftAppend(tabId: TabId, text: String): IO[RegistryError, Unit]

  /** Clear the tab's UI-local draft. */
  def draftClear(tabId: TabId): IO[RegistryError, Unit]

  /** Read the tab's UI-local draft. */
  def draftGet(tabId: TabId): IO[RegistryError, String]

  /** The tab's local folded state (its watermark), including a `Terminal`
    * status once delivered.
    */
  def snapshot(tabId: TabId): IO[RegistryError, SessionSnapshot]

  /** The tab's bounded history of folded snapshots + eviction counter. */
  def history(tabId: TabId): IO[RegistryError, HistoryView]

  /** The tab's bounded scrollback lines + eviction counter. */
  def scrollback(tabId: TabId): IO[RegistryError, ScrollbackView]

object SessionRegistry:

  /** History bound: the last 200 folded snapshots per tab. */
  val HistoryCap: Int = 200

  /** Scrollback bound: the last 500 text lines per tab. */
  val ScrollbackCap: Int = 500

  def make(hub: EventHub): UIO[SessionRegistry] =
    Ref
      .make(
        RegState(Map.empty[TabId, TabLive], Vector.empty[TabId], None)
      )
      .map(stateRef => new Live(hub, stateRef))

  /** The registry's single source of truth: open tabs, their creation order and
    * the current focus — updated atomically, so the read model (`tabs`,
    * navigation, close) can never observe a torn state.
    */
  private final case class RegState(
      tabs: Map[TabId, TabLive],
      order: Vector[TabId],
      focus: Option[TabId]
  )

  /** Per-tab mutable state (all refs; the tab object itself is never mutated).
    * `reducerFailures` is an honest diagnostic counter for a structurally
    * impossible invariant breach (the hub only buffers envelopes its own fold
    * accepted).
    */
  private final class TabLive(
      val id: TabId,
      val jobId: JobId,
      val rootId: RootId,
      val label: Ref[String],
      val snapshot: Ref[SessionSnapshot],
      val history: Ref[Vector[SessionSnapshot]],
      val historyEvicted: Ref[Long],
      val scrollback: Ref[Vector[String]],
      val scrollbackEvicted: Ref[Long],
      val unread: Ref[Long],
      val draft: Ref[String],
      val reducerFailures: Ref[Long],
      val consumer: Ref[Option[Fiber.Runtime[Nothing, Unit]]]
  )

  /** Per-tab reassembler: dedup by seq + cross-class reordering before the fold
    * (ADR-024 §4: the two class forwarders give unspecified relative arrival
    * order; per-job seq order is the only order the reducer speaks about).
    * Single-consumer by construction (one stream fold per tab).
    *
    * A seq gap stalls every later envelope until the gap fills — correct only
    * while the hub forwarders deliver each envelope (forwarder supervision is a
    * future card).
    */
  private final class Reorder(initialLastSeq: Long):
    private var last: Long = initialLastSeq
    private val pending = mutable.Map.empty[Long, EventEnvelope]

    /** Accept one envelope; return the (possibly empty) batch now foldable in
      * strict seq order. Duplicates (`seq <= last`) are dropped.
      */
    def offer(e: EventEnvelope): Vector[EventEnvelope] =
      if e.seq <= last then Vector.empty
      else
        pending(e.seq) = e
        val out = Vector.newBuilder[EventEnvelope]
        var next = last + 1L
        while pending.contains(next) do
          out += pending.remove(next).get
          next += 1L
        last = next - 1L
        out.result()

  private final class Live(
      hub: EventHub,
      stateRef: Ref[RegState]
  ) extends SessionRegistry:

    def create(jobId: JobId, rootId: RootId, label: String): UIO[TabId] =
      for
        // Subscribe FIRST (loss-window-free registration + watermark, ADR-024
        // §2.2); the tab becomes visible only once fully wired.
        (initial, stream) <- hub.subscribe(jobId)
        snapshotR <- Ref.make[SessionSnapshot](initial)
        historyR <- Ref.make(Vector[SessionSnapshot](initial))
        historyEvR <- Ref.make(0L)
        scrollR <- Ref.make(Vector.empty[String])
        scrollEvR <- Ref.make(0L)
        unreadR <- Ref.make(0L)
        draftR <- Ref.make("")
        failR <- Ref.make(0L)
        labelR <- Ref.make(label)
        consumerR <- Ref.make[Option[Fiber.Runtime[Nothing, Unit]]](None)
        id = TabId(UUID.randomUUID().toString)
        live = new TabLive(
          id,
          jobId,
          rootId,
          labelR,
          snapshotR,
          historyR,
          historyEvR,
          scrollR,
          scrollEvR,
          unreadR,
          draftR,
          failR,
          consumerR
        )
        fiber <- startConsumer(live, initial, stream)
        _ <- consumerR.set(Some(fiber))
        _ <- stateRef.update { s =>
          s.copy(
            tabs = s.tabs + (id -> live),
            order = s.order :+ id,
            focus = Some(id)
          )
        }
        // Review D1-1: the forked consumer may already have delivered an event
        // while this tab was not yet registered/focused, and `bumpUnread` then
        // could not see the focus. The tab is focused from now on, so later
        // deliveries skip the bump; this reset closes the creation window.
        // (The alternative — registering state BEFORE `startConsumer` — was
        // rejected: it would make the tab visible before its consumer fiber
        // exists, opening a close-before-fork leak window.)
        _ <- live.unread.set(0L)
      yield id

    def tabs: UIO[List[TabView]] =
      stateRef.get
        .flatMap(s => ZIO.foreach(s.order)(id => viewTab(s.tabs(id))))
        .map(_.toList)

    def tab(tabId: TabId): IO[RegistryError, TabView] =
      for
        t <- requireTab(tabId)
        v <- viewTab(t)
      yield v

    def close(tabId: TabId): IO[RegistryError, CloseDecision] =
      for
        t <- requireTab(tabId)
        local <- t.snapshot.get
        hubSnap <- hub.snapshot(t.jobId)
        // The hub's folded state is the authoritative job status (the tab's
        // local consumer may lag by queued envelopes); `None` = the job has
        // published nothing (initial `Queued` — still live, still a choice).
        terminal = JobStatus.terminal.contains(
          hubSnap.map(_.status).getOrElse(local.status)
        )
        affected <- lineage(t.rootId, tabId)
        _ <- if terminal then removeTab(tabId) else ZIO.unit
      yield
        if terminal
        then CloseDecision.Closed(affected)
        else CloseDecision.RunningChoice(affected)

    def hide(tabId: TabId): IO[RegistryError, Unit] =
      for
        _ <- requireTab(tabId)
        _ <- removeTab(tabId)
      yield ()

    def rename(tabId: TabId, label: String): IO[RegistryError, Unit] =
      requireTab(tabId).flatMap(_.label.set(label))

    def focused: UIO[Option[TabId]] = stateRef.get.map(_.focus)

    def focus(tabId: TabId): IO[RegistryError, Unit] =
      for
        t <- requireTab(tabId)
        // UI state only: focus ref + unread reset. No hub call, no publish,
        // no cancel (ADR-024 §2.5). The focus is committed before the unread
        // reset, so a concurrently delivered event can never bump the counter
        // after the reset.
        ok <- stateRef.modify { s =>
          if s.tabs.contains(tabId)
          then (true, s.copy(focus = Some(tabId)))
          else (false, s)
        }
        _ <-
          if ok then t.unread.set(0L)
          else ZIO.fail(RegistryError.UnknownTab(tabId))
      yield ()

    def focusNext(): IO[RegistryError, TabId] = move(1)

    def focusPrev(): IO[RegistryError, TabId] = move(-1)

    def draftAppend(tabId: TabId, text: String): IO[RegistryError, Unit] =
      requireTab(tabId).flatMap(_.draft.update(_ + text))

    def draftClear(tabId: TabId): IO[RegistryError, Unit] =
      requireTab(tabId).flatMap(_.draft.set(""))

    def draftGet(tabId: TabId): IO[RegistryError, String] =
      requireTab(tabId).flatMap(_.draft.get)

    def snapshot(tabId: TabId): IO[RegistryError, SessionSnapshot] =
      requireTab(tabId).flatMap(_.snapshot.get)

    def history(tabId: TabId): IO[RegistryError, HistoryView] =
      for
        t <- requireTab(tabId)
        entries <- t.history.get
        evicted <- t.historyEvicted.get
      yield HistoryView(entries, evicted)

    def scrollback(tabId: TabId): IO[RegistryError, ScrollbackView] =
      for
        t <- requireTab(tabId)
        lines <- t.scrollback.get
        evicted <- t.scrollbackEvicted.get
      yield ScrollbackView(lines, evicted)

    // ----- internals -------------------------------------------------------

    private def startConsumer(
        live: TabLive,
        initial: SessionSnapshot,
        stream: ZStream[Any, Nothing, EventEnvelope]
    ): UIO[Fiber.Runtime[Nothing, Unit]] =
      val reorder = new Reorder(initial.lastSeq)
      // `runFoldZIO`, not `runFold`: the fold step is an effect, and the
      // effectful overload is the only one that RUNS it. (The pure `runFold`
      // would type-check via the bottom-type initial `()`, inferring the
      // accumulator as the effect type itself — the events would be reassembled
      // but never folded into the tab state.)
      stream
        .runFoldZIO(()) { (_, e) =>
          ZIO.foreachDiscard(reorder.offer(e))(applyEvent(live, _))
        }
        .fork

    /** Fold one in-order envelope through [[SessionReducer]] and dispatch it
      * into the tab buffers + unread counter.
      */
    private def applyEvent(live: TabLive, e: EventEnvelope): UIO[Unit] =
      for
        cur <- live.snapshot.get
        _ <- SessionReducer.reduce(cur, e).either.flatMap {
          case Left(_) =>
            // Structurally unreachable (the hub only buffers envelopes its own
            // fold accepted); counted honestly, never a silent drop of state:
            // the previous snapshot is kept.
            live.reducerFailures.update(_ + 1L)
          case Right(folded) =>
            live.snapshot.set(folded) *>
              appendHistory(live, folded) *>
              appendScrollback(live, e.payload) *>
              bumpUnread(live)
        }
      yield ()

    private def appendHistory(
        live: TabLive,
        folded: SessionSnapshot
    ): UIO[Unit] =
      live.history
        .modify { v =>
          val all = v :+ folded
          if all.length > HistoryCap
          then ((all.length - HistoryCap).toLong, all.takeRight(HistoryCap))
          else (0L, all)
        }
        .flatMap(ev =>
          if ev > 0L then live.historyEvicted.update(_ + ev) else ZIO.unit
        )

    private def appendScrollback(
        live: TabLive,
        payload: EventPayload
    ): UIO[Unit] =
      val lines = scrollLines(payload)
      if lines.isEmpty then ZIO.unit
      else
        live.scrollback
          .modify { v =>
            val all = v ++ lines
            if all.length > ScrollbackCap
            then
              (
                (all.length - ScrollbackCap).toLong,
                all.takeRight(ScrollbackCap)
              )
            else (0L, all)
          }
          .flatMap(ev =>
            if ev > 0L then live.scrollbackEvicted.update(_ + ev) else ZIO.unit
          )

    /** Scrollback sources (card RAI-057): Diagnostics text and ToolProgress
      * tails only; everything else changes no text lines.
      */
    private def scrollLines(payload: EventPayload): Vector[String] =
      payload match
        case EventPayload.Diagnostics(text)        => splitLines(text)
        case EventPayload.ToolProgress(_, _, tail) => tail.lines
        case _                                     => Vector.empty

    /** Line split semantics: CRLF normalized to LF, split on LF; a trailing
      * newline is a terminator, not an empty line.
      */
    private def splitLines(text: String): Vector[String] =
      val normalized = text.replace("\r\n", "\n")
      val parts = normalized.split("\n", -1).toVector
      if parts.nonEmpty && parts.last.isEmpty && normalized.endsWith("\n")
      then parts.dropRight(1)
      else parts

    /** Unread accounting (card RAI-057): every event delivered to a tab that is
      * NOT focused increments its counter; focus resets it to 0.
      */
    private def bumpUnread(live: TabLive): UIO[Unit] =
      stateRef.get.flatMap { s =>
        if s.focus.contains(live.id) then ZIO.unit
        else live.unread.update(_ + 1L)
      }

    /** ONE-call navigation (card RAI-057: a switch is at most two actions, here
      * exactly one): ring over creation order; with no current focus the first
      * tab is taken.
      */
    private def move(dir: Int): IO[RegistryError, TabId] =
      stateRef
        .modify { s =>
          if s.order.isEmpty
          then (Left(RegistryError.NoTabs): Either[RegistryError, TabId], s)
          else
            val idx =
              s.focus.fold(-1)(f => s.order.indexWhere(_ == f))
            val target =
              if idx < 0 then s.order.head
              else
                s.order(
                  ((idx + dir) % s.order.size + s.order.size) % s.order.size
                )
            (Right(target), s.copy(focus = Some(target)))
        }
        .flatMap {
          case Left(err) => ZIO.fail(err)
          case Right(target) =>
            stateRef.get.flatMap(st =>
              st.tabs
                .get(target)
                .fold(ZIO.succeed(target): IO[RegistryError, TabId])(t =>
                  t.unread.set(0L).as(target)
                )
            )
        }

    /** Remove a tab: interrupt its consumer (which unregisters the hub queue),
      * then atomically drop it from the state and keep the focus valid (the tab
      * after the removed one in creation order, or `None` when it was the last
      * one). Idempotent under concurrent double-close.
      */
    private def removeTab(tabId: TabId): UIO[Unit] =
      for
        maybeConsumer <- stateRef.get.flatMap { s =>
          s.tabs
            .get(tabId)
            .fold(
              ZIO.succeed(Option.empty[Fiber.Runtime[Nothing, Unit]])
            )(t => t.consumer.get)
        }
        _ <- maybeConsumer.fold(ZIO.unit)(_.interrupt.unit)
        // modify yields the newly focused tab (if any) whose unread counter
        // must be reset
        nextFocusTab <- stateRef.modify { s =>
          s.tabs.get(tabId) match
            case None => (None: Option[TabLive], s)
            case Some(_) =>
              val nextFocus =
                if s.focus.contains(tabId)
                then
                  if s.order.size > 1
                  then
                    Some(s.order((s.order.indexOf(tabId) + 1) % s.order.size))
                  else None
                else s.focus
              (
                nextFocus.flatMap(id => s.tabs.get(id)),
                s.copy(
                  tabs = s.tabs - tabId,
                  order = s.order.filter(_ != tabId),
                  focus = nextFocus
                )
              )
        }
        _ <- nextFocusTab.fold(ZIO.unit)(_.unread.set(0L))
      yield ()

    /** Lineage set (card RAI-057): other open tabs with the same `rootId`,
      * creation order, for the caller to handle.
      */
    private def lineage(rootId: RootId, exclude: TabId): UIO[List[TabId]] =
      stateRef.get.map { s =>
        s.order
          .filter(id => id != exclude && s.tabs(id).rootId == rootId)
          .toList
      }

    private def viewTab(t: TabLive): UIO[TabView] =
      for
        local <- t.snapshot.get
        hubSnap <- hub.snapshot(t.jobId)
        unread <- t.unread.get
        label <- t.label.get
        // The hub's folded snapshot is the authoritative job state; the ONE
        // canonical status line renders over exactly that snapshot (RAI-097).
        snap = hubSnap.getOrElse(local)
      yield TabView(
        t.id,
        label,
        t.jobId,
        t.rootId,
        status = snap.status,
        statusText = StatusText.render(snap),
        unreadCount = unread,
        lastSeq = local.lastSeq
      )

    private def requireTab(tabId: TabId): IO[RegistryError, TabLive] =
      stateRef.get.flatMap { s =>
        s.tabs.get(tabId) match
          case Some(t) => ZIO.succeed(t)
          case None    => ZIO.fail(RegistryError.UnknownTab(tabId))
      }

  end Live

end SessionRegistry
