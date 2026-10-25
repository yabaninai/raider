package raider.repl

import org.jline.reader.{
  EndOfFileException,
  LineReader,
  LineReaderBuilder,
  UserInterruptException
}
import org.jline.terminal.{Terminal, TerminalBuilder}
import raider.ui.ShellTerminal
import zio.{Scope, Task, UIO, ZIO}

/** The product shell terminal with the RAI-106 palette surface: the SAME
  * ONE-writer discipline as the frozen `ShellTerminal.jline` (RAI-061/RAI-102),
  * plus two additive capabilities:
  *
  *   - the LineReader carries [[PaletteCompleter]] — TAB prefix-completes over
  *     the registered command catalog (P2P-01);
  *   - `printAbove` is wrapped by [[withPaletteHints]] — an unknown-command
  *     hint block gains the nearest-command line (P2P-02) inside the SAME
  *     atomic printAbove block, so the ONE-writer invariant and the draft
  *     preservation are untouched.
  *
  * The JLine mapping below mirrors the frozen `ShellTerminal.JLine` verbatim
  * (printAbove per line + orDie; EOF → None; idle Ctrl+C → empty line): the
  * frozen RAI-102 PTY acceptance keeps its exact semantics.
  */
object PaletteShellTerminal:

  /** Exact rendered prefix of the frozen unknown-command hint
    * (`ShellFeedback.render(Hint(UnknownCommand(raw)))`): the decorator's
    * anchor. Pinned by specs — a frozen-render change fails them loudly here,
    * at the one coupling point, instead of silently dropping suggestions.
    */
  val UnknownCommandPrefix: String = "hint: unknown command: "

  /** Decorator over ANY ShellTerminal (unit-testable with scripted terminals):
    * when a printed block starts with the frozen unknown-command hint, the
    * nearest registered command (computed from the SAME raw token the router
    * rejected) is inserted as the second line of the SAME block. Blocks without
    * the anchor — and unknown commands with no near match — pass through
    * unchanged.
    */
  def withPaletteHints(underlying: ShellTerminal): ShellTerminal =
    new ShellTerminal:
      def printAbove(lines: Vector[String]): UIO[Unit] =
        lines.headOption.filter(_.startsWith(UnknownCommandPrefix)) match
          case None => underlying.printAbove(lines)
          case Some(first) =>
            val raw = first.substring(UnknownCommandPrefix.length)
            val decorated = ShellPalette.suggestionLine(raw) match
              case None => lines
              case Some(suggestion) =>
                lines.take(1) ++ Vector(suggestion) ++ lines.drop(1)
            underlying.printAbove(decorated)

      def readLine(prompt: String): Task[Option[String]] =
        underlying.readLine(prompt)

      def close: UIO[Unit] = underlying.close

      override def size: (Int, Int) = underlying.size

  /** Palette-enabled real JLine terminal, scoped like the frozen factory. */
  def jline: ZIO[Scope, Throwable, ShellTerminal] =
    ZIO
      .acquireRelease(
        ZIO.attempt {
          val terminal = TerminalBuilder.terminal()
          val reader = LineReaderBuilder
            .builder()
            .terminal(terminal)
            .completer(new PaletteCompleter)
            .build()
          RawJLine(terminal, reader)
        }
      )(t => t.close)
      .map(withPaletteHints(_))

  /** Mirrors frozen `ShellTerminal.JLine` (RAI-061) with the completer added at
    * construction; private — the decorated surface above is the product.
    */
  private final case class RawJLine(terminal: Terminal, reader: LineReader)
      extends ShellTerminal:

    def printAbove(lines: Vector[String]): UIO[Unit] =
      // A print failure means the terminal is gone (shutdown): a defect is the
      // honest outcome — never a silent drop, never a fake success.
      ZIO.attempt(lines.foreach(reader.printAbove)).orDie

    def readLine(prompt: String): Task[Option[String]] =
      ZIO.attempt {
        try Some(reader.readLine(prompt))
        catch
          case _: EndOfFileException     => None
          case _: UserInterruptException => Some("")
      }

    def close: UIO[Unit] = ZIO.attempt(terminal.close()).orDie

    override def size: (Int, Int) = (terminal.getWidth, terminal.getHeight)

end PaletteShellTerminal
