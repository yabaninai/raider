package raider.runtime.sdlc.approval

import raider.core.sdlc.SdlcEvidence.CandidateIdentity
import zio.test.*
import zio.json.*

import java.nio.file.Files
import java.time.Instant

object ApprovalArtifactsSpec extends ZIOSpecDefault:
  import ApprovalArtifacts.*
  import ApprovalArtifacts.Refusal.*

  private val candidate = CandidateIdentity(
    "repo",
    "worktree",
    Some("base"),
    Some("head"),
    Some("revision"),
    "tree",
    "task",
    "plan",
    "policy",
    "config"
  )

  private val expires = Instant.parse("2030-01-01T00:00:00Z")
  private val now = Instant.parse("2029-01-01T00:00:00Z")

  private val approval =
    request("before-merge", candidate, expires).toOption.get

  private val decision = Decision(
    SchemaVersion,
    "before-merge",
    approval.candidateDigest,
    expires.toString,
    approved = true
  )

  private def state(c: CandidateIdentity = candidate) = CurrentState(
    c,
    c.policyDigest,
    c.treeDigest,
    c.taskDigest,
    c.baseRevision,
    List("check-evidence"),
    CandidateIdentity.digest(c),
    Set("merge")
  )

  def spec = suite("SDLC approval request and fresh invocation")(
    test(
      "SDLC-APR-01 request is digest-bound, written atomically, and a fresh invocation re-reads current state"
    ) {
      val dir = Files.createTempDirectory("approval-artifacts-")
      val path = dir.resolve("approval-request.json")
      val checkpoint =
        headlessCheckpoint(path, "before-merge", candidate, expires)
      var reads = 0
      val result = recheck(
        approval,
        Some(decisionJson(decision)),
        now,
        () => {
          reads += 1
          Right(state())
        },
        "merge"
      )
      val content = Files.readString(path).fromJson[RequestCarrier]
      assertTrue(
        checkpoint == Right(NeedsApproval(approval)),
        checkpoint.toOption.exists(_.exitCode == 25),
        content == Right(
          RequestCarrier(
            SchemaVersion,
            "approval-request",
            approval.checkpointId,
            approval
          )
        ),
        decodeRequestArtifact(Files.readString(path)) == Right(approval),
        approval.candidateDigest == CandidateIdentity.digest(candidate),
        result == Right(Authorized(approval.candidateDigest, "before-merge")),
        reads == 1
      )
    },
    test(
      "SDLC-APR-02 refusal matrix: absent, malformed, denied, expired, mismatched digest and stale candidate"
    ) {
      val stale =
        candidate.copy(treeDigest = "new-tree", revision = Some("new-revision"))
      val staleRequest = request("before-merge", stale, expires).toOption.get
      val invalidDigest = decision.copy(candidateDigest = "0" * 64)
      val denied = decision.copy(approved = false)
      val expiredRequest = request("before-merge", candidate, now).toOption.get
      assertTrue(
        recheck(approval, None, now, () => Right(state()), "merge") == Left(
          MissingDecision
        ),
        recheck(
          approval,
          Some("not-json"),
          now,
          () => Right(state()),
          "merge"
        ).isLeft,
        recheck(
          approval,
          Some(decisionJson(decision).dropRight(1) + ",\"untrusted\":true}"),
          now,
          () => Right(state()),
          "merge"
        ) == Left(InvalidDecision("unknown field: untrusted")),
        recheck(
          approval,
          Some(decisionJson(denied)),
          now,
          () => Right(state()),
          "merge"
        ) == Left(Denied),
        validateDecisionFile(
          expiredRequest,
          decision.copy(expiresAt = expiredRequest.expiresAt),
          now,
          state(),
          "merge"
        ) == Left(Expired),
        validateDecisionFile(
          approval,
          invalidDigest,
          now,
          state(),
          "merge"
        ) == Left(InvalidDecision("decision does not match request")),
        recheck(
          staleRequest,
          Some(
            decisionJson(
              decision.copy(candidateDigest = staleRequest.candidateDigest)
            )
          ),
          now,
          () => Right(state()),
          "merge"
        ) == Left(CandidateMismatch)
      )
    },
    test(
      "SDLC-APR-03 changed policy, repository, task, evidence, or capability refuses; no resume state is retained"
    ) {
      val changedPolicy = state(candidate.copy(policyDigest = "policy-new"))
      val changedRepo = state(candidate.copy(treeDigest = "repo-new"))
      val changedTask = state(candidate.copy(taskDigest = "task-new"))
      val noEvidence = state().copy(evidenceDigests = Nil)
      val mismatchedEvidence =
        state().copy(evidenceCandidateDigest = "0" * 64)
      val changedCurrentPolicy = state().copy(policyDigest = "policy-new")
      val changedCurrentRepo = state().copy(repositoryStateDigest = "repo-new")
      val changedCurrentTask = state().copy(taskDigest = "task-new")
      val noCapability = state().copy(ownerCapabilities = Set.empty)
      assertTrue(
        validateDecisionFile(
          approval,
          decision,
          now,
          changedPolicy,
          "merge"
        ) == Left(CandidateMismatch),
        CandidateIdentity.digest(
          changedCurrentPolicy.candidate
        ) == approval.candidateDigest,
        validateDecisionFile(
          approval,
          decision,
          now,
          changedCurrentPolicy,
          "merge"
        ) == Left(PolicyChanged),
        CandidateIdentity.digest(
          changedCurrentRepo.candidate
        ) == approval.candidateDigest,
        validateDecisionFile(
          approval,
          decision,
          now,
          changedCurrentRepo,
          "merge"
        ) == Left(RepositoryChanged),
        CandidateIdentity.digest(
          changedCurrentTask.candidate
        ) == approval.candidateDigest,
        validateDecisionFile(
          approval,
          decision,
          now,
          changedCurrentTask,
          "merge"
        ) == Left(TaskOrBaseChanged),
        validateDecisionFile(
          approval,
          decision,
          now,
          changedRepo,
          "merge"
        ) == Left(CandidateMismatch),
        validateDecisionFile(
          approval,
          decision,
          now,
          changedTask,
          "merge"
        ) == Left(CandidateMismatch),
        validateDecisionFile(
          approval,
          decision,
          now,
          noEvidence,
          "merge"
        ) == Left(EvidenceChanged),
        mismatchedEvidence.evidenceDigests.nonEmpty,
        mismatchedEvidence.evidenceCandidateDigest != approval.candidateDigest,
        validateDecisionFile(
          approval,
          decision,
          now,
          mismatchedEvidence,
          "merge"
        ) == Left(EvidenceChanged),
        validateDecisionFile(
          approval,
          decision,
          now,
          noCapability,
          "merge"
        ) == Left(CapabilityMissing),
        decision.schemaVersion == SchemaVersion
      )
    },
    test(
      "SDLC-DISPATCH-01 request dispatch stops with NeedsApproval; a new dispatch reads request and decision files and rechecks owner state"
    ) {
      val dir = Files.createTempDirectory("checkpoint-dispatch-")
      val requestPath = dir.resolve("request.json")
      val decisionPath = dir.resolve("decision.json")
      val newRequest = CheckpointDispatch(
        "before-merge",
        candidate,
        expires,
        requestPath,
        None,
        "merge",
        () => Right(state()),
        () => now
      )
      val requested = newRequest.evaluate()
      Files.writeString(decisionPath, decisionJson(decision))
      var reads = 0
      val approved = CheckpointDispatch(
        "before-merge",
        candidate,
        expires,
        requestPath,
        Some(decisionPath),
        "merge",
        () => {
          reads += 1
          Right(state())
        },
        () => now
      ).evaluate()
      val stale = CheckpointDispatch(
        "before-merge",
        candidate,
        expires,
        requestPath,
        Some(decisionPath),
        "merge",
        () => Right(state(candidate.copy(treeDigest = "changed"))),
        () => now
      ).evaluate()
      assertTrue(
        requested.isInstanceOf[CheckpointOutcome.NeedsApproval],
        requested match
          case CheckpointOutcome.NeedsApproval(value) =>
            value.exitCode == 25 && Files.isRegularFile(requestPath)
          case _ => false,
        approved match
          case CheckpointOutcome.Authorized(value) =>
            value.candidateDigest == CandidateIdentity.digest(candidate)
          case _ => false,
        reads == 1,
        stale == CheckpointOutcome.Refused(CandidateMismatch)
      )
    }
  )
