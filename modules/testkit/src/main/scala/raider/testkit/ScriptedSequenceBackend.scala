package raider.testkit

import raider.core.*
import zio.{Ref, UIO, ZIO, Scope}
import zio.stream.ZStream

/** RAI-010.a loop support (RAI-006 territory, minimal documented extension):
  * a scripted ModelBackend that plays ONE script per `stream()` call —
  * k-th call plays k-th script. This is what a tool ROUND needs: round 1 asks
  * for a tool, round 2 sees the tool result and answers.
  *
  * Fixture discipline is unchanged (mock output is never a live answer):
  * `fixtureMode` is always true. An EXTRA call beyond the scripted sequence is
  * a typed StreamProtocol failure — a loop that makes more model calls than the
  * fixture provides can never pass silently.
  */
final class ScriptedSequenceBackend private (
  scripts: Vector[Vector[ModelEvent]],
  counter: Ref[Int],
  recorder: Ref[Vector[ModelRequest]]
) extends ModelBackend:

  override def capabilities: ModelCapabilities =
    ModelCapabilities(
      streaming = CapabilityStatus.Supported,
      tools = CapabilityStatus.Supported,
      cancellationAck = CapabilityStatus.Supported
    )

  override def stream(input: ModelRequest): ZStream[Scope, RaiderError, ModelEvent] =
    ZStream.fromZIO:
      for
        k <- counter.getAndUpdate(_ + 1)
        _ <- recorder.update(_ :+ input)
        script <- scripts.lift(k) match
          case Some(s) => ZIO.succeed(s)
          case None =>
            ZIO.fail(RaiderError.StreamProtocol(
              s"scripted sequence exhausted: model call #$k but only " +
                s"${scripts.size} script(s) planned (fixtureMode=true)"))
      yield script
    .flatMap(ZStream.fromIterable(_))

  /** First-class fixture marker (mock never passes as live). */
  def fixtureMode: Boolean = true

  /** All requests seen so far, in call order. */
  def requests: UIO[Vector[ModelRequest]] = recorder.get

  /** Exact planned scripts (for asserting what the loop was given). */
  def plannedScripts: Vector[Vector[ModelEvent]] = scripts

object ScriptedSequenceBackend:
  /** One script per model call: `ScriptedSequenceBackend(round1Events,
    * round2Events, ...)`. Zero scripts = the first call fails typed. */
  def apply(scripts: Vector[ModelEvent]*): UIO[ScriptedSequenceBackend] =
    for
      counter  <- Ref.make(0)
      recorder <- Ref.make(Vector.empty[ModelRequest])
    yield new ScriptedSequenceBackend(scripts.toVector, counter, recorder)
end ScriptedSequenceBackend
