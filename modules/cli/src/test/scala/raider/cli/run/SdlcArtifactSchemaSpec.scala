package raider.cli.run

import zio.Chunk
import zio.json.ast.Json
import zio.json.EncoderOps
import zio.test.*

object SdlcArtifactSchemaSpec extends ZIOSpecDefault:
  import SdlcArtifactSchema.*

  private val records = Kind.values.toList.zipWithIndex.map { case (kind, i) =>
    Record(
      kind,
      s"evidence-$i",
      Json.Obj(Chunk("digest" -> Json.Str(s"sha-$i")))
    )
  }

  private val v1Line =
    """{"schemaVersion":1,"seq":1,"type":"diagnostics","detail":"ok"}"""

  private val v1Run = """{"schemaVersion":1,"status":"succeeded","checks":[]}"""

  def spec = suite("versioned SDLC artifact records")(
    test("SDLC-WIRE-01a: no evidence retains exact v1 run bytes") {
      assertTrue(addToRunJson(v1Run, Nil) == v1Run)
    },
    test(
      "SDLC-WIRE-01b: each typed record roundtrips in run.json and events.jsonl; additive run fields tolerate"
    ) {
      val run = addToRunJson(v1Run, records)
      val runRecords = Json.decoder
        .decodeJson(run)
        .toOption
        .collect { case Json.Obj(fields) =>
          fields.toMap.get("sdlcEvidence")
        }
        .flatten
        .collect { case Json.Arr(xs) => xs.toVector }
      val runDecoded = runRecords.toVector.flatten.map(_.toJson).map(decode)
      val tolerantRun = run.dropRight(1) + ",\"futureRunField\":true}"
      for
        dir <- zio.ZIO
          .attempt(java.nio.file.Files.createTempDirectory("sdlc-wire"))
          .orDie
        file = dir.resolve("events.jsonl")
        writer <- EventsJsonlWriter.open(file)
        _ <- zio.ZIO.foreachDiscard(records)(writer.appendSdlc)
        _ <- writer.close
        lines <- zio.ZIO
          .attempt(
            java.nio.file.Files
              .readAllLines(file)
              .toArray
              .toVector
              .map(_.toString)
          )
          .orDie
        eventDecoded = lines.map(decode)
      yield assertTrue(
        runDecoded.forall(_.isRight),
        decodeRunJson(tolerantRun).contains(records),
        eventDecoded.forall(_.isRight),
        eventDecoded.collect { case Right(r) => r } == records,
        runRecords.exists(_.size == records.size)
      )
    },
    test(
      "SDLC-WIRE-03: a mixed v1/v2 event stream preserves order and v1 bytes"
    ) {
      val input = Vector(v1Line, encode(records.head), v1Line)
      val decoded = decodeMixed(input)
      assertTrue(
        decoded.exists(_.size == 3),
        decoded.toOption.exists(_.head == Left(v1Line)),
        decoded.toOption.exists(_.last == Left(v1Line)),
        decoded.toOption.exists(_.apply(1) == Right(records.head))
      )
    },
    test(
      "SDLC-WIRE-02: typed record refuses unknown fields and unsupported versions"
    ) {
      val unknown = encode(records.head).dropRight(1) + ",\"unexpected\":true}"
      val version = encode(records.head)
        .replace("\"schemaVersion\":2", "\"schemaVersion\":3")
      assertTrue(
        decode(unknown) == Left(DecodeError.UnknownField("unexpected")),
        decode(version) == Left(DecodeError.UnsupportedVersion(3L))
      )
    }
  )

end SdlcArtifactSchemaSpec
