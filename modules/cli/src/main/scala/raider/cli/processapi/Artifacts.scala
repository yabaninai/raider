package raider.cli.processapi

import raider.core.ids.ArtifactId
import raider.core.result.{ArtifactEntry, RunResult}
import zio.json.EncoderOps
import zio.{UIO, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.UUID

/** RAI-201 invocation artifacts (ADR-037 §4; ci-runtime §7; fixture
  * `normal-handshake-run-terminal`): `run.json`, `result.json`, `events.jsonl`
  * and `summary.md` are written into the invocation's work dir, then hashed
  * into `artifacts.json` (the manifest excludes itself). The terminal frame's
  * artifact refs are derived from the SAME manifest — bounded references that
  * provably point at existing files on disk.
  *
  * events.jsonl itself is streamed by [[raider.cli.run.EventsJsonlWriter]]
  * during the run; this writer only records its honest size in the manifest. An
  * unwritable artifact environment fails the run exactly like the headless
  * runner's late artifact write — IO failures are defects, absorbed by the
  * session into a truthful bounded-cleanup terminal.
  */
object Artifacts:

  final case class ManifestFile(
      name: String,
      sha256: String,
      mediaType: String,
      sizeBytes: Long
  )

  final case class Written(
      dir: Path,
      files: Vector[ManifestFile],
      entries: Vector[ArtifactEntry]
  )

  /** Writes run.json / result.json / summary.md and the artifacts.json manifest
    * over all four canonical files. run.json's own artifact list covers the
    * other three canonical files (self-references are excluded); the manifest
    * and the terminal frame's refs cover all four.
    */
  def write(
      workDir: Path,
      runResult: RunResult,
      resultPayload: zio.json.ast.Json,
      eventsLines: Long
  ): ZIO[Any, Nothing, Written] =
    ZIO.attempt {
      Files.createDirectories(workDir)
      val resultJson = resultPayload.toJson
      val summary = summaryText(runResult, eventsLines)
      writeString(workDir.resolve("result.json"), resultJson)
      writeString(workDir.resolve("summary.md"), summary)

      def entryOf(f: ManifestFile): ArtifactEntry =
        ArtifactEntry(
          artifactId = ArtifactId(UUID.randomUUID().toString),
          sha256 = f.sha256,
          mediaType = f.mediaType,
          sizeBytes = f.sizeBytes,
          path = f.name
        )

      val nonSelf = Vector(
        ManifestFile(
          "result.json",
          sha256(resultJson),
          "application/json",
          utf8Size(resultJson)
        ),
        ManifestFile(
          "events.jsonl",
          sha256Of(workDir.resolve("events.jsonl")),
          "application/x-ndjson",
          sizeOf(workDir.resolve("events.jsonl"))
        ),
        ManifestFile(
          "summary.md",
          sha256(summary),
          "text/markdown",
          utf8Size(summary)
        )
      )
      val runJson =
        runResult.copy(artifacts = nonSelf.map(entryOf).toList).toJson
      writeString(workDir.resolve("run.json"), runJson)

      val files = ManifestFile(
        "run.json",
        sha256(runJson),
        "application/json",
        utf8Size(runJson)
      ) +: nonSelf
      val manifestJson =
        zio.json.ast.Json.Obj(
          "schemaVersion" -> zio.json.ast.Json.Num(1),
          "files" -> zio.json.ast.Json.Arr(
            files.map { f =>
              zio.json.ast.Json.Obj(
                "name" -> zio.json.ast.Json.Str(f.name),
                "sha256" -> zio.json.ast.Json.Str(f.sha256),
                "mediaType" -> zio.json.ast.Json.Str(f.mediaType),
                "sizeBytes" -> zio.json.ast.Json.Num(f.sizeBytes)
              )
            }*
          )
        )
      writeString(workDir.resolve("artifacts.json"), manifestJson.toJson)

      Written(workDir, files, files.map(entryOf))
    }.orDie

  private def writeString(path: Path, content: String): Unit =
    Files.writeString(path, content, UTF_8)

  private def summaryText(runResult: RunResult, eventsLines: Long): String =
    val passed = runResult.checks.count(
      _.outcome == raider.core.result.CheckOutcome.Passed
    )
    val lines = new scala.collection.mutable.ListBuffer[String]()
    lines += "# process-api invocation"
    lines += ""
    lines += s"- rootId: `${runResult.rootId.value}`"
    lines += s"- execution status: ${runResult.status}"
    lines += s"- task verdict: ${runResult.taskVerdict.getOrElse("unavailable")}"
    lines += s"- exit code: ${runResult.exitCode}"
    lines += s"- checks: $passed/${runResult.checks.size} passed"
    lines += s"- events exported: $eventsLines"
    runResult.error.foreach(e =>
      lines += s"- error: ${Protocol.bounded(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))}"
    )
    lines += ""
    lines.mkString("\n")

  private def sha256(content: String): String =
    sha256OfBytes(content.getBytes(UTF_8))

  private def sha256Of(path: Path): String =
    sha256OfBytes(Files.readAllBytes(path))

  private def sha256OfBytes(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    digest.map(b => f"${b & 0xff}%02x").mkString

  private def utf8Size(content: String): Long =
    content.getBytes(UTF_8).length.toLong

  private def sizeOf(path: Path): Long =
    if Files.exists(path) then Files.size(path) else 0L

end Artifacts
