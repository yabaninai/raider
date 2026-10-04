package raider.tools.process

import raider.core.RaiderError
import raider.tools.files.read.Workspace
import zio.test.{live, *}
import zio.{Duration as ZDuration, Exit as ZExit, ZIO}

import java.nio.file.{Files, Path}

/** RAI-016.a PROC-01..03: real bounded processes via ProcessBuilder; env
  * allowlist (no inherited credentials); timeout kill; typed negatives; honest
  * DirectChildOnly cleanup gap. Real time via `live`.
  */
object ProcessToolSpec extends ZIOSpecDefault:

  private def tempWorkspace(): Path =
    Files.createTempDirectory("raider-proc-ws")

  private def codeOf(exit: ZExit[RaiderError, ExecOutcome]): Option[String] =
    exit match
      case ZExit.Failure(cause) => cause.failures.headOption.map(_.code)
      case ZExit.Success(_)     => None

  def spec = suite("ProcessTool RAI-016.a")(
    test("PROC-01: /bin/echo captured with exit code and bounded output") {
      live {
        ZIO.scoped {
          for
            tool <- Workspace
              .make(tempWorkspace().toString)
              .map(new ProcessTool(_))
            res <- tool.exec(
              ExecRequest(argv = List("/bin/echo", "-n", "hello-raider"))
            )
          yield assertTrue(
            res.exitCode.contains(0),
            res.stdout == "hello-raider",
            !res.truncated,
            !res.timedOut,
            res.durationMs >= 0,
            res.cleanup == CleanupGuarantee.DirectChildOnly // documented gap
          )
        }
      }
    },
    test("PROC-01: env allowlist passes ONLY listed keys — nothing inherited") {
      live {
        ZIO.scoped {
          for
            tool <- Workspace
              .make(tempWorkspace().toString)
              .map(new ProcessTool(_))
            res <- tool.exec(
              ExecRequest(
                argv = List("/usr/bin/env"),
                envAllowlist = List("RAIDER_ALLOWED"),
                envValues = Map("RAIDER_ALLOWED" -> "yes")
              )
            )
          yield assertTrue(
            res.exitCode.contains(0),
            res.stdout.contains("RAIDER_ALLOWED=yes"),
            // the test JVM HAS PATH/HOME in its own environment; the child must
            // NOT inherit them (env is built exclusively from the allowlist)
            !res.stdout.contains("PATH="),
            !res.stdout.contains("HOME=")
          )
        }
      }
    },
    test(
      "PROC-02: shell metacharacters are literal data (no shell interpolation)"
    ) {
      live {
        ZIO.scoped {
          for
            tool <- Workspace
              .make(tempWorkspace().toString)
              .map(new ProcessTool(_))
            res <- tool.exec(
              ExecRequest(
                argv = List("/bin/echo", "a; rm -rf / | cat > /tmp/pwned")
              )
            )
          yield assertTrue(
            res.stdout.trim == "a; rm -rf / | cat > /tmp/pwned"
          )
        }
      }
    },
    test("PROC-02: output truncation is declared at the byte bound") {
      live {
        ZIO.scoped {
          for
            tool <- Workspace
              .make(tempWorkspace().toString)
              .map(new ProcessTool(_))
            big = "x" * 10_000
            res <- tool.exec(
              ExecRequest(argv = List("/bin/echo", big), maxOutputBytes = 1000)
            )
          yield assertTrue(
            res.truncated,
            res.stdout.length == 1000,
            res.stdout.forall(_ == 'x')
          )
        }
      }
    },
    test("PROC-02: timeout kills the child (bounded wall time, declared)") {
      live {
        ZIO.scoped {
          for
            tool <- Workspace
              .make(tempWorkspace().toString)
              .map(new ProcessTool(_))
            res <- tool.exec(
              ExecRequest(
                argv = List("/bin/sleep", "30"),
                timeout = ZDuration.fromMillis(400)
              )
            )
          yield assertTrue(
            res.timedOut,
            res.durationMs < 10_000, // kill happened; the test did not hang
            res.exitCode.forall(_ != 0)
          )
        }
      }
    },
    test(
      "PROC-02: typed negatives (argv / cwd escape / env policy / sandbox deny)"
    ) {
      live {
        ZIO.scoped {
          for
            root <- ZIO.attemptBlocking(tempWorkspace()).orDie
            tool <- Workspace.make(root.toString).map(new ProcessTool(_))
            emptyArgv <- tool.exec(ExecRequest(argv = Nil)).exit
            emptyHead <- tool.exec(ExecRequest(argv = List("  "))).exit
            escape <- tool
              .exec(
                ExecRequest(
                  argv = List("/bin/echo", "x"),
                  cwdRelative = Some("../../../../tmp")
                )
              )
              .exit
            envPolicy <- tool
              .exec(
                ExecRequest(
                  argv = List("/bin/echo"),
                  envAllowlist = List("A"),
                  envValues = Map("A" -> "1", "B" -> "2")
                )
              )
              .exit
            zeroBound <- tool
              .exec(ExecRequest(argv = List("/bin/echo"), maxOutputBytes = 0))
              .exit
            sandbox <- tool
              .exec(
                ExecRequest(argv = List("/bin/echo"), sandboxRequired = true)
              )
              .exit
          yield assertTrue(
            codeOf(emptyArgv).contains("RA-INP"),
            codeOf(emptyHead).contains("RA-INP"),
            codeOf(escape).contains("RA-TOOLDENY"), // cwd containment
            codeOf(envPolicy).contains("RA-INP"), // key outside allowlist
            codeOf(zeroBound).contains("RA-INP"),
            codeOf(sandbox).contains("RA-CAP") // Unknown isolation -> deny
          )
        }
      }
    }
  )
