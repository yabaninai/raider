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
import raider.core.trace.TraceEvent
import raider.runtime.events.EventEnvelope
import raider.runtime.lineage.reduce.LineageReducer

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.UUID
import scala.util.control.NonFatal
import zio.json.EncoderOps
import zio.json.ast.Json

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
      modelResponses: Map[Int, String],
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
          .write(runDir, graph, Vector.empty, None, modelResponses)
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

  /** Model responses retained by the session trace and the settled return
    * value. Tool arguments come from the actual ToolCallStarted trace events;
    * the final assistant response is the exact value returned by the chat run.
    */
  def responses(
      trace: Vector[TraceEvent],
      finalAnswer: String
  ): Map[Int, String] =
    val calls = trace
      .collect { case call: TraceEvent.ToolCallStarted => call }
      .groupBy(_.round)
    val text = trace
      .collect { case received: TraceEvent.TextReceived => received }
      .groupBy(_.round)
    val rounds = calls.keySet ++ text.keySet
    val captured = rounds.map { round =>
      val toolCalls = calls.getOrElse(round, Vector.empty).map { call =>
        Json.Obj(
          "id" -> Json.Str(call.callId),
          "name" -> Json.Str(call.tool),
          "arguments" -> Json.Str(call.argumentsJson)
        )
      }
      val response =
        if toolCalls.nonEmpty then
          Json.Obj("tool_calls" -> Json.Arr(toolCalls*)).toJson
        else text.getOrElse(round, Vector.empty).map(_.text).mkString
      round -> response
    }.toMap
    val finalRound = trace.collect { case started: TraceEvent.RoundStarted =>
      started.round
    }.maxOption
    finalRound.fold(captured)(round => captured.updated(round, finalAnswer))

end ChatLineageExport
