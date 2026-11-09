package raider.evalexport

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

import zio.*

import raider.evalexport.model.*

/** Golden re-pin tool for contracts/eval-export/expected (RAI-040).
  *
  * Run ONLY via `modules/eval-export/run-tests.sh --update-goldens` (which
  * compiles this test tree with the same pinned compiler as the suite). Writing
  * a golden is a deliberate, reviewable act: the tool re-exports the four
  * deterministic fixtures, writes every bundle document into
  * `contracts/eval-export/expected/<name>/`, then re-reads and re-verifies what
  * it pinned (byte comparison, manifest integrity, lineage reconstruction,
  * reconciliation chain). Any self-check failure is a non-zero exit — a repin
  * that cannot re-verify is never left behind as green.
  *
  * Goldens (all byte-stable: deterministic fixtures, no clock, no map iteration
  * order):
  *   - normal-run — complete two-episode run with verdicts, calls with known
  *     and partially-known prices, four tool calls (digested raw args, pinned
  *     redacted-safe args, denied/failed results, artifact hash).
  *   - cancelled-run — partial run: explicit cancelled/interrupted episodes,
  *     pending + absent verifier states, no finishedAtMs.
  *   - unknown-price — honestly unknown usage prices (explicit nulls +
  *     unknown=true), never zeros.
  *   - late-usage — cancelled-run after two versioned late-usage appends
  *     (self-chained reconciliation.jsonl; every other document identical to
  *     cancelled-run).
  *
  * A committed repin MUST be accompanied by a review of the full semantic diff
  * (see contracts/eval-export/README.md, re-pin rules).
  */
object GoldenRepin extends ZIOAppDefault:

  private val goldens: Vector[(String, ExportBundle)] = Vector(
    "normal-run" -> Fixtures.exported(Fixtures.Normal.input),
    "cancelled-run" -> Fixtures.exported(Fixtures.Cancelled.input),
    "unknown-price" -> Fixtures.exported(Fixtures.UnknownPrice.input),
    "late-usage" -> Fixtures.lateUsageBundle
  )

  def run: ZIO[Any, Any, Unit] =
    for
      _ <- ZIO.foreachDiscard(goldens)(writeGolden.tupled)
      checked <- ZIO.foreach(goldens) { case (name, bundle) =>
        selfCheck(name, bundle).map(problem => name -> problem)
      }
      failures = checked.collect { case (name, Some(problem)) =>
        s"$name: $problem"
      }
      _ <- ZIO.when(failures.nonEmpty) {
        ZIO.fail(new IllegalStateException(failures.mkString("\n")))
      }
      _ <- ZIO.attempt {
        println(
          s"[repin] pinned ${goldens.size} goldens; byte-compare, integrity, lineage and chain self-checks passed"
        )
      }
    yield ()

  /** Write all six documents of one bundle into its golden directory. */
  private def writeGolden(
      name: String,
      bundle: ExportBundle
  ): ZIO[Any, Throwable, Unit] =
    ZIO.attempt {
      val dir = Fixtures.expectedDir(name)
      Files.createDirectories(dir)
      bundle.files.foreach(f =>
        Files.writeString(dir.resolve(f.name), f.content, UTF_8)
      )
      println(
        s"[repin] $name -> $dir (${bundle.files.map(f => s"${f.name}:${f.byteCount}B").mkString(", ")})"
      )
    }

  /** Re-read the golden directory from disk and verify it end to end. The same
    * checks the suite runs, executed immediately after the write.
    */
  private def selfCheck(
      name: String,
      bundle: ExportBundle
  ): ZIO[Any, Throwable, Option[String]] =
    ZIO.attempt {
      val repinned = ExportBundle(
        ExportBundle.AllNames.map { n =>
          val path = Fixtures.expectedDir(name).resolve(n)
          ExportFile(n, new String(Files.readAllBytes(path), UTF_8))
        }
      )
      val problems = scala.collection.mutable.ListBuffer.empty[String]
      Fixtures
        .goldenMismatch(repinned, name)
        .foreach(problems += "byte mismatch after repin: " + _)
      repinned
        .verifyIntegrity()
        .left
        .map(e => problems += "integrity: " + e.message)
      Lineage
        .reconstruct(repinned)
        .left
        .map(e => problems += "lineage: " + e.message)
      Reconciliation
        .verifyChain(repinned)
        .left
        .map(e => problems += "chain: " + e.message)
      if problems.isEmpty then {
        println(s"[repin] self-check ok: $name")
        None
      } else Some(problems.mkString("; "))
    }
