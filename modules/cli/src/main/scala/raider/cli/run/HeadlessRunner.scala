package raider.cli.run

import raider.core.*
import raider.cli.CoderPrompts
import raider.core.bundle.BundleManifest
import raider.core.result.{
  CheckOutcome,
  CheckResult,
  ExecutionStatus,
  ExitCodes,
  RunResult
}
import raider.runtime.admission.{Admission, SlotKind}
import raider.runtime.budget.{BudgetLimits, CostEstimate, MicroUsd, UsageReport}
import raider.runtime.events.{EventEnvelope, EventHub, EventPayload}
import raider.runtime.jobs.{JobManager, StatusLedger}
import raider.runtime.loop.{AgentLoop, LoopEvents}
import zio.{Promise, Ref, Scope, ZIO}
import zio.json.EncoderOps

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.time.Instant
import java.util.UUID

/** Headless runner (RAI-022 slice): preflight → ONE admitted dispatch →
  * artifacts (run.json / events.jsonl / summary.md) → stable exit code
  * (ci-runtime §8). The invocation owns its root: state lives in
  * `<out>/work-<uuid>` — concurrent invocations never share directories
  * (RUN-03). run.json is written atomically (temp+rename) and VALIDATED before
  * the write; the schema validation is recorded as a real check. `--mock` marks
  * fixture mode: the mock marker is recorded in summary.md and events (a mock
  * answer can never pass as a live one). Live providers (openai / anthropic,
  * RAI-071) build REAL backends via [[ProviderBackend]] behind the
  * env-credential preflight (RAI-064).
  *
  * RAI-086: `--context <file>` wires the parsed [[CiContext]] into run.json as
  * the additive `ci` field — UNTRUSTED contexts are exported ONLY via
  * `redacted` (identity-bearing fields collapse to placeholders), trusted ones
  * pass through unchanged. The run.json `accounting` field carries the REAL
  * final [[BudgetLedger]] snapshot ([[AccountingView.json]]) and appears ONLY
  * when the invocation actually passed through the ledger — a run that failed
  * an earlier preflight has no ledger and therefore NO accounting field
  * (nothing is invented); summary.md gets the honest one-line cost view.
  */
