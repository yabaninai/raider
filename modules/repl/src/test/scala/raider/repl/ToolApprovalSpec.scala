package raider.repl

import raider.core.*
import raider.repl.facade.*
import zio.*
import zio.test.*

object ToolApprovalSpec extends ZIOSpecDefault:

  private def tool(
      toolName: String,
      recoveryClass: RecoveryClass,
      invoked: Ref[Int]
  ): Tool = new Tool:
    val name = toolName
    val version = 1
    val description = "approval fixture"
    val recovery = recoveryClass
    val timeoutMs = 1000L
    val capabilities = ToolCapabilities(concurrentSafe = false)
    def invoke(argumentsJson: String): ZIO[Scope, RaiderError, String] =
      invoked.update(_ + 1).as("{}")

  def spec =
    suite("ordinary tool-call approval gate")(
      test("approved mutating call reaches the tool with its policy context") {
        for
          invoked <- Ref.make(0)
          captured <- Ref.make(Option.empty[ToolApprovalContext])
          registry <- ZIO.fromEither(
            ToolRegistry.of(
              tool("fs_patch", RecoveryClass.Mutating, invoked)
            )
          )
          hook = new ToolApprovalHook:
            def decide(context: ToolApprovalContext) =
              captured.set(Some(context)).as(ToolApprovalDecision.Approve)
          guarded = FacadeOps.approvalTools(registry, Some(hook))
          result <- ZIO.scoped(
            guarded.lookup("fs_patch").get.invoke("{\"path\":\"a\"}")
          )
          count <- invoked.get
          context <- captured.get
        yield assertTrue(
          result == "{}",
          count == 1,
          context.exists(c =>
            c.tool == "fs_patch" && c.recovery == RecoveryClass.Mutating &&
              c.argumentsJson == "{\"path\":\"a\"}" && c.requestId.nonEmpty
          )
        )
      },
      test("deny and missing approver refuse without invoking a mutation") {
        for
          invoked <- Ref.make(0)
          registry <- ZIO.fromEither(
            ToolRegistry.of(
              tool("proc_run", RecoveryClass.Mutating, invoked)
            )
          )
          denied = FacadeOps.approvalTools(
            registry,
            Some(
              new ToolApprovalHook:
                def decide(context: ToolApprovalContext) =
                  ZIO.succeed(ToolApprovalDecision.Deny)
            )
          )
          absent = FacadeOps.approvalTools(registry, None)
          denial <- ZIO
            .scoped(denied.lookup("proc_run").get.invoke("{}"))
            .either
          noApprover <- ZIO
            .scoped(absent.lookup("proc_run").get.invoke("{}"))
            .either
          count <- invoked.get
        yield assertTrue(
          denial.left.exists(_.isInstanceOf[RaiderError.ToolDenied]),
          noApprover.left.exists(_.isInstanceOf[RaiderError.ToolDenied]),
          count == 0
        )
      },
      test("read-only tools execute without asking the approver") {
        for
          invoked <- Ref.make(0)
          registry <- ZIO.fromEither(
            ToolRegistry.of(
              tool("fs_read", RecoveryClass.ReadOnly, invoked)
            )
          )
          hookCalls <- Ref.make(0)
          hook = new ToolApprovalHook:
            def decide(context: ToolApprovalContext) =
              hookCalls.update(_ + 1).as(ToolApprovalDecision.Deny)
          guarded = FacadeOps.approvalTools(registry, Some(hook))
          result <- ZIO.scoped(guarded.lookup("fs_read").get.invoke("{}"))
          invocations <- invoked.get
          approvals <- hookCalls.get
        yield assertTrue(result == "{}", invocations == 1, approvals == 0)
      }
    )

end ToolApprovalSpec
