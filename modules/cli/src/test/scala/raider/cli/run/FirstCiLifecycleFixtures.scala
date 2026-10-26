package raider.cli.run

import raider.core.{BundleWorkflow, Program, RaiderError, Task}
import zio.ZIO

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** RAI-187 lifecycle fixtures (test-only bundle entries, FCI-LIFE-01/02).
  * Jarred at test time from the test classes directory and loaded through the
  * ordinary [[raider.cli.run.BundleLoader]] path; both stay LAZY descriptors
  * (API-03): instantiating them dispatches nothing.
  */

/** FCI-LIFE-01: a workflow that sleeps for `--input` milliseconds — long enough
  * for a small `--deadline` to interrupt it mid-work so the deadline exit (24)
  * and the honest deadline artifacts can be observed at the process boundary.
  */
class LongSleepWorkflow extends BundleWorkflow:
  def name: String = "long-sleep"

  def program: Program[String, String] =
    Program.fromFunction("long-sleep") { input =>
      Task(
        ZIO
          .sleep(zio.Duration.fromMillis(input.trim.toLong))
          .as("done")
      )
    }

/** FCI-LIFE-02: a workflow that spawns a REAL grandchild process (`exec sleep
  * <seconds>`), records its PID into a marker file, and sleeps. The grandchild
  * is owned through a scoped acquisition whose release destroys it, so a
  * runner-fiber interrupt (the SIGTERM bounded stop) must destroy it — the test
  * verifies no orphan survives the CLI process. Input format:
  * `<marker-file-path>|<sleep-seconds>`.
  */
class SpawnSleepWorkflow extends BundleWorkflow:
  def name: String = "spawn-sleep"

  def program: Program[String, String] =
    Program.fromFunction("spawn-sleep") { input =>
      val parts = input.split("\\|", 2)
      val marker = Path.of(parts(0))
      val sleepSeconds = parts(1).trim
      Task(
        ZIO
          .scoped(
            ZIO
              .acquireRelease(
                ZIO.attempt {
                  val process = new ProcessBuilder(
                    "/bin/sh",
                    "-c",
                    s"exec sleep $sleepSeconds"
                  ).start()
                  val parent = marker.getParent
                  Option(parent).foreach(Files.createDirectories(_))
                  Files.writeString(marker, s"pid=${process.pid()}", UTF_8)
                  process
                }
              )(process => ZIO.attempt(process.destroy()).ignore)
              .flatMap(_ =>
                ZIO.sleep(zio.Duration.fromSeconds(sleepSeconds.toLong))
              )
          )
          .mapError(err => RaiderError.ToolFailed(String.valueOf(err)))
          .as("done")
      )
    }
