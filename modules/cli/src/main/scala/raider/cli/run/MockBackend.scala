package raider.cli.run

import raider.core.*
import zio.{Scope, ZIO}
import zio.stream.ZStream

import java.util.UUID

/** Fixture backend for `--mock` runs (RAI-022): clearly marked output ("[mock]
  * …"), no tools, no network, no spend. Fixture discipline: a mock answer can
  * never pass as a live model answer — the marker is part of the TEXT, and
  * summary.md/events carry it too.
  */
object MockBackend:

  val Marker: String = "[mock]"

  def backend: ModelBackend = new ModelBackend:
    def capabilities: ModelCapabilities =
      ModelCapabilities(
        streaming = CapabilityStatus.Supported,
        tools = CapabilityStatus.Unsupported, // honest: the mock has no tools
        cancellationAck = CapabilityStatus.Unknown
      )
    def stream(input: ModelRequest): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap:
        ZIO.succeed:
          val attempt = AttemptId(s"mock-${UUID.randomUUID().toString.take(8)}")
          val prompt = input.messages.lastOption.map(_.content).getOrElse("")
          ZStream(
            ModelEvent.Started(attempt),
            ModelEvent
              .TextDelta(attempt, s"$Marker answer to: ${prompt.take(80)}"),
            ModelEvent.Finished(attempt, "stop")
          )

end MockBackend
