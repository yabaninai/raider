package raider.cli.run

import raider.core.RaiderError
import zio.json.{DecoderOps, EncoderOps}
import zio.test._

/** RAI-078 acceptance (RAI-023/025 slice), CICTX-01..03:
  *   - CICTX-01: generic/github/gitlab fixture roundtrips (parse/encode
  *     lossless; env builders map their fixed allowlist exactly);
  *   - CICTX-02: secret-named env vars (TOKEN|KEY|PASSWORD|SECRET) are dropped
  *     BEFORE any value is read — values appear in neither the full context
  *     JSON nor the redacted view; the drop counter counts them;
  *   - CICTX-03: unknown ci type / missing required fields / broken JSON are
  *     typed `CiContextError`s with an explicit `toRaiderError`.
  *
  * Pure suite: no env access, no network, no spend.
  */
object CiContextSpec extends ZIOSpecDefault:

  private val sha1 = "3f0ad0c9b19e6c58f76a5b2e2d0f1c4a9e8d7c6b"
  private val sha2 = "0123456789abcdef0123456789abcdef01234567"
  private val sha3 = "fedcba9876543210fedcba9876543210fedcba98"

  private val genericCtx = CiContext(
    ci = "generic",
    repo = "acme/widgets",
    ref = "refs/heads/main",
    sha = sha1,
    pipeline = "release",
    job = "build",
    trusted = true
  )

  private val genericJson =
    """{"ci":"generic","repo":"acme/widgets","ref":"refs/heads/main","sha":"""" + sha1 +
      """","pipeline":"release","job":"build","trusted":true}"""

  private val githubCtx = CiContext(
    ci = "github",
    repo = "acme/widgets",
    ref = "refs/pull/7/merge",
    sha = sha2,
    pipeline = "review",
    job = "raider-review",
    trusted = true
  )

  private val gitlabCtx = CiContext(
    ci = "gitlab",
    repo = "acme/widgets",
    ref = "main",
    sha = sha3,
    pipeline = "4242",
    job = "raider-review",
    trusted = false
  )

  def spec = suite("ci context (RAI-078)")(
    test("CICTX-01: generic fixture roundtrip (parse -> encode -> parse)") {
      val parsed = CiContext.parse(genericJson)
      val encoded = genericCtx.toJson
      assertTrue(
        parsed == Right(genericCtx),
        encoded.fromJson[CiContext] == Right(genericCtx),
        // redacted() is identity for a trusted context
        genericCtx.redacted == genericCtx
      )
    },
    test(
      "CICTX-01: github env fixture — fixed allowlist mapping + wire roundtrip"
    ) {
      val env = Map(
        "GITHUB_REPOSITORY" -> "acme/widgets",
        "GITHUB_REF" -> "refs/pull/7/merge",
        "GITHUB_SHA" -> sha2,
        "GITHUB_WORKFLOW" -> "review",
        "GITHUB_JOB" -> "raider-review",
        // non-allowlisted, non-secret: ignored, no raw env export
        "CI" -> "true",
        "RUNNER_OS" -> "macOS"
      )
      val built = CiContext.fromGitHubEnv(env, trusted = true)
      assertTrue(
        built.map(_.context) == Right(githubCtx),
        built.map(_.droppedSecrets) == Right(0),
        githubCtx.toJson.fromJson[CiContext] == Right(githubCtx)
      )
    },
    test(
      "CICTX-01: gitlab env fixture — fixed allowlist mapping + wire roundtrip"
    ) {
      val env = Map(
        "CI_PROJECT_PATH" -> "acme/widgets",
        "CI_COMMIT_REF_NAME" -> "main",
        "CI_COMMIT_SHA" -> sha3,
        "CI_PIPELINE_ID" -> "4242",
        "CI_JOB_NAME" -> "raider-review",
        "CI_SERVER_HOST" -> "gitlab.example.com"
      )
      val built = CiContext.fromGitLabEnv(env, trusted = false)
      assertTrue(
        built.map(_.context) == Right(gitlabCtx),
        built.map(_.droppedSecrets) == Right(0),
        gitlabCtx.toJson.fromJson[CiContext] == Right(gitlabCtx)
      )
    },
    test(
      "CICTX-02: secret env values NEVER enter the context or the redacted view (drop + counter)"
    ) {
      val token = "short-test-val"
      val key = "test-key-111"
      val passwd = "pw-2"
      val env = Map(
        "GITHUB_REPOSITORY" -> "acme/widgets",
        "GITHUB_REF" -> "refs/pull/7/merge",
        "GITHUB_SHA" -> sha2,
        "GITHUB_WORKFLOW" -> "review",
        "GITHUB_JOB" -> "raider-review",
        "GITHUB_TOKEN" -> token,
        "MY_API_KEY" -> key,
        "PASSWORD" -> passwd,
        "DEPLOY_SECRET" -> "s3cr3t-value"
      )
      val built = CiContext.fromGitHubEnv(env, trusted = false)
      val fullJson = built.map(_.context.toJson).getOrElse("")
      val viewJson = built.map(_.context.redacted.toJson).getOrElse("")
      assertTrue(
        built.map(_.droppedSecrets) == Right(4),
        built.map(_.droppedSecretNames) == Right(
          Vector("DEPLOY_SECRET", "GITHUB_TOKEN", "MY_API_KEY", "PASSWORD")
        ),
        !fullJson.contains(token),
        !fullJson.contains(key),
        !fullJson.contains(passwd),
        !viewJson.contains(token),
        !viewJson.contains(key),
        !viewJson.contains(passwd),
        // untrusted: identity fields are placeholders in the limited view
        !viewJson.contains("acme/widgets"),
        viewJson.contains(CiContext.Placeholder),
        // trust flag and platform survive the limited view
        built.map(_.context.redacted.ci) == Right("github"),
        built.map(_.context.redacted.trusted) == Right(false)
      )
    },
    test(
      "CICTX-02: gitlab secret names dropped the same way; untrusted redacted view is the export path"
    ) {
      val jobToken = "gt-token"
      val env = Map(
        "CI_PROJECT_PATH" -> "acme/widgets",
        "CI_COMMIT_REF_NAME" -> "main",
        "CI_COMMIT_SHA" -> sha3,
        "CI_PIPELINE_ID" -> "4242",
        "CI_JOB_NAME" -> "raider-review",
        "CI_JOB_TOKEN" -> jobToken,
        "CI_DEPLOY_PASSWORD" -> "deploy-pass-value"
      )
      val built = CiContext.fromGitLabEnv(env, trusted = false)
      val viewJson = built.map(_.context.redacted.toJson).getOrElse("")
      assertTrue(
        built.map(_.droppedSecrets) == Right(2),
        built.map(_.droppedSecretNames) == Right(
          Vector("CI_DEPLOY_PASSWORD", "CI_JOB_TOKEN")
        ),
        !viewJson.contains(jobToken),
        !viewJson.contains("deploy-pass-value")
      )
    },
    test("CICTX-02: no allowlisted env name can ever hit the secret wall") {
      val allowlist = Vector(
        "GITHUB_REPOSITORY",
        "GITHUB_REF",
        "GITHUB_SHA",
        "GITHUB_WORKFLOW",
        "GITHUB_JOB",
        "CI_PROJECT_PATH",
        "CI_COMMIT_REF_NAME",
        "CI_COMMIT_SHA",
        "CI_PIPELINE_ID",
        "CI_JOB_NAME"
      )
      assertTrue(
        allowlist.forall(n => !CiContext.isSecretEnvName(n)),
        CiContext.isSecretEnvName("GITHUB_TOKEN"),
        CiContext.isSecretEnvName("ci_job_token"),
        CiContext.isSecretEnvName("MY_API_KEY"),
        CiContext.isSecretEnvName("PASSWORD"),
        !CiContext.isSecretEnvName("RUNNER_OS")
      )
    },
    test("CICTX-03: unknown ci type -> typed UnknownCiType (RA-INP)") {
      val bad = genericJson.replace("\"ci\":\"generic\"", "\"ci\":\"cirrus\"")
      val r = CiContext.parse(bad)
      assertTrue(
        r == Left(CiContextError.UnknownCiType("cirrus")),
        r.fold(_.toRaiderError.code == "RA-INP", _ => false),
        r.fold(
          _.toRaiderError.isInstanceOf[RaiderError.InputValidation],
          _ => false
        ),
        r.fold(_.toRaiderError.detail.contains("cirrus"), _ => false)
      )
    },
    test("CICTX-03: missing required field -> typed MissingField (RA-INP)") {
      val noRepo =
        """{"ci":"generic","ref":"refs/heads/main","sha":""" +
          s""""$sha1","pipeline":"release","job":"build","trusted":true}"""
      val blankRepo = noRepo
        .replace("\"ci\":\"generic\"", "\"ci\":\"generic\",\"repo\":\"   \"")
      val noTrusted = genericJson.replace(",\"trusted\":true", "")
      assertTrue(
        CiContext.parse(noRepo) ==
          Left(CiContextError.MissingField("repo", "json")),
        CiContext.parse(blankRepo) ==
          Left(CiContextError.MissingField("repo", "json")),
        CiContext.parse(noTrusted) ==
          Left(CiContextError.MissingField("trusted", "json")),
        CiContext
          .parse(noRepo)
          .fold(_.toRaiderError.code == "RA-INP", _ => false)
      )
    },
    test("CICTX-03: broken JSON / wrong-typed field -> typed Malformed") {
      assertTrue(
        CiContext.parse("not json at all").isLeft,
        CiContext
          .parse("not json at all")
          .fold(
            _.isInstanceOf[CiContextError.Malformed],
            _ => false
          ),
        CiContext
          .parse(
            """{"ci":"generic","repo":"r","ref":"x","sha":"y","pipeline":"p","job":"j","trusted":"yes"}"""
          )
          .fold(_.isInstanceOf[CiContextError.Malformed], _ => false)
      )
    },
    test(
      "CICTX-03: env builder missing field -> typed MissingField naming the env var source"
    ) {
      val r = CiContext.fromGitHubEnv(
        Map("GITHUB_REPOSITORY" -> "acme/widgets"),
        trusted = true
      )
      val g = CiContext.fromGitLabEnv(Map.empty, trusted = false)
      assertTrue(
        r == Left(CiContextError.MissingField("ref", "GITHUB_REF")),
        g == Left(CiContextError.MissingField("repo", "CI_PROJECT_PATH"))
      )
    },
    test(
      "CICTX-03: every CiContextError maps explicitly to RaiderError, never implicit"
    ) {
      val errs: List[CiContextError] = List(
        CiContextError.Malformed("broken payload"),
        CiContextError.MissingField("sha", "GITHUB_SHA"),
        CiContextError.UnknownCiType("jenkins")
      )
      assertTrue(
        errs.map(_.toRaiderError.code).toSet == Set("RA-INP"),
        errs.forall(_.toRaiderError.isInstanceOf[RaiderError.InputValidation]),
        errs.map(_.toRaiderError.detail).forall(_.nonEmpty)
      )
    }
  )
