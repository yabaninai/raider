package raider.cli.workflow

import raider.cli.run.{Credentials, EventsJsonlWriter, HeadlessRunner}
import raider.core.{
  JobId,
  ModelBackend,
  RaiderError,
  RootId,
  SessionId,
  Task,
  ToolRegistry
}
import raider.core.result.{
  CheckOutcome,
  CheckResult,
  ExecutionStatus,
  RunResult
}
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.events.{EventEnvelope, EventPayload}
import raider.runtime.jobs.JobStatus
import raider.runtime.sdlc.approval.{
  ApprovalArtifacts,
  CheckpointDispatch,
  CheckpointOutcome
}
import raider.core.sdlc.SdlcEvidence.CandidateIdentity

import zio.json.EncoderOps
import zio.{Ref, Scope, ZIO, Unsafe}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal
import scala.jdk.CollectionConverters.*

/** `raider workflow run --classes <dir> --input <file> --out <dir> [--source
  * <file>]` (RAI-155.d; ADR-033 §7 — the reserved proposal names become real
  * commands here).
  *
  * NON-TTY launcher for a compiled chat-exported workflow (Option L, ci-runtime
  * §12): the same compiled launcher artifact a user runs LOCALLY as a plain
  * `main`, driven here through the existing headless discipline — preflight
  * order, ci-runtime §7 artifacts (run.json / summary.md, plus events.jsonl
  * lifecycle streaming), §8 stable exit codes, SIGTERM bounded stop, and a
  * fresh full policy/credential check PER INVOCATION (CWF-A03/D03: no approval
  * and no authority is inherited from the exporting chat or from a previous
  * run).
  *
  * ROUTE (recorded per ADR-029 §3.1/§7 and ADR-033 §7): CLI-internal assembly.
  * PKT-1 `WorkflowLaunch.make` does not exist in the runtime module (and a
  * CLI-local imitation of it would be a second admission shape). Instead this
  * launcher drives the compiled artifact's OWN public ExplicitAssembly surface
  * — the generated `buildBackend` factory plus the same public runtime parts
  * the generated `buildLaunch` assembles (CodingToolset rooted at the input
  * workspace, validated BudgetLimits, ONE Admission per run) — under the
  * ADR-029 D1/D3/D4 rules:
  *
  *   - explicit services, no hidden fallback; credentials resolved at EXECUTION
  *     time from the passed environment (production: `sys.env`, the identical
  *     source the generated launcher uses; the parameter is the RAI-064
  *     HeadlessRunner test seam), never from argv/chat/manifest;
  *   - construction validates and allocates but dispatches NOTHING (D1 rule 1;
  *     the .c sentinel probes prove the decode/construct path is
  *     zero-dispatch); the ONE dispatch is the workflow program itself;
  *   - one root/admission/ledger per invocation (D3): the generated agent stage
  *     creates its own loop root over the Launch built here; there is no second
  *     scheduler — the run is one ZIO flow on the caller's scope;
  *   - the ONLY blocking interpreter boundary is this console adapter's final
  *     join (D4), mirroring Main.scala.
  *
  * Launcher/input boundary (the .c review requirement): the input JSON is DATA.
  * It is decoded by the compiled `decodeOrRefuse` inside the String boundary
  * BEFORE any service construction; trusted checks execute ONLY the compiled
  * `trustedChecks` constants (check argv can never enter through this command's
  * argv, the input JSON, or the manifest — hostile extra input fields are
  * ignored by the versioned decoder and never interpreted as commands). stdin
  * is NEVER read (closed stdin is safe by construction).
  */
