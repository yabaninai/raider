# Raider Onboarding (RAI-080)

Getting-started guide for a new user: from a clean clone to the first
agent response. Source references below describe inspected code in
`modules/cli/src/main/scala/raider/cli/**` (`Main`, `CliArgs`),
`modules/repl/src/main/scala/raider/repl/**`, `modules/tools`, `Makefile`,
`build.sbt`, `README.md`. References in the form `file:line` are the source of
truth; if the code changes, this guide must be rechecked. This guide does not
claim its setup commands were executed as part of this audit. For commands and
results checked by the runnable examples verifier, see the [current launch
guide](user-guide.md) and [verified REPL/bundle gallery](user-guide-repl.md).
This is documentation, not a gate: UX01 thresholds and metrics are NOT measured
or claimed here; they remain obligations of their cards (see
`docs/working-product.md`, `docs/quality-gates.md`).

---

## 1. Requirements

- **JDK 21** — CI builds with Temurin 21 (`.github/workflows/ci.yml:8-9`);
  Scala 3.9.0 requires JDK 17+ minimum and is verified on 21.
- **sbt 1.11.4** — pinned in `project/build.properties:1`; the `sbt`
  launcher must be installed.
- **Scala 3.9.0 + ZIO 2.1.26 + zio-json 0.10.0 + JLine 4.0.14** — pinned
  versions (`build.sbt:3,10-11`; JLine: `build.sbt:97-99`). No new dependencies
  are added (AGENTS.md language policy).
- **coursier cache**: by default `COURSIER_CACHE=/tmp/cc-master`
  (`Makefile:7`) is a make variable, not an environment substitution.
- **`toolchain.json`**: `make bootstrap` (`Makefile:13-14`) downloads the pinned
  set of jars (scala3-compiler/scala3-repl/scala3-library 3.9.0, zio,
  zio-json, jline, coursier-interface — `scripts/bootstrap.sh:9-17`) into
  `artifacts/tools/m2` and writes an inventory (coordinates, sizes,
  sha256) in `artifacts/toolchain/toolchain.json`
  (`experiments/repl-spike/fetch_toolchain.py:14-17`). The operation is
  idempotent; in a prepared snapshot/CI the jars are already present, so
  bootstrap does NOT need to be run again.

Build check without the REPL: `make compile` → `sbt --batch compile`
(`Makefile:16-17`).

---

## 2. First REPL session (mock)

The REPL uses a real in-process Scala 3.9.0 compiler
(`modules/repl-engine`, `build.sbt:90-100`), separate from the headless CLI
distribution (which has no compiler — `build.sbt:150`). Do not launch the
interactive REPL through sbt: the sbt launcher can consume or redirect its
stdin. Use the direct Java classpath path below, as in the verified [REPL
gallery](user-guide-repl.md); the [current launch guide](user-guide.md) covers
the supported setup and configuration.

```sh
sh scripts/build/user-examples.sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main
```

The module's only entry point is `raider.repl.Main`
(`modules/repl/src/main/scala/raider/repl/Main.scala:221`; sbt settings
`run / fork` — `build.sbt:122-125`). Without environment variables, the
scripted/mock backend is used: `RAIDER_PROVIDER` defaults to `mock`
(`repl/Main.scala:65`), and the prelude creates a `ScriptedModelBackend` and
session (`repl/Main.scala:194-207`) — no network or keys are needed.

The banner suggests the first steps (`repl/Main.scala:232-239`):

```scala
scout.ask("hello")            // foreground response from the fixture agent
val j = worker.start("bg")    // background task
j.await()                     // wait for the result
:quit
```

- `scout.ask(prompt)` — a blocking foreground request
  (`modules/repl/src/main/scala/raider/repl/facade/FacadeOps.scala:192-193`).
- Fixture response is clearly marked: `[scripted-fixture] mock answer …`
  (`repl/Main.scala:199`).

---

## 3. First headless run (mock) and artifacts

Build the headless fat jar (CLI without compiler/JLine — `build.sbt:150-151`,
main class `raider.cli.Main` — `build.sbt:169`):

```sh
make raider-jar
```

(`Makefile:58-59` → `sbt --batch raiderCli/assembly`; the result is
`modules/cli/target/scala-3.9.0/raider-cli.jar`.)

