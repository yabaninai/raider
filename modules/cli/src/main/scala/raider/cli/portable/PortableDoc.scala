package raider.cli.portable

import raider.core.RaiderError
import raider.dsl.portable.PortableDiagnostic
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

/** Shared portable-document surface for the ADR-036 CLI routes (RAI-199): typed
  * byte reading, the frozen validator entry point and the diagnostic rendering
  * shared by `check`, `explain` and the `run --doc` launch route.
  *
  * Everything here is ZERO-DISPATCH by construction: reading and validating a
  * document never constructs a backend, an admission or a trusted program.
  */
object PortableDoc:

  val Format = "raider.portable-workflow"

  def sha256Hex(bytes: Array[Byte]): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  def readBytes(path: Path): Either[RaiderError, Array[Byte]] =
    try
      if !Files.isRegularFile(path) then
        Left(
          RaiderError.InputValidation(
            s"document is not a readable regular file: $path"
          )
        )
      else Right(Files.readAllBytes(path))
    catch
      case NonFatal(t) =>
        Left(
          RaiderError.InputValidation(
            s"cannot read document $path: ${String.valueOf(t)}"
          )
        )

  /** The frozen v1.0 validation (RAI-198 corpus discipline): bounds, schema and
    * structural preflight. Never dispatches; never resolves authority.
    */
  def validate(bytes: Array[Byte]): Either[PortableDiagnostic, Json] =
    raider.dsl.portable.PortableWorkflowValidator.validate(bytes)

  /** Renders one diagnostic as a closed JSON object (machine-checkable stdout
    * for the zero-dispatch routes; identifiers and paths stay verbatim).
    */
  def diagnosticJson(diag: PortableDiagnostic): Json =
    def opt(value: Option[String]): Json = value match
      case Some(v) => Json.Str(v)
      case None    => Json.Null
    Json.Obj(
      "code" -> Json.Str(diag.code),
      "documentPath" -> Json.Str(diag.documentPath),
      "stepId" -> opt(diag.stepId),
      "offendingRef" -> opt(diag.offendingRef),
      "suggestedCorrection" -> Json.Str(diag.suggestedCorrection)
    )

  /** One-line human rendering for stderr (WorkflowRun message discipline). */
  def diagnosticLine(diag: PortableDiagnostic): String =
    val at =
      if diag.documentPath.isEmpty then ""
      else s" at ${diag.documentPath}"
    val step = diag.stepId.fold("")(id => s" (step '$id')")
    s"${diag.code}$at$step: ${diag.suggestedCorrection}"

  /** Extracts the five declared root-limit integers from a VALIDATED document
    * (the frozen schema guarantees their presence and ranges). This is the
    * launch route's read-side twin of the lowerer's metadata derivation; the
    * lowerer re-derives the same values into `PortableLaunchMetadata`.
    */
  def declaredLimits(document: Json): Either[RaiderError, DeclaredLimits] =
    document match
      case obj: Json.Obj =>
        def intOf(name: String): Either[RaiderError, Long] =
          obj.fields
            .collectFirst { case ("limits", limits: Json.Obj) => limits }
            .flatMap(_.fields.collectFirst { case (`name`, Json.Num(value)) =>
              value.longValueExact()
            })
            .toRight(
              RaiderError.InputValidation(
                s"validated document is missing limits.$name"
              )
            )
        for
          attempts <- intOf("attempts")
          parallelism <- intOf("parallelism")
          deadlineMs <- intOf("deadlineMs")
          tokens <- intOf("tokens")
          children <- intOf("children")
        yield DeclaredLimits(
          attempts.toInt,
          parallelism.toInt,
          deadlineMs,
          tokens,
          children.toInt
        )
      case _ =>
        Left(RaiderError.InputValidation("document is not a JSON object"))

  final case class DeclaredLimits(
      attempts: Int,
      parallelism: Int,
      deadlineMs: Long,
      tokens: Long,
      children: Int
  )

  /** Reads the output-target declaration of a VALIDATED document with the
    * lowerer's own precedence (bindings first, then verdict) so the launch
    * route can project the trusted verdict honestly. `None` = no target.
    */
  def outputTarget(document: Json): Option[OutputTarget] =
    def pointerOf(obj: Json.Obj, key: String): Option[String] =
      obj.fields.collectFirst { case (`key`, Json.Str(p)) => p }
    document match
      case obj: Json.Obj =>
        val outputs = obj.fields.collectFirst {
          case ("outputs", out: Json.Obj) => out
        }
        val bindings = outputs.flatMap(
          _.fields.collectFirst { case ("bindings", b: Json.Obj) => b }
        )
        val verdict = outputs.flatMap(
          _.fields.collectFirst { case ("verdict", v: Json.Obj) => v }
        )
        val verdictFrom = verdict.flatMap(v => pointerOf(v, "from"))
        val verdictAuthority = verdict.flatMap(v => pointerOf(v, "authority"))
        bindings
          .flatMap(_.fields.headOption)
          .collect { case (name, Json.Str(p)) =>
            OutputTarget(p, isVerdict = false, Some(name))
          }
          .orElse {
            verdictFrom.map { p =>
              OutputTarget(
                p,
                isVerdict = verdictAuthority.contains("trustedVerifier"),
                None
              )
            }
          }
      case _ => None

  final case class OutputTarget(
      pointer: String,
      isVerdict: Boolean,
      bindingName: Option[String]
  )

end PortableDoc
