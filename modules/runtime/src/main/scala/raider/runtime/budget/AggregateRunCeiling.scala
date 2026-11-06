package raider.runtime.budget

import raider.core.{
  ModelBackend,
  ModelCapabilities,
  ModelEvent,
  ModelRequest,
  RaiderError,
  Usage
}
import zio.{Duration, Scope, UIO, ZIO}
import zio.stream.ZStream

import java.util.concurrent.CompletableFuture
import scala.collection.mutable.ArrayBuffer

/** One run's aggregate ceilings (RAI-258): the run-level token, dispatch and
  * wall-clock limits that bind the root loop AND every delegated child jointly,
  * enforced by wrapping each `ModelBackend` of the run.
  *
  * Discipline (honest accounting, mirroring TokenBudgetBackend):
  *   - every admitted dispatch gets exactly one per-actor entry;
  *   - usage is folded from the wire's `UsageObserved` events (currency-neutral
  *     input+output sums via plain long adds with `Math.addExact` overflow
  *     guards);
  *   - an in-flight attempt whose usage arrives past a token ceiling COMPLETES
  *     (the spend already happened; failing it mid-flight would lie about what
  *     was paid) — the FIRST dispatch past the ceiling is the one refused
  *     before any wire call;
  *   - a tripped ceiling refuses every subsequent admission with a typed error
  *     (`ProviderBudget` for dispatch/token, `DeadlineExceeded` for the run
  *     deadline — the frozen §8 exit mapping turns these into 23/24).
  *
  * Trip notification is a JDK `CompletableFuture` (no scheduler, no second
  * runtime): the delegation layer races `awaitTripped` against the remaining
  * deadline to cancel live children out-of-band. First trip wins; `stop` is
  * idempotent. This object is process-local and must be shared by the root
  * wrapper and every child wrapper of ONE run.
  */
object AggregateRunCeiling:

  /** The ceilings of one run; `None` disables a dimension. */
  final case class Config(
      maxDispatches: Option[Int],
      maxTotalTokens: Option[Long],
      deadline: Option[Duration]
  )

  /** Why the run stopped accepting dispatches. */
  sealed trait CeilingTrip:
    def detail: String

  object CeilingTrip:

    final case class DispatchCap(cap: Int) extends CeilingTrip:

      val detail: String =
        s"aggregate dispatch cap of $cap reached; further model dispatch is refused"

    final case class TokenCap(cap: Long, observed: Long) extends CeilingTrip:

      val detail: String =
        s"aggregate token ceiling of $cap exceeded (observed $observed); " +
          "further model dispatch is refused"

    final case class Deadline(limit: Duration) extends CeilingTrip:

      val detail: String =
        s"aggregate run deadline of ${limit.toMillis / 1000}s elapsed; " +
          "further model dispatch is refused"

  /** Per-actor view (root = "root", children = "<agent>#<n>"). */
  final case class ActorUsage(
      actor: String,
      dispatches: Int,
      inputTokens: Long,
      outputTokens: Long
  )

  final case class EntryView(
      actor: String,
      dispatches: Int,
      inputTokens: Long,
      outputTokens: Long,
      settled: Option[String]
  )

  final case class Snapshot(
      config: Config,
      elapsed: Duration,
      dispatches: Int,
      totalTokens: Usage,
      byActor: Vector[ActorUsage],
      entries: Vector[EntryView],
      tripped: Option[CeilingTrip]
  )

  /** Validates and builds the ceiling object. Negative or zero ceilings are
    * typed configuration refusals (flag-named so a CLI caller sees the flag).
    */
  def make(
      maxDispatches: Option[Int],
      maxTotalTokens: Option[Long],
      deadline: Option[Duration]
  ): Either[RaiderError, AggregateRunCeiling] =
    if maxDispatches.exists(_ <= 0) then
      Left(
        RaiderError.Configuration(
          "--max-dispatches must be a positive number of dispatches"
        )
      )
    else if maxTotalTokens.exists(_ <= 0) then
      Left(
        RaiderError.Configuration(
          "--max-tokens must be a positive token total"
        )
      )
    else if deadline.exists(_.toNanos <= 0) then
      Left(
        RaiderError.Configuration(
          "--run-deadline must be a positive number of seconds"
        )
      )
    else
      Right(
        new AggregateRunCeiling(
          Config(maxDispatches, maxTotalTokens, deadline),
          System.nanoTime()
        )
      )

end AggregateRunCeiling

