package raider.backend.pi

import raider.backend.pi.stand.StandHandle
import raider.core.*
import zio.*

/** PIRPC-03: framing robustness, retry semantics and cancellation/ownership
  * truth through REAL subprocess stands:
  *   - U+2028/U+2029 inside string payloads are DATA, never record breaks;
  *   - auto-retry rounds (agent_end willRetry=true) are not completion;
  *   - abort is acknowledged by agent_settled(aborted=true);
  *   - a child that ignores abort is destroyed within a bounded deadline and
  *     the outcome is reported as uncertain, never as a clean cancel;
  *   - scope close kills the child (no orphans).
  */
object PiRpcPirpc03Spec:

  def main(args: Array[String]): Unit = PiSpecSupport.runUnsafe(spec)

  private def spec: ZIO[Any, Throwable, Unit] =
    unicodeSeparators *> retryNotTerminal *> cancelAcknowledged *>
      cancelIgnoredDeadline *> cancelUnsettledUncertain *> scopeCloseNoOrphan

  private def configFor(
      script: String,
      settleWaitMs: Long = 3000,
      extra: String*
  ): ZIO[Any, Throwable, PiRpcConfig] =
    ZIO
      .fromEither(
        PiRpcConfig.make(
          command = StandHandle.argv(script, extra*),
          versionCommand = StandHandle.versionArgv(),
          settleWaitMs = settleWaitMs
        )
      )
      .mapError(e => new RuntimeException(e.detail))

  private def unicodeSeparators: ZIO[Any, Throwable, Unit] =
    ZIO
      .scoped {
        for
          cfg <- configFor("unicode-separators")
          backend <- PiRpcBackend.make(cfg)
          run <- backend.run("work")
          events <- run.events.runCollect
        yield events
      }
      .timeout(60.seconds)
      .flatMap {
        case None =>
          ZIO.attempt(
            PiSpecSupport.check("unicode-separators completes within 60s")(
              false,
              "timeout"
            )
          )
        case Some(events) =>
          ZIO.attempt {
            val es = events.toList
            val deltas = es.collect { case ModelEvent.TextDelta(_, t) => t }
            PiSpecSupport.check(
              "U+2028/U+2029 stay inside one frame (single text delta)"
            )(
              deltas.size == 1,
              s"deltas=$deltas"
            )
            val expected = "sep\u2028inside\u2029frame"
            PiSpecSupport.check(
              "U+2028/U+2029 preserved byte-exact in the decoded payload"
            )(
              deltas.headOption.contains(expected),
              s"delta=${deltas.headOption.map(_.map(c => f"\\u${c.toInt}%04x").mkString)}"
            )
            PiSpecSupport.check("unicode run still settles normally")(
              es.lastOption.exists {
                case ModelEvent.Finished(_, reason) => reason == "settled";
                case _                              => false
              },
              s"last=${es.lastOption}"
            )
          }
      }

  private def retryNotTerminal: ZIO[Any, Throwable, Unit] =
    ZIO
      .scoped {
        for
          cfg <- configFor("retry-then-settle")
          backend <- PiRpcBackend.make(cfg)
          run <- backend.run("work")
          events <- run.events.runCollect
        yield events
      }
      .timeout(60.seconds)
      .flatMap {
        case None =>
          ZIO.attempt(
            PiSpecSupport
              .check("retry-then-settle completes within 60s")(false, "timeout")
          )
        case Some(events) =>
          ZIO.attempt {
            val es = events.toList
            val retryStart = es.indexWhere {
              case ModelEvent.MetadataObserved(_, "pi.retry.start", _) => true;
              case _                                                   => false
            }
            val willRetryEnd = es.indexWhere {
              case ModelEvent.MetadataObserved(_, "pi.agent.end", v) =>
                v.contains("true"); case _                           => false
            }
            val recovered = es.indexWhere {
              case ModelEvent.TextDelta(_, t) => t == "recovered after retry";
              case _                          => false
            }
            val terminal = es.indexWhere {
              case _: ModelEvent.Finished | _: ModelEvent.Failed => true;
              case _                                             => false
            }

            PiSpecSupport.check(
              "retry round recorded as metadata (pi.retry.start)"
            )(
              retryStart >= 0,
              s"events=${es.map(m => m.getClass.getSimpleName).mkString(",")}"
            )
            PiSpecSupport.check(
              "agent_end(willRetry=true) recorded but not terminal"
            )(
              willRetryEnd >= 0 && willRetryEnd < terminal,
              s"willRetryEnd=$willRetryEnd terminal=$terminal"
            )
            PiSpecSupport.check(
              "post-retry delta observed after the retry boundary"
            )(
              recovered > willRetryEnd,
              s"recovered=$recovered willRetryEnd=$willRetryEnd"
            )
            PiSpecSupport.check("single terminal event, at the very end")(
              terminal == es.length - 1 &&
                es.lastOption.exists {
                  case ModelEvent.Finished(_, reason) => reason == "settled";
                  case _                              => false
                },
              s"terminal=$terminal len=${es.length} last=${es.lastOption}"
            )
          }
      }

  private def cancelAcknowledged: ZIO[Any, Throwable, Unit] =
    ZIO
      .scoped {
        for
          cfg <- configFor("abort-ack")
          backend <- PiRpcBackend.make(cfg)
          run <- backend.run("work")
          first <- run.events.take(2).runCollect
          outcome <- run.cancel
          rest <- run.events.runCollect
        yield (outcome, rest)
      }
      .timeout(60.seconds)
      .flatMap {
        case None =>
          ZIO.attempt(
            PiSpecSupport.check("cancelAcknowledged completes within 60s")(
              false,
              "timeout"
            )
          )
        case Some((outcome, rest)) =>
          ZIO.attempt {
            PiSpecSupport.check(
              "cancel acknowledged via agent_settled(aborted=true)"
            )(
              outcome.detail.contains("Acknowledged") || outcome
                .isInstanceOf[PiCancelOutcome.Acknowledged],
              s"outcome=$outcome"
            )
            PiSpecSupport.check("cancelled run ends with Failed(Cancelled)")(
              rest.lastOption.exists {
                case ModelEvent.Failed(_, e) => e.code == "RA-CANCEL";
                case _                       => false
              },
              s"last=${rest.lastOption}"
            )
          }
      }

  private def cancelIgnoredDeadline: ZIO[Any, Throwable, Unit] =
    ZIO
      .scoped {
        for
          cfg <- configFor("ignore-abort", settleWaitMs = 1200)
          backend <- PiRpcBackend.make(cfg)
          run <- backend.run("work")
          first <- run.events.take(2).runCollect
          t0 <- ZIO.clockWith(
            _.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          )
          outcome <- run.cancel
          t1 <- ZIO.clockWith(
            _.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          )
          alive <- run.childAlive
          rest <- run.events.runCollect
        yield (outcome, t1 - t0, alive, rest)
      }
      .timeout(60.seconds)
      .flatMap {
        case None =>
          ZIO.attempt(
            PiSpecSupport.check("cancelIgnoredDeadline completes within 60s")(
              false,
              "timeout"
            )
          )
        case Some((outcome, elapsedMs, aliveAfterCancel, rest)) =>
          ZIO.attempt {
            PiSpecSupport.check(
              "ignored abort reported as Uncertain, not a clean cancel"
            )(
              outcome.isInstanceOf[PiCancelOutcome.Uncertain] &&
                outcome.detail.contains("uncertain"),
              s"outcome=$outcome"
            )
            PiSpecSupport.check(
              "uncertain outcome discloses child destruction"
            )(
              outcome.detail.contains("destroyed") || outcome.detail
                .contains("stopped"),
              s"outcome=$outcome"
            )
            PiSpecSupport.check(
              "child destroyed within bounded deadline (<10s wall)"
            )(
              elapsedMs < 10000,
              s"elapsedMs=$elapsedMs"
            )
            PiSpecSupport.check("child destroyed by the cancel deadline")(
              !aliveAfterCancel,
              s"aliveAfterCancel=$aliveAfterCancel"
            )
            PiSpecSupport.check("events end truthfully after destroyed cancel")(
              rest.lastOption.exists {
                case ModelEvent.Failed(_, e) =>
                  e.code == "RA-CANCEL" || e.code == "RA-UNAVAIL"
                case _ => false
              },
              s"last=${rest.lastOption}"
            )
          }
      }

  private def cancelUnsettledUncertain: ZIO[Any, Throwable, Unit] =
    ZIO
      .scoped {
        for
          cfg <- configFor("abort-unsettled", settleWaitMs = 1200)
          backend <- PiRpcBackend.make(cfg)
          run <- backend.run("work")
          first <- run.events.take(2).runCollect
          outcome <- run.cancel
          rest <- run.events.runCollect
          alive <- run.childAlive
        yield (outcome, rest, alive)
      }
      .timeout(60.seconds)
      .flatMap {
        case None =>
          ZIO.attempt(
            PiSpecSupport.check(
              "cancelUnsettledUncertain completes within 60s"
            )(false, "timeout")
          )
        case Some((outcome, rest, alive)) =>
          ZIO.attempt {
            PiSpecSupport.check(
              "abort acknowledged at protocol level but no settle => Uncertain"
            )(
              outcome.isInstanceOf[PiCancelOutcome.Uncertain] &&
                outcome.detail.contains("uncertain"),
              s"outcome=$outcome"
            )
            PiSpecSupport.check(
              "no clean-cancel claim without settle confirmation"
            )(
              !outcome.isInstanceOf[PiCancelOutcome.Acknowledged],
              s"outcome=$outcome"
            )
            PiSpecSupport.check("child destroyed after uncertain cancel")(
              !alive,
              s"alive=$alive"
            )
            PiSpecSupport.check("events end truthfully after destroyed cancel")(
              rest.lastOption.exists {
                case ModelEvent.Failed(_, e) =>
                  e.code == "RA-CANCEL" || e.code == "RA-UNAVAIL"
                case _ => false
              },
              s"last=${rest.lastOption}"
            )
          }
      }

  private def scopeCloseNoOrphan: ZIO[Any, Throwable, Unit] =
    ZIO
      .scoped {
        for
          cfg <- configFor("abort-ack")
          backend <- PiRpcBackend.make(cfg)
          run <- backend.run("work")
          first <- run.events.take(1).runCollect
        yield run
      }
      .flatMap { run =>
        ZIO.sleep(500.millis) *>
          run.childAlive.flatMap { alive =>
            ZIO.attempt(
              PiSpecSupport.check(
                "scope close (no explicit cancel) leaves no orphan child"
              )(
                !alive,
                s"alive=$alive"
              )
            )
          }
      }
