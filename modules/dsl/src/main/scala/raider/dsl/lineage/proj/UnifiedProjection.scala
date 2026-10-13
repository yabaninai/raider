package raider.dsl.lineage.proj

/** Unified multi-job lineage projection (card RAI-145, ADR-031 + ADR-032): the
  * unified multi-job call graph (one campaign + correlation-linked child
  * segments, ADR-032 §1) projects to an EXECUTABLE generated Scala source that
  * composes over the ADR-031 dry-run runtime
  * (`raider.dsl.lineage.proj.runtime.LineageStep`) plus the FROZEN DSL
  * composition verbs the recorded loop actually used:
  *
  *   - '''phase defs per pipeline stage''' — wire-native `SessionNode.Phase`
  *     nodes (v2 `phase_started`, ADR-032) become named sub-`Program` defs;
  *   - '''delegation blocks where the contract links jobs''' — a `Delegation`
  *     node becomes a named def whose body splices the CHILD segment's nodes
  *     (real parent-child edges from the joined stream, never invented);
  *   - '''batch fan-out as the recorded loop composition''' — sibling
  *     delegation groups the recorded loop actually dispatched as one fan-out
  *     project to `raider.dsl.composition.batch` (the frozen n-ary ordered
  *     bounded batch; results keep INPUT order, COMP-01). The fan-out FACT is a
  *     recorded composition carried in via [[FanOutGroup]] and VALIDATED
  *     against the graph (members must be same-parent siblings, contiguous in
  *     view order, delegation-kind) — parallelism is never inferred from node
  *     order or timestamps, so no parallelism is invented (card hard rule).
  *     `pipeline`/`parallel`/`batch` illustrative SYNTAX is not an approved API
  *     and is never emitted; the pinned verb choice lives in the card record.
  *
  * ADR-031 §2.2 governs the `LineageStep` RUNTIME module, which stays exactly
  * as accepted (only `Task.succeed`/`fail`/`map`/`flatMap`). This generator
  * adds the card-authorized composition layer on top: `batch` for recorded
  * fan-out groups, `Task.map`/`flatMap` chains for sequencing (the same
  * discipline the single-run renderer emits). The dry-run property is
  * unchanged: construction and execution dispatch no model call, no tool, no
  * I/O; the single observation boundary stays `Task.zio` on the built `Program`
  * — `batch` runs under the one effect interpreter (RAI-017), creating no
  * second execution semantics.
  *
  * Honesty rules (card UNI-02/UNI-03):
  *   - the generated source carries the recorded cross-run provenance pointers
  *     (ADR-032 §1) and the per-segment correlation report as DATA vals —
  *     environment-relative pointers, never payloads;
  *   - the graph's edges are the ONLY edges: unlinked native roots simply do
  *     not occur in the unified view (RAI-144 never joins them), so the
  *     projection cannot merge them; segment statuses are carried typed on
  *     [[GeneratedProjection]] (linked / incomplete / unlinked), never silently
  *     flattened;
  *   - a recorded non-success outcome projects to `LineageStep.failed` exactly
  *     like the single-run renderer (ADR-031 §2.4).
  *
  * Output is byte-identical on repeat (LIN2-C-01 discipline): all counters and
  * buffers are local to the invocation and every iteration walks the view in
  * pre-order. Payloads never enter the source: `dataRef` KEYS only (ADR-031
  * §2.5); literal text goes through the same escape discipline as the
  * single-run renderer (`\uXXXX` for C0+DEL inside string literals — the pinned
  * dotc 3.9.0 rejects `\xHH` there).
  */
