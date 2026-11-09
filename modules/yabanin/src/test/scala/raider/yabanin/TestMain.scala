package raider.yabanin

import zio.Scope
import zio.test.*

/** Test entrypoint for the yabanin module (RAI-037). Run via
  * modules/yabanin/run-tests.sh, which sets RAIDER_REPO_ROOT for the contract
  * goldens.
  *
  * The default TestEnvironment is kept: pure contract specs never consult the
  * clock, and the doctor spec runs its real loopback HTTP calls through
  * `ZIO.attemptBlocking` (blocking executor), never through timed effects on
  * the frozen TestClock.
  */
object TestMain extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("raider.yabanin")(
      raider.yabanin.labels.PathEscapeSpec.spec,
      raider.yabanin.labels.LabelsSpec.spec,
      raider.yabanin.connection.WrapperEnvSpec.spec,
      raider.yabanin.doctor.DoctorSpec.spec
    )
