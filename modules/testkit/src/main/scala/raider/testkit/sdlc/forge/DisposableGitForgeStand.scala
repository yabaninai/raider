package raider.testkit.sdlc.forge

import ForgeProtocol.*
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** Generic forge fixture backed by a private, disposable local Git repository.
  * It never contacts a remote or pushes a ref.
  */
final class DisposableGitForgeStand private (val repository: Path)
    extends AutoCloseable:
  private var pullRequests = Map.empty[String, PullRequestState]
  private var operations = Set.empty[OperationId]

  private def git(args: String*): String =
    val process = new ProcessBuilder(
      (Seq("git", "-C", repository.toString) ++ args)*
    ).start()
    val stdout = process.getInputStream.readAllBytes()
    val stderr = process.getErrorStream.readAllBytes()
    val exit = process.waitFor()
    if exit != 0 then
      throw new IllegalStateException(
        s"git ${args.mkString(" ")} failed: ${String(stderr, java.nio.charset.StandardCharsets.UTF_8)}"
      )
    String(stdout, java.nio.charset.StandardCharsets.UTF_8).trim

  private def head: String = git("rev-parse", "refs/heads/main")

  private def response(
      id: OperationId,
      outcome: Outcome,
      pr: Option[String] = None
  ): Response =
    Response(id, outcome, Snapshot(head, pullRequests), pr)

  def commit(request: Request): Response = synchronized {
    if request.kind != OperationKind.Commit then
      return response(request.operationId, Outcome.Rejected)
    if operations.contains(request.operationId) then
      return response(request.operationId, Outcome.Duplicate)
    if request.preconditions.expectedRef != head then
      return response(request.operationId, Outcome.Stale)
    val tree = request.tree match
      case Some(value) => value
      case None        => return response(request.operationId, Outcome.Rejected)
    git(
      "-c",
      "user.name=Raider Test",
      "-c",
      "user.email=raider-test@localhost",
      "commit",
      "--allow-empty",
      "-m",
      tree
    )
    operations += request.operationId
    response(request.operationId, Outcome.Applied)
  }

  def createPullRequest(request: Request): Response = synchronized {
    if request.kind != OperationKind.PullRequest then
      return response(request.operationId, Outcome.Rejected)
    if operations.contains(request.operationId) then
      return response(request.operationId, Outcome.Duplicate)
    if request.preconditions.expectedRef != head then
      return response(request.operationId, Outcome.Stale)
    val id = request.pullRequestId.getOrElse(s"pr-${pullRequests.size + 1}")
    if pullRequests.contains(id) then
      return response(request.operationId, Outcome.Conflict, Some(id))
    git("switch", "-c", s"pr/$id")
    git(
      "-c",
      "user.name=Raider Test",
      "-c",
      "user.email=raider-test@localhost",
      "commit",
      "--allow-empty",
      "-m",
      s"candidate for $id"
    )
    git("switch", "main")
    pullRequests = pullRequests.updated(id, PullRequestState.Open)
    operations += request.operationId
    response(request.operationId, Outcome.Applied, Some(id))
  }

  def pullRequestStatus(id: String): Option[PullRequestState] = synchronized {
    val localRefs = refs
    if localRefs.contains(s"pr/$id") then pullRequests.get(id) else None
  }

  def merge(request: Request): Response = synchronized {
    if request.kind != OperationKind.Merge then
      return response(request.operationId, Outcome.Rejected)
    if operations.contains(request.operationId) then
      return response(
        request.operationId,
        Outcome.Duplicate,
        request.pullRequestId
      )
    if request.preconditions.expectedRef != head then
      return response(request.operationId, Outcome.Stale, request.pullRequestId)
    val id = request.pullRequestId match
      case Some(value) => value
      case None        => return response(request.operationId, Outcome.Rejected)
    if !pullRequests.contains(id) then
      return response(request.operationId, Outcome.Rejected, Some(id))
    if pullRequests(id) != PullRequestState.Open then
      return response(request.operationId, Outcome.Conflict, Some(id))
    val proc = new ProcessBuilder(
      "git",
      "-C",
      repository.toString,
      "-c",
      "user.name=Raider Test",
      "-c",
      "user.email=raider-test@localhost",
      "merge",
      "--no-ff",
      "--no-edit",
      s"pr/$id"
    ).start()
    val stdout = proc.getInputStream.readAllBytes()
    val stderr = proc.getErrorStream.readAllBytes()
    val exit = proc.waitFor()
    if exit != 0 then
      git("merge", "--abort")
      val details = String(stderr, java.nio.charset.StandardCharsets.UTF_8)
      if details.contains("CONFLICT") then
        response(request.operationId, Outcome.Conflict, Some(id))
      else
        throw new IllegalStateException(
          s"git merge failed: $details${String(stdout, java.nio.charset.StandardCharsets.UTF_8)}"
        )
    else
      pullRequests = pullRequests.updated(id, PullRequestState.Merged)
      operations += request.operationId
      response(request.operationId, Outcome.Applied, Some(id))
  }

  /** Read the actual repository refs without changing them. */
  def refs: Map[String, String] = synchronized {
    git(
      "for-each-ref",
      "--format=%(refname:short) %(objectname)",
      "refs/heads"
    ).linesIterator
      .filter(_.nonEmpty)
      .map { line =>
        val parts = line.split(" ", 2)
        parts(0) -> parts(1)
      }
      .toMap
  }

  def reconcile(operationId: OperationId): Response = synchronized {
    if operations.contains(operationId) then
      response(operationId, Outcome.AlreadyApplied)
    else response(operationId, Outcome.Rejected)
  }

  override def close(): Unit =
    val paths = Files.walk(repository)
    try paths.iterator().asScala.toSeq.reverse.foreach(Files.deleteIfExists(_))
    finally paths.close()

object DisposableGitForgeStand:

  def create(): DisposableGitForgeStand =
    val directory = Files.createTempDirectory("raider-forge-git-")
    val process =
      new ProcessBuilder("git", "-C", directory.toString, "init", "-b", "main")
        .start()
    val exit = process.waitFor()
    if exit != 0 then
      throw new IllegalStateException(
        "git init failed for disposable forge fixture"
      )
    val stand = new DisposableGitForgeStand(directory)
    Files.writeString(directory.resolve("seed.txt"), "disposable forge seed\n")
    val seed =
      new ProcessBuilder("git", "-C", directory.toString, "add", "seed.txt")
        .start()
    if seed.waitFor() != 0 then
      throw new IllegalStateException(
        "git add failed for disposable forge fixture"
      )
    val commit = new ProcessBuilder(
      "git",
      "-C",
      directory.toString,
      "-c",
      "user.name=Raider Test",
      "-c",
      "user.email=raider-test@localhost",
      "commit",
      "-m",
      "seed"
    ).start()
    if commit.waitFor() != 0 then
      throw new IllegalStateException(
        "git seed commit failed for disposable forge fixture"
      )
    stand
