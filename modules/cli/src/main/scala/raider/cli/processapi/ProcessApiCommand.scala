package raider.cli.processapi

import zio.{UIO, ZIO}

import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.AtomicBoolean

/** RAI-201 entrypoint: `raider process-api [flags]`. Strict argv: only the
  * documented flags are accepted (ADR-037 §5 launch flags); unknown flags,
  * duplicates or missing values are refused with exit 21 BEFORE any runtime
  * wiring — a process adapter must never guess its way into a run.
  *
  * SIGTERM: the JVM shutdown hook fires the session's [[Session.SignalBridge]]
  * and waits AT MOST 5 seconds for the session to settle, then RETURNS — it
  * NEVER calls System.exit (hook discipline from Main.scala; calling exit
  * inside a hook deadlocks Shutdown.runHooks). The graceful path resolves the
  * exit code (143 per result.scala §8) and main() returns it normally; the
  * POSIX death after hooks still yields 143 if hooks are too slow.
  */
object ProcessApiCommand:

  private val KnownFlags: Set[String] = Set(
    "--provider",
    "--model",
    "--agent",
    "--workspace",
    "--out",
    "--base-url"
  )

  private val Providers = Set("mock", "openai", "anthropic")
  private val Agents = Set("plain", "coder")

  private def defaultBaseUrl(provider: String): String = provider match
    case "mock"      => "http://mock.invalid/v1"
    case "openai"    => "https://api.openai.com/v1"
    case "anthropic" => "https://api.anthropic.com/v1"
    case _           => "http://mock.invalid/v1"

  /** Pure routing: parses argv into a [[Session.Config]] or a refusal reason.
    */
  def parse(args: List[String]): Either[String, Session.Config] =
    @scala.annotation.tailrec
    def loop(
        rest: List[String],
        acc: Map[String, String]
    ): Either[String, Map[String, String]] =
      rest match
        case Nil => Right(acc)
        case flag :: value :: tail if KnownFlags.contains(flag) =>
          if acc.contains(flag) then Left(s"duplicate flag $flag")
          else loop(tail, acc + (flag -> value))
        case flag :: Nil if KnownFlags.contains(flag) =>
          Left(s"missing value for $flag")
        case other :: _ =>
          Left(s"unknown argument '${Protocol.bounded(other, 64)}'")

    loop(args, Map.empty).flatMap { flags =>
      val provider = flags.getOrElse("--provider", "mock")
      if !Providers.contains(provider) then
        Left(
          s"unsupported --provider '$provider' (expected one of: mock, openai, anthropic)"
        )
      else
        val agent = flags.getOrElse("--agent", "plain")
        if !Agents.contains(agent) then
          Left(s"unsupported --agent '$agent' (expected plain or coder)")
        else
          val baseUrlE: Either[String, String] = flags.get("--base-url") match
            case Some(raw) => validateHttpUrl(raw)
            case None      => Right(defaultBaseUrl(provider))
          baseUrlE.flatMap { url =>
            val workspace =
              Paths.get(
                flags.getOrElse("--workspace", System.getProperty("user.dir"))
              )
            val out = Paths.get(flags.getOrElse("--out", "./raider-out"))
            Right(
              Session.Config(
                launch = RootRun.LaunchConfig(
                  provider = provider,
                  model = flags.getOrElse("--model", "mock-model"),
                  coder = agent == "coder",
                  workspace = workspace.toAbsolutePath.normalize(),
                  baseUrl = url,
                  env = sys.env
                ),
                outDir = out
              )
            )
          }
    }

  private def validateHttpUrl(raw: String): Either[String, String] =
    try
      val uri = java.net.URI.create(raw)
      val scheme = uri.getScheme
      if scheme != "http" && scheme != "https" then
        Left("base URL must be absolute http(s)")
      else Right(uri.toString)
    catch
      case _: IllegalArgumentException =>
        Left(s"invalid --base-url '${Protocol.bounded(raw, 64)}'")
      case e: Exception =>
        Left(s"invalid --base-url: ${e.getClass.getSimpleName}")

  /** Runs the session to completion and returns the process exit code. Blocking
    * entrypoint (called from Main's pre-parse dispatch slot).
    */
  def command(args: List[String]): Int =
    parse(args) match
      case Left(reason) =>
        System.err.println(s"raider process-api: $reason")
        raider.core.result.ExitCodes.ConfigInvalid
      case Right(cfg) =>
        val done = new AtomicBoolean(false)
        val program: ZIO[Any, Nothing, Int] =
          for
            bridge <- Session.SignalBridge.make
            fiber <- Session.run(cfg, bridge).forkDaemon
            _ <- ZIO.succeed(installSigtermHook(bridge, done))
            code <- fiber.join.ensuring(ZIO.succeed(done.set(true)))
          yield code
        zio.Unsafe.unsafe { implicit unsafe =>
          zio.Runtime.default.unsafe.run(program).getOrThrow()
        }

  /** Hook discipline (Main.scala): fire the bridge on a daemon fiber, wait a
    * BOUNDED 5s for the session to settle, then return — never System.exit.
    */
  private def installSigtermHook(
      bridge: Session.SignalBridge,
      done: AtomicBoolean
  ): Unit =
    Runtime.getRuntime.addShutdownHook(
      new Thread(
        () => {
          bridge.fire()
          val deadline = System.currentTimeMillis() + 5000L
          while !done.get() && System.currentTimeMillis() < deadline do
            try Thread.sleep(50)
            catch case _: InterruptedException => ()
        },
        "raider-process-api-sigterm"
      )
    )

end ProcessApiCommand
