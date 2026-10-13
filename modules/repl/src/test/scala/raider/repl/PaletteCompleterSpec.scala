package raider.repl

import raider.ui.CommandRouter
import zio.test._

/** RAI-106 unit fixtures for the TAB palette (P2P-01): candidate sets over the
  * registered catalog through the pure lookup the completer delegates to, and
  * inertness outside `:`-words. The real JLine TAB path is exercised end to end
  * by the PTY fixtures (TabShellPalettePtySpec).
  */
object PaletteCompleterSpec extends ZIOSpecDefault:

  def spec =
    suite("PaletteCompleterSpec (RAI-106: TAB palette over the catalog)")(
      test("unique prefix completes to the one registered command") {
        assertTrue(PaletteCompleter.candidates(":pl") == Vector(":plugins")) &&
        assertTrue(PaletteCompleter.candidates(":he") == Vector(":help")) &&
        assertTrue(PaletteCompleter.candidates(":sw") == Vector(":switch"))
      },
      test("empty command word lists the whole registered catalog") {
        val all = PaletteCompleter.candidates(":")
        assertTrue(all == ShellPalette.commands) &&
        assertTrue(all.toSet == CommandRouter.commands.toSet)
      },
      test("no candidates outside ':'-words (TAB stays inert)") {
        assertTrue(PaletteCompleter.candidates("").isEmpty) &&
        assertTrue(PaletteCompleter.candidates("val x").isEmpty) &&
        assertTrue(PaletteCompleter.candidates(":switch al").isEmpty) &&
        assertTrue(PaletteCompleter.candidates("résumé").isEmpty)
      },
      test("only the trailing token matters (caret semantics)") {
        assertTrue(
          PaletteCompleter.candidates(":plugins") == Vector(":plugins")
        ) &&
        assertTrue(PaletteCompleter.candidates(":state") == Vector(":state")) &&
        assertTrue(
          PaletteCompleter.candidates(":tabs :ne") == Vector(":new", ":next")
        )
      }
    )

end PaletteCompleterSpec
