package raider.yabanin.connection

import raider.yabanin.labels.*
import zio.Scope
import zio.json.JsonEncoder
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

/** Wrapper environment recognition, gateway URL validation, child-only
  * environment construction, and wire document shape coverage (RAI-037,
  * contract §2, `docs/ci-runtime.md`). YBCON-01: effective context with
  * sorted/canonical labels and the wrapper-first child-only env.
  */
object WrapperEnvSpec extends ZIOSpecDefault:

  private val L      = LabelLimits.Default
  private val Url    = "http://127.0.0.1:18080/gw/v1/"
  private val UrlOut = "http://127.0.0.1:18080/gw/v1"

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("WrapperEnv")(
    suite("GatewayUrl")(
      test("normalizes scheme case and trims trailing slashes, keeps port and path") {
        assertTrue(GatewayUrl.validate("HTTP://Example.com:8443/gw/v1///") == Right("http://Example.com:8443/gw/v1")) &&
        assertTrue(GatewayUrl.validate(Url) == Right(UrlOut)) &&
        assertTrue(GatewayUrl.validate("http://gw.example") == Right("http://gw.example"))
      },
      test("rejects non-absolute, non-http schemes, and missing hosts (typed, no echo)") {
        assertTrue(GatewayUrl.validate("/relative/path").isLeft) &&
        assertTrue(GatewayUrl.validate("ftp://gw.example").isLeft) &&
        assertTrue(GatewayUrl.validate("http:///no-host").isLeft) &&
        {
          val err = GatewayUrl.validate("ftp://secret-host-name.example/x").fold(_.message, _ => "")
          assertTrue(err.nonEmpty && !err.contains("secret-host-name"))
        }
      },
      test("rejects query strings, fragments, and user info") {
        assertTrue(GatewayUrl.validate("http://gw.example/p?token=x").isLeft) &&
        assertTrue(GatewayUrl.validate("http://gw.example/p#frag").isLeft) &&
        assertTrue(GatewayUrl.validate("http://user:pass@gw.example").isLeft)
      },
    ),
    suite("WrapperEnv.resolve")(
      test("pure standalone environment: owner standalone, no problems, profile inactive") {
        val ctx = WrapperEnv.resolve(Map("HOME" -> "/fixture/home"), None, Vector.empty, L)
        assertTrue(ctx.owner == LifecycleOwner.Standalone) &&
        assertTrue(ctx.gatewayUrl.isEmpty) &&
        assertTrue(ctx.problems.isEmpty) &&
        assertTrue(!ctx.credential.valuePresent) &&
        assertTrue(ctx.labels.accepted.isEmpty && ctx.labels.dropped.isEmpty)
      },
      test("full wrapper environment: owner wrapper, url normalized, labels merged and sorted") {
        val env = Map(
          "YABANIN_GATEWAY_URL" -> Url,
          "YABANIN_API_KEY"     -> "fixture-value-not-a-secret",
          "YABANIN_LABELS"      -> "task=CI-4311,pipeline=nightly",
        )
        val ctx = WrapperEnv.resolve(env, None, Vector(("task", "CLIENT-ATTEMPT"), ("harness", "raider")), L)
        assertTrue(ctx.owner == LifecycleOwner.Wrapper) &&
        assertTrue(ctx.gatewayUrl.contains(UrlOut)) &&
        assertTrue(ctx.credential.valuePresent) &&
        assertTrue(ctx.credential.reference == "YABANIN_API_KEY") &&
        assertTrue(ctx.labels.label("task").map(_.value).contains("CI-4311")) &&
        assertTrue(ctx.labels.label("task").map(_.provenance).contains(LabelProvenance.Wrapper)) &&
        assertTrue(ctx.labels.label("harness").map(_.provenance).contains(LabelProvenance.Client)) &&
        assertTrue(ctx.labels.renderHeader == "harness=raider,pipeline=nightly,task=CI-4311") &&
        assertTrue(ctx.problems.isEmpty)
      },
      test("blank YABANIN_GATEWAY_URL yields invalid_gateway_url and no_base_url, never a fake") {
        val ctx = WrapperEnv.resolve(Map("YABANIN_GATEWAY_URL" -> "   "), None, Vector.empty, L)
        assertTrue(ctx.gatewayUrl.isEmpty) &&
        assertTrue(ctx.owner == LifecycleOwner.Wrapper) &&
        assertTrue(ctx.problems.map(_.name).contains(ContextProblemName.InvalidGatewayUrl)) &&
        assertTrue(ctx.problems.map(_.name).contains(ContextProblemName.NoBaseUrl))
      },
      test("unusable gateway URL scheme yields invalid_gateway_url without echoing the value") {
        val ctx = WrapperEnv.resolve(Map("YABANIN_GATEWAY_URL" -> "ftp://unusable-host.example"), None, Vector.empty, L)
        val details = ctx.problems.map(_.detail).mkString(" ")
        assertTrue(ctx.problems.map(_.name).contains(ContextProblemName.InvalidGatewayUrl)) &&
        assertTrue(!details.contains("unusable-host"))
      },
      test("blank YABANIN_API_KEY yields blank_api_key_ref with no credential value") {
        val ctx = WrapperEnv.resolve(Map("YABANIN_API_KEY" -> ""), None, Vector.empty, L)
        assertTrue(!ctx.credential.valuePresent) &&
        assertTrue(ctx.problems.map(_.name).contains(ContextProblemName.BlankApiKeyRef))
      },
      test("unparsable YABANIN_LABELS yields wrapper_labels_unparsable while the rest resolves") {
        val env = Map(
          "YABANIN_GATEWAY_URL" -> Url,
          "YABANIN_API_KEY"     -> "fixture-value-not-a-secret",
          "YABANIN_LABELS"      -> "task",
        )
        val ctx = WrapperEnv.resolve(env, None, Vector(("harness", "raider")), L)
        assertTrue(ctx.problems.map(_.name).contains(ContextProblemName.WrapperLabelsUnparsable)) &&
        assertTrue(ctx.gatewayUrl.contains(UrlOut)) &&
        assertTrue(ctx.labels.renderHeader == "harness=raider")
      },
      test("blank YABANIN_LABELS is canonical empty text: no labels and no label problem") {
        val ctx = WrapperEnv.resolve(Map("YABANIN_LABELS" -> ""), None, Vector(("harness", "raider")), L)
        assertTrue(ctx.owner == LifecycleOwner.Wrapper) &&
        assertTrue(ctx.wrapperLabels.accepted.isEmpty) &&
        assertTrue(ctx.labels.renderHeader == "harness=raider") &&
        // The wrapper owner is active with no base URL anywhere, so the one
        // legitimate visible problem is no_base_url (contract §2); nothing
        // else may be recorded.
        assertTrue(ctx.problems.map(_.name) == Vector(ContextProblemName.NoBaseUrl))
      },
      test("explicit base URL wins over the wrapper variable") {
        val env = Map("YABANIN_GATEWAY_URL" -> Url)
        val ctx = WrapperEnv.resolve(env, Some("http://explicit.example/base"), Vector.empty, L)
        assertTrue(ctx.gatewayUrl.contains("http://explicit.example/base")) &&
        assertTrue(ctx.problems.isEmpty)
      },
      test("explicit base URL present but unusable: invalid_gateway_url plus no_base_url") {
        val ctx     = WrapperEnv.resolve(Map.empty, Some("http://bad host"), Vector.empty, L)
        val details = ctx.problems.map(_.detail).mkString(" ")
        assertTrue(ctx.gatewayUrl.isEmpty) &&
        assertTrue(ctx.problems.map(_.name).toSet ==
          Set(ContextProblemName.InvalidGatewayUrl, ContextProblemName.NoBaseUrl)) &&
        assertTrue(!details.contains("bad host"))
      },
    ),
    suite("ChildEnv.build")(
      test("wrapper-first launch: child receives only allowlisted and constructed entries") {
        val parent = Map(
          "YABANIN_GATEWAY_URL" -> Url,
          "YABANIN_API_KEY"     -> "fixture-value-not-a-secret",
          "YABANIN_LABELS"      -> "task=CI-4311",
          "PATH"                -> "/usr/bin:/bin",
          "CI_JOB_TOKEN"        -> "fixture-job-token",
          "HOME"                -> "/fixture/home",
        )
        val ctx    = WrapperEnv.resolve(parent, None, Vector(("harness", "raider")), L)
        val child  = ChildEnv.build(ctx, parent)
        val names  = child.entries.map(_._1)
        assertTrue(names.toSet ==
          Set("YABANIN_GATEWAY_URL", "YABANIN_API_KEY", "YABANIN_LABELS", "RAIDER_PROVIDER", "RAIDER_BASE_URL", "RAIDER_OPENAI_EXTRA_HEADERS")) &&
        assertTrue(names.distinct.size == names.size) &&
        assertTrue(child.entries.toMap.get("RAIDER_PROVIDER").contains("openai")) &&
        assertTrue(child.entries.toMap.get("RAIDER_BASE_URL").contains(UrlOut)) &&
        assertTrue(child.entries.toMap.get("RAIDER_OPENAI_EXTRA_HEADERS").contains("X-Yabanin-Labels: harness=raider,task=CI-4311")) &&
        assertTrue(child.omissions.isEmpty) &&
        assertTrue(child.overrides.exists(o => o.name == "YABANIN_API_KEY" && o.kind == ChildEnv.OverrideKind.PassThrough)) &&
        assertTrue(child.overrides.exists(o => o.name == "RAIDER_PROVIDER" && o.kind == ChildEnv.OverrideKind.FixedProvider)) &&
        assertTrue(child.overrides.exists(o => o.name == "RAIDER_BASE_URL" && o.kind == ChildEnv.OverrideKind.ResolvedBaseUrl))
      },
      test("non-allowlisted parent variables never reach the child") {
        val parent = Map("SECRET_TOKEN" -> "fixture-token-value", "GITHUB_TOKEN" -> "fixture-gh-token")
        val ctx    = WrapperEnv.resolve(Map.empty, None, Vector.empty, L)
        val child  = ChildEnv.build(ctx, parent)
        assertTrue(!child.entries.exists(e => e._1.contains("TOKEN"))) &&
        assertTrue(child.entries.map(_._1).toSet == Set("RAIDER_PROVIDER")) &&
        assertTrue(child.omissions.nonEmpty)
      },
      test("blank allowlisted values become visible omissions, not silent passes") {
        val parent = Map("YABANIN_GATEWAY_URL" -> "", "YABANIN_API_KEY" -> "  ")
        val ctx    = WrapperEnv.resolve(parent, None, Vector.empty, L)
        val child  = ChildEnv.build(ctx, parent)
        assertTrue(!child.entries.exists(_._1 == "YABANIN_GATEWAY_URL")) &&
        assertTrue(child.omissions.exists(_.name == "YABANIN_GATEWAY_URL")) &&
        assertTrue(child.omissions.exists(_.name == "YABANIN_API_KEY")) &&
        assertTrue(child.omissions.exists(_.name == "RAIDER_BASE_URL")) &&
        assertTrue(!child.entries.exists(_._1 == "RAIDER_OPENAI_EXTRA_HEADERS"))
      },
      test("empty merged labels omit the extra-headers entry entirely") {
        val ctx   = WrapperEnv.resolve(Map.empty, None, Vector.empty, L)
        val child = ChildEnv.build(ctx, Map.empty)
        assertTrue(!child.entries.exists(_._1 == "RAIDER_OPENAI_EXTRA_HEADERS")) &&
        assertTrue(!child.overrides.exists(_.name == "RAIDER_OPENAI_EXTRA_HEADERS"))
      },
    ),
    suite("documents")(
      test("wrapper-env document field order matches the schema property order") {
        val ctx   = WrapperEnv.resolve(Map("YABANIN_GATEWAY_URL" -> Url), None, Vector(("harness", "raider")), L)
        val child = ChildEnv.build(ctx, Map("YABANIN_GATEWAY_URL" -> Url))
        val fields = WrapperEnvDocument.toAst(ctx, L, Some(child)) match
          case Json.Obj(fields) => fields.map(_._1).toVector
          case _                => Vector.empty
        assertTrue(fields == Vector("gateway_url", "credential", "labels", "owner", "problems", "child_env")) &&
        assertTrue(
          fieldNamesOf(WrapperEnvDocument.toAst(ctx, L, Some(child)), "credential") ==
            Vector("kind", "reference", "value_present")
        ) &&
        assertTrue(
          fieldNamesOf(WrapperEnvDocument.toAst(ctx, L, Some(child)), "labels") ==
            Vector("accepted", "dropped", "header", "limits")
        )
      },
      test("wrapper-env document omits child_env when not built and keeps gateway_url null explicit") {
        val ctx = WrapperEnv.resolve(Map.empty, None, Vector.empty, L)
        val json = summon[JsonEncoder[WrapperEnvDocument.Document]]
          .encodeJson(WrapperEnvDocument.Document(ctx, L, None), None).toString
        assertTrue(json.contains("\"gateway_url\":null")) &&
        assertTrue(!json.contains("child_env")) &&
        assertTrue(json.contains("\"value_present\":false")) &&
        assertTrue(json.contains("\"owner\":\"standalone\""))
      },
      test("labels document limits block matches the schema field set and order") {
        val set = LabelHeader.fromClient(Vector(("harness", "raider")), L)
        val fields = LabelSetDocument.toAst(set, L) match
          case Json.Obj(fields) => fields.map(_._1).toVector
          case _                => Vector.empty
        assertTrue(fields == Vector("accepted", "dropped", "header", "limits")) &&
        assertTrue(
          fieldNamesOf(LabelSetDocument.toAst(set, L), "limits") ==
            Vector("max_per_source", "max_effective", "max_value_length_bytes", "reserved_key_prefix", "client_key_max_length")
        ) &&
        {
          // accepted entries expose exactly key, value, provenance.
          val entryFields = LabelSetDocument.toAst(set, L) match
            case Json.Obj(fields) =>
              fields.find(_._1 == "accepted") match
                case Some((_, Json.Arr(items))) =>
                  items.map {
                    case Json.Obj(entry) => entry.map(_._1).toVector
                    case _               => Vector.empty[String]
                  }.toVector
                case _ => Vector.empty[Vector[String]]
            case _ => Vector.empty[Vector[String]]
          assertTrue(entryFields == Vector(Vector("key", "value", "provenance")))
        }
      },
    ),
  )

  private def fieldNamesOf(json: Json, field: String): Vector[String] = json match
    case Json.Obj(fields) =>
      fields.find(_._1 == field).map(_._2) match
        case Some(Json.Obj(inner)) => inner.map(_._1).toVector
        case _                     => Vector.empty
    case _ => Vector.empty
