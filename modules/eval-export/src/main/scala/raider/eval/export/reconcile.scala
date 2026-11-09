package raider.evalexport

import zio.json.*

/** Late-usage reconciliation (RAI-040 acceptance EXPORT-03).
  *
  * Usage evidence can arrive after the export (provider reports, gateway
  * correction). This object appends VERSIONED reconciliation records to
  * reconciliation.jsonl and leaves every other document byte-identical —
  * the manifest is not rewritten, no section digest changes, and the
  * original usage numbers remain visible as `previousUsage` of each record.
  *
  * Chain discipline (executable, see [[Reconciliation.verifyChain]]):
  *   - records are numbered rc-1, rc-2, ... in file order;
  *   - per call, reconciliationVersion runs strictly 1..n;
  *   - `previousUsageSha256` pins the canonical JSON of the usage the record
  *     corrects (the call's usage for version 1, the previous record's
  *     correctedUsage afterwards) — a complete per-call correction history;
  *   - `prevFileSha256` pins the sha256 of the reconciliation file content
  *     BEFORE the append (the empty-file hash for the first record), which
  *     makes the whole append history tamper-evident without any manifest
  *     re-write.
  */
object Reconciliation:

  /** Append one versioned late-usage record. Pure: returns a new bundle
    * where reconciliation.jsonl has one more line and every other file is
    * byte-identical.
    */
  def append(bundle: ExportBundle, late: model.LateUsage): Either[model.ExportError, ExportBundle] =
    for
      calls  <- bundle.parseJsonl[wire.CallWire](ExportBundle.CallsName)
      call   <- calls.find(_.callId == late.callId).toRight(model.ExportError.MissingCall(late.callId))
      _      <- validateLate(late)
      corrected <- validateUsage(late.usage, late.callId)
      existing       <- bundle.parseJsonl[wire.ReconciliationWire](ExportBundle.ReconciliationName)
      existingContent <- bundle.content(ExportBundle.ReconciliationName)
      forCall = existing.filter(_.callId == late.callId)
      version = forCall.map(_.reconciliationVersion).foldLeft(0)(math.max) + 1
      previous = forCall.lastOption.map(_.correctedUsage).getOrElse(call.usage)
      record = wire.ReconciliationWire(
        schemaVersion = model.schemaVersion,
        reconciliationId = s"rc-${existing.size + 1}",
        runId = call.runId,
        callId = call.callId,
        attemptId = call.attemptId,
        jobId = call.jobId,
        reconciliationVersion = version,
        previousUsage = previous,
        previousUsageSha256 = Digests.sha256HexUtf8(wire.UsageWire.canonical(previous)),
        correctedUsage = corrected,
        source = late.source,
        atMs = late.atMs,
        prevFileSha256 = Digests.sha256HexUtf8(existingContent)
      )
    yield bundle.withFile(ExportBundle.ReconciliationName, existingContent + record.toJson + "\n")

  /** Verify the append chain of a bundle: every record's prevFileSha256 must
    * equal the sha256 of the file prefix before it, ids must be rc-1..rc-n in
    * order, and per-call versions must run 1..n without gaps.
    */
  def verifyChain(bundle: ExportBundle): Either[model.ExportError, Unit] =
    for
      content <- bundle.content(ExportBundle.ReconciliationName)
      recs    <- bundle.parseJsonl[wire.ReconciliationWire](ExportBundle.ReconciliationName)
      _       <- walk(content, recs)
    yield ()

  private def walk(content: String, recs: Vector[wire.ReconciliationWire]): Either[model.ExportError, Unit] =
    val lines = ExportBundle.splitJsonl(content)
    if lines.size != recs.size then
      Left(
        model.ExportError.ChainViolation(
          s"file has ${lines.size} lines but ${recs.size} records decode"
        )
      )
    else
      val nextVersion = scala.collection.mutable.Map.empty[String, Int]
      var prefix      = ""
      recs.zipWithIndex.foldLeft[Either[model.ExportError, Unit]](Right(())) { (acc, pair) =>
        acc.flatMap { _ =>
          val (rec, idx) = pair
          val expectedId = s"rc-${idx + 1}"
          if rec.reconciliationId != expectedId then
            Left(model.ExportError.ChainViolation(s"expected reconciliation id $expectedId, got '${rec.reconciliationId}'"))
          else if Digests.sha256HexUtf8(prefix) != rec.prevFileSha256 then
            Left(
              model.ExportError.ChainViolation(
                s"record '$expectedId' prevFileSha256 does not match the file content before it"
              )
            )
          else
            val expectedVersion = nextVersion.getOrElse(rec.callId, 0) + 1
            if rec.reconciliationVersion != expectedVersion then
              Left(
                model.ExportError.ChainViolation(
                  s"call '${rec.callId}' expected reconciliation version $expectedVersion, got ${rec.reconciliationVersion}"
                )
              )
            else
              nextVersion(rec.callId) = expectedVersion
              prefix += lines(idx) + "\n"
              Right(())
        }
      }

  private def validateLate(late: model.LateUsage): Either[model.ExportError, Unit] =
    if late.source.isEmpty then Left(model.ExportError.EmptyField("source", "late usage"))
    else if late.atMs < 0 then Left(model.ExportError.EmptyField("atMs", "late usage"))
    else Right(())

  private def validateUsage(usage: model.Usage, callId: String): Either[model.ExportError, wire.UsageWire] =
    wire.UsageWire
      .validate(wire.UsageWire.fromUsage(usage))
      .left.map(msg => model.ExportError.BadUsage(s"late usage for call '$callId': $msg"))

end Reconciliation
