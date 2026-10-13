<!-- RAI-072: documentation of ACTUALLY implemented job-cancellation behavior
     (RAI-007/008/059/063). Adds no new decisions; every claim includes a
     file:line reference in this tree. -->

# Job cancellation: JobManager, scopes, OOB channel

This document describes the implemented cancellation mechanics in
`modules/runtime/src/main/scala/raider/runtime/` (plus the UI boundary in
`modules/ui/`). The contract frame is [runtime-contracts.md](runtime-contracts.md)
§4–§5; this document describes actual code behavior.

## 1. State machine and Cancelling

Implemented statuses: `Queued`, `Running`, `Waiting`, `WaitingApproval`,
`Cancelling`, and terminal `Succeeded`, `Failed`, `Cancelled`, `Interrupted`,
`Uncertain` (`JobStatus.scala:12-27` in
`modules/runtime/src/main/scala/raider/runtime/jobs/JobStatus.scala`). The sole
guard `canTransition` checks legal transitions (`JobStatus.scala:29-42`):
`Cancelling` can be entered from `Queued`/`Running`/`Waiting`/`WaitingApproval`;
from `Cancelling`, only `Cancelled`/`Interrupted`/`Uncertain` are allowed. A
terminal status cannot transition again; a late completion attempt is a late
observation (`JobStatus.scala:5-9`).

Cancellation does not write a separate status string: the completion reason is
encoded by the terminal status type and typed error (see §3), not arbitrary
strings.

## 2. JobManager.cancel / cancelAny

`JobManager` owns each job fiber; `cancel`/`cancelAny` are the cancellation
entry point (`JobManager.scala:291-311` in
`modules/runtime/src/main/scala/raider/runtime/jobs/JobManager.scala`):

- Semantics: one atomic STM transaction `transitionSTM(entry, Cancelling)`;
  if the guard rejects the transaction (job is already terminal or the edge is
  invalid), it returns `false` and does nothing; otherwise it returns `true`
  (`JobManager.scala:294-296,305-308`).
- The event hook for transition to `Cancelling` runs BEFORE issuing the
  interrupt — the job event order equals commit order
  (`JobManager.scala:297-303`).
- `cancel` and `cancelAny` have identical semantics; `cancelAny` is the
  heterogeneous variant for scope/admin ownership (`JobManager.scala:282-289`).

Job registration is in a narrow uninterruptible section: there is no window in
which the fiber is already running but the registry does not know about it
(`JobManager.scala:29-32,115-124`). If registration itself is interrupted in
the fork window, no “ghost Running” entry remains: `abortGhost` runs — status
`Cancelled`, typed result `RaiderError.Cancelled` is published
(`JobManager.scala:136-142,334-351`).

## 3. Cancellation racing with completion: cancellation wins

Final completion is one total STM transaction, `settle`
(`JobManager.scala:166-221`):

- If the status is already terminal, this is only a late observation; status
  does not change and the hook is not called (`JobManager.scala:200-204,225`).
- If completion races with a confirmed cancellation (`Cancelling` has already
  been committed and `canTransition` forbids direct `Succeeded`/`Failed`),
  cancellation WINS: status becomes `Cancelled`; waiters receive typed result
  `RaiderError.Cancelled("...cancelled while completing")`; completion is
  recorded as a late observation (`JobManager.scala:205-216,227-243`).

Consequences:

- One terminal winner and one root result completion; exactly one `Terminal`
  event per job (the hook runs once per committed change, and a terminal status
  allows no further transitions — `JobManager.scala:33-39`).
- `await` is idempotent, supports any number of waiters, and does not restart
  the task (`JobManager.scala:22-27,273-278`).
- A watcher defect does not leave waiters hanging: typed `Failed` is published
  (`JobManager.scala:145-161`).
- The registry is bounded (`maxRetained = 256` by default): on overflow, the
  oldest TERMINAL entries are evicted; live jobs are not evicted
  (`JobManager.scala:250-271,408-417`).

## 4. RaiderScope: subtree and truthful reporting

`RaiderScope` (RAI-008) owns forked children and finalizers
(`RaiderScope.scala:26-35` in
`modules/runtime/src/main/scala/raider/runtime/scopes/RaiderScope.scala`):

- `fork` rejects new forks after closing begins with typed
  `RaiderError.Cancelled("scope ... is closing")`; launch+registration is one
  uninterruptible step (no-orphan) (`RaiderScope.scala:45-67`).
- `close(grace)`: cancels all unjoined children via `cancelAny`, waits for a
  terminal result within grace, then runs finalizers LIFO
  (`RaiderScope.scala:77-115`). The entire child cancel+settle is bounded by
  grace: a child stuck in an uninterruptible region cannot hold the scope
  hostage — it is reported as `Uncertain`, never fake `Cancelled`
  (`RaiderScope.scala:84-96`).
