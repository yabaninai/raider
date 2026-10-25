package raider.repl.facade

import raider.core.{
  JobId,
  MessageId,
  ModelBackend,
  RaiderError,
  RequestMessage,
  RootId,
  Task,
  ToolRegistry
}
import raider.runtime.jobs.{JobHandle, JobStatus}
import raider.runtime.loop.AgentLoop
import raider.runtime.events.EventCodecs.given
import raider.runtime.mailbox.{Mailbox, MailboxError, Message}
import raider.runtime.tools.approval.ToolApproval
import zio.{Chunk, Exit, Promise, Unsafe, ZIO}
import zio.json.EncoderOps

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.annotation.targetName
import scala.jdk.CollectionConverters.*

/** REPL facade ops (runtime-contracts §3): public `ask/start/await/send/
  * cancel/watch` are extension methods in `raider.repl`, never core members.
  *
  * These ops are the blocking facade boundary — for the REPL thread only, never
  * from workflow fibers (which use the async runtime API directly). Every op
  * delegates to the ONE canonical runtime path (AgentLoop / JobManager); there
  * is no second execution semantics. Failures surface as typed RaiderError
  * exceptions (RunFailed-style, contracts §3) for the console.
  *
  * Ergonomics layer (RAI-018 slice, working-product §4 golden scenarios):
  *   - `scout("prompt")` builds a LAZY `AgentAsk` descriptor (construction
  *     dispatches nothing — API-03) with `.run()/.ask()/.start()/.map(f)`;
  *   - `all(scout, reviewer).ask("p")` and `all(askA, askB).run()` compose
  *     through the ONE `raider.dsl.composition` interpreter (tuple, fail-fast);
  *   - `batch(inputs, parallelism)(f)` composes through `composition.batch`;
  *   - `openSession(worker)` returns a `Chat` that keeps conversation history
  *     (multi-turn continuity through the same AgentLoop).
  */
final case class AgentRef(name: String):
  override def toString: String = s"Agent(${name})"

