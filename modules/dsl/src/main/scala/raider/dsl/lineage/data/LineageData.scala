package raider.dsl.lineage.data

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}

/** FROZEN lineage-data.json document (schemaVersion=1) — LIN-02B, ADR-027 §3.
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
  */
final case class LineageDataDocument(
    schemaVersion: Int,
    generator: GeneratorInfo,
    runId: String,
    nodes: Vector[DataNode],
    data: Map[String, NodeData]
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
      agent: String
  )

  case PhaseData(
      name: String,
      note: Option[String]
  )

object NodeData:

  // Canonical data shapes (review F1): `usage`, `outcome`, `resultTail` and
  // `note` are required-nullable keys in the frozen schema, so their `None`
  // encodes as an explicit `Json.Null`, never as an omitted key. DelegationData
  // has no optional fields and keeps the derived encoder.
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
      Json.Obj(
        Chunk(
          "name" -> Json.Str(data.name),
          "note" -> data.note.fold[Json](Json.Null)(Json.Str(_))
        )
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
  case BrokenRef(nodeId: String, dataRef: String)

/** Atomic file IO + read validation for lineage-data.json (LIN-02B contract).
  */
object LineageDataFile:

  private val SupportedSchemaVersion = 1

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

  /** Decode + validate: schemaVersion must be 1; every node dataRef must
    * resolve into `data`. parents-before-children ordering is intentionally NOT
    * checked here — that is the graph layer (wave-2).
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
    if document.schemaVersion != SupportedSchemaVersion then
      Left(LineageDataError.SchemaVersion(document.schemaVersion))
    else
      val broken = document.nodes.flatMap { node =>
        node.dataRef
          .filter(ref => !document.data.contains(ref))
          .map(ref => LineageDataError.BrokenRef(node.id, ref))
      }
      broken.headOption match
        case Some(error) => Left(error)
        case None        => Right(document)
