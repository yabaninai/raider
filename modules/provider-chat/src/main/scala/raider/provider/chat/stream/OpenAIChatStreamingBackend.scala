package raider.provider.chat.stream

import raider.core.*
import raider.provider.chat.OpenAIChatBackend
import zio.{Scope, ZIO}
import zio.json.EncoderOps
import zio.stream.ZStream

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration as JDuration

/** RAI-012 slice: OpenAI-compatible Chat STREAMING wire (`"stream":true`,
  * `stream_options.include_usage`) as a product `ModelBackend` — SSE framing
  * (SseFramer) + semantic chunk parser (ChatChunkParser), deliberately separate
  * layers.
  *
  * Transport rules (mirror the non-streaming slice): Bearer auth from Config,
  * 401/403 → ProviderAuth, 429 → ProviderRateLimit, 5xx → ProviderUnavailable,
  * other non-200 → StreamProtocol. The response body is closed on EVERY exit
  * (acquire-release on the response in the CALLER's scope — the ModelBackend
  * contract is `ZStream[Scope, …]`, so the scope outlives consumption),
  * including interrupt: interrupting the consumer closes the body and cancels
  * the in-flight request. TCP close is NOT proof upstream stopped metering —
  * money stays the loop's Uncertain accounting. No retries, ever (AGENTS
  * boundaries).
  *
  * Two ZIO hazards handled explicitly (found by fixtures, 2026-10-04):
  *   - the end-of-stream flush must run AFTER the chunks: it is built inside
  *     `ZStream.unwrap`, never at stream-construction time;
  *   - the blocking read must not race its own interruption (that surfaces as a
  *     raw IOException): the read parks in a daemon fiber and the PROMISE await
  *     is what carries the typed DeadlineExceeded idle deadline.
  *
  * Idle timeout: `callTimeoutMs` bounds the wait BETWEEN chunks (and the
  * connect phase) — silence beyond it is a typed DeadlineExceeded, not a hang.
  * Bounded buffering: frames longer than `maxFrameBytes` fail typed
  * StreamProtocol (SSE-02/03).
  */
final class OpenAIChatStreamingBackend private (
    cfg: OpenAIChatBackend.Config,
    client: HttpClient,
    maxFrameBytes: Int
) extends ModelBackend:

  override def capabilities: ModelCapabilities =
    ModelCapabilities(
      streaming = CapabilityStatus.Supported,
      tools = CapabilityStatus.Supported,
      cancellationAck = CapabilityStatus.Unknown // close ≠ server-side ack
    )

  override def stream(
      input: ModelRequest
  ): ZStream[Scope, RaiderError, ModelEvent] =
    ZStream.unwrap:
      for
        body <- ZIO.fromEither(
          OpenAIChatStreamingBackend.encodeStreamingRequest(input)
        )
        req = HttpRequest
          .newBuilder(URI.create(cfg.baseUrl + "/chat/completions"))
          .timeout(JDuration.ofMillis(cfg.callTimeoutMs))
          .header("Authorization", s"Bearer ${cfg.apiKey}")
          .header("Content-Type", "application/json")
          .header("Accept", "text/event-stream")
          .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
          .build()
        // body closed on EVERY exit path of the caller's scope (SSE-03)
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
        val parser = new ChatChunkParser

        def readBounded: ZIO[Any, RaiderError, Option[(Array[Byte], Unit)]] =
          // one chunk read, bounded by the idle deadline: `race` makes the
          // deadline decisive — when the timer wins, the parked blocking read
          // is interrupted and its IOException is swallowed as the loser
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
          resp.statusCode() match
            case 401 | 403 =>
              ZStream.fail(
                RaiderError.ProviderAuth(
                  s"provider rejected auth (HTTP ${resp.statusCode()})"
                )
              )
            case 429 =>
              ZStream.fail(
                RaiderError.ProviderRateLimit("provider rate limit (HTTP 429)")
              )
            case s if s >= 500 =>
              ZStream.fail(
                RaiderError.ProviderUnavailable(
                  s"provider unavailable (HTTP $s)"
                )
              )
            case 200 =>
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
            case s =>
              ZStream.fail(RaiderError.StreamProtocol(s"unexpected HTTP $s"))
        // end-of-stream flush: ToolCallReady + Finished — LAZY via unwrap:
        // parser.finish() must run AFTER the chunks, never at construction
        head ++ ZStream.unwrap:
          ZIO
            .succeed(parser.finish())
            .flatMap:
              case Left(err)  => ZIO.succeed(ZStream.fail(err))
              case Right(evs) => ZIO.succeed(ZStream.fromIterable(evs))

object OpenAIChatStreamingBackend:

  /** Build with a fresh JDK client. `maxFrameBytes` bounds a single SSE frame.
    */
  def make(
      config: Either[RaiderError, OpenAIChatBackend.Config],
      maxFrameBytes: Int = 1 << 20
  ): ZIO[Any, RaiderError, OpenAIChatStreamingBackend] =
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
            new OpenAIChatStreamingBackend(cfg, client, maxFrameBytes)

  private def encodeStreamingRequest(
      input: ModelRequest
  ): Either[RaiderError, String] =
    val msgs = input.messages
      .map { m =>
        s"""{"role":${m.role.toJson},"content":${m.content.toJson}}"""
      }
      .mkString("[", ",", "]")
    Right:
      s"""{"model":${input.model.toJson},"messages":$msgs,""" +
        s""""max_tokens":${input.maxOutputTokens},"stream":true,""" +
        """"stream_options":{"include_usage":true}}"""

end OpenAIChatStreamingBackend
