package raider.dsl.lineage.replay

import raider.dsl.lineage.data.{
  DataNode,
  GeneratorInfo,
  LineageDataDocument,
  NodeData,
  NodeKind,
  Usage
}
import raider.dsl.lineage.store.{LineageDataStore, StoreError}

import zio.*
import zio.json.EncoderOps
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** LIN-05D acceptance (packet L39): recorded-source data access only.
  *
  *   - LIN5-D-01 — golden tiny-graph: every dataRef of the document is
  *     reachable, lookups are repeatable, `dataRefs` order is deterministic;
  *   - LIN5-D-02 — exact lookup failures are typed: unknown ref → `Missing`; a
  *     ref to non-model data (phase/tool/delegation) → `KindMismatch`;
  *   - LIN5-D-03 — `fromFile` goes through the typed recording store: a broken
  *     file surfaces as `StoreError.Corrupt` pass-through (never masked into a
  *     success or a different error), an absent file as `StoreError.Missing`.
  *
  * Temp files live ONLY under the dsl module `target/` (packet rule).
  */
object RecordedSourceSpec extends ZIOSpecDefault:

  private val generator =
    GeneratorInfo("raider", "0.1.0-SNAPSHOT", "3.9.0", "21")

  // Exact shape of contracts/lineage/golden/tiny-graph/lineage-data.json:
  // a phase root, one model_call with the recorded Russian prompt/response,
  // two tool_calls; usage carries observedMicroUsd with reservedMicroUsd null.
  private def goldenTinyGraph(): LineageDataDocument =
    LineageDataDocument(
      schemaVersion = 1,
      generator = generator,
      runId = "run-tiny-001",
      nodes = Vector(
        DataNode(
          "n0",
          NodeKind.Phase,
          None,
          1L,
          0L,
          Vector.empty,
          Some("d0")
        ),
        DataNode(
          "n1",
          NodeKind.ModelCall,
          Some("n0"),
          2L,
          120L,
          Vector.empty,
          Some("d1")
        ),
        DataNode(
          "n2",
          NodeKind.ToolCall,
          Some("n1"),
          3L,
          340L,
          Vector.empty,
          Some("d2")
        ),
        DataNode(
          "n3",
          NodeKind.ToolCall,
          Some("n1"),
          4L,
          510L,
          Vector.empty,
          Some("d3")
        )
      ),
      data = Map(
        "d0" -> NodeData.PhaseData("investigate", None),
        "d1" -> NodeData.ModelCallData(
          "mock-coder",
          1,
          "Find the entry point and explain how to run the tests.",
          "The entry point is raider.cli.Main; run the tests with make quality-changed.",
          Some(Usage(None, Some(1500L), false))
        ),
        "d2" -> NodeData.ToolCallData(
          "read_file",
          "call-001",
          "ReadOnly",
          Some("Succeeded"),
          "path=README.md",
          Some("# Raider development entrypoints (RAI-003 stage B).")
        ),
        "d3" -> NodeData.ToolCallData(
          "edit_file",
          "call-002",
          "Mutating",
          Some("Failed"),
          "path=docs/notes.md",
          None
        )
      )
    )

  private val expectedModelCall = NodeData.ModelCallData(
    "mock-coder",
    1,
    "Find the entry point and explain how to run the tests.",
    "The entry point is raider.cli.Main; run the tests with make quality-changed.",
    Some(Usage(None, Some(1500L), false))
  )

  // Real frozen golden fixture from the integrated tree (read-only): resolved
  // whether sbt runs tests from the repo root or the module directory.
  private val goldenFixturePath: Path =
    Seq(
      Path.of("contracts/lineage/golden/tiny-graph/lineage-data.json"),
      Path.of("../../contracts/lineage/golden/tiny-graph/lineage-data.json")
    ).find(Files.isRegularFile(_))
      .getOrElse(
        throw new IllegalStateException(
          "LIN5-D-01 requires contracts/lineage/golden/tiny-graph/lineage-data.json in the tree"
        )
      )

  // Packet rule: temp files ONLY under target/. sbt runs tests either from the
  // repo root (modules/dsl/target) or the module dir (target) — cover both.
  private val targetRoot: Path =
    Seq(Path.of("modules/dsl/target"), Path.of("target"))
      .find(Files.isDirectory(_))
      .getOrElse(
        throw new IllegalStateException(
          "LIN-05D spec requires the dsl module target/ directory: temp files must stay under target/"
        )
      )

  private def withTempDir[A](
      use: Path => ZIO[Any, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt(Files.createTempDirectory(targetRoot, "lin5d-replay"))
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

  private def isMissing(
      result: Either[ReplayDataError, NodeData.ModelCallData]
  ): Boolean =
    result.left.toOption.exists {
      case _: ReplayDataError.Missing => true
      case _                          => false
    }

  private def kindMismatch(
      result: Either[ReplayDataError, NodeData.ModelCallData],
      dataRef: String,
      actual: String
  ): Boolean =
    result.left.toOption.contains(
      ReplayDataError.KindMismatch(dataRef, "ModelCallData", actual)
    )

  def spec = suite("RecordedSource LIN-05D")(
    suite("LIN5-D-01 golden tiny-graph resolves, repeatable, deterministic")(
      test(
        "dataRefs lists every document ref in sorted order; repeated calls identical"
      ) {
        val source = RecordedSource(goldenTinyGraph())
        val first = source.dataRefs
        val second = source.dataRefs
        assertTrue(
          first == Vector("d0", "d1", "d2", "d3"),
          first == second,
          first == goldenTinyGraph().data.keys.toVector.sorted
        )
      },
      test(
        "modelOutput resolves the recorded model call by exact DataRef, repeatable"
      ) {
        val source = RecordedSource(goldenTinyGraph())
        val first = source.modelOutput("d1")
        val second = source.modelOutput("d1")
        assertTrue(
          first == Right(expectedModelCall),
          first == second,
          source.has("d1")
        )
      },
      test(
        "every node dataRef of the golden document is present and never Missing"
      ) {
        val doc = goldenTinyGraph()
        val source = RecordedSource(doc)
        val nodeRefs = doc.nodes.flatMap(_.dataRef).toSet
        assertTrue(
          nodeRefs.nonEmpty,
          nodeRefs == source.dataRefs.toSet,
          source.dataRefs.forall(ref => source.has(ref)),
          source.dataRefs.forall(ref => !isMissing(source.modelOutput(ref)))
        )
      },
      test(
        "the real golden fixture file loads through fromFile and resolves d1"
      ) {
        RecordedSource.fromFile(goldenFixturePath) match
          case Left(error) =>
            assertTrue(false, s"fromFile failed: $error" != "")
          case Right(source) =>
            assertTrue(
              source.dataRefs == Vector("d0", "d1", "d2", "d3"),
              source.modelOutput("d1") == Right(expectedModelCall)
            )
      }
    ),
    suite("LIN5-D-02 typed lookup failures")(
      test("unknown ref → Missing; has() is false") {
        val source = RecordedSource(goldenTinyGraph())
        assertTrue(
          source.modelOutput("d-missing") == Left(
            ReplayDataError.Missing("d-missing")
          ),
          isMissing(source.modelOutput("d-missing")),
          !source.has("d-missing")
        )
      },
      test("ref to phase data → KindMismatch(ModelCallData, PhaseData)") {
        val source = RecordedSource(goldenTinyGraph())
        assertTrue(
          source.has("d0"),
          kindMismatch(source.modelOutput("d0"), "d0", "PhaseData")
        )
      },
      test("ref to tool data → KindMismatch(ModelCallData, ToolCallData)") {
        val source = RecordedSource(goldenTinyGraph())
        assertTrue(
          kindMismatch(source.modelOutput("d2"), "d2", "ToolCallData"),
          kindMismatch(source.modelOutput("d3"), "d3", "ToolCallData")
        )
      },
      test(
        "ref to delegation data → KindMismatch; empty document exposes no refs"
      ) {
        val doc = LineageDataDocument(
          schemaVersion = 1,
          generator = generator,
          runId = "run-empty",
          nodes = Vector(
            DataNode(
              "n0",
              NodeKind.Delegation,
              None,
              1L,
              0L,
              Vector.empty,
              Some("d-del")
            )
          ),
          data =
            Map("d-del" -> NodeData.DelegationData("child-job-1", "worker"))
        )
        val source = RecordedSource(doc)
        val empty = RecordedSource(
          LineageDataDocument(1, generator, "run-none", Vector.empty, Map.empty)
        )
        assertTrue(
          kindMismatch(source.modelOutput("d-del"), "d-del", "DelegationData"),
          empty.dataRefs == Vector.empty,
          !empty.has("d-del"),
          isMissing(empty.modelOutput("d-del"))
        )
      }
    ),
    suite("LIN5-D-03 fromFile: typed store errors pass through, never masked")(
      test(
        "a document written by the store loads and behaves as the in-memory source"
      ) {
        val doc = goldenTinyGraph()
        withTempDir { dir =>
          val path = dir.resolve("lineage-data.json")
          for
            written <- ZIO.attempt(LineageDataStore.write(path, doc))
            loaded = RecordedSource.fromFile(path)
            source = loaded.toOption.get
          yield assertTrue(
            written == Right(()),
            loaded.exists(_.dataRefs == Vector("d0", "d1", "d2", "d3")),
            source.dataRefs == Vector("d0", "d1", "d2", "d3"),
            source.modelOutput("d1") == Right(expectedModelCall)
          )
        }
      },
      test(
        "broken file → StoreError.Corrupt pass-through with the raw prefix"
      ) {
        val truncated = goldenTinyGraph().toJson.take(180)
        withTempDir { dir =>
          val path = dir.resolve("broken.json")
          for
            _ <- ZIO.attempt(
              Files.writeString(path, truncated, StandardCharsets.UTF_8)
            )
            result = RecordedSource.fromFile(path)
            onDisk <- ZIO.attempt(Files.readAllBytes(path))
          yield assertTrue(
            result.left.toOption.exists {
              case StoreError.Corrupt(_, detail) =>
                detail.contains(truncated.take(40))
              case _ => false
            },
            onDisk.sameElements(
              truncated.getBytes(StandardCharsets.UTF_8)
            ) // untouched, no repair
          )
        }
      },
      test("absent file → StoreError.Missing") {
        withTempDir { dir =>
          val path = dir.resolve("no-such-lineage-data.json")
          val result = RecordedSource.fromFile(path)
          assertTrue(result == Left(StoreError.Missing(path)))
        }
      }
    )
  )
