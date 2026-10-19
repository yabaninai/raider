package raider.cli.run

import zio.ZIO
import zio.test.*
import zio.test.live

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** RAI-100 acceptance (WRAP-01..03): the `--ci` wrapper selection drives the
  * GitHub/GitLab wiring of [[HeadlessRunner]].
  *   - WRAP-01: `github` -> GITHUB_* env detection (CiContext.fromGitHubEnv)
  *     plus the "CI annotations" summary section; `gitlab` -> CI_*; `generic`
  *     or absent flag -> no wrapper behavior at all; `--context` wins over
  *     `--ci` when both are given.
  *   - WRAP-02: untrusted contexts (env detection NEVER raises trust) export
  *     the redacted view into run.json AND the summary annotations;
  *     secret-named env vars are dropped before any value is read.
  *   - WRAP-03: the existing CIW/RUN suites stay green — covered by running
  *     this file in the same raiderCli test run (CiWiringSpec and
  *     HeadlessRunnerSpec are intentionally UNCHANGED: the summary extension is
  *     additive and their assertions are contains-based).
  *
  * The `--ci` value reaches the runner as the [[HeadlessRunner.run]] `ci`
  * parameter: argv parsing lives in CliArgs/Main, which are OUTSIDE this card's
  * allowed paths (the argv wiring needs its own scope card). Mock backend only:
  * no network, no spend, no real CI.
  */
