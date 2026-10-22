package raider.cli.run

import raider.core.RaiderError
import raider.core.result.{CheckOutcome, CheckResult, ExitCodes}

/** Deterministic CI report decisions. Model output is deliberately absent from
  * this API: only executed check evidence and frozen policy can determine a
  * task verdict.
  */
private[run] object CiReport:

  enum TaskVerdict:
    case Passed, Advisory, Blocking

  enum Failure:
    case BlockingCheck
    case PolicyDenial
    case Deadline
    case Uncertainty
    case WriteFailure
    case CancellationSignal(code: Int)
    case Preflight(code: Int)
    case Success

  /** `advisoryChecks` is the frozen policy's explicit set of check IDs. An
    * unknown or omitted check remains blocking by default.
    */
  def taskVerdict(
      checks: List[CheckResult],
      advisoryChecks: Set[String]
  ): TaskVerdict =
    val failed = checks.filter(c =>
      c.outcome == CheckOutcome.Failed || c.outcome == CheckOutcome.Errored
    )
    if failed.exists(c => !advisoryChecks.contains(c.id)) then
      TaskVerdict.Blocking
    else if failed.nonEmpty then TaskVerdict.Advisory
    else TaskVerdict.Passed

  /** ci-runtime §8 precedence. A signal is retained; otherwise a required
    * artifact failure outranks uncertain execution, which outranks the root
    * stop reason.
    */
  def exitCode(failures: Set[Failure]): Int =
    failures
      .collectFirst { case Failure.CancellationSignal(code) => code }
      .orElse(
        Option.when(failures.contains(Failure.WriteFailure))(
          ExitCodes.ArtifactWriteFailure
        )
      )
      .orElse(
        Option.when(failures.contains(Failure.Uncertainty))(ExitCodes.Uncertain)
      )
      .orElse(
        Option.when(failures.contains(Failure.Deadline))(
          ExitCodes.DeadlineExceeded
        )
      )
      .orElse(
        Option.when(failures.contains(Failure.PolicyDenial))(
          ExitCodes.PolicyDenied
        )
      )
      .orElse(failures.collectFirst { case Failure.Preflight(code) => code })
      .orElse(
        Option.when(failures.contains(Failure.BlockingCheck))(
          ExitCodes.TaskRejected
        )
      )
      .getOrElse(ExitCodes.Success)

  def exitCode(error: RaiderError): Int = HeadlessRunner.exitCodeFor(error)

  def xmlEscape(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .map {
        case c if c < ' ' && c != '\n' && c != '\r' && c != '\t' => '\uFFFD'
        case c                                                   => c
      }

  /** Serialize executed check evidence as JUnit XML. Untrusted check IDs and
    * details are escaped at the XML boundary, independently of Markdown.
    */
  def junit(checks: List[CheckResult]): String =
    def testCase(c: CheckResult): String =
      val inner = c.outcome match
        case CheckOutcome.Passed => ""
        case CheckOutcome.Failed =>
          s"""<failure message="${xmlEscape(
              c.detail.take(120)
            )}">${xmlEscape(c.detail)}</failure>"""
        case CheckOutcome.Errored =>
          s"""<error message="${xmlEscape(
              c.detail.take(120)
            )}">${xmlEscape(c.detail)}</error>"""
        case CheckOutcome.Skipped => "<skipped/>"
      s"""    <testcase name="${xmlEscape(
          c.id
        )}" classname="raider.run.checks">$inner</testcase>"""

    val failures = checks.count(_.outcome == CheckOutcome.Failed)
    val errored = checks.count(_.outcome == CheckOutcome.Errored)
    val skipped = checks.count(_.outcome == CheckOutcome.Skipped)
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<testsuite name="raider run" tests="${checks.size}" failures="$failures" errors="$errored" skipped="$skipped">
       |${checks.map(testCase).mkString("\n")}
       |</testsuite>
       |""".stripMargin

  /** Make untrusted report text inert in GitHub workflow-command processing. */
  def neutralizeWorkflowCommands(value: String): String =
    value
      .replace("%", "%25")
      .replace("\r", "%0D")
      .replace("\n", "%0A")
      .replace("::", "%3A%3A")

  /** Report paths are always relative POSIX paths; reject traversal and
    * platform-specific absolute paths before they enter a manifest.
    */
  def safeRelativePath(value: String): Boolean =
    val normalized = value.replace('\\', '/')
    normalized.nonEmpty &&
    !normalized.startsWith("/") &&
    !normalized
      .split("/", -1)
      .exists(part => part.isEmpty || part == "." || part == "..") &&
    !normalized.matches("^[A-Za-z]:.*")
