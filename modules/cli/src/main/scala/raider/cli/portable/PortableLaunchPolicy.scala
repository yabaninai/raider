package raider.cli.portable

import raider.core.RaiderError
import raider.dsl.portable.PortableLaunchMetadata
import raider.runtime.budget.BudgetLimits
import zio.Duration

/** The launch-layer intersection of the DOCUMENT's declared root ceilings with
  * the INVOCATION OWNER's policy (RAI-199; coordinator scope note for m2-p2:
  * root-limit intersection is enforced at the launch layer, never in the DSL
  * and never inside the opaque lowered program).
  *
  * Mapping (documented for the acceptance fixtures):
  *   - attempts = min(declared.attempts, invocation.attempts); floors at 1.
  *     Becomes BOTH the shared admission `maxAttempts` and the per-agent-loop
  *     `maxRounds` (every model round is a billable attempt; exhaustion is the
  *     typed RA-LBUDGET).
  *   - maxLLM = 1 always — the single dispatch discipline: every model call of
  *     every pinned trusted agent is admitted through ONE admission, so the
  *     launch layer truly bounds concurrent model dispatch (the lowered program
  *     is opaque and gets NO outer Model slot — an outer slot would hold the
  *     only LLM permit and deadlock the inner rounds).
  *   - tools = clamp(declared.parallelism, 1, 64) — the admitted tool-slot
  *     width; the lowerer additionally bounds map/join fan-out width.
  *   - children = clamp(max(1, declared.children), 1, 64). The declared 0 ("no
  *     fan-out") semantics are enforced by the LOWERER's map guard; the
  *     admission floor of 1 only reflects BudgetLimits.make's >= 1 domain.
  *   - tokens = declared.tokens — enforced by the launcher-owned
  *     [[TokenBudgetBackend]] envelope (no runtime token knob exists; usage is
  *     observed from the backend wire and the NEXT dispatch past the ceiling is
  *     refused typed RA-PBUDGET; an in-flight attempt already paid for
  *     completes honestly).
  *   - deadline = the OUTER bound is the minimum of the optional invocation
  *     `--deadline-s` and the document's DECLARED `deadlineMs`: the document is
  *     untrusted input, so its declared deadline gets launcher-side teeth (the
  *     lowered program may additionally carry its own internal deadline; the
  *     launch-layer bound fires independently of what the opaque program does).
  */
object PortableLaunchPolicy:

  /** Invocation-owner limits (flags of `raider workflow run --doc`). */
  final case class InvocationLimits(
      maxAttempts: Int,
      deadlineSeconds: Option[Long]
  )

  /** The effective launch: shared budget ceilings, the per-agent-loop round
    * bound, the optional outer deadline and the token ceiling for the wire
    * envelope.
    */
  final case class EffectiveLaunch(
      limits: BudgetLimits,
      maxRoundsPerAgent: Int,
      outerDeadline: Option[Duration],
      tokenCeiling: Long
  )

  def intersect(
      declared: PortableLaunchMetadata,
      invocation: InvocationLimits
  ): Either[RaiderError, EffectiveLaunch] =
    val attempts =
      math.max(1, math.min(declared.maxAttempts, invocation.maxAttempts))
    val tools = math.max(1, math.min(declared.maxParallelism, 64))
    val children = math.max(1, math.min(declared.maxChildren, 64))
    for limits <- BudgetLimits.make(
        maxConcurrentLlm = 1,
        maxConcurrentTools = tools,
        maxChildren = children,
        maxAttempts = attempts
      )
    yield EffectiveLaunch(
      limits,
      maxRoundsPerAgent = attempts,
      // The outer deadline: the minimum of the invocation's --deadline-s and
      // the DECLARED deadlineMs (both are launcher-side bounds; the declared
      // one must bite even without an explicit invocation flag). A non-positive
      // declared deadline is treated as "no declared bound".
      outerDeadline =
        val invocationBound =
          invocation.deadlineSeconds.filter(_ > 0).map(Duration.fromSeconds(_))
        val declaredBound =
          if declared.deadlineMs > 0 then
            Some(Duration.fromMillis(declared.deadlineMs))
          else None
        (invocationBound, declaredBound) match
          case (Some(a), Some(b)) =>
            Some(if a.toMillis <= b.toMillis then a else b)
          case (Some(a), None) => Some(a)
          case (None, b)       => b,
      tokenCeiling = declared.maxTokens
    )

end PortableLaunchPolicy
