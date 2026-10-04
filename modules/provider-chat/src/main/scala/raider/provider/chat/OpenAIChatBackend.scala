package raider.provider.chat

import raider.core.*
import zio.{Scope, Task, ZIO}
import zio.json.*
import zio.stream.ZStream

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration as JDuration

/** RAI-011 feasibility slice: OpenAI-compatible Chat Completions over the JDK
  * HttpClient as a product `ModelBackend` — non-streaming ONLY (SSE framing is
  * RAI-012, deliberately absent here; this is a transport slice, not a full
  * provider card).
  *
  * Wire rules (provider-protocols §2): base URL kept verbatim (no /models
  * probing), auth via Bearer from the constructed Config — the key is NEVER
  * logged or echoed into error details. Status mapping: 401/403 → ProviderAuth,
  * 429 → ProviderRateLimit, 5xx → ProviderUnavailable, other non-200 or
  * unparsable body → StreamProtocol. Token usage is recorded truthfully
  * (priceKnown=false — no price table at this layer, never free).
  *
  * Cancellation: interrupting the stream cancels the in-flight request
  * (fromCompletionStage cancels the CompletableFuture). TCP close is NOT proof
  * that upstream stopped metering — the loop's Uncertain accounting governs
  * money, this class adds no retries (AGENTS: no auto-retry of ambiguous
  * attempts).
  */
final class OpenAIChatBackend private (
    cfg: OpenAIChatBackend.Config,
    client: HttpClient
) extends ModelBackend:

  override def capabilities: ModelCapabilities =
    ModelCapabilities(
      streaming = CapabilityStatus.Unsupported, // honest: non-streaming slice
      tools = CapabilityStatus.Supported,
      cancellationAck = CapabilityStatus.Unknown // CF cancel ≠ server ack
    )

  override def stream(
      input: ModelRequest
  ): ZStream[Scope, RaiderError, ModelEvent] =
    ZStream.unwrap:
      for
        body <- ZIO.fromEither(Wire.encodeRequest(input))
        req = HttpRequest
          .newBuilder(URI.create(cfg.baseUrl + "/chat/completions"))
          .timeout(JDuration.ofMillis(cfg.callTimeoutMs))
          .header("Authorization", s"Bearer ${cfg.apiKey}")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
          .build()
        resp <- ZIO
          .fromCompletionStage(
            client.sendAsync(req, HttpResponse.BodyHandlers.ofString(UTF_8))
          )
          .mapError: t =>
            RaiderError.ProviderUnavailable(
              s"transport failure: ${t.getClass.getSimpleName}"
            )
        events <- resp.statusCode() match
          case 401 | 403 =>
            ZIO.fail(
              RaiderError.ProviderAuth(
                s"provider rejected auth (HTTP ${resp.statusCode()})"
              )
            )
          case 429 =>
            ZIO.fail(
              RaiderError.ProviderRateLimit("provider rate limit (HTTP 429)")
            )
          case s if s >= 500 =>
            ZIO.fail(
              RaiderError.ProviderUnavailable(s"provider unavailable (HTTP $s)")
            )
          case 200 =>
            ZIO
              .fromEither(Wire.decodeResponse(resp.body()))
              .flatMap(es => ZIO.succeed(ZStream.fromIterable(es)))
          case s => ZIO.fail(RaiderError.StreamProtocol(s"unexpected HTTP $s"))
      yield events

object OpenAIChatBackend:

  /** Endpoint + credential binding. `apiKey` is write-only: toString redacts.
    */
  final case class Config(baseUrl: String, apiKey: String, callTimeoutMs: Long):

    override def toString: String =
      s"Config($baseUrl, apiKey=<redacted>, callTimeoutMs=$callTimeoutMs)"

  object Config:

    def make(
        baseUrl: String,
        apiKey: String,
        callTimeoutMs: Long = 30000L
    ): Either[RaiderError, Config] =
      val trimmed = baseUrl.replaceAll("/+$", "")
      if !(trimmed.startsWith("http://") || trimmed.startsWith("https://")) then
        Left(RaiderError.Configuration("baseUrl must be an http(s) URL"))
      else if apiKey.isEmpty then
        Left(RaiderError.Configuration("apiKey must be non-empty"))
      else if callTimeoutMs <= 0 then
        Left(RaiderError.Configuration("callTimeoutMs must be > 0"))
      else Right(Config(trimmed, apiKey, callTimeoutMs))

  /** Build with a fresh JDK client (connect timeout mirrors the call timeout).
    */
  def make(config: Either[RaiderError, Config]): Task[OpenAIChatBackend] =
    config match
      case Left(err) => ZIO.fail(err)
      case Right(cfg) =>
        ZIO.succeed:
          val client = HttpClient
            .newBuilder()
            .connectTimeout(JDuration.ofMillis(cfg.callTimeoutMs))
            .build()
          new OpenAIChatBackend(cfg, client)

