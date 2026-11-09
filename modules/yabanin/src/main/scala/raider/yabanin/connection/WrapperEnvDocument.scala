package raider.yabanin.connection

import raider.yabanin.labels.LabelLimits
import raider.yabanin.labels.LabelSetDocument
import zio.Chunk
import zio.json.JsonEncoder
import zio.json.ast.Json

/** Wire document for the effective wrapper context
  * (`contracts/yabanin/wrapper-env.schema.json`).
  *
  * The encoder is hand-written over the zio-json AST so the emitted field set
  * and order match the schema exactly. Required `null`s (`gateway_url`) stay
  * explicit; `child_env` is schema-optional and is emitted only when the caller
  * provides a built child environment.
  */
object WrapperEnvDocument:

  /** Payload: the resolved effective context, the label limits it was resolved
    * under, and optionally the child-only environment record.
    */
  final case class Document(
      ctx: EffectiveContext,
      limits: LabelLimits,
      childEnv: Option[ChildEnv.ChildEnvironment]
  )

  given JsonEncoder[Document] =
    JsonEncoder[Json].contramap(d => toAst(d.ctx, d.limits, d.childEnv))

  /** Builds the document AST. Field order matches the schema property order:
    * gateway_url, credential, labels, owner, problems, [child_env].
    */
  def toAst(
      ctx: EffectiveContext,
      limits: LabelLimits,
      childEnv: Option[ChildEnv.ChildEnvironment]
  ): Json =
    val fields = Chunk[(String, Json)](
      "gateway_url" -> (ctx.gatewayUrl match
        case Some(url) => Json.Str(url)
        case None      => Json.Null),
      "credential" -> Json.Obj(
        "kind" -> Json.Str(ctx.credential.kind),
        "reference" -> Json.Str(ctx.credential.reference),
        "value_present" -> Json.Bool(ctx.credential.valuePresent)
      ),
      "labels" -> LabelSetDocument.toAst(ctx.labels, limits),
      "owner" -> Json.Str(ctx.owner.encode),
      "problems" -> Json.Arr(Chunk.fromIterable(ctx.problems.map(problemAst)))
    )
    // `child_env` is schema-optional: present only when built. Keys are
    // environment variable names; values are the exact strings passed to the
    // child (credential values only under the allowlisted variable name).
    val withChild = childEnv match
      case Some(env) => fields :+ ("child_env" -> childEnvAst(env))
      case None      => fields
    Json.Obj(withChild)

  private def problemAst(problem: ContextProblem): Json =
    Json.Obj(
      "name" -> Json.Str(problem.name.encode),
      "detail" -> Json.Str(problem.detail)
    )

  private def childEnvAst(env: ChildEnv.ChildEnvironment): Json =
    Json.Obj(Chunk.fromIterable(env.entries.map { case (name, value) =>
      name -> Json.Str(value)
    }))
