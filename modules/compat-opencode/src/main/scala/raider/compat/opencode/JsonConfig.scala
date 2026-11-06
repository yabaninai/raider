package raider.compat.opencode

import zio.json.ast.Json

import scala.collection.mutable

/** One patch derived from a JSON config entry. `origin` records whether the
  * entry came from the legacy V1 config ("legacy-v1") or the native V2
  * config ("native-v2"); when both define the same agent the native entry
  * wins per pinned mergeMaps semantics and a conflict is reported.
  */
final case class AgentPatch(
  id: String,
  name: Option[String] = None,
  description: Option[String] = None,
  system: Option[String] = None,
  mode: Option[String] = None,
  hidden: Option[Boolean] = None,
  color: Option[String] = None,
  steps: Option[Int] = None,
  model: Option[ModelRef] = None,
  request: Option[RequestSettings] = None,
  permissions: Vector[PermissionRule] = Vector.empty,
  disabled: Boolean = false,
  unsupportedFields: Vector[String] = Vector.empty,
  origin: String,
)

final case class Overlay(
  entries: Vector[AgentPatch],
  globalRules: Vector[PermissionRule],
  smallModel: Option[AgentPatch],
  defaultAgent: Option[String],
  diagnostics: Vector[Diagnostic],
)

/** Decoder for OpenCode JSON config documents: the legacy V1 keys (agent,
  * mode, permission, tools, small_model, default_agent) and the native V2
  * keys (agents, permissions), mirroring pinned config-normalize.ts and
  * v1-config-migrate.ts. Paths in diagnostics are prefixed with the
  * document path.
  */
