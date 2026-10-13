package raider.cli.run

import raider.core.result.ExitCodes
import raider.runtime.events.EventPayload
import raider.runtime.jobs.JobStatus
import raider.runtime.lineage.io.{
  LineageError,
  LineageEventReader,
  TruncatedTail
}
import raider.runtime.lineage.model.SessionNode
import raider.runtime.lineage.reduce.{LineageReducer, ReduceError}
import raider.runtime.lineage.model.GraphHash

import zio.ZIO

import java.nio.file.{Files, Path}
import java.security.MessageDigest

/** RAI-129 (§13 recovery remainder): the product `raider resume <run-dir>`
  * command — the minimal recovery surface over a PAST run's journal.
  *
  * The journal of a headless run is its `<run-dir>/events.jsonl` (LIN-03A: one
  * [[raider.runtime.events.EventEnvelope]] per line, frozen ADR-024 codecs,
  * streamed incrementally, honest truncated tail on a crash or a SIGTERM).
  * Resume re-reads that journal and restores the OBSERVABLE state:
  *
  *   - the journal is read through the accepted LIN-01B reader
  *     [[LineageEventReader]] — the same typed torn-tail semantics as the
  *     journal slice ([[raider.runtime.journal.JobJournal.replay]]): the prefix
  *     up to the LAST GOOD record is recovered, an undecodable LAST line is
  *     reported as a truncated tail (never silently dropped, never silently
  *     trusted), and any earlier unreadable content is a typed error;
  *   - the recovered prefix is RE-FOLDED through the accepted LIN5-03
  *     [[LineageReducer.reducePartial]] into the session state as pure data —
  *     this is the "replay from the last consistent point WITHOUT replaying
  *     mutations" of the card: a deterministic fold over envelopes, nothing is
  *     executed (no model call, no tool call, no workspace write, no journal
  *     append — a run whose tools mutated the world gets NO second execution of
  *     them);
  *   - a run with no `Terminal` record is reported as `interrupted` (§13
  *     restart marking: unfinished jobs are Interrupted/Uncertain; the last
  *     tool is NEVER executed automatically);
  *   - artifact pointers (known run artifact names present in the run dir) are
  *     reported with size + SHA-256 — recovery output is checkable, and the run
  *     directory itself stays byte-identical (resume is READ-ONLY; unlike
  *     [[raider.runtime.journal.JobJournal.replay]] it does NOT truncate the
  *     torn tail, because nothing ever appends to a past run's journal — the
  *     uncertain bytes stay preserved for reconciliation);
  *   - a missing journal (no run dir, or no `events.jsonl` in it) is an honest
  *     typed error, never a fabricated empty recovery.
  *
  * Exit codes reuse the frozen ci-runtime §8 mapping only — no invented codes
  * (ReplayCli discipline):
  *   - 0 — recovered, journal consistent to its end (the report says honestly
  *     whether the run itself reached a terminal status);
  *   - 26 — recovered to the last consistent point, tail UNCERTAIN (the
  *     truncated-tail marker names the line; bytes after the cut are lost);
  *   - 21 — usage error, journal missing, or journal unreadable (typed
  *     `LineageError`: empty / malformed line / schema version / seq gap);
  *   - 20 — the recovered prefix does not fold ([[ReduceError]]).
  *
  * Surface choice (card contract, precedent-based): a run-module CLI
  * subcommand, exactly like RAI-109 wired `raider replay` — the journal and
  * artifacts live in the run module's `<out>/work-<uuid>` invocation dirs, the
  * verdict is console-only ([[HeadlessRunner.Outcome.result = None]]: a resume
  * verifies/recovers a PAST run and never creates a new invocation root), and
  * exit-code consistency is a ci-runtime §8 run-module concern. The REPL has no
  * run-dir surface.
  *
  * Non-goals honored (card): no phase/recovery wire events, no retention /
  * redaction policy, no distributed recovery, no frozen-schema change, no
  * continuation EXECUTION (a resumed run is never re-dispatched — that is the
  * owner schema-card's future surface).
  *
  * Determinism: the whole pipeline is pure (no wall clock, no RNG, no iteration
  * over unordered sets in the rendered output), so repeated resumes of the same
  * run dir render byte-identical reports — the property REC-03 asserts across
  * its ×3 repetitions.
  */
