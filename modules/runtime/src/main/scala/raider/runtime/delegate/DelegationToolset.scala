package raider.runtime.delegate

import raider.runtime.loop.AgentLoop

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.jobs.{JobHandle, JobManager}
import zio.{Scope, ZIO}
import zio.json.{DecoderOps, EncoderOps}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** Model-callable delegation tools (runtime-contracts §11, LOOP-01/W09):
  * `delegate`, `await_agent`, `cancel_agent`, `list_agents` — over the SAME
  * JobManager / root ledger / admission as workflow fork (one owner, one root
  * budget; children's attempts land on the delegating root).
  *
  * §11 semantics implemented here:
  *   - `delegate` validates the agent name against an explicit whitelist and
  *     the children count against `limits.maxChildren`; returns the child
  *     handle ID and state — NEVER the transcript;
  *   - `await_agent` suspends the parent loop and does NOT hold a model slot
  *     (children run under the same maxConcurrentLlm=1 — BUD-03 no-deadlock);
  *     it holds ONE tool slot for the duration, so construction requires
  *     `maxConcurrentTools >= 2` (an awaiting parent must not starve a
  *     tool-using child);
  *   - children CANNOT re-delegate: the child registry is the base registry
  *     only (recursion is a separate policy, not an accident);
  *   - unknown names produce structured feedback JSON (never silent misses);
  *     `send_agent` is deliberately absent (steering = RA-CAP obligation).
  *
  * All tool outputs are single-line JSON matched back by tool_call_id.
  */
final class DelegationToolset private (
    jobs: JobManager,
    backend: ModelBackend,
    admission: Admission,
    limits: BudgetLimits,
    baseRegistry: ToolRegistry,
    allowedAgents: Set[String],
    root: RootId
):

  private val children = new ConcurrentHashMap[String, JobHandle[String]]()
  private val spawned = new AtomicInteger(0)

  private val childTraces =
    new ConcurrentHashMap[String, zio.Ref[Vector[
      raider.core.trace.TraceEvent
    ]]]()

  private def fieldJson(k: String, v: String): String = s""""$k":${v.toJson}"""

  private def feedback(pairs: String*): ZIO[Any, RaiderError, String] =
    ZIO.succeed(pairs.mkString("{", ",", "}"))

  private def delegateInvoke(
      argumentsJson: String
  ): ZIO[Scope, RaiderError, String] =
    argumentsJson.fromJson[DelegationToolset.DelegateArgs] match
      case Left(err) =>
        feedback(
          fieldJson("error", "invalid_arguments"),
          fieldJson("detail", err.take(80))
        )
      case Right(args) if !allowedAgents.contains(args.agent) =>
        feedback(
          fieldJson("error", "unknown_agent"),
          fieldJson("name", args.agent),
          fieldJson("known", allowedAgents.toVector.sorted.mkString(","))
        )
      case Right(args) =>
        val n = spawned.incrementAndGet()
        if n > limits.maxChildren then
          feedback(
            fieldJson("error", "max_children"),
            "\"limit\":" + limits.maxChildren.toString
          )
        else
          for
            traceRef <- zio.Ref.make(Vector.empty[raider.core.trace.TraceEvent])
            childLoop = AgentLoop(
              backend,
              baseRegistry,
              s"scripted:${args.agent}",
              admission,
              root,
              raider.runtime.budget.CostEstimate.Unknown,
              ev => traceRef.update(_ :+ ev)
            )
            handle <- jobs.start(
              root,
              s"delegate:${args.agent}",
              Task(
                childLoop.runText(
                  List(RequestMessage("user", args.input)),
                  limits,
                  limits.maxAttempts
                )
              )
            )
            _ <- ZIO.succeed(children.put(handle.id.value, handle))
            _ <- ZIO.succeed(childTraces.put(handle.id.value, traceRef))
          yield s"""{"child_id":${handle.id.value.toJson},"state":"Running"}"""

  private def awaitInvoke(
      argumentsJson: String
  ): ZIO[Scope, RaiderError, String] =
    argumentsJson.fromJson[DelegationToolset.ChildArgs] match
      case Left(err) =>
        feedback(
          fieldJson("error", "invalid_arguments"),
          fieldJson("detail", err.take(80))
        )
      case Right(args) =>
        Option(children.get(args.child_id)) match
          case None =>
            feedback(
              fieldJson("error", "unknown_child"),
              fieldJson("child_id", args.child_id)
            )
          case Some(handle) =>
            // suspends the parent loop; holds NO model slot (§11) — the tool
            // slot is held for the duration, hence maxConcurrentTools >= 2
            jobs.await(handle).either.map {
              case Right(result) =>
                s"""{"child_id":${args.child_id.toJson},""" +
                  s""""status":"Succeeded","result":${result.toJson}}"""
              case Left(e) =>
                s"""{"child_id":${args.child_id.toJson},""" +
                  s""""status":"Failed","error":{"code":${e.code.toJson},""" +
                  s""""detail":${e.detail.toJson}}}"""
            }

  private def cancelInvoke(
      argumentsJson: String
  ): ZIO[Scope, RaiderError, String] =
    argumentsJson.fromJson[DelegationToolset.ChildArgs] match
      case Left(err) =>
        feedback(
          fieldJson("error", "invalid_arguments"),
          fieldJson("detail", err.take(80))
        )
      case Right(args) =>
        Option(children.get(args.child_id)) match
          case None =>
            feedback(
              fieldJson("error", "unknown_child"),
              fieldJson("child_id", args.child_id)
            )
          case Some(handle) =>
            jobs
              .cancelAny(handle)
              .map(c =>
                s"""{"child_id":${args.child_id.toJson},"cancelled":$c}"""
              )

  private def listInvoke: ZIO[Scope, RaiderError, String] =
    ZIO
      .foreach(children.values.asScala.toList) { h =>
        jobs
          .snapshotAny(h)
          .map(s =>
            s"""{"child_id":${s.id.value.toJson},"status":"${s.status}"}"""
          )
      }
      .map(list => s"""{"agents":${list.mkString("[", ",", "]")}}""")

  private def toolOf(
      toolName: String,
      descr: String,
      run: String => ZIO[Scope, RaiderError, String]
  ): Tool =
    new Tool:
      def name = toolName
      def version = 1
      def description = descr
      def recovery = RecoveryClass.ReadOnly
      def timeoutMs = 600000L // await_agent legitimately parks long
      def capabilities = ToolCapabilities(concurrentSafe = false)
      def invoke(argumentsJson: String) = run(argumentsJson)

  private val delegateT = toolOf(
    "delegate",
    "start a child agent; args {\"agent\",\"input\"}",
    delegateInvoke
  )

  private val awaitT =
    toolOf("await_agent", "wait for a child; args {\"child_id\"}", awaitInvoke)

  private val cancelT =
    toolOf("cancel_agent", "cancel a child; args {\"child_id\"}", cancelInvoke)

  private val listT = toolOf(
    "list_agents",
    "list child agents and statuses; args {}",
    _ => listInvoke
  )

  /** The parent loop's registry: base tools + delegation tools. */
  def augment(base: ToolRegistry): Either[RaiderError, ToolRegistry] =
    ToolRegistry.build(
      base.tools.values.toList ++ List(delegateT, awaitT, cancelT, listT)
    )

  /** Child snapshot ids (admin/tests). */
  def childIds: List[String] =
    children.keys().asScala.toList.sorted

  /** Full trace of a child (for :log / transcript output). */
  def childTrace(
      childId: String
  ): ZIO[Any, Nothing, Option[List[raider.core.trace.TraceEvent]]] =
    Option(childTraces.get(childId)) match
      case Some(ref) => ref.get.map(v => Some(v.toList))
      case None      => ZIO.none

  /** Settle wait for admin/tests: waits until the child job is terminal. */
  def awaitSettled(childId: String): ZIO[Any, Nothing, Unit] =
    Option(children.get(childId)) match
      case None    => ZIO.unit
      case Some(h) => jobs.awaitSettled(h)

object DelegationToolset:

  final case class DelegateArgs(agent: String, input: String)

  object DelegateArgs:
    given zio.json.JsonDecoder[DelegateArgs] = zio.json.DeriveJsonDecoder.gen

  final case class ChildArgs(child_id: String)

  object ChildArgs:
    given zio.json.JsonDecoder[ChildArgs] = zio.json.DeriveJsonDecoder.gen

  val ToolNames: List[String] =
    List("delegate", "await_agent", "cancel_agent", "list_agents")

  /** Validated construction (BUD-03 discipline): awaiting parents hold one tool
    * slot, so a tool-using child needs a second slot; the base registry must
    * not shadow delegation tool names.
    */
  def make(
      jobs: JobManager,
      backend: ModelBackend,
      admission: Admission,
      limits: BudgetLimits,
      baseRegistry: ToolRegistry,
      allowedAgents: Set[String],
      root: RootId
  ): Either[RaiderError, DelegationToolset] =
    if limits.maxConcurrentTools < 2 then
      Left(
        RaiderError.Configuration(
          "delegation needs maxConcurrentTools >= 2: the awaiting parent " +
            "holds one tool slot while children run (BUD-03 no-deadlock)"
        )
      )
    else
      val clash = ToolNames.filter(n => baseRegistry.lookup(n).isDefined)
      if clash.nonEmpty then
        Left(
          RaiderError.Configuration(
            s"base registry must not define delegation tools: " +
              clash.mkString(", ")
          )
        )
      else
        Right(
          new DelegationToolset(
            jobs,
            backend,
            admission,
            limits,
            baseRegistry,
            allowedAgents,
            root
          )
        )

end DelegationToolset
