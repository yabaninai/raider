package raider.cli.run

import raider.cli.lineage.SessionGraphView
import raider.dsl.lineage.proj.LineageRenderer
import raider.runtime.lineage.model.{GraphHash, SessionGraph}
import raider.runtime.lineage.reduce.{LineageReducer, ReduceError}
import raider.runtime.lineage.wire.LineageEnvelope

/** Immutable incremental view of a live campaign's graph projection.
  *
  * Each accepted wire envelope produces a new value. The graph is reduced with
  * the partial-prefix semantics so open rounds and tools are not advertised as
  * completed nodes. Rendering is pure; callers can publish a completed snapshot
  * without exposing a half-rendered graph. A rejected envelope leaves the
  * previous value untouched.
  */
final case class LiveLineageProjection private (
    wire: Vector[LineageEnvelope],
    graph: SessionGraph,
    source: Option[String],
    graphHash: String,
    projectionStatus: LiveLineageProjection.Status
)

object LiveLineageProjection:

  enum Error:
    case Reduce(detail: ReduceError)
    case View(detail: SessionGraphView.ViewError)

  enum Status:
    case Valid
    case Invalid(detail: String)

  /** No projection exists until a campaign has emitted at least one envelope.
    */
  val initial: Option[LiveLineageProjection] = None

  /** Apply one emitted envelope and produce a complete new graph snapshot. A
    * graph can be valid before it contains enough nodes to render a Scala
    * Program (for example, while a first round is still open); that condition
    * remains visible as Status.Invalid rather than discarding the graph update.
    * Reducer/view failures are typed and leave the prior value untouched.
    */
  def append(
      current: Option[LiveLineageProjection],
      envelope: LineageEnvelope
  ): Either[Error, LiveLineageProjection] =
    val nextWire = current.fold(Vector(envelope))(_.wire :+ envelope)
    for
      reduced <- LineageReducer
        .reducePartialWire(nextWire)
        .left
        .map(Error.Reduce.apply)
      view <- SessionGraphView.toView(reduced.graph).left.map(Error.View.apply)
      rendered = LineageRenderer.render(view)
    yield LiveLineageProjection(
      nextWire,
      reduced.graph,
      rendered.toOption,
      GraphHash(reduced.graph),
      rendered.fold(e => Status.Invalid(String.valueOf(e)), _ => Status.Valid)
    )

end LiveLineageProjection
