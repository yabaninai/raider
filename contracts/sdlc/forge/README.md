# Generic forge protocol fixtures

`LocalForgeStandSpec` is the deterministic Scala protocol fixture for the vendor-neutral forge contract. The in-memory `LocalForgeStand` is the fast protocol layer; `DisposableGitForgeStand` separately initializes and removes a real temporary local Git repository and performs commit, pull-request branch/status, merge, and stale-ref checks against actual refs. Neither stand contacts a remote or acts as a real forge adapter.

| Fixture | Scenario | Expected typed result |
| --- | --- | --- |
| SDLC-FORGE-01 | Commit, PR create/status, merge | `Applied`; PR status transitions `Open` to `Merged` |
| SDLC-FORGE-02a | Stale-ref and conflict injection | `Stale`, `Conflict` |
| SDLC-FORGE-02b | Repeated operation identity | `Duplicate`; no repeated mutation |
| SDLC-FORGE-02c | Timeout/unknown after effect | `TimedOutUncertain`, blind retry `ReconcileRequired`, read-only reconcile `AlreadyApplied` |
| SDLC-FORGE-02d | Disposable Git commit, PR branch/status, merge, stale ref | Actual temporary `refs/heads/main` and `refs/heads/pr/<id>` change; stale expected object ID returns `Stale` |
| SDLC-FORGE-02e | Asynchronous pending-to-timeout window | Injected async commit is `Pending` before virtual-clock deadline, then `TimedOutUncertain`; mutation retry is blocked until read-only reconcile |
| SDLC-FORGE-03 | Competing owners serialize; stale state is read-only reconciled before explicit retry | one concurrent request `Applied`, the other `Stale`; fresh-ref retry `Applied` |

Each mutating call checks its expected ref against a fresh snapshot immediately before applying the request. An uncertain operation is retained as terminal for mutation purposes until explicit read-only reconciliation. No outcome establishes exactly-once behavior.

The async window uses ZIO's virtual test clock, not wall-clock sleeps. The disposable repository uses local Git commands only and is removed after the fixture; Git must be installed for this test.
