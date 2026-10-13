package raider.repl

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** RAI-090: API-key resolution for the REPL live backends — environment only,
  * never argv (parity with the CLI, RAI-064). `RAIDER_API_KEY` carries the key
  * value itself; `RAIDER_API_KEY_CMD` optionally names a helper command that is
  * run via `sh -c`, the FIRST line of its stdout being the key. Precedence: a
  * non-empty `RAIDER_API_KEY` wins over the helper.
  *
  * DUPLICATION NOTE (deliberate): this object is a SIMPLIFIED copy of the CLI
  * credentials pattern (`raider.cli.run.Credentials`). The repl module must not
  * depend on raider-cli (headless-distribution rule: cli carries no compiler,
  * repl carries no cli) and build.sbt is coordinator-owned, so a shared
  * credentials module needs a separate scope card. Until then keep the two in
  * sync by review; the load-bearing parts kept identical: env-only sourcing,
  * `sh -c` first-stdout-line helper, bounded helper wait, and the `mask` form
  * (first 4 chars + `***`, shorter values collapse to `<redacted>`) as the ONLY
  * form of a secret that may reach logs/errors. Simplified vs the CLI original:
  * no typed RaiderError channel — a missing key degrades into a human-readable
  * notice and the scripted/mock backend (a live-wiring failure must never crash
  * the REPL), and helper failures surface as `None` rather than per-failure
  * typed errors.
  */
object LiveCredentials:

  /** The env var that carries the key value. */
  val EnvKey = "RAIDER_API_KEY"

  /** The env var that carries an optional helper command reference. */
  val EnvHelper = "RAIDER_API_KEY_CMD"

  /** Providers that make live network calls and therefore need a credential.
    * Only the mock/scripted path is exempt.
    */
  val LiveProviders: Set[String] = Set("openai", "anthropic")

  def isLive(provider: String): Boolean = LiveProviders.contains(provider)

  /** Safe-to-print form of a secret: first 4 chars + `***`; empty or short
    * values collapse to `<redacted>` so a short secret is never revealed whole.
    * This is the ONLY form of the key allowed in logs/notices/errors.
    */
  def mask(value: String): String =
    if value.isEmpty || value.length <= 4 then "<redacted>"
    else value.take(4) + "***"

  /** PURE key resolution (unit-tested, RKEY-01..03). `envValue` is the raw
    * `RAIDER_API_KEY` value, `helperOutput` the first stdout line the helper
    * runner captured (both injectable so tests never spawn processes):
    *   - mock/unknown provider -> Right(None) (no key needed; existing mock
    *     path)
    *   - live + non-empty env value -> Right(Some(trimmed key))
    *   - live + helper output present -> Right(Some(trimmed first line))
    *   - live + both empty -> Left(guidance message explaining how to enable
    *     live mode; contains only env-var names, never any secret).
    */
  def resolveKey(
      provider: String,
      envValue: Option[String],
      helperOutput: Option[String]
  ): Either[String, Option[String]] =
    if !isLive(provider) then Right(None)
    else
      val fromEnv = envValue.map(_.trim).filter(_.nonEmpty)
      val fromHelper = helperOutput.map(_.trim).filter(_.nonEmpty)
      fromEnv.orElse(fromHelper) match
        case Some(key) => Right(Some(key))
        case None      => Left(missingKeyMessage(provider))

  /** Guidance shown when a live provider starts without a resolvable key. Names
    * only env-var names — never a key or a helper command text.
    */
  def missingKeyMessage(provider: String): String =
    s"live provider '$provider' has no API key: set $EnvKey (env value) or " +
      s"$EnvHelper (helper command; its first stdout line is used) to go " +
      "live — secrets must not appear in argv or shell history. " +
      "Continuing with the scripted/mock backend."

  /** Production entrypoint: gathers the two inputs from the environment (the
    * helper runs only when `RAIDER_API_KEY` is absent/blank) and delegates to
    * the pure `resolveKey`. `env` and `helper` are injectable for tests. When a
    * helper was configured but produced nothing, the guidance message says so —
    * the command text itself stays `<redacted>` by design.
    */
  def resolve(
      provider: String,
      env: Map[String, String] = sys.env,
      helper: String => Option[String] = runHelper
  ): Either[String, Option[String]] =
    if !isLive(provider) then Right(None)
    else
      val envValue = env.get(EnvKey)
      val helperCmd = env.get(EnvHelper).map(_.trim).filter(_.nonEmpty)
      val helperOutput = helperCmd.flatMap(helper)
      resolveKey(provider, envValue, helperOutput) match
        case Left(msg) if helperCmd.isDefined && helperOutput.isEmpty =>
          Left(
            msg +
              " (a helper was configured but produced no key; the command " +
              "text is <redacted> by design)"
          )
        case other => other

  /** Hard wall-clock bound on the whole helper operation (simplified copy of
    * the CLI Credentials bound): a hung helper must not hang the REPL.
    */
  private val helperTimeoutSeconds: Long = 10

  /** Run the helper (`sh -c <command>`) and return the FIRST line of stdout as
    * the key. Simplified copy of `raider.cli.run.Credentials.runHelper` (see
    * the duplication note above). The command text and the key are NEVER logged
    * — a caller-visible failure is just `None`. stderr is drained on a daemon
    * thread so a chatty helper cannot deadlock on a full pipe, and the
    * remainder of stdout is drained after the first line so the child never
    * blocks on the write side either.
    */
  private def runHelper(command: String): Option[String] =
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
            new InputStreamReader(
              proc.getInputStream,
              StandardCharsets.UTF_8
            )
          )
          first = Option(reader.readLine()).map(_.trim).filter(_.nonEmpty)
          LazyList
            .continually(Option(reader.readLine()))
            .takeWhile(_.isDefined)
            .foreach(_ => ())
        catch case _: java.io.IOException => ()
      )
      stdoutReader.setDaemon(true)
      stdoutReader.start()
      stdoutReader.join(helperTimeoutSeconds * 1000L)
      if proc.waitFor(helperTimeoutSeconds, TimeUnit.SECONDS) then first
      else
        proc.destroy() // graceful first (SIGTERM semantics)
        if !proc.waitFor(2, TimeUnit.SECONDS) then proc.destroyForcibly()
        None
    catch case _: Exception => None

end LiveCredentials
