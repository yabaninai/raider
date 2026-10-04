package raider.cli.run

import raider.core.*
import raider.core.bundle.BundleManifest
import raider.core.result.{CheckOutcome, CheckResult, ExecutionStatus,
                           ExitCodes, RunResult}
import raider.runtime.admission.{Admission, SlotKind}
import raider.runtime.budget.{BudgetLimits, CostEstimate, MicroUsd}
import raider.runtime.loop.AgentLoop
import zio.{Scope, ZIO}
import zio.json.EncoderOps

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.time.Instant
import java.util.UUID

/** Headless runner (RAI-022 slice): preflight → ONE admitted dispatch →
  * artifacts (run.json / events.jsonl / summary.md) → stable exit code
  * (ci-runtime §8). The invocation owns its root: state lives in
  * `<out>/work-<uuid>` — concurrent invocations never share directories
  * (RUN-03). run.json is written atomically (temp+rename) and VALIDATED
  * before the write; the schema validation is recorded as a real check.
  * --mock is mandatory in this slice: live providers are not wired (the
  * fixture marker is recorded in summary.md and events).
  */
object HeadlessRunner:

  final case class Outcome(exitCode: Int, workDir: Path,
                           result: Option[RunResult])

  private val workDirHolder =
    new java.util.concurrent.atomic.AtomicReference[Path](Path.of("."))

  /** Stable exit-code mapping (ci-runtime §8): total over the 18 families —
    * no catch-all zero. */
  def exitCodeFor(e: RaiderError): Int = e match
    case _: RaiderError.Configuration        => ExitCodes.ConfigInvalid
    case _: RaiderError.InputValidation      => ExitCodes.ConfigInvalid
    case _: RaiderError.Compilation          => ExitCodes.ConfigInvalid
    case _: RaiderError.OutputValidation     => ExitCodes.TaskRejected
    case _: RaiderError.ToolFailed           => ExitCodes.TaskRejected
    case _: RaiderError.ChildFailed          => ExitCodes.TaskRejected
    case _: RaiderError.ProviderAuth         => ExitCodes.RuntimeFailure
    case _: RaiderError.ProviderRateLimit    => ExitCodes.RuntimeFailure
    case _: RaiderError.ProviderUnavailable  => ExitCodes.RuntimeFailure
    case _: RaiderError.StreamProtocol       => ExitCodes.RuntimeFailure
    case _: RaiderError.ProviderBudget       => ExitCodes.BudgetExhausted
    case _: RaiderError.LocalBudgetExceeded  => ExitCodes.BudgetExhausted
    case _: RaiderError.DeadlineExceeded     => ExitCodes.DeadlineExceeded
    case _: RaiderError.CapabilityUnsupported => ExitCodes.NeedsApproval
    case _: RaiderError.Cancelled            => ExitCodes.Uncertain
    case _: RaiderError.ToolOutcomeUncertain => ExitCodes.Uncertain
    case _: RaiderError.ToolDenied           => ExitCodes.PolicyDenied
    case _: RaiderError.JournalFailure       => ExitCodes.ArtifactWriteFailure

  /** Run to completion; returns the process exit code. Never throws. */
  def run(args: CliArgs): ZIO[Any, Nothing, Outcome] =
    ZIO.scoped:
      for
        startedAt  <- ZIO.succeed(Instant.now())
        root        = RootId(s"cli_${UUID.randomUUID().toString.take(8)}")
        workDir     = Paths.get(args.out.toString,
                        s"work-${UUID.randomUUID().toString.take(8)}")
        _           = workDirHolder.set(workDir) // for trace writing
        outcome    <- execute(args, root).either
        finishedAt <- ZIO.succeed(Instant.now())
        exitCode    = outcome.fold(exitCodeFor, _ => ExitCodes.Success)
        runResult   = RunResult(
          schemaVersion = 1,
          rootId = root,
          status = outcome.fold(_ => ExecutionStatus.Failed,
            _ => ExecutionStatus.Succeeded),
          exitCode = exitCode,
          taskVerdict = None, // no verifier in this slice: no fake verdict
          checks = checksFor(outcome),
          artifacts = List.empty,
          output = outcome.toOption.map(text =>
            result.OutputPayload("raider/text", 1, text.toJson)),
          error = outcome.fold(Some(_), _ => None),
          startedAt = startedAt.toString,
          finishedAt = finishedAt.toString)
        _          <- writeArtifacts(workDir, runResult, args, outcome)
      yield Outcome(exitCode, workDir, Some(runResult))

  /** Preflight families (args/manifest/load/entry) vs run-time failures. */
  private def isPreflightFailure(e: RaiderError): Boolean = e match
    case _: RaiderError.Configuration   => true
    case _: RaiderError.InputValidation => true
    case _                              => false

  private def writeTrace(workDir: Path, rootId: String, model: String,
      traceRef: zio.Ref[Vector[raider.core.trace.TraceEvent]]): ZIO[Any, Nothing, Unit] =
    ZIO.succeed {
      try
        import zio.json.EncoderOps
        java.nio.file.Files.createDirectories(workDir)
        val events = zio.Unsafe.unsafe { implicit u =>
          zio.Runtime.default.unsafe.run(traceRef.get).getOrThrow()
        }
        val trace = raider.core.trace.RunTrace(rootId, model, events.toList)
        java.nio.file.Files.writeString(workDir.resolve("transcript.json"),
          trace.toJson, java.nio.charset.StandardCharsets.UTF_8)
      catch case _: Exception => () // trace is best-effort observability
    }

  private def printEvent(ev: raider.core.trace.TraceEvent): Unit =
    ev match
      case raider.core.trace.TraceEvent.ToolCallStarted(_, _, tool, _, args) =>
        System.err.println(f"  \u001b[34m→ $tool\u001b[0m ${args.take(120)}")
      case raider.core.trace.TraceEvent.ToolCallFinished(_, _, tool, _, result, ok) =>
        System.err.println(f"  \u001b[32m← $tool\u001b[0m ${result.take(150)}")
      case raider.core.trace.TraceEvent.RoundStarted(_, r) =>
        System.err.println(f"\u001b[2m[round $r]\u001b[0m")
      case raider.core.trace.TraceEvent.TextReceived(_, _, text) =>
        System.err.println(f"  \u001b[32m← ${text.take(120)}\u001b[0m")
      case raider.core.trace.TraceEvent.RunFailed(_, code, detail) =>
        System.err.println(f"\u001b[31m✗ FAILED [$code]: ${detail.take(120)}\u001b[0m")
      case _ => ()

  private def targetLine(t: CliArgs.Target): String = t match
    case CliArgs.Target.Bundle(jar, wf) => s"bundle $jar / workflow $wf"
    case CliArgs.Target.Agent(name)     => s"built-in agent $name"

  private def checksFor(outcome: Either[RaiderError, String]): List[CheckResult] =
    List(CheckResult("bundle-preflight",
      if outcome.fold(isPreflightFailure, _ => true) then CheckOutcome.Failed
      else CheckOutcome.Passed,
      outcome.fold(e => s"typed failure ${e.code}", _ => "preflight ok")))

  /** Preflight (args/manifest/load/entry/input) happens BEFORE the single
    * admitted dispatch — spend comes last (RUN-02).
    *
    * ADMISSION NOTE: bundle programs are OPAQUE tasks, so the runner admits
    * them itself (one outer slot). The built-in agents run THROUGH
    * AgentLoop, which performs its OWN admission (the single dispatch
    * discipline belongs to the loop) — wrapping the loop in an outer slot
    * would nest two takes on maxLLM=1 and deadlock (found by fixture,
    * 2026-10-04). */
  private def execute(args: CliArgs, root: RootId)
      : ZIO[Scope, RaiderError, String] =
    for
      input <- ZIO.fromEither(args.inputText)
      text  <- args.target match
        case CliArgs.Target.Agent(name) =>
          for
            limits    <- ZIO.fromEither(BudgetLimits.make(maxConcurrentTools = 2, maxAttempts = 48))
            admission <- Admission.make(Right(limits))
            // coder = REAL workspace tools, rooted at the invocation's
            // workspace (default: CWD) — self-hosting discipline: the model
            // can only touch what the operator pointed the runner at
            wsPath    <- ZIO.succeed(args.workspace
                          .getOrElse(Paths.get("").toAbsolutePath))
            toolset   <- if name == "coder"
                           then raider.tools.CodingToolset
                                  .make(wsPath.toString)
                                  .flatMap(st =>
                                    ZIO.fromEither(st.registry).map(r => Some(r)))
                           else ZIO.succeed(None)
            registry   = toolset.getOrElse(ToolRegistry.empty)
            backend   <- args.provider match
                           case "openai" =>
                             raider.provider.chat.OpenAIChatBackend.make(
                               raider.provider.chat.OpenAIChatBackend.Config
                                 .make(args.baseUrl, args.apiKey,
                                       callTimeoutMs = 300000L)
                             ).mapError {
                               case e: RaiderError => e
                               case t => RaiderError.ProviderUnavailable(
                                 s"backend init: ${t.getClass.getSimpleName}")
                             }
                           case _ => ZIO.succeed(MockBackend.backend)
            model      = if name == "coder" then args.model
                         else s"builtin:$name"
            // OBSERVABILITY: a trace recorder that collects events into a
            // Ref; written to transcript.json after the run
            traceRef  <- zio.Ref.make(Vector.empty[raider.core.trace.TraceEvent])
            // VERBOSE trace: prints tool calls to stderr in real-time
            sysPrompt  = if name == "coder" then Some(CoderSystemPrompt) else None
            loop       = AgentLoop(backend, registry, model, admission, root,
                           CostEstimate.Unknown, ev => ZIO.succeed {
                             zio.Unsafe.unsafe { implicit u => zio.Runtime.default.unsafe.run(traceRef.update(_ :+ ev)).getOrThrow() }
                             printEvent(ev)
                           }, sysPrompt)
            resultEith <- loop.runText(List(RequestMessage("user", input)),
                            limits, math.min(limits.maxAttempts, 12)).either
            _          <- writeTrace(workDirHolder.get, root.value, model, traceRef)
            result     <- ZIO.fromEither(resultEith)
          yield result
        case CliArgs.Target.Bundle(jar, workflow) =>
          for
            manifest  <- ZIO.fromEither(BundleLoader.readManifest(jar))
            programs  <- ZIO.fromEither(BundleLoader.load(jar, manifest))
            entry     <- ZIO.fromEither(
                           BundleManifest.entry(manifest, workflow))
            program   <- ZIO.fromEither(
                           programs.get(entry.name).toRight(
                             RaiderError.InputValidation(
                               s"bundle entry '${entry.name}' did not load a program")))
            limits    <- ZIO.fromEither(BudgetLimits.make())
            admission <- Admission.make(Right(limits))
            text      <- admission.withSlot(
                           SlotKind.Model, root, CostEstimate.Unknown, None) {
                           Task.zio(program(input)).map(s => (s, MicroUsd(0)))
                         }
          yield text
    yield text

  private def writeArtifacts(workDir: Path, runResult: RunResult,
      args: CliArgs, outcome: Either[RaiderError, String])
      : ZIO[Any, Nothing, Unit] =
    ZIO.attempt {
      Files.createDirectories(workDir)
      // run.json — validate BEFORE the write; the check result is part of
      // the document (a real check, not a decoration)
      val schemaOk = RunResult.validate(runResult)
      val finalResult = schemaOk.fold(
        err => runResult.copy(checks = runResult.checks :+
          CheckResult("run-result-schema", CheckOutcome.Failed, err.detail)),
        _ => runResult.copy(checks = runResult.checks :+
          CheckResult("run-result-schema", CheckOutcome.Passed,
            "validated pre-write")))
      val tmp = workDir.resolve("run.json.tmp")
      Files.writeString(tmp, finalResult.toJson, UTF_8)
      Files.move(tmp, workDir.resolve("run.json"),
        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      // events.jsonl (bounded, versioned envelopes)
      val events =
        Vector("run.started", "run.finished").map { t =>
          schemas.VersionedEnvelope.encodeValidated(1,
            s"""{"event":"$t","root":"${runResult.rootId.value}",""" +
              s""""at":"${Instant.now()}"}""").fold(_ => "", identity)
        }.mkString("", "\n", "\n")
      Files.writeString(workDir.resolve("events.jsonl"), events, UTF_8)
      // summary.md — the fixture marker is MANDATORY under --mock
      val fixtureLine =
        if args.mock then "backend: **fixture/mock** (fixtureMode; no live network)"
        else "backend: live (not wired in this slice)"
      val statusLine = outcome.fold(
        e => s"FAILED — ${e.code}: ${e.detail.take(160)}",
        t => s"SUCCEEDED — output: ${t.take(200)}")
      Files.writeString(workDir.resolve("summary.md"),
        s"""# raider run
           |
           |$statusLine
           |
           |$fixtureLine
           |
           |- target: ${targetLine(args.target)}
           |- provider: ${args.provider} (model: ${args.model})
           |- exit code: ${runResult.exitCode}
           |""".stripMargin, UTF_8)
      ()
    }.orDie
  private val CoderSystemPrompt =
    """You are a coding agent working inside a Scala 3 + ZIO project called Raider.
You have tools: fs_read (read files), fs_search (search text), fs_edit (edit files with sha256 verification), proc_run (run commands).
Workspace is the project root. All paths are relative to it.

Rules:
- Use fs_read to understand code before making changes
- Use fs_edit with the current file's sha256 to make changes
- Use proc_run to run commands (compilation, tests) — argv is explicit, no shell
- After making changes, run compilation to verify
- If compilation fails, read the errors and fix them
- Always give a clear, concise final answer""".stripMargin

end HeadlessRunner
