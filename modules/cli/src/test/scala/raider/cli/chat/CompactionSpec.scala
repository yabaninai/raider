package raider.cli.chat

import raider.core.RequestMessage
import zio.test.*

/** Conversation compaction contract (nightly Phase 2.3): when history grows
  * past the threshold, the oldest slice is summarized away and the most recent
  * messages stay intact.
  */
object CompactionSpec extends ZIOSpecDefault:

  private def msg(i: Int) = RequestMessage("user", s"m$i")

  override def spec: Spec[Any, Any] =
    suite("Compaction")(
      test("no compaction at or under the keep limit") {
        val h = (1 to 20).map(msg).toList
        assertTrue(ChatLoop.splitForCompaction(h).isEmpty)
      },
      test(
        "over threshold: oldest slice to summarize + recent kept, middle dropped"
      ) {
        val h = (1 to 35).map(msg).toList // 35 > 30 threshold
        val plan = ChatLoop.splitForCompaction(h)
        assertTrue(
          plan.isDefined,
          plan.get._1 == (1 to 10).map(msg).toList, // oldest 10 summarized
          plan.get._2 == (16 to 35).map(msg).toList, // most recent 20 intact
          plan.get._1.size + plan.get._2.size == 30 // middle 5 dropped
        )
      },
      test("exactly at threshold: no compaction") {
        val h = (1 to 30).map(msg).toList
        assertTrue(ChatLoop.splitForCompaction(h).isEmpty)
      },
      test(
        "just over threshold: oldest 10 summarized, recent kept, middle dropped"
      ) {
        val h = (1 to 31).map(msg).toList
        val plan = ChatLoop.splitForCompaction(h)
        assertTrue(
          plan.get._1 == (1 to 10).map(msg).toList,
          plan.get._2 == (12 to 31).map(msg).toList,
          plan.get._1.size + plan.get._2.size == 30
        )
      },
      test(
        "forced compaction below threshold: summarize the oldest non-kept slice"
      ) {
        val h = (1 to 24).map(msg).toList
        val plan = ChatLoop.splitForCompaction(h, force = true)
        assertTrue(
          plan.get._1 == (1 to 4).map(msg).toList,
          plan.get._2 == (5 to 24).map(msg).toList
        )
      },
      test("forced compaction with nothing to drop: not applicable") {
        val h = (1 to 15).map(msg).toList
        assertTrue(ChatLoop.splitForCompaction(h, force = true).isEmpty)
      }
    )
