package raider.evalexport

import zio.Scope
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

/** Golden + structural tests of the export transform (RAI-040 acceptance
  * EXPORT-01).
  *
  * The golden tests pin the EXACT bytes of every exported document against
  * `contracts/eval-export/expected/<name>/` — a shape change, a field-order
  * change, or a whitespace change is a test failure, never a silent update. The
  * schema test is honest about its limits: it checks the six pinned schema
  * files are well-formed JSON objects with the right `$schema`, `$id`, and
  * `type` headers; it does NOT run a JSON Schema validator (none exists in this
  * environment — documented in the contract README).
  */
object ExportSpec extends ZIOSpecDefault:

  override def spec: Spec[Scope, Any] = suite("export")(
    test("normal run exports byte-identical golden documents") {
      val bundle = Fixtures.exported(Fixtures.Normal.input)
      assertTrue(Fixtures.goldenMismatch(bundle, "normal-run").isEmpty)
    },
    test("normal run round-trips through the validating wire codecs") {
      val bundle = Fixtures.exported(Fixtures.Normal.input)
      val manifest =
        bundle.parseJson[wire.ManifestWire](ExportBundle.ManifestName)
      val episodes =
        bundle.parseJsonl[wire.EpisodeWire](ExportBundle.EpisodesName)
      val calls = bundle.parseJsonl[wire.CallWire](ExportBundle.CallsName)
      val tools = bundle.parseJsonl[wire.ToolWire](ExportBundle.ToolsName)
      val verdicts =
        bundle.parseJsonl[wire.VerdictWire](ExportBundle.VerdictsName)
      assertTrue(
        manifest.isRight,
        episodes.map(_.size) == Right(2),
        calls.map(_.size) == Right(3),
        tools.map(_.size) == Right(4),
        verdicts.map(_.size) == Right(2)
      )
    },
    test("normal run manifest carries the pinned shape and coverage") {
      val bundle = Fixtures.exported(Fixtures.Normal.input)
      val manifest = Fixtures.manifestOf(bundle)
      assertTrue(
        manifest.schemaVersion == 1,
        manifest.runId == "run-20261106-normal",
        manifest.generator == wire
          .GeneratorWire("raider", "0.4.0-dev", "3.9.0", "21.0.5"),
        manifest.model == "mock-coder",
        manifest.runStatus == "complete",
        manifest.finishedAtMs == Some(8000L),
        manifest.lineage.rootId == "run-20261106-normal",
        manifest.lineage.jobs.size == 2,
        manifest.lineage.jobs(1) ==
          wire.JobLineageWire(
            "job-child",
            Some("job-root"),
            "subtask: refactor module",
            Vector("att-2")
          ),
        manifest.coverage == wire.CoverageWire(
          episodes = 2,
          modelCalls = 3,
          toolCalls = 4,
          verdicts = 2,
          verifier = wire.VerifierCoverageWire(
            expected = 2,
            produced = 2,
            pending = 0,
            absent = 0
          ),
          usage = wire.UsageCoverageWire(
            calls = 3,
            withObserved = 3,
            unknownPrice = 0
          )
        )
      )
    },
    test("normal run integrity digests pin the actual section bytes") {
      val bundle = Fixtures.exported(Fixtures.Normal.input)
      val manifest = Fixtures.manifestOf(bundle)
      assertTrue(
        manifest.integrity.episodesSha256 == bundle
          .file(ExportBundle.EpisodesName)
          .map(_.sha256)
          .getOrElse(""),
        manifest.integrity.callsSha256 == bundle
          .file(ExportBundle.CallsName)
          .map(_.sha256)
          .getOrElse(""),
        manifest.integrity.toolsSha256 == bundle
          .file(ExportBundle.ToolsName)
          .map(_.sha256)
          .getOrElse(""),
        manifest.integrity.verdictsSha256 == bundle
          .file(ExportBundle.VerdictsName)
          .map(_.sha256)
          .getOrElse(""),
        bundle.verifyIntegrity() == Right(())
      )
    },
    test("lineage reconstructs from the exported documents alone") {
      val bundle = Fixtures.exported(Fixtures.Normal.input)
      assertTrue(
        Lineage.reconstruct(bundle) == Right(
          Lineage.ReconstructedLineage(
            rootId = "run-20261106-normal",
            jobs = Vector(
              Lineage.ReconstructedJob(
                "job-root",
                None,
                "root agent loop",
                Vector("att-1")
              ),
              Lineage.ReconstructedJob(
                "job-child",
                Some("job-root"),
                "subtask: refactor module",
                Vector("att-2")
              )
            ),
            episodes = 2,
            modelCalls = 3,
            toolCalls = 4,
            verdicts = 2,
            reconciliations = 0
          )
        )
      )
    },
    test("episodes export all four outcomes in canonical order") {
      val bundle = Fixtures.exported(Fixtures.Normal.input)
      val ep1 = Fixtures
        .episodesOf(bundle)
        .find(_.episodeId == "ep-1")
        .getOrElse(sys.error("ep-1 absent"))
      assertTrue(
        ep1.jobId == "job-root",
        ep1.completion == wire
          .CompletionWire("completed", Some("succeeded"), None, None, 3000L),
        ep1.verifier == wire.VerifierWire("produced", Some("verify.sh"), None),
        ep1.outcomes
          .map(_.kind) == Vector("execution", "task", "safety", "protocol"),
        ep1.outcomes(1) ==
          wire.OutcomeWire(
            "task",
            "pass",
            None,
            Some(Fixtures.sha("verify.sh stdout for ep-1: 12 checks ok"))
          ),
        ep1.outcomes(2) == wire.OutcomeWire(
          "safety",
          "pass",
          None,
          Some(Fixtures.sha("ep-1 safety scan report: no violations"))
        ),
        ep1.outcomes(3) == wire.OutcomeWire(
          "protocol",
          "missing",
          Some("noEvidenceRecorded"),
          None
        )
      )
    },
    test("unknown-price run exports byte-identical golden documents") {
      val bundle = Fixtures.exported(Fixtures.UnknownPrice.input)
      assertTrue(Fixtures.goldenMismatch(bundle, "unknown-price").isEmpty)
    },
    test("unknown usage prices are explicit nulls, never zeros") {
      val bundle = Fixtures.exported(Fixtures.UnknownPrice.input)
      val content =
        bundle.file(ExportBundle.CallsName).map(_.content).getOrElse("")
      val calls = Fixtures.callsOf(bundle)
      assertTrue(
        content.contains(
          """{"reservedMicroUsd":null,"observedMicroUsd":null,"unknown":true}"""
        ),
        content.contains(
          """{"reservedMicroUsd":null,"observedMicroUsd":1200,"unknown":false}"""
        ),
        calls(0).usage == wire.UsageWire(None, None, true),
        calls(1).usage == wire.UsageWire(None, Some(1200L), false),
        Fixtures.manifestOf(bundle).coverage.usage ==
          wire.UsageCoverageWire(calls = 2, withObserved = 1, unknownPrice = 1)
      )
    },
    test("all six pinned schema files are well-formed JSON 2020-12 objects") {
      // Structural sanity only: the schemas' $schema/$id/type headers. A real
      // schema validation run is out of scope for this environment (see the
      // contract README, limitations).
      val checks = ExportBundle.AllNames.map { name =>
        val path = Fixtures.schemasDir.resolve(s"$name.schema.json")
        if !java.nio.file.Files.exists(path) then
          Left(s"missing schema file: $path")
        else
          val text = new String(
            java.nio.file.Files.readAllBytes(path),
            java.nio.charset.StandardCharsets.UTF_8
          )
          text.fromJson[zio.json.ast.Json] match
            case Left(msg) => Left(s"$path is not valid JSON: $msg")
            case Right(doc: zio.json.ast.Json.Obj) =>
              val fields = doc.fields.toMap
              val ok =
                fields.get("$schema").exists {
                  case zio.json.ast.Json.Str(v) =>
                    v.contains("json-schema.org/draft/2020-12/schema")
                  case _ => false
                } &&
                  fields.get("$id").exists {
                    case zio.json.ast.Json.Str(v) =>
                      v.endsWith(s"/schemas/$name")
                    case _ => false
                  } &&
                  fields.get("type").contains(zio.json.ast.Json.Str("object"))
              if ok then Right(())
              else
                Left(
                  path.toString + " lacks the pinned $schema/$id/type headers"
                )
            case Right(_) => Left(s"$path is not a JSON object")
      }
      assertTrue(checks.collect { case Left(m) => m }.isEmpty)
    },
    test("the same input always exports byte-identical documents") {
      val a = Fixtures.exported(Fixtures.Normal.input)
      val b = Fixtures.exported(Fixtures.Normal.input)
      assertTrue(a.identical(b))
    }
  )
