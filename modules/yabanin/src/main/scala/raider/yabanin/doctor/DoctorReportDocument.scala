package raider.yabanin.doctor

import raider.yabanin.labels.LabelLimits
import raider.yabanin.labels.LabelSetDocument
import zio.Chunk
import zio.json.JsonEncoder
import zio.json.ast.Json

/** Wire document for the doctor report
  * (`contracts/yabanin/doctor-report.schema.json`).
  *
  * The encoder is hand-written over the zio-json AST so the emitted field set
  * and order match the schema exactly: required `null`s (`http_status`,
  * `docs_version`, `decision`) stay explicit and `fallback_attempted` is the
  * structural constant `false` — the report type carries no such field.
  */
object DoctorReportDocument:

  /** Builds the document AST. Field order matches the schema property order:
    * base_url, labels, models, session_keys, decision, fallback_attempted.
    */
  def toAst(report: DoctorReport): Json =
    Json.Obj(
      "base_url" -> Json.Str(report.baseUrl),
      "labels" -> LabelSetDocument.toAst(report.labels, LabelLimits.Default),
      "models" -> modelsAst(report.models),
      "session_keys" -> sessionKeysAst(report.sessionKeys),
      "decision" -> (report.decision match
        case Some(d) =>
          Json.Obj(
            "header" -> Json.Str(d.header),
            "value" -> Json.Str(d.value),
            "observed_on" -> Json.Str(d.observedOn)
          )
        case None => Json.Null),
      "fallback_attempted" -> Json.Bool(false)
    )

  given JsonEncoder[DoctorReport] = JsonEncoder[Json].contramap(toAst)

  private def modelsAst(p: ModelsProbe): Json =
    Json.Obj(
      "classification" -> Json.Str(p.classification.encode),
      "http_status" -> intOrNull(p.httpStatus),
      "model_ids" -> Json.Arr(
        Chunk.fromIterable(p.modelIds.map(id => Json.Str(id)))
      ),
      "docs_version" -> strOrNull(p.docsVersion),
      "detail" -> Json.Str(p.detail)
    )

  private def sessionKeysAst(p: SessionKeysProbe): Json =
    Json.Obj(
      "classification" -> Json.Str(p.classification.encode),
      "http_status" -> intOrNull(p.httpStatus),
      "detail" -> Json.Str(p.detail)
    )

  private def intOrNull(v: Option[Int]): Json = v match
    case Some(n) => Json.Num(BigDecimal(n))
    case None    => Json.Null

  private def strOrNull(v: Option[String]): Json = v match
    case Some(s) => Json.Str(s)
    case None    => Json.Null
