package raider.cli.run

import raider.core.RootId
import raider.runtime.events.{EventEnvelope, EventCodecs}
import raider.runtime.events.EventCodecs.given
import raider.runtime.lineage.model.GraphHash
import raider.runtime.lineage.reduce.LineageReducer
import raider.cli.lineage.LineageSnapshot
import zio.ZIO
import zio.json.DecoderOps
import zio.test._

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** LIN-03B acceptance (wiring the live lineage into HeadlessRunner, ADR-027):
  *   - LIN3-B-01: a settled mock run writes EXACTLY ONE `lineage:` line into
  *     summary.md and its hash EQUALS `GraphHash(reduce(...))` of the envelopes
  *     RE-READ from the run's own events.jsonl checkpoint;
  *   - LIN3-B-02: repeating the run over the same input is deterministic — the
  *     same events reduce to the same hash, and two independent runs agree once
  *     the run-varying identity/timestamp fields (the random `cli_*` rootId and
  *     wall-clock `atMs`, both frozen into the GraphHash canonical form) are
  *     normalized away;
  *   - LIN3-B-03: a preflight-failed run carries NO lineage line at all.
  *
  * The comparisons use only the frozen public API (`LineageReducer`,
  * `GraphHash`, case-class `copy` in test code) — no production seam was added
  * for testability.
  */
