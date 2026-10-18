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

Use a fresh operation identity for a new authorized attempt. On `TimedOutUncertain` or `Unknown`, call `reconcile` before any new mutation. Repeating an uncertain operation without that read returns `ReconcileRequired`. The fixture is local and in-process; it does not contact a forge or exercise Git plumbing.
