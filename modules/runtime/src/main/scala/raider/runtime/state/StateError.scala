package raider.runtime.state

import raider.core.RaiderError

import java.nio.file.Path

/** Typed state-store failures (m1.md RAI-076).
  *
  * Own sealed family on the JobsError/EventError/MailboxError pattern:
  * `RaiderError` is frozen core and its subtypes live in a single core file, so
  * the conversion is explicit (`toRaiderError`), never implicit. Every refusal
  * is typed. A corrupt persisted document is NEVER silently recreated — the
  * failing operation reports [[Corrupt]] and the offending bytes stay untouched
  * for inspection.
  */
sealed trait StateError extends Product with Serializable:
  def toRaiderError: RaiderError

object StateError:

  /** A per-scope quota refused the write; `limit` names the exceeded bound
    * (`maxKeys`, `maxValueLen` or `maxTotalBytes`) with its configured value.
    */
  final case class QuotaExceeded(scope: Scope, limit: String)
      extends StateError:

    def toRaiderError: RaiderError =
      RaiderError.LocalBudgetExceeded(
        s"state quota exceeded for ${scope.describe}: $limit"
      )

  /** CAS refusal ([[StateStore.putIfRevision]]): the key's actual revision is
    * not the expected one. `actual` is the key's CURRENT revision (`None` = the
    * key does not exist).
    */
  final case class RevisionConflict(key: String, actual: Option[Long])
      extends StateError:

    def toRaiderError: RaiderError =
      RaiderError.CapabilityUnsupported(
        s"state revision conflict on key '$key': " +
          s"actual revision ${actual.fold("absent")(_.toString)}"
      )

  /** Malformed scope identity or key (empty id, path separators, traversal, bad
    * characters, too long) — refused before any storage is touched.
    */
  final case class ScopeViolation(scope: Scope, reason: String)
      extends StateError:

    def toRaiderError: RaiderError =
      RaiderError.InputValidation(
        s"invalid state scope ${scope.describe}: $reason"
      )

  /** A persisted document exists but cannot be trusted (unreadable JSON,
    * unsupported version, name/content mismatch, non-monotonic revision, or the
    * base path is not a directory). Construction/reads fail typed; the store
    * never deletes or rewrites the offending file.
    */
  final case class Corrupt(path: Path, reason: String) extends StateError:

    def toRaiderError: RaiderError =
      RaiderError.JournalFailure(s"corrupt state document $path: $reason")

  /** Filesystem failure of a post-commit persistence step (write/delete of a
    * document): the caller learns that the in-memory image may be ahead of disk
    * — no silent success, no fake durability.
    */
  final case class Io(path: Path, reason: String) extends StateError:

    def toRaiderError: RaiderError =
      RaiderError.JournalFailure(
        s"state file operation failed for $path: $reason"
      )

end StateError