object WrapWiringSpec extends ZIOSpecDefault:

  private def tempDir(): Path =
    Files.createTempDirectory("raider-wrap-test")

  private def scoutArgs(out: Path, extra: List[String] = Nil) =
    CliArgs
      .parse(
        List(
          "run",
          "--agent",
          "scout",
          "--input",
          "task-42",
          "--out",
          out.toString,
          "--mock"
        ) ++ extra
      )
      .fold(e => throw e, identity)

  private def summaryOf(workDir: Path): String =
    Files.readString(workDir.resolve("summary.md"), UTF_8)

  private def rawRunJson(workDir: Path): String =
    Files.readString(workDir.resolve("run.json"), UTF_8)

  /** Trusted GENERIC ci-context payload on disk — used to prove `--context`
    * beats `--ci github` when both are given.
    */
  private def trustedGenericContextFile(dir: Path): Path =
    val p = dir.resolve("ctx-trusted-generic.json")
    Files.writeString(
      p,
      """{"ci":"generic","repo":"acme/widgets","ref":"refs/heads/main",""" +
        """"sha":"0123456789abcdef0123456789abcdef01234567",""" +
        """"pipeline":"release","job":"build","trusted":true}""",
      UTF_8
    )
    p

  /** Untrusted GITHUB ci-context payload on disk (WRAP-02). */
  private def untrustedGithubContextFile(dir: Path): Path =
    val p = dir.resolve("ctx-untrusted-github.json")
    Files.writeString(
      p,
      """{"ci":"github","repo":"acme/widgets","ref":"refs/heads/main",""" +
        """"sha":"deadbeef","pipeline":"build","job":"test","trusted":false}""",
      UTF_8
    )
    p

  /** GitHub Actions fixture env: the fixed allowlist PLUS a secret-named var
    * (GITHUB_TOKEN) whose value must never be read (secret wall).
    */
  private val githubEnv = Map(
    "GITHUB_REPOSITORY" -> "acme/widgets",
    "GITHUB_REF" -> "refs/heads/main",
    "GITHUB_SHA" -> "aaaabbbbccccddddeeeeffff0000111122223333",
    "GITHUB_WORKFLOW" -> "build",
    "GITHUB_JOB" -> "test",
    "GITHUB_TOKEN" -> "wrap-github-token-value"
  )

  /** GitLab CI fixture env, same secret-wall probe. */
  private val gitlabEnv = Map(
    "CI_PROJECT_PATH" -> "acme/widgets",
    "CI_COMMIT_REF_NAME" -> "main",
    "CI_COMMIT_SHA" -> "ffff000011112222333344445555666677778888",
    "CI_PIPELINE_ID" -> "4242",
    "CI_JOB_NAME" -> "test",
    "CI_JOB_TOKEN" -> "wrap-gitlab-token-value"
  )

  def spec = suite("ci wrappers wiring (RAI-100)")(
    test(
      "WRAP-01: --ci github -> GITHUB_* env detection + CI annotations in summary (untrusted -> placeholders)"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO.attempt(tempDir()).orDie
            r <- HeadlessRunner.run(
              scoutArgs(work.resolve("o1")),
              githubEnv,
              Some("github")
            )
            summary = summaryOf(r.workDir)
            raw = rawRunJson(r.workDir)
          yield assertTrue(
            r.exitCode == 0,
            summary.contains("## CI annotations"),
            summary.contains("- ci: github"),
            // env detection never raises trust -> redacted view in summary
            summary.contains("- pipeline: <redacted>"),
            summary.contains("- ref: <redacted>"),
            summary.contains("- trusted: false"),
            // identity values and secret VALUES never reach the summary
            !summary.contains("acme/widgets"),
            !summary.contains("refs/heads/main"),
            !summary.contains("aaaabbbbccccdddd"),
            !summary.contains("wrap-github-token-value"),
            // run.json carries the same redacted ci view (086 integration)
            raw.contains("\"ci\":{"),
            raw.contains("\"trusted\":false"),
            !raw.contains("acme/widgets"),
            !raw.contains("aaaabbbbccccdddd"),
            !raw.contains("wrap-github-token-value")
          )
        }
      }
    },
    test(
      "WRAP-01: --ci gitlab -> CI_* env detection + CI annotations in summary"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO.attempt(tempDir()).orDie
            r <- HeadlessRunner.run(
              scoutArgs(work.resolve("o1")),
              gitlabEnv,
              Some("gitlab")
            )
            summary = summaryOf(r.workDir)
            raw = rawRunJson(r.workDir)
          yield assertTrue(
            r.exitCode == 0,
            summary.contains("## CI annotations"),
            summary.contains("- ci: gitlab"),
            summary.contains("- pipeline: <redacted>"),
            summary.contains("- ref: <redacted>"),
            summary.contains("- trusted: false"),
            !summary.contains("acme/widgets"),
            !summary.contains("4242"),
            !summary.contains("wrap-gitlab-token-value"),
            raw.contains("\"ci\":{"),
            raw.contains("\"trusted\":false"),
            !raw.contains("4242"),
            !raw.contains("wrap-gitlab-token-value")
          )
        }
      }
    },
    test(
      "WRAP-01: --ci generic and absent flag -> no annotations, no ci field (even with GITHUB_* env present)"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO.attempt(tempDir()).orDie
            r1 <- HeadlessRunner.run(
              scoutArgs(work.resolve("o1")),
              githubEnv,
              Some("generic")
            )
            r2 <- HeadlessRunner.run(
              scoutArgs(work.resolve("o2")),
              githubEnv,
              None
            )
            (s1, s2) = (summaryOf(r1.workDir), summaryOf(r2.workDir))
            (raw1, raw2) =
              (rawRunJson(r1.workDir), rawRunJson(r2.workDir))
          yield assertTrue(
            r1.exitCode == 0,
            r2.exitCode == 0,
            // generic wrapper behavior = "as is": no section, no ci field
            !s1.contains("CI annotations"),
            !s2.contains("CI annotations"),
            !raw1.contains("\"ci\":{"),
            !raw2.contains("\"ci\":{")
          )
        }
      }
    },
    test(
      "WRAP-01: --context wins over --ci — the explicit owner payload is authoritative"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO.attempt(tempDir()).orDie
            ctx <- ZIO
              .attempt(trustedGenericContextFile(work))
              .orDie
            r <- HeadlessRunner.run(
              scoutArgs(
                work.resolve("o1"),
                List("--context", ctx.toString)
              ),
              githubEnv,
              Some("github")
            )
            summary = summaryOf(r.workDir)
            raw = rawRunJson(r.workDir)
          yield assertTrue(
            r.exitCode == 0,
            // annotations render the CONTEXT (generic, trusted -> real values)
            summary.contains("## CI annotations"),
            summary.contains("- ci: generic"),
            summary.contains("- pipeline: release"),
            summary.contains("- ref: refs/heads/main"),
            summary.contains("- trusted: true"),
            // the --ci flag did NOT override the explicit payload
            !summary.contains("- ci: github"),
            // trusted context passes through run.json unchanged (086 rule)
            raw.contains("\"repo\":\"acme/widgets\""),
            raw.contains("\"trusted\":true")
          )
        }
      }
    },
    test(
      "WRAP-01/adversarial: unknown --ci value -> typed Configuration refusal (exit 21, no annotations, no ci field)"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO.attempt(tempDir()).orDie
            r <- HeadlessRunner.run(
              scoutArgs(work.resolve("o1")),
              githubEnv,
              Some("cirrus")
            )
            summary = summaryOf(r.workDir)
            raw = rawRunJson(r.workDir)
          yield assertTrue(
            r.exitCode == 21, // Configuration -> ConfigInvalid
            r.result.exists(_.error.exists(_.code == "RA-CFG")),
            summary.contains("unknown --ci 'cirrus'"),
            !summary.contains("CI annotations"),
            !raw.contains("\"ci\":{")
          )
        }
      }
    },
    test(
      "WRAP-01/adversarial: --ci github with missing allowlisted env -> typed refusal naming the env var (exit 21, no spend)"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO.attempt(tempDir()).orDie
            r <- HeadlessRunner.run(
              scoutArgs(work.resolve("o1")),
              Map("GITHUB_REPOSITORY" -> "acme/widgets"),
              Some("github")
            )
            summary = summaryOf(r.workDir)
            raw = rawRunJson(r.workDir)
          yield assertTrue(
            r.exitCode == 21, // InputValidation -> ConfigInvalid
            // the typed MissingField names the missing env var SOURCE
            summary.contains("GITHUB_REF"),
            !summary.contains("CI annotations"),
            // the context was never built -> nothing is exported
            !raw.contains("\"ci\":{"),
            !raw.contains("acme/widgets")
          )
        }
      }
    },
    test(
      "WRAP-02: untrusted --context payload -> redacted annotations AND redacted run.json (secrets absent)"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO.attempt(tempDir()).orDie
            ctx <- ZIO
              .attempt(untrustedGithubContextFile(work))
              .orDie
            r <- HeadlessRunner.run(
              scoutArgs(
                work.resolve("o1"),
                List("--context", ctx.toString)
              ),
              githubEnv,
              None
            )
            summary = summaryOf(r.workDir)
            raw = rawRunJson(r.workDir)
          yield assertTrue(
            r.exitCode == 0,
            summary.contains("## CI annotations"),
            summary.contains("- ci: github"),
            summary.contains("- pipeline: <redacted>"),
            summary.contains("- ref: <redacted>"),
            summary.contains("- trusted: false"),
            // untrusted identity values never reach ANY artifact
            !summary.contains("acme/widgets"),
            !summary.contains("deadbeef"),
            !summary.contains("refs/heads/main"),
            // the env secret is never read on the --context path either
            !summary.contains("wrap-github-token-value"),
            !raw.contains("deadbeef"),
            !raw.contains("wrap-github-token-value"),
            raw.contains("\"repo\":\"<redacted>\""),
            raw.contains("\"trusted\":false")
          )
        }
      }
    }
  ) @@ TestAspect.withLiveClock
  // RAI-178: HeadlessRunner.run's ensurings poll on the ambient clock;

  // live-clock discipline (docs/work/records/RAI-178.md). Assertions unchanged.
