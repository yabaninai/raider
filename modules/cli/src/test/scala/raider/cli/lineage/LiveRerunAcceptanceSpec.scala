package raider.cli.lineage

import raider.core.backend.ModelEvent
import raider.core.{
  AttemptId,
  CapabilityStatus,
  ModelBackend,
  ModelCapabilities,
  ModelRequest,
  RaiderError
}
import raider.runtime.budget.BudgetLimits
import zio.*
import zio.stream.ZStream
import zio.test.*
import zio.json.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference

/** LIN6-LIVE acceptance uses only an explicitly supplied local scripted
  * backend. No endpoint or paid provider is contacted.
  */
object LiveRerunAcceptanceSpec extends ZIOSpecDefault:
  private val Prompt = "Inspect the current fixture and report its new value."
  private val FreshAnswer = "fresh stand answer, not the recorded answer"
  private val attempt = AttemptId("live-stand-1")

  private final class TextStand(
      response: String,
      observed: AtomicReference[String],
      stall: Boolean = false
  ) extends ModelBackend:

    override val capabilities = ModelCapabilities(
      streaming = CapabilityStatus.Supported,
      tools = CapabilityStatus.Supported,
      cancellationAck = CapabilityStatus.Supported
    )

    override def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      observed.set(input.messages.lastOption.fold("")(_.content))
      if stall then ZStream.fromZIO(ZIO.never)
      else
        ZStream.fromIterable(
          Vector(
            ModelEvent.Started(attempt),
            ModelEvent.TextDelta(attempt, response),
            ModelEvent.Finished(attempt, "stop")
          )
        )

  private final class TwoRoundStand(observed: AtomicReference[String])
      extends ModelBackend:
    private val calls = new java.util.concurrent.atomic.AtomicInteger(0)

    override val capabilities = ModelCapabilities(
      streaming = CapabilityStatus.Supported,
      tools = CapabilityStatus.Supported,
      cancellationAck = CapabilityStatus.Supported
    )

    override def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      observed.set(input.messages.lastOption.fold("")(_.content))
      val round = calls.getAndIncrement()
      if round == 0 then
        ZStream.fromIterable(
          Vector(
            ModelEvent.Started(attempt),
            ModelEvent.ToolCallReady(
              attempt,
              raider.core.ToolCallId("fresh-read"),
              "fs_read",
              "{\"path\":\"notes.txt\"}"
            ),
            ModelEvent.Finished(attempt, "tool_calls")
          )
        )
      else ZStream.fromZIO(ZIO.never)

  private def withFixture(
      effect: (Path, Path, LineageSnapshot.Snapshot) => ZIO[
        Any,
        Throwable,
        TestResult
      ]
  ): ZIO[Any, Throwable, TestResult] =
    ZIO.acquireReleaseWith(
      ZIO.attempt {
        val root = Files.createTempDirectory("lin6-live-acceptance")
        val workspace = Files.createDirectory(root.resolve("workspace"))
        Files.writeString(
          workspace.resolve("notes.txt"),
          "current workspace input\n",
          UTF_8
        )
        val isolatedParent =
          Files.createDirectory(root.resolve("isolated-parent"))
        val pointerDir = Files.createDirectory(root.resolve("snapshot"))
        val doc = ReplayHarness.document
        val published = LineageSnapshot.publish(
          pointerDir,
          doc.toJson.getBytes(UTF_8),
          "// committed source\n".getBytes(UTF_8)
        )
        published.fold(
          error => throw new IllegalStateException(error.toString),
          identity
        )
        val snapshot = LineageSnapshot
          .read(pointerDir)
          .fold(
            error => throw new IllegalStateException(error.toString),
            identity
          )
        (root, workspace, isolatedParent, snapshot)
      }
    ) { case (root, _, _, _) => ZIO.attempt(deleteRecursively(root)).orDie } {
      case (_, workspace, isolatedParent, snapshot) =>
        effect(workspace, isolatedParent, snapshot)
    }

  private def deleteRecursively(root: Path): Unit =
    val stream = Files.walk(root)
    try
      stream
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .forEach(p => Files.deleteIfExists(p))
    finally stream.close()

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LiveRerunAcceptance")(
    test(
      "LIN6-LIVE-01: pointer snapshot provenance plus fresh prompt and fresh answer"
    ) {
      val observed = new AtomicReference[String]("")
      withFixture { (workspace, parent, snapshot) =>
        val backend =
          ReplayHarness.liveBackend(new TextStand(FreshAnswer, observed))
        ZIO
          .scoped(
            IsolatedRerun.live(
              snapshot,
              Prompt,
              "scripted-live-model",
              workspace,
              parent,
              backend
            )
          )
          .mapError(error => new RuntimeException(error.toString))
          .flatMap { outcome =>
            ZIO.attempt(Files.isRegularFile(snapshot.dataPath)).map {
              snapshotReadable =>
                assertTrue(
                  observed.get() == Prompt,
                  outcome.report.contains("first-divergence"),
                  outcome.liveAnswer.contains(FreshAnswer),
                  !outcome.liveAnswer
                    .contains(ReplayHarness.Round2FinalResponse),
                  snapshotReadable,
                  snapshot.generation.nonEmpty
                )
            }
          }
      }
    },
    test(
      "LIN6-LIVE-02: exhausted attempt budget is a typed refusal, not replay success"
    ) {
      val observed = new AtomicReference[String]("")
      withFixture { (workspace, parent, snapshot) =>
        val limits = BudgetLimits.make(maxAttempts = 1).toOption.get
        val backend = ReplayHarness.liveBackend(new TwoRoundStand(observed))
        ZIO
          .scoped(
            IsolatedRerun.live(
              snapshot,
              Prompt,
              "scripted-live-model",
              workspace,
              parent,
              backend,
              limits = limits,
              maxRounds = 1
            )
          )
          .either
          .map {
            case Left(RerunError.Replay(_: RaiderError.LocalBudgetExceeded)) =>
              assertTrue(observed.get().nonEmpty, snapshot.generation.nonEmpty)
            case other =>
              throw new IllegalStateException(
                s"expected typed live budget refusal, got $other"
              )
          }
      }
    },
    test(
      "LIN6-LIVE-03: cancellation interrupts fresh execution and pointer remains readable"
    ) {
      val observed = new AtomicReference[String]("")
      withFixture { (workspace, parent, snapshot) =>
        val backend = ReplayHarness.liveBackend(
          new TextStand(FreshAnswer, observed, stall = true)
        )
        ZIO.scoped {
          for
            fiber <- IsolatedRerun
              .live(
                snapshot,
                Prompt,
                "scripted-live-model",
                workspace,
                parent,
                backend
              )
              .fork
            _ <- waitForPrompt(observed).timeoutFail(
              new RuntimeException(
                "live backend did not receive the fresh prompt"
              )
            )(zio.Duration.fromSeconds(3))
            exit <- fiber.interrupt
            after <- ZIO.attempt(
              LineageSnapshot.read(snapshot.dataPath.getParent)
            )
            parentClean <- ZIO.attempt {
              !Files.exists(parent) || {
                val entries = Files.list(parent)
                try !entries.iterator().hasNext
                finally entries.close()
              }
            }
          yield assertTrue(
            exit.isInterrupted,
            after == Right(snapshot),
            observed.get() == Prompt,
            parentClean
          )
        }
      }
    }
  ) @@ TestAspect.withLiveClock

  private def waitForPrompt(observed: AtomicReference[String]): UIO[Unit] =
    ZIO.succeed(observed.get().nonEmpty).flatMap { ready =>
      if ready then ZIO.unit
      else ZIO.sleep(zio.Duration.fromMillis(5)) *> waitForPrompt(observed)
    }

end LiveRerunAcceptanceSpec
