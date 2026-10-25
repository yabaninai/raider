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
      },
      test("renders parsed diff hunks supplied by the caller") {
        val rendered = ChatLayout
          .render(
            ChatLayout.Frame(
              50,
              8,
              Vector.empty,
              Vector.empty,
              "ready",
              diffHunks = Vector(
                ChatLayout.DiffHunk(
                  "@@ -1,1 +1,1 @@",
                  Vector(
                    ChatLayout.DiffLine.Remove("old value"),
                    ChatLayout.DiffLine.Add("new value")
                  )
                )
              )
            )
          )
          .mkString("\n")
        assertTrue(
          rendered.contains("@@ -1,1 +1,1 @@"),
          rendered.contains("\u001b[31m-old value\u001b[0m"),
          rendered.contains("\u001b[32m+new value\u001b[0m")
        )
      },
      test(
        "hostile ESC bytes and carriage returns inside hunk rows are neutralized (RAI-192)"
      ) {
        val rendered = ChatLayout
          .render(
            ChatLayout.Frame(
              60,
              8,
              Vector.empty,
              Vector.empty,
              "ready",
              diffHunks = Vector(
                ChatLayout.DiffHunk(
                  "@@ -1,1 +1,1 @@",
                  Vector(
                    ChatLayout.DiffLine.Remove("old \u001b[2Jvalue\r\nbroken"),
                    ChatLayout.DiffLine.Add("new \u001b]0;titlevalue")
                  )
                )
              )
            )
          )
          .mkString("\n")
        assertTrue(
          // raw escape reaches no terminal consumer through any path
          !rendered.contains("\u001b[2J"),
          !rendered.contains("\u001b]0;"),
          !rendered.contains("\r"),
          !rendered.contains("\nbroken"),
          // hostile bytes render as visible glyphs instead
          rendered.contains("␛[2J"),
          rendered.contains("␛]0;"),
          rendered.contains("␍"),
          rendered.contains("\u001b[31m-old ␛[2Jvalue␍␊broken\u001b[0m"),
          rendered.contains("\u001b[32m+new ␛]0;titlevalue\u001b[0m")
        )
      },
      test(
        "SGR-aware clip keeps full visible width and balances colors on truncation (RAI-192)"
      ) {
        val long = "x" * 60
        val lines = ChatLayout.render(
          ChatLayout.Frame(
            20,
            7,
            Vector.empty,
            Vector.empty,
            "ready",
            diffHunks = Vector(
              ChatLayout.DiffHunk(
                "@@ -1 +1 @@",
                Vector(
                  ChatLayout.DiffLine.Remove("y" * 60),
                  ChatLayout.DiffLine.Add(long)
                )
              )
            )
          )
        )
        val colored = lines.filter(l =>
          l.contains("\u001b[32m") || l.contains("\u001b[31m")
        )
        assertTrue(
          colored.size == 2,
          colored.forall { line =>
            val plain = Ansi.replaceAllIn(line, "")
            width(plain) <= 20 &&
            line.endsWith("\u001b[0m") &&
            // the colored add row is not clipped earlier than the columns
            // bound: the old width-counting bug lost visible content
            (if line.startsWith("\u001b[32m") then width(plain) == 20 else true)
          },
          colored.exists(l => Ansi.replaceAllIn(l, "") == "+" + "x" * 19),
          colored.exists(l => Ansi.replaceAllIn(l, "") == "-" + "y" * 19)
        )
      },
      test("per-hunk review markers render with hunk numbers (RAI-192)") {
        val rendered = ChatLayout
          .render(
            ChatLayout.Frame(
              60,
              9,
              Vector.empty,
              Vector.empty,
              "ready",
              diffHunks = Vector(
                ChatLayout.DiffHunk(
                  "@@ -1,1 +1,1 @@",
                  Vector(
                    ChatLayout.DiffLine.Remove("old one"),
                    ChatLayout.DiffLine.Add("new one")
                  ),
                  review = Some(ChatLayout.HunkReview.Accepted)
                ),
                ChatLayout.DiffHunk(
                  "@@ -5,1 +5,1 @@",
                  Vector(
                    ChatLayout.DiffLine.Remove("old two"),
                    ChatLayout.DiffLine.Add("new two")
                  ),
                  review = Some(ChatLayout.HunkReview.Pending)
                )
              )
            )
          )
          .mkString("\n")
        assertTrue(
          rendered.contains("[1:accepted]"),
          rendered.contains("[2:pending]"),
          rendered.contains("\u001b[32m+new one\u001b[0m"),
          rendered.contains("\u001b[31m-old two\u001b[0m")
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
