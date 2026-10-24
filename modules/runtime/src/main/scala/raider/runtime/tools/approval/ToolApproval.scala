package raider.runtime.tools.approval

import raider.core.{RaiderError, RecoveryClass, Tool, ToolRegistry}
import zio.{UIO, ZIO}

import java.util.concurrent.atomic.AtomicLong

/** The frozen policy stamp and exact invocation payload presented for a
  * per-call approval decision.
  */
final case class ToolApprovalContext(
    requestId: String,
    tool: String,
    recovery: RecoveryClass,
    argumentsJson: String
)

enum ToolApprovalDecision:
  case Approve, Deny

/** An explicit decision for one mutating invocation. */
trait ToolApprovalHook:
  def decide(context: ToolApprovalContext): UIO[ToolApprovalDecision]

object ToolApproval:

  /** Wrap mutating tools with a fail-closed per-call decision. Other recovery
    * classes retain their original behavior; the hook cannot alter the stamp or
    * invocation payload.
    */
  def guard(
      tools: ToolRegistry,
      approver: Option[ToolApprovalHook]
  ): ToolRegistry =
    val approvalSeq = AtomicLong(0L)
    val wrapped = tools.names
      .flatMap(tools.lookup)
      .map: tool =>
        if tool.recovery != RecoveryClass.Mutating then tool
        else
          new Tool:
            def name: String = tool.name
            def version: Int = tool.version
            def description: String = tool.description
            def recovery: RecoveryClass = tool.recovery
            def timeoutMs: Long = tool.timeoutMs
            def capabilities = tool.capabilities
            override def parametersJsonSchema: String =
              tool.parametersJsonSchema
            def invoke(argumentsJson: String) =
              val decision = approver match
                case None => ZIO.succeed(ToolApprovalDecision.Deny)
                case Some(hook) =>
                  hook.decide(
                    ToolApprovalContext(
                      s"tool-${approvalSeq.incrementAndGet()}",
                      tool.name,
                      tool.recovery,
                      argumentsJson
                    )
                  )
              decision.flatMap:
                case ToolApprovalDecision.Approve =>
                  tool.invoke(argumentsJson)
                case ToolApprovalDecision.Deny =>
                  ZIO.fail(
                    RaiderError.ToolDenied(
                      s"approval denied or unavailable for mutating tool '${tool.name}'"
                    )
                  )
    ToolRegistry.build(wrapped).getOrElse(ToolRegistry.empty)
