package raider.cli.run

import raider.core.{JobId, RootId}
import raider.runtime.events.{
  EventCodecs,
  EventEnvelope,
  EventHub,
  EventPayload,
  SessionReducer,
  WaitReason
}
import raider.runtime.events.EventCodecs.given
import raider.runtime.jobs.{JobManager, JobStatus, StatusLedger}
import zio.*
import zio.json.{DecoderOps, EncoderOps}
import zio.stream.ZStream
import zio.test.*
import zio.test.TestAspect.withLiveClock

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** RAI-060 acceptance: STAT-02 (headless events.jsonl = REAL envelopes,
  * codec-validated, unchanged run.json/transcript/exit codes) and STAT-03
  * (burst/slow-consumer/ordering/cancel on the real hub, RAI-059 terminal
  * authority).
  */
object EventExportSpec extends ZIOSpecDefault:

  private val root = RootId("exp-root")
  private val sid = StatusLedger.sessionId

  private def agentArgs(out: java.nio.file.Path) =
    CliArgs
      .parse(
        List(
          "run",
          "--agent",
          "scout",
          "--input",
          "task-42",
          "--out",
          out.toString,
          "--mock"
        )
      )
      .fold(e => throw e, identity)

  def spec = suite("headless event export (RAI-060)")(
    test(
      "STAT-02: events.jsonl = real envelopes (codec roundtrip, no-gap seqs, full emission sequence); run.json/transcript/exit unchanged"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO
              .attempt(
                Files.createTempDirectory("raider-exp-stat02")
              )
              .orDie
            outcome <- HeadlessRunner.run(agentArgs(work))
            eventsRaw = Files.readString(
              outcome.workDir.resolve("events.jsonl"),
              UTF_8
            )
            parsed = eventsRaw.split("\n").filter(_.trim.nonEmpty).toList
            envelopes = parsed
              .map(_.fromJson[EventEnvelope])
              .collect { case Right(e) => e }
            roundtripped = envelopes.map { e =>
              e.toJson.fromJson[EventEnvelope] == Right(e)
            }
            runJson = Files.readString(
              outcome.workDir.resolve("run.json"),
              UTF_8
            )
            transcript = outcome.workDir.resolve("transcript.json")
            seqs = envelopes.map(_.seq)
            payloadTypes = envelopes.map(payloadName)
            hasWaiting = envelopes.exists { e =>
              e.payload match
                case EventPayload.Waiting(WaitReason.Slot) => true
                case _                                     => false
            }
            hasModelStart = envelopes.exists { e =>
              e.payload match
                case EventPayload.ModelCallStarted(r, m, _) =>
                  r == 1 && m == "builtin:scout"
                case _ => false
            }
            hasModelFinish = envelopes.exists { e =>
              e.payload match
                case EventPayload.ModelCallFinished(r, _, _) => r == 1
                case _                                       => false
            }
            firstIsStart = envelopes.headOption.exists(e =>
              e.seq == 1L && e.payload == EventPayload.JobStatusChanged(
                JobStatus.Queued,
                JobStatus.Running,
                None
              )
            )
            lastIsTerminal = envelopes.lastOption.exists(
              _.payload ==
                EventPayload.Terminal(JobStatus.Succeeded)
            )
          yield assertTrue(
            outcome.exitCode == 0,
            envelopes.nonEmpty,
            parsed.size == envelopes.size, // every line decodes
            envelopes.forall(_.schemaVersion == 1),
            envelopes.forall(e => e.jobId.value.nonEmpty),
            seqs == (1L to seqs.size.toLong).toList, // no gaps, no dupes
            firstIsStart,
            hasWaiting,
            hasModelStart,
            hasModelFinish,
            lastIsTerminal,
            payloadTypes.indexOf("Waiting") < payloadTypes
              .indexOf("ModelCallStarted"),
            roundtripped.forall(identity), // codec roundtrip
            !eventsRaw.contains("run.started"), // synthetic markers are gone
            !eventsRaw.contains("run.finished"),
            runJson.fromJson[raider.core.result.RunResult].isRight,
            Files.exists(transcript),
            Files.size(transcript) > 0
          )
        }
      }
    },
    test(
      "STAT-03: STAT-02 also holds for a preflight-failed run (terminal envelope present, exit code unchanged)"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO
              .attempt(
                Files.createTempDirectory("raider-exp-stat02f")
              )
              .orDie
            // live provider, no key → typed preflight failure (exit 21)
            args = CliArgs
              .parse(
                List(
                  "run",
                  "--agent",
                  "scout",
                  "--input",
                  "t",
                  "--out",
                  work.toString,
                  "--provider",
                  "openai",
                  "--base-url",
                  "not-a-url"
                )
              )
              .fold(e => throw e, identity)
            outcome <- HeadlessRunner.run(args, Map.empty)
            raw = Files.readString(
              outcome.workDir.resolve("events.jsonl"),
              UTF_8
            )
            envelopes = raw
              .split("\n")
              .filter(_.trim.nonEmpty)
              .map(_.fromJson[EventEnvelope])
              .collect { case Right(e) => e }
              .toList
          yield assertTrue(
            outcome.exitCode == 21,
            envelopes.nonEmpty,
            envelopes.map(_.seq) == (1L to envelopes.size.toLong).toList,
            envelopes.last.payload == EventPayload
              .Terminal(JobStatus.Failed)
          )
        }
      }
    },
    test(
      "STAT-03: slow consumer burst — reliable+diagnostics interleave reorders to per-job seq order, no loss"
    ) {
      live {
        for
          hub <- EventHub.make()
          jobId = JobId("j_burst")
          subscribed <- hub.subscribe(jobId)
          (snapshot, stream) = subscribed
          // publish interleaved classes WITHOUT consuming: the subscriber
          // queue absorbs the burst (backpressure sits on the forwarder)
          _ <- ZIO.foreachDiscard(1 to 300) { i =>
            hub.publish(jobId, root, sid, EventPayload.Diagnostics(s"d$i")) *>
              hub.publish(
                jobId,
                root,
                sid,
                EventPayload.Waiting(WaitReason.Slot)
              )
          }
          got <- EventExport
            .inOrder(snapshot.lastSeq)(stream)
            .take(600)
            .runCollect
            .timeout(30.seconds)
            .some
            .orElseFail(new RuntimeException("burst not fully delivered"))
          // NOTE: classify OUTSIDE assertTrue — the smart-assert macro
          // mangles pattern-matching lambdas (type tests evaluate false)
          waitingCount = got.count(e =>
            e.payload.isInstanceOf[EventPayload.Waiting]
          )
        yield assertTrue(
          got.map(_.seq) == Chunk.fromIterable(1L to 600L),
          waitingCount == 300
        )
      }
    },
    test(
      "STAT-03: diagnostics overflow — hub drops+counts, stream never gaps, Terminal preserved last"
    ) {
      live {
        for
          // tiny diagnostics capacity → the burst deterministically overflows
          hub <- EventHub.make(reliableCapacity = 64, diagnosticsCapacity = 8)
          jobId = JobId("j_overflow")
          subscribed <- hub.subscribe(jobId)
          (snapshot, stream) = subscribed
          _ <- ZIO.foreachDiscard(1 to 2000) { i =>
            hub.publish(jobId, root, sid, EventPayload.Diagnostics(s"d$i"))
          }
          _ <- hub.publish(
            jobId,
            root,
            sid,
            EventPayload.Waiting(WaitReason.Slot)
          )
          _ <- hub.publish(
            jobId,
            root,
            sid,
            EventPayload.Terminal(JobStatus.Succeeded)
          )
          dropped <- hub.droppedDiagnostics
          expected = 2000L - dropped + 2L // drops never consumed a sequence
          got <- EventExport
            .inOrder(snapshot.lastSeq)(stream)
            .take(expected)
            .runCollect
            .timeout(30.seconds)
            .some
            .orElseFail(new RuntimeException("drain incomplete"))
        yield assertTrue(
          dropped > 0L, // diagnostics coalescing actually happened
          got.map(_.seq) == Chunk.fromIterable(1L to expected),
          got.last.payload == EventPayload.Terminal(JobStatus.Succeeded)
        )
      }
    },
    test(
      "STAT-03: cancel mid-run — Terminal(Cancelled) present and last by per-job seq"
    ) {
      live {
        for
          hub <- EventHub.make()
          manager <- JobManager.make(
            onTransition = Some(StatusLedger.onTransition(hub, root))
          )
          barrier <- Promise.make[Nothing, Unit]
          handle <- manager.start(
            root,
            "cancel-me",
            raider.core.Task(barrier.await *> ZIO.sleep(60.seconds))
          )
          subscribed <- hub.subscribe(handle.id)
          (snapshot, stream) = subscribed
          got <- Ref.make(Vector.empty[EventEnvelope])
          collector <- stream
            .foreach(e => got.update(_ :+ e))
            .forkDaemon
          _ <- barrier.succeed(())
          _ <- ZIO.sleep(50.millis)
          _ <- manager.cancel(handle)
          _ <- manager.await(handle).either
          _ <- EventExport.awaitCaughtUp(
            hub,
            handle.id,
            got.get.map(v => v.lastOption.fold(0L)(_.seq))
          )
          _ <- collector.interrupt
          collected <- got.get
          // The subscribe happened AFTER manager.start: the start transition
          // (seq 1) lives in the SNAPSHOT (watermark), the rest in the stream —
          // exactly the loss-window-free contract; the prefix reconstructs it.
          evs = EventExport.startTransition(snapshot).toVector ++ collected
          terminalCount = evs.count(e =>
            e.payload.isInstanceOf[EventPayload.Terminal]
          )
          terminalPayload = evs.lastOption.map(_.payload)
        yield assertTrue(
          evs.size >= 3,
          evs.map(_.seq) == (1L to evs.size.toLong).toList,
          evs.last.seq == evs.map(_.seq).max,
          terminalCount == 1,
          terminalPayload.contains(
            EventPayload.Terminal(JobStatus.Cancelled)
          )
        )
      }
    },
    test(
      "EventExport.startTransition reconstructs the exact pre-subscribe prefix (lastSeq==1), refuses otherwise"
    ) {
      val jobId = JobId("j_pfx")
      val one = reducerAt(jobId, 1L)
      val two = reducerAt(jobId, 2L)
      assertTrue(
        EventExport.startTransition(one).exists { e =>
          e.seq == 1L &&
          e.schemaVersion == 1 &&
          e.atMs == one.updatedAtMs &&
          e.payload == EventPayload.JobStatusChanged(
            JobStatus.Queued,
            JobStatus.Running,
            None
          )
        },
        EventExport.startTransition(two).isEmpty
      )
    }
  ) @@ withLiveClock @@ TestAspect.timeout(180.seconds)

  private def payloadName(e: EventEnvelope): String =
    e.payload match
      case _: EventPayload.JobStatusChanged     => "JobStatusChanged"
      case _: EventPayload.Waiting              => "Waiting"
      case _: EventPayload.ModelCallStarted     => "ModelCallStarted"
      case _: EventPayload.ModelCallFinished    => "ModelCallFinished"
      case _: EventPayload.ToolStarted          => "ToolStarted"
      case _: EventPayload.ToolProgress         => "ToolProgress"
      case _: EventPayload.ToolFinished         => "ToolFinished"
      case _: EventPayload.RetryScheduled       => "RetryScheduled"
      case _: EventPayload.BudgetChanged        => "BudgetChanged"
      case _: EventPayload.Delegated            => "Delegated"
      case _: EventPayload.Diagnostics          => "Diagnostics"
      case _: EventPayload.DiagnosticsCoalesced => "DiagnosticsCoalesced"
      case _: EventPayload.Terminal             => "Terminal"

  private def reducerAt(jobId: JobId, seq: Long) =
    SessionReducer
      .initial(jobId, root, sid)
      .copy(lastSeq = seq, updatedAtMs = 42L * seq)

end EventExportSpec
