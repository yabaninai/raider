package raider.cli

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.loop.AgentLoop
import raider.tools.files.ProjectInstructions
import zio.stream.ZStream
import zio.test.*
import zio.{Scope, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** RAI-269 acceptance: the coder system prompt is composed at the CONFIGURED
  * workspace root (the same root the coding toolset's write-path policy
  * enforces), not unconditionally at the process CWD.
  *
  *   - CPR-01 — with a workspace whose AGENTS.md DIFFERS from the CWD's, the
  *     composed prompt carries the WORKSPACE-root instructions (and not the
  *     other root's).
  *   - CPR-02 — the no-`--workspace` default expression both frontends use
  *     (`Paths.get("").toAbsolutePath`) composes exactly the loader semantics
  *     at the process CWD (RAI-259 default behavior unchanged).
  *   - CPR-03 — a workspace root without AGENTS.md composes the bare base
  *     prompt byte-identical (fail-open preserved), and an invalid workspace
  *     root still fails toolset construction with the existing typed
  *     diagnostics (the prompt never fabricates instructions).
  *   - CPR-04 — the base text is pinned EXACTLY: RAI-270 refreshed the frozen
  *     Phase 4.3 six-tool text to the full registry surface, and this pin was
  *     updated in byte-identical lockstep with `CoderPrompts.CoderBasePrompt`;
  *     silent drift must fail this spec.
  *   - CPR-05 — unicode and multiline instruction fixtures survive composition
  *     intact.
  *   - CPR-06 — a workspace root reached through a symlink (and the raw
  *     `/var/...` temp path macOS hands out) still loads its instructions:
  *     canonicalization matches `Workspace.make`/`WritePathPolicy`.
  *   - CPR-07 — stand transcript: the composed prompt reaches the model as the
  *     system message on EVERY request of the session.
  *   - CPR-08 — concurrency: prompt composition is a pure read; parallel
  *     compositions of one root are deterministic and identical.
  *
  * RAI-270 acceptance:
  *
  *   - CPR-09 — the base prompt names EVERY tool the coding registry offers
  *     (all five mutating tools — `fs_edit`, `fs_patch`, `fs_patch_multi`,
  *     `fs_delete`, `proc_run` — plus the read-only three), with
  *     `fs_patch_multi`'s all-or-nothing semantics and `fs_delete`'s
  *     single-file irreversibility stated on their own tool lines.
  *
  * No network, no spend.
  */
object CoderPromptsSpec extends ZIOSpecDefault:

  private val a = AttemptId("cpr-att")

  /** The RAI-270-refreshed base text, pinned for exact-text equality (CPR-04).
    * Keep this literal byte-identical to `CoderPrompts.CoderBasePrompt`.
    */
  private val FrozenBase =
    """You are an expert Scala 3 + ZIO developer working on the Raider project.
Workspace is the project root; all paths are relative to it.

Available tools:
- fs_read(path, max_lines): Read a file. Returns content + sha256.
- fs_search(query, glob): Search for text in files.
- fs_edit(path, content, expected_sha256): Write a file (empty expected_sha256 creates it). Use the sha from fs_read.
- fs_patch(path, diff, expected_sha256): Apply a unified diff to one file.
- fs_patch_multi(diff, expected_sha256): Apply a multi-file git-style diff (new, modified and deleted file sections). ALL-OR-NOTHING: every listed file is validated against its current sha256 before anything is written; if any check fails, nothing is applied. expected_sha256 maps each changed path to its current sha256.
- fs_delete(path, expected_sha256): Delete exactly one regular file (directories are refused). Irreversible — there is no undo; requires the current sha256.
- fs_tree(depth, glob): List the workspace structure (bounded).
- proc_run(argv, timeout_s): Run a command. Returns exit code + output.

Workflow:
1. READ the relevant files first
2. UNDERSTAND the current code structure
3. MAKE the minimal necessary change
4. RUN sbt compile to verify
5. If errors, READ the errors and FIX them
6. RUN sbt test to confirm
7. Give a concise summary

Rules:
- Never overwrite a file without reading it first
- Always use the sha256 from your last fs_read
- If compilation fails, fix the specific error, don't rewrite the file
- Prefer fs_patch for small single-file changes, fs_edit for new files, fs_patch_multi for coordinated multi-file changes
- fs_delete is permanent — delete only files the task requires"""

  private def tempDir(prefix: String): Path =
    Files.createTempDirectory(prefix)

  private def writeAgents(root: Path, content: String): Unit =
    Files.writeString(root.resolve("AGENTS.md"), content, UTF_8)
    ()

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
            ModelEvent.Started(AttemptId(s"cpr$k")) +:
              (if k < turns.length then turns(k)(input)
               else Vector(ModelEvent.Finished(AttemptId(s"cpr$k"), "stop")))
          )

  private def lims: BudgetLimits =
    BudgetLimits.make(maxAttempts = 16).fold(e => throw e, identity)

  def spec = suite("coder prompts: workspace-root threading (RAI-269)")(
    test(
      "CPR-01: --workspace at a root whose AGENTS.md differs from the CWD's — the prompt carries the WORKSPACE instructions"
    ) {
      val workspace = tempDir("raider-cpr-ws")
      val cwdLike = tempDir("raider-cpr-cwd")
      writeAgents(workspace, "WORKSPACE-ROOT-INSTR use sbt --batch\n")
      writeAgents(cwdLike, "OTHER-ROOT-INSTR never used here\n")
      val composed = CoderPrompts.CoderSystemPrompt(workspace)
      assertTrue(
        composed.startsWith(CoderPrompts.CoderBasePrompt),
        composed.contains("## Project instructions (from AGENTS.md"),
        composed.contains("WORKSPACE-ROOT-INSTR use sbt --batch"),
        !composed.contains("OTHER-ROOT-INSTR")
      )
    },
    test(
      "CPR-02: no --workspace — the frontends' default expression composes the CWD (RAI-259 behavior unchanged)"
    ) {
      val cwd = Paths.get("").toAbsolutePath
      val composed = CoderPrompts.CoderSystemPrompt(cwd)
      val oracle =
        ProjectInstructions.surface(
          CoderPrompts.CoderBasePrompt,
          cwd.toRealPath()
        )
      assertTrue(composed == oracle)
    },
    test(
      "CPR-03: no AGENTS.md at the root — bare base prompt; invalid root keeps toolset diagnostics and fail-opens the prompt"
    ) {
      val bare = tempDir("raider-cpr-bare")
      val missing = bare.resolve("does-not-exist")
      val composedBare = CoderPrompts.CoderSystemPrompt(bare)
      val composedMissing = CoderPrompts.CoderSystemPrompt(missing)
      for
        // the bounded diagnostics for an invalid workspace stay owned by
        // toolset construction (untouched by RAI-269)
        toolset <- raider.tools.CodingToolset.make(missing.toString).either
      yield assertTrue(
        composedBare == CoderPrompts.CoderBasePrompt,
        composedMissing == CoderPrompts.CoderBasePrompt,
        toolset.isLeft
      )
    },
    test(
      "CPR-04: the refreshed (RAI-270) base prompt is pinned exactly"
    ) {
      assertTrue(CoderPrompts.CoderBasePrompt == FrozenBase)
    },
    test(
      "CPR-05: unicode and multiline instruction fixtures survive composition intact"
    ) {
      val root = tempDir("raider-cpr-uni")
      val instr =
        "Always run sbt --batch test 🧪\n第二行：保持多行结构\ncafé ☕ — end of rules\n"
      writeAgents(root, instr)
      val composed = CoderPrompts.CoderSystemPrompt(root)
      assertTrue(
        composed.contains(instr),
        composed.contains("## Project instructions (from AGENTS.md")
      )
    },
    test(
      "CPR-06: a symlinked workspace root still loads its instructions (canonicalization matches Workspace.make)"
    ) {
      val real = tempDir("raider-cpr-real")
      writeAgents(real, "SYMLINKED-ROOT-INSTR\n")
      val linkDir = tempDir("raider-cpr-link")
      val linked = linkDir.resolve("ws")
      Files.createSymbolicLink(linked, real)
      // A raw temp path (NOT realpath'd first) is what an operator's
      // --workspace /var/folders/... looks like on macOS; canonicalization
      // must make the loader see the same root WritePathPolicy enforces.
      val composedLinked = CoderPrompts.CoderSystemPrompt(linked)
      val composedRaw = CoderPrompts.CoderSystemPrompt(real)
      assertTrue(
        composedLinked.contains("SYMLINKED-ROOT-INSTR"),
        composedRaw.contains("SYMLINKED-ROOT-INSTR")
      )
    },
    test(
      "CPR-07: stand transcript — the workspace-composed prompt reaches the model as the system message on EVERY request"
    ) {
      val workspace = tempDir("raider-cpr-stand")
      Files.writeString(workspace.resolve("notes.txt"), "n\n", UTF_8)
      writeAgents(workspace, "STAND-INSTR-MARKER\n")
      val sys = Some(CoderPrompts.CoderSystemPrompt(workspace))
      val backend = new TurnBackend(
        Vector(
          _ =>
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.ToolCallReady(
                a,
                ToolCallId("r1"),
                "fs_read",
                """{"path":"notes.txt"}"""
              ),
              ModelEvent.Finished(a, "tool_round")
            ),
          _ =>
            Vector[ModelEvent](
              ModelEvent.Started(a),
              ModelEvent.TextDelta(a, "done"),
              ModelEvent.Finished(a, "stop")
            )
        )
      )
      for
        ws <- raider.tools.CodingToolset.make(workspace.toString)
        registry = ws.registry.fold(e => throw e, identity)
        adm <- Admission.make(Right(lims))
        loop = AgentLoop(
          backend,
          registry,
          "m",
          adm,
          AgentLoop.newRoot(),
          systemPrompt = sys
        )
        _ <- loop.runText(
          List(RequestMessage("user", "read the notes")),
          lims,
          maxRounds = 8
        )
      yield
        val reqs = backend.requests
        assertTrue(
          reqs.size == 2,
          reqs.forall(_.messages.head.role == "system"),
          reqs.forall(
            _.messages.head.content.startsWith(CoderPrompts.CoderBasePrompt)
          ),
          reqs.forall(_.messages.head.content.contains("STAND-INSTR-MARKER"))
        )
    },
    test(
      "CPR-08: concurrency — parallel compositions of one root are deterministic and identical"
    ) {
      val root = tempDir("raider-cpr-par")
      writeAgents(root, "PAR-INSTR-MARKER\n")
      val sequential = CoderPrompts.CoderSystemPrompt(root)
      for parallel <- ZIO.collectAllPar(
          List.fill(8)(ZIO.succeed(CoderPrompts.CoderSystemPrompt(root)))
        )
      yield assertTrue(
        parallel.forall(_ == sequential),
        sequential.contains("PAR-INSTR-MARKER")
      )
    },
    test(
      "CPR-09: the base prompt names EVERY tool the coding registry offers; fs_patch_multi is all-or-nothing, fs_delete is single-file irreversible (RAI-270)"
    ) {
      val ws = tempDir("raider-cpr-inv")
      for
        toolset <- raider.tools.CodingToolset.make(ws.toString)
        reg = toolset.registry.fold(e => throw e, identity)
      yield
        val base = CoderPrompts.CoderBasePrompt
        // The five mutating tools per the live registry stamps (RAI-149),
        // cross-checked against the canonical taxonomy — the prompt must name
        // exactly the surface the model can call, and call out the key
        // semantics of the structured mutation tools.
        val mutating = reg.names.filter(name =>
          reg.lookup(name).exists(_.recovery == RecoveryClass.Mutating)
        )
        assertTrue(
          reg.names == List(
            "fs_delete",
            "fs_edit",
            "fs_patch",
            "fs_patch_multi",
            "fs_read",
            "fs_search",
            "fs_tree",
            "proc_run"
          ),
          mutating == List(
            "fs_delete",
            "fs_edit",
            "fs_patch",
            "fs_patch_multi",
            "proc_run"
          ),
          reg.names.forall(base.contains),
          // fs_patch_multi: the all-or-nothing guarantee on its own tool line
          base.linesIterator.exists(l =>
            l.contains("fs_patch_multi") && l.contains("ALL-OR-NOTHING")
          ),
          // fs_delete: exactly one regular file + irreversibility on its own
          // tool line
          base.linesIterator.exists(l =>
            l.contains("fs_delete") &&
              l.contains("exactly one regular file") &&
              l.contains("Irreversible")
          )
        )
    }
  )
