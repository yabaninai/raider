package raider.runtime.lineage.replay

import java.nio.file.Path

import raider.core.{RootId, SessionId}
import raider.runtime.lineage.io.{
  LineageError,
  LineageEventReader,
  ParsedEvents,
  TruncatedTail
}
import raider.runtime.lineage.model.GraphHash
import raider.runtime.lineage.reduce.{LineageReducer, ReduceError}

/** Successful replay verification (LIN-05B, packet L25, ADR-027): the reduced
  * campaign graph hashed to exactly the expected canonical
  * [[raider.runtime.lineage.model.GraphHash]] hex.
  */
final case class ReplayMatch(
    rootId: RootId,
    sessionId: SessionId,
    nodeCount: Int,
    hash: String
)

/** Result of [[ReplayVerifier.verifyPartial]] (LIN5-03, ADR-027): the session
  * graph reduced from the FLUSHED prefix of a recorded events.jsonl.
  *
  * The checkpoint medium IS the recording itself — the headless runner streams
  * events.jsonl with append + flush per line ([[raider.runtime.lineage.io]],
  * LIN-03A-prep), so after a crash the file holds an honest prefix of complete
  * lines. No additional checkpoint file is invented (wire-freeze): the partial
  * graph is always the strict reduce of `readablePrefix`, or — when the torn
  * tail cost the campaign its final `Terminal` marker(s) — its tolerant LIN5-03
  * counterpart `LineageReducer.reducePartial(readablePrefix)`.
  *
  * `complete = true` means the file had no truncated tail and the graph is the
  * FULL campaign graph (then [[hash]] equals the whole-run
  * [[raider.runtime.lineage.model.GraphHash]] and `tail` is empty). A torn last
  * line yields `complete = false` with the reported [[tail]] — the graph holds
  * exactly the COMPLETED nodes of the readable prefix: terminally-complete jobs
  * keep their batch nodes; a job the crash left unterminated keeps only its
  * finished nodes (no half-built rounds, no unknown-outcome tool flushes), and
  * the reducer is deterministic, so the same file always yields the same graph
  * and hash.
  */
final case class PartialReplay(
    rootId: RootId,
    sessionId: SessionId,
    nodeCount: Int,
    hash: String,
    complete: Boolean,
    tail: Option[TruncatedTail]
)

/** Typed failure layers of [[ReplayVerifier.verify]]. Sealed and total over the
  * verification pipeline: every layer reports its own error type and nothing is
  * silently skipped — a damaged recording refuses to verify, it never verifies
  * "with a caveat".
  */
enum ReplayError:

  /** Content-level failure while reading the events file (malformed line before
    * the tail, unsupported schemaVersion, seq gap, empty file) — the
    * [[LineageError]] of [[raider.runtime.lineage.io.LineageEventReader.read]]
    * surfaced verbatim.
    */
  case Read(error: LineageError)

  /** The file ends with an undecodable last line
    * ([[raider.runtime.lineage.io.TruncatedTail]]): typically a torn final
    * record from a crash mid-write. This is an honest REFUSAL — the recording
    * as written is incomplete, so replay cannot claim to match it, even if the
    * readable prefix would reduce to a plausible graph.
    */
  case Truncated(tail: TruncatedTail)

  /** The readable envelopes do not reduce to a valid session graph (unknown
    * parent, per-job seq violation, event after terminal, unmatched finish,
    * missing terminal, …) — the [[ReduceError]] of
    * [[raider.runtime.lineage.reduce.LineageReducer.reduce]] surfaced verbatim.
    * For a torn file the ONE exception is a pure missing-terminal failure: it
    * is retried with `LineageReducer.reducePartial` (see [[verifyPartial]]);
    * every other reduce error is damage and stays typed.
    */
  case Reduce(error: ReduceError)

  /** The reduced graph is structurally valid but its canonical hash differs
    * from the expected one. Both hex strings are carried verbatim for
    * diagnosis; structural divergence reports belong to
    * [[GraphComparator.compare]] (LIN5-B-02), not to the string comparison this
    * verifier performs.
    */
  case HashMismatch(expected: String, actual: String)
end ReplayError

