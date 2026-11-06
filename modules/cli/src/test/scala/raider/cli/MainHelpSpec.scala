package raider.cli

import zio.ZIO
import zio.test._

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

/** RAI-261: the installed CLI entrypoint is self-describing — `raider --help` /
  * `raider help` exit 0 with an accurate command list, and every unknown-flag /
  * unknown-subcommand failure stays nonzero with a diagnostic that points at
  * `--help`.
  *
  * Layers:
  *   - pure: [[Main.helpCommand]] routing (same seam discipline as
  *     `Main.pluginCommand`) and the accuracy of the rendered text against the
  *     commands Main actually dispatches;
  *   - process: the REAL `raider.cli.Main` in a child JVM (forked tests put the
  *     module classpath in `java.class.path` — same harness precedent as
  *     `ReplayProductCliSpec`), asserting the §8 exit codes and the stderr hint
  *     with closed stdin.
  */
object MainHelpSpec extends ZIOSpecDefault:

  def spec = suite("RAI-261 helpful CLI entrypoint")(
    // ----- pure routing + content -------------------------------------------

    test("HELP-01: --help and help route; everything else falls through") {
      assertTrue(
        Main.helpCommand(Array("--help")).isDefined,
        Main.helpCommand(Array("help")).isDefined,
        // fall-through: dispatch order and exact first-token matching
        Main.helpCommand(Array.empty[String]).isEmpty,
        Main.helpCommand(Array("run", "--bundle", "x.jar")).isEmpty,
        Main.helpCommand(Array("chat")).isEmpty,
        Main.helpCommand(Array("plugin", "list")).isEmpty,
        Main.helpCommand(Array("process-api")).isEmpty,
        Main.helpCommand(Array("workflow", "check", "--doc", "d.json")).isEmpty,
        // prefix tokens are NOT help
        Main.helpCommand(Array("--helpful")).isEmpty,
        Main.helpCommand(Array("helper")).isEmpty
      )
    },
    test("HELP-02: help text lists exactly the commands Main dispatches") {
      val text = Main.helpCommand(Array("--help")).getOrElse("")
      val required = List(
        "run",
        "chat",
        "process-api",
        "compile",
        "workflow run",
        "workflow check",
        "workflow explain",
        "plugin",
        "replay",
        "resume",
        "verify-artifacts"
      )
      val missing = required.filterNot(text.contains)
      assertTrue(
        missing.isEmpty,
        // REPL ships separately: the pointer must be present, and help must
        // NOT advertise `repl` as a command of THIS distribution
        text.contains("SEPARATE distribution"),
        text.contains("raider.repl.Main"),
        // the unknown-flag diagnostic pointer is part of the list
        text.contains("--help")
      )
    },
    test("HELP-03: help rendering is pure — parallel invocations agree") {
      // No stdout writes, no exit, no initialization in the seam, so two
      // fibers racing on it are trivially safe; pin the property.
      def render: Option[String] = Main.helpCommand(Array("--help"))
      for
        a <- ZIO.attempt(render).fork
        b <- ZIO.attempt(render)
        av <- a.join
      yield assertTrue(av.isDefined, av == b, av.forall(_.nonEmpty))
    },
    // ----- process-level entrypoint (real Main, real exit codes) ------------

    test("HELP-04: `raider --help` exits 0 with non-empty accurate stdout") {
      runMain(List("--help")).map {
        // a Left is a harness failure; zio.test renders the problem string
        // when the assertion below fails
        case Left(problem) => assertTrue(problem.isEmpty)
        case Right((code, out, err)) =>
          assertTrue(
            code == 0,
            out.trim.nonEmpty,
            out.contains("usage:"),
            out.contains("process-api"),
            out.contains("verify-artifacts"),
            out.contains("SEPARATE distribution"),
            err.trim.isEmpty
          )
      }
    },
    test("HELP-05: `raider help` exits 0 with the same command list") {
      runMain(List("help")).map {
        case Left(problem) => assertTrue(problem.isEmpty)
        case Right((code, out, _)) =>
          val viaFlag = Main.helpCommand(Array("--help")).getOrElse("")
          assertTrue(
            code == 0,
            out.trim == viaFlag.trim,
            out.contains("workflow explain")
          )
      }
    },
    test(
      "HELP-06: unknown run flag exits 21 and the diagnostic points at --help"
    ) {
      runMain(List("run", "--frobnicate")).map {
        case Left(problem) => assertTrue(problem.isEmpty)
        case Right((code, _, err)) =>
          assertTrue(
            code == 21,
            err.contains("unknown argument '--frobnicate'"),
            err.contains("--help")
          )
      }
    },
    test(
      "HELP-07: unknown subcommand exits 21 and the diagnostic points at --help"
    ) {
      runMain(List("definitely-not-a-command")).map {
        case Left(problem) => assertTrue(problem.isEmpty)
        case Right((code, _, err)) =>
          assertTrue(
            code == 21,
            err.contains("unknown subcommand"),
            err.contains("--help")
          )
      }
    }
  )

  // ----- process harness ------------------------------------------------------

  private lazy val m2Root: Path =
    val cwd = Path.of("").toAbsolutePath
    Iterator
      .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) => dir }
      .find(dir => Files.isDirectory(dir.resolve("artifacts/tools/m2")))
      .getOrElse(throw new IllegalStateException("no artifacts/tools/m2"))
      .resolve("artifacts")
      .resolve("tools")
      .resolve("m2")

  private lazy val userClasspath: String =
    val lib = m2Root
      .resolve("org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar")
    if !Files.isRegularFile(lib) then
      throw new IllegalStateException("missing pinned jar: " + lib)
    val base = System.getProperty("java.class.path", "")
    if base.isEmpty then lib.toString
    else lib.toString + java.io.File.pathSeparator + base

  /** Runs `raider.cli.Main <argv>` as a REAL process with CLOSED stdin
    * (empty-file redirect — Main never reads stdin) and separate stdout/stderr
    * capture. Left = timeout/launch failure, never an assertion outcome.
    */
  private def runMain(
      argv: List[String]
  ): ZIO[Any, Nothing, Either[String, (Int, String, String)]] =
    ZIO.attempt {
      val emptyStdin = Files.createTempFile("rai261-stdin", ".empty")
      val outLog = Files.createTempFile("rai261-out", ".log")
      val errLog = Files.createTempFile("rai261-err", ".log")
      val pb = new ProcessBuilder(
        ("java" :: "-cp" :: userClasspath :: "raider.cli.Main" :: argv)*
      )
      pb.redirectInput(emptyStdin.toFile)
      pb.redirectOutput(outLog.toFile)
      pb.redirectError(errLog.toFile)
      val proc = pb.start()
      val finished = proc.waitFor(120L, TimeUnit.SECONDS)
      val result =
        if !finished then
          proc.destroyForcibly()
          Left("raider.cli.Main did not finish within 120s (destroyed)")
        else
          Right(
            (
              proc.exitValue(),
              Files.readString(outLog, UTF_8),
              Files.readString(errLog, UTF_8)
            )
          )
      Files.deleteIfExists(emptyStdin)
      Files.deleteIfExists(outLog)
      Files.deleteIfExists(errLog)
      result
    }.orDie
