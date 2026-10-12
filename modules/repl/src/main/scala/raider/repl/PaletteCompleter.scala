package raider.repl

import org.jline.reader.{Candidate, Completer, LineReader, ParsedLine}

/** TAB completion over the shell command palette (card RAI-106, P2P-01).
  *
  * The completer turns the palette into the ≤2-press surface: a unique prefix
  * (`:pl` + TAB) completes to the full command name, an ambiguous prefix (`:` +
  * TAB) lists the registered catalog — found via [[ShellPalette.complete]],
  * i.e. derived from the frozen router, never a hand-maintained list.
  *
  * Only a trailing `:`-word completes; any other word (tab labels, Scala input)
  * yields no candidates, so TAB stays inert exactly where the shell had no
  * completion before (additive behavior only). Completion runs synchronously
  * inside `readLine` on the console thread — the same ONE-writer discipline as
  * the rest of the session (spike RAI-056 / RAI-061): JLine serializes the
  * completion redraw and `printAbove` through the reader's lock, and any
  * completer surprise degrades to zero candidates, never a crash.
  */
object PaletteCompleter:

  /** Candidates for the text up to the caret (pure — spec-tested; the PTY
    * fixtures exercise the real TAB path end to end).
    */
  def candidates(textUpToCursor: String): Vector[String] =
    val word = wordAt(textUpToCursor)
    if word.startsWith(":") then ShellPalette.complete(word) else Vector.empty

  /** The trailing whitespace-delimited token up to the caret — the region the
    * default JLine parser reports as the current word, so candidate insertion
    * replaces exactly this token.
    */
  private def wordAt(text: String): String =
    val cut = math.max(text.lastIndexOf(' '), text.lastIndexOf('\t'))
    if cut < 0 then text else text.substring(cut + 1)

end PaletteCompleter

final class PaletteCompleter extends Completer:

  override def complete(
      reader: LineReader,
      line: ParsedLine,
      candidates: java.util.List[Candidate]
  ): Unit =
    try
      PaletteCompleter
        .candidates(line.line().substring(0, line.cursor()))
        .foreach(c => candidates.add(Candidate(c)))
    catch
      case _: Exception =>
        candidates.clear() // defensive: a broken palette must not kill the loop

end PaletteCompleter
