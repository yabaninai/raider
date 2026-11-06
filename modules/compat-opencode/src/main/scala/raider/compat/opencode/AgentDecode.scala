package raider.compat.opencode

import zio.json.ast.Json

import scala.collection.mutable

/** Faithful port of the pinned V1 agent migration
  * (vendor/opencode-profiles/upstream/core/v1-config-migrate.ts migrateAgent):
  * known V1 keys are mapped, unknown keys and temperature/top_p/options land
  * in request.body, the theme color falls back to #aaaaaa, maxSteps aliases
  * steps, the model selection regex rejects strings containing '#', and
  * tools+permission maps merge keeping the first position of a repeated key.
  */
object LegacyMigrate:

  final case class Migrated(
    name: Option[String],
    description: Option[String],
    system: Option[String],
    mode: Option[String],
    hidden: Option[Boolean],
    color: Option[String],
    steps: Option[Int],
    model: Option[ModelRef],
    request: Option[RequestSettings],
    rules: Vector[PermissionRule],
    disabled: Boolean,
  )

  private val Known: Set[String] = Set(
    "name", "model", "variant", "temperature", "top_p", "prompt", "tools", "disable",
    "description", "mode", "hidden", "options", "color", "steps", "maxSteps", "permission",
  )

  private def invalid(path: Vector[String], message: String): Diagnostic =
    Diagnostic("invalid", path, message)

  /** v1-config-migrate.ts modelSelection: provider/model, no '#' allowed
    * (a joined model#variant string therefore decodes to no model at all).
    */
  def parseLegacyModel(s: String): Option[ModelRef] =
    val slash = s.indexOf('/')
    if slash <= 0 || slash == s.length - 1 || s.contains("#")
      || !s.substring(0, slash).matches("""[^/#]+""")
      || !s.substring(slash + 1).matches("""[^#]+""")
    then None
    else Some(ModelRef(s.substring(0, slash), s.substring(slash + 1), None))

  def migrateAgent(fields: Json.Obj, forcePrimary: Boolean): Either[Vector[Diagnostic], Migrated] =
    val diags = mutable.ListBuffer.empty[Diagnostic]
    val body = mutable.ListBuffer.empty[(String, Json)]
    val permMap = mutable.LinkedHashMap.empty[String, Json]

    var name: Option[String] = None
    var description: Option[String] = None
    var system: Option[String] = None
    var mode: Option[String] = None
    var hidden: Option[Boolean] = None
    var color: Option[String] = None
    var steps: Option[Int] = None
    var modelStr: Option[String] = None
    var variantStr: Option[String] = None
    var disabled = false

    fields.fields.foreach { case (key, value) =>
      key match
        case "name" =>
          value match
            case Json.Str(s) => name = Some(s)
            case _           => diags += invalid(Vector("name"), "expected a string")
        case "description" =>
          value match
            case Json.Str(s) => description = Some(s)
            case _           => diags += invalid(Vector("description"), "expected a string")
        case "prompt" =>
          value match
            case Json.Str(s) => system = Some(s)
            case _           => diags += invalid(Vector("prompt"), "expected a string")
        case "mode" =>
          value match
            case Json.Str(s) if Model.modes.contains(s) => mode = Some(s)
            case _                                      => diags += invalid(Vector("mode"), "expected subagent, primary, or all")
        case "hidden" =>
          value match
            case Json.Bool(b) => hidden = Some(b)
            case _            => diags += invalid(Vector("hidden"), "expected a boolean")
        case "color" =>
          value match
            case Json.Str(s) => color = Some(if s.startsWith("#") then s else "#aaaaaa")
            case _           => diags += invalid(Vector("color"), "expected a string")
        case "steps" | "maxSteps" =>
          if key == "maxSteps" && steps.isDefined then ()
          else
            value match
              case Json.Num(n) =>
                val bd = BigDecimal(n)
                if bd.isValidInt && bd.toInt > 0 then steps = Some(bd.toInt)
                else diags += invalid(Vector(key), "expected a positive integer")
              case _ => diags += invalid(Vector(key), "expected a positive integer")
        case "model" =>
          value match
            case Json.Str(s) => modelStr = Some(s)
            case _           => diags += invalid(Vector("model"), "expected a string")
        case "variant" =>
          value match
            case Json.Str(s) => variantStr = Some(s)
            case _           => diags += invalid(Vector("variant"), "expected a string")
        case "disable" =>
          value match
            case Json.Bool(b) => disabled = b
            case _            => diags += invalid(Vector("disable"), "expected a boolean")
        case "tools" =>
          value match
            case obj @ Json.Obj(_) =>
              obj.fields.foreach { case (action, v) => permMap.update(action, v) }
            case _ => diags += invalid(Vector("tools"), "expected a record")
        case "permission" =>
          value match
            case obj @ Json.Obj(_) =>
              obj.fields.foreach { case (action, v) => permMap.update(action, v) }
            case Json.Str(_) =>
              permMap.update("*", value)
            case _ => diags += invalid(Vector("permission"), "expected a string or a record")
        case "options" =>
          value match
            case obj @ Json.Obj(_) => body ++= obj.fields
            case _                 => diags += invalid(Vector("options"), "expected a record")
        case "temperature" | "top_p" =>
          body += (key -> value)
        case _ =>
          body += (key -> value)
    }

    if diags.isEmpty then
      val rules = permMap.toList.flatMap { case (action, v) =>
        val normalized = PermissionMigration.normalizeAction(action)
        v match
          case Json.Bool(enabled) =>
            Vector(PermissionRule(normalized, "*", if enabled then "allow" else "deny"))
          case Json.Str(_) =>
            effectOf(v) match
              case Some(effect) => Vector(PermissionRule(normalized, "*", effect))
              case None =>
                diags += invalid(Vector("permission", action), "expected allow, deny, or ask")
                Vector.empty
          case entry @ Json.Obj(_) =>
            entry.fields.zipWithIndex.flatMap { case ((resource, effect), index) =>
              effectOf(effect) match
                case Some(decoded) => Vector(PermissionRule(normalized, resource, decoded))
                case None =>
                  diags += invalid(
                    Vector("permission", action, index.toString),
                    "expected allow, deny, or ask",
                  )
                  Vector.empty
            }
          case _ =>
            diags += invalid(
              Vector("permission", action),
              "expected a boolean, an effect, or a resource-to-effect record",
            )
            Vector.empty
      }
      if diags.isEmpty then
        val joinedModel = modelStr.map { m =>
          variantStr match
            case Some(v) if !m.contains("#") && v.matches("""[^#]+""") => m + "#" + v
            case _                                                     => m
        }
        val model = joinedModel.flatMap(parseLegacyModel)
        val resolvedMode = if forcePrimary then Some("primary") else mode
        val request =
          if body.isEmpty then None
          else Some(RequestSettings(None, Some(Json.Obj(body.toList*))))
        Right(
          Migrated(
            name, description, system, resolvedMode, hidden, color,
            steps, model, request, rules.toVector, disabled,
          )
        )
      else Left(diags.toVector)
    else Left(diags.toVector)

  private def effectOf(v: Json): Option[String] = v match
    case Json.Str(s) if Model.effects.contains(s) => Some(s)
    case _                                        => None