object JsonConfig:

  def decode(cfg: Json, docPath: String): Either[String, Overlay] =
    cfg match
      case obj @ Json.Obj(_) =>
        val diags = mutable.ListBuffer.empty[Diagnostic]
        for
          globalRules <- decodeGlobals(obj, docPath)
          smallModel = decodeSmallModel(obj, docPath, diags)
          entries = decodeEntries(obj, docPath, diags)
          defaultAgent = strField(obj, "default_agent")
        yield Overlay(entries, globalRules, smallModel, defaultAgent, diags.toVector)
      case _ => Left(s"$docPath: expected a JSON object")

  private def decodeGlobals(obj: Json.Obj, docPath: String): Either[String, Vector[PermissionRule]] =
    val tools = obj.get("tools") match
      case Some(v) =>
        val r = PermissionMigration.migrateTools(v)
        if r.diagnostics.nonEmpty then Left(s"$docPath: invalid tools config: ${r.diagnostics.head.message}")
        else Right(r.rules)
      case None => Right(Vector.empty)
    val permission = obj.get("permission") match
      case Some(v) =>
        val r = PermissionMigration.migratePermissions(v)
        if r.diagnostics.nonEmpty then Left(s"$docPath: invalid permission config: ${r.diagnostics.head.message}")
        else Right(r.rules)
      case None => Right(Vector.empty)
    val native = obj.get("permissions") match
      case Some(Json.Arr(items)) =>
        val collected = mutable.ListBuffer.empty[PermissionRule]
        items.zipWithIndex.foreach { case (item, index) =>
          rule(item) match
            case Some(r) => collected += r
            case None    => ()
        }
        // Invalid native global rules fail the document like schema violations.
        val allValid = items.forall(rule(_).isDefined)
        if allValid then Right(collected.toVector)
        else Left(s"$docPath: invalid native permissions entry")
      case Some(_) => Left(s"$docPath: invalid native permissions entry")
      case None    => Right(Vector.empty)
    for t <- tools; p <- permission; n <- native yield t ++ p ++ n

  private def rule(item: Json): Option[PermissionRule] =
    item match
      case obj @ Json.Obj(_) =>
        for
          action <- strField(obj, "action")
          resource <- strField(obj, "resource")
          effect <- strField(obj, "effect") if Model.effects.contains(effect)
        yield PermissionRule(action, resource, effect)
      case _ => None

  /** Legacy agent and mode maps merged with the native agents map: legacy
    * entries keep their position, native overwrites the value, conflicts are
    * reported once per id.
    */
  private def decodeEntries(obj: Json.Obj, docPath: String, diags: mutable.ListBuffer[Diagnostic]): Vector[AgentPatch] =
    val legacy = mutable.LinkedHashMap.empty[String, AgentPatch]
    val native = mutable.LinkedHashMap.empty[String, AgentPatch]

    obj.get("agent").foreach { v =>
      mapEntries(v).foreach { case (id, fields) =>
        LegacyMigrate.migrateAgent(fields, forcePrimary = false) match
          case Right(m) =>
            legacy.update(id, legacyPatch(id, m))
          case Left(errs) =>
            errs.foreach(d => diags += d.copy(path = Vector(docPath, id) ++ d.path))
      }
    }
    obj.get("mode").foreach { v =>
      mapEntries(v).foreach { case (id, fields) =>
        LegacyMigrate.migrateAgent(fields, forcePrimary = true) match
          case Right(m) =>
            legacy.update(id, legacyPatch(id, m))
          case Left(errs) =>
            errs.foreach(d => diags += d.copy(path = Vector(docPath, id) ++ d.path))
      }
    }
    obj.get("agents").foreach { v =>
      mapEntries(v).foreach { case (id, fields) =>
        NativeDecode.decode(fields, systemFallback = None) match
          case Right(a) =>
            native.update(
              id,
              AgentPatch(
                id = id,
                name = a.name,
                description = a.description,
                system = a.system,
                mode = a.mode,
                hidden = a.hidden,
                color = a.color,
                steps = a.steps,
                model = a.model,
                request = a.request,
                permissions = a.permissions,
                disabled = a.disabled,
                unsupportedFields = a.unsupported.toVector,
                origin = "native-v2",
              ),
            )
          case Left(errs) =>
            errs.foreach(d => diags += d.copy(path = Vector(docPath, id) ++ d.path))
      }
    }

    native.foreach { case (id, n) =>
      legacy.get(id) match
        case Some(l) =>
          if differs(l, n) then
            diags += Diagnostic(
              "conflict",
              Vector(docPath, id),
              "legacy and native agent definitions differ; the native value wins",
            )
          legacy.update(id, mergePatches(l, n))
        case None => legacy.update(id, n)
    }
    legacy.values.toVector

  private def mapEntries(v: Json): Vector[(String, Json.Obj)] = v match
    case obj @ Json.Obj(_) => obj.fields.collect { case (k, o @ Json.Obj(_)) => k -> o }.toVector
    case _                 => Vector.empty

  private def legacyPatch(id: String, m: LegacyMigrate.Migrated): AgentPatch =
    AgentPatch(
      id = id,
      name = m.name,
      description = m.description,
      system = m.system,
      mode = m.mode,
      hidden = m.hidden,
      color = m.color,
      steps = m.steps,
      model = m.model,
      request = m.request,
      permissions = m.rules,
      disabled = m.disabled,
      origin = "legacy-v1",
    )

  /** Field-wise comparison of the overlapping definitions. */
  private def differs(l: AgentPatch, n: AgentPatch): Boolean =
    l.description != n.description && (l.description.isDefined && n.description.isDefined) ||
    l.system != n.system && (l.system.isDefined && n.system.isDefined) ||
    l.mode != n.mode && (l.mode.isDefined && n.mode.isDefined) ||
    l.hidden != n.hidden && (l.hidden.isDefined && n.hidden.isDefined) ||
    l.color != n.color && (l.color.isDefined && n.color.isDefined) ||
    l.steps != n.steps && (l.steps.isDefined && n.steps.isDefined) ||
    l.model != n.model && (l.model.isDefined && n.model.isDefined)

  private def mergePatches(l: AgentPatch, n: AgentPatch): AgentPatch =
    AgentPatch(
      id = l.id,
      name = n.name.orElse(l.name),
      description = n.description.orElse(l.description),
      system = n.system.orElse(l.system),
      mode = n.mode.orElse(l.mode),
      hidden = n.hidden.orElse(l.hidden),
      color = n.color.orElse(l.color),
      steps = n.steps.orElse(l.steps),
      model = n.model.orElse(l.model),
      request = Importer.mergeRequest(l.request, n.request),
      permissions = l.permissions ++ n.permissions,
      disabled = l.disabled || n.disabled,
      unsupportedFields = (l.unsupportedFields ++ n.unsupportedFields).distinct,
      origin = "native-v2",
    )

  /** small_model becomes a title overlay with the legacy model selection
    * semantics (v1-config-migrate.ts).
    */
  private def decodeSmallModel(obj: Json.Obj, docPath: String, diags: mutable.ListBuffer[Diagnostic]): Option[AgentPatch] =
    obj.get("small_model") match
      case None => None
      case Some(Json.Str(s)) =>
        LegacyMigrate.parseLegacyModel(s) match
          case Some(m) => Some(AgentPatch(id = "title", model = Some(m), origin = "legacy-v1"))
          case None =>
            diags += Diagnostic(
              "invalid",
              Vector(docPath, "small_model"),
              "expected provider/model",
            )
            None
      case Some(_) =>
        diags += Diagnostic(
          "unsupported",
          Vector(docPath, "small_model"),
          "omitted unsupported legacy model reference",
        )
        None

  private def strField(obj: Json.Obj, key: String): Option[String] =
    obj.get(key).collect { case Json.Str(s) => s }
