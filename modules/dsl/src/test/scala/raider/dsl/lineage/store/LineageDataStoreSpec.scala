package raider.dsl.lineage.store

import raider.dsl.lineage.data.{
  DataNode,
  GeneratorInfo,
  LineageDataDocument,
  NodeData,
  NodeKind,
  Usage
}

import zio.*
import zio.json.EncoderOps
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** LIN-02D acceptance (packet L13): recording-store mechanics only.
  *
  *   - LIN2-D-01 — write→read roundtrip is byte-identical to the document's
  *     codec-canonical form;
  *   - LIN2-D-02 — truncated/corrupt file → typed `StoreError.Corrupt` with the
  *     raw prefix in diagnostics; a valid-looking prefix is never masked into
  *     success; the store never repairs or replays on its own;
  *   - LIN2-D-03 — parallel writes to distinct paths both succeed; writes to
  *     the same path follow the documented single-writer contract: the
  *     overlapping writer gets a typed `StoreError.Io`, never silent
  *     corruption.
  *
  * Temp files live ONLY under the dsl module `target/` (packet rule).
  */
object LineageDataStoreSpec extends ZIOSpecDefault:

  private val generator = GeneratorInfo("raider", "0.7.0", "3.9.0", "21")

  // All four NodeKinds and all four NodeData variants; `data` built in
  // non-sorted order — the canonical encoder sorts keys, so every write of
  // this document is byte-identical regardless of map order (LIN2-D-01).
  private def sampleDoc(runId: String = "run-lin2d"): LineageDataDocument =
    LineageDataDocument(
      schemaVersion = 1,
      generator = generator,
      runId = runId,
      nodes = Vector(
        DataNode(
          "n0",
          NodeKind.Phase,
          None,
          1L,
          0L,
          Vector.empty,
          Some("d-phase")
        ),
        DataNode(
          "n1",
          NodeKind.ModelCall,
          Some("n0"),
          2L,
          5L,
          Vector("n0"),
          Some("d-model")
        ),
        DataNode(
          "n2",
          NodeKind.ToolCall,
          Some("n1"),
          3L,
          9L,
          Vector("n1"),
          Some("d-tool-1")
        ),
        DataNode(
          "n3",
          NodeKind.Delegation,
          Some("n0"),
          4L,
          14L,
          Vector("n1"),
          Some("d-del")
        ),
        DataNode("n4", NodeKind.Phase, Some("n0"), 5L, 30L, Vector.empty, None)
      ),
      data = Map(
        "d-tool-1" -> NodeData.ToolCallData(
          "files.write",
          "call-9",
          "Mutating",
          Some("Succeeded"),
          "path=out.txt",
          Some("ok")
        ),
        "d-model" -> NodeData.ModelCallData(
          "glm-5.3-flash",
          1,
          "prompt text",
          "response text",
          Some(Usage(Some(1500L), Some(1200L), false))
        ),
        "d-phase" -> NodeData.PhaseData("setup", Some("bootstrap")),
        "d-del" -> NodeData.DelegationData("child-job-1", "worker")
      )
    )

  // Packet rule: temp files ONLY under target/. sbt runs tests either from the
  // repo root (modules/dsl/target) or the module dir (target) — cover both.
  private val targetRoot: Path =
    Seq(Path.of("modules/dsl/target"), Path.of("target"))
      .find(Files.isDirectory(_))
      .getOrElse(
        throw new IllegalStateException(
          "LIN-02D spec requires the dsl module target/ directory: temp files must stay under target/"
        )
      )

  private def withTempDir[A](
      use: Path => ZIO[Any, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt(Files.createTempDirectory(targetRoot, "lin2d-store"))
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

  private def bytesOf(path: Path): ZIO[Any, Throwable, Array[Byte]] =
    ZIO.attempt(Files.readAllBytes(path))

  private def fileCount(dir: Path, suffix: String): Int =
    Using.resource(Files.walk(dir)) { stream =>
      stream.iterator().asScala.count(_.getFileName.toString.endsWith(suffix))
    }

  private def isCorrupt(
      result: Either[StoreError, LineageDataDocument]
  ): Boolean =
    result.left.toOption.exists {
      case _: StoreError.Corrupt => true
      case _                     => false
    }

  private def corruptDetail(
      result: Either[StoreError, LineageDataDocument]
  ): Option[String] =
    result.left.toOption.collect { case StoreError.Corrupt(_, detail) =>
      detail
    }

  private def isIo(result: Either[StoreError, LineageDataDocument]): Boolean =
    result.left.toOption.exists {
      case _: StoreError.Io => true
      case _                => false
    }

  def spec = suite("LineageDataStore LIN-02D")(
    suite("LIN2-D-01 atomic write + byte-identical canonical roundtrip")(
      test(
        "write stores the codec-canonical bytes; read returns the same document"
      ) {
        val doc = sampleDoc()
        val canonical = doc.toJson
        withTempDir { dir =>
          val path = dir.resolve("lineage-data.json")
          for
            written <- ZIO.attempt(LineageDataStore.write(path, doc))
            onDisk <- bytesOf(path)
            readBack = LineageDataStore.read(path)
          yield assertTrue(
            written == Right(()),
            readBack == Right(doc),
            onDisk.sameElements(canonical.getBytes(StandardCharsets.UTF_8)),
            // compare as strings: Array[Byte] inside Either would be
            // identity-compared by ==
            readBack.map(_.toJson) == Right(canonical)
          )
        }
      },
      test("a second write to the same path replaces the whole document") {
        val first = sampleDoc("run-first")
        val second = sampleDoc("run-second")
        withTempDir { dir =>
          val path = dir.resolve("lineage-data.json")
          for
            _ <- ZIO.attempt(LineageDataStore.write(path, first))
            _ <- ZIO.attempt(LineageDataStore.write(path, second))
            onDisk <- bytesOf(path)
            readBack = LineageDataStore.read(path)
          yield assertTrue(
            readBack == Right(second),
            onDisk.sameElements(second.toJson.getBytes(StandardCharsets.UTF_8))
          )
        }
      },
      test(
        "missing parent directories are created; successful writes leave no tmp files"
      ) {
        val doc = sampleDoc()
        withTempDir { dir =>
          val path = dir.resolve("nested/deep/lineage-data.json")
          for
            _ <- ZIO.attempt(LineageDataStore.write(path, doc))
            readBack = LineageDataStore.read(path)
            leftovers = fileCount(dir, ".tmp")
          yield assertTrue(readBack == Right(doc), leftovers == 0)
        }
      }
    ),
    suite("LIN2-D-02 corruption is typed, never masked, never self-repaired")(
      test(
        "truncated tail → Corrupt carrying the raw prefix; file left untouched"
      ) {
        val truncated = sampleDoc().toJson.take(200)
        withTempDir { dir =>
          val path = dir.resolve("trunc.json")
          for
            _ <- ZIO.attempt(
              Files.writeString(path, truncated, StandardCharsets.UTF_8)
            )
            first = LineageDataStore.read(path)
            second = LineageDataStore.read(path)
            onDisk <- bytesOf(path)
          yield assertTrue(
            isCorrupt(first),
            corruptDetail(first).exists(_.contains(truncated.take(40))),
            first == second,
            onDisk.sameElements(truncated.getBytes(StandardCharsets.UTF_8))
          )
        }
      },
      test(
        "complete JSON followed by garbage tail → Corrupt, not success"
      ) {
        val doc = sampleDoc()
        val poisoned = doc.toJson + "  <-- torn tail bytes after a whole object"
        withTempDir { dir =>
          val path = dir.resolve("garbage-tail.json")
          for
            _ <- ZIO.attempt(
              Files.writeString(path, poisoned, StandardCharsets.UTF_8)
            )
            result = LineageDataStore.read(path)
          yield assertTrue(
            isCorrupt(result),
            result != Right(doc),
            corruptDetail(result).exists(_.contains(doc.toJson.take(40)))
          )
        }
      },
      test(
        "two concatenated whole documents → Corrupt (valid prefix not masked)"
      ) {
        val doc = sampleDoc()
        val doubled = doc.toJson + doc.toJson
        withTempDir { dir =>
          val path = dir.resolve("doubled.json")
          for
            _ <- ZIO.attempt(
              Files.writeString(path, doubled, StandardCharsets.UTF_8)
            )
            result = LineageDataStore.read(path)
          yield assertTrue(
            isCorrupt(result),
            corruptDetail(result).exists(_.contains(doc.toJson.take(40)))
          )
        }
      },
      test(
        "insignificant surrounding whitespace is accepted (single value + ws)"
      ) {
        val doc = sampleDoc()
        withTempDir { dir =>
          val path = dir.resolve("padded.json")
          for
            _ <- ZIO.attempt(
              Files.writeString(path, "\n  " + doc.toJson + "\n")
            )
            result = LineageDataStore.read(path)
          yield assertTrue(result == Right(doc))
        }
      },
      test(
        "pretty-printed but valid JSON still reads (strictness is at the value boundary, not canonicality)"
      ) {
        // The sample doc's string values contain no ':' or ',', so the
        // replacements below only widen structural separators — valid JSON.
        val doc = sampleDoc()
        val pretty = doc.toJson.replace(",", " , ").replace(":", " : ")
        withTempDir { dir =>
          val path = dir.resolve("pretty.json")
          for
            _ <- ZIO.attempt(Files.writeString(path, pretty))
            result = LineageDataStore.read(path)
          yield assertTrue(result == Right(doc))
        }
      },
      test("empty file → Corrupt") {
        withTempDir { dir =>
          val path = dir.resolve("empty.json")
          for
            _ <- ZIO.attempt(Files.writeString(path, ""))
            result = LineageDataStore.read(path)
          yield assertTrue(isCorrupt(result))
        }
      },
      test(
        "valid JSON of the wrong shape → Corrupt (decode failure, mechanics layer)"
      ) {
        withTempDir { dir =>
          val path = dir.resolve("wrong-shape.json")
          for
            _ <- ZIO.attempt(
              Files.writeString(path, """{"x":1}""", StandardCharsets.UTF_8)
            )
            result = LineageDataStore.read(path)
          yield assertTrue(isCorrupt(result))
        }
      },
      test("absent file → Missing") {
        withTempDir { dir =>
          val absent = dir.resolve("absent.json")
          val result = LineageDataStore.read(absent)
          assertTrue(result == Left(StoreError.Missing(absent)))
        }
      },
      test("directory at the path → Io, not Missing") {
        withTempDir { dir =>
          val target = dir.resolve("a-directory")
          for
            _ <- ZIO.attempt(Files.createDirectory(target))
            result = LineageDataStore.read(target)
          yield assertTrue(isIo(result))
        }
      }
    ),
    suite("LIN2-D-03 concurrent writes")(
      test("parallel writes to distinct paths all succeed and read back") {
        val docs = (1 to 8).map(i => sampleDoc(s"run-$i"))
        withTempDir { dir =>
          val pairs = docs.zipWithIndex.map((doc, i) =>
            doc -> dir.resolve(s"doc-$i.json")
          )
          for
            results <- ZIO.foreachPar(pairs) { case (doc, path) =>
              ZIO.attempt(LineageDataStore.write(path, doc))
            }
            readBack <- ZIO.foreach(pairs) { case (doc, path) =>
              ZIO
                .attempt(LineageDataStore.read(path))
                .map(_.toOption.contains(doc))
            }
            leftovers = fileCount(dir, ".tmp")
          yield assertTrue(
            results.forall(_.isRight),
            readBack.forall(identity),
            leftovers == 0
          )
        }
      },
      test(
        "overlapping writes to one path: every failure is typed Io, final file is a whole document"
      ) {
        val docs = (1 to 8).map(i => sampleDoc(s"run-$i"))
        withTempDir { dir =>
          val target = dir.resolve("shared.json")
          for
            results <- ZIO.foreachPar(docs)(doc =>
              ZIO.attempt(LineageDataStore.write(target, doc))
            )
            finalRead = LineageDataStore.read(target)
          yield assertTrue(
            results.exists(_.isRight),
            results.forall {
              case Right(())                   => true
              case Left(_: StoreError.Io)      => true
              case Left(_: StoreError.Corrupt) => false
              case Left(_: StoreError.Missing) => false
            },
            finalRead.fold(_ => false, docs.contains)
          )
        }
      },
      test(
        "held single-writer guard → the overlapping write is refused with typed Io; target untouched"
      ) {
        // Deterministic form of the refusal branch: the per-path guard is
        // pinned (package-private accessor, same slice as JobJournal's writer
        // model) exactly as an in-flight writer would hold it. ReentrantLock
        // is reentrant, so the refused attempt must come from ANOTHER thread;
        // lock/unlock must run on one and the same thread (ZIO fibers
        // migrate between carrier threads), hence one blocking block that
        // pins, awaits the child attempt, and releases.
        val docA = sampleDoc("run-a")
        val docB = sampleDoc("run-b")
        withTempDir { dir =>
          val target = dir.resolve("pinned.json")
          for
            _ <- ZIO.attempt(LineageDataStore.write(target, docA))
            refused <- ZIO.attemptBlocking {
              val pinned = LineageDataStore.writeLockFor(target)
              pinned.lock()
              try
                val task = new java.util.concurrent.FutureTask[
                  Either[StoreError, Unit]
                ](() => LineageDataStore.write(target, docB))
                val worker = new Thread(task, "lin2d-second-writer")
                worker.start()
                task.get() // fails fast on tryLock: no real blocking window
              finally pinned.unlock()
            }
            onDisk <- bytesOf(target)
            stillReadable = LineageDataStore.read(target)
          yield assertTrue(
            refused.left.toOption.exists {
              case StoreError.Io(_, detail) => detail.contains("single-writer")
              case _                        => false
            },
            onDisk.sameElements(docA.toJson.getBytes(StandardCharsets.UTF_8)),
            stillReadable == Right(docA)
          )
        }
      }
    )
  )
