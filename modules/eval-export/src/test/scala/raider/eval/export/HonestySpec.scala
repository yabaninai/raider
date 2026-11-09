package raider.evalexport

import zio.Scope
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

import raider.evalexport.model.*

/** Honesty tests of the export surface (RAI-040 acceptance EXPORT-02).
  *
  * These tests fail if the exporter could ever produce a dishonest document: a
  * fabricated verdict, a smuggled raw argument, an environment value, a
  * zero-conflated unknown price, or an invisible attempt.
  */
object HonestySpec extends ZIOSpecDefault:

  override def spec: Spec[Scope, Any] = suite("honesty")(
    // -- verifier honesty -------------------------------------------------------

    test(
      "a declared verifier that produced nothing exports pending, never a verdict"
    ) {
      val input = Fixtures.Normal.input.copy(
        verdicts =
          Fixtures.Normal.input.verdicts.filterNot(_.episodeId == "ep-2")
      )
      val bundle = Fixtures.exported(input)
      val ep2 = Fixtures
        .episodesOf(bundle)
        .find(_.episodeId == "ep-2")
        .getOrElse(sys.error("ep-2 absent"))
      val manifest = Fixtures.manifestOf(bundle)
      // Real verdicts for produced episodes are exported (evidence is never
      // dropped); the pending episode must not gain one.
      val verdictEpisodes = Fixtures.verdictsOf(bundle).map(_.episodeId)
      assertTrue(
        ep2.verifier == wire.VerifierWire(
          "pending",
          Some("verify.sh"),
          Some("verifierExpectedButMissing")
        ),
        ep2.outcomes
          .find(_.kind == "task")
          .contains(
            wire.OutcomeWire(
              "task",
              "missing",
              Some("verifierExpectedButMissing"),
              None
            )
          ),
        manifest.coverage.verifier == wire.VerifierCoverageWire(
          expected = 2,
          produced = 1,
          pending = 1,
          absent = 0
        ),
        verdictEpisodes == Vector("ep-1")
      )
    },
    test("an undeclared verifier exports absent with an explicit reason") {
      val input = Fixtures.Normal.input.copy(
        episodes = Fixtures.Normal.input.episodes.map { ep =>
          if ep.episodeId == "ep-1" then
            ep.copy(verifierExpected = false, verifierId = None)
          else ep
        },
        verdicts =
          Fixtures.Normal.input.verdicts.filterNot(_.episodeId == "ep-1")
      )
      val bundle = Fixtures.exported(input)
      val ep1 = Fixtures
        .episodesOf(bundle)
        .find(_.episodeId == "ep-1")
        .getOrElse(sys.error("ep-1 absent"))
      val manifest = Fixtures.manifestOf(bundle)
      assertTrue(
        ep1.verifier == wire
          .VerifierWire("absent", None, Some("noVerifierDeclared")),
        ep1.outcomes
          .find(_.kind == "task")
          .contains(
            wire
              .OutcomeWire("task", "missing", Some("noVerifierDeclared"), None)
          ),
        manifest.coverage.verifier == wire.VerifierCoverageWire(
          expected = 1,
          produced = 1,
          pending = 0,
          absent = 1
        )
      )
    },
    test(
      "a verdict on an episode that declared no verifier is a typed input error"
    ) {
      val input = Fixtures.Normal.input.copy(
        episodes = Fixtures.Normal.input.episodes.map { ep =>
          if ep.episodeId == "ep-1" then
            ep.copy(verifierExpected = false, verifierId = None)
          else ep
        }
        // the ep-1 verdict is intentionally left in place
      )
      Fixtures.exportOf(input) match
        case Left(model.ExportError.VerifierMismatch("ep-1", reason)) =>
          assertTrue(reason.contains("did not expect"))
        case other =>
          sys.error(s"expected VerifierMismatch for ep-1, got: $other")
    },
    test("a verdict referencing an unknown episode is a typed input error") {
      val input = Fixtures.Normal.input.copy(
        verdicts =
          Fixtures.Normal.input.verdicts :+ Fixtures.Normal.input.verdicts.head
            .copy(episodeId = "ep-404")
      )
      Fixtures.exportOf(input) match
        case Left(
              model.ExportError.DanglingReference(from, id, missing, kind)
            ) =>
          assertTrue(id == "ep-404", missing == "ep-404", kind == "episode")
        case other =>
          sys.error(s"expected DanglingReference, got: $other")
    },

    // -- argument / environment honesty -----------------------------------------

    test("raw tool args are digested, never embedded") {
      val bundle = Fixtures.exported(Fixtures.Normal.input)
      val tools = Fixtures.toolsOf(bundle)
      val tc1 =
        tools.find(_.toolCallId == "tc-1").getOrElse(sys.error("tc-1 absent"))
      val raw = """{"path":"src/main/scala/App.scala"}"""
      val allContent = ExportBundle.AllNames
        .map(n => bundle.file(n).map(_.content).getOrElse(""))
        .mkString
      assertTrue(
        tc1.argsDigest.contains(Fixtures.sha(raw)),
        !allContent.contains(raw),
        !allContent.contains("src/main/scala/App.scala")
      )
    },
    test("failed and denied tool calls also digest their raw args") {
      val bundle = Fixtures.exported(Fixtures.Normal.input)
      val tools = Fixtures.toolsOf(bundle)
      val tc2 =
        tools.find(_.toolCallId == "tc-2").getOrElse(sys.error("tc-2 absent"))
      val tc3 =
        tools.find(_.toolCallId == "tc-3").getOrElse(sys.error("tc-3 absent"))
      assertTrue(
        tc2.argsDigest.contains(Fixtures.sha("""{"path":"missing.scala"}""")),
        tc3.argsDigest.contains(Fixtures.sha("""{"cmd":["make","check"]}"""))
      )
    },
    test(
      "explicitly pinned redacted-safe args travel verbatim, digest over the pinned text"
    ) {
      val bundle = Fixtures.exported(Fixtures.Normal.input)
      val tools = Fixtures.toolsOf(bundle)
      val tc4 =
        tools.find(_.toolCallId == "tc-4").getOrElse(sys.error("tc-4 absent"))
      val pinned = """{"path":"examples/demo.scala","mode":"read"}"""
      val toolsContent =
        bundle.file(ExportBundle.ToolsName).map(_.content).getOrElse("")
      assertTrue(
        tc4.argsDigest.contains(Fixtures.sha(pinned)),
        toolsContent.contains(pinned),
        tc4.artifacts == Vector(
          wire.ArtifactWire(
            "artifact-ws-1",
            Fixtures.sha(
              "workspace artifact: examples/demo.scala (fixture bytes)"
            ),
            "text/x-scala",
            128L
          )
        )
      )
    },
    test("an env reference with a value is rejected at the door") {
      val input = Fixtures.Normal.input.copy(
        tools = Fixtures.Normal.input.tools.map { t =>
          if t.toolCallId == "tc-3" then
            t.copy(envRefs = Vector("CI_JOB_TOKEN=abc123"))
          else t
        }
      )
      Fixtures.exportOf(input) match
        case Left(model.ExportError.BadEnvRef(v)) =>
          assertTrue(v == "CI_JOB_TOKEN=abc123")
        case other => sys.error(s"expected BadEnvRef, got: $other")
    },
    test("env references export as NAME-only lists") {
      val bundle = Fixtures.exported(Fixtures.Normal.input)
      val tc3 = Fixtures
        .toolsOf(bundle)
        .find(_.toolCallId == "tc-3")
        .getOrElse(sys.error("tc-3 absent"))
      val allContent = ExportBundle.AllNames
        .map(n => bundle.file(n).map(_.content).getOrElse(""))
        .mkString
      assertTrue(
        tc3.envRefs == Vector("CI_JOB_TOKEN"),
        !allContent.contains("CI_JOB_TOKEN=")
      )
    },
    test("multibyte args digest over UTF-8 bytes and never travel") {
      val raw = """{"path":"docs/例ノート.md"}"""
      val input = Fixtures.UnknownPrice.input.copy(
        tools = Vector(
          ToolCallInput(
            toolCallId = "tc-mb",
            attemptId = "att-1",
            tool = "fs_read",
            callId = None,
            atMs = 1500L,
            result = ToolResult.Succeeded,
            argsJsonText = Some(raw),
            argsRedactedSafe = None,
            envRefs = Vector.empty,
            artifacts = Vector.empty
          )
        )
      )
      val bundle = Fixtures.exported(input)
      val tc = Fixtures
        .toolsOf(bundle)
        .find(_.toolCallId == "tc-mb")
        .getOrElse(sys.error("tc-mb absent"))
      val allContent = ExportBundle.AllNames
        .map(n => bundle.file(n).map(_.content).getOrElse(""))
        .mkString
      assertTrue(
        tc.argsDigest.contains(Fixtures.sha(raw)),
        !allContent.contains("docs/"),
        !allContent.contains("例ノート.md")
      )
    },

    // -- usage honesty -----------------------------------------------------------

    test("unknown=false with a null observed price is a typed input error") {
      val input = Fixtures.UnknownPrice.input.copy(
        calls = Fixtures.UnknownPrice.input.calls.map { c =>
          if c.callId == "call-1" then c.copy(usage = Usage(None, None, false))
          else c
        }
      )
      Fixtures.exportOf(input) match
        case Left(model.ExportError.BadUsage(reason)) =>
          assertTrue(
            reason.contains("call 'call-1'") && reason.contains("unknown=false")
          )
        case other => sys.error(s"expected BadUsage, got: $other")
    },
    test(
      "unknown=true with a number is rejected by the validating wire codec"
    ) {
      val tampered =
        """{"schemaVersion":1,"runId":"run-20261106-unknown-price","callId":"call-1","attemptId":"att-1",""" +
          """"jobId":"job-root","round":1,"model":"mock-coder","atMs":700,""" +
          """"usage":{"reservedMicroUsd":null,"observedMicroUsd":42,"unknown":true}}"""
      val result = tampered.fromJson[wire.CallWire]
      result match
        case Left(msg) =>
          assertTrue(
            msg.contains("unknown=true requires a null observedMicroUsd")
          )
        case Right(_) =>
          sys.error("tampered usage decoded successfully — honesty rule broken")
    },

    // -- completeness honesty ------------------------------------------------------

    test("every attempt must carry exactly one episode record") {
      val withoutEp = Fixtures.Normal.input.copy(
        episodes =
          Fixtures.Normal.input.episodes.filterNot(_.episodeId == "ep-2")
      )
      Fixtures.exportOf(withoutEp) match
        case Left(model.ExportError.AttemptWithoutEpisode(attemptId)) =>
          assertTrue(attemptId == "att-2")
        case other => sys.error(s"expected AttemptWithoutEpisode, got: $other")
    },
    test(
      "an episode attempt bound to two attempts is a duplicate, and a lineage attempt mismatch is typed"
    ) {
      // tamper: point ep-1 at att-2 (which belongs to job-child)
      val bundle = Fixtures.exported(Fixtures.Normal.input)
      val episodesContent =
        bundle.file(ExportBundle.EpisodesName).map(_.content).getOrElse("")
      val lines = ExportBundle.splitJsonl(episodesContent)
      val tamperedEp1 =
        lines(0).replace("\"attemptId\":\"att-1\"", "\"attemptId\":\"att-2\"")
      val tampered = bundle.withFile(
        ExportBundle.EpisodesName,
        (tamperedEp1 +: lines.drop(1)).map(_ + "\n").mkString
      )
      Lineage.reconstruct(tampered) match
        case Left(
              model.ExportError
                .AttemptJobMismatch(attemptId, expectedJobId, actualJobId)
            ) =>
          assertTrue(
            attemptId == "att-2",
            expectedJobId == "job-root",
            actualJobId == "job-child"
          )
        case Right(_) =>
          sys.error("tampered episode decoded — lineage cross-check broken")
        case Left(other) =>
          sys.error(s"expected AttemptJobMismatch, got: ${other.message}")
    }
  )
