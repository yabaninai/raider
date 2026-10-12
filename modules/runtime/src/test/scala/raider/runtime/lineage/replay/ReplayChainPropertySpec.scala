package raider.runtime.lineage.replay

import java.nio.file.{Files, Path}

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.events.{EventEnvelope, EventPayload}
import raider.runtime.lineage.corpus.LineageCorpus
import raider.runtime.lineage.model.GraphHash
import raider.runtime.lineage.reduce.LineageReducer
import zio.test.*

/** W22-R8 property sweep over the WHOLE deterministic replay chain on the
  * existing production code (packet R8; tests only — no production change): for
  * every seed 1..50 of [[LineageCorpus.campaign]]:
  *   1. write → `ReplayVerifier.verify(hash)` is a `ReplayMatch` equal to the
  *      reduced graph's identity (LIN5-B-01 swept over 5× the original seed
  *      range);
  *   2. `ReplayVerifier.verifyPartial` over the INTACT file is `complete =
  *      true` with the same graph and hash (LIN5-03 P1 swept);
  *   3. `GraphComparator.compareNormalized(g, renameJobs(g))` is a Match — the
  *      rename-invariance strengthening of packet L54 (F-L51-1): ALL jobs are
  *      renamed through a seed-deterministic rotation that REVERSES the
  *      alphabetical order in which the jobs first compete at seq 1, plus fresh
  *      root/session ids and zeroed `atMs`. The raw `compare` must still SEE
  *      the identity drift; the normalized compare must not care.
  *
  * Determinism: the sweep itself is a deterministic enumeration —
  * `Gen.fromIterable(1 to 50)` runs each seed EXACTLY once (50 runs, no random
  * sampling, no shrink search), so no seed can be silently skipped the way
  * random sampling could. The corpus, reducer, hasher and comparator are pure —
  * no clocks, no RNG beyond the corpus LCG seeded by `seed`. Files go to the
  * runtime target dir (never a source path), one per seed.
  */
object ReplayChainPropertySpec extends ZIOSpecDefault:

  /** sbt test JVMs run with cwd = build root; every file this suite creates
    * stays inside the runtime target dir.
    */
  private def specDir(): Path =
    val dir = Path.of("modules", "runtime", "target", "lineage-replay-w22-r8")
    Files.createDirectories(dir)
    dir

  /** Name slots of the permutation, in FIXED alphabetical order `ann < bob <
    * cid`; the rotation offset `k` maps root/child-1/child-2 to slots
    * `(0+k)%3`/`(1+k)%3`/`(2+k)%3`, so `k = 1` and `k = 2` reverse the
    * alphabetical relation of structurally-first jobs (the F-L51-1 trap).
    */
  private val NameSlots = Vector("ann", "bob", "cid")

  private def renamedJob(job: JobId, seed: Int, k: Int): JobId =
    val rel =
      if job.value.endsWith("-root") then 0
      else if job.value.endsWith("-child-1") then 1
      else 2
    JobId(s"w22r8-${NameSlots((rel + k) % 3)}-$seed")

  /** The seed campaign with EVERY job renamed through the deterministic
    * rotation, fresh root/session identity and zeroed `atMs` — identity drift
    * only, the emission structure untouched (delegation edges follow their
    * jobs; per-job seqs are a bijection, so contiguity is preserved).
    */
  private def renamedCampaign(seed: Int): Vector[EventEnvelope] =
    val k = (seed - 1) % 3
    LineageCorpus
      .campaign(seed)
      .map { env =>
        val payload = env.payload match
          case d: EventPayload.Delegated =>
            d.copy(childJobId = renamedJob(d.childJobId, seed, k))
          case other => other
        env.copy(
          jobId = renamedJob(env.jobId, seed, k),
          rootId = RootId(s"renamed-$seed"),
          sessionId = SessionId(s"rsess-$seed"),
          atMs = 0L,
          payload = payload
        )
      }

  private def reduced(seed: Int, envelopes: Vector[EventEnvelope]) =
    LineageReducer.reduce(envelopes) match
      case Right(g) => g
      case Left(err) =>
        throw new IllegalStateException(s"seed $seed reduce: $err")

  def spec = suite("ReplayChainProperty")(
    test(
      "PROP-01 sweep, seeds 1..50: verify Match + verifyPartial complete + rename-invariant normalized Match"
    ) {
      // Deterministic enumeration (review R1): Gen.fromIterable runs each
      // seed exactly once — 50 runs total, no sampled repeats and no ~13.5%
      // per-run chance of a randomly-skipped seed under default TestConfig.
      check(Gen.fromIterable(1 to 50)) { seed =>
        val envelopes = LineageCorpus.campaign(seed)
        val g = reduced(seed, envelopes)
        val hash = GraphHash(g)
        val path = LineageCorpus.writeJsonl(
          envelopes,
          specDir().resolve(s"w22-r8-seed-$seed.jsonl")
        )

        // (1) whole-file verify: exact ReplayMatch of the reduced graph
        val verifyResult = ReplayVerifier.verify(path, hash)
        val verifyExpected =
          Right(ReplayMatch(g.rootId, g.sessionId, g.nodes.length, hash))

        // (2) partial verify over the INTACT file: complete, no torn tail,
        // same graph/hash
        val partialResult = ReplayVerifier.verifyPartial(path)
        val partialExpected = Right(
          PartialReplay(
            g.rootId,
            g.sessionId,
            g.nodes.length,
            hash,
            complete = true,
            tail = None
          )
        )

        // (3) rename-invariance: raw compare SEES the identity drift, the
        // normalized compare matches BOTH directions on one canonical hash
        val renamed = reduced(seed, renamedCampaign(seed))
        val renamedHash = GraphHash(renamed)
        val normExpected = GraphHash(GraphComparator.normalize(g))
        val rawCompare = GraphComparator.compare(g, renamed)
        val normForward = GraphComparator.compareNormalized(g, renamed)
        val normBackward = GraphComparator.compareNormalized(renamed, g)
        val renamedNormHash = GraphHash(GraphComparator.normalize(renamed))

        // Separate diagnostic asserts (review R2): each invariant reports
        // its own expected/actual on failure — nothing weakened, the
        // conjunction below is exactly the former single boolean.
        assertTrue(verifyResult == verifyExpected) &&
        assertTrue(partialResult == partialExpected) &&
        assertTrue(rawCompare.isLeft) &&
        assertTrue(
          renamedHash != hash
        ) && // jobs are structure keys of the raw form
        assertTrue(normForward == Right(normExpected)) &&
        assertTrue(normBackward == Right(normExpected)) &&
        assertTrue(renamedNormHash == normExpected)
      }
    }
  )

end ReplayChainPropertySpec
