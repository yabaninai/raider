package raider.evalexport

import zio.json.*

/** Pure exporter (RAI-040): a validated [[model.EvalRunInput]] becomes an
  * [[ExportBundle]] of six canonical JSON documents. No IO, no clock, no
  * hidden state: the same input always yields byte-identical documents
  * (deterministic record order = input order; deterministic verdict ids
  * v-1..v-n; digests over pinned bytes).
  *
  * Honesty invariants implemented here (card acceptance EXPORT-02):
  *   - verdicts are exported ONLY from actual [[model.VerifierResult]]
  *     records; episodes whose declared verifier produced nothing export
  *     `pending`, episodes without a declared verifier export `absent` —
  *     both with explicit reasons, never a fabricated verdict;
  *   - an export's honesty is a straight pass-through of the input's
  *     honesty: the input MUST NOT contain a verdict for an episode that did
  *     not expect one ([[model.ExportError.VerifierMismatch]]);
  *   - raw tool args never appear in any document; only their sha256 digest
  *     (or explicitly pinned redacted-safe args) travels;
  *   - env references are names only; unknown usage prices stay null.
  *
  * Cross-references are validated first; a broken input produces NO
  * documents (typed [[model.ExportError]], all-or-nothing).
  */
object Exporter:

  import model.*
  import model.ExportError.*

  /** Validate the input and build all six documents. */
  def exportRun(input: EvalRunInput): Either[ExportError, ExportBundle] =
    for
      _ <- validate(input)
      runId            = input.run.runId
      jobById          = index(input.jobs.map(j => j.jobId -> j))
      attemptById      = index(input.attempts.map(a => a.attemptId -> a))
      episodeById      = index(input.episodes.map(e => e.episodeId -> e))
      verdictsByEpisode = input.verdicts.groupBy(_.episodeId)
      episodeWires <- traverse(input.episodes)(episodeWire(runId, attemptById, verdictsByEpisode))
      callWires    <- traverse(input.calls)(callWire(runId, attemptById))
      toolWires    <- traverse(input.tools)(toolWire(runId, attemptById))
      verdictWires <- traverse(input.verdicts.zipWithIndex)(verdictWire(runId, episodeById, attemptById))
      episodesFile = ExportFile(ExportBundle.EpisodesName, jsonl(episodeWires.map(_.toJson)))
      callsFile    = ExportFile(ExportBundle.CallsName, jsonl(callWires.map(_.toJson)))
      toolsFile    = ExportFile(ExportBundle.ToolsName, jsonl(toolWires.map(_.toJson)))
      verdictsFile = ExportFile(ExportBundle.VerdictsName, jsonl(verdictWires.map(_.toJson)))
      manifest <- manifestWire(
        input,
        episodeWires,
        callWires,
        toolWires,
        verdictWires,
        episodesSha = episodesFile.sha256,
        callsSha = callsFile.sha256,
        toolsSha = toolsFile.sha256,
        verdictsSha = verdictsFile.sha256
      )
      manifestFile = ExportFile(ExportBundle.ManifestName, manifest.toJson + "\n")
    yield ExportBundle(
      Vector(
        manifestFile,
        episodesFile,
        callsFile,
        toolsFile,
        verdictsFile,
        ExportFile(ExportBundle.ReconciliationName, "")
      )
    )

  // -- validation -------------------------------------------------------------

  /** Full input validation (all-or-nothing). Deterministic order: run shape,
    * id uniqueness, lineage, references, verifier honesty, usage honesty,
    * tool honesty.
    */
  def validate(input: EvalRunInput): Either[ExportError, Unit] =
    for
      _ <- validateRun(input.run)
      _ <- validateUnique(input)
      _ <- validateLineage(input)
      _ <- validateReferences(input)
      _ <- validateVerifiers(input)
      _ <- validateUsage(input)
      _ <- validateTools(input)
    yield ()

  private def validateRun(run: RunInfo): Either[ExportError, Unit] =
    for
      _ <- if run.generator.name == "raider" then Right(()) else Left(BadGeneratorName(run.generator.name))
      _ <- nonEmpty("runId", run.runId)
      _ <- nonEmpty("generator.version", run.generator.version)
      _ <- nonEmpty("generator.scalaVersion", run.generator.scalaVersion)
      _ <- nonEmpty("generator.jdkVersion", run.generator.jdkVersion)
      _ <- nonEmpty("model", run.model)
      _ <- if run.startedAtMs >= 0 then Right(()) else Left(EmptyField("startedAtMs", "run"))
    yield ()

  private def validateUnique(input: EvalRunInput): Either[ExportError, Unit] =
    for
      _ <- unique(input.jobs.map(_.jobId), "job")
      _ <- unique(input.attempts.map(_.attemptId), "attempt")
      _ <- unique(input.episodes.map(_.episodeId), "episode")
      _ <- unique(input.calls.map(_.callId), "model call")
      _ <- unique(input.tools.map(_.toolCallId), "tool call")
    yield ()

  private def validateLineage(input: EvalRunInput): Either[ExportError, Unit] =
    for
      _ <- input.jobs.zipWithIndex.foldLeft[Either[ExportError, Unit]](Right(())) { (acc, pair) =>
        for
          _ <- acc
          (job, idx) = pair
          _ <- job.parentJobId match
            case None => Right(())
            case Some(parent) =>
              val parentIdx = input.jobs.indexWhere(_.jobId == parent)
              if parentIdx < 0 then Left(DanglingReference(s"job '${job.jobId}'", job.jobId, parent, "parent job"))
              else if parentIdx >= idx then Left(ParentNotEarlier(job.jobId, parent))
              else Right(())
        yield ()
      }
      _ <- input.attempts.foldLeft[Either[ExportError, Unit]](Right(())) { (acc, a) =>
        for
          _ <- acc
          _ <- nonEmpty("attemptId", a.attemptId)
          _ <-
            if input.jobs.exists(_.jobId == a.jobId) then Right(())
            else Left(DanglingReference(s"attempt '${a.attemptId}'", a.attemptId, a.jobId, "job"))
        yield ()
      }
      _ <-
        val roots = input.jobs.count(_.parentJobId.isEmpty)
        if roots == 1 then Right(()) else Left(MultipleRootJobs(roots))
    yield ()

  private def validateReferences(input: EvalRunInput): Either[ExportError, Unit] =
    for
      _ <- input.episodes.foldLeft[Either[ExportError, Unit]](Right(())) { (acc, ep) =>
        for
          _ <- acc
          _ <- nonEmpty("episodeId", ep.episodeId)
          _ <- validateCompletion(ep)
          _ <-
            if input.attempts.exists(_.attemptId == ep.attemptId) then Right(())
            else Left(DanglingReference(s"episode '${ep.episodeId}'", ep.episodeId, ep.attemptId, "attempt"))
          _ <- validateEvidence(ep.safety, ep.episodeId)
          _ <- validateEvidence(ep.protocol, ep.episodeId)
        yield ()
      }
      _ <- duplicateEpisodeAttempts(input)
      _ <- attemptsHaveEpisodes(input)
      _ <- input.calls.foldLeft[Either[ExportError, Unit]](Right(())) { (acc, c) =>
        for
          _ <- acc
          _ <- nonEmpty("callId", c.callId)
          _ <-
            if input.attempts.exists(_.attemptId == c.attemptId) then Right(())
            else Left(DanglingReference(s"model call '${c.callId}'", c.callId, c.attemptId, "attempt"))
        yield ()
      }
      _ <- input.tools.foldLeft[Either[ExportError, Unit]](Right(())) { (acc, t) =>
        for
          _ <- acc
          _ <- nonEmpty("toolCallId", t.toolCallId)
          _ <- nonEmpty("tool", t.tool)
          _ <-
            if input.attempts.exists(_.attemptId == t.attemptId) then Right(())
            else Left(DanglingReference(s"tool call '${t.toolCallId}'", t.toolCallId, t.attemptId, "attempt"))
          _ <- t.callId match
            case Some(cid) =>
              if input.calls.exists(_.callId == cid) then Right(())
              else Left(DanglingReference(s"tool call '${t.toolCallId}'", t.toolCallId, cid, "model call"))
            case None => Right(())
          _ <- validateResultReasons(t)
        yield ()
      }
      _ <- input.verdicts.foldLeft[Either[ExportError, Unit]](Right(())) { (acc, v) =>
        for
          _ <- acc
          _ <- nonEmpty("verifierId", v.verifierId)
          _ <- nonEmpty("evidenceKind", v.evidenceKind)
          _ <- shaHex("evidenceSha256", v.evidenceSha256)
          _ <-
            if input.episodes.exists(_.episodeId == v.episodeId) then Right(())
            else Left(DanglingReference(s"verdict for '${v.episodeId}'", v.episodeId, v.episodeId, "episode"))
        yield ()
      }
    yield ()

  private def validateVerifiers(input: EvalRunInput): Either[ExportError, Unit] =
    input.episodes.foldLeft[Either[ExportError, Unit]](Right(())) { (acc, ep) =>
      for
        _ <- acc
        _ <- (ep.verifierExpected, ep.verifierId) match
          case (true, Some(id))  => if id.isEmpty then Left(VerifierMismatch(ep.episodeId, "declared verifierId is empty")) else Right(())
          case (true, None)      => Left(VerifierMismatch(ep.episodeId, "verifier expected but no verifierId declared"))
          case (false, Some(_))  => Left(VerifierMismatch(ep.episodeId, "verifierId declared but no verifier expected"))
          case (false, None)     => Right(())
        _ <- input.verdicts
          .filter(_.episodeId == ep.episodeId)
          .foldLeft[Either[ExportError, Unit]](Right(())) { (vAcc, _) =>
            vAcc.flatMap(_ =>
              if ep.verifierExpected then Right(())
              else Left(VerifierMismatch(ep.episodeId, "verdict present but episode did not expect a verifier"))
            )
          }
      yield ()
    }

  private def validateUsage(input: EvalRunInput): Either[ExportError, Unit] =
    input.calls.foldLeft[Either[ExportError, Unit]](Right(())) { (acc, c) =>
      for
        _ <- acc
        _ <- wire.UsageWire.validate(wire.UsageWire.fromUsage(c.usage)).left.map(msg => BadUsage(s"call '${c.callId}': $msg"))
      yield ()
    }

  private def validateTools(input: EvalRunInput): Either[ExportError, Unit] =
    input.tools.foldLeft[Either[ExportError, Unit]](Right(())) { (acc, t) =>
      for
        _ <- acc
        _ <- t.envRefs.foldLeft[Either[ExportError, Unit]](Right(())) { (eAcc, e) =>
          eAcc.flatMap(_ =>
            if envRefPattern.matches(e) then Right(())
            else Left(BadEnvRef(e))
          )
        }
        _ <- t.artifacts.foldLeft[Either[ExportError, Unit]](Right(())) { (aAcc, a) =>
          for
            _ <- aAcc
            _ <- nonEmpty("artifactId", a.artifactId)
            _ <- nonEmpty("mediaType", a.mediaType)
            _ <- shaHex("artifacts.sha256", a.sha256)
            _ <- if a.sizeBytes >= 0 then Right(()) else Left(EmptyField("sizeBytes", s"artifact '${a.artifactId}'"))
          yield ()
        }
        _ <- t.argsRedactedSafe match
          case Some(text) => parseJsonObject(text, t.toolCallId).map(_ => ())
          case None       => Right(())
      yield ()
    }

  private def validateCompletion(ep: EpisodeInput): Either[ExportError, Unit] = ep.completion match
    case Completion.Completed(_, atMs) =>
      if atMs >= 0 then Right(()) else Left(EmptyField("atMs", s"episode '${ep.episodeId}'"))
    case Completion.Partial(_, reason, atMs) =>
      for
        _ <- if reason.nonEmpty then Right(()) else Left(EmptyField("reason", s"partial episode '${ep.episodeId}'"))
        _ <- if atMs >= 0 then Right(()) else Left(EmptyField("atMs", s"episode '${ep.episodeId}'"))
      yield ()

  private def validateEvidence(ev: Option[OutcomeEvidence], episodeId: String): Either[ExportError, Unit] =
    ev match
      case None    => Right(())
      case Some(e) => e.evidenceSha256 match
        case None    => Right(())
        case Some(h) => shaHex(s"evidenceSha256 of episode '$episodeId'", h)

  private def validateResultReasons(t: ToolCallInput): Either[ExportError, Unit] = t.result match
    case ToolResult.Failed(reason)     => checkNonEmpty(reason, "reason", t)
    case ToolResult.Denied(denyReason) => checkNonEmpty(denyReason, "denyReason", t)
    case ToolResult.Uncertain(reason)  => checkNonEmpty(reason, "reason", t)
    case _                             => Right(())

  private def checkNonEmpty(value: String, field: String, t: ToolCallInput): Either[ExportError, Unit] =
    if value.nonEmpty then Right(()) else Left(EmptyField(field, s"tool call '${t.toolCallId}'"))

  private def duplicateEpisodeAttempts(input: EvalRunInput): Either[ExportError, Unit] =
    val seen = scala.collection.mutable.Set.empty[String]
    input.episodes.foldLeft[Either[ExportError, Unit]](Right(())) { (acc, ep) =>
      for
        _ <- acc
        _ <-
          if seen.add(ep.attemptId) then Right(())
          else Left(DuplicateId("episode attempt", ep.attemptId))
      yield ()
    }

  /** 1:1 episode-attempt: every attempt must carry exactly one episode
    * record, otherwise its execution evidence would be silently invisible in
    * the export (an omission is not honesty).
    */
  private def attemptsHaveEpisodes(input: EvalRunInput): Either[ExportError, Unit] =
    val withEpisode = input.episodes.map(_.attemptId).toSet
    input.attempts.foldLeft[Either[ExportError, Unit]](Right(())) { (acc, a) =>
      acc.flatMap { _ =>
        if withEpisode.contains(a.attemptId) then Right(())
        else Left(AttemptWithoutEpisode(a.attemptId))
      }
    }

  // -- wire assembly (total after validate) ------------------------------------

  private def episodeWire(
      runId: String,
      attemptById: Map[String, AttemptRecord],
      verdictsByEpisode: Map[String, Vector[VerifierResult]]
  )(ep: EpisodeInput): Either[ExportError, wire.EpisodeWire] =
    for
      attempt <- lookup(ep.attemptId, attemptById, s"episode '${ep.episodeId}'", "attempt")
      job = attempt.jobId
      hasVerdict = verdictsByEpisode.get(ep.episodeId).exists(_.nonEmpty)
      verifierState =
        if hasVerdict then wire.VerifierWire("produced", ep.verifierId, None)
        else if ep.verifierExpected then
          wire.VerifierWire("pending", ep.verifierId, Some(Reasons.verifierExpectedButMissing))
        else wire.VerifierWire("absent", None, Some(Reasons.noVerifierDeclared))
      outcomes = Vector(
        executionOutcome(ep.completion),
        taskOutcome(ep, verdictsByEpisode.getOrElse(ep.episodeId, Vector.empty)),
        evidenceOutcome("safety", ep.safety),
        evidenceOutcome("protocol", ep.protocol)
      )
    yield wire.EpisodeWire(
      schemaVersion,
      runId,
      ep.episodeId,
      ep.attemptId,
      job,
      completionWire(ep.completion),
      verifierState,
      outcomes
    )

  private def callWire(
      runId: String,
      attemptById: Map[String, AttemptRecord]
  )(c: ModelCallInput): Either[ExportError, wire.CallWire] =
    for
      attempt <- lookup(c.attemptId, attemptById, s"model call '${c.callId}'", "attempt")
    yield wire.CallWire(
      schemaVersion,
      runId,
      c.callId,
      c.attemptId,
      attempt.jobId,
      c.round,
      c.model,
      c.atMs,
      wire.UsageWire.fromUsage(c.usage)
    )

  private def toolWire(
      runId: String,
      attemptById: Map[String, AttemptRecord]
  )(t: ToolCallInput): Either[ExportError, wire.ToolWire] =
    for
      attempt <- lookup(t.attemptId, attemptById, s"tool call '${t.toolCallId}'", "attempt")
      (outcome, reason, denyReason) = outcomeOf(t.result)
      digest = t.argsJsonText
        .map(Digests.sha256HexUtf8)
        .orElse(t.argsRedactedSafe.map(Digests.sha256HexUtf8))
      redacted <- t.argsRedactedSafe match
        case Some(text) => parseJsonObject(text, t.toolCallId).map(Some(_))
        case None       => Right(None)
      artifacts = t.artifacts.map(a => wire.ArtifactWire(a.artifactId, a.sha256, a.mediaType, a.sizeBytes))
    yield wire.ToolWire(
      schemaVersion,
      runId,
      t.toolCallId,
      t.attemptId,
      attempt.jobId,
      t.tool,
      t.callId,
      t.atMs,
      outcome,
      reason,
      denyReason,
      digest,
      redacted,
      t.envRefs,
      artifacts
    )

  private def verdictWire(
      runId: String,
      episodeById: Map[String, EpisodeInput],
      attemptById: Map[String, AttemptRecord]
  )(pair: (VerifierResult, Int)): Either[ExportError, wire.VerdictWire] =
    val (v, idx) = pair
    for
      ep      <- lookup(v.episodeId, episodeById, s"verdict '${idx + 1}'", "episode")
      attempt <- lookup(ep.attemptId, attemptById, s"episode '${ep.episodeId}'", "attempt")
    yield wire.VerdictWire(
      schemaVersion,
      runId,
      s"v-${idx + 1}",
      v.episodeId,
      attempt.attemptId,
      attempt.jobId,
      v.verifierId,
      lower(v.verdict),
      v.atMs,
      v.evidenceSha256,
      v.evidenceKind,
      v.note
    )

  private def manifestWire(
      input: EvalRunInput,
      episodeWires: Vector[wire.EpisodeWire],
      callWires: Vector[wire.CallWire],
      toolWires: Vector[wire.ToolWire],
      verdictWires: Vector[wire.VerdictWire],
      episodesSha: String,
      callsSha: String,
      toolsSha: String,
      verdictsSha: String
  ): Either[ExportError, wire.ManifestWire] =
    Right {
      val attemptsByJob = input.attempts.groupMap(_.jobId)(_.attemptId)
      val jobs = input.jobs.map(j =>
        wire.JobLineageWire(j.jobId, j.parentJobId, j.label, attemptsByJob.getOrElse(j.jobId, Vector.empty))
      )
      val completed = input.episodes.forall { ep =>
        ep.completion match
          case Completion.Completed(_, _)   => true
          case Completion.Partial(_, _, _) => false
      }
      val runStatus = if input.run.finishedAtMs.isDefined && completed then "complete" else "partial"
      val expected  = input.episodes.count(_.verifierExpected)
      val produced  = input.episodes.count { ep =>
        input.verdicts.exists(_.episodeId == ep.episodeId)
      }
      val usage = wire.UsageCoverageWire(
        calls = callWires.size,
        withObserved = callWires.count(_.usage.observedMicroUsd.isDefined),
        unknownPrice = callWires.count(_.usage.unknown)
      )
      wire.ManifestWire(
        schemaVersion,
        wire.GeneratorWire(
          input.run.generator.name,
          input.run.generator.version,
          input.run.generator.scalaVersion,
          input.run.generator.jdkVersion
        ),
        input.run.runId,
        input.run.model,
        runStatus,
        input.run.startedAtMs,
        input.run.finishedAtMs,
        wire.LineageWire(input.run.runId, jobs),
        wire.CoverageWire(
          episodes = episodeWires.size,
          modelCalls = callWires.size,
          toolCalls = toolWires.size,
          verdicts = verdictWires.size,
          verifier = wire.VerifierCoverageWire(
            expected = expected,
            produced = produced,
            pending = expected - produced,
            absent = input.episodes.size - expected
          ),
          usage = usage
        ),
        wire.IntegrityWire(episodesSha, callsSha, toolsSha, verdictsSha)
      )
    }

  // -- helpers -----------------------------------------------------------------

  /** Canonical reasons for honest absence (shared with the wire validators). */
  private object Reasons:
    val verifierExpectedButMissing = wire.modelExport.verifierExpectedButMissing
    val noVerifierDeclared         = wire.modelExport.noVerifierDeclared
    val noEvidenceRecorded         = wire.modelExport.noEvidenceRecorded

  private val schemaVersion = model.schemaVersion

  private val envRefPattern = "^[A-Za-z_][A-Za-z0-9_]*$".r

  private def jsonl(lines: Vector[String]): String = lines.map(_ + "\n").mkString

  private def lower(e: scala.reflect.Enum): String = e.toString.toLowerCase

  private def index[A](pairs: Vector[(String, A)]): Map[String, A] =
    pairs.toMap // uniqueness already validated

  private def unique(ids: Vector[String], kind: String): Either[ExportError, Unit] =
    ids.foldLeft[Either[ExportError, Set[String]]](Right(Set.empty)) { (acc, id) =>
      acc.flatMap(seen =>
        if seen.contains(id) then Left(DuplicateId(kind, id))
        else Right(seen + id)
      )
    }.map(_ => ())

  private def lookup[A](id: String, m: Map[String, A], from: String, kind: String): Either[ExportError, A] =
    m.get(id).toRight(DanglingReference(from, id, id, kind))

  private def traverse[A, B](xs: Vector[A])(f: A => Either[ExportError, B]): Either[ExportError, Vector[B]] =
    xs.foldLeft[Either[ExportError, Vector[B]]](Right(Vector.empty))((acc, x) =>
      acc.flatMap(done => f(x).map(done :+ _))
    )

  private def nonEmpty(field: String, where: String): Either[ExportError, Unit] =
    if where.nonEmpty then Right(()) else Left(EmptyField(field, field))

  private def shaHex(field: String, value: String): Either[ExportError, Unit] =
    if Digests.isSha256Hex(value) then Right(())
    else Left(BadSha256(field, value))

  private def parseJsonObject(text: String, toolCallId: String): Either[ExportError, zio.json.ast.Json] =
    text.fromJson[zio.json.ast.Json] match
      case Left(msg) =>
        Left(BadToolArgs(toolCallId, s"argsRedactedSafe is not valid JSON: $msg"))
      case Right(j: zio.json.ast.Json.Obj) => Right(j)
      case Right(_) =>
        Left(BadToolArgs(toolCallId, "argsRedactedSafe must be a JSON object"))

  private def completionWire(c: Completion): wire.CompletionWire = c match
    case Completion.Completed(outcome, atMs) =>
      wire.CompletionWire("completed", Some(lower(outcome)), None, None, atMs)
    case Completion.Partial(terminal, reason, atMs) =>
      wire.CompletionWire("partial", None, Some(lower(terminal)), Some(reason), atMs)

  private def executionOutcome(c: Completion): wire.OutcomeWire = c match
    case Completion.Completed(ExecutionStatus.Succeeded, _) => wire.OutcomeWire("execution", "pass", None, None)
    case Completion.Completed(ExecutionStatus.Failed, _)    => wire.OutcomeWire("execution", "fail", None, None)
    case Completion.Partial(terminal, reason, _)            =>
      wire.OutcomeWire("execution", lower(terminal), Some(reason), None)

  private def taskOutcome(ep: EpisodeInput, verdicts: Vector[VerifierResult]): wire.OutcomeWire =
    verdicts.headOption match
      case Some(v) => wire.OutcomeWire("task", lower(v.verdict), v.note, Some(v.evidenceSha256))
      case None =>
        val reason =
          if ep.verifierExpected then Reasons.verifierExpectedButMissing else Reasons.noVerifierDeclared
        wire.OutcomeWire("task", "missing", Some(reason), None)

  private def evidenceOutcome(kind: String, ev: Option[OutcomeEvidence]): wire.OutcomeWire = ev match
    case Some(e) => wire.OutcomeWire(kind, lower(e.status), e.reason, e.evidenceSha256)
    case None    => wire.OutcomeWire(kind, "missing", Some(Reasons.noEvidenceRecorded), None)

  private def outcomeOf(r: ToolResult): (String, Option[String], Option[String]) = r match
    case ToolResult.Succeeded          => ("succeeded", None, None)
    case ToolResult.Failed(reason)     => ("failed", Some(reason), None)
    case ToolResult.Denied(denyReason) => ("denied", None, Some(denyReason))
    case ToolResult.Cancelled          => ("cancelled", None, None)
    case ToolResult.Timeout            => ("timeout", None, None)
    case ToolResult.Uncertain(reason)  => ("uncertain", Some(reason), None)

end Exporter
