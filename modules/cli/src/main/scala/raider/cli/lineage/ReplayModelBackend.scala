package raider.cli.lineage

import raider.core.*
import raider.dsl.lineage.data.{LineageDataDocument, NodeData}
import raider.dsl.lineage.replay.RecordedSource

import zio.{Scope, ZIO}
import zio.Chunk
import zio.json.{JsonDecoder, JsonEncoder}
import zio.json.ast.Json
import zio.stream.ZStream

import java.util.concurrent.atomic.AtomicInteger

/** Typed replay-backend failure (LIN-06A, ADR-027 §2). The FROZEN `RaiderError`
  * family (runtime-contracts §14) gains no new variant — a lane card cannot
  * extend the wire — so the stream channel carries `RaiderError.StreamProtocol`
  * while the typed instance rides the exception cause chain (see
  * [[ReplayBackendError.toRaiderError]] /
  * [[ReplayBackendError.fromRaiderError]]): no string parsing, no masking into
  * a fake success.
  */
enum ReplayBackendError(what: String) extends RuntimeException(what):

  /** The (1-based) n-th stream call has no recorded answer: the record holds
    * only `recordedCount` model calls.
    */
  case Overrun(requestOrdinal: Int, recordedCount: Int)
      extends ReplayBackendError(
        s"replay backend overrun: model request #$requestOrdinal " +
          s"but only $recordedCount recorded model call(s)"
      )

  /** The record holds no model-call data at all: replay can never start, so
    * every stream call fails immediately with this.
    */
  case Exhausted
      extends ReplayBackendError(
        "replay backend exhausted: no recorded model call data"
      )

  /** STRICT-mode prompt drift (LIN-05G): the incoming request's prompt does not
    * equal the recorded prompt of the (1-based) `requestOrdinal`-th model call.
    * `detail` is bounded and CONTENT-FREE by construction — lengths and stable
    * hex fingerprints only; the full prompt texts never enter the error
    * message, logs or tool output.
    */
  case PromptDrift(requestOrdinal: Int, detail: String)
      extends ReplayBackendError(
        s"replay backend prompt drift: model request #$requestOrdinal: $detail"
      )

object ReplayBackendError:

  /** Bridges a typed replay failure onto the frozen `RaiderError` stream
    * channel: `StreamProtocol` with the deterministic `what` message on the
    * wire, the typed instance attached as the exception cause. Fresh error
    * object per failure, cause set exactly once — no shared state.
    */
  def toRaiderError(error: ReplayBackendError): RaiderError.StreamProtocol =
    val wire = RaiderError.StreamProtocol(error.getMessage)
    wire.initCause(error)
    wire

  /** Typed recovery of a replay-backend failure from the stream channel:
    * `Some(typed)` iff the error was produced by [[ReplayModelBackend]].
    */
  def fromRaiderError(error: RaiderError): Option[ReplayBackendError] =
    error.getCause match
      case replay: ReplayBackendError => Some(replay)
      case _                          => None

end ReplayBackendError

