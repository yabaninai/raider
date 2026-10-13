package raider.repl

import zio.test._

/** RAI-132 `:send` command surface — pure pins without an engine (the full
  * end-to-end path is SteerCommandsPtySpec):
  *
  *   - STR-02 (usage, no dispatch): bare `:send`, a missing or blank text never
  *     parse — the command prints the usage line and dispatches nothing;
  *   - the freshness registry (ReplChatCommands) and Commands.helpText carry
  *     :send with the exact usage token (the full equality net is
  *     ReplChatPaletteSpec; here the RAI-132 entries are pinned like RAI-124
  *     pins :type);
  *   - the stale "send steering" out-of-slice prose is gone;
  *   - arguments are escaped into generated string literals safely.
  */
object SendCommandsSpec extends ZIOSpecDefault:

  def spec = suite("SendCommandsSpec (RAI-132: :send input plumbing)")(
    suite("parseSend: usage refusals never dispatch (STR-02)")(
      test("bare :send and id-only forms are usage refusals") {
        assertTrue(Commands.parseSend(":send").isEmpty) &&
        assertTrue(Commands.parseSend(":send j_1").isEmpty) &&
        assertTrue(Commands.parseSend(":send j_1   ").isEmpty) &&
        assertTrue(Commands.parseSend(":send   ").isEmpty)
      },
      test("blank text is a usage refusal — quotes-only text included") {
        assertTrue(Commands.parseSend(":send j_1 \"\"").isEmpty) &&
        assertTrue(Commands.parseSend(":send j_1    ").isEmpty) &&
        assertTrue(Commands.parseSend(":send j_1 \t ").isEmpty)
      },
      test("a near-miss command is not a send") {
        assertTrue(Commands.parseSend(":sendx j_1 hi").isEmpty) &&
        assertTrue(Commands.parseSend(":sendj_1 hi").isEmpty) &&
        assertTrue(Commands.parseSend(":cancel j_1").isEmpty)
      },
      test("the exact form parses: id + multiword text preserved verbatim") {
        assertTrue(
          Commands.parseSend(":send j_1 hello world") ==
            Some(("j_1", "hello world"))
        ) &&
        assertTrue(
          Commands.parseSend(":send j_42 focus the parser") ==
            Some(("j_42", "focus the parser"))
        ) &&
        assertTrue(
          Commands.parseSend(":send j_1 \"quoted value\"") ==
            Some(("j_1", "\"quoted value\""))
        )
      }
    ),
    suite("surface freshness: helpText, registry and palette carry :send")(
      test("the helpText line is the two-argument usage with the summary") {
        val line = Commands.helpText.linesIterator
          .find(_.contains(":send"))
          .getOrElse("")
        assertTrue(
          line.contains(":send <jobId> <text>"),
          line.contains("steer a running job")
        )
      },
      test(
        "the registry entry sits between :cancel and :log (helpText order)"
      ) {
        val names = ReplChatCommands.names
        val cancelAt = names.indexOf(":cancel")
        val sendAt = names.indexOf(":send")
        val logAt = names.indexOf(":log")
        assertTrue(
          sendAt == cancelAt + 1,
          logAt == sendAt + 1
        )
      },
      test("the palette/completion surface derives :send from the registry") {
        assertTrue(ShellPalette.chatCommands.contains(":send")) &&
        assertTrue(
          ShellPalette.completeChat(":se") == Vector(":send")
        ) &&
        assertTrue(
          CompletionSupport.chatCandidates(":se") == Some(Vector("send"))
        )
      },
      test("the stale out-of-slice prose about send steering is gone") {
        assertTrue(!Commands.helpText.contains("send steering")) &&
        assertTrue(Commands.helpText.contains("Not in this slice:"))
      }
    ),
    suite("escapeScala: generated command sources cannot break")(
      test("quotes, backslashes and control characters are escaped") {
        assertTrue(Commands.escapeScala("say \"hi\"") == "say \\\"hi\\\"") &&
        assertTrue(Commands.escapeScala("back\\slash") == "back\\\\slash") &&
        assertTrue(Commands.escapeScala("a\nb") == "a\\u000ab") &&
        assertTrue(Commands.escapeScala("plain") == "plain")
      }
    )
  )

end SendCommandsSpec
