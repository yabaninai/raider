package raider.runtime.lineage.wire

import raider.core.{JobId, RootId, SessionId}
import raider.core.codecs.given
import raider.runtime.events.{EventEnvelope, EventPayload}
import raider.runtime.events.EventCodecs.given
import raider.runtime.lineage.model.RecoveryClass
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Field-set strictness shared by the strictly-decoded v2 objects: any key
  * outside `allowed` is a decode failure (unknown fields are rejected, never
  * silently ignored).
  */
private[wire] def strictKeys(
    fields: Chunk[(String, Json)],
    allowed: Set[String]
): Either[String, Unit] =
  fields.find((k, _) => !allowed.contains(k)) match
    case Some((k, _)) => Left(s"unknown field '$k'")
    case None         => Right(())

/** Native artifact pointer of a cross-run provenance record (ADR-032 §1):
  * `kind` names the artifact class (e.g. `work_dir`, `events`), `path` is the
  * environment-relative location. Both non-empty; strictly decoded (unknown
  * fields rejected, never silently ignored).
  */
final case class ArtifactPointer(kind: String, path: String)

object ArtifactPointer:

  given JsonCodec[ArtifactPointer] = JsonCodec(
    JsonEncoder[Json].contramap { p =>
      Json.Obj(
        Chunk(
          "kind" -> Json.Str(p.kind),
          "path" -> Json.Str(p.path)
        )
      )
    },
    JsonDecoder[Json].mapOrFail {
      case Json.Obj(fields) =>
        def nonEmptyStr(name: String): Either[String, String] =
          fields
            .collectFirst { case (k, Json.Str(v)) if k == name => v }
            .fold(Left(s"missing '$name'"): Either[String, String]) { v =>
              if v.nonEmpty then Right(v) else Left(s"empty '$name'")
            }
        strictKeys(fields, Set("kind", "path")).flatMap { _ =>
          nonEmptyStr("kind").flatMap { kind =>
            nonEmptyStr("path").map(ArtifactPointer(kind, _))
          }
        }
      case _ => Left("artifact pointer must be a JSON object")
    }
  )

end ArtifactPointer

/** Payload of the versioned lineage envelope (ADR-032 §1, card RAI-143).
  * [[Frozen]] wraps one of the 13 frozen ADR-024 cases verbatim; the three v2
  * cases are additive and never reinterpret a frozen case.
  */
enum LineagePayload:

  /** One of the frozen 13 payloads, decoded/encoded by the frozen codecs. */
  case Frozen(payload: EventPayload)

  /** Phase enter with recovery metadata: creates a `SessionNode.Phase`.
    * `recovery` is the live re-run isolation policy class of the phase
    * (stream-carried metadata; deliberately not a graph-model field, ADR-032
    * §5).
    */
  case PhaseStarted(
      name: String,
      recovery: RecoveryClass,
      note: Option[String]
  )

  /** Phase exit: closes the innermost open phase with this name (LIFO per job).
    */
  case PhaseFinished(name: String)

  /** Cross-run provenance record: at most ONE per stream (a second is a typed
    * reducer error). `parentRunId` absent = root run, never guessed;
    * `artifacts` are the native artifact pointers of the upstream run.
    */
  case RunProvenance(
      parentRunId: Option[String],
      artifacts: Vector[ArtifactPointer]
  )

end LineagePayload

