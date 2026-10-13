package raider.repl

import java.nio.charset.StandardCharsets

/** RAI-132 / STR-03 — test-scope PTY launcher for the PRODUCT chat REPL host:
  * `raider.repl.Main` (the legacy no-tabs cycle) driven under a pseudo-terminal
  * by SteerCommandsPtySpec. Test scope only — the launcher CALLS the product
  * main (same shape as ReplChatPtyApp; the PTY-slave winsize fix happens HERE,
  * before the product host builds its terminal, because a `script`-run PTY has
  * no winsize and degrades JLine rendering).
  */
object SteerPtyApp:

  private def setPtySize(rows: Int, cols: Int): Unit =
    try
      new ProcessBuilder("stty", "rows", rows.toString, "cols", cols.toString)
        .inheritIO()
        .start()
        .waitFor()
    catch case _: Exception => ()

  def main(args: Array[String]): Unit =
    setPtySize(rows = 40, cols = 120)
    // The PRODUCT chat REPL entrypoint: mock provider by default (no
    // RAIDER_* env is set by the driver) — scripted backend, no network,
    // no --tabs (the legacy cycle is the surface under regression).
    raider.repl.Main.main(Array.empty)
    val note = "rai132: product main returned without exiting"
    System.err.write(note.getBytes(StandardCharsets.UTF_8))
    System.err.flush()
    Runtime.getRuntime.exit(3)

end SteerPtyApp
