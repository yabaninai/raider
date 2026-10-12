package demo

import raider.core.{BundleWorkflow, Program, RaiderError, Task}

/** A pure compiled example: no model backend and no network calls. */
class TaskSummary extends BundleWorkflow:
  override def name: String = "task-summary"

  override def program: Program[String, String] =
    val splitTasks: Program[String, List[String]] =
      Program.fromFunction("split-tasks") { input =>
        Task.succeed(input.split(",", -1).toList.map(_.trim).filter(_.nonEmpty))
      }

    val summarize: Program[List[String], String] =
      Program.fromFunction("summarize") { tasks =>
        if tasks.isEmpty then
          Task.fail(RaiderError.InputValidation("input must contain a task"))
        else
          Task.succeed(s"${tasks.size} tasks: ${tasks.mkString(" | ")}")
      }

    splitTasks.andThen(summarize)
