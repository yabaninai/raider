package raider.testkit

import raider.core.*
import zio.{Ref, UIO, ZIO, Scope}
import zio.stream.ZStream

/** RAI-006: deterministic scripted ModelBackend — no LLM, no network, no clocks.
  *
  * The script is an exact ordered sequence of normalized ModelEvents; every
  * request is recorded. Fixture mode is a first-class marker (mock output must
  * never be presented as a live model answer). Core contract suites can run
  * against any backend; this one makes them deterministic and fast.
  */
final class ScriptedModelBackend private (
  val fixtureMode: Boolean,
  script: Vector[ModelEvent],
  recorder: Ref[Vector[ModelRequest]]
) extends ModelBackend:

  override def capabilities: ModelCapabilities =
    ModelCapabilities(
      streaming = CapabilityStatus.Supported,
      tools = CapabilityStatus.Supported,
      cancellationAck = CapabilityStatus.Supported
    )

  override def stream(input: ModelRequest): ZStream[Scope, RaiderError, ModelEvent] =
    ZStream.fromZIO(recorder.update(_ :+ input)) *>
      ZStream.fromIterable(script)

  /** All requests seen so far, in order. */
  def requests: UIO[Vector[ModelRequest]] = recorder.get

  /** The exact script (for asserting what the loop observed). */
  def plannedEvents: Vector[ModelEvent] = script

object ScriptedModelBackend:
  /** Create a scripted backend emitting `script` in order. */
  def apply(events: ModelEvent*): UIO[ScriptedModelBackend] =
    Ref.make(Vector.empty[ModelRequest]).map(ref =>
      new ScriptedModelBackend(fixtureMode = true, events.toVector, ref))

  /** Failing attempt: a single Failed event with the given error. */
  def failing(error: RaiderError): UIO[ScriptedModelBackend] =
    apply(ModelEvent.Failed(AttemptId("scripted-1"), error))