/** Replay verification over a recorded events.jsonl (LIN-05B, packet L25,
  * ADR-027): read → reduce → hash → string comparison against the expected
  * hash. The pipeline is fully deterministic — the reader and reducer are pure
  * folds over the stream, [[GraphHash]] is a pure function of structure — so
  * the same file always verifies the same way (LIN5-B-01).
  *
  * Error honesty:
  *   - a truncated tail is `ReplayError.Truncated`, NEVER a success with an
  * annotation: the recording as written is damaged and must be re-verified from
  * an intact copy;
  *   - reader/reducer/hash layers are typed ([[ReplayError.Read]],
  * [[ReplayError.Reduce]], [[ReplayError.HashMismatch]]); the first failing
  * layer wins and nothing downstream runs;
  *   - environment-level IO failures (missing/unreadable file) stay
  *     environment-level: they surface as the underlying `IOException`, the
  *     same honesty rule as the reader — content distrust is typed, IO is not.
  *
  * [[verifyPartial]] (LIN5-03) builds the deterministic crash-consistency read
  * side on the same pipeline. NOT part of this card: projection execution and
  * replaying the recording with an LLM. This verifier is the deterministic
  * mechanics those layers build on: whole-file, whole-graph, hash-only.
  */
object ReplayVerifier:

  /** Verifies that the events file at `eventsPath` reduces to a session graph
    * whose canonical hash equals `expectedHash`.
    *
    * @return
    *   the [[ReplayMatch]] (identity, node count, verified hash), or the typed
    *   [[ReplayError]] of the first failing layer.
    */
  def verify(
      eventsPath: Path,
      expectedHash: String
  ): Either[ReplayError, ReplayMatch] =
    LineageEventReader.read(eventsPath) match
      case Left(err) => Left(ReplayError.Read(err))
      case Right(ParsedEvents(_, Some(tail))) =>
        Left(ReplayError.Truncated(tail))
      case Right(ParsedEvents(envelopes, None)) =>
        LineageReducer.reduce(envelopes) match
          case Left(err) => Left(ReplayError.Reduce(err))
          case Right(graph) =>
            val hash = GraphHash(graph)
            if hash != expectedHash then
              Left(ReplayError.HashMismatch(expectedHash, hash))
            else
              Right(
                ReplayMatch(
                  graph.rootId,
                  graph.sessionId,
                  graph.nodes.length,
                  hash
                )
              )

  /** Reduces the FLUSHED prefix of the events file into a consistent partial
    * graph (LIN5-03, ADR-027): the crash-consistency read side of the streamed
    * events.jsonl checkpoint ([[PartialReplay]] for the contract).
    *
    * Honesty rules, mirroring [[verify]]:
    *   - a torn last line does NOT fail the read — it is exactly the crash
    *     signature this method exists for; the prefix graph is returned with
    *     `complete = false` and the tail reported;
    *   - for a torn file the strict reduce runs first. If it fails ONLY with
    *     `ReduceError.MissingTerminal` (the torn record cost the campaign its
    *     final `Terminal` marker — the root job finishes last, so a real crash
    *     tears exactly that line), the prefix is retried through
    *     [[raider.runtime.lineage.reduce.LineageReducer.reducePartial]] and its
    *     completed-nodes graph is returned: `Right(PartialReplay(…, complete =
    *     false, tail))`. Any OTHER reduce error (seq gap, unknown parent,
    *     after-terminal, unmatched finish, …) is DAMAGE, not incompleteness,
    *     and stays a typed [[ReplayError.Reduce]] refusal — never papered over
    *     into a "plausible" partial graph;
    *   - damage BEFORE the tail (malformed line, seq gap, unsupported schema)
    *     stays a TYPED [[ReplayError.Read]] refusal;
    *   - an intact file takes exactly the [[verify]] path (strict reduce, no
    *     retry): `complete = true` with the same graph and hash `verify` would
    *     produce (no separate code path, same deterministic pipeline).
    */
  def verifyPartial(eventsPath: Path): Either[ReplayError, PartialReplay] =
    LineageEventReader.read(eventsPath) match
      case Left(err) => Left(ReplayError.Read(err))
      case Right(ParsedEvents(envelopes, tail)) =>
        val reduced = tail match
          case Some(_) =>
            // Torn tail: strict reduce first; ONLY a pure missing-terminal
            // failure is the LIN5-03 crash shape and is retried as a partial
            // reduce. Every other error is damage and stays typed.
            LineageReducer.reduce(envelopes) match
              case Left(ReduceError.MissingTerminal(_)) =>
                LineageReducer.reducePartial(envelopes)
              case other => other
          case None => LineageReducer.reduce(envelopes)
        reduced match
          case Left(err) => Left(ReplayError.Reduce(err))
          case Right(graph) =>
            val hash = GraphHash(graph)
            Right(
              PartialReplay(
                graph.rootId,
                graph.sessionId,
                graph.nodes.length,
                hash,
                complete = tail.isEmpty,
                tail
              )
            )

end ReplayVerifier