enum UnifiedProjectionError:

  /** Rootless view — nothing to project. */
  case Empty

  /** A node id repeats on the current root-to-node path (defensive guard, same
    * as the single-run renderer).
    */
  case Cyclic(id: String)

  /** A node kind or recorded outcome class this revision does not render. */
  case UnknownKind(detail: String)

  /** A fan-out group does not match the graph: unknown/duplicate member,
    * non-delegation member, wrong parent, non-contiguous members, degenerate
    * group (size < 2) or invalid parallelism. A mismatching group is refused
    * typed — the projection never "fixes" a recorded composition to fit.
    */
  case BadFanOut(detail: String)

/** Recorded fan-out group of the unified pipeline (RAI-145): the sibling
  * delegation nodes the recorded loop dispatched as ONE bounded batch, with the
  * parallelism the loop actually used. `parentId` is the common parent node id
  * (`None` for the root scope). Carried in as RECORDED data and validated
  * against the graph — see [[UnifiedProjection]].
  */
final case class FanOutGroup(
    parentId: Option[String],
    members: Vector[String],
    parallelism: Int
)

/** One native artifact pointer of the recorded provenance record (ADR-032 §1),
  * verbatim (`kind` e.g. `work_dir`/`events`; `path` environment-relative).
  * Data-only: carried into the generated artifact and the typed projection
  * value, never into a step report payload.
  */
final case class ProvenancePointer(kind: String, path: String)

/** Typed status of one segment of the unified export (card UNI-03: typed report
  * entries, never silent merges).
  */
object SegmentEntry:

  enum Status:

    /** Joined with correlation and terminal-complete in the exported stream. */
    case Linked

    /** Joined, but the segment's stream carried no `Terminal` — the strict
      * reduction refused typed and only the COMPLETED nodes survive (LIN5-03
      * partial discipline). `detail` carries the recorded refusal.
      */
    case Incomplete(detail: String)

    /** Never joined: the job's stream carried no `Delegated` introduction, so
      * RAI-144 kept it a separate native root. It projects separately (its own
      * graph) and appears here only as the typed separation record.
      */
    case Unlinked(detail: String)

    def render: String = this match
      case Linked        => "linked"
      case Incomplete(d) => "incomplete: " + d
      case Unlinked(d)   => "unlinked: " + d

end SegmentEntry

/** One segment of the unified export: the child job id, the correlation key
  * (`None` for an unlinked root), the typed status.
  */
final case class SegmentEntry(
    jobId: String,
    invocationId: Option[String],
    status: SegmentEntry.Status
)

/** Input of the unified projection (RAI-145). `view` is the UNIFIED tree (the
  * product adapter over the reduced multi-job graph); the recorded-data fields
  * are validated and carried verbatim.
  */
final case class UnifiedProjectionInput(
    runId: String,
    view: LineageView,
    fanOut: Vector[FanOutGroup] = Vector.empty,
    provenance: Vector[ProvenancePointer] = Vector.empty,
    segments: Vector[SegmentEntry] = Vector.empty
)

/** The generated unified projection: the compiled-artifact source, the object
  * name, the named sub-`Program` def vals in pre-order discovery order, and the
  * validated recorded data (fan-out groups, provenance pointers, typed segment
  * entries).
  */
final case class GeneratedProjection(
    source: String,
    objectName: String,
    defNames: Vector[String],
    fanOut: Vector[FanOutGroup],
    provenance: Vector[ProvenancePointer],
    segments: Vector[SegmentEntry]
)

