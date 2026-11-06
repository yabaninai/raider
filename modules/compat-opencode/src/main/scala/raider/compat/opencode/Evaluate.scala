package raider.compat.opencode

/** Faithful port of the pinned OpenCode Permission.evaluate / resolve
  * semantics (vendor/opencode-profiles/upstream/core/permission.ts): the last
  * matching rule wins, a missing match asks; across multiple resources a deny
  * wins first, otherwise any ask yields ask, otherwise allow.
  */
object Evaluate:

  def effect(action: String, resource: String, rules: Vector[PermissionRule]): String =
    rules
      .findLast(r => Wildcard.matches(action, r.action) && Wildcard.matches(resource, r.resource))
      .map(_.effect)
      .getOrElse("ask")

  def resolve(action: String, resources: Vector[String], rules: Vector[PermissionRule]): String =
    if resources.exists(r => effect(action, r, rules) == "deny") then "deny"
    else if resources.exists(r => effect(action, r, rules) == "ask") then "ask"
    else "allow"
