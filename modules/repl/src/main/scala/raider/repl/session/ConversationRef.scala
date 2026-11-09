package raider.repl.session

import raider.core.{
  Program,
  RaiderError,
  RequestMessage,
  RootId,
  SessionId,
  Task
}
import raider.repl.facade.{AgentRef, FacadeOps, ReplSession}
import raider.runtime.conversation.{
  Conversation,
  ConversationArchive,
  ConversationBounds,
  ConversationStore,
  TurnId,
  TurnOutcome,
  TurnReceipt,
  TurnRunner
}
import zio.ZIO

import java.nio.file.Path

/** Blocking handle for ONE background conversation turn (the `start` verb of
  * dsl-experience §8 — same verb semantics, conversation-scoped: `ask` is
  * foreground, `start` is background).
  *
  *   - `await()` blocks the REPL thread until the turn settles and returns the
  *     reply; failures surface as typed `RaiderError` throws (RunFailed-style,
  *     contracts §3). Idempotent: any number of awaits.
  *   - `cancel()` requests cancellation (queued turns are skipped at dispatch,
  *     in-flight turns are interrupted). It returns whether the request was
  *     DELIVERED — the receipt still decides the final outcome (completion
  *     wins), so a turn that finished before the request landed keeps its reply
  *     and `await()` returns it. `false` = unknown turn id.
  */
final class ConversationTurn(
    val id: TurnId,
    receipt: TurnReceipt,
    ref: ConversationRef
):

  def await(): String =
    FacadeOps.unsafeRun(receipt.settled.await) match
      case TurnOutcome.Done(reply)   => reply
      case TurnOutcome.Failed(error) => throw error
      case TurnOutcome.Cancelled =>
        throw RaiderError.Cancelled(
          s"conversation ${ref.conversation.sessionId.value}: turn ${id.value} " +
            "was cancelled before it settled (close or explicit cancellation); no reply was fabricated"
        )

  def cancel(): Boolean = ref.cancel(id)

/** REPL handle for one serialized conversation session (RAI-036; dsl-experience
  * §8): `openSession`-style multi-turn continuity whose turns are SERIALIZED by
  * the runtime [[Conversation]] dispatcher — exactly one provider turn in
  * flight at a time, in acceptance order, regardless of session admission
  * limits (SESSION-01).
  *
  * Verbs (semantics do not change from the facade — only the scoping does):
  *   - `ask(prompt)` — foreground: accepts the turn, blocks the REPL thread
  *     until the reply. Refusals are typed (sealed → RA-INP; bounds/queue →
  *     RA-LBUDGET — honest errors, never compaction).
  *   - `start(prompt)` — background: returns a [[ConversationTurn]] receipt
  *     immediately; the turn still runs through the SAME serialized dispatcher.
  *   - `program` / the underlying [[Conversation]] implement the ONE execution
  *     contract [[Program]]: `apply(input)` is the lazy Task spelling of ask.
  *   - `close()` — seals the conversation (idempotent, safe from any thread):
  *     every accepted turn settles (completion wins for the in-flight turn,
  *     queued turns cancel); later asks refuse typed.
  *   - `archive(base)` — persists the full prompt/reply transcript
  *     (schemaVersion 1) under `<sessionId>.json`; reopen is explicit via
  *     [[SessionArchive.reopen]] and executes NOTHING until the next ask.
  *
  * The provider execution point is the facade's ONE `textAttemptMessages` path
  * over the same AgentLoop as `ask`/`openSession` — no second runtime.
  */
