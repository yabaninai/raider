package raider.repl

import raider.repl.engine.ReplEngine
import org.jline.reader.{Candidate, Completer, LineReader, ParsedLine, Parser}
import zio.Unsafe

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** JLine completion wiring (RAI-068): a Completer over the live ReplEngine and
  * a word-boundary Parser that matches Scala member syntax.
  *
  * WHY A CUSTOM PARSER: JLine's default word is whitespace-delimited, so for
  * "List(1).map" the "current word" would be the whole expression and a "map"
  * candidate would clobber the receiver on insertion. The parser below reports
  * the trailing segment after the last delimiter ('.', brackets, whitespace,
  * ...) as the word, so JLine replaces only that segment: "hel" -> "hello",
  * "List(1).map" -> "List(1).mapConserve", "xs." lists every member.
  *
  * THREADING MODEL (RAI-068 doc obligation): JLine invokes the Completer
  * synchronously INSIDE readLine on the console thread; engine.eval runs on
  * that same thread only after readLine returns (the console REPL is a plain
  * synchronous loop — there is no background eval fiber in this slice).
  * Completion and eval are therefore strictly serialized by control flow; no
  * additional locking is required for driver/state safety, and none is added
  * here. The engine-side completion cache keeps its own synchronization as
  * insurance rather than relying on this model.
  *
  * CHAT COMMANDS (RAI-111, RAI-068 follow-up): a trailing `:`-word is a console
  * command (`Commands.handle` intercepts it BEFORE any engine eval in
  * Main.drive), so asking dotty for completions of ":jo" is meaningless. The
  * completer answers such words from the derived chat catalog
  * ([[ShellPalette.completeChat]] over the [[ReplChatCommands]] registry)
  * WITHOUT touching the engine, exactly the ≤2-presses palette surface of the
  * shell side. Labels are the command names WITHOUT the leading ':': the parser
  * below treats ':' as a delimiter, so the word region under the caret excludes
  * it and insertion must complete only the trailing segment (the same
  * member-segment semantics as the engine branch).
  */
object CompletionSupport:

  /** Chat-command candidates for the text up to the caret (pure — spec-tested
    * without an engine). `Some(labels)` = the word is a `:`-command word and
    * the labels (colon-less, insertion-ready) are the whole answer; `None` =
    * not a `:`-word, the engine branch owns the query. A `:`-word with no
    * catalog match yields `Some(empty)` — still no engine query, and no
    * candidates.
    */
  def chatCandidates(textUpToCursor: String): Option[Vector[String]] =
    val cut = math.max(
      textUpToCursor.lastIndexOf(' '),
      textUpToCursor.lastIndexOf('\t')
    )
    val word =
      if cut < 0 then textUpToCursor else textUpToCursor.substring(cut + 1)
    if word.startsWith(":") then
      Some(ShellPalette.completeChat(word).map(_.drop(1)))
    else None

  /** Characters that terminate a completion word. '.' MUST be here: it keeps
    * candidate insertion local to the trailing member segment. Backtick is NOT
    * a delimiter so backtick-quoted identifiers complete as one word.
    */
  private val WordDelimiters = " \t.,;:=()[]{}\"'<>\\"

  /** Tab completion against the engine: the WHOLE text up to the caret is
    * handed to engine.completions (dotty parses it itself and resolves the
    * visible scope of the current session state), each label becomes a plain
    * Candidate. The engine is documented never-failing, but any synchronous
    * throw while building/running the effect (or any JLine-side surprise) is
    * caught and degrades to zero candidates: completion must never crash the
    * REPL (COMP-02 discipline at the UI boundary).
    */
  final class EngineCompleter(engine: ReplEngine) extends Completer:

    override def complete(
        reader: LineReader,
        line: ParsedLine,
        candidates: java.util.List[Candidate]
    ): Unit =
      try
        val prefix = line.line().substring(0, line.cursor())
        CompletionSupport.chatCandidates(prefix) match
          case Some(labels) =>
            labels.foreach(label => candidates.add(Candidate(label)))
          case None =>
            val labels = Unsafe.unsafe { implicit u =>
              zio.Runtime.default.unsafe
                .run(engine.completions(prefix))
                .getOrThrow()
            }
            labels.foreach(label => candidates.add(Candidate(label)))
      catch
        case NonFatal(_) =>
          candidates.clear() // defensive: a broken engine yields no completions

  end EngineCompleter

  /** Parser whose "current word" is the trailing identifier segment (see the
    * object scaladoc). words() keeps full segments for internal JLine
    * consistency (wordIndex points into it); word() is the segment text up to
    * the caret, which is exactly the region candidate insertion replaces.
    */
  object ScalaWordParser extends Parser:

    override def parse(
        line: String,
        cursor: Int,
        target: Parser.ParseContext
    ): ParsedLine =
      val text = line
      val caret = cursor
      val starts = collection.mutable.ArrayBuffer(0)
      val segments = collection.mutable.ArrayBuffer[String]()
      var segStart = 0
      var i = 0
      while i < text.length do
        if WordDelimiters.indexOf(text.charAt(i)) >= 0 then
          segments += text.substring(segStart, i)
          segStart = i + 1
          starts += segStart
        i += 1
      segments += text.substring(segStart)

      // the segment containing the caret: the last one starting at/before it
      var idx = starts.length - 1
      while idx > 0 && starts(idx) > caret do idx -= 1
      val wordStart = starts(idx)

      new ParsedLine:
        def line(): String = text
        def cursor(): Int = caret
        def wordIndex(): Int = idx
        def wordCursor(): Int = caret - wordStart
        def word(): String = text.substring(wordStart, caret)
        def words(): java.util.List[String] = segments.toList.asJava

  end ScalaWordParser

end CompletionSupport
