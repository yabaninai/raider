# Generic forge protocol fixtures

`LocalForgeStandSpec` is the deterministic, in-process Scala protocol fixture for the vendor-neutral forge contract. Its typed operation surface covers commit (tree to ref), pull-request creation/status, merge, read-only reconciliation, and operation correlation. The stand is not a real forge adapter and performs no network or filesystem Git operations.

| Fixture | Scenario | Expected typed result |
| --- | --- | --- |
| SDLC-FORGE-01 | Commit, PR create/status, merge | `Applied`; PR status transitions `Open` to `Merged` |
| SDLC-FORGE-02a | Stale-ref and conflict injection | `Stale`, `Conflict` |
| SDLC-FORGE-02b | Repeated operation identity | `Duplicate`; no repeated mutation |
| SDLC-FORGE-02c | Timeout/unknown after effect | `TimedOutUncertain`, blind retry `ReconcileRequired`, read-only reconcile `AlreadyApplied` |
| SDLC-FORGE-03 | Stale ref followed by fresh reconcile and explicit retry | initial `Stale`, retry with current ref `Applied` |

Each mutating call checks its expected ref against a fresh snapshot immediately before applying the request. An uncertain operation is retained as terminal for mutation purposes until explicit read-only reconciliation. No outcome establishes exactly-once behavior.
