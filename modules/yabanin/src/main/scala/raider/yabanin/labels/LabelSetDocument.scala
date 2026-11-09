package raider.yabanin.labels

import zio.Chunk
import zio.json.JsonEncoder
import zio.json.ast.Json

/** Wire document for the canonical label set
  * (`contracts/yabanin/labels.schema.json`).
  *
  * The encoder is hand-written over the zio-json AST so the emitted field set
  * and order match the schema exactly: required `null`s stay explicit and
  * optional fields are omitted (never `null`) — zio-json derived encoders
  * drop `None`, which is why [[toAst]] controls every field.
  */
object LabelSetDocument:

  /** The document payload: a canonical label set plus the limits it was
    * canonicalized under.
    */
  final case class Document(set: LabelSet, limits: LabelLimits)

  /** Builds the document AST. Field order matches the schema property order:
    * accepted, dropped, header, limits.
    */
  def toAst(set: LabelSet, limits: LabelLimits): Json =
    Json.Obj(
      "accepted" -> Json.Arr(Chunk.fromIterable(set.accepted.map(acceptedAst))),
      "dropped" -> Json.Arr(Chunk.fromIterable(set.dropped.map(droppedAst))),
      "header" -> Json.Str(set.renderHeader),
      "limits" -> limitsAst(limits),
    )

  given JsonEncoder[Document] = JsonEncoder[Json].contramap(d => toAst(d.set, d.limits))

  private def acceptedAst(label: Label): Json =
    Json.Obj(
      "key" -> Json.Str(label.key),
      "value" -> Json.Str(label.value),
      "provenance" -> Json.Str(label.provenance.encode),
    )

  private def droppedAst(dropped: DroppedLabel): Json =
    val fields = Chunk(
      "key" -> Json.Str(dropped.key),
      "reason" -> Json.Str(dropped.reason.encode),
    )
    // `detail` is schema-optional: present when defined, omitted otherwise.
    val withDetail = dropped.detail match
      case Some(text) => fields :+ ("detail" -> Json.Str(text))
      case None       => fields
    Json.Obj(withDetail)

  private def limitsAst(limits: LabelLimits): Json =
    Json.Obj(
      "max_per_source" -> Json.Num(BigDecimal(limits.maxPerSource)),
      "max_effective" -> Json.Num(BigDecimal(limits.maxEffective)),
      "max_value_length_bytes" -> Json.Num(BigDecimal(limits.maxValueLengthBytes)),
      "reserved_key_prefix" -> Json.Str(limits.reservedKeyPrefix),
      "client_key_max_length" -> Json.Num(BigDecimal(limits.clientKeyMaxLength)),
    )
