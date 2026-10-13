package raider.dsl.workflow

/** Supported Task Specification selection (ADR-033 §4, §5): a bounded,
  * immutable snapshot of the explicitly marked chat turns, the named task
  * parameters, the named trusted checks and the one bounded agent stage.
  *
  * This is the pure INPUT CONTRACT of the deterministic workflow renderer
  * (RAI-155.c): plain data, no cli dependency, no behavior. The chat-side
  * adapter that PRODUCES this value from a saved session is RAI-155.b's surface
  * (its turn ledger lands separately); this module only validates and renders
  * it. Anything outside the STS shape is a typed refusal at render time
  * (ADR-033 §8 R1/R3/R4), never an approximation.
  */
enum StsTurnKind:
  case Intent
  case Refinement
  case Ordinary

enum StsTurnStatus:
  case Completed
  case Failed
  case Interrupted
  case Unknown

/** One captured turn of the selection (ADR-033 §5.1 subset the renderer needs):
  * stable id, explicit kind/status marking and the bounded request text.
  * `Ordinary` turns are capture context and are NOT exportable structure — they
  * must not appear in an [[StsSelection]].
  */
final case class StsTurn(
    turnId: String,
    kind: StsTurnKind,
    status: StsTurnStatus,
    text: String
)

/** A named trusted check (ADR-033 §4.1): user-registered name plus a validated
  * argv list executed WITHOUT a shell. Model text can never add or alter a
  * check — checks enter the workflow only through this explicit list.
  */
final case class NamedCheck(name: String, argv: List[String])

/** A named task parameter that varies per run (ADR-033 §4.1): externalized to
  * the input file, never hard-coded into the generated source. The first slice
  * knows exactly two names: `targetPath` and `summary`.
  */
final case class StsParam(name: String, value: String)

/** The ONE sequential bounded agent stage (ADR-033 §4.1): no parallelism, no
  * delegation, no phases. `maxRounds` is the upper bound of model attempts; the
  * effective policy is re-checked at run time.
  */
final case class StsAgentStage(maxRounds: Int)

/** Non-secret origin references of the exporting session (ADR-033 §5.1
  * configRefs discipline): provider kind, model name and optional base URL.
  * Credential VALUES are forbidden here (R4); credentials stay env/helper
  * references resolved at run time.
  */
final case class StsOrigin(
    providerKind: String,
    model: String,
    baseUrl: Option[String]
)

/** The bounded immutable selection snapshot (ADR-033 §11.2): exactly one
  * completed intent turn, ordered completed refinement turns, the named task
  * params, a non-empty check list, one bounded agent stage and the origin
  * references.
  */
final case class StsSelection(
    sessionId: String,
    intent: StsTurn,
    refinements: List[StsTurn],
    taskParams: List[StsParam],
    checks: List[NamedCheck],
    agentStage: StsAgentStage,
    origin: StsOrigin
)
