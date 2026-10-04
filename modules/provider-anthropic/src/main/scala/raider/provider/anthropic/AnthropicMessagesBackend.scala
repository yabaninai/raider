package raider.provider.anthropic

import raider.core.*
import raider.provider.chat.stream.SseFramer
import zio.{Scope, ZIO}
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration as JDuration

/** RAI-013 slice: Anthropic-compatible Messages wire (`/v1/messages`) as a
  * product `ModelBackend` — non-streaming AND SSE streaming (`"stream": true`),
  * native semantics, NO fake OpenAI conversion (provider-protocols §4).
  *
  * Wire rules: `x-api-key` + pinned `anthropic-version` headers; required
  * `max_tokens` from ModelRequest; loop tool-result envelopes (role "tool",
  * `{"tool_call_id","output"}`) are translated into NATIVE `tool_result`
  * content blocks (correlation preserved — never plain text). Tool DEFINITIONS
  * are not yet sent on the wire (structured message contract = later card,
  * documented obligation). Stop reasons end_turn/tool_use/ max_tokens pass
  * through honestly (max_tokens truncation is NOT a full task output — the
  * loop/caller owns that policy).
  *
  * Errors: 401/403 → ProviderAuth, 429 → ProviderRateLimit, 5xx →
  * ProviderUnavailable, other non-200 → StreamProtocol; mid-stream `error`
  * events and error-after-200 envelopes → ProviderUnavailable with the
  * anthropic error type; unknown content block/delta types fail typed (no
  * silent semantic drops). The api key never appears in details.
  *
  * Transport: same patterns as the OpenAI slice — body closed on every exit
  * (caller scope), idle deadline via race (loser's IOException swallowed), LAZY
  * end-of-stream flush, no retries, cancellationAck=Unknown.
  */
final class AnthropicMessagesBackend private (
    cfg: AnthropicMessagesBackend.Config,
    client: HttpClient,
    maxFrameBytes: Int,
    streaming: Boolean
) extends ModelBackend:

  override def capabilities: ModelCapabilities =
    ModelCapabilities(
      streaming =
        if streaming then CapabilityStatus.Supported
        else CapabilityStatus.Unsupported,
      tools = CapabilityStatus.Supported,
      cancellationAck = CapabilityStatus.Unknown
    )

  override def stream(
      input: ModelRequest
  ): ZStream[Scope, RaiderError, ModelEvent] =
    if streaming then sseStream(input) else stringStream(input)

  private def sseStream(
      input: ModelRequest
  ): ZStream[Scope, RaiderError, ModelEvent] =
    ZStream.unwrap:
      for
        body <- ZIO.fromEither(
          AnthropicMessagesBackend.encodeRequest(input, streaming = true)
        )
        req <- ZIO.succeed(
          AnthropicMessagesBackend.buildRequest(cfg, body, sse = true)
        )
        // body closed on EVERY exit path of the caller's scope (ANTH-03)
        resp <- ZIO.acquireRelease(
          ZIO
            .fromCompletionStage(
              client.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream())
            )
            .mapError(t =>
              RaiderError.ProviderUnavailable(
                s"transport failure: ${t.getClass.getSimpleName}"
              )
            )
        )(r => ZIO.succeed(r.body().close()))
      yield
        val is = resp.body()
        val framer = new SseFramer(maxFrameBytes)
        val parser = new AnthropicStreamParser

        def readBounded: ZIO[Any, RaiderError, Option[(Array[Byte], Unit)]] =
          val readChunk: ZIO[Any, RaiderError, Either[Unit, Option[
            (Array[Byte], Unit)
          ]]] =
            ZIO
              .attemptBlockingIO {
                val buf = new Array[Byte](8192)
                val n = is.read(buf) // blocks until >= 1 byte or EOF
                if n < 0 then Array.emptyByteArray
                else java.util.Arrays.copyOfRange(buf, 0, n)
              }
              .mapError(t =>
                RaiderError.ProviderUnavailable(
                  s"stream read failure: ${t.getClass.getSimpleName}"
                )
              )
              .flatMap(bs => if bs.isEmpty then ZIO.none else ZIO.some(bs))
              .map(bsOpt => Right(bsOpt.map(bs => (bs, ()))))
          readChunk
            .race(
              ZIO
                .sleep(zio.Duration.fromMillis(cfg.callTimeoutMs))
                .as(Left(()))
            )
            .flatMap:
              case Right(r) => ZIO.succeed(r)
              case Left(()) =>
                ZIO.fail(
                  RaiderError.DeadlineExceeded(
                    s"no SSE data within ${cfg.callTimeoutMs}ms (idle)"
                  )
                )

        val head: ZStream[Any, RaiderError, ModelEvent] =
          AnthropicMessagesBackend.statusCheck(resp.statusCode()) match
            case Left(err) => ZStream.fail(err)
            case Right(()) =>
              ZStream
                .unfoldZIO(())(_ => readBounded)
                .flatMap { bytes =>
                  ZStream.unwrap:
                    framer.feed(bytes) match
                      case Left(err) => ZIO.succeed(ZStream.fail(err))
                      case Right(frames) =>
                        val parsed = frames
                          .foldLeft[Either[RaiderError, Vector[ModelEvent]]](
                            Right(Vector.empty)
                          ) { (acc, frame) =>
                            acc.flatMap(evs => parser.feed(frame).map(evs ++ _))
                          }
                        ZIO.succeed(parsed match
                          case Left(err)  => ZStream.fail(err)
                          case Right(evs) => ZStream.fromIterable(evs))
                }
        // LAZY end-of-stream flush (single cumulative UsageObserved + Finished)
        head ++ ZStream.unwrap:
          ZIO
            .succeed(parser.finish())
            .flatMap:
              case Left(err)  => ZIO.succeed(ZStream.fail(err))
              case Right(evs) => ZIO.succeed(ZStream.fromIterable(evs))

  private def stringStream(
      input: ModelRequest
  ): ZStream[Scope, RaiderError, ModelEvent] =
    ZStream.unwrap:
      for
        body <- ZIO.fromEither(
          AnthropicMessagesBackend.encodeRequest(input, streaming = false)
        )
        req <- ZIO.succeed(
          AnthropicMessagesBackend.buildRequest(cfg, body, sse = false)
        )
        resp <- ZIO.acquireRelease(
          ZIO
            .fromCompletionStage(
              client.sendAsync(req, HttpResponse.BodyHandlers.ofString(UTF_8))
            )
            .mapError(t =>
              RaiderError.ProviderUnavailable(
                s"transport failure: ${t.getClass.getSimpleName}"
              )
            )
        )(_ => ZIO.unit)
      yield AnthropicMessagesBackend.statusCheck(resp.statusCode()) match
        case Left(err) => ZStream.fail(err)
        case Right(()) =>
          ZStream.unwrap:
            ZIO
              .succeed(
                AnthropicMessagesBackend.decodeResponse(
                  resp.body().asInstanceOf[String]
                )
              )
              .flatMap:
                case Left(err)  => ZIO.succeed(ZStream.fail(err))
                case Right(evs) => ZIO.succeed(ZStream.fromIterable(evs))

