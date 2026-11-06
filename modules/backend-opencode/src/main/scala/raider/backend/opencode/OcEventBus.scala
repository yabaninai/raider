package raider.backend.opencode

import zio.Clock
import zio.Queue
import zio.Ref
import zio.ZIO
import zio.Scope
import zio.json.JsonDecoder
import zio.stream.ZStream

/** Shared bus over the OpenCode server-wide global event feed
 * (GET /global/event).
 *
 * Reconnect discipline (documented and enforced here):
 *   - reconnection re-subscribes READ-ONLY: only GET /global/event is
 *     reopened. Mutating calls (prompt_async, abort) are NEVER replayed by
 *     the bus or by waiters, and history is never re-served: events appended
 *     while a connection was down are a permanent gap for new connections.
 *   - reconnect attempts are bounded (`OcConfig.reconnectAttempts`); on
 *     exhaustion the bus enters a terminal state and every subscriber stream
 *     fails typed, so waiters can reconcile read-only (GET endpoints) instead
 *     of guessing.
 *   - events are fanned out to per-subscriber queues; a subscriber only sees
 *     events matching its predicate, so concurrent sessions cannot cross-read
 *     each other's events through a session-scoped subscription.
 */
trait OcEventBus:
  /** All global events observed on the shared feed (unfiltered). */
  def stream: ZStream[Any, OcError, OcGlobalEvent]

  /** Events matching the predicate, delivered from the same shared feed.
   */
  def filtered(predicate: OcGlobalEvent => Boolean): ZStream[Any, OcError, OcGlobalEvent]

  /** Synchronously registers a subscription and returns its queue. When this
   * effect completes, the subscriber is already visible to the reader fiber,
   * so a caller that submits a mutation right afterwards cannot miss the run
   * evidence the server emits while processing it. The subscription lives in
   * the caller's scope.
   */
  def subscribe(
      predicate: OcGlobalEvent => Boolean,
  ): ZIO[Scope, Nothing, zio.Dequeue[Either[OcError, OcGlobalEvent]]]

