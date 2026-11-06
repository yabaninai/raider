package raider.backend.pi

import raider.core.RaiderError

/** Validated connection settings for the pi RPC external backend (task card
  * RAI-034). Construct only through `make`; all fields are validated once so
  * the backend itself stays total.
  *
  * `command` is the argv that starts a pi RPC process (mode flag and script
  * arguments included). `versionCommand` is the separate argv used for the
  * version gate; it must terminate on its own. `expectedVersion` gates `run`:
  * when defined, `probeVersion` is executed first and a mismatch fails with
  * RA-CFG before any pi child is spawned. `settleWaitMs` bounds cancel
  * acknowledgment waits (graceful); the same value bounds how long cancel may
  * take before the outcome is classified as uncertain.
  */
final case class PiRpcConfig(
    command: Vector[String],
    versionCommand: Vector[String],
    expectedVersion: Option[String] = None,
    envExtra: Map[String, String] = Map.empty,
    maxFrameBytes: Int = 16 * 1024 * 1024,
    dispatchTimeoutMs: Long = 15000L,
    settleWaitMs: Long = 3000L,
    gracefulStopMs: Long = 2000L,
    forceStopMs: Long = 1000L
)

object PiRpcConfig:

  /** Validates construction inputs. `RaiderError.Configuration` carries the
    * public explanation in `detail`; no secrets ever flow through here.
    */
  def make(
      command: Seq[String],
      versionCommand: Seq[String],
      expectedVersion: Option[String] = None,
      envExtra: Map[String, String] = Map.empty,
      maxFrameBytes: Int = 16 * 1024 * 1024,
      dispatchTimeoutMs: Long = 15000L,
      settleWaitMs: Long = 3000L,
      gracefulStopMs: Long = 2000L,
      forceStopMs: Long = 1000L
  ): Either[RaiderError.Configuration, PiRpcConfig] =
    def bad(what: String): Either[RaiderError.Configuration, PiRpcConfig] =
      Left(RaiderError.Configuration(what))
    if command.isEmpty then bad("pi command must not be empty")
    else if command.exists(_.isEmpty) then
      bad("pi command must not contain empty arguments")
    else if versionCommand.isEmpty then
      bad("pi versionCommand must not be empty")
    else if maxFrameBytes < 1024 then
      bad("maxFrameBytes must be at least 1024 bytes")
    else if dispatchTimeoutMs <= 0L then
      bad("dispatchTimeoutMs must be positive")
    else if settleWaitMs <= 0L then bad("settleWaitMs must be positive")
    else if gracefulStopMs <= 0L then bad("gracefulStopMs must be positive")
    else if forceStopMs <= 0L then bad("forceStopMs must be positive")
    else
      Right(
        PiRpcConfig(
          command = command.toVector,
          versionCommand = versionCommand.toVector,
          expectedVersion = expectedVersion,
          envExtra = envExtra,
          maxFrameBytes = maxFrameBytes,
          dispatchTimeoutMs = dispatchTimeoutMs,
          settleWaitMs = settleWaitMs,
          gracefulStopMs = gracefulStopMs,
          forceStopMs = forceStopMs
        )
      )

/** Result of the version gate probe: the first stdout line of the child,
  * trimmed, plus whether it satisfied the configured expectation. When
  * `expectedVersion` is unset, `matchesExpectation` is always true.
  */
final case class PiVersionInfo(line: String, matchesExpectation: Boolean)

/** Classification of a cancel outcome (runtime-contracts §10: a cancel is
  * either observable or explicitly uncertain; we never claim success without
  * evidence).
  */
sealed trait PiCancelOutcome:
  def detail: String

object PiCancelOutcome:

  /** pi reported `agent_settled(aborted=true)` inside the wait window, or the
    * run had already settled before cancel was requested.
    */
  final case class Acknowledged(settledAborted: Boolean, detail: String)
      extends PiCancelOutcome

  /** pi did not settle within the deadline; the child was destroyed/stopped, so
    * the final result of the in-flight work is not observable.
    */
  final case class Uncertain(detail: String, childDestroyed: Boolean)
      extends PiCancelOutcome
