<!-- Work record. Translated from the Russian original. -->

# RAI-017.a: Typed composition (all/batch/batchCollect) — record

Date: 2026-10-03. Writer: root. Dependencies: RAI-002 slice1 (codecs, previously
accepted), RAI-008 (scopes, accepted), RAI-009 (admission, accepted in this
session)—all ready; started from FROZEN Program/Task
(modules/core/program.scala, task.scala were not changed).

## Deliverable

Production (1 file): `modules/dsl/src/main/scala/raider/dsl/composition/Composition.scala`

- `all(Task, Task): Task[(A,B)]` — one interpreter (Task.zio), zipPar = parallel
  fail-fast (first failure interrupts sibling — COMP-02);
- `all(Program, Program): Program[I,(A,B)]` — heterogeneous arity-2 composition
  with tuple output; construction is lazy and dispatches nothing (API-03);
- `batch(inputs, parallelism)(run): Task[List[R]]` — ordered bounded batch:
  results follow INPUT order regardless of completion order; parallelism limits
  concurrency; an empty batch dispatches nothing; parallelism < 1 → typed
  InputValidation (no silent clamp);
- `batch.collect(...): Task[List[BatchOutcome[R]]]` — domain failures become
  explicit `Failed` outcomes; external interruption and defects are re-raised
  (NOT swallowed), per §5 (verified by interruption test);
- `BatchOutcome` = Succeeded | Failed (typed).

Build: `build.sbt` — raiderDsl received `testSettings` (zio-test; build.sbt is
the coordinator's file).

Tests: `modules/dsl/src/test/scala/raider/dsl/composition/CompositionSpec.scala`
— 10 tests: typed tuple; Program composition; andThen via frozen contract;
fail-fast interrupts sibling (bounded 5s; sibling does not finish); typed
rejection for parallelism=0 (RA-INP); empty batch without dispatch; input-order
results despite reverse completion order; peak concurrency ≤ parallelism;
domain→Outcome (401 → Failed, order preserved); external interruption re-raised
as interruption.

Fixture: `contracts/compile/positive/Composition.scala` — positive compilation
(all arity-2 Programs/Tasks, andThen chain, bounded batch) via pinned dotc
(automatically included by compile.sh glob).

## COMP-03 (maxLLM=1 without deadlock) — cross-reference

Covered by the RAI-009 BUD-03 test (JobManager+Admission, manager.await without
a permit)—recorded in docs/work/records/RAI-009.md; no duplicate test was added
here. Scope/fork/join are RAI-008 SCOPE-01..03 (RaiderScope). The Composition
layer introduces no second semantics for Ref.

## Checks (exact)

- `sbt --batch "raiderDsl/test"` → exit 0, **10 passed / 0 failed**.
- `make quality-changed QUALITY_EXECUTE=1` → exit 0, **6/6 gates**
  (sbt-test ran 43+ tests across all modules: core contracts + runtime 33 +
  dsl 10 + testkit 3), manifest artifacts/quality/20261003-140237/.
- Negative compile case “incompatible andThen does not compile” — existing
  contracts/compile/negative/AndThenMismatch.scala; compile-fixtures gate
  checks it in this same run (exit 0).

## Test defects fixed during the card

1. Eager effects while CONSTRUCTING Task (`Task { sideEffect; zio }` — sideEffect
   runs during construction). Caught by peak-concurrency test: effects must be
   inside ZIO. The production composition code itself is lazy at construction
   (API-03)—confirmed.
2. `fiber.await` already returns Exit—an extra `.exit` breaks the assertion.

## Limitations (honest)

- fork/join DSL wrappers (COMP-03 scope part) are in the runtime layer
  (RaiderScope); DSL facade execution is RAI-018 (not this card).
- Batch with a parallelism limit is not integrated with Admission slots (it is
  Task composition without model calls); batch→model-permit integration is the
  RAI-010 loop.
