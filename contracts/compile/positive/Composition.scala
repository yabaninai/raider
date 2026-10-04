// RAI-017 COMP-01 positive fixture: typed composition compiles against the
// frozen Program/Task contracts (all arity-2, andThen chain, ordered batch).
import raider.core.{Program, Task}
import raider.dsl.composition.{all, batch}

object CompositionCompose:

  val double: Program[Int, Int] =
    Program.fromFunction[Int, Int]("double")(i => Task.succeed(i * 2))

  val label: Program[Int, String] =
    Program.fromFunction[Int, String]("label")(i => Task.succeed(s"n$i"))

  // all(Program, Program) -> Program[Int, (Int, String)]
  val both: Program[Int, (Int, String)] = all(double, label)

  // andThen through the frozen contract: Program[Int,(Int,String)] -> Program[Int,Int]
  val sumPair: Program[Int, Int] =
    both.andThen(Program.fromFunction[(Int, String), Int]("sum")(p => Task.succeed(p._1)))

  // all(Task, Task) -> Task[(String, Int)]
  val pair: Task[(String, Int)] = all(Task.succeed("a"), Task.succeed(1))

  // ordered bounded batch -> Task[List[String]]
  val batched: Task[List[String]] =
    batch(List("x", "y"), parallelism = 2)(name => Task.succeed(name.toUpperCase))

  val value: (Int, String) = (42, "n21")
