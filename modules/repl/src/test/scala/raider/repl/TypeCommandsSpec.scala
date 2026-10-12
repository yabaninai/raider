package raider.repl

import raider.repl.engine.{EvalResult, ReplEngine}
import zio.test._

/** RAI-124 / TYPE — the `:type <expression>` command of the REPL cycle,
  * in-process over the REAL dotty 3.9.0 engine (no mock interpreter): every
  * case drives the same `engine.eval` path the console loop uses, with the
  * exact sources the console submits (Commands.typeQuerySource for a buffered
  * multiline query).
  *
  *   - TYPE-01: pinned exact types for plain expressions, val/def/case-class
  *     bindings (visible across evals), lambdas and tuples; the expression is
  *     NEVER RUN (a side-effecting block answers with its type and the marker
  *     never appears); session bindings and resN numbering are identical
  *     before/after a query.
  *   - TYPE-02: a nonexistent identifier and a type mismatch are the HONEST
  *     compiler diagnostics (no fabricated Any/Unit); the REPL stays alive and
  *     the next command runs; the empty query is the driver's own usage line.
  *   - Multiline: the buffered query flattens into the single-line command
  *     source the dotty command grammar accepts; statement blocks need explicit
  *     `;` (honest syntax error otherwise — the documented residue).
  *   - Freshness: helpText/registry/palette carry :type (the full equality net
  *     is ReplChatPaletteSpec; here only the RAI-124 entries are pinned).
  */
