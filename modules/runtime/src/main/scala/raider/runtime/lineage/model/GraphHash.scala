package raider.runtime.lineage.model

import java.security.MessageDigest

/** Deterministic SHA-256 fingerprint of a [[SessionGraph]] (packet L1,
  * LIN1-A-03; canonical-form injectivity fixed by packet L18, LIN-01A-fix / W8
  * review F2).
  *
  * Canonical form: one fixed-order field line per node, node lines sorted by
  * `(seq, id)`. Sorting makes the hash INVARIANT under sibling reordering of
  * the `nodes` vector (parents/ids/seqs unchanged) — the emission order is a
  * reducer property, not a graph identity property. Field order inside a line
  * is fixed; heap/map iteration order never enters. Hex lowercase, 64 chars.
  *
  * Field separator is `|`, line separator is `\n`; `refs` lists separate ids
  * with `,`. To keep the canonical form INJECTIVE (two different graphs must
  * never share a canonical string), every free-text field is passed through
  * [[esc]] before it enters a line — including the `root=`/`session=` header
  * values (W10 review F-L28-1). Escape scheme:
  *
  *   - `\` → `\\`
  *   - `|` (field sep) → `\|`
  *   - `,` (refs-list sep) → `\,`
  *   - `\n` (line sep) → `\n`
  *   - `\r` → `\r`
  *   - `\t` → `\t`
  *   - any other C0 control (U+0000–U+001F) or DEL (U+007F) → `\xHH`, two
  *     lowercase hex digits of the code point
  *
  * Every `\` in an escaped field therefore starts an escape sequence, and no
  * escaped field contains a literal `|`, `,`, newline or control character, so
  * field/line/refs-list boundaries stay unambiguous and the original value is
  * recoverable from the canonical line.
  *
  * Absent/empty fields (None parent, None `dataRef`/outcome/note, empty
  * `inputs`) are marked with the composite sentinel `Absent` — the raw word
  * "absent" wrapped in raw U+0000 on both sides. [[esc]] never emits a raw
  * control character (a U+0000 inside a real value becomes the four characters
  * `\x00`), so no valid esc-value can produce the sentinel byte sequence and
  * the "field is absent" marker is unreachable for any real value — including
  * the historical bare `-` marker itself (W9 review F1).
  *
  * BREAKING (LIN-01A-fix, W8 review F2): the previous scheme escaped only `\`
  * and `\n`, so a `|` inside callId/tool/node-id values shifted field
  * boundaries and collided two different graphs onto one hash, and a raw
  * tool-outcome reason (arbitrary stderr bytes) could break the canonical line
  * structure. Hash values produced by the previous canonicalization are NOT
  * comparable with hashes produced by the current scheme; consumers must
  * recompute, never compare across the scheme change.
  *
  * BREAKING (W9 review F1): the previous scheme marked absent fields with a
  * bare `-`, which collided with real `-` values (a parent id/data ref/input
  * id/note of `-` was indistinguishable from the field being absent). Canonical
  * hashes from either earlier scheme are NOT comparable with the current
  * scheme; consumers must recompute, never compare across scheme changes.
  *
  * BREAKING (W10 review F-L28-1/F-L28-2): the previous scheme inserted the
  * `root=`/`session=` header values raw and joined `refs` lists with a raw `,`,
  * so a `\n` inside a RootId/SessionId could forge fake node lines (two
  * different VALIDATED graphs sharing one hash) and a `,` inside a single id
  * merged `[a,b]` with `[a],[b]`. Both positions now go through [[esc]].
  * Canonical hashes from the earlier schemes are NOT comparable with the
  * current scheme; consumers must recompute, never compare across scheme
  * changes.
  */
object GraphHash:

  /** Composite marker for absent/empty fields — see the object scaladoc. Raw
    * U+0000 on both sides makes it NOT representable as valid [[esc]] output:
    * esc escapes every U+0000 of a real value into the text `\x00`, so the raw
    * sentinel can never collide with an escaped field value (W9 review F1). Not
    * wire-facing: it exists only inside the hashed canonical string.
    */
  private val Absent = "\u0000" + "absent" + "\u0000"

  def apply(g: SessionGraph): String =
    val lines = g.nodes
      .map(n => (n.seq, n.id.value, canonicalNode(n)))
      .sortBy((seq, id, _) => (seq, id))
      .map((_, _, line) => line)
    val payload =
      s"session-graph/v1\nroot=${esc(g.rootId.value)}\nsession=${esc(g.sessionId.value)}\n" +
        lines.mkString("", "\n", "\n")
    hex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes("UTF-8")))

  private def hex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString

  /** Escape scheme for canonical-form fields — see the object scaladoc.
    * Injective by construction: `\` is escaped first class, so each `\` in the
    * output opens exactly one of the listed sequences.
    */
  private def esc(s: String): String =
    val out = new StringBuilder(s.length + 8)
    s.foreach {
      case '\\'                          => out.append("\\\\")
      case '|'                           => out.append("\\|")
      case ','                           => out.append("\\,")
      case '\n'                          => out.append("\\n")
      case '\r'                          => out.append("\\r")
      case '\t'                          => out.append("\\t")
      case c if c < ' ' || c == '\u007f' => out.append(f"\\x${c.toInt}%02x")
      case c                             => out.append(c)
    }
    out.result()

  private def optRef(r: Option[DataRef]): String =
    r.fold(Absent)(d => esc(d.value))

  private def refs(ids: Vector[NodeId]): String =
    if ids.isEmpty then Absent
    else ids.map(i => esc(i.value)).mkString(",")

  private def canonicalNode(n: SessionNode): String = n match
    case SessionNode.ModelCall(
          id,
          parent,
          seq,
          atMs,
          round,
          model,
          inputs,
          dr
        ) =>
      s"model_call|${esc(id.value)}|${parent.fold(Absent)(p => esc(p.value))}|$seq|$atMs|$round|${esc(model)}|${refs(inputs)}|${esc(dr.value)}"
    case SessionNode.ToolCall(
          id,
          parent,
          seq,
          atMs,
          callId,
          tool,
          recovery,
          outcome,
          inputs,
          dr
        ) =>
      s"tool_call|${esc(id.value)}|${parent.fold(Absent)(p => esc(p.value))}|$seq|$atMs|${esc(callId)}|${esc(tool)}|${recovery.toString}|${outcome
          .fold(Absent)(o => esc(o.toString))}|${refs(inputs)}|${optRef(dr)}"
    case SessionNode.Delegation(
          id,
          parent,
          seq,
          atMs,
          childJobId,
          agent,
          inputs
        ) =>
      s"delegation|${esc(id.value)}|${parent.fold(Absent)(p => esc(p.value))}|$seq|$atMs|${esc(childJobId.value)}|${esc(agent)}|${refs(inputs)}"
    case SessionNode.Phase(id, parent, seq, name, note) =>
      s"phase|${esc(id.value)}|${parent.fold(Absent)(p => esc(p.value))}|$seq|${esc(name)}|${note.fold(Absent)(esc)}"
