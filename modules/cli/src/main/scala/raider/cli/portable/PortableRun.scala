package raider.cli.portable

import raider.cli.run.{
  Credentials,
  EventsJsonlWriter,
  HeadlessRunner,
  ProviderBackend
}
import raider.core.{JobId, RaiderError, RootId, SessionId, Task}
import raider.core.result.{
  CheckOutcome,
  CheckResult,
  ExecutionStatus,
  ExitCodes,
  OutputPayload,
  RunResult
}
import raider.dsl.portable.PortableWorkflowLowerer
import raider.runtime.admission.Admission
import raider.runtime.events.{EventEnvelope, EventPayload}
import raider.runtime.jobs.JobStatus
import raider.tools.CodingToolset

import zio.json.*
import zio.json.ast.Json
import zio.{Ref, Scope, Unsafe, ZIO}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal

/** `raider workflow run --doc <file> --input <file> --out <dir> --workspace
  * <dir> [--provider mock|openai|anthropic] [--model <name>] [--base-url <url>]
  * [--attempts <n>] [--deadline-s <s>]` (RAI-199; ADR-036): the opt-in PORTABLE
  * launch route over the existing launch/runtime/reporting boundary.
  *
  * Discipline (mirrors [[raider.cli.workflow.WorkflowRun]] without touching the
  * compiled route):
  *   - preflight order: document validation (zero dispatch) BEFORE credential
  *     resolution BEFORE assembly BEFORE the ONE dispatch;
  *   - fresh policy per invocation: credentials from `env` only (RAI-064),
  *     authority ONLY from [[PortableTrustedRegistry]] (never the document);
  *   - launch-layer enforcement: intersected root limits (attempts/children/
  *     tools/tokens) through ONE admission with maxLLM=1 and the shared token
  *     ceiling envelope ([[PortableLaunchPolicy]], [[TokenBudgetBackend]]);
  *   - ci-runtime §7 artifacts (run.json / summary.md / events.jsonl), §8
  *     stable exit codes, SIGTERM bounded stop, closed stdin by construction;
  *   - the trusted verdict is PROJECTED, not claimed: a launched check that did
  *     not pass is a failed run (RA-OUT) — the model cannot manufacture
  *     success.
  */
