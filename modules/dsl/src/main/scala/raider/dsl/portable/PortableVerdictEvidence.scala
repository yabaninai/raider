package raider.dsl.portable

import zio.json.ast.Json

/** Typed verdict-evidence origin for the portable launch route (RAI-212).
  *
  * A portable document can declare `verdict.from` with `trustedVerifier`
  * authority while the bindings target — the lowerer's actual ROOT output —
  * comes from an AGENT step, a join or a conditional. Several frozen corpus
  * fixtures are shaped exactly this way, and the validator accepts any declared
  * verdict whose authority is the literal `trustedVerifier` (authority is a
  * document string, never a runtime fact). The launcher's verdict discipline
  * therefore cannot be value-based alone: an agent whose output happens to
  * carry `{"status": "passed"}` would otherwise mint a `taskVerdict` claim the
  * trusted check never produced, and an agent could veto the process exit code
  * with `{"status": "failed"}`.
  *
  * This resolver classifies the STRUCTURE behind the lowerer's own output
  * target so the claim can be gated on evidence origin:
  *
  *   - [[TrustedCheck]]: the root output IS a trusted check's result — reached
  *     directly, through a sequence (its last child survives the fold), or
  *     through a conditional whose BOTH branches resolve to trusted checks. The
  *     only origin whose observed value may gate a verdict claim.
  *   - [[AgentStep]]: every evidence path ends at model-controlled agent output
  *     (agents, all-agent joins, agent conditionals, maps over agent
  *     templates). Permissive: the value can neither claim the verdict nor veto
  *     the run.
  *   - [[Mixed]]: combined or unattributable evidence — joins mixing agent and
  *     check members, conditionals whose branches differ in kind, aggregates of
  *     check results (map/join always wrap outputs in an array, so the root
  *     output is never ONE check's result), repair/checkpoint outputs
  *     (caller-supplied programs that are not the pinned checks), unresolved or
  *     cyclic references. Permissive, like agent evidence.
  *
  * The observed value refinement is recorded by [[record]]: when a NON-trusted
  * origin's root output asserts verdict-shaped evidence (a top-level string
  * `status`, exactly the field the trusted-check projector reads), the recorded
  * code becomes `contradictory` and the asserted status is kept VERBATIM — the
  * assertion contradicts the evidence origin and is auditable without ever
  * controlling the exit code.
  *
  * Pure structure over an ALREADY-VALIDATED document: zero dispatch, no
  * registry, no I/O. It mirrors the frozen lowerer's target precedence
  * (bindings first entry, then `verdict.from`) and step semantics without
  * altering them — RAI-198 rulings XE-LOWER-01..03 stay untouched.
  */