The first run uses the mock provider, with no keys or network:

```sh
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar run \
  --agent scout --provider mock \
  --input "Summarize what Raider is in one sentence" \
  --out artifacts/first-run
```

- The `run` subcommand is parsed by `CliArgs.parse`
  (`modules/cli/src/main/scala/raider/cli/run/CliArgs.scala:95`);
  built-in agents `scout|worker|coder` are listed in `CliArgs.scala:72`.
- Exactly ONE JSON result line is printed to stdout
  (`modules/cli/src/main/scala/raider/cli/Main.scala:171-176`):
  `{"status":"Succeeded","exitCode":0,"workDir":"..."}`.
- `--agent scout|worker` runs mock loops without tools; `coder` uses real
  workspace tools `fs_read, fs_search, fs_edit, fs_patch, fs_tree,
  proc_run` (`CliArgs.scala:69-72`; the set is in
  `modules/tools/src/main/scala/raider/tools/CodingToolset.scala:202-250`;
  wiring — `HeadlessRunner.scala:394-400`).

Artifacts are written under `<--out>/work-<8 hex>`, one directory per run
(`modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala:112-116`,
with a UUID suffix; concurrent runs do not share directories):

| File | Description | Source |
| --- | --- | --- |
| `run.json` | Run result; atomically written using temp+rename, validated BEFORE writing | `HeadlessRunner.scala:500-530` |
| `events.jsonl` | Job-layer event envelopes (schemaVersion 1), streamed during the run | `HeadlessRunner.scala:216,531-535` |
| `summary.md` | Human-readable summary + exit code | `HeadlessRunner.scala:562-576` |
| `transcript.json` | Model-loop event trace (best effort) | `HeadlessRunner.scala:285-305` |

Mock backend responses are always marked with `[mock]`
(`modules/cli/src/main/scala/raider/cli/run/MockBackend.scala:16,33`): a
fixture never presents itself as a live response. The optional `--mock` flag
(`CliArgs.scala:111`) also records fixture mode in `summary.md`
(`HeadlessRunner.scala:537-540`); by itself it does NOT replace the backend —
the provider is selected by `--provider` (default `mock`, `CliArgs.scala:36`).

Alternative without the fat jar: a classpath launcher with model auto-detection:
`scripts/selfdev/raider.sh run --agent coder --provider openai --input "…"
--out artifacts/selfdev` (`scripts/selfdev/raider.sh`).

---

## 4. Providers and keys

The only supported providers are `mock | openai | anthropic`
(`CliArgs.scala:149-158`). OpenAI-compatible and Anthropic Messages transports
are implemented (`ProviderBackend.scala:29-57`; Anthropic uses native
`/v1/messages`, `provider-anthropic`). Anthropic response handling includes
native `tool_use`/`tool_result`, but request encoding does not yet send tool
definitions; RAI-127 remains open, so tool-call parity is not implemented.

**Keys come ONLY from the environment, never from argv** (RAI-064):

- `RAIDER_API_KEY` — the key value; a non-empty value takes precedence
  (`modules/cli/src/main/scala/raider/cli/run/Credentials.scala:26,59-61`);
- `RAIDER_API_KEY_CMD` — helper command, run through `sh -c`; the key is the
  first stdout line, and the helper is limited to 10 seconds
  (`Credentials.scala:29,79,97-159`);
- `--api-key` in argv is deliberately refused: typed error `RA-CFG`,
  exit 21 (`CliArgs.scala:114-120`; for `chat` — `Main.scala:116-122`).
  The value is never echoed.
- Neither the key nor helper command text is logged or written to artifacts;
  the safe form is `mask()` (first 4 characters + `***` or `<redacted>`,
  `Credentials.scala:35-37`).

Interactive chat (the `chat` subcommand, `Main.scala:112-137`):

```sh
export RAIDER_API_KEY="<key from your secret store>"   # or RAIDER_API_KEY_CMD='<helper>'
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar chat \
  --provider openai --base-url <url> --model <id>
```

