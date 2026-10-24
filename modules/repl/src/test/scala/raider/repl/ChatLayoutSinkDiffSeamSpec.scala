package raider.repl

import raider.tools.files.edit.UnifiedDiff
import zio.test.*

/** RAI-191 seam fixture: the REPL caller parses streamed diff fences with the
  * existing `raider.tools.files.edit.UnifiedDiff` and the renderer consumes the
  * mapped hunks. Proves parse → hunks → render without duplicating the parser.
  */
object ChatLayoutSinkDiffSeamSpec extends ZIOSpecDefault:

  def spec =
    suite("ChatLayoutSinkDiffSeamSpec (RAI-191 parse→hunks→render)")(
      test(
        "complete fences parse via UnifiedDiff, map to hunks, and render styled"
      ) {
        val firstBody = "@@ -1,2 +1,2 @@\n context\n-removed\n+added"
        val secondBody = "@@ -5,1 +5,1 @@\n-old\n+new"
        val text =
          s"intro\n```diff\n$firstBody\n```\nmiddle\n```diff\n$secondBody\n```"
        // The same bodies through the existing tools parser directly: the
        // caller must reuse it, not re-implement it.
        val directFirst = UnifiedDiff.parse(firstBody)
        val hunks = ChatLayoutSink.parseDiffHunks(text)
        val rendered = raider.ui.ChatLayout
          .render(
            raider.ui.ChatLayout.Frame(
              60,
              12,
              Vector.empty,
              Vector.empty,
              "ready",
              diffHunks = hunks
            )
          )
          .mkString("\n")
        assertTrue(
          directFirst.isRight,
          directFirst.toOption.map(_.map(_.header)) ==
            Some(List("@@ -1,2 +1,2 @@")),
          hunks.size == 2,
          hunks.head.header == "@@ -1,2 +1,2 @@",
          hunks.head.lines == Vector(
            raider.ui.ChatLayout.DiffLine.Context("context"),
            raider.ui.ChatLayout.DiffLine.Remove("removed"),
            raider.ui.ChatLayout.DiffLine.Add("added")
          ),
          hunks(1).header == "@@ -5,1 +5,1 @@",
          hunks(1).lines == Vector(
            raider.ui.ChatLayout.DiffLine.Remove("old"),
            raider.ui.ChatLayout.DiffLine.Add("new")
          ),
          rendered.contains("@@ -1,2 +1,2 @@"),
          rendered.contains(" context"),
          rendered.contains("\u001b[31m-removed\u001b[0m"),
          rendered.contains("\u001b[32m+added\u001b[0m"),
          rendered.contains("\u001b[36m@@ -5,1 +5,1 @@\u001b[0m"),
          rendered.contains("\u001b[31m-old\u001b[0m"),
          rendered.contains("\u001b[32m+new\u001b[0m")
        )
      },
      test(
        "unterminated fences parse to nothing while streaming and malformed " +
          "hunks drop without crashing"
      ) {
        val streaming = "```diff\n@@ -1,1 +1,1 @@\n-old\n+new"
        val malformed = "```diff\n@@ -1,1 +1,1 @@\ngarbage\n```"
        assertTrue(
          ChatLayoutSink.parseDiffHunks(streaming).isEmpty,
          ChatLayoutSink.parseDiffHunks(malformed).isEmpty,
          ChatLayoutSink.parseDiffHunks("").isEmpty
        )
      },
      test("CRLF fences render hunk rows without carriage returns") {
        val hunks = ChatLayoutSink.parseDiffHunks(
          "```diff\r\n@@ -1,1 +1,1 @@\r\n-old\r\n+new\r\n```"
        )
        val rendered = raider.ui.ChatLayout
          .render(
            raider.ui.ChatLayout.Frame(
              60,
              8,
              Vector.empty,
              Vector.empty,
              "ready",
              diffHunks = hunks
            )
          )
          .mkString("\n")
        assertTrue(
          hunks.size == 1,
          !rendered.contains("\r"),
          rendered.contains("\u001b[32m+new\u001b[0m")
        )
      }
    ) @@ TestAspect.sequential

end ChatLayoutSinkDiffSeamSpec