- `ScopeCloseReport` is truthful: `cancelledChildren` (confirmed complete),
  `uncertainChildren` (nonterminal at grace), counters and finalizer errors,
  and whether finalizers were interrupted; final status is
  `Closed | Uncertain | Interrupted` (`RaiderScope.scala:9-24,109-115`).
- `scoped`: a body failure is preserved; with a clean body, incomplete cleanup
  turns success into typed failure — `ToolOutcomeUncertain` for Uncertain and
  `Cancelled` for interrupted cleanup — there is no fake success
  (`RaiderScope.scala:119-150`).

## 5. Delegation: model tool, OOB channel, deadlines

Child agents live in the same `JobManager`/root as workflow forks
(`DelegationToolset.scala:17-20` in
`modules/runtime/src/main/scala/raider/runtime/delegate/DelegationToolset.scala`).

- Model tool `cancel_agent`: the parent spends a model round; for an unknown
  child it returns structured JSON feedback, for a known child it calls
  `jobs.cancelAny` and returns `{"child_id":...,"cancelled":true|false}`
  (`DelegationToolset.scala:276-297,333-334`).
- Out-of-band channel (RAI-063): `cancelOutOfBand(childId)` and
  `cancelAllOutOfBand` call `jobs.cancelAny` DIRECTLY — without a model-tool
  round or model admission; cancellation is available even when a “parked”
  child holds the only model slot (`DelegationToolset.scala:52-59,366-387`).
  Return: `false` means unknown or already terminal child;
  `cancelAllOutOfBand` returns IDs whose cancellation was accepted.
- Child deadlines (Scala-level API; the public delegate-tool schema is
  unchanged): `setChildDeadline` schedules an owner-side watchdog; a repeated
  call replaces the previous deadline (`DelegationToolset.scala:389-410`); on
  expiry the watchdog cancels the child outside model admission and bounds
  cleanup with the same grace pattern as `RaiderScope.close`
  (`DelegationToolset.scala:443-478`).
- Truthful deadline result is `DeadlineOutcome`: `Settled` (cancelled and
  terminal within grace), `Uncertain` (nonterminal within grace — unobservable,
  not fake `Cancelled`), `AlreadyTerminal` (deadline fired after completion)
  (`DelegationToolset.scala:412-429,646-647`); default grace is 5 s
  (`DelegationToolset.scala:671-672`).

## 6. Cancellation events and exit code

- The job layer publishes transitions through `StatusLedger`: `to: Cancelling`
  is emitted as `JobStatusChanged(..., to = Cancelling)`, terminal `to` as
  `Terminal(outcome)`; publication errors are swallowed
  (`StatusLedger.scala:36-50` in
  `modules/runtime/src/main/scala/raider/runtime/jobs/StatusLedger.scala`).
- On a cancelled launch/root post-permit cancellation, the loop layer emits
  `ToolFinished(callId, tool, Cancelled)`; an interrupted call emits nothing
  (`LoopEvents.scala:200-220` in
  `modules/runtime/src/main/scala/raider/runtime/loop/LoopEvents.scala`).
- Headless exit code: `RaiderError.Cancelled` maps to `ExitCodes.Uncertain`
  (= 26) (`HeadlessRunner.scala:83` in
  `modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala`;
  `result.scala:29` in `modules/core/src/main/scala/raider/core/`).

## 7. UI boundary: report-only

The tab layer never cancels on its own: `:close` on a live tab returns the
`Hide`/`CancelRequested` choice; `CancelRequested` is REPORTING ONLY (typed
feedback `CancelReported`), there is no direct cancel binding to runtime in the
UI — the binding (RAI-062) is not implemented
(`SessionRegistry.scala:84-95,97-114` in
`modules/ui/src/main/scala/raider/ui/SessionRegistry.scala`;
`ShellExecutor.scala:164-198`; `ShellFeedback.scala:32-40,92-95`). Details:
[tabs.md](tabs.md) §5.

## 8. Honest limitations

- Waiting for interruption is not unbounded: a fiber in an uninterruptible
  region delays cancellation; in this case the scope/watchdog honestly reports
  `Uncertain` rather than waiting forever (`RaiderScope.scala:84-96`;
  `DelegationToolset.scala:443-478`).
- Cancellation wins the status race, but does not rewrite an already published
  result: late completions are only late observations in the counter
  (`JobManager.scala:196-221`).
- The documented semantics do not promise durable resume or exactly-once for
  arbitrary lambdas: cancellation interrupts the fiber; `await` waits for the
  published result and does not restart the task (`JobManager.scala:22-27,273-278`);
  automatic recovery/retry is not implemented (see
  [runtime-contracts.md](runtime-contracts.md) §13).
