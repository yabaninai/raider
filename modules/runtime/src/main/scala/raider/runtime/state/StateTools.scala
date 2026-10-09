package raider.runtime.state

import raider.core.{
  JobId,
  RaiderError,
  RecoveryClass,
  RootId,
  Tool,
  ToolCapabilities
}
import zio.json.{DecoderOps, DeriveJsonDecoder, EncoderOps, JsonDecoder}
import zio.{Scope => ZioScope, ZIO}

/** Opt-in agent state tools (m1.md RAI-085, roadmap-12k P5.B): the four
  * [[StateStore]] operations exposed behind the frozen `raider.core.Tool` JSON
  * contract so a model-driven loop can keep scratch state between turns.
  *
  *   - Opt-in law: the tools exist ONLY where a caller passes a [[StateStore]]
  *     to [[StateTools.store]] — no store, no tools (`List.empty`); assembling
  *     them into a real registry stays the caller's decision (wiring into
  *     CodingToolset is a separate card).
  *   - Scoping: every tool defaults to the Agent scope of the bound `agentJob`;
  *     `Root` and `Global` are reachable ONLY through the explicit `scope`
  *     argument (`"root:<rootId>"` / `"global"`) and are echoed in every
  *     result. Nothing here resolves Global by default.
  *   - Errors: refusals the model can correct (bad args, scope violations,
  *     quota refusals, CAS conflicts) come back as structured single-line JSON
  *     `{"error","detail"}` feedback (the CodingToolset discipline, §8/§10) —
  *     the run continues and the model can retry with a smaller value or the
  *     actual revision. Crash-flavored failures (corrupt/IO journal errors)
  *     stay typed `RaiderError`s.
  *
  * All outputs are single-line JSON; `state_get` also reports the key's
  * revision so a model can drive the CAS discipline through tools alone
  * (revision is read in a second transaction — `putIfRevision` remains the
  * single atomic check-and-write authority).
  */
object StateTools:

  /** The four state tools bound to `store`, defaulting to Agent(`agentJob`). */
  def store(st: StateStore, agentJob: JobId): List[Tool] =
    val bound = new StateTools(st, agentJob)
    List(bound.stateGet, bound.statePut, bound.stateDelete, bound.stateKeys)

  /** Absent-store overload: `None` is observationally identical to the feature
    * not existing (opt-in law, roadmap P5 exit).
    */
  def store(stOpt: Option[StateStore], agentJob: JobId): List[Tool] =
    stOpt.fold(List.empty[Tool])(st => store(st, agentJob))

  // ---- argument wires (CodingToolset layout; missing optional → None) ----

  private final case class GetArgs(key: String, scope: Option[String])

  private object GetArgs:
    given JsonDecoder[GetArgs] = DeriveJsonDecoder.gen[GetArgs]

  private final case class PutArgs(
      key: String,
      value: String,
      scope: Option[String],
      expectedRev: Option[Long]
  )

  private object PutArgs:
    given JsonDecoder[PutArgs] = DeriveJsonDecoder.gen[PutArgs]

  private final case class DeleteArgs(key: String, scope: Option[String])

  private object DeleteArgs:
    given JsonDecoder[DeleteArgs] = DeriveJsonDecoder.gen[DeleteArgs]

  private final case class KeysArgs(scope: Option[String])

  private object KeysArgs:
    given JsonDecoder[KeysArgs] = DeriveJsonDecoder.gen[KeysArgs]

  // ---- argument schemas (single-line JSON, sent to the model). Object-level
  // vals: they are fully initialized before any tool instance is built. ------

  private val StateGetSchema: String =
    "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\",\"description\":\"state key\"},\"scope\":{\"type\":\"string\",\"description\":\"omit or agent = this agent's private scope (default); root:<rootId> = shared run blackboard; global = explicit cross-run\"}},\"required\":[\"key\"],\"additionalProperties\":false}"

  private val StatePutSchema: String =
    "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\",\"description\":\"state key\"},\"value\":{\"type\":\"string\",\"description\":\"value to store\"},\"scope\":{\"type\":\"string\",\"description\":\"omit or agent = this agent's private scope (default); root:<rootId> = shared run blackboard; global = explicit cross-run\"},\"expectedRev\":{\"type\":\"integer\",\"description\":\"compare-and-swap: commit only when the key's current revision equals this; omit for unconditional write\"}},\"required\":[\"key\",\"value\"],\"additionalProperties\":false}"

  private val StateDeleteSchema: String =
    "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\",\"description\":\"state key\"},\"scope\":{\"type\":\"string\",\"description\":\"omit or agent = this agent's private scope (default); root:<rootId> = shared run blackboard; global = explicit cross-run\"}},\"required\":[\"key\"],\"additionalProperties\":false}"

  private val StateKeysSchema: String =
    "{\"type\":\"object\",\"properties\":{\"scope\":{\"type\":\"string\",\"description\":\"omit or agent = this agent's private scope (default); root:<rootId> = shared run blackboard; global = explicit cross-run\"}},\"required\":[],\"additionalProperties\":false}"

