package raider.tools.process

import zio.test.*
import zio.ZIO

/** proc_run output parsing contract (nightly Phase 2.6): common tool outputs
  * become structured data so the model can see typed compilation/test failures
  * instead of raw text dumps.
  */
object OutputParserSpec extends ZIOSpecDefault:

  private val SbtCompileFail =
    """[info] compiling 1 Scala source ...
      |[error] -- Error: /ws/src/main/scala/demo/Calc.scala:10:5 
      |[error] 10 |val x: Int = "s"
      |[error]    |            ^^^
      |[error]    |        Found:    ("s" : String)
      |[error] one error found
      |[error] (Compile / compileIncremental) Compilation failed""".stripMargin

  private val SbtTestPass =
    """raider.tools.CodingToolsetSpec
      |17 tests passed. 0 tests failed. 0 tests ignored.
      |[info] Completed tests""".stripMargin

  private val SbtTestFail =
    """raider.tools.Spec
      |9 tests passed. 2 tests failed. 1 tests ignored.
      |[error] Failed tests:""".stripMargin

  override def spec: Spec[Any, Any] =
    suite("OutputParser")(
      test("sbt compile errors are extracted with file/line/col") {
        val parsed = OutputParser.parse(SbtCompileFail, "", Some(1))
        val errors = parsed match
          case p: OutputParser.SbtCompile => p.errors
          case _                          => Nil
        assertTrue(
          !parsed.success,
          errors.size == 1,
          errors.headOption.exists(
            _.file == "/ws/src/main/scala/demo/Calc.scala"
          ),
          errors.headOption.exists(_.line == 10),
          errors.headOption.exists(_.col.contains(5))
        )
      },
      test("clean compile output with exit 0 is a success") {
        val parsed = OutputParser.parse("[info] done compiling", "", Some(0))
        val parsedType = parsed match
          case p: OutputParser.SbtCompile => "sbt_compile"
          case other                      => other.typeName // any other shape
        assertTrue(
          parsedType == "sbt_compile",
          parsed.success,
          parsed match
            case c: OutputParser.SbtCompile => c.errors.isEmpty
            case _                          => false
        )
      },
      test("sbt test summary line is parsed") {
        val parsed = OutputParser.parse(SbtTestPass, "", Some(0))
        assertTrue(
          parsed.typeName == "sbt_test",
          parsed.success,
          parsed == OutputParser.SbtTest(17, 0, 0, success = true)
        )
      },
      test("failing test summary maps success=false") {
        val parsed = OutputParser.parse(SbtTestFail, "", Some(1))
        assertTrue(
          parsed == OutputParser.SbtTest(9, 2, 1, success = false),
          !parsed.success
        )
      },
      test("stderr is scanned too") {
        val parsed =
          OutputParser
            .parse("", "[error] -- Error: /ws/A.scala:3:1 boom", Some(1))
        val errors = parsed match
          case p: OutputParser.SbtCompile => p.errors
          case _                          => Nil
        assertTrue(
          !parsed.success,
          errors.headOption.exists(_.file == "/ws/A.scala")
        )
      },
      test("unrecognized output stays Unknown (never fabricated)") {
        val out = "some random program output\nmore lines"
        assertTrue(OutputParser.parse(out, "", Some(0)) == OutputParser.Unknown)
      },
      test("encode produces the structured JSON wire form") {
        val json = OutputParser.encode(
          OutputParser.SbtCompile(
            List(OutputParser.CompileError("/ws/A.scala", 3, Some(1), "boom")),
            success = false
          )
        )
        assertTrue(
          json.contains(""""type":"sbt_compile""""),
          json.contains(""""success":false"""),
          json.contains("/ws/A.scala")
        )
      }
    )
