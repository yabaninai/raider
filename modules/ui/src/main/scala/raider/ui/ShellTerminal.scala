package raider.ui

import org.jline.reader.{
  EndOfFileException,
  LineReader,
  LineReaderBuilder,
  UserInterruptException
}
import org.jline.terminal.{Terminal, TerminalBuilder}
import zio.{Scope, Task, UIO, ZIO}

/** The ONLY input/output path of the session (card RAI-061: ONE writer). JLine
  * is isolated behind this interface — everything else ([[CommandRouter]],
  * [[TabBarRenderer]], [[ShellFeedback]], [[ShellExecutor]], [[TabShell]]) is
  * terminal-free and tested directly.
  */
trait ShellTerminal:

  /** Print complete lines ABOVE the active input row (JLine printAbove); the
    * in-progress draft is preserved (proved by spike RAI-056).
    */
  def printAbove(lines: Vector[String]): UIO[Unit]

  /** Read one input line. `None` = EOF (same as `:quit`).
    *
    * An idle Ctrl+C clears the CURRENT line only: JLine raises
    * UserInterruptException and this implementation maps it to an EMPTY line —
    * the loop continues, tabs and drafts untouched.
    */
  def readLine(prompt: String): Task[Option[String]]

  /** Release the terminal. */
  def close: UIO[Unit]

  /** Current terminal geometry for frame renderers; non-terminal test doubles
    * may keep the conservative default.
    */
  def size: (Int, Int) = (100, 30)

object ShellTerminal:

  /** Real JLine terminal (ADR-016 inline mode, spike RAI-056 setup). Scoped:
    * the terminal is closed when the scope exits. Never used by tests — they
    * script this interface instead.
    */
  def jline: ZIO[Scope, Throwable, ShellTerminal] =
    ZIO
      .acquireRelease(
        ZIO.attempt {
          val terminal = TerminalBuilder.terminal()
          val reader = LineReaderBuilder.builder().terminal(terminal).build()
          new JLine(terminal, reader)
        }
      )(t => t.close)

  private final class JLine(terminal: Terminal, reader: LineReader)
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
