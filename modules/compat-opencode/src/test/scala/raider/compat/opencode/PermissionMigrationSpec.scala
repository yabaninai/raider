package raider.compat.opencode

import zio.Scope
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*


/** Faithfulness spec for the pinned V1 -> V2 permission migration
  * (vendor/opencode-profiles/upstream/core/config-normalize.ts migrateTools /
  * migratePermissions, and v1-config-migrate.ts normalizeAction /
  * permissions()).
  */
object PermissionMigrationSpec extends ZIOSpecDefault:

  private def parse(s: String): Json =
    Json.decoder.decodeJson(s) match
      case Right(v) => v
      case Left(e)  => sys.error(s"test fixture is not JSON: $s ($e)")

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("PermissionMigration")(
    test("normalizeAction renames V1 actions") {
      assertTrue(PermissionMigration.normalizeAction("write") == "edit") &&
      assertTrue(PermissionMigration.normalizeAction("patch") == "edit") &&
      assertTrue(PermissionMigration.normalizeAction("apply_patch") == "edit") &&
      assertTrue(PermissionMigration.normalizeAction("task") == "subagent") &&
      assertTrue(PermissionMigration.normalizeAction("bash") == "shell") &&
      assertTrue(PermissionMigration.normalizeAction("read") == "read") &&
      assertTrue(PermissionMigration.normalizeAction("question") == "question") &&
      assertTrue(PermissionMigration.normalizeAction("webfetch") == "webfetch")
    },
    test("migrateTools maps booleans onto allow/deny with resource *") {
      val r = PermissionMigration.migrateTools(parse("""{"write": false, "grep": true}"""))
      assertTrue(
        r.rules == Vector(
          PermissionRule("edit", "*", "deny"),
          PermissionRule("grep", "*", "allow"),
        )
      ) && assertTrue(r.diagnostics.isEmpty)
    },
    test("migrateTools rejects non-record and non-boolean entries") {
      val bad = PermissionMigration.migrateTools(parse("""42"""))
      assertTrue(bad.rules.isEmpty) && assertTrue(bad.diagnostics.exists(_.kind == "invalid")) &&
      assertTrue(
        PermissionMigration
          .migrateTools(parse("""{"grep": "yes"}"""))
          .diagnostics
          .exists(d => d.kind == "invalid" && d.path == Vector("tools", "grep"))
      )
    },
    test("migratePermissions handles string form as global * rule") {
      val r = PermissionMigration.migratePermissions(parse(""""deny""""))
      assertTrue(r.rules == Vector(PermissionRule("*", "*", "deny"))) && assertTrue(r.diagnostics.isEmpty)
    },
    test("migratePermissions handles action-string form") {
      val r = PermissionMigration.migratePermissions(parse("""{"edit": "deny", "webfetch": "ask"}"""))
      assertTrue(
        r.rules == Vector(
          PermissionRule("edit", "*", "deny"),
          PermissionRule("webfetch", "*", "ask"),
        )
      ) && assertTrue(r.diagnostics.isEmpty)
    },
    test("migratePermissions handles action-resource-effect maps in key order") {
      val r = PermissionMigration.migratePermissions(
        parse("""{"bash": {"*": "ask", "git diff": "allow", "git log*": "allow", "grep *": "allow"}}""")
      )
      assertTrue(
        r.rules == Vector(
          PermissionRule("shell", "*", "ask"),
          PermissionRule("shell", "git diff", "allow"),
          PermissionRule("shell", "git log*", "allow"),
          PermissionRule("shell", "grep *", "allow"),
        )
      ) && assertTrue(r.diagnostics.isEmpty)
    },
    test("migratePermissions reports unknown effects as invalid") {
      val r = PermissionMigration.migratePermissions(parse("""{"edit": {"*": "nope"}}"""))
      assertTrue(r.rules.isEmpty) &&
      assertTrue(r.diagnostics.exists(d => d.kind == "invalid" && d.path.startsWith(Vector("permission", "edit"))))
    },
    test("migratePermissions reports unknown action-effect literals as invalid") {
      val r = PermissionMigration.migratePermissions(parse("""{"edit": "maybe"}"""))
      assertTrue(r.rules.isEmpty) &&
      assertTrue(r.diagnostics.exists(d => d.kind == "invalid" && d.path == Vector("permission", "edit")))
    },
  )