object FacadeOps:

  private[repl] val jobSeq = AtomicLong(0)

  /** Handles started through this facade in this process (single-session W10
    * slice): backs `:jobs`/`:cancel <id>` commands. Heterogeneous on purpose:
    * admin views go through snapshotAny, typed results stay with the holder.
    */
  private[repl] val started = ConcurrentHashMap[JobId, JobHandle[?]]()

  private[repl] val traces =
    ConcurrentHashMap[JobId, zio.Ref[Vector[raider.core.trace.TraceEvent]]]()

  private[repl] def handleById(id: JobId): Option[JobHandle[?]] =
    Option(started.get(id))

  private[repl] def traceOf(
      id: JobId
  ): Option[zio.Ref[Vector[raider.core.trace.TraceEvent]]] =
    Option(traces.get(id))

  private[repl] def allHandles: List[JobHandle[?]] =
    started.values.asScala.toList.sortBy(_.id.value)

  // ----- RAI-132: steering surface (facade wiring over the runtime mailbox) --

  /** Steering channels of facade-started jobs: `mailboxP` is fulfilled with the
    * runtime Mailbox under the REAL job id right after `start` returns (the
    * same publish-then-await shape as the frozen delegation wiring); `sealedP`
    * resolves when the terminal watcher closed the mailbox.
    */
  private[repl] val steer = ConcurrentHashMap[JobId, SteerChannel]()

  /** Owning session per started job — the JobManager that can snapshot it
    * (handles from different sessions share this process-global registry).
    */
  private[repl] val owners = ConcurrentHashMap[JobId, ReplSession]()

  private val steerSeq = AtomicLong(0)

  private def nextSteerId(): String = s"st-${steerSeq.incrementAndGet()}"

  /** `:jobs` marker (RAI-132): a job is steerable while its steering channel
    * exists and the job is not terminal — terminal jobs refuse sends typed
    * (never silently steerable).
    */
  def isSteerable(id: JobId, status: JobStatus): Boolean =
    steer.containsKey(id) && !JobStatus.terminal(status)

  private[repl] def unsafeRun[A](eff: ZIO[Any, RaiderError, A]): A =
    Unsafe.unsafe { implicit u =>
      zio.Runtime.default.unsafe.run(eff) match
        case Exit.Success(value) => value
        case Exit.Failure(cause) => throw cause.squash
    }

  /** One-line admin rendering of a job snapshot (shared with :jobs). */
  def renderSnapshot(s: raider.runtime.jobs.JobSnapshot): String =
    s"${s.id.value}  ${s.status}  ${s.label}" +
      (if s.lateObservations > 0 then s"  (late=${s.lateObservations})" else "")

  private[repl] def textAttempt(
      session: ReplSession,
      agent: AgentRef,
      prompt: String
  ): ZIO[Any, RaiderError, String] =
    textAttemptMessages(session, agent, List(RequestMessage("user", prompt)))

  /** REPL system prompt (nightly Phase 3.2): prepended to EVERY facade model
    * call so live backends behave as coding agents with the registered tools.
    */
  val ReplSystemPrompt: String =
    """You are a coding agent in a Scala 3 + ZIO project.
Tools: fs_read, fs_search, fs_edit, fs_patch, fs_tree, proc_run.
Be concise. Use tools before answering.""".stripMargin

  /** Interactive echo (nightly Phase 3.3): deltas to stdout, ready tool calls
    * and tool results to stderr — as they happen, not after.
    */
  private def echoBackend(
      b: ModelBackend,
      sink: Option[TextRenderSink]
  ): ModelBackend =
    raider.runtime.display.StreamingDisplay(
      b,
      onTextDelta = t => sink.fold(zio.Console.print(t).orDie)(_.textDelta(t)),
      onToolCall = (n, a) =>
        sink match
          case Some(render) => render.toolCallCard(n, a, None)
          case None =>
            zio.Console.printLineError(s"  → $n ${a.take(160)}").orDie
    )

  private def echoTracer(
      sink: Option[TextRenderSink]
  ): raider.core.trace.TraceEvent => zio.UIO[Unit] =
    case raider.core.trace.TraceEvent.ToolCallFinished(
          _,
          _,
          tool,
          _,
          result,
          ok
        ) =>
      val mark = if ok then "✓" else "✗"
      sink match
        case Some(render) =>
          render.toolCallCard(tool, "{}", Some(s"$mark ${result.take(120)}"))
        case None =>
          zio.Console
            .printLineError(s"  $mark ← $tool: ${result.take(150)}")
            .orDie
    case raider.core.trace.TraceEvent.ToolCallRefused(_, _, tool, _, reason) =>
      sink match
        case Some(render) =>
          render.toolCallCard(
            tool,
            "{}",
            Some(s"policy refused: ${reason.take(160)}")
          )
        case None =>
          zio.Console
            .printLineError(s"⊘ $tool refused: ${reason.take(160)}")
            .orDie
    case _ => zio.ZIO.unit

  /** The ONE agent execution point (RAI-010.a) over an explicit message list —
    * the multi-turn Chat continuity rides the same loop.
    */
  private[repl] def textAttemptMessages(
      session: ReplSession,
      agent: AgentRef,
      messages: List[RequestMessage]
  ): ZIO[Any, RaiderError, String] =
    val backend =
      if session.echoStream then
        echoBackend(session.backend, session.renderSink)
      else session.backend
    val modelName =
      if session.model == "scripted" then s"scripted:${agent.name}"
      else session.model
    val dispatchTools = ToolApproval.guard(session.tools, session.toolApproval)
    val root = AgentLoop.newRoot()
    val loop = AgentLoop(
      backend,
      dispatchTools,
      modelName,
      session.admission,
      root,
      trace =
        if session.echoStream then echoTracer(session.renderSink)
        else _ => zio.ZIO.unit,
      systemPrompt = Some(ReplSystemPrompt)
    )
    for
      (_, events) <- session.eventHub.subscribe(JobId(root.value))
      forwarder <- events
        .mapZIO(event =>
          session.renderSink.fold(zio.ZIO.unit)(_.eventJson(event.toJson))
        )
        .runDrain
        .forkDaemon
      result <- loop
        .runText(messages, session.limits, session.limits.maxAttempts)
        .ensuring(forwarder.interrupt.unit)
    yield result

  /** RAI-132: the ONE start path of the facade — registers the job (visible in
    * :jobs/:cancel), opens its steering channel over the runtime Mailbox, and
    * runs the attempt as a steering-aware turn loop. `g` is the deferred
    * post-map of a lazy descriptor (applied to the loop's final text exactly
    * like the previous eager `Task.map` did). Same one runtime, same
    * textAttemptMessages execution point — steering only adds turn boundaries.
    */
  private[repl] def startSteerable[A](
      session: ReplSession,
      agent: AgentRef,
      prompt: String,
      g: String => A
  ): JobHandle[A] =
    val n = jobSeq.incrementAndGet()
    val (mbxP, sealedP) = unsafeRun:
      for
        mbxP <- Promise.make[Nothing, Mailbox]
        p <- Promise.make[Nothing, Unit]
      yield (mbxP, p)
    val channel = SteerChannel(mbxP, sealedP)
    val task = Task(
      steerableAttempt(
        session,
        agent,
        List(RequestMessage("user", prompt)),
        channel
      )
    ).map(g)
    val handle = unsafeRun:
      session.jobs.start(
        RootId(s"root_job_$n"),
        s"${agent.name}: ${prompt.take(48)}",
        task
      )
    started.put(handle.id, handle)
    owners.put(handle.id, session)
    steer.put(handle.id, channel)
    // best-effort trace: if the task wraps a traced loop, its events are here
    val traceRef = zio.Unsafe.unsafe { implicit u =>
      zio.Ref.unsafe.make(Vector.empty[raider.core.trace.TraceEvent])(using u)
    }
    traces.put(handle.id, traceRef)
    // Publish the mailbox under the REAL job id; the daemon watcher seals it
    // at the observed terminal status and resolves every leftover receipt
    // (expired — acked but never applied is kept visible, never silent).
    unsafeRun:
      Mailbox
        .make(handle.id, SteerChannel.Capacity)
        .flatMap(m => mbxP.succeed(m).as(m))
        .flatMap { mbx =>
          session.jobs.awaitSettled(handle) *>
            session.jobs.snapshotAny(handle).flatMap { snap =>
              snap.status match
                case t: JobStatus.Terminal => mbx.close(t)
                case _ => zio.ZIO.unit // unreachable; stay total
            } *> finalizeExpired(channel, mbx) *>
            sealedP.succeed(()).unit
        }
        .forkDaemon
        .unit
    handle

  /** RAI-132 turn loop — the facade-side twin of the frozen delegation
    * childTurns (DelegationToolset RAI-074), consuming the SAME runtime Mailbox
    * API with the SAME application semantics:
    *
    *   1. At EVERY turn boundary the channel's mailbox is drained; a non-empty
    *      batch is appended as ONE user message
    *      `owner steering:\n- [msgId] <text>…` (arrival order, ids visible)
    *      before the next model call — never mid-call, never mid-tool.
    *   2. Turn 1 runs the initial input; turn N>1 carries the conversation
    *      forward verbatim (prior messages + the previous final text) plus the
    *      fresh batch. One textAttemptMessages (the ONE execution point) per
    *      turn — no second runtime.
    *   3. The attempt settles at the first boundary whose drain is empty.
    */
  private[repl] def steerableAttempt(
      session: ReplSession,
      agent: AgentRef,
      initial: List[RequestMessage],
      channel: SteerChannel
  ): ZIO[Any, RaiderError, String] =
    channel.mailboxP.await.flatMap { mbx =>
      def steeringSection(
          drained: Chunk[Message]
      ): List[RequestMessage] =
        if drained.isEmpty then Nil
        else
          List(
            RequestMessage(
              "user",
              drained
                .map(m => s"- [${m.id.value}] ${m.content}")
                .mkString("owner steering:\n", "\n", "")
            )
          )

      def runTurn(
          conversation: List[RequestMessage],
          drained: Chunk[Message]
      ): ZIO[Any, RaiderError, String] =
        val withSteering = conversation ++ steeringSection(drained)
        markApplied(mbx, channel, drained) *>
          textAttemptMessages(session, agent, withSteering).flatMap {
            finalText =>
              mbx.endTurn *> mbx.drainOnTurnBoundary.flatMap { queued =>
                if queued.isEmpty then ZIO.succeed(finalText)
                else
                  runTurn(
                    withSteering :+ RequestMessage(
                      AgentLoop.AssistantRole,
                      finalText
                    ),
                    queued
                  )
              }
          }

      mbx.drainOnTurnBoundary.flatMap(runTurn(initial, _))
    }

  /** A drained batch is APPLIED at this boundary: its texts are part of the
    * next model-turn input (visible in the recorded request). Each queued
    * receipt resolves with the Applied receipt. Applied means "included in the
    * model input", not "the turn completed" — an interrupted turn keeps the
    * delivery honest (the mailbox conservation already counted the drain).
    */
  private def markApplied(
      mbx: Mailbox,
      channel: SteerChannel,
      drained: Chunk[Message]
  ): zio.UIO[Unit] =
    ZIO.foreachDiscard(drained) { m =>
      Option(channel.pending.remove(m.id)) match
        case None => zio.ZIO.unit
        case Some(p) =>
          p.succeed(
            Some(SteerReceipt.Applied(mbx.jobId, m.id, m.content))
          ).unit
    }

  /** Terminal tail of the watcher: every acked-but-never-applied receipt
    * resolves as expired (`None`) — a final drain retrieves what the mailbox
    * still held after the close, then any residual registration is swept.
    * Nothing hangs, nothing is silently lost.
    */
  private def finalizeExpired(
      channel: SteerChannel,
      mbx: Mailbox
  ): zio.UIO[Unit] =
    mbx.drainOnTurnBoundary.flatMap { late =>
      ZIO.foreachDiscard(late) { m =>
        Option(channel.pending.remove(m.id))
          .fold(zio.ZIO.unit)(_.succeed(None).unit)
      } *>
        ZIO.foreachDiscard(channel.pending.values.asScala.toList)(
          _.succeed(None).unit
        )
    }

  /** RAI-132: the facade send/steer channel — delegates to the runtime steering
    * mailbox of the job's channel. TOTAL: every outcome is a typed receipt,
    * never an exception, never a silent no-op.
    *
    *   - unknown job (or no steering channel) → Rejected(UnknownJob);
    *   - terminal job → Rejected(TerminalJob) via the owning-session snapshot
    *     (the mailbox refuses TerminalJob too once the watcher sealed it, so
    *     the race window is also typed);
    *   - running job → the mailbox ack (exactly-once per msgId, bounded
    *     capacity) → Queued; MailboxError.Full → Rejected(Full).
    */
  private[repl] def sendToJob(
      id: JobId,
      text: String
  ): ZIO[Any, RaiderError, SteerReceipt] =
    Option(steer.get(id)) match
      case None =>
        ZIO.succeed(
          SteerReceipt.Rejected(
            id,
            MessageId(nextSteerId()),
            text,
            MailboxError.UnknownJob(id)
          )
        )
      case Some(channel) =>
        val mid = MessageId(nextSteerId())
        terminalStatusOf(id).flatMap {
          case Some(t) =>
            ZIO.succeed(
              SteerReceipt.Rejected(
                id,
                mid,
                text,
                MailboxError.TerminalJob(id, t)
              )
            )
          case None =>
            channel.mailboxP.await.flatMap { mbx =>
              for
                settled <- Promise.make[Nothing, Option[SteerReceipt.Applied]]
                _ <- ZIO.succeed(channel.pending.put(mid, settled))
                acked <- mbx.send(Message(mid, text)).either
                receipt <- acked match
                  case Right(ack) =>
                    mbx.pendingCount.map(p =>
                      SteerReceipt.Queued(
                        id,
                        mid,
                        text,
                        p,
                        ack.duplicate,
                        settled
                      )
                    )
                  case Left(err) =>
                    ZIO.succeed:
                      channel.pending.remove(mid)
                      SteerReceipt.Rejected(id, mid, text, err)
              yield receipt
            }
        }

  private def terminalStatusOf(
      id: JobId
  ): ZIO[Any, Nothing, Option[JobStatus.Terminal]] =
    (Option(owners.get(id)), Option(started.get(id))) match
      case (Some(session), Some(handle)) =>
        session.jobs.snapshotAny(handle).map { snap =>
          snap.status match
            case t: JobStatus.Terminal => Some(t)
            case _                     => None
        }
      case _ => ZIO.succeed(None)

