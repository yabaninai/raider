package demo

import raider.core.{BundleWorkflow, Program, RaiderError, Task}
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** A typed, deterministic release-readiness check; no model or network calls.
  */
class TaskSummary extends BundleWorkflow:
  override def name: String = "task-summary"

  override def program: Program[String, String] =
    val parseChecks: Program[String, List[(String, String)]] =
      Program.fromFunction("parse-release-checks") { input =>
        Task.succeed(
          input.split(",", -1).toList.map(_.trim).filter(_.nonEmpty).map {
            item =>
              item.split("=", 2) match
                case Array(name, status) => name.trim -> status.trim.toLowerCase
                case _                   => item -> "invalid"
          }
        )
      }

    val evaluate: Program[List[(String, String)], String] =
      Program.fromFunction("evaluate-release-checks") { checks =>
        val required = List("tests", "migration", "rollback")
        val names = checks.map(_._1)
        val duplicates = names.diff(names.distinct).distinct
        val unknown = names.filterNot(required.contains)
        val missing = required.filterNot(names.contains)
        val invalid = checks.collect {
          case (name, status) if status != "pass" && status != "pending" => name
        }
        if duplicates.nonEmpty || unknown.nonEmpty || invalid.nonEmpty then
          Task.fail(
            RaiderError.InputValidation(
              s"invalid checks: ${(duplicates ++ unknown ++ invalid).distinct.sorted.mkString(", ")}"
            )
          )
        else if missing.nonEmpty then
          Task.fail(
            RaiderError.InputValidation(
              s"missing required checks: ${missing.mkString(", ")}"
            )
          )
        else
          val pending = checks.collect { case (name, "pending") => name }.sorted
          val passed = checks.collect { case (name, "pass") => name }.sorted
          val decision = if pending.isEmpty then "READY" else "BLOCKED"
          val action =
            if pending.isEmpty then "No follow-up required."
            else s"Complete: ${pending.mkString(", ")}."
          val report = Json.Obj(
            Chunk(
              "decision" -> Json.Str(decision),
              "passed" -> Json.Arr(Chunk.fromIterable(passed.map(Json.Str(_)))),
              "pending" -> Json.Arr(
                Chunk.fromIterable(pending.map(Json.Str(_)))
              ),
              "action" -> Json.Str(action)
            )
          )
          Task.succeed(report.toJson)
      }

    parseChecks.andThen(evaluate)
