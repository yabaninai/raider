package raider.cli.processapi

import raider.core.result.{
  CheckOutcome,
  CheckResult,
  ExecutionStatus,
  ExitCodes,
  OutputPayload,
  RunResult
}
import raider.core.{RaiderError, RootId}
import raider.cli.processapi.Protocol.{Observation, ProtocolFailure}
import raider.cli.run.{EventsJsonlWriter, HeadlessRunner}
import raider.runtime.budget.UsageReport
import raider.runtime.events.{EventEnvelope, EventHub, EventPayload}
import raider.runtime.jobs.{JobManager, StatusLedger}
import zio.json.EncoderOps
import zio.json.ast.Json
import zio.{Clock, Duration, IO, Promise, Ref, Scope, UIO, Unsafe, ZIO}

import java.io.{FileDescriptor, FileOutputStream, IOException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** RAI-201 session core (ADR-037 §5-§6): one JSON-lines stdio session owning at
  * most one runtime root. Reader on stdin → frame dispatch; receipts and
  * observations through [[Outbound]]; settle-once machinery producing exactly
  * one terminal frame per root and a stable exit code. Signal handling: the JVM
  * shutdown hook fires [[SignalBridge]] (it must never call System.exit —
  * Main.scala hook discipline); the session turns SIGTERM into a graceful
  * bounded stop with exit 143.
  */
object Session:

  final case class Config(
      launch: RootRun.LaunchConfig,
      outDir: Path,
      stallBound: Duration = Duration.fromSeconds(30)
  )

  private enum Phase:
    case AwaitingHello, Active, Running, Settling

  private enum SettleMode(val reason: String):
    case Natural extends SettleMode("run completed")
    case ClientCancelled extends SettleMode("cancel requested by client")

    case EofAfterDispatch
        extends SettleMode("stdin closed while the run was live; run cancelled")

    case ProtocolFatal(msg: String)
        extends SettleMode(s"protocol failure: $msg")

    case PeerDead(why: String) extends SettleMode(s"stdout channel died: $why")
    case Sigterm extends SettleMode("SIGTERM received; bounded stop")
    case EofClean extends SettleMode("stdin closed with no owned run")

  private final case class SessionState(
      phase: Phase,
      run: Option[Running],
      protocolFailed: Boolean,
      settleStarted: Boolean
  )

  private object SessionState:

    val initial: SessionState =
      SessionState(Phase.AwaitingHello, None, false, false)

  private final case class Running(
      started: RootRun.Started,
      requestId: String
  )

  private val MaxRetention = 1024
  private val CancelAwaitBound: Duration = Duration.fromSeconds(60)

  /** Bounded window a naturally completing run gets to finish relaying its
    * trailing events (events.jsonl journal + live observations) before the
    * terminal frame is written. The hub subscription stream does not end on job
    * completion, so the window always elapses; it only needs to cover the drain
    * of the subscriber queue's tail, which is millisecond-scale.
    */
  private val ForwarderDrainBound: Duration = Duration.fromSeconds(1)

  /** Installed by ProcessApiCommand's JVM shutdown hook; fired from a non-ZIO
    * thread, so it forks the action daemon-style.
    */
  final class SignalBridge private (ref: AtomicReference[Option[UIO[Unit]]]):
    def install(action: UIO[Unit]): Unit = ref.set(Some(action))

    def fire(): Unit =
      ref.getAndSet(None).foreach { action =>
        Unsafe.unsafe { implicit unsafe =>
          zio.Runtime.default.unsafe.run(action.forkDaemon)
        }
      }

  object SignalBridge:

    def make: UIO[SignalBridge] =
      ZIO.succeed(
        new SignalBridge(new AtomicReference[Option[UIO[Unit]]](None))
      )

  /** Runs one session to completion; returns the process exit code. Internal
    * defects (never expected) settle at the adapter's contract exit 70.
    */
  def run(cfg: Config, bridge: SignalBridge): ZIO[Any, Nothing, Int] =
    ZIO
      .scoped(session(cfg, bridge))
      .catchAllDefect(_ => ZIO.succeed(AdapterExitCode.InternalDefect))

  /** All outbound bytes go to fd 1 (FileOutputStream on FileDescriptor.out —
    * NOT PrintStream, whose buffering can reorder frames against System.err).
    * Frames are LF-delimited on the wire (ADR-037 §3): the newline is part of
    * every frame this sink emits, never optional.
    */
  private def stdoutSink(
      stdout: FileOutputStream
  ): Json => IO[Outbound.ChannelDead, Unit] =
    json =>
      ZIO
        .attemptBlocking {
          val bytes = json.toJson.getBytes(UTF_8) :+ '\n'.toByte
          stdout.write(bytes)
          stdout.flush()
        }
        .refineOrDie { case e: IOException =>
          Outbound.ChannelDead(
            Protocol
              .bounded(Option(e.getMessage).getOrElse("stdout write failed"))
          )
        }

  private def session(
      cfg: Config,
      bridge: SignalBridge
  ): ZIO[Scope, Nothing, Int] =
    for
      startedAt <- Clock.instant
      root = RootId(s"proc_${UUID.randomUUID().toString.take(8)}")
      workDir = cfg.outDir.toAbsolutePath.resolve(
        s"invocation-${UUID.randomUUID().toString.take(8)}"
      )
      _ <- ZIO.attempt(Files.createDirectories(workDir)).orDie
      hub <- EventHub.make()
      writer <- EventsJsonlWriter.open(workDir.resolve("events.jsonl"))
      _ <- ZIO.addFinalizer(writer.close)
      ledger = raider.runtime.jobs.StatusLedger.onTransition(hub, root)
      manager <- JobManager.make(onTransition = Some(ledger))
      retention <- Ref.make(Vector.empty[EventEnvelope])
      outcomeRef <- Ref.make(Option.empty[Either[RaiderError, String]])
      usageRef <- Ref.make(Option.empty[UsageReport])
      state <- Ref.make(SessionState.initial)
      exitDone <- Promise.make[Nothing, Int]
      artifactErrorRef <- Ref.make(Option.empty[String])
      stdout <- ZIO.succeed(new FileOutputStream(FileDescriptor.out))
      outbound <- Outbound.make(stdoutSink(stdout), cfg.stallBound)
      core = new Core(
        cfg,
        root,
        workDir,
        hub,
        manager,
        writer,
        retention,
        outcomeRef,
        usageRef,
        state,
        exitDone,
        outbound,
        artifactErrorRef,
        startedAt
      )
      _ <- ZIO.succeed(bridge.install(core.requestSigterm))
      _ <- outbound.writer.forkScoped
      _ <- core.deathWatcher.forkScoped
      _ <- core.readLoop(new Framing.LineAssembler).forkScoped
      code <- exitDone.await
    yield code

  private final class Core(
      cfg: Config,
      root: RootId,
      workDir: Path,
      hub: EventHub,
      manager: JobManager,
      writer: EventsJsonlWriter,
      retention: Ref[Vector[EventEnvelope]],
      outcomeRef: Ref[Option[Either[RaiderError, String]]],
      usageRef: Ref[Option[UsageReport]],
      state: Ref[SessionState],
      exitDone: Promise[Nothing, Int],
      outbound: Outbound,
      artifactErrorRef: Ref[Option[String]],
      startedAt: Instant
  ):

    // ------------------------------------------------------------------
    // stdin reading
    // ------------------------------------------------------------------

    def readLoop(assembler: Framing.LineAssembler): ZIO[Scope, Nothing, Unit] =
      ZIO.suspendSucceed {
        readChunk.flatMap {
          case None => onEof(assembler)
          case Some(chunk) =>
            dispatchAll(assembler.feed(chunk), assembler)
        }
      }

    /** Any stdin read error is EOF semantics: the peer is gone (or broken). The
      * bounded-stop path that follows decides the honest verdict.
      */
    private def readChunk: UIO[Option[Array[Byte]]] =
      ZIO
        .attemptBlocking {
          val buffer = new Array[Byte](16384)
          val n = System.in.read(buffer)
          if n < 0 then None
          else Some(java.util.Arrays.copyOf(buffer, n))
        }
        .fold(_ => None, identity)

    private def dispatchAll(
        results: Vector[Either[ProtocolFailure, Json]],
        assembler: Framing.LineAssembler
    ): ZIO[Scope, Nothing, Unit] =
      ZIO.suspendSucceed {
        Ref.make(true).flatMap { keepReading =>
          ZIO
            .foreach(results) { result =>
              keepReading.get.flatMap {
                case false => ZIO.unit
                case true =>
                  dispatch(result).flatMap {
                    case true  => ZIO.unit
                    case false => keepReading.set(false)
                  }
              }
            }
            .unit *> keepReading.get.flatMap {
            case true  => readLoop(assembler)
            case false => ZIO.unit
          }
        }
      }

    private def dispatch(
        result: Either[ProtocolFailure, Json]
    ): ZIO[Scope, Nothing, Boolean] =
      result match
        case Left(failure) => dispatchFailure(failure, None)
        case Right(json) =>
          ZIO.succeed(Protocol.decode(json)).flatMap {
            case Left(failure) =>
              dispatchFailure(failure, Protocol.correlation(json))
            case Right(frame) => handleFrame(frame)
          }

    private def dispatchFailure(
        failure: ProtocolFailure,
        correlation: Option[String]
    ): ZIO[Scope, Nothing, Boolean] =
      replyError(failure, correlation) *>
        (if failure.fatal then
           state.update(_.copy(protocolFailed = true)) *>
             beginSettle(SettleMode.ProtocolFatal(failure.message)).as(false)
         else state.get.map(s => !s.settleStarted))

    private def handleFrame(
        frame: Protocol.ClientFrame
    ): ZIO[Scope, Nothing, Boolean] =
      state.get.flatMap { s =>
        frame match
          case h: Protocol.Hello     => hello(h, s)
          case sub: Protocol.Submit  => submit(sub, s)
          case obs: Protocol.Observe => observe(obs)
          case c: Protocol.Cancel    => cancel(c, s)
      }

    private def hello(
        h: Protocol.Hello,
        s: SessionState
    ): ZIO[Scope, Nothing, Boolean] =
      s.phase match
        case Phase.AwaitingHello =>
          h.requiredCapabilities.find(cap =>
            !Protocol.SupportedCapabilities.contains(cap)
          ) match
            case Some(cap) =>
              replyError(
                Protocol.UnsupportedCapability(cap),
                Some(h.requestId)
              ) *>
                beginSettle(
                  SettleMode.ProtocolFatal(s"unsupported capability '$cap'")
                ).as(false)
            case None =>
              outbound
                .receipt(Protocol.helloAck(h.requestId))
                .catchAll(_ => ZIO.unit) *>
                state.update(_.copy(phase = Phase.Active)).as(true)
        case _ =>
          replyError(Protocol.HelloAlreadyNegotiated, Some(h.requestId))
            .as(true)

    private def submit(
        sub: Protocol.Submit,
        s: SessionState
    ): ZIO[Scope, Nothing, Boolean] =
      // Duplicate detection is OWNERSHIP-keyed, not phase-keyed
      // (contracts/process-api/README.md — submit while a root is owned:
      // bounded `duplicate_submit` refusal). A natural settle flips the phase
      // to Settling while the invocation still owns its root, so a duplicate
      // submit racing that transition must still get the typed
      // `duplicate_submit` answer instead of the input-validation error.
      // `input_rejected` stays reserved for its typed causes (pre-dispatch
      // validation on a session that owns no root).
      if s.run.isDefined then
        replyError(Protocol.DuplicateSubmit, Some(sub.requestId)).as(true)
      else
        s.phase match
          case Phase.AwaitingHello =>
            replyError(Protocol.HandshakeRequired, Some(sub.requestId)).as(true)
          case Phase.Active =>
            Protocol.validateSubmitInput(sub.input) match
              case Left(err)   => replyError(err, Some(sub.requestId)).as(true)
              case Right(task) => startRoot(sub.requestId, task).as(true)
          // Running/Settling without an owned root cannot occur (the phase
          // only leaves Active together with a started root); keep the
          // bounded refusal as the honest fallback.
          case _ =>
            replyError(
              Protocol.InputRejected("session is settling; submit refused"),
              Some(sub.requestId)
            ).as(true)

    private def startRoot(
        requestId: String,
        task: String
    ): ZIO[Scope, Nothing, Unit] =
      RootRun
        .start(cfg.launch, hub, manager, writer, root, task)(forward(requestId))
        .flatMap { started =>
          // ADR-037 §4: a successfully started root is acknowledged with an
          // `accepted` frame carrying the rootId. No strict FIFO guarantee
          // ties this receipt to event observations — an observation with
          // sequence 1 can be forwarded before the accepted frame lands — so
          // consumers correlate frames by requestId, not by arrival order.
          outbound
            .receipt(Protocol.accepted(requestId, root.value))
            .catchAll(_ => ZIO.unit) *>
            state
              .update(
                _.copy(
                  phase = Phase.Running,
                  run = Some(Running(started, requestId))
                )
              ) *>
            manager
              .await(started.handle)
              .either
              .flatMap { outcome =>
                outcomeRef.set(Some(outcome)) *>
                  started.admission.budgetLedger.usage
                    .flatMap(u => usageRef.set(Some(u))) *>
                  beginSettle(SettleMode.Natural)
              }
              .forkDaemon
              .unit
        }
        .catchAll { err =>
          replyError(
            Protocol.PreflightFailed(
              wire(Option(err.getMessage).getOrElse("launch refused"))
            ),
            Some(requestId)
          )
        }

    private def observe(obs: Protocol.Observe): ZIO[Scope, Nothing, Boolean] =
      retention.get.flatMap { kept =>
        val missed = kept.filter(_.seq > obs.afterSequence)
        if missed.isEmpty then
          replyError(
            Protocol.ObserveFailed(
              s"no observations retained after sequence ${obs.afterSequence}"
            ),
            Some(obs.requestId)
          ).as(true)
        else
          ZIO
            .foreachDiscard(missed) { env =>
              outbound.receipt(
                Protocol.observation(
                  obs.requestId,
                  Observation(
                    env.seq,
                    kindOf(env.payload),
                    messageOf(env.payload),
                    None
                  )
                )
              )
            }
            .catchAll(_ => ZIO.unit)
            .as(true)
      }

    private def cancel(
        c: Protocol.Cancel,
        s: SessionState
    ): ZIO[Scope, Nothing, Boolean] =
      s.run match
        case None =>
          outbound
            .receipt(Protocol.cancelAck(c.requestId, "not_found"))
            .catchAll(_ => ZIO.unit)
            .as(true)
        case Some(run) =>
          if s.settleStarted then
            outbound
              .receipt(Protocol.cancelAck(c.requestId, "already_terminal"))
              .catchAll(_ => ZIO.unit)
              .as(true)
          else
            manager.cancel(run.started.handle).flatMap {
              case true =>
                outbound
                  .receipt(Protocol.cancelAck(c.requestId, "accepted"))
                  .catchAll(_ => ZIO.unit) *>
                  beginSettle(SettleMode.ClientCancelled).as(true)
              case false =>
                outbound
                  .receipt(Protocol.cancelAck(c.requestId, "already_terminal"))
                  .catchAll(_ => ZIO.unit)
                  .as(true)
            }

    private def replyError(
        failure: ProtocolFailure,
        requestId: Option[String]
    ): UIO[Unit] =
      outbound
        .receipt(Protocol.error(requestId, failure))
        .catchAll(_ => ZIO.unit)

    private def onEof(
        assembler: Framing.LineAssembler
    ): ZIO[Scope, Nothing, Unit] =
      assembler.finish() match
        case Some(failure) => dispatchFailure(failure, None).unit
        case None =>
          state.get.flatMap { s =>
            s.phase match
              case Phase.Running  => beginSettle(SettleMode.EofAfterDispatch)
              case Phase.Settling => ZIO.unit
              case _              => beginSettle(SettleMode.EofClean)
          }

    // ------------------------------------------------------------------
    // settle machinery
    // ------------------------------------------------------------------

    def requestSigterm: UIO[Unit] = beginSettle(SettleMode.Sigterm)

    def deathWatcher: UIO[Unit] =
      outbound.channelDeath.flatMap(dead =>
        beginSettle(SettleMode.PeerDead(dead.reason))
      )

    /** Settles at most once; later requests are absorbed (SIGTERM racing a
      * natural completion, cancellation racing EOF, etc.). The first claimer
      * records its mode; absorb attempts keep the winner's verdict.
      */
    @volatile private var requestedMode: SettleMode = SettleMode.Natural

    private def beginSettle(mode: SettleMode): UIO[Unit] =
      state
        .modify { s =>
          val claimed = !s.settleStarted
          (claimed, s.copy(phase = Phase.Settling, settleStarted = true))
        }
        .flatMap {
          case true  => ZIO.succeed { requestedMode = mode } *> settle
          case false => ZIO.unit
        }

    private def settle: UIO[Unit] =
      state.get.flatMap { s =>
        s.run match
          case Some(run) =>
            settleWithRun(
              mode = requestedMode,
              run = run,
              protocolFailed = s.protocolFailed
            )
          case None => settleWithoutRun
      }

    private def settleWithoutRun: UIO[Unit] =
      for
        _ <- outbound.closeInput()
        _ <- outbound.terminalWrittenOrDone.unit
        exit = requestedMode match
          case SettleMode.ProtocolFatal(_) => ExitCodes.RuntimeFailure
          case SettleMode.Sigterm          => ExitCodes.SigTerm
          case SettleMode.PeerDead(_)      => ExitCodes.Uncertain
          case _                           => ExitCodes.Success
        _ <- noteSettled(exit)
        // The note is emitted BEFORE the exit is released: `exitDone.succeed`
        // lets the main fiber close the scope and exit the JVM, which would
        // otherwise race (and under load, lose against) this diagnostic write.
        _ <- exitDone.succeed(exit)
      yield ()

    private def settleWithRun(
        mode: SettleMode,
        run: Running,
        protocolFailed: Boolean
    ): UIO[Unit] =
      for
        // A natural completion lets the event forwarder finish relaying the
        // run's trailing events (journal + observations) before the terminal
        // frame; cancelled/interrupted/failed-settle runs stop streaming
        // immediately.
        _ <- mode match
          case SettleMode.Natural =>
            run.started.forwarder.await
              .timeout(ForwarderDrainBound)
              .catchAllDefect(_ => ZIO.unit)
              .unit
          case _ => ZIO.unit
        _ <- run.started.forwarder.interrupt.forkDaemon.unit
        outcomeOpt <- mode match
          case SettleMode.Natural =>
            outcomeRef.get.flatMap {
              case Some(o) => ZIO.succeed(Some(o))
              case None    => awaitBounded(run)
            }
          case _ =>
            manager.cancel(run.started.handle).unit *> awaitBounded(run)
        _ <- run.started.admission.budgetLedger.usage.flatMap(u =>
          usageRef.set(Some(u))
        )
        usage <- usageRef.get
        finishedAt <- Clock.instant
        base = buildRunResult(mode, outcomeOpt, usage, finishedAt)
        checked = appendSchemaCheck(base, mode)
        eventsLines <- writer.written
        written <- Artifacts
          .write(workDir, checked, resultPayload(outcomeOpt), eventsLines)
          .map(Some(_): Option[Artifacts.Written])
          .catchAllDefect { d =>
            artifactErrorRef.set(
              Some(
                Protocol
                  .bounded(Option(d.getMessage).getOrElse(d.getClass.getName))
              )
            ) *> ZIO.succeed(Option.empty[Artifacts.Written])
          }
        _ <- outbound.flushGap(run.requestId).catchAll(_ => ZIO.unit)
        artifactError <- artifactErrorRef.get
        _ <- outbound.terminal(
          Protocol.terminal(
            run.requestId,
            root.value,
            terminalBody(
              mode,
              checked,
              protocolFailed,
              usage,
              written,
              artifactError
            )
          )
        )
        exit = computeExit(mode, checked)
        channelOk <- outbound.terminalWrittenOrDone
        finalExit =
          if !channelOk && exit == ExitCodes.Success then ExitCodes.Uncertain
          else exit
        _ <- noteSettled(finalExit)
        // Same ordering as settleWithoutRun: the diagnostic must be on
        // stderr before the exit code is released to the main fiber.
        _ <- exitDone.succeed(finalExit)
      yield ()

    private def awaitBounded(
        run: Running
    ): UIO[Option[Either[RaiderError, String]]] =
      manager
        .await(run.started.handle)
        .either
        .timeout(CancelAwaitBound)
        .map {
          case None =>
            Some(
              Left(
                RaiderError.ToolOutcomeUncertain(
                  "bounded cleanup window elapsed while awaiting the stopped run"
                )
              )
            )
          case Some(o) => Some(o)
        }

    private def buildRunResult(
        mode: SettleMode,
        outcomeOpt: Option[Either[RaiderError, String]],
        usage: Option[UsageReport],
        finishedAt: Instant
    ): RunResult =
      val (status, exitCode, verdict, errorOpt, outputOpt) =
        mode match
          case SettleMode.Natural =>
            outcomeOpt match
              case Some(Right(text)) =>
                (
                  ExecutionStatus.Succeeded,
                  ExitCodes.Success,
                  "success",
                  None,
                  Some(OutputPayload("raider/text", 1, text.toJson))
                )
              case Some(Left(err)) =>
                (
                  ExecutionStatus.Failed,
                  HeadlessRunner.exitCodeFor(err),
                  "task-failed",
                  Some(err),
                  None
                )
              case None =>
                (
                  ExecutionStatus.Uncertain,
                  ExitCodes.Uncertain,
                  "incomplete",
                  Some(
                    RaiderError.ToolOutcomeUncertain(
                      "run outcome unavailable after settle"
                    )
                  ),
                  None
                )
          case other =>
            val (st, code) = other match
              case SettleMode.Sigterm =>
                (ExecutionStatus.Interrupted, ExitCodes.SigTerm)
              case SettleMode.ProtocolFatal(_) =>
                (ExecutionStatus.Failed, ExitCodes.RuntimeFailure)
              case _ => (ExecutionStatus.Cancelled, ExitCodes.Uncertain)
            (
              st,
              code,
              "incomplete",
              Some(RaiderError.Cancelled(other.reason)),
              None
            )

      val runOutcomeCheck =
        mode match
          case SettleMode.Natural =>
            outcomeOpt match
              case Some(Right(_)) =>
                CheckResult(
                  "run-outcome",
                  CheckOutcome.Passed,
                  "run completed with a final answer"
                )
              case Some(Left(err)) =>
                CheckResult(
                  "run-outcome",
                  CheckOutcome.Failed,
                  wire(
                    Option(err.getMessage).getOrElse(err.getClass.getSimpleName)
                  )
                )
              case None =>
                CheckResult(
                  "run-outcome",
                  CheckOutcome.Errored,
                  "run outcome unavailable"
                )
          case other =>
            CheckResult("run-outcome", CheckOutcome.Failed, wire(other.reason))

      RunResult(
        schemaVersion = 1,
        rootId = root,
        status = status,
        exitCode = exitCode,
        taskVerdict = Some(verdict),
        checks = List(runOutcomeCheck),
        artifacts = List.empty,
        output = outputOpt,
        error = errorOpt,
        startedAt = startedAt.toString,
        finishedAt = finishedAt.toString
      )

    /** The run-result document is validated BEFORE it is trusted or written; a
      * schema failure on a naturally-finished run forces the task-failed
      * mapping (exit 10). Signal-mapped verdicts keep their exit code and only
      * gain the failed check — signals are never downgraded.
      */
    private def appendSchemaCheck(r: RunResult, mode: SettleMode): RunResult =
      RunResult.validate(r) match
        case Right(()) =>
          r.copy(
            checks = r.checks :+ CheckResult(
              "run-result-schema",
              CheckOutcome.Passed,
              "run result document validated before write"
            )
          )
        case Left(err) =>
          val adjusted =
            if mode == SettleMode.Natural then
              r.copy(
                status = ExecutionStatus.Failed,
                exitCode = ExitCodes.TaskRejected,
                taskVerdict = Some("task-failed")
              )
            else r
          adjusted.copy(
            checks = adjusted.checks :+ CheckResult(
              "run-result-schema",
              CheckOutcome.Failed,
              wire(Option(err.getMessage).getOrElse("schema validation failed"))
            )
          )

    private def computeExit(mode: SettleMode, checked: RunResult): Int =
      mode match
        case SettleMode.Natural => checked.exitCode
        case SettleMode.ClientCancelled | SettleMode.EofAfterDispatch |
            SettleMode.PeerDead(_) =>
          ExitCodes.Uncertain
        case SettleMode.ProtocolFatal(_) => ExitCodes.RuntimeFailure
        case SettleMode.Sigterm          => ExitCodes.SigTerm
        case SettleMode.EofClean         => ExitCodes.Success

    private def terminalBody(
        mode: SettleMode,
        checked: RunResult,
        protocolFailed: Boolean,
        usage: Option[UsageReport],
        written: Option[Artifacts.Written],
        artifactError: Option[String]
    ): Protocol.TerminalBody =
      val executionStatus = checked.status match
        case ExecutionStatus.Succeeded   => "completed"
        case ExecutionStatus.Failed      => "failed"
        case ExecutionStatus.Cancelled   => "cancelled"
        case ExecutionStatus.Interrupted => "interrupted"
        case ExecutionStatus.Uncertain   => "uncertain"
      val safetyVerdict =
        if checked.exitCode == ExitCodes.PolicyDenied || checked.exitCode == ExitCodes.NeedsApproval
        then "denied"
        else "passed"
      // v1 does not export accounting evidence files (lane contract §4);
      // declaring "complete" without exported evidence would be a lie, so the
      // honest value is always "unavailable" here. The captured ledger usage
      // still lands in run.json checks and the work record.
      val _ = usage
      val accountingStatus = "unavailable"
      Protocol.TerminalBody(
        executionStatus = executionStatus,
        taskVerdict = checked.taskVerdict.getOrElse("incomplete"),
        safetyVerdict = safetyVerdict,
        protocolVerdict = if protocolFailed then "failed" else "passed",
        accountingStatus = accountingStatus,
        cleanupStatus = if written.isDefined then "complete" else "bounded",
        exitCode = checked.exitCode,
        artifacts = written
          .map(w =>
            w.entries.map(e =>
              Protocol.ArtifactRef(
                e.path,
                e.sha256,
                e.mediaType,
                e.sizeBytes,
                root.value
              )
            )
          )
          .getOrElse(Vector.empty),
        primaryError = checked.error.map(e =>
          wire(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
        ),
        secondaryError = artifactError
      )

    private def resultPayload(
        outcomeOpt: Option[Either[RaiderError, String]]
    ): Json =
      outcomeOpt match
        case Some(Right(text)) => Json.Obj("output" -> Json.Str(text))
        case Some(Left(err)) =>
          Json.Obj(
            "error" -> Json.Str(
              wire(Option(err.getMessage).getOrElse(err.getClass.getSimpleName))
            )
          )
        case None => Json.Obj("executionStatus" -> Json.Str("uncertain"))

    private def noteSettled(exit: Int): UIO[Unit] =
      ZIO.succeed(
        System.err.println(
          s"raider process-api: root=${root.value} settled exit=$exit"
        )
      )

    // ------------------------------------------------------------------
    // observation forwarding
    // ------------------------------------------------------------------

    private def forward(requestId: String): EventEnvelope => UIO[Unit] = env =>
      env.payload match
        case EventPayload.Terminal(_) => ZIO.unit
        case _ =>
          retention.update { v =>
            if v.length >= MaxRetention then v.tail :+ env else v :+ env
          } *>
            outbound.observation(
              requestId,
              env.seq,
              kindOf(env.payload),
              messageOf(env.payload)
            )

    private def kindOf(payload: EventPayload): String =
      payload match
        case EventPayload.JobStatusChanged(_, _, _) => "status"
        case _                                      => "progress"

    private def messageOf(payload: EventPayload): String =
      val raw = payload match
        case EventPayload.JobStatusChanged(from, to, _) => s"$from -> $to"
        case EventPayload.Waiting(reason)               => s"waiting: $reason"
        case EventPayload.ModelCallStarted(round, model, _) =>
          s"round $round: model call started ($model)"
        case EventPayload.ModelCallFinished(round, durationMs, _) =>
          s"round $round: model call finished in ${durationMs}ms"
        case EventPayload.ToolStarted(_, tool, summary) =>
          s"tool $tool: $summary"
        case EventPayload.ToolProgress(_, tool, tail) =>
          s"tool $tool: ${tail.lines.lastOption.getOrElse("progress")}"
        case EventPayload.ToolFinished(_, tool, outcome) =>
          s"tool $tool: $outcome"
        case EventPayload.RetryScheduled(attempt, delayMs, _) =>
          s"retry scheduled (attempt $attempt in ${delayMs}ms)"
        case EventPayload.BudgetChanged(_, _, _) => "budget updated"
        case EventPayload.Delegated(child, agent) =>
          s"delegated to $agent ($child)"
        case EventPayload.Diagnostics(text) => text
        case EventPayload.DiagnosticsCoalesced(lost) =>
          s"$lost diagnostic event(s) coalesced"
        case EventPayload.Terminal(_) => "terminal"
      wire(raw)

    private def wire(s: String): String =
      Protocol.bounded(s.replaceAll("[\\u0000-\\u001f\\u007f\\r\\n]+", " "))

  end Core

  /** Adapter-private exit code for unexpected internal defects (lane contract
    * §"Failure surface"); not part of the shared ExitCodes mapping.
    */
  private object AdapterExitCode:
    val InternalDefect = 70

end Session