object LineagePayload:

  /** Discriminator values of the frozen 13 cases — the routing set between the
    * frozen codec and the v2 cases. The frozen enum is pinned (ADR-024); a
    * change there is a separate schema decision.
    */
  private val FrozenTypes: Set[String] = Set(
    "JobStatusChanged",
    "Waiting",
    "ModelCallStarted",
    "ModelCallFinished",
    "ToolStarted",
    "ToolProgress",
    "ToolFinished",
    "RetryScheduled",
    "BudgetChanged",
    "Delegated",
    "Diagnostics",
    "DiagnosticsCoalesced",
    "Terminal"
  )

  private def fieldsOf(value: Json): Either[String, Chunk[(String, Json)]] =
    value match
      case Json.Obj(fields) => Right(fields)
      case _                => Left("expected a JSON object")

  private def strField(
      fields: Chunk[(String, Json)],
      name: String
  ): Either[String, String] =
    fields
      .collectFirst { case (k, Json.Str(v)) if k == name => v }
      .fold(Left(s"missing '$name'"): Either[String, String])(Right(_))

  private def optStrField(
      fields: Chunk[(String, Json)],
      name: String
  ): Either[String, Option[String]] =
    fields.collectFirst { case (k, v) if k == name => v } match
      case None              => Right(None)
      case Some(Json.Null)   => Right(None)
      case Some(Json.Str(v)) => Right(Some(v))
      case Some(_)           => Left(s"'$name' must be a string")

  private def nonEmpty(name: String, v: String): Either[String, String] =
    if v.nonEmpty then Right(v) else Left(s"empty '$name'")

  private def recoveryFromName(name: String): Either[String, RecoveryClass] =
    name match
      case "ReadOnly"   => Right(RecoveryClass.ReadOnly)
      case "Idempotent" => Right(RecoveryClass.Idempotent)
      case "Mutating"   => Right(RecoveryClass.Mutating)
      case other        => Left(s"unknown RecoveryClass '$other'")

  private def decodeArtifacts(
      value: Json
  ): Either[String, Vector[ArtifactPointer]] =
    value match
      case Json.Arr(items) =>
        items.foldLeft[Either[String, Vector[ArtifactPointer]]](
          Right(Vector.empty)
        ) { (acc, item) =>
          for
            got <- acc
            next <- JsonDecoder[ArtifactPointer].fromJsonAST(item)
          yield got :+ next
        }
      case _ => Left("'artifacts' must be an array")

  private def decodePhaseStarted(value: Json): Either[String, LineagePayload] =
    for
      fields <- fieldsOf(value)
      _ <- strictKeys(fields, Set("type", "name", "recovery", "note"))
      rawName <- strField(fields, "name")
      name <- nonEmpty("name", rawName)
      rawRecovery <- strField(fields, "recovery")
      recovery <- recoveryFromName(rawRecovery)
      note <- optStrField(fields, "note")
    yield LineagePayload.PhaseStarted(name, recovery, note)

  private def decodePhaseFinished(value: Json): Either[String, LineagePayload] =
    for
      fields <- fieldsOf(value)
      _ <- strictKeys(fields, Set("type", "name"))
      rawName <- strField(fields, "name")
      name <- nonEmpty("name", rawName)
    yield LineagePayload.PhaseFinished(name)

  private def decodeRunProvenance(value: Json): Either[String, LineagePayload] =
    for
      fields <- fieldsOf(value)
      _ <- strictKeys(fields, Set("type", "parentRunId", "artifacts"))
      rawParent <- optStrField(fields, "parentRunId")
      parent <- rawParent match
        case Some(v) => nonEmpty("parentRunId", v).map(Some(_))
        case None    => Right(None)
      artifacts <- fields
        .collectFirst { case (k, v) if k == "artifacts" => v } match
        case Some(v) => decodeArtifacts(v)
        case None    => Left("missing 'artifacts'")
    yield LineagePayload.RunProvenance(parent, artifacts)

  /** Codec of the versioned payload union: frozen type names route to the
    * frozen ADR-024 codec (v1 decode pinned bit-for-bit); the three v2 names
    * are strict hand-written decoders (unknown fields are decode failures). An
    * unknown `type` is a decode failure, never a guess.
    */
  given JsonCodec[LineagePayload] = JsonCodec(
    JsonEncoder[Json].contramap {
      case LineagePayload.Frozen(payload) =>
        summon[JsonEncoder[EventPayload]]
          .toJsonAST(payload)
          .fold(msg => sys.error(msg), identity)
      case LineagePayload.PhaseStarted(name, recovery, note) =>
        val base = Chunk(
          "type" -> Json.Str("phase_started"),
          "name" -> Json.Str(name),
          "recovery" -> Json.Str(recovery.toString)
        )
        Json.Obj(note.fold(base)(n => base :+ ("note" -> Json.Str(n))))
      case LineagePayload.PhaseFinished(name) =>
        Json.Obj(
          Chunk(
            "type" -> Json.Str("phase_finished"),
            "name" -> Json.Str(name)
          )
        )
      case LineagePayload.RunProvenance(parentRunId, artifacts) =>
        val base = Chunk("type" -> Json.Str("run_provenance"))
        val withParent =
          parentRunId.fold(base)(id => base :+ ("parentRunId" -> Json.Str(id)))
        Json.Obj(
          withParent :+ ("artifacts" -> Json.Arr(
            Chunk.fromIterable(
              artifacts.map(a =>
                Json.Obj(
                  Chunk("kind" -> Json.Str(a.kind), "path" -> Json.Str(a.path))
                )
              )
            )
          ))
        )
    },
    JsonDecoder[Json].mapOrFail { value =>
      fieldsOf(value).flatMap { fields =>
        strField(fields, "type").flatMap {
          case t if FrozenTypes.contains(t) =>
            summon[JsonDecoder[EventPayload]]
              .fromJsonAST(value)
              .map(LineagePayload.Frozen(_))
          case "phase_started"  => decodePhaseStarted(value)
          case "phase_finished" => decodePhaseFinished(value)
          case "run_provenance" => decodeRunProvenance(value)
          case other => Left(s"unknown lineage payload type '$other'")
        }
      }
    }
  )