object TypeCommandsSpec extends ZIOSpecDefault:

  private def valueRepr(r: EvalResult): Option[String] = r match
    case EvalResult.Value(repr) => Some(repr)
    case _                      => None

  private def compileMsg(r: EvalResult): Option[String] = r match
    case EvalResult.CompileError(msg) => Some(msg)
    case _                            => None

  /** The exact console submission path for a user-typed query line. */
  private def query(engine: ReplEngine, source: String) =
    engine.eval(Commands.typeQuerySource(source))

  def spec = suite("TypeCommandsSpec (RAI-124: :type — type without running)")(
    suite("TYPE-01: exact types, no execution, bindings untouched")(
      test("plain expressions print their exact type (pinned)") {
        for
          engine <- ReplEngine.make()
          list <- query(engine, ":type List(1, 2)")
          str <- query(engine, ":type (1 * 54).toString")
          lambda <- query(engine, ":type (i: Int) => i.toString")
          tuple <- query(engine, ":type (1, \"a\", true)")
        yield assertTrue(
          valueRepr(list).exists(_.trim == "List[Int]"),
          valueRepr(str).exists(_.trim == "String"),
          valueRepr(lambda).exists(_.trim == "Int => String"),
          valueRepr(tuple).exists(_.trim == "(Int, String, Boolean)")
        )
      },
      test("val/def/case class bindings are visible to the query") {
        for
          engine <- ReplEngine.make()
          _ <- engine.eval("val x = 42")
          x <- query(engine, ":type x")
          _ <- engine.eval("case class Foo(n: Int)")
          ctor <- query(engine, ":type Foo(1)")
          field <- query(engine, ":type Foo(1).n")
          _ <- engine.eval("def f(a: Int) = a * 2")
          call <- query(engine, ":type f(2)")
        yield assertTrue(
          valueRepr(x).exists(_.trim == "Int"),
          valueRepr(ctor).exists(_.trim == "Foo"),
          valueRepr(field).exists(_.trim == "Int"),
          valueRepr(call).exists(_.trim == "Int")
        )
      },
      test("the expression is never run: side effects stay absent") {
        for
          engine <- ReplEngine.make()
          result <- query(
            engine,
            ":type { println(\"RAI124-SIDE-EFFECT\"); 42 }"
          )
        yield assertTrue(
          valueRepr(result).exists(_.trim == "Int"),
          !valueRepr(result).exists(_.contains("RAI124-SIDE-EFFECT"))
        )
      },
      test("bindings and resN numbering are identical before/after a query") {
        for
          engine <- ReplEngine.make()
          _ <- engine.eval("val keep = 7")
          res0 <- engine.eval("1 + 1") // val res0: Int = 2
          _ <- query(engine, ":type List(1)") // must not touch the session
          kept <- engine.eval("keep") // the binding still resolves (res1)
          numbering <- engine.eval("2 + 2") // next slot with NO gap (res2)
        yield assertTrue(
          valueRepr(res0).exists(_.contains("val res0: Int = 2")),
          valueRepr(kept).exists(_.contains("val res1: Int = 7")),
          valueRepr(numbering).exists(_.contains("val res2: Int = 4"))
        )
      },
      test(
        "a buffered multiline query flattens to the working command source"
      ) {
        val flattened = Commands.typeQuerySource(":type List(1,\n  2)")
        for
          engine <- ReplEngine.make()
          multi <- engine.eval(flattened)
          block <- query(engine, ":type {\n  val a = 1; a + 1\n}")
        yield assertTrue(
          flattened == ":type List(1,   2)",
          valueRepr(multi).exists(_.trim == "List[Int]"),
          valueRepr(block).exists(_.trim == "Int")
        )
      }
    ),
    suite("TYPE-02: honest errors, alive REPL, usage")(
      test("a nonexistent identifier is the compiler's own Not Found error") {
        for
          engine <- ReplEngine.make()
          missing <- query(engine, ":type notDefinedId")
          alive <- query(
            engine,
            ":type List(1, 2)"
          ) // the very next query works
        yield assertTrue(
          compileMsg(missing).exists(m =>
            m.contains("Not Found") && m.contains("Not found: notDefinedId")
          ),
          valueRepr(alive).exists(_.trim == "List[Int]")
        )
      },
      test(
        "a type mismatch is the honest diagnostic (no fabricated Any/Unit)"
      ) {
        for
          engine <- ReplEngine.make()
          mismatch <- query(engine, ":type (42: String)")
          block <- query(engine, ":type { val q: String = 42; q }")
        yield
          val found = compileMsg(mismatch)
          val blockFound = compileMsg(block)
          assertTrue(found.exists(_.contains("Type Mismatch"))) &&
          assertTrue(found.exists(_.contains("Found:"))) &&
          assertTrue(found.exists(_.contains("Required: String"))) &&
          assertTrue(valueRepr(mismatch).isEmpty) && // never a fabricated Any
          assertTrue(blockFound.exists(_.contains("Type Mismatch")))
      },
      test("the empty query is the driver's usage line, no crash") {
        for
          engine <- ReplEngine.make()
          usage <- query(engine, ":type")
          alive <- query(engine, ":type List(1)")
        yield assertTrue(
          valueRepr(usage).exists(_.trim == ":type <expression>"),
          valueRepr(alive).exists(_.trim == "List[Int]")
        )
      }
    ),
    suite("input plumbing (pure)")(
      test("isTypeQuery: exact forms only, near-misses stay ordinary input") {
        assertTrue(
          Commands.isTypeQuery(":type") &&
            Commands.isTypeQuery(":type List(1)") &&
            !Commands.isTypeQuery(":typeFoo List(1)") &&
            !Commands.isTypeQuery(":typ") &&
            !Commands.isTypeQuery("type x")
        )
      },
      test("typeQuerySource flattens newlines and trims command edges") {
        assertTrue(
          Commands.typeQuerySource(":type x\n") == ":type x"
        ) &&
        assertTrue(
          Commands.typeQuerySource("  :type (1,\n    2)  ") ==
            ":type (1,     2)"
        ) &&
        assertTrue(
          Commands.typeQuerySource(":type {\n  val a = 1; a + 1\n}") ==
            ":type {   val a = 1; a + 1 }"
        )
      }
    ),
    suite("freshness pins (RAI-111 registry discipline)")(
      test("helpText, the registry and the palette carry :type") {
        val helpLines = Commands.helpText.linesIterator.toVector
        assertTrue(
          helpLines.exists(l =>
            l.startsWith("  :type <expression>") && l.contains(
              "compiled but not run"
            )
          )
        ) &&
        assertTrue(
          ReplChatCommands.names.contains(":type")
        ) &&
        assertTrue(ShellPalette.chatCommands.contains(":type"))
      },
      test(
        "the stale out-of-slice prose is gone from helpText"
      ) {
        assertTrue(
          !Commands.helpText.contains("Not in this slice: :type")
        )
      }
    )
  )

end TypeCommandsSpec
