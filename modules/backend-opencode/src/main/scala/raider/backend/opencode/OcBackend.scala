package raider.backend.opencode

import java.util.UUID

import zio.Clock
import zio.Dequeue
import zio.IO
import zio.Queue
import zio.Ref
import zio.ZIO
import zio.Scope
import zio.json.JsonEncoder
import zio.json.JsonDecoder
import zio.stream.ZStream

/** Visible server identity after a successful health gate. `apiFamily >= 1`
 * (the pinned v1 surface) is required to attach; an unparseable version
 * string blocks launch (unknown required guarantee never passes silently).
 */
final case class OcServerInfo(version: String, apiFamily: Int, healthy: Boolean):
  def supportsPinnedSurface: Boolean = apiFamily >= OpenCodeBackend.RequiredApiFamily

/** Client-side confirmation that prompt_async was accepted (HTTP 204).
 * `messageID` is the client-chosen identifier sent in the body; no
 * exactly-once claim is attached to it.
 */
final case class OcPromptAccepted(sessionId: String, messageId: String)

final case class OcAbortAck(sessionId: String, acked: Boolean)

/** Terminal outcome of a settled prompt run.
 */
sealed trait OcRunOutcome:
  def sessionId: String
  def messageId: String
object OcRunOutcome:
  final case class Completed(
      sessionId: String,
      messageId: String,
      assistantMessageId: Option[String],
      text: String,
      tools: Vector[OcPart.Tool],
      cost: Option[Double],
      usage: Option[OcTokens],
      reconciled: Boolean,
  ) extends OcRunOutcome
  final case class Aborted(
      sessionId: String,
      messageId: String,
      serverError: OcMessageError,
      tools: Vector[OcPart.Tool],
      ackRequested: Boolean,
      reconciled: Boolean,
  ) extends OcRunOutcome
  final case class Failed(
      sessionId: String,
      messageId: String,
      serverError: OcMessageError,
      tools: Vector[OcPart.Tool],
      reconciled: Boolean,
  ) extends OcRunOutcome
  /** Honest outcome when the run could not be settled: the mutation may still
   * be running server-side. Never produced by an automatic retry.
   */
  final case class Uncertain(
      sessionId: String,
      messageId: String,
      reason: String,
  ) extends OcRunOutcome

/** Settled-completion state machine (settle = terminal event evidence, not
 * the first stream event). Completion requires an assistant message for this
 * run (parentID == messageId) with `time.completed` set AND a `session.idle`
 * for the session. A `session.error` settles the run immediately (Aborted for
 * MessageAbortedError, Failed otherwise).
 */
private[opencode] final case class OcSettleState(
    sessionId: String,
    messageId: String,
    assistant: Option[OcAssistantMessage],
    assistantCompleted: Boolean,
    serverError: Option[OcMessageError],
    abortSeen: Boolean,
    idleSeen: Boolean,
    texts: Map[String, String],
    textOrder: Vector[String],
    tools: Map[String, OcPart.Tool],
):
  def settled: Boolean = serverError.isDefined || (assistantCompleted && idleSeen)

  def step(ev: OcGlobalEvent): OcSettleState =
    ev.payload match
      case OcBusEvent.SessionIdle(id) if id == sessionId =>
        if assistantCompleted || serverError.isDefined then copy(idleSeen = true) else this
      case OcBusEvent.SessionError(Some(id), Some(err)) if id == sessionId =>
        copy(serverError = Some(err), abortSeen = abortSeen || err.name == "MessageAbortedError")
      case OcBusEvent.MessageUpdated(OcMessage.Assistant(info))
          if info.sessionID == sessionId && info.parentID.contains(messageId) =>
        val err = info.error
        copy(
          assistant = Some(info),
          assistantCompleted = assistantCompleted || info.time.completed.isDefined,
          serverError = err.orElse(serverError),
          abortSeen = abortSeen || err.exists(_.name == "MessageAbortedError"),
        )
      case OcBusEvent.MessagePartUpdated(OcPart.Text(pid, sid, mid, text), delta)
          if sid == sessionId && mid != messageId =>
        // Parts of the client-submitted user message carry messageID ==
        // messageId (the prompt body id); they are echo of the request, not
        // run evidence, and must not enter the joined answer text. Delta
        // events append the incremental chunk; a part update without a
        // delta carries the authoritative accumulated text.
        val current = texts.getOrElse(pid, "")
        val next = delta match
          case Some(d) => current + d
          case None    => text.getOrElse(current)
        copy(
          texts = texts.updated(pid, next),
          textOrder = if textOrder.contains(pid) then textOrder else textOrder :+ pid,
        )
      case OcBusEvent.MessagePartUpdated(tool: OcPart.Tool, _)
          if tool.sessionID == sessionId && tool.messageID != messageId =>
        copy(tools = tools.updated(tool.id, tool))
      case _ => this

  /** Folds a message-list entry (read-only reconciliation). */
  def observe(entry: OcMessageWithParts): OcSettleState =
    val withInfo = entry.info match
      case OcMessage.Assistant(info)
          if info.sessionID == sessionId && info.parentID.contains(messageId) =>
        val err = info.error
        copy(
          assistant = Some(info),
          assistantCompleted = assistantCompleted || info.time.completed.isDefined,
          serverError = err.orElse(serverError),
          abortSeen = abortSeen || err.exists(_.name == "MessageAbortedError"),
        )
      case _ => this
    entry.parts.foldLeft(withInfo)((s, p) => s.step(OcGlobalEvent(None, OcBusEvent.MessagePartUpdated(p, None))))

  def textJoined: String = textOrder.flatMap(texts.get).mkString

  def finalTools: Vector[OcPart.Tool] =
    tools.toVector.sortBy(_._1).map(_._2)

