# Raider Nightly Improvement Master Prompt

## Context
You are working on Raider, a self-hosting agent harness for CI pipelines
(Scala 3 + ZIO 2). The project has 12 modules, 193 tests passing, and a
working self-hosting loop (coder agent reads/edits/runs code via llama.cpp).

## Your Mission
Work through the tasks below IN PRIORITY ORDER. Each task is independent —
if you get stuck, skip it and move to the next. After EVERY task:
1. Run `COURSIER_CACHE=/tmp/cc-master sbt --batch compile` (must be exit 0)
2. Run `COURSIER_CACHE=/tmp/cc-master sbt --batch test` (must be 0 failures)
3. Run `COURSIER_CACHE=/tmp/cc-master make quality-changed QUALITY_EXECUTE=1`
   (must be 6/6 gates passed)

## Rules
- Product code: Scala 3 + ZIO only (`modules/**`). Python only in `scripts/`.
- NO new dependencies in build.sbt without extreme necessity.
- ALL code must compile with `-Werror` (zero warnings).
- After completing a task, create a work record in `docs/work/records/`.
- Update `docs/work/board.md` with your status.
- Use `git add -A && git commit -m "feat: <description>"` after each completed task.
- Write behavioral fixtures FIRST (ZIO Test), then implementation.
- Do NOT modify `modules/core/**` frozen contracts without an ADR.
- The llama.cpp server runs on `127.0.0.1:8081` — NEVER make HTTP calls to it
  from product tests (only from the selfdev scripts).
- All documentation must be in English.

---

## PHASE 1: Quality Infrastructure (HIGHEST PRIORITY — do first)

### Task 1.1: Add scalafmt to build (~30 min)
Add sbt-scalafmt plugin to `project/plugins.sbt`:
```scala
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")
```
Add to `build.sbt`:
```scala
ThisBuild / scalafmtOnCompile := false
```
Run `sbt scalafmtAll` to format all code.
Add `sbt scalafmtCheckAll` to the quality gate registry (`scripts/quality-policy/registry.json`).
Registry entry:
```json
"scalafmt-check": {
  "argv": ["sbt", "--batch", "scalafmtCheckAll"],
  "timeout_s": 120
}
```
Add to `fast` profile and `static` profile.

### Task 1.2: Add scalafix static analysis (~45 min)
Add sbt-scalafix plugin:
```scala
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.12.1")
```
Create `.scalafix.conf` with rules:
```conf
rules = [
  DisableSyntax
  LeakingImplicitClassVal
  ProcedureSyntax
]
```
Add `sbt scalafixAll --check` to quality gates.
Registry entry:
```json
"scalafix-check": {
  "argv": ["sbt", "--batch", "scalafixAll", "--check"],
  "timeout_s": 180
}
```

### Task 1.3: Add forbidden API checks (~30 min)
Create `scripts/quality/forbidden_apis.py` that scans `modules/**/*.scala` for:
- `Thread.stop` (deprecated, unsafe)
- `Await.result` without timeout (blocking)
- `Runtime.exec` (use ProcessBuilder instead)
- `System.exit` outside of `raider.cli.Main`
- Hardcoded secrets (patterns like `"sk-", "api_key =", "password ="`)
- Double money arithmetic (should use MicroUsd)

Add to quality gates:
```json
"forbidden-apis": {
  "argv": ["python3", "scripts/quality/forbidden_apis.py"],
  "timeout_s": 60
}
```

### Task 1.4: Expand gate profiles (~30 min)
Update `scripts/quality-policy/registry.json`:
- `fast` profile: add scalafmt-check, scalafix-check, forbidden-apis
- Add `tools` profile: `sbt-test` (runs tools module tests)
- Add `transport` profile: `sbt-test` (runs provider tests)
- Add `repl` profile: `sbt-test` + `repl-smoke`
- Add `ci` profile: `sbt-test` + `cli-smoke`

### Task 1.5: Add REPL smoke gate (~20 min)
Registry entry:
```json
"repl-smoke": {
  "argv": ["sh", "scripts/quality/repl_smoke.sh"],
  "timeout_s": 120
}
```

### Task 1.6: Add CLI smoke gate (~20 min)
Registry entry:
```json
"cli-smoke": {
  "argv": ["sh", "scripts/quality/cli_smoke.sh"],
  "timeout_s": 120
}
```

---

## PHASE 2: Core Improvements (after Phase 1)