- `chat` flags: `--provider` (default `openai`, `Main.scala:128`),
  `--base-url` (default `http://127.0.0.1:8081/v1`, `Main.scala:129`),
  `--model` (default: auto-detect ONLY at
  `http://127.0.0.1:8081/v1/models`, otherwise `local`; `Main.scala:37-52,130`),
  `--workspace`, `--resume <name>` (`Main.scala:131-132`; `--resume`
  loads a saved session, `ChatLoop.scala:150-172`).
- Session commands: `:help :quit(:q/:exit/Ctrl+D) :clear :context :tools
  :trace :save <name> :sessions :load <name> :compact :fix :pipe :delegate`
  (`modules/cli/src/main/scala/raider/cli/chat/ChatLoop.scala:296-325`,
  help: `ChatLoop.scala:767-784`; sessions are stored in `~/.raider/sessions/`,
  `ChatLoop.scala:775`).

One-shot run with a live model and tools:

```sh
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar run \
  --agent coder --provider openai \
  --base-url <url> --model <id> \
  --input "Read README.md and describe this project" \
  --out artifacts/live-run
```

The workspace root is set with `--workspace <dir>`, defaulting to the current
directory (`HeadlessRunner.scala:388-393`). Other `run` flags: `--input-json <file>`
instead of `--input` (mutually exclusive — `CliArgs.scala:141-148`),
`--bundle <jar> --workflow <name>` instead of `--agent` (mutually exclusive —
`CliArgs.scala:177-203`), and `--context <file>` for ci-context JSON. The file
must exist when parsed (`CliArgs.scala:166-176`). IMPORTANT: `--ci github|gitlab`,
mentioned in `HeadlessRunner` javadoc (RAI-100), is NOT yet accepted by the argv
parser (`CliArgs.scala:76-89` does not include it; `Main` always passes
`ci = None` — `HeadlessRunner.scala:91-97`); attempting to pass it returns
`unknown argument '--ci'`.

REPL with a live provider (`raider.repl.Main`): set `RAIDER_PROVIDER=openai`
(or `anthropic`) plus `RAIDER_BASE_URL`/`RAIDER_MODEL`
(`repl/Main.scala:65-67`). The key is resolved the same way as in the CLI —
`RAIDER_API_KEY` / `RAIDER_API_KEY_CMD`
(`repl/Main.scala:69-72`;
`modules/repl/src/main/scala/raider/repl/LiveCredentials.scala:31-34,89-106`).
The live openai/anthropic branches build real streaming backends
(`repl/Main.scala:115-161`); WITHOUT a resolvable key, the REPL does NOT fail:
it prints a notice (environment variable names only) and continues on the
scripted/mock path
(`repl/Main.scala:101-102`; `LiveCredentials.scala:77-81`).

HTTP status mapping (both live providers): 401/403 → `RA-AUTH`,
429 → `RA-RATE` (automatic retries at 1s/2s/4s, maximum 3 —
`ProviderBackend.scala:59-62`), 5xx → `RA-UNAVAIL`, other statuses → `RA-STREAM`
(`modules/provider-chat/src/main/scala/raider/provider/chat/stream/OpenAIChatStreamingBackend.scala:20-21,125-160`;
`modules/provider-anthropic/src/main/scala/raider/provider/anthropic/AnthropicMessagesBackend.scala:28-30,263-265`);
all map to exit 20. In `run`, backend construction errors are typed refusals
(`ProviderBackend.scala:63-74`); in `chat`, a backend setup refusal visibly
falls back to `[fallback-mock] backend setup failed…`
(`ChatLoop.scala:786-808`) — it is never presented as a live connection.

For base URLs (OpenAI-compatible profile): the URL is used as provided,
and the request is `POST <base>/chat/completions`. Raider works directly with
OpenAI/Anthropic-compatible APIs without a gateway; protocols are described in
[provider-protocols.md](provider-protocols.md). Real paid calls are permitted
only with an explicitly set mode and budget (AGENTS.md); development/CI use
mock/replay.

---

## 5. REPL commands and facade (brief)

- **Tab completion** — actual candidates from the dotty compiler for the current
  session state (`repl/Main.scala:284-290`;
  `modules/repl/src/main/scala/raider/repl/CompletionSupport.scala:45-64`):
  `hel`<Tab>, `List(1).map`<Tab>, `xs.`<Tab>. Insertion is local to the trailing
  identifier segment (`CompletionSupport.scala:31-35`).
