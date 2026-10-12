package raider.repl

import raider.core.{JobId, RootId}
import raider.runtime.jobs.JobStatus
import raider.ui.{
  CloseChoiceResolver,
  CloseDecision,
  CommandRouter,
  ShellCommand,
  ShellInputError,
  ShellTerminal,
  TabBarRenderer,
  TabCloseChoice,
  TabId,
  TabShell,
  TabView
}
import zio._
import zio.test._

/** RAI-106 unit fixtures: the palette catalog is fresh against the FROZEN
  * CommandRouter/ShellCommand surface (P2P-01 help freshness), the nearest
  * suggestion is correct (P2P-02), and the unknown-command decoration flows
  * through the REAL frozen TabShell loop over a scripted terminal (one writer,
  * same block).
  *
  * Freshness net (why each layer matters):
  *   - every listed name must route (a removed/renamed command fails here);
  *   - helpLines/hintBar `:tokens` must equal the probed catalog exactly (a
  *     hand-edited help/hint line that rots fails here);
  *   - routing the listed commands covers EVERY ShellCommand constructor —
  *     `tag` is a total match with no wildcard, so a NEW enum case breaks
  *     compilation (-Werror) until it is routed and listed (no silent rot).
  */
object ShellPaletteSpec extends ZIOSpecDefault:

  private val tab = TabView(
    id = TabId("t1"),
    label = "alpha",
    jobId = JobId("j1"),
    rootId = RootId("r1"),
    status = JobStatus.Queued,
    statusText = "queued",
    unreadCount = 0L,
    lastSeq = 0L
  )

  private val tabs = List(tab)

  private def route(raw: String): Either[ShellInputError, ShellCommand] =
    CommandRouter.parse(raw, tabs, Some(tab.id))

  // Total over ShellCommand: a new case is a compile error, not a silent gap.
  private def tag(c: ShellCommand): String = c match
    case ShellCommand.Tabs      => "Tabs"
    case ShellCommand.New(_)    => "New"
    case ShellCommand.Switch(_) => "Switch"
    case ShellCommand.Next      => "Next"
    case ShellCommand.Prev      => "Prev"
    case ShellCommand.Rename(_) => "Rename"
    case ShellCommand.Close(_)  => "Close"
    case ShellCommand.Help      => "Help"
    case ShellCommand.Quit      => "Quit"
    case ShellCommand.Plugins   => "Plugins"
    case ShellCommand.State(_)  => "State"
    case ShellCommand.Aux       => "Aux"
    case ShellCommand.Draft(_)  => "Draft"

  private def colonTokens(lines: Vector[String]): Vector[String] =
    lines.flatMap(_.split("\\s+")).filter(_.startsWith(":")).distinct

  private val allCommandTags = Set(
    "Tabs",
    "New",
    "Switch",
    "Next",
    "Prev",
    "Rename",
    "Close",
    "Help",
    "Quit",
    "Plugins",
    "State",
    "Aux",
    "Draft"
  )

  // ----- scripted-terminal harness: the REAL frozen TabShell loop -----

  private val resolver: CloseChoiceResolver = new CloseChoiceResolver:
    def resolve(running: CloseDecision.RunningChoice): UIO[TabCloseChoice] =
      ZIO.succeed(TabCloseChoice.CancelRequested)

  private def runScripted(
      lines: List[String]
  ): ZIO[Any, Throwable, Vector[String]] =
    ZIO.scoped {
      for
        printed <- Ref.make(Vector.empty[String])
        inputs <- Queue.unbounded[String]
        _ <- ZIO.foreachDiscard(lines)(inputs.offer)
        base = new ShellTerminal:
          def printAbove(l: Vector[String]): UIO[Unit] = printed.update(_ ++ l)
          def readLine(prompt: String): Task[Option[String]] =
            inputs.take.map(Some(_))
          def close: UIO[Unit] = ZIO.unit
        wiring <- TabShellHost.wiring
        _ <- TabShell.run(
          wiring.hub,
          wiring.registry,
          PaletteShellTerminal.withPaletteHints(base),
          resolver
        )
        out <- printed.get
      yield out
    }

  def spec =
    suite("ShellPaletteSpec (RAI-106: palette freshness + nearest hint)")(
      suite("catalog freshness (P2P-01)")(
        test("every listed command routes to something other than Unknown") {
          assertTrue(
            CommandRouter.commands.forall(ShellPalette.isRegistered)
          ) &&
          assertTrue(ShellPalette.commands == CommandRouter.commands)
        },
        test("unregistered names are rejected by the probe") {
          assertTrue(!ShellPalette.isRegistered(":nope")) &&
          assertTrue(!ShellPalette.isRegistered("nope")) &&
          assertTrue(ShellPalette.complete(":nope").isEmpty)
        },
        test("helpLines list exactly the registered catalog, in order") {
          val helpTokens = colonTokens(TabBarRenderer.helpLines)
          assertTrue(helpTokens == ShellPalette.commands)
        },
        test("hintBar lists exactly the registered catalog, in order") {
          val hintTokens = colonTokens(Vector(TabBarRenderer.hintBar))
          assertTrue(hintTokens == ShellPalette.commands)
        },
        test("routing the catalog covers every ShellCommand constructor") {
          val routed = Vector(
            ":tabs",
            ":new alpha",
            ":switch 1",
            ":next",
            ":prev",
            ":rename beta",
            ":close",
            ":help",
            ":quit",
            ":plugins",
            ":state agent",
            ":aux",
            "hello draft"
          ).map(route)
          assertTrue(routed.forall(_.isRight)) &&
          assertTrue(
            routed.flatMap(_.toOption.map(tag)).toSet == allCommandTags
          )
        },
        test("empty-prefix palette lookup yields nothing; prefix finds one") {
          assertTrue(ShellPalette.complete("").isEmpty) &&
          assertTrue(ShellPalette.complete(":pl") == Vector(":plugins")) &&
          assertTrue(
            ShellPalette.complete(":").toSet ==
              CommandRouter.commands.toSet
          )
        }
      ),
      suite("nearest-command suggestion (P2P-02)")(
        test("edit-distance near miss suggests the registered command") {
          assertTrue(ShellPalette.nearest(":hlp").contains(":help")) &&
          assertTrue(ShellPalette.nearest(":stete").contains(":state")) &&
          assertTrue(ShellPalette.nearest(":tabs2").contains(":tabs"))
        },
        test("prefix near miss suggests the shortest prefix match") {
          assertTrue(ShellPalette.nearest(":plug").contains(":plugins"))
        },
        test("registered and far-out tokens get no suggestion") {
          assertTrue(ShellPalette.nearest(":help").isEmpty) &&
          assertTrue(ShellPalette.nearest(":zzzzzzz").isEmpty) &&
          assertTrue(ShellPalette.suggestionLine(":zzzzzzz").isEmpty)
        },
        test("suggestion line is an indented hint") {
          assertTrue(
            ShellPalette.suggestionLine(":hlp") ==
              Some("  did you mean: :help?")
          )
        }
      ),
      suite("unknown-command decoration through the real TabShell loop")(
        test("near miss gains the did-you-mean line inside the SAME block") {
          runScripted(List(":hlp", ":quit")).map { out =>
            val idx = out.indexOf("hint: unknown command: :hlp")
            assertTrue(idx >= 0) &&
            assertTrue(out(idx + 1) == "  did you mean: :help?") &&
            assertTrue(out(idx + 2).startsWith("  available commands: "))
          }
        } @@ TestAspect.timeout(60.seconds),
        test("far-out unknown keeps the plain hint (P2W-02 unchanged)") {
          runScripted(List(":bogus", ":quit")).map { out =>
            val idx = out.indexOf("hint: unknown command: :bogus")
            assertTrue(idx >= 0) &&
            assertTrue(out(idx + 1).startsWith("  available commands: ")) &&
            assertTrue(out.forall(!_.contains("did you mean")))
          }
        } @@ TestAspect.timeout(60.seconds),
        test("non-hint blocks pass through byte-identical") {
          runScripted(List(":tabs", ":quit")).map { out =>
            val expected =
              TabBarRenderer.tabBar(Nil, None) // bar + hint bar lines
            assertTrue(out.take(expected.size) == expected) &&
            assertTrue(out.forall(!_.contains("did you mean")))
          }
        } @@ TestAspect.timeout(60.seconds)
      )
    )

end ShellPaletteSpec
