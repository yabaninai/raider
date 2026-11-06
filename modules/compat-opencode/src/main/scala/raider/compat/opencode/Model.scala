package raider.compat.opencode

import zio.Chunk
import zio.json.ast.Json

/** Data model and JSON encoding for the OpenCode profile import document
  * (contracts/compat/opencode/import.schema.json). Profiles, rules, and
  * diagnostics mirror the pinned OpenCode shapes; the encoder emits fields in
  * schema order and omits absent optional fields.
  */
final case class PermissionRule(action: String, resource: String, effect: String)

final case class ModelRef(providerID: String, model: String, variant: Option[String])

final case class RequestSettings(headers: Option[Map[String, String]], body: Option[Json.Obj])

final case class Profile(
  id: String,
  name: String,
  description: Option[String],
  mode: String,
  hidden: Boolean,
  color: Option[String],
  steps: Option[Int],
  model: Option[ModelRef],
  request: Option[RequestSettings],
  system: Option[String],
  permissions: Vector[PermissionRule],
  source: String,
  origin: String,
  sourcePath: String,
  unsupportedFields: Vector[String],
)

final case class Diagnostic(kind: String, path: Vector[String], message: String)

final case class CorpusPin(repo: String, commit: String, license: String, manifestSha256: String)

final case class ImportDocument(
  schemaVersion: Int,
  corpus: CorpusPin,
  defaultAgentId: String,
  profiles: Vector[Profile],
  diagnostics: Vector[Diagnostic],
)

object Model:

  val modes: Vector[String] = Vector("subagent", "primary", "all")
  val effects: Vector[String] = Vector("allow", "deny", "ask")

  private def field(name: String, value: Json) = Some(name -> value)

  private def optField(name: String, value: Option[Json]) = value.map(name -> _)

  private def numOf(bd: scala.math.BigDecimal): Json.Num = Json.Num(bd.underlying)

  def ruleJson(r: PermissionRule): Json =
    Json.Obj(
      List(
        "action" -> Json.Str(r.action),
        "resource" -> Json.Str(r.resource),
        "effect" -> Json.Str(r.effect),
      )*
    )

  private def modelJson(m: ModelRef): Json =
    Json.Obj(
      (List(
        "providerID" -> Json.Str(m.providerID),
        "model" -> Json.Str(m.model),
      ) ++ m.variant.toList.map(v => "variant" -> Json.Str(v)))*
    )

  private def requestJson(r: RequestSettings): Json =
    Json.Obj(
      r.headers.toList.map(h =>
        "headers" -> Json.Obj(h.toList.sortBy(_._1).map((k, v) => k -> Json.Str(v))*)
      ) ++
        r.body.toList.map(b => "body" -> b)*
    )

  def profileJson(p: Profile): Json =
    Json.Obj(
      List(
        field("id", Json.Str(p.id)),
        field("name", Json.Str(p.name)),
        optField("description", p.description.map(Json.Str(_))),
        field("mode", Json.Str(p.mode)),
        field("hidden", Json.Bool(p.hidden)),
        optField("color", p.color.map(Json.Str(_))),
        optField("steps", p.steps.map(s => numOf(BigDecimal(s)))),
        optField("model", p.model.map(modelJson)),
        optField("request", p.request.map(requestJson)),
        optField("system", p.system.map(Json.Str(_))),
        field("permissions", Json.Arr(Chunk.fromIterable(p.permissions.map(ruleJson)))),
        field("source", Json.Str(p.source)),
        field("origin", Json.Str(p.origin)),
        field("sourcePath", Json.Str(p.sourcePath)),
        field(
          "unsupportedFields",
          Json.Arr(Chunk.fromIterable(p.unsupportedFields.map(Json.Str(_)))),
        ),
      ).flatten*
    )

  private def diagnosticJson(d: Diagnostic): Json =
    Json.Obj(
      List(
        "kind" -> Json.Str(d.kind),
        "path" -> Json.Arr(Chunk.fromIterable(d.path.map(Json.Str(_)))),
        "message" -> Json.Str(d.message),
      )*
    )

  def documentJson(doc: ImportDocument): Json =
    Json.Obj(
      List(
        "schemaVersion" -> numOf(BigDecimal(doc.schemaVersion)),
        "corpus" -> Json.Obj(
          List(
            "repo" -> Json.Str(doc.corpus.repo),
            "commit" -> Json.Str(doc.corpus.commit),
            "license" -> Json.Str(doc.corpus.license),
            "manifestSha256" -> Json.Str(doc.corpus.manifestSha256),
          )*
        ),
        "defaultAgentId" -> Json.Str(doc.defaultAgentId),
        "profiles" -> Json.Arr(Chunk.fromIterable(doc.profiles.map(profileJson))),
        "diagnostics" -> Json.Arr(Chunk.fromIterable(doc.diagnostics.map(diagnosticJson))),
      )*
    )

  /** Stable pretty encoding (2-space indent) used for the reviewed golden
    * fixture and byte comparisons.
    */
  def encodePretty(doc: ImportDocument): String =
    Json.encoder.encodeJson(documentJson(doc), Some(2)).toString
