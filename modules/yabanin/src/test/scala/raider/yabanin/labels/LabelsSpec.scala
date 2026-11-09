package raider.yabanin.labels

import zio.Scope
import zio.test.*
import zio.test.Assertion.*

/** Canonical label-set contract coverage (RAI-037, contract §1): pinned
  * render/merge/parse cases from `expected/labels-golden.json`, limits
  * equality between the implementation defaults and the golden pin, and the
  * YBCON-03 acceptance behaviors (matrix roots/attempts distinct; merging
  * wrapper labels preserves protected CI attribution).
  */
object LabelsSpec extends ZIOSpecDefault:

  private val L = LabelLimits.Default

  private def pairsOf(ps: Vector[Goldens.LabelPair]): Vector[(String, String)] =
    ps.map(p => (p.key, p.value))

  private def acceptedCheck(set: LabelSet, expected: Vector[Goldens.LabelPair], withProvenance: Boolean): TestResult =
    val got = set.accepted.map(l => (l.key, l.value))
    if got != expected.map(p => (p.key, p.value)) then
      assertTrue(s"accepted = $got, want ${expected.map(p => (p.key, p.value))}" == "")
    else if withProvenance then
      val gotProv = set.accepted.map(l => (l.key, l.provenance.encode))
      val wantProv = expected.map(p => (p.key, p.provenance.getOrElse("client")))
      if gotProv == wantProv then assertTrue(true)
      else assertTrue(s"accepted provenance = $gotProv, want $wantProv" == "")
    else assertTrue(true)

  private def droppedCheck(set: LabelSet, expected: Vector[Goldens.DroppedGolden]): TestResult =
    val got = set.dropped.map(d => (d.key, d.reason.encode))
    if got == expected.map(d => (d.key, d.reason)) then assertTrue(true)
    else assertTrue(s"dropped = $got, want ${expected.map(d => (d.key, d.reason))}" == "")

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("Labels")(
    test("implementation default limits equal the golden pin") {
      val g = Goldens.labels.limits
      assertTrue(L.maxPerSource == g.max_per_source) &&
      assertTrue(L.maxEffective == g.max_effective) &&
      assertTrue(L.maxValueLengthBytes == g.max_value_length_bytes) &&
      assertTrue(L.reservedKeyPrefix == g.reserved_key_prefix) &&
      assertTrue(L.clientKeyMaxLength == g.client_key_max_length)
    },
    test("render cases produce the pinned sorted canonical header, accepted set, and drops") {
      val results = Goldens.labels.render_cases.map { c =>
        val set = LabelHeader.fromClient(pairsOf(c.client_labels), L)
        assertTrue(set.renderHeader == c.expected_header) &&
        acceptedCheck(set, c.expected_accepted, withProvenance = false) &&
        droppedCheck(set, c.expected_dropped)
      }
      results.foldLeft[TestResult](assertTrue(true))(_ && _)
    },
    test("merge cases admit wrapper labels first and preserve provenance") {
      val results = Goldens.labels.merge_cases.map { c =>
        val wrapper = LabelHeader.fromWrapper(pairsOf(c.wrapper_labels), L)
        val client  = LabelHeader.fromClient(pairsOf(c.client_labels), L)
        val merged  = LabelHeader.merge(wrapper, client, L)
        assertTrue(merged.renderHeader == c.expected_header) &&
        acceptedCheck(merged, c.expected_accepted, withProvenance = true) &&
        droppedCheck(merged, c.expected_dropped)
      }
      results.foldLeft[TestResult](assertTrue(true))(_ && _)
    },
    test("parse cases accept canonical text and reject violations with pinned reasons") {
      val results = Goldens.labels.parse_cases.map { c =>
        LabelHeader.parseRaw(c.raw, L) match
          case Right(pairs) =>
            c.expected_pairs match
              case Some(expected) =>
                // The golden pins the decoded pair set in canonical sorted
                // order; wire order of the raw text is not pinned.
                val got    = pairs.map(p => (p.key, p.value)).sortBy(_._1)
                val wanted = expected.map(p => (p.key, p.value)).sortBy(_._1)
                if got == wanted then assertTrue(true)
                else assertTrue(s"case ${c.name}: pairs = $got, want $wanted" == "")
              case None => assertTrue(s"case ${c.name}: unexpected parse success" == "")
          case Left(err) =>
            c.expected_error_reason match
              case Some(reason) => assertTrue(err.reason == reason)
              case None         => assertTrue(s"case ${c.name}: unexpected error ${err.message}" == "")
      }
      results.foldLeft[TestResult](assertTrue(true))(_ && _)
    },
    test("parsed pairs are attributed wrapper provenance") {
      val golden = Goldens.labels
      val results = golden.parse_cases.map { c =>
        LabelHeader.parseRaw(c.raw, L) match
          case Right(pairs) =>
            if pairs.forall(_.provenance == LabelProvenance.Wrapper) then assertTrue(true)
            else assertTrue(s"case ${c.name}: provenance = ${pairs.map(_.provenance)}, want all wrapper" == "")
          case Left(_) => assertTrue(true) // rejection pinned by the reason cases above
      }
      results.foldLeft[TestResult](assertTrue(true))(_ && _)
    },
    test("YBCON-03: matrix roots and attempts produce pairwise-distinct canonical headers") {
      val matrix = Goldens.labels.render_cases.filter(_.name.startsWith("matrix-"))
      assertTrue(matrix.size == 3) &&
      assertTrue(matrix.map(_.expected_header).distinct.size == 3) &&
      {
        val results = matrix.map { c =>
          val set = LabelHeader.fromClient(pairsOf(c.client_labels), L)
          assertTrue(s"${c.name}: ${set.renderHeader} != ${c.expected_header}" == "") || assertTrue(
            set.renderHeader == c.expected_header
          )
        }
        results.foldLeft[TestResult](assertTrue(true))(_ && _)
      }
    },
    test("YBCON-03: merging preserves the protected wrapper CI attribution") {
      val c = Goldens.labels.merge_cases.find(_.name == "merge-protected").getOrElse(
        throw new IllegalStateException("merge-protected case missing from the golden")
      )
      val wrapper = LabelHeader.fromWrapper(pairsOf(c.wrapper_labels), L)
      val client  = LabelHeader.fromClient(pairsOf(c.client_labels), L)
      val merged  = LabelHeader.merge(wrapper, client, L)
      val task    = merged.label("task")
      assertTrue(task.map(_.value).contains("CI-4311")) &&
      assertTrue(task.map(_.provenance).contains(LabelProvenance.Wrapper)) &&
      assertTrue(!merged.renderHeader.contains("CLIENT-ATTEMPT")) &&
      assertTrue(merged.dropped.exists(d => d.key == "task" && d.reason == DropReason.ProtectedWrapperKey))
    },
    test("merge carries client canonicalization drops visibly (nothing silently discarded)") {
      val wrapper = LabelHeader.fromWrapper(Vector(("task", "CI-1")), L)
      val client  = LabelHeader.fromClient(Vector(("bad key", "x"), ("ok", "v")), L)
      val merged  = LabelHeader.merge(wrapper, client, L)
      assertTrue(merged.dropped.exists(d => d.key == "bad key" && d.reason == DropReason.MalformedKey)) &&
      assertTrue(merged.label("ok").isDefined) &&
      assertTrue(merged.label("task").map(_.provenance).contains(LabelProvenance.Wrapper))
    },
    test("canonicalization check order matches contract §1.3 (duplicate wins before cap)") {
      // 21 distinct pairs + a duplicate of an already-admitted key: the
      // duplicate drop is recorded before the per-source cap kicks in.
      val input = Vector(("task", "FIRST"), ("task", "SECOND")) ++
        (1 to 19).map(i => (f"k$i%02d", s"v$i")).toVector
      val set = LabelHeader.fromClient(input, L)
      assertTrue(set.accepted.size == L.maxPerSource) &&
      assertTrue(set.dropped.count(_.reason == DropReason.DuplicateKey) == 1) &&
      assertTrue(set.dropped.count(_.reason == DropReason.PerSourceOverflow) == 0)
    },
  )
