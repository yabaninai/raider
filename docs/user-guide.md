# Examples you can run today

This guide documents behavior in the current source checkout. The examples use the pinned Scala 3.9.0 / ZIO 2.1.26 toolchain. They do not imply a published SDK or autonomous release workflow.

## Build and run the examples

Requirements: JDK 21, sbt 1.11.4, Python 3, the JDK `jar` command, and a populated Coursier cache (or access to resolve pinned dependencies). Run from the repository root:

```sh
sh scripts/build/user-examples.sh
python3 scripts/quality/user_examples.py
```

The verifier runs the actual Scala compiler, REPL, bundle loader, native CLI, workspace tools and subprocess tool. Each run writes a report, captured stdout/stderr and hashes under the ignored `artifacts/user-examples/verification-*/` directory. A nonzero command exit, assertion failure, timeout or missing artifact fails verification.

For the Scala REPL, prepare the runtime classpath and start the real compiler-backed console:

```sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main
```

The mock REPL prints `[scripted-fixture]` responses. They are fixed demonstration responses and do not read your repository or solve arbitrary requests. See [REPL and typed workflow examples](user-guide-repl.md).

## 1. Fix a billing boundary bug

The fixture models a realistic cents-based pricing defect: a fixed $10 discount should apply at and above a $100 subtotal, but the initial implementation uses `>` and excludes exactly 10,000 cents. The Scala test covers below, at, and above the boundary.

Run the deterministic offline demo:

```sh
python3 scripts/quality/user_examples.py --only coding
```

This starts a local scripted OpenAI-compatible stand and runs the actual `coder` agent and native workspace tools in a disposable copy. The stand is a fixed tool-call script, not a live model; it checks each preceding tool result before selecting the next action. The expected sequence is:

1. Read `Discount.scala` and record its SHA-256.
2. Search for the strict threshold condition.
3. Compile both Scala files with the pinned Scala 3 compiler.
4. Run `DiscountSpec`; the exact-boundary assertion fails with exit 1.
5. Change `>` to `>=` using the read hash as the edit precondition.
6. Compile again and run the boundary tests; they pass with exit 0.

The verifier checks the transcript, compiler and test exits, hashes, resulting source, and that the checked-in fixture remains unchanged. The final CLI `summary.md` contains `[scripted-billing]` so the fixed stand cannot be mistaken for model output.

### Optional live run

The same fixture can be sent to a live configured OpenAI-compatible provider. Tool definition parity for the Anthropic Messages backend is tracked separately (RAI-127), so this tool-using scenario is not documented as supported on Anthropic. Use the live mode only after the coordinator has selected the endpoint, model and spend budget. Credentials stay in `RAIDER_API_KEY` or `RAIDER_API_KEY_CMD`; never put them in a URL, argv, report, or transcript. The driver records provider, model, sanitized endpoint identity, and run artifacts, but never reads or writes credential values.

```sh
RAIDER_LIVE_APPROVED=1 \
RAIDER_PROVIDER=openai \
RAIDER_BASE_URL="$RAIDER_BASE_URL" \
RAIDER_MODEL="$RAIDER_MODEL" \
python3 scripts/quality/user_examples.py --only coding --live
```

The live mode does not retry a run after an ambiguous result. It verifies the resulting Scala source and successful run; it does not claim that one model run establishes general coding quality. Do not use a paid endpoint until its budget is explicitly set.

## 2. Audit a repository and write evidence

The audit fixture contains a payment configuration that permits plaintext fallback. The offline example uses the native `coder` tools to inspect the tree, read and search the configuration, then create `AUDIT.json` in the disposable workspace. The report records severity, exact path/line/matched text, impact and a concrete remediation. The verifier checks the report schema and content, source hash, tool transcript and that the source configuration was not edited.

```sh
python3 scripts/quality/user_examples.py --only audit
```

As with the bugfix, the local stand scripts the tool calls. It verifies the mechanics and structured evidence path, not model reasoning. An explicitly configured live audit uses an OpenAI-compatible endpoint and the same opt-in and budget requirements:

```sh
RAIDER_LIVE_APPROVED=1 \
RAIDER_PROVIDER=openai \
RAIDER_BASE_URL="$RAIDER_BASE_URL" \
RAIDER_MODEL="$RAIDER_MODEL" \
python3 scripts/quality/user_examples.py --only audit --live
```

