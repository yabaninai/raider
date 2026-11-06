package raider.backend.opencode

import zio.Scope
import zio.test.*

/** Test entrypoint for the backend-opencode module (RAI-035). Run via
  * modules/backend-opencode/run-tests.sh, which sets RAIDER_REPO_ROOT for the
  * pinned-events corpus.
  *
  * The default TestEnvironment is kept: the pure unit specs (OcSse framer,
  * OcWire codecs) do not consult the clock, and the backend spec provides the
  * live Clock per test through its helpers (see OpenCodeBackendSpec: the
  * suite macro of zio-test 2.1.26 matches the test-body environment literally,
  * so bodies stay ZIO[Any] and timed effects receive Clock.ClockLive inside
  * the helper). Under the default TestClock a timed wait would never advance,
  * which is why no helper ever leaves the live Clock implicit.
  */
object TestMain extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("raider.backend.opencode")(
    OcSseSpec.spec,
    OcWireSpec.spec,
    OpenCodeBackendSpec.spec,
  )
