package raider.dsl.lineage.data

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}

/** Versioned lineage-data.json document (LIN-02B, ADR-027 §3; v2 additive
  * extension ADR-032, card RAI-143).
  *
  * Wire rules (same discipline as raider.runtime.events.EventCodecs):
  *   - `NodeKind` encodes as the schema's snake_case names
  *     (`model_call|tool_call|delegation|phase`); an unknown name is a decode
  *     failure, never a best-effort guess.
  *   - `NodeData` cases have disjoint required fields, so each `data` entry is
  *     a flat node-specific object (no discriminator on the wire); the decoder
  *     tries the four case codecs in a fixed order.
  *   - Option fields the frozen schema marks required-nullable (`parentId`,
  *     `usage`, `outcome`, `resultTail`, `note`, `reservedMicroUsd`,
  *     `observedMicroUsd`) encode `None` as an explicit `Json.Null` (review F1:
  *     a root document must pass `contracts/lineage/lineage-data.schema.json`).
  *     The decoder accepts both an explicit `null` and absence for them.
  *   - `dataRef` is optional but non-null in the schema, so it stays omitted
  *     when absent (an explicit null would violate `type: "string"`).
  *   - `data` object keys are sorted on encode, so the same document always
  *     serializes to byte-identical JSON regardless of map construction order.
  *   - ADR-032 v2 fields (`provenance`, `phaseData.recovery`,
  *     `delegationData.invocationId`) are optional and OMITTED when absent, so
  *     a v1 document encodes byte-identically to the frozen revision; carrying
  *     any of them in a `schemaVersion: 1` document is refused typed by
  *     [[LineageDataFile.read]] (`LineageDataError.V2FieldOnV1`), never
  *     silently ignored.
  */
final case class LineageDataDocument(
    schemaVersion: Int,
    generator: GeneratorInfo,
    runId: String,
    nodes: Vector[DataNode],
    data: Map[String, NodeData],
    provenance: Option[ProvenanceRecord] = None
)

object LineageDataDocument:

  // Canonical output: sorted `data` keys keep every write byte-stable.
  private given JsonEncoder[Map[String, NodeData]] =
    JsonEncoder[Json].contramap { entries =>
      Json.Obj(
        Chunk.fromIterable(entries.toList.sortBy(_._1)).map {
          case (key, value) =>
            key -> summon[JsonEncoder[NodeData]]
              .toJsonAST(value)
              .fold(msg => sys.error(msg), identity)
        }
      )
    }

  given JsonEncoder[LineageDataDocument] =
    DeriveJsonEncoder.gen[LineageDataDocument]

  given JsonDecoder[LineageDataDocument] =
    DeriveJsonDecoder.gen[LineageDataDocument]

end LineageDataDocument

final case class GeneratorInfo(
    name: String,
    version: String,
    scalaVersion: String,
    jdkVersion: String
)

object GeneratorInfo:
  given JsonCodec[GeneratorInfo] = DeriveJsonCodec.gen[GeneratorInfo]

/** Cross-run provenance record (ADR-032 §1): `parentRunId` absent/null = root
  * run, never guessed; `artifacts` are native artifact pointers of the upstream
  * run. `parentRunId` is optional-nullable on the wire (omitted when absent).
  */
final case class ProvenanceRecord(
    parentRunId: Option[String],
    artifacts: Vector[ProvenanceArtifact]
)

object ProvenanceRecord:
  given JsonCodec[ProvenanceRecord] = DeriveJsonCodec.gen[ProvenanceRecord]

final case class ProvenanceArtifact(kind: String, path: String)

object ProvenanceArtifact:

  given JsonCodec[ProvenanceArtifact] =
    DeriveJsonCodec.gen[ProvenanceArtifact]

final case class DataNode(
    id: String,
    kind: NodeKind,
    parentId: Option[String],
    seq: Long,
    atMs: Long,
    inputs: Vector[String],
    dataRef: Option[String]
)

object DataNode:

  // Canonical node shape (review F1): every key the frozen schema marks
  // required is present — `parentId` is an explicit null for roots. `dataRef`
  // is optional and non-null in the schema, so it stays omitted when absent.
  given JsonEncoder[DataNode] =
    JsonEncoder[Json].contramap { node =>
      val base = Chunk(
        "id" -> Json.Str(node.id),
        "kind" -> summon[JsonEncoder[NodeKind]]
          .toJsonAST(node.kind)
          .fold(msg => sys.error(msg), identity),
        "parentId" -> node.parentId.fold[Json](Json.Null)(Json.Str(_)),
        "seq" -> Json.Num(node.seq),
        "atMs" -> Json.Num(node.atMs),
        "inputs" -> Json.Arr(Chunk.fromIterable(node.inputs).map(Json.Str(_)))
      )
      Json.Obj(
        node.dataRef.fold(base)(ref => base :+ ("dataRef" -> Json.Str(ref)))
      )
    }

  given JsonDecoder[DataNode] = DeriveJsonDecoder.gen[DataNode]

end DataNode

