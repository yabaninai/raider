import raider.core.*

/** RAI-002 negative compile fixture: mismatched andThen stages must NOT compile. */
object AndThenMismatch:
  val ints: Program[String, Int] =
    Program.fromFunction("ints")(q => Task.succeed(q.length))
  val needsBoolean: Program[Int, String] =
    Program.fromFunction("needs-bool")((b: Boolean) => Task.succeed(b.toString))
  val bad: Program[String, String] = ints andThen needsBoolean // type error: Int ≠ Boolean