object OcSettleState:
  def initial(sessionId: String, messageId: String): OcSettleState =
    OcSettleState(sessionId, messageId, None, false, None, false, false, Map.empty, Vector.empty, Map.empty)

/** Attached backend view over one OpenCode server: sessions, prompts, aborts,
 * and session-scoped views over the shared global event feed.
 */
final case class ConnectedBackend(
    cfg: OcConfig,
    http: OcHttp,
    bus: OcEventBus,
    info: OcServerInfo,
    abortFlags: Ref[Set[String]],
):

  /** Unfiltered view of the shared global feed. */
  def events: ZStream[Any, OcError, OcGlobalEvent] = bus.stream

  /** Session-scoped event view. The predicate filters on the session id AND
   * the workspace directory when configured, so concurrent sessions in other
   * directories cannot cross-read events. The subscription registers when
   * the stream starts; use [[subscribeSessionQueue]] when registration must
   * complete before a mutating call is sent.
   */
  def subscribeSession(sessionId: String): ZStream[Any, OcError, OcBusEvent] =
    bus.filtered(sessionPredicate(sessionId)).map(_.payload)

  /** Synchronous session-scoped subscription: when the effect completes, the
   * subscriber is already registered on the shared bus, so run evidence
   * emitted while the server processes a subsequent prompt_async cannot be
   * missed. Lives in the caller's scope.
   */
  def subscribeSessionQueue(
      sessionId: String,
  ): ZIO[Scope, Nothing, zio.Dequeue[Either[OcError, OcGlobalEvent]]] =
    bus.subscribe(sessionPredicate(sessionId))

  private def sessionPredicate(sessionId: String): OcGlobalEvent => Boolean = ev =>
    val sessionMatches = ev.payload.sessionID.contains(sessionId)
    val dirMatches = cfg.directory.forall(d => ev.directory.contains(d))
    sessionMatches && dirMatches

  def createSession(title: Option[String] = None, parentID: Option[String] = None): IO[OcError, OcSession] =
    http
      .post("/session", json = Some(summon[JsonEncoder[OcCreateSessionBody]].encodeJson(OcCreateSessionBody(parentID, title), None).toString))
      .flatMap(r => http.decodeBody[OcSession](r))

  def session(id: String): IO[OcError, OcSession] =
    http.get("/session/" + OcConfig.urlEncode(id)).flatMap(r => http.decodeBody[OcSession](r))

  /** Map of sessionID -> status type ("idle" | "retry" | "busy"). */
  def sessionStatus(): IO[OcError, Map[String, String]] =
    http.get("/session/status").flatMap(r => http.decodeBody[Map[String, OcSessionStatusInfo]](r)).map {
      m => m.view.mapValues(_.status).toMap
    }

  /** Read-only message list for a session (also the reconciliation source). */
  def messages(id: String): IO[OcError, Vector[OcMessageWithParts]] =
    http
      .get("/session/" + OcConfig.urlEncode(id) + "/message")
      .flatMap(r => http.decodeBody[List[OcMessageWithParts]](r))
      .map(_.toVector)

  /** Fire-and-forget prompt submission (POST prompt_async, HTTP 204).
   * Timeouts/failures after send surface as ToolOutcomeUncertain and are
   * never retried by the adapter.
   */
  def promptAsync(
      sessionId: String,
      text: String,
      model: Option[OcModelRef] = None,
      agent: Option[String] = None,
  ): IO[OcError, OcPromptAccepted] =
    if text.trim.isEmpty then
      ZIO.fail(OcError.InputValidation("prompt text must not be empty"))
    else
      val messageId = "msg-" + UUID.randomUUID().toString
      val body = OcPromptBody(model, agent, List(OcTextPart(text)), Some(messageId), None, None, None)
      http
        .post(
          "/session/" + OcConfig.urlEncode(sessionId) + "/prompt_async",
          json = Some(summon[JsonEncoder[OcPromptBody]].encodeJson(body, None).toString),
          mutation = true,
        )
        .as(OcPromptAccepted(sessionId, messageId))

  /** Requests an abort for a running session. `acked=true` is the server's
   * acknowledgement that an abort was requested (the outcome itself still
   * arrives as events: MessageAbortedError + session.idle). `acked=false`
   * means nothing was running. Definitive transport failures clear the
   * local abort flag; ambiguous outcomes keep it (the server may have
   * processed the abort).
   */
  def abort(sessionId: String): IO[OcError, OcAbortAck] =
    abortFlags.update(_ + sessionId) *>
      http
        .post("/session/" + OcConfig.urlEncode(sessionId) + "/abort", mutation = true)
        .foldZIO(
          err =>
            err match
              case uncertain: OcError.ToolOutcomeUncertain => ZIO.fail(uncertain)
              case _ => abortFlags.update(_ - sessionId) *> ZIO.fail(err)
          ,
          r =>
            val body = r.body.trim
            if body == "true" then ZIO.succeed(OcAbortAck(sessionId, acked = true))
            else if body == "false" then
              // Definitive answer: nothing is running, so the pending abort
              // flag is consumed even though the server never saw a run.
              abortFlags.update(_ - sessionId).as(OcAbortAck(sessionId, acked = false))
            else ZIO.fail(OcError.OutputValidation("abort response is not a JSON boolean: " + r.body.take(64)))
        )

  /** Settled prompt run: submits via prompt_async, then waits for the pinned
   * settled-completion evidence. Settles as Completed (assistant completed +
   * session.idle), Aborted (MessageAbortedError), Failed (other session
   * error), or — after the settle deadline or an event-stream failure —
   * reconciles read-only (GET /session/{id}/message) and only then reports
   * Uncertain if the run still cannot be settled. Mutations are never
   * replayed.
   *
   * The session-scoped subscription is opened BEFORE prompt_async is sent:
   * run evidence emitted while the server processes the submission is
   * buffered in a queue, so settle evidence cannot be missed.
   */
  def prompt(
      sessionId: String,
      text: String,
      model: Option[OcModelRef] = None,
      agent: Option[String] = None,
  ): ZIO[Scope & Clock, OcError, OcRunOutcome] =
    ZIO.scoped[Scope & Clock]:
      for
        // Registered synchronously: by the time prompt_async is sent, the
        // subscriber is already visible to the reader fiber, so the run
        // evidence the server emits while processing the submission is
        // buffered for this run.
        q <- subscribeSessionQueue(sessionId)
        accepted <- promptAsync(sessionId, text, model, agent)
        outcome <- awaitSettleFromQueue(q, sessionId, accepted.messageId)
      yield outcome

  private def ackRequested(sessionId: String): ZIO[Any, Nothing, Boolean] =
    abortFlags.get.map(_.contains(sessionId))

  private def clearAbortFlagIfConsumed(sessionId: String, state: OcSettleState): ZIO[Any, Nothing, Unit] =
    if state.abortSeen then abortFlags.update(_ - sessionId) else ZIO.unit

  private def awaitSettleFromQueue(
      q: Dequeue[Either[OcError, OcGlobalEvent]],
      sessionId: String,
      messageId: String,
  ): ZIO[Scope & Clock, OcError, OcRunOutcome] =
    ZStream
      .fromQueue(q)
      .flatMap {
        case Right(ev)   => ZStream.succeed(ev)
        case Left(error) => ZStream.fail(error)
      }
      .runFoldWhile(OcSettleState.initial(sessionId, messageId))(!_.settled)(_.step(_))
      .timeout(zio.Duration.fromMillis(cfg.settleDeadlineMs))
      .flatMap {
        case Some(state) =>
          for
            ack <- ackRequested(sessionId)
            _ <- clearAbortFlagIfConsumed(sessionId, state)
            outcome = buildOutcome(state, ack, reconciled = false)
          yield outcome
        case None =>
          reconcile(sessionId, messageId, "settle deadline exceeded without terminal events")
      }
      .catchAll(err =>
        reconcile(sessionId, messageId, s"event stream failed (${err.code}: ${err.detail})")
      )

  private def buildOutcome(state: OcSettleState, ackRequested: Boolean, reconciled: Boolean): OcRunOutcome =
    state.serverError match
      case Some(err) if state.abortSeen =>
        OcRunOutcome.Aborted(state.sessionId, state.messageId, err, state.finalTools, ackRequested, reconciled)
      case Some(err) =>
        OcRunOutcome.Failed(state.sessionId, state.messageId, err, state.finalTools, reconciled)
      case None =>
        state.assistant match
          case Some(a) if a.time.completed.isDefined =>
            OcRunOutcome.Completed(
              state.sessionId,
              state.messageId,
              Some(a.id),
              state.textJoined,
              state.finalTools,
              a.cost,
              a.tokens,
              reconciled,
            )
          case _ =>
            OcRunOutcome.Uncertain(
              state.sessionId,
              state.messageId,
              "settled-completion evidence incomplete (no terminal error, assistant not completed)",
            )

  /** Read-only reconciliation after a deadline or a stream failure. Uses GET
   * endpoints only; never re-sends prompt_async or abort.
   */
  private def reconcile(
      sessionId: String,
      messageId: String,
      trigger: String,
  ): ZIO[Scope & Clock, OcError, OcRunOutcome] =
    messages(sessionId).foldZIO(
      err =>
        ZIO.succeed(
          OcRunOutcome.Uncertain(
            sessionId,
            messageId,
            s"$trigger; read-only reconciliation also failed (${err.code}: ${err.detail})",
          )
        ),
      list =>
        for
          ack <- ackRequested(sessionId)
          state = list.foldLeft(OcSettleState.initial(sessionId, messageId))(_.observe(_))
          // The message list carries no session.idle evidence; a completed or
          // errored assistant message for this run settles the run here.
          settledByList =
            state.serverError.isDefined || state.assistant.exists(_.time.completed.isDefined)
          outcome =
            if settledByList then buildOutcome(state, ack, reconciled = true)
            else
              OcRunOutcome.Uncertain(
                sessionId,
                messageId,
                s"$trigger; server message list shows no settled assistant message for this run",
              )
        yield outcome
    )