object HeadlessRunner:

  final case class Outcome(
      exitCode: Int,
      workDir: Path,
      result: Option[RunResult]
  )

  /** Stable exit-code mapping (ci-runtime §8): total over the 18 families — no
    * catch-all zero.
    */
  def exitCodeFor(e: RaiderError): Int = e match
    case _: RaiderError.Configuration         => ExitCodes.ConfigInvalid
    case _: RaiderError.InputValidation       => ExitCodes.ConfigInvalid
    case _: RaiderError.Compilation           => ExitCodes.ConfigInvalid
    case _: RaiderError.OutputValidation      => ExitCodes.TaskRejected
    case _: RaiderError.ToolFailed            => ExitCodes.TaskRejected
    case _: RaiderError.ChildFailed           => ExitCodes.TaskRejected
    case _: RaiderError.ProviderAuth          => ExitCodes.RuntimeFailure
    case _: RaiderError.ProviderRateLimit     => ExitCodes.RuntimeFailure
    case _: RaiderError.ProviderUnavailable   => ExitCodes.RuntimeFailure
    case _: RaiderError.StreamProtocol        => ExitCodes.RuntimeFailure
    case _: RaiderError.ProviderBudget        => ExitCodes.BudgetExhausted
    case _: RaiderError.LocalBudgetExceeded   => ExitCodes.BudgetExhausted
    case _: RaiderError.DeadlineExceeded      => ExitCodes.DeadlineExceeded
    case _: RaiderError.CapabilityUnsupported => ExitCodes.NeedsApproval
    case _: RaiderError.Cancelled             => ExitCodes.Uncertain
    case _: RaiderError.ToolOutcomeUncertain  => ExitCodes.Uncertain
    case _: RaiderError.ToolDenied            => ExitCodes.PolicyDenied
    case _: RaiderError.JournalFailure        => ExitCodes.ArtifactWriteFailure

  /** Run to completion; returns the process exit code. Never throws. Uses the
    * process environment for credential resolution (RAI-064).
    */
  def run(args: CliArgs): ZIO[Any, Nothing, Outcome] = run(args, sys.env)

  /** Run with an explicit environment — a test seam for RAI-064 so credential
    * resolution can be exercised hermetically (no reliance on the process env).
    */
  def run(args: CliArgs, env: Map[String, String]): ZIO[Any, Nothing, Outcome] =
    ZIO.scoped:
      for
        startedAt <- ZIO.succeed(Instant.now())
        root = RootId(s"cli_${UUID.randomUUID().toString.take(8)}")
        workDir = Paths.get(
          args.out.toString,
          s"work-${UUID.randomUUID().toString.take(8)}"
        )
        // RAI-060: real event seam — hub + job layer (RAI-059) wired for THIS
        // invocation; the whole execute (preflight included) runs as one job,
        // so events.jsonl covers queued→running→terminal for every run.
        hub <- EventHub.make()
        manager <- JobManager.make(
          onTransition = Some(StatusLedger.onTransition(hub, root))
        )
        // RAI-086: captured out of the job body — the ci view parsed during
        // preflight and the REAL final ledger snapshot. Each stays None when
        // its stage was never reached (malformed context / preflight before
        // the ledger) — the artifact then honestly lacks the field.
        ciRef <- Ref.make(Option.empty[CiContext])
        usageRef <- Ref.make(Option.empty[UsageReport])
        jobOutcome <- executeAsJob(
          args,
          root,
          env,
          workDir,
          hub,
          manager,
          ciRef,
          usageRef
        )
        (outcome, exported) = jobOutcome
        ciOpt <- ciRef.get
        usageOpt <- usageRef.get
        finishedAt <- ZIO.succeed(Instant.now())
        exitCode = outcome.fold(exitCodeFor, _ => ExitCodes.Success)
        runResult = RunResult(
          schemaVersion = 1,
          rootId = root,
          status = outcome
            .fold(_ => ExecutionStatus.Failed, _ => ExecutionStatus.Succeeded),
          exitCode = exitCode,
          taskVerdict = None, // no verifier in this slice: no fake verdict
          checks = checksFor(outcome),
          artifacts = List.empty,
          output = outcome.toOption.map(text =>
            result.OutputPayload("raider/text", 1, text.toJson)
          ),
          error = outcome.fold(Some(_), _ => None),
          startedAt = startedAt.toString,
          finishedAt = finishedAt.toString
        )
        _ <- writeArtifacts(
          workDir,
          runResult,
          args,
          outcome,
          exported,
          ciOpt,
          usageOpt
        )
      yield Outcome(exitCode, workDir, Some(runResult))

  /** RAI-060: the whole run as ONE JobManager job, with ALL envelopes collected
    * for events.jsonl.
    *
    * Ordering guarantee (no loss window): the job body is held on a barrier
    * until AFTER the subscriber registered (snapshot + watermark in one hub
    * transaction). At that point exactly ONE envelope exists — the start
    * transition Queued→Running published by `manager.start` before the handle
    * was returned — and it is reconstructed EXACTLY from the subscription
    * snapshot (its atMs IS the snapshot's updatedAtMs; nothing is invented).
    * Everything else arrives on the subscribed stream, seq-ordered before the
    * write ([[EventExport.inOrder]]).
    *
    * Slow-consumer/overflow policy (hub semantics, documented here per
    * STAT-03): a subscriber queue offers backpressure to the forwarder fibers,
    * never to publishers; a diagnostics-class overflow is dropped and counted
    * INSIDE the hub (`droppedDiagnostics`) without consuming a sequence, and
    * reliable envelopes (status/Terminal/approval) are never silently lost.
    * Before the job settles, the observed drop count is exported as a REAL
    * `DiagnosticsCoalesced` envelope (best-effort: it rides the diagnostics
    * class), so the file itself carries the coalescing marker without breaking
    * the per-job stream contract (never after Terminal).
    */
  private def executeAsJob(
      args: CliArgs,
      root: RootId,
      env: Map[String, String],
      workDir: Path,
      hub: EventHub,
      manager: JobManager,
      ciOut: Ref[Option[CiContext]],
      usageOut: Ref[Option[UsageReport]]
  ): ZIO[Any, Nothing, (Either[RaiderError, String], Vector[EventEnvelope])] =
    for
      barrier <- Promise.make[Nothing, JobId]
      droppedBefore <- hub.droppedDiagnostics
      job = Task(
        barrier.await.flatMap { jobId =>
          val le = LoopEvents(hub, jobId, root, StatusLedger.sessionId)
          // The observed hub-level diagnostics drop count rides out as a REAL
          // DiagnosticsCoalesced envelope BEFORE the job settles (so it is
          // never after Terminal); the marker is best-effort (diagnostics
          // class), the reliable path is never affected.
          val emitMarker = hub.droppedDiagnostics.flatMap { dropped =>
            val lost = dropped - droppedBefore
            ZIO.when(lost > 0L)(
              le.emit(EventPayload.DiagnosticsCoalesced(lost))
            )
          }
          ZIO
            .scoped(
              execute(args, root, env, workDir, Some(le), ciOut, usageOut)
            )
            .foldZIO(
              err => emitMarker *> ZIO.fail(err),
              ok => emitMarker.as(ok)
            )
        }
      )
      handle <- manager.start(root, "headless", job)
      collected <- Ref.make(Vector.empty[EventEnvelope])
      subscribed <- hub.subscribe(handle.id)
      (snapshot, stream) = subscribed
      collector <- stream
        .foreach(e => collected.update(_ :+ e))
        .forkDaemon
      prefix = EventExport.startTransition(snapshot)
      prefixCount = prefix.fold(0)(_ => 1)
      _ <- barrier.succeed(handle.id).unit
      result <- manager
        .await(handle)
        .either
        .ensuring(
          EventExport
            .awaitCaughtUp(
              hub,
              handle.id,
              collected.get.map(v => (v.size + prefixCount).toLong)
            )
            .zipRight(collector.interrupt)
        )
      got <- collected.get
      exported = prefix.toVector ++ got
    yield (result, exported)

  /** Preflight families (args/manifest/load/entry) vs run-time failures. */
  private def isPreflightFailure(e: RaiderError): Boolean = e match
    case _: RaiderError.Configuration   => true
    case _: RaiderError.InputValidation => true
    case _                              => false

  private def writeTrace(
      workDir: Path,
      rootId: String,
      model: String,
      traceRef: zio.Ref[Vector[raider.core.trace.TraceEvent]]
  ): ZIO[Any, Nothing, Unit] =
    ZIO.succeed {
      try
        import zio.json.EncoderOps
        java.nio.file.Files.createDirectories(workDir)
        val events = zio.Unsafe.unsafe { implicit u =>
          zio.Runtime.default.unsafe.run(traceRef.get).getOrThrow()
        }
        val trace = raider.core.trace.RunTrace(rootId, model, events.toList)
        java.nio.file.Files.writeString(
          workDir.resolve("transcript.json"),
          trace.toJson,
          java.nio.charset.StandardCharsets.UTF_8
        )
      catch case _: Exception => () // trace is best-effort observability
    }

  private def printEvent(ev: raider.core.trace.TraceEvent): Unit =
    ev match
      case raider.core.trace.TraceEvent.ToolCallStarted(_, _, tool, _, args) =>
        System.err.println(f"  \u001b[34m→ $tool\u001b[0m ${args.take(120)}")
      case raider.core.trace.TraceEvent.ToolCallFinished(
            _,
            _,
            tool,
            _,
            result,
            ok
          ) =>
        System.err.println(f"  \u001b[32m← $tool\u001b[0m ${result.take(150)}")
      case raider.core.trace.TraceEvent.RoundStarted(_, r) =>
        System.err.println(f"\u001b[2m[round $r]\u001b[0m")
      case raider.core.trace.TraceEvent.TextReceived(_, _, text) =>
        System.err.println(f"  \u001b[32m← ${text.take(120)}\u001b[0m")
      case raider.core.trace.TraceEvent.RunFailed(_, code, detail) =>
        System.err.println(
          f"\u001b[31m✗ FAILED [$code]: ${detail.take(120)}\u001b[0m"
        )
      case _ => ()

  private def targetLine(t: CliArgs.Target): String = t match
    case CliArgs.Target.Bundle(jar, wf) => s"bundle $jar / workflow $wf"
    case CliArgs.Target.Agent(name)     => s"built-in agent $name"

  private def checksFor(
      outcome: Either[RaiderError, String]
  ): List[CheckResult] =
    List(
      CheckResult(
        "bundle-preflight",
        if outcome.fold(isPreflightFailure, _ => true) then CheckOutcome.Failed
        else CheckOutcome.Passed,
        outcome.fold(e => s"typed failure ${e.code}", _ => "preflight ok")
      )
    )

  /** Preflight (args/manifest/load/entry/input) happens BEFORE the single
    * admitted dispatch — spend comes last (RUN-02).
    *
    * ADMISSION NOTE: bundle programs are OPAQUE tasks, so the runner admits
    * them itself (one outer slot). The built-in agents run THROUGH AgentLoop,
    * which performs its OWN admission (the single dispatch discipline belongs
    * to the loop) — wrapping the loop in an outer slot would nest two takes on
    * maxLLM=1 and deadlock (found by fixture, 2026-10-04).
    */
  private def execute(
      args: CliArgs,
      root: RootId,
      env: Map[String, String],
      workDir: Path,
      events: Option[LoopEvents],
      ciOut: Ref[Option[CiContext]],
      usageOut: Ref[Option[UsageReport]]
  ): ZIO[Scope, RaiderError, String] =
    for
      // RAI-086 preflight (args-level, FIRST): parse the ci-context payload
      // BEFORE any dispatch — a typed parse failure refuses the launch with
      // no spend. A successfully parsed context is published to ciOut
      // immediately, so it is exported even when a LATER preflight step
      // fails the run (the failed run.json still carries the honest view).
      ciOpt <- ZIO.fromEither(contextFrom(args))
      _ <- ciOut.set(ciOpt)
      // RAI-064 preflight: a live provider requires a credential from the
      // environment BEFORE any dispatch/spend — no network call, no artifact,
      // no spend when the key is missing (SETUP-02). Mock needs no key.
      keyOpt <- ZIO.fromEither(Credentials.resolve(args.provider, env))
      input <- ZIO.fromEither(args.inputText)
      text <- args.target match
        case CliArgs.Target.Agent(name) =>
          for
            limits <- ZIO.fromEither(
              BudgetLimits.make(maxConcurrentTools = 2, maxAttempts = 48)
            )
            admission <- Admission.make(Right(limits))
            // coder = REAL workspace tools, rooted at the invocation's
            // workspace (default: CWD) — self-hosting discipline: the model
            // can only touch what the operator pointed the runner at
            wsPath <- ZIO.succeed(
              args.workspace
                .getOrElse(Paths.get("").toAbsolutePath)
            )
            toolset <-
              if name == "coder"
              then
                raider.tools.CodingToolset
                  .make(wsPath.toString)
                  .flatMap(st => ZIO.fromEither(st.registry).map(r => Some(r)))
              else ZIO.succeed(None)
            registry = toolset.getOrElse(ToolRegistry.empty)
            // RAI-071: one factory maps the validated --provider value onto
            // its backend — mock | openai | anthropic (Messages wire); both
            // live wires ride the same credential preflight resolved above
            // and the same RateLimitRetries wrapper.
            backend <- ProviderBackend.make(args.provider, args.baseUrl, keyOpt)
            model =
              if name == "coder" then args.model
              else s"builtin:$name"
            // OBSERVABILITY: a trace recorder that collects events into a
            // Ref; written to transcript.json after the run
            traceRef <- zio.Ref.make(Vector.empty[raider.core.trace.TraceEvent])
            // VERBOSE trace: prints tool calls to stderr in real-time
            sysPrompt =
              if name == "coder" then Some(CoderPrompts.CoderSystemPrompt)
              else None
            loop = AgentLoop(
              backend,
              registry,
              model,
              admission,
              root,
              CostEstimate.Unknown,
              ev =>
                ZIO.succeed {
                  zio.Unsafe.unsafe { implicit u =>
                    zio.Runtime.default.unsafe
                      .run(traceRef.update(_ :+ ev))
                      .getOrThrow()
                  }
                  printEvent(ev)
                },
              sysPrompt,
              events
            )
            resultEith <- loop
              .runText(
                List(RequestMessage("user", input)),
                limits,
                math.min(limits.maxAttempts, 12)
              )
              .either
            // RAI-086: capture the REAL final ledger snapshot whether the
            // loop settled or failed — the report stays truthful either way
            // (unpriced attempts are counted, never treated as free).
            _ <- captureUsage(admission, usageOut)
            _ <- writeTrace(workDir, root.value, model, traceRef)
            result <- ZIO.fromEither(resultEith)
          yield result
        case CliArgs.Target.Bundle(jar, workflow) =>
          for
            manifest <- ZIO.fromEither(BundleLoader.readManifest(jar))
            programs <- ZIO.fromEither(BundleLoader.load(jar, manifest))
            entry <- ZIO.fromEither(BundleManifest.entry(manifest, workflow))
            program <- ZIO.fromEither(
              programs
                .get(entry.name)
                .toRight(
                  RaiderError.InputValidation(
                    s"bundle entry '${entry.name}' did not load a program"
                  )
                )
            )
            limits <- ZIO.fromEither(BudgetLimits.make())
            admission <- Admission.make(Right(limits))
            slotResult <- admission
              .withSlot(
                SlotKind.Model,
                root,
                CostEstimate.Unknown,
                None
              ) {
                Task.zio(program(input)).map(s => (s, MicroUsd(0)))
              }
              .either
            // RAI-086: real ledger snapshot on both slot outcomes
            _ <- captureUsage(admission, usageOut)
            text <- ZIO.fromEither(slotResult)
          yield text
    yield text

  private def writeArtifacts(
      workDir: Path,
      runResult: RunResult,
      args: CliArgs,
      outcome: Either[RaiderError, String],
      exported: Vector[EventEnvelope],
      ci: Option[CiContext],
      usage: Option[UsageReport]
  ): ZIO[Any, Nothing, Unit] =
    ZIO.attempt {
      Files.createDirectories(workDir)
      // run.json — validate BEFORE the write; the check result is part of
      // the document (a real check, not a decoration)
      val schemaOk = RunResult.validate(runResult)
      val finalResult = schemaOk.fold(
        err =>
          runResult.copy(checks =
            runResult.checks :+
              CheckResult("run-result-schema", CheckOutcome.Failed, err.detail)
          ),
        _ =>
          runResult.copy(checks =
            runResult.checks :+
              CheckResult(
                "run-result-schema",
                CheckOutcome.Passed,
                "validated pre-write"
              )
          )
      )
      val tmp = workDir.resolve("run.json.tmp")
      Files.writeString(
        tmp,
        // RAI-086: additive `ci`/`accounting` splice (frozen core schema is
        // not extended in this slice); absent input → absent field
        spliceExtras(finalResult.toJson, ci, usage),
        UTF_8
      )
      Files.move(
        tmp,
        workDir.resolve("run.json"),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE
      )
      // events.jsonl (RAI-060): REAL per-job EventEnvelope lines —
      // schemaVersion 1, the zio-json codec from raider.runtime.events,
      // seq-ordered, no gaps (hub drops never consume a sequence). Replaces
      // the former two synthetic run.started/run.finished records.
      Files.writeString(
        workDir.resolve("events.jsonl"),
        EventExport.jsonl(exported),
        UTF_8
      )
      // summary.md — the fixture marker is MANDATORY under --mock
      val fixtureLine =
        if args.mock then
          "backend: **fixture/mock** (fixtureMode; no live network)"
        else s"backend: live (${args.provider})"
      val statusLine = outcome.fold(
        e => s"FAILED — ${e.code}: ${e.detail.take(160)}",
        t => s"SUCCEEDED — output: ${t.take(200)}"
      )
      // RAI-086: honest cost line — present ONLY when the run went through
      // the budget ledger; unknown cost is never shown as a number
      val costLine = usage.fold("")(u => s"- ${AccountingView.human(u)}\n")
      Files.writeString(
        workDir.resolve("summary.md"),
        s"""# raider run
           |
           |$statusLine
           |
           |$fixtureLine
           |
           |- target: ${targetLine(args.target)}
           |- provider: ${args.provider} (model: ${args.model})
           |$costLine- exit code: ${runResult.exitCode}
           |- events: ${exported.size} event envelope(s) exported (schemaVersion 1)
           |""".stripMargin,
        UTF_8
      )
      ()
    }.orDie

  /** RAI-086: `--context` payload reader. `None` = flag absent. The file was
    * already exist-checked at parse time (typed Configuration); a read failure
    * here (race: removed between the checks) is a typed InputValidation, and a
    * malformed/incomplete payload is the typed `CiContextError` (converted to
    * its `RaiderError` view) — the launch is refused either way, BEFORE any
    * dispatch/spend.
    */
  private def contextFrom(
      args: CliArgs
  ): Either[RaiderError, Option[CiContext]] =
    args.context match
      case None => Right(None)
      case Some(file) =>
        try
          val raw = Files.readString(file, UTF_8)
          CiContext.parse(raw).left.map(_.toRaiderError).map(Some(_))
        catch
          case e: Exception =>
            Left(
              RaiderError.InputValidation(
                s"cannot read --context: ${e.getClass.getSimpleName}"
              )
            )

  /** RAI-086: the final REAL ledger snapshot into `usageOut` (honest even on a
    * failed loop: unpriced/uncertain attempts are reported, never dropped).
    */
  private def captureUsage(
      admission: Admission,
      usageOut: Ref[Option[UsageReport]]
  ): ZIO[Any, Nothing, Unit] =
    admission.budgetLedger.usage.flatMap(r => usageOut.set(Some(r)))

  /** RAI-086: additive run.json extension. The frozen core RunResult schema is
    * NOT modified in this slice; `ci` and `accounting` are spliced into the
    * serialized document at the JSON-AST level instead (downstream RunResult
    * decoders ignore unknown keys — the change stays additive). A missing input
    * leaves the field absent (never a fabricated placeholder). The untrusted
    * context is exported ONLY via [[CiContext.redacted]] (trusted passes
    * through unchanged); extras are appended after the existing keys.
    */
  private def spliceExtras(
      base: String,
      ci: Option[CiContext],
      usage: Option[UsageReport]
  ): String =
    import zio.json.ast.Json
    def astOf(json: String): Option[Json] =
      Json.decoder.decodeJson(json).toOption
    val extras: List[(String, Json)] =
      ci.flatMap(c => astOf(c.redacted.toJson).map(j => "ci" -> j)).toList ++
        usage
          .flatMap(u =>
            astOf(AccountingView.json(u)).map(j => "accounting" -> j)
          )
          .toList
    if extras.isEmpty then base
    else
      Json.decoder.decodeJson(base) match
        case Right(Json.Obj(fields)) =>
          Json.Obj(fields ++ zio.Chunk.fromIterable(extras)).toJson
        case _ => base // impossible: RunResult JSON is always an object

end HeadlessRunner
