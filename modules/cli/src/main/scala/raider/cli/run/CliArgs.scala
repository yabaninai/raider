package raider.cli.run

import raider.core.RaiderError

import java.nio.file.{Files, Path, Paths}

/** Headless CLI arguments (RAI-022 slice): pure parser — no shell, no stdin,
  * typed errors. Subcommand `run` only: run (--bundle <jar> --workflow <name> |
  * --agent <name>) (--input <text> | --input-json <file>) --out <dir> [--mock]
  * `--agent` and `--bundle/--workflow` are MUTUALLY exclusive (working-product
  * §3). `--mock` marks fixture allowance: live providers are NOT wired in this
  * slice, so the runner refuses without it.
  *
  * RAI-064: `--api-key` is REMOVED — passing it is a typed Configuration error
  * pointing at the env path. Credentials are resolved from the environment (see
  * [[Credentials]]), never from argv, so a secret can't reach shell history or
  * the process listing.
  *
  * RAI-071: `--provider` accepts `mock|openai|anthropic` — the Anthropic
  * Messages wire is a first-class backend alongside the OpenAI-compatible one;
  * both share the same env-credential preflight ([[Credentials]]) and the same
  * `--base-url`/`--model` flags.
  *
  * RAI-086: `--context <file>` points at a ci-context JSON payload (wire schema
  * of [[CiContext]]). The file must EXIST at parse time — a missing file is a
  * typed Configuration error here; a malformed or incomplete payload is refused
  * later by [[CiContext.parse]] as a typed `CiContextError` during the runner
  * preflight (before any dispatch).
  */
final case class CliArgs(
    target: CliArgs.Target,
    input: Option[String],
    inputJson: Option[Path],
    out: Path,
    mock: Boolean,
    provider: String = "mock",
    baseUrl: String = "http://127.0.0.1:8081/v1",
    model: String = "local",
    workspace: Option[Path] = None,
    context: Option[Path] = None
):
  // RAI-064: NO credential field — the key is resolved from the environment
  // (RAIDER_API_KEY / RAIDER_API_KEY_CMD) at runtime, never carried in args.

  def inputText: Either[RaiderError, String] = (input, inputJson) match
    case (Some(text), _) => Right(text)
    case (None, Some(file)) =>
      try Right(java.nio.file.Files.readString(file))
      catch
        case e: Exception =>
          Left(
            RaiderError.InputValidation(
              s"cannot read --input-json: ${e.getClass.getSimpleName}"
            )
          )
    case (None, None) =>
      Left(
        RaiderError.InputValidation("one of --input/--input-json is required")
      )

