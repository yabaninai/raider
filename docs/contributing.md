# Contributor Guide

How to extend Raider without breaking its guarantees. Read
`AGENTS.md` (rules of engagement) and `docs/architecture.md` (how the pieces
compose) first. Product code is **Scala 3 + ZIO only** (`modules/**`,
`contracts/**`, `examples/**`); Python lives only in `scripts/` glue. All code
compiles with `-Werror` (zero warnings) and scalafmt/scalafix must pass.

## 0. Setup and everyday commands

For user setup and runnable examples, start with the [current launch guide](user-guide.md)
and [verified REPL/bundle gallery](user-guide-repl.md). The commands here are
developer checks; a command is not evidence that it was run on your checkout.

```sh
make compile                 # sbt --batch compile  (-Werror)
COURSIER_CACHE=/tmp/cc-master sbt --batch test          # full suite
make quality-changed                    # classify changes → profiles
COURSIER_CACHE=/tmp/cc-master make quality-changed QUALITY_EXECUTE=1   # run gates
make quality-profile PROFILES="repl,ci" # semantic profiles for your slice
sh scripts/quality/repl_smoke.sh        # REPL non-TTY smoke
sh scripts/quality/cli_smoke.sh         # headless CLI smoke (needs raiderCli/test first)
make raider-jar                         # fat JAR: modules/cli/target/scala-3.9.0/raider-cli.jar
```

Before you finish a task: `make quality-changed QUALITY_EXECUTE=1` must pass,
plus the semantic profiles your slice touches. A timeout or a skipped check is
NEVER a pass. After real execution, write a work record under
`docs/work/records/` (template: `docs/templates/work-record.md`) and update
`docs/work/board.md`.

## 1. How to add a new tool

Tools live in `modules/tools` under `raider.tools`. Walk through an existing
one (`files/edit/PatchTool.scala` is the most complete example).

1. **Implement the tool** as a class over `Workspace`:

   ```scala
   final class MyTool(workspace: Workspace):
     def apply(args...): ZIO[Any, RaiderError, MyResult] = ...
   ```

   Rules:
   - Containment: resolve paths against `workspace.root`, normalize, refuse
     anything that escapes (realpath check — see `EditTool.resolveSafe`).
   - Preconditions: expected-SHA (`fs_edit`/`fs_patch` pattern) for mutations.
   - Atomicity: temp file + `ATOMIC_MOVE` rename for writes.
   - Bounds: refuse oversized inputs/outputs with typed
     `RaiderError.InputValidation`.
   - Return typed `RaiderError`s for crashes; policy denials (bounds/escape)
     may surface as feedback to the model.

2. **Register it in `CodingToolset`**:
   - add a private `...Invoke` that decodes args with a zio-json case class and
     returns single-line JSON;
   - add the JSON-schema string and `toolOf(name, description, schema, invoke)`;
   - add the tool to `registry`'s list;
   - construct it in `CodingToolset.make`.

3. **Name it in the prompts** if agents should use it (`ChatLoop` sysPrompt,
   `HeadlessRunner.CoderSystemPrompt`, `FacadeOps.ReplSystemPrompt`).

4. **Write fixtures FIRST** (`modules/tools/src/test/...`): the happy path,
   the containment denial, the precondition denial, and the bounds. ZIO Test
   only; no sleeps where a TestClock/ref will do; never touch the real
   network or `$HOME`.

5. **Verify**: `sbt --batch raiderTools/test`, then
   `make quality-changed QUALITY_EXECUTE=1`. Update `docs/api.md` (tool table)
   if the surface changed.

## 2. How to add a new provider

Providers are isolated modules (`modules/provider-chat`,
`modules/provider-anthropic` are the references).

1. Create `modules/provider-<name>` depending on `raiderCore` only (+ zio-streams);
   register it in `build.sbt` (a coordinator-owned file — keep the edit
   minimal and mirrored in this guide).