enum NodeKind:
  case ModelCall, ToolCall, Delegation, Phase

object NodeKind:

  // snake_case wire names are part of the frozen schema (packets/L3 contract).
  given JsonCodec[NodeKind] = JsonCodec(
    JsonEncoder.string.contramap {
      case NodeKind.ModelCall  => "model_call"
      case NodeKind.ToolCall   => "tool_call"
      case NodeKind.Delegation => "delegation"
      case NodeKind.Phase      => "phase"
    },
    JsonDecoder.string.mapOrFail {
      case "model_call" => Right(NodeKind.ModelCall)
      case "tool_call"  => Right(NodeKind.ToolCall)
      case "delegation" => Right(NodeKind.Delegation)
      case "phase"      => Right(NodeKind.Phase)
      case other        => Left(s"unknown NodeKind '$other'")
    }
  )

enum NodeData:

  case ModelCallData(
      model: String,
      round: Int,
      prompt: String,
      response: String,
      usage: Option[Usage]
  )

  case ToolCallData(
      tool: String,
      callId: String,
      recovery: String,
      outcome: Option[String],
      argsSummary: String,
      resultTail: Option[String]
  )

  case DelegationData(
      childJobId: String,
      agent: String,
      invocationId: Option[String] = None
  )

  case PhaseData(
      name: String,
      note: Option[String],
      recovery: Option[String] = None
  )

object NodeData:

  // Canonical data shapes (review F1): `usage`, `outcome`, `resultTail` and
  // `note` are required-nullable keys in the frozen schema, so their `None`
  // encodes as an explicit `Json.Null`, never as an omitted key. The ADR-032
  // v2 fields (`PhaseData.recovery`, `DelegationData.invocationId`) are
  // optional NON-nullable, so their `None` stays omitted entirely — a v1
  // document encodes byte-identically to the frozen revision.
  private given JsonEncoder[ModelCallData] =
    JsonEncoder[Json].contramap { data =>
      Json.Obj(
        Chunk(
          "model" -> Json.Str(data.model),
          "round" -> Json.Num(data.round),
          "prompt" -> Json.Str(data.prompt),
          "response" -> Json.Str(data.response),
          "usage" -> data.usage.fold[Json](Json.Null)(usage =>
            summon[JsonEncoder[Usage]]
              .toJsonAST(usage)
              .fold(msg => sys.error(msg), identity)
          )
        )
      )
    }

  private given JsonDecoder[ModelCallData] = DeriveJsonDecoder.gen

  private given JsonEncoder[ToolCallData] =
    JsonEncoder[Json].contramap { data =>
      Json.Obj(
        Chunk(
          "tool" -> Json.Str(data.tool),
          "callId" -> Json.Str(data.callId),
          "recovery" -> Json.Str(data.recovery),
          "outcome" -> data.outcome.fold[Json](Json.Null)(Json.Str(_)),
          "argsSummary" -> Json.Str(data.argsSummary),
          "resultTail" -> data.resultTail.fold[Json](Json.Null)(Json.Str(_))
        )
      )
    }

  private given JsonDecoder[ToolCallData] = DeriveJsonDecoder.gen

  private given JsonEncoder[DelegationData] = DeriveJsonEncoder.gen
  private given JsonDecoder[DelegationData] = DeriveJsonDecoder.gen

  private given JsonEncoder[PhaseData] =
    JsonEncoder[Json].contramap { data =>
      val base = Chunk(
        "name" -> Json.Str(data.name),
        "note" -> data.note.fold[Json](Json.Null)(Json.Str(_))
      )
      Json.Obj(
        data.recovery.fold(base)(r => base :+ ("recovery" -> Json.Str(r)))
      )
    }

  private given JsonDecoder[PhaseData] = DeriveJsonDecoder.gen

  // Flat wire objects (no discriminator): the four cases have disjoint
  // required fields, so a fixed-order attempt decodes deterministically.
  given JsonEncoder[NodeData] =
    JsonEncoder[Json].contramap {
      case v: ModelCallData =>
        summon[JsonEncoder[ModelCallData]]
          .toJsonAST(v)
          .fold(msg => sys.error(msg), identity)
      case v: ToolCallData =>
        summon[JsonEncoder[ToolCallData]]
          .toJsonAST(v)
          .fold(msg => sys.error(msg), identity)
      case v: DelegationData =>
        summon[JsonEncoder[DelegationData]]
          .toJsonAST(v)
          .fold(msg => sys.error(msg), identity)
      case v: PhaseData =>
        summon[JsonEncoder[PhaseData]]
          .toJsonAST(v)
          .fold(msg => sys.error(msg), identity)
    }

  given JsonDecoder[NodeData] =
    JsonDecoder[Json].mapOrFail { value =>
      val attempts = Seq(
        summon[JsonDecoder[ModelCallData]].fromJsonAST(value),
        summon[JsonDecoder[ToolCallData]].fromJsonAST(value),
        summon[JsonDecoder[DelegationData]].fromJsonAST(value),
        summon[JsonDecoder[PhaseData]].fromJsonAST(value)
      )
      attempts
        .collectFirst { case Right(data) => Right(data) }
        .getOrElse(Left("lineage data entry matches no NodeData variant"))
    }

