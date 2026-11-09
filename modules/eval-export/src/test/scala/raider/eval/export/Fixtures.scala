package raider.evalexport

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}

import raider.evalexport.model.*

/** Deterministic fixtures for the eval-export tests (RAI-040).
  *
  * Every evidence digest is computed at fixture-build time over a pinned
  * descriptive string, so the fixtures are stable without binary blobs and the
  * exported goldens stay reproducible. The goldens themselves live under
  * `contracts/eval-export/expected/<name>/` and are (re)pinned only via
  * `run-tests.sh --update-goldens` (a deliberate, reviewable act).
  *
  * Fixtures:
  *   - [[Normal]] — a complete two-episode run with verdicts, three model calls
  *     (one with a null reserved price), four tool calls covering
  *     succeeded/failed/denied results, raw args, explicitly pinned
  *     redacted-safe args, env NAME references, and one artifact hash.
  *   - [[Cancelled]] — a partial run: one completed episode with a verdict, one
  *     cancelled episode whose declared verifier produced nothing (pending),
  *     and one interrupted episode without a declared verifier (absent). No
  *     finishedAtMs. This is the base of the late-usage golden.
  *   - [[UnknownPrice]] — a run whose usage prices are honestly unknown
  *     (explicit nulls plus `unknown=true`).
  *   - [[Late1]] / [[Late2]] — two late-usage corrections applied to
  *     [[Cancelled]]'s `call-1` through [[Reconciliation.append]].
  */
