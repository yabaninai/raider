package raider.cli.run

import raider.core.RaiderError
import zio.json.{
  DecoderOps,
  DeriveJsonCodec,
  DeriveJsonDecoder,
  JsonCodec,
  JsonDecoder
}

/** Typed CI-context failures (RAI-078; ADR-024 EventError pattern).
  *
  * Own sealed family on the pattern of EventError/MailboxError: `RaiderError`
  * is frozen core and its subtypes live in a single core file, so the
  * conversion is explicit (`toRaiderError`), never implicit. Parsing never
  * throws: every refusal is a typed value, and no error text ever embeds an env
  * VALUE — only names and JSON structure.
  */
sealed trait CiContextError extends Product with Serializable:
  def toRaiderError: RaiderError

object CiContextError:

  /** The payload is not a decodable ci-context object (broken JSON or a
    * wrong-typed field).
    */
  final case class Malformed(msg: String) extends CiContextError:

    def toRaiderError: RaiderError =
      RaiderError.InputValidation(s"ci context malformed: $msg")

  /** A required field is absent or blank. `source` names WHERE the field was
    * expected from (`json` or the exact env var name) — never a value.
    */
  final case class MissingField(field: String, source: String)
      extends CiContextError:

    def toRaiderError: RaiderError =
      RaiderError.InputValidation(
        s"ci context field '$field' missing or blank (source: $source)"
      )

  /** `ci` is not one of the known platform tags. */
  final case class UnknownCiType(value: String) extends CiContextError:

    def toRaiderError: RaiderError =
      RaiderError.InputValidation(
        s"unknown ci type '$value' (known: generic, github, gitlab)"
      )

/** Normalized CI execution context (RAI-078, RAI-023/025 slice; ci-runtime.md
  * §4).
  *
  * Wire schema:
  * `{"ci":"generic|github|gitlab","repo","ref","sha","pipeline","job","trusted":bool}`.
  * `trusted` is an explicit owner-policy flag passed in by the caller from a
  * trusted allowlist — env detection NEVER raises it (RAI-023 non-goal:
  * "trusting env flag alone").
  *
  * Secret wall: the env builders read ONLY a fixed per-platform allowlist of
  * names, and every env name matching TOKEN|KEY|PASSWORD|SECRET is dropped
  * BEFORE any value is read (see [[EnvSource]] for the drop counter). Secret
  * env values therefore cannot enter the context, its JSON, or the redacted
  * view. Untrusted contexts must be exported only via `redacted`.
  *
  * Ingestion of untrusted payloads goes through [[CiContext.parse]] (typed
  * errors); the derived codec is for round-tripping already-validated contexts
  * (fixtures, artifacts).
  */
final case class CiContext(
    ci: String,
    repo: String,
    ref: String,
    sha: String,
    pipeline: String,
    job: String,
    trusted: Boolean
):

  /** Limited view for an UNTRUSTED context: the platform tag and the trust flag
    * survive; every identity-bearing free-form field (repo, ref, sha, pipeline,
    * job) collapses to a fixed placeholder. Trusted contexts pass through
    * unchanged.
    */
  def redacted: CiContext =
    if trusted then this
    else
      copy(
        repo = CiContext.Placeholder,
        ref = CiContext.Placeholder,
        sha = CiContext.Placeholder,
        pipeline = CiContext.Placeholder,
        job = CiContext.Placeholder
      )