// ---------------------------------------------------------------------------
// AgentAsk: a lazy, typed prompt descriptor (API-03: construction dispatches
// nothing; only run/ask/start do).
// ---------------------------------------------------------------------------

final class AgentAsk[A] private[facade] (
    val agent: AgentRef,
    val prompt: String,
    root: Task[String],
    g: String => A = identity
):
  private[facade] def underlying: Task[A] = root.map(g)

  /** Typed post-processing; still lazy, still the one interpreter. */
  def map[B](f: A => B): AgentAsk[B] =
    new AgentAsk[B](agent, prompt, root, g.andThen(f))

  /** Foreground execution; blocks the REPL thread until the final text. */
  def run()(using ReplSession): A = FacadeOps.unsafeRun(Task.zio(underlying))

  /** Alias of run() — reads naturally on freshly built descriptors. */
  def ask()(using ReplSession): A = run()

  /** Background start: a registered job handle (visible in :jobs/:cancel),
    * steerable via `send`/`:send` (RAI-132).
    */
  def start()(using session: ReplSession): JobHandle[A] =
    FacadeOps.startSteerable(session, agent, prompt, g)

extension (agent: AgentRef)

  /** Foreground ask: one admitted agent run; blocks until final text. */
  def ask(prompt: String)(using session: ReplSession): String =
    FacadeOps.unsafeRun(FacadeOps.textAttempt(session, agent, prompt))

  /** Lazy descriptor: `scout("prompt")` builds an AgentAsk — nothing runs until
    * `.run()/.ask()/.start()`. Composable via map/all/batch.
    */
  def apply(prompt: String)(using session: ReplSession): AgentAsk[String] =
    new AgentAsk(
      agent,
      prompt,
      Task(FacadeOps.textAttempt(session, agent, prompt))
    )

  /** Background start: returns a job handle immediately; the fiber is owned by
    * the session JobManager and survives across REPL submissions. The job is
    * steerable (RAI-132): `handle.send(...)` / `:send <id> <text>`.
    */
  def start(prompt: String)(using session: ReplSession): JobHandle[String] =
    FacadeOps.startSteerable(session, agent, prompt, identity)

