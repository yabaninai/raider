package raider.dsl.lineage.data

import zio.json.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** SCH-01/SCH-03 acceptance for the lineage-data resource (card RAI-143,
  * ADR-032 §4): v2 documents decode; v1 documents decode UNCHANGED bit-for-bit;
  * a v1 document carrying v2 fields is refused typed; unknown future versions
  * stay refused typed.
  */
object LineageDataV2Spec extends ZIOSpecDefault:

  private val tempDir: Path =
    val dir = Files.createTempDirectory("lineage-data-v2-spec")
    Runtime.getRuntime.addShutdownHook(new Thread(() => deleteRecursively(dir)))
    dir

  private def deleteRecursively(p: Path): Unit =
    if Files.isDirectory(p) then
      val children = Files.newDirectoryStream(p)
      try children.forEach((c: Path) => deleteRecursively(c))
      finally children.close()
    Files.deleteIfExists(p)
    ()

  private def fixture(name: String, content: String): Path =
    val path = tempDir.resolve(name)
    Files.writeString(path, content, StandardCharsets.UTF_8)
    path

  /** Minimal valid v1 document (frozen shape, no v2 fields). */
  private val v1Doc =
    """{"schemaVersion":1,"generator":{"name":"raider","version":"0.1.0-SNAPSHOT","scalaVersion":"3.9.0","jdkVersion":"21"},"runId":"run-v2-spec","nodes":[{"id":"n0","kind":"phase","parentId":null,"seq":1,"atMs":0,"inputs":[],"dataRef":"d0"},{"id":"n1","kind":"delegation","parentId":"n0","seq":2,"atMs":10,"inputs":[],"dataRef":"d1"}],"data":{"d0":{"name":"baseline","note":null},"d1":{"childJobId":"job-child","agent":"worker-2"}}}"""

  /** The same document at schemaVersion 2 with all three ADR-032 additions. */
  private val v2Doc =
    """{"schemaVersion":2,"generator":{"name":"raider","version":"0.1.0-SNAPSHOT","scalaVersion":"3.9.0","jdkVersion":"21"},"runId":"run-v2-spec","nodes":[{"id":"n0","kind":"phase","parentId":null,"seq":1,"atMs":0,"inputs":[],"dataRef":"d0"},{"id":"n1","kind":"delegation","parentId":"n0","seq":2,"atMs":10,"inputs":[],"dataRef":"d1"}],"data":{"d0":{"name":"baseline","note":null,"recovery":"Mutating"},"d1":{"childJobId":"job-child","agent":"worker-2","invocationId":"call-9"}},"provenance":{"parentRunId":"run-parent-1","artifacts":[{"kind":"work_dir","path":"/tmp/run-1"}]}}"""

  def spec = suite("LineageDataV2 (SCH-01/SCH-03, ADR-032)")(
    test(
      "SCH-01 v2 document decodes with provenance, phase recovery, correlation"
    ) {
      val path = fixture("v2.json", v2Doc)
      val read = LineageDataFile.read(path)
      val doc = read.toOption.get
      assertTrue(
        read.isRight,
        doc.schemaVersion == 2,
        doc.provenance ==
          Some(
            ProvenanceRecord(
              Some("run-parent-1"),
              Vector(ProvenanceArtifact("work_dir", "/tmp/run-1"))
            )
          ),
        doc.data("d0") == NodeData
          .PhaseData("baseline", None, Some("Mutating")),
        doc.data("d1") == NodeData
          .DelegationData("job-child", "worker-2", Some("call-9"))
      )
    },
    test(
      "SCH-01 v1 document decodes UNCHANGED and re-encodes byte-identically"
    ) {
      val path = fixture("v1.json", v1Doc)
      val read = LineageDataFile.read(path)
      val doc = read.toOption.get
      assertTrue(
        read.isRight,
        doc.schemaVersion == 1,
        doc.provenance.isEmpty,
        doc.toJson == v1Doc
      )
    },
    test("SCH-01 v2 document re-encodes to a stable, schema-shaped form") {
      val doc = v2Doc.fromJson[LineageDataDocument].toOption.get
      val json = doc.toJson
      assertTrue(
        json.fromJson[LineageDataDocument] == Right(doc),
        json.contains("\"provenance\":{\"parentRunId\":\"run-parent-1\""),
        json.contains("\"recovery\":\"Mutating\""),
        json.contains("\"invocationId\":\"call-9\"")
      )
    },
    test(
      "SCH-01 v1 encode stays byte-identical when v2 fields are absent (additive, not reshaped)"
    ) {
      // Building the document via the codec API must produce exactly the
      // frozen v1 wire form — no empty v2 keys, no reordering.
      val doc = LineageDataDocument(
        1,
        GeneratorInfo("raider", "0.1.0-SNAPSHOT", "3.9.0", "21"),
        "run-v2-spec",
        Vector(
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
            NodeKind.Delegation,
            Some("n0"),
            2L,
            10L,
            Vector.empty,
            Some("d1")
          )
        ),
        Map(
          "d0" -> NodeData.PhaseData("baseline", None),
          "d1" -> NodeData.DelegationData("job-child", "worker-2")
        )
      )
      assertTrue(doc.toJson == v1Doc)
    },
    test(
      "SCH-03 v1 document carrying v2 fields -> V2FieldOnV1, typed, never silent"
    ) {
      // Append provenance at DOCUMENT level (before the final doc brace).
      val provenanceOnV1 = fixture(
        "v1-prov.json",
        v1Doc.replace(
          "\"worker-2\"}}}",
          "\"worker-2\"}},\"provenance\":{\"artifacts\":[]}}"
        )
      )
      val recoveryOnV1 = fixture(
        "v1-recovery.json",
        v1Doc.replace(
          "\"name\":\"baseline\",\"note\":null}",
          "\"name\":\"baseline\",\"note\":null,\"recovery\":\"Mutating\"}"
        )
      )
      val invocationOnV1 = fixture(
        "v1-invocation.json",
        v1Doc.replace(
          "\"childJobId\":\"job-child\",\"agent\":\"worker-2\"",
          "\"childJobId\":\"job-child\",\"agent\":\"worker-2\",\"invocationId\":\"call-9\""
        )
      )
      val r1 = LineageDataFile.read(provenanceOnV1)
      val r2 = LineageDataFile.read(recoveryOnV1)
      val r3 = LineageDataFile.read(invocationOnV1)
      assertTrue(
        r1 == Left(LineageDataError.V2FieldOnV1("top-level provenance")),
        r2 == Left(LineageDataError.V2FieldOnV1("node n0: phaseData.recovery")),
        r3 == Left(
          LineageDataError.V2FieldOnV1("node n1: delegationData.invocationId")
        )
      )
    },
    test("SCH-03 unknown future version 3 stays refused typed") {
      val path = fixture(
        "v3.json",
        v1Doc.replace("\"schemaVersion\":1", "\"schemaVersion\":3")
      )
      assertTrue(
        LineageDataFile.read(path) ==
          Left(LineageDataError.SchemaVersion(3))
      )
    },
    test(
      "SCH-03 recovery is carried verbatim (schema enum + validator check it, codec is not a guesser)"
    ) {
      // The schema restricts recovery to the closed enum; the DSL codec
      // carries the string verbatim (validator-checked), consistent with the
      // frozen toolCallData.recovery handling.
      val doc = v2Doc
        .replace("\"recovery\":\"Mutating\"", "\"recovery\":\"Other\"")
        .fromJson[LineageDataDocument]
        .toOption
        .get
      assertTrue(
        doc.data("d0") == NodeData.PhaseData("baseline", None, Some("Other"))
      )
    }
  )

end LineageDataV2Spec