/** ReplayModelBackend: a `ModelBackend` whose answers ARE the recorded ones
  * (LIN-06A, ADR-027 §2): like the LIN-05 replay interpreter, answers come from
  * the lineage data resource, never from a model — this card adds the
  * `ModelEvent` translation so the full AgentLoop replay harness (the NEXT
  * card) can drive an unmodified loop over a record. No network, no model, no
  * clocks: each event sequence is a pure function of the record.
  *
  * Association strategy: ORDINAL. The n-th stream call (1-based) on this
  * backend instance replays the n-th recorded `ModelCallData` of the document.
  * Deterministic record order = `RecordedSource.dataRefs` (sorted — the codec's
  * canonical `data` key order) filtered to the refs that resolve to
  * `NodeData.ModelCallData`. The ordinal is taken when a stream is first pulled
  * (an atomic counter, the same discipline as the scripted testkit backends),
  * so even concurrent pulls observe a strict total arrival order. The rule is
  * positional BY DESIGN: the record stores answers, not a request-to-answer
  * correspondence, and ordinal playback is exactly what reproduces a recorded
  * run.
  *
  * Event shape per call: `Started` -> one `TextDelta` carrying the whole
  * recorded response (synthetic single chunk) -> `UsageObserved` (only when the
  * record carries usage) -> `Finished("stop")`. Attempt ids are deterministic
  * (`replay-1`, `replay-2`, ...), so a repeat replay over the same record with
  * a fresh backend yields identical event sequences. Usage mapping is honest:
  * the frozen lineage-data schema stores COST (micro-USD) and has no token
  * counts, so token fields stay 0 with unknown currency and `priceKnown`
  * reflects the recorded observed cost.
  *
  * TOOL ROUNDS (LIN-06B-1, packet L48, ADR-027 §2): a recorded response that
  * decodes as a JSON object with a non-empty `tool_calls` array of
  * `{"id","name","arguments"}` entries is replayed as a TOOL round — one
  * `ToolCallReady` per entry (`arguments` taken verbatim when it is a JSON
  * string, compactly encoded when it is a JSON value, `{}` when absent) and
  * `Finished("tool_calls")` — the same `message.tool_calls` → `ToolCallReady`
  * translation the OpenAI-compatible live backend performs, so an unmodified
  * AgentLoop executes the recorded tool round exactly as the live one. Any
  * other response (including other JSON documents) stays a plain-text round.
  * The record convention is frozen here: the full prompt/response strings of
  * lineage-data are the single source of truth, and a tool round is fully
  * described by its recorded response text.
  *
  * STRICT PROMPT VERIFICATION (LIN-05G, opt-in): with `verifyPrompts = true`
  * every stream call ALSO verifies its incoming request before replaying — the
  * incoming prompt (the content of the LAST `user` message, falling back to the
  * last message of any role, then to the empty string) must EQUAL the recorded
  * `ModelCallData.prompt` of the same ordinal, and any difference fails the
  * stream with a typed [[ReplayBackendError.PromptDrift]] whose detail is
  * bounded and content-free (lengths + stable hex fingerprints — never the full
  * texts, nothing logged). The default `false` keeps the association purely
  * positional and content-blind. Single-round records whose recorded prompt IS
  * the user message match exactly; multi-round records whose recorded prompt
  * embeds assistant/tool renderings are EXPECTED to drift — that is the check
  * working, not a defect. Exact string equality: no normalization, no clocks,
  * no randomness.
  *
  * Honest failures and capabilities: exhausting the records fails the stream
  * with a typed [[ReplayBackendError]] (`Overrun` for the (n)-th request beyond
  * the record, `Exhausted` when the source has no model-call data at all).
  * Capabilities are honest PER RECORD: `streaming` = Supported (the synthetic
  * single chunk); `tools` = Supported iff at least one recorded response
  * carries a `tool_calls` envelope (a plain-text record still cannot serve tool
  * rounds — LIN-06A discipline), `cancellationAck` = Unknown — a replay makes
  * no interruption guarantees.
  *
  * @param recorded
  *   read-only view over the recorded answers; refs and data are read ONLY
  *   through it (single source of truth).
  * @param doc
  *   the owning lineage-data document `recorded` was built from (bound by
  *   `RecordedSource(doc)`); kept as construction evidence, never re-read or
  *   re-derived so the document is not a second source of truth.
  * @param verifyPrompts
  *   LIN-05G strict mode (default false): verify each incoming request's prompt
  *   against the recorded prompt of the same ordinal (see the class scaladoc);
  *   a mismatch fails with [[ReplayBackendError.PromptDrift]].
  */
