package raider.evalexport

import zio.json.*
import zio.json.ast.Json

/** Wire model of the agent-eval export documents (RAI-040) — the exact JSON
  * shape pinned by the six schema files under contracts/eval-export/schemas.
  *
  * Rules (all enforced by these codecs, not just by prose):
  *   - `schemaVersion` is 1 on every record; other versions are decode
    *     failures (a reader never guesses at an unknown revision).
  *   - Option fields are written as EXPLICIT null (custom nullable encoders
    *   below; zio-json derivation would otherwise omit the fields). This is
    *   the honesty rule "unknown price is null, never 0": the AST of every
    *   exported document shows the difference between "no evidence" and
    *   "value zero".
  *   - ids are plain strings; enum-valued strings are closed sets; sha256
  *   -   fields are lowercase 64-hex; env references are NAME-shaped.
  *   - decoders validate the same invariants as the exporter (round-trip
  *   -   check): a tampered or malformed document fails to decode, it does
  *       not silently survive.
  *
  * Known limitation (documented in contracts/eval-export/README.md): zio-json
  * decoders ignore unknown fields; additionalProperties=false is enforced by
  * the JSON Schemas, not by these decoders.
  */
object wire:

  // -- explicit-null encoders ------------------------------------------------

  private def nullableEncoder[A](using enc: JsonEncoder[A]): JsonEncoder[Option[A]] =
    new JsonEncoder[Option[A]]:
      def unsafeEncode(o: Option[A], indent: Option[Int], out: zio.json.internal.Write): Unit =
        o match
          case Some(a) => enc.unsafeEncode(a, indent, out)
          case None    => out.write("null")

  given nullableStringEncoder: JsonEncoder[Option[String]] = nullableEncoder
  given nullableLongEncoder: JsonEncoder[Option[Long]]     = nullableEncoder
  given nullableJsonEncoder: JsonEncoder[Option[Json]]     = nullableEncoder

  // -- shared validators ------------------------------------------------------

  private def versionOk(v: Int): Either[String, Unit] =
    if v == model.schemaVersion then Right(())
    else Left(model.ExportError.UnsupportedSchemaVersion(v, model.schemaVersion).message)

  private def nonEmpty(field: String, v: String): Either[String, Unit] =
    if v.isEmpty then Left(model.ExportError.EmptyField(field, field).message) else Right(())

  private def shaOk(field: String, v: Option[String]): Either[String, Unit] = v match
    case Some(h) => shaOk(field, h)
    case None    => Right(())

  private def shaOk(field: String, v: String): Either[String, Unit] =
    if Digests.isSha256Hex(v) then Right(())
    else Left(model.ExportError.BadSha256(field, v).message)

  private val envRefPattern = "^[A-Za-z_][A-Za-z0-9_]*$".r

  private def envRefOk(v: String): Either[String, Unit] =
    if envRefPattern.matches(v) then Right(())
    else Left(model.ExportError.BadEnvRef(v).message)

  // -- usage ------------------------------------------------------------------

  final case class UsageWire(
      reservedMicroUsd: Option[Long],
      observedMicroUsd: Option[Long],
      unknown: Boolean
  )

  object UsageWire:
    def fromUsage(u: model.Usage): UsageWire =
      UsageWire(u.reservedMicroUsd, u.observedMicroUsd, u.unknown)

    given encoder: JsonEncoder[UsageWire] = DeriveJsonEncoder.gen[UsageWire]
    given decoder: JsonDecoder[UsageWire] =
      DeriveJsonDecoder.gen[UsageWire].mapOrFail(validate)

    /** Canonical compact JSON of a usage record — the bytes hashed by
      * `previousUsageSha256` in the reconciliation chain.
      */
    def canonical(u: UsageWire): String = u.toJson

    def validate(u: UsageWire): Either[String, UsageWire] =
      if u.unknown && u.observedMicroUsd.isDefined then
        Left("unknown=true requires a null observedMicroUsd")
      else if !u.unknown && u.observedMicroUsd.isEmpty then
        Left("unknown=false requires a non-null observedMicroUsd (never fabricate)")
      else Right(u)

  // -- completion / verifier / outcome records of an episode ------------------

  final case class CompletionWire(
      kind: String, // completed | partial
      outcome: Option[String],  // succeeded | failed
      terminal: Option[String], // cancelled | interrupted | uncertain
      reason: Option[String],
      atMs: Long
  )

  object CompletionWire:
    given encoder: JsonEncoder[CompletionWire] = DeriveJsonEncoder.gen[CompletionWire]
    given decoder: JsonDecoder[CompletionWire] =
      DeriveJsonDecoder.gen[CompletionWire].mapOrFail(validate)

    def validate(c: CompletionWire): Either[String, CompletionWire] =
      c.kind match
        case "completed" =>
          (c.terminal, c.reason) match
            case (None, None) =>
              c.outcome match
                case Some("succeeded" | "failed") => Right(c)
                case other => Left(s"completed episode requires outcome succeeded|failed, got $other")
            case _ => Left("completed episode must not carry terminal/reason")
        case "partial" =>
          (c.outcome, c.terminal, c.reason) match
            case (None, Some(t), Some(r)) if t == "cancelled" || t == "interrupted" || t == "uncertain" =>
              if r.isEmpty then Left("partial episode requires a non-empty reason")
              else Right(c)
            case _ =>
              Left("partial episode requires terminal cancelled|interrupted|uncertain, a reason, and no outcome")
        case other => Left(s"completion kind must be completed|partial, got '$other'")

  final case class VerifierWire(
      state: String, // produced | pending | absent
      verifierId: Option[String],
      reason: Option[String]
  )

  object VerifierWire:
    given encoder: JsonEncoder[VerifierWire] = DeriveJsonEncoder.gen[VerifierWire]
    given decoder: JsonDecoder[VerifierWire] =
      DeriveJsonDecoder.gen[VerifierWire].mapOrFail(validate)

    def validate(v: VerifierWire): Either[String, VerifierWire] =
      v.state match
        case "produced" =>
          (v.verifierId, v.reason) match
            case (Some(id), None) if id.nonEmpty => Right(v)
            case _ => Left("produced verifier state requires verifierId and no reason")
        case "pending" =>
          (v.verifierId, v.reason) match
            case (Some(id), Some(r)) if id.nonEmpty && r == modelExport.verifierExpectedButMissing => Right(v)
            case _ => Left("pending verifier state requires verifierId and the explicit expected-but-missing reason")
        case "absent" =>
          (v.verifierId, v.reason) match
            case (None, Some(r)) if r == modelExport.noVerifierDeclared => Right(v)
            case _ => Left("absent verifier state requires no verifierId and the explicit no-verifier reason")
        case other => Left(s"verifier state must be produced|pending|absent, got '$other'")

  final case class OutcomeWire(
      kind: String, // execution | task | safety | protocol
      status: String, // pass|fail|error|cancelled|interrupted|uncertain|missing
      reason: Option[String],
      evidenceSha256: Option[String]
  )

  object OutcomeWire:
    private val statuses =
      Set("pass", "fail", "error", "cancelled", "interrupted", "uncertain", "missing")

    given encoder: JsonEncoder[OutcomeWire] = DeriveJsonEncoder.gen[OutcomeWire]
    given decoder: JsonDecoder[OutcomeWire] =
      DeriveJsonDecoder.gen[OutcomeWire].mapOrFail(validate)

    def validate(o: OutcomeWire): Either[String, OutcomeWire] =
      val kindOk = o.kind == "execution" || o.kind == "task" || o.kind == "safety" || o.kind == "protocol"
      if !kindOk then Left(s"outcome kind must be execution|task|safety|protocol, got '${o.kind}'")
      else if !statuses.contains(o.status) then Left(s"outcome status '$o.status' is not a closed-set member")
      else if o.status == "missing" then
        o match
          case OutcomeWire(_, "missing", Some(r), None) if r.nonEmpty => Right(o)
          case _ => Left("missing outcome requires a reason and no evidence")
      else
        o.evidenceSha256 match
          case Some(h) => shaOk("outcomes.evidenceSha256", h).map(_ => o)
          case None    => Right(o)

  final case class EpisodeWire(
      schemaVersion: Int,
      runId: String,
      episodeId: String,
      attemptId: String,
      jobId: String,
      completion: CompletionWire,
      verifier: VerifierWire,
      outcomes: Vector[OutcomeWire] // canonical order: execution, task, safety, protocol
  )

  object EpisodeWire:
    given encoder: JsonEncoder[EpisodeWire] = DeriveJsonEncoder.gen[EpisodeWire]
    given decoder: JsonDecoder[EpisodeWire] =
      DeriveJsonDecoder.gen[EpisodeWire].mapOrFail(validate)

    def validate(e: EpisodeWire): Either[String, EpisodeWire] =
      for
        _ <- versionOk(e.schemaVersion)
        _ <- nonEmpty("runId", e.runId)
        _ <- nonEmpty("episodeId", e.episodeId)
        _ <- nonEmpty("attemptId", e.attemptId)
        _ <- nonEmpty("jobId", e.jobId)
        _ <-
          if e.outcomes.size == 4
            && e.outcomes.map(_.kind) == Vector("execution", "task", "safety", "protocol")
          then Right(())
          else Left("episode outcomes must be exactly [execution, task, safety, protocol] in canonical order")
      yield e

  // -- calls ------------------------------------------------------------------

  final case class CallWire(
      schemaVersion: Int,
      runId: String,
      callId: String,
      attemptId: String,
      jobId: String,
      round: Int,
      model: String,
      atMs: Long,
      usage: UsageWire
  )

  object CallWire:
    given encoder: JsonEncoder[CallWire] = DeriveJsonEncoder.gen[CallWire]
    given decoder: JsonDecoder[CallWire] =
      DeriveJsonDecoder.gen[CallWire].mapOrFail(validate)

    def validate(c: CallWire): Either[String, CallWire] =
      for
        _ <- versionOk(c.schemaVersion)
        _ <- nonEmpty("runId", c.runId)
        _ <- nonEmpty("callId", c.callId)
        _ <- nonEmpty("attemptId", c.attemptId)
        _ <- nonEmpty("jobId", c.jobId)
        _ <- UsageWire.validate(c.usage)
      yield c

  // -- tools ------------------------------------------------------------------

  final case class ArtifactWire(
      artifactId: String,
      sha256: String,
      mediaType: String,
      sizeBytes: Long
  )

  object ArtifactWire:
    given encoder: JsonEncoder[ArtifactWire] = DeriveJsonEncoder.gen[ArtifactWire]
    given decoder: JsonDecoder[ArtifactWire] =
      DeriveJsonDecoder.gen[ArtifactWire].mapOrFail(validate)

    def validate(a: ArtifactWire): Either[String, ArtifactWire] =
      for
        _ <- nonEmpty("artifactId", a.artifactId)
        _ <- shaOk("artifacts.sha256", a.sha256)
        _ <- nonEmpty("mediaType", a.mediaType)
      yield a

  final case class ToolWire(
      schemaVersion: Int,
      runId: String,
      toolCallId: String,
      attemptId: String,
      jobId: String,
      tool: String,
      callId: Option[String],
      atMs: Long,
      outcome: String, // succeeded | failed | denied | cancelled | timeout | uncertain
      reason: Option[String],
      denyReason: Option[String],
      /** sha256 over the RAW arg text; the raw text itself never travels. */
      argsDigest: Option[String],
      /** Redacted-safe args the caller explicitly pinned for embedding. */
      argsRedactedSafe: Option[Json],
      envRefs: Vector[String], // NAME references only, never values
      artifacts: Vector[ArtifactWire]
  )

  object ToolWire:
    private val outcomes = Set("succeeded", "failed", "denied", "cancelled", "timeout", "uncertain")

    given encoder: JsonEncoder[ToolWire] = DeriveJsonEncoder.gen[ToolWire]
    given decoder: JsonDecoder[ToolWire] =
      DeriveJsonDecoder.gen[ToolWire].mapOrFail(validate)

    def validate(t: ToolWire): Either[String, ToolWire] =
      for
        _ <- versionOk(t.schemaVersion)
        _ <- nonEmpty("runId", t.runId)
        _ <- nonEmpty("toolCallId", t.toolCallId)
        _ <- nonEmpty("attemptId", t.attemptId)
        _ <- nonEmpty("jobId", t.jobId)
        _ <- nonEmpty("tool", t.tool)
        _ <- outcomeReasonOk(t)
        _ <- shaOk("argsDigest", t.argsDigest)
        _ <- t.artifacts.foldLeft[Either[String, Unit]](Right(()))((acc, a) => acc.flatMap(_ => ArtifactWire.validate(a).map(_ => ())))
        _ <- t.envRefs.foldLeft[Either[String, Unit]](Right(()))((acc, e) => acc.flatMap(_ => envRefOk(e)))
      yield t

    private def outcomeReasonOk(t: ToolWire): Either[String, Unit] =
      if !outcomes.contains(t.outcome) then Left(s"tool outcome '${t.outcome}' is not a closed-set member")
      else
        t.outcome match
          case "denied" =>
            t.denyReason match
              case Some(r) if r.nonEmpty && t.reason.isEmpty => Right(())
              case _ => Left("denied tool call requires denyReason and no other reason")
          case "failed" | "uncertain" =>
            t.reason match
              case Some(r) if r.nonEmpty && t.denyReason.isEmpty => Right(())
              case _ => Left(s"${t.outcome} tool call requires a non-empty reason")
          case _ =>
            if t.reason.isEmpty && t.denyReason.isEmpty then Right(())
            else Left("succeeded|cancelled|timeout tool call must not carry reason/denyReason")

  // -- verdicts ---------------------------------------------------------------

  final case class VerdictWire(
      schemaVersion: Int,
      runId: String,
      verdictId: String,
      episodeId: String,
      attemptId: String,
      jobId: String,
      verifierId: String,
      verdict: String, // pass | fail | error
      atMs: Long,
      evidenceSha256: String,
      evidenceKind: String,
      note: Option[String]
  )

  object VerdictWire:
    given encoder: JsonEncoder[VerdictWire] = DeriveJsonEncoder.gen[VerdictWire]
    given decoder: JsonDecoder[VerdictWire] =
      DeriveJsonDecoder.gen[VerdictWire].mapOrFail(validate)

    def validate(v: VerdictWire): Either[String, VerdictWire] =
      for
        _ <- versionOk(v.schemaVersion)
        _ <- nonEmpty("runId", v.runId)
        _ <- nonEmpty("verdictId", v.verdictId)
        _ <- nonEmpty("episodeId", v.episodeId)
        _ <- nonEmpty("attemptId", v.attemptId)
        _ <- nonEmpty("jobId", v.jobId)
        _ <- nonEmpty("verifierId", v.verifierId)
        _ <-
          if v.verdict == "pass" || v.verdict == "fail" || v.verdict == "error" then Right(())
          else Left(s"verdict '${v.verdict}' is not a closed-set member")
        _ <- shaOk("evidenceSha256", v.evidenceSha256)
        _ <- nonEmpty("evidenceKind", v.evidenceKind)
      yield v

  // -- reconciliation ----------------------------------------------------------

  final case class ReconciliationWire(
      schemaVersion: Int,
      reconciliationId: String, // rc-<lineNumber>
      runId: String,
      callId: String,
      attemptId: String,
      jobId: String,
      reconciliationVersion: Int, // per-call 1..n, strictly increasing
      previousUsage: UsageWire,
      previousUsageSha256: String,
      correctedUsage: UsageWire,
      source: String,
      atMs: Long,
      /** sha256 of the reconciliation file content BEFORE this append (chain
        * anchor; empty-file hash for the first record).
        */
      prevFileSha256: String
  )

  object ReconciliationWire:
    given encoder: JsonEncoder[ReconciliationWire] = DeriveJsonEncoder.gen[ReconciliationWire]
    given decoder: JsonDecoder[ReconciliationWire] =
      DeriveJsonDecoder.gen[ReconciliationWire].mapOrFail(validate)

    def validate(r: ReconciliationWire): Either[String, ReconciliationWire] =
      for
        _ <- versionOk(r.schemaVersion)
        _ <- nonEmpty("reconciliationId", r.reconciliationId)
        _ <- nonEmpty("runId", r.runId)
        _ <- nonEmpty("callId", r.callId)
        _ <- nonEmpty("attemptId", r.attemptId)
        _ <- nonEmpty("jobId", r.jobId)
        _ <- nonEmpty("source", r.source)
        _ <-
          if r.reconciliationVersion >= 1 then Right(())
          else Left("reconciliationVersion must be >= 1")
        _ <- UsageWire.validate(r.previousUsage)
        _ <- UsageWire.validate(r.correctedUsage)
        _ <- shaOk("previousUsageSha256", r.previousUsageSha256)
        _ <- shaOk("prevFileSha256", r.prevFileSha256)
      yield r

  // -- manifest -----------------------------------------------------------------

  final case class GeneratorWire(
      name: String,
      version: String,
      scalaVersion: String,
      jdkVersion: String
  )

  final case class JobLineageWire(
      jobId: String,
      parentJobId: Option[String],
      label: String,
      attempts: Vector[String]
  )

  final case class LineageWire(
      rootId: String,
      jobs: Vector[JobLineageWire]
  )

  final case class VerifierCoverageWire(
      expected: Int,
      produced: Int,
      pending: Int,
      absent: Int
  )

  final case class UsageCoverageWire(
      calls: Int,
      withObserved: Int,
      unknownPrice: Int
  )

  final case class CoverageWire(
      episodes: Int,
      modelCalls: Int,
      toolCalls: Int,
      verdicts: Int,
      verifier: VerifierCoverageWire,
      usage: UsageCoverageWire
  )

  final case class IntegrityWire(
      episodesSha256: String,
      callsSha256: String,
      toolsSha256: String,
      verdictsSha256: String
  )

  final case class ManifestWire(
      schemaVersion: Int,
      generator: GeneratorWire,
      runId: String,
      model: String,
      runStatus: String, // complete | partial
      startedAtMs: Long,
      finishedAtMs: Option[Long],
      lineage: LineageWire,
      coverage: CoverageWire,
      /** Section-integrity digests. reconciliation.jsonl is deliberately NOT
        * pinned here: it is append-only and self-chained via prevFileSha256.
        */
      integrity: IntegrityWire
  )

  object ManifestWire:
    given encoder: JsonEncoder[GeneratorWire] = DeriveJsonEncoder.gen[GeneratorWire]
    given encoderJobLineage: JsonEncoder[JobLineageWire] = DeriveJsonEncoder.gen[JobLineageWire]
    given encoderLineage: JsonEncoder[LineageWire] = DeriveJsonEncoder.gen[LineageWire]
    given encoderVerifierCoverage: JsonEncoder[VerifierCoverageWire] = DeriveJsonEncoder.gen[VerifierCoverageWire]
    given encoderUsageCoverage: JsonEncoder[UsageCoverageWire] = DeriveJsonEncoder.gen[UsageCoverageWire]
    given encoderCoverage: JsonEncoder[CoverageWire] = DeriveJsonEncoder.gen[CoverageWire]
    given encoderIntegrity: JsonEncoder[IntegrityWire] = DeriveJsonEncoder.gen[IntegrityWire]
    given encoderManifest: JsonEncoder[ManifestWire] = DeriveJsonEncoder.gen[ManifestWire]
    given decoderGenerator: JsonDecoder[GeneratorWire] = DeriveJsonDecoder.gen[GeneratorWire]
    given decoderJobLineage: JsonDecoder[JobLineageWire] = DeriveJsonDecoder.gen[JobLineageWire]
    given decoderLineage: JsonDecoder[LineageWire] = DeriveJsonDecoder.gen[LineageWire]
    given decoderVerifierCoverage: JsonDecoder[VerifierCoverageWire] = DeriveJsonDecoder.gen[VerifierCoverageWire]
    given decoderUsageCoverage: JsonDecoder[UsageCoverageWire] = DeriveJsonDecoder.gen[UsageCoverageWire]
    given decoderCoverage: JsonDecoder[CoverageWire] = DeriveJsonDecoder.gen[CoverageWire]
    given decoderIntegrity: JsonDecoder[IntegrityWire] = DeriveJsonDecoder.gen[IntegrityWire]
    given decoder: JsonDecoder[ManifestWire] =
      DeriveJsonDecoder.gen[ManifestWire].mapOrFail(validate)

    def validate(m: ManifestWire): Either[String, ManifestWire] =
      for
        _ <- versionOk(m.schemaVersion)
        _ <- nonEmpty("runId", m.runId)
        _ <- nonEmpty("model", m.model)
        _ <-
          if m.runStatus == "complete" || m.runStatus == "partial" then Right(())
          else Left(s"runStatus '${m.runStatus}' is not complete|partial")
        _ <-
          if m.lineage.rootId == m.runId then Right(())
          else Left("manifest lineage root must equal runId")
        _ <- shaOk("integrity.episodesSha256", m.integrity.episodesSha256)
        _ <- shaOk("integrity.callsSha256", m.integrity.callsSha256)
        _ <- shaOk("integrity.toolsSha256", m.integrity.toolsSha256)
        _ <- shaOk("integrity.verdictsSha256", m.integrity.verdictsSha256)
      yield m

  private[evalexport] object modelExport:
    /** Canonical reasons for honest verifier/outcome absence (EXPORT-02). */
    val verifierExpectedButMissing = "verifierExpectedButMissing"
    val noVerifierDeclared         = "noVerifierDeclared"
    val noEvidenceRecorded         = "noEvidenceRecorded"

end wire
