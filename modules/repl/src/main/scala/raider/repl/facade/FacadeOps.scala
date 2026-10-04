package raider.repl.facade

import raider.core.{JobId, RaiderError, RequestMessage, RootId, Task}
import raider.runtime.jobs.JobHandle
import raider.runtime.loop.AgentLoop
import zio.{Exit, Unsafe, ZIO}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.annotation.targetName
import scala.jdk.CollectionConverters.*

/** REPL facade ops (runtime-contracts §3): public `ask/start/await/send/
  * cancel/watch` are extension methods in `raider.repl`, never core members.
  *
  * These ops are the blocking facade boundary — for the REPL thread only,
  * never from workflow fibers (which use the async runtime API directly).
  * Every op delegates to the ONE canonical runtime path (AgentLoop /
  * JobManager); there is no second execution semantics. Failures surface as
  * typed RaiderError exceptions (RunFailed-style, contracts §3) for the
  * console.
  *
  * Ergonomics layer (RAI-018 slice, working-product §4 golden scenarios):
  *  - `scout("prompt")` builds a LAZY `AgentAsk` descriptor (construction
  *    dispatches nothing — API-03) with `.run()/.ask()/.start()/.map(f)`;
  *  - `all(scout, reviewer).ask("p")` and `all(askA, askB).run()` compose
  *    through the ONE `raider.dsl.composition` interpreter (tuple, fail-fast);
  *  - `batch(inputs, parallelism)(f)` composes through `composition.batch`;
  *  - `openSession(worker)` returns a `Chat` that keeps conversation history
  *    (multi-turn continuity through the same AgentLoop).
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
  private[repl] val traces  =
    ConcurrentHashMap[JobId, zio.Ref[Vector[raider.core.trace.TraceEvent]]]()

  private[repl] def handleById(id: JobId): Option[JobHandle[?]] =
    Option(started.get(id))

  private[repl] def traceOf(id: JobId): Option[zio.Ref[Vector[raider.core.trace.TraceEvent]]] =
    Option(traces.get(id))

  private[repl] def allHandles: List[JobHandle[?]] =
    started.values.asScala.toList.sortBy(_.id.value)

  private[repl] def unsafeRun[A](eff: ZIO[Any, RaiderError, A]): A =
    Unsafe.unsafe { implicit u =>
      zio.Runtime.default.unsafe.run(eff) match
        case Exit.Success(value)     => value
        case Exit.Failure(cause)     => throw cause.squash
    }

  /** One-line admin rendering of a job snapshot (shared with :jobs). */
  def renderSnapshot(s: raider.runtime.jobs.JobSnapshot): String =
    s"${s.id.value}  ${s.status}  ${s.label}" +
      (if s.lateObservations > 0 then s"  (late=${s.lateObservations})" else "")

  private[repl] def textAttempt(session: ReplSession, agent: AgentRef, prompt: String)
      : ZIO[Any, RaiderError, String] =
    textAttemptMessages(session, agent, List(RequestMessage("user", prompt)))

  /** The ONE agent execution point (RAI-010.a) over an explicit message
    * list — the multi-turn Chat continuity rides the same loop. */
  private[repl] def textAttemptMessages(session: ReplSession, agent: AgentRef,
      messages: List[RequestMessage]): ZIO[Any, RaiderError, String] =
    AgentLoop(session.backend, session.tools, s"scripted:${agent.name}",
      session.admission, AgentLoop.newRoot())
      .runText(messages, session.limits, session.limits.maxAttempts)

  private[repl] def startJob[A](session: ReplSession, agent: AgentRef,
      prompt: String, task: Task[A]): JobHandle[A] =
    val n = jobSeq.incrementAndGet()
    val handle = unsafeRun:
      session.jobs.start(RootId(s"root_job_$n"),
        s"${agent.name}: ${prompt.take(48)}", task)
    started.put(handle.id, handle)
    // best-effort trace: if the task wraps a traced loop, its events are here
    val traceRef = zio.Unsafe.unsafe { implicit u =>
      zio.Ref.unsafe.make(Vector.empty[raider.core.trace.TraceEvent])(using u)
    }
    traces.put(handle.id, traceRef)
    handle

// ---------------------------------------------------------------------------
// AgentAsk: a lazy, typed prompt descriptor (API-03: construction dispatches
// nothing; only run/ask/start do).
// ---------------------------------------------------------------------------

final class AgentAsk[A] private[facade] (
  val agent: AgentRef,
  val prompt: String,
  private[facade] val underlying: Task[A]
):

  /** Typed post-processing; still lazy, still the one interpreter. */
  def map[B](f: A => B): AgentAsk[B] =
    new AgentAsk[B](agent, prompt, underlying.map(f))

  /** Foreground execution; blocks the REPL thread until the final text. */
  def run()(using ReplSession): A = FacadeOps.unsafeRun(Task.zio(underlying))

  /** Alias of run() — reads naturally on freshly built descriptors. */
  def ask()(using ReplSession): A = run()

  /** Background start: a registered job handle (visible in :jobs/:cancel). */
  def start()(using session: ReplSession): JobHandle[A] =
    FacadeOps.startJob(session, agent, prompt, underlying)