extension [A](job: JobHandle[A])

  /** Await terminal outcome; idempotent, any number of waiters (JOB-03). */
  def await()(using session: ReplSession): A =
    FacadeOps.unsafeRun(session.jobs.await(job))

  /** Parameterless spelling of the same await (golden-scenario ergonomics).
    * targetName avoids the empty-parens erasure clash with await().
    */
  @targetName("awaitNoParens")
  def await(using session: ReplSession): A =
    FacadeOps.unsafeRun(session.jobs.await(job))

  /** Request cancellation; false if the job is already terminal. */
  def cancel()(using session: ReplSession): Boolean =
    FacadeOps.unsafeRun(session.jobs.cancelAny(job))

  /** Watch: print the current snapshot, wait until terminal, print the final
    * one. (Streaming per-event watch is a beta obligation, cut from W10.)
    */
  def watch()(using session: ReplSession): Unit =
    FacadeOps.unsafeRun:
      session.jobs.snapshotAny(job).flatMap { first =>
        zio.Console.printLine(FacadeOps.renderSnapshot(first)).orDie *>
          session.jobs.awaitSettled(job) *>
          session.jobs.snapshotAny(job).flatMap { last =>
            zio.Console.printLine(FacadeOps.renderSnapshot(last)).orDie
          }
      }

  /** Send/steer a running conversation (RAI-132): the text is delegated to the
    * job's runtime steering mailbox and applied at the NEXT model-turn boundary
    * (never mid-call, never mid-tool). The typed receipt returns immediately —
    * [[SteerReceipt.Queued]] (await its `settled` for Applied/expired) or
    * [[SteerReceipt.Rejected]] with the verbatim mailbox refusal. Never silent;
    * unknown/terminal/overflow are typed refusals.
    */
  def send(message: String)(using session: ReplSession): SteerReceipt =
    FacadeOps.unsafeRun(FacadeOps.sendToJob(job.id, message))

