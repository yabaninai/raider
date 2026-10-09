package raider.core.plugin

import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}

/** RAI-084 (HOOK-01..03): bounded execution of declared hook capabilities.
  *
  * `runHook` is a FLAT function: it always returns a plain [[HookOutcome]] —
  * the hook completed, it threw, or the millisecond bound expired — and never
  * leaks an exception from hook code. The default bound is
  * `HookRunner.DefaultTimeoutMs` (2 s); both the constructor bound and the
  * per-call bound must be > 0.
  *
  * Hook discovery is reflection over the `RaiderHook` interface: the candidate
  * instance must implement `raider.core.plugin.RaiderHook` (runtime interface
  * check, typed `NotAHook` refusal otherwise), and it is dispatched under the
  * declared name `RaiderHook.name` — a call under any other name is a typed
  * `Undeclared` refusal, never a silent invocation.
  *
  * Honesty boundaries (ADR-025 §1/§2.5):
  *   - the plugin classloader (ADR-025 §2.4) is a COMPATIBILITY boundary, NOT a
  *     security sandbox. Hook code runs with full process authority; this bound
  *     is an availability guard, NOT containment, and grants no isolation
  *     beyond "one thread per invocation".
  *   - the hook contract hands over exactly one value: the read-only
  *     `inputJson` string. No policy, budget or registry handles cross the SPI
  *     seam. (Nothing can stop a hook from reaching JVM statics itself — that
  *     is the no-sandbox statement above, not a gap in this runner.)
  *   - the bound NEVER uses the deprecated stop API (no async stop): on expiry
  *     the outcome is typed `TimedOut` and the worker gets one best-effort
  *     cooperative interrupt. A hook that ignores interruption (or spins
  *     without blocking) keeps its daemon thread until the JVM exits — the
  *     runner does not lie about that.
  *   - the output is the hook's own string, passed through verbatim; the runner
  *     never parses or validates it.
  *
  * `PluginError` is a frozen sealed family (ADR-025 §5) and is deliberately NOT
  * extended here: typed failures live in the [[HookOutcome]]/[[HookFailure]]
  * families of this file. For hosts that want to surface a hook failure through
  * the existing plugin error channel, [[HookOutcome.toPluginError]] constructs
  * the existing `PluginError.Malformed` case — it extends nothing.
  */
final class HookRunner(defaultTimeoutMs: Long = HookRunner.DefaultTimeoutMs):

  if defaultTimeoutMs <= 0 then
    throw IllegalArgumentException(
      s"HookRunner defaultTimeoutMs must be > 0, got $defaultTimeoutMs"
    )

  /** The bound every call without an explicit `timeoutMs` argument uses. */
  def defaultTimeout: Long = defaultTimeoutMs

  /** Run one hook invocation under a wall-clock bound (millisecond granularity,
    * REAL time — no virtual/test clocks).
    *
    * @param instance
    *   candidate object; only `RaiderHook` instances can run (typed refusal
    *   otherwise)
    * @param hookName
    *   the declared hook capability name to dispatch the instance under
    * @param inputJson
    *   read-only input; handed to the hook verbatim, never mutated or parsed
    * @param timeoutMs
    *   per-call bound in milliseconds, must be > 0 (typed `InvalidTimeout`
    *   refusal otherwise)
    */
  def runHook(
      instance: AnyRef,
      hookName: String,
      inputJson: String,
      timeoutMs: Long = defaultTimeoutMs
  ): HookOutcome =
    if timeoutMs <= 0 then
      HookOutcome.Failed(hookName, HookFailure.InvalidTimeout(timeoutMs))
    else
      instance match
        case hook: RaiderHook =>
          if hook.name != hookName then
            HookOutcome.Failed(hookName, HookFailure.Undeclared(hook.name))
          else invokeBounded(hook, hookName, inputJson, timeoutMs)
        case other =>
          HookOutcome.Failed(
            hookName,
            HookFailure.NotAHook(other.getClass.getName)
          )

  /** One dedicated daemon worker thread per invocation: outcomes cross a
    * capacity-1 handoff with a NON-blocking offer, so a late-finishing hook is
    * never blocked and no mutable state is shared between calls (HOOK-03
    * isolation is structural, not enforced).
    */
  private def invokeBounded(
      hook: RaiderHook,
      hookName: String,
      inputJson: String,
      timeoutMs: Long
  ): HookOutcome =
    val box = new ArrayBlockingQueue[Either[Throwable, String]](1)
    val worker =
      new Thread(workerBody(hook, inputJson, box), s"raider-hook-$hookName")
    worker.setDaemon(true)
    worker.start()
    try
      Option(box.poll(timeoutMs, TimeUnit.MILLISECONDS)) match
        case Some(Right(output)) => HookOutcome.Completed(hookName, output)
        case Some(Left(thrown))  =>
          // Errors thrown by hook code are typed too: never rethrown.
          HookOutcome.Failed(
            hookName,
            HookFailure.Threw(
              thrown.getClass.getName,
              Option(thrown.getMessage)
            )
          )
        case None =>
          worker.interrupt() // cooperative only; Thread.stop is never used
          HookOutcome.Failed(hookName, HookFailure.TimedOut(timeoutMs))
    catch
      case _: InterruptedException =>
        // The CALLER was interrupted while waiting: restore its flag, typed out.
        Thread.currentThread().interrupt()
        HookOutcome.Failed(hookName, HookFailure.Interrupted)

  /** The worker body: run the hook once and offer the attempt. EVERY Throwable
    * becomes a typed `Left` (including interruption caused by the runner's own
    * timeout interrupt — at that point the caller has already been given
    * `TimedOut`, so the late result is simply dropped).
    */
  private def workerBody(
      hook: RaiderHook,
      inputJson: String,
      box: ArrayBlockingQueue[Either[Throwable, String]]
  ): Runnable = () =>
    val attempt: Either[Throwable, String] =
      try Right(hook.apply(inputJson))
      catch case t: Throwable => Left(t)
    box.offer(attempt)
    ()

