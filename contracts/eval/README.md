# Eval task fixtures (RAI-081)

Six small real-task fixtures for the agent quality eval required by
[quality-gates.md §11](../../docs/quality-gates.md) and W16 of
[working-product.md](../../docs/working-product.md). Acceptance IDs targeted:
EVAL-01..03 of the protected task eval card family (RAI-041 lineage; this
authoring card: RAI-081).

Set: `repo-search`, `structured-extraction`, `safe-edit`, `test-driven-bugfix`,
`review-planted-bug`, `parallel-synthesis`.

## Layout (per task)

```
contracts/eval/tasks/<name>/
  task.md         agent-facing task statement
  meta.json       {"name", "kind", "budget_hint"}
  fixture/        mini repo handed to the agent as its workspace (planted defect where applicable)
  hidden-tests/   PROTECTED verifier: check.sh (+ expected/ or regression data)
  solution/       reference solution delta over fixture/ (sanity run only)
  verify.sh       entry point: runs hidden-tests against a workspace
```

`meta.json` fields: `name` (task id), `kind` (search | extraction | edit |
bugfix | review | synthesis), `budget_hint` (recommended step/time budget for
the future eval run; not a gate threshold).

## Running the sanity check (no LLM, no network)

Each fixture is self-contained and verifiable without an LLM and without a
Scala toolchain (POSIX shell; `python3` only for JSON deep-compare in
structured-extraction and parallel-synthesis).

```sh
# syntax check of all scripts
sh -n contracts/eval/tasks/*/verify.sh
sh -n contracts/eval/tasks/*/hidden-tests/check.sh

# sanity: reference solution must pass its own verifier
sh contracts/eval/tasks/repo-search/verify.sh              # -> exit 0
sh contracts/eval/tasks/structured-extraction/verify.sh    # -> exit 0
sh contracts/eval/tasks/safe-edit/verify.sh                # -> exit 0
sh contracts/eval/tasks/test-driven-bugfix/verify.sh       # -> exit 0
sh contracts/eval/tasks/review-planted-bug/verify.sh       # -> exit 0
sh contracts/eval/tasks/parallel-synthesis/verify.sh       # -> exit 0
```

`verify.sh` contract:

- `verify.sh <workspace-dir>` — grade the given agent workspace;
- `verify.sh` (no args) — sanity mode: copy `fixture/` to a temp dir, overlay
  the `solution/` delta, grade that;
- exit codes: `0` pass, `1` verification failed, `2` usage/environment error.

The fixture mini-repos are deliberately shell-based (instead of compiled
Scala) so that grading is deterministic, fast and network-free; the sample
Scala sources inside fixtures are task material, not product code.

## How the eval card must run these (future work)

1. Copy ONLY `fixture/` into a fresh isolated agent workspace per attempt.
2. Run the agent on that workspace with the tool policy from the eval card;
   the workspace must not contain `hidden-tests/`, `solution/`, `verify.sh`
   or any other task-level files.
3. After the attempt, grade with `verify.sh <workspace-dir>` and record
   stdout, exit code and the diff of the workspace as the verdict artifact.
4. Report usefulness separately from safety/protocol gates
   (quality-gates.md §11); a failed verifier is `failed`, never `skipped`.

## Protected verifier rules (quality-gates.md §11)

- `hidden-tests/`, `solution/` and `verify.sh` are verifier material. They
  live OUTSIDE the agent workspace by construction; never copy them into a
  workspace, never reveal their content in agent prompts, transcripts or
  context. Eval holdout material must not enter agent context either.
- For coding tasks (`safe-edit`, `test-driven-bugfix`) the verifiers enforce
  hidden regression tests and diff constraints (protected files byte-identical,
  no extra files, only the declared paths changed). Deleting or weakening
  visible tests cannot pass: `test-driven-bugfix` also byte-compares
  `tests/run-tests.sh` against its baseline and runs its own hidden cases.
- Any change to fixtures or hidden tests is a corpus change and requires
  independent review in a separate card (RAI-041 contract).

## No-answer rule

`fixture/` never contains the answer or the solution: ground truth lives only
in `hidden-tests/` and the `solution/` delta. For review-planted-bug the
planted defect and its line numbers are stated only in `hidden-tests/`.

## Limitations

- LLM execution of these fixtures is a separate card (with its own budget and
  repeat policy per quality-gates.md §11); this directory only authoring.
- Negative/adversarial cases (EVAL-02/03) run in the eval card, not here;
  the sanity negative example in the card report shows one verifier rejecting
  a broken workspace.
