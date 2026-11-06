package raider.backend.pi

import zio.json.EncoderOps
import zio.json.ast.Json

/** Capability disclosure for the pi RPC external backend (runtime-contracts
  * §13). The record is an honest, machine-readable statement of what this
  * adapter guarantees; `canonicalJson` renders it deterministically (sorted
  * keys, 2-space indent, no trailing newline) so the golden fixture in
  * `contracts/backends/pi/golden/pi-rpc-capabilities.json` can be compared
  * byte-for-byte.
  *
  * The trust-boundary facts are the point of this file: tools execute inside
  * pi, so Raider tool policy is NOT enforced inside pi; usage cost is not
  * priced by Raider (`priceKnown=false`); no session/steering/structured output
  * support in the RPC surface that Raider consumes.
  */
object PiRpcCapabilities:

  final case class PiCapabilityReport(
      schemaVersion: Int,
      backend: String,
      protocol: String,
      streaming: String,
      tools: String,
      cancellationAck: String,
      nestedAccounting: String,
      toolOwnership: String,
      sessionSupport: String,
      structuredOutput: String,
      steering: String,
      priceModel: String
  ):
    def canonicalJson: String = PiRpcCapabilities.canonicalJson(this)

  /** The single canonical disclosure for this adapter version. Upstream pin
    * value `55a5c6914570e2ae31ca36297654483bbbf46883` is part of the protocol
    * description.
    */
  def defaults: PiCapabilityReport =
    PiCapabilityReport(
      schemaVersion = 1,
      backend = "pi-rpc",
      protocol =
        "pi-rpc/1 (upstream pin 55a5c6914570e2ae31ca36297654483bbbf46883)",
      streaming = "supported",
      tools = "supported-externally-owned",
      cancellationAck = "supported",
      nestedAccounting = "unknown",
      toolOwnership =
        "tools execute inside pi; Raider tool policy is NOT enforced inside pi",
      sessionSupport = "unsupported",
      structuredOutput = "unsupported",
      steering = "unsupported",
      priceModel =
        "priceKnown=false: pi reports cost, Raider does not price pi usage"
    )

  private def quote(s: String): String = Json.Str(s).toJson

  /** Deterministic canonical rendering: keys sorted lexicographically, 2-space
    * indent, exactly one trailing `}` with no newline. `schemaVersion` is the
    * only non-string value (a plain integer).
    */
  def canonicalJson(report: PiCapabilityReport): String =
    val fields: List[(String, String)] = List(
      "backend" -> quote(report.backend),
      "cancellationAck" -> quote(report.cancellationAck),
      "nestedAccounting" -> quote(report.nestedAccounting),
      "priceModel" -> quote(report.priceModel),
      "protocol" -> quote(report.protocol),
      "schemaVersion" -> report.schemaVersion.toString,
      "sessionSupport" -> quote(report.sessionSupport),
      "steering" -> quote(report.steering),
      "streaming" -> quote(report.streaming),
      "structuredOutput" -> quote(report.structuredOutput),
      "toolOwnership" -> quote(report.toolOwnership),
      "tools" -> quote(report.tools)
    )
    val body = fields
      .sortBy(_._1)
      .map { case (k, v) => s"""  "$k": $v""" }
      .mkString(",\n")
    "{\n" + body + "\n}"

  /** Convenience: the canonical JSON of the default disclosure. */
  def defaultsJson: String = canonicalJson(defaults)
