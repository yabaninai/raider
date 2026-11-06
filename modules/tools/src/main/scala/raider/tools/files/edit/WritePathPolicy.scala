package raider.tools.files.edit

import raider.core.RaiderError

import java.nio.file.{Files, Path, Paths}

/** RAI-259 deny-by-default write-path containment policy for the coder's
  * structured mutation tools.
  *
  * Every mutating file tool (`fs_edit`, `fs_patch`, `fs_patch_multi`,
  * `fs_delete` — all funneled through `EditTool.resolveSafe`) resolves its
  * target through [[resolveForWrite]] BEFORE any disk effect. A target is
  * usable only if ALL checks hold:
  *
  *   1. the path is non-empty and workspace-relative (absolute targets are
  *      refused — `root.resolve(absolute)` would discard the root entirely);
  *   2. the normalized candidate stays lexically inside the workspace root
  *      (rejects `..` traversal);
  *   3. the FULLY RESOLVED target — realpath of the nearest EXISTING ancestor
  *      joined with the non-existing remainder — stays inside the realpath of
  *      the root. Check 3 is what closes the create-through-symlinked-directory
  *      hole: a NOT-YET-EXISTING file under a directory symlink pointing
  *      outside the workspace is DENIED instead of silently written through
  *      (the pre-RAI-259 check only realpath'd targets that already existed).
  *
  * The policy is an immutable value (`rootReal` fixed at construction), so
  * concurrent invocations from parallel delegated children (which share the
  * parent's registry instance) observe identical decisions by construction.
  * Combined with the toolset's mutation semaphore the check-then-write sequence
  * is process-local; this is model tool policy, NOT a sandbox against other
  * processes or the REPL (trust boundary: REPL/plugins run trusted code).
  */
final case class WritePathPolicy(rootReal: Path):

  /** Resolve a workspace-relative write target, or a typed denial. `Left`
    * carries model-correctable `RA-TOOLDENY` feedback (or `RA-INP` for a blank
    * path); unexpected filesystem I/O surfaces as an exception for the caller
    * to wrap (EditTool maps it to `RA-TOOLFAIL`). Diagnostic phrasing keeps the
    * established "escapes workspace" prefixes consumers already match on.
    */
  def resolveForWrite(relativePath: String): Either[RaiderError, Path] =
    if relativePath.trim.isEmpty then
      Left(RaiderError.InputValidation("path must be non-empty"))
    else
      val candidate = Paths.get(relativePath)
      if candidate.isAbsolute then
        Left(
          RaiderError.ToolDenied(
            s"path escapes workspace: $relativePath (absolute targets are refused; use a workspace-relative path)"
          )
        )
      else
        val raw = rootReal.resolve(candidate).normalize()
        if !raw.startsWith(rootReal) then
          Left(
            RaiderError.ToolDenied(
              s"path escapes workspace: $relativePath (workspace root: $rootReal)"
            )
          )
        else
          // Resolve THROUGH the nearest existing ancestor, then re-join the
          // non-existing remainder (relative to THAT ancestor): symlinks
          // anywhere on the existing prefix are followed and re-checked
          // against the root. For an existing target the remainder is empty
          // and the resolved target itself is returned.
          val anc = nearestExisting(raw)
          val ancReal = anc.toRealPath()
          val real = ancReal.resolve(anc.relativize(raw)).normalize()
          if !real.startsWith(rootReal) then
            Left(
              RaiderError.ToolDenied(
                s"symlink escapes workspace: $relativePath (resolves to $real, outside root $rootReal)"
              )
            )
          else Right(real)

  /** Deepest existing ancestor of `p` (Files.exists follows symlinks). The walk
    * terminates at the root, which exists by construction (Workspace.make
    * already realpath'd it); the Option wrapper is defense in depth against a
    * null parent.
    */
  private def nearestExisting(p: Path): Path =
    var cur = Option(p)
    while cur.isDefined && !Files.exists(cur.get) do
      cur = cur.flatMap(par => Option(par.getParent))
    cur.filter(cur0 => Files.exists(cur0)).getOrElse(rootReal)
