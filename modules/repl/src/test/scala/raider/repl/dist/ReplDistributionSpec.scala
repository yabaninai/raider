package raider.repl.dist

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import zio.test.*

/** Staging-library acceptance (RAI-262): refusal of compiler-less jars,
  * complete executable layout with verifiable SHA256SUMS, idempotent restaging,
  * and a zip round-trip through the unpacker. All filesystem observations are
  * captured into eager values before cleanup, because zio-test assert arrows
  * evaluate lazily after the body returns.
  */
object ReplDistributionSpec extends ZIOSpecDefault:

  private def specLayout(outDir: Path) =
    ReplDistribution.Layout(
      InstalledDistFixture.fullJar,
      DistHarness.repoFile("modules/repl/packaging/raider-repl"),
      DistHarness.repoFile("modules/repl/packaging/README.txt"),
      InstalledDistFixture.TestVersion,
      outDir,
      InstalledDistFixture.DistName
    )

  override def spec: Spec[Any, Any] = suite("raider.repl.dist")(
    test("staging refuses a jar without the in-process compiler") {
      InstalledDistFixture.withFreshDir("rai262-refuse") { outDir =>
        val layout =
          specLayout(outDir).copy(jar = InstalledDistFixture.compilerLessJar)
        val result = ReplDistribution.stage(layout)
        val refusal = result.fold(identity, _ => "")
        val distDir = outDir.resolve(InstalledDistFixture.DistName)
        val isLeft = result.isLeft
        val noStaging = !Files.exists(distDir)
        assertTrue(
          isLeft,
          refusal.contains("lacks the in-process"),
          noStaging
        )
      }
    },
    test("staged layout is complete, executable, and SHA256SUMS verifies") {
      InstalledDistFixture.withFreshDir("rai262-layout") { outDir =>
        val staged = ReplDistribution
          .stage(specLayout(outDir))
          .fold(
            err => throw new IllegalStateException(err),
            identity
          )
        val distDir = staged.distDir
        val sums = Files.readString(
          distDir.resolve("SHA256SUMS"),
          StandardCharsets.UTF_8
        )
        val sumsMap = sums.linesIterator
          .map(l => l.split("  ", 2).toSeq)
          .map { case Seq(hash, name) => name -> hash }
          .toMap
        val expectedNames =
          Set("README.txt", "raider-repl", "raider-repl.jar", "version")
        val payloadPresent =
          expectedNames.map(name => Files.isRegularFile(distDir.resolve(name)))
        val archivePresent = Files.isRegularFile(staged.archive)
        // Independent digest code path (R-1): one-shot MessageDigest + HexFormat
        // formatting, deliberately not ReplDistribution.sha256Hex.
        def independentSha256(path: Path): String =
          val md = java.security.MessageDigest.getInstance("SHA-256")
          java.util.HexFormat
            .of()
            .formatHex(md.digest(Files.readAllBytes(path)))
        val hex64 = raw"[0-9a-f]{64}".r
        val sumsFormatOk =
          sumsMap.forall { case (_, hash) => hex64.matches(hash) }
        val sumsMatch = expectedNames.forall { name =>
          sumsMap
            .get(name)
            .contains(ReplDistribution.sha256Hex(distDir.resolve(name)))
        }
        val sumsIndependent = expectedNames.forall { name =>
          sumsMap.get(name).contains(independentSha256(distDir.resolve(name)))
        }
        val (shasumOk, shasumOut) = DistHarness.shasumVerify(distDir)
        val perms =
          Files.getPosixFilePermissions(distDir.resolve("raider-repl"))
        val execBit = perms.contains(
          java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
        )
        val versionOk =
          Files.readString(
            distDir.resolve("version"),
            StandardCharsets.UTF_8
          ) ==
            InstalledDistFixture.TestVersion + "\n"
        assertTrue(
          payloadPresent.forall(identity),
          archivePresent,
          sumsMap.keySet == expectedNames,
          sumsFormatOk,
          sumsMatch,
          sumsIndependent,
          shasumOk,
          execBit,
          versionOk
        )
      }
    },
    test("staging is idempotent and clears stale files") {
      InstalledDistFixture.withFreshDir("rai262-idem") { outDir =>
        val distDir = outDir.resolve(InstalledDistFixture.DistName)
        Files.createDirectories(distDir)
        Files.writeString(distDir.resolve("stale-leftover.txt"), "old")
        val first = ReplDistribution.stage(specLayout(outDir))
        val second = ReplDistribution.stage(specLayout(outDir))
        val staleGone = !Files.exists(distDir.resolve("stale-leftover.txt"))
        val jarPresent = Files.isRegularFile(distDir.resolve("raider-repl.jar"))
        val archivePresent =
          second.fold(_ => false, staged => Files.isRegularFile(staged.archive))
        assertTrue(
          first.isRight,
          second.isRight,
          staleGone,
          jarPresent,
          archivePresent
        )
      }
    },
    test("zip archive round-trips through the unpacker") {
      InstalledDistFixture.withFreshDir("rai262-zip") { unpackRoot =>
        val staged = InstalledDistFixture.staged
        DistHarness.unpack(staged.archive, unpackRoot)
        val distDir = unpackRoot.resolve(InstalledDistFixture.DistName)
        val stagedJar = staged.distDir.resolve("raider-repl.jar")
        val unpackedJar = distDir.resolve("raider-repl.jar")
        val launcherPresent =
          Files.isRegularFile(distDir.resolve("raider-repl"))
        val jarSizeMatches = Files.size(unpackedJar) == Files.size(stagedJar)
        val perms =
          Files.getPosixFilePermissions(distDir.resolve("raider-repl"))
        val execBit = perms.contains(
          java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
        )
        assertTrue(
          launcherPresent,
          jarSizeMatches,
          execBit
        )
      }
    }
  )