### Task 2.1: Streaming output in CLI chat (~2 hours)
File: `modules/cli/src/main/scala/raider/cli/chat/ChatLoop.scala`

Currently tool calls are only shown on stderr AFTER the model response.
Add REAL-TIME streaming:
1. Use `OpenAIChatStreamingBackend` instead of `OpenAIChatBackend`
2. Wire the SSE stream to print text deltas as they arrive
3. Show `→ tool_name(args)` when a ToolCallReady event fires
4. Show `← result_preview` when a tool completes
5. Show the model's text response character-by-character (or in chunks)

Acceptance: `raider chat` shows tool calls and text as they happen, not after.

### Task 2.2: Session persistence (~1 hour)
File: NEW `modules/cli/src/main/scala/raider/cli/chat/Session.scala`

```scala
case class SavedSession(
  id: String,
  messages: List[RequestMessage],
  model: String,
  timestamp: String
)
```

- Save to `~/.raider/sessions/<id>.json` using zio-json codecs
- `:save <name>` in chat saves current history
- `raider chat --resume <name>` loads a session
- `:sessions` lists all saved sessions
- `:load <name>` loads a session into current context

### Task 2.3: Conversation compaction (~1 hour)
When history exceeds 30 messages:
- Summarize the oldest 10 into a single system message
- Keep the most recent 20 intact
- Use the model: send old messages with "Summarize in 2 sentences"
- `:compact` command triggers manually
- Automatic compaction when context exceeds budget

### Task 2.4: fs_patch — diff-based editing (~3 hours)
File: NEW `modules/tools/src/main/scala/raider/tools/files/edit/PatchTool.scala`

Implement unified diff format parsing and application:
- Parse `@@ -start,count +start,count @@` hunk headers
- Match context lines before applying
- Apply +/- line changes
- SHA-256 verification (same as fs_edit)
- Atomic write (temp + rename)
- Register in CodingToolset with JSON schema

Tests: apply a simple patch, context mismatch fails, stale sha fails.

### Task 2.5: fs_tree — project structure (~1 hour)
File: NEW `modules/tools/src/main/scala/raider/tools/files/TreeTool.scala`

Return directory tree as JSON:
- Bounded depth (default 3, max 5)
- Optional glob filter
- Skip .git, target, node_modules
- Max 500 entries
- Register in CodingToolset

### Task 2.6: Test/verify loop (~2 hours)
File: `modules/tools/src/main/scala/raider/tools/CodingToolset.scala`

Enhance `proc_run` to parse common outputs:
- `sbt compile` errors: extract `[error] -- Error: file:line:col message`
- `sbt test` results: extract `X tests passed, Y failed`
- Return structured result: `{"parsed": {"type": "sbt_compile", "errors": [...], "success": true}}`

This enables the model to see structured compilation errors and fix them.

---

## PHASE 3: REPL Live Backend (after Phase 2)

### Task 3.1: Wire REPL to live provider (~2 hours)
Files: `modules/repl/src/main/scala/raider/repl/Main.scala`, `MainBridge.scala`

The REPL currently uses ScriptedModelBackend. Wire it to OpenAIChatBackend:
- Read `RAIDER_PROVIDER` env var (default "mock")
- Read `RAIDER_MODEL` env var for model name
- Construct OpenAIChatBackend when provider is "openai"
- Inject via MainBridge (beware classloader splitting — use ThreadLocal)

The tricky part: the in-process REPL classloader loads classes SELF-FIRST.
Use MainBridge with ThreadLocal to pass the backend across classloader boundary.

### Task 3.2: System prompt in REPL (~30 min)
Prepend to every model call:
```
You are a coding agent in a Scala 3 + ZIO project.
Tools: fs_read, fs_search, fs_edit, proc_run.
Be concise. Use tools before answering.
```

### Task 3.3: Streaming in REPL (~1 hour)
Show tool calls and model responses as they happen in the REPL:
- Print `→ tool(args)` to stderr
- Print `← result` to stderr
- Print model text response to stdout

---

## PHASE 4: Error Recovery & Auto-retry (after Phase 3)

### Task 4.1: Compilation error retry loop (~2 hours)
When `proc_run` returns compilation errors:
1. Send the errors back to the model as context
2. Ask the model to fix them
3. Re-run compilation
4. Repeat up to 3 times
5. If still failing, report the errors to the user

