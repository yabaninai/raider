package raider.core

import zio.{Duration, ZIO}

/** Task: a lazy unit of agent work (runtime-contracts §3, product-repl §2).
  * Sealed: the only implementation is the private ZIO wrapper, so composition
  * stays total and no external subclass can smuggle eager effects.
  *
  * A thin wrapper over a ZIO effect. Constructing, mapping, printing or
  * importing a Task never dispatches anything (API-03); execution happens only
  * through the single interpreter via the launch facade (`ask/run/start`) or
  * the headless Runner. No serializable AST is promised for arbitrary Scala
  * callbacks.
  */
sealed trait Task[+A]:
  def map[B](f: A => B): Task[B]
  def flatMap[B](f: A => Task[B]): Task[B]

object Task:

  private[core] def effect[A](zio: ZIO[Any, RaiderError, A]): Task[A] =
    new Impl(zio)

  /** Public constructor from a ZIO effect (mirror of the interpreter boundary):
    * Task stays a thin lazy wrapper, construction never dispatches.
    */
  def apply[A](zio: ZIO[Any, RaiderError, A]): Task[A] = effect(zio)

  /** The single interpreter boundary: exposes the underlying ZIO effect to the
    * runtime launcher. Everything else (facade, DSL, runner) goes through the
    * same runtime path — there is no second execution semantics.
    */
  def zio[A](task: Task[A]): ZIO[Any, RaiderError, A] =
    task match
      case impl: Impl[a] =>
        impl.underlying.asInstanceOf[ZIO[Any, RaiderError, A]]

  def succeed[A](value: A): Task[A] = effect(ZIO.succeed(value))

  def fail(error: RaiderError): Task[Nothing] = effect(ZIO.fail(error))

  /** Sleep without starting anything: pure description used by tests. */
  def sleepNanos(n: Long): Task[Unit] =
    effect(ZIO.unit.delay(Duration.fromNanos(n)))

  private final class Impl[+A](val underlying: ZIO[Any, RaiderError, A])
      extends Task[A]:
    def map[B](f: A => B): Task[B] = new Impl(underlying.map(f))

    def flatMap[B](f: A => Task[B]): Task[B] =
      new Impl(
        underlying.flatMap(a =>
          f(a) match
            case impl: Impl[b] =>
              impl.underlying.asInstanceOf[ZIO[Any, RaiderError, B]]
        )
      )