object PortableVerdictEvidence:

  /** The structural origin of the root output. */
  sealed trait Origin:
    /** The origin's own code (before value refinement). */
    def code: String

    /** The step ids on the resolved evidence path, root composition first
      * (possibly empty when the path is unattributable).
      */
    def stepIds: List[String]

  /** The root output IS a pinned trusted check's result: directly, through a
    * sequence (last child), or through a conditional whose both branches are
    * trusted checks. The only origin that may gate a verdict claim.
    */
  final case class TrustedCheck(stepIds: List[String]) extends Origin:
    val code = "trusted-check"

  /** Every evidence path ends at model-controlled agent output. Permissive:
    * never claims the verdict, never vetoes the exit code.
    */
  final case class AgentStep(stepIds: List[String]) extends Origin:
    val code = "agent-step"

  /** Combined or unattributable evidence: mixed compositions, aggregates of
    * check results, repair/checkpoint outputs, unresolved or cyclic references.
    * Permissive, like agent evidence.
    */
  final case class Mixed(stepIds: List[String]) extends Origin:
    val code = "mixed"

  /** One run's recorded evidence: the structural origin refined by the root
    * output's observed value.
    */
  final case class Recorded(origin: Origin, assertedStatus: Option[String]):

    /** The code recorded in run.json: a non-trusted origin whose value asserts
      * a verdict-shaped status is recorded as `contradictory` (the assertion
      * contradicts the evidence origin); a trusted-check origin keeps its own
      * code because its value IS the check's own status.
      */
    def code: String =
      origin match
        case _: TrustedCheck => origin.code
        case _ =>
          if assertedStatus.isDefined then "contradictory" else origin.code

    /** The ONLY condition under which the launcher may claim the trusted
      * verdict: the root output IS the trusted check's result AND its value
      * says passed.
      */
    def claimsTrustedVerdict: Boolean =
      origin match
        case _: TrustedCheck => assertedStatus.contains("passed")
        case _               => false

  /** Resolves the structural origin of the root-output target with the
    * lowerer's own precedence (bindings first entry, then `verdict.from`).
    * `None` = the document declares no output target at all (defensive: a
    * validated v1.0 document always declares one).
    */
  def originOf(document: Json): Option[Origin] =
    document match
      case obj: Json.Obj =>
        val outputs = field(obj, "outputs").collect { case o: Json.Obj => o }
        val bindings = outputs
          .flatMap(field(_, "bindings"))
          .collect { case b: Json.Obj => b }
        val verdictFrom = outputs
          .flatMap(field(_, "verdict"))
          .collect { case v: Json.Obj => v }
          .flatMap(string(_, "from"))
        val pointer = bindings
          .flatMap(_.fields.headOption)
          .collect { case (_, Json.Str(p)) => p }
          .orElse(verdictFrom)
        pointer.map { p =>
          val byId = stepsById(obj)
          stepIdFromPointer(p) match
            case Some(id) => resolveStep(id, byId, Set.empty)
            case None     => Mixed(Nil)
        }
      case _ => None

  /** Records one run's evidence: the structural origin plus the verdict-shaped
    * status the root output asserts (None = no assertion observed). The status
    * extraction mirrors the trusted-check projector exactly: a top-level string
    * `status` field, nothing else.
    */
  def record(origin: Origin, rootOutput: Json): Recorded =
    Recorded(origin, assertedStatus = statusOf(rootOutput))

  /** The recorded `verdictEvidence` object for the run.json splice (additive,
    * frozen core schema untouched): the refined origin code, the target pointer
    * verbatim, the evidence path step ids, and the asserted status when one was
    * observed.
    */
  def evidenceJson(recorded: Recorded, targetPointer: String): Json =
    val base = zio.Chunk[(String, Json)](
      "origin" -> Json.Str(recorded.code),
      "target" -> Json.Str(targetPointer),
      "steps" -> Json.Arr(
        zio.Chunk.fromIterable(recorded.origin.stepIds.map(Json.Str.apply))
      )
    )
    Json.Obj(
      base ++ recorded.assertedStatus
        .map(s => zio.Chunk[(String, Json)]("assertedStatus" -> Json.Str(s)))
        .getOrElse(zio.Chunk.empty)
    )

  // ------------------------------------------------------------------//
  // Structural walk (mirrors the frozen lowerer's execution semantics)  //
  // ------------------------------------------------------------------//

  private def resolveStep(
      id: String,
      byId: Map[String, Json.Obj],
      visited: Set[String]
  ): Origin =
    if visited(id) then Mixed(Nil) // defensive: the validator refuses cycles
    else
      byId.get(id) match
        case None => Mixed(List(id)) // defensive: unresolved reference
        case Some(step) =>
          val inner = visited + id
          string(step, "kind") match
            case Some("trustedCheck") => TrustedCheck(List(id))
            case Some("agent")        => AgentStep(List(id))
            case Some("sequence")     =>
              // foldLeft over children: the LAST child's output survives.
              childIds(step).lastOption match
                case Some(last) =>
                  resolveStep(last, byId, inner) match
                    case TrustedCheck(ids) => TrustedCheck(id +: ids)
                    case AgentStep(ids)    => AgentStep(id +: ids)
                    case Mixed(ids)        => Mixed(id +: ids)
                case None => Mixed(List(id))
            case Some("conditional") =>
              // The chosen branch's output passes through; statically both
              // branches must agree in kind for a single attribution.
              val branches =
                List(string(step, "then"), string(step, "else")).flatten
                  .map(resolveStep(_, byId, inner))
              val ids = branches.flatMap(_.stepIds)
              branches match
                case List(_: TrustedCheck, _: TrustedCheck) =>
                  TrustedCheck(id +: ids)
                case List(_: AgentStep, _: AgentStep) => AgentStep(id +: ids)
                case _                                => Mixed(id +: ids)
            case Some("map") =>
              // The output is the ARRAY of per-item template results — an
              // aggregate of agent results stays agent evidence, an aggregate
              // of check results is never ONE check's result.
              string(step, "templateStep") match
                case Some(template) =>
                  resolveStep(template, byId, inner) match
                    case AgentStep(ids)    => AgentStep(id +: ids)
                    case TrustedCheck(ids) => Mixed(id +: ids)
                    case Mixed(ids)        => Mixed(id +: ids)
                case None => Mixed(List(id))
            case Some("join") =>
              // The output is the ARRAY of member results: all-agent members
              // stay agent evidence; any other composition is combined.
              val origins = childIds(step).map(resolveStep(_, byId, inner))
              val ids = id +: origins.flatMap(_.stepIds)
              if origins.nonEmpty && origins.forall(_.isInstanceOf[AgentStep])
              then AgentStep(ids)
              else Mixed(ids)
            // Repair/checkpoint outputs come from caller-supplied programs
            // that are not the pinned trusted checks.
            case Some("repair")     => Mixed(List(id))
            case Some("checkpoint") => Mixed(List(id))
            case _                  => Mixed(List(id))

  private def stepsById(root: Json.Obj): Map[String, Json.Obj] =
    field(root, "steps").toList.flatMap {
      case Json.Arr(values) =>
        values.collect { case s: Json.Obj =>
          string(s, "id").map(_ -> s)
        }.flatten
      case _ => Nil
    }.toMap

  private def childIds(step: Json.Obj): List[String] =
    field(step, "steps").toList.flatMap {
      case Json.Arr(values) => values.collect { case Json.Str(s) => s }
      case _                => Nil
    }

  /** Exactly the lowerer's pointer rule: the step id is the first segment after
    * `#/steps/`.
    */
  private def stepIdFromPointer(pointer: String): Option[String] =
    val prefix = "#/steps/"
    if pointer.startsWith(prefix) then
      Some(pointer.stripPrefix(prefix).takeWhile(_ != '/'))
    else None

  /** Exactly the trusted-check projector's extraction: a top-level string
    * `status` field.
    */
  private def statusOf(output: Json): Option[String] = output match
    case obj: Json.Obj =>
      obj.fields.collectFirst { case ("status", Json.Str(v)) => v }
    case _ => None

  private def field(value: Json.Obj, name: String): Option[Json] =
    value.fields.collectFirst { case (`name`, result) => result }

  private def string(value: Json.Obj, name: String): Option[String] =
    field(value, name).collect { case Json.Str(result) => result }

end PortableVerdictEvidence
