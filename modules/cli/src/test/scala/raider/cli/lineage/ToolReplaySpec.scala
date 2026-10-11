package raider.cli.lineage

import zio.test.*

import raider.core.{RootId, SessionId}
import raider.dsl.lineage.data.{
  DataNode,
  GeneratorInfo,
  LineageDataDocument,
  NodeData,
  NodeKind
}
import raider.runtime.events.ToolOutcome
import raider.runtime.lineage.model.{
  DataRef,
  NodeId,
  RecoveryClass,
  SessionGraph,
  SessionNode
}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.Using

/** LIN5-F acceptance (LIN-05F, ADR-027 §2–3 — deterministic tool recompute):
  *
  *   - LIN5-F-01 — fixture workspace + fixture lineage-data: fs_read tool calls
  *     are RECOMPUTED by really reading the fixture workspace files (bounded,
  *     ReadTool discipline) and compare to the recorded sources as Match;
  *     traversal order is `(seq, id)` — NOT the graph emission order; a repeat
  *     replay is equal and renders byte-identically.
  *   - LIN5-F-02 — substituting a workspace file's content recomputes to
  *     Mismatch naming EXACTLY the drifted field (`resultTail`); untouched
  *     files stay Match.
  *   - LIN5-F-03 — a non-deterministic/unknown tool is typed
  *     `RecomputeError.Unsupported` (honest, never a fake Match); replay
  *     without a recorded document is `NoRecord` for every tool node (the graph
  *     model carries no args — without the record there is nothing to
  *     re-execute).
  *
  * F-COV-1 (W16 review) — behavioral coverage of the packet's special cases
  * (argsSummary is recorded data that drives workspace FS reads, so every
  * special case needs a live regression test):
  *
  *   - FCOV-01 — recorded `argsSummary` cannot escape the workspace (`../..`
  *     traversal, absolute path outside, symlink inside → target outside):
  *     typed `RecomputeError.ReadFailed`, and a marker-decoy file OUTSIDE the
  *     workspace never leaks into the result.
  *   - FCOV-02 — a NUL byte in file content is a typed binary refusal, never a
  *     Match carrying garbage.
  *   - FCOV-03 — `resultTail` is exactly the LAST `TailMaxChars` (2000) of the
  *     read slice (2500-char file, 2000/2001 edges, ranged slice); a shorter
  *     read stays full.
  *   - FCOV-04 — the same file recorded with a non-canonical argsSummary
  *     (different key order) is an honest `Mismatch(Vector("argsSummary"))`,
  *     not a Match.
  *   - FCOV-05 — `tool=edit_file` is typed `Unsupported` through the spec
  *     (existing behavior pinned).
  *   - FCOV-06 — malformed recorded args (duplicate key, unknown key,
  *     non-integer) are typed `ArgsUnreadable`.
  *
  * The fixture lineage documents are built in code (the production codec path
  * is covered by the golden-fixture specs); the fixture WORKSPACE is real
  * temporary files on disk — the only IO the recompute is allowed to do.
  */
