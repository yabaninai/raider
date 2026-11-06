package raider.cli

import raider.tools.files.ProjectInstructions

import java.nio.file.Path

/** The coder system prompt, single-sourced (nightly Phase 4.3 text): both
  * frontends — the interactive chat loop and the headless `--agent coder` path
  * — send exactly this prompt, so they cannot drift apart. The REPL facade
  * keeps its own shorter prompt (FacadeOps.ReplSystemPrompt) because that
  * surface is interactive-exploratory, not a coding-agent task loop.
  *
  * RAI-259: `CoderSystemPrompt` is the BASE prompt plus, when present, the
  * project-instructions section loaded by
  * [[raider.tools.files.ProjectInstructions]] from the workspace root. RAI-269:
  * that root is the CONFIGURED workspace root (`--workspace`), threaded in by
  * both frontends from the SAME value they hand to the coding toolset — so the
  * instructions are read from the same root the write-path policy enforces —
  * and it defaults to the process working directory when no `--workspace` is
  * given (RAI-259 default behavior, unchanged). The lookup is deny-by-default
  * and fail-open: no instruction file, an unreadable one, or one whose symlink
  * resolves outside the root leaves the prompt byte-identical to the base.
  *
  * RAI-270: the base text was refreshed from the frozen Phase 4.3 six-tool list
  * to the FULL current coding surface — all eight registry tools (`fs_read`,
  * `fs_search`, `fs_edit`, `fs_patch`, `fs_patch_multi`, `fs_delete`,
  * `fs_tree`, `proc_run`), including `fs_patch_multi`'s all-or-nothing batch
  * semantics and `fs_delete`'s single-file irreversibility. Prompt text only:
  * tool behavior, policy and registry are untouched, composition with
  * instructions is unchanged, and CPR-04 pins the refreshed text byte-exactly.
  */
object CoderPrompts:

  /** RAI-270: refreshed base text naming the FULL current coding-tool surface
    * (RAI-260 added `fs_patch_multi`/`fs_delete` to the six-tool Phase 4.3
    * list): all five mutating tools (`proc_run`, `fs_edit`, `fs_patch`,
    * `fs_patch_multi`, `fs_delete`) plus the read-only three, with
    * `fs_patch_multi`'s ALL-OR-NOTHING batch semantics and `fs_delete`'s
    * single-file irreversibility stated on their tool lines. Text-only refresh
    * — no tool behavior/policy change; composition via [[CoderSystemPrompt]] is
    * untouched.
    */
  val CoderBasePrompt: String =
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
- fs_delete is permanent — delete only files the task requires""".stripMargin

  /** Base prompt + project-instructions section (AGENTS.md at the configured
    * `workspaceRoot` — first match wins, 64 KiB cap, fail-open). RAI-269: the
    * root arrives from the same resolution the frontends hand to the coding
    * toolset, so an explicit `--workspace` away from the CWD feeds THIS prompt
    * from that root, and the no-flag default stays the process CWD (RAI-259
    * behavior). The root is canonicalized to its real path first (same
    * resolution as `Workspace.make`/`WritePathPolicy`), so the loader's
    * containment check compares the root form the tools enforce. A `def`, not a
    * `val`: every session build re-reads the root, so tests and long-lived JVMs
    * see the CURRENT file rather than a frozen snapshot.
    */
  def CoderSystemPrompt(workspaceRoot: Path): String =
    ProjectInstructions.surface(CoderBasePrompt, canonicalRoot(workspaceRoot))

  /** Resolve the configured root the way `Workspace.make` does (real path), so
    * the loader's containment check compares the same root form the write-path
    * policy enforces. Fail-open: a root that cannot be resolved (missing or
    * broken symlink) falls back to the normalized absolute path, where the
    * loader finds no instruction file and the base prompt is used unchanged —
    * diagnostics for an invalid workspace stay owned by toolset construction.
    */
  private def canonicalRoot(root: Path): Path =
    try root.toRealPath()
    catch case _: Exception => root.toAbsolutePath.normalize()
