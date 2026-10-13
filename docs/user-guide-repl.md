# Scala REPL and typed workflow examples

These examples exercise the current Scala REPL and compiled bundle loader. The REPL examples use `RAIDER_PROVIDER=mock`; each response contains `[scripted-fixture]`. The first five use fixed mock replies. The billing review example can run against a configured OpenAI-compatible model; mock success checks DSL compilation and composition mechanics, not model reasoning.

## Prepare the classpath

From the repository root:

```sh
sh scripts/build/user-examples.sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
```

Each `.repl` file runs in its own process. Bindings do not carry over between examples.

## 1. Scala compiler

The real Scala 3.9.0 REPL compiles values, a case class, a typed function and a multiline definition. The assertion checks the result before printing the marker.

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main \
  < examples/user-guide/repl/01compiler.repl
```

Expected marker: `EXAMPLE-01 PASS`.

## 2. Lazy ask and map

`scout(prompt)` creates a descriptor. `run()` executes it, and `map` transforms the fixed fixture answer into a length. Before running the ask, `:type` inspects its descriptor and a deliberately effectful expression. Printed boundaries isolate the type-query answers, and an assertion checks that inspection did not execute the expression. The final assertion checks that the fixture text is nonempty.

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main \
  < examples/user-guide/repl/02lazy-map.repl
```

Expected marker: `EXAMPLE-02 PASS`.

## 3. Parallel `all` and ordered `batch`

`all` combines two asks into a tuple. `batch` runs an ordered input list with a concurrency bound and keeps the input order in its result. The returned strings are scripted placeholders.

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main \
  < examples/user-guide/repl/03parallel-batch.repl
```

Expected marker: `EXAMPLE-03 PASS`.

## 4. Background job

`start` returns a job handle and `await` obtains its result. `:jobs` prints the completed job snapshot. The process assigns the job ID, so its exact value varies.

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main \
  < examples/user-guide/repl/04background-job.repl
```

Expected marker: `EXAMPLE-04 PASS`, followed by a succeeded `worker` job.

## 5. In-memory session history

`openSession(worker)` keeps two user/assistant turns in the `Chat` object. The assertion checks four messages. This session has no close operation or disk persistence, and fixed mock replies do not prove the second turn used prior context.

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main \
  < examples/user-guide/repl/05session.repl
```

Expected marker: `EXAMPLE-05 PASS`.

## 6. Parallel billing review with typed labels

This runnable Scala REPL example creates two lazy asks about the same actual billing boundary defect, maps their answers into a typed `(label, answer)` result, and executes both with `all(...).run()`. Each prompt asks for useful review or tests and forbids tools.

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main \
  < examples/user-guide/repl/06billing-review.repl
```

Expected marker: `EXAMPLE-06 PASS`. Mock replies are placeholders. To exercise the prompts with a live model, the verifier runs this same file once with the configured OpenAI-compatible endpoint:

```sh
RAIDER_LIVE_APPROVED=1 RAIDER_PROVIDER=openai \
RAIDER_BASE_URL="$RAIDER_BASE_URL" RAIDER_MODEL="$RAIDER_MODEL" \
python3 scripts/quality/user_examples.py --only repl-review --live
```

The run executes both asks through the actual DSL. In live mode, it checks that the JSON test plan agrees with the policy at all three boundary inputs and rejects observed tool requests. Choose the model and budget before opting in.

## 7. Typed release-readiness bundle

`TaskSummary` is the existing workflow entry name. Its typed `Program` parses check records, validates that `tests`, `migration`, and `rollback` are present, rejects duplicate/unknown checks and invalid statuses, then returns a structured readiness decision. Pending checks appear in the result's action field.

```sh
java -cp "$RAIDER_CP" raider.cli.Main run \
  --bundle artifacts/user-examples/task-summary.jar \
  --workflow task-summary \
  --input 'tests=pass,migration=pass,rollback=pending' \
  --out artifacts/user-examples/release-check \
  --mock
```

The run output reports `BLOCKED`, lists `rollback` as pending, and gives a follow-up action. `--mock` satisfies the current CLI backend configuration; the workflow itself makes no model call. Missing required checks and invalid statuses fail validation with CLI exit 21.

The bundle manifest declares `trusted-local`. That is a trust declaration, not a sandbox. Only run bundles you trust.