object ToolReplaySpec extends ZIOSpecDefault:

  // ----- fixture plumbing ------------------------------------------------------

  private val Generator =
    GeneratorInfo("raider", "0.1.0-SNAPSHOT", "3.9.0", "21")

  private val NotesPath = "notes.md"
  private val NotesContent = "header one\nheader two\ntail line"
  private val GuidePath = "docs/guide.md"
  private val GuideContent = "guide line A\nguide line B"

  private def toolNode(
      id: String,
      seq: Long,
      tool: String,
      callId: String,
      recovery: RecoveryClass,
      dataRef: Option[String]
  ): SessionNode.ToolCall =
    SessionNode.ToolCall(
      NodeId(id),
      None,
      seq,
      0,
      callId,
      tool,
      recovery,
      Some(ToolOutcome.Succeeded),
      Vector.empty,
      dataRef.map(DataRef(_))
    )

  /** Fixture document. Emission order is deliberately NOT `(seq, id)`-sorted:
    * `t-a` has seq 2 and is emitted first, `t-b` has seq 1 — recompute order
    * must follow `(seq, id)`. The guide call records a ranged read (`offset=1
    * max_lines=1`) so the bounded-slice rule is exercised too.
    */
  private def baselineDoc: LineageDataDocument =
    LineageDataDocument(
      schemaVersion = 1,
      generator = Generator,
      runId = "run-lin5f",
      nodes = Vector(
        DataNode(
          "t-a",
          NodeKind.ToolCall,
          None,
          2,
          340,
          Vector.empty,
          Some("d-a")
        ),
        DataNode(
          "t-b",
          NodeKind.ToolCall,
          None,
          1,
          510,
          Vector.empty,
          Some("d-b")
        )
      ),
      data = Map(
        "d-a" -> NodeData.ToolCallData(
          tool = "fs_read",
          callId = "call-a",
          recovery = "ReadOnly",
          outcome = Some("Succeeded"),
          argsSummary = "path=notes.md",
          resultTail = Some(NotesContent)
        ),
        "d-b" -> NodeData.ToolCallData(
          tool = "fs_read",
          callId = "call-b",
          recovery = "ReadOnly",
          outcome = Some("Succeeded"),
          argsSummary = "path=docs/guide.md offset=1 max_lines=1",
          resultTail = Some("guide line B")
        )
      )
    )

  private def baselineGraph: SessionGraph =
    SessionGraph(
      RootId("run-lin5f"),
      SessionId("sess-lin5f"),
      Vector(
        toolNode(
          "t-a",
          2,
          "fs_read",
          "call-a",
          RecoveryClass.ReadOnly,
          Some("d-a")
        ),
        toolNode(
          "t-b",
          1,
          "fs_read",
          "call-b",
          RecoveryClass.ReadOnly,
          Some("d-b")
        )
      )
    )

  /** Real temporary workspace: the only IO surface of this card. */
  private def withWorkspace(files: (String, String)*)(run: Path => Unit): Unit =
    val root = Files.createTempDirectory("lin5f-ws-")
    try
      files.foreach { case (rel, content) =>
        val target = root.resolve(rel)
        Files.createDirectories(target.getParent)
        Files.writeString(target, content, StandardCharsets.UTF_8)
      }
      run(root)
    finally
      Using.resource(Files.walk(root)) { stream =>
        stream
          .sorted(java.util.Comparator.reverseOrder[Path]())
          .forEach(p => { Files.deleteIfExists(p); () })
        ()
      }

  // ----- spec ------------------------------------------------------------------

  def spec = suite("LIN5-F deterministic tool recompute in replay")(
    test(
      "LIN5-F-01: fs_read calls recompute against the fixture workspace, order is (seq,id), repeat is identical"
    ) {
      // replay results cross the withWorkspace closure as Option, unwrapped
      // with .orNull (not the DisableSyntax.null literal — same sanctioned
      // pattern as LineageRendererSpec; the Some is always set before use).
      var report1Opt: Option[ToolReplayReport] = None
      var report2Opt: Option[ToolReplayReport] = None
      withWorkspace(NotesPath -> NotesContent, GuidePath -> GuideContent) {
        ws =>
          report1Opt = Some(
            ToolReplay.replayTools(baselineGraph, Some(baselineDoc), ws)
          )
          report2Opt = Some(
            ToolReplay.replayTools(baselineGraph, Some(baselineDoc), ws)
          )
      }
      val report1 = report1Opt.orNull
      val report2 = report2Opt.orNull
      assertTrue(
        report1.rootId == "run-lin5f",
        report1.sessionId == "sess-lin5f",
        report1.toolNodes == 2,
        // traversal follows (seq, id): t-b (seq 1) BEFORE t-a (seq 2),
        // although the graph emits t-a first
        report1.entries.map(_.nodeId) == Vector("t-b", "t-a"),
        report1.entries.forall(_.comparison == Right(ToolComparison.Match)),
        report1.entries.find(_.nodeId == "t-a").get.recomputed == Some(
          NodeData.ToolCallData(
            "fs_read",
            "call-a",
            "ReadOnly",
            Some("Succeeded"),
            "path=notes.md",
            Some(NotesContent)
          )
        ),
        // ranged read: offset=1 max_lines=1 of "guide line A\nguide line B"
        report1.entries.find(_.nodeId == "t-b").get.recomputed == Some(
          NodeData.ToolCallData(
            "fs_read",
            "call-b",
            "ReadOnly",
            Some("Succeeded"),
            "path=docs/guide.md offset=1 max_lines=1",
            Some("guide line B")
          )
        ),
        report1 == report2,
        report1.render == report2.render,
        report1.render.getBytes(StandardCharsets.UTF_8).toSeq ==
          report2.render.getBytes(StandardCharsets.UTF_8).toSeq
      )
    },
    test(
      "LIN5-F-02: substituted workspace content recomputes to Mismatch naming exactly resultTail"
    ) {
      var reportOpt: Option[ToolReplayReport] = None
      withWorkspace(
        NotesPath -> "header one\nheader two\nTAMPERED tail",
        GuidePath -> GuideContent
      ) { ws =>
        reportOpt = Some(
          ToolReplay.replayTools(baselineGraph, Some(baselineDoc), ws)
        )
      }
      val report = reportOpt.orNull
      val notesEntry = report.entries.find(_.nodeId == "t-a").get
      val guideEntry = report.entries.find(_.nodeId == "t-b").get
      assertTrue(
        notesEntry.comparison ==
          Right(ToolComparison.Mismatch(Vector("resultTail"))),
        guideEntry.comparison == Right(ToolComparison.Match)
      )
    },
    test(
      "LIN5-F-03: unknown/non-deterministic tool is typed Unsupported, never a fake Match"
    ) {
      val doc = baselineDoc.copy(
        nodes = baselineDoc.nodes :+ DataNode(
          "t-c",
          NodeKind.ToolCall,
          None,
          3,
          700,
          Vector.empty,
          Some("d-c")
        ),
        data = baselineDoc.data + ("d-c" -> NodeData.ToolCallData(
          tool = "proc_run",
          callId = "call-c",
          recovery = "Mutating",
          outcome = Some("Succeeded"),
          argsSummary = "argv=[ls,-la]",
          resultTail = Some("total 0")
        ))
      )
      val graph = SessionGraph(
        RootId("run-lin5f"),
        SessionId("sess-lin5f"),
        baselineGraph.nodes :+ toolNode(
          "t-c",
          3,
          "proc_run",
          "call-c",
          RecoveryClass.Mutating,
          Some("d-c")
        )
      )
      var reportOpt: Option[ToolReplayReport] = None
      withWorkspace(NotesPath -> NotesContent, GuidePath -> GuideContent) {
        ws =>
          reportOpt = Some(ToolReplay.replayTools(graph, Some(doc), ws))
      }
      val report = reportOpt.orNull
      val proc = report.entries.find(_.nodeId == "t-c").get
      assertTrue(
        proc.comparison == Left(RecomputeError.Unsupported("proc_run")),
        proc.recomputed.isEmpty,
        report.render.contains("error=unsupported tool=proc_run")
      )
    },
    test(
      "LIN5-F-03: no recorded document → NoRecord for every tool node (no recorded args to re-execute)"
    ) {
      var reportOpt: Option[ToolReplayReport] = None
      withWorkspace(NotesPath -> NotesContent, GuidePath -> GuideContent) {
        ws =>
          reportOpt = Some(ToolReplay.replayTools(baselineGraph, None, ws))
      }
      val report = reportOpt.orNull
      assertTrue(
        report.entries.map(_.comparison) ==
          Vector(
            Right(ToolComparison.NoRecord),
            Right(ToolComparison.NoRecord)
          ),
        report.entries.flatMap(_.recomputed).isEmpty,
        report.render.contains("no-record")
      )
    },
    test(
      "compareRecorded: None is NoRecord; Mismatch lists drifted fields in the fixed field order"
    ) {
      val base: NodeData.ToolCallData = NodeData.ToolCallData(
        "fs_read",
        "c1",
        "ReadOnly",
        Some("Succeeded"),
        "path=a.md",
        Some("x")
      )
      val drifted: NodeData.ToolCallData = NodeData.ToolCallData(
        "fs_read2",
        "c1",
        "ReadOnly",
        None,
        "path=b.md",
        None
      )
      assertTrue(
        ToolComparison.compareRecorded(base, None) == ToolComparison.NoRecord,
        ToolComparison
          .compareRecorded(base, Some(base)) == ToolComparison.Match,
        ToolComparison.compareRecorded(drifted, Some(base)) ==
          ToolComparison.Mismatch(
            Vector("tool", "outcome", "argsSummary", "resultTail")
          )
      )
    },
    test(
      "kind-honesty: a dataRef resolving to non-ToolCallData is ArgsUnreadable, not a guess"
    ) {
      val doc = baselineDoc.copy(
        data = baselineDoc.data + ("d-m" -> NodeData.ModelCallData(
          "mock-coder",
          1,
          "p",
          "r",
          None
        ))
      )
      val graph = SessionGraph(
        RootId("run-lin5f"),
        SessionId("sess-lin5f"),
        Vector(
          toolNode(
            "t-m",
            4,
            "fs_read",
            "call-m",
            RecoveryClass.ReadOnly,
            Some("d-m")
          )
        )
      )
      var reportOpt: Option[ToolReplayReport] = None
      withWorkspace(NotesPath -> NotesContent) { ws =>
        reportOpt = Some(ToolReplay.replayTools(graph, Some(doc), ws))
      }
      val report = reportOpt.orNull
      report.entries.head.comparison match
        case Left(RecomputeError.ArgsUnreadable(nodeId, detail)) =>
          assertTrue(nodeId == "t-m", detail.contains("ModelCallData"))
        case other =>
          throw new IllegalStateException(
            "expected ArgsUnreadable, got: " + other
          )
    },
    test(
      "FCOV-01: recorded argsSummary cannot escape the workspace — traversal, absolute path and symlink all typed ReadFailed, the outside decoy never leaks"
    ) {
      val marker = "DECOY-OUTSIDE-CONTENT-L45-never-readable"
      val outside = Files.createTempDirectory("lin5f-outside-")
      val decoy = outside.resolve("decoy-secret.txt")
      Files.writeString(decoy, marker, StandardCharsets.UTF_8)
      val escapeLink = "escape-link.txt"
      var reportOpt: Option[ToolReplayReport] = None
      try
        withWorkspace(NotesPath -> NotesContent) { ws =>
          // symlink INSIDE the workspace pointing at a file OUTSIDE of it
          Files.createSymbolicLink(ws.resolve(escapeLink), decoy.toAbsolutePath)
          def escapeData(callId: String, pathText: String) =
            NodeData.ToolCallData(
              tool = "fs_read",
              callId = callId,
              recovery = "ReadOnly",
              outcome = Some("Succeeded"),
              argsSummary = "path=" + pathText,
              // the decoy marker as the recorded tail: a defective
              // implementation that (wrongly) read outside would leak it
              resultTail = Some(marker)
            )
          val doc = baselineDoc.copy(
            nodes = Vector(
              DataNode(
                "t-trav",
                NodeKind.ToolCall,
                None,
                1,
                11,
                Vector.empty,
                Some("d-trav")
              ),
              DataNode(
                "t-abs",
                NodeKind.ToolCall,
                None,
                2,
                12,
                Vector.empty,
                Some("d-abs")
              ),
              DataNode(
                "t-sym",
                NodeKind.ToolCall,
                None,
                3,
                13,
                Vector.empty,
                Some("d-sym")
              )
            ),
            data = Map(
              "d-trav" -> escapeData("call-trav", "../../../../etc/passwd"),
              "d-abs" -> escapeData(
                "call-abs",
                decoy.toAbsolutePath.toString
              ),
              "d-sym" -> escapeData("call-sym", escapeLink)
            )
          )
          val graph = SessionGraph(
            RootId("run-lin5f"),
            SessionId("sess-lin5f"),
            Vector(
              toolNode(
                "t-trav",
                1,
                "fs_read",
                "call-trav",
                RecoveryClass.ReadOnly,
                Some("d-trav")
              ),
              toolNode(
                "t-abs",
                2,
                "fs_read",
                "call-abs",
                RecoveryClass.ReadOnly,
                Some("d-abs")
              ),
              toolNode(
                "t-sym",
                3,
                "fs_read",
                "call-sym",
                RecoveryClass.ReadOnly,
                Some("d-sym")
              )
            )
          )
          reportOpt = Some(ToolReplay.replayTools(graph, Some(doc), ws))
        }
      finally
        Files.deleteIfExists(decoy)
        Files.deleteIfExists(outside)
      val report = reportOpt.orNull
      // extracts the ReadFailed detail only from an honest typed failure of
      // the entry's own node — anything else fails the assertion below
      def readFailedDetail(entry: ToolReplayEntry): String =
        entry.comparison match
          case Left(RecomputeError.ReadFailed(nodeId, detail)) =>
            if nodeId == entry.nodeId then detail
            else "WRONG-NODE:" + nodeId
          case other => "NOT-READ-FAILED:" + other
      val trav = report.entries.find(_.nodeId == "t-trav").get
      val abs = report.entries.find(_.nodeId == "t-abs").get
      val sym = report.entries.find(_.nodeId == "t-sym").get
      assertTrue(
        report.entries.map(_.nodeId) == Vector("t-trav", "t-abs", "t-sym"),
        report.entries.flatMap(_.recomputed).isEmpty,
        readFailedDetail(trav).contains("traversal"),
        readFailedDetail(abs).contains("traversal"),
        readFailedDetail(sym).contains("symlink"),
        report.render.contains("error=read-failed"),
        !report.render.contains(marker),
        !report.toString.contains(marker)
      )
    },
    test(
      "FCOV-02: a NUL byte in the file is a typed binary refusal — never a Match carrying garbage"
    ) {
      var reportOpt: Option[ToolReplayReport] = None
      withWorkspace("blob.bin" -> "before\u0000after") { ws =>
        val doc = baselineDoc.copy(
          nodes = Vector(
            DataNode(
              "t-bin",
              NodeKind.ToolCall,
              None,
              1,
              21,
              Vector.empty,
              Some("d-bin")
            )
          ),
          data = Map(
            "d-bin" -> NodeData.ToolCallData(
              tool = "fs_read",
              callId = "call-bin",
              recovery = "ReadOnly",
              outcome = Some("Succeeded"),
              argsSummary = "path=blob.bin",
              // garbage recorded tail: a defective Match would carry it
              resultTail = Some("GARBAGE-RECORDED-TAIL")
            )
          )
        )
        val graph = SessionGraph(
          RootId("run-lin5f"),
          SessionId("sess-lin5f"),
          Vector(
            toolNode(
              "t-bin",
              1,
              "fs_read",
              "call-bin",
              RecoveryClass.ReadOnly,
              Some("d-bin")
            )
          )
        )
        reportOpt = Some(ToolReplay.replayTools(graph, Some(doc), ws))
      }
      val report = reportOpt.orNull
      val entry = report.entries.head
      assertTrue(
        report.entries.map(_.nodeId) == Vector("t-bin"),
        entry.comparison ==
          Left(
            RecomputeError.ReadFailed(
              "t-bin",
              "binary file (NUL byte) is not read as text: blob.bin"
            )
          ),
        entry.recomputed.isEmpty,
        report.render.contains("error=read-failed"),
        report.render.contains("NUL")
      )
    },
    test(
      "FCOV-03: resultTail is exactly the LAST TailMaxChars (2000) of the slice; at/under the bound the read stays full"
    ) {
      def positional(len: Int): String =
        (0 until len).map(i => (i % 10).toString).mkString
      val full2000 = positional(2000)
      val over2001 = positional(2001)
      val over2500 = positional(2500)
      var reportOpt: Option[ToolReplayReport] = None
      withWorkspace(
        "full.md" -> full2000,
        "over2001.md" -> over2001,
        "over2500.md" -> over2500
      ) { ws =>
        def recorded(file: String, tail: String) =
          NodeData.ToolCallData(
            "fs_read",
            "call-" + file,
            "ReadOnly",
            Some("Succeeded"),
            "path=" + file,
            Some(tail)
          )
        val doc = baselineDoc.copy(
          nodes = Vector(
            DataNode(
              "t-full",
              NodeKind.ToolCall,
              None,
              1,
              31,
              Vector.empty,
              Some("d-full")
            ),
            DataNode(
              "t-2001",
              NodeKind.ToolCall,
              None,
              2,
              32,
              Vector.empty,
              Some("d-2001")
            ),
            DataNode(
              "t-2500",
              NodeKind.ToolCall,
              None,
              3,
              33,
              Vector.empty,
              Some("d-2500")
            )
          ),
          data = Map(
            "d-full" -> recorded("full.md", full2000),
            // exactly one char over the bound: the FIRST char must be dropped
            "d-2001" -> recorded("over2001.md", over2001.substring(1)),
            "d-2500" -> recorded("over2500.md", over2500.substring(500))
          )
        )
        val graph = SessionGraph(
          RootId("run-lin5f"),
          SessionId("sess-lin5f"),
          Vector(
            toolNode(
              "t-full",
              1,
              "fs_read",
              "call-full.md",
              RecoveryClass.ReadOnly,
              Some("d-full")
            ),
            toolNode(
              "t-2001",
              2,
              "fs_read",
              "call-over2001.md",
              RecoveryClass.ReadOnly,
              Some("d-2001")
            ),
            toolNode(
              "t-2500",
              3,
              "fs_read",
              "call-over2500.md",
              RecoveryClass.ReadOnly,
              Some("d-2500")
            )
          )
        )
        reportOpt = Some(ToolReplay.replayTools(graph, Some(doc), ws))
      }
      val report = reportOpt.orNull
      val full = report.entries.find(_.nodeId == "t-full").get
      val edge = report.entries.find(_.nodeId == "t-2001").get
      val over = report.entries.find(_.nodeId == "t-2500").get
      val edgeTail = edge.recomputed.get.resultTail.get
      val overTail = over.recomputed.get.resultTail.get
      assertTrue(
        report.entries.map(_.nodeId) == Vector("t-full", "t-2001", "t-2500"),
        full.comparison == Right(ToolComparison.Match),
        full.recomputed.get.resultTail == Some(full2000),
        edge.comparison == Right(ToolComparison.Match),
        edgeTail.length == 2000,
        edgeTail == over2001.substring(1),
        edgeTail != over2001.substring(0, 2000),
        over.comparison == Right(ToolComparison.Match),
        overTail.length == 2000,
        overTail == over2500.substring(500)
      )
    },
    test(
      "FCOV-04: the same file recorded with a non-canonical argsSummary (key order) is an honest Mismatch(argsSummary), not a Match"
    ) {
      var reportOpt: Option[ToolReplayReport] = None
      withWorkspace(NotesPath -> NotesContent) { ws =>
        val doc = baselineDoc.copy(
          nodes = Vector(
            DataNode(
              "t-canon",
              NodeKind.ToolCall,
              None,
              1,
              41,
              Vector.empty,
              Some("d-canon")
            )
          ),
          data = Map(
            "d-canon" -> NodeData.ToolCallData(
              tool = "fs_read",
              callId = "call-canon",
              recovery = "ReadOnly",
              outcome = Some("Succeeded"),
              // same file, same read — but non-canonical key order; the
              // recompute re-emits the canonical path/offset/max_lines form
              argsSummary = "offset=0 path=notes.md",
              resultTail = Some(NotesContent)
            )
          )
        )
        val graph = SessionGraph(
          RootId("run-lin5f"),
          SessionId("sess-lin5f"),
          Vector(
            toolNode(
              "t-canon",
              1,
              "fs_read",
              "call-canon",
              RecoveryClass.ReadOnly,
              Some("d-canon")
            )
          )
        )
        reportOpt = Some(ToolReplay.replayTools(graph, Some(doc), ws))
      }
      val report = reportOpt.orNull
      val entry = report.entries.head
      assertTrue(
        report.entries.map(_.nodeId) == Vector("t-canon"),
        entry.comparison ==
          Right(ToolComparison.Mismatch(Vector("argsSummary"))),
        entry.recomputed.isDefined,
        entry.recomputed.get.argsSummary == "path=notes.md offset=0",
        entry.recomputed.get.resultTail == Some(NotesContent),
        report.render.contains("mismatch fields=argsSummary")
      )
    },
    test(
      "FCOV-05: tool=edit_file through the spec is typed Unsupported (existing behavior pinned)"
    ) {
      var reportOpt: Option[ToolReplayReport] = None
      withWorkspace(NotesPath -> NotesContent) { ws =>
        val doc = baselineDoc.copy(
          nodes = Vector(
            DataNode(
              "t-edit",
              NodeKind.ToolCall,
              None,
              1,
              51,
              Vector.empty,
              Some("d-edit")
            )
          ),
          data = Map(
            "d-edit" -> NodeData.ToolCallData(
              tool = "edit_file",
              callId = "call-edit",
              recovery = "Mutating",
              outcome = Some("Succeeded"),
              argsSummary = "path=notes.md old_text=a new_text=b",
              resultTail = Some("edited!")
            )
          )
        )
        val graph = SessionGraph(
          RootId("run-lin5f"),
          SessionId("sess-lin5f"),
          Vector(
            toolNode(
              "t-edit",
              1,
              "edit_file",
              "call-edit",
              RecoveryClass.Mutating,
              Some("d-edit")
            )
          )
        )
        reportOpt = Some(ToolReplay.replayTools(graph, Some(doc), ws))
      }
      val report = reportOpt.orNull
      val entry = report.entries.head
      assertTrue(
        report.entries.map(_.nodeId) == Vector("t-edit"),
        entry.comparison == Left(RecomputeError.Unsupported("edit_file")),
        entry.recomputed.isEmpty,
        report.render.contains("error=unsupported tool=edit_file")
      )
    },
    test(
      "FCOV-06: malformed recorded args (duplicate key, unknown key, non-integer) are typed ArgsUnreadable"
    ) {
      def malformed(callId: String, argsSummary: String) =
        NodeData.ToolCallData(
          tool = "fs_read",
          callId = callId,
          recovery = "ReadOnly",
          outcome = Some("Succeeded"),
          argsSummary = argsSummary,
          resultTail = Some(NotesContent)
        )
      val doc = baselineDoc.copy(
        nodes = Vector(
          DataNode(
            "t-dup",
            NodeKind.ToolCall,
            None,
            1,
            61,
            Vector.empty,
            Some("d-dup")
          ),
          DataNode(
            "t-unk",
            NodeKind.ToolCall,
            None,
            2,
            62,
            Vector.empty,
            Some("d-unk")
          ),
          DataNode(
            "t-nonint",
            NodeKind.ToolCall,
            None,
            3,
            63,
            Vector.empty,
            Some("d-nonint")
          )
        ),
        data = Map(
          "d-dup" -> malformed(
            "call-dup",
            "path=notes.md path=docs/guide.md"
          ),
          "d-unk" -> malformed("call-unk", "path=notes.md bogus=1"),
          "d-nonint" -> malformed(
            "call-nonint",
            "path=notes.md offset=abc"
          )
        )
      )
      val graph = SessionGraph(
        RootId("run-lin5f"),
        SessionId("sess-lin5f"),
        Vector(
          toolNode(
            "t-dup",
            1,
            "fs_read",
            "call-dup",
            RecoveryClass.ReadOnly,
            Some("d-dup")
          ),
          toolNode(
            "t-unk",
            2,
            "fs_read",
            "call-unk",
            RecoveryClass.ReadOnly,
            Some("d-unk")
          ),
          toolNode(
            "t-nonint",
            3,
            "fs_read",
            "call-nonint",
            RecoveryClass.ReadOnly,
            Some("d-nonint")
          )
        )
      )
      var reportOpt: Option[ToolReplayReport] = None
      withWorkspace(NotesPath -> NotesContent) { ws =>
        reportOpt = Some(ToolReplay.replayTools(graph, Some(doc), ws))
      }
      val report = reportOpt.orNull
      // extracts the ArgsUnreadable detail only from an honest typed failure
      // of the entry's own node — anything else fails the assertions below
      def unreadableDetail(entry: ToolReplayEntry): String =
        entry.comparison match
          case Left(RecomputeError.ArgsUnreadable(nodeId, detail)) =>
            if nodeId == entry.nodeId then detail
            else "WRONG-NODE:" + nodeId
          case other => "NOT-ARGS-UNREADABLE:" + other
      val dup = report.entries.find(_.nodeId == "t-dup").get
      val unk = report.entries.find(_.nodeId == "t-unk").get
      val nonint = report.entries.find(_.nodeId == "t-nonint").get
      assertTrue(
        report.entries.map(_.nodeId) == Vector("t-dup", "t-unk", "t-nonint"),
        report.entries.flatMap(_.recomputed).isEmpty,
        unreadableDetail(dup) == "duplicate args key 'path'",
        unreadableDetail(unk) == "unsupported args keys: bogus",
        unreadableDetail(nonint) ==
          "args key 'offset' is not an integer: abc",
        report.render.contains("error=args-unreadable")
      )
    }
  )

end ToolReplaySpec
