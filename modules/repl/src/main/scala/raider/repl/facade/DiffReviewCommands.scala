package raider.repl.facade

import raider.repl.ChatLayoutSink
import raider.tools.files.edit.PatchTool
import raider.tools.files.read.Workspace
import zio.Unsafe

import java.nio.file.Paths

/** RAI-192 facade: drive the per-hunk diff review rendered by the chat layout.
  * The commands operate on the [[ChatLayoutSink]] of the installed session and
  * apply accepted hunks ONLY through the production `fs_patch` policy path
  * (`PatchTool.applySelected` — containment, size bound, sha256 provenance).
  * There is no second patch implementation here.
  *
  * Every method is total (returns a String, never throws for user input) and
  * pushes the same text to the chat transcript as a Status line when a sink is
  * available. Fail-closed rule: a review with no provenance, no accepted hunks,
  * several targets or an already-applied state is refused, never partially
  * applied.
  */
object DiffReviewCommands:

  /** Show the open review: decision state per hunk, target and applied flag. */
  def status(): String =
    chat() match
      case None => respond(None, unavailable)
      case Some(target) =>
        target.reviewSnapshot() match
          case None =>
            respond(
              Some(target),
              "diff review: no diff review open " +
                "(no complete diff fence in the current turn)"
            )
          case Some(snapshot) =>
            val state = if snapshot.applied then "applied" else "open"
            val decisions = snapshot.decisions.zipWithIndex
              .map:
                case (Some(true), index)  => s"${index + 1}:accepted"
                case (Some(false), index) => s"${index + 1}:rejected"
                case (None, index)        => s"${index + 1}:pending"
              .mkString(" ")
            respond(
              Some(target),
              s"diff review ($state, target " +
                s"${snapshot.targets.headOption.getOrElse("<none>")}) | $decisions"
            )

  /** Mark hunk `index1` (1-based, as rendered in the `[n:state]` markers).
    * Anchors the target-file sha256 provenance at the FIRST decision; a
    * provenance failure is surfaced but does not block deciding — the apply
    * step still refuses without provenance.
    */
  def decide(index1: Int, accept: Boolean): String =
    chat() match
      case None => respond(None, unavailable)
      case Some(target) =>
        target.reviewSnapshot() match
          case None =>
            respond(Some(target), "diff review: no diff review open")
          case Some(snapshot) =>
            if snapshot.applied then
              respond(
                Some(target),
                "diff review already applied; decisions are locked until the next turn"
              )
            else if index1 < 1 || index1 > snapshot.hunkCount then
              respond(
                Some(target),
                s"hunk $index1 does not exist (valid: 1..${snapshot.hunkCount})"
              )
            else
              anchorProvenance(target, snapshot)
              val verb = if accept then "accepted" else "rejected"
              if target.setHunkDecision(index1 - 1, accept) then
                val fresh = target.reviewSnapshot().getOrElse(snapshot)
                respond(
                  Some(target),
                  s"diff review | hunk $index1 $verb " +
                    s"(${fresh.decidedCount}/${fresh.hunkCount} decided)"
                )
              else
                respond(
                  Some(target),
                  "hunk decision refused (review already applied)"
                )

  /** Apply every ACCEPTED hunk through the production patch policy. Pending and
    * rejected hunks are never applied. The review is sealed after the first
    * successful apply — a second apply of the same set is refused.
    */
  def applyAccepted(): String =
    chat() match
      case None => respond(None, unavailable)
      case Some(target) =>
        target.reviewSnapshot() match
          case None =>
            respond(Some(target), "diff review: no diff review open")
          case Some(snapshot) =>
            if snapshot.applied then
              respond(
                Some(target),
                "diff review already applied — refusing to apply twice"
              )
            else if snapshot.acceptedIndices.isEmpty then
              respond(
                Some(target),
                "nothing to apply: no hunks accepted " +
                  "(pending and rejected hunks are never applied)"
              )
            else
              snapshot.targets.toList match
                case Nil =>
                  respond(
                    Some(target),
                    "refused: diff target path missing " +
                      "(a '--- a/…' / '+++ b/…' header pair is required before apply)"
                  )
                case _ :: _ :: _ =>
                  respond(
                    Some(target),
                    s"refused: review spans multiple files " +
                      s"(${snapshot.targets.mkString(", ")}); one file per review in this slice"
                  )
                case List(one) =>
                  snapshot.provenance match
                    case None =>
                      respond(
                        Some(target),
                        s"refused: no sha256 provenance for $one " +
                          "(target unreadable when the review opened); re-run the turn"
                      )
                    case Some(sha) =>
                      applyThroughPolicy(target, one, snapshot, sha)

  // --- internals ---

  private val unavailable =
    "diff review unavailable: no chat layout sink in this session"

  private def chat(): Option[ChatLayoutSink] =
    ReplCommands.currentSession.flatMap(
      _.renderSink.collect { case chat: ChatLayoutSink => chat }
    )

  private def respond(chat: Option[ChatLayoutSink], text: String): String =
    chat.foreach(_.note(text))
    text

  private def respond(target: ChatLayoutSink, text: String): String =
    target.note(text)
    text

  /** First-write-wins provenance anchor (single-target reviews only). */
  private def anchorProvenance(
      target: ChatLayoutSink,
      snapshot: raider.repl.ReviewSnapshot
  ): Unit =
    if snapshot.provenance.isEmpty then
      snapshot.targets.toList match
        case List(one) =>
          workspacePatch().foreach { tool =>
            Unsafe.unsafe { implicit u =>
              zio.Runtime.default.unsafe.run(tool.currentSha(one)) match
                case zio.Exit.Success(sha) => target.attachReviewProvenance(sha)
                case _ => () // apply refuses without provenance
            }
          }
        case _ => () // multi-target reviews apply-refuse later

  private def applyThroughPolicy(
      target: ChatLayoutSink,
      relativePath: String,
      snapshot: raider.repl.ReviewSnapshot,
      sha: String
  ): String =
    workspacePatch() match
      case None =>
        respond(target, "refused: workspace root unavailable for patching")
      case Some(tool) =>
        val selected =
          snapshot.acceptedIndices.map(index => snapshot.allHunks(index)).toList
        Unsafe.unsafe { implicit u =>
          zio.Runtime.default.unsafe
            .run(tool.applySelected(relativePath, selected, sha)) match
            case zio.Exit.Success(result) =>
              target.sealReview()
              respond(
                target,
                s"diff review | applied ${selected.size} hunk(s) to " +
                  s"$relativePath | new sha256 ${result.newSha256.take(12)}..."
              )
            case zio.Exit.Failure(cause) =>
              cause.failureOrCause match
                case Left(error: raider.core.RaiderError) =>
                  respond(
                    target,
                    s"diff review | apply refused: ${error.detail.take(160)}"
                  )
                case _ =>
                  respond(
                    target,
                    "diff review | apply failed " +
                      "(see session log); review stays open"
                  )
        }

  /** Test hook: pin the patch root instead of the process cwd (unit tests run
    * inside the build tree and must never mutate it). Always None in product.
    */
  private[repl] var workspaceRootOverride: Option[String] = None

  /** PatchTool over the process working directory — the same root the chat REPL
    * session serves; construction failure is a Left-style None.
    */
  private def workspacePatch(): Option[PatchTool] =
    try
      val root = workspaceRootOverride.getOrElse(
        Paths.get("").toAbsolutePath.toString
      )
      val workspace = Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe
          .run(Workspace.make(root))
          .getOrThrow()
      }
      Some(new PatchTool(workspace))
    catch case _: Throwable => None

end DiffReviewCommands