end NodeData

final case class Usage(
    reservedMicroUsd: Option[Long],
    observedMicroUsd: Option[Long],
    unknown: Boolean
)

object Usage:

  // The frozen schema marks reservedMicroUsd/observedMicroUsd
  // required-nullable: None encodes as an explicit null, never an omitted key
  // (review F1).
  given JsonEncoder[Usage] =
    JsonEncoder[Json].contramap { usage =>
      Json.Obj(
        Chunk(
          "reservedMicroUsd" -> usage.reservedMicroUsd
            .fold[Json](Json.Null)(Json.Num(_)),
          "observedMicroUsd" -> usage.observedMicroUsd
            .fold[Json](Json.Null)(Json.Num(_)),
          "unknown" -> Json.Bool(usage.unknown)
        )
      )
    }

  given JsonDecoder[Usage] = DeriveJsonDecoder.gen[Usage]

end Usage

enum LineageDataError:
  case Malformed(detail: String)
  case SchemaVersion(found: Int)

  /** A `schemaVersion: 1` document carries an ADR-032 v2 field (`provenance`,
    * `phaseData.recovery` or `delegationData.invocationId`): v1 is pinned, so
    * the mixing anomaly is refused typed, never silently ignored (ADR-032 §4).
    */
  case V2FieldOnV1(detail: String)
  case BrokenRef(nodeId: String, dataRef: String)

/** Atomic file IO + read validation for lineage-data.json (LIN-02B contract;
  * versioned per ADR-032 §4).
  */
object LineageDataFile:

  /** The frozen revision (pinned) and the ADR-032 additive revision. */
  private val SupportedSchemaVersions = Set(1, 2)

  /** Write atomically: unique tmp file in the target directory + ATOMIC_MOVE
    * (SessionStore/JobJournal pattern). Concurrent writers to distinct targets
    * never interact; a reader of one target always sees a whole document.
    */
  def write(doc: LineageDataDocument, path: Path): Unit =
    val dir = Option(path.toAbsolutePath.getParent).getOrElse(Path.of("."))
    Files.createDirectories(dir)
    val tmp = Files.createTempFile(dir, path.getFileName.toString + ".", ".tmp")
    try
      Files.writeString(tmp, doc.toJson, StandardCharsets.UTF_8)
      Files.move(
        tmp,
        path,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE
      )
    finally
      Files.deleteIfExists(tmp)
      ()

  /** Decode + validate: schemaVersion must be 1 (frozen) or 2 (ADR-032); a v1
    * document must not carry any v2 field; every node dataRef must resolve into
    * `data`. parents-before-children ordering is intentionally NOT checked here
    * — that is the graph layer (wave-2).
    */
  def read(path: Path): Either[LineageDataError, LineageDataDocument] =
    try
      Files
        .readString(path, StandardCharsets.UTF_8)
        .fromJson[LineageDataDocument] match
        case Left(error)     => Left(LineageDataError.Malformed(error))
        case Right(document) => validate(document)
    catch
      case error: java.io.IOException =>
        Left(LineageDataError.Malformed(String.valueOf(error)))

  private def validate(
      document: LineageDataDocument
  ): Either[LineageDataError, LineageDataDocument] =
    if !SupportedSchemaVersions.contains(document.schemaVersion) then
      Left(LineageDataError.SchemaVersion(document.schemaVersion))
    else
      v2FieldsOnV1(document) match
        case Some(detail) => Left(LineageDataError.V2FieldOnV1(detail))
        case None =>
          val broken = document.nodes.flatMap { node =>
            node.dataRef
              .filter(ref => !document.data.contains(ref))
              .map(ref => LineageDataError.BrokenRef(node.id, ref))
          }
          broken.headOption match
            case Some(error) => Left(error)
            case None        => Right(document)

  /** The first v2-only field of a `schemaVersion: 1` document, if any
    * (deterministic order: top-level provenance, then node order).
    */
  private def v2FieldsOnV1(
      document: LineageDataDocument
  ): Option[String] =
    if document.schemaVersion != 1 then None
    else
      val nodeLevel = document.nodes.collectFirst {
        case node
            if node.dataRef
              .flatMap(document.data.get)
              .flatMap(v2FieldName)
              .isDefined =>
          val name = node.dataRef
            .flatMap(document.data.get)
            .flatMap(v2FieldName)
            .getOrElse("")
          s"node ${node.id}: $name"
      }
      document.provenance.map(_ => "top-level provenance").orElse(nodeLevel)
  end v2FieldsOnV1

  private def v2FieldName(payload: NodeData): Option[String] = payload match
    case NodeData.PhaseData(_, _, Some(_)) =>
      Some("phaseData.recovery")
    case NodeData.DelegationData(_, _, Some(_)) =>
      Some("delegationData.invocationId")
    case _ => None
  end v2FieldName
