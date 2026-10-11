package raider.runtime.lineage.replay

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.util.Try

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.events.{EventEnvelope, EventPayload, ToolOutcome}
import raider.runtime.lineage.corpus.LineageCorpus
import raider.runtime.lineage.io.{LineageError, LineageEventReader}
import raider.runtime.lineage.model.GraphHash
import raider.runtime.lineage.reduce.{LineageReducer, ReduceError}
import zio.test.*

/** LIN-05B acceptance for the replay verifier (packet L25, ADR-027):
  *   - LIN5-B-01: `verify(campaign→jsonl→hash)` is a `ReplayMatch` whose hash
  *     is the canonical `GraphHash`, repeatable across ≥ 3 seeds (plus a
  *     property sweep over 20 seeds);
  *   - LIN5-B-02 (verifier half): every damage mode of the corpus fails TYPED —
  *     corrupted tail → `ReplayError.Truncated` (an honest refusal, never a
  *     success with a caveat), seq gap / wrong schemaVersion / empty file →
  *     `ReplayError.Read`, wrong expected hash → `ReplayError.HashMismatch` —
  *     and nothing is silently skipped;
  *   - environment-level IO (missing file) stays environment-level: it surfaces
  *     as the underlying `IOException`, the module's honesty rule — content
  *     distrust is typed, IO is not.
  *
  * LIN5-03 (partial replay over the flushed checkpoint, F-L28-3 fix): an intact
  * file verifies partial-complete exactly like `verify` (P1); a torn tail over
  * a complete readable prefix yields the prefix graph (P2); a torn tail whose
  * prefix fails the strict reduce ONLY with `MissingTerminal` is retried via
  * `LineageReducer.reducePartial` (P5); any other damage — reader-level (seq
  * gap, malformed line: P3/P4) or reduce-level (P6) — stays a typed refusal,
  * never a "plausible" partial graph.
  */
