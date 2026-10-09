package raider.runtime.delegate

import raider.runtime.loop.AgentLoop

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.jobs.{JobHandle, JobManager, JobStatus}
import raider.runtime.mailbox.{Ack, Mailbox, MailboxError, Message}
import zio.{Chunk, Duration, Fiber, IO, Promise, Scope, UIO, ZIO}
import zio.json.{DecoderOps, EncoderOps}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** Model-callable delegation tools (runtime-contracts §11, LOOP-01/W09):
  * `delegate`, `await_agent`, `cancel_agent`, `list_agents` — over the SAME
  * JobManager / root ledger / admission as workflow fork (one owner, one root
  * budget; children's attempts land on the delegating root).
  *
  * §11 semantics implemented here:
  *   - `delegate` validates the agent name against an explicit whitelist and
  *     the children count against `limits.maxChildren`; returns the child
  *     handle ID and state — NEVER the transcript;
  *   - `await_agent` suspends the parent loop and does NOT hold a model slot
  *     (children run under the same maxConcurrentLlm=1 — BUD-03 no-deadlock);
  *     it holds ONE tool slot for the duration, so construction requires
  *     `maxConcurrentTools >= 2` (an awaiting parent must not starve a
  *     tool-using child);
  *   - children CANNOT re-delegate: the child registry is the base registry
  *     only (recursion is a separate policy, not an accident);
  *   - unknown names produce structured feedback JSON (never silent misses);
  *     `send_agent` is deliberately absent as a MODEL tool (steering is the
  *     owner/REPL channel, not a model-decided capability).
  *
  * All tool outputs are single-line JSON matched back by tool_call_id.
  *
  * RAI-074 steering mailbox wiring (P3.A remainder, over the accepted RAI-065
  * `Mailbox`): every fork registers a bounded per-child mailbox (map childId ->
  * Mailbox, capacity `mailboxCapacity`, default 32). The OWNER/REPL channel is
  * `sendToChild` — a SCALA-level API, NOT a model tool; it inherits the typed
  * mailbox errors (`UnknownJob`, `Full`, `TerminalJob`) verbatim. The child
  * applies steering at TURN boundaries only — see [[childTurns]] for the exact
  * application semantics; an in-flight tool call is never modified (the Mailbox
  * Draining window guarantees the boundary). A terminal watcher seals the
  * mailbox with the child's OBSERVED terminal status; later sends are the typed
  * TerminalJob refusal, and anything acked but never applied stays retrievable
  * by a final drain through the [[childMailbox]] accessor — an ack is never
  * silently converted into a loss.
  *
  * RAI-063 out-of-band cancel (OOB): `cancel_agent` above is the MODEL-tool
  * path — the parent needs its own model round to issue it, which is
  * unreachable when a parked child holds the only model slot (finding
  * AgentLoopDelegationSpec:389-393: at maxLLM=1 the parent can never get a slot
  * to even send cancel_agent). `cancelOutOfBand` / `cancelAllOutOfBand` are the
  * REPL/UI/OWNER channel: they call `jobs.cancelAny` DIRECTLY, with no
  * model-tool round and no model admission — cancel must be reachable OUTSIDE
  * model admission. `cancelInvoke` semantics stay unchanged.
  *
  * RAI-063 child deadlines: `setChildDeadline` (and the constructor default)
  * schedule owner-side watchdogs that fire OUTSIDE model admission and cancel
  * queued+running children; cleanup is bounded by a grace (RaiderScope.close
  * pattern: a child stuck in an uninterruptible region is reported Uncertain,
  * never a fake Cancelled). Deadlines are a SCALA-level API on purpose: the
  * delegate tool's argumentsJson (public tool schema) is unchanged.
  *
  * Event seam (RAI-059, ADR-024 §2.6): an optional [[DelegationEvents]] context
  * (hub + root) publishes `Delegated(childJobId, agent)` on fork and the
  * ChildAwaited-equivalent `Diagnostics` on observed child completion. `None` =
  * zero overhead; emission never alters the tool feedback JSON.
  *
  * RAI-073 Delegated/Terminal race: `jobs.start` forks the child before its
  * handle is known, so an instantly-completing child can settle (the JobManager
  * listener publishes `Terminal` under the child's id) BEFORE the post-start
  * `Delegated` emission — and the hub reducer silently refuses any event after
  * a terminal snapshot (Malformed, no sequence). Fix, entirely on the delegate
  * side: the child task parks on a per-fork gate before returning, the gate is
  * released only AFTER the `Delegated` publish attempt (and on ANY exit of the
  * invocation, via a scope finalizer, so an orphaned child always settles). The
  * child's own stream is therefore always
  * `… Delegated(seq n) → Terminal(seq n+1)`.
  */
final class DelegationToolset private (
    jobs: JobManager,
    backend: ModelBackend,
    admission: Admission,
    limits: BudgetLimits,
    baseRegistry: ToolRegistry,
    allowedAgents: Set[String],
    root: RootId,
    defaultChildDeadline: Option[Duration],
    deadlineGrace: Duration,
    delegationEvents: Option[DelegationEvents] = None,
    mailboxCapacity: Int = DelegationToolset.DefaultMailboxCapacity
):

  /** RAI-059: failure-absorbing child-completion emission (see
    * [[DelegationEvents]]); `None` = `ZIO.unit`.
    */
  private def emitChildCompleted(
      childId: JobId,
      status: String,
      detail: Option[String]
  ): UIO[Unit] =
    delegationEvents match
      case Some(ev) => ev.childCompleted(childId, status, detail)
      case None     => ZIO.unit

  private val children = new ConcurrentHashMap[String, JobHandle[String]]()
  private val spawned = new AtomicInteger(0)

  private val childTraces =
    new ConcurrentHashMap[String, zio.Ref[Vector[
      raider.core.trace.TraceEvent
    ]]]()

  // RAI-063: per-child deadline watchdogs (owner-side, outside admission);
  // the promise is fulfilled ONCE by the latest watchdog for that child
  private val watchdogs =
    new ConcurrentHashMap[String, Fiber.Runtime[Nothing, Unit]]()

  private val deadlineWatchers =
    new ConcurrentHashMap[
      String,
      Promise[Nothing, DelegationToolset.DeadlineOutcome]
    ]()

  // RAI-074: per-child steering mailboxes (accepted RAI-065 API), registered
  // at fork; the OWNER/REPL channel writes via sendToChild, the child's turn
  // loop drains strictly at turn boundaries.
  private val mailboxes = new ConcurrentHashMap[String, Mailbox]()

  // RAI-074: fulfilled by the terminal watcher AFTER the mailbox is sealed —
  // the deterministic await point for tests/admin (after it resolves, sends
  // are TerminalJob refusals and a final drain is stable).
  private val mailboxSealed =
    new ConcurrentHashMap[String, Promise[Nothing, Unit]]()

  private def fieldJson(k: String, v: String): String = s""""$k":${v.toJson}"""

  private def feedback(pairs: String*): ZIO[Any, RaiderError, String] =
    ZIO.succeed(pairs.mkString("{", ",", "}"))

  private def delegateInvoke(
      argumentsJson: String
  ): ZIO[Scope, RaiderError, String] =
    argumentsJson.fromJson[DelegationToolset.DelegateArgs] match
      case Left(err) =>
        feedback(
          fieldJson("error", "invalid_arguments"),
          fieldJson("detail", err.take(80))
        )
      case Right(args) if !allowedAgents.contains(args.agent) =>
        feedback(
          fieldJson("error", "unknown_agent"),
          fieldJson("name", args.agent),
          fieldJson("known", allowedAgents.toVector.sorted.mkString(","))
        )
      case Right(args) =>
        val n = spawned.incrementAndGet()
        if n > limits.maxChildren then
          feedback(
            fieldJson("error", "max_children"),
            "\"limit\":" + limits.maxChildren.toString
          )
        else
          for
            // RAI-073: the child parks on this gate before its task returns,
            // so its settle (Terminal) cannot outrun the Delegated emission
            // below. Interruptibly — cancel/deadline paths are unchanged.
            gate <- Promise.make[Nothing, Unit]
            // If this invocation dies before the explicit release (any
            // interruption), the parked child must still settle on its own.
            _ <- ZIO.addFinalizer(gate.succeed(()).unit)
            // RAI-074: the child task may start before this fork site knows
            // the job id, so its mailbox is published through this promise;
            // the task parks on it and cannot drain before registration.
            mailboxReady <- Promise.make[Nothing, Mailbox]
            traceRef <- zio.Ref.make(Vector.empty[raider.core.trace.TraceEvent])
            childLoop = AgentLoop(
              backend,
              baseRegistry,
              s"scripted:${args.agent}",
              admission,
              root,
              raider.runtime.budget.CostEstimate.Unknown,
              ev => traceRef.update(_ :+ ev)
            )
            handle <- jobs.start(
              root,
              s"delegate:${args.agent}",
              Task(
                mailboxReady.await
                  .flatMap(mbx =>
                    childTurns(
                      childLoop,
                      mbx,
                      List(RequestMessage("user", args.input)),
                      limits,
                      limits.maxAttempts
                    )
                  )
                  // hold the task's completion until Delegated is emitted
                  .flatMap(result => gate.await.as(result))
              )
            )
            // RAI-074: the handle-to-registration stretch is uninterruptible,
            // so there is NO window where a started child could wait forever
            // for its mailbox (an interrupt here is delivered after this
            // block; the gate finalizer still settles the child).
            _ <- ZIO.uninterruptible {
              for
                mbx <- Mailbox.make(handle.id, mailboxCapacity)
                sealedP <- Promise.make[Nothing, Unit]
                _ <- ZIO.succeed(children.put(handle.id.value, handle))
                _ <- ZIO.succeed(childTraces.put(handle.id.value, traceRef))
                _ <- ZIO.succeed(mailboxes.put(handle.id.value, mbx))
                _ <- ZIO.succeed(mailboxSealed.put(handle.id.value, sealedP))
                _ <- mailboxReady.succeed(mbx).unit
                // terminal watcher: seals the mailbox when the job settles
                _ <- mailboxCloser(handle, mbx, sealedP).forkDaemon
              yield ()
            }
            // RAI-073 ordering point: the deadline setup and the Delegated
            // publish attempt complete BEFORE the gate releases the child;
            // `ensuring` releases it on every exit (publish errors are
            // absorbed inside DelegationEvents, feedback JSON unchanged).
            _ <- (defaultChildDeadline match
              case Some(d) =>
                setChildDeadline(handle.id.value, d).unit
              case None => ZIO.unit
            ) *>
              (delegationEvents match
                case Some(ev) => ev.delegated(handle.id, args.agent)
                case None     => ZIO.unit
              ).ensuring(gate.succeed(()).unit)
          yield s"""{"child_id":${handle.id.value.toJson},"state":"Running"}"""

  private def awaitInvoke(
      argumentsJson: String
  ): ZIO[Scope, RaiderError, String] =
    argumentsJson.fromJson[DelegationToolset.ChildArgs] match
      case Left(err) =>
        feedback(
          fieldJson("error", "invalid_arguments"),
          fieldJson("detail", err.take(80))
        )
      case Right(args) =>
        Option(children.get(args.child_id)) match
          case None =>
            feedback(
              fieldJson("error", "unknown_child"),
              fieldJson("child_id", args.child_id)
            )
          case Some(handle) =>
            // suspends the parent loop; holds NO model slot (§11) — the tool
            // slot is held for the duration, hence maxConcurrentTools >= 2
            jobs.await(handle).either.flatMap {
              case Right(result) =>
                // RAI-059: observed child completion (ChildAwaited equivalent)
                emitChildCompleted(handle.id, "Succeeded", None) *>
                  ZIO.succeed(
                    s"""{"child_id":${args.child_id.toJson},""" +
                      s""""status":"Succeeded","result":${result.toJson}}"""
                  )
              case Left(e) =>
                emitChildCompleted(handle.id, "Failed", Some(e.code)) *>
                  ZIO.succeed(
                    s"""{"child_id":${args.child_id.toJson},""" +
                      s""""status":"Failed","error":{"code":${e.code.toJson},""" +
                      s""""detail":${e.detail.toJson}}}"""
                  )
            }

  private def cancelInvoke(
      argumentsJson: String
  ): ZIO[Scope, RaiderError, String] =
    argumentsJson.fromJson[DelegationToolset.ChildArgs] match
      case Left(err) =>
        feedback(
          fieldJson("error", "invalid_arguments"),
          fieldJson("detail", err.take(80))
        )
      case Right(args) =>
        Option(children.get(args.child_id)) match
          case None =>
            feedback(
              fieldJson("error", "unknown_child"),
              fieldJson("child_id", args.child_id)
            )
          case Some(handle) =>
            jobs
              .cancelAny(handle)
              .map(c =>
                s"""{"child_id":${args.child_id.toJson},"cancelled":$c}"""
              )

  private def listInvoke: ZIO[Scope, RaiderError, String] =
    ZIO
      .foreach(children.values.asScala.toList) { h =>
        jobs
          .snapshotAny(h)
          .map(s =>
            s"""{"child_id":${s.id.value.toJson},"status":"${s.status}"}"""
          )
      }
      .map(list => s"""{"agents":${list.mkString("[", ",", "]")}}""")

  private def toolOf(
      toolName: String,
      descr: String,
      run: String => ZIO[Scope, RaiderError, String]
  ): Tool =
    new Tool:
      def name = toolName
      def version = 1
      def description = descr
      def recovery = RecoveryClass.ReadOnly
      def timeoutMs = 600000L // await_agent legitimately parks long
      def capabilities = ToolCapabilities(concurrentSafe = false)
      def invoke(argumentsJson: String) = run(argumentsJson)

  private val delegateT = toolOf(
    "delegate",
    "start a child agent; args {\"agent\",\"input\"}",
    delegateInvoke
  )

  private val awaitT =
    toolOf("await_agent", "wait for a child; args {\"child_id\"}", awaitInvoke)

  private val cancelT =
    toolOf("cancel_agent", "cancel a child; args {\"child_id\"}", cancelInvoke)

  private val listT = toolOf(
    "list_agents",
    "list child agents and statuses; args {}",
    _ => listInvoke
  )

  /** The parent loop's registry: base tools + delegation tools. */
  def augment(base: ToolRegistry): Either[RaiderError, ToolRegistry] =
    ToolRegistry.build(
      base.tools.values.toList ++ List(delegateT, awaitT, cancelT, listT)
    )

  /** Child snapshot ids (admin/tests). */
  def childIds: List[String] =
    children.keys().asScala.toList.sorted

  /** Full trace of a child (for :log / transcript output). */
  def childTrace(
      childId: String
  ): ZIO[Any, Nothing, Option[List[raider.core.trace.TraceEvent]]] =
    Option(childTraces.get(childId)) match
      case Some(ref) => ref.get.map(v => Some(v.toList))
      case None      => ZIO.none

  /** Settle wait for admin/tests: waits until the child job is terminal. */
  def awaitSettled(childId: String): ZIO[Any, Nothing, Unit] =
    Option(children.get(childId)) match
      case None    => ZIO.unit
      case Some(h) => jobs.awaitSettled(h)

  // -- RAI-063: out-of-band owner channel (no model-tool round) --------------

  /** Out-of-band cancel (RAI-063): cancels the child DIRECTLY via
    * `jobs.cancelAny` — no model admission, no model-tool round, no second
    * slot. This is the REPL/UI/owner channel that stays reachable when a parked
    * child holds the only model slot (finding AgentLoopDelegationSpec:389-393).
    * True when cancellation was requested; false for an unknown child or an
    * already-terminal child. The model-tool `cancel_agent` semantics are
    * unchanged.
    */
  def cancelOutOfBand(childId: String): UIO[Boolean] =
    Option(children.get(childId)) match
      case None    => ZIO.succeed(false)
      case Some(h) => jobs.cancelAny(h)

  /** Out-of-band cancel of every live child (RAI-063); returns the ids whose
    * cancellation was accepted (order: registry iteration order).
    */
  def cancelAllOutOfBand: UIO[List[String]] =
    ZIO.foldLeft(children.values.asScala.toList)(List.empty[String]) {
      (acc, h) => jobs.cancelAny(h).map(if _ then acc :+ h.id.value else acc)
    }

  // -- RAI-063: per-child deadlines (Scala-level; tool schema unchanged) -----

  /** Schedule an owner-side deadline for one child: at `deadline` from NOW a
    * watchdog cancels the child out-of-band (queued or running) and waits for
    * the terminal outcome within the bounded [[deadlineGrace]]. A repeated call
    * REPLACES the previous watchdog for that child. False for an unknown child.
    * The public delegate tool schema is intentionally unchanged.
    */
  def setChildDeadline(childId: String, deadline: Duration): UIO[Boolean] =
    Option(children.get(childId)) match
      case None => ZIO.succeed(false)
      case Some(h) =>
        for
          wp <- Promise.make[Nothing, DelegationToolset.DeadlineOutcome]
          _ <- ZIO.succeed(deadlineWatchers.put(childId, wp))
          prev <- ZIO.succeed(Option(watchdogs.remove(childId)))
          _ <- prev match
            case Some(old) => old.interrupt.unit
            case None      => ZIO.unit
          wd <- watchdog(h, deadline).forkDaemon
          _ <- ZIO.succeed(watchdogs.put(childId, wd))
        yield true

  /** Honest outcome of a fired deadline (RAI-063), non-blocking snapshot:
    * `None` = not fired yet (or no deadline set / unknown child); `Settled` =
    * cancelled and terminal within the grace; `Uncertain` = not terminal within
    * the grace (unobservable — reported as uncertain, never a fake Cancelled);
    * `AlreadyTerminal` = deadline fired after the child had finished. `None` is
    * also left when the deadline raced another canceller (that canceller owns
    * the outcome).
    */
  def deadlineOutcome(
      childId: String
  ): UIO[Option[DelegationToolset.DeadlineOutcome]] =
    Option(deadlineWatchers.get(childId)) match
      case None => ZIO.none
      case Some(p) =>
        p.poll.flatMap {
          case None      => ZIO.none
          case Some(res) => res.map(Some(_))
        }

  /** Blocking wait for the LATEST deadline watchdog of a child to fire
    * (deterministic, no polling); `None` for an unknown child / no deadline.
    * Bound the wait at the call site — a watchdog only fulfils when its
    * deadline expires.
    */
  def awaitDeadlineOutcome(
      childId: String
  ): UIO[Option[DelegationToolset.DeadlineOutcome]] =
    Option(deadlineWatchers.get(childId)) match
      case None    => ZIO.none
      case Some(p) => p.await.map(Some(_))

  /** One deadline watchdog: sleep, then cancel OOB and settle within the
    * bounded grace (RaiderScope.close pattern — the WHOLE cancel+settle is
    * bounded, because `fiber.interrupt` itself blocks on a child stuck in an
    * uninterruptible region; such a child is reported Uncertain, never a fake
    * Cancelled, and cannot hold the watchdog hostage).
    */
  private def watchdog(h: JobHandle[?], deadline: Duration): UIO[Unit] =
    ZIO.sleep(deadline) *>
      jobs
        .cancelAny(h)
        .timeout(deadlineGrace)
        .flatMap {
          // cancel request itself did not complete within the grace: the
          // child may or may not die later — outcome honestly uncertain
          case None =>
            classify(h, DelegationToolset.DeadlineOutcome.Uncertain)
          case Some(false) =>
            // cancelAny refused: classify by what we can OBSERVE
            jobs.snapshotAny(h).flatMap { s =>
              if JobStatus.terminal(s.status) then
                classify(
                  h.id.value,
                  DelegationToolset.DeadlineOutcome.AlreadyTerminal
                )
              else
                // raced another canceller mid-Cancelling: it owns the outcome
                ZIO.unit
            }
          case Some(true) =>
            jobs.awaitSettled(h).timeout(deadlineGrace).flatMap {
              case Some(_) =>
                classify(h.id.value, DelegationToolset.DeadlineOutcome.Settled)
              case None =>
                classify(h, DelegationToolset.DeadlineOutcome.Uncertain)
            }
        }

  /** Record the deadline outcome unless the child JUST reached a terminal
    * status between the grace timeout and this check (benign race — then it
    * settled and Settled is the honest report).
    */
  private def classify(
      h: JobHandle[?],
      onUnobservable: DelegationToolset.DeadlineOutcome
  ): UIO[Unit] =
    jobs.snapshotAny(h).flatMap { s =>
      if JobStatus.terminal(s.status) then
        classify(h.id.value, DelegationToolset.DeadlineOutcome.Settled)
      else classify(h.id.value, onUnobservable)
    }

  private def classify(
      childId: String,
      outcome: DelegationToolset.DeadlineOutcome
  ): UIO[Unit] =
    Option(deadlineWatchers.get(childId)) match
      case None    => ZIO.unit
      case Some(p) => p.succeed(outcome).unit

  // -- RAI-074: steering mailbox wiring (owner channel + turn boundaries) ----

  /** The child's steering-aware turn loop — RAI-074 APPLICATION SEMANTICS (the
    * exact choice this wiring card makes):
    *
    *   1. A child's execution is a sequence of TURNS. Turn 1 runs the delegated
    *      input; turn N>1 carries the conversation forward verbatim (prior
    *      messages + the previous turn's final text as an assistant message) —
    *      one [[AgentLoop.runText]] per turn, no second runtime.
    *   2. At EVERY turn boundary — before a turn starts and right after the
    *      previous runText definitively returns (never mid-run, never while a
    *      tool call is in flight) — the child's mailbox is drained; a non-empty
    *      batch is appended as ONE extra user message
    *      `owner steering:\n- [<msgId>] <text>…` (arrival order, ids visible)
    *      before the next runText. Messages sent while a turn is in flight land
    *      at the NEXT boundary (the Mailbox Draining window guarantees a second
    *      drain never steals next-turn messages).
    *   3. The child settles at the first boundary whose drain is empty (or on a
    *      definitive error, leaving the sealed mailbox to the watcher).
    */
  private def childTurns(
      childLoop: AgentLoop,
      mbx: Mailbox,
      initial: List[RequestMessage],
      ceilings: BudgetLimits,
      maxRounds: Int
  ): ZIO[Any, RaiderError, String] =

    /** The `owner steering:` section: ONE user message per boundary, one
      * `- [msgId] text` line per drained message in arrival order.
      */
    def steeringSection(drained: Chunk[Message]): List[RequestMessage] =
      if drained.isEmpty then Nil
      else
        val body = drained
          .map(m => s"- [${m.id.value}] ${m.content}")
          .mkString("owner steering:\n", "\n", "")
        List(RequestMessage("user", body))

    /** One turn: apply the ALREADY-DRAINED batch (taken by the caller at the
      * boundary) as the steering section, run, then close the window and take
      * what queued for the next turn — an empty queue settles the child, a
      * non-empty one carries the conversation forward verbatim plus the fresh
      * batch (never re-drained, never re-applied).
      */
    def runTurn(
        conversation: List[RequestMessage],
        drained: Chunk[Message]
    ): ZIO[Any, RaiderError, String] =
      val withSteering = conversation ++ steeringSection(drained)
      childLoop
        .runText(withSteering, ceilings, maxRounds)
        .flatMap { finalText =>
          // close the boundary window, then take what queued for the NEXT
          // turn while this one ran
          mbx.endTurn *>
            mbx.drainOnTurnBoundary.flatMap { queued =>
              if queued.isEmpty then ZIO.succeed(finalText)
              else
                val carried = withSteering :+ RequestMessage(
                  AgentLoop.AssistantRole,
                  finalText
                )
                runTurn(carried, queued)
            }
        }

    mbx.drainOnTurnBoundary.flatMap(drained => runTurn(initial, drained))

  /** RAI-074: seals the child mailbox when the job settles — the OBSERVED
    * terminal status is recorded (close is idempotent, first terminal wins),
    * then the per-child sealed signal is fulfilled. Afterwards `sendToChild` is
    * the typed TerminalJob refusal and a final drain through [[childMailbox]]
    * is stable (acks before the seal stay retrievable — an ack is never
    * silently converted into a loss).
    */
  private def mailboxCloser(
      h: JobHandle[?],
      mbx: Mailbox,
      sealedP: Promise[Nothing, Unit]
  ): UIO[Unit] =
    jobs.awaitSettled(h) *>
      jobs.snapshotAny(h).flatMap { snap =>
        snap.status match
          case t: JobStatus.Terminal => mbx.close(t)
          // unreachable: awaitSettled resolves only once a terminal outcome
          // is published — stay total, leave the mailbox open if ever not
          case _ => ZIO.unit
      } *> sealedP.succeed(()).unit

  /** RAI-074 owner/REPL steering channel — NOT a model tool: the parent's model
    * never sees this API; the REPL/UI/delegating owner does. Exactly-once per
    * `msgId` for the mailbox lifetime (a re-send is an idempotent ack); the ack
    * means ENQUEUED — application happens at a turn boundary (see
    * [[childTurns]]). Typed refusals: [[MailboxError.UnknownJob]] (no such
    * child mailbox), [[MailboxError.Full]] (bounded overflow), and
    * [[MailboxError.TerminalJob]] (child settled — start a new delegation) once
    * the terminal watcher sealed the mailbox.
    */
  def sendToChild(
      childId: String,
      msgId: String,
      text: String
  ): IO[MailboxError, Ack] =
    Option(mailboxes.get(childId)) match
      case None      => ZIO.fail(MailboxError.UnknownJob(JobId(childId)))
      case Some(mbx) => mbx.send(Message(MessageId(msgId), text))

  /** RAI-074 admin/test accessor: the child's mailbox (`None` = unknown child)
    * — observe `pendingCount`/`state` and final-drain conservation accounting
    * after the mailbox was sealed.
    */
  def childMailbox(childId: String): Option[Mailbox] =
    Option(mailboxes.get(childId))

  /** Deterministic wait until the terminal watcher SEALED the child's mailbox
    * (job settled, close committed). Unknown child: returns immediately.
    */
  def awaitChildMailboxSealed(childId: String): UIO[Unit] =
    Option(mailboxSealed.get(childId)) match
      case None       => ZIO.unit
      case Some(prom) => prom.await.unit

object DelegationToolset:

  final case class DelegateArgs(agent: String, input: String)

  object DelegateArgs:
    given zio.json.JsonDecoder[DelegateArgs] = zio.json.DeriveJsonDecoder.gen

  final case class ChildArgs(child_id: String)

  object ChildArgs:
    given zio.json.JsonDecoder[ChildArgs] = zio.json.DeriveJsonDecoder.gen

  val ToolNames: List[String] =
    List("delegate", "await_agent", "cancel_agent", "list_agents")

  /** RAI-074: default per-child steering-mailbox capacity. */
  val DefaultMailboxCapacity: Int = 32

  /** Honest result of a fired child deadline (RAI-063). See
    * [[DelegationToolset.deadlineOutcome]].
    */
  enum DeadlineOutcome:
    case Settled, Uncertain, AlreadyTerminal

  /** Validated construction (BUD-03 discipline): awaiting parents hold one tool
    * slot, so a tool-using child needs a second slot; the base registry must
    * not shadow delegation tool names.
    *
    * RAI-063: `defaultChildDeadline` — when set, EVERY spawned child gets a
    * deadline watchdog from spawn (Scala-level API; the delegate tool's public
    * argumentsJson schema is unchanged); `deadlineGrace` bounds the
    * cancel+settle cleanup on deadline expiry (uncertain past it, never a fake
    * Cancelled).
    *
    * RAI-074: `mailboxCapacity` is the bound of every forked child's steering
    * mailbox; it must be > 0 (typed Configuration refusal — overflow is
    * otherwise the child's runtime [[MailboxError.Full]]).
    */
  def make(
      jobs: JobManager,
      backend: ModelBackend,
      admission: Admission,
      limits: BudgetLimits,
      baseRegistry: ToolRegistry,
      allowedAgents: Set[String],
      root: RootId,
      defaultChildDeadline: Option[Duration] = None,
      deadlineGrace: Duration = Duration.fromSeconds(5),
      delegationEvents: Option[DelegationEvents] = None,
      mailboxCapacity: Int = DelegationToolset.DefaultMailboxCapacity
  ): Either[RaiderError, DelegationToolset] =
    if limits.maxConcurrentTools < 2 then
      Left(
        RaiderError.Configuration(
          "delegation needs maxConcurrentTools >= 2: the awaiting parent " +
            "holds one tool slot while children run (BUD-03 no-deadlock)"
        )
      )
    else if deadlineGrace.toNanos <= 0L then
      Left(
        RaiderError.Configuration(
          s"deadlineGrace must be > 0, got $deadlineGrace"
        )
      )
    else if mailboxCapacity <= 0 then
      Left(
        RaiderError.Configuration(
          s"mailboxCapacity must be > 0, got $mailboxCapacity"
        )
      )
    else
      val clash = ToolNames.filter(n => baseRegistry.lookup(n).isDefined)
      if clash.nonEmpty then
        Left(
          RaiderError.Configuration(
            s"base registry must not define delegation tools: " +
              clash.mkString(", ")
          )
        )
      else
        Right(
          new DelegationToolset(
            jobs,
            backend,
            admission,
            limits,
            baseRegistry,
            allowedAgents,
            root,
            defaultChildDeadline,
            deadlineGrace,
            delegationEvents,
            mailboxCapacity
          )
        )

end DelegationToolset
