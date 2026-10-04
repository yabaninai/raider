package raider.cli.run

import raider.core.RaiderError

import java.nio.file.{Path, Paths}

/** Headless CLI arguments (RAI-022 slice): pure parser — no shell, no stdin,
  * typed errors. Subcommand `run` only: run (--bundle <jar> --workflow <name> |
  * --agent <name>) (--input <text> | --input-json <file>) --out <dir> [--mock]
  * `--agent` and `--bundle/--workflow` are MUTUALLY exclusive (working-product
  * §3). `--mock` marks fixture allowance: live providers are NOT wired in this
  * slice, so the runner refuses without it.
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
    apiKey: String = "no-key",
    workspace: Option[Path] = None
):

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

  /** Built-in agents: scout/worker = plain mock loops; coder = REAL workspace
    * tools (fs_read/fs_search/proc_run) for self-hosting runs.
    */
  val BuiltInAgents: Set[String] = Set("scout", "worker", "coder")

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
      "--api-key",
      "--workspace"
    )

  /** Parse `run` argv (the subcommand token included). */
  def parse(argv: List[String]): Either[RaiderError, CliArgs] =
    argv match
      case Nil => Left(RaiderError.Configuration("usage: raider run [options]"))
      case "run" :: rest  => parseRun(rest, Map.empty, mock = false)
      case "chat" :: rest => parseRun(rest, Map.empty, mock = false)
      case other :: _ =>
        Left(
          RaiderError.Configuration(
            s"unknown subcommand '$other' (only 'run' exists in this slice)"
          )
        )

  private def parseRun(
      rest: List[String],
      acc: Map[String, String],
      mock: Boolean
  ): Either[RaiderError, CliArgs] =
    rest match
      case Nil              => assemble(acc, mock)
      case "--mock" :: tail => parseRun(tail, acc, mock = true)
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
        case None                                    => Right("mock")
        case Some(p) if p == "mock" || p == "openai" => Right(p)
        case Some(other) =>
          Left(
            RaiderError.Configuration(
              s"unknown --provider '$other' (mock|openai)"
            )
          )
      baseUrl = acc.getOrElse("--base-url", "http://127.0.0.1:8081/v1")
      model = acc.getOrElse("--model", "local")
      apiKey = acc.getOrElse("--api-key", "no-key")
      workspace = acc.get("--workspace").map(Paths.get(_))
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
      apiKey,
      workspace
    )

  private def requireString(
      acc: Map[String, String],
      flag: String
  ): Either[RaiderError, String] =
    acc.get(flag).filter(_.trim.nonEmpty) match
      case Some(v) => Right(v)
      case None    => Left(RaiderError.Configuration(s"$flag is required"))

end CliArgs