object CiContext:

  /** Fixed placeholder used by `redacted` — carries no provenance. */
  val Placeholder = "<redacted>"

  /** Known platform tags. */
  val KnownCi: Set[String] = Set("generic", "github", "gitlab")

  /** Env builders refuse any name containing one of these markers (name match
    * only, case-insensitive); their values are never read. The match is
    * deliberately a substring test — the fail-safe direction.
    */
  private val SecretMarkers = Vector("TOKEN", "KEY", "PASSWORD", "SECRET")

  /** True when the env var NAME hits the secret wall (TOKEN|KEY|PASSWORD|
    * SECRET substring, case-insensitive). Names are safe to log; values of
    * matching vars are never read.
    */
  def isSecretEnvName(name: String): Boolean =
    val upper = name.toUpperCase
    SecretMarkers.exists(upper.contains)

  given JsonCodec[CiContext] = DeriveJsonCodec.gen[CiContext]

  /** Parse and validate an UNTRUSTED ci-context payload. Missing/blank fields
    * and unknown platform tags are typed `CiContextError`s.
    */
  def parse(json: String): Either[CiContextError, CiContext] =
    json.fromJson[Raw] match
      case Left(err) => Left(CiContextError.Malformed(err))
      case Right(raw) =>
        validate(raw)

  /** Build the context from a GitHub Actions environment (fixed allowlist,
    * GITHUB_* headers): repo<-GITHUB_REPOSITORY, ref<-GITHUB_REF,
    * sha<-GITHUB_SHA, pipeline<-GITHUB_WORKFLOW, job<-GITHUB_JOB. `trusted`
    * comes from the owner allowlist, never from env. Secret-named vars are
    * dropped before anything is read; the counter travels in [[EnvSource]].
    */
  def fromGitHubEnv(
      env: Map[String, String],
      trusted: Boolean
  ): Either[CiContextError, EnvSource] =
    buildEnv(
      "github",
      Vector(
        "GITHUB_REPOSITORY" -> "repo",
        "GITHUB_REF" -> "ref",
        "GITHUB_SHA" -> "sha",
        "GITHUB_WORKFLOW" -> "pipeline",
        "GITHUB_JOB" -> "job"
      ),
      env,
      trusted
    )

  /** Build the context from a GitLab CI environment (fixed allowlist, CI_*
    * headers): repo<-CI_PROJECT_PATH, ref<-CI_COMMIT_REF_NAME,
    * sha<-CI_COMMIT_SHA, pipeline<-CI_PIPELINE_ID, job<-CI_JOB_NAME. Same trust
    * and secret-wall rules as [[fromGitHubEnv]].
    */
  def fromGitLabEnv(
      env: Map[String, String],
      trusted: Boolean
  ): Either[CiContextError, EnvSource] =
    buildEnv(
      "gitlab",
      Vector(
        "CI_PROJECT_PATH" -> "repo",
        "CI_COMMIT_REF_NAME" -> "ref",
        "CI_COMMIT_SHA" -> "sha",
        "CI_PIPELINE_ID" -> "pipeline",
        "CI_JOB_NAME" -> "job"
      ),
      env,
      trusted
    )

  /** Env-builder result: the validated context plus the secret-wall drop
    * inventory. `droppedSecretNames` holds NAMES only (safe to log); the values
    * of those variables are never read. Non-secret env vars outside the
    * allowlist are silently ignored — no raw env export.
    */
  final case class EnvSource(
      context: CiContext,
      droppedSecretNames: Vector[String]
  ):
    /** Drop counter for secret-named env vars. */
    def droppedSecrets: Int = droppedSecretNames.size

  /** Shared builder core: drop secret-named vars FIRST (counter), read only the
    * fixed allowlist, refuse missing/blank fields as typed errors.
    */
  private def buildEnv(
      ci: String,
      fields: Vector[(String, String)], // (env var name, context field)
      env: Map[String, String],
      trusted: Boolean
  ): Either[CiContextError, EnvSource] =
    val dropped = env.keys.filter(isSecretEnvName).toVector.sorted
    val rest = env.removedAll(dropped)
    val extracted = fields.map((envName, field) =>
      (field, envName, rest.get(envName).map(_.trim).filter(_.nonEmpty))
    )
    val missing = extracted.collect { case (field, envName, None) =>
      CiContextError.MissingField(field, envName)
    }
    if missing.nonEmpty then Left(missing.head)
    else
      val values =
        extracted.collect { case (field, _, Some(v)) => field -> v }.toMap
      Right(
        EnvSource(
          CiContext(
            ci,
            values("repo"),
            values("ref"),
            values("sha"),
            values("pipeline"),
            values("job"),
            trusted
          ),
          dropped
        )
      )

  private def validate(raw: Raw): Either[CiContextError, CiContext] =
    def required(
        field: String,
        value: Option[String]
    ): Either[CiContextError, String] =
      value.map(_.trim).filter(_.nonEmpty) match
        case Some(v) => Right(v)
        case None    => Left(CiContextError.MissingField(field, "json"))
    for
      ci <- required("ci", raw.ci)
      repo <- required("repo", raw.repo)
      ref <- required("ref", raw.ref)
      sha <- required("sha", raw.sha)
      pipeline <- required("pipeline", raw.pipeline)
      job <- required("job", raw.job)
      trusted <- raw.trusted match
        case Some(t) => Right(t)
        case None =>
          Left(CiContextError.MissingField("trusted", "json"))
      _ <-
        if KnownCi.contains(ci) then Right(())
        else Left(CiContextError.UnknownCiType(ci))
    yield CiContext(ci, repo, ref, sha, pipeline, job, trusted)

  /** Permissive wire shape: every field optional, so a MISSING field is a typed
    * `MissingField` rather than an opaque decode error.
    */
  private final case class Raw(
      ci: Option[String],
      repo: Option[String],
      ref: Option[String],
      sha: Option[String],
      pipeline: Option[String],
      job: Option[String],
      trusted: Option[Boolean]
  )

  private object Raw:
    given JsonDecoder[Raw] = DeriveJsonDecoder.gen[Raw]