object ReplayVerifierSpec extends ZIOSpecDefault:

  /** sbt test JVMs run with cwd = build root; every file this suite creates
    * stays inside the runtime target dir (never a source path).
    */
  private def specDir(): Path =
    val dir = Path.of("modules", "runtime", "target", "lineage-replay-spec")
    Files.createDirectories(dir)
    dir

  private def writeCampaign(seed: Int, name: String): (Path, String) =
    val envelopes = LineageCorpus.campaign(seed)
    val graph = LineageReducer.reduce(envelopes) match
      case Right(g)  => g
      case Left(err) => throw new IllegalStateException(s"seed $seed: $err")
    val path = LineageCorpus.writeJsonl(envelopes, specDir().resolve(name))
    (path, GraphHash(graph))

  def spec = suite("ReplayVerifier")(
    suite("LIN5-B-01 campaign replay matches")(
      test(
        "verify over written jsonl is a ReplayMatch with the canonical hash"
      ) {
        Vector(1, 2, 3)
          .map { seed =>
            val (path, expectedHash) =
              writeCampaign(seed, s"lin5-b-01-seed-$seed.jsonl")
            val graph = LineageReducer
              .reduce(LineageCorpus.campaign(seed))
              .getOrElse(throw new IllegalStateException(s"seed $seed"))
            val first = ReplayVerifier.verify(path, expectedHash)
            val second = ReplayVerifier.verify(path, expectedHash)
            assertTrue(
              first == second, // repeatable: same file, same result
              first == Right(
                ReplayMatch(
                  RootId(s"root-$seed"),
                  SessionId(s"sess-$seed"),
                  graph.nodes.length,
                  expectedHash
                )
              )
            )
          }
          .reduce(_ && _)
      },
      test("property: replay matches for every seed 1..20") {
        check(Gen.int(1, 20)) { seed =>
          val (path, expectedHash) =
            writeCampaign(seed, s"lin5-b-01-prop-$seed.jsonl")
          ReplayVerifier.verify(path, expectedHash) match
            case Right(m) =>
              assertTrue(m.hash == expectedHash, m.hash.length == 64)
            case Left(err) =>
              throw new IllegalStateException(s"seed $seed: $err")
        }
      }
    ),
    suite("LIN5-B-02 typed refusals")(
      test("corrupted tail: honest Truncated refusal, not a success") {
        val seed = 1
        val envelopes = LineageCorpus.campaign(seed)
        val expectedHash = GraphHash(
          LineageReducer
            .reduce(envelopes)
            .getOrElse(throw new IllegalStateException(s"seed $seed"))
        )
        val path = LineageCorpus.writeLines(
          LineageCorpus.corruptedTail(envelopes),
          specDir().resolve("lin5-b-02-corrupted-tail.jsonl")
        )
        ReplayVerifier.verify(path, expectedHash) match
          case Left(ReplayError.Truncated(tail)) =>
            // the tail is the ADDED last line: line n+1, a strict prefix of
            // the last real line
            assertTrue(
              tail.lineNo == envelopes.length + 1,
              tail.rawPrefixLen > 0
            )
          case other =>
            throw new IllegalStateException(
              s"expected Truncated, got $other"
            )
      },
      test("seq gap: reader-layer LineageError.SeqGap surfaces typed") {
        val seed = 1
        val envelopes = LineageCorpus.campaign(seed)
        val firstJob = envelopes.head.jobId.value
        val expectedHash =
          GraphHash(
            LineageReducer
              .reduce(envelopes)
              .getOrElse(throw new IllegalStateException(s"seed $seed"))
          )
        val path = LineageCorpus.writeLines(
          LineageCorpus.withSeqGap(envelopes),
          specDir().resolve("lin5-b-02-seq-gap.jsonl")
        )
        // withSeqGap drops the first envelope of the first job: the reader
        // then sees seq 2 where 1 is expected
        assertTrue(
          ReplayVerifier.verify(path, expectedHash) ==
            Left(ReplayError.Read(LineageError.SeqGap(firstJob, 1L, 2L)))
        )
      },
      test("wrong schemaVersion: rejected before any reduction") {
        val seed = 1
        val envelopes = LineageCorpus.campaign(seed)
        val expectedHash =
          GraphHash(
            LineageReducer
              .reduce(envelopes)
              .getOrElse(throw new IllegalStateException(s"seed $seed"))
          )
        val path = LineageCorpus.writeLines(
          LineageCorpus.wrongSchemaVersion(envelopes),
          specDir().resolve("lin5-b-02-wrong-schema.jsonl")
        )
        assertTrue(
          ReplayVerifier.verify(path, expectedHash) ==
            Left(
              ReplayError.Read(LineageError.SchemaVersionUnsupported(999))
            )
        )
      },
      test("empty file: Read(LineageError.Empty)") {
        val path = specDir().resolve("lin5-b-02-empty.jsonl")
        Files.write(path, Array.emptyByteArray)
        assertTrue(
          ReplayVerifier.verify(path, "0" * 64) ==
            Left(ReplayError.Read(LineageError.Empty))
        )
      },
      test("wrong expected hash: HashMismatch carries both sides") {
        val (path, realHash) = writeCampaign(1, "lin5-b-02-hash.jsonl")
        val bogus = "f" * 64
        ReplayVerifier.verify(path, bogus) match
          case Left(ReplayError.HashMismatch(expected, actual)) =>
            assertTrue(
              expected == bogus,
              actual == realHash,
              expected != actual
            )
          case other =>
            throw new IllegalStateException(
              s"expected HashMismatch, got $other"
            )
      },
      test("missing file: environment-level IOException, not a typed error") {
        val missing = specDir().resolve("lin5-b-02-does-not-exist.jsonl")
        Files.deleteIfExists(missing)
        val outcome = Try(ReplayVerifier.verify(missing, "0" * 64))
        assertTrue(
          outcome.isFailure,
          outcome.failed.toOption.get.isInstanceOf[java.io.IOException]
        )
      }
    ),
    suite("LIN5-03 partial replay over the flushed checkpoint")(
      test(
        "LIN5-03 P1: intact file verifies partial-complete, equal to verify"
      ) {
        val (path, realHash) = writeCampaign(2, "lin5-03-intact.jsonl")
        val full = ReplayVerifier.verify(path, realHash)
        ReplayVerifier.verifyPartial(path) match
          case Right(partial) =>
            assertTrue(
              partial.complete,
              partial.tail.isEmpty,
              partial.hash == realHash,
              full == Right(
                ReplayMatch(
                  partial.rootId,
                  partial.sessionId,
                  partial.nodeCount,
                  partial.hash
                )
              )
            )
          case other =>
            throw new IllegalStateException(
              s"expected PartialReplay, got $other"
            )
      },
      test(
        "LIN5-03 P2: torn tail → consistent prefix graph, truncation reported"
      ) {
        val envelopes = LineageCorpus.campaign(3)
        val full = LineageReducer.reduce(envelopes) match
          case Right(g)  => g
          case Left(err) => throw new IllegalStateException(err.toString)
        val path = specDir().resolve("lin5-03-torn.jsonl")
        Files.write(
          path,
          LineageCorpus
            .corruptedTail(envelopes)
            .mkString("", "\n", "\n")
            .getBytes(
              StandardCharsets.UTF_8
            )
        )
        val prefixEnvelopes = LineageEventReader.read(path) match
          case Right(parsed) => parsed.envelopes
          case Left(err)     => throw new IllegalStateException(err.toString)
        val prefixGraph = LineageReducer.reduce(prefixEnvelopes) match
          case Right(g)  => g
          case Left(err) => throw new IllegalStateException(err.toString)
        ReplayVerifier.verifyPartial(path) match
          case Right(partial) =>
            assertTrue(
              !partial.complete,
              partial.tail.isDefined,
              partial.hash == GraphHash(prefixGraph),
              partial.nodeCount == prefixGraph.nodes.length,
              partial.nodeCount <= full.nodes.length,
              partial.hash != GraphHash(
                full
              ) || partial.nodeCount == full.nodes.length
            )
          case other =>
            throw new IllegalStateException(
              s"expected PartialReplay, got $other"
            )
      },
      test("LIN5-03 P3: mid-file seq gap is a typed refusal, no fake partial") {
        val path = specDir().resolve("lin5-03-seqgap.jsonl")
        Files.write(
          path,
          LineageCorpus
            .withSeqGap(LineageCorpus.campaign(1))
            .mkString("", "\n", "\n")
            .getBytes(StandardCharsets.UTF_8)
        )
        // The reader validates per-job seq contiguity itself (LIN-01B), so a
        // mid-file gap refuses at the READ layer — verifyPartial must surface
        // it typed, never as a "plausible" partial graph.
        val typedRead =
          ReplayVerifier.verifyPartial(path) match
            case Left(ReplayError.Read(LineageError.SeqGap(_, _, _))) => true
            case _                                                    => false
        assertTrue(typedRead)
      },
      test("LIN5-03 P4: mid-file malformed line stays Read-typed") {
        val path = specDir().resolve("lin5-03-malformed.jsonl")
        val lines = LineageCorpus.jsonlLines(LineageCorpus.campaign(4))
        Files.write(
          path,
          (lines.take(2) :+ "{not json at all" :+ lines.last)
            .mkString("", "\n", "\n")
            .getBytes(StandardCharsets.UTF_8)
        )
        val outcome = Try(ReplayVerifier.verifyPartial(path))
        assertTrue(
          outcome.isSuccess,
          outcome.get.isLeft,
          outcome.get.swap.toOption.get.isInstanceOf[ReplayError.Read]
        )
      },
      test(
        "LIN5-03 P5 (F3-01): REAL crash prefix — torn root-Terminal line verifies partial"
      ) {
        // The root job finishes LAST (root Terminal is the final line), so the
        // real crash shape tears exactly that line: the strict reducer refuses
        // the prefix with MissingTerminal (the W10 finding F-L28-3), the
        // partial contract must return Right(PartialReplay(complete=false)).
        val seed = 5
        val envelopes = LineageCorpus.campaign(seed)
        val full = LineageReducer
          .reduce(envelopes)
          .getOrElse(throw new IllegalStateException(s"seed $seed"))
        val lines = LineageCorpus.jsonlLines(envelopes)
        val torn = lines.dropRight(1) :+ lines.last.take(lines.last.length / 2)
        val path =
          LineageCorpus.writeLines(
            torn,
            specDir().resolve("lin5-03-p5-crash.jsonl")
          )
        val prefix = LineageEventReader.read(path) match
          case Right(parsed) => parsed.envelopes
          case Left(err)     => throw new IllegalStateException(err.toString)
        // precondition: this is exactly the MissingTerminal-only case
        assertTrue(
          LineageReducer.reduce(prefix) ==
            Left(ReduceError.MissingTerminal(s"job-$seed-root"))
        )
        val partialGraph = LineageReducer.reducePartial(prefix) match
          case Right(g)  => g
          case Left(err) => throw new IllegalStateException(err.toString)
        ReplayVerifier.verifyPartial(path) match
          case Right(partial) =>
            assertTrue(
              !partial.complete,
              partial.tail.isDefined,
              partial.tail.get.lineNo == envelopes.length,
              partial.rootId == RootId(s"root-$seed"),
              partial.sessionId == SessionId(s"sess-$seed"),
              partial.hash == GraphHash(partialGraph),
              partial.nodeCount == partialGraph.nodes.length,
              // only the Terminal MARKER was lost: every emitted construct of
              // every job was already finished, so the partial graph holds all
              // completed nodes and hashes like the full one
              partial.hash == GraphHash(full),
              partial.nodeCount == full.nodes.length
            )
          case other =>
            throw new IllegalStateException(
              s"expected PartialReplay, got $other"
            )
      },
      test(
        "LIN5-03 P6 (F3-02): torn tail over a contradictory prefix stays Reduce-typed"
      ) {
        // A torn tail only forgives a MISSING TERMINAL; any other reduce-level
        // contradiction (here: ToolFinished without an open start) is damage,
        // never papered over into a "plausible" partial graph.
        val bad = EventEnvelope(
          1,
          1L,
          JobId("job-bad"),
          RootId("root-bad"),
          SessionId("sess-bad"),
          1760000000000L,
          EventPayload.ToolFinished("call-9", "bash", ToolOutcome.Succeeded)
        )
        val lines = LineageCorpus.jsonlLines(Vector(bad))
        val torn = lines :+ lines.last.take(lines.last.length / 2)
        val path =
          LineageCorpus.writeLines(torn, specDir().resolve("lin5-03-p6.jsonl"))
        assertTrue(
          ReplayVerifier.verifyPartial(path) ==
            Left(
              ReplayError.Reduce(
                ReduceError.UnmatchedFinish(
                  "job-bad",
                  1L,
                  "ToolFinished(callId=call-9)"
                )
              )
            )
        )
      }
    )
  )

end ReplayVerifierSpec
