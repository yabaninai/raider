package raider.runtime.delegate

import raider.core.*
import raider.runtime.admission.{Admission, SlotKind}
import raider.runtime.budget.{BudgetLimits, CostEstimate, MicroUsd}
import raider.runtime.jobs.{JobManager, JobStatus}
import raider.runtime.loop.AgentLoop
import zio.stream.ZStream
import zio.test.{live, TestAspect, assertTrue, test, ZIOSpecDefault, suite}
import zio.duration2DurationOps
import zio.{Duration as ZDuration, Promise, Scope, ZIO}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

/** RAI-257 acceptance (PROP-01..04): configured-model propagation into
  * delegated children.
  *
  *   - PROP-01: a toolset wired with the parent's configured model
  *     (`defaultChildModel`) dispatches every child provider request under that
  *     model — the `scripted:<agent>` label never reaches a live backend.
  *   - PROP-02: an explicit delegate-args `model` override beats the parent
  *     default (per-child override wins).
  *   - PROP-03: the historical default (no `defaultChildModel`) keeps the
  *     scripted-backend label `scripted:<agent>` — byte-compatible scripted
  *     corpus behavior.
  *   - PROP-04: cancellation/budget-ownership of a propagated-model child: a
  *     child parked mid-stream under the propagated model is OOB-cancelled,
  *     releases its model slot, and its attempt stays on the shared run root's
  *     books (admission attempts + unpriced-ledger count), never silently
  *     dropped.
  *
  * Deterministic throughout (RAI-092 discipline): content-routed recording
  * backend, gate/promise parks, bounded wall-clock caps only where a real-time
  * claim is the assertion; terminal statuses are read after settle awaits.
  */
