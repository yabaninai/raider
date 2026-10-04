import raider.core.*

/** RAI-002 positive compile fixture: composition builds, nothing dispatches. */
object ProgramCompose:
  val scout: Program[String, String] =
    Program.fromFunction("scout")(q => Task.succeed(s"facts($q)"))
  val reviewer: Program[String, String] =
    Program.fromFunction("reviewer")(f => Task.succeed(s"review($f)"))
  val writer: Program[String, Int] =
    Program.fromFunction("writer")(r => Task.succeed(r.length))

  val pipeline: Program[String, String] = scout andThen reviewer
  val sized: Program[String, Int] = pipeline.mapOutput(_.length)
  val chained: Program[String, Int] = scout andThen reviewer andThen writer

  val task: Task[Int] = chained("проверь проект")      // construction only
  val mapped: Task[String] = task.map(n => s"n=$n")    // still no dispatch
  val flat: Task[Int] = mapped.flatMap(s => Task.succeed(s.length))