object UnifiedProjection:

  private val Header =
    "// Generated by Raider lineage projection (ADR-027/ADR-032 unified multi-job). Do not edit — comparator detects edits."

  /** ADR-031 §2.1 rule 1: the generated source is NOT a rerun/replay entrypoint
    * and must say so (same statement as the single-run renderer, plus the
    * unified shape and the pinned frozen-verb choice).
    */
  private val DryRunStatement = Vector(
    "// ADR-031 dry-run description: executing this projection dispatches no model call, no",
    "// tool call, no I/O — it only reports the recorded call-graph structure. Not a replay",
    "// or rerun entrypoint.",
    "// Unified multi-job projection (RAI-145): phase defs per pipeline stage, delegation",
    "// blocks per correlation-linked child segment (ADR-032), recorded sibling fan-out groups",
    "// composed with the frozen DSL verb batch (raider.dsl.composition.batch; results keep",
    "// input order). No parallelism is invented: edges come only from the reduced graph."
  )

  private val Indent = "  "

  /** Recorded outcome classes that project to a failed step (ADR-031 §2.4) —
    * the same closed vocabulary as the single-run renderer.
    */
  private val FailedOutcomes = Set("Failed", "Cancelled", "Timeout")
  private val SuccessOutcome = "Succeeded"

  /** One planned statement of a scope body (same shape as the single-run
    * renderer's plan): a runtime constructor with the inputs slot left open for
    * scope-local value binding, or a def call. Batch fan-out is an EMISSION
    * concern: it groups adjacent `Call`s at emission time, it is not a plan.
    */
  private enum Plan:
    def nodeId: String
    def dataRef: Option[String]

    case Ctor(
        nodeId: String,
        dataRef: Option[String],
        inputs: Vector[String],
        prefix: String,
        suffix: String
    ) extends Plan

    case Call(nodeId: String, dataRef: Option[String], call: String)
        extends Plan

  end Plan

  /** One emission element of a scope: a single statement or one recorded batch
    * fan-out over a contiguous run of def calls.
    */
  private enum Elem:
    case One(plan: Plan)
    case Fan(group: FanOutGroup, calls: Vector[Plan.Call])
  end Elem

  private final case class DefBlock(
      name: String,
      // The def NODE's own id: the def body scope holds the node's CHILDREN,
      // so the fan-out groups valid inside this body are those whose parentId
      // equals this id (root-scope groups carry parentId = None).
      scopeNode: Option[String],
      body: scala.collection.mutable.ArrayBuffer[Plan]
  )

  /** Deterministic generation of the unified projection source. Fails typed on
    * an empty view, a cycle, an unsupported kind/outcome or a fan-out group
    * that does not match the graph.
    */
  def generate(
      in: UnifiedProjectionInput
  ): Either[UnifiedProjectionError, GeneratedProjection] =
    if in.view.roots.isEmpty then Left(UnifiedProjectionError.Empty)
    else
      val out = new StringBuilder
      val path = scala.collection.mutable.Set.empty[String]
      val defCounts = scala.collection.mutable.HashMap.empty[String, Int]
      val defs = scala.collection.mutable.ArrayBuffer.empty[DefBlock]
      var needsNodeKindImport = false
      var needsBatchImport = false

      def nextDefName(base: String): String =
        val seen = defCounts.getOrElse(base, 0)
        defCounts(base) = seen + 1
        if seen == 0 then base else base + "_" + (seen + 1)

      // ----- fan-out validation (typed; a mismatch is never "fixed") --------

      val byId = scala.collection.mutable.HashMap
        .empty[String, (NodeView, Option[String])]
      def index(n: NodeView, parent: Option[String]): Unit =
        if byId.contains(n.id) then () // tree walk guards below; no-op
        else
          byId(n.id) = (n, parent)
          n.children.foreach(c => index(c, Some(n.id)))
      in.view.roots.foreach(r => index(r, None))

      def failFan(detail: String) =
        Left(UnifiedProjectionError.BadFanOut(detail))

      val validated: Either[UnifiedProjectionError, Vector[FanOutGroup]] =
        in.fanOut.foldLeft[Either[UnifiedProjectionError, Vector[FanOutGroup]]](
          Right(Vector.empty)
        ) { (acc, g) =>
          acc.flatMap { ok =>
            if g.parallelism < 1 then
              failFan("parallelism must be >= 1, got " + g.parallelism)
            else if g.members.sizeIs < 2 then
              failFan(
                "fan-out group needs >= 2 members, got " + g.members.length
              )
            else if g.members.distinct.length != g.members.length then
              failFan(
                "duplicate member in fan-out group: " + g.members.mkString(",")
              )
            else if g.members.exists(!byId.contains(_)) then
              failFan("unknown fan-out member: " + g.members.mkString(","))
            else if !g.members.forall { m =>
                byId(m)._1.kind.isInstanceOf[NodeKind.Delegation]
              }
            then
              failFan(
                "fan-out member is not a delegation: " + g.members.mkString(",")
              )
            else if !g.members.forall(m => byId(m)._2 == g.parentId)
            then
              failFan(
                "fan-out members do not share parent " + g.parentId.getOrElse(
                  "<root>"
                ) +
                  ": " + g.members.mkString(",")
              )
            else
              // contiguity + order: the members must appear as one contiguous
              // run, in view order, among the parent's children
              val childIds = g.parentId match
                case None    => in.view.roots.map(_.id)
                case Some(p) => byId(p)._1.children.map(_.id)
              val pos = childIds.indexOfSlice(g.members)
              if pos < 0 then
                failFan(
                  "fan-out members are not a contiguous in-order sibling run under " +
                    g.parentId.getOrElse("<root>") + ": " + g.members.mkString(
                      ","
                    )
                )
              else if ok.exists(_.members.exists(g.members.contains)) then
                failFan(
                  "member claimed by two fan-out groups: " + g.members.mkString(
                    ","
                  )
                )
              else Right(ok :+ g)
          }
        }

      validated.flatMap { groups =>
        // member id -> its group (emission lookup)
        val groupOf =
          scala.collection.mutable.HashMap.empty[String, FanOutGroup]
        groups.foreach(g => g.members.foreach(m => groupOf(m) = g))

        // ----- walk (pre-order; same discipline as the single-run renderer) --

        def dataSuffix(n: NodeView): String =
          n.dataRef.fold("")(ref => " // data: " + escape(ref))

        def strLit(raw: String): String = "\"" + escapeLiteral(raw) + "\""

        def refLit(dataRef: Option[String]): String =
          dataRef.fold("None")(ref => "Some(" + strLit(ref) + ")")

        def failureClass(
            n: NodeView
        ): Either[UnifiedProjectionError, Option[String]] =
          n.outcome match
            case None | Some(SuccessOutcome)               => Right(None)
            case Some(cls) if FailedOutcomes.contains(cls) => Right(Some(cls))
            case Some(other) =>
              Left(
                UnifiedProjectionError.UnknownKind(
                  "unsupported recorded outcome: " + escape(other)
                )
              )

        def atomicStep(
            n: NodeView,
            nesting: Int,
            plainPrefix: String,
            failedKindText: String
        ): Either[UnifiedProjectionError, Plan] =
          failureClass(n).map {
            case None =>
              Plan.Ctor(
                n.id,
                n.dataRef,
                n.inputs,
                plainPrefix + refLit(n.dataRef) + ", ",
                ", nesting = " + nesting + ")"
              )
            case Some(cls) =>
              needsNodeKindImport = true
              Plan.Ctor(
                n.id,
                n.dataRef,
                n.inputs,
                "LineageStep.failed(" + strLit(n.id) + ", " + failedKindText +
                  ", " + refLit(n.dataRef) + ", ",
                ", nesting = " + nesting + ", reason = " +
                  strLit("recorded outcome: " + cls) + ")"
              )
          }

        def namedDef(
            n: NodeView,
            depth: Int,
            base: String,
            label: String,
            failedKindText: String,
            into: scala.collection.mutable.ArrayBuffer[Plan]
        ): Either[UnifiedProjectionError, Unit] =
          val defName = nextDefName(base)
          // Reserve the def block BEFORE recursing: defs emit in pre-order
          // discovery order (outer first), like the single-run renderer. The
          // def BODY is the node's children scope (fan-out groups are scoped
          // per that node id).
          val body = scala.collection.mutable.ArrayBuffer.empty[Plan]
          defs += DefBlock(defName, Some(n.id), body)
          for
            own <- atomicStep(
              n,
              depth + 1,
              "LineageStep." +
                (if n.kind.isInstanceOf[NodeKind.Delegation] then "delegation("
                 else "phase(") +
                strLit(n.id) + ", " + strLit(label) + ", ",
              failedKindText
            )
            _ = body += own
            _ <- n.children.foldLeft[Either[UnifiedProjectionError, Unit]](
              Right(())
            )((acc, c) => acc.flatMap(_ => visit(c, depth + 1, body)))
            _ = into += Plan.Call(n.id, n.dataRef, defName + "(())")
          yield ()

        def visit(
            n: NodeView,
            depth: Int,
            into: scala.collection.mutable.ArrayBuffer[Plan]
        ): Either[UnifiedProjectionError, Unit] =
          if path.contains(n.id) then Left(UnifiedProjectionError.Cyclic(n.id))
          else
            path += n.id
            val kind: Matchable = n.kind
            val walkChildren: Either[UnifiedProjectionError, Unit] => Either[
              UnifiedProjectionError,
              Unit
            ] =
              _.flatMap(_ =>
                n.children.foldLeft[Either[UnifiedProjectionError, Unit]](
                  Right(())
                )((acc, c) => acc.flatMap(_ => visit(c, depth, into)))
              )
            val planned: Either[UnifiedProjectionError, Unit] = kind match
              case NodeKind.ModelCall(model, round) =>
                walkChildren(
                  atomicStep(
                    n,
                    depth,
                    "LineageStep.model(" + strLit(n.id) + ", " + strLit(model) +
                      ", " + round + ", ",
                    "NodeKind.ModelCall(" + strLit(model) + ", " + round + ")"
                  ).map(into += _)
                )
              case NodeKind.ToolCall(tool, recovery) =>
                walkChildren(
                  atomicStep(
                    n,
                    depth,
                    "LineageStep.tool(" + strLit(n.id) + ", " + strLit(tool) +
                      ", " + strLit(recovery) + ", ",
                    "NodeKind.ToolCall(" + strLit(tool) + ", " + strLit(
                      recovery
                    ) + ")"
                  ).map(into += _)
                )
              case NodeKind.Delegation(agent) =>
                namedDef(
                  n,
                  depth,
                  "delegation_" + slug(agent),
                  agent,
                  "NodeKind.Delegation(" + strLit(agent) + ")",
                  into
                )
              case NodeKind.Phase(name) =>
                namedDef(
                  n,
                  depth,
                  "phase_" + slug(name),
                  name,
                  "NodeKind.Phase(" + strLit(name) + ")",
                  into
                )
              case other =>
                val describe =
                  Option(other).fold("null")(_.getClass.getSimpleName)
                Left(
                  UnifiedProjectionError.UnknownKind(
                    "unsupported node kind: " + describe
                  )
                )
            planned.map(_ => path -= n.id)

        // ----- emission -------------------------------------------------------

        def inputsArg(
            inputs: Vector[String],
            vars: scala.collection.mutable.HashMap[String, String]
        ): String =
          if inputs.isEmpty then "Vector.empty"
          else
            "Vector(" + inputs
              .map(id => vars.get(id).fold(strLit(id))(v => v + ".head.id"))
              .mkString(", ") + ")"

        def planLine(
            s: Plan,
            vars: scala.collection.mutable.HashMap[String, String]
        ): String =
          s match
            case Plan.Call(_, _, call) => call
            case Plan.Ctor(_, _, inputs, prefix, suffix) =>
              prefix + inputsArg(inputs, vars) + suffix
        // NOTE: the `// data:` comment is appended once at emission time
        // (emitScope) — planLine stays comment-free so the batch fan-out
        // lines and the single-statement lines share one comment discipline.

        /** Emits one scope body as a value-threaded chain (ADR-031 §2.3), with
          * recorded fan-out groups composed via the frozen `batch` verb: a
          * contiguous run of grouped def-call statements becomes ONE
          * `batch(List(<calls>), <parallelism>)(p => p(()))` element whose
          * result flattens into the pre-order report vector (batch results keep
          * INPUT order — COMP-01 — so the report order stays the graph's
          * pre-order). Every element's fragment is bound (`n_*` variables,
          * deduped per scope); the final `.map` concatenates them in
          * declaration order, which equals the graph's pre-order walk.
          */
        def emitScope(
            baseIndent: String,
            steps: Vector[Plan],
            groupsHere: Vector[FanOutGroup]
        ): Unit =
          val vars = scala.collection.mutable.HashMap.empty[String, String]
          val varCounts = scala.collection.mutable.HashMap.empty[String, Int]
          def varFor(id: String): String =
            val baseName = "n_" + slug(id)
            val seen = varCounts.getOrElse(baseName, 0)
            varCounts(baseName) = seen + 1
            val name =
              if seen == 0 then baseName else baseName + "_" + (seen + 1)
            vars(id) = name
            name

          // group plans into emission elements
          val localGroups = groupsHere.filter(g =>
            steps.exists {
              case Plan.Call(id, _, _) => groupOf.get(id).contains(g)
              case _                   => false
            }
          )
          val elems = scala.collection.mutable.ArrayBuffer.empty[Elem]
          val emitted = scala.collection.mutable.Set.empty[FanOutGroup]
          var i = 0
          while i < steps.length do
            steps(i) match
              case c: Plan.Call
                  if groupOf.get(c.nodeId).exists(localGroups.contains) =>
                val g = groupOf(c.nodeId)
                if emitted.contains(g) then i += 1 // middle member: consumed
                else
                  val calls =
                    steps.slice(i, i + g.members.length).collect {
                      case cc: Plan.Call => cc
                    }
                  elems += Elem.Fan(g, calls)
                  emitted += g
                  i += g.members.length
              case other =>
                elems += Elem.One(other)
                i += 1

          val evars = elems.map {
            case Elem.One(p)    => varFor(p.nodeId)
            case Elem.Fan(g, _) => varFor(g.members.head + ":batch")
          }

          def dataComment(e: Elem): String = e match
            case Elem.One(p) =>
              p.dataRef.fold("")(ref => " // data: " + escape(ref))
            case Elem.Fan(_, _) => ""

          def elemLine(e: Elem): String = e match
            case Elem.One(p)        => planLine(p, vars)
            case Elem.Fan(g, calls) =>
              // The group composes the member SUB-PROGRAMS unapplied; the
              // batch runner applies each one (Program.apply(()) builds the
              // Task lazily — no dispatch at composition time). Elements keep
              // the graph's pre-order; results keep INPUT order (COMP-01).
              "batch(List(" +
                calls.map(c => c.call.stripSuffix("(())")).mkString(", ") +
                "), " + g.parallelism + ")(p => p(()))"

          if elems.sizeIs == 1 then
            out
              .append(baseIndent)
              .append(elemLine(elems.head))
              .append(dataComment(elems.head))
              .append('\n')
          else
            val last = elems.length - 1
            // The concatenated fragment expression of one element: a batch
            // fan-out flattens its ordered member fragments into the vector.
            def varExpr(k: Int): String = elems(k) match
              case Elem.One(_)    => evars(k)
              case Elem.Fan(_, _) => evars(k) + ".flatten"
            elems.zipWithIndex.foreach { (e, k) =>
              val ind = baseIndent + Indent * 2 * k
              out
                .append(ind)
                .append(elemLine(e))
                .append(dataComment(e))
                .append('\n')
              if k < last then
                out
                  .append(ind + Indent)
                  .append(".flatMap { ")
                  .append(evars(k))
                  .append(" =>")
                  .append('\n')
              else
                out
                  .append(ind + Indent)
                  .append(".map { ")
                  .append(evars(k))
                  .append(" =>")
                  .append('\n')
                out
                  .append(ind + Indent * 2)
                  .append((0 to last).map(varExpr).mkString(" ++ "))
                  .append('\n')
                out.append(ind + Indent).append('}').append('\n')
            }
            for j <- last - 1 to 0 by -1 do
              out
                .append(baseIndent + Indent * (2 * j + 1))
                .append('}')
                .append('\n')

        def emitMember(
            name: String,
            body: Vector[Plan],
            groupsHere: Vector[FanOutGroup]
        ): Unit =
          // The sub-Program member is a val (strict, pure construction — the
          // runtime dispatches nothing at build time), mirroring the
          // review-approved PRC-02 fixture shape: the parent scope splices the
          // sub-program by applying it — `<name>(())` is `Program.apply(())`.
          out
            .append(Indent)
            .append("val ")
            .append(name)
            .append(": Program[Unit, Vector[StepReport]] =")
            .append('\n')
          out
            .append(Indent * 2)
            .append("Program.fromFunction(\"")
            .append(name)
            .append("\") { _ =>")
            .append('\n')
          emitScope(Indent * 3, body, groupsHere)
          out.append(Indent * 2).append('}').append('\n')

        // groups scoped per parent id (def body / root scope)
        def groupsUnder(parent: Option[String]): Vector[FanOutGroup] =
          groups.filter(_.parentId == parent)

        val rootSteps = scala.collection.mutable.ArrayBuffer.empty[Plan]
        val walkResult = in.view.roots
          .foldLeft[Either[UnifiedProjectionError, Unit]](Right(()))((acc, r) =>
            acc.flatMap(_ => visit(r, 0, rootSteps))
          )

        walkResult.map { _ =>
          // Import flags must be FINAL before the header is written: the
          // batch flag follows from the plans (a validated group is emitted
          // iff one of its member call sites occurs in a scope), the NodeKind
          // flag from the walk (failed constructors).
          needsBatchImport =
            (defs.iterator.flatMap(_.body.iterator) ++ rootSteps.iterator)
              .exists {
                case Plan.Call(id, _, _) => groupOf.contains(id)
                case _                   => false
              }
          out.append(Header).append('\n')
          DryRunStatement.foreach(line => out.append(line).append('\n'))
          out.append('\n')
          out.append("import raider.core.Program").append('\n')
          if needsBatchImport then
            out.append("import raider.dsl.composition.batch").append('\n')
          if needsNodeKindImport then
            out.append("import raider.dsl.lineage.proj.NodeKind").append('\n')
          out
            .append(
              "import raider.dsl.lineage.proj.runtime.{LineageStep, StepReport}"
            )
            .append('\n')
          out.append('\n')
          val objName = "lineage_" + slug(in.runId)
          out.append("object ").append(objName).append(':').append('\n')
          if in.provenance.nonEmpty then
            out
              .append(Indent)
              .append(
                "// Recorded cross-run provenance (ADR-032 §1): verbatim environment-relative"
              )
              .append('\n')
            out
              .append(Indent)
              .append(
                "// artifact pointers of the joined segments. Data-only; payloads stay in the"
              )
              .append('\n')
            out
              .append(Indent)
              .append("// lineage-data resource, addressable by dataRef keys.")
              .append('\n')
            out
              .append(Indent)
              .append("val runProvenance: Vector[(String, String)] =")
              .append('\n')
            out
              .append(Indent * 2)
              .append(
                "Vector(" + in.provenance
                  .map(p =>
                    "(\"" + escapeLiteral(p.kind) + "\", \"" + escapeLiteral(
                      p.path
                    ) + "\")"
                  )
                  .mkString(", ") + ")"
              )
              .append('\n')
            out.append('\n')
          if in.segments.nonEmpty then
            out
              .append(Indent)
              .append(
                "// Correlation report of the unified export (RAI-145): (job, invocation, status)."
              )
              .append('\n')
            out
              .append(Indent)
              .append(
                "// Typed entries on GeneratedProjection.segments; never a silent merge."
              )
              .append('\n')
            out
              .append(Indent)
              .append("val segments: Vector[(String, String, String)] =")
              .append('\n')
            out
              .append(Indent * 2)
              .append(
                "Vector(" + in.segments
                  .map(s =>
                    "(\"" + escapeLiteral(s.jobId) + "\", \"" +
                      escapeLiteral(s.invocationId.getOrElse("-")) + "\", \"" +
                      escapeLiteral(s.status.render) + "\")"
                  )
                  .mkString(", ") + ")"
              )
              .append('\n')
            out.append('\n')
          defs.zipWithIndex.foreach { (d, k) =>
            if k > 0 then out.append('\n')
            emitMember(d.name, d.body.toVector, groupsUnder(d.scopeNode))
          }
          if defs.nonEmpty then out.append('\n')
          out
            .append(Indent)
            .append("val program: Program[Unit, Vector[StepReport]] =")
            .append('\n')
          out
            .append(Indent * 2)
            .append("Program.fromFunction(\"")
            .append(objName)
            .append("\") { _ =>")
            .append('\n')
          emitScope(Indent * 3, rootSteps.toVector, groupsUnder(None))
          out.append(Indent * 2).append('}').append('\n')

          GeneratedProjection(
            source = out.result(),
            objectName = objName,
            defNames = defs.map(_.name).toVector,
            fanOut = groups,
            provenance = in.provenance,
            segments = in.segments
          )
        }
      }

  /** Identifier slug: letters/digits/underscore kept, everything else mapped to
    * `_`; an empty or digit-leading result is prefixed so it is a valid Scala
    * identifier. Deterministic (same discipline as the single-run renderer).
    */
  private def slug(raw: String): String =
    val cleaned = raw.map(c => if c.isLetterOrDigit || c == '_' then c else '_')
    if cleaned.isEmpty || cleaned.head.isDigit then "_" + cleaned else cleaned

  /** Escape discipline for free text entering a `//` COMMENT of the generated
    * source (renderer parity): `\` and `"` escaped classically, `\n`/`\r`/`\t`
    * as two-character sequences, every other C0 control or DEL as `\xHH`
    * (lexically inert inside a comment). Error-detail strings never compiled go
    * through the same function.
    */
  private def escape(raw: String): String =
    val out = new StringBuilder(raw.length + 8)
    raw.foreach {
      case '\\'                          => out.append("\\\\")
      case '"'                           => out.append("\\\"")
      case '\n'                          => out.append("\\n")
      case '\r'                          => out.append("\\r")
      case '\t'                          => out.append("\\t")
      case c if c < ' ' || c == '\u007f' => out.append(f"\\x${c.toInt}%02x")
      case c                             => out.append(c)
    }
    out.result()

  /** Escape discipline for free text entering a generated STRING literal
    * (RAI-151 renderer parity): `\` and `"` escaped classically, `\n`/`\r`/`\t`
    * as single-character escapes, every other C0 control or DEL as `\uXXXX`
    * (four lowercase hex digits) — the pinned dotc 3.9.0 rejects `\xHH` inside
    * string literals.
    */
  private def escapeLiteral(raw: String): String =
    val out = new StringBuilder(raw.length + 8)
    raw.foreach {
      case '\\'                          => out.append("\\\\")
      case '"'                           => out.append("\\\"")
      case '\n'                          => out.append("\\n")
      case '\r'                          => out.append("\\r")
      case '\t'                          => out.append("\\t")
      case c if c < ' ' || c == '\u007f' => out.append(f"\\u${c.toInt}%04x")
      case c                             => out.append(c)
    }
    out.result()

end UnifiedProjection
