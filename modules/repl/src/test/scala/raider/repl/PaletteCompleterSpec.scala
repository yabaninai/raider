package raider.repl

import org.jline.reader.{Candidate, Parser}
import org.jline.reader.impl.DefaultParser
import raider.ui.CommandRouter
import zio.test._
import scala.jdk.CollectionConverters.*

/** RAI-106 unit fixtures for the TAB palette (P2P-01): candidate sets over the
  * registered catalog via the real JLine parser path (DefaultParser — the
  * parser the palette LineReader is built with), and inertness outside
  * `:`-words.
  */
object PaletteCompleterSpec extends ZIOSpecDefault:

  private def candidatesFor(text: String, cursor: Int): Vector[String] =
    val parsed =
      new DefaultParser().parse(text, cursor, Parser.ParseContext.COMPLETE)
    val buffer = new java.util.ArrayList[Candidate]()
    new PaletteCompleter().complete(null, parsed, buffer)
    buffer.asScala.toVector.map(_.value)

  def spec =
    suite("PaletteCompleterSpec (RAI-106: TAB palette over the catalog)")(
      test("unique prefix completes to the one registered command") {
        assertTrue(candidatesFor(":pl", 3) == Vector(":plugins")) &&
        assertTrue(candidatesFor(":he", 3) == Vector(":help")) &&
        assertTrue(candidatesFor(":sw", 3) == Vector(":switch"))
      },
      test("empty command word lists the whole registered catalog") {
        val all = candidatesFor(":", 1)
        assertTrue(all == ShellPalette.commands) &&
        assertTrue(all.toSet == CommandRouter.commands.toSet)
      },
      test("no candidates outside ':'-words (TAB stays inert)") {
        assertTrue(candidatesFor("", 0).isEmpty) &&
        assertTrue(candidatesFor("val x", 5).isEmpty) &&
        assertTrue(candidatesFor(":switch al", 10).isEmpty) &&
        assertTrue(candidatesFor("привет", 6).isEmpty)
      },
      test("mid-word cursor only sees the text up to the caret") {
        assertTrue(candidatesFor(":plugins", 4) == Vector(":plugins")) &&
        assertTrue(candidatesFor(":state", 3) == Vector(":state"))
      }
    )

end PaletteCompleterSpec