/** Wire codecs for the non-streaming Chat Completions slice. Explicit shapes
  * (no derivation for the wire): unknown provider fields are ignored, missing
  * mandatory fields are typed StreamProtocol failures, never guessed.
  */
private[chat] object Wire:

  def encodeRequest(input: ModelRequest): Either[RaiderError, String] =
    val msgs = input.messages
      .map { m =>
        s"""{"role":${m.role.toJson},"content":${m.content.toJson}}"""
      }
      .mkString("[", ",", "]")
    val toolsJson = input.tools match
      case Nil => ""
      case defs =>
        defs
          .map { d =>
            s"""{"type":"function","function":{"name":${d.name.toJson},""" +
              s""""description":${d.description.toJson},""" +
              s""""parameters":${d.parametersJsonSchema}}}"""
          }
          .mkString(",\"tools\":[", ",", "]")
    Right:
      s"""{"model":${input.model.toJson},"messages":$msgs,""" +
        s""""max_tokens":${input.maxOutputTokens}$toolsJson}"""

  // ---- response wire ----
  private final case class FunctionWire(name: String, arguments: String)

  private object FunctionWire:
    given JsonDecoder[FunctionWire] = DeriveJsonDecoder.gen[FunctionWire]

  private final case class ToolCallWire(id: String, function: FunctionWire)

  private object ToolCallWire:
    given JsonDecoder[ToolCallWire] = DeriveJsonDecoder.gen[ToolCallWire]

  private final case class MessageWire(
      role: String,
      content: Option[String],
      tool_calls: Option[List[ToolCallWire]]
  )

  private object MessageWire:
    given JsonDecoder[MessageWire] = DeriveJsonDecoder.gen[MessageWire]

  private final case class ChoiceWire(
      index: Int,
      message: MessageWire,
      finish_reason: Option[String]
  )

  private object ChoiceWire:
    given JsonDecoder[ChoiceWire] = DeriveJsonDecoder.gen[ChoiceWire]

  private final case class UsageWire(
      prompt_tokens: Long,
      completion_tokens: Long
  )

  private object UsageWire:
    given JsonDecoder[UsageWire] = DeriveJsonDecoder.gen[UsageWire]

  private final case class ResponseWire(
      id: String,
      choices: List[ChoiceWire],
      usage: Option[UsageWire]
  )

  private object ResponseWire:
    given JsonDecoder[ResponseWire] = DeriveJsonDecoder.gen[ResponseWire]

  /** Normalize the 200 body into ordered ModelEvents. */
  def decodeResponse(body: String): Either[RaiderError, Vector[ModelEvent]] =
    body.fromJson[ResponseWire] match
      case Left(err) =>
        Left(
          RaiderError.StreamProtocol(
            s"unparsable provider body: ${err.take(160)}"
          )
        )
      case Right(r) =>
        if r.choices.isEmpty then
          Left(RaiderError.StreamProtocol("provider response has no choices"))
        else
          val attempt = AttemptId(r.id)
          val events = Vector.newBuilder[ModelEvent]
          events += ModelEvent.Started(attempt)
          r.choices.sortBy(_.index).foreach { c =>
            c.message.content.foreach { t =>
              if t.nonEmpty then events += ModelEvent.TextDelta(attempt, t)
            }
            c.message.tool_calls.getOrElse(Nil).foreach { tc =>
              events += ModelEvent.ToolCallReady(
                attempt,
                ToolCallId(tc.id),
                tc.function.name,
                tc.function.arguments
              )
            }
          }
          r.usage.foreach { u =>
            events += ModelEvent.UsageObserved(
              attempt,
              Usage(u.prompt_tokens, u.completion_tokens, None),
              priceKnown = false
            )
          }
          events += ModelEvent.Finished(
            attempt,
            r.choices.flatMap(_.finish_reason).headOption.getOrElse("stop")
          )
          Right(events.result())

end Wire
