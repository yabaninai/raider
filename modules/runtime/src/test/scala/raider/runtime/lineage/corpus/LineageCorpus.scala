package raider.runtime.lineage.corpus

import java.nio.charset.StandardCharsets
import java.nio.file.{
  AtomicMoveNotSupportedException,
  Files,
  Path,
  StandardCopyOption
}

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.budget.MicroUsd
import raider.runtime.events.EventCodecs.given
import raider.runtime.events.{
  EventEnvelope,
  EventPayload,
  LineTail,
  ToolOutcome,
  UsageSnapshot,
  WaitReason
}
import raider.runtime.jobs.JobStatus
import zio.json.*

/** Deterministic events.jsonl corpus generator (LIN-05A, parent LIN-05 prep;
  * ADR-027). Lives in raiderRuntime TEST scope only — fixture tooling for
  * lineage positive/negative tests (L2, wave-2), never a product input path.
  *
  * [[campaign]] replays a realistic delegation campaign as pure data: one root
  * job that delegates two children (`Delegated`), model rounds with
  * `UsageSnapshot` cost, tool calls covering ALL four `ToolOutcome`s
  * (Succeeded/Failed/Cancelled/Timeout), `BudgetChanged`, `RetryScheduled`,
  * `JobStatusChanged`/`Waiting` transitions and exactly one `Terminal` per job.
  * Envelopes use the frozen 13-case `EventPayload` family encoded with the
  * canonical `EventCodecs` (schemaVersion 1, one JSON document per line — the
  * same per-line rule as `raider.cli.run.EventExport.jsonl`, minus the
  * single-job seq sort: a multi-job campaign keeps emission order because `seq`
  * restarts per job).
  *
  * Determinism: no wall clock, no global RNG — an immutable LCG seeded by
  * `seed` varies costs/durations/tails while the structure is fixed, so the
  * same seed byte-for-byte replays the same JSONL on any JVM (LIN5-A-01).
  *
  * NOT generated on purpose: phase markers. `EventPayload` has NO phase case
  * (13 frozen cases); phase-markers are a separate schema decision (wave-2,
  * ADR-027 addendum §6) — do not emulate them with `Diagnostics` or any other
  * payload here.
  */