object WorkflowRun:

  final case class Args(
      classes: Path,
      input: Path,
      out: Path,
      source: Option[Path],
      checkpoint: Option[(String, String)] = None,
      decisionFile: Option[Path] = None,
      approvalRequest: Option[Path] = None
  )

  // ------------------------------------------------------------------//
  // Argv parsing                                                        //
  // ------------------------------------------------------------------//

  /** Parses the argv AFTER the `workflow run` prefix (stripped by the Main
    * routing, so run/chat flags can never be confused with launch flags).
    */
  def parse(argv: List[String]): Either[RaiderError, Args] =
    val flagsWithValue = Set(
      "--classes",
      "--input",
      "--out",
      "--source",
      "--checkpoint",
      "--decision-file",
      "--approval-request"
    )
    @scala.annotation.tailrec
    def loop(
        rest: List[String],
        acc: Map[String, String]
    ): Either[RaiderError, Args] = rest match
      case Nil => assemble(acc)
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
              "--classes <dir> --input <file> --out <dir> [--source <edited.scala>] [--checkpoint <id>:<scope> [--decision-file <json>]])"
          )
        )
    loop(argv, Map.empty)

  private def assemble(acc: Map[String, String]): Either[RaiderError, Args] =
    val checkpoint: Either[RaiderError, Option[(String, String)]] =
      acc.get("--checkpoint") match
        case None => Right(None)
        case Some(value) =>
          value.split(":", 2) match
            case Array(id, scope)
                if id.matches(
                  "[A-Za-z0-9][A-Za-z0-9._-]{0,127}"
                ) && scope.trim.nonEmpty =>
              Right(Some(id -> scope))
            case _ =>
              Left(
                RaiderError.Configuration("--checkpoint must be <id>:<scope>")
              )
    for
      classes <- requirePath(acc, "--classes")
      input <- requirePath(acc, "--input")
      out <- requirePath(acc, "--out")
      parsed <- checkpoint
      decision = acc.get("--decision-file").map(Paths.get(_))
      request = acc.get("--approval-request").map(Paths.get(_))
      _ <- Either.cond(
        !(decision.nonEmpty || request.nonEmpty) || parsed.nonEmpty,
        (),
        RaiderError.Configuration(
          "--decision-file and --approval-request require --checkpoint"
        )
      )
    yield Args(
      classes,
      input,
      out,
      acc.get("--source").map(Paths.get(_)),
      parsed,
      decision,
      request
    )

  private def requirePath(
      acc: Map[String, String],
      flag: String
  ): Either[RaiderError, Path] =
    acc.get(flag).filter(_.trim.nonEmpty) match
      case Some(v) => Right(Paths.get(v))
      case None    => Left(RaiderError.Configuration(s"$flag is required"))

  // ------------------------------------------------------------------//
  // Outcome                                                             //
  // ------------------------------------------------------------------//

  final case class Outcome(
      exitCode: Int,
      workDir: Path,
      error: Option[RaiderError]
  )

  // ------------------------------------------------------------------//
  // Run state (drives the truthful artifacts on every exit path)        //
  // ------------------------------------------------------------------//

  private enum Phase:

    case Preflight, Loaded, Credentials, Decoded, Assembled, Dispatched,
      Completed

  private final case class RunState(
      phase: Phase = Phase.Preflight,
      status: ExecutionStatus = ExecutionStatus.Failed,
      terminal: JobStatus.Terminal = JobStatus.Failed,
      exitCode: Int = raider.core.result.ExitCodes.RuntimeFailure,
      error: Option[RaiderError] = None,
      output: Option[String] = None,
      inputDigest: String = "",
      identity: Option[WorkflowBuildIdentity] = None,
      artifactsWritten: Boolean = false
  )

  /** Same provider mapping as the generated `credentialProvider`: the two
    * first-class transports of the artifact become the credential-provider
    * names of `raider.cli.run.Credentials`.
    */
  private def mapProvider(providerKind: String): Either[RaiderError, String] =
    providerKind match
      case "openai-compatible"    => Right("openai")
      case "anthropic-compatible" => Right("anthropic")
      case other =>
        Left(
          RaiderError.Configuration(
            s"unsupported providerKind '$other' in the compiled artifact; supported: openai-compatible, anthropic-compatible"
          )
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

  /** The TYPED output-dir probe (RAI-159): creating the run's work dir (and
    * with it the whole `--out` parent chain) IS the create+write probe — a
    * failure here maps to the honest [[RaiderError.JournalFailure]]
    * (RA-JOURNAL, §8 exit 27). This must run BEFORE [[EventsJsonlWriter.open]]:
    * the writer maps its own createDirectories/newBufferedWriter IO failures
    * through `.orDie`, so an unwritable parent `--out` used to escape as a raw
    * `AccessDeniedException` defect (subprocess exit 1 with a raw stack,
    * outside the §8 table) and shadow this typed mapping.
    */
  private def ensureOutputWritable(
      args: Args,
      workDir: Path
  ): ZIO[Any, RaiderError, Unit] =
    ZIO
      .attempt(Files.createDirectories(workDir))
      .mapError(t =>
        RaiderError.JournalFailure(
          s"output directory is not writable: ${args.out} (${String.valueOf(t)})"
        )
      )
      .unit

  // ------------------------------------------------------------------//
  // The launcher run                                                    //
  // ------------------------------------------------------------------//

  /** One headless launch. Never throws. `env` is injectable (the RAI-064
    * HeadlessRunner seam); production passes `sys.env`.
    */
  private def newWorkDir(args: Args): Path =
    Paths.get(args.out.toString, s"work-${UUID.randomUUID().toString.take(8)}")

  def run(args: Args, env: Map[String, String]): ZIO[Any, Nothing, Outcome] =
    run(args, env, newWorkDir(args))

  private def run(
      args: Args,
      env: Map[String, String],
      workDir: Path
  ): ZIO[Any, Nothing, Outcome] =
    checkpointOutcome(args) match
      case Some(Left(err)) =>
        ZIO.succeed(
          Outcome(HeadlessRunner.exitCodeFor(err), args.out, Some(err))
        )
      case Some(Right(false)) =>
        ZIO.succeed(
          Outcome(raider.core.result.ExitCodes.NeedsApproval, args.out, None)
        )
      case _ =>
        ZIO.scoped:
          body(args, env, workDir).fold(
            err =>
              Outcome(HeadlessRunner.exitCodeFor(err), args.out, Some(err)),
            _ => Outcome(raider.core.result.ExitCodes.Success, args.out, None)
          )

  /** Checkpoint policy is declared by the launcher and gates dispatch only. The
    * request/decision never supplies candidate or capability authority.
    */
  private def checkpointOutcome(
      args: Args
  ): Option[Either[RaiderError, Boolean]] =
    args.checkpoint.map { case (checkpointId, scope) =>
      val evaluated = for
        identity <- preflightBuild(args)
        input <- readInput(args.input)
        inputDigest = WorkflowBuildIdentity.sha256Hex(input)
        workspace <- workspaceFromInput(input)
        treeDigest <- digestWorkspace(workspace)
        policyDigest = WorkflowBuildIdentity.sha256Hex("workflow-run:" + scope)
        candidate = CandidateIdentity(
          workspace.toString,
          workspace.toString,
          None,
          Some(identity.classDigest),
          Some(identity.sourceDigest),
          treeDigest,
          inputDigest,
          identity.sourceDigest,
          policyDigest,
          WorkflowBuildIdentity.sha256Hex(identity.toolchain.toString)
        )
        _ <- CandidateIdentity
          .validate(candidate)
          .left
          .map(RaiderError.InputValidation.apply)
        requestPath = args.approvalRequest.getOrElse(
          args.out.resolve(s"approval-$checkpointId.json")
        )
        expiry <-
          if args.decisionFile.nonEmpty then
            try
              Right(
                Instant.parse(
                  ApprovalArtifacts
                    .decodeRequestArtifact(
                      Files.readString(requestPath, StandardCharsets.UTF_8)
                    )
                    .toOption
                    .get
                    .expiresAt
                )
              )
            catch
              case _: Throwable =>
                Left(
                  RaiderError.InputValidation(
                    "checkpoint request artifact is missing or invalid"
                  )
                )
          else Right(Instant.now().plusSeconds(3600))
        dispatch = CheckpointDispatch(
          checkpointId,
          candidate,
          expiry,
          requestPath,
          args.decisionFile,
          "workflow-run",
          () => {
            val currentIdentity = preflightBuild(args).left.map(_ =>
              ApprovalArtifacts.Refusal.CandidateMismatch
            )
            val currentInput = readInput(args.input).left.map(_ =>
              ApprovalArtifacts.Refusal.CandidateMismatch
            )
            val currentTree = digestWorkspace(workspace).left.map(_ =>
              ApprovalArtifacts.Refusal.CandidateMismatch
            )
            for
              freshIdentity <- currentIdentity
              freshInput <- currentInput
              freshTree <- currentTree
              _ <- Either.cond(
                freshIdentity == identity && WorkflowBuildIdentity.sha256Hex(
                  freshInput
                ) == inputDigest && freshTree == treeDigest,
                (),
                ApprovalArtifacts.Refusal.CandidateMismatch
              )
            yield ApprovalArtifacts.CurrentState(
              candidate,
              candidate.policyDigest,
              candidate.treeDigest,
              candidate.taskDigest,
              candidate.baseRevision,
              List(
                "compiled-workflow:" + identity.classDigest,
                "workspace:" + freshTree
              ),
              CandidateIdentity.digest(candidate),
              Set("workflow-run")
            )
          }
        )
      yield dispatch.evaluate() match
        case CheckpointOutcome.Authorized(_)    => Right(true)
        case CheckpointOutcome.NeedsApproval(_) => Right(false)
        case CheckpointOutcome.Refused(reason) =>
          Left(RaiderError.InputValidation(s"checkpoint refused: $reason"))
      evaluated.flatMap(identity)
    }

  private def workspaceFromInput(input: String): Either[RaiderError, Path] =
    import zio.json.ast.Json
    Json.decoder.decodeJson(input) match
      case Right(Json.Obj(fields)) =>
        fields.collectFirst { case ("workspace", Json.Str(value)) =>
          value
        } match
          case Some(value) if value.trim.nonEmpty =>
            Right(Paths.get(value).toAbsolutePath.normalize)
          case _ =>
            Left(
              RaiderError.InputValidation(
                "checkpoint requires a workspace string in workflow input"
              )
            )
      case _ =>
        Left(
          RaiderError.InputValidation(
            "checkpoint requires valid workflow input JSON"
          )
        )

  private def digestWorkspace(workspace: Path): Either[RaiderError, String] =
    try
      if !Files.isDirectory(workspace) then
        Left(
          RaiderError.InputValidation(
            s"checkpoint workspace is not a directory: $workspace"
          )
        )
      else
        val stream = Files.walk(workspace)
        val files =
          try
            stream
              .iterator()
              .asScala
              .toVector
              .filter(Files.isRegularFile(_))
              .sortBy(p => workspace.relativize(p).toString)
          finally stream.close()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        files.foreach { file =>
          val relative = workspace.relativize(file).toString
          val bytes = Files.readAllBytes(file)
          digest.update(
            (relative + "\n" + bytes.length.toString + "\n")
              .getBytes(StandardCharsets.UTF_8)
          )
          digest.update(bytes)
        }
        Right(digest.digest().map(b => f"${b & 0xff}%02x").mkString)
    catch
      case t: Throwable =>
        Left(
          RaiderError.InputValidation(
            s"cannot inspect checkpoint workspace: ${t.getClass.getSimpleName}"
          )
        )

  private def body(
      args: Args,
      env: Map[String, String],
      workDir: Path
  ): ZIO[Scope, RaiderError, Unit] =
    ZIO.scoped:
      for
        startedAt <- ZIO.succeed(Instant.now())
        root = RootId(s"wf_${UUID.randomUUID().toString.take(8)}")
        state <- Ref.make(RunState())
        // RAI-159 (UO-01): the typed output-dir probe runs BEFORE the events
        // writer opens — an unwritable parent --out now fails TYPED
        // (RA-JOURNAL, exit 27) with honest artifact absence (nothing was
        // created under --out), never as the writer's raw open defect.
        _ <- ensureOutputWritable(args, workDir)
        writer <- EventsJsonlWriter.open(workDir.resolve("events.jsonl"))
        seq <- Ref.make(0L)
        emit = (payload: EventPayload) =>
          for
            n <- seq.updateAndGet(_ + 1L)
            _ <- writer.append(envelope(n, root, payload))
          yield ()
        // Truthful artifacts on EVERY exit path. The SUCCESS path writes them
        // IN-FLOW below (a write failure there must fail the run — never
        // green without artifacts, ci-runtime §7); this finalizer covers
        // typed failures and bounded interruption (best-effort within the
        // shutdown hook's wait).
        _ <- ZIO.addFinalizer(
          state.get.flatMap { st =>
            val ensured =
              if st.status == ExecutionStatus.Succeeded && !st.artifactsWritten
              then st.copy(exitCode = raider.core.result.ExitCodes.Success)
              else st
            // Finalizers cannot fail: typed errors AND defects are swallowed
            // here (the success path already validated the write in-flow).
            writeArtifacts(workDir, root, startedAt, ensured)
              .fold(_ => (), _ => ())
              .catchAllDefect(_ => ZIO.unit) *> writer.close
          }
        )
        _ <- emit(
          EventPayload
            .JobStatusChanged(JobStatus.Queued, JobStatus.Running, None)
        )
        // The launch flow proper, with the SIGTERM interruption handler:
        // a bounded interrupt (Main.scala discipline) marks the run state
        // Interrupted BEFORE the finalizer writes the truthful incomplete
        // artifacts (exit 143 is then the process's POSIX death status).
        _ <- flow(args, env, state, root, startedAt, workDir, emit)
          .onInterrupt(
            state.update(st =>
              if st.error.isEmpty && st.status != ExecutionStatus.Succeeded then
                st.copy(
                  status = ExecutionStatus.Interrupted,
                  terminal = JobStatus.Interrupted,
                  exitCode = raider.core.result.ExitCodes.SigTerm
                )
              else st
            )
          )
      yield ()

  /** The launch flow: preflight → load → credential preflight → validated
    * decode → CLI-internal assembly → THE single dispatch → artifacts. Every
    * step is typed; the dispatch happens exactly once.
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
      // ---- preflight (before ANY load/dispatch; ci-runtime §12 order) ----
      identity <- record(state, ZIO.fromEither(preflightBuild(args)))
      rawInput <- record(state, ZIO.fromEither(readInput(args.input)))
      // RAI-159: re-check of the output dir already created in `body` (the
      // typed probe there now precedes the events-writer open). A failure
      // HERE is recorded, so the finalizer still writes truthful failed
      // artifacts and closes the writer (the TOCTOU case: the dir turned
      // unwritable after the body probe).
      _ <- record(state, ensureOutputWritable(args, workDir))
      _ <- state.update(
        _.copy(
          identity = Some(identity),
          inputDigest = WorkflowBuildIdentity.sha256Hex(rawInput)
        )
      )
      _ <- emit(
        EventPayload.Diagnostics(
          "build identity: source sha256-" + identity.sourceDigest.take(12) +
            "... classes sha256-" + identity.classDigest.take(12) + "..."
        )
      )
      // ---- load the compiled artifact (typed; no dispatch) ----
      module <- record(state, loadModule(args.classes, identity))
      _ <- state.update(_.copy(phase = Phase.Loaded))
      providerKind <- record(
        state,
        ZIO.fromEither(stringConstant(module, "providerKind"))
      )
      maxRounds <- record(
        state,
        ZIO.fromEither(intConstant(module, "maxRounds"))
      )
      // ---- credential preflight (RAI-064 discipline, BEFORE construction):
      //      a fresh resolution for THIS invocation — no inherited approval,
      //      no inherited authority (CWF-A03/D03) ----
      provider <- record(
        state,
        ZIO.fromEither(mapProvider(providerKind))
      )
      keyOpt <- record(
        state,
        ZIO.fromEither(Credentials.resolve(provider, env))
      )
      _ <- state.update(_.copy(phase = Phase.Credentials))
      // ---- validated input decoding INSIDE the String boundary (typed;
      //      zero dispatch — the .c sentinel probes prove this path) ----
      decoded <- record(
        state,
        ZIO
          .attempt(
            invokeTask(module, "decodeOrRefuse", rawInput)
              .asInstanceOf[Task[AnyRef]]
          )
          .mapError(t =>
            RaiderError.InputValidation(
              s"compiled artifact has no callable 'decodeOrRefuse(String)' member: ${String.valueOf(t)}"
            )
          )
          .flatMap(t => Task.zio(t))
      )
      _ <- state.update(_.copy(phase = Phase.Decoded))
      // ---- CLI-internal assembly over the artifact's OWN surface ----
      launch <- record(state, buildLaunch(module, decoded, keyOpt, maxRounds))
      _ <- state.update(_.copy(phase = Phase.Assembled))
      program <- record(state, loadWorkflow(module, launch))
      _ <- checkpointOutcome(args) match
        case None | Some(Right(true)) => ZIO.unit
        case Some(Left(err))          => ZIO.fail(err)
        case Some(Right(false)) =>
          ZIO.fail(
            RaiderError.InputValidation(
              "checkpoint policy requires approval before dispatch"
            )
          )
      _ <- state.update(_.copy(phase = Phase.Dispatched))
      result <- Task.zio(program(rawInput)).either
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
          ) *> emit(EventPayload.Terminal(terminal)) *> ZIO.fail(err)
        case Right(report) =>
          for
            _ <- state.update(
              _.copy(
                phase = Phase.Completed,
                status = ExecutionStatus.Succeeded,
                terminal = JobStatus.Succeeded,
                exitCode = raider.core.result.ExitCodes.Success,
                output = Some(report)
              )
            )
            _ <- emit(EventPayload.Terminal(JobStatus.Succeeded))
            // In-flow artifact write for the success path: a write failure
            // must fail the run (never green without artifacts).
            st <- state.get
            _ <- writeArtifacts(workDir, root, startedAt, st)
              .mapError(t =>
                RaiderError.JournalFailure(
                  s"cannot write run artifacts under $workDir: ${String.valueOf(t)}"
                )
              )
            _ <- state.update(_.copy(artifactsWritten = true))
          yield ()
    yield ()

  /** Records a typed failure into the run state before it propagates, so the
    * finalizer's artifacts name the real cause.
    */
  private def record[S, R](
      state: Ref[RunState],
      effect: ZIO[S, RaiderError, R]
  ): ZIO[S, RaiderError, R] =
    effect.tapError(err => state.update(_.copy(error = Some(err))))

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
  // Preflight (identity / staleness / input)                            //
  // ------------------------------------------------------------------//

  /** Build-identity preflight: the identity must load, the classes must still
    * hash to the recorded digest (partial write / corruption detection), and
    * when the edited source is supplied it must match the compiled source
    * digest (stale-build detection — a manual edit invalidates earlier result
    * claims, CWF-D02).
    */
  private def preflightBuild(
      args: Args
  ): Either[RaiderError, WorkflowBuildIdentity] =
    for
      identity <- WorkflowBuildIdentity.load(args.classes)
      _ <- WorkflowBuildIdentity.verifyClasses(identity, args.classes)
      _ <- args.source match
        case None => Right(())
        case Some(src) =>
          try
            val bytes = Files.readAllBytes(src)
            val actual = WorkflowBuildIdentity.sha256Hex(bytes)
            if actual == identity.sourceDigest then Right(())
            else
              Left(
                RaiderError.InputValidation(
                  s"stale build: $src hashes to sha256-$actual but the build in ${args.classes} " +
                    s"was compiled from sha256-${identity.sourceDigest}; recompile with 'raider compile'"
                )
              )
          catch
            case t: Throwable =>
              Left(
                RaiderError.InputValidation(
                  s"cannot read --source $src: ${String.valueOf(t)}"
                )
              )
    yield identity

  private def readInput(input: Path): Either[RaiderError, String] =
    try
      if !Files.isRegularFile(input) then
        Left(
          RaiderError.InputValidation(
            s"input is not a readable regular file: $input"
          )
        )
      else Right(Files.readString(input, StandardCharsets.UTF_8))
    catch
      case t: Throwable =>
        Left(
          RaiderError.InputValidation(
            s"cannot read input $input: ${String.valueOf(t)}"
          )
        )

  // ------------------------------------------------------------------//
  // Compiled-artifact loading and reflective invocation (by NAME — the   //
  // ChatWorkflowCompileGateSpec loaded-probe discipline)                 //
  // ------------------------------------------------------------------//

  private def loadModule(
      classesDir: Path,
      identity: WorkflowBuildIdentity
  ): ZIO[Scope, RaiderError, AnyRef] =
    ZIO
      .acquireRelease(
        ZIO
          .attempt {
            val loader = new java.net.URLClassLoader(
              // The compiled classes live under `<build>/classes` (the
              // raider compile layout); the identity names the module class.
              Array(classesDir.resolve("classes").toUri.toURL),
              getClass.getClassLoader
            )
            val cls = Class.forName(identity.moduleClass, true, loader)
            val mod = cls
              .getField("MODULE$")
              .get(null) // scalafix:ok DisableSyntax.null
            (loader, mod)
          }
          .mapError(t =>
            RaiderError.InputValidation(
              s"compiled artifact does not expose module ${identity.moduleClass}: ${String.valueOf(t)}"
            )
          )
      )(l => ZIO.succeed(l._1.close()))
      .map(_._2)

  private def member(module: AnyRef, name: String): java.lang.reflect.Method =
    val candidates =
      module.getClass.getDeclaredMethods.filter(_.getName == name)
    if candidates.isEmpty then
      throw new NoSuchMethodException(module.getClass.getName + "." + name)
    val m = candidates.head
    m.setAccessible(true)
    m

  private def stringConstant(
      module: AnyRef,
      name: String
  ): Either[RaiderError, String] =
    try Right(member(module, name).invoke(module).asInstanceOf[String])
    catch
      case t: Throwable =>
        Left(
          RaiderError.InputValidation(
            s"cannot read the editable constant '$name' from the compiled artifact: ${String.valueOf(t)}"
          )
        )

  private def intConstant(
      module: AnyRef,
      name: String
  ): Either[RaiderError, Int] =
    try
      Right(member(module, name).invoke(module).asInstanceOf[Integer].intValue)
    catch
      case t: Throwable =>
        Left(
          RaiderError.InputValidation(
            s"cannot read the editable constant '$name' from the compiled artifact: ${String.valueOf(t)}"
          )
        )

  private def invokeTask(
      module: AnyRef,
      name: String,
      arg: AnyRef
  ): Task[?] =
    member(module, name).invoke(module, arg).asInstanceOf[Task[?]]

  private def readStringField(input: AnyRef, name: String): String =
    member(input, name).invoke(input).asInstanceOf[String]

  // ------------------------------------------------------------------//
  // CLI-internal assembly (the artifact's OWN factory + runtime parts)  //
  // ------------------------------------------------------------------//

  private def buildLaunch(
      module: AnyRef,
      input: AnyRef,
      keyOpt: Option[String],
      maxRounds: Int
  ): ZIO[Any, RaiderError, AnyRef] =
    for
      // The generated factory (Option L): maps the two first-class transports
      // with typed auth failure and NO hidden fallback.
      backend <- ZIO
        .attempt(
          invokeTask(module, "buildBackend", keyOpt.asInstanceOf[AnyRef])
            .asInstanceOf[Task[ModelBackend]]
        )
        .mapError(t =>
          RaiderError.InputValidation(
            s"compiled artifact has no callable 'buildBackend(Option[String])' member: ${String.valueOf(t)}"
          )
        )
        .flatMap(t => Task.zio(t))
      workspace <- ZIO
        .attempt(readStringField(input, "workspace"))
        .mapError(t =>
          RaiderError.InputValidation(
            s"decoded input lacks 'workspace': ${String.valueOf(t)}"
          )
        )
      // Explicit services (ADR-029 D1 rule 2): coding tools rooted at the
      // input workspace, validated limits, ONE Admission for this run (D3).
      tools <- raider.tools.CodingToolset
        .make(workspace)
        .flatMap(st => ZIO.fromEither(st.registry))
      limits <- ZIO.fromEither(BudgetLimits.make(maxAttempts = maxRounds))
      admission <- Admission.make(Right(limits))
      launch <- ZIO
        .attempt {
          val loader = module.getClass.getClassLoader
          val launchCls =
            Class.forName(moduleClassOf(module) + "$Launch", true, loader)
          launchCls
            .getDeclaredConstructor(
              classOf[ModelBackend],
              classOf[ToolRegistry],
              classOf[Admission],
              classOf[BudgetLimits]
            )
            .newInstance(backend, tools, admission, limits)
        }
        .mapError(t =>
          RaiderError.InputValidation(
            s"compiled artifact lacks the Launch case class: ${String.valueOf(t)}"
          )
        )
    yield launch

  private def moduleClassOf(module: AnyRef): String =
    module.getClass.getName.stripSuffix("$")

  private def loadWorkflow(
      module: AnyRef,
      launch: AnyRef
  ): ZIO[Any, RaiderError, String => Task[String]] =
    ZIO
      .attempt {
        val program = member(module, "workflow")
          .invoke(module, launch)
          .asInstanceOf[raider.core.Program[String, String]]
        (raw: String) => program(raw)
      }
      .mapError(t =>
        RaiderError.InputValidation(
          s"compiled artifact has no callable 'workflow(Launch)' member: ${String.valueOf(t)}"
        )
      )

  // ------------------------------------------------------------------//
  // Artifacts (ci-runtime §7 — run.json / summary.md; events.jsonl is   //
  // streamed during the run by EventsJsonlWriter)                       //
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
          if st.status == ExecutionStatus.Succeeded then
            Some("trusted-checks-passed")
          else None,
        checks = artifactChecks(st),
        artifacts = List.empty,
        output = st.output.map(r =>
          raider.core.result.OutputPayload("raider/text", 1, r.toJson)
        ),
        error = st.error,
        startedAt = startedAt.toString,
        finishedAt = finishedAt.toString
      )
      ZIO.attempt {
        Files.createDirectories(workDir)
        // run.json — validated BEFORE the write (the schema check result is
        // part of the document, same discipline as HeadlessRunner).
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
          spliceIdentity(validated.toJson, st)
        )
        writeAtomic(workDir.resolve("summary.md"), summaryMd(st, root))
        ()
      }.orDie
    }

  private def artifactChecks(st: RunState): List[CheckResult] =
    st.identity.toList.map { id =>
      CheckResult(
        "build-identity-verified",
        CheckOutcome.Passed,
        s"classes match sha256-${id.classDigest.take(12)}... (source sha256-${id.sourceDigest.take(12)}...)"
      )
    } ++ st.error.map { err =>
      CheckResult(
        "workflow-launch",
        CheckOutcome.Failed,
        s"${err.code}: ${err.detail.take(400)}"
      )
    }

  /** Additive splice of the build identity + input digest into run.json (the
    * RAI-086 `ci`/`accounting` splice discipline: the frozen core schema is not
    * extended). This is what makes a manual source edit invalidate earlier
    * result claims (CWF-D02): every run artifact names the exact source it was
    * built from and the exact input it ran on.
    */
  private def spliceIdentity(runJson: String, st: RunState): String =
    import zio.json.ast.Json
    val extras: List[(String, Json)] =
      st.identity.toList
        .flatMap(i =>
          Json.decoder
            .decodeJson(i.toJson)
            .toOption
            .map(j => "buildIdentity" -> j)
        ) ++
        (if st.inputDigest.isEmpty then Nil
         else
           List(
             "inputDigest" -> Json.Str("sha256-" + st.inputDigest)
           ))
    if extras.isEmpty then runJson
    else
      Json.decoder.decodeJson(runJson) match
        case Right(Json.Obj(fields)) =>
          Json.Obj(fields ++ zio.Chunk.fromIterable(extras)).toJson
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

  /** Compact human report. The workflow report embeds model-derived text, so it
    * is rendered inside a tilde-fenced block as UNTRUSTED DATA (bounded, never
    * executed — ci-runtime §7 reporter discipline).
    */
  private def summaryMd(st: RunState, root: RootId): String =
    val sb = new StringBuilder
    sb.append("# workflow run ").append(root.value).append("\n\n")
    sb.append("outcome: **").append(st.status.toString).append("**")
    st.error.foreach(e =>
      sb.append(" — ").append(e.code).append(": ").append(e.detail.take(200))
    )
    sb.append("\n\n")
    st.identity.foreach { id =>
      sb.append("- source: sha256-").append(id.sourceDigest).append("\n")
      sb.append("- classes: sha256-").append(id.classDigest).append("\n")
    }
    if st.inputDigest.nonEmpty then
      sb.append("- input: sha256-").append(st.inputDigest).append("\n")
    sb.append(
      "- accounting: unpriced/unknown on this path (CostEstimate.Unknown); no cost is reported as zero\n"
    )
    st.output.foreach { r =>
      sb.append("\n## workflow report (untrusted data, verbatim, bounded)\n\n")
      sb.append("~~~~\n")
      sb.append(r.take(4000))
      if r.length > 4000 then sb.append("\n... [truncated]")
      sb.append("\n~~~~\n")
    }
    sb.result()

  /** Best-effort truthful incomplete marker for the adversarial case where the
    * bounded SIGTERM stop expires before the run's own finalizers could write
    * the artifacts (ci-runtime §6: interrupted outcomes are saved best-effort;
    * absence of a complete result always means incomplete).
    */
  private def writeInterruptedMarker(out: Path, workDir: Path): Unit =
    try
      val stamp = java.time.format.DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss")
        .withZone(java.time.ZoneOffset.UTC)
        .format(Instant.now())
      val body =
        """{"schemaVersion":1,"kind":"chat-workflow-run-interrupted",""" +
          "\"status\":\"Interrupted\",\"exitCode\":" +
          raider.core.result.ExitCodes.SigTerm +
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
  // Console adapter (the ONLY blocking interpreter boundary)            //
  // ------------------------------------------------------------------//

  private val BoundedStopMillis = 5000L

  /** Runs one launch with the Main.scala SIGTERM discipline: the runner is a
    * daemon fiber; the shutdown hook interrupts it and waits AT MOST
    * [[BoundedStopMillis]] for the truthful-artifact finalizers, then RETURNS
    * (never System.exit inside the hook — Shutdown.runHooks deadlocks; found by
    * the RAI-022 probe). The POSIX SIGTERM death yields the 143 status; a
    * normal completion marks `done` first.
    */
  def command(argv: List[String]): Int =
    // The `workflow` token is stripped by Main; `run` is the only subcommand.
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
                  runtime.unsafe.run(fiber.interrupt.forkDaemon).getOrThrow()
                  val deadline = System.currentTimeMillis() + BoundedStopMillis
                  while !done.get() && System.currentTimeMillis() < deadline do
                    Thread.sleep(50)
                  if !done.get() then
                    // The bounded stop expired before the run finalizers
                    // completed (e.g. the loop's bounded transport retry
                    // re-entered a hanging provider call): write the
                    // truthful interrupted marker best-effort — the outcome
                    // stays incomplete/uncertain, never a green artifact.
                    writeInterruptedMarker(args.out, workDir)
                  // RETURN — never System.exit here (Shutdown.runHooks
                  // deadlocks; found by the RAI-022 probe).
                },
                "raider-workflow-sigterm-stop"
              )
              Runtime.getRuntime.addShutdownHook(hook)
              val outcome: Outcome =
                try
                  val o = runtime.unsafe.run(fiber.join).getOrThrow()
                  done.set(true)
                  o
                catch
                  case _: InterruptedException =>
                    // An interrupted join is not evidence that the runner's
                    // finalizers have completed. Keep the shutdown hook's
                    // bounded wait active so it can either observe cleanup or
                    // publish the truthful interruption marker.
                    Outcome(
                      raider.core.result.ExitCodes.SigTerm,
                      args.out,
                      None
                    )
                  case _: zio.FiberFailure =>
                    // As above, do not let the join-side signal suppress the
                    // hook's marker before artifact finalization has settled.
                    Outcome(
                      raider.core.result.ExitCodes.SigTerm,
                      args.out,
                      None
                    )
              outcome.error.foreach(e =>
                System.err.println(s"raider ${e.code}: ${e.detail}")
              )
              System.out.println(
                s"""{"status":"${
                    if outcome.exitCode == 0 then "succeeded"
                    else if outcome.exitCode == raider.core.result.ExitCodes.NeedsApproval
                    then "needs-approval"
                    else "failed"
                  },"exitCode":${outcome.exitCode},""" +
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
          s"raider RA-CFG: unknown workflow subcommand '${other.mkString(" ")}' (only 'run' exists)"
        )
        raider.core.result.ExitCodes.ConfigInvalid

end WorkflowRun