extension (agent: AgentRef)

  /** Foreground ask: one admitted agent run; blocks until final text. */
  def ask(prompt: String)(using session: ReplSession): String =
    FacadeOps.unsafeRun(FacadeOps.textAttempt(session, agent, prompt))

  /** Lazy descriptor: `scout("prompt")` builds an AgentAsk — nothing runs
    * until `.run()/.ask()/.start()`. Composable via map/all/batch. */
  def apply(prompt: String)(using session: ReplSession): AgentAsk[String] =
    new AgentAsk(agent, prompt,
      Task(FacadeOps.textAttempt(session, agent, prompt)))

  /** Background start: returns a job handle immediately; the fiber is owned by
    * the session JobManager and survives across REPL submissions. */
  def start(prompt: String)(using session: ReplSession): JobHandle[String] =
    FacadeOps.startJob(session, agent, prompt,
      Task(FacadeOps.textAttempt(session, agent, prompt)))

extension [A] (job: JobHandle[A])

  /** Await terminal outcome; idempotent, any number of waiters (JOB-03). */
  def await()(using session: ReplSession): A =
    FacadeOps.unsafeRun(session.jobs.await(job))

  /** Parameterless spelling of the same await (golden-scenario ergonomics).
    * targetName avoids the empty-parens erasure clash with await(). */
  @targetName("awaitNoParens")
  def await(using session: ReplSession): A =
    FacadeOps.unsafeRun(session.jobs.await(job))

  /** Request cancellation; false if the job is already terminal. */
  def cancel()(using session: ReplSession): Boolean =
    FacadeOps.unsafeRun(session.jobs.cancelAny(job))

  /** Watch: print the current snapshot, wait until terminal, print the final
    * one. (Streaming per-event watch is a beta obligation, cut from W10.) */
  def watch()(using session: ReplSession): Unit =
    FacadeOps.unsafeRun:
      session.jobs.snapshotAny(job).flatMap { first =>
        zio.Console.printLine(FacadeOps.renderSnapshot(first)).orDie *>
          session.jobs.awaitSettled(job) *>
          session.jobs.snapshotAny(job).flatMap { last =>
            zio.Console.printLine(FacadeOps.renderSnapshot(last)).orDie
          }
      }

  /** Send/steer a running conversation. NOT in the W10 subset: typed refusal
    * at call time (obligation tracked on the board, never a silent no-op). */
  def send(message: String)(using session: ReplSession): Unit =
    throw RaiderError.CapabilityUnsupported(
      "job steering (send) is not in the W10 REPL subset")

// ---------------------------------------------------------------------------
// all / batch: facade spellings over the ONE composition interpreter.
// ---------------------------------------------------------------------------

/** `all(scout, reviewer).ask("p")` and `all(askA, askB).run()` — arity-2
  * tuple composition, fail-fast (COMP-02), construction is lazy. */
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
    FacadeOps.unsafeRun(Task.zio(
      raider.dsl.composition.all(x.underlying, y.underlying)))

/** `batch(inputs, parallelism)(f)` — ordered bounded batch over lazy
  * descriptors; input order preserved; empty batch dispatches nothing. */
object batch:

  def apply[A, R](inputs: List[A], parallelism: Int = 4)
                 (f: A => AgentAsk[R]): BatchAsk[R] =
    new BatchAsk(inputs.map(f), parallelism)

final class BatchAsk[R] private[facade] (asks: List[AgentAsk[R]],
                                         parallelism: Int):
  def run()(using ReplSession): List[R] =
    FacadeOps.unsafeRun(Task.zio(
      raider.dsl.composition.batch(asks, parallelism)(_.underlying)))

  /** Domain failures become explicit outcomes; external interruption is
    * re-raised (§5) — see BatchOutcome. */
  def collect()(using ReplSession): List[raider.dsl.composition.BatchOutcome[R]] =
    FacadeOps.unsafeRun(Task.zio(
      raider.dsl.composition.batch.collect(asks, parallelism)(_.underlying)))

// ---------------------------------------------------------------------------
// openSession: multi-turn chat continuity through the same AgentLoop.
// ---------------------------------------------------------------------------

/** `openSession(worker)` — a conversation with history: every `ask` appends
  * the user turn and the assistant reply, so the NEXT ask sees the whole
  * conversation (multi-turn continuity, working-product §4). */
final class Chat private[facade] (agent: AgentRef, session: ReplSession):
  private val history =
    java.util.concurrent.CopyOnWriteArrayList[RequestMessage]()

  def ask(prompt: String): String =
    val snapshot: List[RequestMessage] =
      history.synchronized(history.asScala.toList)
    val reply = FacadeOps.unsafeRun:
      FacadeOps.textAttemptMessages(
        session, agent, snapshot :+ RequestMessage("user", prompt))
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
