package raider.ui

import raider.core.{JobId, RootId}
import zio.{UIO, ZIO}

import java.util.UUID

/** The caller's decision when `:close` hits a live tab (card RAI-061).
  * `CancelRequested` is a REPORT to the runtime cancel binding (RAI-062):
  * nothing in the UI layer ever cancels, interrupts or publishes a job —
  * ADR-024 §2.5.
  */
trait CloseChoiceResolver:
  def resolve(running: CloseDecision.RunningChoice): UIO[TabCloseChoice]

object CloseChoiceResolver:

  /** Interactive resolver over the same ONE reader/writer. `h`/`hide` →
    * [[TabCloseChoice.Hide]]; `c`/`cancel` →
    * [[TabCloseChoice.CancelRequested]]; anything else is re-asked up to
    * `attempts` times, then the SAFE, non-destructive default applies:
    * CancelRequested (report only — nothing hidden, nothing cancelled). EOF
    * counts as an invalid answer.
    */
  def fromTerminal(
      terminal: ShellTerminal,
      prompt: String,
      attempts: Int = 3
  ): CloseChoiceResolver =
    new CloseChoiceResolver:
      def resolve(running: CloseDecision.RunningChoice): UIO[TabCloseChoice] =
        def ask(left: Int): UIO[TabCloseChoice] =
          // A dead terminal during the dialogue behaves like EOF: the safe
          // non-destructive default (report-only) applies.
          terminal.readLine(prompt).fold(_ => None, identity).flatMap {
            case None => ZIO.succeed(TabCloseChoice.CancelRequested)
            case Some(line) =>
              line.trim.toLowerCase match
                case "h" | "hide" => ZIO.succeed(TabCloseChoice.Hide)
                case "c" | "cancel" =>
                  ZIO.succeed(TabCloseChoice.CancelRequested)
                case _ =>
                  if left <= 1 then
                    terminal
                      .printAbove(
                        Vector(
                          "hint: answer h (hide) or c (cancel); defaulting to cancel-request (report only)"
                        )
                      )
                      .as(TabCloseChoice.CancelRequested)
                  else ask(left - 1)
          }
        ask(attempts)

/** Effect layer between the router and the registry (card RAI-061). Registry
  * failures NEVER escape the executor: they surface as the typed
  * [[ShellFeedback.Failure]] the renderer prints as `error: ...`.
  */
