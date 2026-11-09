package raider.evalexport

import zio.Scope
import zio.test.*

/** Test entrypoint for the eval-export module (RAI-040). Run via
  * modules/eval-export/run-tests.sh, which sets RAIDER_REPO_ROOT for the
  * pinned contract goldens (contracts/eval-export/expected) and the pinned
  * schema set (contracts/eval-export/schemas).
  *
  * The default TestEnvironment is kept: every spec in this module is pure
  * (deterministic fixtures, no clock, no IO beyond the repo-relative golden
  * reads), so no Live.clock juggling is needed. Suites:
  *   - ExportSpec  (EXPORT-01): byte-exact goldens, wire round-trip,
  *     manifest shape/coverage/integrity, lineage reconstruction, schema
  *     header pins, determinism.
  *   - HonestySpec (EXPORT-02): no fabricated verdicts (pending/absent with
  *     explicit reasons), args digested never embedded, env NAME-only refs,
  *     unknown prices null, attempt/episode completeness.
  *   - ReconcileSpec (EXPORT-03): partial/cancelled run golden, append
  *     discipline (other documents byte-identical), versioned per-call chain,
  *     tamper-evident file chain, late-usage golden.
  */
object TestMain extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("raider.evalexport")(
    ExportSpec.spec,
    ReconcileSpec.spec,
    HonestySpec.spec,
  )
