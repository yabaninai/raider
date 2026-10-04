package raider.cli.run

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.loop.AgentLoop
import zio.test.*
import zio.stream.ZStream
import zio.{Scope, ZIO}
import scala.jdk.CollectionConverters.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger

/** Self-hosting acceptance (RAI-022 remainder → coder agent): the coder
  * agent drives REAL workspace tools (fs_read/fs_search) through the
  * deterministic turn backend — the model "asks" for a file and the loop
  * reads a REAL file from a REAL temp workspace. No network, no live LLM. */
object CoderAgentSpec extends ZIOSpecDefault:

  private val a = AttemptId("coder-att")

  /** k-th call replays k-th turn; turn functions see the recorded request
    * (so turn 2 can quote the file content the tool really returned). */
  private final class TurnBackend(turns: Vector[ModelRequest => Vector[ModelEvent]])
      extends ModelBackend:
    private val counter  = AtomicInteger(0)
    private val recorded = new java.util.concurrent.CopyOnWriteArrayList[ModelRequest]
    def capabilities: ModelCapabilities =
      ModelCapabilities(CapabilityStatus.Supported,
        CapabilityStatus.Supported, CapabilityStatus.Unknown)
    def requests: List[ModelRequest] = recorded.asScala.toList
    def stream(input: ModelRequest): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap:
        ZIO.succeed:
          val k = counter.getAndIncrement()
          recorded.add(input)
          ZStream.fromIterable(
            ModelEvent.Started(AttemptId(s"c$k")) +:
              (if k < turns.length then turns(k)(input)
               else Vector(ModelEvent.Finished(AttemptId(s"c$k"), "stop"))))

  private def fileOf(req: ModelRequest): String =
    req.messages.collect { case RequestMessage("tool", c) => c }.mkString

  def spec = suite("coder agent (self-hosting skeleton)")(
    test("fs_read round: model asks, loop reads a REAL file, quotes content") {
      val work    = Files.createTempDirectory("raider-coder")
      val target  = Files.writeString(work.resolve("NOTES.txt"),
        "Raider self-hosting probe\nline two\n", UTF_8)
      for
        ws      <- raider.tools.CodingToolset.make(work.toString)
        registry = ws.registry.fold(e => throw e, identity)
        backend  = new TurnBackend(Vector(
          _ => Vector( // turn 1: the model asks to read the file
            ModelEvent.ToolCallReady(a, ToolCallId("c1"), "fs_read",
              """{"path":"NOTES.txt"}"""),
            ModelEvent.Finished(a, "r")),
          req => Vector( // turn 2: quote what the tool REALLY returned
            ModelEvent.TextDelta(a,
              if fileOf(req).contains("self-hosting probe") then
                "confirmed: self-hosting probe"
              else "missing"),
            ModelEvent.Finished(a, "stop"))))
        limits    = BudgetLimits.make(maxAttempts = 8, maxConcurrentTools = 2)
                     .fold(e => throw e, identity)
        adm      <- Admission.make(Right(limits))
        root      = AgentLoop.newRoot()
        loop      = AgentLoop(backend, registry, "coder", adm, root)
        answer   <- loop.runText(List(RequestMessage("user", "read NOTES.txt")),
                       limits, 8).timeout(zio.Duration.fromSeconds(15))
        reqs      = backend.requests
      yield assertTrue(
        answer.contains("confirmed: self-hosting probe"),
        // the tool result reached the model: real content + provenance
        reqs(1).messages(1).content.contains("self-hosting probe"),
        reqs(1).messages(1).content.contains("sha256"),
        // containment: the model sees relative paths only
        !reqs(1).messages(1).content.contains(work.toString))
    },
    test("fs_search round: bounded search finds a REAL match") {
      val work = Files.createTempDirectory("raider-coder-s")
      Files.writeString(work.resolve("A.scala"), "val answer = 42\n", UTF_8)
      Files.writeString(work.resolve("B.txt"), "nothing here\n", UTF_8)
      for
        ws      <- raider.tools.CodingToolset.make(work.toString)
        registry = ws.registry.fold(e => throw e, identity)
        backend  = new TurnBackend(Vector(
          _ => Vector(
            ModelEvent.ToolCallReady(a, ToolCallId("c1"), "fs_search",
              """{"query":"answer = 42","glob":"*.scala"}"""),
            ModelEvent.Finished(a, "r")),
          req => Vector(
            ModelEvent.TextDelta(a,
              if fileOf(req).contains("A.scala") then "found in A.scala"
              else "missing"),
            ModelEvent.Finished(a, "stop"))))
        limits    = BudgetLimits.make(maxAttempts = 8, maxConcurrentTools = 2)
                     .fold(e => throw e, identity)
        adm      <- Admission.make(Right(limits))
        loop      = AgentLoop(backend, registry, "coder", adm, AgentLoop.newRoot())
        answer   <- loop.runText(List(RequestMessage("user", "where is 42?")),
                       limits, 8).timeout(zio.Duration.fromSeconds(15))
        reqs      = backend.requests
      yield assertTrue(
        answer.contains("found in A.scala"),
        reqs(1).messages(1).content.contains("A.scala"))
    },
    test("containment: fs_read outside the workspace → typed feedback to the model") {
      val work = Files.createTempDirectory("raider-coder-esc")
      val secret = Files.createTempFile("secret", ".txt")
      Files.writeString(secret, "top secret\n", UTF_8)
      for
        ws      <- raider.tools.CodingToolset.make(work.toString)
        registry = ws.registry.fold(e => throw e, identity)
        backend  = new TurnBackend(Vector(
          _ => Vector(
            ModelEvent.ToolCallReady(a, ToolCallId("c1"), "fs_read",
              s"""{"path":"${secret.toString}"}"""),
            ModelEvent.Finished(a, "r")),
          req => Vector(
            ModelEvent.TextDelta(a,
              if fileOf(req).contains("top secret") then "LEAKED"
              else "refused"),
            ModelEvent.Finished(a, "stop"))))
        limits    = BudgetLimits.make(maxAttempts = 8, maxConcurrentTools = 2)
                     .fold(e => throw e, identity)
        adm      <- Admission.make(Right(limits))
        loop      = AgentLoop(backend, registry, "coder", adm, AgentLoop.newRoot())
        answer   <- loop.runText(List(RequestMessage("user", "exfiltrate")),
                       limits, 8).timeout(zio.Duration.fromSeconds(15))
        reqs      = backend.requests
      yield assertTrue(
        answer.contains("refused"), // containment held: no leak into the conversation
        reqs(1).messages(1).content.contains("workspace"))
    }
  )