final class ConversationRef private[session] (
    val conversation: Conversation,
    val agent: AgentRef,
    session: ReplSession
):

  /** Foreground ask (blocks the calling thread until the reply). */
  def ask(prompt: String): String =
    FacadeOps.unsafeRun(Task.zio(conversation.apply(prompt)))

  /** Background start: the turn is accepted and its receipt returned
    * immediately; execution remains serialized inside the conversation.
    */
  def start(prompt: String): ConversationTurn =
    val receipt = FacadeOps.unsafeRun(conversation.submit(prompt))
    ConversationTurn(receipt.id, receipt, this)

  /** The ONE execution contract surface (runtime-contracts §3): a lazy Task
    * that accepts and settles the turn. `ref.program.apply("...")` is the
    * Program-spelling of `ask`.
    */
  def program: Program[String, String] = conversation

  /** Request cancellation of a known turn; see [[ConversationTurn.cancel]]. */
  def cancel(id: TurnId): Boolean =
    FacadeOps.unsafeRun(conversation.cancelTurn(id))

  /** Seal the conversation: idempotent, safe from any thread. Session jobs and
    * their steering channels are NOT touched (conversation-scoped lifecycle,
    * same discipline as the facade Chat).
    */
  def close(): Unit = FacadeOps.unsafeRun(conversation.close)

  /** Conversation so far: user/assistant messages in turn order. */
  def messages: List[RequestMessage] =
    FacadeOps.unsafeRun(conversation.snapshot)

  /** Persist the transcript as a schemaVersion-1 archive document. The recorded
    * `model` is the session model; the session id and the root that OWNED the
    * turns are preserved verbatim.
    */
  def archive(base: Path = ConversationStore.defaultBase): Path =
    val archivedAtMs = System.currentTimeMillis()
    FacadeOps.unsafeRun:
      for
        snap <- conversation.snapshot
        archive <- ZIO.fromEither(
          ConversationArchive.fromSnapshot(
            conversation.sessionId,
            conversation.root,
            conversation.label,
            session.model,
            snap,
            archivedAtMs
          )
        )
        path <- ConversationStore(base).save(archive)
      yield path

object ConversationRef:

  /** Open a fresh serialized conversation with `agent` through the calling
    * session. Nothing dispatches at open (API-03): the first turn runs only on
    * the first explicit `ask`/`start`.
    */
  def open(
      agent: AgentRef,
      bounds: ConversationBounds = ConversationBounds.default,
      label: Option[String] = None,
      root: RootId = Conversation.newRoot(),
      seed: List[RequestMessage] = Nil
  )(using session: ReplSession): ConversationRef =
    new ConversationRef(
      FacadeOps.unsafeRun(
        Conversation.make(root, bounds, turnRunner(session, agent), label, seed)
      ),
      agent,
      session
    )

  /** The per-turn execution point: the facade's ONE agent path over an explicit
    * message list (same loop as openSession/ask — no second runtime).
    */
  private[session] def turnRunner(
      session: ReplSession,
      agent: AgentRef
  ): TurnRunner =
    TurnRunner(messages =>
      FacadeOps.textAttemptMessages(session, agent, messages)
    )

end ConversationRef

/** Archive/restore surface for conversation sessions (RAI-036, SESSION-01/02):
  * the durable counterpart of [[ConversationRef]]. Storage discipline mirrors
  * the CLI session store — strict id sanitizing, atomic writes, typed refusals
  * — via [[ConversationStore]].
  */
object SessionArchive:

  /** Load one archived conversation. Missing documents, corrupt documents,
    * invalid ids and unreadable schema versions are DISTINCT typed errors
    * (RA-INP) — never a silent empty session (SESSION-02).
    */
  def load(
      id: String,
      base: Path = ConversationStore.defaultBase
  ): ConversationArchive =
    FacadeOps.unsafeRun(ConversationStore(base).load(id))

  /** Ids of all readable archives, sorted (corrupt files are skipped by the
    * listing; loading them directly still fails typed).
    */
  def list(base: Path = ConversationStore.defaultBase): List[SessionId] =
    FacadeOps.unsafeRun(ConversationStore(base).list())

  /** Reopen an archived conversation as a LIVE conversation with `agent`
    * (SESSION-01). Honesty rules:
    *   - the archived transcript becomes the EXPLICIT seed — reopening executes
    *     NOTHING until the next explicit `ask`/`start` (no implicit agent
    *     restart, SESSION-02);
    *   - the archived session id is PRESERVED (same durable identity), while
    *     the conversation gets a FRESH root: the archived root belonged to the
    *     dead session and is never reused (runtime-contracts §5 ownership);
    *   - the archive must fit the target bounds — an oversized transcript is a
    *     typed RA-LBUDGET refusal BEFORE any turn runs (no compaction).
    */
  def reopen(
      id: String,
      agent: AgentRef,
      base: Path = ConversationStore.defaultBase,
      bounds: ConversationBounds = ConversationBounds.default,
      label: Option[String] = None
  )(using session: ReplSession): ConversationRef =
    val archive = load(id, base)
    FacadeOps.unsafeRun:
      for
        _ <- ZIO
          .fromEither(ConversationArchive.fitsBounds(archive, bounds))
        conv <- Conversation.make(
          root = Conversation.newRoot(),
          bounds = bounds,
          runner = ConversationRef.turnRunner(session, agent),
          label = label,
          seed = ConversationArchive.toSeed(archive),
          sessionId = SessionId(archive.sessionId)
        )
      yield new ConversationRef(conv, agent, session)

end SessionArchive
