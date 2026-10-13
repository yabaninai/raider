// Scala 3 showcase for Raider REPL, previously checked through raider.repl.Main.
// Blank lines separate blocks: the REPL submits the buffered input on a blank line.
// Agent composition at the end uses the scripted backend in the mock prelude.

enum JobState {
  case Queued
  case Running(step: String)
  case Waiting(reason: String)
  case Done(result: String)
  case Cancelled(by: String)
}

def describe(s: JobState): String = s match {
  case JobState.Queued          => "queued (not yet admitted)"
  case JobState.Running(step)   => s"running: $step"
  case JobState.Waiting(reason) => s"waiting: $reason"
  case JobState.Done(r)         => s"done: $r"
  case JobState.Cancelled(by)   => s"cancelled ($by)"
}

describe(JobState.Running("proc_run: sbt test"))

trait Show[A]:
  def show(a: A): String

given Show[JobState] with
  def show(a: JobState) = describe(a)

def report[A: Show](a: A): String = summon[Show[A]].show(a)

extension (s: String) def shouted: String = s.toUpperCase + "!"

report(JobState.Waiting("LLM slot occupied"))

"native Scala".shouted

object budget {
  opaque type Tokens = Long
  def Tokens(n: Long): Tokens = if n < 0 then throw IllegalArgumentException("negative") else n
  extension (t: Tokens)
    def +(o: Tokens): Tokens = t + o
    def value: Long = t
}

given Conversion[Int, budget.Tokens] = n => budget.Tokens(n.toLong)

val spent: budget.Tokens = 42

val total = spent + budget.Tokens(8)

total.value

def label(id: String | Int): String = id match {
  case s: String => s"name: $s"
  case i: Int    => s"number: $i"
}

label("coder-1")

label(7)

case class Cfg(model: String, maxTokens: Int)

def parseModel(s: String): Either[String, String] = if s.trim.isEmpty then Left("empty model") else Right(s.trim)
def parseMax(n: String): Either[String, Int]      = n.toIntOption.toRight("not a number")

val cfg: Either[String, Cfg] = for {
  m <- parseModel(" qwen-27b ")
  t <- parseMax("4096")
} yield Cfg(m, t)

cfg

import zio.*
val prog = for {
  n1 <- ZIO.succeed(21)
  n2 <- ZIO.succeed(n1 * 2)
  _  <- ZIO.succeed(println("the ZIO chain ran"))
} yield n2

zio.Unsafe.unsafe { implicit u => zio.Runtime.default.unsafe.run(prog).getOrThrow() }

type Elem[X] = X match {
  case List[t]   => t
  case Vector[t] => t
  case Option[t] => t
}

val s: Elem[List[String]] = "ok"

summon[Elem[Option[Int]] =:= Int]

val answer = scout("In one paragraph, how do Scala 3 opaque types and givens cover the use cases of Rust newtypes and traits?").start().await()