object Fixtures:

  def sha(s: String): String = Digests.sha256HexUtf8(s)

  val generator: model.Generator =
    model.Generator("raider", "0.4.0-dev", "3.9.0", "21.0.5")

  // -- normal run ---------------------------------------------------------------

  object Normal:

    val input: model.EvalRunInput = model.EvalRunInput(
      run = model.RunInfo(
        runId = "run-20261106-normal",
        generator = generator,
        model = "mock-coder",
        startedAtMs = 1000L,
        finishedAtMs = Some(8000L)
      ),
      jobs = Vector(
        model.JobRecord("job-root", None, "root agent loop"),
        model
          .JobRecord("job-child", Some("job-root"), "subtask: refactor module")
      ),
      attempts = Vector(
        model.AttemptRecord("att-1", "job-root", 1100L, Some(3000L)),
        model.AttemptRecord("att-2", "job-child", 3100L, Some(5000L))
      ),
      episodes = Vector(
        model.EpisodeInput(
          episodeId = "ep-1",
          attemptId = "att-1",
          completion =
            model.Completion.Completed(model.ExecutionStatus.Succeeded, 3000L),
          verifierExpected = true,
          verifierId = Some("verify.sh"),
          safety = Some(
            model.OutcomeEvidence(
              model.OutcomeStatus.Pass,
              None,
              Some(sha("ep-1 safety scan report: no violations"))
            )
          ),
          protocol = None
        ),
        model.EpisodeInput(
          episodeId = "ep-2",
          attemptId = "att-2",
          completion =
            model.Completion.Completed(model.ExecutionStatus.Failed, 5000L),
          verifierExpected = true,
          verifierId = Some("verify.sh"),
          safety = None,
          protocol = None
        )
      ),
      calls = Vector(
        model.ModelCallInput(
          "call-1",
          "att-1",
          1,
          "mock-coder",
          1200L,
          model.Usage(Some(15L), Some(1800L), false)
        ),
        model.ModelCallInput(
          "call-2",
          "att-1",
          2,
          "mock-coder",
          2400L,
          model.Usage(Some(15L), Some(2100L), false)
        ),
        model.ModelCallInput(
          "call-3",
          "att-2",
          1,
          "mock-coder",
          3200L,
          model.Usage(None, Some(900L), false)
        )
      ),
      tools = Vector(
        model.ToolCallInput(
          toolCallId = "tc-1",
          attemptId = "att-1",
          tool = "fs_read",
          callId = Some("call-1"),
          atMs = 1300L,
          result = model.ToolResult.Succeeded,
          argsJsonText = Some("""{"path":"src/main/scala/App.scala"}"""),
          argsRedactedSafe = None,
          envRefs = Vector("CI_RUN_ID"),
          artifacts = Vector.empty
        ),
        model.ToolCallInput(
          toolCallId = "tc-2",
          attemptId = "att-1",
          tool = "fs_edit",
          callId = None,
          atMs = 2600L,
          result = model.ToolResult.Failed("file not found"),
          argsJsonText = Some("""{"path":"missing.scala"}"""),
          argsRedactedSafe = None,
          envRefs = Vector.empty,
          artifacts = Vector.empty
        ),
        model.ToolCallInput(
          toolCallId = "tc-3",
          attemptId = "att-2",
          tool = "proc_run",
          callId = None,
          atMs = 3300L,
          result = model.ToolResult.Denied("policy: network egress denied"),
          argsJsonText = Some("""{"cmd":["make","check"]}"""),
          argsRedactedSafe = None,
          envRefs = Vector("CI_JOB_TOKEN"),
          artifacts = Vector.empty
        ),
        model.ToolCallInput(
          toolCallId = "tc-4",
          attemptId = "att-2",
          tool = "fs_read",
          callId = Some("call-3"),
          atMs = 3400L,
          result = model.ToolResult.Succeeded,
          argsJsonText = None,
          argsRedactedSafe =
            Some("""{"path":"examples/demo.scala","mode":"read"}"""),
          envRefs = Vector.empty,
          artifacts = Vector(
            model.ArtifactRef(
              "artifact-ws-1",
              sha("workspace artifact: examples/demo.scala (fixture bytes)"),
              "text/x-scala",
              128L
            )
          )
        )
      ),
      verdicts = Vector(
        model.VerifierResult(
          episodeId = "ep-1",
          verifierId = "verify.sh",
          verdict = model.VerdictValue.Pass,
          atMs = 3100L,
          evidenceSha256 = sha("verify.sh stdout for ep-1: 12 checks ok"),
          evidenceKind = "verify.sh:stdout",
          note = None
        ),
        model.VerifierResult(
          episodeId = "ep-2",
          verifierId = "verify.sh",
          verdict = model.VerdictValue.Fail,
          atMs = 5100L,
          evidenceSha256 =
            sha("verify.sh stdout for ep-2: regression r2 failed"),
          evidenceKind = "verify.sh:stdout",
          note = Some("hidden regression r2 failed")
        )
      )
    )

  // -- cancelled run ---------------------------------------------------------------

  object Cancelled:

    val input: model.EvalRunInput = model.EvalRunInput(
      run = model
        .RunInfo("run-20261106-cancelled", generator, "mock-coder", 100L, None),
      jobs = Vector(model.JobRecord("job-root", None, "cancelled agent loop")),
      attempts = Vector(
        model.AttemptRecord("att-1", "job-root", 100L, Some(2000L)),
        model.AttemptRecord("att-2", "job-root", 3000L, None),
        model.AttemptRecord("att-3", "job-root", 3500L, None)
      ),
      episodes = Vector(
        model.EpisodeInput(
          episodeId = "ep-1",
          attemptId = "att-1",
          completion =
            model.Completion.Completed(model.ExecutionStatus.Succeeded, 2000L),
          verifierExpected = true,
          verifierId = Some("verify.sh"),
          safety = None,
          protocol = None
        ),
        model.EpisodeInput(
          episodeId = "ep-2",
          attemptId = "att-2",
          completion = model.Completion.Partial(
            model.TerminalState.Cancelled,
            "user requested cancellation",
            6000L
          ),
          verifierExpected = true,
          verifierId = Some("verify.sh"),
          safety = None,
          protocol = None
        ),
        model.EpisodeInput(
          episodeId = "ep-3",
          attemptId = "att-3",
          completion = model.Completion.Partial(
            model.TerminalState.Interrupted,
            "runner SIGTERM before terminal evidence",
            6500L
          ),
          verifierExpected = false,
          verifierId = None,
          safety = None,
          protocol = None
        )
      ),
      calls = Vector(
        model.ModelCallInput(
          "call-1",
          "att-1",
          1,
          "mock-coder",
          500L,
          model.Usage(Some(10L), Some(500L), false)
        )
      ),
      tools = Vector.empty,
      verdicts = Vector(
        model.VerifierResult(
          episodeId = "ep-1",
          verifierId = "verify.sh",
          verdict = model.VerdictValue.Pass,
          atMs = 1500L,
          evidenceSha256 =
            sha("verify.sh stdout for cancelled ep-1: 7 checks ok"),
          evidenceKind = "verify.sh:stdout",
          note = None
        )
      )
    )

  // -- unknown-price run ---------------------------------------------------------------

  object UnknownPrice:

    val input: model.EvalRunInput = model.EvalRunInput(
      run = model.RunInfo(
        "run-20261106-unknown-price",
        generator,
        "mock-coder",
        500L,
        Some(2500L)
      ),
      jobs = Vector(model.JobRecord("job-root", None, "priceless agent loop")),
      attempts =
        Vector(model.AttemptRecord("att-1", "job-root", 600L, Some(2000L))),
      episodes = Vector(
        model.EpisodeInput(
          episodeId = "ep-1",
          attemptId = "att-1",
          completion =
            model.Completion.Completed(model.ExecutionStatus.Succeeded, 2000L),
          verifierExpected = false,
          verifierId = None,
          safety = None,
          protocol = None
        )
      ),
      calls = Vector(
        model.ModelCallInput(
          "call-1",
          "att-1",
          1,
          "mock-coder",
          700L,
          model.Usage(None, None, true)
        ),
        model.ModelCallInput(
          "call-2",
          "att-1",
          2,
          "mock-coder",
          1800L,
          model.Usage(None, Some(1200L), false)
        )
      ),
      tools = Vector.empty,
      verdicts = Vector.empty
    )

  // -- late usage ------------------------------------------------------------------

  val Late1: model.LateUsage =
    model.LateUsage(
      "call-1",
      model.Usage(Some(10L), Some(1800L), false),
      "provider usage report",
      9000L
    )

  val Late2: model.LateUsage =
    model.LateUsage(
      "call-1",
      model.Usage(Some(10L), Some(1900L), false),
      "provider usage correction",
      9500L
    )

  /** [[Cancelled]] after both late-usage appends (the late-usage golden). */
  lazy val lateUsageBundle: ExportBundle =
    Reconciliation.append(exported(Cancelled.input), Late1) match
      case Right(afterFirst) =>
        Reconciliation.append(afterFirst, Late2) match
          case Right(afterSecond) => afterSecond
          case Left(e)            => throw new IllegalStateException(e.message)
      case Left(e) => throw new IllegalStateException(e.message)

  // -- helpers ---------------------------------------------------------------------

  /** Export a fixture input; a validation failure is a fixture bug and fails
    * the test loudly.
    */
  def exported(input: model.EvalRunInput): ExportBundle =
    Exporter.exportRun(input) match
      case Right(bundle) => bundle
      case Left(e) =>
        throw new IllegalStateException("fixture export failed: " + e.message)

  /** Export an input without unwrapping, for typed-failure tests. */
  def exportOf(
      input: model.EvalRunInput
  ): Either[model.ExportError, ExportBundle] =
    Exporter.exportRun(input)

  def episodesOf(bundle: ExportBundle): Vector[wire.EpisodeWire] =
    unwrap(bundle.parseJsonl[wire.EpisodeWire](ExportBundle.EpisodesName))

  def callsOf(bundle: ExportBundle): Vector[wire.CallWire] =
    unwrap(bundle.parseJsonl[wire.CallWire](ExportBundle.CallsName))

  def toolsOf(bundle: ExportBundle): Vector[wire.ToolWire] =
    unwrap(bundle.parseJsonl[wire.ToolWire](ExportBundle.ToolsName))

  def verdictsOf(bundle: ExportBundle): Vector[wire.VerdictWire] =
    unwrap(bundle.parseJsonl[wire.VerdictWire](ExportBundle.VerdictsName))

  def manifestOf(bundle: ExportBundle): wire.ManifestWire =
    unwrap(bundle.parseJson[wire.ManifestWire](ExportBundle.ManifestName))

  def reconciliationOf(bundle: ExportBundle): Vector[wire.ReconciliationWire] =
    unwrap(
      bundle.parseJsonl[wire.ReconciliationWire](
        ExportBundle.ReconciliationName
      )
    )

  private def unwrap[A](e: Either[model.ExportError, A]): A = e match
    case Right(a) => a
    case Left(e) =>
      throw new IllegalStateException("decode failed: " + e.message)

  // -- golden helpers ----------------------------------------------------------------

  def repoRoot: java.nio.file.Path =
    Paths.get(sys.env.getOrElse("RAIDER_REPO_ROOT", "."))

  def expectedDir(name: String): java.nio.file.Path =
    repoRoot.resolve("contracts/eval-export/expected").resolve(name)

  def schemasDir: java.nio.file.Path =
    repoRoot.resolve("contracts/eval-export/schemas")

  /** Byte-compare every bundle document against the pinned golden directory.
    * Returns a human-readable description of the first mismatch, or None when
    * everything matches (including "no golden pinned yet", which is a real
    * failure of the test run, never a pass).
    */
  def goldenMismatch(bundle: ExportBundle, name: String): Option[String] =
    val dir = expectedDir(name)
    ExportBundle.AllNames.foldLeft[Option[String]](None) { (acc, file) =>
      acc.orElse {
        val path = dir.resolve(file)
        if !Files.exists(path) then
          Some(
            s"golden file missing: $path (run modules/eval-export/run-tests.sh --update-goldens)"
          )
        else
          val pinned = new String(Files.readAllBytes(path), UTF_8)
          val actual = bundle
            .file(file)
            .map(_.content)
            .getOrElse("<file absent from bundle>")
          if actual == pinned then None
          else
            Some(
              s"golden mismatch for $name/$file\n--- pinned ---\n$pinned--- actual ---\n$actual"
            )
      }
    }
