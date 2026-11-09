package raider.evalexport

/** Lineage reconstruction (RAI-040 acceptance EXPORT-01): the root ->
  * job -> attempt chains are rebuilt from the EXPORTED documents alone —
  * without the original input, without any journal. A consumer of the export
  * directory (e.g. Yabanin eval tooling) gets the same structural facts the
  * producer had, or a typed failure.
  *
  * This is also the executable part of the schema story: the reconstruction
  * reads every document through the validated wire decoders (closed enums,
  * sha256 shapes, explicit-null usage rules) and cross-checks the manifest's
  * coverage counts against the actual section records. It does NOT run the
  * JSON Schemas themselves (no validator library is available in this
  * environment; see contracts/eval-export/README.md — limitations).
  */
object Lineage:

  final case class ReconstructedJob(
      jobId: String,
      parentJobId: Option[String],
      label: String,
      attempts: Vector[String]
  )

  final case class ReconstructedLineage(
      rootId: String,
      jobs: Vector[ReconstructedJob],
      episodes: Int,
      modelCalls: Int,
      toolCalls: Int,
      verdicts: Int,
      reconciliations: Int
  )

  /** Rebuild the lineage from the bundle; every document is decoded with the
    * validating wire codecs first, so a tampered document fails here.
    */
  def reconstruct(bundle: ExportBundle): Either[model.ExportError, ReconstructedLineage] =
    for
      manifest        <- bundle.parseJson[wire.ManifestWire](ExportBundle.ManifestName)
      episodes        <- bundle.parseJsonl[wire.EpisodeWire](ExportBundle.EpisodesName)
      calls           <- bundle.parseJsonl[wire.CallWire](ExportBundle.CallsName)
      tools           <- bundle.parseJsonl[wire.ToolWire](ExportBundle.ToolsName)
      verdicts        <- bundle.parseJsonl[wire.VerdictWire](ExportBundle.VerdictsName)
      reconciliations <- bundle.parseJsonl[wire.ReconciliationWire](ExportBundle.ReconciliationName)
      _               <- checkStructure(manifest, episodes, calls, tools, verdicts, reconciliations)
    yield ReconstructedLineage(
      rootId = manifest.lineage.rootId,
      jobs = manifest.lineage.jobs.map(j => ReconstructedJob(j.jobId, j.parentJobId, j.label, j.attempts)),
      episodes = episodes.size,
      modelCalls = calls.size,
      toolCalls = tools.size,
      verdicts = verdicts.size,
      reconciliations = reconciliations.size
    )

  private def checkStructure(
      manifest: wire.ManifestWire,
      episodes: Vector[wire.EpisodeWire],
      calls: Vector[wire.CallWire],
      tools: Vector[wire.ToolWire],
      verdicts: Vector[wire.VerdictWire],
      reconciliations: Vector[wire.ReconciliationWire]
  ): Either[model.ExportError, Unit] =
    val jobs = manifest.lineage.jobs
    for
      _ <-
        val roots = jobs.count(_.parentJobId.isEmpty)
        if roots == 1 then Right(()) else Left(model.ExportError.MultipleRootJobs(roots))
      _ <- jobs.zipWithIndex.foldLeft[Either[model.ExportError, Unit]](Right(())) { (acc, pair) =>
        for
          _ <- acc
          (job, idx) = pair
          _ <- job.parentJobId match
            case None => Right(())
            case Some(parent) =>
              val parentIdx = jobs.indexWhere(_.jobId == parent)
              if parentIdx < 0 then
                Left(model.ExportError.DanglingReference(s"job '${job.jobId}'", job.jobId, parent, "parent job"))
              else if parentIdx >= idx then Left(model.ExportError.ParentNotEarlier(job.jobId, parent))
              else Right(())
        yield ()
      }
      _ <- unique(jobs.map(_.jobId), "lineage job")
      chain = attemptChain(jobs)
      _ <- unique(chain.map(_._1), "lineage attempt")
      _ <- episodes.foldLeft[Either[model.ExportError, Unit]](Right(())) { (acc, e) =>
        acc.flatMap(_ => resolveAttempt(e.attemptId, e.jobId, s"episode '${e.episodeId}'", chain))
      }
      _ <- calls.foldLeft[Either[model.ExportError, Unit]](Right(())) { (acc, c) =>
        acc.flatMap(_ => resolveAttempt(c.attemptId, c.jobId, s"model call '${c.callId}'", chain))
      }
      _ <- tools.foldLeft[Either[model.ExportError, Unit]](Right(())) { (acc, t) =>
        acc.flatMap(_ => resolveAttempt(t.attemptId, t.jobId, s"tool call '${t.toolCallId}'", chain))
      }
      _ <- verdicts.foldLeft[Either[model.ExportError, Unit]](Right(())) { (acc, v) =>
        acc.flatMap(_ => resolveAttempt(v.attemptId, v.jobId, s"verdict '${v.verdictId}'", chain))
      }
      _ <- coverageMatches(manifest, episodes.size, calls.size, tools.size, verdicts.size)
      _ <- reconciliations.foldLeft[Either[model.ExportError, Unit]](Right(())) { (acc, r) =>
        acc.flatMap { _ =>
          if calls.exists(_.callId == r.callId) then Right(())
          else
            Left(
              model.ExportError.DanglingReference(
                s"reconciliation '${r.reconciliationId}'",
                r.reconciliationId,
                r.callId,
                "model call"
              )
            )
        }
      }
    yield ()

  /** attemptId -> jobId over the manifest lineage (parents before children,
    * attempts listed per job).
    */
  private def attemptChain(jobs: Vector[wire.JobLineageWire]): Vector[(String, String)] =
    jobs.flatMap(j => j.attempts.map(_ -> j.jobId))

  private def resolveAttempt(
      attemptId: String,
      jobId: String,
      from: String,
      chain: Vector[(String, String)]
  ): Either[model.ExportError, Unit] =
    chain.find(_._1 == attemptId) match
      case None =>
        Left(model.ExportError.DanglingReference(from, attemptId, attemptId, "attempt"))
      case Some((_, chainJob)) if chainJob != jobId =>
        Left(model.ExportError.AttemptJobMismatch(attemptId, jobId, chainJob))
      case _ => Right(())

  private def coverageMatches(
      manifest: wire.ManifestWire,
      episodes: Int,
      calls: Int,
      tools: Int,
      verdicts: Int
  ): Either[model.ExportError, Unit] =
    val cov = manifest.coverage
    if cov.episodes != episodes then
      Left(model.ExportError.IntegrityMismatch("coverage.episodes", cov.episodes.toString, episodes.toString))
    else if cov.modelCalls != calls then
      Left(model.ExportError.IntegrityMismatch("coverage.modelCalls", cov.modelCalls.toString, calls.toString))
    else if cov.toolCalls != tools then
      Left(model.ExportError.IntegrityMismatch("coverage.toolCalls", cov.toolCalls.toString, tools.toString))
    else if cov.verdicts != verdicts then
      Left(model.ExportError.IntegrityMismatch("coverage.verdicts", cov.verdicts.toString, verdicts.toString))
    else Right(())

  private def unique(ids: Vector[String], kind: String): Either[model.ExportError, Unit] =
    ids.foldLeft[Either[model.ExportError, Set[String]]](Right(Set.empty)) { (acc, id) =>
      acc.flatMap(seen =>
        if seen.contains(id) then Left(model.ExportError.DuplicateId(kind, id))
        else Right(seen + id)
      )
    }.map(_ => ())

end Lineage