final class AggregateRunCeiling private[budget] (
    config: AggregateRunCeiling.Config,
    startedAtNanos: Long
):
  import AggregateRunCeiling.*

  private var dispatches = 0
  private var totalInput = 0L
  private var totalOutput = 0L
  private var tripped: Option[CeilingTrip] = None
  private val entries = ArrayBuffer.empty[(String, Entry)]
  private val tripFuture = new CompletableFuture[CeilingTrip]()

  private def committed: Long = Math.addExact(totalInput, totalOutput)

  private final case class Entry(
      dispatches: Int,
      inputTokens: Long,
      outputTokens: Long,
      settled: Option[String]
  )

  private def elapsedNanos: Long = System.nanoTime() - startedAtNanos

  private def entryOf(actor: String): Option[Entry] =
    entries.collectFirst { case (a, e) if a == actor => e }

  private def updateEntry(actor: String)(f: Entry => Entry): Unit =
    val idx = entries.indexWhere(_._1 == actor)
    if idx >= 0 then
      val (a, e) = entries(idx)
      entries(idx) = (a, f(e))

  private def denial(t: CeilingTrip): RaiderError = t match
    case _: CeilingTrip.Deadline => RaiderError.DeadlineExceeded(t.detail)
    case _                       => RaiderError.ProviderBudget(t.detail)

  private def tripNow(t: CeilingTrip): Unit =
    if tripped.isEmpty then
      tripped = Some(t)
      // complete() only wakes waiters; it never re-enters this object.
      tripFuture.complete(t)
      ()

  /** Admits one model dispatch for `actor` or refuses it with the typed ceiling
    * error. Admissions and trip checks are atomic under one lock, so concurrent
    * parent/child admissions cannot overshoot a cap.
    */
  def admit(actor: String): Either[RaiderError, Unit] = synchronized:
    tripped match
      case Some(t) => Left(denial(t))
      case None =>
        config.deadline match
          case Some(d) if elapsedNanos >= d.toNanos =>
            val t: CeilingTrip = CeilingTrip.Deadline(d)
            tripNow(t)
            Left(denial(t))
          case _ =>
            config.maxDispatches match
              case Some(cap) if dispatches >= cap =>
                val t: CeilingTrip = CeilingTrip.DispatchCap(cap)
                tripNow(t)
                Left(denial(t))
              case _ =>
                config.maxTotalTokens match
                  case Some(cap) if committed > cap =>
                    val t: CeilingTrip = CeilingTrip.TokenCap(cap, committed)
                    tripNow(t)
                    Left(denial(t))
                  case _ =>
                    dispatches += 1
                    entryOf(actor) match
                      case Some(e) =>
                        updateEntry(actor)(e =>
                          e.copy(dispatches = e.dispatches + 1)
                        )
                      case None =>
                        entries += ((actor, Entry(1, 0L, 0L, None)))
                    Right(())

  /** Folds one wire usage report into the run ledger and per-actor entry. The
    * in-flight honesty rule: usage past the token ceiling still accumulates
    * (the spend happened); the ceiling trips so the NEXT dispatch is refused.
    */
  def observe(actor: String, usage: Usage): Unit = synchronized:
    totalInput = Math.addExact(totalInput, usage.inputTokens)
    totalOutput = Math.addExact(totalOutput, usage.outputTokens)
    updateEntry(actor): e =>
      e.copy(
        inputTokens = Math.addExact(e.inputTokens, usage.inputTokens),
        outputTokens = Math.addExact(e.outputTokens, usage.outputTokens)
      )
    if tripped.isEmpty then
      config.maxTotalTokens match
        case Some(cap) if committed > cap =>
          tripNow(CeilingTrip.TokenCap(cap, committed))
        case _ => ()

  /** Settles an admitted dispatch terminally. Idempotent first-wins: a
    * "provider-error" followed by a late "response" (or vice versa) keeps the
    * first outcome. Unknown actors (never admitted) are ignored.
    */
  def settle(actor: String, outcome: String): Unit = synchronized:
    if entryOf(actor).exists(_.settled.isEmpty) then
      updateEntry(actor)(e => e.copy(settled = Some(outcome)))

  /** Registers a trip (watchdog, cancel path, or an admission check). First
    * trip wins; later calls are no-ops.
    */
  def stop(t: CeilingTrip): Unit = synchronized:
    tripNow(t)

  /** Completes when any ceiling trips; never completes otherwise (callers race
    * it against their own timeout). Interruptible.
    */
  def awaitTripped: UIO[CeilingTrip] =
    ZIO.fromCompletionStage(tripFuture).orDie

  /** Time left before the deadline trips (None when no deadline or already
    * elapsed — the deadline check in `admit` catches the latter).
    */
  def remainingDeadline: Option[Duration] = config.deadline.map: d =>
    val remaining = d.toNanos - elapsedNanos
    if remaining <= 0 then Duration.Zero else Duration.fromNanos(remaining)

  def elapsed: Duration = Duration.fromNanos(elapsedNanos)

  /** The validated ceilings this run was built with (wiring/tests). */
  def runConfig: Config = config

  def trippedCeiling: Option[CeilingTrip] = synchronized(tripped)

  def snapshot: UIO[Snapshot] = ZIO.succeed(synchronized:
    Snapshot(
      config,
      elapsed,
      dispatches,
      Usage(totalInput, totalOutput, None),
      entries
        .map((a, e) =>
          ActorUsage(a, e.dispatches, e.inputTokens, e.outputTokens)
        )
        .toVector,
      entries
        .map((a, e) =>
          EntryView(a, e.dispatches, e.inputTokens, e.outputTokens, e.settled)
        )
        .toVector,
      tripped
    ))

  /** Wraps `underlying` so its dispatches count against this run's ceilings
    * under `actor`. Admission happens BEFORE any wire call; a refusal fails the
    * stream with the typed ceiling error. Each stream settles its entry exactly
    * once: "response" after the normal end-of-stream marker, "provider-error"
    * on stream failure.
    */
  def wired(underlying: ModelBackend, actor: String): ModelBackend =
    new ModelBackend:
      def capabilities: ModelCapabilities = underlying.capabilities

      def stream(
          input: ModelRequest
      ): ZStream[Scope, RaiderError, ModelEvent] =
        admit(actor) match
          case Left(err) => ZStream.fail(err)
          case Right(_) =>
            val marked: ZStream[Scope, RaiderError, Either[ModelEvent, Unit]] =
              underlying.stream(input).map(Left(_): Either[ModelEvent, Unit]) ++
                ZStream.succeed(Right(()): Either[ModelEvent, Unit])
            marked
              .onError(_ => ZIO.succeed(settle(actor, "provider-error")))
              .tap:
                case Left(ModelEvent.UsageObserved(_, usage, _)) =>
                  ZIO.succeed(observe(actor, usage))
                case Left(_)  => ZIO.unit
                case Right(_) => ZIO.succeed(settle(actor, "response"))
              .collect { case Left(ev) => ev }

end AggregateRunCeiling
