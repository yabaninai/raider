# Generic forge stand example

The stand is provided in the testkit module so protocol consumers can exercise it without adding a production forge dependency. A small caller can construct `LocalForgeStand`, commit a candidate tree, create a pull request, inspect its typed status, and merge it:

```scala
import raider.testkit.sdlc.forge.*
import ForgeProtocol.*

val forge = LocalForgeStand("base")
val commit = forge.commit(Request(OperationId("op-1"), OperationKind.Commit,
  Preconditions("base"), tree = Some("candidate-tree")))
val pr = forge.createPullRequest(Request(OperationId("op-2"), OperationKind.PullRequest,
  Preconditions("candidate-tree")))
val status = forge.pullRequestStatus(pr.pullRequestId.get)
```

Use a fresh operation identity for a new authorized attempt. On `TimedOutUncertain` or `Unknown`, call `reconcile` before any new mutation. Repeating an uncertain operation without that read returns `ReconcileRequired`. This in-memory stand is the fast protocol layer.

`DisposableGitForgeStand.create()` provides the slower filesystem-backed fixture. It initializes an isolated temporary repository, commits on `main`, creates a `pr/<id>` branch, reports local pull-request status, and merges that branch into `main`. It makes no remote calls or pushes and must be closed to remove the temporary repository. `LocalForgeStandSpec` also exercises an asynchronous `Pending` window with ZIO's virtual clock before the injected result transitions to `TimedOutUncertain` and is reconciled.
