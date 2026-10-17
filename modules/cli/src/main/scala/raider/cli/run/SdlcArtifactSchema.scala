package raider.cli.run

import zio.Chunk
import zio.json.ast.Json
import zio.json.{DecoderOps, EncoderOps}

/** Versioned SDLC record projection shared by run.json and events.jsonl. The
  * typed evidence payload is owned by the core SDLC contract; this wire layer
  * only adds its record discriminator and schema version.
  */
object SdlcArtifactSchema:
  val CurrentVersion = 2

  enum Kind(val wireName: String):
    case CheckResult extends Kind("check-result")
    case ReviewVerdict extends Kind("review-verdict")
    case CandidateIdentity extends Kind("candidate-identity")
    case IntegrationResult extends Kind("integration-result")

  final case class Record(kind: Kind, id: String, data: Json.Obj)

  enum DecodeError:
    case Malformed(detail: String)
    case UnsupportedVersion(found: Long)
    case UnknownKind(found: String)
    case UnknownField(field: String)
    case UnknownPayloadField(kind: Kind, field: String)
    case MissingField(field: String)

  def encode(record: Record): String =
    Json
      .Obj(
        Chunk(
          "schemaVersion" -> Json.Num(CurrentVersion),
          "type" -> Json.Str(record.kind.wireName),
          "id" -> Json.Str(record.id),
          "data" -> record.data
        )
      )
      .toJson

  /** Add the v2 evidence extension to a run artifact. With no records the
    * original bytes are returned unchanged (the v1 golden pin).
    */
  def addToRunJson(base: String, records: List[Record]): String =
    if records.isEmpty then base
    else
      Json.decoder.decodeJson(base) match
        case Right(Json.Obj(fields)) =>
          val evidence = Json.Arr(
            Chunk.fromIterable(
              records.map(r => Json.decoder.decodeJson(encode(r)).toOption.get)
            )
          )
          Json
            .Obj(
              fields ++ Chunk(
                "sdlcSchemaVersion" -> Json.Num(CurrentVersion),
                "sdlcEvidence" -> evidence
              )
            )
            .toJson
        case _ => base

  /** Decode only the versioned record envelope. Unknown fields in a typed
    * record are refused rather than discarded; additive fields on surrounding
    * run documents remain the responsibility of their tolerant v1 consumers.
    */
  def decode(input: String): Either[DecodeError, Record] =
    Json.decoder
      .decodeJson(input)
      .left
      .map(DecodeError.Malformed.apply)
      .flatMap {
        case Json.Obj(fields) =>
          val values = fields.toMap
          values.keys
            .find(k => !Set("schemaVersion", "type", "id", "data")(k)) match
            case Some(field) => Left(DecodeError.UnknownField(field))
            case None =>
              def required(name: String) =
                values.get(name).toRight(DecodeError.MissingField(name))
              for
                versionJson <- required("schemaVersion")
                version <- versionJson match
                  case Json.Num(n) =>
                    scala.util
                      .Try(n.longValueExact())
                      .toEither
                      .left
                      .map(_ =>
                        DecodeError
                          .Malformed("schemaVersion must be an integer")
                      )
                  case _ =>
                    Left(
                      DecodeError.Malformed("schemaVersion must be an integer")
                    )
                _ <- Either.cond(
                  version == CurrentVersion,
                  (),
                  DecodeError.UnsupportedVersion(version)
                )
                kindJson <- required("type")
                kindText <- kindJson match
                  case Json.Str(s) => Right(s)
                  case _ => Left(DecodeError.Malformed("type must be a string"))
                kind <- Kind.values
                  .find(_.wireName == kindText)
                  .toRight(DecodeError.UnknownKind(kindText))
                idJson <- required("id")
                id <- idJson match
                  case Json.Str(s) if s.nonEmpty => Right(s)
                  case _ =>
                    Left(DecodeError.Malformed("id must be a non-empty string"))
                dataJson <- required("data")
                data <- dataJson match
                  case obj: Json.Obj =>
                    obj.fields
                      .find(_._1 != "digest")
                      .map(kv =>
                        Left(DecodeError.UnknownPayloadField(kind, kv._1))
                      )
                      .getOrElse {
                        obj.fields.find(_._1 == "digest") match
                          case Some((_, Json.Str(_))) => Right(obj)
                          case Some(_) =>
                            Left(
                              DecodeError.Malformed(
                                s"${kind.wireName}.data.digest must be a string"
                              )
                            )
                          case None => Right(obj)
                      }
                  case _ =>
                    Left(DecodeError.Malformed("data must be an object"))
              yield Record(kind, id, data)
        case _ => Left(DecodeError.Malformed("record must be a JSON object"))
      }

  /** Read the additive run.json SDLC projection. Unrelated top-level keys are
    * intentionally tolerated for forward-compatible v1 consumers, while the
    * version and each typed record remain strict.
    */
  def decodeRunJson(input: String): Either[DecodeError, List[Record]] =
    Json.decoder
      .decodeJson(input)
      .left
      .map(DecodeError.Malformed.apply)
      .flatMap {
        case Json.Obj(fields) =>
          val values = fields.toMap
          (values.get("sdlcSchemaVersion"), values.get("sdlcEvidence")) match
            case (None, None) => Right(Nil)
            case (Some(Json.Num(version)), Some(Json.Arr(items))) =>
              scala.util
                .Try(version.longValueExact())
                .toEither
                .left
                .map(_ =>
                  DecodeError.Malformed("sdlcSchemaVersion must be an integer")
                )
                .flatMap(v =>
                  Either.cond(
                    v == CurrentVersion,
                    items,
                    DecodeError.UnsupportedVersion(v)
                  )
                )
                .flatMap(items =>
                  items.toList.foldLeft[Either[DecodeError, List[Record]]](
                    Right(Nil)
                  ) { case (acc, item) =>
                    acc.flatMap(done => decode(item.toJson).map(done :+ _))
                  }
                )
            case (Some(_), Some(_)) =>
              Left(DecodeError.Malformed("invalid SDLC version/evidence pair"))
            case _ =>
              Left(
                DecodeError.Malformed("incomplete SDLC version/evidence pair")
              )
        case _ =>
          Left(DecodeError.Malformed("run artifact must be a JSON object"))
      }

  /** Decode a JSONL stream containing frozen v1 event objects and v2 SDLC
    * records. Existing v1 lines are returned byte-for-byte and not interpreted
    * by the SDLC codec.
    */
  def decodeMixed(
      lines: Iterable[String]
  ): Either[DecodeError, Vector[Either[String, Record]]] =
    lines.iterator
      .filter(_.nonEmpty)
      .foldLeft[Either[DecodeError, Vector[Either[String, Record]]]](
        Right(Vector.empty)
      ) { case (acc, line) =>
        acc.flatMap { records =>
          Json.decoder
            .decodeJson(line)
            .left
            .map(DecodeError.Malformed.apply)
            .flatMap {
              case obj: Json.Obj =>
                val fields = obj.fields.toMap
                fields.get("type") match
                  case Some(Json.Str(t))
                      if Kind.values.exists(_.wireName == t) =>
                    decode(line).map(r => records :+ Right(r))
                  case Some(Json.Str(t)) =>
                    fields.get("schemaVersion") match
                      case Some(Json.Num(v)) =>
                        scala.util.Try(v.longValueExact()).toEither match
                          case Right(1L) => Right(records :+ Left(line))
                          case Right(CurrentVersion) =>
                            Left(DecodeError.UnknownKind(t))
                          case Right(version) =>
                            Left(DecodeError.UnsupportedVersion(version))
                          case Left(_) =>
                            Left(
                              DecodeError.Malformed(
                                "schemaVersion must be an integer"
                              )
                            )
                      case _ =>
                        Left(
                          DecodeError
                            .Malformed("event line lacks schemaVersion")
                        )
                  case _ =>
                    fields.get("schemaVersion") match
                      case Some(Json.Num(v)) =>
                        scala.util.Try(v.longValueExact()).toEither match
                          case Right(1L) => Right(records :+ Left(line))
                          case Right(version) =>
                            Left(DecodeError.UnsupportedVersion(version))
                          case Left(_) =>
                            Left(
                              DecodeError
                                .Malformed("schemaVersion must be an integer")
                            )
                      case _ =>
                        Left(
                          DecodeError
                            .Malformed("event line lacks schemaVersion")
                        )
              case _ =>
                Left(DecodeError.Malformed("event line must be a JSON object"))
            }
        }
      }

end SdlcArtifactSchema
