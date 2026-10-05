package raider.tools.files

import raider.core.RaiderError
import raider.tools.files.read.Workspace
import zio.ZIO

import java.nio.file.{Files, Path}

/** fs_tree (nightly Phase 2.5): bounded workspace directory listing as data.
  *
  * Bounds: depth capped at `TreeTool.MaxDepth` (caller default 3), total
  * entries capped (500 default) with honest `truncated` reporting, skip-list
  * for VCS/build noise (.git, target, node_modules), glob filter on FILE names
  * (dirs always traversable so filtering stays meaningful), and workspace
  * containment — symlinks pointing outside the workspace are refused/omitted,
  * never followed.
  */
final class TreeTool(workspace: Workspace):

  def tree(
      depth: Int,
      glob: Option[String],
      maxEntries: Int = TreeTool.MaxEntries
  ): ZIO[Any, RaiderError, TreeResult] =
    val effectiveDepth = math.max(0, math.min(depth, TreeTool.MaxDepth))
    val globRegex = glob.map(TreeTool.globToRegex)
    ZIO
      .attempt {
        val buf = scala.collection.mutable.ListBuffer.empty[TreeEntry]
        var total = 0
        var truncated = false

        def walk(dir: Path, rel: String, level: Int): Unit =
          if level >= effectiveDepth then ()
          else
            import scala.jdk.CollectionConverters.*
            val stream = Files.list(dir)
            try
              val children = stream.iterator().asScala.toList
              // dirs first, then files — stable, readable output
              val dirs = children
                .filter(Files.isDirectory(_))
                .sortBy(_.getFileName.toString)
              val files = children
                .filterNot(Files.isDirectory(_))
                .sortBy(_.getFileName.toString)
              dirs.foreach: d =>
                val name = d.getFileName.toString
                if !TreeTool.SkipList.contains(name) then
                  val childRel = if rel.isEmpty then name else s"$rel/$name"
                  // never follow symlinks that leave the workspace
                  val real =
                    try d.toRealPath()
                    catch case _: Exception => d
                  if real.startsWith(workspace.root) then
                    walk(d, childRel, level + 1)
              files.foreach: f =>
                val name = f.getFileName.toString
                val matches = globRegex.forall(r => r.matches(name))
                // file-level containment first: a symlink pointing outside
                // the workspace is omitted entirely, never followed/reported
                val real =
                  try f.toRealPath()
                  catch case _: Exception => f
                val contained = real.startsWith(workspace.root)
                if matches && contained then
                  total += 1
                  if buf.size >= maxEntries then truncated = true
                  else
                    val childRel =
                      if rel.isEmpty then name else s"$rel/$name"
                    buf += TreeEntry(
                      childRel,
                      isDir = false,
                      Files.size(f)
                    )
            finally stream.close()
        walk(workspace.root, "", 0)
        TreeResult(buf.toList, total, truncated, effectiveDepth)
      }
      .mapError(e =>
        RaiderError.ToolFailed(s"fs_tree: ${e.getMessage.take(120)}")
      )

object TreeTool:
  val MaxDepth = 5
  val MaxEntries = 500
  val SkipList = Set(".git", "target", "node_modules")

  /** Simple glob → regex: `*` (not crossing `/` is NOT enforced — file names
    * only), `?` single char, everything else literal.
    */
  def globToRegex(glob: String): scala.util.matching.Regex =
    val sb = new StringBuilder
    glob.foreach:
      case '*' => sb.append(".*")
      case '?' => sb.append('.')
      case c   => sb.append(java.util.regex.Pattern.quote(c.toString))
    sb.result().r

final case class TreeEntry(path: String, isDir: Boolean, bytes: Long)

final case class TreeResult(
    entries: List[TreeEntry],
    total: Int,
    truncated: Boolean,
    depthUsed: Int
)
