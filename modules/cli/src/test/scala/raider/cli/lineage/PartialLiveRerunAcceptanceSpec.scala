package raider.cli.lineage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import raider.core.RaiderError
import raider.core.backend.ModelEvent
import raider.core.{
  AttemptId,
  CapabilityStatus,
  ModelBackend,
  ModelCapabilities,
  ModelRequest,
  ToolCallId
}
import raider.runtime.budget.BudgetLimits
import raider.runtime.lineage.model.{DataRef, NodeId, SessionGraph, SessionNode}
import raider.runtime.lineage.replay.*
import zio.*
import zio.json.*
import zio.test.*
import zio.stream.ZStream

object PartialLiveRerunAcceptanceSpec extends ZIOSpecDefault:
  private val selectedId = NodeId("job-rec:n11")
  private val siblingId = NodeId("job-rec:n4")
  private val freshPrompt = "Recompute the selected result from current inputs."
  private val attempt = AttemptId("lin7-live-stand")

  private final class FreshAnswerStand(
      observedPrompt: java.util.concurrent.atomic.AtomicReference[String]
  ) extends ModelBackend:

    override val capabilities = ModelCapabilities(
      streaming = CapabilityStatus.Supported,
      tools = CapabilityStatus.Supported,
      cancellationAck = CapabilityStatus.Supported
    )

    override def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      observedPrompt.set(input.messages.lastOption.fold("")(_.content))
      ZStream.fromIterable(
        Vector(
          ModelEvent.Started(attempt),
          ModelEvent.TextDelta(attempt, "fresh result from scripted stand"),
          ModelEvent.Finished(attempt, "stop")
        )
      )

  private final class ToolThenTextStand extends ModelBackend:
    private val calls = new java.util.concurrent.atomic.AtomicInteger(0)

    override val capabilities = ModelCapabilities(
      streaming = CapabilityStatus.Supported,
      tools = CapabilityStatus.Supported,
      cancellationAck = CapabilityStatus.Supported
    )

    override def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      if calls.getAndIncrement() == 0 then
        ZStream.fromIterable(
          Vector(
            ModelEvent.Started(attempt),
            ModelEvent.ToolCallReady(
              attempt,
              ToolCallId("partial-budget-read"),
              "fs_read",
              "{\"path\":\"notes.txt\"}"
            ),
            ModelEvent.Finished(attempt, "tool_calls")
          )
        )
      else
        ZStream.fromIterable(
          Vector(
            ModelEvent.Started(attempt),
            ModelEvent.TextDelta(attempt, "unreached second-round answer"),
            ModelEvent.Finished(attempt, "stop")
          )
        )

  private def fixture(
      effect: (Path, Path) => ZIO[Any, Throwable, TestResult]
  ): ZIO[Any, Throwable, TestResult] =
    ZIO.acquireReleaseWith(
      ZIO.attempt {
        val root = Files.createTempDirectory("lin7-partial-live")
        val workspace = Files.createDirectory(root.resolve("workspace"))
        Files.writeString(
          workspace.resolve("notes.txt"),
          "current notes\n",
          UTF_8
        )
        val pointer = Files.createDirectory(root.resolve("snapshot"))
        val document = ReplayHarness.document
        LineageSnapshot
          .publish(
            pointer,
            document.toJson.getBytes(UTF_8),
            "// committed fixture source\n".getBytes(UTF_8)
          )
          .fold(
            error => throw new IllegalStateException(error.toString),
            identity
          )
        (root, workspace, pointer)
      }
    ) { case (root, _, _) => ZIO.attempt(deleteTree(root)).orDie } {
      case (_, workspace, pointer) => effect(workspace, pointer)
    }

  private def deleteTree(root: Path): Unit =
    val stream = Files.walk(root)
    try
      stream
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .forEach(path => Files.deleteIfExists(path))
    finally stream.close()

  def spec = suite("PartialLiveRerun LIN-07")(
    test(
      "LIN7-LIVE-01 reads the committed pointer and diffs fresh selected work"
    ) {
      fixture { (workspace, pointer) =>
        val dispatched =
          new java.util.concurrent.atomic.AtomicReference[SessionGraph]()
        val backend = new RerunBackend:
          override def execute(
              request: RerunRequest
          ): ZIO[Scope, RaiderError, RerunExecution] =
            dispatched.set(request.recordedGraph)
            val rerun = request.recordedGraph.copy(nodes =
              request.recordedGraph.nodes.map {
                case model: SessionNode.ModelCall =>
                  model.copy(dataRef = DataRef("fresh-model-output"))
                case other => other
              }
            )
            ZIO.succeed(RerunExecution("fresh scripted result", rerun))

        val limits = BudgetLimits.make().toOption.get
        ZIO
          .scoped(
            PartialLiveRerun.run(
              pointer,
              workspace,
              workspace.getParent.resolve("isolated"),
              ReplaySelection.Subtree(selectedId),
              freshPrompt,
              "scripted-partial-model",
              limits,
              2,
              backend
            )
          )
          .flatMap { result =>
            ZIO.attempt {
              val committedAgain = LineageSnapshot.read(pointer).toOption.get
              val originalGraph =
                ReplayHarness.recordedGraph(committedAgain.document)
              val unchangedSibling = originalGraph.nodes.find(_.id == siblingId)
              assertTrue(
                result.snapshot.generation == committedAgain.generation,
                dispatched.get().nodes.map(_.id) == Vector(selectedId),
                dispatched.get().nodes.head.inputs.isEmpty,
                result.diff.isLeft,
                result.result.graph.nodes.exists {
                  case model: SessionNode.ModelCall =>
                    model.dataRef == DataRef("fresh-model-output")
                  case _ => false
                },
                unchangedSibling == originalGraph.nodes.find(_.id == siblingId),
                result.snapshot.document == committedAgain.document
              )
            }
          }
      }
    },
    test(
      "LIN7-CANCEL-LIVE keeps the pointer readable and removes isolated state"
    ) {
      fixture { (workspace, pointer) =>
        val isolationParent = workspace.getParent.resolve("isolated-on-cancel")
        val started = new java.util.concurrent.CountDownLatch(1)
        val backend = new RerunBackend:
          override def execute(
              request: RerunRequest
          ): ZIO[Scope, RaiderError, RerunExecution] =
            ZIO.succeed(started.countDown()) *> ZIO.never

        val limits = BudgetLimits.make().toOption.get
        ZIO.scoped {
          for
            fiber <- PartialLiveRerun
              .run(
                pointer,
                workspace,
                isolationParent,
                ReplaySelection.Subtree(selectedId),
                freshPrompt,
                "scripted-partial-model",
                limits,
                1,
                backend
              )
              .fork
            ready <- ZIO.attempt(
              started.await(3, java.util.concurrent.TimeUnit.SECONDS)
            )
            exit <- fiber.interrupt
            snapshot <- ZIO.attempt(LineageSnapshot.read(pointer))
            parentClean <- ZIO.attempt {
              !Files.exists(isolationParent) || {
                val children = Files.list(isolationParent)
                try !children.iterator().hasNext
                finally children.close()
              }
            }
            workspaceContents <- ZIO.attempt(
              Files.readString(workspace.resolve("notes.txt"), UTF_8)
            )
          yield assertTrue(
            ready,
            exit.isInterrupted,
            snapshot.isRight,
            parentClean,
            workspaceContents == "current notes\n"
          )
        }
      }
    },
    test("LIN7-LIVE-HARNESS uses the CLI live backend with a fresh prompt") {
      fixture { (workspace, pointer) =>
        val observedPrompt =
          new java.util.concurrent.atomic.AtomicReference[String]("")
        val backend =
          ReplayHarness.liveBackend(new FreshAnswerStand(observedPrompt))
        val limits = BudgetLimits.make().toOption.get
        ZIO
          .scoped(
            PartialLiveRerun.run(
              pointer,
              workspace,
              workspace.getParent.resolve("isolated-harness"),
              ReplaySelection.Subtree(selectedId),
              freshPrompt,
              "scripted-partial-model",
              limits,
              2,
              backend
            )
          )
          .map { result =>
            assertTrue(
              observedPrompt.get() == freshPrompt,
              result.result.graph.nodes.nonEmpty,
              result.snapshot.generation.nonEmpty
            )
          }
      }
    },
    test("LIN7-BUDGET-LIVE exhausts the explicit budget during fresh work") {
      fixture { (workspace, pointer) =>
        val limits = BudgetLimits.make(maxAttempts = 1).toOption.get
        val backend = ReplayHarness.liveBackend(new ToolThenTextStand)
        ZIO
          .scoped(
            PartialLiveRerun
              .run(
                pointer,
                workspace,
                workspace.getParent.resolve("isolated-budget"),
                ReplaySelection.Subtree(selectedId),
                freshPrompt,
                "scripted-partial-model",
                limits,
                1,
                backend
              )
              .either
          )
          .map {
            case Left(_: RaiderError.LocalBudgetExceeded) => assertTrue(true)
            case other =>
              throw new IllegalStateException(
                s"expected typed mid-work budget refusal, got $other"
              )
          }
      }
    }
  )

end PartialLiveRerunAcceptanceSpec
