package raider.runtime.lineage.model

import raider.core.{RootId, SessionId}
import zio.test.*

/** RAI-148 acceptance (docs/tasks/lineage.md, ADR-027 §6.2):
  * [[RecoveryTaxonomy]] is the single static tool-name → [[RecoveryClass]]
  * source, and `fs_patch`/`proc_run` are Mutating.
  *
  *   - TAX-01: unit pins — every exec-class name (including `proc_run`) and
  *     every write-class name (including `fs_patch`, the owner-flagged miss:
  *     captured repairs were classified ReadOnly despite modifying source)
  *     classifies Mutating through [[RecoveryTaxonomy.classify]].
  *   - RAI-268: the RAI-260 structured mutation tools `fs_patch_multi` and
  *     `fs_delete` join the write class — lineage reducers, live graphs and the
  *     CLI isolation guard must not under-report them (the registry stamps are
  *     Mutating). Classification is stable under repeated evaluation (pure
  *     lookup, no concurrency surface).
  *   - TAX-03: the ADR default is unchanged — UNKNOWN tool names stay ReadOnly,
  *     and the static taxonomy never guesses `Idempotent`. Matching is EXACT:
  *     case variants and near-misses of KNOWN names stay ReadOnly too.
  *   - TAX-03 (hash policy): the recovery verdict is PART of the [[GraphHash]]
  *     canonical form (`tool_call|...|tool|recovery|...`), so reclassifying a
  *     historical recorded graph (fs_patch/proc_run ReadOnly → Mutating)
  *     changes its hash BY DESIGN. Graphs without reclassified tools hash
  *     identically — pinned here with one pair of nodes differing only in the
  *     verdict, and one no-change pair.
  *
  * The no-second-copy property is enforced by review + grep evidence in the
  * work record (set literals exist only in this object), not by a unit test.
  */
object RecoveryTaxonomySpec extends ZIOSpecDefault:

  private def classifiedMutating(names: Set[String]): Vector[String] =
    names.toVector.sorted.filterNot(RecoveryTaxonomy.isMutating)

  /** Minimal valid graph holding ONE tool-call node with the given verdict. */
  private def oneToolGraph(
      tool: String,
      recovery: RecoveryClass
  ): SessionGraph =
    SessionGraph(
      RootId("root-tax"),
      SessionId("sess-tax"),
      Vector(
        SessionNode.ToolCall(
          NodeId("job-tax:n3"),
          None,
          3L,
          1000L,
          "call-1",
          tool,
          recovery,
          None,
          Vector.empty,
          None
        )
      )
    ).validated.fold(
      violation =>
        throw new IllegalStateException(s"fixture invalid: $violation"),
      identity
    )

  def spec = suite("RecoveryTaxonomy (RAI-148)")(
    test(
      "TAX-01: every exec-class name classifies Mutating (proc_run included)"
    ) {
      val misses = classifiedMutating(RecoveryTaxonomy.ExecTools)
      assertTrue(
        RecoveryTaxonomy.ExecTools ==
          Set("bash", "process", "proc_run", "python", "sh"),
        RecoveryTaxonomy.classify("proc_run") == RecoveryClass.Mutating,
        RecoveryTaxonomy.isMutating("proc_run"),
        misses.isEmpty
      )
    },
    test(
      "TAX-01: every write-class name classifies Mutating (fs_patch included; RAI-268: fs_patch_multi and fs_delete included)"
    ) {
      val misses = classifiedMutating(RecoveryTaxonomy.WriteTools)
      assertTrue(
        RecoveryTaxonomy.WriteTools == Set(
          "apply_patch",
          "edit",
          "fs_delete",
          "fs_edit",
          "fs_patch",
          "fs_patch_multi",
          "fs_write",
          "mkdir",
          "mv",
          "patch",
          "rm",
          "touch",
          "write"
        ),
        RecoveryTaxonomy.classify("fs_patch") == RecoveryClass.Mutating,
        RecoveryTaxonomy.isMutating("fs_patch"),
        misses.isEmpty
      )
    },
    test(
      "RAI-268: fs_patch_multi and fs_delete classify Mutating — classify/isMutating agree, stable under repeated evaluation"
    ) {
      val names = Vector("fs_patch_multi", "fs_delete")
      // pure lookup: repeated evaluation returns the identical verdict every
      // time (no memoization drift, no concurrency surface to expose)
      val repeated =
        names.map(n => (1 to 64).map(_ => RecoveryTaxonomy.classify(n)).toSet)
      assertTrue(
        names.forall(RecoveryTaxonomy.classify(_) == RecoveryClass.Mutating),
        names.forall(RecoveryTaxonomy.isMutating(_)),
        names.forall(n =>
          RecoveryTaxonomy.isMutating(n) ==
            (RecoveryTaxonomy.classify(n) == RecoveryClass.Mutating)
        ),
        repeated.forall(_ == Set(RecoveryClass.Mutating)),
        names
          .forall(n => RecoveryTaxonomy.classify(n) != RecoveryClass.Idempotent)
      )
    },
    test(
      "TAX-03: unknown tools stay ReadOnly-by-default (ADR discipline unchanged; RAI-268 case variants included)"
    ) {
      val unknown = Vector(
        "fs_read",
        "fs_search",
        "fs_tree",
        "search",
        "web_search",
        "fs_read_v2",
        "totally_unknown",
        "FS_PATCH",
        "fs_patch ",
        "",
        "patch-tool",
        // RAI-268: exact-name matching — variants/near-misses of the two new
        // Mutating names never gain Mutating power through the default
        "FS_DELETE",
        "FS_PATCH_MULTI",
        "fs_Delete",
        "Fs_Patch_Multi",
        "fs_delete ",
        " fs_delete",
        "fs_patch_multi ",
        "fs_delete_v2",
        "fs_patch_multi_x",
        "fs_patch_mulit",
        "delete",
        "fs_remove"
      )
      assertTrue(
        unknown
          .map(RecoveryTaxonomy.classify(_))
          .forall(_ == RecoveryClass.ReadOnly),
        unknown.forall(!RecoveryTaxonomy.isMutating(_))
      )
    },
    test(
      "TAX-03: the static taxonomy never guesses Idempotent"
    ) {
      val all = RecoveryTaxonomy.ExecTools ++ RecoveryTaxonomy.WriteTools ++
        Vector("fs_read", "unknown-thing", "")
      assertTrue(
        all
          .map(RecoveryTaxonomy.classify(_))
          .forall(_ != RecoveryClass.Idempotent)
      )
    },
    test(
      "TAX-03 hash policy: the recovery verdict enters GraphHash — reclassification changes the hash by design"
    ) {
      val readOnly = GraphHash(oneToolGraph("fs_patch", RecoveryClass.ReadOnly))
      val mutating =
        GraphHash(oneToolGraph("fs_patch", RecoveryClass.Mutating))
      val unchangedA =
        GraphHash(oneToolGraph("fs_read", RecoveryClass.ReadOnly))
      val unchangedB =
        GraphHash(oneToolGraph("fs_read", RecoveryClass.ReadOnly))
      assertTrue(
        readOnly != mutating,
        unchangedA == unchangedB
      )
    }
  )

end RecoveryTaxonomySpec
