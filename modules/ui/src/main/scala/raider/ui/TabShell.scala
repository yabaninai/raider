package raider.ui

import raider.core.JobId
import raider.runtime.events.EventHub
import zio.{Queue, Scope, UIO, ZIO}

/** The inline tab shell (card RAI-061): a THIN JLine loop over
  * [[SessionRegistry]] + [[EventHub]].
  *
  *   - All parsing/rendering is pure ([[CommandRouter]], [[TabBarRenderer]],
  *     [[ShellFeedback]]); the registry semantics are already tested (RAI-057).
  *   - ONE writer: everything visible goes through [[ShellTerminal.printAbove]]
  *     (JLine printAbove — the draft survives background writes, spike
  *     RAI-056). There is no raw println anywhere in the session.
  *   - Tab-bar updates on hub events come from the background pump below: label
  *     + status + unread, rendered per event.
  *   - A switch is ONE command (executor calls `focus`/`focusNext`/`focusPrev`
  *     once); focus never calls the hub and never cancels (ADR-024 §2.5).
  *   - The `:plugins`/`:state` screens (RAI-088) read their data from the PURE
  *     [[ShellViews]] providers wired at construction — the shell itself
  *     performs no IO beyond the ONE writer/reader (terminal).
  *   - The `:aux` screen (LIN-04A, ADR-027 §3.3) is the ONE auxiliary tab: a
  *     pure [[AuxTabProvider]] wired at construction, display-only — switching
  *     to it and back never touches the session registry.
  */
