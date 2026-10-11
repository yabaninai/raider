package raider.tools

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.loop.AgentLoop
import raider.runtime.state.{Scope as StateScope, StateStore}
import zio.stream.ZStream
import zio.test.*
import zio.{Scope, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** RAI-094 (STW-01..03): the opt-in state wiring. A coder toolset built with a
  * StateStore registers the four state tools AFTER the six coding tools and the
  * model-driven loop REALLY writes/reads agent state through them; without a
  * store the registry is byte-identical to the pre-card one. Deterministic: the
  * scripted TurnBackend replays the turns (CoderAgentSpec pattern) — no
  * network, no live LLM.
  */
object CodingToolsetStateSpec extends ZIOSpecDefault:

  private val a = AttemptId("stw-att")

  /** k-th call replays k-th turn; turn functions see the recorded request (so a
    * later turn can quote what the tools REALLY returned).
    */
  private final class TurnBackend(
      turns: Vector[ModelRequest => Vector[ModelEvent]]
  ) extends ModelBackend:
    private val counter = AtomicInteger(0)

    private val recorded =
      new java.util.concurrent.CopyOnWriteArrayList[ModelRequest]

    def capabilities: ModelCapabilities =
      ModelCapabilities(
        CapabilityStatus.Supported,
        CapabilityStatus.Supported,
        CapabilityStatus.Unknown
      )

    def requests: List[ModelRequest] = recorded.asScala.toList

    def stream(input: ModelRequest): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap:
        ZIO.succeed:
          val k = counter.getAndIncrement()
          recorded.add(input)
          ZStream.fromIterable(
            ModelEvent.Started(AttemptId(s"s$k")) +:
              (if k < turns.length then turns(k)(input)
               else Vector(ModelEvent.Finished(AttemptId(s"s$k"), "stop")))
          )

  /** Everything the model has seen so far (tool results are role="tool"). */
  private def contentOf(req: ModelRequest): String =
    req.messages.map(_.content).mkString("\n")

  private val SixNames = List(
    "fs_edit",
    "fs_patch",
    "fs_read",
    "fs_search",
    "fs_tree",
    "proc_run"
  )

  private val StateNames = List(
    "state_delete",
    "state_get",
    "state_keys",
    "state_put"
  )

  def spec = suite("coding toolset state wiring (RAI-094, STW-01..03)")(
    test(
      "STW-01: with a store the registry appends the four state tools after the six coding tools"
    ) {
      val work = Files.createTempDirectory("raider-stw-reg")
      for
        store <- StateStore.memory()
        ws <- CodingToolset.make(work.toString, Some((store, JobId("stw-01"))))
        reg = ws.registry.fold(e => throw e, identity)
      yield assertTrue(
        reg.size == 10,
        reg.names == SixNames ++ StateNames
      )
    },
    test(
      "STW-01: model-driven round — state_put then state_get hit the REAL store"
    ) {
      val work = Files.createTempDirectory("raider-stw-round")
      for
        store <- StateStore.memory()
        job = JobId("stw-coder")
        ws <- CodingToolset.make(work.toString, Some((store, job)))
        registry = ws.registry.fold(e => throw e, identity)
        backend = new TurnBackend(
          Vector(
            _ =>
              Vector( // turn 1: the model saves its scratch state
                ModelEvent.ToolCallReady(
                  a,
                  ToolCallId("s1"),
                  "state_put",
                  """{"key":"notes","value":"wip-42"}"""
                ),
                ModelEvent.Finished(a, "r")
              ),
            _ =>
              Vector( // turn 2: the model reads it back
                ModelEvent.ToolCallReady(
                  a,
                  ToolCallId("s2"),
                  "state_get",
                  """{"key":"notes"}"""
                ),
                ModelEvent.Finished(a, "r")
              ),
            req =>
              Vector( // turn 3: quote what the tool REALLY returned
                ModelEvent.TextDelta(
                  a,
                  if contentOf(req).contains("wip-42") then "confirmed: wip-42"
                  else "missing"
                ),
                ModelEvent.Finished(a, "stop")
              )
          )
        )
        limits = BudgetLimits
          .make(maxAttempts = 8, maxConcurrentTools = 2)
          .fold(e => throw e, identity)
        adm <- Admission.make(Right(limits))
        loop = AgentLoop(backend, registry, "coder", adm, AgentLoop.newRoot())
        answer <- loop
          .runText(List(RequestMessage("user", "remember wip-42")), limits, 8)
          .timeout(zio.Duration.fromSeconds(15))
        reqs = backend.requests
        stored <- store.get(StateScope.Agent(job), "notes")
      yield assertTrue(
        answer.contains("confirmed: wip-42"),
        // the store was REALLY mutated through the tool call (default Agent scope)
        stored == Some("wip-42"),
        // the put feedback reached the model as structured JSON, matched
        // back by tool_call_id (s1) with the real Agent scope and revision
        contentOf(reqs(1)).contains("\"tool_call_id\":\"s1\""),
        contentOf(reqs(1)).contains("\"ok\":true"),
        contentOf(reqs(1)).contains("agent(stw-coder)"),
        contentOf(reqs(1)).contains("\"revision\":1"),
        // the get result reached the model with the value and its revision
        contentOf(reqs(2)).contains("\"found\":true"),
        contentOf(reqs(2)).contains("wip-42"),
        contentOf(reqs(2)).contains("\"revision\":1")
      )
    },
    test(
      "STW-03: without a store the registry is exactly the six coding tools — no state tools, unchanged behavior"
    ) {
      val work = Files.createTempDirectory("raider-stw-none")
      for
        ws <- CodingToolset.make(work.toString) // default: no store
        reg = ws.registry.fold(e => throw e, identity)
      yield assertTrue(
        reg.size == 6,
        reg.names == SixNames,
        !reg.names.exists(_.startsWith("state_"))
      )
    }
  )
