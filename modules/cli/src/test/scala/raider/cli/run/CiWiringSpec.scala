package raider.cli.run

import zio.json.ast.Json
import zio.test.*
import zio.test.live
import zio.ZIO
import zio.json.DecoderOps

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** RAI-086 acceptance (CIW-01..03): `--context` wires the parsed [[CiContext]]
  * into run.json (redacted for untrusted, full for trusted); the run.json
  * `accounting` section renders the REAL [[BudgetLedger]] snapshot honestly —
  * unknown cost is never masked as zero and the field is ABSENT when the run
  * never reached the ledger; the wiring is additive (existing fields/exit codes
  * unchanged — CIW-03 is the existing RUN-spec suite staying green in the same
  * test run).
  */
object CiWiringSpec extends ZIOSpecDefault:

  private def tempDir(): Path =
    Files.createTempDirectory("raider-ciw-test")

  /** Valid ci-context payload on disk (wire schema of CiContext). */
  private def contextFile(dir: Path, trusted: Boolean): Path =
    val p = dir.resolve(
      if trusted then "ctx-trusted.json" else "ctx-untrusted.json"
    )
    Files.writeString(
      p,
      s"""{"ci":"github","repo":"org/repo","ref":"refs/heads/main",""" +
        s""""sha":"deadbeef","pipeline":"build","job":"test","trusted":$trusted}""",
      UTF_8
    )
    p

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

  private def rawRunJson(workDir: Path): String =
    Files.readString(workDir.resolve("run.json"), UTF_8)

  /** Extract a top-level object field from the raw run.json text — proves the
    * field is REALLY present in the serialized artifact (a typed RunResult
    * decode would silently drop the additive extras).
    */
  private def objField(raw: String, key: String): Json =
    raw.fromJson[Json] match
      case Right(Json.Obj(fields)) =>
        fields
          .collectFirst { case (`key`, j) => j }
          .getOrElse(
            throw new IllegalStateException(s"field '$key' absent in run.json")
          )
      case other =>
        throw new IllegalStateException(s"run.json is not an object: $other")

  def spec = suite("ci wiring (RAI-086)")(
    test(
      "CIW-01: --context → ci in run.json: redacted view for untrusted, full view for trusted"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO.attempt(tempDir()).orDie
            untrusted <- ZIO.attempt(contextFile(work, trusted = false)).orDie
            r1 <- HeadlessRunner.run(
              scoutArgs(
                work.resolve("o1"),
                List("--context", untrusted.toString)
              )
            )
            raw1 = rawRunJson(r1.workDir)
            trusted <- ZIO.attempt(contextFile(work, trusted = true)).orDie
            r2 <- HeadlessRunner.run(
              scoutArgs(
                work.resolve("o2"),
                List("--context", trusted.toString)
              )
            )
            raw2 = rawRunJson(r2.workDir)
          yield assertTrue(
            r1.exitCode == 0,
            raw1.contains("\"ci\":{"),
            raw1.contains("\"repo\":\"<redacted>\""),
            raw1.contains("\"trusted\":false"),
            // untrusted identity-bearing values never reach the artifact
            !raw1.contains("org/repo"),
            !raw1.contains("deadbeef"),
            !raw1.contains("refs/heads/main"),
            r2.exitCode == 0,
            raw2.contains("\"ci\":{"),
            raw2.contains("\"repo\":\"org/repo\""),
            raw2.contains("\"sha\":\"deadbeef\""),
            raw2.contains("\"trusted\":true")
          )
        }
      }
    },
    test(
      "CIW-02: accounting in run.json is the REAL ledger view — unpriced counted, unknown=true (never masked as zero); summary carries the honest cost line"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO.attempt(tempDir()).orDie
            outcome <- HeadlessRunner.run(scoutArgs(work))
            raw = rawRunJson(outcome.workDir)
            acc = objField(raw, "accounting") // throws → test fails if absent
            unpriced <- ZIO.succeed {
              acc match
                case Json.Obj(fields) =>
                  fields
                    .collectFirst { case ("unpricedAttempts", Json.Num(v)) =>
                      // Json.Num holds java.math.BigDecimal: exact, overflow-safe
                      v.longValueExact()
                    }
                    .getOrElse(
                      throw new IllegalStateException(
                        "no unpricedAttempts in accounting"
                      )
                    )
                case _ =>
                  throw new IllegalStateException("accounting is not an object")
            }
            summary = Files.readString(
              outcome.workDir.resolve("summary.md"),
              UTF_8
            )
          yield assertTrue(
            outcome.exitCode == 0,
            // the loop dispatches under CostEstimate.Unknown: at least one
            // unpriced attempt MUST be reported — unknown is true even
            // though the observed mock money is zero (0 never masks it)
            unpriced >= 1L,
            raw.contains("\"unknown\":true"),
            summary.contains("cost: unknown ("),
            // an unknown cost is never displayed as a number
            !summary.contains("cost: $")
          )
        }
      }
    },
    test(
      "CIW-01/adversarial: missing --context file is a typed Configuration at parse; malformed payload refuses the launch with no ci field exported"
    ) {
      val missing = CliArgs.parse(
        List(
          "run",
          "--agent",
          "scout",
          "--input",
          "t",
          "--out",
          "o",
          "--mock",
          "--context",
          "/nonexistent/raider-ciw-ctx.json"
        )
      )
      live {
        ZIO.scoped {
          for
            work <- ZIO.attempt(tempDir()).orDie
            bad <- ZIO.attempt {
              val p = work.resolve("bad.json")
              Files.writeString(p, "{not json", UTF_8)
              p
            }.orDie
            r <- HeadlessRunner.run(
              scoutArgs(work.resolve("o1"), List("--context", bad.toString))
            )
            raw = rawRunJson(r.workDir)
          yield assertTrue(
            missing.fold(_.code == "RA-CFG", _ => false),
            missing.fold(_.detail.contains("--context"), _ => false),
            r.exitCode == 21, // typed InputValidation → ConfigInvalid
            raw.contains("ci context malformed"),
            // parse failed → there is NO honest ci value to export
            !raw.contains("\"ci\":{")
          )
        }
      }
    },
    test(
      "CIW-02/honest-absence: ci is still exported when a LATER preflight fails, but accounting stays ABSENT when the budget ledger was never reached"
    ) {
      live {
        ZIO.scoped {
          for
            work <- ZIO.attempt(tempDir()).orDie
            ctx <- ZIO.attempt(contextFile(work, trusted = false)).orDie
            // no --input: the input preflight fails AFTER the context parse,
            // and admission/ledger are never created for this invocation
            args = CliArgs
              .parse(
                List(
                  "run",
                  "--agent",
                  "scout",
                  "--out",
                  work.resolve("o1").toString,
                  "--mock",
                  "--context",
                  ctx.toString
                )
              )
              .fold(e => throw e, identity)
            r <- HeadlessRunner.run(args)
            raw = rawRunJson(r.workDir)
            summary = Files.readString(
              r.workDir.resolve("summary.md"),
              UTF_8
            )
          yield assertTrue(
            r.exitCode == 21,
            // the parsed context is honestly exported on the FAILED run too
            raw.contains("\"ci\":{"),
            raw.contains("\"repo\":\"<redacted>\""),
            // no ledger → no accounting field, no cost claim anywhere
            !raw.contains("\"accounting\":{"),
            !summary.contains("cost:")
          )
        }
      }
    }
  )
