package raider.dsl.lineage.data

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

object LineageDataSpec extends ZIOSpecDefault:

  private val generator = GeneratorInfo("raider", "0.7.0", "3.9.0", "21")

  // Six data keys built in non-sorted order: canonical output must stay
  // byte-stable regardless of map construction order (LIN2-B-01).
  private def sampleDoc(runId: String = "run-2026-10-10"): LineageDataDocument =
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
          NodeKind.ToolCall,
          Some("n1"),
          4L,
          14L,
          Vector("n1"),
          Some("d-tool-2")
        ),
        DataNode(
          "n4",
          NodeKind.Delegation,
          Some("n0"),
          5L,
          20L,
          Vector("n1"),
          Some("d-del")
        ),
        DataNode("n5", NodeKind.Phase, Some("n0"), 6L, 30L, Vector.empty, None)
      ),
      data = Map(
        "d-tool-2" -> NodeData.ToolCallData(
          "files.write",
          "call-9",
          "Mutating",
          None,
          "path=out.txt",
          None
        ),
        "d-model" -> NodeData.ModelCallData(
          "glm-5.3-flash",
          1,
          "prompt text",
          "response text",
          Some(Usage(Some(1500L), Some(1200L), false))
        ),
        "d-phase" -> NodeData.PhaseData("setup", Some("bootstrap")),
        "d-del" -> NodeData.DelegationData("child-job-1", "worker"),
        "d-tool-1" -> NodeData.ToolCallData(
          "state.get",
          "call-4",
          "ReadOnly",
          Some("Succeeded"),
          "key=x",
          Some("value")
        ),
        "d-tail" -> NodeData.PhaseData("verify", None)
      )
    )

  // Shape of contracts/lineage/golden/tiny-graph/lineage-data.json (ASCII text
  // substitutions only): root node with explicit null parentId, phase note
  // null, usage reservedMicroUsd null, tool resultTail null (LIN2B-F1-03).
  private def goldenDoc(): LineageDataDocument =
    LineageDataDocument(
      schemaVersion = 1,
      generator = GeneratorInfo("raider", "0.1.0-SNAPSHOT", "3.9.0", "21"),
      runId = "run-tiny-001",
      nodes = Vector(
        DataNode("n0", NodeKind.Phase, None, 1L, 0L, Vector.empty, Some("d0")),
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
          "entry point",
          "cli main",
          Some(Usage(None, Some(1500L), false))
        ),
        "d2" -> NodeData.ToolCallData(
          "read_file",
          "call-001",
          "ReadOnly",
          Some("Succeeded"),
          "path=README.md",
          Some("readme tail")
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

  // --- AST helpers (no JSON-schema tool in the module: the frozen-schema
  // shape is pinned structurally against the encoded AST + literals). ---

  private def astOf(doc: LineageDataDocument): Json =
    summon[JsonEncoder[LineageDataDocument]].toJsonAST(doc) match
      case Right(ast) => ast
      case Left(error) =>
        throw new IllegalStateException(s"AST encode failed: $error")

  private def fieldOf(json: Json, key: String): Option[Json] = json match
    case Json.Obj(fields) => fields.find(_._1 == key).map(_._2)
    case _                => None

  private def keysOf(json: Json): Set[String] = json match
    case Json.Obj(fields) => fields.map(_._1).toSet
    case _                => Set.empty

  private def nodesOf(doc: LineageDataDocument): Vector[Json] =
    fieldOf(astOf(doc), "nodes") match
      case Some(Json.Arr(items)) => items.toVector
      case _                     => Vector.empty

  private def dataEntriesOf(doc: LineageDataDocument): Map[String, Json] =
    fieldOf(astOf(doc), "data") match
      case Some(Json.Obj(fields)) => fields.toMap
      case _                      => Map.empty

  private def withTempDir[A](
      use: Path => ZIO[Any, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt(Files.createTempDirectory("lin2b"))
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

  private def writeFile(
      path: Path,
      content: String
  ): ZIO[Any, Throwable, Unit] =
    ZIO.attempt(Files.writeString(path, content, StandardCharsets.UTF_8)).unit

  private def tmpCount(dir: Path): Int =
    Files
      .list(dir)
      .iterator()
      .asScala
      .count(_.getFileName.toString.endsWith(".tmp"))

  private def isMalformed(
      result: Either[LineageDataError, LineageDataDocument]
  ): Boolean =
    result.left.toOption.exists {
      case _: LineageDataError.Malformed => true
      case _                             => false
    }

  def spec = suite("LineageData LIN-02B")(
    suite("LIN2-B-01 roundtrip doc→json→doc")(
      test("roundtrip is identical for all node kinds and data variants") {
        val doc = sampleDoc()
        val decoded = doc.toJson.fromJson[LineageDataDocument]
        assertTrue(decoded == Right(doc))
      },
      test("output format is byte-identical and data keys are sorted") {
        val doc = sampleDoc()
        val json1 = doc.toJson
        val json2 = json1.fromJson[LineageDataDocument].map(_.toJson)
        val positions =
          Seq("d-del", "d-model", "d-phase", "d-tail", "d-tool-1", "d-tool-2")
            .map(key => json1.indexOf("\"" + key + "\":"))
        assertTrue(json2 == Right(json1), positions == positions.sorted)
      },
      test("file roundtrip: write→read→write is byte-identical") {
        withTempDir { dir =>
          val p1 = dir.resolve("a.json")
          val p2 = dir.resolve("b.json")
          for
            _ <- ZIO.attempt(LineageDataFile.write(sampleDoc(), p1))
            readBack = LineageDataFile.read(p1)
            _ <- ZIO.attempt(
              readBack.foreach(doc => LineageDataFile.write(doc, p2))
            )
            bytes1 <- ZIO.attempt(Files.readAllBytes(p1))
            bytes2 <- ZIO.attempt(Files.readAllBytes(p2))
          yield assertTrue(
            readBack == Right(sampleDoc()),
            bytes1.sameElements(bytes2)
          )
        }
      },
      test("frozen wire format: snake_case kinds, flat data, explicit nulls") {
        val node = DataNode(
          "n1",
          NodeKind.ModelCall,
          None,
          2L,
          5L,
          Vector("n0"),
          Some("d-model")
        )
        val phase: NodeData = NodeData.PhaseData("verify", None)
        val nullsDecoded =
          """{"tool":"t","callId":"c","recovery":"ReadOnly","outcome":null,"argsSummary":"a","resultTail":null}"""
            .fromJson[NodeData]
        val missingDecoded = """{"name":"verify"}""".fromJson[NodeData]
        val usageUnknown = NodeData.ModelCallData(
          "m",
          1,
          "p",
          "r",
          Some(Usage(None, None, true))
        )
        assertTrue(
          NodeKind.ModelCall.toJson == "\"model_call\"",
          NodeKind.ToolCall.toJson == "\"tool_call\"",
          NodeKind.Delegation.toJson == "\"delegation\"",
          NodeKind.Phase.toJson == "\"phase\"",
          node.toJson == """{"id":"n1","kind":"model_call","parentId":null,"seq":2,"atMs":5,"inputs":["n0"],"dataRef":"d-model"}""",
          phase.toJson == """{"name":"verify","note":null}""",
          nullsDecoded == Right(
            NodeData.ToolCallData("t", "c", "ReadOnly", None, "a", None)
          ),
          missingDecoded == Right(NodeData.PhaseData("verify", None)),
          usageUnknown.toJson.fromJson[NodeData] == Right(usageUnknown)
        )
      }
    ),
    suite("LIN2B-F1-01 canonical output matches frozen schema shape")(
      test("document and generator required keys are present") {
        val root = astOf(sampleDoc())
        assertTrue(
          keysOf(root) == Set(
            "schemaVersion",
            "generator",
            "runId",
            "nodes",
            "data"
          ),
          keysOf(fieldOf(root, "generator").getOrElse(Json.Null)) ==
            Set("name", "version", "scalaVersion", "jdkVersion")
        )
      },
      test(
        "every node carries parentId: explicit null for roots, string otherwise"
      ) {
        val nodes = nodesOf(sampleDoc())
        val shapeOf = (json: Json) =>
          fieldOf(json, "parentId") match
            case Some(Json.Null)   => "null"
            case Some(_: Json.Str) => "string"
            case Some(_)           => "other"
            case None              => "absent"
        val shapes = nodes.map(shapeOf)
        assertTrue(
          nodes.size == 6,
          // n0 is the root; n1..n5 are children.
          shapes == Vector(
            "null",
            "string",
            "string",
            "string",
            "string",
            "string"
          )
        )
      },
      test(
        "node required keys present; non-null optional dataRef stays omitted"
      ) {
        val nodes = nodesOf(sampleDoc())
        val requiredNodeKeys =
          Set("id", "kind", "parentId", "seq", "atMs", "inputs")
        // n5 is the only node with dataRef=None: the key must be omitted
        // (schema types dataRef as a non-null string), the rest keep it.
        val dataRefPresent = nodes.map(keysOf(_).contains("dataRef"))
        assertTrue(
          nodes.forall(n => requiredNodeKeys.subsetOf(keysOf(n))),
          dataRefPresent == Vector(true, true, true, true, true, false)
        )
      },
      test("phase node without dataRef encodes the exact frozen shape") {
        val doc = LineageDataDocument(
          schemaVersion = 1,
          generator = generator,
          runId = "run-noref",
          nodes = Vector(
            DataNode("p", NodeKind.Phase, None, 1L, 0L, Vector.empty, None)
          ),
          data = Map.empty
        )
        assertTrue(
          doc.toJson ==
            """{"schemaVersion":1,"generator":{"name":"raider","version":"0.7.0",""" +
            """"scalaVersion":"3.9.0","jdkVersion":"21"},"runId":"run-noref",""" +
            """"nodes":[{"id":"p","kind":"phase","parentId":null,"seq":1,"atMs":0,"inputs":[]}],""" +
            """"data":{}}"""
        )
      },
      test(
        "data variant shapes carry all required-nullable keys with explicit nulls"
      ) {
        val data = dataEntriesOf(sampleDoc())
        val keys =
          (key: String) => keysOf(data.getOrElse(key, Json.Obj(Chunk.empty)))
        val isNull = (key: String, field: String) =>
          data.get(key).flatMap(fieldOf(_, field)).contains(Json.Null)
        val isStr = (key: String, field: String) =>
          data
            .get(key)
            .flatMap(fieldOf(_, field))
            .exists(_.isInstanceOf[Json.Str])
        assertTrue(
          keys("d-tool-2") == Set(
            "tool",
            "callId",
            "recovery",
            "outcome",
            "argsSummary",
            "resultTail"
          ),
          isNull("d-tool-2", "outcome"),
          isNull("d-tool-2", "resultTail"),
          isStr("d-tool-1", "outcome"),
          isStr("d-tool-1", "resultTail"),
          keys("d-model") == Set(
            "model",
            "round",
            "prompt",
            "response",
            "usage"
          ),
          keysOf(
            data
              .get("d-model")
              .flatMap(fieldOf(_, "usage"))
              .getOrElse(Json.Null)
          ) ==
            Set("reservedMicroUsd", "observedMicroUsd", "unknown"),
          keys("d-phase") == Set("name", "note"),
          isStr("d-phase", "note"),
          isNull("d-tail", "note"),
          keys("d-del") == Set("childJobId", "agent")
        )
      },
      test(
        "model data with usage=None and Usage(None, None, true) encode schema-legal shapes"
      ) {
        val usageNull = NodeData.ModelCallData("m", 1, "p", "r", None)
        val usageUnknown: NodeData = NodeData.ModelCallData(
          "m",
          1,
          "p",
          "r",
          Some(Usage(None, None, true))
        )
        assertTrue(
          usageNull.toJson ==
            """{"model":"m","round":1,"prompt":"p","response":"r","usage":null}""",
          usageUnknown.toJson ==
            """{"model":"m","round":1,"prompt":"p","response":"r","usage":{"reservedMicroUsd":null,"observedMicroUsd":null,"unknown":true}}"""
        )
      }
    ),
    suite("LIN2B-F1-03 golden-form byte stability")(
      test(
        "canonical output byte-matches the golden form (explicit nulls, sorted keys)"
      ) {
        val doc = goldenDoc()
        val expected =
          """{"schemaVersion":1,"generator":{"name":"raider","version":"0.1.0-SNAPSHOT",""" +
            """"scalaVersion":"3.9.0","jdkVersion":"21"},"runId":"run-tiny-001",""" +
            """"nodes":[{"id":"n0","kind":"phase","parentId":null,"seq":1,"atMs":0,"inputs":[],"dataRef":"d0"},""" +
            """{"id":"n1","kind":"model_call","parentId":"n0","seq":2,"atMs":120,"inputs":[],"dataRef":"d1"},""" +
            """{"id":"n2","kind":"tool_call","parentId":"n1","seq":3,"atMs":340,"inputs":[],"dataRef":"d2"},""" +
            """{"id":"n3","kind":"tool_call","parentId":"n1","seq":4,"atMs":510,"inputs":[],"dataRef":"d3"}],""" +
            """"data":{"d0":{"name":"investigate","note":null},""" +
            """"d1":{"model":"mock-coder","round":1,"prompt":"entry point","response":"cli main",""" +
            """"usage":{"reservedMicroUsd":null,"observedMicroUsd":1500,"unknown":false}},""" +
            """"d2":{"tool":"read_file","callId":"call-001","recovery":"ReadOnly","outcome":"Succeeded",""" +
            """"argsSummary":"path=README.md","resultTail":"readme tail"},""" +
            """"d3":{"tool":"edit_file","callId":"call-002","recovery":"Mutating","outcome":"Failed",""" +
            """"argsSummary":"path=docs/notes.md","resultTail":null}}}"""
        assertTrue(
          doc.toJson == expected,
          doc.toJson.fromJson[LineageDataDocument].map(_.toJson) == Right(
            expected
          )
        )
      }
    ),
    suite("LIN2-B-02 typed read errors")(
      test("schemaVersion != 1 → SchemaVersion(found)") {
        withTempDir { dir =>
          val json = sampleDoc().toJson
          for
            _ <- writeFile(
              dir.resolve("v0.json"),
              json.replace("\"schemaVersion\":1", "\"schemaVersion\":0")
            )
            _ <- writeFile(
              dir.resolve("v2.json"),
              json.replace("\"schemaVersion\":1", "\"schemaVersion\":2")
            )
            readV0 = LineageDataFile.read(dir.resolve("v0.json"))
            readV2 = LineageDataFile.read(dir.resolve("v2.json"))
          yield assertTrue(
            readV0 == Left(LineageDataError.SchemaVersion(0)),
            readV2 == Left(LineageDataError.SchemaVersion(2))
          )
        }
      },
      test("unresolved dataRef → BrokenRef(nodeId, dataRef)") {
        withTempDir { dir =>
          val json = sampleDoc().toJson
            .replace("\"dataRef\":\"d-del\"", "\"dataRef\":\"d-missing\"")
          for
            _ <- writeFile(dir.resolve("brk.json"), json)
            result = LineageDataFile.read(dir.resolve("brk.json"))
          yield assertTrue(
            result == Left(LineageDataError.BrokenRef("n4", "d-missing"))
          )
        }
      },
      test("first broken node wins when several refs are broken") {
        withTempDir { dir =>
          val json = sampleDoc().toJson
            .replace("\"dataRef\":\"d-del\"", "\"dataRef\":\"d-miss-1\"")
            .replace("\"dataRef\":\"d-model\"", "\"dataRef\":\"d-miss-2\"")
          for
            _ <- writeFile(dir.resolve("brk.json"), json)
            result = LineageDataFile.read(dir.resolve("brk.json"))
          yield assertTrue(
            result == Left(LineageDataError.BrokenRef("n1", "d-miss-2"))
          )
        }
      },
      test("malformed JSON → Malformed") {
        withTempDir { dir =>
          for
            _ <- writeFile(dir.resolve("bad.json"), "{not json")
            result = LineageDataFile.read(dir.resolve("bad.json"))
          yield assertTrue(isMalformed(result))
        }
      },
      test("unreadable path (missing file) → Malformed") {
        withTempDir { dir =>
          val result = LineageDataFile.read(dir.resolve("absent.json"))
          assertTrue(isMalformed(result))
        }
      },
      test("data entry matching no NodeData variant → Malformed") {
        withTempDir { dir =>
          val json =
            """{"schemaVersion":1,"generator":{"name":"raider","version":"0.7.0",""" +
              """"scalaVersion":"3.9.0","jdkVersion":"21"},"runId":"r",""" +
              """"nodes":[{"id":"n1","kind":"model_call","seq":1,"atMs":0,"inputs":[],"dataRef":"d-bad"}],""" +
              """"data":{"d-bad":{}}}"""
          for
            _ <- writeFile(dir.resolve("doc.json"), json)
            result = LineageDataFile.read(dir.resolve("doc.json"))
          yield assertTrue(isMalformed(result))
        }
      },
      test("unknown NodeKind string → Malformed") {
        withTempDir { dir =>
          val json = sampleDoc().toJson
            .replace("\"kind\":\"phase\"", "\"kind\":\"banana\"")
          for
            _ <- writeFile(dir.resolve("doc.json"), json)
            result = LineageDataFile.read(dir.resolve("doc.json"))
          yield assertTrue(isMalformed(result))
        }
      }
    ),
    suite("LIN2-B-03 atomic concurrent writes")(
      test("concurrent writes to distinct paths do not interfere") {
        withTempDir { dir =>
          val docs = (1 to 8).map(i => sampleDoc(s"run-$i"))
          val paths = docs.zipWithIndex.map { case (_, i) =>
            dir.resolve(s"doc-$i.json")
          }
          val pairs = docs.zip(paths)
          for
            _ <- ZIO.foreachPar(pairs) { case (doc, path) =>
              ZIO.attempt(LineageDataFile.write(doc, path))
            }
            readBack <- ZIO.foreach(pairs) { case (doc, path) =>
              ZIO
                .attempt(LineageDataFile.read(path))
                .map(_.toOption.contains(doc))
            }
            tmpLeft = tmpCount(dir)
          yield assertTrue(readBack.forall(identity), tmpLeft == 0)
        }
      },
      test(
        "concurrent writes to the same path leave a whole readable document"
      ) {
        withTempDir { dir =>
          val docs = (1 to 8).map(i => sampleDoc(s"run-$i"))
          val target = dir.resolve("shared.json")
          for
            _ <- ZIO.foreachPar(docs)(doc =>
              ZIO.attempt(LineageDataFile.write(doc, target))
            )
            back <- ZIO.attempt(LineageDataFile.read(target))
          yield assertTrue(back.fold(_ => false, docs.contains))
        }
      }
    ),
    suite("validation scope")(
      test(
        "parents-before-children is not checked at the data layer (graph layer, wave-2)"
      ) {
        withTempDir { dir =>
          val doc = sampleDoc()
          val reordered = doc.copy(nodes = Vector(doc.nodes(1), doc.nodes(0)))
          for
            _ <- ZIO.attempt(
              LineageDataFile.write(reordered, dir.resolve("re.json"))
            )
            readBack = LineageDataFile.read(dir.resolve("re.json"))
          yield assertTrue(readBack == Right(reordered))
        }
      }
    )
  )
