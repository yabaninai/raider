package raider.repl

import raider.core.{ModelBackend, ToolRegistry}

/** Bridge across the classloader split: ThreadLocal variables are JVM-wide
  * (same thread = same value regardless of which classloader reads them),
  * unlike object statics which exist once per classloader world.
  */
object MainBridge:
  private val backendRef = new ThreadLocal[ModelBackend]
  private val toolsRef = new ThreadLocal[ToolRegistry]

  def setBackend(b: ModelBackend): Unit = backendRef.set(b)
  def setTools(t: ToolRegistry): Unit = toolsRef.set(t)

  /** Called from the INTERPRETER world — ThreadLocal is shared. */
  def backend: ModelBackend = backendRef.get()

  def tools: ToolRegistry =
    Option(toolsRef.get()).getOrElse(ToolRegistry.empty)

  def setupLive(provider: String, baseUrl: String): Unit =
    if provider == "openai" then
      try
        val config = raider.provider.chat.OpenAIChatBackend.Config
          .make(baseUrl, "no-key", callTimeoutMs = 300000L)
        config match
          case Right(cfg) =>
            zio.Unsafe.unsafe { implicit u =>
              zio.Runtime.default.unsafe.run(
                raider.provider.chat.OpenAIChatBackend.make(Right(cfg))
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