- **Multiline input** accumulates in a buffer; a blank line submits it
  (`repl/Main.scala:322-342`; the brace-balance heuristic is at `356-365`).
  Ctrl+C in a TTY clears the pending buffer instead of killing the session
  (`repl/Main.scala:291-297,309-312`).
- **Console commands** (`modules/repl/src/main/scala/raider/repl/Commands.scala:17-78`):
  `:help` (`:h`), `:jobs`, `:cancel <id>`, `:log <id>`, `:reset`, `:quit`
  (`:q`, `:exit`), and `:type <expression>`. The type query uses native dotty TypeOf against current bindings without evaluating the expression (RAI-124). The diagnostic prefix and PTY oracle follow-ups remain in RAI-130.
  `:log` requires that a task have a trace (`modules/repl/src/main/scala/raider/repl/facade/ReplCommands.scala:140-141`).
- **Agent facade** (`FacadeOps.scala`):
  `scout.ask("…")` (192-193); a lazy descriptor
  `scout("…").run() / .ask() / .start() / .map(f)` (169-187, 198-203);
  background task `worker.start("…")` → `JobHandle` (208-214) with
  `.await()` (219-227), `.cancel()` (230-231), `.watch()` (236-244);
  composition `all(scout, worker).ask("…")` / `all(askA, askB).run()` (261-279);
  `batch(inputs, parallelism)(f).run()/.collect()` (284-311);
  multi-turn chat `openSession(worker).ask("…")` (321-345).
  Steering (`send`) returns a typed refusal because it is outside the W10 slice (249-252).
- In the scripted/mock prelude, `scout` and `worker` are wired
  (`repl/Main.scala:205-206`); the live prelude also wires `coder`
  (`repl/Main.scala:186-188`).
- Honest shutdown: active tasks are reported and discarded on exit — there is
  NO durable resume (`modules/repl/src/main/scala/raider/repl/facade/ReplCommands.scala:147-156`; `repl/Main.scala:400-410`).

---

## 6. Plugins (brief)

Implemented (RAI-075/083/101): the `plugin` subcommand is routed BEFORE
`run`/`chat` (`Main.scala:101-109`); the catalog is `~/.raider/plugins`, overridden by
env `RAIDER_PLUGINS_DIR` (`modules/cli/src/main/scala/raider/cli/run/PluginCli.scala:78-85`).

```sh
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar plugin list
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar plugin install <jar>
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar plugin remove <name>
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar plugin enable <name>
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar plugin disable <name>
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar plugin update <name> <jar>
java -jar modules/cli/target/scala-3.9.0/raider-cli.jar plugin rollback <name>
```

The dispatcher is `PluginCli.execute` (`PluginCli.scala:91-112`); usage is in
`PluginCli.scala:71-73`. Output is one plain JSON line on stdout; exit 0 means
success, and a typed refusal means exit 21 (`Main.scala:92-98`; spec:
`modules/cli/src/test/scala/raider/cli/MainPluginSpec.scala`). The install command
checks the manifest + payload sha256 + co-install preflight; update stores
`<name>.prev.jar` for rollback (`PluginCli.scala:13-45,157-198,301-405`).
Semantics are limited to catalog files: a running session keeps its own
classloader and picks up a new version only after restart
(`PluginCli.scala:47-55`).

---

## 7. Troubleshooting: typed errors

Where to look:

- argv parsing errors → stderr in the form `raider: <CODE>: <details>` +
  the corresponding family exit code (`Main.scala:140-142`).
- `chat` preflight (including missing key) → stderr + exit 21
  (`ChatLoop.scala:100-110`).
- `run` refusals → JSON result line on stdout, `run.json` (`error` field with
  typed code), and `summary.md` (`HeadlessRunner.scala:145-171,541-544`);
  the tool trace and `✗ FAILED [CODE]` are streamed to stderr
  (`HeadlessRunner.scala:307-328`).
- The REPL does not fail when a key is missing: it prints a notice and uses scripted/mock
  (`repl/Main.scala:101-102`).

Common cases:

- `RA-CFG: missing credentials for provider 'openai': set RAIDER_API_KEY
  (env) or RAIDER_API_KEY_CMD (helper command)…` — the live provider has no
  key. Preflight runs BEFORE any network call or spend
  (`HeadlessRunner.scala:375-378`; `Credentials.scala:67-73`). Set the env var
  or helper (see section 4).
- `--api-key` in argv is deliberately refused as `RA-CFG`, exit 21; the value
  is not echoed (`CliArgs.scala:114-120`). A helper that fails to print the
  key on its first stdout line or exceeds 10 seconds also returns `RA-CFG`
  (`Credentials.scala:135-151`).
- `unknown --provider '…' (mock|openai|anthropic)` — exit 21
  (`CliArgs.scala:149-158`).
- `unknown argument '…'` / `$flag requires a value` / `$flag specified
  twice` — check the flags against the sections above (`CliArgs.scala:121-128`).
- `unknown built-in agent '…'; known: coder, scout, worker` — exit 21
  (`CliArgs.scala:180-185`).
- `--input and --input-json are mutually exclusive` /
  `--agent is mutually exclusive with --bundle/--workflow` /
  `--bundle requires --workflow` — exit 21 (`CliArgs.scala:141-148,177-203`).
- `--context file does not exist: <path>` — exit 21 during parsing
  (`CliArgs.scala:166-176`); malformed context payload is refused with a typed
  error BEFORE dispatch (`HeadlessRunner.scala:580-602`).
- `--ci` is rejected as `unknown argument` — the flag has not yet been added
  to the parser (see section 4; `CliArgs.scala:76-89`).
- `RA-AUTH` (provider rejects key, HTTP 401/403) / `RA-RATE` (429; retries at
  1s/2s/4s) / `RA-UNAVAIL` (5xx) / `RA-STREAM` — exit 20. Check the key's
  expiry and scopes.
- Budgets: `RA-PBUDGET` (provider limiter) or `RA-LBUDGET` (local admission) —
  exit 23. Tool policy `RA-TOOLDENY` — exit 22. Deadline `RA-DEADLINE` — exit 24.

Stable exit codes are part of the [ci-runtime.md](ci-runtime.md) §8 contract; constants are in
`modules/core/src/main/scala/raider/core/result.scala:21-32`, and the mapping
`HeadlessRunner.exitCodeFor` (`HeadlessRunner.scala:68-86`); there is no
catch-all mapping to zero:

| Code | Family (constant) | Typical codes |
| --- | --- | --- |
| 0 | `Success` | Successful completion |
| 10 | `TaskRejected` | `RA-OUT`, `RA-TOOLFAIL`, `RA-CHILD` |
| 20 | `RuntimeFailure` | `RA-AUTH`, `RA-RATE`, `RA-UNAVAIL`, `RA-STREAM` |
| 21 | `ConfigInvalid` | `RA-CFG`, `RA-INP`, `RA-COMPILE` (parsing, preflight, bundle) |
| 22 | `PolicyDenied` | `RA-TOOLDENY` |
| 23 | `BudgetExhausted` | `RA-PBUDGET`, `RA-LBUDGET` |
| 24 | `DeadlineExceeded` | `RA-DEADLINE` |
| 25 | `NeedsApproval` | `RA-CAP` |
| 26 | `Uncertain` | `RA-CANCEL`, `RA-TOOLUNCERT` |
| 27 | `ArtifactWriteFailure` | `RA-JOURNAL` |
| 130 / 143 | `SigInt` / `SigTerm` | SIGINT / SIGTERM (bounded shutdown of up to 5 s — `Main.scala:14-19,54`) |

Error codes are defined in `modules/core/src/main/scala/raider/core/errors.scala:18-70`.

---

## Where to go next

- Product goals and working acceptance: [working-product.md](working-product.md).
- Gates and required checks: [quality-gates.md](quality-gates.md).
- Headless/CI contract (artifacts, exit codes, trust): [ci-runtime.md](ci-runtime.md).
- More on the REPL: [product-repl.md](product-repl.md); plugins: [plugins.md](plugins.md).
- Project and build overview: [../README.md](../README.md).

Reminder: this guide does not claim that UX01 thresholds have been met; those
measurements belong to separate cards. Nothing described here requires secrets
in argv, files, or examples — only references to environment variables.