// ---------------------------------------------------------------------------
// all / batch: facade spellings over the ONE composition interpreter.
// ---------------------------------------------------------------------------

/** `all(scout, reviewer).ask("p")` and `all(askA, askB).run()` — arity-2 tuple
  * composition, fail-fast (COMP-02), construction is lazy.
  */
object all:

  def apply(a: AgentRef, b: AgentRef): AllAgents =
    new AllAgents(a, b)

  def apply[A, B](x: AgentAsk[A], y: AgentAsk[B]): AllAsks[A, B] =
    new AllAsks(x, y)

final class AllAgents(a: AgentRef, b: AgentRef):

  def ask(prompt: String)(using ReplSession): (String, String) =
    (a.ask(prompt), b.ask(prompt))

final class AllAsks[A, B](x: AgentAsk[A], y: AgentAsk[B]):

  def run()(using ReplSession): (A, B) =
    FacadeOps.unsafeRun(
      Task.zio(raider.dsl.composition.all(x.underlying, y.underlying))
    )

/** `batch(inputs, parallelism)(f)` — ordered bounded batch over lazy
  * descriptors; input order preserved; empty batch dispatches nothing.
  */
object batch:

  def apply[A, R](inputs: List[A], parallelism: Int = 4)(
      f: A => AgentAsk[R]
  ): BatchAsk[R] =
    new BatchAsk(inputs.map(f), parallelism)

