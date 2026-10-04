package raider.core

import zio.Scope
import zio.json.jsonDiscriminator
import zio.stream.ZStream

/** ModelBackend: exactly ONE inference attempt over a provider wire
  * (runtime-contracts §3, §9). The native agent loop (RAI-010) drives this;
  * AgentBackend is the agent-level contract and is NOT a rename of this.
  *
  * Events are normalized: providers translate complete tool rounds/usage/errors
  * into these; partial streaming tool arguments are deltas only and are never
  * executable until ToolCallReady.
  */
object backend:

  final case class Usage(
      inputTokens: Long,
      outputTokens: Long,
      currency: Option[String]
  ):

    /** Token sums are currency-neutral; mixing different declared currencies
      * yields an unknown currency rather than silently keeping one side.
      */
    def plus(other: Usage): Usage =
      val mixed = (currency, other.currency) match
        case (Some(a), Some(b)) if a == b => Some(a)
        case (None, None)                 => None
        case _                            => None // mixed/unknown
      Usage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        mixed
      )

  @jsonDiscriminator("type")
  sealed trait ModelEvent

  object ModelEvent:
    final case class Started(attempt: AttemptId) extends ModelEvent

    final case class MetadataObserved(
        attempt: AttemptId,
        key: String,
        value: String
    ) extends ModelEvent

    final case class TextDelta(attempt: AttemptId, text: String)
        extends ModelEvent

    final case class ToolCallDelta(
        attempt: AttemptId,
        call: ToolCallId,
        index: Int,
        argumentFragment: String
    ) extends ModelEvent

    final case class ToolCallReady(
        attempt: AttemptId,
        call: ToolCallId,
        name: String,
        argumentsJson: String
    ) extends ModelEvent

    final case class UsageObserved(
        attempt: AttemptId,
        usage: Usage,
        priceKnown: Boolean
    ) extends ModelEvent

    final case class Finished(attempt: AttemptId, reason: String)
        extends ModelEvent

    final case class Failed(attempt: AttemptId, error: RaiderError)
        extends ModelEvent

  final case class RequestMessage(role: String, content: String)

  /** A tool definition for the wire (OpenAI tools format subset). */
  final case class ToolDefinition(
      name: String,
      description: String,
      parametersJsonSchema: String
  )

  final case class ModelRequest(
      model: String,
      messages: List[RequestMessage],
      maxOutputTokens: Int,
      tools: List[ToolDefinition] = Nil
  )

  sealed trait CapabilityStatus

  object CapabilityStatus:
    case object Supported extends CapabilityStatus
    case object Unsupported extends CapabilityStatus
    case object Unknown extends CapabilityStatus

  final case class ModelCapabilities(
      streaming: CapabilityStatus,
      tools: CapabilityStatus,
      cancellationAck: CapabilityStatus
  )

  trait ModelBackend:
    def capabilities: ModelCapabilities
    def stream(input: ModelRequest): ZStream[Scope, RaiderError, ModelEvent]

export backend.{
  Usage,
  ModelEvent,
  RequestMessage,
  ModelRequest,
  ModelCapabilities,
  CapabilityStatus,
  ModelBackend,
  ToolDefinition
}
