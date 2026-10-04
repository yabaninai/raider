package raider.core

/** Program: the one execution contract shared by REPL, DSL composition and the
  * compiled CI bundle (runtime-contracts §3, ci-runtime §1–2).
  *
  * `apply(input)` only builds a lazy Task — no model/tool/network effects
  * happen at construction, printing or import time. `ask`/`start` are REPL
  * extension methods over this contract, never members of core (no
  * terminal/global ReplSession dependency here).
  */
trait Program[I, O]:
  def apply(input: I): Task[O]

  infix def andThen[P](next: Program[O, P]): Program[I, P] =
    Program.join(this, next)

  def mapOutput[P](f: O => P): Program[I, P] =
    Program.mapped(this, f)

/** An agent: a named, configurable Program (descriptor + executable behavior).
  */
trait Agent[I, O] extends Program[I, O]:
  def name: String

/** A workflow: a Program with a registered name and version for bundles. */
trait Workflow[I, O] extends Program[I, O]:
  def name: String
  def version: Int

object Program:

  private final class Mapped[I, A, B](source: Program[I, A], f: A => B)
      extends Program[I, B]:
    def apply(input: I): Task[B] = source.apply(input).map(f)

  private final class Joined2[A, B, C](
      first: Program[A, B],
      second: Program[B, C]
  ) extends Program[A, C]:
    def apply(input: A): Task[C] = first.apply(input).flatMap(second.apply)

  def join[A, B, C](
      first: Program[A, B],
      second: Program[B, C]
  ): Program[A, C] =
    Joined2(first, second)

  def mapped[I, A, B](source: Program[I, A], f: A => B): Program[I, B] =
    new Mapped(source, f)

  def fromFunction[I, O](name: String)(body: I => Task[O]): Program[I, O] =
    new Named(name, body)

  private final class Named[I, O](val name: String, body: I => Task[O])
      extends Program[I, O]
      with Agent[I, O]:
    def apply(input: I): Task[O] = body(input)
