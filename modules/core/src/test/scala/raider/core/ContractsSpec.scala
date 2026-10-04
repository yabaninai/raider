package raider.core

import raider.core.codecs.given
import raider.core.schemas.{RunContext, VersionedEnvelope}

import zio.json._

/** RAI-002 contract spec: canonical roundtrips + typed negative cases.
  * Plain main (no framework yet — RAI-006 brings ZIO Test); exit 0 iff all pass. */
object ContractsSpec:

  private var failures = 0
  private def check(name: String)(ok: Boolean, detail: => String = ""): Unit =
    if ok then println(s"[pass] $name")
    else { failures += 1; println(s"[FAIL] $name ${detail}") }

  private def roundtrip[A: JsonCodec](name: String, value: A): Unit =
    val json = value.toJson
    json.fromJson[A] match
      case Right(decoded) => check(s"$name-roundtrip")(decoded == value, s"$json -> $decoded")
      case Left(err)      => check(s"$name-roundtrip")(false, s"decode failed: $err")

  def main(args: Array[String]): Unit =
    // ---- IDs
    roundtrip("id-job", JobId("j_018"))
    roundtrip("id-root", RootId("r_9"))

    // ---- Usage, incl. mixed-currency honesty
    roundtrip("usage", Usage(inputTokens = 17, outputTokens = 9, currency = Some("usd-micro")))
    val mixed = Usage(1, 1, Some("a")).plus(Usage(2, 2, Some("b")))
    check("usage-mixed-currency-unknown")(mixed.currency.isEmpty)
    val same = Usage(1, 1, Some("usd")).plus(Usage(2, 2, Some("usd")))
    check("usage-same-currency-kept")(same.currency.contains("usd") && same.inputTokens == 3)

    // ---- ModelEvent discriminator roundtrips (§9 set)
    val a = AttemptId("att-1")
    roundtrip[ModelEvent]("event-started", ModelEvent.Started(a))
    roundtrip[ModelEvent]("event-text", ModelEvent.TextDelta(a, "hello ✓"))
    roundtrip[ModelEvent]("event-tooldelta", ModelEvent.ToolCallDelta(a, ToolCallId("c1"), 0, "{\"pa"))
    roundtrip[ModelEvent]("event-toolready", ModelEvent.ToolCallReady(a, ToolCallId("c1"), "fs.read", "{\"path\":\"README\"}"))
    roundtrip[ModelEvent]("event-usage", ModelEvent.UsageObserved(a, Usage(5, 5, None), priceKnown = false))
    roundtrip[ModelEvent]("event-metadata", ModelEvent.MetadataObserved(a, "gateway-model", "glm-4.7"))
    roundtrip[ModelEvent]("event-finished", ModelEvent.Finished(a, "stop"))
    roundtrip[ModelEvent]("event-failed", ModelEvent.Failed(a, RaiderError.ProviderRateLimit("429")))
    val readyEvent: ModelEvent = ModelEvent.ToolCallReady(a, ToolCallId("c9"), "exec.run", "{}")
    val encoded = readyEvent.toJson
    check("event-discriminator-shape")(encoded.contains(""""type":"ToolCallReady""""), encoded)

    // ---- errors: flat wire, unknown code rejected
    val authErr: RaiderError = RaiderError.ProviderAuth("401 from stand")
    val journalErr: RaiderError = RaiderError.JournalFailure("disk full")
    roundtrip("error-auth", authErr)
    roundtrip("error-journal", journalErr)
    check("error-unknown-code-rejected")(
      """{"code":"RA-NOPE","detail":"x","retryable":false}""".fromJson[RaiderError].isLeft)
    check("error-malformed-rejected")("not json".fromJson[RaiderError].isLeft)

    // ---- versioned envelope: v1 ok, unknown/malformed rejected before payload
    val okEncode = VersionedEnvelope.encodeValidated(1, "{}")
    check("envelope-v1-encoded")(okEncode.isRight)
    check("envelope-unknown-version-encode")(
      VersionedEnvelope.encodeValidated(2, "{}").swap.exists(_.detail.contains("unsupported")))
    check("envelope-unknown-version-decode")(
      VersionedEnvelope.decodeValidated("""{"schemaVersion":7,"payload":"{}"}""")
        .swap.exists(_.code == "RA-INP"))
    check("envelope-malformed-decode")(
      VersionedEnvelope.decodeValidated("}{").swap.exists(_.code == "RA-INP"))

    // ---- RunContext validation
    val ctx = RunContext(1, "generic", Some("inst-1"), Some("repo-1"), None,
      Some("job-7"), None, attempt = 1, declaredTrustLevel = "untrusted")
    roundtrip("context", ctx)
    check("context-bad-version")(
      RunContext.validate(ctx.copy(schemaVersion = 3)).swap.exists(_.code == "RA-INP"))
    check("context-empty-system")(
      RunContext.validate(ctx.copy(system = " ")).swap.exists(_.detail.contains("system")))
    check("context-zero-attempt")(
      RunContext.validate(ctx.copy(attempt = 0)).swap.exists(_.detail.contains("attempt")))

    // ---- RAI-002 remainder: bundle/result/launch schemas (canonical wire)
    import raider.core.bundle.*
    import raider.core.result.*
    val bm = BundleManifest(1, "pipelines",
      List(BundleEntry("repair", "demo.P$repair$", 1),
           BundleEntry("inspect", "demo.P$inspect$", 2)), "trusted-local")
    roundtrip("bundle-manifest", bm)
    check("bundle-entry-unknown-rejected")(
      BundleManifest.entry(bm, "nope").swap.exists(_.detail.contains("known")))
    check("bundle-dup-rejected")(
      BundleManifest.validate(bm.copy(entries =
        List(BundleEntry("a", "A", 1), BundleEntry("a", "A", 1)))).swap.exists(_.code == "RA-INP"))

    import raider.core.codecs.given
    val rr = RunResult(1, RootId("r-42"), ExecutionStatus.Uncertain,
      ExitCodes.Uncertain, None,
      List(CheckResult("schema", CheckOutcome.Skipped, "component stage")),
      List(ArtifactEntry(ArtifactId("a-9"), "b" * 64, "application/json", 7, "out/a.json")),
      None, Some(RaiderError.ToolOutcomeUncertain("cancel mid-dispatch")),
      "t0", "t1")
    roundtrip("run-result", rr)
    check("result-validation-ok")(RunResult.validate(rr).isRight)
    check("result-unknown-exit-rejected")(
      RunResult.validate(rr.copy(exitCode = 5)).swap.exists(_.detail.contains("unknown exitCode")))
    check("exit-codes-stable-set")(
      ExitCodes.all == Set(0, 10, 20, 21, 22, 23, 24, 25, 26, 27, 130, 143))

    println(s"contracts: ${if failures == 0 then "ALL PASS" else s"$failures FAILURES"}")
    if failures > 0 then sys.exit(1) else sys.exit(0)