object OcEventBus:

  private final case class OcSubscriber(
      predicate: OcGlobalEvent => Boolean,
      queue: Queue[Either[OcError, OcGlobalEvent]],
  )

  private sealed trait BusState
  private object BusState:
    case object Open extends BusState
    final case class Terminal(error: OcError) extends BusState
    case object Closed extends BusState

  /** Builds the bus and starts the single shared reader fiber in the caller's
   * scope. Never fails on connect errors: stream failures surface on the
   * subscriber streams so waiters can reconcile.
   */
  def live(cfg: OcConfig, http: OcHttp): ZIO[Scope & Clock, Nothing, OcEventBus] =
    for
      subscribers <- Ref.make(Vector.empty[OcSubscriber])
      state <- Ref.make[BusState](BusState.Open)
      bus = new OcEventBusLive(cfg, http, subscribers, state)
      _ <- ZIO.addFinalizer(state.set(BusState.Closed))
      _ <- bus.start()
    yield bus

  private final class OcEventBusLive(
      cfg: OcConfig,
      http: OcHttp,
      subscribers: Ref[Vector[OcSubscriber]],
      state: Ref[BusState],
    ) extends OcEventBus:

    /** Starts the single shared reader fiber in the caller's scope. */
    def start(): ZIO[Scope & Clock, Nothing, Unit] = readerLoop.forkScoped.unit

    def stream: ZStream[Any, OcError, OcGlobalEvent] = filtered(_ => true)

    def subscribe(
        predicate: OcGlobalEvent => Boolean,
    ): ZIO[Scope, Nothing, zio.Dequeue[Either[OcError, OcGlobalEvent]]] =
      for
        queue <- Queue.unbounded[Either[OcError, OcGlobalEvent]]
        sub = OcSubscriber(predicate, queue)
        _ <- subscribers.update(_ :+ sub)
        _ <- state.get.flatMap {
          case BusState.Terminal(error) => queue.offer(Left(error))
          case BusState.Closed =>
            queue.offer(Left(OcError.Cancelled("event bus already closed")))
          case BusState.Open => ZIO.unit
        }
        _ <- ZIO.addFinalizer(subscribers.update(_.filterNot(_ eq sub)) *> queue.shutdown)
      yield queue

    def filtered(predicate: OcGlobalEvent => Boolean): ZStream[Any, OcError, OcGlobalEvent] =
      ZStream
        .unwrapScoped[Any](subscribe(predicate).map(q => ZStream.fromQueue(q)))
        .flatMap {
          case Right(event) => ZStream.succeed(event)
          case Left(error)  => ZStream.fail(error)
        }

    private def backoffMillis(attempt: Int): Long =
      math.min(100L * (1L << math.min(attempt - 1, 4)), 2000L)

    private def readerLoop: ZIO[Clock, Nothing, Unit] =
      runAttempt(0)

    private def runAttempt(attempt: Int): ZIO[Clock, Nothing, Unit] =
      ZIO
        .scoped[Clock]:
          for
            stream <- openConnection
            _ <- consume(stream)
          yield ()
        .foldZIO(
          error => next(error, attempt + 1),
          _ => next(OcError.ProviderUnavailable("event stream ended by server"), attempt + 1),
        )

    private def next(error: OcError, attempt: Int): ZIO[Clock, Nothing, Unit] =
      state.get.flatMap {
        case BusState.Open if attempt <= cfg.reconnectAttempts =>
          ZIO.sleep(zio.Duration.fromMillis(backoffMillis(attempt))) *> runAttempt(attempt)
        case BusState.Open =>
          state.set(BusState.Terminal(error)) *> publishTerminal(error)
        case _ => ZIO.unit
      }

    private def publishTerminal(error: OcError): ZIO[Any, Nothing, Unit] =
      subscribers.get.flatMap { subs =>
        ZIO.foreachDiscard(subs)(sub => sub.queue.offer(Left(error)).unit)
      }

    private def openConnection: ZIO[Scope, OcError, OcHttp#OcEventStream] =
      for
        stream <- http.openEventStream()
        _ <-
          if stream.contentType.contains("text/event-stream") then ZIO.unit
          else
            ZIO
              .attempt(stream.body.close())
              .ignore
              .flatMap(_ =>
                ZIO.fail(
                  OcError.StreamProtocol(
                    s"event endpoint returned content-type '${stream.contentType}', expected text/event-stream"
                  )
                )
              )
      yield stream

    private def consume(stream: OcHttp#OcEventStream): ZIO[Clock, OcError, Unit] =
      val framer = new OcSseFramer(cfg.maxEventFrameBytes)
      val body = stream.body
      val read: ZIO[Any, OcError, Option[Array[Byte]]] =
        ZIO
          .attemptBlockingCancelable(
            {
              val buffer = new Array[Byte](8192)
              val n = body.read(buffer)
              if n < 0 then None else Some(java.util.Arrays.copyOf(buffer, n))
            },
          )(ZIO.attempt(body.close()).ignore)
          .mapError(_ => OcError.ProviderUnavailable("event stream read failed"))
          .timeoutFail(
            OcError.ProviderUnavailable(s"no SSE frames within ${cfg.eventIdleTimeoutMs} ms")
          )(zio.Duration.fromMillis(cfg.eventIdleTimeoutMs))

      def step: ZIO[Any, OcError, Unit] =
        read.flatMap {
          case None =>
            ZIO.fail(OcError.ProviderUnavailable("event stream ended by server"))
          case Some(bytes) =>
            val frames =
              try Right(framer.push(bytes))
              catch case e: OcSse.Overflow => Left(e.limit)
            frames match
              case Left(limit) =>
                ZIO.fail(OcError.StreamProtocol(s"SSE frame exceeded $limit bytes"))
              case Right(parsed) =>
                ZIO.foreachDiscard(parsed) { frame =>
                  OcSse.dataPayload(frame) match
                    case None => ZIO.unit
                    case Some(data) =>
                      summon[JsonDecoder[OcGlobalEvent]].decodeJson(data) match
                        case Left(err) =>
                          ZIO.fail(OcError.StreamProtocol("undecodable event frame: " + shortErr(err)))
                        case Right(event) => publish(event)
                }
        }

      ZIO.iterate(())(_ => true)(_ => step)

    private def shortErr(s: String, max: Int = 200): String =
      if s.length <= max then s else s.take(max) + "..."

    private def publish(event: OcGlobalEvent): ZIO[Any, Nothing, Unit] =
      subscribers.get.flatMap { subs =>
        ZIO.foreachDiscard(subs.filter(_.predicate(event)))(sub => sub.queue.offer(Right(event)).unit)
      }
