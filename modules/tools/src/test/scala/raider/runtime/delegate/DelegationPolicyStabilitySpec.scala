package raider.runtime.delegate

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.jobs.JobManager
import raider.tools.CodingToolset
import zio.json.*
import zio.test.*
import zio.stream.ZStream
import zio.{Duration as ZDuration, Scope, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** RAI-259 acceptance, delegated-children path: the write-path policy is STABLE
  * under concurrent tool calls from PARALLEL delegated children.
  *
  * Children share the parent's `CodingToolset` registry instance (and thus the
  * immutable `WritePathPolicy` and the mutation semaphore), so every child's
  * out-of-root mutation must receive the SAME typed denial — an explicit
  * `RA-TOOLDENY` JSON in that child's own tool feedback, visible in the
  * recorded model requests — while in-root writes from sibling children land
  * unharmed, and NO out-of-root artifact ever appears.
  *
  *   - CONC-01 — one in-root writer and one escaper forked in parallel: the
  *     writer's file lands, the escaper observes `outcome:denied` (its own
  *     round-2 answer, marker-dispatched scripted backend), and the escape file
  *     does not exist.
  *   - CONC-02 — four parallel children (two distinct in-root writes, two
  *     escapes): both escapes produce the identical denial code, both writes
  *     land, no out-of-root artifacts; deterministic invariants only — which
  *     child finishes first is NOT pinned.
  *
  * No network, no spend, real workspace tools.
  */
