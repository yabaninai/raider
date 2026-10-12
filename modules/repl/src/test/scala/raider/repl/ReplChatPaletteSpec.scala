package raider.repl

import zio.test._

/** RAI-111 unit fixtures: the chat-command palette of the REPL cycle.
  *
  * Freshness net (why each layer matters):
  *   - `Commands.helpText` is the SOURCE OF TRUTH of the chat-command surface
  *     (card RAI-111; Commands.scala itself is outside this lane's boundary).
  *     The spec parses its command lines (name + usage + summary, in order) and
  *     requires the parsed surface to EQUAL the [[ReplChatCommands]] registry —
  *     a command added to or removed from Commands, or a help line edited,
  *     breaks this equality until the registry follows. That equality IS the
  *     "freshness test catches add/remove" acceptance: a registry entry without
  *     a helpText line fails, a helpText line without a registry entry fails.
  *   - the card-named cycle commands (:jobs/:cancel/:log/:reset) are pinned to
  *     exist, so a helpText rewrite cannot silently drop them;
  *   - the derived surfaces ([[ShellPalette.chatCommands]],
  *     [[ShellPalette.completeChat]], [[CompletionSupport.chatCandidates]]) are
  *     pure functions of the registry — asserted here without an engine;
  *   - the PTY end-to-end path (real JLine TAB over the product hosts) lives in
  *     ReplChatCyclePtySpec (chat REPL) and TabShellChatPalettePtySpec
  *     (TabShell hint/streaming discipline).
  */
object ReplChatPaletteSpec extends ZIOSpecDefault:

  /** helpText command line: two-space indent, the `:name [<arg>]` usage token,
    * then 2+ spaces, then the summary. Deliberately strict — a helpText
    * formatting change must FAIL here (loud drift), not pass silently.
    */
  private val CommandLine = raw"^  (:\S+(?: <[^>]+>)?)\s{2,}(.+?)\s*$$".r

  /** The parsed source of truth: (name, usage, summary) in helpText order. */
  private def parsedHelpText: Vector[(String, String, String)] =
    Commands.helpText.linesIterator.collect {
      case CommandLine(usage, summary) =>
        val name = usage.takeWhile(c => c != ' ')
        (name, usage, summary)
    }.toVector

  def spec =
    suite("ReplChatPaletteSpec (RAI-111: chat commands in the palette)")(
      suite("freshness against Commands.helpText (PAL2-01)")(
        test("the registry equals the parsed helpText surface exactly") {
          val fromSource = parsedHelpText
          val fromRegistry =
            ReplChatCommands.catalog.map(c => (c.name, c.usage, c.summary))
          assertTrue(fromSource == fromRegistry)
        },
        test("registry names are the derived palette catalog, in order") {
          assertTrue(
            ShellPalette.chatCommands == ReplChatCommands.catalog.map(_.name)
          ) &&
          assertTrue(
            ShellPalette.chatCommands == parsedHelpText.map((n, _, _) => n)
          )
        },
        test("the card-named cycle commands are pinned in the catalog") {
          val names = ShellPalette.chatCommands.toSet
          assertTrue(names.contains(":jobs")) &&
          assertTrue(names.contains(":cancel")) &&
          assertTrue(names.contains(":log")) &&
          assertTrue(names.contains(":reset"))
        },
        test("out-of-slice names never enter the catalog via prose") {
          // ":type" appears in the "Not in this slice" line — the strict
          // command-line parser must not pick it up as a palette entry.
          assertTrue(!ShellPalette.chatCommands.contains(":type")) &&
          assertTrue(Commands.helpText.contains("Not in this slice: :type"))
        }
      ),
      suite("derived lookup surfaces")(
        test("completeChat: prefix finds the one command; empty yields none") {
          assertTrue(ShellPalette.completeChat(":jo") == Vector(":jobs")) &&
          assertTrue(ShellPalette.completeChat(":ca") == Vector(":cancel")) &&
          assertTrue(
            ShellPalette.completeChat(":").toSet ==
              ReplChatCommands.names.toSet
          ) &&
          assertTrue(ShellPalette.completeChat("").isEmpty)
        },
        test(
          "nearest searches the combined catalog (chat near miss suggests)"
        ) {
          assertTrue(ShellPalette.nearest(":jbs").contains(":jobs")) &&
          assertTrue(ShellPalette.nearest(":cancle").contains(":cancel")) &&
          assertTrue(ShellPalette.nearest(":hepl").contains(":help"))
        },
        test("a chat command itself never yields a suggestion") {
          // In tabs mode a chat name is not routable, but it IS a known
          // command of the product: hinting ":jobs?" for ":jobs" would be
          // absurd. The plain available-commands hint stays the only hint.
          assertTrue(ShellPalette.nearest(":jobs").isEmpty) &&
          assertTrue(ShellPalette.nearest(":cancel").isEmpty) &&
          assertTrue(ShellPalette.nearest(":log").isEmpty) &&
          assertTrue(ShellPalette.nearest(":reset").isEmpty) &&
          assertTrue(ShellPalette.suggestionLine(":jobs").isEmpty)
        },
        test("shell-side RAI-106 nearest semantics are unchanged") {
          assertTrue(ShellPalette.nearest(":hlp").contains(":help")) &&
          assertTrue(ShellPalette.nearest(":stete").contains(":state")) &&
          assertTrue(ShellPalette.nearest(":zzzzzzz").isEmpty) &&
          assertTrue(ShellPalette.commands == raider.ui.CommandRouter.commands)
        }
      ),
      suite("CompletionSupport.chatCandidates (REPL-cycle TAB)")(
        test("a unique prefix completes the trailing segment (colon-less)") {
          assertTrue(
            CompletionSupport.chatCandidates(":jo") == Some(Vector("jobs"))
          ) &&
          assertTrue(
            CompletionSupport.chatCandidates(":ca") == Some(Vector("cancel"))
          )
        },
        test("the bare colon lists the whole catalog") {
          assertTrue(
            CompletionSupport.chatCandidates(":") ==
              Some(ShellPalette.chatCommands.map(_.drop(1)))
          )
        },
        test("a colon word without a match yields Some(empty) — no engine") {
          assertTrue(
            CompletionSupport.chatCandidates(":zzz") ==
              Some(Vector.empty)
          )
        },
        test("non-command words are left to the engine branch") {
          assertTrue(CompletionSupport.chatCandidates("val x").isEmpty) &&
          assertTrue(CompletionSupport.chatCandidates("List(1).ma").isEmpty) &&
          assertTrue(CompletionSupport.chatCandidates(":cancel ").isEmpty) &&
          assertTrue(CompletionSupport.chatCandidates("").isEmpty)
        },
        test("only the trailing token matters") {
          assertTrue(
            CompletionSupport.chatCandidates("val x :jo") ==
              Some(Vector("jobs"))
          ) &&
          assertTrue(
            CompletionSupport.chatCandidates(":tabs :lo") ==
              Some(Vector("log"))
          )
        }
      )
    )

end ReplChatPaletteSpec