final class ReplayModelBackend(
    recorded: RecordedSource,
    doc: LineageDataDocument,
    verifyPrompts: Boolean = false
) extends ModelBackend:

  private val AttemptPrefix = "replay-"
  private val FinishReason = "stop"
  private val ToolRoundFinish = "tool_calls"
  private val UserRole = "user"

  // Deterministic playback order: sorted dataRefs filtered to model calls.
  private val records: Vector[(String, NodeData.ModelCallData)] =
    recorded.dataRefs.flatMap { ref =>
      recorded.modelOutput(ref).toOption.map(ref -> _)
    }

  private val nextOrdinal = new AtomicInteger(0)

  override def capabilities: ModelCapabilities =
    ModelCapabilities(
      streaming = CapabilityStatus.Supported,
      tools =
        if records.exists((_, data) => toolCallsOf(data.response).isDefined)
        then CapabilityStatus.Supported
        else CapabilityStatus.Unsupported,
      cancellationAck = CapabilityStatus.Unknown
    )

  override def stream(
      input: ModelRequest
  ): ZStream[Scope, RaiderError, ModelEvent] =
    ZStream
      .fromZIO:
        for
          k <- ZIO.succeed(nextOrdinal.getAndIncrement())
          events <- records.lift(k) match
            case Some((_, data)) =>
              if verifyPrompts then
                verifyNoDrift(k + 1, input, data).as(
                  replayEvents(AttemptId(attemptId(k)), data)
                )
              else ZIO.succeed(replayEvents(AttemptId(attemptId(k)), data))
            case None =>
              ZIO.fail(
                ReplayBackendError.toRaiderError(
                  if records.isEmpty then ReplayBackendError.Exhausted
                  else ReplayBackendError.Overrun(k + 1, records.size)
                )
              )
        yield events
      .flatMap(ZStream.fromIterable(_))

  /** STRICT prompt verification (LIN-05G): the incoming prompt (see
    * [[incomingPrompt]]) must EQUAL the recorded prompt of this call; any
    * difference fails the stream with a typed, content-free
    * [[ReplayBackendError.PromptDrift]]. Exact string equality — deterministic,
    * no normalization, no content logging.
    */
  private def verifyNoDrift(
      n: Int,
      input: ModelRequest,
      data: NodeData.ModelCallData
  ): ZIO[Any, RaiderError, Unit] =
    val incoming = incomingPrompt(input)
    if incoming == data.prompt then ZIO.unit
    else
      ZIO.fail(
        ReplayBackendError.toRaiderError(
          ReplayBackendError.PromptDrift(n, driftDetail(data.prompt, incoming))
        )
      )

  /** The incoming prompt under verification (LIN-05G): the content of the LAST
    * `user` message of the request; a request with no `user` message falls back
    * to its last message of any role; a request with no messages verifies as
    * the empty string.
    */
  private def incomingPrompt(request: ModelRequest): String =
    request.messages
      .findLast(_.role == UserRole)
      .orElse(request.messages.lastOption)
      .map(_.content)
      .getOrElse("")

  /** Bounded, content-free drift detail: prompt LENGTHS and stable hex
    * fingerprints only — the full prompt texts never enter the error (the
    * no-content rule of LIN-05G).
    */
  private def driftDetail(recorded: String, incoming: String): String =
    s"recorded prompt (len ${recorded.length}, fp ${fingerprint(recorded)}) " +
      s"does not match incoming prompt (len ${incoming.length}, fp ${fingerprint(incoming)})"

  // Stable content-free fingerprint: the Java-spec String.hashCode in hex —
  // deterministic across runs and JVMs, bounded (8 chars), reveals no prompt
  // content.
  private def fingerprint(value: String): String =
    f"${value.hashCode}%08x"

  private def attemptId(zeroBased: Int): String =
    s"$AttemptPrefix${zeroBased + 1}"

  private def replayEvents(
      attempt: AttemptId,
      data: NodeData.ModelCallData
  ): Vector[ModelEvent] =
    val usage = data.usage.map(u =>
      // The frozen record carries cost (micro-USD), never token counts:
      // token fields stay 0/unknown; priceKnown = observed cost recorded.
      ModelEvent.UsageObserved(
        attempt,
        Usage(0, 0, None),
        u.observedMicroUsd.isDefined
      )
    )
    // Order mirrors the live backend translation: Started -> body
    // (text delta OR tool calls) -> UsageObserved? -> Finished.
    val (body, finish) =
      toolCallsOf(data.response) match
        case Some(calls) =>
          (
            calls.map((id, name, args) =>
              ModelEvent.ToolCallReady(attempt, id, name, args)
            ),
            ModelEvent.Finished(attempt, ToolRoundFinish)
          )
        case None =>
          (
            Vector(ModelEvent.TextDelta(attempt, data.response)),
            ModelEvent.Finished(attempt, FinishReason)
          )
    (Vector(ModelEvent.Started(attempt)) ++ body ++ usage :+ finish)

  /** The `tool_calls` envelope of a recorded response, if it IS one: a JSON
    * object with a non-empty `tool_calls` array of objects carrying `id` and
    * `name` (see the class scaladoc). Any other value — including other JSON
    * documents and an empty `tool_calls` array — is a plain-text response.
    */
  private def toolCallsOf(
      response: String
  ): Option[Vector[(ToolCallId, String, String)]] =
    summon[JsonDecoder[Json]].decodeJson(response).toOption.flatMap {
      case Json.Obj(fields) =>
        fields.collectFirst { case (_, calls: Json.Arr) =>
          calls.elements
        } match
          case None => None
          case Some(items) =>
            val calls = items.flatMap {
              case Json.Obj(member) =>
                for
                  id <- strField(member, "id")
                  name <- strField(member, "name")
                yield (ToolCallId(id), name, argsField(member))
              case _ => None
            }
            if calls.isEmpty then None else Some(calls.toVector)
      case _ => None
    }

  private def strField(
      fields: Chunk[(String, Json)],
      key: String
  ): Option[String] =
    fields.collectFirst { case (`key`, Json.Str(v)) => v }

  /** Recorded tool arguments: a JSON string is used VERBATIM (the OpenAI wire
    * convention), any other JSON value is compactly encoded, absence is `{}`.
    */
  private def argsField(fields: Chunk[(String, Json)]): String =
    fields
      .collectFirst { case ("arguments", Json.Str(raw)) => raw }
      .orElse(fields.collectFirst { case ("arguments", value) =>
        summon[JsonEncoder[Json]].encodeJson(value, None).toString
      })
      .getOrElse("{}")

end ReplayModelBackend
