package raider.repl

import raider.repl.engine.ReplEngine
import org.jline.reader.{Candidate, LineReader, LineReaderBuilder, Parser}
import org.jline.terminal.TerminalBuilder
import zio.ZIO
import zio.test.*

/** RAI-068 console-side wiring fixtures: the Scala word parser keeps candidate
  * insertion local to the trailing identifier segment, the EngineCompleter
  * surfaces real dotty labels as plain Candidates, and any engine crash
  * degrades to zero candidates instead of killing the REPL (COMP-02 discipline
  * at the UI boundary).
  */
object CompletionWiringSpec extends ZIOSpecDefault:

  private def reader(): LineReader =
    LineReaderBuilder
      .builder()
      .terminal(TerminalBuilder.builder().dumb(true).build())
      .build()

  private def parse(line: String, cursor: Int) =
    CompletionSupport.ScalaWordParser.parse(
      line,
      cursor,
      Parser.ParseContext.COMPLETE
    )

  private def completeVia(
      engine: ReplEngine,
      line: String,
      cursor: Int
  ): java.util.List[Candidate] =
    val out = new java.util.ArrayList[Candidate]()
    CompletionSupport
      .EngineCompleter(engine)
      .complete(reader(), parse(line, cursor), out)
    out

  private def values(cs: java.util.List[Candidate]): List[String] =
    (0 until cs.size()).toList.map(i => cs.get(i).value())

  def spec = suite("CompletionWiring")(
    test("parser: word is the trailing segment after the last delimiter") {
      val dotted = parse("List(1).map", 11)
      val bare = parse("hel", 3)
      val afterDot = parse("xs.", 3)
      val mid = parse("val xs = List(1).map", 20)
      assertTrue(
        dotted.word() == "map",
        dotted.wordIndex() == 3,
        dotted.wordCursor() == 3,
        bare.word() == "hel",
        bare.wordIndex() == 0,
        afterDot.word() == "",
        afterDot.wordCursor() == 0,
        mid.word() == "map"
      )
    },
    test(
      "completer surfaces real engine labels for prefix and member queries"
    ) {
      for
        engine <- ReplEngine.make()
        _ <- engine.eval("val hello = 1")
        _ <- engine.eval("val xs = List(1)")
        prefix <- ZIO.succeed(completeVia(engine, "hel", 3))
        member <- ZIO.succeed(completeVia(engine, "xs.map", 6))
      yield assertTrue(
        values(prefix).contains("hello"),
        values(member).contains("map")
      )
    },
    test("an engine that dies during completion leaves candidates empty") {
      val dying = new ReplEngine:
        def eval(source: String) = zio.ZIO.die(new RuntimeException("boom"))
        def completions(line: String) =
          zio.ZIO.die(new RuntimeException("boom"))
        def reset() = zio.ZIO.unit
      val out = completeVia(dying, "hel", 3)
      assertTrue(out.isEmpty)
    },
    test(
      "an engine that throws while building the effect leaves candidates empty"
    ) {
      val throwing = new ReplEngine:
        def eval(source: String) = zio.ZIO.die(new RuntimeException("boom"))
        def completions(line: String): zio.UIO[List[String]] =
          throw new IllegalStateException("boom")
        def reset() = zio.ZIO.unit
      val out = completeVia(throwing, "hel", 3)
      assertTrue(out.isEmpty)
    }
  ) @@ TestAspect.sequential

end CompletionWiringSpec
