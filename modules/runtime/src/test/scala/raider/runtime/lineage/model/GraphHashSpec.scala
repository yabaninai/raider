package raider.runtime.lineage.model

import raider.core.{RootId, SessionId}
import raider.runtime.events.ToolOutcome
import zio.test.*

/** LIN-01A-fix (W8 review F2) acceptance: injectivity of the [[GraphHash]]
  * canonical form.
  *
  *   - F2-01 reproduces the review collision: with `|` unescaped, the field
  *     split (callId="c|1", tool="t") and (callId="c", tool="1|t") produced
  *     identical canonical lines — two different graphs, one hash;
  *   - F2-02 pins the canonical structure against tool-outcome reasons carrying
  *     `\n`, `|` and control characters (tool-stderr bytes);
  *   - F2-03 (the existing 11 SessionGraphSpec tests keep passing) lives in
  *     SessionGraphSpec and is not duplicated here;
  *   - W9-F1 (W9 review) pins absent-field sentinel injectivity: a real field
  *     value equal to the historical `-` marker — or to the new composite
  *     sentinel string itself — never merges with a genuinely absent field;
  *   - W10 F-L28 pins the last two unescaped positions of the canonical form:
  *     `root=`/`session=` header values (a `\n` could forge node lines) and the
  *     refs-list `,` separator (a `,` inside one id could merge two id lists).
  */
