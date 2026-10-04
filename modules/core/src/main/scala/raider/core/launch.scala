package raider.core

/** Explicit launch capability (runtime-contracts §3): "Launch type signatures
  * must have `using ReplSession` or an equivalent explicit capability,
  * established by the prelude".
  *
  * Core defines only the capability CONTRACT and the explicit definition
  * context. There is deliberately no global launch hook here: importing this
  * package grants nothing, constructing Programs/Tasks grants nothing — only a
  * capability instance established by the REPL prelude or the headless loader
  * can sit in a launch signature's `using` position. The concrete session
  * (ReplSession) lives in the repl module and satisfies this trait.
  */
object launch:

  trait LaunchCapability:
    def sessionId: SessionId

  /** Explicit, immutable definition registry snapshot handed to the REPL/loader
    * (RAI-002: "DefinitionContext is explicitly provided by the REPL/ loader;
    * no global launch in imports"). Lookup of an unregistered name is a
    * structured error naming the known entries (§11), never a prompt-side guess
    * or silent miss.
    */
  final case class DefinitionContext(
      capability: LaunchCapability,
      agents: List[String],
      workflows: List[String]
  )

  object DefinitionContext:

    def validate(ctx: DefinitionContext): Either[RaiderError, Unit] =
      val dupAgents = ctx.agents
        .groupBy(identity)
        .collect { case (n, ns) if ns.size > 1 => n }
        .toList
        .sorted
      val dupWorkflows = ctx.workflows
        .groupBy(identity)
        .collect { case (n, ns) if ns.size > 1 => n }
        .toList
        .sorted
      if ctx.agents.exists(_.trim.isEmpty) || ctx.workflows.exists(
          _.trim.isEmpty
        )
      then
        Left(
          RaiderError.InputValidation("agent/workflow names must be non-empty")
        )
      else if dupAgents.nonEmpty then
        Left(
          RaiderError.InputValidation(
            s"duplicate agent names: ${dupAgents.mkString(", ")}"
          )
        )
      else if dupWorkflows.nonEmpty then
        Left(
          RaiderError.InputValidation(
            s"duplicate workflow names: ${dupWorkflows.mkString(", ")}"
          )
        )
      else Right(())

    /** Registered agent name; unknown → structured error with known names. */
    def requireAgent(
        ctx: DefinitionContext,
        name: String
    ): Either[RaiderError, String] =
      if ctx.agents.contains(name) then Right(name)
      else
        Left(
          RaiderError.InputValidation(
            s"unknown agent '$name'; registered: ${ctx.agents.sorted.mkString(", ")}"
          )
        )

    /** Registered workflow name; unknown → structured error with known names.
      */
    def requireWorkflow(
        ctx: DefinitionContext,
        name: String
    ): Either[RaiderError, String] =
      if ctx.workflows.contains(name) then Right(name)
      else
        Left(
          RaiderError.InputValidation(
            s"unknown workflow '$name'; registered: ${ctx.workflows.sorted.mkString(", ")}"
          )
        )

end launch
