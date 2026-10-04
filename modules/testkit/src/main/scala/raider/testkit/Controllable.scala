package raider.testkit

import raider.core.*
import zio.{Duration, Promise, Ref, UIO, ZIO, Scope}
import zio.stream.ZStream

/** RAI-006.b (MOCK-03): controllable gates for deterministic cancellation and
  * finish races — no real sleeps anywhere.
  *
  * Determinism contract: every primitive RECORDS first, PARKS second. Once the
  * recorded observation reports the call (requests/started), the consumer fiber
  * is at (or within one micro-step of) its park point — interrupting, adjusting
  * a virtual clock or opening the gate from the test fiber is then a
  * deterministic race-free action. Fixture discipline is unchanged:
  * `fixtureMode` is always true.
  */

/** A scripted ModelBackend whose k-th `stream()` call records the request and
  * then parks on gate k INSIDE dispatch until `open(k)` is called. With no gate
  * left, an extra call fails typed StreamProtocol.
  */
final class GatedModelBackend private (
    scripts: Vector[Vector[ModelEvent]],
    gates: Vector[Promise[Nothing, Unit]],
    counter: Ref[Int],
    recorder: Ref[Vector[ModelRequest]]
) extends ModelBackend:

  override def capabilities: ModelCapabilities =
    ModelCapabilities(
      streaming = CapabilityStatus.Supported,
      tools = CapabilityStatus.Supported,
      cancellationAck = CapabilityStatus.Supported
    )

  override def stream(
      input: ModelRequest
  ): ZStream[Scope, RaiderError, ModelEvent] =
    ZStream
      .fromZIO:
        for
          k <- counter.getAndUpdate(_ + 1)
          _ <- recorder.update(
            _ :+ input
          ) // record FIRST: visibility = parked soon
          gate <- gates.lift(k) match
            case Some(g) => ZIO.succeed(g)
            case None =>
              ZIO.fail(
                RaiderError.StreamProtocol(
                  s"gated sequence exhausted: model call #$k but only " +
                    s"${gates.size} gate(s) planned (fixtureMode=true)"
                )
              )
          _ <- gate.await // deterministic park inside dispatch
          script <- scripts.lift(k) match
            case Some(s) => ZIO.succeed(s)
            case None =>
              ZIO.fail(
                RaiderError.StreamProtocol(
                  s"gated sequence has no script for call #$k"
                )
              )
        yield script
      .flatMap(ZStream.fromIterable(_))

  /** First-class fixture marker. */
  def fixtureMode: Boolean = true

  /** All requests seen so far; size k+1 ⇒ call k is parked at gate k. */
  def requests: UIO[Vector[ModelRequest]] = recorder.get

  /** Open gate k (the k-th model call proceeds). */
  def open(k: Int): UIO[Unit] =
    gates.lift(k).fold(ZIO.unit)(_.succeed(()).unit)

  /** Open every remaining gate. */
  def openAll: UIO[Unit] = ZIO.foreachDiscard(gates)(_.succeed(()).unit)

object GatedModelBackend:

  def apply(scripts: Vector[ModelEvent]*): UIO[GatedModelBackend] =
    for
      gates <- ZIO.foreach(scripts.toList)(_ => Promise.make[Nothing, Unit])
      counter <- Ref.make(0)
      recorder <- Ref.make(Vector.empty[ModelRequest])
    yield new GatedModelBackend(
      scripts.toVector,
      gates.toVector,
      counter,
      recorder
    )

/** A Tool whose `invoke` records the arguments and then parks on a gate until
  * `open` is called (or times out via the caller's virtual clock adjustments —
  * the gate itself is promise-based and clock-free).
  */
final class GatedTool private (
    val name: String,
    val version: Int,
    val description: String,
    val recovery: RecoveryClass,
    val timeoutMs: Long,
    val capabilities: ToolCapabilities,
    holder: Ref[Promise[Nothing, Unit]],
    callsRef: Ref[Vector[String]],
    outputJson: String
) extends Tool:

  def invoke(argumentsJson: String): ZIO[Scope, RaiderError, String] =
    for
      gate <- Promise.make[Nothing, Unit]
      _ <- holder.set(gate)
      _ <- callsRef.update(_ :+ argumentsJson) // started ⇒ parked at gate soon
      _ <- gate.await
    yield outputJson

  /** Arguments recorded so far; size >= 1 ⇒ invoke parked at the gate. */
  def started: UIO[Vector[String]] = callsRef.get

  /** Open the gate of the CURRENT invocation. */
  def open: UIO[Unit] = holder.get.flatMap(_.succeed(()).unit)

object GatedTool:

  def apply(
      name: String = "gated",
      outputJson: String = """{"ok":true}""",
      recovery: RecoveryClass = RecoveryClass.ReadOnly,
      timeoutMs: Long = 60000L,
      concurrentSafe: Boolean = false
  ): UIO[GatedTool] =
    for
      gate <- Promise.make[Nothing, Unit]
      holder <- Ref.make(gate)
      calls <- Ref.make(Vector.empty[String])
    yield new GatedTool(
      name,
      1,
      "controllable gated tool",
      recovery,
      timeoutMs,
      ToolCapabilities(concurrentSafe),
      holder,
      calls,
      outputJson
    )

end GatedTool