The live driver makes one CLI run and does not replay after an ambiguous result. It checks the resulting JSON report and source remains unchanged; model-authored findings still need human review.

## 3. Parallel DSL review with useful prompts

The REPL can compose real requests lazily. This example asks one agent to identify the exact billing boundary defect and another to propose the smallest useful boundary test set. `map` adds stable labels to each answer; `all(...).run()` executes both asks together.

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main \
  < examples/user-guide/repl/06billing-review.repl
```

The mock run verifies that the actual Scala code compiles, both asks execute, and the labels survive. Its fixed responses are placeholders. The same file can be run once against a deliberately configured OpenAI-compatible model:

```sh
RAIDER_LIVE_APPROVED=1 RAIDER_PROVIDER=openai \
RAIDER_BASE_URL="$RAIDER_BASE_URL" RAIDER_MODEL="$RAIDER_MODEL" \
python3 scripts/quality/user_examples.py --only repl-review --live
```

The prompts request analysis of inline code without tools. The live verifier rejects any observed tool request and checks the returned JSON test plan against the stated policy: `9999 → 0`, `10000 → 1000`, `10001 → 1000`. Review prose still needs inspection. The selected model comes from `RAIDER_MODEL`, and the live REPL runs in a disposable workspace.

## 4. Typed release-readiness workflow

The compiled bundle consumes required checks for `tests`, `migration`, and `rollback`. It validates names/statuses, rejects missing or malformed entries, and emits a structured decision with passed checks, pending checks and a follow-up action. It is deterministic Scala code and makes no provider call.

```sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
java -cp "$RAIDER_CP" raider.cli.Main run \
  --bundle artifacts/user-examples/task-summary.jar \
  --workflow task-summary \
  --input 'tests=pass,migration=pass,rollback=pending' \
  --out artifacts/user-examples/release-check \
  --mock
```

The `summary.md` output contains a `BLOCKED` decision and identifies `rollback` as the action to complete. The verifier also checks a missing-required-check input and an invalid status; both must fail with CLI exit 21. Empty test input is not treated as a successful release.

## 5. Inspect run evidence

The billing example produces actual CLI run artifacts: `run.json`, `summary.md`, `events.jsonl`, `transcript.json`, `lineage.scala` and `lineage-data.json`. The verifier checks their presence and status, then compiles the exported Scala lineage projection with the pinned compiler:

```sh
python3 scripts/quality/user_examples.py --only artifacts
```

These artifacts describe a completed run. They are not a durable session, source workflow, or promise that arbitrary runs can be resumed.

## Native coding tools and trust

The current headless `coder` registers `fs_read`, `fs_search`, `proc_run`, `fs_edit`, `fs_patch`, and `fs_tree`. Process requests use structured argv, an empty child environment unless explicitly allowlisted, a workspace cwd, timeouts and bounded output. `scout` and `worker` in headless mode have no workspace tool registry.

A repository command or Scala program executes with the process user's permissions. Workspace containment checks are not a sandbox for trusted code. Keep live examples on disposable workspaces.

## Configure a provider

Headless runs accept `--provider openai|anthropic`, `--base-url`, and `--model`. Supply credentials using `RAIDER_API_KEY` or a `RAIDER_API_KEY_CMD` helper reference; credential values do not belong in CLI arguments. The Scala REPL reads `RAIDER_PROVIDER`, `RAIDER_BASE_URL`, and `RAIDER_MODEL` from its environment. Check the startup notice and response marker: scripted or mock output is not model output. Remote calls require a deliberately selected account and budget.

### Local Yabanin profile

Yabanin is an OpenAI-compatible endpoint; use `RAIDER_PROVIDER=openai`. Choose a model registered with the coding-plan provider when subscription-only routing is required. The locally checked profile requests `glm-5.3-flash`; the gateway model registry identifies it as `zai-coding`.

If you keep a private, Git-ignored `.env.yabanin` file with your endpoint and env/helper credential configuration, load it without typing the key into shell history:

```sh
set -a
. ./.env.yabanin
set +a
RAIDER_LIVE_APPROVED=1 python3 scripts/quality/user_examples.py --only coding --live
```

The private configuration file is not part of the checkout. Set the endpoint, model and budget deliberately before using the opt-in. The examples do not push commits, publish releases or deploy anything.
