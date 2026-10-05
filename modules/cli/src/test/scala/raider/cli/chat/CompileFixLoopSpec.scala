package raider.cli.chat

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.loop.AgentLoop
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** Compile-fix loop fixtures (nightly Phase 4.1): proc_run compile failures go
  * back to the model with the errors as context, up to maxRounds fix cycles; a
  * passing compile never spends a model call; persistent failure reports the
  * remaining errors.
  */
object CompileFixLoopSpec extends ZIOSpecDefault:

  private val att = AttemptId("cf-att")

  /** Scripted text backend: every call answers with `reply`, records requests.
    */
  private final class ScriptedText(reply: String) extends ModelBackend:
    val requests = new CopyOnWriteArrayList[ModelRequest]()

    val capabilities: ModelCapabilities = ModelCapabilities(
      CapabilityStatus.Supported,
      CapabilityStatus.Supported,
      CapabilityStatus.Unknown
    )

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap:
        ZIO.succeed:
          requests.add(input)
          ZStream(
            ModelEvent.Started(att),
            ModelEvent.TextDelta(att, reply),
            ModelEvent.Finished(att, "stop")
          )

  /** proc_run stub returning pre-scripted tool outputs in order. */
  private final class ScriptedProc(outputs: List[String]):
    private val calls = AtomicInteger(0)
    private val buffer = scala.collection.mutable.ListBuffer.empty[String]

    val tool: Tool = new Tool:
      def name = "proc_run"
      def version = 1
      def description = "scripted proc_run stub"
      def recovery = RecoveryClass.ReadOnly
      def timeoutMs = 1000L
      def capabilities = ToolCapabilities(concurrentSafe = false)
      def invoke(argumentsJson: String) =
        ZIO.succeed {
          buffer.synchronized {
            val i = calls.getAndIncrement()
            if i < outputs.length then outputs(i) else outputs.last
          }
        }

    def callCount: Int = calls.get()

  private def compileFailJson(
      exitCode: Int,
      file: String,
      line: Int,
      msg: String
  ): String =
    val parsed =
      s"""{"type":"sbt_compile","success":false,"errors":[""" +
        s"""{"file":"$file","line":$line,"col":null,"message":"$msg"}]}"""
    s"""{"exitCode":$exitCode,"stdout":"[error] -- Error: $file:$line $msg",""" +
      s""""stderr":"","truncated":false,"timedOut":false,"parsed":$parsed}"""

  private val compileOkJson =
    """{"exitCode":0,"stdout":"[info] done compiling","stderr":"",""" +
      """"truncated":false,"timedOut":false,"parsed":{"type":"sbt_compile","success":true,"errors":[]}}"""

  private def makeLoop(backend: ModelBackend, proc: ScriptedProc) =
    for
      admission <- Admission.make(BudgetLimits.make(maxAttempts = 64))
      registry <- ZIO
        .fromEither(ToolRegistry.build(List(proc.tool)))
      limits = BudgetLimits.make(maxAttempts = 64).toOption.get
      history <- Ref.make(List.empty[RequestMessage])
    yield (
      AgentLoop(backend, registry, "m", admission, AgentLoop.newRoot()),
      limits,
      history
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("CompileFixLoop")(
      test("errors go back to the model; loop ends when compile passes") {
        val proc = ScriptedProc(
          List(
            compileFailJson(1, "/ws/A.scala", 10, "type mismatch"),
            compileFailJson(1, "/ws/B.scala", 3, "not found"),
            compileOkJson
          )
        )
        val backend = ScriptedText("fixed it")
        for
          (loop, limits, history) <- makeLoop(backend, proc)
          outcome <- CompileFixLoop.run(loop, loop.tools, limits, history)
          reqs = backend.requests.asScala.toList
        yield assertTrue(
          outcome.success,
          outcome.attempts == 3,
          proc.callCount == 3,
          reqs.size == 2, // two fix rounds (model NOT called for the pass)
          reqs.head.messages.exists(_.content.contains("A.scala")),
          reqs.last.messages.exists(_.content.contains("B.scala")),
          reqs.last.messages.exists(
            _.content.contains("type mismatch")
          ) // first errors stay in history context
        )
      },
      test("clean compile spends no model call") {
        val proc = ScriptedProc(List(compileOkJson))
        val backend = ScriptedText("should not be called")
        for
          (loop, limits, history) <- makeLoop(backend, proc)
          outcome <- CompileFixLoop.run(loop, loop.tools, limits, history)
        yield assertTrue(
          outcome.success,
          outcome.attempts == 1,
          backend.requests.isEmpty,
          outcome.lastErrors.isEmpty
        )
      },
      test("persistent failure: 1+maxRounds compiles, errors reported") {
        val proc = ScriptedProc(
          List(compileFailJson(1, "/ws/A.scala", 1, "boom"))
        )
        val backend = ScriptedText("trying")
        for
          (loop, limits, history) <- makeLoop(backend, proc)
          outcome <- CompileFixLoop.run(
            loop,
            loop.tools,
            limits,
            history,
            maxRounds = 2
          )
        yield assertTrue(
          !outcome.success,
          outcome.attempts == 3, // 1 initial + 2 fix rounds
          backend.requests.asScala.size == 2,
          outcome.lastErrors.exists(_.contains("A.scala"))
        )
      },
      test("missing proc_run is a typed input validation") {
        val backend = ScriptedText("x")
        for
          admission <- Admission.make(BudgetLimits.make())
          registry <- ZIO.fromEither(ToolRegistry.build(Nil))
          limits = BudgetLimits.make(maxAttempts = 8).toOption.get
          history <- Ref.make(List.empty[RequestMessage])
          loop = AgentLoop(
            backend,
            registry,
            "m",
            admission,
            AgentLoop.newRoot()
          )
          err <- CompileFixLoop
            .run(loop, registry, limits, history)
            .flip
        yield assertTrue(err.isInstanceOf[RaiderError.InputValidation])
      }
    )
