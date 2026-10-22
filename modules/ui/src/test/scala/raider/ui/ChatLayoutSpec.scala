package raider.ui

import zio.test.*

object ChatLayoutSpec extends ZIOSpecDefault:

  def spec =
    suite("ChatLayoutSpec (RAI-188: chat-first frame and ANSI row diff)")(
      test(
        "keeps the conversation central with bounded history and aux chrome"
      ) {
        val messages =
          (1 to (ChatLayout.HistoryLimit + 2)).map { n =>
            ChatLayout.Message(ChatLayout.Role.User, s"message-$n")
          }.toVector
        val lines = ChatLayout.render(
          ChatLayout.Frame(
            columns = 48,
            rows = 8,
            messages = messages,
            tabs = Vector("*1. coding [running]", "2. review [queued]"),
            status = "running | 3 events",
            auxiliary = Some(Vector("lineage: current run"))
          )
        )
        assertTrue(
          lines.size == 8,
          lines.head == " CHAT ",
          lines.exists(_.contains("message-202")),
          !lines.exists(_.contains("message-1 ")),
          lines.exists(_.contains("lineage: current run")),
          lines(lines.size - 2).contains("coding"),
          lines.last == "running | 3 events"
        )
      },
      test("wraps wide and combining Unicode by display cells") {
        val lines = ChatLayout.render(
          ChatLayout.Frame(
            columns = 12,
            rows = 7,
            messages = Vector(
              ChatLayout.Message(ChatLayout.Role.Assistant, "漢😀e\u0301 words")
            ),
            tabs = Vector.empty,
            status = "ready"
          )
        )
        val body = lines.drop(1).dropRight(2).filter(_.nonEmpty)
        assertTrue(
          body.nonEmpty,
          body.forall(width(_) <= 12),
          body.mkString.contains("漢😀e\u0301")
        )
      },
      test("ANSI renderer emits initial frame then only changed rows") {
        val renderer = new ChatAnsiRenderer
        val first = ChatLayout.Frame(30, 5, Vector.empty, Vector.empty, "ready")
        val initial = renderer.draw(first)
        val unchanged = renderer.draw(first)
        val changed = renderer.draw(first.copy(status = "running"))
        val grown = renderer.draw(first.copy(rows = 7))
        val shrunk = renderer.draw(first)
        assertTrue(
          initial.nonEmpty,
          initial.headOption.contains("\u001b[?25l"),
          unchanged.isEmpty,
          changed.exists(_.contains("running")),
          changed.size == 3,
          grown.size == 9,
          shrunk.exists(_.contains("\u001b[6;1H\u001b[K")),
          shrunk.exists(_.contains("\u001b[7;1H\u001b[K"))
        )
      }
    )

  private def width(text: String): Int =
    text
      .codePoints()
      .toArray
      .map(cp => math.max(0, org.jline.utils.WCWidth.wcwidth(cp)))
      .sum

end ChatLayoutSpec
