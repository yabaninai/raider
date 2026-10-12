package raider.runtime.loop.corpus

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import zio.test.*
import zio.{Scope, ZIO}

/** RAI-114 acceptance: the ONE spec-runner of the plan-mode scenario corpus
  * (PEVAL-01/02/03).
  *   - PEVAL-01: >= 6 deterministic scenarios; the corpus is stable data
  *     (byte-identical regeneration, JSONL roundtrip) and every scenario's
  *     pinned typed verdict is checked against an actual scripted execution;
  *   - PEVAL-02: the negatives are IN the corpus — typed `PlanRejected` after
  *     the repair budget with an empty artifacts dir (s4), and the
  *     bounded-cancel cleanup with a repeated plan (s6);
  *   - PEVAL-03: all scenarios execute concurrently, each against its own
  *     TempDir/admission/manager/backend (no shared writes), deterministically.
  *
  * Flake sweep: run this suite 3 times (commands in the work record).
  */
object PlanScenarioCorpusSpec extends ZIOSpecDefault:

  /** sbt test JVMs run with cwd = build root; every file this suite creates
    * stays inside the runtime target dir (never a source path).
    */
  private def specDir(): Path =
    val dir = Path.of("modules", "runtime", "target", "plan-corpus-spec")
    Files.createDirectories(dir)
    dir

  private def write(path: Path, lines: Vector[String]): Unit =
    Files.write(
      path,
      lines.mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8)
    )

  def spec = suite("PlanScenarioCorpus RAI-114")(
    test(
      "PEVAL-01: the corpus is >= 6 scenarios and regenerates byte-identically (hash equal)"
    ) {
      val a = specDir().resolve("scenarios-a.jsonl")
      val b = specDir().resolve("scenarios-b.jsonl")
      write(a, PlanScenarioCorpus.jsonlLines())
      write(b, PlanScenarioCorpus.jsonlLines(PlanScenarioCorpus.scenarios))
      assertTrue(
        PlanScenarioCorpus.scenarios.size >= 6,
        PlanScenarioCorpus.jsonlLines() ==
          PlanScenarioCorpus.jsonlLines(PlanScenarioCorpus.scenarios),
        PlanScenarioCorpus.sha256Hex(
          Files.readAllLines(a).asScala.toVector
        ) == PlanScenarioCorpus.sha256Hex(
          Files.readAllLines(b).asScala.toVector
        )
      )
    },
    test("PEVAL-01: JSONL roundtrip restores every scenario exactly") {
      val lines = PlanScenarioCorpus.jsonlLines()
      val corrupted =
        Vector(lines.head.replace("ReadOnly", "NotAClass"))
      assertTrue(
        PlanScenarioCorpus.parseJsonl(lines) ==
          Right(PlanScenarioCorpus.scenarios),
        // an unknown recovery class is a decode failure, never a guess
        PlanScenarioCorpus.parseJsonl(corrupted).isLeft
      )
    },
    test(
      "PEVAL-01/02: every scenario's pinned typed verdict holds under scripted execution (incl. PlanRejected-without-artifact, bounded cancel)"
    ) {
      for results <- ZIO.foreach(PlanScenarioCorpus.scenarios) { scenario =>
          PlanScenarioCorpus
            .exec(scenario)
            .map(observed => PlanScenarioCorpus.verify(scenario, observed))
        }
      yield results.reduce(_ && _)
    },
    test(
      "PEVAL-03: scenarios run concurrently in isolation — parallel execution of the whole corpus stays green"
    ) {
      for results <- ZIO.foreachPar(PlanScenarioCorpus.scenarios) { scenario =>
          PlanScenarioCorpus
            .exec(scenario)
            .map(observed => PlanScenarioCorpus.verify(scenario, observed))
        }
      yield results.reduce(_ && _)
    }
  )

end PlanScenarioCorpusSpec