object LineageCorpus:

  final val SchemaVersion: Int = 1

  /** schemaVersion stamped by [[wrongSchemaVersion]]; any value != 1 is invalid
    * for this seam revision.
    */
  final val WrongSchemaVersion: Int = 999

  /** One realistic campaign for `seed`: 1 root job, 2 delegated children,
    * interleaved emission order (parent fan-out, children working, children
    * settling, parent finishing last). Per-job `seq` is assigned by
    * construction as 1..n in emission order — monotonic without gaps.
    */
  def campaign(seed: Int = 1): Vector[EventEnvelope] =
    val rootId = RootId(s"root-$seed")
    val sessionId = SessionId(s"sess-$seed")
    val root = JobId(s"job-$seed-root")
    val child1 = JobId(s"job-$seed-child-1")
    val child2 = JobId(s"job-$seed-child-2")

    // Threaded in emission order: changing the script shifts value slots
    // intentionally, never the determinism of a fixed seed.
    val (uRoot1, r1) = usage(Rng(seed))
    val (dRoot1, r2) = durationMs(r1)
    val (tRoot1, r3) = tail(r2, "bash", 4)
    val (tRoot2, r4) = tail(r3, "edit", 3)
    val (uRoot2, r5) = usage(r4)
    val (dRoot2, r6) = durationMs(r5)
    val (retryDelayMs, r7) = delayMs(r6)
    val (tRoot3, r8) = tail(r7, "search", 3)
    val (uRoot3, r9) = usage(r8)
    val (dRoot3, r10) = durationMs(r9)
    val (uChild1a, r11) = usage(r10)
    val (dChild1a, r12) = durationMs(r11)
    val (tChild1a, r13) = tail(r12, "bash", 4)
    val (diagLost, r14) = r13.below(8)
    val (tChild1b, r15) = tail(r14, "edit", 3)
    val (uChild1b, r16) = usage(r15)
    val (dChild1b, r17) = durationMs(r16)
    val (uChild2a, r18) = usage(r17)
    val (dChild2a, r19) = durationMs(r18)
    val (tChild2a, r20) = tail(r19, "search", 3)
    val (budgetReserved, r21) = r20.below(2_000_000)
    val (budgetObserved, r22) = r21.below(budgetReserved + 1)
    val (budgetUnknown, r23) = r22.below(4)

    val script = Vector.newBuilder[(JobId, EventPayload)]
    def emit(job: JobId, payload: EventPayload): Unit =
      script += (job -> payload)

    // Root starts, runs one model round, fans out two children.
    emit(
      root,
      EventPayload.JobStatusChanged(JobStatus.Queued, JobStatus.Running, None)
    )
    emit(root, EventPayload.ModelCallStarted(1, ModelA, Some(12)))
    emit(root, EventPayload.ModelCallFinished(1, dRoot1, uRoot1))
    emit(root, EventPayload.Delegated(child1, AgentA))
    emit(
      child1,
      EventPayload.JobStatusChanged(JobStatus.Queued, JobStatus.Running, None)
    )
    emit(root, EventPayload.Delegated(child2, AgentB))
    emit(child1, EventPayload.ModelCallStarted(1, ModelB, Some(9)))
    emit(child1, EventPayload.ModelCallFinished(1, dChild1a, uChild1a))
    emit(
      child1,
      EventPayload.ToolStarted(
        CallChild1a,
        "bash",
        "sbt --batch raiderCore/compile"
      )
    )
    emit(child1, EventPayload.ToolProgress(CallChild1a, "bash", tChild1a))
    emit(
      child1,
      EventPayload.ToolFinished(CallChild1a, "bash", ToolOutcome.Succeeded)
    )
    emit(
      child1,
      EventPayload.Diagnostics(s"raiderCore compiled clean (root $rootId)")
    )
    emit(root, EventPayload.ToolStarted(CallRoot1, "bash", "ls -la"))
    emit(root, EventPayload.ToolProgress(CallRoot1, "bash", tRoot1))
    emit(
      root,
      EventPayload.ToolFinished(CallRoot1, "bash", ToolOutcome.Succeeded)
    )
    // Child-2 works and gets steered away mid-search (Cancelled outcome).
    emit(
      child2,
      EventPayload.JobStatusChanged(JobStatus.Queued, JobStatus.Running, None)
    )
    emit(child2, EventPayload.ModelCallStarted(1, ModelB, Some(8)))
    emit(child2, EventPayload.ModelCallFinished(1, dChild2a, uChild2a))
    emit(child2, EventPayload.DiagnosticsCoalesced(diagLost.toLong))
    emit(
      child2,
      EventPayload.ToolStarted(
        CallChild2a,
        "search",
        "grep -rn \"TODO\" modules/runtime"
      )
    )
    emit(child2, EventPayload.ToolProgress(CallChild2a, "search", tChild2a))
    emit(
      child2,
      EventPayload.ToolFinished(CallChild2a, "search", ToolOutcome.Cancelled)
    )
    emit(
      child2,
      EventPayload.JobStatusChanged(
        JobStatus.Running,
        JobStatus.Cancelling,
        None
      )
    )
    // Root: a failing edit, a retry, and the second model round.
    emit(
      root,
      EventPayload.ToolStarted(
        CallRoot2,
        "edit",
        "patch modules/app/Main.scala"
      )
    )
    emit(root, EventPayload.ToolProgress(CallRoot2, "edit", tRoot2))
    emit(
      root,
      EventPayload.ToolFinished(CallRoot2, "edit", ToolOutcome.Failed("exit 1"))
    )
    emit(
      root,
      EventPayload.RetryScheduled(2, retryDelayMs, "rate limited (mock stand)")
    )
    emit(root, EventPayload.ModelCallStarted(2, ModelA, Some(14)))
    emit(root, EventPayload.ModelCallFinished(2, dRoot2, uRoot2))
    // Children settle before the parent: child-2 cancelled, child-1 succeeded.
    emit(child2, EventPayload.Terminal(JobStatus.Cancelled))
    emit(
      child1,
      EventPayload.ToolStarted(
        CallChild1b,
        "edit",
        "patch modules/core/ids.scala"
      )
    )
    emit(child1, EventPayload.ToolProgress(CallChild1b, "edit", tChild1b))
    emit(
      child1,
      EventPayload.ToolFinished(CallChild1b, "edit", ToolOutcome.Timeout)
    )
    emit(child1, EventPayload.ModelCallStarted(2, ModelB, Some(11)))
    emit(child1, EventPayload.ModelCallFinished(2, dChild1b, uChild1b))
    emit(child1, EventPayload.Terminal(JobStatus.Succeeded))
    // Root waits on budget, resumes, finishes with a timed-out search and one last round.
    emit(
      root,
      EventPayload.BudgetChanged(
        MicroUsd(budgetReserved.toLong),
        MicroUsd(budgetObserved.toLong),
        budgetUnknown.toLong
      )
    )
    emit(
      root,
      EventPayload.JobStatusChanged(
        JobStatus.Running,
        JobStatus.Waiting,
        Some(WaitReason.Budget)
      )
    )
    emit(root, EventPayload.Waiting(WaitReason.Budget))
    emit(
      root,
      EventPayload.JobStatusChanged(JobStatus.Waiting, JobStatus.Running, None)
    )
    emit(
      root,
      EventPayload.ToolStarted(CallRoot3, "search", "grep -rn TODO modules/")
    )
    emit(root, EventPayload.ToolProgress(CallRoot3, "search", tRoot3))
    emit(
      root,
      EventPayload.ToolFinished(CallRoot3, "search", ToolOutcome.Timeout)
    )
    emit(root, EventPayload.ModelCallStarted(3, ModelB, Some(16)))
    emit(root, EventPayload.ModelCallFinished(3, dRoot3, uRoot3))
    emit(root, EventPayload.Terminal(JobStatus.Succeeded))

    val emissions = script.result()
    val baseMs = BaseMs + seed.toLong.abs * 1000L
    val (_, envelopes) = emissions.zipWithIndex.foldLeft(
      (Map.empty[JobId, Long], Vector.empty[EventEnvelope])
    ) { case ((counts, acc), ((job, payload), i)) =>
      val seq = counts.getOrElse(job, 0L) + 1L
      (
        counts.updated(job, seq),
        acc :+ EventEnvelope(
          SchemaVersion,
          seq,
          job,
          rootId,
          sessionId,
          baseMs + i * AtMsStepMs,
          payload
        )
      )
    }
    envelopes

  /** Canonical JSONL lines: one envelope per line, zio-json `EventCodecs`.
    * Lines carry no trailing newline; the writer appends the final `\n`.
    */
  def jsonlLines(envelopes: Vector[EventEnvelope]): Vector[String] =
    envelopes.map(_.toJson)

  /** Strict inverse of [[jsonlLines]] (blank lines skipped): the first failing
    * line aborts with a `line N:` position; unknown discriminators, unknown
    * JobStatus names and malformed JSON are decode failures — never guesses.
    */
  def parseJsonl(lines: Vector[String]): Either[String, Vector[EventEnvelope]] =
    lines.zipWithIndex.foldLeft[Either[String, Vector[EventEnvelope]]](
      Right(Vector.empty)
    ) { case (acc, (line, idx)) =>
      acc.flatMap { parsed =>
        val trimmed = line.trim
        if trimmed.isEmpty then Right(parsed)
        else
          trimmed
            .fromJson[EventEnvelope]
            .left
            .map(err => s"line ${idx + 1}: $err")
            .map(parsed :+ _)
      }
    }

  /** Atomic JSONL write of the corpus (LIN5-A-01/02 drivers): parent
    * directories are created, content goes to a unique `.name…tmp` sibling,
    * then a move publishes the file — no partial file is ever observable at
    * `path` and no tmp sibling survives a completed call (ATOMIC_MOVE, with a
    * documented plain-move fallback for filesystems that reject it).
    */
  def writeJsonl(envelopes: Vector[EventEnvelope], path: Path): Path =
    writeLines(jsonlLines(envelopes), path)

  /** Atomic write of ready-made JSONL lines (used by the adversarial variants):
    * tmp file + move, parents created, returns the target path.
    */
  def writeLines(lines: Vector[String], path: Path): Path =
    val dir = Option(path.getParent).getOrElse(Path.of("."))
    Files.createDirectories(dir)
    val tmp = Files.createTempFile(dir, s".${path.getFileName}", ".tmp")
    val bytes =
      (if lines.isEmpty then "" else lines.mkString("", "\n", "\n"))
        .getBytes(StandardCharsets.UTF_8)
    Files.write(tmp, bytes)
    try
      Files.move(
        tmp,
        path,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE
      )
    catch
      case _: AtomicMoveNotSupportedException =>
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(tmp)
    path

  /** Adversarial variant 1 — corrupted file tail: canonical lines plus one
    * trailing line that is a strict PREFIX of the last real line (truncated
    * JSON). A strict prefix of a `{…}` document is never valid JSON (the
    * document ends at its outermost `}`, the last character of the line), so
    * the damage is exactly: unparseable last line, every preceding line intact.
    */
  def corruptedTail(envelopes: Vector[EventEnvelope]): Vector[String] =
    val lines = jsonlLines(envelopes)
    lines.lastOption match
      case None       => Vector("{")
      case Some(last) => lines :+ last.take(last.length / 2)

  /** Adversarial variant 2 — per-job seq gap: drops ONE envelope that has a
    * same-job successor (the first such in emission order), so the affected job
    * replays 1..n with exactly one missing seq while every other job stays
    * contiguous. The lines still decode — a seq validator, not the codec, must
    * catch this. A DETECTABLE gap needs a job with ≥ 2 events (the campaign
    * always has one); with none, the first envelope is dropped as the honest
    * best effort.
    */
  def withSeqGap(envelopes: Vector[EventEnvelope]): Vector[String] =
    val dropAt = envelopes.indices
      .find(i =>
        i + 1 < envelopes.size && envelopes(i).jobId == envelopes(i + 1).jobId
      )
      .getOrElse(0)
    jsonlLines(envelopes.patch(dropAt, Nil, 1))

  /** Adversarial variant 3 — wrong schemaVersion: every envelope is rewritten
    * to [[WrongSchemaVersion]]. The zio-json codec is deliberately PERMISSIVE
    * here (schemaVersion is an unvalidated Int field — pinned by the spec), so
    * the damage is exactly: decodable lines whose schemaVersion violates the
    * seam contract; a schema gate must reject them, the codec alone will not.
    */
  def wrongSchemaVersion(envelopes: Vector[EventEnvelope]): Vector[String] =
    jsonlLines(envelopes.map(_.copy(schemaVersion = WrongSchemaVersion)))

  private final val ModelA = "mock-model-a"
  private final val ModelB = "mock-model-b"
  private final val AgentA = "worker-a"
  private final val AgentB = "worker-b"
  private final val CallRoot1 = "call-root-1"
  private final val CallRoot2 = "call-root-2"
  private final val CallRoot3 = "call-root-3"
  private final val CallChild1a = "call-child1-1"
  private final val CallChild1b = "call-child1-2"
  private final val CallChild2a = "call-child2-1"

  /** Campaign epoch anchor (matches EventCodecSpec fixtures); `atMs` is
    * informational, stepped 250ms per emission index.
    */
  private final val BaseMs = 1760000000000L
  private final val AtMsStepMs = 250L

  /** Minimal immutable LCG (64-bit Knuth constants): the corpus needs spread,
    * not cryptographic quality. Pure state threading — the same seed always
    * replays the same numbers on any JVM.
    */
  private final case class Rng(state: Long):

    def next: (Long, Rng) =
      val s = state * 6364136223846793005L + 1442695040888963407L
      (s, Rng(s))

    /** Non-negative value in `[0, bound)`; `bound` must be positive. */
    def below(bound: Int): (Int, Rng) =
      val (v, r) = next
      (((v >>> 1) % bound.toLong).toInt, r)

  private object Rng:

    def apply(seed: Int): Rng = Rng(
      seed.toLong * 2862933555777941757L + 3037000493L
    )

  /** Model-round cost: reserved 50_000..950_000 micro-USD, observed 70..100% of
    * reserved, ~1 in 4 rounds honestly `unknown`.
    */
  private def usage(r: Rng): (UsageSnapshot, Rng) =
    val (micros, r1) = r.below(900_000)
    val reserved = micros.toLong + 50_000L
    val (pct, r2) = r1.below(31)
    val observed = reserved * (70 + pct) / 100
    val (flag, r3) = r2.below(4)
    (UsageSnapshot(MicroUsd(reserved), MicroUsd(observed), flag == 0), r3)

  private def durationMs(r: Rng): (Long, Rng) =
    val (d, r1) = r.below(4000)
    (250L + d.toLong, r1)

  private def delayMs(r: Rng): (Long, Rng) =
    val (d, r1) = r.below(5000)
    (1000L + d.toLong, r1)

  /** Bounded output tail: `shownMax` lines max kept, honest truncation counters
    * for what was cut.
    */
  private def tail(r: Rng, tool: String, shownMax: Int): (LineTail, Rng) =
    val (shown, r1) = r.below(shownMax + 1)
    val (cut, r2) = r1.below(3)
    (LineTail(sampleLines(tool).take(shown), cut, cut * 32L), r2)

  private def sampleLines(tool: String): Vector[String] = tool match
    case "bash" =>
      Vector(
        "total 48",
        "drwxr-xr-x  12 ci  ci   4096 src",
        "drwxr-xr-x   5 ci  ci   4096 modules"
      )
    case "edit" =>
      Vector(
        "hunk #1 succeeded at 41",
        "patch applied: 1 file changed, 2 insertions(+)"
      )
    case "search" =>
      Vector(
        "modules/app/Main.scala:12: TODO",
        "modules/runtime/events/hub.scala:88: TODO"
      )
    case other =>
      Vector(s"$other: no captured output")

end LineageCorpus
