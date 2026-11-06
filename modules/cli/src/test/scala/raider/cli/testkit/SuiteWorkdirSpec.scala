package raider.cli.testkit

import zio.test._
import java.nio.file.Files

/** RAI-274: regression coverage for the RAI-272 review advisory A1 —
  * [[SuiteWorkdir]]'s recursive wipe must never follow a symlink. A planted
  * `dir`-type symlink must be deleted as the link itself while the directory it
  * points to (potentially anywhere on disk) keeps its contents, and a dangling
  * link must still be removed by the wipe. Each scenario uses its own
  * suite-work directory name, so the tests are order-independent under any
  * internal test parallelism.
  */
object SuiteWorkdirSpec extends ZIOSpecDefault:

  override def spec =
    suite("SuiteWorkdir")(
      test(
        "A1: wipe deletes a directory symlink itself, not the target contents"
      ) {
        val target = Files.createTempDirectory("rai274-a1-target")
        val canary = target.resolve("canary.txt")
        Files.writeString(canary, "must survive the wipe")
        val dir = SuiteWorkdir.forSuite("cli", "SuiteWorkdirSpecLinkTarget")
        Files.createSymbolicLink(
          dir.resolve("link-to-target"),
          target.toAbsolutePath
        )
        // re-entering the suite re-wipes the dir, link included
        SuiteWorkdir.forSuite("cli", "SuiteWorkdirSpecLinkTarget")
        assertTrue(
          Files.exists(canary), // the wipe did not recurse through the link
          Files.notExists(
            dir.resolve("link-to-target")
          ), // the link itself is gone
          Files.isDirectory(target) // the external target survives intact
        )
      },
      test("A1: wipe removes a dangling symlink without following it") {
        val dir = SuiteWorkdir.forSuite("cli", "SuiteWorkdirSpecDanglingLink")
        val dangling = dir.resolve("dangling-link")
        Files.createSymbolicLink(dangling, dir.resolve("no-such-target"))
        SuiteWorkdir.forSuite("cli", "SuiteWorkdirSpecDanglingLink")
        assertTrue(
          Files.notExists(dangling),
          Files.isDirectory(dir) // the recreated working dir is intact
        )
      }
    )
