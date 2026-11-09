package raider.evalexport

import zio.Scope
import zio.test.*
import zio.test.Assertion.*

/** Reconciliation tests (RAI-040 acceptance EXPORT-03).
  *
  * Pins the cancelled-run golden (partial run shape), the append discipline
  * (all other documents byte-identical, versioned per-call chain,
  * self-anchoring file chain), and the late-usage golden after two appends.
  */
object ReconcileSpec extends ZIOSpecDefault:

  override def spec: Spec[Scope, Any] = suite("reconcile")(
    test("cancelled run exports byte-identical golden documents") {
      val bundle = Fixtures.exported(Fixtures.Cancelled.input)
      assertTrue(Fixtures.goldenMismatch(bundle, "cancelled-run").isEmpty)
    },
    test(
      "cancelled run keeps cancellation partial and verifier coverage honest"
    ) {
      val bundle = Fixtures.exported(Fixtures.Cancelled.input)
      val manifest = Fixtures.manifestOf(bundle)
      val episodes = Fixtures.episodesOf(bundle)
      val ep2 =
        episodes.find(_.episodeId == "ep-2").getOrElse(sys.error("ep-2 absent"))
      val ep3 =
        episodes.find(_.episodeId == "ep-3").getOrElse(sys.error("ep-3 absent"))
      assertTrue(
        manifest.runStatus == "partial",
        manifest.finishedAtMs == None,
        manifest.coverage.verifier == wire.VerifierCoverageWire(
          expected = 2,
          produced = 1,
          pending = 1,
          absent = 1
        ),
        ep2.completion ==
          wire.CompletionWire(
            "partial",
            None,
            Some("cancelled"),
            Some("user requested cancellation"),
            6000L
          ),
        ep2.outcomes
          .find(_.kind == "execution")
          .contains(
            wire.OutcomeWire(
              "execution",
              "cancelled",
              Some("user requested cancellation"),
              None
            )
          ),
        ep3.completion ==
          wire.CompletionWire(
            "partial",
            None,
            Some("interrupted"),
            Some("runner SIGTERM before terminal evidence"),
            6500L
          ),
        ep3.verifier == wire.VerifierWire(
          "absent",
          None,
          Some("noVerifierDeclared")
        )
      )
    },
    test("an append leaves every other document byte-identical") {
      val base = Fixtures.exported(Fixtures.Cancelled.input)
      val appended = Reconciliation.append(base, Fixtures.Late1) match
        case Right(b) => b
        case Left(e)  => sys.error("append failed: " + e.message)
      val unchanged = ExportBundle.AllNames
        .filter(_ != ExportBundle.ReconciliationName)
        .forall(n => base.file(n) == appended.file(n))
      val recContent = appended
        .file(ExportBundle.ReconciliationName)
        .map(_.content)
        .getOrElse("")
      assertTrue(unchanged, recContent.linesIterator.size == 1)
    },
    test("two appends on one call build a versioned correction chain") {
      val bundle = Fixtures.lateUsageBundle
      val recs = Fixtures.reconciliationOf(bundle)
      val call = Fixtures
        .callsOf(bundle)
        .find(_.callId == "call-1")
        .getOrElse(sys.error("call-1 absent"))
      val v1 = recs(0)
      val v2 = recs(1)
      val canonicalCallUsage = wire.UsageWire.canonical(call.usage)
      assertTrue(
        recs.size == 2,
        v1.reconciliationId == "rc-1",
        v1.reconciliationVersion == 1,
        v1.callId == "call-1",
        v1.attemptId == "att-1",
        v1.jobId == "job-root",
        v1.runId == "run-20261106-cancelled",
        v1.previousUsage == wire.UsageWire(Some(10L), Some(500L), false),
        v1.previousUsageSha256 == Fixtures.sha(canonicalCallUsage),
        v1.correctedUsage == wire.UsageWire(Some(10L), Some(1800L), false),
        v1.prevFileSha256 == Fixtures.sha(""),
        v1.source == "provider usage report",
        v2.reconciliationId == "rc-2",
        v2.reconciliationVersion == 2,
        v2.previousUsage == v1.correctedUsage,
        v2.previousUsageSha256 == Fixtures.sha(
          wire.UsageWire.canonical(v1.correctedUsage)
        ),
        v2.correctedUsage == wire.UsageWire(Some(10L), Some(1900L), false),
        v2.source == "provider usage correction"
      )
    },
    test("verifyChain accepts a well-formed append history") {
      assertTrue(
        Reconciliation.verifyChain(Fixtures.lateUsageBundle) == Right(())
      )
    },
    test("a tampered reconciliation line breaks the file chain") {
      val bundle = Fixtures.lateUsageBundle
      val content = bundle
        .file(ExportBundle.ReconciliationName)
        .map(_.content)
        .getOrElse("")
      val tampered = bundle.withFile(
        ExportBundle.ReconciliationName,
        content.replace("9000", "9001")
      )
      assertTrue(
        tampered.file(ExportBundle.ReconciliationName).map(_.content) != bundle
          .file(ExportBundle.ReconciliationName)
          .map(_.content)
      )
      Reconciliation.verifyChain(tampered) match
        case Left(model.ExportError.ChainViolation(reason)) =>
          assertTrue(reason.contains("prevFileSha256"))
        case Right(_) =>
          sys.error("tampered chain verified — chain check broken")
        case Left(e) => sys.error(s"expected ChainViolation, got: ${e.message}")
    },
    test("an unknown call id is a typed MissingCall") {
      val base = Fixtures.exported(Fixtures.Cancelled.input)
      Reconciliation.append(
        base,
        model.LateUsage(
          "call-404",
          model.Usage(None, Some(1L), false),
          "provider usage report",
          9000L
        )
      ) match
        case Left(model.ExportError.MissingCall(callId)) =>
          assertTrue(callId == "call-404")
        case other => sys.error(s"expected MissingCall, got: $other")
    },
    test(
      "a late usage that hides an unknown price behind unknown=false is a typed BadUsage"
    ) {
      val base = Fixtures.exported(Fixtures.Cancelled.input)
      Reconciliation.append(
        base,
        model.LateUsage(
          "call-1",
          model.Usage(None, None, false),
          "provider usage report",
          9000L
        )
      ) match
        case Left(model.ExportError.BadUsage(reason)) =>
          assertTrue(
            reason.contains("call 'call-1'") && reason.contains("unknown=false")
          )
        case other => sys.error(s"expected BadUsage, got: $other")
    },
    test("an empty late-usage source is rejected") {
      val base = Fixtures.exported(Fixtures.Cancelled.input)
      Reconciliation.append(
        base,
        model.LateUsage("call-1", model.Usage(None, Some(1L), false), "", 9000L)
      ) match
        case Left(model.ExportError.EmptyField(field, _)) =>
          assertTrue(field == "source")
        case other => sys.error(s"expected EmptyField, got: $other")
    },
    test("late-usage bundle exports byte-identical golden documents") {
      assertTrue(
        Fixtures.goldenMismatch(Fixtures.lateUsageBundle, "late-usage").isEmpty
      )
    },
    test("lineage counts reconciliation records of the appended bundle") {
      Lineage.reconstruct(Fixtures.lateUsageBundle) match
        case Right(lineage) => assertTrue(lineage.reconciliations == 2)
        case Left(e)        => sys.error("reconstruct failed: " + e.message)
    }
  )
