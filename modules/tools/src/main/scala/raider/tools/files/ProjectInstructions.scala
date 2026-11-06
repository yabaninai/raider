package raider.tools.files

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

/** RAI-259: project-instruction file for the native coder, loaded from the
  * workspace root and surfaced into the coder system prompt.
  *
  *   - `DefaultFileNames` — first existing match wins (precedence by order).
  *   - `MaxBytes` — hard cap; larger files load truncated and say so.
  *   - Fail-open: a missing/unreadable/empty file means NO instructions (`load`
  *     returns `None` and the base prompt is used unchanged) — a broken
  *     instruction file must never take the coder down.
  *   - Deny-by-default containment: an instruction file that is a symlink
  *     resolving outside the root is IGNORED (same policy spirit as the
  *     write-path policy; the prompt must only ever reflect root-local text).
  */
final case class ProjectInstructions(
    file: String,
    content: String,
    truncated: Boolean,
    bytes: Long
)

object ProjectInstructions:

  /** Candidate file names at the workspace root, in precedence order. */
  val DefaultFileNames: List[String] = List("AGENTS.md")

  /** Hard content cap (bytes); larger files are loaded truncated. */
  val MaxBytes: Int = 64 * 1024

  /** Load the first usable instruction file under `rootReal`. A file is usable
    * when it exists, is a regular file whose realpath stays inside the root,
    * and carries non-blank content. I/O failures fall through to the next
    * candidate (fail-open), never throw.
    */
  def load(
      rootReal: Path,
      names: List[String] = DefaultFileNames
  ): Option[ProjectInstructions] =
    names.iterator
      .map(loadOne(rootReal, _))
      .collectFirst { case Some(instr) => instr }

  private def loadOne(
      rootReal: Path,
      name: String
  ): Option[ProjectInstructions] =
    try
      val candidate = rootReal.resolve(Paths.get(name))
      // Deny-by-default: only a regular file inside the root (a symlink must
      // resolve back INTO the root) may feed the prompt.
      if !candidate.normalize().startsWith(rootReal) then None
      else if !Files.exists(candidate) then None
      else
        val real = candidate.toRealPath()
        if !real.startsWith(rootReal) || !Files.isRegularFile(real) then None
        else
          // Read at most MaxBytes+1 bytes to detect truncation cheaply.
          val in = Files.newInputStream(real)
          val read =
            try in.readNBytes(MaxBytes + 1)
            finally in.close()
          val truncated = read.length > MaxBytes
          val bounded = if truncated then read.take(MaxBytes) else read
          val content = new String(bounded.toArray, UTF_8)
          if content.trim.isEmpty then None
          else
            Some(
              ProjectInstructions(
                file = name,
                content = content,
                truncated = truncated,
                bytes = bounded.length.toLong
              )
            )
    catch case _: Exception => None // fail-open: no instructions

  /** The system-prompt section appended for loaded instructions. */
  def section(instr: ProjectInstructions): String =
    val truncMark =
      if instr.truncated then s"\n[instructions truncated at $MaxBytes bytes]"
      else ""
    s"""\n\n## Project instructions (from ${instr.file} at the project root)\n\nThe text below was loaded from this repository's instruction file. It carries the project owner's standing rules for working in this workspace; follow it for this session.\n\n${instr.content}$truncMark\n"""

  /** Compose the coder system prompt: base prompt unchanged when no usable
    * instructions exist, base + section otherwise.
    */
  def surface(
      basePrompt: String,
      rootReal: Path,
      names: List[String] = DefaultFileNames
  ): String =
    load(rootReal, names).fold(basePrompt)(i => basePrompt + section(i))
