package raider.cli.run

import zio.Chunk
import zio.json.ast.Json
import zio.json.EncoderOps
import zio.test.*
import raider.core.{Program, Task}
import raider.core.sdlc.{SdlcEvidence, SdlcStages}
import zio.json.*

object SdlcArtifactSchemaSpec extends ZIOSpecDefault:
  import SdlcArtifactSchema.*

  private val sampleCandidate = SdlcEvidence.CandidateIdentity(
    "repo",
    "worktree",
    Some("base"),
    Some("head"),
    None,
    "tree",
    "task",
    "plan",
    "policy",
    "config"
  )

  private val typedEvidence = List(
    SdlcEvidence.artifact(
      "check-result",
      SdlcEvidence.CheckResult(
        "compile",
        "definition",
        "argv",
        SdlcEvidence.CheckOutcome.Passed,
        Some(0),
        "scala",
        "verifier",
        sampleCandidate,
        "output",
        "policy"
      )
    ),
    SdlcEvidence.artifact(
      "review-verdict",
      SdlcEvidence.ReviewVerdict(
        "reviewer",
        SdlcEvidence.ReviewDecision.Acceptable,
        "findings",
        "task",
        "plan",
        sampleCandidate,
        List("check-digest"),
        "policy"
      )
    ),
    SdlcEvidence.artifact("candidate-identity", sampleCandidate),
    SdlcEvidence.artifact(
      "integration-result",
      SdlcEvidence.IntegrationResult(
        "operation",
        "local",
        sampleCandidate,
        "target",
        "preconditions",
        Some("observed"),
        SdlcEvidence.IntegrationOutcome.Applied,
        List("review-digest"),
        "policy"
      )
    )
  )

  private val records = typedEvidence.zipWithIndex.map { case (json, index) =>
    SdlcArtifactSchema
      .fromEvidence(
        s"evidence-$index",
        SdlcEvidence.decodeArtifact(json).toOption.get
      )
      .toOption
      .get
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
    test("SDLC-WIRE-03c: mixed stream refuses unknown record kinds") {
      val unknownKind =
        """{"schemaVersion":2,"type":"future-record","id":"future-1","data":{}}"""
      assertTrue(
        decodeMixed(Vector(v1Line, unknownKind)) ==
          Left(DecodeError.UnknownKind("future-record"))
      )
    },
    test(
      "SDLC-WIRE-01c: a completed SDLC stage run persists all typed records in both artifacts"
    ) {
      import SdlcEvidence.*
      import SdlcStages.*
      val candidate = CandidateIdentity(
        "repo",
        "worktree",
        Some("base"),
        Some("head"),
        None,
        "tree",
        "task",
        "plan",
        "policy",
        "config"
      )
      val check = CheckResult(
        "compile",
        "definition",
        "argv",
        CheckOutcome.Passed,
        Some(0),
        "scala",
        "verifier",
        candidate,
        "output",
        "policy"
      )
      val review = ReviewVerdict(
        "reviewer",
        ReviewDecision.Acceptable,
        "findings",
        "task",
        "plan",
        candidate,
        List("check-digest"),
        "policy"
      )
      val integration = IntegrationResult(
        "operation",
        "local",
        candidate,
        "target",
        "preconditions",
        Some("observed"),
        IntegrationOutcome.Applied,
        List("review-digest"),
        "policy"
      )
      val stages = List(
        Stage.Task,
        Stage.Plan,
        Stage.Implement,
        Stage.Checks,
        Stage.Review,
        Stage.Integration
      )
      val typed = Map(
        Stage.Checks -> List(SdlcEvidence.Record.Check(check)),
        Stage.Review -> List(SdlcEvidence.Record.Review(review)),
        Stage.Integration -> List(SdlcEvidence.Record.Integration(integration))
      )
      val handlers = stages.map { stage =>
        stage -> Program.fromFunction[Context, StageResult](stage.toString) {
          context =>
            Task.succeed(
              StageResult(
                context,
                successful = true,
                stage.toString,
                typedEvidence = typed.getOrElse(stage, Nil)
              )
            )
        }
      }.toMap
      val program = SdlcStages
        .program(
          WorkflowDescription(
            stages.map(s => StageDescription(s, Nil, Nil, Nil)),
            Limits(0, 0)
          ),
          handlers
        )
        .toOption
        .get
      Task.zio(program(Context(candidate))).flatMap { results =>
        val records = SdlcArtifactSchema.fromStageResults(results).toOption.get
        val run = SdlcArtifactSchema.addToRunJson(v1Run, records)
        for
          dir <- zio.ZIO
            .attempt(java.nio.file.Files.createTempDirectory("sdlc-stage-run"))
            .orDie
          _ <- zio.ZIO
            .attempt(
              java.nio.file.Files.writeString(dir.resolve("run.json"), run)
            )
            .orDie
          runArtifact <- zio.ZIO
            .attempt(java.nio.file.Files.readString(dir.resolve("run.json")))
            .orDie
          writer <- EventsJsonlWriter.open(dir.resolve("events.jsonl"))
          _ <- zio.ZIO.foreachDiscard(records)(writer.appendSdlc)
          _ <- writer.close
          lines <- zio.ZIO
            .attempt(
              java.nio.file.Files
                .readAllLines(dir.resolve("events.jsonl"))
                .toArray
                .toVector
                .map(_.toString)
            )
            .orDie
          eventResults = SdlcArtifactSchema.decodeMixed(lines)
        yield assertTrue(
          records.map(_.kind).toSet == Kind.values.toSet,
          SdlcArtifactSchema.decodeRunJson(runArtifact).contains(records),
          eventResults.toOption.exists(_.collect { case Right(record) =>
            record
          } == records)
        )
      }
    },
    test(
      "SDLC-WIRE-02: typed record refuses unknown envelope/payload fields and unsupported versions"
    ) {
      val unknown = encode(records.head).dropRight(1) + ",\"unexpected\":true}"
      val version = encode(records.head)
        .replace("\"schemaVersion\":2", "\"schemaVersion\":3")
      val unknownPayload = encode(records.head).replace(
        "\"checkId\":\"compile\"",
        "\"checkId\":\"compile\",\"unexpected\":true"
      )
      assertTrue(
        decode(unknown) == Left(DecodeError.UnknownField("unexpected")),
        decode(unknownPayload) ==
          Left(
            DecodeError.UnknownPayloadField(records.head.kind, "unexpected")
          ),
        decode(version) == Left(DecodeError.UnsupportedVersion(3L))
      )
    },
    test("SDLC-WIRE-02: malformed typed interior returns a typed refusal") {
      val malformed = encode(records.head)
        .replace("\"checkId\":\"compile\"", "\"checkId\":17")
      assertTrue(
        decode(malformed) ==
          Left(
            DecodeError.Malformed(
              ".checkId(expected string)"
            )
          )
      )
    }
  )

end SdlcArtifactSchemaSpec