end LineagePayload

/** The versioned lineage envelope (ADR-032, card RAI-143): the lineage-layer
  * counterpart of the frozen ADR-024 envelope. The ADR-024 seam
  * (`raider.runtime.events.**`) is NOT modified; a [[LineageEnvelope]] at
  * `schemaVersion: 1` is wire-identical to the frozen envelope (same field
  * names, same order, same codec semantics) and adds, at `schemaVersion: 2`,
  * the optional correlation field `invocationId` plus the three v2 payload
  * cases.
  *
  * Versioning rules (ADR-032 §2, pinned):
  *   - v1 documents decode UNCHANGED bit-for-bit: the payload delegates to the
  *     frozen `EventCodecs`, and the derived envelope encoder emits
  *     `invocationId` last, omitted when absent — a v1 envelope re-encodes to
  *     the frozen bytes.
  *   - `invocationId` on a `schemaVersion: 1` envelope is a version-mixing
  *     anomaly: the reader refuses it typed, never silently ignores it.
  *   - Any `schemaVersion` other than 1 or 2 is refused typed by the reader and
  *     the reducer — unknown future versions are never guessed.
  *
  * Envelope-level unknown fields are tolerated with exactly the frozen codec
  * semantics (zio-json derived decode); v2 payload objects are strict (see
  * [[LineagePayload]]).
  */
final case class LineageEnvelope(
    schemaVersion: Int,
    seq: Long,
    jobId: JobId,
    rootId: RootId,
    sessionId: SessionId,
    atMs: Long,
    payload: LineagePayload,
    invocationId: Option[String] = None
)

object LineageEnvelope:
  given JsonCodec[LineageEnvelope] = DeriveJsonCodec.gen[LineageEnvelope]
end LineageEnvelope

/** Constants and views over the versioned wire. */
object LineageWire:

  /** The frozen `schemaVersion` of the ADR-024 seam (pinned). */
  val V1: Int = 1

  /** The ADR-032 additive revision. */
  val V2: Int = 2

  /** Views a frozen envelope as a lineage envelope: the identity view of the
    * pinned v1 form ([[LineagePayload.Frozen]], no `invocationId`). Callers
    * that must enforce the strict v1 rule (`schemaVersion == 1`) check it
    * BEFORE wrapping — the v1 reducer entry points do.
    */
  def wrap(e: EventEnvelope): LineageEnvelope =
    LineageEnvelope(
      e.schemaVersion,
      e.seq,
      e.jobId,
      e.rootId,
      e.sessionId,
      e.atMs,
      LineagePayload.Frozen(e.payload),
      None
    )

end LineageWire