object DelegationPolicyStabilitySpec extends ZIOSpecDefault:

  private val root = RootId("pp-root")

  private val tenSec = ZDuration.fromSeconds(15)

  private def lims: BudgetLimits =
    BudgetLimits
      .make(
        maxConcurrentLlm = 4,
        maxConcurrentTools = 4,
        maxAttempts = 16,
        maxChildren = 4
      )
      .fold(e => throw e, identity)

  /** Marker-dispatched scripted backend: every request is inspected for its
    * child marker in the (accumulating) user message; each marker owns an
    * independent turn counter, so parallel children replay deterministic,
    * marker-local scripts regardless of scheduling.
    *
    * Round 0 emits the configured fs_edit call for that marker; any later round
    * reads the accumulated tool feedback in the request and answers
    * `outcome:denied` / `outcome:stale` / `outcome:applied` from what the child
    * ACTUALLY saw.
    */
  private final class MarkerBackend(
      calls: Map[
        String,
        (String, String, String)
      ] // marker -> (tool, callId, args)
  ) extends ModelBackend:
    private val counters = new ConcurrentHashMap[String, AtomicInteger]
    val recorded = new CopyOnWriteArrayList[ModelRequest]

    def capabilities: ModelCapabilities =
      ModelCapabilities(
        CapabilityStatus.Supported,
        CapabilityStatus.Supported,
        CapabilityStatus.Unknown
      )

    def requests: List[ModelRequest] = recorded.asScala.toList

    private def markerOf(input: ModelRequest): String =
      calls.keys
        .find(m =>
          input.messages
            .exists(msg => msg.role == "user" && msg.content.contains(m))
        )
        .getOrElse("<no-marker>")

    def stream(
        input: ModelRequest
    ): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap:
        ZIO.succeed:
          recorded.add(input)
          val marker = markerOf(input)
          val counter =
            counters.computeIfAbsent(marker, _ => new AtomicInteger(0))
          val k = counter.getAndIncrement()
          val att = AttemptId(s"$marker-t$k")
          if k == 0 then
            val (tool, callId, args) = calls(marker)
            ZStream(
              ModelEvent.Started(att),
              ModelEvent.ToolCallReady(att, ToolCallId(callId), tool, args),
              ModelEvent.Finished(att, "tool_round")
            )
          else
            val feedback =
              input.messages.filter(_.role == "tool").map(_.content).mkString
            val outcome =
              if feedback.contains("RA-TOOLDENY") then "outcome:denied"
              else if feedback.contains("STALE") then "outcome:stale"
              else "outcome:applied"
            ZStream(
              ModelEvent.Started(att),
              ModelEvent.TextDelta(att, outcome),
              ModelEvent.Finished(att, "stop")
            )

  private def childIdOf(feedback: String): String =
    """"child_id":"(j_\d+)"""".r
      .findFirstMatchIn(feedback)
      .map(_.group(1))
      .getOrElse("j_missing")

  private def withWorkspace[A](
      f: Path => ZIO[Any, Any, A]
  ): ZIO[Any, Any, A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt(Files.createTempDirectory("raider-pp-delegate"))
    )(_ => ZIO.unit) { dir => f(dir) }

  /** A wired delegation context over a REAL CodingToolset registry as the
    * baseRegistry — the exact registry a delegated child receives.
    */
  private def wire(
      dir: Path,
      backend: ModelBackend
  ): ZIO[Any, Any, ToolRegistry] =
    for
      jobs <- JobManager.make(onTransition = None)
      adm <- Admission.make(Right(lims))
      coding <- CodingToolset.make(dir.toString)
      baseReg = coding.registry.fold(e => throw e, identity)
      toolset <- ZIO.fromEither(
        DelegationToolset.make(
          jobs,
          backend,
          adm,
          lims,
          baseReg,
          Set("coder"),
          root
        )
      )
      registry = toolset
        .augment(baseReg)
        .fold(e => throw e, identity)
    yield registry

  private def doDelegate(
      registry: ToolRegistry,
      input: String
  ): ZIO[Any, Any, String] =
    for
      tool <- ZIO.succeed(
        registry
          .lookup("delegate")
          .getOrElse(throw new AssertionError("delegate tool missing"))
      )
      feedback <- ZIO
        .scoped(tool.invoke(s"""{"agent":"coder","input":"$input"}"""))
        .timeout(tenSec)
    yield feedback.getOrElse("<timeout>")

  private def doAwait(
      registry: ToolRegistry,
      childId: String
  ): ZIO[Any, Any, String] =
    for
      tool <- ZIO.succeed(
        registry
          .lookup("await_agent")
          .getOrElse(throw new AssertionError("await_agent tool missing"))
      )
      feedback <- ZIO
        .scoped(tool.invoke(s"""{"child_id":"$childId"}"""))
        .timeout(tenSec)
    yield feedback.getOrElse("<timeout>")

  /** Delegate, then await: the child's final text comes back inside the
    * await_agent feedback JSON.
    */
  private def runChild(
      registry: ToolRegistry,
      marker: String
  ): ZIO[Any, Any, String] =
    for
      fb <- doDelegate(registry, marker)
      done <- doAwait(registry, childIdOf(fb))
    yield done

  private def editArgs(path: String, content: String): String =
    s"""{"path":${path.toJson},"content":${content.toJson},"expected_sha256":""}"""

  /** The child's own round-1 tool feedback (what the escaper actually saw). */
  private def toolFeedbackFor(
      backend: MarkerBackend,
      marker: String
  ): String =
    backend.requests
      .filter(req =>
        req.messages.exists(m => m.role == "user" && m.content.contains(marker))
      )
      .flatMap(_.messages)
      .filter(_.role == "tool")
      .map(_.content)
      .mkString

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite(
      "write-path policy stability under parallel delegated children (RAI-259)"
    )(
      test(
        "CONC-01: parallel in-root writer + escaper — write lands, escaper denied typed in its own transcript, nothing escapes"
      ) {
        withWorkspace { dir =>
          val backend = MarkerBackend(
            Map(
              "writer-a" -> (
                "fs_edit",
                "wa-1",
                editArgs("notes-a.txt", "inside\n")
              ),
              "escaper-b" -> (
                "fs_edit",
                "eb-1",
                editArgs("../pp-conc01-escape.txt", "pwned")
              )
            )
          )
          for
            registry <- wire(dir, backend)
            outcomes <- ZIO.collectAllPar(
              List(
                runChild(registry, "writer-a"),
                runChild(registry, "escaper-b")
              )
            )
          yield
            // EAGER capture (assertTrue thunks run after tmp-dir cleanup)
            val notesOnDisk =
              Files.readString(dir.resolve("notes-a.txt"), UTF_8)
            val leak =
              Files.exists(dir.getParent.resolve("pp-conc01-escape.txt"))
            val escaperFeedback = toolFeedbackFor(backend, "escaper-b")
            assertTrue(
              outcomes.count(_.contains("outcome:applied")) == 1,
              outcomes.count(_.contains("outcome:denied")) == 1,
              notesOnDisk == "inside\n",
              !leak,
              escaperFeedback.contains("\"error\":\"RA-TOOLDENY\""),
              escaperFeedback.contains("escapes workspace")
            )
        }
      },
      test(
        "CONC-02: four parallel children (two in-root writes, two escapes) — identical denials, both writes land, zero out-of-root artifacts"
      ) {
        withWorkspace { dir =>
          val backend = MarkerBackend(
            Map(
              "writer-w1" -> (
                "fs_edit",
                "w1-1",
                editArgs("w1.txt", "one\n")
              ),
              "writer-w2" -> (
                "fs_edit",
                "w2-1",
                // flat path: fs_edit does not create parent directories
                editArgs("w2.txt", "two\n")
              ),
              "escaper-e1" -> (
                "fs_edit",
                "e1-1",
                editArgs("../pp-conc02-escape-1.txt", "pwned")
              ),
              "escaper-e2" -> (
                "fs_edit",
                "e2-1",
                editArgs("link/e2.txt", "pwned")
              )
            )
          )
          for
            registry <- wire(dir, backend)
            // e2 goes through a symlinked directory pointing outside — the
            // exact RAI-259 hole, now under parallel load
            outside = Files.createTempDirectory("raider-pp-conc02-outside")
            _ <- ZIO.attempt(
              Files.createSymbolicLink(dir.resolve("link"), outside)
            )
            outcomes <- ZIO.collectAllPar(
              List(
                runChild(registry, "writer-w1"),
                runChild(registry, "writer-w2"),
                runChild(registry, "escaper-e1"),
                runChild(registry, "escaper-e2")
              )
            )
          yield
            // EAGER capture (assertTrue thunks run after tmp-dir cleanup)
            val w1 = Files.readString(dir.resolve("w1.txt"), UTF_8)
            val w2 = Files.readString(dir.resolve("w2.txt"), UTF_8)
            val leaks =
              Files
                .list(dir.getParent)
                .iterator()
                .asScala
                .map(_.getFileName.toString)
                .filter(_.startsWith("pp-conc02"))
                .toList
            val outsideEntries =
              Files.list(outside).iterator().asScala.toList
            val e1Feedback = toolFeedbackFor(backend, "escaper-e1")
            val e2Feedback = toolFeedbackFor(backend, "escaper-e2")
            assertTrue(
              outcomes.count(_.contains("outcome:applied")) == 2,
              outcomes.count(_.contains("outcome:denied")) == 2,
              w1 == "one\n",
              w2 == "two\n",
              leaks.isEmpty,
              outsideEntries.isEmpty,
              e1Feedback.contains("\"error\":\"RA-TOOLDENY\""),
              e2Feedback.contains("\"error\":\"RA-TOOLDENY\""),
              e2Feedback.contains("symlink escapes workspace")
            )
        }
      }
    )
