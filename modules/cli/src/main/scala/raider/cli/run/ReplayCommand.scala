package raider.cli.run

import raider.cli.lineage.ReplayCli

import zio.ZIO

import java.nio.file.Path

/** RAI-109: the product `raider replay` dispatch — the ONLY bridge between the
  * general CLI ([[HeadlessRunner]]) and the accepted LIN-10 replay chain. FULL
  * delegation: [[ReplayCli.exec]] owns the replay argv validation (`<run-dir>`,
  * `--workspace`, `--data`, usage errors), stdout/stderr rendering, typed
  * failures and the frozen ci-runtime §8 exit mapping — nothing is duplicated
  * here, so the product entry keeps exact flag/exit parity with the standalone
  * `ReplayCliMain` entry (which stays as the transitional input,
  * docs/lineage.md §10).
  *
  * The [[HeadlessRunner.Outcome]] carries `result = None` on purpose: the
  * replay verdict goes to the console through ReplayCli (verdict lines on
  * stdout, typed failures on stderr), NOT through the run.json artifact surface
  * — a replay is a verification of a PAST run, it does not create a new
  * invocation root. `workDir` is the caller's placeholder path and is never
  * read for a replay outcome.
  */
object ReplayCommand:

  /** Runs the replay chain for the raw `raider replay` argv and returns the
    * outcome with ReplayCli's mapped exit code. Runtime defects (broken
    * invariants) crash loudly by the LIN-10 contract — they are never mapped to
    * a quiet code here either.
    */
  def outcome(
      argv: List[String],
      workDir: Path
  ): ZIO[Any, Nothing, HeadlessRunner.Outcome] =
    ZIO.succeed {
      val code = ReplayCli.exec(argv)
      HeadlessRunner.Outcome(code, workDir, None)
    }

end ReplayCommand
