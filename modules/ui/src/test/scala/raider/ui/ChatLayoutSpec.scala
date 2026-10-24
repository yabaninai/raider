package raider.ui

import zio.test.*

object ChatLayoutSpec extends ZIOSpecDefault:

  private val Ansi = "\\u001b\\[[0-9;]*m".r

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
          Ansi.replaceAllIn(lines.head, "").startsWith("┌─ CHAT"),
          lines.exists(_.contains("message-202")),
          !lines.exists(_.contains("message-1 ")),
          lines.exists(_.contains("lineage: current run")),
          lines(lines.size - 2).contains("coding"),
          lines.last.contains("running | 3 events")
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
      test("bounded viewport moves back through chat history") {
        val history = Vector(
          ChatLayout.Message(ChatLayout.Role.User, "older entry"),
          ChatLayout.Message(ChatLayout.Role.Assistant, "middle entry"),
          ChatLayout.Message(ChatLayout.Role.User, "newer entry")
        )
        val latest = ChatLayout.render(
          ChatLayout.Frame(40, 5, history, Vector("chat"), "ready")
        )
        val scrolled = ChatLayout.render(
          ChatLayout.Frame(
            40,
            5,
            history,
            Vector("chat"),
            "scrolled",
            scrollOffset = 2
          )
        )
        assertTrue(
          latest.exists(_.contains("newer entry")),
          !latest.exists(_.contains("older entry")),
          scrolled.exists(_.contains("older entry")),
          !scrolled.exists(_.contains("newer entry"))
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
      },
      test(
        "markdown and partial code blocks render incrementally with syntax color"
      ) {
        val complete = ChatLayout
          .render(
            ChatLayout.Frame(
              60,
              9,
              Vector(
                ChatLayout.Message(
                  ChatLayout.Role.Assistant,
                  "# Heading\n- item\n```scala\nval answer = 42\n```"
                )
              ),
              Vector.empty,
              "ready"
            )
          )
          .mkString("\n")
        val partial = ChatLayout
          .render(
            ChatLayout.Frame(
              60,
              8,
              Vector(
                ChatLayout.Message(
                  ChatLayout.Role.Assistant,
                  "```json\n{\"ok\": true"
                )
              ),
              Vector.empty,
              "streaming"
            )
          )
          .mkString("\n")
        assertTrue(
          complete.contains("Heading"),
          complete.contains("• item"),
          complete.contains("\u001b[34mval\u001b[0m"),
          complete.contains("┌────────────"),
          Ansi.replaceAllIn(partial, "").contains("{\"ok\": true"),
          partial.contains("\u001b[32m")
        )
      },
      test(
        "unified diff lines, themes, and hostile control characters are safe"
      ) {
        def rendered(theme: ChatLayout.Theme) = ChatLayout
          .render(
            ChatLayout.Frame(
              50,
              8,
              Vector(
                ChatLayout.Message(
                  ChatLayout.Role.Assistant,
                  "```diff\n@@ -1 +1 @@\n-old\n+new\n```\nraw \u001b[2J 界e\u0301"
                )
              ),
              Vector.empty,
              "ready",
              theme = theme
            )
          )
          .mkString("\n")
        val dark = rendered(ChatLayout.Theme.Dark)
        val light = rendered(ChatLayout.Theme.Light)
        assertTrue(
          dark.contains("\u001b[31m-old\u001b[0m"),
          dark.contains("\u001b[32m+new\u001b[0m"),
          dark.contains("␛[2J"),
          dark.contains("界e\u0301"),
          dark != light,
          light.contains("\u001b[38;5;25m")
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