/** Facade: health gating + attachment to one OpenCode server.
 */
final class OpenCodeBackend private[opencode] (cfg: OcConfig, http: OcHttp):

  def health(): IO[OcError, OcHealth] =
    http.get("/global/health").flatMap(r => http.decodeBody[OcHealth](r))

  /** Verifies the health gate (healthy + parseable version in the required
   * API family) and attaches to the shared event feed. The returned backend
   * and its bus live in the caller's scope.
   */
  def connect(): ZIO[Scope & Clock, OcError, ConnectedBackend] =
    for
      h <- health()
      _ <- ZIO
        .whenDiscard(!h.healthy):
          ZIO.fail(OcError.Configuration("server reports healthy=false; refusing to attach"))
      family <- ZIO
        .fromEither(OpenCodeBackend.versionFamily(h.version))
        .mapError(detail =>
          OcError.Configuration(
            s"server version '${h.version}' is not a recognizable v1-family version; refusing to attach ($detail)"
          )
        )
      _ <- ZIO
        .whenDiscard(family < OpenCodeBackend.RequiredApiFamily):
          ZIO.fail(
            OcError.Configuration(
              s"server version '${h.version}' is outside the pinned v${OpenCodeBackend.RequiredApiFamily} API family; refusing to attach"
            )
          )
      bus <- OcEventBus.live(cfg, http)
      flags <- Ref.make(Set.empty[String])
    yield ConnectedBackend(cfg, http, bus, OcServerInfo(h.version, family, healthy = true), flags)

object OpenCodeBackend:
  /** The pinned wire surface is the v1 HTTP API. */
  val RequiredApiFamily: Int = 1

  /** Parses the leading integer of the version string as the API family
   * ("1.19.4" -> 1). Unparseable/empty versions are refused.
   */
  def versionFamily(version: String): Either[String, Int] =
    val trimmed = version.trim
    val m = "^(\\d+)\\.".r.findPrefixMatchOf(trimmed)
    m match
      case Some(m2) => Right(m2.group(1).toInt)
      case None     => Left("expected '<int>.<int>[...]'")

  def make(cfg: OcConfig): IO[OcError, OpenCodeBackend] =
    cfg.lifecycle match
      case OcLifecycle.External =>
        ZIO.succeed(new OpenCodeBackend(cfg, new OcHttp(cfg)))
      case owned: OcLifecycle.Owned =>
        ZIO.fail(
          OcError.CapabilityUnsupported(
            "owned server lifecycle is not implemented; run `opencode serve` externally (External lifecycle); requested command: " +
              owned.command.mkString(" ")
          )
        )
