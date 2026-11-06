package raider.repl.dist

import java.nio.file.Files
import java.nio.file.Path
import scala.util.Using

/** Shared lazily-built artifacts for the installed-distribution specs
  * (RAI-262): one full fat jar, one compiler-less adversarial jar and one
  * staged distribution, all under java.io.tmpdir (outside the repository) and
  * removed by a JVM shutdown hook. Heavy artifacts are built once per test JVM
  * and shared by the specs.
  */
object InstalledDistFixture:

  val TestVersion = "0.1.0-rc.1+rai262"
  val DistName = "raider-repl-dist-test"

  private val base = Files.createTempDirectory("rai262-dist")
  Runtime.getRuntime.addShutdownHook(
    new Thread(() => DistHarness.deleteRecursive(base))
  )

  lazy val fullJar: Path =
    DistHarness.packFatJar(base.resolve("raider-repl.jar"), "raider.repl.Main")

  lazy val compilerLessJar: Path =
    DistHarness.packFatJar(
      base.resolve("raider-repl-nocompiler.jar"),
      "raider.repl.Main",
      skipCompiler = true
    )

  lazy val staged: StagedDistribution =
    val launcher = DistHarness.repoFile("modules/repl/packaging/raider-repl")
    val readme = DistHarness.repoFile("modules/repl/packaging/README.txt")
    ReplDistribution
      .stage(
        ReplDistribution.Layout(
          fullJar,
          launcher,
          readme,
          TestVersion,
          base,
          DistName
        )
      )
      .fold(err => throw new IllegalStateException(err), identity)

  /** Creates a fresh, empty directory for a clean-install run (unpacked
    * distribution, HOME, working dir — never a repo checkout).
    */
  def freshDir(prefix: String): Path = Files.createTempDirectory(prefix)

  def withFreshDir[A](prefix: String)(f: Path => A): A =
    val dir = freshDir(prefix)
    try f(dir)
    finally DistHarness.deleteRecursive(dir)