This creates the "edit → compile → fix" loop that makes the agent productive.

### Task 4.2: Rate limiting and backoff (~1 hour)
In the provider backends:
- Track 429 responses
- Implement exponential backoff (1s, 2s, 4s)
- Maximum 3 retries before giving up
- Use ZIO's `retry` combinator with `Schedule.exponential`

### Task 4.3: Prompt engineering (~1 hour)
Improve the coder system prompt:
```
You are an expert Scala 3 + ZIO developer working on the Raider project.

Available tools:
- fs_read(path, max_lines): Read a file. Returns content + sha256.
- fs_search(query, glob): Search for text in files.
- fs_edit(path, content, expected_sha256): Write a file. Use sha from fs_read.
- fs_patch(path, diff, expected_sha256): Apply a diff patch.
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
- Prefer fs_patch for small changes, fs_edit for new files
```

---

## PHASE 5: Packaging & CI (after Phase 4)

### Task 5.1: GitHub Actions CI (~1 hour)
File: NEW `.github/workflows/ci.yml`

```yaml
name: CI
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - uses: coursier/cache-action@v6
      - run: sbt --batch compile
      - run: sbt --batch test
      - run: sbt --batch scalafmtCheckAll
  quality:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - run: make quality-changed QUALITY_EXECUTE=1
```

### Task 5.2: Fat JAR packaging (~1 hour)
Add sbt-assembly plugin:
```scala
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.1.5")
```
Create a `raider-cli` assembly that produces a standalone JAR:
```sh
sbt raiderCli/assembly
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar chat --provider openai
```

### Task 5.3: Makefile improvements (~30 min)
Add targets:
```makefile
raider-jar:
	sbt --batch raiderCli/assembly

raider-chat:
	java -jar modules/cli/target/scala-3.9.0/raider-cli.jar chat --provider openai

raider-run:
	java -jar modules/cli/target/scala-3.9.0/raider-cli.jar run --agent coder --provider openai --input "$(INPUT)" --out artifacts/selfdev

quality-full:
	COURSIER_CACHE=/tmp/cc-master sbt --batch test
	COURSIER_CACHE=/tmp/cc-master sbt --batch scalafmtCheckAll
	python3 scripts/quality/forbidden_apis.py
	make quality-changed QUALITY_EXECUTE=1
	sh scripts/quality/repl_smoke.sh
	sh scripts/quality/cli_smoke.sh
```

---

## PHASE 6: Documentation (last, after all code changes)

### Task 6.1: Write API documentation
- Document all public APIs in core, runtime, tools
- Include ScalaDoc comments
- Add usage examples

### Task 6.2: Write architecture guide
- Explain the module structure
- Describe data flow (model → tools → result)
- Explain the budget/admission system

### Task 6.3: Write contributor guide
- How to add a new tool
- How to add a new provider
- How to run quality gates
- How to write tests

---

## Verification Checklist (run after ALL tasks)

```sh
# 1. Full compile (zero warnings)
COURSIER_CACHE=/tmp/cc-master sbt --batch compile

# 2. Full test suite
COURSIER_CACHE=/tmp/cc-master sbt --batch test

# 3. Format check
COURSIER_CACHE=/tmp/cc-master sbt --batch scalafmtCheckAll

# 4. Static analysis
COURSIER_CACHE=/tmp/cc-master sbt --batch scalafixAll --check

# 5. Forbidden APIs
python3 scripts/quality/forbidden_apis.py

# 6. Quality gates (all profiles)
COURSIER_CACHE=/tmp/cc-master make quality-changed QUALITY_EXECUTE=1

# 7. REPL smoke
sh scripts/quality/repl_smoke.sh

# 8. CLI smoke
sh scripts/quality/cli_smoke.sh

# 9. PTY smoke
python3 scripts/quality/repl_pty_smoke.py

# 10. Live self-hosting test (requires llama.cpp on 8081)
sh scripts/selfdev/raider.sh run --agent coder --provider openai \
  --input "Read build.sbt and list the modules" --out artifacts/selfdev
```

ALL of these must pass. If any fails, fix it before committing.

## Commit Strategy
After each PHASE (not each task), create a commit:
```sh
git add -A
git commit -m "feat(phase-N): <description of what was done>"
```

Final commit should be:
```sh
git add -A
git commit -m "feat: nightly improvements — quality gates, streaming, tools, packaging"
```
