package raider.repl

import java.nio.charset.StandardCharsets

/** RAI-111 / PAL2 — test-scope PTY launcher for the PRODUCT chat REPL host:
  * `raider.repl.Main` (the real W10 cycle) driven under a pseudo-terminal by
  * ReplChatCyclePtySpec. Test scope only, no product code imports test classes
  * — the launcher CALLS the product main.
  *
  * Why this exists (RAI-102 harness rationale, same class): a `script`-run PTY
  * has NO winsize set (0x0), which degrades JLine rendering (redraw junk — the
  * prompt becomes unmatchable fragments). Main is a frozen file for this lane,
  * so the size fix happens HERE, on the PTY slave (this process's own stdin),
  * BEFORE the product host builds its terminal. Failures are ignored: a non-tty
  * run keeps JLine's defaults, and checks then fail on content — never a fake
  * pass.
  */
object ReplChatPtyApp:

  /** Test-owned terminal geometry (probe parity: the PTY driver owns the size).
    */
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
    // RAIDER_* env is set by the driver) — scripted backend, no network.
    raider.repl.Main.main(Array.empty)
    // Main.main exits the JVM itself (deterministic exit); reaching here is
    // only possible on an unexpected product-path change — keep an honest
    // non-zero marker for the driver (utf-8 note kept for transcripts).
    val note = "rai111: product main returned without exiting"
    System.err.write(note.getBytes(StandardCharsets.UTF_8))
    System.err.flush()
    Runtime.getRuntime.exit(3)

end ReplChatPtyApp