object ResumeCommand:

  /** File name of the run journal inside a run directory (LIN-03A layout). */
  val JournalFileName = "events.jsonl"

  /** Known run artifact names resume reports pointers for (fixed order — part
    * of the deterministic report). Files outside this list are not claimed as
    * artifacts; the journal itself is always listed first when present (it is
    * the input, and a pointer to it makes the report self-describing).
    */
  val ArtifactNames: Vector[String] = Vector(
    JournalFileName,
    "run.json",
    "summary.md",
    "transcript.json",
    "lineage.scala",
    "lineage-data.json",
    "checkpoint.json"
  )

  /** Closed typed-failure set of the resume command (ReplayCli discipline: one
    * case per failure source, no stringly-typed catch-all).
    */
  enum Failure:

    /** The run directory or its `events.jsonl` does not exist — an honest typed
      * refusal (REC-02), never a fabricated empty recovery.
      */
    case JournalMissing(runDir: Path, journal: Path)

    /** The journal exists but cannot be read as an event stream (typed
      * [[LineageError]]: `Empty`, `MalformedLine`, `SchemaVersionUnsupported`,
      * `SeqGap`). Content distrust is DATA-named, never silent.
      */
    case Journal(path: Path, error: LineageError)

    /** The recovered prefix does not fold into session state ([[ReduceError]]).
      * Unreachable for a well-formed run journal; kept typed so a broken writer
      * regression can never surface as a crash or a fake recovery.
      */
    case Reduce(error: ReduceError)
  end Failure

  /** One artifact pointer: the honest file facts at recovery time. */
  final case class ArtifactPointer(
      name: String,
      sizeBytes: Long,
      sha256: String
  )

  /** The recovered observable state of a past run — pure data, named fields
    * (REC-01's "состояние восстановлено (named-поля)").
    *
    *   - `graph` — the deterministic
    *     [[raider.runtime.lineage.model.SessionGraph]] re-folded from the
    *     recovered prefix (the restored state machine);
    *   - `events` / `lastSeq` — how much journal was recovered and where the
    *     last consistent point sits;
    *   - `terminal` — the run's recorded terminal outcome, when the journal
    *     reached one;
    *   - `lastObserved` — the last observed non-terminal job status (named for
    *     the `interrupted` verdict);
    *   - `toolCallsStarted` — how many tool calls the journal RECORDS as
    *     started (recovery context only — resume executed none of them);
    *   - `jobs` — distinct job ids seen in the prefix (root + delegations);
    *   - `tail` — the typed truncated-tail marker, `None` = journal consistent
    *     to its end;
    *   - `artifacts` — the present artifact pointers.
    */
  final case class Recovered(
      runDir: Path,
      journal: Path,
      graph: raider.runtime.lineage.model.SessionGraph,
      events: Int,
      lastSeq: Long,
      terminal: Option[JobStatus.Terminal],
      lastObserved: Option[JobStatus],
      toolCallsStarted: Long,
      jobs: Vector[String],
      tail: Option[TruncatedTail],
      artifacts: Vector[ArtifactPointer]
  )

  /** The command outcome: recovered state or a typed [[Failure]]. */
  type Verdict = Either[Failure, Recovered]

  /** Parses the raw resume argv (the `resume` token included, same rule as
    * [[ReplayCli.parse]]): exactly one positional run directory, no flags — the
    * minimal surface. Anything else is a usage error rendered with the usage
    * text.
    */
  def parse(argv: List[String]): Either[String, Path] =
    val args = argv.toList match
      case "resume" :: tail => tail
      case other            => other
    args match
      case Nil => Left(s"missing <run-dir>\n${Usage}")
      case runDir :: Nil =>
        if runDir.startsWith("--") then Left(s"unknown flag: $runDir\n${Usage}")
        else Right(Path.of(runDir))
      case _ => Left(s"unexpected extra arguments\n${Usage}")
  end parse

  /** Runs the recovery pipeline for `runDir` — pure, total over its typed error
    * channel: journal missing → [[Failure.JournalMissing]]; unreadable journal
    * → [[Failure.Journal]]; unfolded prefix → [[Failure.Reduce]]; otherwise the
    * named-field [[Recovered]] state. The run directory is never written.
    */
  def recover(runDir: Path): Verdict =
    val journal = runDir.resolve(JournalFileName)
    if !Files.isDirectory(runDir) || !Files.isRegularFile(journal) then
      Left(Failure.JournalMissing(runDir, journal))
    else
      LineageEventReader.read(journal) match
        case Left(error) => Left(Failure.Journal(journal, error))
        case Right(parsed) =>
          val envs = parsed.envelopes
          // Observable-status fold over the recovered prefix (pure data —
          // the fold itself IS the "replay without mutations": nothing here
          // executes or writes anything).
          var lastObserved = Option.empty[JobStatus]
          var terminal = Option.empty[JobStatus.Terminal]
          var toolCallsStarted = 0L
          envs.foreach { env =>
            env.payload match
              case EventPayload.JobStatusChanged(_, to, _) =>
                lastObserved = Some(to)
              case EventPayload.Terminal(t) => terminal = Some(t)
              case EventPayload.ToolStarted(_, _, _) =>
                toolCallsStarted += 1L
              case _ => ()
          }
          val jobs =
            envs.map(_.jobId.value).distinct // stream order — deterministic
          LineageReducer.reducePartial(envs) match
            case Left(error) => Left(Failure.Reduce(error))
            case Right(graph) =>
              Right(
                Recovered(
                  runDir = runDir,
                  journal = journal,
                  graph = graph,
                  events = envs.size,
                  lastSeq = envs.lastOption.map(_.seq).getOrElse(0L),
                  terminal = terminal,
                  lastObserved = lastObserved,
                  toolCallsStarted = toolCallsStarted,
                  jobs = jobs,
                  tail = parsed.truncatedTail,
                  artifacts = artifactPointers(runDir)
                )
              )
  end recover

  /** The present-artifact pointer scan: fixed candidate order, size + SHA-256
    * of each regular file. Read-only; missing names contribute nothing (nothing
    * is invented).
    */
  private def artifactPointers(runDir: Path): Vector[ArtifactPointer] =
    ArtifactNames.flatMap { name =>
      val p = runDir.resolve(name)
      if Files.isRegularFile(p) then
        val bytes = Files.readAllBytes(p)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = digest.map(b => f"$b%02x").mkString
        Some(ArtifactPointer(name, Files.size(p), hex))
      else None
    }

  // ---------------------------------------------------------------------------
  // Rendering and exit codes (ReplayCli conventions: report on stdout, typed
  // failures on stderr, frozen §8 mapping only)
  // ---------------------------------------------------------------------------

  private val Usage =
    s"""usage: raider resume <run-dir>
       |
       |Recovers the observable state of a PAST run from its journal
       |(<run-dir>/$JournalFileName): re-reads the typed journal (a torn tail
       |is reported, never silently dropped), re-folds the recovered prefix
       |into session state (pure data fold — NOTHING is re-executed: no model
       |call, no tool call, no write), and reports jobs, status and artifact
       |pointers. A run without a Terminal record is reported as interrupted.
       |
       |Exit codes (ci-runtime §8): 0 = recovered (journal consistent; the
       |report states whether the run itself finished), 26 = recovered to the
       |last consistent point with an UNCERTAIN truncated tail, 21 = usage /
       |journal missing / journal unreadable, 20 = journal does not fold.""".stripMargin

  /** The deterministic report text (stdout). Named fields only; the
    * uncertain-tail marker is part of the report AND of the exit code.
    */
  def render(r: Recovered): String =
    val statusLine = r.terminal match
      case Some(t) => s"status: terminal ${statusName(t)}"
      case None =>
        val observed = r.lastObserved.fold("none")(statusName)
        s"status: interrupted (last observed: $observed)"
    val tailLine = r.tail.fold("uncertain-tail: none") { t =>
      s"uncertain-tail: line ${t.lineNo} of $JournalFileName " +
        s"(${t.rawPrefixLen} chars, undecodable)"
    }
    val sb = new StringBuilder
    sb.append(s"resume: ${r.runDir}\n")
    sb.append(s"$statusLine\n")
    sb.append(s"root: ${r.graph.rootId.value}\n")
    sb.append(s"session: ${r.graph.sessionId.value}\n")
    val counts = nodeCounts(r.graph)
    sb.append(
      s"nodes: ${r.graph.nodes.size} (model: ${counts._1}, tools: ${counts._2}, " +
        s"delegations: ${counts._3}, phases: ${counts._4})\n"
    )
    sb.append(s"graph-hash: ${GraphHash(r.graph)}\n")
    sb.append(s"jobs: ${r.jobs.mkString(",")}\n")
    sb.append(s"events: ${r.events} (last consistent seq: ${r.lastSeq})\n")
    sb.append(s"tool-calls: ${r.toolCallsStarted} started (not re-executed)\n")
    sb.append(s"$tailLine\n")
    if r.artifacts.isEmpty then sb.append("artifacts: none\n")
    else
      sb.append("artifacts:\n")
      r.artifacts.foreach { a =>
        sb.append(s"  ${a.name} ${a.sizeBytes} B sha256:${a.sha256}\n")
      }
    sb.result()
  end render

  private def nodeCounts(
      g: raider.runtime.lineage.model.SessionGraph
  ): (Int, Int, Int, Int) =
    var model = 0
    var tools = 0
    var delegations = 0
    var phases = 0
    g.nodes.foreach {
      case _: SessionNode.ModelCall  => model += 1
      case _: SessionNode.ToolCall   => tools += 1
      case _: SessionNode.Delegation => delegations += 1
      case _: SessionNode.Phase      => phases += 1
    }
    (model, tools, delegations, phases)

  private def statusName(s: JobStatus): String = s.toString

  private def renderFailure(f: Failure): String = f match
    case Failure.JournalMissing(runDir, journal) =>
      s"resume journal missing: $journal (no raider run journal in $runDir)"
    case Failure.Journal(path, error) =>
      s"resume journal unreadable at $path: ${renderLineage(error)}"
    case Failure.Reduce(error) =>
      s"resume journal does not fold: $error"

  private def renderLineage(e: LineageError): String = e match
    case LineageError.Empty => "empty journal (LineageError.Empty)"
    case LineageError.MalformedLine(lineNo, detail) =>
      s"malformed line $lineNo (LineageError.MalformedLine): $detail"
    case LineageError.SchemaVersionUnsupported(found) =>
      s"schemaVersion $found unsupported (LineageError.SchemaVersionUnsupported)"
    case LineageError.SeqGap(job, expected, found) =>
      s"seq gap for job $job: expected $expected, found $found (LineageError.SeqGap)"

  /** Exit code of a verdict — the frozen ci-runtime §8 mapping only:
    * recovered+consistent → 0; recovered with an uncertain tail → 26
    * ([[ExitCodes.Uncertain]]: the bytes after the last consistent point are
    * lost, so the run's tail outcome is honestly uncertain); typed failures
    * 21/20 per the [[Failure]] case (usage and missing/unreadable journal are
    * config/input-invalid — the ReplayCli precedent for missing/corrupt input;
    * an unfoldable prefix is a runtime-data failure — the ReplayCli precedent
    * for [[Failure.Reduce]]).
    */
  def exitCode(verdict: Verdict): Int = verdict match
    case Left(_: Failure.JournalMissing) => ExitCodes.ConfigInvalid
    case Left(_: Failure.Journal)        => ExitCodes.ConfigInvalid
    case Left(_: Failure.Reduce)         => ExitCodes.RuntimeFailure
    case Right(recovered) =>
      if recovered.tail.isDefined then ExitCodes.Uncertain
      else ExitCodes.Success
  end exitCode

  /** Exec body: verdict report on stdout, typed failures and usage on stderr
    * (HeadlessRunner convention), then the mapped exit code as the RETURN value
    * — the process exit belongs to `raider.cli.Main`. Pure and
    * side-effect-free: repeated invocations on the same run dir render
    * byte-identical reports and leave the directory byte-identical (REC-03).
    */
  def exec(argv: List[String]): Int =
    parse(argv) match
      case Left(usageError) =>
        System.err.println(usageError)
        System.err.flush()
        ExitCodes.ConfigInvalid
      case Right(runDir) =>
        recover(runDir) match
          case Left(failure) =>
            val code = exitCode(Left(failure))
            System.err.println(s"typed-error: ${renderFailure(failure)}")
            System.err.println(s"exit $code: ${failureReason(code)}")
            System.err.flush()
            code
          case Right(recovered) =>
            System.out.print(render(recovered))
            System.out.flush()
            val code = exitCode(Right(recovered))
            if code != ExitCodes.Success then
              System.err.println(s"exit $code: ${failureReason(code)}")
              System.err.flush()
            code
  end exec

  private def failureReason(code: Int): String = code match
    case ExitCodes.RuntimeFailure => "runtime-failure"
    case ExitCodes.ConfigInvalid  => "config-invalid"
    case ExitCodes.Uncertain      => "outcome-uncertain"
    case other                    => s"failure-$other"

  /** The [[HeadlessRunner]] bridge (RAI-129): the product `raider resume`
    * dispatch — full delegation to [[exec]], mirroring
    * [[ReplayCommand.outcome]]. The outcome carries `result = None` on purpose:
    * the recovery report goes to the console, NOT through the run.json artifact
    * surface — a resume never creates a new invocation root. `workDir` is the
    * caller's placeholder path and is never read for a resume outcome.
    */
  def outcome(
      argv: List[String],
      workDir: Path
  ): ZIO[Any, Nothing, HeadlessRunner.Outcome] =
    ZIO.succeed {
      val code = exec(argv)
      HeadlessRunner.Outcome(code, workDir, None)
    }

end ResumeCommand