/** Shared decoder for native V2 agent objects (pinned ConfigAgent.Info in
  * vendor/opencode-profiles/upstream/schema/config-agent.ts): typed fields,
  * hex colors only, positive step counts, permission rule arrays, and the
  * provider/model[#variant] model selection string. Unknown keys are reported
  * as unsupported.
  */
object NativeDecode:

  final case class DecodedAgent(
    name: Option[String],
    description: Option[String],
    system: Option[String],
    mode: Option[String],
    hidden: Option[Boolean],
    color: Option[String],
    steps: Option[Int],
    model: Option[ModelRef],
    request: Option[RequestSettings],
    permissions: Vector[PermissionRule],
    disabled: Boolean,
    unsupported: Vector[String],
  )

  private def invalid(path: Vector[String], message: String): Diagnostic =
    Diagnostic("invalid", path, message)

  /** ConfigSelection: provider/model with an optional #variant suffix. */
  def parseNativeModel(s: String): Option[ModelRef] =
    val slash = s.indexOf('/')
    if slash <= 0 || slash == s.length - 1 || s.substring(0, slash).contains("#") then None
    else
      val rest = s.substring(slash + 1)
      val hash = rest.indexOf('#')
      if hash < 0 then Some(ModelRef(s.substring(0, slash), rest, None))
      else if hash == 0 || hash == rest.length - 1 || rest.substring(hash + 1).contains("#") then None
      else Some(ModelRef(s.substring(0, slash), rest.substring(0, hash), Some(rest.substring(hash + 1))))

  def decode(fields: Json.Obj, systemFallback: Option[String]): Either[Vector[Diagnostic], DecodedAgent] =
    val diags = mutable.ListBuffer.empty[Diagnostic]
    val unsupported = mutable.ListBuffer.empty[String]

    var name: Option[String] = None
    var description: Option[String] = None
    var system: Option[String] = None
    var mode: Option[String] = None
    var hidden: Option[Boolean] = None
    var color: Option[String] = None
    var steps: Option[Int] = None
    var model: Option[ModelRef] = None
    var request: Option[RequestSettings] = None
    var permissions = Vector.empty[PermissionRule]
    var disabled = false

    fields.fields.foreach { case (key, value) =>
      key match
        case "name" =>
          value match
            case Json.Str(s) => name = Some(s)
            case _           => diags += invalid(Vector("name"), "expected a string")
        case "description" =>
          value match
            case Json.Str(s) => description = Some(s)
            case _           => diags += invalid(Vector("description"), "expected a string")
        case "system" =>
          value match
            case Json.Str(s) => system = Some(s)
            case _           => diags += invalid(Vector("system"), "expected a string")
        case "mode" =>
          value match
            case Json.Str(s) if Model.modes.contains(s) => mode = Some(s)
            case _                                      => diags += invalid(Vector("mode"), "expected subagent, primary, or all")
        case "hidden" =>
          value match
            case Json.Bool(b) => hidden = Some(b)
            case _            => diags += invalid(Vector("hidden"), "expected a boolean")
        case "color" =>
          value match
            case Json.Str(s) if s.matches("""#[0-9a-fA-F]{6}""") => color = Some(s)
            case Json.Str(_)                                     => () // invalid color: dropped, agent survives
            case _                                               => diags += invalid(Vector("color"), "expected a string")
        case "steps" =>
          value match
            case Json.Num(n) =>
              val bd = BigDecimal(n)
              if bd.isValidInt && bd.toInt > 0 then steps = Some(bd.toInt)
              else diags += invalid(Vector("steps"), "expected a positive integer")
            case _ => diags += invalid(Vector("steps"), "expected a positive integer")
        case "model" | "variant" =>
          () // handled below, in order
        case "request" =>
          value match
            case obj @ Json.Obj(_) =>
              val headers = obj.get("headers").collect { case h @ Json.Obj(_) => h }
              val body = obj.get("body").collect { case b @ Json.Obj(_) => b }
              val headerMap: Option[Map[String, String]] = headers.map { h =>
                h.fields.collect { case (k, Json.Str(v)) => k -> v }.toMap
              }
              val headersOk = headers.forall(h => h.fields.forall(_._2.isInstanceOf[Json.Str]))
              if headersOk then request = Some(RequestSettings(headerMap, body))
              else diags += invalid(Vector("request", "headers"), "expected a record of strings")
            case _ => diags += invalid(Vector("request"), "expected a record")
        case "permissions" =>
          value match
            case Json.Arr(items) =>
              val collected = mutable.ListBuffer.empty[PermissionRule]
              items.zipWithIndex.foreach { case (item, index) =>
                item match
                  case ruleObj @ Json.Obj(_) =>
                    val action = ruleObj.get("action").collect { case Json.Str(s) => s }
                    val resource = ruleObj.get("resource").collect { case Json.Str(s) => s }
                    val effect = ruleObj.get("effect").collect { case Json.Str(s) => s }
                    (action, resource, effect) match
                      case (Some(a), Some(r), Some(e)) if Model.effects.contains(e) =>
                        collected += PermissionRule(a, r, e)
                      case _ =>
                        diags += invalid(
                          Vector("permissions", index.toString),
                          "expected a permission rule with allow, deny, or ask",
                        )
                  case _ =>
                    diags += invalid(Vector("permissions", index.toString), "expected a permission rule")
              }
              permissions = collected.toVector
            case _ => diags += invalid(Vector("permissions"), "expected an array")
        case "disabled" =>
          value match
            case Json.Bool(b) => disabled = b
            case _            => diags += invalid(Vector("disabled"), "expected a boolean")
        case _ =>
          unsupported += key
    }

    // model/variant: joined then parsed with the native selection regex.
    if diags.isEmpty then
      val modelStr = fields.get("model").collect { case Json.Str(s) => s }
      val variantStr = fields.get("variant").collect { case Json.Str(s) => s }
      val joined = modelStr.map { m =>
        variantStr match
          case Some(v) if !m.contains("#") && v.matches("""[^#]+""") => m + "#" + v
          case _                                                     => m
      }
      joined match
        case Some(m) =>
          parseNativeModel(m) match
            case Some(ref) => model = Some(ref)
            case None      => diags += invalid(Vector("model"), "expected provider/model[#variant]")
        case None => ()

    if diags.isEmpty then
      Right(
        DecodedAgent(
          name, description, system.orElse(systemFallback), mode, hidden, color, steps,
          model, request, permissions, disabled, unsupported.toVector,
        )
      )
    else Left(diags.toVector)
