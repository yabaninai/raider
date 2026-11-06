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
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** RAI-260 acceptance, delegated-children path: the coding toolset's new
  * mutation tools (`fs_patch_multi`, `fs_delete`) reach the CHILD loop verbatim
  * through `DelegationToolset.make(..., baseRegistry, ...)` — a delegated child
  * can apply a structured multi-file batch against the REAL workspace — and
  * parallel delegated children touching the SAME file serialize safely: exactly
  * one batch lands, the other is stale-refused with typed feedback (fail
  * closed), and the disk is never corrupted.
  *
  * DELMUT-01 — one child applies a multi-file batch; the report reaches the
  * child as structured tool feedback and the child's final answer carries the
  * outcome back to the parent through `await_agent`.
  *
  * DELMUT-02 — two children forked IN PARALLEL, both fs_patch_multi the same
  * shared file from the same base provenance: deterministic invariants only —
  * exactly one `outcome:applied` and one `outcome:stale` (which child wins is
  * NOT pinned), the final disk content equals exactly one of the two intended
  * results, and a STALE PATCH refusal was observed. The child echoes its own
  * round-1 tool feedback in round 2 (marker-dispatched scripted backend), so
  * the assertion reads what the child ACTUALLY saw.
  *
  * No network, no spend, real workspace tools.
  */
object DelegationMutationToolsSpec extends ZIOSpecDefault:

  private val root = RootId("d-mut-root")

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

  private def sha256Hex(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(UTF_8))
      .map("%02x" format _)
      .mkString

  /** Marker-dispatched scripted backend: every request is inspected for its
    * child marker in the (accumulating) user message, and each marker owns an
    * independent turn counter — so two interleaved children replay
    * deterministic, marker-local scripts regardless of scheduling.
    *
    * Round 0 emits the fs_patch_multi call for that marker; any later round
    * reads the accumulated tool feedback in the request and answers
    * `outcome:stale` if a STALE PATCH refusal is present, else
    * `outcome:applied`.
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
              if feedback.contains("STALE PATCH") then "outcome:stale"
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
      f: java.nio.file.Path => ZIO[Any, Any, A]
  ): ZIO[Any, Any, A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt(Files.createTempDirectory("raider-delmut"))
    )(_ => ZIO.unit) { dir => f(dir) }

  /** A wired delegation context over a REAL CodingToolset registry as the
    * baseRegistry — the exact registry a delegated child receives.
    */
  private def wire(
      dir: java.nio.file.Path,
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
    * await_agent feedback JSON (`"result":...`).
    */
  private def runChild(
      registry: ToolRegistry,
      marker: String
  ): ZIO[Any, Any, String] =
    for
      fb <- doDelegate(registry, marker)
      done <- doAwait(registry, childIdOf(fb))
    yield done

  /** A modify-section hunk replacing the single line `from` with `to`. */
  private def oneLinePatch(name: String, from: String, to: String): String =
    s"""diff --git a/$name b/$name
       |index 0000000..1111111 100644
       |--- a/$name
       |+++ b/$name
       |@@ -1,1 +1,1 @@
       |-$from
       |+$to
       |""".stripMargin

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("delegated children reach the RAI-260 mutation tools")(
      test(
        "DELMUT-01: a delegated child applies an fs_patch_multi batch; report reaches the child, outcome returns to the parent"
      ) {
        withWorkspace { dir =>
          val taskSha =
            val c = "todo\n"
            Files.writeString(dir.resolve("task.txt"), c, UTF_8)
            sha256Hex(c)
          val diff = oneLinePatch("task.txt", "todo", "done-A")
          val args =
            s"""{"diff":${diff.toJson},"expected_sha256":{"task.txt":${taskSha.toJson}}}"""
          val backend = MarkerBackend(
            Map("apply-batch" -> ("fs_patch_multi", "ab-1", args))
          )
          for
            registry <- wire(dir, backend)
            fb <- doDelegate(registry, "apply-batch")
            childId = childIdOf(fb)
            done <- doAwait(registry, childId)
          yield
            val onDisk = Files.readString(dir.resolve("task.txt"), UTF_8)
            assertTrue(
              childId != "j_missing",
              done.contains("outcome:applied"),
              onDisk == "done-A\n",
              // the child really received the structured report as feedback
              backend.requests
                .flatMap(_.messages)
                .filter(_.role == "tool")
                .exists(_.content.contains("task.txt"))
            )
        }
      },
      test(
        "DELMUT-02: two parallel delegated children on the SAME file — exactly one batch lands, the other stale-refused, disk consistent"
      ) {
        withWorkspace { dir =>
          val base = "shared-v1\n"
          val sharedSha =
            Files.writeString(dir.resolve("shared.txt"), base, UTF_8)
            sha256Hex(base)
          val mkArgs = (to: String) =>
            s"""{"diff":${oneLinePatch(
                "shared.txt",
                "shared-v1",
                to
              ).toJson},""" +
              s""""expected_sha256":{"shared.txt":${sharedSha.toJson}}}"""
          val backend = MarkerBackend(
            Map(
              "child-one" -> ("fs_patch_multi", "c1-1", mkArgs("edited-one")),
              "child-two" -> ("fs_patch_multi", "c2-1", mkArgs("edited-two"))
            )
          )

          for
            registry <- wire(dir, backend)
            outcomes <- ZIO.collectAllPar(
              List(
                runChild(registry, "child-one"),
                runChild(registry, "child-two")
              )
            )
          yield
            val onDisk = Files.readString(dir.resolve("shared.txt"), UTF_8)
            val appliedCount =
              outcomes.count(_.contains("outcome:applied"))
            val staleCount = outcomes.count(_.contains("outcome:stale"))
            val staleObserved = backend.requests
              .flatMap(_.messages)
              .filter(_.role == "tool")
              .exists(_.content.contains("STALE PATCH"))
            val diskConsistent =
              onDisk == "edited-one\n" || onDisk == "edited-two\n"
            assertTrue(
              appliedCount == 1,
              staleCount == 1,
              staleObserved,
              diskConsistent
            )
        }
      }
    )
