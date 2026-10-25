package raider.repl.facade

import raider.core.{
  AttemptId,
  ModelBackend,
  ModelEvent,
  RaiderError,
  ToolRegistry
}
import raider.repl.ChatLayoutSink
import raider.runtime.budget.BudgetLimits
import zio.Unsafe

/** RAI-192 facade: switch the active chat session between providers WITHOUT a
  * REPL restart. The mock provider replays a scripted stub (no network); the
  * live providers construct the production backends exactly like the startup
  * prelude (`OpenAIChatStreamingBackend` / `AnthropicMessagesBackend` with
  * `RateLimitRetries`, coding tools, maxConcurrentTools=2, maxAttempts=48).
  * Construction dials nothing — the first live network byte still happens on
  * the next chat turn.
  *
  * Credential rules (unchanged policy): the key is resolved only through
  * [[raider.repl.LiveCredentials.resolve]] (env / env-cmd helper, injectable
  * `env` for tests), it never enters a status line — notices carry the masked
  * form at most — and a missing key or refused configuration fails closed to
  * "model unchanged" with a sanitized reason.
  */
object ModelCommands:

  /** Providers accepted by [[switch]] — exactly the startup prelude set. */
  val providers: Vector[String] = Vector("mock", "openai", "anthropic")

  /** Current binding summary (no credentials). Also surfaced as a chat status
    * line, like every other `:model` outcome.
    */
  def describe(): String =
    ReplCommands.currentSession match
      case None =>
        emit("no session installed yet (the REPL prelude has not run)")
      case Some(session) =>
        emit(
          s"provider ${providerOf(session.backend)} | model ${session.model} | " +
            "switch with :model <mock|openai|anthropic> [model] [baseUrl]"
        )

  /** Rebuild the session over a new backend and install it. Fails closed with a
    * human-readable refusal whenever anything is off (busy turn, unknown
    * provider, missing key, refused configuration); the old session stays
    * installed in that case.
    *
    * Limitation (documented, honest): interpreter-world code that already
    * captured the old session (the `given ReplSession`, AgentRefs, running
    * futures) keeps it; facade entry points that resolve the session
    * dynamically (`ReplCommands.currentSession`, `:chat` turns) pick up the new
    * one.
    */
  def switch(
      provider: String,
      model: String = "",
      baseUrl: String = "",
      env: Map[String, String] = sys.env
  ): String =
    val normalized = Option(provider).getOrElse("").trim.toLowerCase
    def refuse(message: String): String =
      emit(s"model switch refused | $message")
    normalized match
      case value if value.isEmpty =>
        refuse("usage: :model <mock|openai|anthropic> [model] [baseUrl]")
      case value if !providers.contains(value) =>
        refuse(
          s"unknown provider '${provider.trim}' " +
            s"(valid: ${providers.mkString(", ")})"
        )
      case value =>
        ReplCommands.currentSession match
          case None =>
            refuse("no session installed yet (the prelude has not run)")
          case Some(old) =>
            if ReplCommands.chatJobBusy then
              refuse("a chat turn is still running; :cancel first")
            else
              raider.repl.LiveCredentials.resolve(value, env = env) match
                case Left(_) =>
                  // Left from resolve is exactly the missing-key case; the
                  // guidance names only env vars, this refusal names only
                  // env vars too — and stays accurate for a switch context.
                  refuse(
                    s"no API key resolved for '$value' " +
                      s"(set ${raider.repl.LiveCredentials.EnvKey} or " +
                      s"${raider.repl.LiveCredentials.EnvHelper}); " +
                      s"model unchanged: ${old.model}"
                  )
                case Right(keyOpt) =>
                  val nextModel = resolveModel(value, model, env)
                  buildBackend(value, keyOpt.getOrElse(""), baseUrl, env) match
                    case Left(message) =>
                      refuse(s"$message model unchanged: ${old.model}")
                    case Right(backend) =>
                      wire(value, old, backend, nextModel) match
                        case Left(message) =>
                          refuse(s"$message model unchanged: ${old.model}")
                        case Right(status) => emit(status)

  // --- internals ---

  private def emit(text: String): String =
    ReplCommands.currentSession.flatMap(
      _.renderSink.collect { case chat: ChatLayoutSink => chat }
    ) match
      case Some(chat) => chat.note(text)
      case None       => ()
    text

  private def resolveModel(
      provider: String,
      model: String,
      env: Map[String, String]
  ): String =
    val explicit = Option(model).getOrElse("").trim
    if explicit.nonEmpty then explicit
    else if provider == "mock" then "scripted"
    else
      env.get("RAIDER_MODEL").map(_.trim).filter(_.nonEmpty).getOrElse("local")

  private def buildBackend(
      provider: String,
      key: String,
      baseUrl: String,
      env: Map[String, String]
  ): Either[String, ModelBackend] =
    provider match
      case "mock" =>
        val attempt = AttemptId("raider-mock-switch")
        Unsafe.unsafe { implicit u =>
          zio.Runtime.default.unsafe.run(
            raider.testkit.ScriptedModelBackend(
              ModelEvent.Started(attempt),
              ModelEvent.TextDelta(
                attempt,
                "[mock backend] scripted stub; no live network"
              ),
              ModelEvent.Finished(attempt, "stop")
            )
          ) match
            case zio.Exit.Success(backend) => Right(backend: ModelBackend)
            case _ => Left("mock backend construction failed")
        }
      case "openai" =>
        withKey(provider, key) {
          val url = resolveBaseUrl(baseUrl, env)
          val configured =
            raider.provider.chat.OpenAIChatBackend.Config
              .make(url, key, 300000L)
          configured match
            case Left(_) =>
              Left(s"openai wiring refused (invalid configuration for $url)")
            case Right(cfg) =>
              Unsafe.unsafe { implicit u =>
                zio.Runtime.default.unsafe.run(
                  raider.provider.chat.stream.OpenAIChatStreamingBackend
                    .make(Right(cfg))
                    .map(raider.runtime.display.RateLimitRetries(_))
                ) match
                  case zio.Exit.Success(backend) =>
                    Right(backend: ModelBackend)
                  case _ =>
                    Left(s"openai backend construction failed ($url)")
              }
        }
      case "anthropic" =>
        withKey(provider, key) {
          val url = resolveBaseUrl(baseUrl, env)
          val configured =
            raider.provider.anthropic.AnthropicMessagesBackend.Config
              .make(url, key, callTimeoutMs = 300000L)
          configured match
            case Left(_) =>
              Left(s"anthropic wiring refused (invalid configuration for $url)")
            case Right(cfg) =>
              Unsafe.unsafe { implicit u =>
                zio.Runtime.default.unsafe.run(
                  raider.provider.anthropic.AnthropicMessagesBackend
                    .make(Right(cfg), streaming = true)
                ) match
                  case zio.Exit.Success(backend) =>
                    Right(backend: ModelBackend)
                  case _ =>
                    Left(s"anthropic backend construction failed ($url)")
              }
        }
      case other => Left(s"unknown provider '$other'")

  /** Live providers fail closed without a key; mock never needs one. */
  private def withKey(provider: String, key: String)(
      build: => Either[String, ModelBackend]
  ): Either[String, ModelBackend] =
    if provider == "mock" then build
    else if key.isEmpty then
      Left(
        s"no API key resolved for '$provider' " +
          s"(set ${raider.repl.LiveCredentials.EnvKey} or " +
          s"${raider.repl.LiveCredentials.EnvHelper})"
      )
    else build

  private def resolveBaseUrl(
      baseUrl: String,
      env: Map[String, String]
  ): String =
    val explicit = Option(baseUrl).getOrElse("").trim
    if explicit.nonEmpty then explicit
    else
      env
        .get("RAIDER_BASE_URL")
        .map(_.trim)
        .filter(_.nonEmpty)
        .getOrElse("https://api.openai.com/v1")

  private def wire(
      provider: String,
      old: raider.repl.facade.ReplSession,
      backend: ModelBackend,
      model: String
  ): Either[String, String] =
    try
      val live = raider.repl.LiveCredentials.isLive(provider)
      val tools = Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe
          .run {
            if live then
              raider.tools.CodingToolset
                .make(java.nio.file.Paths.get("").toAbsolutePath.toString)
                .flatMap(ts => zio.ZIO.fromEither(ts.registry))
                .catchAll(_ => zio.ZIO.succeed(ToolRegistry.empty))
            else zio.ZIO.succeed(ToolRegistry.empty)
          }
          .getOrThrow()
      }
      val limits =
        if live then BudgetLimits.make(maxConcurrentTools = 2, maxAttempts = 48)
        else BudgetLimits.make()
      val session = Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe
          .run(
            ReplSession.make(
              backend,
              limits,
              tools,
              model = model,
              echoStream = true,
              renderSink = old.renderSink,
              toolApproval = old.toolApproval
            )
          )
          .getOrThrow()
      }
      ReplCommands.install(session)
      val family =
        if !live then "scripted replay (no live network)"
        else if provider == "openai" then "OpenAI-compatible streaming"
        else "Anthropic Messages streaming"
      Right(
        s"model switched: $provider/$model ($family); next :chat uses it"
      )
    catch
      case error: RaiderError =>
        // Sanitized by contract: provider bodies/secrets never enter detail.
        Left(s"session construction refused (${error.code})")
      case _: Throwable =>
        Left("session construction failed")

  private def providerOf(backend: ModelBackend): String =
    backend match
      case raider.runtime.display.RateLimitRetries(inner, _, _, _) =>
        providerOf(inner)
      case other =>
        other.getClass.getName.toLowerCase match
          case value if value.contains("openai")    => "openai"
          case value if value.contains("anthropic") => "anthropic"
          case value if value.contains("scripted")  => "mock"
          case _                                    => "custom"

end ModelCommands
