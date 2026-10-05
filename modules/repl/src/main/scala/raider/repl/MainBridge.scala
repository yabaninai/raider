package raider.repl

import raider.core.{ModelBackend, ToolRegistry}

/** Bridge across the classloader split: ThreadLocal variables are JVM-wide
  * (same thread = same value regardless of which classloader reads them),
  * unlike object statics which exist once per classloader world.
  */
object MainBridge:
  private val backendRef = new ThreadLocal[ModelBackend]
  private val toolsRef = new ThreadLocal[ToolRegistry]
  private val modelRef = new ThreadLocal[String]

  def setBackend(b: ModelBackend): Unit = backendRef.set(b)
  def setTools(t: ToolRegistry): Unit = toolsRef.set(t)

  /** Called from the INTERPRETER world — ThreadLocal is shared. */
  def backend: ModelBackend = backendRef.get()

  def tools: ToolRegistry =
    Option(toolsRef.get()).getOrElse(ToolRegistry.empty)

  /** Configured live model (RAIDER_MODEL); "scripted" when unset. */
  def liveModel: String = Option(modelRef.get()).getOrElse("scripted")

  def setupLive(provider: String, baseUrl: String): Unit =
    val model = Option(System.getenv("RAIDER_MODEL")).getOrElse("local")
    modelRef.set(model)
    if provider == "openai" then
      try
        val config = raider.provider.chat.OpenAIChatBackend.Config
          .make(baseUrl, "no-key", callTimeoutMs = 300000L)
        config match
          case Right(cfg) =>
            zio.Unsafe.unsafe { implicit u =>
              zio.Runtime.default.unsafe.run(
                // STREAMING wire (Phase 3.1/3.3): REPL sees deltas live;
                // 429s back off exponentially (Phase 4.2)
                raider.provider.chat.stream.OpenAIChatStreamingBackend
                  .make(Right(cfg))
                  .map(raider.runtime.display.RateLimitRetries(_))
              ) match
                case zio.Exit.Success(b) => backendRef.set(b)
                case _                   => ()
            }
          case Left(_) => ()
      catch case _: Exception => ()
      try
        zio.Unsafe.unsafe { implicit u =>
          zio.Runtime.default.unsafe.run(
            raider.tools.CodingToolset
              .make(java.nio.file.Paths.get("").toAbsolutePath.toString)
              .flatMap(ts => zio.ZIO.fromEither(ts.registry))
          ) match
            case zio.Exit.Success(r) => toolsRef.set(r)
            case _                   => ()
        }
      catch case _: Exception => ()

end MainBridge