object LineageSummarySpec extends ZIOSpecDefault:

  /** The single summary.md lineage line: `lineage: graph <hex64>, <n> node(s)
    * (checkpoint: events.jsonl)`.
    */
  private val LineageLine =
    raw"""- lineage: graph ([0-9a-f]{64}), (\d+) node\(s\) \(checkpoint: events\.jsonl\)""".r

  private def tempDir(): Path =
    Files.createTempDirectory("raider-lineage-test")

  private def mockAgentArgs(out: Path) =
    CliArgs
      .parse(
        List(
          "run",
          "--agent",
          "scout",
          "--input",
          "task-42",
          "--out",
          out.toString,
          "--mock"
        )
      )
      .fold(e => throw e, identity)

  private def summaryOf(workDir: Path): String =
    Files.readString(workDir.resolve("summary.md"), UTF_8)

  /** Re-reads the events.jsonl checkpoint and decodes every line with the
    * frozen wire codec — the exact input a lineage consumer would replay.
    */
  private def envelopesOf(workDir: Path): Vector[EventEnvelope] =
    val lines =
      Files.readString(workDir.resolve("events.jsonl"), UTF_8).split("\n")
    val decoded =
      lines.filter(_.trim.nonEmpty).toList.map(_.fromJson[EventEnvelope])
    decoded.collectFirst { case Left(err) => err } match
      case Some(err) =>
        throw new IllegalStateException(s"bad events.jsonl line: $err")
      case None => ()
    decoded.map(_.toOption.get).toVector

  private def hashFrom(summary: String): (String, Int) =
    val found = LineageLine.findAllMatchIn(summary).toList
    found match
      case m :: Nil => (m.group(1), m.group(2).toInt)
      case other =>
        throw new IllegalStateException(
          s"expected exactly one lineage line, got ${other.size}"
        )

  def spec = suite("headless lineage line (LIN-03B)")(
    test(
      "LIN3-B-01: mock run — one summary line, hash == GraphHash(reduce(events.jsonl re-read))"
    ) {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          outcome <- HeadlessRunner.run(mockAgentArgs(work))
          summary <- ZIO.attempt(summaryOf(outcome.workDir)).orDie
          (hash, nodes) <- ZIO.attempt(hashFrom(summary)).orDie
          envelopes <- ZIO.attempt(envelopesOf(outcome.workDir)).orDie
          graph = LineageReducer.reduce(envelopes)
        yield assertTrue(
          outcome.exitCode == 0,
          graph.isRight,
          graph.fold(_ => 0, _.nodes.size) == nodes,
          nodes >= 1, // the mock round is a real ModelCall node, not a stub
          graph.fold(_ => "", GraphHash(_)) == hash
        )
      }
    },
    test(
      "LIN3-B-02: repeating the run over the same input gives the same hash"
    ) {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          r1 <- HeadlessRunner.run(mockAgentArgs(work))
          r2 <- HeadlessRunner.run(mockAgentArgs(work))
          summary1 <- ZIO.attempt(summaryOf(r1.workDir)).orDie
          summary2 <- ZIO.attempt(summaryOf(r2.workDir)).orDie
          (h1, n1) <- ZIO.attempt(hashFrom(summary1)).orDie
          (h2, n2) <- ZIO.attempt(hashFrom(summary2)).orDie
          ev1 <- ZIO.attempt(envelopesOf(r1.workDir)).orDie
          ev2 <- ZIO.attempt(envelopesOf(r2.workDir)).orDie
          // Replay determinism (LIN1-01): the same envelope vector always
          // reduces to the same graph and the same GraphHash.
          replay = LineageReducer.reduce(ev1)
          // Two fresh runs differ ONLY in run-varying identity/timestamp
          // fields (random rootId, wall-clock atMs — both baked into the
          // frozen GraphHash canonical form). Normalizing exactly those
          // fields must make the two campaigns hash-identical.
          defuse = (evs: Vector[EventEnvelope]) =>
            evs.map(_.copy(rootId = RootId("cli_norm"), atMs = 0L))
          norm1 = LineageReducer.reduce(defuse(ev1))
          norm2 = LineageReducer.reduce(defuse(ev2))
        yield assertTrue(
          h1 == replay.fold(_ => "", GraphHash(_)),
          n1 == n2,
          norm1.isRight && norm2.isRight,
          norm1.fold(_ => "", GraphHash(_)) == norm2.fold(_ => "", GraphHash(_))
        )
      }
    },
    test("LIN3-B-03: preflight-failed run carries no lineage line") {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          // Typed preflight refusal BEFORE any dispatch (RAI-064): live
          // provider, no env key — Configuration family, no campaign output.
          args = CliArgs
            .parse(
              List(
                "run",
                "--agent",
                "scout",
                "--input",
                "task-42",
                "--out",
                work.toString,
                "--provider",
                "openai",
                "--base-url",
                "not-a-url"
              )
            )
            .fold(e => throw e, identity)
          outcome <- HeadlessRunner.run(args, Map.empty)
          summary <- ZIO.attempt(summaryOf(outcome.workDir)).orDie
        yield assertTrue(
          outcome.exitCode == 21,
          summary.contains("FAILED"),
          !summary.contains("lineage:")
        )
      }
    },
    test(
      "LIN3-03: a real invalid live phase fold is diagnostic-only and prior committed snapshot stays readable"
    ) {
      ZIO.scoped {
        for
          work <- ZIO.attempt(tempDir()).orDie
          // Establish a valid, committed session snapshot before the bad live
          // input arrives. This is deliberately the source of the pointer used
          // below, rather than a hand-built manifest.
          priorOutcome <- HeadlessRunner.run(mockAgentArgs(work))
          prior <- ZIO.attempt(LineageSnapshot.read(priorOutcome.workDir)).orDie
          (invalidOutcome, live) <- LiveInvalidSession.run(work)
          diagnostic = HeadlessRunner.liveProjectionDiagnostic(live)
          reportLine = diagnostic.map(HeadlessRunner.liveProjectionSummaryLine)
        yield assertTrue(
          priorOutcome.exitCode == invalidOutcome,
          invalidOutcome == 0,
          diagnostic.exists(_.contains("fold error:")),
          reportLine.exists(_.contains("lineage live projection: invalid")),
          prior.isRight,
          prior.toOption.exists(s =>
            Files.isRegularFile(s.sourcePath) && Files.isRegularFile(s.dataPath)
          ),
          prior.toOption.exists(s =>
            Files.isReadable(s.sourcePath) && Files.isReadable(s.dataPath)
          )
        )
      }
    }
  )
