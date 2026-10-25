package raider.dsl.portable

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicInteger

import raider.core.{Program, RaiderError, Task}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import scala.jdk.CollectionConverters.*

object PortableWorkflowValidatorSpec extends ZIOSpecDefault:

  private def repoRoot: Path =
    val cwd = Paths.get("").toAbsolutePath
    Iterator
      .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) => dir }
      .find(dir => Files.isRegularFile(dir.resolve("build.sbt")))
      .getOrElse(cwd)

  private def contract(relative: String): Path =
    repoRoot.resolve("contracts/workflow-portable").resolve(relative)

  private def diagnostic(result: Either[PortableDiagnostic, Json]) =
    result.swap.toOption

  private def parsedFixture(relative: String): Json.Obj =
    Files
      .readString(contract(s"valid/$relative"))
      .fromJson[Json]
      .toOption
      .get
      .asInstanceOf[Json.Obj]

  private def objField(obj: Json.Obj, name: String): Option[Json] =
    obj.fields.collectFirst { case (`name`, value) => value }

  /** Rebuild an object with one entry's value replaced. */
  private def objUpdated(obj: Json.Obj, key: String, value: Json): Json.Obj =
    Json.Obj(
      obj.fields.map(entry => if entry._1 == key then (key, value) else entry)
    )

  private def childObject(obj: Json.Obj, name: String): Option[Json.Obj] =
    objField(obj, name) match
      case Some(child: Json.Obj) => Some(child)
      case _                     => None

  /** Replace the value at a nested field path with a mutated value. */
  private def setField(
      target: Json,
      path: List[String],
      value: Json
  ): Json =
    target match
      case obj: Json.Obj =>
        path match
          case Nil         => obj
          case last :: Nil => objUpdated(obj, last, value)
          case head :: rest =>
            childObject(obj, head) match
              case Some(child) =>
                objUpdated(obj, head, setField(child, rest, value))
              case None => obj
      case _ => target

  /** Add a field to the object reached through a nested field path. */
  private def addField(
      target: Json,
      path: List[String],
      key: String,
      value: Json
  ): Json =
    target match
      case obj: Json.Obj =>
        path match
          case Nil => Json.Obj(obj.fields :+ (key -> value))
          case head :: rest =>
            childObject(obj, head) match
              case Some(child) =>
                objUpdated(obj, head, addField(child, rest, key, value))
              case None => obj
      case _ => target

  private def withStep(doc: Json, stepId: String)(
      f: Json.Obj => Json
  ): Json =
    doc match
      case obj: Json.Obj =>
        objField(obj, "steps") match
          case Some(Json.Arr(steps)) =>
            objUpdated(
              obj,
              "steps",
              Json.Arr(
                steps.map {
                  case step: Json.Obj
                      if objField(step, "id").contains(Json.Str(stepId)) =>
                    f(step)
                  case other => other
                }
              )
            )
          case _ => obj
      case _ => doc

  private def stepField(
      doc: Json,
      stepId: String,
      key: String,
      value: Json
  ): Json =
    withStep(doc, stepId)(step => objUpdated(step, key, value))

  private val keep: Json => Json = identity

  private def num(value: Long): Json = Json.Num(value)

  private final case class ConformanceRow(
      field: String,
      fixture: String,
      positive: Json,
      negative: Json,
      code: String,
      documentPath: String
  )

  private def conformance(
      field: String,
      fixture: String,
      positive: Json => Json,
      negative: Json => Json,
      code: String,
      documentPath: String
  ): ConformanceRow =
    val document = parsedFixture(fixture)
    ConformanceRow(
      field,
      fixture,
      positive(document),
      negative(document),
      code,
      documentPath
    )

  /** One canonical frozen-field name per row; the test asserts the table
    * carries exactly this set, so a new frozen field cannot be added without
    * extending the conformance table.
    */
  private val expectedConformanceFields = Set(
    "$schema",
    "$id",
    "format",
    "version",
    "limits",
    "schemas",
    "steps",
    "outputs",
    "metadata",
    "limits.deadlineMs",
    "limits.attempts",
    "limits.parallelism",
    "limits.children",
    "limits.tokens",
    "schemas.inputs",
    "schemas.outputs",
    "schema.type",
    "schema.properties",
    "schema.required",
    "schema.enum",
    "schema.items",
    "schema.additionalProperties",
    "schema.minItems",
    "schema.maxItems",
    "schema.minLength",
    "schema.maxLength",
    "schema.minimum",
    "schema.maximum",
    "step.id",
    "step.kind",
    "step.outputSchema",
    "step.input",
    "agent.profile",
    "agent.toolset",
    "agent.structuredOutput",
    "trustedCheck.checkId",
    "trustedCheck.registrySnapshot",
    "sequence.steps",
    "conditional.when",
    "conditional.then",
    "conditional.else",
    "predicate.op",
    "predicate.value",
    "predicate.left",
    "predicate.right",
    "predicate.predicates",
    "map.items",
    "map.templateStep",
    "map.maxItems",
    "map.parallelism",
    "join.steps",
    "join.failure",
    "repair.operationStep",
    "repair.maxAttempts",
    "repair.safeOperationRef",
    "checkpoint.checkpointRef",
    "checkpoint.placement",
    "outputs.bindings",
    "outputs.verdict.from",
    "outputs.verdict.authority",
    "outputs.artifacts",
    "artifact.name",
    "artifact.from"
  )

  private def conformanceRows: List[ConformanceRow] =
    List(
      // -- root document fields (agent.json) --
      conformance(
        "$schema",
        "agent.json",
        keep,
        setField(_, List("$schema"), num(202012)),
        "invalid-value",
        "/$schema"
      ),
      conformance(
        "$id",
        "agent.json",
        keep,
        setField(_, List("$id"), num(7)),
        "invalid-value",
        "/$id"
      ),
      conformance(
        "format",
        "agent.json",
        keep,
        setField(_, List("format"), num(1)),
        "invalid-value",
        "/format"
      ),
      conformance(
        "version",
        "agent.json",
        keep,
        setField(_, List("version"), num(7)),
        "invalid-type",
        "/version"
      ),
      conformance(
        "limits",
        "agent.json",
        keep,
        setField(_, List("limits"), Json.Str("strict")),
        "invalid-type",
        "/limits"
      ),
      conformance(
        "schemas",
        "agent.json",
        keep,
        setField(_, List("schemas"), Json.Str("none")),
        "invalid-type",
        "/schemas"
      ),
      conformance(
        "steps",
        "agent.json",
        keep,
        setField(_, List("steps"), Json.Obj("id" -> Json.Str("x"))),
        "invalid-type",
        "/steps"
      ),
      conformance(
        "outputs",
        "agent.json",
        keep,
        setField(_, List("outputs"), Json.Str("final")),
        "invalid-type",
        "/outputs"
      ),
      conformance(
        "metadata",
        "agent.json",
        addField(
          _,
          Nil,
          "metadata",
          Json.Obj("origin" -> Json.Str("conformance"))
        ),
        addField(_, Nil, "metadata", Json.Obj("origin" -> num(7))),
        "invalid-value",
        "/metadata"
      ),
      // -- limits fields (agent.json) --
      conformance(
        "limits.deadlineMs",
        "agent.json",
        keep,
        setField(_, List("limits", "deadlineMs"), Json.Str("30000")),
        "invalid-type",
        "/limits/deadlineMs"
      ),
      conformance(
        "limits.attempts",
        "agent.json",
        keep,
        setField(_, List("limits", "attempts"), Json.Str("3")),
        "invalid-type",
        "/limits/attempts"
      ),
      conformance(
        "limits.parallelism",
        "agent.json",
        keep,
        setField(_, List("limits", "parallelism"), Json.Bool(true)),
        "invalid-type",
        "/limits/parallelism"
      ),
      conformance(
        "limits.children",
        "agent.json",
        keep,
        setField(_, List("limits", "children"), Json.Str("0")),
        "invalid-type",
        "/limits/children"
      ),
      conformance(
        "limits.tokens",
        "agent.json",
        keep,
        setField(_, List("limits", "tokens"), Json.Num(BigDecimal("12000.5"))),
        "invalid-type",
        "/limits/tokens"
      ),
      // -- named schema groups (agent.json) --
      conformance(
        "schemas.inputs",
        "agent.json",
        keep,
        setField(_, List("schemas", "inputs"), Json.Arr(Json.Str("request"))),
        "invalid-type",
        "/schemas/inputs"
      ),
      conformance(
        "schemas.outputs",
        "agent.json",
        keep,
        setField(_, List("schemas", "outputs"), Json.Str("none")),
        "invalid-type",
        "/schemas/outputs"
      ),
      // -- schema-subset keywords --
      conformance(
        "schema.type",
        "agent.json",
        keep,
        setField(_, List("schemas", "inputs", "request", "type"), num(1)),
        "invalid-value",
        "/schemas/inputs/request/type"
      ),
      conformance(
        "schema.properties",
        "agent.json",
        keep,
        setField(
          _,
          List("schemas", "inputs", "request", "properties"),
          Json.Str("text")
        ),
        "invalid-type",
        "/schemas/inputs/request/properties"
      ),
      conformance(
        "schema.required",
        "agent.json",
        keep,
        setField(
          _,
          List("schemas", "inputs", "request", "required"),
          Json.Str("text")
        ),
        "invalid-type",
        "/schemas/inputs/request/required"
      ),
      conformance(
        "schema.enum",
        "agent.json",
        keep,
        setField(
          _,
          List("schemas", "outputs", "verdict", "enum"),
          Json.Str("pass")
        ),
        "invalid-value",
        "/schemas/outputs/verdict/enum"
      ),
      conformance(
        "schema.items",
        "bounded-map.json",
        keep,
        setField(
          _,
          List("schemas", "inputs", "items", "items"),
          Json.Str("string")
        ),
        "invalid-type",
        "/schemas/inputs/items/items"
      ),
      conformance(
        "schema.additionalProperties",
        "bounded-map.json",
        addField(
          _,
          List("schemas", "inputs", "items"),
          "additionalProperties",
          Json.Bool(false)
        ),
        addField(
          _,
          List("schemas", "inputs", "items"),
          "additionalProperties",
          Json.Bool(true)
        ),
        "invalid-value",
        "/schemas/inputs/items/additionalProperties"
      ),
      conformance(
        "schema.minItems",
        "bounded-map.json",
        addField(_, List("schemas", "inputs", "items"), "minItems", num(1)),
        addField(
          _,
          List("schemas", "inputs", "items"),
          "minItems",
          Json.Str("1")
        ),
        "invalid-type",
        "/schemas/inputs/items/minItems"
      ),
      conformance(
        "schema.maxItems",
        "bounded-map.json",
        keep,
        setField(
          _,
          List("schemas", "inputs", "items", "maxItems"),
          Json.Str("8")
        ),
        "invalid-type",
        "/schemas/inputs/items/maxItems"
      ),
      conformance(
        "schema.minLength",
        "output-verdict.json",
        doc =>
          addField(
            addField(
              doc,
              List("schemas", "outputs", "verdict"),
              "minLength",
              num(1)
            ),
            List("schemas", "outputs", "verdict"),
            "maxLength",
            num(64)
          ),
        doc =>
          addField(
            addField(
              doc,
              List("schemas", "outputs", "verdict"),
              "minLength",
              Json.Str("1")
            ),
            List("schemas", "outputs", "verdict"),
            "maxLength",
            num(64)
          ),
        "invalid-type",
        "/schemas/outputs/verdict/minLength"
      ),
      conformance(
        "schema.maxLength",
        "output-verdict.json",
        doc =>
          addField(
            addField(
              doc,
              List("schemas", "outputs", "verdict"),
              "minLength",
              num(1)
            ),
            List("schemas", "outputs", "verdict"),
            "maxLength",
            num(64)
          ),
        doc =>
          addField(
            addField(
              doc,
              List("schemas", "outputs", "verdict"),
              "minLength",
              num(1)
            ),
            List("schemas", "outputs", "verdict"),
            "maxLength",
            Json.Str("64")
          ),
        "invalid-type",
        "/schemas/outputs/verdict/maxLength"
      ),
      conformance(
        "schema.minimum",
        "output-verdict.json",
        setField(
          _,
          List("schemas", "outputs", "verdict"),
          Json.Obj(
            "type" -> Json.Str("integer"),
            "minimum" -> num(0),
            "maximum" -> num(100)
          )
        ),
        setField(
          _,
          List("schemas", "outputs", "verdict"),
          Json.Obj(
            "type" -> Json.Str("integer"),
            "minimum" -> Json.Str("0"),
            "maximum" -> num(100)
          )
        ),
        "invalid-type",
        "/schemas/outputs/verdict"
      ),
      conformance(
        "schema.maximum",
        "output-verdict.json",
        setField(
          _,
          List("schemas", "outputs", "verdict"),
          Json.Obj(
            "type" -> Json.Str("integer"),
            "minimum" -> num(0),
            "maximum" -> num(100)
          )
        ),
        setField(
          _,
          List("schemas", "outputs", "verdict"),
          Json.Obj(
            "type" -> Json.Str("integer"),
            "minimum" -> num(0),
            "maximum" -> Json.Str("100")
          )
        ),
        "invalid-type",
        "/schemas/outputs/verdict"
      ),
      // -- base step fields (agent.json, step 0) --
      conformance(
        "step.id",
        "agent.json",
        keep,
        stepField(_, "summarize", "id", num(7)),
        "invalid-type",
        "/steps/0/id"
      ),
      conformance(
        "step.kind",
        "agent.json",
        keep,
        stepField(_, "summarize", "kind", num(7)),
        // A non-string kind cannot unlock kind-specific fields, so the first
        // kind-specific key is rejected as an unsupported construct.
        "unsupported-construct",
        "/steps/0/profile"
      ),
      conformance(
        "step.outputSchema",
        "agent.json",
        keep,
        stepField(_, "summarize", "outputSchema", num(7)),
        "invalid-type",
        "/steps/0/outputSchema"
      ),
      conformance(
        "step.input",
        "agent.json",
        keep,
        stepField(_, "summarize", "input", num(7)),
        "invalid-type",
        "/steps/0/input"
      ),
      // -- agent step fields (agent.json, step 0) --
      conformance(
        "agent.profile",
        "agent.json",
        keep,
        stepField(_, "summarize", "profile", num(7)),
        "invalid-type",
        "/steps/0/profile"
      ),
      conformance(
        "agent.toolset",
        "agent.json",
        keep,
        stepField(_, "summarize", "toolset", num(7)),
        "invalid-type",
        "/steps/0/toolset"
      ),
      conformance(
        "agent.structuredOutput",
        "agent.json",
        keep,
        stepField(_, "summarize", "structuredOutput", Json.Str("true")),
        "invalid-type",
        "/steps/0/structuredOutput"
      ),
      // -- trustedCheck step fields (agent.json, step 1) --
      conformance(
        "trustedCheck.checkId",
        "agent.json",
        keep,
        stepField(_, "verify", "checkId", num(7)),
        "invalid-type",
        "/steps/1/checkId"
      ),
      conformance(
        "trustedCheck.registrySnapshot",
        "agent.json",
        keep,
        stepField(_, "verify", "registrySnapshot", num(7)),
        "invalid-type",
        "/steps/1/registrySnapshot"
      ),
      // -- sequence child list (sequence.json, step 1) --
      conformance(
        "sequence.steps",
        "sequence.json",
        keep,
        stepField(_, "ordered", "steps", Json.Str("draft")),
        "invalid-value",
        "/steps/1/steps"
      ),
      // -- conditional fields (conditional.json, step 2) --
      conformance(
        "conditional.when",
        "conditional.json",
        keep,
        stepField(_, "branch", "when", Json.Str("enabled")),
        "invalid-type",
        "/steps/2/when"
      ),
      conformance(
        "conditional.then",
        "conditional.json",
        keep,
        stepField(_, "branch", "then", num(7)),
        "invalid-type",
        "/steps/2/then"
      ),
      conformance(
        "conditional.else",
        "conditional.json",
        keep,
        stepField(_, "branch", "else", num(7)),
        "invalid-type",
        "/steps/2/else"
      ),
      // -- predicate fields (conditional.json) --
      conformance(
        "predicate.op",
        "conditional.json",
        keep,
        stepField(
          _,
          "branch",
          "when",
          Json.Obj(
            "op" -> num(7),
            "value" -> Json.Str("#/inputs/request/enabled")
          )
        ),
        "invalid-value",
        "/steps/2/when/op"
      ),
      conformance(
        "predicate.value",
        "conditional.json",
        keep,
        stepField(
          _,
          "branch",
          "when",
          Json.Obj("op" -> Json.Str("isPresent"), "value" -> num(7))
        ),
        "invalid-type",
        "/steps/2/when/value"
      ),
      conformance(
        "predicate.left",
        "conditional.json",
        stepField(
          _,
          "branch",
          "when",
          Json.Obj(
            "op" -> Json.Str("equals"),
            "left" -> Json.Str("#/inputs/request/enabled"),
            "right" -> Json.Bool(true)
          )
        ),
        stepField(
          _,
          "branch",
          "when",
          Json.Obj(
            "op" -> Json.Str("equals"),
            "left" -> Json.Obj("deep" -> num(1)),
            "right" -> Json.Bool(true)
          )
        ),
        "invalid-value",
        "/steps/2/when"
      ),
      conformance(
        "predicate.right",
        "conditional.json",
        stepField(
          _,
          "branch",
          "when",
          Json.Obj(
            "op" -> Json.Str("equals"),
            "left" -> Json.Str("#/inputs/request/enabled"),
            "right" -> Json.Bool(true)
          )
        ),
        stepField(
          _,
          "branch",
          "when",
          Json.Obj(
            "op" -> Json.Str("equals"),
            "left" -> Json.Str("#/inputs/request/enabled"),
            "right" -> Json.Arr(Json.Str("no"))
          )
        ),
        "invalid-value",
        "/steps/2/when"
      ),
      conformance(
        "predicate.predicates",
        "conditional.json",
        stepField(
          _,
          "branch",
          "when",
          Json.Obj(
            "op" -> Json.Str("any"),
            "predicates" -> Json.Arr(
              Json.Obj(
                "op" -> Json.Str("isPresent"),
                "value" -> Json.Str("#/inputs/request/enabled")
              )
            )
          )
        ),
        stepField(
          _,
          "branch",
          "when",
          Json.Obj("op" -> Json.Str("any"), "predicates" -> Json.Str("all"))
        ),
        "invalid-value",
        "/steps/2/when/predicates"
      ),
      // -- map step fields (bounded-map.json, step 1) --
      conformance(
        "map.items",
        "bounded-map.json",
        keep,
        stepField(_, "per-item", "items", num(7)),
        "invalid-value",
        "/steps/1/items"
      ),
      conformance(
        "map.templateStep",
        "bounded-map.json",
        keep,
        stepField(_, "per-item", "templateStep", num(7)),
        "invalid-type",
        "/steps/1/templateStep"
      ),
      conformance(
        "map.maxItems",
        "bounded-map.json",
        keep,
        stepField(_, "per-item", "maxItems", Json.Str("8")),
        "invalid-type",
        "/steps/1/maxItems"
      ),
      conformance(
        "map.parallelism",
        "bounded-map.json",
        keep,
        stepField(_, "per-item", "parallelism", Json.Str("2")),
        "invalid-type",
        "/steps/1/parallelism"
      ),
      // -- join step fields (join.json, step 2) --
      conformance(
        "join.steps",
        "join.json",
        keep,
        stepField(_, "together", "steps", Json.Str("left")),
        "invalid-value",
        "/steps/2/steps"
      ),
      conformance(
        "join.failure",
        "join.json",
        keep,
        stepField(_, "together", "failure", num(7)),
        "invalid-type",
        "/steps/2/failure"
      ),
      // -- repair step fields (repair.json, step 1) --
      conformance(
        "repair.operationStep",
        "repair.json",
        keep,
        stepField(_, "repair-edit", "operationStep", num(7)),
        "invalid-type",
        "/steps/1/operationStep"
      ),
      conformance(
        "repair.maxAttempts",
        "repair.json",
        keep,
        stepField(_, "repair-edit", "maxAttempts", Json.Str("2")),
        "invalid-type",
        "/steps/1/maxAttempts"
      ),
      conformance(
        "repair.safeOperationRef",
        "repair.json",
        keep,
        stepField(_, "repair-edit", "safeOperationRef", num(7)),
        "invalid-type",
        "/steps/1/safeOperationRef"
      ),
      // -- checkpoint step fields (checkpoint.json, step 0) --
      conformance(
        "checkpoint.checkpointRef",
        "checkpoint.json",
        keep,
        stepField(_, "approval", "checkpointRef", num(7)),
        "invalid-type",
        "/steps/0/checkpointRef"
      ),
      conformance(
        "checkpoint.placement",
        "checkpoint.json",
        keep,
        stepField(_, "approval", "placement", num(7)),
        "invalid-type",
        "/steps/0/placement"
      ),
      // -- outputs fields --
      conformance(
        "outputs.bindings",
        "agent.json",
        keep,
        setField(_, List("outputs", "bindings", "summary"), num(7)),
        "invalid-value",
        "/outputs/bindings/summary"
      ),
      conformance(
        "outputs.verdict.from",
        "output-verdict.json",
        keep,
        setField(_, List("outputs", "verdict", "from"), num(7)),
        "invalid-value",
        "/outputs/verdict"
      ),
      conformance(
        "outputs.verdict.authority",
        "output-verdict.json",
        keep,
        setField(_, List("outputs", "verdict", "authority"), num(7)),
        "invalid-value",
        "/outputs/verdict"
      ),
      conformance(
        "outputs.artifacts",
        "output-verdict.json",
        keep,
        setField(_, List("outputs", "artifacts"), Json.Str("report")),
        "invalid-type",
        "/outputs/artifacts"
      ),
      conformance(
        "artifact.name",
        "output-verdict.json",
        keep,
        setField(
          _,
          List("outputs", "artifacts"),
          Json.Arr(
            Json.Obj(
              "name" -> num(7),
              "from" -> Json.Str("#/steps/verify/output")
            )
          )
        ),
        "invalid-value",
        "/outputs/artifacts/0"
      ),
      conformance(
        "artifact.from",
        "output-verdict.json",
        keep,
        setField(
          _,
          List("outputs", "artifacts"),
          Json.Arr(
            Json.Obj("name" -> Json.Str("check-report"), "from" -> num(7))
          )
        ),
        "invalid-value",
        "/outputs/artifacts/0"
      )
    )

  def spec =
    suite("PortableWorkflowValidator bounded preflight")(
      test(
        "both oversized corpus artifacts refuse at the root by UTF-8 byte count"
      ) {
        val artifacts = List(
          "refusals/fixtures/oversized.json",
          "refusals/fixtures/oversized-utf8.json"
        )
        val results = artifacts.map(path =>
          PortableWorkflowValidator.validate(Files.readAllBytes(contract(path)))
        )
        assertTrue(
          results.forall(result =>
            diagnostic(result).exists(d =>
              d.code == "document-too-large" && d.documentPath == "" &&
                d.offendingRef.contains("262145 bytes")
            )
          )
        )
      },
      test(
        "known positive fixtures pass the decoder and supported major check"
      ) {
        val listing = Files.list(contract("valid"))
        val files =
          try listing.iterator().asScala.toList
          finally listing.close()
        val results = files.map(path =>
          PortableWorkflowValidator.validate(Files.readAllBytes(path))
        )
        assertTrue(files.size == 10, results.forall(_.isRight))
      },
      test(
        "agent and sequence lower to the existing lazy Program composition tree"
      ) {
        val agent = Files
          .readString(contract("valid/agent.json"))
          .fromJson[Json]
          .toOption
          .get
        val sequence = Files
          .readString(contract("valid/sequence.json"))
          .fromJson[Json]
          .toOption
          .get
        val native = Program.fromFunction[Json, Json]("native-scripted")(_ =>
          Task.succeed(Json.Null)
        )
        val registry = PortableProgramRegistry(
          agents = Map("worker" -> native, "extractor" -> native),
          checks = Map(
            (
              "format-check",
              "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            ) -> native
          )
        )
        val loweredAgent = PortableWorkflowLowerer.lower(agent, registry)
        val loweredSequence = PortableWorkflowLowerer.lower(sequence, registry)
        assertTrue(
          loweredAgent.exists(
            _.structure == PortableProgramNode("summarize", "agent", Nil)
          ),
          loweredSequence.exists(
            _.structure == PortableProgramNode(
              "ordered",
              "sequence",
              List(
                PortableProgramNode("draft", "agent", Nil),
                PortableProgramNode("verify", "trustedCheck", Nil)
              )
            )
          )
        )
      },
      test("all ten positive fixtures lower with trusted native descriptors") {
        val listing = Files.list(contract("valid"))
        val files =
          try listing.iterator().asScala.toList
          finally listing.close()
        val native = Program.fromFunction[Json, Json]("native-scripted")(_ =>
          Task.succeed(Json.Null)
        )
        val checks = files.flatMap { path =>
          Files.readString(path).fromJson[Json].toOption.toList.flatMap {
            case obj: Json.Obj =>
              obj.fields
                .collectFirst { case ("steps", Json.Arr(steps)) =>
                  steps.toList
                }
                .getOrElse(Nil)
                .flatMap {
                  case step: Json.Obj =>
                    val id = step.fields.collectFirst {
                      case ("checkId", Json.Str(v)) => v
                    }
                    val snapshot = step.fields.collectFirst {
                      case ("registrySnapshot", Json.Str(v)) => v
                    }
                    for checkId <- id; revision <- snapshot
                    yield (checkId -> revision) -> native
                  case _ => Nil
                }
            case _ => Nil
          }
        }.toMap
        val registry = PortableProgramRegistry(
          agents = List("worker", "extractor", "scout").map(_ -> native).toMap,
          checks = checks,
          safeOperations = Map("safe-edit-v1" -> native),
          checkpoints = Map("review-before-write" -> native)
        )
        val lowered = files.map { path =>
          PortableWorkflowLowerer.lower(
            Files.readString(path).fromJson[Json].toOption.get,
            registry
          )
        }
        assertTrue(files.size == 10, lowered.forall(_.isRight))
      },
      test(
        "all ten positive fixtures execute through scripted native programs"
      ) {
        val listing = Files.list(contract("valid"))
        val files =
          try listing.iterator().asScala.toList.sortBy(_.getFileName.toString)
          finally listing.close()
        val calls = new AtomicInteger(0)
        val scripted =
          Program.fromFunction[Json, Json]("portable-scripted") { input =>
            Task(ZIO.succeed { calls.incrementAndGet(); input })
          }
        val checkIds = files.flatMap { path =>
          Files.readString(path).fromJson[Json].toOption.toList.flatMap {
            case obj: Json.Obj =>
              obj.fields
                .collectFirst { case ("steps", Json.Arr(steps)) =>
                  steps.toList
                }
                .getOrElse(Nil)
                .flatMap {
                  case step: Json.Obj =>
                    for
                      id <- step.fields.collectFirst {
                        case ("checkId", Json.Str(v)) => v
                      }
                      revision <- step.fields.collectFirst {
                        case ("registrySnapshot", Json.Str(v)) => v
                      }
                    yield (id -> revision) -> scripted
                  case _ => Nil
                }
            case _ => Nil
          }
        }.toMap
        val registry = PortableProgramRegistry(
          agents =
            List("worker", "extractor", "scout").map(_ -> scripted).toMap,
          checks = checkIds,
          safeOperations = Map("safe-edit-v1" -> scripted),
          checkpoints = Map("review-before-write" -> scripted)
        )
        val input = Json.Obj(
          "inputs" -> Json.Obj(
            "request" -> Json.Str("Run the deterministic portable fixture"),
            "candidate" -> Json.Str("candidate"),
            "items" -> Json.Arr(Json.Str("one"), Json.Str("two"))
          )
        )
        val lowered = files.map { path =>
          val document = Files.readString(path).fromJson[Json].toOption.get
          PortableWorkflowLowerer.lower(document, registry)
        }
        val executions = lowered.flatMap(_.toOption).map { workflow =>
          Task.zio(workflow.program(input)).either
        }
        ZIO.foreach(executions)(identity).map { results =>
          assertTrue(
            files.size == 10,
            lowered.forall(_.isRight),
            results.forall(_.isRight),
            calls.get() >= files.size
          )
        }
      },
      test("lowering emits validated root ceilings as launch metadata") {
        val document = Files
          .readString(contract("valid/root-limits.json"))
          .fromJson[Json]
          .toOption
          .get
        val native = Program.fromFunction[Json, Json]("native-scripted")(_ =>
          Task.succeed(Json.Null)
        )
        val lowered = PortableWorkflowLowerer
          .lower(
            document,
            PortableProgramRegistry(
              Map("worker" -> native),
              Map(
                (
                  "bounded-check",
                  "sha256:1111111111111111111111111111111111111111111111111111111111111111"
                ) -> native
              )
            )
          )
        assertTrue(
          lowered.exists(
            _.launchMetadata == PortableLaunchMetadata(
              maxAttempts = 1,
              maxParallelism = 1,
              deadlineMs = 5000L,
              maxTokens = 100L,
              maxChildren = 0
            )
          )
        )
      },
      test("unknown-major refusal matches the frozen diagnostic fields") {
        val result = PortableWorkflowValidator.validate(
          """{"format":"raider.portable-workflow","version":"2.0"}"""
        )
        assertTrue(
          diagnostic(result).contains(
            PortableDiagnostic(
              "unknown-major-version",
              "/version",
              None,
              Some("2.0"),
              "Use a supported major version or upgrade the validator."
            )
          )
        )
      },
      test(
        "the frozen refusal corpus is executed and diagnostics match exactly"
      ) {
        val manifest = Files
          .readString(contract("refusals/manifest.json"))
          .fromJson[Json]
          .toOption
          .get
          .asInstanceOf[Json.Obj]
        val cases = manifest.fields.collectFirst {
          case ("cases", Json.Arr(values)) => values.toList
        }.get
        val actual = cases.map { entry =>
          val c = entry.asInstanceOf[Json.Obj]
          val id = c.fields.collectFirst { case ("id", Json.Str(value)) =>
            value
          }.get
          val expected = c.fields.collectFirst {
            case ("expected", value: Json.Obj) => value
          }.get
          val input = c.fields.collectFirst {
            case ("rawDocument", Json.Str(raw)) =>
              raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            case ("fixture", Json.Str(relative)) =>
              Files.readAllBytes(contract(s"refusals/$relative"))
            case ("document", value) =>
              value.toJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)
          }.get
          val diagnostic =
            PortableWorkflowValidator.validate(input).swap.toOption
          (id, expected, diagnostic)
        }
        val matches = actual.forall { case (_, expected, diagnostic) =>
          diagnostic.exists { d =>
            def field(name: String) =
              expected.fields.find(_._1 == name).map(_._2)
            val step = field("stepId") match {
              case Some(Json.Str(v)) => Some(v); case _ => None
            }
            val ref = field("offendingRef") match {
              case Some(Json.Str(v)) => Some(v); case _ => None
            }
            d == PortableDiagnostic(
              field("code").collect { case Json.Str(v) => v }.get,
              field("documentPath").collect { case Json.Str(v) => v }.get,
              step,
              ref,
              field("suggestedCorrection").collect { case Json.Str(v) => v }.get
            )
          }
        }
        assertTrue(cases.size == 17, actual.forall(_._3.nonEmpty), matches)
      },
      test("malformed UTF-8 and invalid JSON are refused locally") {
        val malformedUtf8 = Array(0xc3.toByte, 0x28.toByte)
        val badJson = PortableWorkflowValidator.validate("{]")
        assertTrue(
          PortableWorkflowValidator
            .validate(malformedUtf8)
            .swap
            .toOption
            .nonEmpty,
          diagnostic(badJson).exists(_.code == "invalid-json")
        )
      },
      test(
        "every declared field has its frozen JSON type and closed object shape"
      ) {
        val valid = Files.readString(contract("valid/agent.json"))
        val wrongBoolean = valid.replace(
          "\"structuredOutput\": true",
          "\"structuredOutput\": \"true\""
        )
        val wrongLimit = valid.replace(
          "\"attempts\": 3",
          "\"attempts\": \"3\""
        )
        val extraStepField = valid.replace(
          "\"structuredOutput\": true",
          "\"structuredOutput\": true, \"surprise\": false"
        )
        assertTrue(
          diagnostic(PortableWorkflowValidator.validate(wrongBoolean))
            .exists(d =>
              d.code == "invalid-type" && d.documentPath.endsWith(
                "/structuredOutput"
              )
            ),
          diagnostic(PortableWorkflowValidator.validate(wrongLimit))
            .exists(d =>
              d.code == "invalid-type" && d.documentPath == "/limits/attempts"
            ),
          diagnostic(PortableWorkflowValidator.validate(extraStepField))
            .exists(d =>
              d.code == "unsupported-construct" && d.documentPath.endsWith(
                "/surprise"
              )
            )
        )
      },
      test("schema keyword types and enum values match their declared types") {
        val valid = Files.readString(contract("valid/agent.json"))
        val wrongRequired = valid.replace(
          "\"required\": [\"text\"]",
          "\"required\": \"text\""
        )
        val wrongEnumValue = valid.replace(
          "\"enum\": [\"pass\", \"fail\", \"incomplete\", \"uncertain\", \"refused\"]",
          "\"enum\": [1, 2]"
        )
        val itemBoundOnString = valid.replace(
          "\"type\": \"string\", \"enum\":",
          "\"type\": \"string\", \"maxItems\": 2, \"enum\":"
        )
        assertTrue(
          diagnostic(PortableWorkflowValidator.validate(wrongRequired))
            .exists(d =>
              d.code == "invalid-type" && d.documentPath.endsWith("/required")
            ),
          diagnostic(PortableWorkflowValidator.validate(wrongEnumValue))
            .exists(d => d.documentPath.endsWith("/enum")),
          diagnostic(PortableWorkflowValidator.validate(itemBoundOnString))
            .exists(d => d.documentPath == "/schemas/outputs/verdict")
        )
      },
      test(
        "all declared pointer sites resolve through frozen input/output schemas"
      ) {
        val valid = Files.readString(contract("valid/conditional.json"))
        val dangling = valid.replace(
          "#/inputs/request/enabled",
          "#/inputs/request/missing"
        )
        val unknownOutput = valid.replace(
          "\"outputSchema\": \"text\"",
          "\"outputSchema\": \"unknown-output\""
        )
        assertTrue(
          diagnostic(PortableWorkflowValidator.validate(dangling)).exists(d =>
            d.code == "missing-reference" && d.documentPath.endsWith("/value")
          ),
          diagnostic(PortableWorkflowValidator.validate(unknownOutput)).exists(
            d =>
              d.code == "missing-reference" && d.documentPath.endsWith(
                "/outputSchema"
              )
          )
        )
      },
      test("validator and lowerer construction invoke no configured program") {
        val calls = new AtomicInteger(0)
        val counted =
          Program.fromFunction[Json, Json]("dispatch-spy") { input =>
            Task(ZIO.succeed { calls.incrementAndGet(); input })
          }
        val document = Files
          .readString(contract("valid/agent.json"))
          .fromJson[Json]
          .toOption
          .get
        val registry = PortableProgramRegistry(
          agents = Map("extractor" -> counted),
          checks = Map.empty
        )
        val validation = PortableWorkflowValidator.validate(document.toJson)
        val lowering = PortableWorkflowLowerer.lower(document, registry)
        assertTrue(validation.isRight, lowering.isRight, calls.get() == 0)
      },
      test(
        "a lowered run preserves typed step failure and does not run later sequence steps"
      ) {
        val sequenceText = Files
          .readString(contract("valid/sequence.json"))
          .replace(
            "\"steps\": [\"draft\", \"verify\"]",
            "\"steps\": [\"draft\", \"verify\", \"after\"]"
          )
          .replace(
            "\"outputSchema\": \"text\" }],",
            "\"outputSchema\": \"text\" }, {\"id\":\"after\",\"kind\":\"agent\",\"profile\":\"worker\",\"toolset\":\"read-only\",\"input\":\"#/inputs/request\",\"outputSchema\":\"text\",\"structuredOutput\":true}],"
          )
        val sequence = sequenceText.fromJson[Json].toOption.get
        val agents = new AtomicInteger(0)
        val checks = new AtomicInteger(0)
        val agent =
          Program.fromFunction[Json, Json]("scripted-agent") { input =>
            Task(ZIO.succeed { agents.incrementAndGet(); input })
          }
        val check =
          Program.fromFunction[Json, Json]("scripted-failing-check") { _ =>
            Task(
              ZIO
                .succeed(checks.incrementAndGet())
                .flatMap(_ =>
                  ZIO.fail(RaiderError.ToolFailed("scripted check failure"))
                )
            )
          }
        val registry = PortableProgramRegistry(
          agents = Map("worker" -> agent),
          checks = Map(
            (
              "format-check",
              "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            ) -> check
          )
        )
        val lowered =
          PortableWorkflowLowerer.lower(sequence, registry).toOption.get
        for result <- Task.zio(lowered.program(Json.Null)).either
        yield assertTrue(
          result.swap.toOption.exists(_.isInstanceOf[RaiderError.ToolFailed]),
          agents.get() == 1,
          checks.get() == 1
        )
      },
      test("external cancellation of a lowered run remains interruption") {
        val document = Files
          .readString(contract("valid/agent.json"))
          .fromJson[Json]
          .toOption
          .get
        for
          started <- Promise.make[Nothing, Unit]
          blocked = Program.fromFunction[Json, Json]("scripted-blocked") { _ =>
            Task(started.succeed(()).unit *> ZIO.never)
          }
          registry = PortableProgramRegistry(
            agents = Map("extractor" -> blocked),
            checks = Map.empty
          )
          lowered = PortableWorkflowLowerer
            .lower(document, registry)
            .toOption
            .get
          fiber <- Task.zio(lowered.program(Json.Null)).fork
          _ <- started.await
          exit <- fiber.interrupt
        yield assertTrue(exit.isInterrupted)
      },
      test(
        "the conformance table covers every frozen field with a typed positive and negative"
      ) {
        val rows = conformanceRows
        val failures = rows.flatMap { row =>
          val problems = List.newBuilder[String]
          PortableWorkflowValidator.validate(row.positive.toJson) match
            case Right(_) => ()
            case Left(d) =>
              problems += s"positive rejected with ${d.code} at ${d.documentPath}"
          PortableWorkflowValidator.validate(row.negative.toJson) match
            case Left(d) =>
              if d.code != row.code || d.documentPath != row.documentPath then
                problems +=
                  s"negative expected ${row.code} at ${row.documentPath}, got ${d.code} at ${d.documentPath}"
            case Right(_) =>
              problems += s"negative accepted, expected ${row.code}"
          problems
            .result()
            .map(problem => s"[${row.fixture}] ${row.field}: $problem")
        }
        assertTrue(
          rows.size == 63,
          rows.map(_.field).toSet == expectedConformanceFields,
          failures.mkString("\n") == ""
        )
      },
      test(
        "bounded repair repeats the declared safe operation with candidate-bound evidence"
      ) {
        val candidate = Json.Obj(
          "inputs" -> Json.Obj("request" -> Json.Str("candidate"))
        )
        for
          attempts <- Ref.make(0)
          evidence <- Ref.make(List.empty[(Int, Json)])
          scripted = Program.fromFunction[Json, Json]("scripted-safe-edit") {
            input =>
              Task(
                for
                  ordinal <- attempts.updateAndGet(_ + 1)
                  _ <- evidence.update(_ :+ (ordinal -> input))
                  repaired <-
                    if ordinal == 1 then
                      ZIO.fail(
                        RaiderError.ToolFailed("scripted transient failure")
                      )
                    else ZIO.succeed(Json.Str("repaired-candidate"))
                yield repaired
              )
          }
          registry = PortableProgramRegistry(
            agents = Map.empty,
            checks = Map.empty,
            safeOperations = Map("safe-edit-v1" -> scripted)
          )
          lowered = PortableWorkflowLowerer
            .lower(parsedFixture("repair.json"), registry)
            .toOption
            .get
          result <- Task.zio(lowered.program(candidate)).either
          count <- attempts.get
          recorded <- evidence.get
        yield assertTrue(
          result.contains(Json.Str("repaired-candidate")),
          count == 2,
          recorded.map(_._1) == List(1, 2),
          recorded.map(_._2) == List(candidate, candidate)
        )
      },
      test(
        "repair exhaustion at the declared attempt bound is a typed refusal"
      ) {
        val candidate = Json.Obj(
          "inputs" -> Json.Obj("request" -> Json.Str("candidate"))
        )
        for
          attempts <- Ref.make(0)
          evidence <- Ref.make(List.empty[Json])
          scripted = Program.fromFunction[Json, Json]("scripted-safe-edit") {
            input =>
              Task(
                attempts.update(_ + 1) *> evidence.update(_ :+ input) *>
                  ZIO.fail(
                    RaiderError.ToolFailed("scripted persistent failure")
                  )
              )
          }
          registry = PortableProgramRegistry(
            agents = Map.empty,
            checks = Map.empty,
            safeOperations = Map("safe-edit-v1" -> scripted)
          )
          lowered = PortableWorkflowLowerer
            .lower(parsedFixture("repair.json"), registry)
            .toOption
            .get
          result <- Task.zio(lowered.program(candidate)).either
          count <- attempts.get
          recorded <- evidence.get
        yield assertTrue(
          result.swap.toOption.exists {
            case RaiderError.LocalBudgetExceeded(detail) =>
              detail.contains("repair-edit") && detail.contains(
                "declared attempt bound of 2"
              )
            case _ => false
          },
          count == 2,
          recorded.size == 2,
          recorded.forall(_ == candidate)
        )
      },
      test(
        "an uncertain repair outcome is never repeated automatically"
      ) {
        for
          attempts <- Ref.make(0)
          evidence <- Ref.make(List.empty[Json])
          scripted = Program.fromFunction[Json, Json]("scripted-safe-edit") {
            input =>
              Task(
                attempts.update(_ + 1) *> evidence.update(_ :+ input) *>
                  ZIO.fail(
                    RaiderError.ToolOutcomeUncertain(
                      "scripted ambiguous tool outcome"
                    )
                  )
              )
          }
          registry = PortableProgramRegistry(
            agents = Map.empty,
            checks = Map.empty,
            safeOperations = Map("safe-edit-v1" -> scripted)
          )
          lowered = PortableWorkflowLowerer
            .lower(parsedFixture("repair.json"), registry)
            .toOption
            .get
          result <- Task.zio(lowered.program(Json.Null)).either
          count <- attempts.get
          recorded <- evidence.get
        yield assertTrue(
          result.swap.toOption.exists(
            _.isInstanceOf[RaiderError.ToolOutcomeUncertain]
          ),
          count == 1,
          recorded.size == 1
        )
      }
    )

end PortableWorkflowValidatorSpec