object PortableRun:

  final case class Args(
      doc: Path,
      input: Path,
      out: Path,
      workspace: Path,
      provider: String,
      model: String,
      baseUrl: Option[String],
      attempts: Int,
      deadlineSeconds: Option[Long]
  )

  private val flagsWithValue = Set(
    "--doc",
    "--input",
    "--out",
    "--workspace",
    "--provider",
    "--model",
    "--base-url",
    "--attempts",
    "--deadline-s"
  )

  /** Compiled-route flags are refused typed on the portable route: this slice
    * composes trust from the pinned registry, not from build identity or
    * checkpoint approval artifacts.
    */
  private val compiledRouteFlags = Set(
    "--classes",
    "--source",
    "--checkpoint",
    "--decision-file",
    "--approval-request"
  )

  // ------------------------------------------------------------------//
  // Argv parsing                                                        //
  // ------------------------------------------------------------------//

  /** Parses the argv AFTER the `workflow run` prefix (stripped by Main routing;
    * `--doc` presence is what routes here).
    */
  def parse(argv: List[String]): Either[RaiderError, Args] =
    @scala.annotation.tailrec
    def loop(
        rest: List[String],
        acc: Map[String, String]
    ): Either[RaiderError, Map[String, String]] = rest match
      case Nil => Right(acc)
      case flag :: value :: tail if flagsWithValue.contains(flag) =>
        if acc.contains(flag) then
          Left(RaiderError.Configuration(s"$flag specified twice"))
        else loop(tail, acc.updated(flag, value))
      case flag :: Nil if flagsWithValue.contains(flag) =>
        Left(RaiderError.Configuration(s"$flag requires a value"))
      case unknown :: _ =>
        Left(
          RaiderError.Configuration(
            s"unknown argument '$unknown' (usage: raider workflow run " +
              "--doc <file> --input <file> --out <dir> --workspace <dir> " +
              "[--provider mock|openai|anthropic] [--model <name>] " +
              "[--base-url <url>] [--attempts <n>] [--deadline-s <s>])"
          )
        )
    loop(argv, Map.empty).flatMap { acc =>
      val compiled = compiledRouteFlags.filter(acc.contains)
      if compiled.nonEmpty then
        Left(
          RaiderError.Configuration(
            s"${compiled.mkString(", ")} belong to the compiled route; " +
              "the --doc portable route does not accept them"
          )
        )
      else
        for
          doc <- requirePath(acc, "--doc")
          input <- requirePath(acc, "--input")
          out <- requirePath(acc, "--out")
          workspace = acc
            .get("--workspace")
            .map(Paths.get(_))
            .getOrElse(Paths.get("").toAbsolutePath)
          provider = acc.getOrElse("--provider", "mock").toLowerCase
          _ <- Either.cond(
            Set("mock", "openai", "anthropic").contains(provider),
            (),
            RaiderError.Configuration(
              s"unsupported --provider '$provider'; supported: mock, openai, anthropic"
            )
          )
          _ <- Either.cond(
            provider == "mock" || acc.contains("--model"),
            (),
            RaiderError.Configuration(
              s"--model is required for the live provider '$provider'"
            )
          )
          _ <- Either.cond(
            provider == "mock" || acc.contains("--base-url"),
            (),
            RaiderError.Configuration(
              s"--base-url is required for the live provider '$provider'"
            )
          )
          attempts <- acc.get("--attempts") match
            case None => Right(8)
            case Some(raw) =>
              raw.toIntOption
                .filter(_ >= 1)
                .toRight(
                  RaiderError.Configuration(
                    s"--attempts must be an integer >= 1, got '$raw'"
                  )
                )
          deadline <- acc.get("--deadline-s") match
            case None => Right(None)
            case Some(raw) =>
              raw.toLongOption
                .filter(_ > 0)
                .map(d => Right(Some(d)))
                .getOrElse(
                  Left(
                    RaiderError.Configuration(
                      s"--deadline-s must be a positive number of seconds, got '$raw'"
                    )
                  )
                )
        yield Args(
          doc,
          input,
          out,
          workspace,
          provider,
          acc.get("--model").getOrElse("mock-coder"),
          acc.get("--base-url"),
          attempts,
          deadline
        )
    }

  private def requirePath(
      acc: Map[String, String],
      flag: String
  ): Either[RaiderError, Path] =
    acc.get(flag).filter(_.trim.nonEmpty) match
      case Some(v) => Right(Paths.get(v))
      case None    => Left(RaiderError.Configuration(s"$flag is required"))

  // ------------------------------------------------------------------//
  // Outcome and run state (truthful artifacts on every exit path)        //
  // ------------------------------------------------------------------//

  final case class Outcome(
      exitCode: Int,
      workDir: Path,
      error: Option[RaiderError]
  )

  private enum Phase:

    case Preflight, DocValidated, Credentials, Assembled, Dispatched,
      Completed

  private final case class RunState(
      phase: Phase = Phase.Preflight,
      status: ExecutionStatus = ExecutionStatus.Failed,
      terminal: JobStatus.Terminal = JobStatus.Failed,
      exitCode: Int = ExitCodes.RuntimeFailure,
      error: Option[RaiderError] = None,
      output: Option[String] = None,
      verdictClaimed: Boolean = false,
      inputDigest: String = "",
      docDigest: String = "",
      declaredLimits: Option[Json] = None,
      effectiveLimits: Option[String] = None,
      tokensDeclared: Long = 0L,
      tokensObserved: Long = 0L,
      terminalEmitted: Boolean = false,
      artifactsWritten: Boolean = false
  )

  private def terminalStatusFor(
      err: RaiderError
  ): (ExecutionStatus, JobStatus.Terminal) =
    err match
      case _: RaiderError.Cancelled =>
        (ExecutionStatus.Cancelled, JobStatus.Cancelled)
      case _: RaiderError.ToolOutcomeUncertain =>
        (ExecutionStatus.Uncertain, JobStatus.Uncertain)
      case _ => (ExecutionStatus.Failed, JobStatus.Failed)

  /** The typed output-dir probe (the WorkflowRun RAI-159 discipline): creating
    * the work dir IS the create+write probe; failure maps to the honest
    * RA-JOURNAL (§8 exit 27) BEFORE the events writer opens.
    */
  private def ensureOutputWritable(
      out: Path,
      workDir: Path
  ): ZIO[Any, RaiderError, Unit] =
    ZIO
      .attempt(Files.createDirectories(workDir))
      .mapError(t =>
        RaiderError.JournalFailure(
          s"output directory is not writable: $out (${String.valueOf(t)})"
        )
      )
      .unit

  private def newWorkDir(args: Args): Path =
    Paths.get(args.out.toString, s"work-${UUID.randomUUID().toString.take(8)}")

  // ------------------------------------------------------------------//
  // The launch                                                          //
  // ------------------------------------------------------------------//

  def run(args: Args, env: Map[String, String]): ZIO[Any, Nothing, Outcome] =
    run(args, env, newWorkDir(args))

  private def run(
      args: Args,
      env: Map[String, String],
      workDir: Path
  ): ZIO[Any, Nothing, Outcome] =
    ZIO.scoped:
      body(args, env, workDir).fold(
        err => Outcome(HeadlessRunner.exitCodeFor(err), args.out, Some(err)),
        _ => Outcome(ExitCodes.Success, args.out, None)
      )

  private def body(
      args: Args,
      env: Map[String, String],
      workDir: Path
  ): ZIO[Scope, RaiderError, Unit] =
    ZIO.scoped:
      for
        startedAt <- ZIO.succeed(Instant.now())
        root = RootId(s"pwf_${UUID.randomUUID().toString.take(8)}")
        state <- Ref.make(RunState())
        _ <- ensureOutputWritable(args.out, workDir)
        writer <- EventsJsonlWriter.open(workDir.resolve("events.jsonl"))
        seq <- Ref.make(0L)
        emit = (payload: EventPayload) =>
          for
            n <- seq.updateAndGet(_ + 1L)
            _ <- writer.append(envelope(n, root, payload))
          yield ()
        // Truthful artifacts on EVERY exit path (the success path writes them
        // in-flow below; this finalizer covers typed failures/interruption).
        // Terminal is emitted exactly once: in-flow on the dispatch paths,
        // here for preflight failures and interruption.
        _ <- ZIO.addFinalizer(
          state.get.flatMap { st =>
            writeArtifacts(workDir, root, startedAt, st)
              .fold(_ => (), _ => ())
              .catchAllDefect(_ => ZIO.unit) *>
              (if st.terminalEmitted then ZIO.unit
               else emit(EventPayload.Terminal(st.terminal))) *>
              writer.close
          }
        )
        _ <- emit(
          EventPayload
            .JobStatusChanged(JobStatus.Queued, JobStatus.Running, None)
        )
        _ <- flow(args, env, state, root, startedAt, workDir, emit)
          .onInterrupt(
            state.update(st =>
              if st.error.isEmpty && st.status != ExecutionStatus.Succeeded then
                st.copy(
                  status = ExecutionStatus.Interrupted,
                  terminal = JobStatus.Interrupted,
                  exitCode = ExitCodes.SigTerm
                )
              else st
            )
          )
      yield ()

  /** The launch flow: document gate → credentials → launch-layer assembly → THE
    * single dispatch → verdict projection → artifacts.
    */
  private def flow(
      args: Args,
      env: Map[String, String],
      state: Ref[RunState],
      root: RootId,
      startedAt: Instant,
      workDir: Path,
      emit: EventPayload => ZIO[Any, Nothing, Unit]
  ): ZIO[Scope, RaiderError, Unit] =
    for
      // ---- zero-dispatch document gate ----
      docBytes <- record(
        state,
        ZIO.fromEither(PortableDoc.readBytes(args.doc))
      )
      docDigest = PortableDoc.sha256Hex(docBytes)
      document <- record(
        state,
        ZIO.fromEither(
          PortableDoc.validate(docBytes).left.map { diag =>
            RaiderError.InputValidation(PortableDoc.diagnosticLine(diag))
          }
        )
      )
      declared <- record(
        state,
        ZIO.fromEither(PortableDoc.declaredLimits(document))
      )
      target <- record(state, ZIO.fromEither(outputTargetOf(document)))
      _ <- emit(
        EventPayload.Diagnostics(s"output target: ${target.pointer}")
      )
      _ <- state.update(
        _.copy(
          phase = Phase.DocValidated,
          docDigest = docDigest,
          declaredLimits = Some(declaredJson(declared)),
          tokensDeclared = declared.tokens
        )
      )
      _ <- emit(
        EventPayload.Diagnostics(
          s"portable document validated: sha256-${docDigest.take(12)}... " +
            s"(attempts=${declared.attempts}, tokens=${declared.tokens})"
        )
      )
      // ---- input is DATA (parsed here, executed never) ----
      rawInput <- record(state, readInput(args.input))
      inputJson <- record(state, parseInput(rawInput))
      _ <- state.update(
        _.copy(inputDigest =
          PortableDoc.sha256Hex(rawInput.getBytes(StandardCharsets.UTF_8))
        )
      )
      // ---- credential preflight BEFORE any construction (RAI-064) ----
      keyOpt <- record(
        state,
        ZIO.fromEither(Credentials.resolve(args.provider, env))
      )
      _ <- state.update(_.copy(phase = Phase.Credentials))
      // ---- launch-layer assembly (constructs; dispatches NOTHING) ----
      workspace <- record(state, validateWorkspace(args.workspace))
      backend0 <- record(
        state,
        ProviderBackend.make(args.provider, args.baseUrl.getOrElse(""), keyOpt)
      )
      tools <- record(
        state,
        CodingToolset
          .make(workspace.toString)
          .flatMap(st => ZIO.fromEither(st.registry))
      )
      effective <- record(
        state,
        ZIO.fromEither(
          PortableLaunchPolicy.intersect(
            raider.dsl.portable.PortableLaunchMetadata(
              maxAttempts = declared.attempts,
              maxParallelism = declared.parallelism,
              deadlineMs = declared.deadlineMs,
              maxTokens = declared.tokens,
              maxChildren = declared.children
            ),
            PortableLaunchPolicy.InvocationLimits(
              maxAttempts = args.attempts,
              deadlineSeconds = args.deadlineSeconds
            )
          )
        )
      )
      limits = effective.limits
      admission <- record(state, Admission.make(Right(limits)))
      tokenUsed <- ZIO.succeed(new AtomicLong(0L))
      backend = new TokenBudgetBackend(
        backend0,
        effective.tokenCeiling,
        tokenUsed
      )
      registry <- record(
        state,
        ZIO.fromEither(
          PortableTrustedRegistry.build(
            backend,
            tools,
            admission,
            limits,
            effective.maxRoundsPerAgent,
            args.model,
            workspace,
            root
          )
        )
      )
      lowered <- record(
        state,
        ZIO.fromEither(
          PortableWorkflowLowerer.lower(document, registry).left.map { diag =>
            RaiderError.InputValidation(PortableDoc.diagnosticLine(diag))
          }
        )
      )
      effectiveLine =
        s"maxLLM=1 tools=${limits.maxConcurrentTools} " +
          s"children=${limits.maxChildren} attempts=${limits.maxAttempts} " +
          s"roundsPerAgent=${effective.maxRoundsPerAgent} " +
          s"tokens<=${effective.tokenCeiling} " +
          // The ENFORCED outer bound: min(--deadline-s, declared deadlineMs).
          (effective.outerDeadline match
            case Some(d) => s"outerDeadlineS=${d.getSeconds}"
            case None    => s"outerDeadlineS=none")
      _ <- state.update(
        _.copy(
          phase = Phase.Assembled,
          effectiveLimits = Some(effectiveLine)
        )
      )
      _ <- emit(
        EventPayload.Diagnostics(s"effective launch limits: $effectiveLine")
      )
      _ <- state.update(_.copy(phase = Phase.Dispatched))
      // ---- THE single dispatch (the only place the program is applied) ----
      // The optional outer deadline is enforced at the launch boundary with
      // ZIO.timeoutFail (the frozen DeadlineExceeded mapping → exit 24); an
      // in-flight model attempt lost to it is honest accounting (no claim of
      // a completed spend).
      dispatchZio = effective.outerDeadline match
        case Some(d) =>
          Task
            .zio(lowered.program(inputJson))
            .timeoutFail(
              RaiderError.DeadlineExceeded(
                s"invocation deadline of ${d.getSeconds}s exceeded"
              )
            )(zio.Duration.fromSeconds(d.getSeconds))
        case None => Task.zio(lowered.program(inputJson))
      result <- dispatchZio.either
      _ <- ZIO.succeed(tokenUsed.get()).flatMap { observed =>
        state.update(_.copy(tokensObserved = observed))
      }
      _ <- result match
        case Left(err) =>
          val (status, terminal) = terminalStatusFor(err)
          state.update(
            _.copy(
              error = Some(err),
              status = status,
              terminal = terminal,
              exitCode = HeadlessRunner.exitCodeFor(err)
            )
          ) *> state
            .update(_.copy(terminalEmitted = true)) *> emit(
            EventPayload.Terminal(terminal)
          ) *> ZIO.fail(err)
        case Right(outputJson) =>
          record(
            state,
            ZIO.fromEither(projectOutput(outputJson))
          ).flatMap { projected =>
            state.update(
              _.copy(
                phase = Phase.Completed,
                status = ExecutionStatus.Succeeded,
                terminal = JobStatus.Succeeded,
                exitCode = ExitCodes.Success,
                output = Some(projected.json),
                // The verdict is claimed ONLY when the root output IS the
                // pinned check's passed result; data outputs claim nothing.
                verdictClaimed = projected.verdictPassed
              )
            ) *> state.update(
              _.copy(terminalEmitted = true)
            ) *> emit(EventPayload.Terminal(JobStatus.Succeeded)) *> state.get
              .flatMap { st =>
                // In-flow artifact write: a failure must fail the run (never
                // green without artifacts, ci-runtime §7).
                writeArtifacts(workDir, root, startedAt, st)
                  .mapError(t =>
                    RaiderError.JournalFailure(
                      s"cannot write run artifacts under $workDir: ${String.valueOf(t)}"
                    )
                  )
                  .flatMap { _ =>
                    state.update(_.copy(artifactsWritten = true))
                  }
              }
          }
    yield ()

  /** Records a typed failure into the run state before it propagates — with the
    * FULL honest terminal mapping (exit code and terminal job status), so
    * artifacts written by the finalizer never disagree with the process exit
    * code.
    */
  private def record[S, R](
      state: Ref[RunState],
      effect: ZIO[S, RaiderError, R]
  ): ZIO[S, RaiderError, R] =
    effect.tapError(err =>
      state.update { st =>
        val (status, terminal) = terminalStatusFor(err)
        st.copy(
          error = Some(err),
          status = status,
          terminal = terminal,
          exitCode = HeadlessRunner.exitCodeFor(err)
        )
      }
    )

  /** The output target EXACTLY as the lowerer resolves it (frozen corpus
    * parity): bindings first, then the verdict; when both are declared the
    * bindings target is the root and the launcher claims NO verdict — the
    * output flows as data and no green verdict is manufactured. Refusing the
    * ambiguity would reject valid frozen-corpus documents, so the launcher
    * mirrors the lowerer instead of inventing stricter semantics.
    */
  private def outputTargetOf(
      document: Json
  ): Either[RaiderError, PortableDoc.OutputTarget] =
    PortableDoc
      .outputTarget(document)
      .toRight(
        RaiderError.InputValidation(
          "document declares no output target (outputs.verdict or outputs.bindings)"
        )
      )

  /** The output projection (schema parity note: `outputs` REQUIRES both
    * bindings and verdict, so the root is always the bindings target and the
    * trusted verdict is observed through the ROOT OUTPUT VALUE): when the root
    * output carries the pinned check convention `status: passed`, the run may
    * claim the trusted verdict; any OTHER status value is an honest failed run
    * (RA-OUT) — a gate check that did not pass can never become a green run,
    * and the model cannot manufacture success. An output without a `status`
    * field is data (agent text under this launcher's pinned programs) and
    * succeeds with NO verdict claim.
    */
  private def projectOutput(output: Json): Either[RaiderError, Projected] =
    val status = output match
      case obj: Json.Obj =>
        obj.fields.collectFirst { case ("status", Json.Str(v)) => v }
      case _ => None
    status match
      case Some("passed") =>
        Right(Projected(output.toJson, verdictPassed = true))
      case Some(other) =>
        Left(
          RaiderError.OutputValidation(
            s"trusted verdict did not pass: status '$other'"
          )
        )
      case None => Right(Projected(output.toJson, verdictPassed = false))

  final case class Projected(json: String, verdictPassed: Boolean)

  private def validateWorkspace(
      workspace: Path
  ): ZIO[Any, RaiderError, Path] =
    ZIO
      .attempt {
        if !Files.isDirectory(workspace) then
          Left(
            RaiderError.Configuration(
              s"--workspace is not a directory: $workspace"
            )
          )
        else Right(workspace.toAbsolutePath.normalize)
      }
      .mapError(t =>
        RaiderError.Configuration(
          s"cannot inspect --workspace $workspace: ${String.valueOf(t)}"
        )
      )
      .flatMap {
        case Left(err) => ZIO.fail(err)
        case Right(p)  => ZIO.succeed(p)
      }

  private def readInput(input: Path): ZIO[Any, RaiderError, String] =
    ZIO
      .attempt {
        if !Files.isRegularFile(input) then
          Left(
            RaiderError.InputValidation(
              s"input is not a readable regular file: $input"
            )
          )
        else Right(Files.readString(input, StandardCharsets.UTF_8))
      }
      .mapError(t =>
        RaiderError.InputValidation(
          s"cannot read input $input: ${String.valueOf(t)}"
        )
      )
      .flatMap {
        case Left(err) => ZIO.fail(err)
        case Right(v)  => ZIO.succeed(v)
      }

  /** Input is DATA: parsed once, typed as an object (the document's `#/inputs`
    * pointers resolve against object fields), executed never.
    */
  private def parseInput(raw: String): ZIO[Any, RaiderError, Json] =
    ZIO.fromEither(
      Json.decoder.decodeJson(raw) match
        case Right(obj: Json.Obj) => Right(obj)
        case Right(_) =>
          Left(
            RaiderError.InputValidation(
              "portable input must be a JSON object (the document's #/inputs pointers resolve against it)"
            )
          )
        case Left(err) =>
          Left(RaiderError.InputValidation(s"input is not valid JSON: $err"))
    )

  private def declaredJson(d: PortableDoc.DeclaredLimits): Json =
    Json.Obj(
      "attempts" -> Json.Num(d.attempts.toLong),
      "parallelism" -> Json.Num(d.parallelism.toLong),
      "deadlineMs" -> Json.Num(d.deadlineMs),
      "tokens" -> Json.Num(d.tokens),
      "children" -> Json.Num(d.children.toLong)
    )

  private def envelope(
      seq: Long,
      root: RootId,
      payload: EventPayload
  ): EventEnvelope =
    EventEnvelope(
      schemaVersion = 1,
      seq = seq,
      jobId = JobId("workflow-run"),
      rootId = root,
      sessionId = SessionId(root.value),
      atMs = System.currentTimeMillis(),
      payload = payload
    )

  // ------------------------------------------------------------------//
  // Artifacts (ci-runtime §7 — run.json / summary.md; events.jsonl is    //
  // streamed by EventsJsonlWriter)                                       //
  // ------------------------------------------------------------------//

  private def writeArtifacts(
      workDir: Path,
      root: RootId,
      startedAt: Instant,
      st: RunState
  ): ZIO[Any, RaiderError, Unit] =
    ZIO.succeed(Instant.now()).flatMap { finishedAt =>
      val base = RunResult(
        schemaVersion = 1,
        rootId = root,
        status = st.status,
        exitCode = st.exitCode,
        taskVerdict =
          if st.status == ExecutionStatus.Succeeded && st.verdictClaimed then
            Some("trusted-checks-passed")
          else None,
        checks = st.error.map { err =>
          CheckResult(
            "portable-launch",
            CheckOutcome.Failed,
            s"${err.code}: ${err.detail.take(400)}"
          )
        }.toList,
        artifacts = List.empty,
        output = st.output.map(OutputPayload("raider/json", 1, _)),
        error = st.error,
        startedAt = startedAt.toString,
        finishedAt = finishedAt.toString
      )
      ZIO.attempt {
        Files.createDirectories(workDir)
        // run.json — validated BEFORE the write (the schema check result is
        // part of the document, same discipline as the compiled route).
        val schemaOk = RunResult.validate(base)
        val validated = schemaOk.fold(
          err =>
            base.copy(checks =
              base.checks :+ CheckResult(
                "run-result-schema",
                CheckOutcome.Failed,
                err.detail
              )
            ),
          _ =>
            base.copy(checks =
              base.checks :+ CheckResult(
                "run-result-schema",
                CheckOutcome.Passed,
                "validated pre-write"
              )
            )
        )
        writeAtomic(
          workDir.resolve("run.json"),
          splicePortable(validated.toJson, st)
        )
        writeAtomic(workDir.resolve("summary.md"), summaryMd(st, root))
        ()
      }.orDie
    }

  /** Additive splice of the portable provenance into run.json (the RAI-086
    * splice discipline: the frozen core schema is not extended): the document
    * and input digests, the declared vs effective limits and the observed token
    * accounting.
    */
  private def splicePortable(runJson: String, st: RunState): String =
    Json.decoder.decodeJson(runJson) match
      case Right(obj: Json.Obj) =>
        val extras = zio.Chunk(
          "docDigest" -> Json.Str("sha256-" + st.docDigest),
          "inputDigest" -> Json.Str("sha256-" + st.inputDigest)
        ) ++
          st.declaredLimits.map(j => "declaredLimits" -> j) ++
          st.effectiveLimits.map(s => "effectiveLimits" -> Json.Str(s)) ++
          (if st.tokensDeclared > 0 then
             zio.Chunk(
               "tokensDeclared" -> Json.Num(st.tokensDeclared),
               "tokensObserved" -> Json.Num(st.tokensObserved)
             )
           else zio.Chunk.empty)
        Json.Obj(obj.fields ++ extras).toJson
      case _ => runJson // unreachable: RunResult JSON is always an object

  private def writeAtomic(path: Path, content: String): Unit =
    val tmp = path.resolveSibling(path.getFileName.toString + ".tmp")
    Files.writeString(tmp, content, StandardCharsets.UTF_8)
    Files.move(
      tmp,
      path,
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE
    )

  /** Compact human report; the workflow output embeds model-derived text, so it
    * is rendered inside a tilde-fenced block as UNTRUSTED DATA (bounded, never
    * executed — ci-runtime §7 reporter discipline).
    */
  private def summaryMd(st: RunState, root: RootId): String =
    val sb = new StringBuilder
    sb.append("# portable workflow run ").append(root.value).append("\n\n")
    sb.append("outcome: **").append(st.status.toString).append("**")
    st.error.foreach(e =>
      sb.append(" — ").append(e.code).append(": ").append(e.detail.take(200))
    )
    sb.append("\n\n")
    sb.append("- document: sha256-").append(st.docDigest).append("\n")
    if st.inputDigest.nonEmpty then
      sb.append("- input: sha256-").append(st.inputDigest).append("\n")
    st.declaredLimits.foreach(l =>
      sb.append("- declared limits: ").append(l.toJson).append("\n")
    )
    st.effectiveLimits.foreach(l =>
      sb.append("- effective limits: ").append(l).append("\n")
    )
    if st.tokensDeclared > 0 then
      sb.append("- token accounting: observed ")
        .append(st.tokensObserved)
        .append(" of declared ")
        .append(st.tokensDeclared)
        .append("\n")
    sb.append(
      "- accounting: unpriced/unknown on this path (CostEstimate.Unknown); no cost is reported as zero\n"
    )
    st.output.foreach { r =>
      sb.append("\n## workflow output (untrusted data, verbatim, bounded)\n\n")
      sb.append("~~~~\n")
      sb.append(r.take(4000))
      if r.length > 4000 then sb.append("\n... [truncated]")
      sb.append("\n~~~~\n")
    }
    sb.result()

  /** Best-effort truthful incomplete marker for the adversarial case where the
    * bounded SIGTERM stop expires before the run's finalizers could write the
    * artifacts (ci-runtime §6: absence of a complete result always means
    * incomplete).
    */
  private def writeInterruptedMarker(out: Path, workDir: Path): Unit =
    try
      val stamp = java.time.format.DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss")
        .withZone(java.time.ZoneOffset.UTC)
        .format(Instant.now())
      val body =
        """{"schemaVersion":1,"kind":"portable-workflow-run-interrupted",""" +
          "\"status\":\"Interrupted\",\"exitCode\":" +
          ExitCodes.SigTerm +
          ",\"note\":\"SIGTERM: the bounded shutdown expired before the run" +
          " finalizers completed; the outcome is incomplete/uncertain and is" +
          " never retried automatically\"}"
      List(out, workDir).distinct.foreach(dir =>
        Files.writeString(
          dir.resolve(s"interrupted-$stamp.json"),
          body,
          StandardCharsets.UTF_8
        )
      )
      ()
    catch case NonFatal(_) => () // best-effort by contract

  // ------------------------------------------------------------------//
  // Console adapter (the ONLY blocking interpreter boundary)             //
  // ------------------------------------------------------------------//

  private val BoundedStopMillis = 5000L

  /** Runs one launch with the Main.scala SIGTERM discipline (the
    * [[raider.cli.workflow.WorkflowRun]] adapter pattern): the runner is a
    * daemon fiber; the shutdown hook interrupts it and waits AT MOST
    * [[BoundedStopMillis]] for the truthful-artifact finalizers, then RETURNS
    * (never System.exit inside the hook). The POSIX SIGTERM death yields the
    * 143 status; a normal completion marks `done` first.
    */
  def command(argv: List[String]): Int =
    argv match
      case "run" :: rest =>
        parse(rest) match
          case Left(err) =>
            System.err.println(s"raider ${err.code}: ${err.detail}")
            HeadlessRunner.exitCodeFor(err)
          case Right(args) =>
            Unsafe.unsafe { implicit u =>
              val runtime = zio.Runtime.default
              val done = new AtomicBoolean(false)
              val workDir = newWorkDir(args)
              // forkDaemon: the runner must outlive this Unsafe block.
              val fiber =
                runtime.unsafe
                  .run(run(args, sys.env, workDir).forkDaemon)
                  .getOrThrow()
              val hook = new Thread(
                () => {
                  runtime.unsafe
                    .run(fiber.interrupt.forkDaemon)
                    .getOrThrow()
                  val deadline =
                    System.currentTimeMillis() + BoundedStopMillis
                  while !done.get() && System.currentTimeMillis() < deadline do
                    Thread.sleep(50)
                  if !done.get() then
                    // The bounded stop expired before the finalizers
                    // completed: the truthful interrupted marker (the outcome
                    // stays incomplete/uncertain, never a green artifact).
                    writeInterruptedMarker(args.out, workDir)
                  // RETURN — never System.exit here.
                },
                "raider-portable-sigterm-stop"
              )
              Runtime.getRuntime.addShutdownHook(hook)
              val outcome: Outcome =
                try
                  val o = runtime.unsafe.run(fiber.join).getOrThrow()
                  done.set(true)
                  o
                catch
                  case _: InterruptedException =>
                    Outcome(ExitCodes.SigTerm, args.out, None)
                  case _: zio.FiberFailure =>
                    Outcome(ExitCodes.SigTerm, args.out, None)
              outcome.error.foreach(e =>
                System.err.println(s"raider ${e.code}: ${e.detail}")
              )
              System.out.println(
                s"""{"status":"${
                    if outcome.exitCode == 0 then "succeeded"
                    else "failed"
                  }","exitCode":${outcome.exitCode},""" +
                  s""""workDir":"${outcome.workDir}"}"""
              )
              System.out.flush()
              System.err.flush()
              val exit = outcome.exitCode
              // System.exit does not flush piped stdout: flushed above.
              System.exit(exit)
              exit // unreachable; types the Unsafe block as Int
            }
      case other =>
        System.err.println(
          s"raider RA-CFG: expected 'run' after 'workflow' on the portable route " +
            s"('--doc'), got '${other.mkString(" ")}'"
        )
        ExitCodes.ConfigInvalid

end PortableRun
