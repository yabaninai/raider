package raider.cli.run

import raider.core.RaiderError

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** RAI-064: CLI credentials come ONLY from the environment — never from argv,
  * so a secret can never leak into the shell history or the process listing.
  *
  * RAIDER_API_KEY the key value itself (env). RAIDER_API_KEY_CMD optional
  * helper reference: when set, the command is executed and the FIRST line of
  * stdout is the key.
  *
  * Precedence: `RAIDER_API_KEY` (if non-empty) wins over the helper.
  *
  * Neither the key NOR the helper command text is ever logged, echoed into an
  * error, or written to an artifact — only the output of `mask(...)` (first 4
  * chars + `***`, or `<redacted>`) is safe to print.
  */
object Credentials:

  /** The env var that carries the key value. */
  val EnvKey = "RAIDER_API_KEY"

  /** The env var that carries an optional helper command reference. */
  val EnvHelper = "RAIDER_API_KEY_CMD"

  /** Safe-to-print form of a secret: first 4 chars + `***`. Empty or too-short
    * values collapse to `<redacted>` so a short secret is never revealed whole.
    * This is the ONLY form of the key that may appear in logs/errors.
    */
  def mask(value: String): String =
    if value.isEmpty || value.length <= 4 then "<redacted>"
    else value.take(4) + "***"

  /** True for any provider that makes live network calls and therefore needs a
    * credential. Only the mock backend is exempt.
    */
  def isLive(provider: String): Boolean = provider != "mock"

  /** Resolve the API key for `provider` from the environment.
    *   - mock provider -> Right(None) (no key required; nothing read)
    *   - live + env key present -> Right(Some(key))
    *   - live + helper set -> Right(Some(<first stdout line>))
    *   - live + neither available -> Left(Configuration) — a preflight failure
    *     that happens BEFORE any dispatch/spend.
    *
    * `env` is injectable for tests; production uses the process environment.
    */
  def resolve(
      provider: String,
      env: Map[String, String] = sys.env
  ): Either[RaiderError, Option[String]] =
    if !isLive(provider) then Right(None)
    else
      val fromEnv = env.get(EnvKey).map(_.trim).filter(_.nonEmpty)
      fromEnv match
        case Some(key) => Right(Some(key))
        case None =>
          val helper = env.get(EnvHelper).map(_.trim).filter(_.nonEmpty)
          helper match
            case Some(command) => runHelper(command)
            case None =>
              Left(
                RaiderError.Configuration(
                  s"missing credentials for provider '$provider': set " +
                    s"$EnvKey (env) or $EnvHelper (helper command); " +
                    "secrets must not appear in argv/shell history"
                )
              )

  /** Hard wall-clock bound on the credential helper: a hung helper must not
    * hang the CLI forever. The bound covers the WHOLE helper operation (stdout
    * read AND process exit) as a single deadline, not just `waitFor`.
    */
  private val helperTimeoutSeconds: Long = 10

  /** Run the helper command (`sh -c <command>`) and take the FIRST line of
    * stdout as the key. The command text and the resulting key are NEVER logged
    * — any failure message refers to the command only as `<redacted>`. A
    * missing/empty key, a failed process, or a helper that exceeds
    * `helperTimeoutSeconds` is a typed Configuration error.
    *
    * stderr is drained on a daemon thread and DISCARDED: a helper that writes
    * more than the OS pipe buffer to stderr would otherwise block on the write
    * and deadlock us before `waitFor`. `redirectErrorStream` is deliberately
    * NOT used — it would merge stderr into stdout and pollute the first-line
    * key extraction.
    *
    * stdout is likewise consumed on a thread and joined against the deadline: a
    * helper that hangs without producing output (e.g. `sleep 30`) would
    * otherwise block the first `readLine` forever, past any `waitFor` bound.
    */
  private def runHelper(command: String): Either[RaiderError, Option[String]] =
    try
      val proc = new ProcessBuilder("sh", "-c", command).start()
      val stderrDrain = new Thread(() =>
        try
          val err = proc.getErrorStream
          val buf = new Array[Byte](8192)
          var open = true
          while open do if err.read(buf) < 0 then open = false
        catch case _: java.io.IOException => ()
      )
      stderrDrain.setDaemon(true)
      stderrDrain.start()
      @volatile var first: Option[String] = None
      val stdoutReader = new Thread(() =>
        try
          val reader = new BufferedReader(
            new InputStreamReader(proc.getInputStream, StandardCharsets.UTF_8)
          )
          first = Option(reader.readLine()).map(_.trim).filter(_.nonEmpty)
          // drain the rest so the child never blocks on a full stdout pipe
          LazyList
            .continually(Option(reader.readLine()))
            .takeWhile(_.isDefined)
            .foreach(_ => ())
        catch case _: java.io.IOException => ()
      )
      stdoutReader.setDaemon(true)
      stdoutReader.start()
      val deadlineNanos =
        System.nanoTime() + helperTimeoutSeconds * 1_000_000_000L
      stdoutReader.join(helperTimeoutSeconds * 1000L)
      val remainingMs =
        math.max((deadlineNanos - System.nanoTime()) / 1_000_000L, 0L)
      val exited = proc.waitFor(remainingMs, TimeUnit.MILLISECONDS)
      if !exited then
        proc.destroy() // graceful first (SIGTERM semantics)
        if !proc.waitFor(2, TimeUnit.SECONDS) then proc.destroyForcibly()
        Left(
          RaiderError.Configuration(
            s"credential helper timed out after ${helperTimeoutSeconds}s; " +
              "the helper command is <redacted> by design"
          )
        )
      else
        val code = proc.exitValue()
        first match
          case Some(key) => Right(Some(key))
          case None =>
            Left(
              RaiderError.Configuration(
                s"$EnvHelper helper returned no key (exit $code); " +
                  "the helper command is <redacted> by design"
              )
            )
    catch
      case e: Exception =>
        Left(
          RaiderError.Configuration(
            s"$EnvHelper helper failed to run: ${e.getClass.getSimpleName}; " +
              "the helper command is <redacted> by design"
          )
        )

end Credentials
