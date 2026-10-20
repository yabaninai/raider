package raider.cli.chat

import raider.cli.lineage.{
  CatalogScope,
  CatalogStore,
  EnvPinning,
  LineageCatalog,
  PinnedSummary,
  ToolchainPin
}
import raider.cli.run.{EventExport, RunLineageArtifacts}
import raider.core.JobId
import raider.runtime.events.EventEnvelope
import raider.runtime.lineage.reduce.LineageReducer

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.UUID
import scala.util.control.NonFatal

/** Publishes one completed chat turn through the existing event and lineage
  * exporters. Each turn has an isolated run directory and a Chat catalog scope.
  * The captured stream must already contain the job manager's real terminal;
  * strict reduction refuses an incomplete or contradictory stream, and this
  * publisher never fabricates a terminal marker.
  */
private[chat] object ChatLineageExport:

  def publish(
      runsRoot: Path,
      jobId: JobId,
      events: Vector[EventEnvelope],
      model: Option[String]
  ): Either[String, Path] =
    val safeJobId =
      jobId.value.filter(c => c.isLetterOrDigit || c == '-' || c == '_')
    val runDir = runsRoot.resolve(
      s"work-chat-${safeJobId.take(48)}-${UUID.randomUUID().toString}"
    )
    try
      Files.createDirectories(runDir)
      val eventsPath = runDir.resolve("events.jsonl")
      val tmp = Files.createTempFile(runDir, ".events-", ".tmp")
      try
        Files.writeString(tmp, EventExport.jsonl(events), UTF_8)
        Files.move(
          tmp,
          eventsPath,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE
        )
      finally Files.deleteIfExists(tmp)
      for
        graph <- LineageReducer.reduce(events).left.map(_.toString)
        _ <- RunLineageArtifacts
          .write(runDir, graph, Vector.empty, None)
          .left
          .map(_.toString)
        _ <- ToolchainPin.write(runDir, model).left.map(identity)
        entries <- LineageCatalog.scan(runsRoot).left.map(_.toString)
        own = entries.filter(
          _.path.toAbsolutePath.normalize == runDir.toAbsolutePath.normalize
        )
        _ <- Either.cond(
          own.nonEmpty,
          (),
          "chat run did not reduce into the catalog"
        )
        records = CatalogStore.recordsOf(
          own,
          Some(PinnedSummary.of(EnvPinning.currentEnv())),
          CatalogScope.Chat
        )
        _ <- CatalogStore
          .append(CatalogStore.catalogFile(runsRoot), records)
          .left
          .map(_.toString)
      yield runDir
    catch case NonFatal(error) => Left(String.valueOf(error.getMessage))

end ChatLineageExport
