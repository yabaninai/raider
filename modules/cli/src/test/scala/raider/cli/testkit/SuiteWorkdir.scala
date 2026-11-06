package raider.cli.testkit

import java.nio.file.{Files, LinkOption, Path}

/** RAI-272: per-suite unique working directories under the module target/
  * (never a source path, never a directory shared between suites).
  *
  * The pinned-dotc compile suites historically shared fixed working dirs
  * (`modules/cli/target/lineage-compile/` reused by four suites — the
  * esc-hostile pair of RAI-266 — plus the single-suite
  * `chat-workflow-compile/`). Any cross-suite shared fixed path is a latent
  * interference source under concurrent execution; RAI-266 pinned the serial
  * stance partly because of it (docs/parallel-development.md §1e). [[forSuite]]
  * gives each suite its own `modules/<module>/target/suite-work/<suite>/`
  * directory, deleted and recreated on first use per test JVM (the same
  * fresh-start discipline as the suites' own `freshDir`), so no two suites ever
  * write into one directory again.
  *
  * The build-root anchor is the snapshot marker the suites already use
  * (`artifacts/tools/m2`), located by walking up from the process cwd (sbt runs
  * tests with the module base as cwd; walking up keeps the helper honest under
  * any working directory).
  */
object SuiteWorkdir:

  /** The suite's own working directory: wiped and recreated on every call in
    * this JVM. Call it once per suite — e.g. from the same `lazy val` that used
    * to pin the shared fixed dir — passing the module directory name and the
    * suite's simple name.
    */
  def forSuite(module: String, suite: String): Path =
    val dir = buildRoot()
      .resolve("modules")
      .resolve(module)
      .resolve("target")
      .resolve("suite-work")
      .resolve(suite)
    deleteRecursively(dir)
    Files.createDirectories(dir)
    dir

  /** Post-order delete: the suite dir legitimately holds non-empty `out-<tag>/`
    * directories left by earlier test JVMs, so a flat `Files.delete` per entry
    * would throw DirectoryNotEmptyException (the suites' own `freshDir` may
    * stay non-recursive because dotc emits flat class files into `out-<tag>/`;
    * a suite working dir cannot rely on that).
    *
    * RAI-274 (RAI-272 review advisory A1): every existence/type check uses
    * [[LinkOption.NOFOLLOW_LINKS]]. A symlink inside the suite dir is deleted
    * as the link itself (`Files.delete` never follows links) — never followed,
    * so a malicious or accidental `link -> <any directory>` entry cannot make
    * the wipe recurse into and destroy an outside directory, and a symlink
    * cycle cannot loop the walk. Dangling links (the follow-aware
    * `Files.exists` used to miss them) are removed too.
    */
  private def deleteRecursively(p: Path): Unit =
    if Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) then
      val listing = Files.newDirectoryStream(p)
      try listing.forEach(entry => deleteRecursively(entry))
      finally listing.close()
      Files.delete(p)
    else if Files.exists(p, LinkOption.NOFOLLOW_LINKS) then Files.delete(p)

  private def buildRoot(): Path =
    val cwd = Path.of("").toAbsolutePath
    Iterator
      .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) =>
        dir
      }
      .find(dir => Files.isDirectory(dir.resolve("artifacts/tools/m2")))
      .getOrElse(
        throw new IllegalStateException(
          "no ancestor of " + cwd + " contains artifacts/tools/m2"
        )
      )
