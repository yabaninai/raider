package raider.repl.engine

import zio.test.*

/** Behavioral fixture for the real dotty-backed engine: every case runs the
  * actual Scala 3.9.0 compiler in-process, so this spec is slow by design
  * (seconds of warm-up on the first eval). No synthetic interpreter here.
  */
object ReplEngineSpec extends ZIOSpecDefault:

  private def valueRepr(r: EvalResult): Option[String] = r match
    case EvalResult.Value(repr) => Some(repr)
    case _                      => None

  private def compileMsg(r: EvalResult): Option[String] = r match
    case EvalResult.CompileError(msg) => Some(msg)
    case _                            => None

  private def runtimeMsg(r: EvalResult): Option[String] = r match
    case EvalResult.RuntimeFailure(msg) => Some(msg)
    case _                              => None

  def spec = suite("ReplEngine")(
    test("val bindings and derived expressions live across eval calls") {
      for
        engine <- ReplEngine.make()
        bound <- engine.eval("val a = 40 + 2")
        derived <- engine.eval("a * 2")
      yield assertTrue(
        valueRepr(bound).exists(_.contains("Int = 42")),
        valueRepr(derived).exists(_.contains("= 84"))
      )
    },
    test("case class definitions survive into later eval calls") {
      for
        engine <- ReplEngine.make()
        declared <- engine.eval("case class Pt(x: Int, y: Int)")
        probed <- engine.eval("Pt(3, 4).x")
      yield assertTrue(
        valueRepr(declared).isDefined,
        valueRepr(probed).exists(_.contains("Int = 3"))
      )
    },
    test("a compile error preserves bindings of the prior state") {
      for
        engine <- ReplEngine.make()
        _ <- engine.eval("val a = 40 + 2")
        failed <- engine.eval("val bad: Int = \"oops\"")
        healthy <- engine.eval("a + 1")
      yield assertTrue(
        compileMsg(failed).isDefined,
        valueRepr(healthy).exists(_.contains("43"))
      )
    },
    test("a runtime failure is a result value and the session continues") {
      for
        engine <- ReplEngine.make()
        boom <- engine.eval("1 / 0")
        healthy <- engine.eval("1 + 1")
      yield assertTrue(
        runtimeMsg(boom).exists(_.contains("ArithmeticException")),
        valueRepr(healthy).exists(_.contains("Int = 2"))
      )
    },
    test("reset clears earlier bindings") {
      for
        engine <- ReplEngine.make()
        _ <- engine.eval("val a = 1")
        _ <- engine.reset()
        gone <- engine.eval("a")
      yield assertTrue(compileMsg(gone).isDefined)
    },
    test("completions find a val binding by bare prefix (COMP-01)") {
      for
        engine <- ReplEngine.make()
        _ <- engine.eval("val hello = 1")
        cs <- engine.completions("hel")
      yield assertTrue(
        cs.contains("hello"),
        cs == cs.sorted,
        cs.length <= 50
      )
    },
    test("completions after a dot return member candidates (COMP-01)") {
      for
        engine <- ReplEngine.make()
        _ <- engine.eval("val xs = List(1)")
        cs <- engine.completions("xs.map")
      yield assertTrue(cs.contains("map"))
    },
    test(
      "a syntactically invalid prefix yields an empty list, no exception (COMP-02)"
    ) {
      for
        engine <- ReplEngine.make()
        _ <- engine.eval("val a = 1")
        cs <- engine.completions("###")
      yield assertTrue(cs.isEmpty)
    },
    test(
      "completions still resolve session bindings after a compile-error eval (COMP-03)"
    ) {
      for
        engine <- ReplEngine.make()
        _ <- engine.eval("val hello = 1")
        failed <- engine.eval("val bad: Int = \"oops\"")
        cs <- engine.completions("hel")
      yield assertTrue(
        compileMsg(failed).isDefined,
        cs.contains("hello")
      )
    }
  ) @@ TestAspect.sequential
  // sequential: every test boots its own real compiler; six parallel warm-ups
  // under cross-module load produced a truncated-output flake once (2026-10-03,

  // RAI-011 slice gate). Sequencing removes the contention, checks unchanged.