2. Implement `ModelBackend`:

   ```scala
   final class XBackend private (cfg: Config, client: HttpClient) extends ModelBackend:
     def capabilities = ModelCapabilities(streaming = ..., tools = ..., cancellationAck = ...)
     def stream(input: ModelRequest): ZStream[Scope, RaiderError, ModelEvent] = ...
   ```

   Transport rules (uniform): 401/403 → `ProviderAuth`, 429 →
   `ProviderRateLimit`, 5xx → `ProviderUnavailable`, anything else →
   `StreamProtocol`. Close the response body on EVERY exit path
   (acquire/release in the caller's scope). Bound frames (SSE) and idle time.
   NO hidden retries: 429 backoff is the caller's `RateLimitRetries` decorator,
   nothing else retries.
3. Emit only normalized `ModelEvent`s; tool requests MUST end as
   `ToolCallReady` (deltas are fragments, never executable).
4. For SSE reuse the shared framer from provider-chat
   (`provider-protocols.md` sanctions reusable framing; keep the semantic
   layer wire-specific).
5. Fixtures first: a local HTTP stand (see `OpenAIChatStandSpec`) — wrong key,
   500, unparsable body, chunk-boundary partition property, cancel mid-stream.
   NEVER call a real/paid endpoint from tests.
6. Verify: `make quality-profile PROFILES="transport"` plus full gates.

## 3. How to run the quality gates

The runner is `scripts/quality/quality.py`; the policy is
`scripts/quality-policy/registry.json` (gates + profiles + path mapping).

```sh
make quality-changed                          # classify only (no execution)
COURSIER_CACHE=/tmp/cc-master make quality-changed QUALITY_EXECUTE=1   # execute (fast minimum)
make quality-profile PROFILES="static,unit"   # execute specific profiles
make quality-profile PROFILES="tools,transport,repl,ci"
python3 scripts/quality/quality.py verify artifacts/quality/<run>/manifest.json --require fast
make quality-full                             # everything local (test+fmt+scan+gates+smokes)
```

**Change detection (RAI-054).** Two baseline sources, same
classifier/evidence contract — never a second, weaker runner:

- **Git mode** (default): `git status --porcelain -z --untracked-files=all` in
  the repo root — staged, unstaged, untracked and deleted paths; renames
  expand as delete+add so the origin keeps its profiles. Git-ignored files are
  invisible here.
- **Manifest mode** (snapshot lanes without `.git`): `--base-manifest P` or
  `QUALITY_BASE_MANIFEST=P` diffs against an immutable inventory manifest
  (created once with `python3 scripts/quality/quality.py inventory --out P`).
  The diff covers added/modified/deleted paths, mode and symlink target; a
  missing or corrupt baseline is a hard error. Record
  `Git unavailable: isolated snapshot` in your report and never fall back to
  running checks in a shared root checkout.
- A clean tree (either mode) selects the minimum `fast` profile; no git AND no
  manifest is a hard error, never a silent fast run.
- Evidence run ids are `<YYYYmmdd-HHMMSS>-<pid>-<seq>` — parallel executes get
  distinct run directories.

**Profile classification.** The classifier maps each changed file through
`path_profiles` in the registry to a per-file profile list
(`{path: [profiles]}`): `modules/|contracts/` → static+unit+contracts,
`docs/|README|AGENTS.md` → docs, `scripts/` → self+static, build files →
static+contracts, and so on. A changed path matching NO pattern is a hard
`__unmapped__` refusal — extend the registry, don't bypass it. The executed
plan is the union over changed files; `make quality-changed` (no
`QUALITY_EXECUTE`) prints the plan without running anything.

**Parallel lanes.** Isolated workers work in separate worktrees or snapshot
copies with their own baseline; shared contracts, `build.sbt`, the gate
registry and the board each have a single owner. Full rules and the
assignment template: `docs/parallel-development.md`.

Profiles: `fast` (9-gate minimum), `static`, `unit`, `contracts`, `docs`,
`runtime`, `tools`, `transport` (provider stands), `repl` (tests + REPL
smoke), `ci` (tests + CLI smoke). Evidence binds to the source fingerprint —
if you change sources after a run, rerun the affected gates. A gate that times
out, errors or is skipped is a FAILURE, not a pass. Do not lower thresholds or
disable checks to get green.

## 4. How to write tests

- Framework: **ZIO Test** (`zio.test.*`), fixtures-first: write the behavioral
  spec before the implementation (repo convention; see any `*Spec.scala`).
- Backends are scripted: implement a tiny `ModelBackend` in the spec (see
  `TurnBackend` in `AgentLoopDelegationSpec`, `Flaky` in
  `RateLimitRetriesSpec`, `Scripted` in `StreamingDisplaySpec`).
- Determinism: route scripted turns by request CONTENT, never by arrival
  order (parent/child dispatch is concurrent); prefer `TestClock`/refs over
  sleeps; if real time is unavoidable, mark the test `@@ TestAspect.withLiveClock`.
- Concurrency/runtime changes need cancellation, ownership and budget
  fixtures (`Admission`, `JobManager`, `AgentLoop` specs are the pattern).
- REPL changes need the REAL engine path (`raiderRepl` suite) and, for
  console behavior, `scripts/quality/repl_smoke.sh` — a mock interpreter is
  not sufficient evidence.
- File-system fixtures use `Files.createTempDirectory` with a release
  finalizer; beware lazy zio-test assertions — read files eagerly in the
  effect chain (see `PatchToolSpec`'s "eager read" comment).
- Never call a live model from product tests. Mock/replay only; live stands
  are local, explicit and opt-in.

## 5. Conventions worth keeping

- `-Werror` clean; scalafmt (`sbt scalafmtAll` before you commit) and scalafix
  (`DisableSyntax` bans nulls — use `Option`; Java-interop nullables are
  wrapped, e.g. `Option(System.getenv(...))`).
- Money is `MicroUsd` (Long); Double money arithmetic is gate-blocked.
- Errors are typed `RaiderError`s with stable codes; surface policy denials as
  model feedback, crashes as typed failures.
- Public API changes are additive and defaulted where possible (see
  `ReplSession.model/echoStream`); frozen core contracts need an ADR.
- Documentation in English; work records only AFTER real execution.
