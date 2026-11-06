package raider.backend.pi

import zio.*
import zio.json.*
import zio.json.ast.Json

import scala.io.{Codec, Source}

/** Contracts/backends/pi consistency: the adapter's canonical capabilities JSON
  * must equal the checked-in golden byte-for-byte, and must satisfy the
  * structural constraints of pi-rpc-capabilities.schema.json (required keys,
  * enum values, protocol pin). Honest-unknown rules are asserted directly.
  */
object PiRpcContractSpec:

  val GoldenPath = "contracts/backends/pi/golden/pi-rpc-capabilities.json"
  val SchemaPath = "contracts/backends/pi/pi-rpc-capabilities.schema.json"

  def main(args: Array[String]): Unit = PiSpecSupport.runUnsafe(spec)

  private def spec: ZIO[Any, Throwable, Unit] =
    ZIO.attempt {
      val caps = PiRpcCapabilities.defaults
      val canonical = caps.canonicalJson

      val golden = readFile(GoldenPath)
      PiSpecSupport.check(
        "canonical capabilities JSON equals the golden fixture byte-for-byte"
      )(
        canonical == golden,
        s"canonical=${canonical.take(200)}... golden=${golden.take(200)}..."
      )

      val schema = readFile(SchemaPath)
      schema.fromJson[Json] match
        case Left(err) =>
          PiSpecSupport.check("schema file parses as JSON")(false, err)
        case Right(schemaJson) =>
          schemaChecks(schemaJson, canonical)
    }

  private def schemaChecks(schemaJson: Json, canonical: String): Unit =
    val required: Set[String] =
      schemaJson.asObject.flatMap(_.get("required")).flatMap(_.asArray) match
        case Some(arr) => arr.toSeq.collect { case Json.Str(s) => s }.toSet
        case None      => Set.empty
    val requiredSchema = Set(
      "schemaVersion",
      "backend",
      "protocol",
      "streaming",
      "tools",
      "structuredOutput",
      "steering",
      "cancellationAck",
      "nestedAccounting",
      "sessionSupport",
      "toolOwnership",
      "priceModel"
    )
    PiSpecSupport.check("schema requires the full capability key set")(
      required == requiredSchema,
      s"required=$required expected=$requiredSchema"
    )

    val enumByField = enumValues(schemaJson)
    canonical.fromJson[Json] match
      case Left(err) => PiSpecSupport.check("canonical JSON parses")(false, err)
      case Right(capsJson) =>
        capsJson.asObject.foreach { obj =>
          enumByField.foreach { case (field, allowed) =>
            val v = obj.get(field).flatMap(_.asString)
            PiSpecSupport.check(s"field $field within schema enum")(
              v.exists(allowed.contains),
              s"field=$field value=$v allowed=$allowed"
            )
          }

          val protocol = obj.get("protocol").flatMap(_.asString).getOrElse("")
          PiSpecSupport.check("protocol names the pinned upstream commit")(
            protocol.contains("55a5c6914570e2ae31ca36297654483bbbf46883"),
            s"protocol=$protocol"
          )

          PiSpecSupport.check(
            "nested accounting is honestly Unknown (never claimed)"
          )(
            obj.get("nestedAccounting").flatMap(_.asString).contains("unknown"),
            s"nestedAccounting=${obj.get("nestedAccounting")}"
          )
          PiSpecSupport.check(
            "tool ownership disclaimer present (Raider policy not enforced inside pi)"
          )(
            obj
              .get("toolOwnership")
              .flatMap(_.asString)
              .exists(_.contains("NOT enforced")),
            s"toolOwnership=${obj.get("toolOwnership")}"
          )
          PiSpecSupport.check(
            "session support is Unsupported (process-per-run)"
          )(
            obj
              .get("sessionSupport")
              .flatMap(_.asString)
              .contains("unsupported"),
            s"sessionSupport=${obj.get("sessionSupport")}"
          )
        }

  private def enumValues(schemaJson: Json): Map[String, Set[String]] =
    schemaJson.asObject.flatMap(_.get("properties")).flatMap(_.asObject) match
      case None => Map.empty
      case Some(props) =>
        props.fields.toSeq.collect {
          case (field, Json.Obj(f)) if f.exists(_._1 == "enum") =>
            val allowed = f.find(_._1 == "enum").flatMap(_._2.asArray) match
              case Some(arr) =>
                arr.toSeq.collect { case Json.Str(s) => s }.toSet
              case None => Set.empty[String]
            field -> allowed
        }.toMap

  private def readFile(relPath: String): String =
    val src = Source.fromFile(relPath)(using Codec.UTF8)
    try src.mkString
    finally src.close()
