# BUILD-TOOLCALL: clean-build ToolCall accessor failure

Discovered during DOCS-DEMO-02 verification on 2026-10-13. A fresh JDK21 build of source 95cb8e9 fails in AgentLoop.scala:751: `tc.id` is not a member of the exported `raider.core.ToolCall`, although `tool.ToolCall` defines the alias. Both normal and clean builds reproduce it. Previously compiled incremental root classes are not clean-build proof.

Allowed paths: `modules/runtime/src/main/scala/raider/runtime/loop/AgentLoop.scala`, existing relevant runtime test files if necessary, this card, and its work record.

Investigate the pinned Scala compiler/export accessor behavior. Apply the smallest source-compatible fix using the existing ToolCall descriptor fields; do not change the public core contract, provider wires, admission, cancellation, or tool semantics. No new abstraction or dependency.

Acceptance: fresh clean compile; native loop existing cancellation/ownership/budget tests; example build and real tool round; required changed-gate commands and independent review. Keep the initial failures as evidence. No old compiled classpath workaround may substitute for the repaired fresh build.
