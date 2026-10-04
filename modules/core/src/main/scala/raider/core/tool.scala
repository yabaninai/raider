package raider.core

import zio.Scope
import zio.ZIO

/** Tool contract (RAI-002.b freeze of runtime-contracts §10).
  *
  * At the core boundary a tool speaks JSON in/out: the agent loop receives
  * `ToolCallReady(name, call, argumentsJson)` from a ModelBackend and must
  * validate + dispatch against this registry; typed tools (SPI, RAI-028/031)
  * wrap this contract with codecs, they do not replace it.
  *
  * Recovery classes (§10): a tool is read-only, idempotent (with an explicit
  * idempotency key at the invocation site) or mutating non-idempotent. Parallel
  * execution of mutating tools is NOT automatically safe — `concurrentSafe`
  * must be declared honestly; the default is false.
  */
object tool:

  enum RecoveryClass:
    case ReadOnly
    case Idempotent
    case Mutating

  final case class ToolCapabilities(concurrentSafe: Boolean)

  /** Loop-level call descriptor (matches ModelEvent.ToolCallReady 1:1). */
  final case class ToolCall(
      name: String,
      call: ToolCallId,
      argumentsJson: String
  ):
    /** Alias accessor — avoids Scala 3.9 E046 cyclic on `x.call.y` chains. */
    def id: ToolCallId = call

  /** Result matched back by ToolCallId (§8: result is matched to tool_call_id).
    */
  final case class ToolResult(call: ToolCallId, outputJson: String)

  trait Tool:
    def name: String
    def version: Int
    def description: String
    def recovery: RecoveryClass
    def timeoutMs: Long
    def capabilities: ToolCapabilities
    def invoke(argumentsJson: String): ZIO[Scope, RaiderError, String]

    /** JSON Schema for the arguments — sent to the model so it knows what
      * parameters to pass. Default: permissive (any object).
      */
    def parametersJsonSchema: String =
      "{\"type\":\"object\",\"additionalProperties\":true}"

  /** Immutable registry. `of` validates BEFORE any use: unique names, non-empty
    * name, version >= 1, timeout > 0 — a bad registration is a typed
    * InputValidation failure, never a runtime surprise.
    */
  final case class ToolRegistry private (tools: Map[String, Tool]):
    def lookup(name: String): Option[Tool] = tools.get(name)
    def names: List[String] = tools.keys.toList.sorted
    def size: Int = tools.size

  object ToolRegistry:
    val empty: ToolRegistry = ToolRegistry(Map.empty)

    def of(tools: Tool*): Either[RaiderError, ToolRegistry] = build(
      tools.toList
    )

    def build(tools: List[Tool]): Either[RaiderError, ToolRegistry] =
      val duplicates = tools
        .map(_.name)
        .groupBy(identity)
        .collect { case (n, ns) if ns.size > 1 => n }
        .toList
        .sorted
      if duplicates.nonEmpty then
        Left(
          RaiderError.InputValidation(
            s"duplicate tool names: ${duplicates.mkString(", ")}"
          )
        )
      else
        tools.find(_.name.trim.isEmpty) match
          case Some(bad) =>
            Left(RaiderError.InputValidation("tool name must be non-empty"))
          case None =>
            tools.find(_.version < 1) match
              case Some(bad) =>
                Left(
                  RaiderError.InputValidation(
                    s"tool '${bad.name}' version must be >= 1, got ${bad.version}"
                  )
                )
              case None =>
                tools.find(_.timeoutMs <= 0) match
                  case Some(bad) =>
                    Left(
                      RaiderError.InputValidation(
                        s"tool '${bad.name}' timeoutMs must be > 0, got ${bad.timeoutMs}"
                      )
                    )
                  case None =>
                    Right(ToolRegistry(tools.map(t => t.name -> t).toMap))

export tool.{
  RecoveryClass,
  ToolCapabilities,
  ToolCall,
  ToolResult,
  Tool,
  ToolRegistry
}