end StateTools

/** Tools bound to one store + one default agent job (companion assembles). */
private final class StateTools(store: StateStore, agentJob: JobId):
  import StateTools.*

  // ---- tools --------------------------------------------------------------

  protected val stateGet: Tool = toolOf(
    "state_get",
    "Read one key from agent state (default: this agent's private scope); returns found/value/revision.",
    StateGetSchema,
    RecoveryClass.ReadOnly,
    concurrentSafe = true,
    getInvoke
  )

  protected val statePut: Tool = toolOf(
    "state_put",
    "Write a value to agent state; with expectedRev performs compare-and-swap (conflict is structured feedback, not a crash).",
    StatePutSchema,
    RecoveryClass.Mutating,
    concurrentSafe = false,
    putInvoke
  )

  protected val stateDelete: Tool = toolOf(
    "state_delete",
    "Remove a key from agent state (default: this agent's private scope).",
    StateDeleteSchema,
    RecoveryClass.Idempotent,
    concurrentSafe = false,
    deleteInvoke
  )

  protected val stateKeys: Tool = toolOf(
    "state_keys",
    "List keys in a state scope (default: this agent's private scope).",
    StateKeysSchema,
    RecoveryClass.ReadOnly,
    concurrentSafe = true,
    keysInvoke
  )

  private def toolOf(
      toolName: String,
      descr: String,
      schema: String,
      recoveryClass: RecoveryClass,
      concurrentSafe: Boolean,
      run: String => ZIO[ZioScope, RaiderError, String]
  ): Tool =
    new Tool:
      def name = toolName
      def version = 1
      def description = descr
      def recovery = recoveryClass
      def timeoutMs = 300000L
      def capabilities = ToolCapabilities(concurrentSafe)
      override def parametersJsonSchema = schema
      def invoke(argumentsJson: String): ZIO[ZioScope, RaiderError, String] =
        // Model-correctable refusals are structured feedback to the model
        // (§8/§10) — the run continues. Crash-flavored failures stay typed.
        run(argumentsJson).either.map {
          case Right(output) => output
          case Left(e) if isModelFeedback(e) =>
            s"""{"error":${e.code.toJson},"detail":${e.detail.toJson}}"""
          case Left(e) => throw e
        }

      private def isModelFeedback(e: RaiderError): Boolean = e match
        case _: RaiderError.InputValidation  => true // bad args, ScopeViolation
        case _: RaiderError.OutputValidation => true
        case _: RaiderError.LocalBudgetExceeded   => true // QuotaExceeded
        case _: RaiderError.CapabilityUnsupported => true // RevisionConflict
        case _                                    => false

  // ---- scope argument (explicit-Global-only rule) -------------------------

  private def scopeOf(raw: Option[String]): Either[String, Scope] =
    raw.map(_.trim).filter(_.nonEmpty) match
      case None | Some("agent") => Right(Scope.Agent(agentJob))
      case Some("global")       => Right(Scope.Global)
      case Some(s) if s.startsWith("root:") =>
        val id = s.stripPrefix("root:")
        if id.isEmpty then Left("empty root id — use \"root:<rootId>\"")
        else Right(Scope.Root(RootId(id)))
      case Some(other) =>
        Left(
          s"unknown scope '$other' — omit for this agent's private scope, or use \"root:<rootId>\" / \"global\""
        )

  private def withScope(
      tool: String,
      raw: Option[String]
  )(
      run: Scope => ZIO[Any, StateError, String]
  ): ZIO[ZioScope, RaiderError, String] =
    scopeOf(raw) match
      case Left(msg) =>
        ZIO.fail(RaiderError.InputValidation(s"$tool scope: $msg"))
      case Right(scope) => run(scope).mapError(_.toRaiderError)

  // ---- invocations --------------------------------------------------------

  private def getInvoke(
      argumentsJson: String
  ): ZIO[ZioScope, RaiderError, String] =
    argumentsJson.fromJson[GetArgs] match
      case Left(err) =>
        ZIO.fail(
          RaiderError.InputValidation(s"state_get args: ${err.take(120)}")
        )
      case Right(a) =>
        withScope("state_get", a.scope) { scope =>
          for
            value <- store.get(scope, a.key)
            rev <- store.revision(scope, a.key)
          yield value match
            case Some(v) =>
              s"""{"found":true,"scope":${scope.describe.toJson},""" +
                s""""key":${a.key.toJson},"value":${v.toJson},"revision":${rev
                    .getOrElse(0L)}}"""
            case None =>
              s"""{"found":false,"scope":${scope.describe.toJson},"key":${a.key.toJson}}"""
        }

  private def putInvoke(
      argumentsJson: String
  ): ZIO[ZioScope, RaiderError, String] =
    argumentsJson.fromJson[PutArgs] match
      case Left(err) =>
        ZIO.fail(
          RaiderError.InputValidation(s"state_put args: ${err.take(120)}")
        )
      case Right(a) =>
        withScope("state_put", a.scope) { scope =>
          val write = a.expectedRev.fold(
            store.put(scope, a.key, a.value)
          )(rev => store.putIfRevision(scope, a.key, Some(rev), a.value))
          write.map { rev =>
            s"""{"ok":true,"scope":${scope.describe.toJson},""" +
              s""""key":${a.key.toJson},"revision":$rev}"""
          }
        }

  private def deleteInvoke(
      argumentsJson: String
  ): ZIO[ZioScope, RaiderError, String] =
    argumentsJson.fromJson[DeleteArgs] match
      case Left(err) =>
        ZIO.fail(
          RaiderError.InputValidation(s"state_delete args: ${err.take(120)}")
        )
      case Right(a) =>
        withScope("state_delete", a.scope) { scope =>
          store.delete(scope, a.key).map { removed =>
            s"""{"deleted":$removed,"scope":${scope.describe.toJson},""" +
              s""""key":${a.key.toJson}}"""
          }
        }

  private def keysInvoke(
      argumentsJson: String
  ): ZIO[ZioScope, RaiderError, String] =
    argumentsJson.fromJson[KeysArgs] match
      case Left(err) =>
        ZIO.fail(
          RaiderError.InputValidation(s"state_keys args: ${err.take(120)}")
        )
      case Right(a) =>
        withScope("state_keys", a.scope) { scope =>
          store.keys(scope).map { ks =>
            val items = ks.map(k => k.toJson).mkString("[", ",", "]")
            s"""{"keys":$items,"count":${ks.size},"scope":${scope.describe.toJson}}"""
          }
        }

end StateTools
