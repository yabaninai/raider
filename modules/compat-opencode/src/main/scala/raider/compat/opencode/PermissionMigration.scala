package raider.compat.opencode

import zio.json.ast.Json

import scala.collection.mutable

/** Faithful port of the pinned V1 -> V2 permission migration
  * (vendor/opencode-profiles/upstream/core/config-normalize.ts migrateTools /
  * migratePermissions and core/v1-config-migrate.ts normalizeAction /
  * permissions()).
  */
object PermissionMigration:

  final case class MigrationResult(rules: Vector[PermissionRule], diagnostics: Vector[Diagnostic])

  private val ActionRenames = Map(
    "write" -> "edit",
    "patch" -> "edit",
    "apply_patch" -> "edit",
    "task" -> "subagent",
    "bash" -> "shell",
  )

  /** Map v1 permission/tool keys onto their renamed v2 tool actions so
    * migrated rules keep matching.
    */
  def normalizeAction(action: String): String = ActionRenames.getOrElse(action, action)

  private def isEffect(v: Json): Boolean = v match
    case Json.Str(s) => Model.effects.contains(s)
    case _           => false

  private def effectOf(v: Json): Option[String] = v match
    case Json.Str(s) if Model.effects.contains(s) => Some(s)
    case _                                        => None

  private def invalid(path: Vector[String], message: String): Diagnostic =
    Diagnostic("invalid", path, message)

  /** config-normalize.ts migrateTools: a v1 tools map of booleans becomes
    * allow/deny rules with resource "*"; non-boolean entries are reported at
    * ["tools", action]; a non-record tools value is reported at ["tools"].
    */
  def migrateTools(tools: Json): MigrationResult =
    tools match
      case obj @ Json.Obj(_) =>
        val rules = mutable.ListBuffer.empty[PermissionRule]
        val diags = mutable.ListBuffer.empty[Diagnostic]
        obj.fields.foreach { case (action, raw) =>
          raw match
            case Json.Bool(enabled) =>
              rules += PermissionRule(
                normalizeAction(action),
                "*",
                if enabled then "allow" else "deny",
              )
            case _ =>
              diags += invalid(Vector("tools", action), "expected a boolean tool toggle")
        }
        MigrationResult(rules.toVector, diags.toVector)
      case Json.Null =>
        MigrationResult(Vector.empty, Vector.empty)
      case _ =>
        MigrationResult(Vector.empty, Vector(invalid(Vector("tools"), "expected a record of booleans")))

  /** config-normalize.ts migratePermissions: a bare string becomes a global
    * "*" rule; entries map an action to an effect string or to a resource ->
    * effect record. Invalid effects are reported at ["permission"],
    * ["permission", action], or ["permission", action, entryIndex].
    */
  def migratePermissions(permission: Json): MigrationResult =
    permission match
      case Json.Null =>
        MigrationResult(Vector.empty, Vector.empty)
      case raw @ Json.Str(_) =>
        effectOf(raw) match
          case Some(effect) => MigrationResult(Vector(PermissionRule("*", "*", effect)), Vector.empty)
          case None =>
            MigrationResult(Vector.empty, Vector(invalid(Vector("permission"), "expected allow, deny, or ask")))
      case obj @ Json.Obj(_) =>
        val rules = mutable.ListBuffer.empty[PermissionRule]
        val diags = mutable.ListBuffer.empty[Diagnostic]
        obj.fields.foreach { case (action, raw) =>
          val normalized = normalizeAction(action)
          raw match
            case Json.Str(_) =>
              effectOf(raw) match
                case Some(effect) => rules += PermissionRule(normalized, "*", effect)
                case None =>
                  diags += invalid(Vector("permission", action), "expected allow, deny, or ask")
            case entry @ Json.Obj(_) =>
              entry.fields.zipWithIndex.foreach { case ((resource, effect), index) =>
                effectOf(effect) match
                  case Some(decoded) => rules += PermissionRule(normalized, resource, decoded)
                  case None =>
                    diags += invalid(
                      Vector("permission", action, index.toString),
                      "expected allow, deny, or ask",
                    )
              }
            case _ =>
              diags += invalid(
                Vector("permission", action),
                "expected an effect or a resource-to-effect record",
              )
        }
        MigrationResult(rules.toVector, diags.toVector)
      case _ =>
        MigrationResult(Vector.empty, Vector(invalid(Vector("permission"), "expected a string or a record")))
