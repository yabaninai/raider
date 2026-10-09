package raider.runtime.events

import raider.core.codecs.given
import raider.runtime.budget.MicroUsd
import raider.runtime.jobs.JobStatus
import zio.json.{DeriveJsonCodec, JsonCodec, JsonDecoder, JsonEncoder}

/** Canonical zio-json codecs for the frozen event seam (ADR-024 §2.6).
  *
  * Wire rules:
  *   - IDs encode as plain strings (reuses the core id codecs).
  *   - `EventPayload` is discriminated by a `type` field (same rule as
  *     ModelEvent in raider.core.codecs); adding a case is a schema decision
  *     (new schemaVersion), never a silent shape change.
  *   - `JobStatus` / `JobStatus.Terminal` encode as their plain names; an
  *     unknown name is a decode failure, never a best-effort guess.
  *   - `MicroUsd` encodes as a plain integer (micro-USD, exact).
  *   - `None` Option fields are omitted from the wire; a missing field decodes
  *     as `None`.
  *
  * Roundtrip is a hard contract (tested in EventCodecSpec for all 13 payload
  * cases and the full envelope).
  */
object EventCodecs:

  given JsonCodec[MicroUsd] =
    JsonCodec.long.transform(MicroUsd.apply, (m: MicroUsd) => m.value)

  private def statusName(s: JobStatus): String = s match
    case JobStatus.Queued          => "Queued"
    case JobStatus.Running         => "Running"
    case JobStatus.Waiting         => "Waiting"
    case JobStatus.WaitingApproval => "WaitingApproval"
    case JobStatus.Cancelling      => "Cancelling"
    case JobStatus.Succeeded       => "Succeeded"
    case JobStatus.Failed          => "Failed"
    case JobStatus.Cancelled       => "Cancelled"
    case JobStatus.Interrupted     => "Interrupted"
    case JobStatus.Uncertain       => "Uncertain"

  private def statusFromName(name: String): Either[String, JobStatus] =
    name match
      case "Queued"          => Right(JobStatus.Queued)
      case "Running"         => Right(JobStatus.Running)
      case "Waiting"         => Right(JobStatus.Waiting)
      case "WaitingApproval" => Right(JobStatus.WaitingApproval)
      case "Cancelling"      => Right(JobStatus.Cancelling)
      case "Succeeded"       => Right(JobStatus.Succeeded)
      case "Failed"          => Right(JobStatus.Failed)
      case "Cancelled"       => Right(JobStatus.Cancelled)
      case "Interrupted"     => Right(JobStatus.Interrupted)
      case "Uncertain"       => Right(JobStatus.Uncertain)
      case other             => Left(s"unknown JobStatus '$other'")

  given JsonCodec[JobStatus] = JsonCodec(
    JsonEncoder.string.contramap(statusName),
    JsonDecoder.string.mapOrFail(statusFromName)
  )

  private def terminalFromName(
      name: String
  ): Either[String, JobStatus.Terminal] =
    statusFromName(name) match
      case Right(t: JobStatus.Terminal) => Right(t)
      case Right(other) => Left(s"$other is not a terminal JobStatus")
      case Left(err)    => Left(err)

  given JsonCodec[JobStatus.Terminal] = JsonCodec(
    JsonEncoder.string.contramap((t: JobStatus.Terminal) => statusName(t)),
    JsonDecoder.string.mapOrFail(terminalFromName)
  )

  given JsonCodec[WaitReason] = DeriveJsonCodec.gen[WaitReason]

  given JsonCodec[ToolOutcome] = DeriveJsonCodec.gen[ToolOutcome]

  given JsonCodec[LineTail] = DeriveJsonCodec.gen[LineTail]

  given JsonCodec[UsageSnapshot] = DeriveJsonCodec.gen[UsageSnapshot]

  given JsonCodec[EventPayload] = DeriveJsonCodec.gen[EventPayload]

  given JsonCodec[EventEnvelope] = DeriveJsonCodec.gen[EventEnvelope]

end EventCodecs