object ShellExecutor:

  /** Synthetic job/root binding of `:new` (card RAI-061): unique ids so the tab
    * is a well-formed registry entry, NOT wired to any runtime job — the real
    * binding is card RAI-062.
    */
  private def syntheticJobId: JobId = JobId("ui-" + UUID.randomUUID())
  private def syntheticRootId: RootId = RootId("ui-root-" + UUID.randomUUID())

  private def asFeedback(
      io: ZIO[Any, RegistryError, ShellFeedback]
  ): UIO[ShellFeedback] =
    io.fold(ShellFeedback.Failure(_), identity)

  /** Execute one routed command against the registry. Total: always returns a
    * typed feedback. The screen providers ([[ShellViews]], card RAI-088; the
    * [[AuxTabProvider]], card LIN-04A) are PURE snapshot readers consulted once
    * per `:plugins`/`:state`/`:aux` command — the UI still performs no IO of
    * its own. `Aux` never touches the registry: a switch to the auxiliary tab
    * and back leaves focus, unread and drafts with the session tabs (ADR-024
    * §2.5).
    */
  def run(
      command: ShellCommand,
      registry: SessionRegistry,
      resolver: CloseChoiceResolver,
      views: ShellViews = ShellViews.detached,
      aux: Option[AuxTabProvider] = None
  ): UIO[ShellFeedback] =
    command match
      case ShellCommand.Tabs =>
        for
          views <- registry.tabs
          focused <- registry.focused
        yield ShellFeedback.TabBar(TabBarRenderer.tabBar(views, focused))
      case ShellCommand.New(label) =>
        registry
          .create(syntheticJobId, syntheticRootId, label)
          .flatMap(tabId => registry.tab(tabId).map(ShellFeedback.Created(_)))
          .fold(ShellFeedback.Failure(_), identity)
      case ShellCommand.Switch(tabId) =>
        asFeedback {
          registry.focus(tabId) *>
            registry.tab(tabId).map(ShellFeedback.Switched(_))
        }
      case ShellCommand.Next =>
        asFeedback(
          registry
            .focusNext()
            .flatMap(tabId =>
              registry.tab(tabId).map(ShellFeedback.Switched(_))
            )
        )
      case ShellCommand.Prev =>
        asFeedback(
          registry
            .focusPrev()
            .flatMap(tabId =>
              registry.tab(tabId).map(ShellFeedback.Switched(_))
            )
        )
      case ShellCommand.Rename(label) =>
        registry.focused.flatMap {
          case None =>
            ZIO.succeed(ShellFeedback.Failure(RegistryError.NoTabs))
          case Some(tabId) =>
            asFeedback(
              registry.rename(tabId, label) *>
                registry.tab(tabId).map(ShellFeedback.Renamed(_))
            )
        }
      case ShellCommand.Close(tabId) => close(registry, resolver, tabId)
      case ShellCommand.Help =>
        ZIO.succeed(ShellFeedback.Help(TabBarRenderer.helpLines))
      case ShellCommand.Plugins =>
        ZIO.succeed(ShellFeedback.Plugins(views.plugins()))
      case ShellCommand.State(scope) =>
        ZIO.succeed(
          views.state(scope) match
            case None        => ShellFeedback.StateStoreMissing(scope)
            case Some(usage) => ShellFeedback.State(scope, usage)
        )
      case ShellCommand.Aux =>
        ZIO.succeed(
          aux match
            case None => ShellFeedback.AuxTabMissing
            case Some(p) if !p.available =>
              ShellFeedback.AuxTabUnavailable(p.title)
            case Some(p) => ShellFeedback.AuxTab(p.title, p.render)
        )
      case ShellCommand.Quit => ZIO.succeed(ShellFeedback.Quit)
      case ShellCommand.Draft(line) =>
        registry.focused.flatMap {
          case None =>
            ZIO.succeed(ShellFeedback.Failure(RegistryError.NoTabs))
          case Some(tabId) =>
            asFeedback(
              for
                _ <- registry.draftAppend(tabId, line)
                draft <- registry.draftGet(tabId)
                view <- registry.tab(tabId)
              yield ShellFeedback.DraftEchoed(tabId, view.label, line, draft)
            )
        }

  /** Close flow (card RAI-061): the REGISTRY returns the typed decision; the
    * resolver only decides for a LIVE tab. `Closed` → removed, affected
    * same-root tabs listed (root close reports its children). Live → dialogue:
    * Hide executes (`registry.hide`, the job keeps running); CancelRequested is
    * reported only, never self-cancelled.
    */
  def close(
      registry: SessionRegistry,
      resolver: CloseChoiceResolver,
      tabId: TabId
  ): UIO[ShellFeedback] =
    registry.tab(tabId).either.flatMap {
      case Left(err) => ZIO.succeed(ShellFeedback.Failure(err))
      case Right(view) =>
        registry.close(tabId).either.flatMap {
          case Left(err) => ZIO.succeed(ShellFeedback.Failure(err))
          case Right(CloseDecision.Closed(affected)) =>
            affectedViews(registry, affected).map(views =>
              ShellFeedback.ClosedTerminal(tabId, view.label, views)
            )
          case Right(running: CloseDecision.RunningChoice) =>
            resolver.resolve(running).flatMap {
              case TabCloseChoice.Hide =>
                registry.hide(tabId).either.flatMap {
                  case Left(err) => ZIO.succeed(ShellFeedback.Failure(err))
                  case Right(_) =>
                    affectedViews(registry, running.affected).map(views =>
                      ShellFeedback.Hidden(tabId, view.label, views)
                    )
                }
              case TabCloseChoice.CancelRequested =>
                affectedViews(registry, running.affected).map(views =>
                  ShellFeedback.CancelReported(tabId, view.label, views)
                )
            }
        }
    }

  /** Resolve the lineage ids to (id, label) pairs over the CURRENTLY open tabs
    * (a concurrently closed sibling is skipped in the rendered list; the typed
    * decision keeps the full id list).
    */
  private def affectedViews(
      registry: SessionRegistry,
      ids: List[TabId]
  ): UIO[List[(TabId, String)]] =
    registry.tabs.map(views =>
      ids.flatMap(id => views.find(_.id == id).map(v => id -> v.label))
    )
