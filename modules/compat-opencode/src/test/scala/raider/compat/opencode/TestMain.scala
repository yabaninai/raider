package raider.compat.opencode

import zio.test.*

/** Manual runner for the compat-opencode suite (the module is not registered
  * in the coordinator-owned sbt build; see docs/work/records/RAI-033.md).
  * Run via modules/compat-opencode/run-tests.sh from the worktree root, or
  * set RAIDER_REPO_ROOT for the corpus and golden fixture locations.
  */
object TestMain extends ZIOSpecDefault:

  def spec = suite("raider.compat.opencode")(
    WildcardSpec.spec,
    EvaluateSpec.spec,
    PermissionMigrationSpec.spec,
    MarkdownProfilesSpec.spec,
    RegistrySpec.spec,
    ImporterGoldenSpec.spec,
  )