end HookRunner

object HookRunner:
  /** RAI-084 default per-invocation bound: 2000 ms. */
  val DefaultTimeoutMs: Long = 2000L

/** The result of one bounded hook invocation — its own sealed family (RAI-084).
  * `PluginError` stays frozen (ADR-025 §5); this is the typed alternative the
  * lane contract prescribes.
  */
enum HookOutcome:

  /** The hook name this outcome belongs to (dispatch name, always the requested
    * one — refusals keep the requested name, completions the hook's own).
    */
  def hook: String = this match
    case Completed(name, _) => name
    case Failed(name, _)    => name

  /** Best-effort projection into the existing (frozen) plugin error channel:
    * constructs `PluginError.Malformed`, extends nothing. Completions project
    * to `None` — they are not errors.
    */
  def toPluginError: Option[PluginError.Malformed] = this match
    case Failed(name, failure) =>
      Some(PluginError.Malformed(s"hook '$name': ${failure.detail}"))
    case Completed(_, _) => None

  /** The hook ran to completion; `outputJson` is its own string, verbatim. */
  case Completed(hookName: String, outputJson: String)

  /** The hook did not complete: typed reason in [[HookFailure]]. */
  case Failed(hookName: String, failure: HookFailure)

/** Typed hook failure reasons (RAI-084). The hook name rides on
  * [[HookOutcome.Failed]], so the reasons describe the failure itself.
  */
enum HookFailure:

  /** The bound expired: one cooperative interrupt was delivered; the worker is
    * never force-stopped via the deprecated stop API and may still be running
    * (honesty note above).
    */
  case TimedOut(boundedMs: Long)

  /** The hook threw; `typeName` is the JVM class name. Errors (not just
    * exceptions) are typed too — never rethrown, never swallowed.
    */
  case Threw(typeName: String, message: Option[String])

  /** The instance's declared `RaiderHook.name` differs from the dispatch name:
    * the hook was NOT invoked.
    */
  case Undeclared(declared: String)

  /** The instance does not implement `RaiderHook` (runtime interface check):
    * the hook was NOT invoked.
    */
  case NotAHook(actualType: String)

  /** The call-site bound was not positive. */
  case InvalidTimeout(givenMs: Long)

  /** The CALLER was interrupted while waiting for the bound. */
  case Interrupted

  def detail: String = this match
    case TimedOut(boundedMs) =>
      s"exceeded the ${boundedMs}ms bound (interrupt delivered; the worker is never force-stopped via the deprecated stop API and may still be running)"
    case Threw(typeName, message) =>
      message match
        case Some(m) => s"threw $typeName: $m"
        case None    => s"threw $typeName"
    case Undeclared(declared) =>
      s"instance declares hook '$declared', refusing to dispatch under another name"
    case NotAHook(actualType) =>
      s"instance of $actualType does not implement RaiderHook"
    case InvalidTimeout(givenMs) =>
      s"timeout must be > 0, got $givenMs"
    case Interrupted =>
      "caller interrupted while waiting for the hook"
