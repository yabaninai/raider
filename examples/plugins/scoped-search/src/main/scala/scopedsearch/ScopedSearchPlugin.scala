package scopedsearch

import raider.core.{RaiderError, RecoveryClass, Tool, ToolCapabilities}
import raider.core.plugin.{
  CapabilityRef,
  PluginDescriptor,
  PluginVersion,
  RaiderPlugin
}

import zio.Scope
import zio.ZIO
import zio.json.{DecoderOps, DeriveJsonEncoder, EncoderOps, JsonEncoder}

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

/** RAI-096 SDK fixture: a READ-ONLY tool that searches FILE NAMES for a literal
  * substring, strictly inside one caller-supplied directory.
  *
  * The scope is honest: the tool walks only `<root>` (bounded scan), never the
  * process CWD or the host workspace, and reports `truncated` instead of
  * silently cutting results. Missing/malformed arguments and a missing
  * directory are typed `RaiderError.InputValidation` failures — the tool
  * contract's error channel, never a thrown exception across the seam.
  */
final case class ScopedSearchOut(
    root: String,
    query: String,
    matches: List[String],
    truncated: Boolean
)

object ScopedSearchOut:
  given JsonEncoder[ScopedSearchOut] = DeriveJsonEncoder.gen[ScopedSearchOut]

class ScopedSearchTool extends Tool:

  /** Bounded by design: at most MaxEntries filesystem entries are scanned. */
  val MaxEntries: Int = 500

  def name: String = "scoped.search"

  def version: Int = 1

  def description: String =
    "literal file-NAME search restricted to the given directory; " +
      "args {\"root\": \"<dir>\", \"query\": \"<substring>\"}"

  def recovery: RecoveryClass = RecoveryClass.ReadOnly

  def timeoutMs: Long = 5000L

  def capabilities: ToolCapabilities = ToolCapabilities(concurrentSafe = true)

  override def parametersJsonSchema: String =
    "{\"type\":\"object\"," +
      "\"properties\":{\"root\":{\"type\":\"string\"," +
      "\"description\":\"directory to search inside\"}," +
      "\"query\":{\"type\":\"string\"," +
      "\"description\":\"literal substring of the file name\"}}," +
      "\"required\":[\"root\",\"query\"],\"additionalProperties\":false}"

  def invoke(argumentsJson: String): ZIO[Scope, RaiderError, String] =
    argumentsJson.fromJson[Map[String, String]] match
      case Left(err) =>
        ZIO.fail(
          RaiderError.InputValidation(s"scoped.search args: ${err.take(120)}")
        )
      case Right(args) =>
        val root = args.getOrElse("root", "").trim
        val query = args.getOrElse("query", "")
        if root.isEmpty then
          ZIO.fail(
            RaiderError.InputValidation("scoped.search: 'root' is required")
          )
        else if query.isEmpty then
          ZIO.fail(
            RaiderError.InputValidation("scoped.search: 'query' is required")
          )
        else if !Files.isDirectory(Paths.get(root)) then
          ZIO.fail(
            RaiderError.InputValidation(
              s"scoped.search: not a directory: $root"
            )
          )
        else ZIO.attempt(search(Paths.get(root), query)).orDie

  /** The actual bounded scan. `ZIO.attempt(...).orDie` is honest here: every
    * EXPECTED failure is typed above; an unexpected IO defect stays a defect
    * instead of masquerading as a tool result.
    */
  private def search(dir: java.nio.file.Path, query: String): String =
    val raw =
      val stream = Files.walk(dir)
      try
        stream.limit(MaxEntries.toLong + 1).iterator().asScala.drop(1).toList
      finally stream.close()
    val truncated = raw.size > MaxEntries
    val scanned = if truncated then raw.dropRight(1) else raw
    val matches = scanned
      .filter(p => Files.isRegularFile(p))
      .map(p => p.getFileName.toString)
      .filter(_.contains(query))
      .sorted
    ScopedSearchOut(
      root = dir.toString,
      query = query,
      matches = matches,
      truncated = truncated
    ).toJson

class ScopedSearchPlugin extends RaiderPlugin:

  def descriptor: PluginDescriptor = PluginDescriptor(
    name = "scoped-search",
    version = PluginVersion(1, 0, 0),
    minApiVersion = 1,
    maxApiVersion = 1,
    capabilities = List(CapabilityRef("tool", "scoped.search"))
  )