object CliArgs:

  sealed trait Target

  object Target:
    final case class Bundle(jar: Path, workflow: String) extends Target
    final case class Agent(name: String) extends Target

    /** RAI-109: the `raider replay` subcommand (LIN-10 product wiring). The
      * target carries the RAW argv (the `replay` token included) — the flag
      * surface (`<run-dir>`, `--workspace`, `--data`, usage errors) stays owned
      * by the accepted LIN-10 `ReplayCli.parse`, so the replay argv contract
      * has ONE source and the wiring cannot drift from the standalone entry. No
      * run/chat flag is accepted or validated here.
      */
    final case class Replay(argv: List[String]) extends Target

    /** RAI-129: the `raider resume <run-dir>` subcommand (§13 recovery
      * surface). Same shape as [[Replay]]: the target carries the RAW argv (the
      * `resume` token included) — the argv contract (`<run-dir>`, usage errors)
      * stays owned by `ResumeCommand`, and the recovery is dispatched BEFORE
      * any run/preflight machinery: a resume is a recovery of a PAST run, it
      * never creates a new invocation root. No run/chat flag is accepted or
      * validated here.
      */
    final case class Resume(argv: List[String]) extends Target

  /** Built-in agents: scout/worker = plain mock loops; coder = REAL workspace
    * tools (fs_read/fs_search/proc_run) for self-hosting runs.
    */
  val BuiltInAgents: Set[String] = Set("scout", "worker", "coder")

  // RAI-064: `--api-key` is deliberately ABSENT from the value flags — it is
  // intercepted in parseRun as a typed error (secrets must not be in argv).
  private val flagsWithValue =
    Set(
      "--bundle",
      "--workflow",
      "--agent",
      "--input",
      "--input-json",
      "--out",
      "--provider",
      "--base-url",
      "--model",
      "--workspace",
      "--context"
    )

  /** Parse `run` argv (the subcommand token included). */
  def parse(argv: List[String]): Either[RaiderError, CliArgs] =
    argv match
      case Nil => Left(RaiderError.Configuration("usage: raider run [options]"))
      case "run" :: rest  => parseRun(rest, Map.empty, mock = false)
      case "chat" :: rest => parseRun(rest, Map.empty, mock = false)
      // RAI-109: `raider replay ...` — recognized ADDITIVELY (run/chat parse
      // and errors unchanged); everything after the token is delegated verbatim
      // to the LIN-10 chain (see [[raider.cli.run.ReplayCommand]]).
      case replay @ ("replay" :: _) => Right(replayArgs(replay))
      // RAI-129: `raider resume <run-dir>` — recognized ADDITIVELY the same
      // way; the raw argv is delegated to [[raider.cli.run.ResumeCommand]].
      case resume @ ("resume" :: _) => Right(resumeArgs(resume))
      case other :: _ =>
        Left(
          RaiderError.Configuration(
            s"unknown subcommand '$other' (only 'run' exists in this slice)"
          )
        )

  /** The replay subcommand's [[CliArgs]] shell: NO run/chat flag surface —
    * every replay flag (and its usage errors) is validated later by the frozen
    * `ReplayCli.exec` on the raw argv, so the product entry keeps exact
    * flag/exit parity with the standalone LIN-10 entry. `out` is an inert
    * placeholder: a replay has no `work-<uuid>` invocation root.
    */
  private def replayArgs(fullArgv: List[String]): CliArgs =
    CliArgs(
      target = Target.Replay(fullArgv),
      input = None,
      inputJson = None,
      out = Paths.get(""),
      mock = false
    )

  /** The resume subcommand's [[CliArgs]] shell — the exact [[replayArgs]]
    * shape: NO run/chat flag surface, every resume argument (and its usage
    * errors) is validated by `ResumeCommand` on the raw argv. `out` is an inert
    * placeholder: a resume has no `work-<uuid>` invocation root.
    */
  private def resumeArgs(fullArgv: List[String]): CliArgs =
    CliArgs(
      target = Target.Resume(fullArgv),
      input = None,
      inputJson = None,
      out = Paths.get(""),
      mock = false
    )

  private def parseRun(
      rest: List[String],
      acc: Map[String, String],
      mock: Boolean
  ): Either[RaiderError, CliArgs] =
    rest match
      case Nil              => assemble(acc, mock)
      case "--mock" :: tail => parseRun(tail, acc, mock = true)
      // RAI-064: `--api-key` is a typed error, never parsed as a secret. The
      // message points at the env path and NEVER echoes the supplied value.
      case "--api-key" :: _ =>
        Left(
          RaiderError.Configuration(
            "--api-key is removed: secrets must not appear in argv/shell history; " +
              "set RAIDER_API_KEY (env) or RAIDER_API_KEY_CMD (helper) instead"
          )
        )
      case flag :: value :: tail if flagsWithValue.contains(flag) =>
        if acc.contains(flag) then
          Left(RaiderError.Configuration(s"$flag specified twice"))
        else parseRun(tail, acc.updated(flag, value), mock)
      case flag :: Nil if flagsWithValue.contains(flag) =>
        Left(RaiderError.Configuration(s"$flag requires a value"))
      case unknown :: _ =>
        Left(RaiderError.Configuration(s"unknown argument '$unknown'"))

  private def assemble(
      acc: Map[String, String],
      mock: Boolean
  ): Either[RaiderError, CliArgs] =
    val bundle = acc.get("--bundle")
    val workflow = acc.get("--workflow")
    val agent = acc.get("--agent")
    for
      out <- requireString(acc, "--out").map(Paths.get(_))
      input = acc.get("--input")
      inputJson = acc.get("--input-json").map(Paths.get(_))
      _ <- (input, inputJson) match
        case (Some(_), Some(_)) =>
          Left(
            RaiderError.Configuration(
              "--input and --input-json are mutually exclusive"
            )
          )
        case _ => Right(())
      provider <- acc.get("--provider") match
        case None => Right("mock")
        case Some(p) if p == "mock" || p == "openai" || p == "anthropic" =>
          Right(p)
        case Some(other) =>
          Left(
            RaiderError.Configuration(
              s"unknown --provider '$other' (mock|openai|anthropic)"
            )
          )
      baseUrl = acc.getOrElse("--base-url", "http://127.0.0.1:8081/v1")
      model = acc.getOrElse("--model", "local")
      // RAI-064: no apiKey here — resolved from env at runtime (Credentials).
      workspace = acc.get("--workspace").map(Paths.get(_))
      // RAI-086: exists-check at parse time — a missing context file is a
      // typed Configuration error (the path is echoed; paths are not secrets;
      // file CONTENTS are only read later by CiContext.parse in preflight).
      context <- acc.get("--context") match
        case None => Right(None)
        case Some(v) =>
          val p = Paths.get(v)
          if Files.exists(p) then Right(Some(p))
          else
            Left(
              RaiderError.Configuration(
                s"--context file does not exist: $v"
              )
            )
      target <- (agent, bundle, workflow) match
        case (Some(a), None, None) =>
          if BuiltInAgents.contains(a) then Right(Target.Agent(a))
          else
            Left(
              RaiderError.Configuration(
                s"unknown built-in agent '$a'; known: ${BuiltInAgents.toVector.sorted.mkString(", ")}"
              )
            )
        case (None, Some(jar), Some(wf)) =>
          Right(Target.Bundle(Paths.get(jar), wf))
        case (Some(_), _, _) =>
          Left(
            RaiderError.Configuration(
              "--agent is mutually exclusive with --bundle/--workflow"
            )
          )
        case (_, Some(_), None) =>
          Left(RaiderError.Configuration("--bundle requires --workflow"))
        case (_, None, Some(_)) =>
          Left(RaiderError.Configuration("--workflow requires --bundle"))
        case (None, None, None) =>
          Left(
            RaiderError.Configuration(
              "one of --agent or --bundle/--workflow is required"
            )
          )
    yield CliArgs(
      target,
      input,
      inputJson,
      out,
      mock,
      provider,
      baseUrl,
      model,
      workspace,
      context
    )

  private def requireString(
      acc: Map[String, String],
      flag: String
  ): Either[RaiderError, String] =
    acc.get(flag).filter(_.trim.nonEmpty) match
      case Some(v) => Right(v)
      case None    => Left(RaiderError.Configuration(s"$flag is required"))

end CliArgs
