package raider.tools.search

import raider.core.RaiderError
import raider.tools.files.read.Workspace
import zio.ZIO

import java.nio.file.{Files, FileSystems, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Bounded content search over the workspace (RAI-014). Pure java.nio + ZIO: NO
  * shell concatenation, NO external grep process (§10).
  *
  * Bounds: file walk stops at `maxFiles` (declared truncation), matches stop at
  * `maxMatches` (declared truncation), single-line reads with UTF-8 decode;
  * binary files (NUL byte) are skipped, never dumped. An empty query is a
  * DEFINED result: empty, truncated=false (documented behavior, not an error).
  */
final class SearchTool(workspace: Workspace):

  def search(
      query: String,
      glob: Option[String] = None,
      maxMatches: Int = 100
  ): ZIO[Any, RaiderError, SearchResult] =
    if maxMatches < 1 then
      ZIO.fail(
        RaiderError.InputValidation(s"maxMatches must be >= 1, got $maxMatches")
      )
    else if maxMatches > SearchTool.HardMaxMatches then
      ZIO.fail(
        RaiderError.InputValidation(
          s"maxMatches must be <= ${SearchTool.HardMaxMatches}, got $maxMatches"
        )
      )
    else if query.isEmpty then
      // defined behavior: an empty query matches nothing (documented)
      ZIO.succeed(
        SearchResult(
          matches = Nil,
          truncated = false,
          filesScanned = 0,
          filesWalked = 0
        )
      )
    else
      ZIO
        .attemptBlocking {
          val matcher = glob.map(pattern =>
            FileSystems.getDefault.getPathMatcher(s"glob:$pattern")
          )
          var filesWalked = 0
          var filesScanned = 0
          val found = scala.collection.mutable.ListBuffer.empty[SearchMatch]
          var truncated = false
          var matchesLeft = maxMatches // GLOBAL bound across all files

          Using(Files.walk(workspace.rootReal)) { stream =>
            val it = stream.iterator().asScala
            var stop = false
            while it.hasNext && !stop do
              val p = it.next()
              filesWalked += 1
              if filesWalked > SearchTool.HardMaxFiles then
                truncated = true
                stop = true
              else if Files.isRegularFile(p) && !Files.isSymbolicLink(p) then
                val rel = workspace.rootReal.relativize(p).toString
                val matchesGlob = matcher.forall(_.matches(p.getFileName))
                if matchesGlob then
                  filesScanned += 1
                  if filesScanned > SearchTool.HardMaxScanned then
                    truncated = true
                    stop = true
                  else
                    scanFile(
                      p,
                      rel,
                      query,
                      matchesLeft,
                      found,
                      () => truncated = true,
                      left => matchesLeft = left
                    )
            ()
          }
          SearchResult(found.toList, truncated, filesScanned, filesWalked)
        }
        .mapError(e =>
          RaiderError.ToolFailed(s"search failed: ${e.getMessage}")
        )

  private def scanFile(
      p: Path,
      rel: String,
      query: String,
      maxMatches: Int,
      found: scala.collection.mutable.ListBuffer[SearchMatch],
      markTruncated: () => Unit,
      reportLeft: Int => Unit
  ): Unit =
    if Files.size(p) <= SearchTool.MaxScanFileBytes then
      val bytes = Files.readAllBytes(p)
      if !bytes.contains(0.toByte) then // binary files are skipped, not dumped
        val text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
        val lines =
          if text.isEmpty then Iterator.empty
          else text.split("\n", -1).iterator
        var lineNo = 0
        var left = maxMatches
        while lines.hasNext && left > 0 do
          val line = lines.next()
          lineNo += 1
          val idx = line.indexOf(query)
          if idx >= 0 then
            found += SearchMatch(
              rel,
              lineNo,
              line.take(SearchTool.MaxLineSnippet)
            )
            left -= 1
        reportLeft(left)
        if left == 0 then markTruncated()

object SearchTool:
  val HardMaxMatches: Int = 10_000
  val HardMaxFiles: Int = 50_000
  val HardMaxScanned: Int = 20_000
  val MaxScanFileBytes: Long = 2L * 1024 * 1024 // 2 MiB per file
  val MaxLineSnippet: Int = 500

final case class SearchMatch(file: String, lineNumber: Int, line: String)

final case class SearchResult(
    matches: List[SearchMatch],
    truncated: Boolean,
    filesScanned: Int,
    filesWalked: Int
)
