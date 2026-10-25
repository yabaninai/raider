package raider.cli.portable

import raider.cli.run.HeadlessRunner
import raider.core.RaiderError
import zio.json.ast.Json
import zio.json.*

import java.nio.file.Paths

/** `raider workflow explain --doc <file>` (RAI-199; ADR-036): the ZERO-DISPATCH
  * structural outline. Validates, then prints the step tree, the declared root
  * limits, the output target and — crucially — the REQUIRED TRUSTED REFERENCES
  * the document would need a launcher to pin (profiles, checks with their
  * pinned snapshots, safe operations, checkpoints).
  *
  * Explain never RESOLVES authority: a green explain does not confirm that any
  * launcher can or will run this document; it names what would have to be
  * pinned by the invocation owner. No backend, no admission, no registry.
  */
object PortableExplain:

  def command(argv: List[String]): Int =
    parse(argv) match
      case Left(err) =>
        System.err.println(s"raider ${err.code}: ${err.detail}")
        HeadlessRunner.exitCodeFor(err)
      case Right(doc) =>
        PortableDoc.readBytes(doc) match
          case Left(err) =>
            // IO-level failure: typed RA error, diagnostic channel mirrors
            // the validator's closed shape so consumers parse ONE format.
            System.err.println(s"raider ${err.code}: ${err.detail}")
            System.out.println(
              s"""{"explainable":false,"diagnostic":${ioDiagnostic(
                  doc,
                  err
                ).toJson}}"""
            )
            System.out.flush()
            HeadlessRunner.exitCodeFor(err)
          case Right(bytes) =>
            PortableDoc.validate(bytes) match
              case Left(diag) =>
                System.err.println(
                  s"raider RA-INP: ${PortableDoc.diagnosticLine(diag)}"
                )
                System.out.println(
                  s"""{"explainable":false,"diagnostic":${PortableDoc
                      .diagnosticJson(diag)
                      .toJson}}"""
                )
                System.out.flush()
                raider.core.result.ExitCodes.ConfigInvalid
              case Right(document) =>
                outline(document) match
                  case Left(err) =>
                    System.err.println(s"raider ${err.code}: ${err.detail}")
                    raider.core.result.ExitCodes.ConfigInvalid
                  case Right(outlineJson) =>
                    System.out.println(outlineJson.toJson)
                    System.out.flush()
                    raider.core.result.ExitCodes.Success

  /** The diagnostic shape for IO-level RA failures (same closed fields as the
    * validator's diagnostics; `suggestedCorrection` carries the detail).
    */
  private def ioDiagnostic(
      doc: java.nio.file.Path,
      err: RaiderError
  ): Json =
    Json.Obj(
      "code" -> Json.Str(err.code),
      "documentPath" -> Json.Str(doc.toString),
      "stepId" -> Json.Null,
      "offendingRef" -> Json.Null,
      "suggestedCorrection" -> Json.Str(err.detail)
    )

  private def parse(
      argv: List[String]
  ): Either[RaiderError, java.nio.file.Path] =
    argv match
      case "--doc" :: value :: Nil if value.trim.nonEmpty =>
        Right(Paths.get(value))
      case Nil =>
        Left(RaiderError.Configuration("explain requires --doc <file>"))
      case "--doc" :: _ =>
        Left(RaiderError.Configuration("--doc requires a value"))
      case unknown :: _ =>
        Left(
          RaiderError.Configuration(
            s"unknown argument '$unknown' (usage: raider workflow explain --doc <file>)"
          )
        )

  // ------------------------------------------------------------------//
  // Structural walk (mirror of the lowerer's composition order — the     //
  // outline a run WOULD compose — without resolving any of it).          //
  // ------------------------------------------------------------------//

  private def outline(document: Json): Either[RaiderError, Json] =
    val obj = document.asInstanceOf[Json.Obj]
    val steps = obj.fields
      .collectFirst { case ("steps", Json.Arr(values)) => values.toList }
      .getOrElse(Nil)
      .collect { case step: Json.Obj => step }
    val byId = steps
      .flatMap(step =>
        step.fields
          .collectFirst { case ("id", Json.Str(id)) => id }
          .map(_ -> step)
      )
      .toMap

    def strOf(step: Json.Obj, key: String): Option[String] =
      step.fields.collectFirst { case (`key`, Json.Str(v)) => v }

    def childNodes(
        refs: List[String],
        id: String,
        active: Set[String]
    ): Either[RaiderError, List[Json]] =
      refs.foldLeft[Either[RaiderError, List[Json]]](Right(Nil)) {
        (acc, child) =>
          acc.flatMap(nodes => nodeOf(child, active + id).map(nodes :+ _))
      }

    def nodeOf(id: String, active: Set[String]): Either[RaiderError, Json] =
      if active(id) then
        Left(RaiderError.InputValidation(s"cycle through step '$id'"))
      else
        byId
          .get(id)
          .toRight(
            RaiderError.InputValidation(s"missing step reference '$id'")
          )
          .flatMap { step =>
            val kind = strOf(step, "kind").getOrElse("")
            val extras: Either[RaiderError, List[(String, Json)]] = kind match
              case "agent" =>
                Right(
                  List(
                    "profile" -> Json.Str(strOf(step, "profile").getOrElse("")),
                    "toolset" -> Json.Str(
                      strOf(step, "toolset").getOrElse("")
                    )
                  )
                )
              case "trustedCheck" =>
                Right(
                  List(
                    "checkId" -> Json.Str(strOf(step, "checkId").getOrElse("")),
                    "registrySnapshot" -> Json.Str(
                      strOf(step, "registrySnapshot").getOrElse("")
                    )
                  )
                )
              case "sequence" | "map" | "join" =>
                val refs = step.fields
                  .collectFirst { case ("steps", Json.Arr(values)) =>
                    values.toList.collect { case Json.Str(child) => child }
                  }
                  .orElse(strOf(step, "templateStep").map(List(_)))
                  .getOrElse(Nil)
                childNodes(refs, id, active)
                  .map(children => List("children" -> Json.Arr(children*)))
              case "conditional" =>
                val branches =
                  List(strOf(step, "then"), strOf(step, "else")).flatten
                childNodes(branches, id, active)
                  .map(children => List("children" -> Json.Arr(children*)))
              case "repair" =>
                Right(
                  List(
                    "safeOperationRef" -> Json.Str(
                      strOf(step, "safeOperationRef").getOrElse("")
                    )
                  )
                )
              case "checkpoint" =>
                Right(
                  List(
                    "checkpointRef" -> Json.Str(
                      strOf(step, "checkpointRef").getOrElse("")
                    )
                  )
                )
              case other =>
                Left(
                  RaiderError.InputValidation(
                    s"unsupported step kind '$other'"
                  )
                )
            extras.map(ex =>
              Json.Obj(
                (List("id" -> Json.Str(id), "kind" -> Json.Str(kind)) ++ ex)*
              )
            )
          }

    // Root target with the lowerer's precedence: bindings first, then verdict.
    val rootId =
      PortableDoc.outputTarget(document).map(_.pointer).flatMap { pointer =>
        val prefix = "#/steps/"
        if pointer.startsWith(prefix) then
          Some(pointer.stripPrefix(prefix).takeWhile(_ != '/'))
        else None
      }

    val structure: Either[RaiderError, Option[Json]] = rootId match
      case Some(id) => nodeOf(id, Set.empty).map(Some(_))
      case None     => Right(None)

    // Required trusted references are collected from EVERY step regardless of
    // reachability: explain names all authority the document declares.
    val profiles = steps
      .filter(step => strOf(step, "kind").contains("agent"))
      .flatMap(strOf(_, "profile"))
      .distinct
      .sorted
    val checks = steps
      .filter(step => strOf(step, "kind").contains("trustedCheck"))
      .map(step =>
        Json.Obj(
          "checkId" -> Json.Str(strOf(step, "checkId").getOrElse("")),
          "registrySnapshot" -> Json.Str(
            strOf(step, "registrySnapshot").getOrElse("")
          )
        )
      )
    val safeOps = steps
      .filter(step => strOf(step, "kind").contains("repair"))
      .flatMap(strOf(_, "safeOperationRef"))
      .distinct
    val checkpoints = steps
      .filter(step => strOf(step, "kind").contains("checkpoint"))
      .flatMap(strOf(_, "checkpointRef"))
      .distinct

    val limits = obj.fields
      .collectFirst { case ("limits", limits: Json.Obj) => limits }
      .getOrElse(Json.Obj())
    val outputs = obj.fields
      .collectFirst { case ("outputs", outputs: Json.Obj) => outputs }
      .getOrElse(Json.Obj())

    structure.map { structureJson =>
      Json.Obj(
        "format" -> Json.Str(PortableDoc.Format),
        "limits" -> limits,
        "structure" -> structureJson.getOrElse(Json.Null),
        "outputs" -> outputs,
        "requiredTrusted" -> Json.Obj(
          "profiles" -> Json.Arr(profiles.map(Json.Str(_))*),
          "checks" -> Json.Arr(checks*),
          "safeOperations" -> Json.Arr(safeOps.map(Json.Str(_))*),
          "checkpoints" -> Json.Arr(checkpoints.map(Json.Str(_))*)
        )
      )
    }

end PortableExplain
