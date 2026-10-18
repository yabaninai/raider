package raider.cli.run

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.budget.MicroUsd
import raider.runtime.events.{EventEnvelope, EventPayload, UsageSnapshot}
import raider.runtime.jobs.JobStatus
import raider.runtime.lineage.model.{GraphHash, SessionNode}
import raider.runtime.lineage.wire.{LineageWire, LineageEnvelope}
import zio.test.*

/** Behavioral fixtures for incremental graph-to-source snapshots. */
object LiveLineageProjectionSpec extends ZIOSpecDefault:

  private val job = JobId("lin3-live")
  private val root = RootId("lin3-root")
  private val session = SessionId("lin3-session")

  private def event(seq: Long, payload: EventPayload): LineageEnvelope =
    LineageWire.wrap(
      EventEnvelope(1, seq, job, root, session, 1760000000000L + seq, payload)
    )

  def spec = suite("LiveLineageProjection")(
    test(
      "LIN3-01: every round publishes a complete graph and matching projection"
    ) {
      val rounds = 200
      val stream = (1 to rounds).toVector.flatMap { round =>
        val startSeq = (round - 1L) * 2L + 1L
        Vector(
          event(
            startSeq,
            EventPayload.ModelCallStarted(round, "scripted", None)
          ),
          event(
            startSeq + 1L,
            EventPayload.ModelCallFinished(
              round,
              0L,
              UsageSnapshot(MicroUsd(0), MicroUsd(0), unknown = true)
            )
          )
        )
      } :+ event(
        rounds.toLong * 2L + 1L,
        EventPayload.Terminal(JobStatus.Succeeded)
      )

      val states = stream.foldLeft[Either[LiveLineageProjection.Error, Option[
        LiveLineageProjection
      ]]](
        Right(LiveLineageProjection.initial)
      )((state, line) =>
        state.flatMap(LiveLineageProjection.append(_, line).map(Some(_)))
      )

      val allSnapshotsValid = states.exists { finalState =>
        // Rebuild each incremental prefix independently to assert that no published
        // projection gets ahead of its complete graph nodes.
        stream.indices.forall { index =>
          stream
            .take(index + 1)
            .foldLeft[Either[LiveLineageProjection.Error, Option[
              LiveLineageProjection
            ]]](
              Right(LiveLineageProjection.initial)
            )((state, line) =>
              state.flatMap(LiveLineageProjection.append(_, line).map(Some(_)))
            ) match
            case Right(Some(snapshot)) =>
              snapshot.source.isDefined == ((index + 1) / 2 > 0) &&
              snapshot.graphHash == GraphHash(snapshot.graph) &&
              snapshot.graph.nodes.count(
                _.isInstanceOf[SessionNode.ModelCall]
              ) ==
                (index + 1) / 2
            case _ => false
        } && finalState.exists(
          _.graph.nodes.count(_.isInstanceOf[SessionNode.ModelCall]) == rounds
        )
      }

      assertTrue(states.isRight, allSnapshotsValid)
    },
    test(
      "LIN3-02: invalid incremental input is typed and does not mutate prior snapshot"
    ) {
      val first = event(1L, EventPayload.ModelCallStarted(1, "scripted", None))
      val prior = LiveLineageProjection.append(None, first)
      val invalid = event(
        3L,
        EventPayload.ModelCallFinished(
          1,
          0L,
          UsageSnapshot(MicroUsd(0), MicroUsd(0), unknown = true)
        )
      )
      val attempted = prior.flatMap(snapshot =>
        LiveLineageProjection.append(Some(snapshot), invalid)
      )

      assertTrue(
        prior.isRight,
        attempted.left.toOption.exists {
          case LiveLineageProjection.Error.Reduce(_) => true
          case _                                     => false
        },
        prior.toOption.exists(_.wire == Vector(first))
      )
    }
  )

end LiveLineageProjectionSpec