object TabShell:

  /** Optional chat behavior hosted by the same input loop as the tab shell.
    * Commands remain routed by the shell; only ordinary lines and explicitly
    * chat-owned commands are delegated.
    */
  trait ChatMode:
    def command(raw: String): Option[UIO[Unit]]
    def submit(prompt: String): UIO[Unit]

  final case class Config(
      prompt: String = "raider> ",
      closePrompt: String =
        "tab is running - [h]ide (job keeps running) or [c]ancel-request? "
  )

  /** Run the shell until `:quit`/EOF. Scoped: the background tab-bar pump (and
    * every hub subscription it forks) is interrupted when the scope exits. The
    * terminal lifetime is the caller's ([[ShellTerminal.jline]] is scoped too).
    *
    * `aux` registers the ONE auxiliary tab (card LIN-04A, ADR-027 §3.3): a pure
    * [[AuxTabProvider]] behind `:aux`, default none. It is display-only —
    * switching to it and back never touches the session registry.
    */
  def run(
      hub: EventHub,
      registry: SessionRegistry,
      terminal: ShellTerminal,
      resolver: CloseChoiceResolver,
      config: Config = Config(),
      views: ShellViews = ShellViews.detached,
      aux: Option[AuxTabProvider] = None,
      chat: Option[ChatMode] = None
  ): ZIO[Scope, Throwable, Unit] =
    for
      pump <- TabBarPump.make(hub, registry, terminal)
      _ <- loop(registry, terminal, resolver, config, pump, views, aux, chat)
    yield ()

  private def loop(
      registry: SessionRegistry,
      terminal: ShellTerminal,
      resolver: CloseChoiceResolver,
      config: Config,
      pump: TabBarPump,
      views: ShellViews,
      aux: Option[AuxTabProvider],
      chat: Option[ChatMode]
  ): ZIO[Any, Throwable, Unit] =
    terminal.readLine(config.prompt).flatMap {
      case None => ZIO.unit // EOF: same as :quit
      case Some(line) =>
        val trimmed = line.trim
        // Idle Ctrl+C (JLine already cleared the line) or a blank line: no
        // action, no output — drafts and tabs untouched.
        if trimmed.isEmpty then
          loop(registry, terminal, resolver, config, pump, views, aux, chat)
        else
          for
            chatAction = chat.flatMap(_.command(line))
            _ <- chatAction match
              case Some(action) =>
                action *> loop(
                  registry,
                  terminal,
                  resolver,
                  config,
                  pump,
                  views,
                  aux,
                  chat
                )
              case None =>
                for
                  tabs <- registry.tabs
                  focused <- registry.focused
                  feedback <-
                    CommandRouter
                      .parse(line, tabs, focused)
                      .fold(
                        err =>
                          err match
                            case ShellInputError.UnknownCommand(_)
                                if chat.nonEmpty =>
                              chat.get
                                .submit(line)
                                .as(ShellFeedback.Help(Vector.empty))
                            case _ => ZIO.succeed(ShellFeedback.Hint(err)),
                        cmd =>
                          cmd match
                            case ShellCommand.Draft(text) if chat.nonEmpty =>
                              chat.get
                                .submit(text)
                                .as(ShellFeedback.Help(Vector.empty))
                            case _ =>
                              ShellExecutor
                                .run(cmd, registry, resolver, views, aux)
                      )
                  _ <- terminal.printAbove(ShellFeedback.render(feedback))
                  _ <- afterCommand(feedback, pump)
                  _ <- continue(
                    feedback,
                    registry,
                    terminal,
                    resolver,
                    config,
                    pump,
                    views,
                    aux,
                    chat
                  )
                yield ()
          yield ()
    }

  private def afterCommand(
      feedback: ShellFeedback,
      pump: TabBarPump
  ): UIO[Unit] =
    feedback match
      case ShellFeedback.Created(tab) =>
        // Watch the (synthetic) job so later hub events re-render the bar;
        // also render once now — the tab set changed.
        pump.watch(tab.jobId) *> pump.render
      case ShellFeedback.Switched(_) | ShellFeedback.Renamed(_) |
          ShellFeedback.ClosedTerminal(_, _, _) |
          ShellFeedback.Hidden(_, _, _) |
          ShellFeedback.CancelReported(_, _, _) =>
        pump.render
      case _ => ZIO.unit

  private def continue(
      feedback: ShellFeedback,
      registry: SessionRegistry,
      terminal: ShellTerminal,
      resolver: CloseChoiceResolver,
      config: TabShell.Config,
      pump: TabBarPump,
      views: ShellViews,
      aux: Option[AuxTabProvider],
      chat: Option[ChatMode]
  ): ZIO[Any, Throwable, Unit] =
    feedback match
      case ShellFeedback.Quit => ZIO.unit
      case _ =>
        loop(registry, terminal, resolver, config, pump, views, aux, chat)

  /** Background tab-bar pump: one fiber reading signals; `Watch(jobId)`
    * subscribes to the hub for that job and re-renders the bar on EVERY
    * delivered envelope (label + status + unread); `Render` re-renders after
    * tab-set/focus changes. All fibers are scoped — interrupted with the
    * shell's scope.
    */
  private final class TabBarPump private (
      hub: EventHub,
      registry: SessionRegistry,
      terminal: ShellTerminal,
      signals: Queue[TabBarPump.Signal]
  ):
    import TabBarPump.Signal

    def watch(jobId: JobId): UIO[Unit] =
      signals.offer(Signal.Watch(jobId)).unit

    def render: UIO[Unit] = signals.offer(Signal.Render).unit

    private def renderBar: UIO[Unit] =
      for
        views <- registry.tabs
        focused <- registry.focused
        _ <- terminal.printAbove(TabBarRenderer.tabBar(views, focused))
      yield ()

    private def start: ZIO[Scope, Nothing, Unit] =
      signals.take
        .flatMap {
          case Signal.Watch(jobId) =>
            hub.subscribe(jobId).flatMap { case (_, stream) =>
              stream.tap(_ => renderBar).runDrain.fork.unit
            }
          case Signal.Render => renderBar
        }
        .forever
        .fork
        .unit

  private object TabBarPump:
    sealed trait Signal

    object Signal:
      final case class Watch(jobId: JobId) extends Signal
      case object Render extends Signal

    def make(
        hub: EventHub,
        registry: SessionRegistry,
        terminal: ShellTerminal
    ): ZIO[Scope, Nothing, TabBarPump] =
      for
        signals <- Queue.unbounded[Signal]
        pump = new TabBarPump(hub, registry, terminal, signals)
        _ <- pump.start
      yield pump