object GraphHashSpec extends ZIOSpecDefault:

  private val rootId = RootId("root-f2")
  private val sessionId = SessionId("sess-f2")

  private def graph(nodes: SessionNode*) =
    SessionGraph(rootId, sessionId, nodes.toVector)

  private def tool(callId: String, tool: String, outcome: Option[ToolOutcome]) =
    SessionNode.ToolCall(
      NodeId("tc-1"),
      None,
      1L,
      10L,
      callId,
      tool,
      RecoveryClass.ReadOnly,
      outcome,
      Vector.empty,
      None
    )

  private def isSha256Hex(h: String) =
    h.length == 64 && h.forall(c => c.isDigit || (c >= 'a' && c <= 'f'))

  // W9-F1 builders: each targets one canonical-form position that used to
  // carry the bare "-" absent-marker. GraphHash never validates (see the F2
  // notes above) — the invariant under test is canonical-form injectivity.
  private def model(parent: Option[NodeId], inputs: Vector[NodeId]) =
    SessionNode.ModelCall(
      NodeId("m-1"),
      parent,
      1L,
      10L,
      1,
      "m",
      inputs,
      DataRef("d")
    )

  private def phase(note: Option[String]) =
    SessionNode.Phase(NodeId("p-1"), None, 1L, "setup", note)

  private def toolRef(dr: Option[DataRef]) =
    SessionNode.ToolCall(
      NodeId("tc-1"),
      None,
      1L,
      10L,
      "c",
      "t",
      RecoveryClass.ReadOnly,
      None,
      Vector.empty,
      dr
    )

  override def spec = suite("GraphHashSpec")(
    suite("LIN1A-F2 canonical-form injectivity")(
      test("F2-01: pipe in callId/tool cannot merge two different graphs") {
        val a = graph(tool("c|1", "t", None))
        val b = graph(tool("c", "1|t", None))
        assertTrue(
          a.validated.isRight,
          b.validated.isRight,
          GraphHash(a) != GraphHash(b),
          GraphHash(a) == GraphHash(graph(tool("c|1", "t", None))),
          GraphHash(b) == GraphHash(graph(tool("c", "1|t", None))),
          isSha256Hex(GraphHash(a)),
          isSha256Hex(GraphHash(b))
        )
      },
      test("F2-01: pipe cannot shift field boundaries in other esc fields") {
        // pre-fix both graphs shared one canonical line:
        // id="n|1" + parent=None and id="n" + parent=Some("1|-") both emitted
        // `model_call|n|1|-|...` (GraphHash never validates; the invariant
        // under test is canonical-form injectivity, not graph validation).
        val withWideId = SessionNode.ModelCall(
          NodeId("n|1"),
          None,
          2L,
          10L,
          1,
          "m",
          Vector(NodeId("i")),
          DataRef("d")
        )
        val withWideParent = SessionNode.ModelCall(
          NodeId("n"),
          Some(NodeId("1|-")),
          2L,
          10L,
          1,
          "m",
          Vector(NodeId("i")),
          DataRef("d")
        )
        assertTrue(
          GraphHash(graph(withWideId)) != GraphHash(graph(withWideParent))
        )
      },
      test("F2-02: hostile outcome reason keeps the hash well-formed") {
        val reason = "boom\nsecond|line\r\tctrl=\u0001\u007f"
        val a = graph(tool("c", "t", Some(ToolOutcome.Failed(reason))))
        assertTrue(
          isSha256Hex(GraphHash(a)),
          GraphHash(a) == GraphHash(
            graph(tool("c", "t", Some(ToolOutcome.Failed(reason))))
          ),
          GraphHash(a) != GraphHash(
            graph(tool("c", "t", Some(ToolOutcome.Failed("boom"))))
          ),
          GraphHash(a) != GraphHash(graph(tool("c", "t", None)))
        )
      },
      test("F2-02: reason cannot forge a following node line") {
        val forged = "r\nphase|ph-2||2|setup|-"
        val one = graph(tool("c", "t", Some(ToolOutcome.Failed(forged))))
        val two = graph(
          tool("c", "t", Some(ToolOutcome.Failed("r"))),
          SessionNode.Phase(NodeId("ph-2"), None, 2L, "setup", None)
        )
        assertTrue(GraphHash(one) != GraphHash(two))
      },
      test("F2-02: sibling reorder still normalizes with hostile reasons") {
        val hostile = tool("c", "t", Some(ToolOutcome.Failed("r\nx|y")))
        val phase = SessionNode.Phase(NodeId("ph-2"), None, 2L, "setup", None)
        val g1 = graph(hostile, phase)
        val g2 = graph(phase, hostile)
        assertTrue(GraphHash(g1) == GraphHash(g2))
      }
    ),
    suite("W9-F1 absent-field sentinel injectivity")(
      test("W9F1-01: parent value '-' is not 'no parent'") {
        // pre-fix both graphs emitted `...|model_call|m-1|-|...`: the absent
        // marker and the real parent id "-" escaped to the same text.
        val dashParent = model(Some(NodeId("-")), Vector.empty)
        val noParent = model(None, Vector.empty)
        assertTrue(
          GraphHash(graph(dashParent)) != GraphHash(graph(noParent)),
          GraphHash(graph(dashParent)) == GraphHash(
            graph(model(Some(NodeId("-")), Vector.empty))
          ),
          GraphHash(graph(noParent)) == GraphHash(
            graph(model(None, Vector.empty))
          )
        )
      },
      test("W9F1-01: '-' stays a real value in ref/inputs/note positions too") {
        assertTrue(
          GraphHash(graph(toolRef(Some(DataRef("-"))))) != GraphHash(
            graph(toolRef(None))
          ),
          GraphHash(graph(model(None, Vector(NodeId("-"))))) != GraphHash(
            graph(model(None, Vector.empty))
          ),
          GraphHash(graph(phase(Some("-")))) != GraphHash(graph(phase(None)))
        )
      },
      test(
        "W9F1-01: even the composite sentinel string as a real value stays distinct from absent"
      ) {
        // Composite sentinel per GraphHash scaladoc: raw U+0000 around the
        // word "absent". esc turns the raw NULs of a real value into the four
        // characters `\x00`, so an escaped field can never equal the raw
        // sentinel — the absent marker is unreachable for any real value.
        val sentinel = "\u0000absent\u0000"
        assertTrue(
          GraphHash(graph(model(Some(NodeId(sentinel)), Vector.empty))) !=
            GraphHash(graph(model(None, Vector.empty))),
          GraphHash(graph(phase(Some(sentinel)))) != GraphHash(
            graph(phase(None))
          )
        )
      }
    ),
    suite("W10F-L28 header and refs-list injectivity")(
      test(
        "FL28-1: newline in sessionId cannot forge node lines via the header"
      ) {
        // Pre-fix both graphs shared one canonical payload: the raw sessionId
        // entered the header unescaped, so `\n` + a verbatim copy of B's first
        // node line made two different VALIDATED graphs hash identically
        // (W10 review F-L28-1 reproducer).
        val forged = "phase|ph-2|\u0000absent\u0000|2|setup|\u0000absent\u0000"
        val a = SessionGraph(
          RootId("r"),
          SessionId("s\n" + forged),
          Vector(SessionNode.Phase(NodeId("p1"), None, 3L, "boot", None))
        )
        val b = SessionGraph(
          RootId("r"),
          SessionId("s"),
          Vector(
            SessionNode.Phase(NodeId("ph-2"), None, 2L, "setup", None),
            SessionNode.Phase(NodeId("p1"), None, 3L, "boot", None)
          )
        )
        assertTrue(
          a.validated.isRight,
          b.validated.isRight,
          GraphHash(a) != GraphHash(b),
          GraphHash(a) == GraphHash(
            SessionGraph(
              RootId("r"),
              SessionId("s\n" + forged),
              Vector(SessionNode.Phase(NodeId("p1"), None, 3L, "boot", None))
            )
          ),
          isSha256Hex(GraphHash(a)),
          isSha256Hex(GraphHash(b))
        )
      },
      test("FL28-1: newline in rootId cannot forge node lines via the header") {
        // Same collision shape as FL28-1 above, injected through the root
        // position: pre-fix a `\n` + verbatim node line in rootId merged a
        // one-node graph with a two-node graph sharing the sessionId.
        val forged = "phase|ph-2|\u0000absent\u0000|2|setup|\u0000absent\u0000"
        val a = SessionGraph(
          RootId("r\n" + forged),
          sessionId,
          Vector(SessionNode.Phase(NodeId("p1"), None, 3L, "boot", None))
        )
        val b = SessionGraph(
          RootId("r"),
          sessionId,
          Vector(
            SessionNode.Phase(NodeId("ph-2"), None, 2L, "setup", None),
            SessionNode.Phase(NodeId("p1"), None, 3L, "boot", None)
          )
        )
        assertTrue(
          GraphHash(a) != GraphHash(b),
          GraphHash(a) == GraphHash(
            SessionGraph(
              RootId("r\n" + forged),
              sessionId,
              Vector(SessionNode.Phase(NodeId("p1"), None, 3L, "boot", None))
            )
          ),
          isSha256Hex(GraphHash(a)),
          isSha256Hex(GraphHash(b))
        )
      },
      test("FL28-2: comma inside one input id is not a refs separator") {
        // Pre-fix both graphs emitted the same refs text: the single id "a,b"
        // and the pair "a" + "b" were joined by the unescaped `,` separator
        // (W10 review F-L28-2 reproducer).
        val merged = model(None, Vector(NodeId("a,b")))
        val split = model(None, Vector(NodeId("a"), NodeId("b")))
        assertTrue(
          GraphHash(graph(merged)) != GraphHash(graph(split)),
          GraphHash(graph(merged)) == GraphHash(
            graph(model(None, Vector(NodeId("a,b"))))
          ),
          GraphHash(graph(split)) == GraphHash(
            graph(model(None, Vector(NodeId("a"), NodeId("b"))))
          )
        )
      }
    )
  )
