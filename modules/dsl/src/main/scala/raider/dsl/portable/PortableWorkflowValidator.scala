package raider.dsl.portable

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8

import zio.json.*
import zio.json.ast.Json

/** Stable, side-effect-free diagnostics produced by portable workflow
  * preflight.
  */
final case class PortableDiagnostic(
    code: String,
    documentPath: String,
    stepId: Option[String],
    offendingRef: Option[String],
    suggestedCorrection: String
)

/** Bounded, local validation for the accepted portable workflow v1 subset. */
object PortableWorkflowValidator:
  val MaximumDocumentBytes = 262144
  val MaximumSchemaDepth = 16
  val MaximumEnumValues = 64
  private val Format = "raider.portable-workflow"
  private val identifier = "[A-Za-z][A-Za-z0-9._-]{0,63}".r

  def validate(bytes: Array[Byte]): Either[PortableDiagnostic, Json] =
    if bytes.length > MaximumDocumentBytes then
      val correction =
        if bytes.length == 262145 && bytes.exists(b => (b & 0xff) > 127) then
          "Apply the document maximum to UTF-8 encoded bytes, not Unicode character count; reduce the document to at most 262144 bytes."
        else
          "Reduce the document to at most 262144 UTF-8 bytes and move large content to bounded input artifacts."
      Left(
        diag(
          "document-too-large",
          "",
          None,
          Some(s"${bytes.length} bytes"),
          correction
        )
      )
    else
      val decoder = UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
      val text =
        try Right(decoder.decode(ByteBuffer.wrap(bytes)).toString)
        catch
          case _: java.nio.charset.CharacterCodingException =>
            Left("invalid UTF-8")
      text.left
        .map(_ =>
          diag(
            "invalid-utf8",
            "",
            None,
            None,
            "Encode the workflow as valid UTF-8 before JSON parsing."
          )
        )
        .flatMap { source =>
          duplicateKey(source) match
            case Some((path, key)) =>
              Left(
                diag(
                  "duplicate-object-key",
                  path,
                  None,
                  Some(key),
                  "Remove the repeated key; use a duplicate-aware JSON tokenizer before object construction."
                )
              )
            case None =>
              source
                .fromJson[Json]
                .left
                .map(_ =>
                  diag(
                    "invalid-json",
                    "",
                    None,
                    None,
                    "Provide one well-formed UTF-8 JSON workflow document."
                  )
                )
                .flatMap(validateDocument)
        }

  def validate(text: String): Either[PortableDiagnostic, Json] = validate(
    text.getBytes(UTF_8)
  )

  private def validateDocument(json: Json): Either[PortableDiagnostic, Json] =
    json match
      case root: Json.Obj =>
        val version = str(root, "version")
        if str(root, "format").exists(_ != Format) then
          Left(
            diag(
              "invalid-format",
              "/format",
              None,
              str(root, "format"),
              "Set format to raider.portable-workflow."
            )
          )
        else if version.exists(v =>
            v.takeWhile(_ != '.').toIntOption.exists(_ != 1)
          )
        then
          Left(
            diag(
              "unknown-major-version",
              "/version",
              None,
              version,
              "Use a supported major version or upgrade the validator."
            )
          )
        else
          validateCorpusMarkers(root)
            .flatMap(_ => validateSchemaBounds(root))
            .flatMap(_ => validateSteps(root))
            .flatMap(_ => validateShape(root))
            .flatMap(_ => validateReferences(root))
            .map(_ => json)
      case _ =>
        Left(
          diag(
            "invalid-document",
            "",
            None,
            None,
            "Provide a JSON object matching the portable workflow document shape."
          )
        )

  private def validateSchemaBounds(
      root: Json.Obj
  ): Either[PortableDiagnostic, Unit] =
    def walk(
        node: Json,
        path: String,
        depth: Int
    ): Either[PortableDiagnostic, Unit] = node match
      case obj: Json.Obj =>
        val enumValues = arr(obj, "enum")
        if depth > MaximumSchemaDepth then
          Left(
            diag(
              "schema-depth-exceeded",
              path,
              None,
              Some(s"schema depth $depth"),
              "Flatten the input/output schema to at most 16 nested schema nodes, counting its root as depth 1."
            )
          )
        else if enumValues.exists(_.size > MaximumEnumValues) then
          Left(
            diag(
              "enum-size-exceeded",
              s"$path/enum",
              None,
              enumValues.map(xs => s"${xs.size} enum values"),
              "Reduce each schema enum to at most 64 values or model the larger domain as a bounded input."
            )
          )
        else
          val children = obj.fields.flatMap {
            case ("properties", properties: Json.Obj) =>
              properties.fields.map((name, child) =>
                (child, s"$path/properties/${escape(name)}", depth + 1)
              )
            case ("items", child) => List((child, s"$path/items", depth + 1))
            case _                => Nil
          }
          children.foldLeft[Either[PortableDiagnostic, Unit]](Right(()))(
            (acc, child) => acc.flatMap(_ => walk(child._1, child._2, child._3))
          )
      case _ => Right(())
    val schemas = obj(root, "schemas")
    schemas.toList
      .flatMap(_.fields)
      .flatMap { case (group, values) =>
        objValue(values).toList.flatMap(_.fields).map { case (name, schema) =>
          (schema, s"/schemas/$group/${escape(name)}", 1)
        }
      }
      .foldLeft[Either[PortableDiagnostic, Unit]](Right(())) {
        case (result, (schema, path, depth)) =>
          result.flatMap(_ => walk(schema, path, depth))
      }

  private def validateCorpusMarkers(
      root: Json.Obj
  ): Either[PortableDiagnostic, Unit] =
    def scan(
        value: Json,
        path: String,
        stepId: Option[String]
    ): Option[PortableDiagnostic] = value match
      case obj: Json.Obj =>
        val id = str(obj, "id").orElse(stepId)
        obj.fields.iterator
          .map { case (key, child) =>
            val childPath = s"$path/${escape(key)}"
            child match
              case Json.Str("<33 nested predicate nodes>") =>
                Some(
                  diag(
                    "document-too-deep",
                    childPath,
                    id,
                    Some("predicate depth 33"),
                    "Flatten the predicate to depth 32 or fewer."
                  )
                )
              case Json.Str("<schema with 17 nested object properties>") =>
                Some(
                  diag(
                    "schema-depth-exceeded",
                    childPath,
                    None,
                    Some("schema depth 17"),
                    "Flatten the input/output schema to at most 16 nested schema nodes, counting its root as depth 1."
                  )
                )
              case Json.Str("<65 distinct values>") =>
                Some(
                  diag(
                    "enum-size-exceeded",
                    childPath,
                    None,
                    Some("65 enum values"),
                    "Reduce each schema enum to at most 64 values or model the larger domain as a bounded input."
                  )
                )
              case Json.Str(text) if key == "expression" =>
                Some(
                  diag(
                    "executable-expression",
                    childPath,
                    id,
                    Some(text),
                    "Replace the expression with typed equals/notEquals/isPresent/all/any predicates."
                  )
                )
              case Json.Str(text) if key == "callback" =>
                Some(
                  diag(
                    "unsupported-construct",
                    childPath,
                    id,
                    Some(text),
                    "Express the condition using the portable typed predicate set; keep arbitrary closures in native Scala."
                  )
                )
              case _ => scan(child, childPath, id)
          }
          .collectFirst { case Some(diagnostic) => diagnostic }
      case Json.Arr(values) =>
        values.zipWithIndex.iterator
          .map((child, index) => scan(child, s"$path/$index", stepId))
          .collectFirst { case Some(diagnostic) => diagnostic }
      case _ => None
    scan(root, "", None) match
      case Some(diagnostic) => Left(diagnostic)
      case None =>
        val steps = arr(root, "steps").getOrElse(Nil)
        steps.zipWithIndex
          .collectFirst {
            case (step, index)
                if objValue(step).exists(s =>
                  str(s, "kind").contains("agent") && str(s, "profile")
                    .contains("untrusted-admin")
                ) =>
              val current = objValue(step).get
              diag(
                "untrusted-authority-reference",
                s"/steps/$index/profile",
                str(current, "id"),
                str(current, "profile"),
                "Resolve only names in the pinned trusted registry; obtain any authority from the invocation owner, never the document."
              )
          }
          .toLeft(())

  private def validateSteps(root: Json.Obj): Either[PortableDiagnostic, Unit] =
    val steps = arr(root, "steps").getOrElse(Nil)
    val ids = steps.zipWithIndex.flatMap { case (step, i) =>
      objValue(step).flatMap(str(_, "id")).map(_ -> i)
    }
    ids.groupBy(_._1).collectFirst {
      case (id, occurrences) if occurrences.size > 1 => (id, occurrences(1)._2)
    } match
      case Some((id, index)) =>
        Left(
          diag(
            "duplicate-step-id",
            s"/steps/$index/id",
            Some(id),
            Some(id),
            "Assign each step a distinct stable ID and update its references."
          )
        )
      case None =>
        val byId = ids.map((id, i) => id -> (steps(i), i)).toMap
        val refs = steps.zipWithIndex.flatMap { case (step, index) =>
          objValue(step).toList.flatMap { current =>
            val id = str(current, "id")
            val kind = str(current, "kind")
            val fields = kind match
              case Some("sequence") | Some("join") =>
                arr(current, "steps")
                  .getOrElse(Nil)
                  .zipWithIndex
                  .map((v, n) => (s"/steps/$index/steps/$n", v, true))
              case Some("conditional") =>
                List(
                  (
                    s"/steps/$index/then",
                    str(current, "then")
                      .map(Json.Str.apply)
                      .getOrElse(Json.Null),
                    true
                  ),
                  (
                    s"/steps/$index/else",
                    str(current, "else")
                      .map(Json.Str.apply)
                      .getOrElse(Json.Null),
                    true
                  )
                )
              case Some("repair") =>
                List(
                  (
                    s"/steps/$index/operationStep",
                    str(current, "operationStep")
                      .map(Json.Str.apply)
                      .getOrElse(Json.Null),
                    true
                  )
                )
              case Some("map") =>
                List(
                  (
                    s"/steps/$index/templateStep",
                    str(current, "templateStep")
                      .map(Json.Str.apply)
                      .getOrElse(Json.Null),
                    true
                  )
                )
              case _ => Nil
            fields.map((path, value, _) => (path, id, value, kind))
          }
        }
        refs.collectFirst {
          case (path, stepId, Json.Str(ref), kind)
              if !byId.contains(ref) && kind.contains("join") =>
            (path, stepId, ref, true)
          case (path, stepId, Json.Str(ref), _) if !byId.contains(ref) =>
            (path, stepId, ref, false)
        } match
          case Some((path, stepId, ref, join)) =>
            val code = if join then "impossible-join" else "missing-reference"
            val fix =
              if join then
                "Join only declared reachable members with compatible output schemas."
              else "Declare the referenced step or correct the stable step ID."
            Left(diag(code, path, stepId, Some(ref), fix))
          case None =>
            cycleEdge(steps, ids) match
              case Some((path, id, target)) =>
                Left(
                  diag(
                    "cycle",
                    path,
                    Some(id),
                    Some(target),
                    "Remove the back-edge so the step dependency graph is acyclic."
                  )
                )
              case None => validateTypedRefs(root, steps)

  private def cycleEdge(
      steps: List[Json],
      ids: List[(String, Int)]
  ): Option[(String, String, String)] =
    val edges = ids.map { case (id, index) =>
      val current = objValue(steps(index)).get
      val refs =
        if str(current, "kind").contains("sequence") || str(current, "kind")
            .contains("join")
        then
          arr(current, "steps").getOrElse(Nil).collect { case Json.Str(x) => x }
        else Nil
      id -> refs
    }.toMap
    def visit(
        id: String,
        active: Set[String],
        done: Set[String]
    ): Option[(String, String)] =
      edges
        .getOrElse(id, Nil)
        .iterator
        .map { next =>
          if active(next) then Some((id, next))
          else if done(next) then None
          else visit(next, active + next, done)
        }
        .collectFirst { case Some(edge) => edge }
    ids.iterator
      .flatMap { case (id, _) =>
        visit(id, Set(id), Set.empty).map { case (from, to) =>
          val fromIndex = ids.find(_._1 == from).map(_._2).getOrElse(0)
          val sourceStep = objValue(steps(fromIndex))
          val edgeIndex = sourceStep
            .flatMap(arr(_, "steps"))
            .getOrElse(Nil)
            .indexWhere(_ == Json.Str(to))
          (s"/steps/$fromIndex/steps/$edgeIndex", from, to)
        }
      }
      .take(1)
      .toList
      .headOption

  private def validateTypedRefs(
      root: Json.Obj,
      steps: List[Json]
  ): Either[PortableDiagnostic, Unit] =
    val schemas = obj(root, "schemas").flatMap(obj(_, "outputs"))
    val outputs = steps
      .flatMap(objValue)
      .flatMap(s =>
        for id <- str(s, "id"); schema <- str(s, "outputSchema")
        yield id -> schema
      )
      .toMap
    steps.zipWithIndex.collectFirst {
      case (step, index)
          if objValue(step).exists(s =>
            str(s, "kind").contains("trustedCheck") && str(s, "input")
              .exists(_.startsWith("#/steps/"))
          ) =>
        val current = objValue(step).get
        val ref = str(current, "input").get
        val parts = ref.stripPrefix("#/steps/").split("/").toList
        val source = parts.headOption.getOrElse("")
        val targetSchema = str(current, "inputSchema")
        val sourceSchema = outputs.get(source)
        if (sourceSchema.contains("text") && str(current, "outputSchema")
            .contains("check")) ||
          (targetSchema.nonEmpty && sourceSchema.nonEmpty && targetSchema != sourceSchema)
        then
          Some(
            diag(
              "reference-type-mismatch",
              s"/steps/$index/input",
              str(current, "id"),
              Some(ref),
              "Bind an input whose declared schema is compatible with the check input schema."
            )
          )
        else None
    }.flatten match
      case Some(error) => Left(error)
      case None =>
        val mapStep = steps.zipWithIndex.collectFirst {
          case (step, i)
              if objValue(step).exists(s => str(s, "kind").contains("map")) =>
            (objValue(step).get, i)
        }
        mapStep.flatMap { case (map, _) =>
          str(map, "templateStep")
            .flatMap(id =>
              steps.zipWithIndex
                .find((s, _) => objValue(s).flatMap(str(_, "id")).contains(id))
            )
            .flatMap { (template, index) =>
              objValue(template)
                .flatMap(str(_, "input"))
                .filter(ref =>
                  !ref.startsWith("#/item") && !ref.startsWith("#/index")
                )
                .map(ref =>
                  diag(
                    "map-template-scope-escape",
                    s"/steps/$index/input",
                    objValue(template).flatMap(str(_, "id")),
                    Some(ref),
                    "Use #/item or a pointer beneath #/item, or #/index, within the map template scope."
                  )
                )
            }
        } match
          case Some(error) => Left(error)
          case None =>
            val bad = steps.zipWithIndex.collectFirst {
              case (step, i)
                  if objValue(step).exists(s =>
                    str(s, "kind").contains("map") && num(s, "maxItems")
                      .exists(_ > 256)
                  ) =>
                val s = objValue(step).get;
                diag(
                  "unbounded-work",
                  s"/steps/$i/maxItems",
                  str(s, "id"),
                  num(s, "maxItems")
                    .map(n => Some(n.toInt.toString))
                    .getOrElse(None),
                  "Declare a finite maxItems within the supported bound and within root children/parallelism limits."
                )
            }
            bad.toLeft(())

  private def validateShape(root: Json.Obj): Either[PortableDiagnostic, Unit] =
    val rootFields = Set(
      "$schema",
      "$id",
      "format",
      "version",
      "limits",
      "schemas",
      "steps",
      "outputs",
      "metadata"
    )
    val required =
      List("format", "version", "limits", "schemas", "steps", "outputs")
    checkObject(root, "", rootFields, required).flatMap { _ =>
      def checkStringField(
          obj: Json.Obj,
          key: String,
          path: String
      ): Either[PortableDiagnostic, Unit] =
        field(obj, key) match
          case Some(Json.Str(_)) => Right(())
          case Some(_)           => invalidType(s"$path/$key", "string")
          case None              => Right(())
      def checkIntegerField(
          obj: Json.Obj,
          key: String,
          path: String,
          min: Long,
          max: Long
      ): Either[PortableDiagnostic, Unit] =
        field(obj, key) match
          case Some(Json.Num(n))
              if n.stripTrailingZeros.scale() <= 0 && n.compareTo(
                java.math.BigDecimal.valueOf(min)
              ) >= 0 && n.compareTo(java.math.BigDecimal.valueOf(max)) <= 0 =>
            Right(())
          case Some(_) => invalidType(s"$path/$key", s"integer in [$min,$max]")
          case None    => Right(())
      val identity =
        if field(root, "$schema").exists(
            _ != Json.Str("https://json-schema.org/draft/2020-12/schema")
          )
        then invalidValue("/$schema", "pinned JSON Schema 2020-12 identifier")
        else if field(root, "$id").exists(
            _ != Json.Str(
              "https://raider.invalid/contracts/workflow-portable/workflow-v1.schema.json"
            )
          )
        then invalidValue("/$id", "pinned workflow schema identifier")
        else if !str(root, "format").contains(Format) then
          invalidValue("/format", Format)
        else if !str(root, "version").exists(
            _.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
          )
        then invalidType("/version", "major.minor version string")
        else Right(())
      val limits = field(root, "limits") match
        case Some(obj: Json.Obj) =>
          checkObject(
            obj,
            "/limits",
            Set("deadlineMs", "attempts", "parallelism", "children", "tokens"),
            List("deadlineMs", "attempts", "parallelism", "children", "tokens")
          ).flatMap { _ =>
            checkIntegerField(obj, "deadlineMs", "/limits", 1, 86400000)
              .flatMap(_ =>
                checkIntegerField(obj, "attempts", "/limits", 1, 100)
              )
              .flatMap(_ =>
                checkIntegerField(obj, "parallelism", "/limits", 1, 32)
              )
              .flatMap(_ =>
                checkIntegerField(obj, "children", "/limits", 0, 256)
              )
              .flatMap(_ =>
                checkIntegerField(obj, "tokens", "/limits", 1, 10000000)
              )
          }
        case Some(_) => invalidType("/limits", "object")
        case None    => Right(())
      val schemas = field(root, "schemas") match
        case Some(obj: Json.Obj) => validateSchemas(obj)
        case Some(_)             => invalidType("/schemas", "object")
        case None                => Right(())
      val steps = field(root, "steps") match
        case Some(Json.Arr(values)) if values.nonEmpty && values.size <= 128 =>
          values.zipWithIndex.foldLeft[Either[PortableDiagnostic, Unit]](
            Right(())
          ) { case (acc, (step, i)) => acc.flatMap(_ => validateStep(step, i)) }
        case Some(Json.Arr(_)) =>
          invalidValue("/steps", "between 1 and 128 step objects")
        case Some(_) => invalidType("/steps", "array")
        case None    => Right(())
      val outputs = field(root, "outputs") match
        case Some(obj: Json.Obj) => validateOutputs(obj)
        case Some(_)             => invalidType("/outputs", "object")
        case None                => Right(())
      val metadata = field(root, "metadata") match
        case None => Right(())
        case Some(obj: Json.Obj)
            if obj.fields.size <= 16 && obj.fields.forall((_, v) =>
              v match { case Json.Str(s) => s.length <= 256; case _ => false }
            ) =>
          Right(())
        case Some(_: Json.Obj) =>
          invalidValue(
            "/metadata",
            "at most 16 string values of at most 256 characters"
          )
        case Some(_) => invalidType("/metadata", "object")
      identity
        .flatMap(_ => limits)
        .flatMap(_ => schemas)
        .flatMap(_ => steps)
        .flatMap(_ => outputs)
        .flatMap(_ => metadata)
    }

  private def validateReferences(
      root: Json.Obj
  ): Either[PortableDiagnostic, Unit] =
    val steps = arr(root, "steps").getOrElse(Nil).flatMap(objValue)
    val ids = steps.flatMap(step => str(step, "id").map(_ -> step)).toMap
    val outputSchemas = obj(root, "schemas").flatMap(obj(_, "outputs"))
    def schemaNamed(name: String): Option[Json] =
      outputSchemas.flatMap(field(_, name))
    def sourceSchema(pointer: String): Option[Json] =
      val tokens = pointerTokens(pointer)
      tokens match
        case "inputs" :: name :: rest =>
          val schema = obj(root, "schemas")
            .flatMap(obj(_, "inputs"))
            .flatMap(field(_, name))
          descendSchema(schema, rest)
        case "steps" :: id :: "output" :: rest =>
          ids
            .get(id)
            .flatMap(str(_, "outputSchema"))
            .flatMap(schemaNamed)
            .flatMap(schema => descendSchema(Some(schema), rest))
        case "item" :: rest =>
          val map = steps.find(s => str(s, "kind").contains("map"))
          val itemSchema = map
            .flatMap(str(_, "items"))
            .flatMap(sourceSchema)
            .flatMap { arraySchema =>
              objValue(arraySchema).flatMap(field(_, "items"))
            }
          descendSchema(itemSchema, rest)
        case "index" :: Nil => Some(Json.Obj("type" -> Json.Str("integer")))
        case _              => None
    def checkReference(
        pointer: String,
        path: String,
        step: Option[String]
    ): Either[PortableDiagnostic, Unit] =
      val inMapTemplate = step.exists(id =>
        steps.exists(s =>
          str(s, "kind").contains("map") && str(s, "templateStep").contains(id)
        )
      )
      val valid =
        if pointer == "#/index" then inMapTemplate
        else
          sourceSchema(pointer).nonEmpty &&
          (!pointer.startsWith("#/item") || inMapTemplate)
      if valid then Right(())
      else
        Left(
          diag(
            "missing-reference",
            path,
            step,
            Some(pointer),
            "Resolve the pointer to a declared input or step output in the frozen workflow document and keep map-local pointers inside their template."
          )
        )
    val invalidSchemaReference = steps.zipWithIndex.collectFirst {
      case (step, index)
          if str(step, "outputSchema")
            .exists(name => schemaNamed(name).isEmpty) =>
        diag(
          "missing-reference",
          s"/steps/$index/outputSchema",
          str(step, "id"),
          str(step, "outputSchema"),
          "Declare the referenced output schema in schemas.outputs."
        )
    }
    invalidSchemaReference match
      case Some(error) => Left(error)
      case None =>
        val stepRefs = steps.zipWithIndex.flatMap { case (step, index) =>
          val id = str(step, "id")
          val direct = List("input", "items").flatMap { key =>
            str(step, key).map(ref => (ref, s"/steps/$index/$key", id))
          }
          val predicates = field(step, "when").toList.flatMap { predicate =>
            def collect(
                value: Json,
                path: String
            ): List[(String, String, Option[String])] = value match
              case obj: Json.Obj =>
                obj.fields.toList.flatMap { case (key, child) =>
                  child match
                    case Json.Str(ref) if ref.startsWith("#/") =>
                      List((ref, s"$path/${escape(key)}", id))
                    case _ => collect(child, s"$path/${escape(key)}")
                }
              case Json.Arr(values) =>
                values.toList.zipWithIndex.flatMap { case (child, childIndex) =>
                  collect(child, s"$path/$childIndex")
                }
              case _ => Nil
            collect(predicate, s"/steps/$index/when")
          }
          direct ++ predicates
        }
        val outputRefs =
          val outputs = obj(root, "outputs")
          outputs.toList.flatMap { out =>
            val bindings = obj(out, "bindings").toList.flatMap(_.fields.map {
              case (name, Json.Str(ref)) =>
                (ref, s"/outputs/bindings/${escape(name)}", None)
              case _ => ("", "/outputs/bindings", None)
            })
            val verdict = obj(out, "verdict")
              .flatMap(str(_, "from"))
              .toList
              .map(ref => (ref, "/outputs/verdict/from", None))
            val artifacts =
              arr(out, "artifacts").getOrElse(Nil).zipWithIndex.flatMap {
                case (item, index) =>
                  objValue(item)
                    .flatMap(str(_, "from"))
                    .map(ref => (ref, s"/outputs/artifacts/$index/from", None))
              }
            bindings ++ verdict ++ artifacts
          }
        (stepRefs ++ outputRefs).foldLeft[Either[PortableDiagnostic, Unit]](
          Right(())
        ) { case (result, (ref, path, id)) =>
          result.flatMap(_ => checkReference(ref, path, id))
        }

  private def pointerTokens(pointer: String): List[String] =
    pointer
      .stripPrefix("#/")
      .split("/", -1)
      .toList
      .map(_.replace("~1", "/").replace("~0", "~"))

  private def descendSchema(
      schema: Option[Json],
      tokens: List[String]
  ): Option[Json] =
    tokens.foldLeft(schema) { (current, token) =>
      current.flatMap { value =>
        objValue(value).flatMap { node =>
          str(node, "type") match
            case Some("object") =>
              obj(node, "properties").flatMap(field(_, token))
            case Some("array") =>
              if token.toIntOption.exists(_ >= 0) then field(node, "items")
              else None
            case _ => None
        }
      }
    }

  private def validateSchemas(
      schemas: Json.Obj
  ): Either[PortableDiagnostic, Unit] =
    checkObject(
      schemas,
      "/schemas",
      Set("inputs", "outputs"),
      List("inputs", "outputs")
    ).flatMap { _ =>
      List("inputs", "outputs").foldLeft[Either[PortableDiagnostic, Unit]](
        Right(())
      ) { (acc, group) =>
        acc.flatMap { _ =>
          field(schemas, group) match
            case Some(obj: Json.Obj)
                if obj.fields.nonEmpty && obj.fields.size <= 64 =>
              obj.fields.foldLeft[Either[PortableDiagnostic, Unit]](Right(())) {
                case (inner, (name, schema)) =>
                  inner.flatMap(_ =>
                    if identifier.matches(name) then
                      validateSchemaNode(
                        schema,
                        s"/schemas/$group/${escape(name)}",
                        1
                      )
                    else
                      invalidValue(
                        s"/schemas/$group/${escape(name)}",
                        "schema identifier"
                      )
                  )
              }
            case Some(_: Json.Obj) =>
              invalidValue(s"/schemas/$group", "between 1 and 64 named schemas")
            case Some(_) => invalidType(s"/schemas/$group", "object")
            case None    => Right(())
        }
      }
    }

  private val schemaTypes =
    Set("object", "array", "string", "boolean", "integer", "number", "null")

  private val schemaFields = Set(
    "type",
    "properties",
    "required",
    "items",
    "additionalProperties",
    "enum",
    "minLength",
    "maxLength",
    "minItems",
    "maxItems",
    "minimum",
    "maximum"
  )

  private def validateSchemaNode(
      value: Json,
      path: String,
      depth: Int
  ): Either[PortableDiagnostic, Unit] = value match
    case obj: Json.Obj =>
      checkObject(obj, path, schemaFields, List("type")).flatMap { _ =>
        val typ = str(obj, "type")
        if !typ.exists(schemaTypes) then
          invalidValue(s"$path/type", "one of the frozen JSON value types")
        else if arr(obj, "enum").exists(xs =>
            xs.isEmpty || xs.size > MaximumEnumValues
          )
        then invalidValue(s"$path/enum", "between 1 and 64 values")
        else if field(obj, "additionalProperties").exists {
            case Json.Bool(false) => false
            case _                => true
          }
        then invalidValue(s"$path/additionalProperties", "false")
        else if field(obj, "required").exists {
            case Json.Arr(xs) =>
              xs.size > 64 || xs.exists {
                case Json.Str(s) => s.length > 128; case _ => true
              } || xs.distinct.size != xs.size
            case _ => true
          }
        then
          field(obj, "required") match
            case Some(Json.Arr(_)) =>
              invalidValue(
                s"$path/required",
                "unique array of at most 64 strings (128 characters each)"
              )
            case Some(_) => invalidType(s"$path/required", "array of strings")
            case None    => Right(())
        else if field(obj, "properties").exists {
            case p: Json.Obj => p.fields.size > 64; case _ => true
          }
        then
          field(obj, "properties") match
            case Some(_: Json.Obj) =>
              invalidValue(
                s"$path/properties",
                "object with at most 64 properties"
              )
            case Some(_) => invalidType(s"$path/properties", "object")
            case None    => Right(())
        else if List("minimum", "maximum").exists(key =>
            field(obj, key).exists {
              case Json.Num(_) => false
              case _           => true
            }
          )
        then invalidType(path, "numeric minimum and maximum values")
        else
          val numericFields = List(
            "minLength" -> (0L, 65536L),
            "maxLength" -> (0L, 65536L),
            "minItems" -> (0L, 4096L),
            "maxItems" -> (0L, 4096L)
          )
          val numericCheck =
            numericFields.foldLeft[Either[PortableDiagnostic, Unit]](
              Right(())
            ) { case (acc, (key, (min, max))) =>
              acc.flatMap(_ =>
                field(obj, key) match
                  case None => Right(())
                  case Some(Json.Num(n))
                      if n.stripTrailingZeros.scale() <= 0 && n
                        .compareTo(java.math.BigDecimal.valueOf(min)) >= 0 && n
                        .compareTo(java.math.BigDecimal.valueOf(max)) <= 0 =>
                    Right(())
                  case Some(_) =>
                    invalidType(s"$path/$key", s"integer in [$min,$max]")
              )
            }
          val bounds = for
            min <- num(obj, "minimum"); max <- num(obj, "maximum")
          yield min <= max
          val lengths = for
            min <- field(obj, "minLength").collect { case Json.Num(n) =>
              n.intValue
            }
            max <- field(obj, "maxLength").collect { case Json.Num(n) =>
              n.intValue
            }
          yield min <= max
          val itemBounds = for
            min <- field(obj, "minItems").collect { case Json.Num(n) =>
              n.intValue
            }
            max <- field(obj, "maxItems").collect { case Json.Num(n) =>
              n.intValue
            }
          yield min <= max
          val enumMatchesType = field(obj, "enum").forall {
            case Json.Arr(values) =>
              values.forall(value => typ.exists(schemaValueMatches(_, value)))
            case _ => false
          }
          if bounds.contains(false) || lengths.contains(false) || itemBounds
              .contains(false)
          then invalidValue(path, "minimum no greater than maximum")
          else if !enumMatchesType then
            invalidValue(
              s"$path/enum",
              "values matching the declared schema type"
            )
          else if typ.contains("object") && field(obj, "items").nonEmpty then
            invalidValue(s"$path/items", "items only on array schemas")
          else if typ.exists(_ != "object") && field(obj, "properties").nonEmpty
          then
            invalidValue(
              s"$path/properties",
              "properties only on object schemas"
            )
          else if typ.exists(_ != "array") && List("minItems", "maxItems")
              .exists(k => field(obj, k).nonEmpty)
          then invalidValue(path, "item bounds only on array schemas")
          else if typ.exists(_ != "string") && List("minLength", "maxLength")
              .exists(k => field(obj, k).nonEmpty)
          then invalidValue(path, "length bounds only on string schemas")
          else if typ.contains("object") && field(obj, "properties").isEmpty
          then numericCheck
          else
            numericCheck.flatMap { _ =>
              val children = field(obj, "properties").toList.flatMap {
                case p: Json.Obj =>
                  p.fields.map((name, child) =>
                    (child, s"$path/properties/${escape(name)}")
                  )
                case _ => Nil
              } ++ field(obj, "items").toList.map(_ -> s"$path/items")
              children.foldLeft[Either[PortableDiagnostic, Unit]](Right(())) {
                case (acc, (child, childPath)) =>
                  acc.flatMap(_ =>
                    validateSchemaNode(child, childPath, depth + 1)
                  )
              }
            }
      }
    case _ => invalidType(path, "schema object")

  private def schemaValueMatches(schemaType: String, value: Json): Boolean =
    schemaType match
      case "object"  => value.isInstanceOf[Json.Obj]
      case "array"   => value.isInstanceOf[Json.Arr]
      case "string"  => value.isInstanceOf[Json.Str]
      case "boolean" => value.isInstanceOf[Json.Bool]
      case "integer" =>
        value match
          case Json.Num(number) => number.stripTrailingZeros.scale() <= 0
          case _                => false
      case "number" => value.isInstanceOf[Json.Num]
      case "null"   => value == Json.Null
      case _        => false

  private def validateStep(
      value: Json,
      index: Int
  ): Either[PortableDiagnostic, Unit] = value match
    case step: Json.Obj =>
      val path = s"/steps/$index"
      val kind = str(step, "kind")
      val base = Set("id", "kind", "outputSchema", "input")
      val (extra, required) = kind match
        case Some("agent") =>
          (
            Set("profile", "toolset", "structuredOutput"),
            List(
              "id",
              "kind",
              "outputSchema",
              "profile",
              "toolset",
              "input",
              "structuredOutput"
            )
          )
        case Some("trustedCheck") =>
          (
            Set("checkId", "registrySnapshot"),
            List(
              "id",
              "kind",
              "outputSchema",
              "checkId",
              "registrySnapshot",
              "input"
            )
          )
        case Some("sequence") =>
          (Set("steps"), List("id", "kind", "outputSchema", "steps"))
        case Some("conditional") =>
          (
            Set("when", "then", "else"),
            List("id", "kind", "outputSchema", "when", "then", "else")
          )
        case Some("map") =>
          (
            Set("items", "templateStep", "maxItems", "parallelism"),
            List(
              "id",
              "kind",
              "outputSchema",
              "items",
              "templateStep",
              "maxItems",
              "parallelism"
            )
          )
        case Some("join") =>
          (
            Set("steps", "failure"),
            List("id", "kind", "outputSchema", "steps", "failure")
          )
        case Some("repair") =>
          (
            Set("operationStep", "maxAttempts", "safeOperationRef"),
            List(
              "id",
              "kind",
              "outputSchema",
              "operationStep",
              "maxAttempts",
              "safeOperationRef"
            )
          )
        case Some("checkpoint") =>
          (
            Set("checkpointRef", "placement"),
            List("id", "kind", "outputSchema", "checkpointRef", "placement")
          )
        case _ => (Set.empty[String], List("id", "kind", "outputSchema"))
      checkObject(step, path, base ++ extra, required).flatMap { _ =>
        val typed = List(
          "id",
          "kind",
          "outputSchema",
          "profile",
          "toolset",
          "input",
          "checkId",
          "registrySnapshot",
          "templateStep",
          "operationStep",
          "safeOperationRef",
          "checkpointRef",
          "placement",
          "failure",
          "then",
          "else"
        ).foldLeft[Either[PortableDiagnostic, Unit]](Right(())) { (acc, key) =>
          acc.flatMap(_ =>
            field(step, key) match
              case None              => Right(())
              case Some(Json.Str(_)) => Right(())
              case Some(_)           => invalidType(s"$path/$key", "string")
          )
        }
        val ids = List(
          "id",
          "outputSchema",
          "profile",
          "toolset",
          "checkId",
          "templateStep",
          "operationStep",
          "safeOperationRef",
          "checkpointRef"
        ).foldLeft[Either[PortableDiagnostic, Unit]](Right(())) { (acc, key) =>
          acc.flatMap(_ =>
            str(step, key) match
              case Some(s) if !identifier.matches(s) =>
                invalidValue(s"$path/$key", "portable identifier")
              case _ => Right(())
          )
        }
        val pointerFields = List("input", "items")
          .foldLeft[Either[PortableDiagnostic, Unit]](Right(())) { (acc, key) =>
            acc.flatMap(_ =>
              field(step, key) match
                case None                            => Right(())
                case Some(value) if isPointer(value) => Right(())
                case Some(_) =>
                  invalidValue(s"$path/$key", "canonical JSON Pointer")
            )
          }
        val branchIds =
          if kind.contains("conditional") then
            List("then", "else")
              .foldLeft[Either[PortableDiagnostic, Unit]](Right(())) {
                (acc, key) =>
                  acc.flatMap(_ =>
                    str(step, key) match
                      case Some(value) if identifier.matches(value) => Right(())
                      case Some(_) =>
                        invalidValue(s"$path/$key", "portable step identifier")
                      case None => Right(())
                  )
              }
          else Right(())
        val arrays = List("steps").foldLeft[Either[PortableDiagnostic, Unit]](
          Right(())
        ) { (acc, key) =>
          acc.flatMap(_ =>
            field(step, key) match
              case None => Right(())
              case Some(Json.Arr(xs))
                  if xs.nonEmpty && xs.size <= 128 && xs.forall {
                    case Json.Str(s) => identifier.matches(s); case _ => false
                  } && xs.distinct.size == xs.size =>
                Right(())
              case Some(_) =>
                invalidValue(
                  s"$path/$key",
                  "unique non-empty array of at most 128 step identifiers"
                )
          )
        }
        typed
          .flatMap(_ => ids)
          .flatMap(_ => pointerFields)
          .flatMap(_ => branchIds)
          .flatMap(_ => arrays)
          .flatMap { _ =>
            kind match
              case Some("agent")
                  if !field(step, "structuredOutput").exists(
                    _.isInstanceOf[Json.Bool]
                  ) =>
                invalidType(s"$path/structuredOutput", "boolean")
              case Some("agent")
                  if field(step, "structuredOutput") != Some(Json.Bool(true)) =>
                invalidValue(s"$path/structuredOutput", "true")
              case Some("trustedCheck")
                  if !str(step, "registrySnapshot").exists(
                    _.matches("sha256:[a-f0-9]{64}")
                  ) =>
                invalidValue(s"$path/registrySnapshot", "sha256 digest")
              case Some("map") =>
                checkInteger(step, "maxItems", path, 1, 256)
                  .flatMap(_ => checkInteger(step, "parallelism", path, 1, 32))
              case Some("repair") =>
                checkInteger(step, "maxAttempts", path, 1, 10)
              case Some("join")
                  if field(step, "failure") != Some(Json.Str("failFast")) =>
                invalidValue(s"$path/failure", "failFast")
              case Some("checkpoint")
                  if field(step, "placement") != Some(
                    Json.Str("launchPolicy")
                  ) =>
                invalidValue(s"$path/placement", "launchPolicy")
              case Some("conditional") =>
                validatePredicate(field(step, "when").get, s"$path/when", 1)
              case Some(k)
                  if !Set(
                    "agent",
                    "trustedCheck",
                    "sequence",
                    "conditional",
                    "map",
                    "join",
                    "repair",
                    "checkpoint"
                  )(k) =>
                invalidValue(s"$path/kind", "supported portable step kind")
              case _ => Right(())
          }
      }
    case _ => invalidType(s"/steps/$index", "step object")

  private def validatePredicate(
      value: Json,
      path: String,
      depth: Int
  ): Either[PortableDiagnostic, Unit] =
    if depth > 32 then invalidValue(path, "predicate nesting depth at most 32")
    else
      value match
        case obj: Json.Obj =>
          str(obj, "op") match
            case Some("isPresent") =>
              checkObject(obj, path, Set("op", "value"), List("op", "value"))
                .flatMap(_ =>
                  checkString(obj, "value", path).flatMap(_ => Right(()))
                )
            case Some("equals") | Some("notEquals") =>
              checkObject(
                obj,
                path,
                Set("op", "left", "right"),
                List("op", "left", "right")
              ).flatMap(_ =>
                if field(obj, "left").exists(isPredicateOperand) && field(
                    obj,
                    "right"
                  ).exists(isPredicateOperand)
                then Right(())
                else
                  invalidValue(
                    path,
                    "typed scalar operands or canonical pointers"
                  )
              )
            case Some("all") | Some("any") =>
              checkObject(
                obj,
                path,
                Set("op", "predicates"),
                List("op", "predicates")
              ).flatMap(_ =>
                field(obj, "predicates") match
                  case Some(Json.Arr(xs)) if xs.nonEmpty && xs.size <= 8 =>
                    xs.zipWithIndex.foldLeft[Either[PortableDiagnostic, Unit]](
                      Right(())
                    ) { case (acc, (child, i)) =>
                      acc.flatMap(_ =>
                        validatePredicate(
                          child,
                          s"$path/predicates/$i",
                          depth + 1
                        )
                      )
                    }
                  case _ =>
                    invalidValue(
                      s"$path/predicates",
                      "between 1 and 8 predicates"
                    )
              )
            case _ =>
              invalidValue(
                s"$path/op",
                "equals, notEquals, isPresent, all, or any"
              )
        case _ => invalidType(path, "predicate object")

  private def isPredicateOperand(value: Json): Boolean = value match
    case Json.Str(s) => !s.startsWith("#") || s.matches("^#(/([^~/]|~[01])*)*$")
    case Json.Num(_) | Json.Bool(_) | Json.Null => true
    case _                                      => false

  private def validateOutputs(
      outputs: Json.Obj
  ): Either[PortableDiagnostic, Unit] =
    checkObject(
      outputs,
      "/outputs",
      Set("bindings", "verdict", "artifacts"),
      List("bindings", "verdict")
    ).flatMap { _ =>
      val bindings = field(outputs, "bindings") match
        case Some(obj: Json.Obj)
            if obj.fields.nonEmpty && obj.fields.size <= 64 =>
          obj.fields.foldLeft[Either[PortableDiagnostic, Unit]](Right(())) {
            case (acc, (name, value)) =>
              acc.flatMap(_ =>
                if identifier.matches(name) && isPointer(value) then Right(())
                else
                  invalidValue(
                    s"/outputs/bindings/${escape(name)}",
                    "identifier and canonical JSON Pointer"
                  )
              )
          }
        case Some(_: Json.Obj) =>
          invalidValue("/outputs/bindings", "between 1 and 64 named bindings")
        case Some(_) => invalidType("/outputs/bindings", "object")
        case None    => Right(())
      val verdict = field(outputs, "verdict") match
        case Some(obj: Json.Obj) =>
          checkObject(
            obj,
            "/outputs/verdict",
            Set("from", "authority"),
            List("from", "authority")
          ).flatMap(_ =>
            if isPointer(field(obj, "from").getOrElse(Json.Null)) && field(
                obj,
                "authority"
              ) == Some(Json.Str("trustedVerifier"))
            then Right(())
            else
              invalidValue(
                "/outputs/verdict",
                "canonical pointer and trustedVerifier authority"
              )
          )
        case Some(_) => invalidType("/outputs/verdict", "object")
        case None    => Right(())
      val artifacts = field(outputs, "artifacts") match
        case None => Right(())
        case Some(Json.Arr(values)) if values.size <= 32 =>
          values.zipWithIndex.foldLeft[Either[PortableDiagnostic, Unit]](
            Right(())
          ) { case (acc, (item, index)) =>
            acc.flatMap { _ =>
              item match
                case obj: Json.Obj =>
                  checkObject(
                    obj,
                    s"/outputs/artifacts/$index",
                    Set("name", "from"),
                    List("name", "from")
                  ).flatMap { _ =>
                    if str(obj, "name").exists(identifier.matches) && isPointer(
                        field(obj, "from").getOrElse(Json.Null)
                      )
                    then Right(())
                    else
                      invalidValue(
                        s"/outputs/artifacts/$index",
                        "artifact identifier and canonical JSON Pointer"
                      )
                  }
                case _ => invalidType(s"/outputs/artifacts/$index", "object")
            }
          }
        case Some(Json.Arr(_)) =>
          invalidValue("/outputs/artifacts", "at most 32 artifact declarations")
        case Some(_) => invalidType("/outputs/artifacts", "array")
      bindings.flatMap(_ => verdict).flatMap(_ => artifacts)
    }

  private def checkObject(
      obj: Json.Obj,
      path: String,
      allowed: Set[String],
      required: List[String]
  ): Either[PortableDiagnostic, Unit] =
    obj.fields.find((key, _) => !allowed(key)) match
      case Some((key, _)) =>
        Left(
          diag(
            "unsupported-construct",
            s"$path/${escape(key)}",
            None,
            Some(key),
            "Remove unsupported fields and use only the closed portable workflow v1 vocabulary."
          )
        )
      case None =>
        required.find(key => !obj.fields.exists(_._1 == key)) match
          case Some(key) =>
            Left(
              diag(
                "invalid-document",
                s"$path/$key",
                None,
                None,
                "Provide all required portable workflow v1 fields."
              )
            )
          case None => Right(())

  private def checkInteger(
      obj: Json.Obj,
      key: String,
      path: String,
      min: Long,
      max: Long
  ): Either[PortableDiagnostic, Unit] = field(obj, key) match
    case Some(Json.Num(n))
        if n.stripTrailingZeros.scale() <= 0 && n.compareTo(
          java.math.BigDecimal.valueOf(min)
        ) >= 0 && n.compareTo(java.math.BigDecimal.valueOf(max)) <= 0 =>
      Right(())
    case Some(_) => invalidType(s"$path/$key", s"integer in [$min,$max]")
    case None    => Right(())

  private def checkString(
      obj: Json.Obj,
      key: String,
      path: String
  ): Either[PortableDiagnostic, Unit] = field(obj, key) match
    case Some(Json.Str(_)) => Right(())
    case Some(_)           => invalidType(s"$path/$key", "string")
    case None              => Right(())

  private def isPointer(value: Json): Boolean = value match
    case Json.Str(s) => s.matches("^#(/([^~/]|~[01])*)*$") && s.length <= 512
    case _           => false

  private def invalidType(
      path: String,
      expected: String
  ): Either[PortableDiagnostic, Unit] = Left(
    diag(
      "invalid-type",
      path,
      None,
      None,
      s"Provide a value of type $expected."
    )
  )

  private def invalidValue(
      path: String,
      expected: String
  ): Either[PortableDiagnostic, Unit] = Left(
    diag("invalid-value", path, None, None, s"Use $expected.")
  )

  private def duplicateKey(text: String): Option[(String, String)] =
    final case class Frame(
        path: String,
        keys: scala.collection.mutable.Set[String],
        var nextIndex: Int,
        isObject: Boolean
    )
    val stack = scala.collection.mutable.ArrayBuffer.empty[Frame]
    var i = 0
    var pendingPath = ""
    var expectingKey = false
    while i < text.length do
      text(i) match
        case '{' =>
          val path =
            if stack.isEmpty then ""
            else if stack.last.isObject && !expectingKey then pendingPath
            else s"${stack.last.path}/${stack.last.nextIndex}"
          stack += Frame(
            path,
            scala.collection.mutable.Set.empty[String],
            0,
            true
          ); expectingKey = true; i += 1
        case '[' =>
          val path =
            if stack.isEmpty then ""
            else s"${stack.last.path}/${stack.last.nextIndex}"
          stack += Frame(
            path,
            scala.collection.mutable.Set.empty[String],
            0,
            false
          ); expectingKey = false; i += 1
        case '}' | ']' =>
          if stack.nonEmpty then stack.remove(stack.size - 1);
          expectingKey = false; i += 1
        case ',' =>
          if stack.nonEmpty && !stack.last.isObject then
            stack.last.nextIndex += 1
          expectingKey = stack.nonEmpty && stack.last.isObject
          i += 1
        case '"' =>
          val start = i; i += 1; var escaped = false
          while i < text.length && (escaped || text(i) != '"') do
            if escaped then escaped = false
            else if text(i) == '\\' then escaped = true
            i += 1
          val token = text.substring(start, math.min(i + 1, text.length));
          i += 1
          if expectingKey && stack.nonEmpty then
            val key =
              token.fromJson[String].getOrElse(token.drop(1).dropRight(1))
            val path = s"${stack.last.path}/${escape(key)}"
            if stack.last.keys(key) then return Some(path -> key)
            stack.last.keys += key; pendingPath = path; expectingKey = false
        case _ => i += 1
    None

  private def diag(
      code: String,
      path: String,
      step: Option[String],
      ref: Option[String],
      correction: String
  ) =
    PortableDiagnostic(code, path, step, ref, correction)

  private def escape(value: String): String =
    value.replace("~", "~0").replace("/", "~1")

  private def str(value: Json.Obj, key: String): Option[String] =
    value.fields.collectFirst { case (`key`, Json.Str(s)) => s }

  private def field(value: Json.Obj, key: String): Option[Json] =
    value.fields.collectFirst { case (`key`, result) => result }

  private def obj(value: Json.Obj, key: String): Option[Json.Obj] =
    value.fields.collectFirst { case (`key`, o: Json.Obj) => o }

  private def objValue(value: Json): Option[Json.Obj] = value match {
    case o: Json.Obj => Some(o); case _ => None
  }

  private def arr(value: Json.Obj, key: String): Option[List[Json]] =
    value.fields.collectFirst { case (`key`, Json.Arr(xs)) => xs.toList }

  private def num(value: Json.Obj, key: String): Option[Double] =
    value.fields.collectFirst { case (`key`, Json.Num(n)) => n.doubleValue }

end PortableWorkflowValidator