object DelegationModelPropagationSpec extends ZIOSpecDefault:

  private val a = AttemptId("rai257-att")
  private val c1 = ToolCallId("call-1")
  private val c2 = ToolCallId("call-2")

  private val fiveSec = ZDuration.fromSeconds(5)
  private val tenSec = ZDuration.fromSeconds(10)

  private def lims(
      llm: Int = 2,
      tools: Int = 2,
      attempts: Int = 16,
      children: Int = 4
  ): Either[RaiderError, BudgetLimits] =
    BudgetLimits.make(
      maxConcurrentLlm = llm,
      maxConcurrentTools = tools,
      maxAttempts = attempts,
      maxChildren = children
    )

  private def limsV(
      llm: Int = 2,
      tools: Int = 2,
      attempts: Int = 16,
      children: Int = 4
  ): BudgetLimits =
    lims(llm, tools, attempts, children).fold(e => throw e, identity)

  /** The delegated child's FIRST turn: exactly one message with the delegated
    * input (child loops run without prior history).
    */
  private def isChildTurn(req: ModelRequest, childInput: String): Boolean =
    req.messages.size == 1 && req.messages.headOption
      .exists(_.content == childInput)

  /** Content-routed backend that RECORDS the model id of every provider request
    * (RAI-257) and can park an input mid-stream on a gate (the parked caller
    * holds its model slot — PROP-04). Routing is content-based, never a
    * model-string prefix: the parent campaign issues `delegate` (args JSON is
    * configurable so PROP-02 can carry an explicit model override), then
    * `await_agent` for the real child id, then finishes; the child's single
    * turn answers with text.
    */
  private final class ParkDef(
      val gate: Promise[Nothing, Unit],
      val parked: Promise[Nothing, Unit]
  )

  private final class RecordingBackend(
      childInput: String,
      delegateJson: String
  ) extends ModelBackend:
    private val parks = new ConcurrentHashMap[String, ParkDef]()
    private val requests = new CopyOnWriteArrayList[(String, Boolean)]()

    def parkOn(
        input: String,
        gate: Promise[Nothing, Unit],
        parked: Promise[Nothing, Unit]
    ): Unit =
      val _ = parks.put(input, ParkDef(gate, parked))

    /** Every recorded request: (model id, is-child-turn). */
    def recorded: List[(String, Boolean)] = requests.asScala.toList

    def capabilities: ModelCapabilities = ModelCapabilities(
      CapabilityStatus.Supported,
      CapabilityStatus.Supported,
      CapabilityStatus.Unknown
    )

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap:
        ZIO.succeed:
          requests.add((input.model, isChildTurn(input, childInput)))
          val single = input.messages.size == 1
          val head =
            input.messages.headOption.map(_.content).getOrElse("")
          val pd = if single then Option(parks.get(head)) else None
          val delegateDone = input.messages.exists(m =>
            m.role == "tool" && m.content.contains("call-1")
          )
          val awaitDone = input.messages.exists(m =>
            m.role == "tool" && m.content.contains("call-2")
          )
          val childId = {
            val content = input.messages.collect {
              case RequestMessage("tool", c) => c
            }.mkString
            val r = """"child_id":"(j_\d+)"""".r
            r.findFirstMatchIn(content).map(_.group(1)).getOrElse("j_missing")
          }
          val parentDelegate = Vector(
            ModelEvent.ToolCallReady(a, c1, "delegate", delegateJson),
            ModelEvent.Finished(a, "r")
          )
          val parentTurn2: Vector[ModelEvent] = Vector(
            ModelEvent.ToolCallReady(
              a,
              c2,
              "await_agent",
              s"""{"child_id":"$childId"}"""
            ),
            ModelEvent.Finished(a, "r")
          )
          val parentFinal = Vector(
            ModelEvent.TextDelta(a, "done"),
            ModelEvent.Finished(a, "stop")
          )
          val childFinish = Vector(
            ModelEvent.TextDelta(a, "child-done"),
            ModelEvent.Finished(a, "stop")
          )
          val parkStream: ZStream[Any, Nothing, Nothing] = pd match
            case None => ZStream.empty
            case Some(p) =>
              val pre = ZStream.fromZIO(p.parked.succeed(()).unit)
              val wait = ZStream.fromZIO(p.gate.await)
              (pre ++ wait).drain
          val body: Vector[ModelEvent] =
            if single && head == "u" then parentDelegate
            else if pd.isDefined then childFinish
            else if awaitDone then parentFinal
            else if delegateDone then parentTurn2
            // the child's single (unparked) turn — and the defensive default
            else childFinish
          parkStream ++ ZStream.fromIterable(
            ModelEvent.Started(a) +: body
          )

  /** Owner-side delegate invocation through the REAL tool surface; `argsJson`
    * is the full delegate arguments (PROP-02 adds the model override).
    */
  private def delegateViaTool(
      toolset: DelegationToolset,
      registry: ToolRegistry,
      argsJson: String
  ): ZIO[Scope, RaiderError, Unit] =
    registry.lookup("delegate") match
      case None    => ZIO.fail(RaiderError.Configuration("no delegate tool"))
      case Some(t) => t.invoke(argsJson).unit

  /** The shared PROP-01/02/03 flow: a parent campaign on `parentModel`
    * delegates one scout; the recorded request models are classified into
    * parent/child by content.
    */
  private def propagationOnce(
      parentModel: String,
      childDefault: Option[String],
      delegateJson: String
  ): ZIO[Any, Throwable, (List[String], List[String], Boolean, String)] =
    for
      jobs <- JobManager.make()
      adm <- Admission.make(lims(llm = 2))
      backend = RecordingBackend("find x", delegateJson)
      root = AgentLoop.newRoot()
      toolset <- ZIO.fromEither(
        DelegationToolset.make(
          jobs,
          backend,
          adm,
          limsV(llm = 2),
          ToolRegistry.empty,
          Set("scout"),
          root,
          defaultChildModel = childDefault.getOrElse(
            DelegationToolset.ScriptedModel
          )
        )
      )
      registry = toolset
        .augment(ToolRegistry.empty)
        .fold(e => throw e, identity)
      loop = AgentLoop(
        backend,
        registry,
        parentModel,
        adm,
        root,
        CostEstimate.Priced(MicroUsd(50L))
      )
      answer <- loop
        .runText(List(RequestMessage("user", "u")), limsV(llm = 2), 8)
        .timeout(tenSec)
        .someOrFailException
      kids = toolset.childIds
      _ <- ZIO.when(kids.size != 1)(
        ZIO.fail(RaiderError.OutputValidation(s"kids=$kids"))
      )
      _ <- toolset.awaitSettled(kids.head).timeout(fiveSec).someOrFailException
      snap <- jobs.lookup(JobId(kids.head))
      rec = backend.recorded
      childModels = rec.collect { case (m, true) => m }
      parentModels = rec.collect { case (m, false) => m }
      childOk = snap.exists(_.status == JobStatus.Succeeded)
    yield (childModels, parentModels, childOk, answer)

  def spec = suite("RAI-257: configured-model propagation into children")(
    // PROP-01: the parent's configured model IS the child's request model.
    test(
      "PROP-01: defaultChildModel propagates to the delegated child's provider requests"
    ) {
      live {
        for (childModels, parentModels, childOk, answer) <- propagationOnce(
            "live-model",
            Some("live-model"),
            """{"agent":"scout","input":"find x"}"""
          )
        yield assertTrue(
          answer.contains("done"),
          childOk, // the child settled Succeeded
          childModels.nonEmpty,
          childModels.forall(_ == "live-model"),
          parentModels.nonEmpty,
          parentModels.forall(_ == "live-model")
        )
      }
    },
    // PROP-02: an explicit delegate-args model beats the parent default.
    test(
      "PROP-02: explicit delegate-args model override beats the parent default"
    ) {
      live {
        for (childModels, parentModels, childOk, answer) <- propagationOnce(
            "live-model",
            Some("live-model"),
            """{"agent":"scout","input":"find x","model":"override-model"}"""
          )
        yield assertTrue(
          answer.contains("done"),
          childOk,
          childModels.nonEmpty,
          childModels.forall(_ == "override-model"),
          parentModels.nonEmpty,
          parentModels.forall(_ == "live-model")
        )
      }
    },
    // PROP-03: the historical default keeps the scripted-backend label —
    // byte-compatible scripted corpus behavior.
    test(
      "PROP-03: scripted sentinel default keeps the scripted:<agent> child label"
    ) {
      live {
        for (childModels, parentModels, childOk, answer) <- propagationOnce(
            "scripted:coder",
            None,
            """{"agent":"scout","input":"find x"}"""
          )
        yield assertTrue(
          answer.contains("done"),
          childOk,
          childModels.nonEmpty,
          childModels.forall(_ == "scripted:scout"),
          parentModels.nonEmpty,
          parentModels.forall(_ == "scripted:coder")
        )
      }
    },
    // PROP-04: a propagated-model child parked mid-stream under maxLLM=1 is
    // OOB-cancelled, releases the model slot, and its attempt stays on the
    // SHARED run root's books (admission attempts + unpriced ledger).
    test(
      "PROP-04: cancelled propagated-model child releases its slot and stays on the root's budget books"
    ) {
      live {
        for
          jobs <- JobManager.make()
          adm <- Admission.make(lims(llm = 1))
          gate <- Promise.make[Nothing, Unit]
          parked <- Promise.make[Nothing, Unit]
          backend = RecordingBackend(
            "slow",
            """{"agent":"scout","input":"slow"}"""
          )
          _ = backend.parkOn("slow", gate, parked)
          root = AgentLoop.newRoot()
          toolset <- ZIO.fromEither(
            DelegationToolset.make(
              jobs,
              backend,
              adm,
              limsV(llm = 1),
              ToolRegistry.empty,
              Set("scout"),
              root,
              defaultChildModel = "live-model"
            )
          )
          registry = toolset
            .augment(ToolRegistry.empty)
            .fold(e => throw e, identity)
          loop = AgentLoop(
            backend,
            registry,
            "live-model",
            adm,
            root,
            CostEstimate.Priced(MicroUsd(50L))
          )
          parentF <- loop
            .runText(List(RequestMessage("user", "u")), limsV(llm = 1), 8)
            .forkDaemon
          _ <- parked.await.timeout(fiveSec).someOrFailException
          kids = toolset.childIds
          _ <- ZIO.when(kids.size != 1)(
            ZIO.fail(RaiderError.OutputValidation(s"kids=$kids"))
          )
          // OOB cancel OUTSIDE model admission (the child holds the only slot)
          _ <- (toolset.cancelOutOfBand(kids.head) *>
            toolset.awaitSettled(kids.head))
            .timeout(fiveSec)
            .someOrFailException
          snap <- jobs.lookup(JobId(kids.head))
          answer <- parentF.join.timeout(tenSec)
          modelFree <- adm.availableSlots(SlotKind.Model)
          rootAttempts <- adm.attempts(root)
          usage <- adm.budgetLedger.usage
          childModels = backend.recorded.collect { case (m, true) => m }
        yield assertTrue(
          // the child ran its (parked) attempt under the PROPAGATED model
          childModels.nonEmpty,
          childModels.forall(_ == "live-model"),
          snap.exists(_.status == JobStatus.Cancelled),
          answer.contains("done"), // the parent unblocked and finished
          modelFree == 1, // the cancelled child released the slot
          // budget OWNERSHIP on the shared root: the child's attempt is
          // counted for the run root and its Unknown-estimate round is on the
          // ledger (never silently dropped)
          rootAttempts >= 2,
          usage.unpricedAttempts >= 1
        )
      }
    }
  ) @@ TestAspect.sequential

end DelegationModelPropagationSpec
