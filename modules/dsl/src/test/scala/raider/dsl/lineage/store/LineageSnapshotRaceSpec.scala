package raider.dsl.lineage.store

import raider.dsl.lineage.data.{
  DataNode,
  GeneratorInfo,
  LineageDataDocument,
  NodeData,
  NodeKind,
  Usage
}
import raider.dsl.lineage.proj.{LineageRenderer, LineageView, NodeView}
import raider.dsl.lineage.proj.NodeKind as ViewKind

import zio.*
import zio.json.EncoderOps
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** LIN-14 acceptance (LIN2-03 remainder, docs/tasks/lineage.md; docs/lineage.md
  * §9.2 "конкурентное чтение проекции (LIN2-03) покрыто частично").
  *
  * Property: while a single writer atomically replaces a lineage-data document
  * (tmp + ATOMIC_MOVE) with an alternating whole document, CONCURRENT readers
  * of the same target observe either the old or the new WHOLE document —
  *   - never a torn/partial one (full document equality against the two
  *     known-good documents, plus schemaVersion == 1 and every node dataRef
  *     resolving into `data`);
  *   - the race is never masked as a typed failure: ANY `StoreError.Missing` /
  *     `Corrupt` / `Io` observed by a concurrent reader is a violation.
  *
  * Fixtures:
  *   - LIN2-03-01 — 100 seeded concurrent read/write episodes (≥100 iterations,
  *     thousands of observed reads); 0 partial or masked reads;
  *   - LIN2-03-02 — the identical seeded episode repeated 3× in-spec, all
  *     green; every violation is reported typed (StoreError case + store
  *     detail) so a genuine defect becomes a reproducer, never a weakened
  *     assertion;
  *   - LIN2-03-03 — re-projection (read → view → `LineageRenderer.render`) of
  *     an UNCHANGED document is byte-identical across concurrent readers, and
  *     old/new documents project to byte-DIFFERENT sources.
  *
  * Determinism honesty: the DOCUMENTS (seeds, payloads, structure) are fully
  * deterministic via a local LCG; thread interleaving is not, so assertions are
  * invariants that hold for every interleaving. A green run pins the store's
  * atomicity discipline (whole-document visibility), NOT durability (the
  * no-fsync contract is unchanged) and NOT multi-writer semantics (the harness
  * uses exactly one writer per target, per the store's single-writer model).
  *
  * Temp files live ONLY under the dsl module `target/` (packet rule).
  */
object LineageSnapshotRaceSpec extends ZIOSpecDefault:

  private val generator = GeneratorInfo("raider", "0.7.0", "3.9.0", "21")

  // Harness shape: 100 episodes × (4 readers × ≥8 reads) concurrent with
  // 1 writer × 24 alternating writes → ≥3200 observed reads per fixture run,
  // well above the ≥100 required iterations.
  private val Episodes = 100
  private val ReadersPerEpisode = 4
  private val FixedReadsPerReader = 8
  private val MaxSpinReadsPerReader = 64
  private val WritesPerEpisode = 24
  private val Repeats = 3
  private val ProjectionReaders = 6
  private val ProjectionRounds = 12

  // Fixed seeds: episode i uses EpisodeSeedBase + i; repeats reuse one seed.
  private val EpisodeSeedBase = 20261012L
  private val RepeatSeed = 20261013L
  private val ProjectionSeed = 20261014L

  /** Tiny linear congruential generator: deterministic payloads without
    * depending on zio-test's Random service.
    */
  private final class Lcg(seed: Long):
    private var state: Long = seed

    def nextBounded(mod: Int): Int =
      state = state * 6364136223846793005L + 1442695040888963407L
      ((state >>> 33) % mod.toLong).toInt

  /** A whole lineage-data document with deterministic but seed-varied payload:
    * 5..6 nodes covering all four NodeKinds, every node carrying a resolvable
    * dataRef (the projection mapper is total on such documents).
    */
  private def buildDoc(rng: Lcg, runId: String): LineageDataDocument =
    val models = Vector("glm-5.3-flash", "mock-coder", "mock-scout")
    val tools = Vector("files.write", "grep_search", "shell.run")
    val recoveries = Vector("Mutating", "Idempotent", "Read")
    val agents = Vector("worker", "scout")
    val phases = Vector("plan", "verify", "bootstrap")
    val model = models(rng.nextBounded(models.length))
    val round = 1 + rng.nextBounded(3)
    val tool = tools(rng.nextBounded(tools.length))
    val recovery = recoveries(rng.nextBounded(recoveries.length))
    val agent = agents(rng.nextBounded(agents.length))
    val phaseName = phases(rng.nextBounded(phases.length))
    val withUsage = rng.nextBounded(2) == 0
    val withExtraTool = rng.nextBounded(2) == 0

    val nodes = Vector(
      DataNode("n0", NodeKind.Phase, None, 1L, 1000L, Vector.empty, Some("d0")),
      DataNode(
        "n1",
        NodeKind.ModelCall,
        Some("n0"),
        2L,
        1100L,
        Vector("n0"),
        Some("d1")
      ),
      DataNode(
        "n2",
        NodeKind.ToolCall,
        Some("n1"),
        3L,
        1200L,
        Vector("n1"),
        Some("d2")
      ),
      DataNode(
        "n3",
        NodeKind.Delegation,
        Some("n1"),
        4L,
        1300L,
        Vector("n1"),
        Some("d3")
      ),
      DataNode(
        "n4",
        NodeKind.Phase,
        Some("n0"),
        5L,
        1400L,
        Vector("n0"),
        Some("d4")
      )
    ) ++ (if withExtraTool then
            Vector(
              DataNode(
                "n5",
                NodeKind.ToolCall,
                Some("n2"),
                6L,
                1500L,
                Vector("n2"),
                Some("d5")
              )
            )
          else Vector.empty)

    val baseData = Map(
      "d2" -> NodeData.ToolCallData(
        tool,
        "call-1",
        recovery,
        if withUsage then Some("Succeeded") else None,
        "args ok",
        if withUsage then Some("tail ok") else None
      ),
      "d0" -> NodeData.PhaseData(phaseName, Some("note ok")),
      "d1" -> NodeData.ModelCallData(
        model,
        round,
        "prompt text",
        "response text",
        if withUsage then Some(Usage(Some(1500L), Some(1200L), false)) else None
      ),
      "d4" -> NodeData.PhaseData(phaseName + "-tail", None),
      "d3" -> NodeData.DelegationData("child-job", agent)
    )
    val data =
      if withExtraTool then
        baseData + ("d5" -> NodeData.ToolCallData(
          tool,
          "call-2",
          recovery,
          Some("Failed"),
          "args 2",
          None
        ))
      else baseData

    LineageDataDocument(schemaVersion = 1, generator, runId, nodes, data)

  private def describe(error: StoreError): String = error match
    case StoreError.Missing(path) => s"Missing(${path.getFileName})"
    case StoreError.Corrupt(_, detail) =>
      s"Corrupt($detail)"
    case StoreError.Io(path, detail) =>
      s"Io(${path.getFileName}: $detail)"

  /** The snapshot property for ONE observed read: the document must be whole
    * and valid — exactly one of the two known-good documents. Any decode-level
    * mask (Missing/Corrupt/Io) is reported by the caller as a violation.
    */
  private def wholeDocViolation(
      doc: LineageDataDocument,
      oldDoc: LineageDataDocument,
      newDoc: LineageDataDocument
  ): Option[String] =
    if doc != oldDoc && doc != newDoc then
      Some(
        s"partial/foreign read: runId=${doc.runId}, nodes=${doc.nodes.size}, " +
          s"schemaVersion=${doc.schemaVersion}, data=${doc.data.size}"
      )
    else if doc.schemaVersion != 1 then
      Some(s"invalid schemaVersion=${doc.schemaVersion}")
    else if doc.nodes.exists(n =>
        n.dataRef.exists(ref => !doc.data.contains(ref))
      )
    then Some("broken dataRef (torn document)")
    else None

  private final case class EpisodeResult(
      seed: Long,
      reads: Int,
      fromOld: Int,
      fromNew: Int,
      violations: Vector[String]
  )

  /** One race episode on ONE target path: pre-write `oldDoc`, then `readers`
    * reader fibers (fixed reads + bounded spin until the writer is done) run
    * concurrently with ONE writer alternating `newDoc`/`oldDoc` whole writes.
    */
  private def raceEpisode(
      seed: Long,
      target: Path
  ): ZIO[Any, Throwable, EpisodeResult] =
    val oldDoc = buildDoc(new Lcg(seed), s"run-old-$seed")
    val newDoc = buildDoc(new Lcg(seed ^ 0x5deece66dL), s"run-new-$seed")
    for
      pre <- ZIO.attemptBlocking(LineageDataStore.write(target, oldDoc))
      _ <- pre match
        case Right(()) => ZIO.unit
        case Left(error) =>
          ZIO.fail(
            new IllegalStateException(
              s"LIN2-03 harness: pre-write refused: ${describe(error)}"
            )
          )
      flag <- ZIO.succeed(new AtomicBoolean(false))
      readers <- ZIO.foreach((1 to ReadersPerEpisode).toList) { readerIdx =>
        ZIO.attemptBlocking {
          val seen = List.newBuilder[LineageDataDocument]
          val bad = List.newBuilder[String]
          var i = 0
          var keepGoing = true
          while keepGoing do
            LineageDataStore.read(target) match
              case Right(doc) =>
                wholeDocViolation(doc, oldDoc, newDoc) match
                  case Some(problem) =>
                    bad += s"reader#$readerIdx read#$i: $problem"
                  case None => seen += doc
              case Left(error) =>
                bad += s"reader#$readerIdx read#$i: race masked as " +
                  describe(error)
            i += 1
            keepGoing = i < FixedReadsPerReader ||
              (i < FixedReadsPerReader + MaxSpinReadsPerReader && !flag.get())
          (seen.result(), bad.result())
        }.fork
      }
      // Single writer per target (the store's writer model): one fiber,
      // sequential whole-document writes; the flag is released even on defect
      // so the bounded reader spin can never hang.
      writeBad <- ZIO
        .attemptBlocking {
          val bad = List.newBuilder[String]
          var w = 0
          while w < WritesPerEpisode do
            LineageDataStore
              .write(target, if w % 2 == 0 then newDoc else oldDoc) match
              case Right(()) => ()
              case Left(error) =>
                bad += s"write#$w: ${describe(error)}"
            w += 1
          bad.result()
        }
        .ensuring(ZIO.succeed(flag.set(true)))
      readOuts <- ZIO.foreach(readers)(_.join)
    yield
      val (seens, bads) = readOuts.unzip
      val seenDocs = seens.flatten
      EpisodeResult(
        seed = seed,
        reads = seenDocs.size,
        fromOld = seenDocs.count(_ == oldDoc),
        fromNew = seenDocs.count(_ == newDoc),
        violations = bads.flatten.toVector ++ writeBad.toVector
      )

  // Packet rule: temp files ONLY under target/. sbt runs tests either from the
  // repo root (modules/dsl/target) or the module dir (target) — cover both.
  private val targetRoot: Path =
    Seq(Path.of("modules/dsl/target"), Path.of("target"))
      .find(Files.isDirectory(_))
      .getOrElse(
        throw new IllegalStateException(
          "LIN-14 spec requires the dsl module target/ directory: temp files must stay under target/"
        )
      )

  private def withTempDir[A](
      use: Path => ZIO[Any, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt(Files.createTempDirectory(targetRoot, "lin14-race"))
    )((dir: Path) => ZIO.attempt(deleteRecursively(dir)).ignore)(use)

  private def deleteRecursively(dir: Path): Unit =
    Using.resource(Files.walk(dir)) { stream =>
      stream
        .iterator()
        .asScala
        .toVector
        .reverse
        .foreach(p => Files.deleteIfExists(p))
      ()
    }

  /** Test-local deterministic projection of a whole document into a
    * [[LineageView]] (mirrors the product mapping in
    * `raider.cli.lineage.SessionGraphView`: the document's NodeData determines
    * the rendered NodeKind). Total on the documents this spec builds: every
    * node has a resolvable dataRef.
    */
  private def viewOf(doc: LineageDataDocument): Either[String, LineageView] =
    val childrenByParent: Map[String, Vector[DataNode]] =
      doc.nodes
        .filter(_.parentId.isDefined)
        .groupBy(_.parentId.get)
        .map { case (parent, nodes) => parent -> nodes.sortBy(_.seq) }
    val roots = doc.nodes.filter(_.parentId.isEmpty).sortBy(_.seq)

    def dataOf(node: DataNode): Either[String, NodeData] =
      node.dataRef.flatMap(doc.data.get) match
        case Some(data) => Right(data)
        case None =>
          Left(s"node ${node.id}: unresolvable dataRef ${node.dataRef}")

    def kindOf(node: DataNode): Either[String, ViewKind] =
      dataOf(node).flatMap {
        case m: NodeData.ModelCallData =>
          Right(ViewKind.ModelCall(m.model, m.round))
        case t: NodeData.ToolCallData =>
          Right(ViewKind.ToolCall(t.tool, t.recovery))
        case d: NodeData.DelegationData =>
          Right(ViewKind.Delegation(d.agent))
        case p: NodeData.PhaseData => Right(ViewKind.Phase(p.name))
      }

    def traverse[A, B](
        as: Vector[A]
    )(f: A => Either[String, B]): Either[String, Vector[B]] =
      as.foldLeft[Either[String, Vector[B]]](Right(Vector.empty)) { (acc, a) =>
        for bs <- acc; b <- f(a) yield bs :+ b
      }

    def toNodeView(node: DataNode): Either[String, NodeView] =
      for
        kind <- kindOf(node)
        kids <- traverse(childrenByParent.getOrElse(node.id, Vector.empty))(
          toNodeView
        )
      yield NodeView(node.id, kind, node.parentId, kids, node.dataRef)

    traverse(roots)(toNodeView).map { rootViews =>
      new LineageView:
        def runId: String = doc.runId
        def roots: Vector[NodeView] = rootViews
    }

  private def renderDoc(doc: LineageDataDocument): Either[String, String] =
    viewOf(doc).flatMap(view =>
      LineageRenderer.render(view).left.map(error => s"render failed: $error")
    )

  def spec = suite("LineageDataStore LIN2-03 concurrent-read snapshot")(
    suite("LIN2-03-01 whole-document visibility under concurrent write")(
      test(
        "100 seeded episodes × 4 readers vs 1 alternating writer: every read is a whole valid document; no Missing/Corrupt/Io masks"
      ) {
        withTempDir { dir =>
          for results <- ZIO.foreach((1 to Episodes).toList) { i =>
              raceEpisode(
                EpisodeSeedBase + i.toLong,
                dir.resolve(s"ep-$i").resolve("lineage-data.json")
              )
            }
          yield
            val violations = results.flatMap(_.violations)
            val totalReads = results.map(_.reads).sum
            assertTrue(
              results.size == Episodes,
              violations.isEmpty,
              totalReads >= Episodes * ReadersPerEpisode * FixedReadsPerReader,
              // Liveness of the harness: across the campaign both document
              // generations were actually observed whole (the pre-written old
              // document and the alternating new one).
              results.map(_.fromOld).sum > 0,
              results.map(_.fromNew).sum > 0
            )
        }
      }
    ),
    suite("LIN2-03-02 deterministic repeats, typed diagnostics")(
      test(
        "the identical seeded episode repeated 3× is green every time; any failure is reported typed, never weakened"
      ) {
        withTempDir { dir =>
          for repeats <- ZIO.foreach((1 to Repeats).toList) { r =>
              raceEpisode(
                RepeatSeed,
                dir.resolve(s"repeat-$r").resolve("lineage-data.json")
              )
            }
          yield
            val violations = repeats.flatMap(_.violations)
            val minReads = ReadersPerEpisode * FixedReadsPerReader
            assertTrue(
              repeats.size == Repeats,
              violations.isEmpty,
              repeats.forall(_.reads >= minReads),
              repeats.forall(r => r.fromOld + r.fromNew == r.reads)
            )
        }
      }
    ),
    suite("LIN2-03-03 projection byte-identity for an unchanged document")(
      test(
        "concurrent read→projection rounds of an unchanged document all render byte-identical sources"
      ) {
        val doc = buildDoc(new Lcg(ProjectionSeed), "run-projection")
        withTempDir { dir =>
          val target = dir.resolve("lineage-data.json")
          for
            pre <- ZIO.attemptBlocking(LineageDataStore.write(target, doc))
            _ <- pre match
              case Right(()) => ZIO.unit
              case Left(error) =>
                ZIO.fail(
                  new IllegalStateException(
                    s"LIN2-03 harness: seed write refused: ${describe(error)}"
                  )
                )
            expected = renderDoc(doc)
            rounds <- ZIO.foreachPar((1 to ProjectionReaders).toList) { _ =>
              ZIO.attemptBlocking(
                (1 to ProjectionRounds).toList.map { _ =>
                  LineageDataStore.read(target) match
                    case Right(again) =>
                      if again != doc then
                        Left("unchanged document re-read differently")
                      else renderDoc(again)
                    case Left(error) =>
                      Left(s"read failed: ${describe(error)}")
                }
              )
            }
          yield
            val flat = rounds.flatten
            val failures = flat.collect { case Left(message) => message }
            val sources = flat.collect { case Right(source) => source }
            val expectedSource = expected.getOrElse(
              "<LIN2-03 harness defect: reference render failed>"
            )
            assertTrue(
              failures.isEmpty,
              expected.isRight,
              sources.size == ProjectionReaders * ProjectionRounds,
              sources.forall(_ == expectedSource)
            )
        }
      },
      test(
        "sequential re-read ×2 and re-render ×2 are byte-identical; canonical bytes on disk are stable"
      ) {
        val doc = buildDoc(new Lcg(ProjectionSeed), "run-projection")
        withTempDir { dir =>
          val target = dir.resolve("lineage-data.json")
          for
            _ <- ZIO.attemptBlocking(LineageDataStore.write(target, doc))
            first <- ZIO.attemptBlocking(LineageDataStore.read(target))
            second = LineageDataStore.read(target)
            onDisk <- ZIO.attempt(
              Files.readString(target, StandardCharsets.UTF_8)
            )
            renderedTwice = renderDoc(doc).map(source =>
              source :: source :: Nil
            )
          yield assertTrue(
            first == Right(doc),
            second == Right(doc),
            first.map(_.toJson) == second.map(_.toJson),
            onDisk == doc.toJson,
            renderedTwice.exists(_.distinct.size == 1)
          )
        }
      },
      test(
        "old and new documents project to byte-DIFFERENT sources (identity pin is non-trivial)"
      ) {
        val oldDoc = buildDoc(new Lcg(RepeatSeed), "run-old")
        val newDoc = buildDoc(new Lcg(RepeatSeed ^ 0x5deece66dL), "run-new")
        assertTrue(
          renderDoc(oldDoc) != renderDoc(newDoc),
          oldDoc.toJson != newDoc.toJson
        )
      }
    )
  )
