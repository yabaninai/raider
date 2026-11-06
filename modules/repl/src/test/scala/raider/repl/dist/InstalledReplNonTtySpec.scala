package raider.repl.dist

import java.nio.file.Files
import java.nio.file.Path
import zio.test.*

/** Non-TTY clean-install acceptance for the installed REPL distribution
  * (RAI-262). Everything runs from an UNPACKED distribution in a fresh temp
  * directory — never from the source tree or the sbt classpath — mirroring
  * scripts/quality/repl_smoke.sh end-to-end (real scala3-repl compiler, no mock
  * interpreter) plus the adversarial preflight and SIGTERM cases from the card:
  * bounded diagnostics (exit 21 via the launcher) and signal exit code
  * preservation (143).
  */
object InstalledReplNonTtySpec extends ZIOSpecDefault:

  private def unpackedLauncher(unpackRoot: Path): Path =
    DistHarness
      .unpack(InstalledDistFixture.staged.archive, unpackRoot)
      .resolve(InstalledDistFixture.DistName)
      .resolve("raider-repl")

  override def spec: Spec[Any, Any] = suite("installed repl non-tty")(
    test("--version exits 0 with the staged version") {
      InstalledDistFixture.withFreshDir("rai262-version") { dir =>
        val (exit, out) = DistHarness.runPiped(
          Seq(unpackedLauncher(dir).toString, "--version"),
          env = DistHarness.childEnv(dir)
        )
        assertTrue(
          exit == 0,
          out.contains("raider-repl"),
          out.contains(InstalledDistFixture.TestVersion)
        )
      }
    },
    test("clean install evaluates real Scala in a fresh directory (non-TTY)") {
      InstalledDistFixture.withFreshDir("rai262-install") { dir =>
        val launcher = unpackedLauncher(dir)
        // Independent tool-level verification of the staged SHA256SUMS against
        // the unpacked payload (R-1): shasum -a 256 -c / sha256sum -c.
        val (sumsOk, sumsOut) =
          DistHarness.shasumVerify(dir.resolve(InstalledDistFixture.DistName))
        val (exit, out) = DistHarness.runPiped(
          Seq(launcher.toString),
          env = DistHarness.childEnv(dir),
          input = "val x = 1\nx\n:quit\n",
          cwd = Some(dir)
        )
        assertTrue(
          exit == 0,
          sumsOk,
          sumsOut.contains("OK"),
          out.contains("Raider REPL"),
          out.contains("val x: Int = 1"),
          out.contains("val res0: Int = 1"),
          out.contains("bye")
        )
      }
    },
    test(
      "adversarial: missing distribution jar exits 21 with a one-line diagnostic"
    ) {
      InstalledDistFixture.withFreshDir("rai262-nojar") { dir =>
        val launcher = unpackedLauncher(dir)
        Files.deleteIfExists(launcher.getParent.resolve("raider-repl.jar"))
        val (exit, out) = DistHarness.runPiped(
          Seq(launcher.toString),
          env = DistHarness.childEnv(dir),
          input = ":quit\n"
        )
        assertTrue(
          exit == 21,
          out.contains("distribution jar not found")
        )
      }
    },
    test("adversarial: compiler-less jar exits 21 with a bounded diagnostic") {
      InstalledDistFixture.withFreshDir("rai262-nocompiler") { dir =>
        val launcher = unpackedLauncher(dir)
        val (exit, out) = DistHarness.runPiped(
          Seq(launcher.toString),
          env = DistHarness.childEnv(dir)
            + ("RAIDER_REPL_JAR" -> InstalledDistFixture.compilerLessJar.toString),
          input = "val x = 1\n:quit\n",
          timeoutSeconds = 120
        )
        assertTrue(
          exit == 21,
          out.contains("lacks the in-process Scala compiler")
        )
      }
    },
    test(
      "adversarial: direct JVM invocation without compiler prints the preflight diagnostic"
    ) {
      InstalledDistFixture.withFreshDir("rai262-jvm-direct") { dir =>
        val javaBin = java.nio.file.Paths
          .get(System.getProperty("java.home"), "bin", "java")
        val (exit, out) = DistHarness.runPiped(
          Seq(
            javaBin.toString,
            "-cp",
            InstalledDistFixture.compilerLessJar.toString,
            "raider.repl.Main"
          ),
          env = DistHarness.childEnv(dir),
          input = "val x = 1\n:quit\n",
          timeoutSeconds = 120
        )
        assertTrue(
          exit != 0,
          out.contains("the in-process Scala compiler is missing")
        )
      }
    },
    test("SIGTERM in a scripted session exits 143 (non-TTY)") {
      InstalledDistFixture.withFreshDir("rai262-sigterm") { dir =>
        val session = new DistSession(
          Seq(unpackedLauncher(dir).toString),
          DistHarness.childEnv(dir),
          Some(dir)
        )
        try
          val sawBanner = session.waitForOutput("Raider REPL", 240)
          session.process.destroy() // SIGTERM
          val exited = session.waitForExit(30)
          assertTrue(
            sawBanner,
            exited,
            session.process.exitValue() == 143
          )
        finally session.destroyForcibly()
      }
    }
  )
