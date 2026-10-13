package raider.tools

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.lineage.model.RecoveryTaxonomy
import raider.runtime.loop.{AgentLoop, PlanMode, PlanStore}
import raider.runtime.state.StateStore
import zio.stream.ZStream
import zio.test.*
import zio.{Scope, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** RAI-149 acceptance: tool-level RecoveryClass correctness.
  *
  *   - TOOLRC-01 — every CodingToolset tool carries its correct frozen
  *     `RecoveryClass` (per-tool pins), consistent with the canonical
  *     `RecoveryTaxonomy` membership (RAI-148): exec/write tools Mutating, pure
  *     reads ReadOnly. The opt-in state tools keep their own RAI-094 stamps
  *     (audited, unchanged).
  *   - TOOLRC-02 — in a plan phase, invoking `fs_patch`/`proc_run` is refused
  *     with the typed policy denial (RA-TOOLDENY / tool_denied) and NOTHING
  *     executes (real workspace stays untouched); a genuinely read-only tool
  *     (`fs_read`) still executes; the model is advertised only the ReadOnly
  *     subset. Ordinary runs still execute the mutating coding tools
  *     (enforcement is plan-phase only). Deterministic scripted backend — no
  *     network, no spend.
  *
  * TOOLRC-03 (PlanMode/AgentLoop consumers unchanged) is proven by this file
  * compiling and running against the unchanged `PlanMode.of` /
  * `AgentLoop(..., planMode = ...)` / `runPlan` signatures, plus the existing
  * tools/runtime suites staying green.
  */
object CodingToolsetRecoverySpec extends ZIOSpecDefault:

  private val a = AttemptId("toolrc-att")

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

  private val ReadOnlyNames = List("fs_read", "fs_search", "fs_tree")

  private val validPlanJson =
    """{"title":"Ship it","steps":["read the file","patch it"]}"""

  /** k-th call replays k-th turn. */
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
            ModelEvent.Started(AttemptId(s"t$k")) +:
              (if k < turns.length then turns(k)(input)
               else Vector(ModelEvent.Finished(AttemptId(s"t$k"), "stop")))
          )

  private def toolRound(
      callId: String,
      tool: String,
      args: String
  ): ModelRequest => Vector[ModelEvent] = _ =>
    Vector[ModelEvent](
      ModelEvent.Started(a),
      ModelEvent.ToolCallReady(a, ToolCallId(callId), tool, args),
      ModelEvent.Finished(a, "tool_round")
    )

  private def planTurn(json: String): ModelRequest => Vector[ModelEvent] = _ =>
    Vector[ModelEvent](
      ModelEvent.Started(a),
      ModelEvent.TextDelta(a, json),
      ModelEvent.Finished(a, "stop")
    )

  private def lims: BudgetLimits =
    BudgetLimits.make(maxAttempts = 8).fold(e => throw e, identity)

  private def sha256Hex(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(UTF_8))
      .map("%02x" format _)
      .mkString

  def spec = suite("coding toolset recovery stamps (RAI-149)")(
    suite("TOOLRC-01 per-tool stamps")(
      test(
        "six coding tools carry their correct frozen RecoveryClass (was: all ReadOnly)"
      ) {
        val work = Files.createTempDirectory("raider-toolrc-01")
        for
          ws <- CodingToolset.make(work.toString)
          reg = ws.registry.fold(e => throw e, identity)
          rec = (n: String) => reg.lookup(n).map(_.recovery)
        yield assertTrue(
          reg.names == SixNames,
          rec("fs_read") == Some(RecoveryClass.ReadOnly),
          rec("fs_search") == Some(RecoveryClass.ReadOnly),
          rec("fs_tree") == Some(RecoveryClass.ReadOnly),
          rec("proc_run") == Some(RecoveryClass.Mutating),
          rec("fs_edit") == Some(RecoveryClass.Mutating),
          rec("fs_patch") == Some(RecoveryClass.Mutating)
        )
      },
      test(
        "coding-tool stamps are consistent with the canonical RecoveryTaxonomy membership (RAI-148)"
      ) {
        val work = Files.createTempDirectory("raider-toolrc-01-tax")
        for
          ws <- CodingToolset.make(work.toString)
          reg = ws.registry.fold(e => throw e, identity)
          // name-string comparison: the lineage taxonomy is keyed by tool
          // NAME (its own frozen graph-model enum), the Tool field is the
          // core enum — the membership verdicts must agree
          consistent = SixNames.forall { n =>
            val tool = reg
              .lookup(n)
              .getOrElse(throw new IllegalStateException(s"missing $n"))
            tool.recovery.toString == RecoveryTaxonomy.classify(n).toString
          }
          mutating = SixNames.filter(RecoveryTaxonomy.isMutating)
        yield assertTrue(
          consistent,
          mutating == List("fs_edit", "fs_patch", "proc_run")
        )
      },
      test(
        "opt-in state tools keep their frozen per-tool stamps (audit completeness, unchanged)"
      ) {
        val work = Files.createTempDirectory("raider-toolrc-01-state")
        for
          store <- StateStore.memory()
          ws <- CodingToolset.make(
            work.toString,
            Some((store, JobId("toolrc")))
          )
          reg = ws.registry.fold(e => throw e, identity)
          rec = (n: String) => reg.lookup(n).map(_.recovery)
        yield assertTrue(
          reg.names == SixNames ++ StateNames,
          rec("state_get") == Some(RecoveryClass.ReadOnly),
          rec("state_keys") == Some(RecoveryClass.ReadOnly),
          rec("state_put") == Some(RecoveryClass.Mutating),
          rec("state_delete") == Some(RecoveryClass.Idempotent)
        )
      }
    ),
    suite("TOOLRC-02 plan-phase enforcement over the real toolset")(
      test(
        "plan phase: fs_patch and proc_run → typed policy denial, NOTHING executes; fs_read runs; only ReadOnly defs advertised"
      ) {
        val work = Files.createTempDirectory("raider-toolrc-02")
        val src = work.resolve("src.txt")
        Files.writeString(src, "hello\n", UTF_8)
        val before = Files.readString(src, UTF_8)
        val sha = sha256Hex("hello\n")
        // a REAL patch (correct sha, correct hunk) — only the denial may stop it
        val patchArgs =
          s"""{"path":"src.txt","diff":"--- a/src.txt\\n+++ b/src.txt\\n@@ -1,1 +1,1 @@\\n-hello\\n+world\\n","expected_sha256":"$sha"}"""
        for
          ws <- CodingToolset.make(work.toString)
          registry = ws.registry.fold(e => throw e, identity)
          backend = new TurnBackend(
            Vector(
              toolRound("p1", "fs_patch", patchArgs),
              toolRound("p2", "proc_run", """{"argv":["touch","pwned.txt"]}"""),
              toolRound("r1", "fs_read", """{"path":"src.txt"}"""),
              planTurn(validPlanJson)
            )
          )
          adm <- Admission.make(Right(lims))
          root = AgentLoop.newRoot()
          dir = Files.createTempDirectory("raider-toolrc-02-plan")
          loop = AgentLoop(
            backend,
            registry,
            "m",
            adm,
            root,
            planMode = Some(PlanMode.of(registry, dir))
          )
          proposal <- loop.runPlan(
            List(RequestMessage("user", "plan the change")),
            lims,
            maxRounds = 8
          )
          reqs = backend.requests
          // rounds accumulate history: the FINAL request carries both denials
          // plus the executed fs_read result
          finalToolMsgs = reqs.last.messages.filter(_.role == "tool")
          after = Files.readString(src, UTF_8)
          pwned = Files.exists(work.resolve("pwned.txt"))
          artifact = Files.exists(PlanStore.artifactPath(dir, root))
        yield assertTrue(
          proposal.title == "Ship it",
          reqs.size == 4, // 3 tool rounds + the final plan answer
          // surface narrowing: the plan phase advertises ONLY the ReadOnly subset
          reqs.forall(_.tools.map(_.name) == ReadOnlyNames),
          // each mutating call was denied typed, the loop stayed alive
          finalToolMsgs.count(_.content.contains("RA-TOOLDENY")) == 2,
          finalToolMsgs.count(_.content.contains("tool_denied")) == 2,
          finalToolMsgs.exists(_.content.contains("fs_patch")),
          finalToolMsgs.exists(_.content.contains("proc_run")),
          // the genuinely read-only tool really executed
          finalToolMsgs.exists(_.content.contains("hello")),
          // and NOTHING mutated the workspace (the pre-fix silent pass)
          after == before,
          !pwned,
          artifact
        )
      },
      test(
        "ordinary runText still executes the mutating coding tools (enforcement is plan-phase only)"
      ) {
        val work = Files.createTempDirectory("raider-toolrc-02-ordinary")
        for
          ws <- CodingToolset.make(work.toString)
          registry = ws.registry.fold(e => throw e, identity)
          backend = new TurnBackend(
            Vector(
              toolRound(
                "e1",
                "fs_edit",
                """{"path":"out.txt","content":"data\n","expected_sha256":""}"""
              ),
              _ =>
                Vector[ModelEvent](
                  ModelEvent.Started(a),
                  ModelEvent.TextDelta(a, "done"),
                  ModelEvent.Finished(a, "stop")
                )
            )
          )
          adm <- Admission.make(Right(lims))
          loop = AgentLoop(backend, registry, "m", adm, AgentLoop.newRoot())
          answer <- loop.runText(
            List(RequestMessage("user", "write it")),
            lims,
            maxRounds = 8
          )
          reqs = backend.requests
          written = Files.readString(work.resolve("out.txt"), UTF_8)
        yield assertTrue(
          answer == "done",
          reqs.size == 2,
          written == "data\n",
          reqs(1).messages.exists(_.content.contains("\"action\":\"created\""))
        )
      }
    )
  )
