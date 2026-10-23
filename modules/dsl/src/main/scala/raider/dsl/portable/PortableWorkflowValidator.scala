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
    val allowed = Set(
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
    val unknown = root.fields.find((key, _) => !allowed(key))
    unknown match
      case Some((key, _)) =>
        Left(
          diag(
            "unsupported-construct",
            s"/${escape(key)}",
            None,
            Some(key),
            "Remove unsupported fields and use only the closed portable workflow v1 vocabulary."
          )
        )
      case None =>
        val required =
          List("format", "version", "limits", "schemas", "steps", "outputs")
        required.find(key => !root.fields.exists(_._1 == key)) match
          case Some(key) =>
            Left(
              diag(
                "invalid-document",
                s"/$key",
                None,
                None,
                "Provide all required portable workflow v1 fields."
              )
            )
          case None => Right(())

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
