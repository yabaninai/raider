package raider.cli.portable

import raider.core.{
  ModelBackend,
  Program,
  RaiderError,
  RequestMessage,
  RootId,
  Task,
  ToolRegistry
}
import raider.dsl.portable.PortableProgramRegistry
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.loop.AgentLoop

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import zio.ZIO
import zio.json.ast.Json
import zio.json.EncoderOps

/** The LAUNCHER-PINNED trusted registry (RAI-199; ADR-036 trust rules):
  * authority is supplied ONLY by the invocation owner through this launcher —
  * never through the document, the input JSON or argv. The document may only
  * NAME what is pinned here; anything else fails resolution typed.
  *
  * Pins of this launcher (v1, exact-match trust table):
  *   - profile `coder` + toolset `workspace`: one workspace-rooted coding agent
  *     (AgentLoop over the invocation's backend and CodingToolset). The
  *     declared toolset must match the pinned profile exactly — a document may
  *     not widen or rename the pinned authority, and the launcher does not
  *     synthesize narrowing combinations in this slice.
  *   - check `workspace-marker-present` under `[[checkSnapshot]]`: the fixed
  *     argv `/bin/test -f marker.txt` (the checked path is part of the PINNED
  *     DEFINITION — check argv can never enter through the document, the input
  *     JSON or this command's argv, the WorkflowRun §8 text-is-data rule). Runs
  *     WITHOUT a shell, cwd = the invocation workspace, bounded wait; nonzero
  *     exit / timeout / spawn failure are an honest `status: failed`.
  *   - safeOperations / checkpoints: EMPTY — a document referencing them is
  *     refused typed by the lowerer (no authority is invented here).
  *
  * Every trusted agent program shares the ONE admission of the invocation, so
  * all model rounds are admitted centrally (maxLLM=1) and every round is a
  * billable attempt against the intersected budget.
  */
object PortableTrustedRegistry:

  val ProfileCoder = "coder"
  val ToolsetWorkspace = "workspace"
  val CheckWorkspaceMarker = "workspace-marker-present"
  val MarkerRelativePath = "marker.txt"

  private val CheckArgv = List("/bin/test", "-f", MarkerRelativePath)
  private val CheckTimeoutSeconds = 120L

  /** The pinned provenance digest of a check definition: the document's
    * `registrySnapshot` must equal this value, so a stale or foreign check
    * definition can never pass resolution.
    */
  def checkSnapshot(checkId: String, argv: List[String]): String =
    val material = (checkId +: argv).mkString("\u0000")
    "sha256:" + PortableDoc.sha256Hex(
      material.getBytes(StandardCharsets.UTF_8)
    )

  val workspaceMarkerSnapshot: String =
    checkSnapshot(CheckWorkspaceMarker, CheckArgv)

  /** Builds the registry for ONE invocation. `root` is the run's admission root
    * (one per invocation, D3); `limits`/`maxRoundsPerAgent` come from
    * [[PortableLaunchPolicy.intersect]]; `backend` must already be the
    * token-ceiling envelope so all agents share the declared token budget.
    */
  def build(
      backend: ModelBackend,
      tools: ToolRegistry,
      admission: Admission,
      limits: BudgetLimits,
      maxRoundsPerAgent: Int,
      model: String,
      workspace: Path,
      root: RootId
  ): Either[RaiderError, PortableProgramRegistry] =
    Right(
      PortableProgramRegistry(
        agents = Map(
          ProfileCoder ->
            agentProgram(
              backend,
              tools,
              admission,
              limits,
              maxRoundsPerAgent,
              model,
              root
            )
        ),
        checks = Map(
          (CheckWorkspaceMarker -> workspaceMarkerSnapshot) ->
            checkProgram(workspace)
        )
      )
    )

  /** The pinned `coder` agent: input JSON (DATA) rendered as the user message,
    * bounded tool rounds in the invocation workspace, final text typed as
    * `{"text": ...}`. Dispatch happens ONLY when the lowered program is applied
    * — construction is zero-dispatch.
    */
  private def agentProgram(
      backend: ModelBackend,
      tools: ToolRegistry,
      admission: Admission,
      limits: BudgetLimits,
      maxRoundsPerAgent: Int,
      model: String,
      root: RootId
  ): Program[Json, Json] =
    Program.fromFunction[Json, Json]("portable-agent-coder") { input =>
      val loop = AgentLoop(
        backend = backend,
        tools = tools,
        model = model,
        admission = admission,
        root = root
      )
      Task(
        loop
          .runText(
            List(RequestMessage("user", input.toJson)),
            limits,
            maxRoundsPerAgent
          )
          .map(finalText => Json.Obj("text" -> Json.Str(finalText)))
      )
    }

  /** The pinned marker check: fixed argv, no shell, invocation workspace as
    * cwd, bounded wait. The step's input value is DATA and is never executed;
    * the outcome is the typed launcher convention `{"status": passed|failed,
    * "detail": ...}` — the launch route's verdict projection reads exactly this
    * field, so a check that did not pass can never become a green run.
    */
  private def checkProgram(workspace: Path): Program[Json, Json] =
    Program.fromFunction[Json, Json]("portable-check-workspace-marker") { _ =>
      Task(
        ZIO
          .attemptBlocking {
            val proc = new ProcessBuilder(CheckArgv.asJava)
              .directory(workspace.toFile)
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start()
            val finished =
              proc.waitFor(CheckTimeoutSeconds, TimeUnit.SECONDS)
            if !finished then
              proc.destroyForcibly()
              (false, s"check timed out after ${CheckTimeoutSeconds}s")
            else if proc.exitValue() == 0 then
              (true, MarkerRelativePath + " present")
            else (false, "exit code " + proc.exitValue())
          }
          .mapError(t =>
            RaiderError.ToolFailed(
              s"trusted check '$CheckWorkspaceMarker' could not start: " +
                s"${String.valueOf(t)}"
            )
          )
          .map { case (passed, detail) =>
            Json.Obj(
              "status" -> Json.Str(if passed then "passed" else "failed"),
              "detail" -> Json.Str(detail)
            )
          }
      )
    }

end PortableTrustedRegistry