final class BatchAsk[R] private[facade] (
    asks: List[AgentAsk[R]],
    parallelism: Int
):

  def run()(using ReplSession): List[R] =
    FacadeOps.unsafeRun(
      Task.zio(raider.dsl.composition.batch(asks, parallelism)(_.underlying))
    )

  /** Domain failures become explicit outcomes; external interruption is
    * re-raised (§5) — see BatchOutcome.
    */
  def collect()(using
      ReplSession
  ): List[raider.dsl.composition.BatchOutcome[R]] =
    FacadeOps.unsafeRun(
      Task.zio(
        raider.dsl.composition.batch.collect(asks, parallelism)(_.underlying)
      )
    )

// ---------------------------------------------------------------------------
// openSession: multi-turn chat continuity through the same AgentLoop.
// ---------------------------------------------------------------------------

/** `openSession(worker)` — a conversation with history: every `ask` appends the
  * user turn and the assistant reply, so the NEXT ask sees the whole
  * conversation (multi-turn continuity, working-product §4).
  */
final class Chat private[facade] (agent: AgentRef, session: ReplSession):

  private val history =
    java.util.concurrent.CopyOnWriteArrayList[RequestMessage]()

  def ask(prompt: String): String =
    val snapshot: List[RequestMessage] =
      history.synchronized(history.asScala.toList)
    val reply = FacadeOps.unsafeRun:
      FacadeOps.textAttemptMessages(
        session,
        agent,
        snapshot :+ RequestMessage("user", prompt)
      )
    history.synchronized:
      history.add(RequestMessage("user", prompt))
      history.add(RequestMessage("assistant", reply))
    reply

  /** Conversation so far (for introspection/tests). */
  def messages: List[RequestMessage] =
    history.synchronized(history.asScala.toList)

/** Open a multi-turn chat with an agent (golden-scenario spelling). */
def openSession(agent: AgentRef)(using session: ReplSession): Chat =
  new Chat(agent, session)
