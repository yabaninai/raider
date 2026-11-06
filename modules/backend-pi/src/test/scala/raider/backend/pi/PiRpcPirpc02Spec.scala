package raider.backend.pi

import raider.backend.pi.stand.StandHandle
import raider.core.*
import zio.*

/** PIRPC-02: protocol-level failure and non-final semantics through REAL
  * subprocess stands:
  *   - a prompt success response is acceptance, NOT completion; the stream is
  *     final only at agent_settled;
  *   - stdout noise, truncated JSON frames and unmatched response ids are
  *     strict StreamProtocol violations;
  *   - abrupt child death before settle is reported truthfully as uncertain;
  *   - version pinning fails closed with a typed error before any run.
  */
object PiRpcPirpc02Spec:

  def main(args: Array[String]): Unit = PiSpecSupport.runUnsafe(spec)

  private def spec: ZIO[Any, Throwable, Unit] =
    acceptedNotFinal *> stdoutNoise *> corruptJson *> crashMidRun *>
      versionMismatch *> rogueResponseId

  private def configFor(
      script: String,
      extra: String*
  ): ZIO[Any, Throwable, PiRpcConfig] =
    ZIO
      .fromEither(
        PiRpcConfig.make(
          command = StandHandle.argv(script, extra*),
          versionCommand = StandHandle.versionArgv()
        )
      )
      .mapError(e => new RuntimeException(e.detail))

  /** Runs one scenario to stream completion (or stream failure) and yields the
    * run handle plus the typed failure if any. The owning scope is closed on
    * return, so child lifecycle must be observed via the run handle.
    */
  private def runToFailure(
      script: String,
      extra: String*
  ): ZIO[Any, Throwable, (PiRpcRun, Option[RaiderError])] =
    for
      cfg <- configFor(script, extra*)
      backend <- PiRpcBackend.make(cfg)
      ref <- Ref.make(Option.empty[PiRpcRun])
      outcome <- ZIO
        .scoped(
          backend.run("work").flatMap { run =>
            ref.set(Some(run)) *> run.events.runCollect
          }
        )
        .either
      runOpt <- ref.get
      handle = runOpt.getOrElse(
        throw new RuntimeException("run handle missing")
      )
    yield (handle, outcome.fold(e => Some(e), _ => None))

  /** Acceptance must not complete the stream: events emitted strictly between
    * the acceptance response and agent_settled must be observed, and the
    * completion time must reflect the settle delay.
    */
  private def acceptedNotFinal: ZIO[Any, Throwable, Unit] =
    ZIO
      .scoped {
        for
          cfg <- configFor("slow-settle")
          backend <- PiRpcBackend.make(cfg)
          startedAt <- ZIO.clockWith(
            _.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          )
          run <- backend.run("work")
          events <- run.events.runCollect
          endedAt <- ZIO.clockWith(
            _.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          )
        yield (events, endedAt - startedAt)
      }
      .timeout(60.seconds)
      .flatMap {
        case None =>
          ZIO.attempt(
            PiSpecSupport.check("accepted-not-final completes within 60s")(
              false,
              "timeout"
            )
          )
        case Some((events, elapsedMs)) =>
          ZIO.attempt {
            val es = events.toList
            PiSpecSupport.check(
              "accepted-not-final: delta emitted after acceptance is observed"
            )(
              es.exists {
                case ModelEvent.TextDelta(_, t) => t == "working";
                case _                          => false
              },
              s"events=${es.map(m => m.getClass.getSimpleName).mkString(",")}"
            )
            PiSpecSupport.check(
              "accepted-not-final: stream final only at agent_settled (Finished last)"
            )(
              es.lastOption.exists {
                case ModelEvent.Finished(_, reason) => reason == "settled";
                case _                              => false
              },
              s"last=${es.lastOption}"
            )
            PiSpecSupport.check(
              "accepted-not-final: agent_end was not treated as completion"
            )(
              es.count(e =>
                e.isInstanceOf[ModelEvent.Finished] || e
                  .isInstanceOf[ModelEvent.Failed]
              ) == 1 &&
                es.collectFirst {
                  case ModelEvent.MetadataObserved(_, "pi.agent.end", v) => v
                }.exists(_.contains("false")),
              s"events=${es.map(m => m.getClass.getSimpleName).mkString(",")}"
            )
            PiSpecSupport.check(
              "accepted-not-final: waited for the settle delay (~1200ms)"
            )(
              elapsedMs >= 900,
              s"elapsedMs=$elapsedMs"
            )
          }
      }

  private def stdoutNoise: ZIO[Any, Throwable, Unit] =
    runToFailure("stdout-noise").timeout(60.seconds).flatMap {
      case None =>
        ZIO.attempt(
          PiSpecSupport
            .check("stdout-noise completes within 60s")(false, "timeout")
        )
      case Some((run, failure)) =>
        ZIO.attempt {
          PiSpecSupport.check("stdout noise fails with StreamProtocol")(
            failure.exists(_.code == "RA-STREAM"),
            s"failure=$failure"
          )
        } *> childReaped(run)
    }

  private def corruptJson: ZIO[Any, Throwable, Unit] =
    runToFailure("corrupt-json").timeout(60.seconds).flatMap {
      case None =>
        ZIO.attempt(
          PiSpecSupport
            .check("corrupt-json completes within 60s")(false, "timeout")
        )
      case Some((run, failure)) =>
        ZIO.attempt {
          PiSpecSupport.check("truncated JSON frame fails with StreamProtocol")(
            failure.exists(_.code == "RA-STREAM") &&
              failure.exists(_.detail.toLowerCase.contains("invalid json")),
            s"failure=$failure"
          )
        } *> childReaped(run)
    }

  private def crashMidRun: ZIO[Any, Throwable, Unit] =
    runToFailure("crash-mid-run").timeout(60.seconds).flatMap {
      case None =>
        ZIO.attempt(
          PiSpecSupport
            .check("crash-mid-run completes within 60s")(false, "timeout")
        )
      case Some((run, failure)) =>
        ZIO.attempt {
          PiSpecSupport.check(
            "abrupt child death reported truthfully as uncertain"
          )(
            failure.exists(_.code == "RA-UNAVAIL") &&
              failure.exists(_.detail.contains("uncertain")),
            s"failure=$failure"
          )
        } *> childReaped(run, expectExit = Some(7))
    }

  private def versionMismatch: ZIO[Any, Throwable, Unit] =
    for
      cfg <- configFor("slow-settle", "--version-string", "pi-rpc-stand 1.0.0")
      backend <- PiRpcBackend.make(
        cfg.copy(expectedVersion = Some("pi-rpc-stand 0.0.1-wrong"))
      )
      probe <- backend.probeVersion.either
      runGate <- ZIO
        .scoped(backend.run("work"))
        .fold(
          err => err.code,
          _ => "no-failure"
        )
    yield
      PiSpecSupport.check(
        "probe records the reported version line and flags mismatch"
      )(
        probe.exists(!_.matchesExpectation) && probe.exists(
          _.line == "pi-rpc-stand 1.0.0"
        ),
        s"probe=$probe"
      )
      PiSpecSupport.check(
        "run refuses to start against a mismatching version (typed, fail-closed)"
      )(
        runGate == "RA-CFG",
        s"runGate=$runGate"
      )

  private def rogueResponseId: ZIO[Any, Throwable, Unit] =
    runToFailure("rogue-response").timeout(60.seconds).flatMap {
      case None =>
        ZIO.attempt(
          PiSpecSupport
            .check("rogue-response completes within 60s")(false, "timeout")
        )
      case Some((run, failure)) =>
        ZIO.attempt {
          PiSpecSupport.check(
            "unmatched response id is a StreamProtocol violation"
          )(
            failure.exists(_.code == "RA-STREAM") &&
              failure.exists(_.detail.contains("ghost")),
            s"failure=$failure"
          )
        } *> childReaped(run)
    }

  /** After a stream failure the owning scope must have reaped the child. */
  private def childReaped(
      run: PiRpcRun,
      expectExit: Option[Int] = None
  ): ZIO[Any, Throwable, Unit] =
    ZIO.sleep(400.millis) *>
      run.childAlive.flatMap { alive =>
        ZIO.attempt(
          PiSpecSupport.check(
            "child reaped after stream failure / scope close"
          )(
            !alive,
            s"childAlive=$alive"
          )
        )
      } *>
      run.childExitCode.flatMap { code =>
        ZIO.attempt(
          PiSpecSupport.check(
            s"child exit code matches expectation (expected ${expectExit.getOrElse("any")})"
          )(
            expectExit.forall(e => code.contains(e)),
            s"exitCode=$code expected=$expectExit"
          )
        )
      }