object AnthropicMessagesBackend:

  val PinnedApiVersion: String = "2023-06-01"

  /** Endpoint + credential binding; apiKey is write-only (redacted toString).
    */
  final case class Config(
      baseUrl: String,
      apiKey: String,
      apiVersion: String,
      callTimeoutMs: Long
  ):

    override def toString: String =
      s"Config($baseUrl, apiKey=<redacted>, apiVersion=$apiVersion, " +
        s"callTimeoutMs=$callTimeoutMs)"

  object Config:

    def make(
        baseUrl: String,
        apiKey: String,
        apiVersion: String = PinnedApiVersion,
        callTimeoutMs: Long = 30000L
    ): Either[RaiderError, Config] =
      val trimmed = baseUrl.replaceAll("/+$", "")
      if !(trimmed.startsWith("http://") || trimmed.startsWith("https://")) then
        Left(RaiderError.Configuration("baseUrl must be an http(s) URL"))
      else if apiKey.isEmpty then
        Left(RaiderError.Configuration("apiKey must be non-empty"))
      else if apiVersion.trim.isEmpty then
        Left(RaiderError.Configuration("apiVersion must be non-empty"))
      else if callTimeoutMs <= 0 then
        Left(RaiderError.Configuration("callTimeoutMs must be > 0"))
      else Right(Config(trimmed, apiKey, apiVersion, callTimeoutMs))

  def make(
      config: Either[RaiderError, Config],
      maxFrameBytes: Int = 1 << 20,
      streaming: Boolean = false
  ): ZIO[Any, RaiderError, AnthropicMessagesBackend] =
    config match
      case Left(err) => ZIO.fail(err)
      case Right(cfg) =>
        if maxFrameBytes < 16 then
          ZIO.fail(
            RaiderError.Configuration(
              s"maxFrameBytes must be >= 16, got $maxFrameBytes"
            )
          )
        else
          ZIO.succeed:
            val client = HttpClient
              .newBuilder()
              .connectTimeout(JDuration.ofMillis(cfg.callTimeoutMs))
              .build()
            new AnthropicMessagesBackend(cfg, client, maxFrameBytes, streaming)

  private[anthropic] def buildRequest(
      cfg: Config,
      body: String,
      sse: Boolean
  ): HttpRequest =
    val b = HttpRequest
      .newBuilder(URI.create(cfg.baseUrl + "/messages"))
      .timeout(JDuration.ofMillis(cfg.callTimeoutMs))
      .header("x-api-key", cfg.apiKey)
      .header("anthropic-version", cfg.apiVersion)
      .header("content-type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
    if sse then b.header("Accept", "text/event-stream").build()
    else b.build()

  /** Status → typed error; None = 200 OK. Bodies are not echoed into details
    * beyond status/type (the key never appears anywhere).
    */
  private[anthropic] def statusCheck(status: Int): Either[RaiderError, Unit] =
    status match
      case 401 | 403 =>
        Left(
          RaiderError.ProviderAuth(s"anthropic rejected auth (HTTP $status)")
        )
      case 429 =>
        Left(RaiderError.ProviderRateLimit("anthropic rate limit (HTTP 429)"))
      case s if s >= 500 =>
        Left(
          RaiderError.ProviderUnavailable(s"anthropic unavailable (HTTP $s)")
        )
      case 200 => Right(())
      case s =>
        Left(
          RaiderError.StreamProtocol(
            s"unexpected HTTP $s (anthropic messages; check anthropic-version/parameters)"
          )
        )

  // ---- request encoding ----

  private[anthropic] def encodeRequest(
      input: ModelRequest,
      streaming: Boolean
  ): Either[RaiderError, String] =
    import zio.json.EncoderOps
    val messages: Either[RaiderError, Vector[String]] =
      input.messages.foldLeft[Either[RaiderError, Vector[String]]](
        Right(Vector.empty)
      ) { (acc, m) =>
        acc.flatMap { v =>
          if m.role == "tool" then
            // loop tool-result envelope → NATIVE tool_result block (§4:
            // never plain text — correlation preserved). The tool output
            // JSON rides as a string payload inside tool_result.content
            // (full block mapping = structured message contract, documented
            // obligation).
            case class Envelope(tool_call_id: String, output: Json)
            object Envelope:
              given JsonDecoder[Envelope] = DeriveJsonDecoder.gen[Envelope]
            m.content.fromJson[Envelope] match
              case Left(err) =>
                Left(
                  RaiderError.InputValidation(
                    s"tool message envelope is not ours: ${err.take(80)}"
                  )
                )
              case Right(env) =>
                Right(
                  v :+ s"""{"role":"user","content":[{"type":"tool_result",""" +
                    s""""tool_use_id":${env.tool_call_id.toJson},""" +
                    s""""content":${env.output.toJson}}]}"""
                )
          else
            Right(
              v :+ s"""{"role":${m.role.toJson},"content":${m.content.toJson}}"""
            )
        }
      }
    messages.map { msgs =>
      s"""{"model":${input.model.toJson},""" +
        s""""max_tokens":${input.maxOutputTokens},""" +
        s""""messages":${msgs.mkString("[", ",", "]")},""" +
        s""""stream":$streaming}"""
    }

  // ---- non-streaming response decoding ----

  private final case class ContentBlockWire(
      `type`: String,
      text: Option[String],
      id: Option[String],
      name: Option[String],
      input: Option[Json]
  )

  private object ContentBlockWire:

    given JsonDecoder[ContentBlockWire] =
      DeriveJsonDecoder.gen[ContentBlockWire]

  private final case class UsageWire(input_tokens: Long, output_tokens: Long)

  private object UsageWire:
    given JsonDecoder[UsageWire] = DeriveJsonDecoder.gen[UsageWire]

  private final case class ResponseWire(
      id: String,
      content: List[ContentBlockWire],
      stop_reason: Option[String],
      usage: Option[UsageWire]
  )

  private object ResponseWire:
    given JsonDecoder[ResponseWire] = DeriveJsonDecoder.gen[ResponseWire]

  private[anthropic] def decodeResponse(
      body: String
  ): Either[RaiderError, Vector[ModelEvent]] =
    // error-after-200: an anthropic error envelope instead of a message
    body.fromJson[AnthropicStreamParser.ErrorProbe] match
      case Right(probe) if probe.isError =>
        Left(
          RaiderError.ProviderUnavailable(
            s"anthropic error after 200 (${probe.typeName}): ${probe.errorMessage}"
          )
        )
      case _ =>
        body.fromJson[ResponseWire] match
          case Left(err) =>
            Left(
              RaiderError.StreamProtocol(
                s"unparsable anthropic body: ${err.take(140)}"
              )
            )
          case Right(r) =>
            val attempt = AttemptId(r.id)
            val evs = Vector.newBuilder[ModelEvent]
            evs += ModelEvent.Started(attempt)
            var blockError: Option[RaiderError] = None
            r.content.foreach { cb =>
              if blockError.isEmpty then
                cb.`type` match
                  case "text" =>
                    cb.text.foreach(t =>
                      if t.nonEmpty then evs += ModelEvent.TextDelta(attempt, t)
                    )
                  case "tool_use" =>
                    evs += ModelEvent.ToolCallReady(
                      attempt,
                      ToolCallId(cb.id.getOrElse("tool_use_?")),
                      cb.name.getOrElse(""),
                      cb.input.map(_.toJson).getOrElse("{}")
                    )
                  case other =>
                    blockError = Some(
                      RaiderError.StreamProtocol(
                        s"unknown content block type '$other'"
                      )
                    )
            }
            blockError match
              case Some(err) => Left(err)
              case None =>
                r.usage.foreach(u =>
                  evs += ModelEvent.UsageObserved(
                    attempt,
                    Usage(u.input_tokens, u.output_tokens, None),
                    priceKnown = false
                  )
                )
                evs += ModelEvent.Finished(
                  attempt,
                  r.stop_reason.getOrElse("end_turn")
                )
                Right(evs.result())

end AnthropicMessagesBackend
