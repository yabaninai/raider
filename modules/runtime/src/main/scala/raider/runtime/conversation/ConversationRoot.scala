package raider.runtime.conversation

import raider.core.{RaiderError, RequestMessage, RootId}
import zio.{IO, Ref, Semaphore, UIO, ZIO}

/** Conversation root: the OWNERSHIP anchor for a group of conversation sessions
  * (SESSION-03, runtime-contracts §5). Opening registers the child under the
  * root in a narrow uninterruptible section; closing the root seals it and
  * closes EVERY child in registration order — a root close never orphans a
  * conversation (no daemon dispatcher left running, no unsettled receipts), and
  * a sealed root refuses new opens typed (RA-INP).
  *
  * Lifecycle locking mirrors [[Conversation]]: one lifecycle semaphore
  * serializes open-registration against close, so there is no interval in which
  * a child exists unregistered or a close misses a registering child.
  */
final class ConversationRoot private[conversation] (
    val id: RootId,
    children: Ref[List[Conversation]],
    sealedRef: Ref[Boolean],
    lifecycleLock: Semaphore
):

  /** Opens a child conversation under this root. Nothing dispatches at open
    * (API-03): the child's first turn runs only on its first explicit ask.
    */
  def open(
      bounds: ConversationBounds,
      runner: TurnRunner,
      label: Option[String] = None,
      seed: List[RequestMessage] = Nil
  ): IO[RaiderError, Conversation] =
    lifecycleLock.withPermit {
      sealedRef.get.flatMap {
        case true =>
          ZIO.fail(
            RaiderError.InputValidation(
              s"conversation root ${id.value} is sealed: open after close is refused"
            )
          )
        case false =>
          Conversation
            .make(id, bounds, runner, label, seed)
            .flatMap(conv =>
              // Registration is the only mutation: keep it atomic so a
              // concurrent close cannot miss the child between make and
              // registration (runtime-contracts §5 ownership).
              ZIO.uninterruptible(children.update(_.appended(conv)).as(conv))
            )
      }
    }

  /** Seals the root (idempotent, safe from any fiber) and closes every child
    * conversation in REGISTRATION order: each child settles all its receipts
    * (completion wins for its in-flight turn, queued turns cancel) before the
    * next child is closed.
    */
  def close: UIO[Unit] =
    lifecycleLock.withPermit {
      sealedRef.get.flatMap {
        case true => ZIO.unit
        case false =>
          for
            _ <- sealedRef.set(true)
            cs <- children.get
            _ <- ZIO.foreachDiscard(cs)(_.close)
          yield ()
      }
    }

  /** Child conversations in registration order (introspection/tests). */
  def snapshot: UIO[List[Conversation]] = children.get

  def isSealed: UIO[Boolean] = sealedRef.get

end ConversationRoot

object ConversationRoot:

  /** A fresh root: the ownership anchor id is unique per root. */
  def make(): UIO[ConversationRoot] =
    for
      children <- Ref.make(List.empty[Conversation])
      sealedRef <- Ref.make(false)
      lock <- Semaphore.make(1L)
    yield new ConversationRoot(
      Conversation.newRoot(),
      children,
      sealedRef,
      lock
    )

end ConversationRoot
