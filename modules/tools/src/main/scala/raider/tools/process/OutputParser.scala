package raider.tools.process

import zio.json.EncoderOps

/** Structured parsing of common command outputs (nightly Phase 2.6): sbt
  * compile diagnostics and sbt test summaries become typed data, embedded by
  * proc_run as a `parsed` field so the model can react to compilation errors
  * programmatically instead of re-reading raw text.
  *
  * Honest parsing: output that matches nothing stays `Unknown` — the parser
  * never fabricates a result type.
  */
object OutputParser:

  sealed trait Parsed:
    def typeName: String
    def success: Boolean

  final case class CompileError(
      file: String,
      line: Int,
      col: Option[Int],
      message: String
  )

  final case class SbtCompile(errors: List[CompileError], success: Boolean)
      extends Parsed:
    val typeName: String = "sbt_compile"

  final case class SbtTest(
      passed: Int,
      failed: Int,
      ignored: Int,
      success: Boolean
  ) extends Parsed:
    val typeName: String = "sbt_test"

  case object Unknown extends Parsed:
    val typeName: String = "unknown"
    val success: Boolean = false

  // [error] -- Error: /path/File.scala:10:5 (message may follow on the line)
  private val Diagnostic =
    raw"^\[error\] -- [^:]+: (.+?):(\d+)(?::(\d+))?\s*(.*)".r

  // Scala 2-style: [error] /path/File.scala:10:5 message
  private val DiagnosticS2 =
    raw"^\[error\] (.+?):(\d+)(?::(\d+))?:\s*(.*)".r

  private val TestSummary =
    raw"(\d+) tests passed\. (\d+) tests failed\. (\d+) tests ignored\.".r

  private val LooksLikeSbt = raw"^\[(info|warn|error)\]".r

  def parse(stdout: String, stderr: String, exitCode: Option[Int]): Parsed =
    val text = stdout + "\n" + stderr
    val allLines = text.linesIterator.toList
    val isSbt = allLines.exists(l => LooksLikeSbt.findPrefixOf(l).isDefined)

    if !isSbt then Unknown
    else
      val testMatches = TestSummary.findAllIn(text).toList
      testMatches match
        case summary :: _ =>
          summary match
            case TestSummary(p, f, i) =>
              SbtTest(p.toInt, f.toInt, i.toInt, success = f.toInt == 0)
            case _ => Unknown
        case Nil =>
          val errors = allLines.collect:
            case Diagnostic(file, line, col, msg) =>
              CompileError(file, line.toInt, Option(col).map(_.toInt), msg)
            case DiagnosticS2(file, line, col, msg) =>
              CompileError(file, line.toInt, Option(col).map(_.toInt), msg)
          val hasErrorTag = allLines.exists(_.startsWith("[error]"))
          val success = exitCode.contains(0) && !hasErrorTag
          SbtCompile(errors, success)

  /** Single-line JSON wire form for the `parsed` field. */
  def encode(p: Parsed): String =
    p match
      case SbtCompile(errors, success) =>
        val es = errors
          .map: e =>
            s"""{"file":${e.file.toJson},"line":${e.line},""" +
              s""""col":${e.col.map(_.toString).getOrElse("null").toJson},""" +
              s""""message":${e.message.toJson}}"""
          .mkString("[", ",", "]")
        s"""{"type":"sbt_compile","success":$success,"errors":$es}"""
      case SbtTest(passed, failed, ignored, success) =>
        s"""{"type":"sbt_test","success":$success,"passed":$passed,""" +
          s""""failed":$failed,"ignored":$ignored}"""
      case Unknown => """{"type":"unknown"}"""
