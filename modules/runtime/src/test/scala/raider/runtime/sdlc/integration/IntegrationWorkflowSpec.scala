package raider.runtime.sdlc.integration

import raider.core.sdlc.SdlcEvidence.*
import raider.runtime.sdlc.integration.*
import zio.test.*

object IntegrationWorkflowSpec extends ZIOSpecDefault:

  private val candidate = CandidateIdentity(
    "repo",
    "worktree",
    Some("base"),
    None,
    Some("candidate"),
    "tree-digest",
    "task",
    "plan",
    "policy",
    "config"
  )

  private val check = CheckResult(
    "unit",
    "def",
    "argv",
    CheckOutcome.Passed,
    Some(0),
    "toolchain",
    "verifier",
    candidate,
    "output",
    "policy"
  )

  private val review = ReviewVerdict(
    "reviewer",
    ReviewDecision.Acceptable,
    "findings",
    "task",
    "plan",
    candidate,
    List("unit-result"),
    "policy"
  )

  private final class FakeForge(
      initialFault: Option[(Verb, ForgeOutcome)] = None,
      staleBefore: Option[Verb] = None
  ) extends ForgePort:
    var reconciles = 0
    var mutations = List.empty[Verb]
    var states = Map.empty[String, String]
    var head = "base"
    var target = "base"
    var pullId = 0

    def reconcile(): Either[String, ForgeSnapshot] =
      reconciles += 1
      if staleBefore.contains(
          if mutations.isEmpty then Verb.Commit else Verb.Merge
        )
      then target = "advanced"
      Right(
        ForgeSnapshot(
          "repo",
          "main",
          target,
          head,
          if head == "base" then None
          else Some(CandidateIdentity.digest(candidate)),
          states,
          "policy",
          Set("commit", "pull-request", "merge")
        )
      )

    def mutate(request: ForgeMutation): ForgeResponse =
      mutations :+= request.verb
      val outcome = initialFault
        .filter(_._1 == request.verb)
        .map(_._2)
        .getOrElse(ForgeOutcome.Applied)
      if outcome == ForgeOutcome.Applied then
        request.verb match
          case Verb.Commit => head = "candidate"
          case Verb.PullRequestCreate =>
            pullId += 1
            states += s"pr-$pullId" -> "Open"
          case Verb.Merge =>
            request.pullRequestId.foreach(id => states += id -> "Merged")
          case Verb.PullRequestStatus => ()
      ForgeResponse(
        request.operationId,
        outcome,
        ForgeSnapshot(
          "repo",
          "main",
          target,
          head,
          if head == "base" then None
          else Some(CandidateIdentity.digest(candidate)),
          states,
          "policy",
          Set("commit", "pull-request", "merge")
        ),
        if request.verb == Verb.PullRequestCreate then Some(s"pr-$pullId")
        else request.pullRequestId
      )

    def pullRequestStatus(id: String): Either[String, Option[String]] = Right(
      states.get(id)
    )

  private def workflow(forge: ForgePort) = IntegrationWorkflow(
    forge,
    IntegrationRules(
      "main",
      Set("unit"),
      true,
      "policy",
      Set("commit", "pull-request", "merge")
    )
  )

  private def input(
      checks: List[CheckResult] = List(check),
      reviews: List[ReviewVerdict] = List(review)
  ) =
    IntegrationCandidate(candidate, checks, reviews)

  def spec = suite("RAI-167 configured integration orchestration")(
    test(
      "SDLC-INT-01: green checks and acceptable review permit commit, PR, status, and merge"
    ) {
      val forge = new FakeForge()
      val run = workflow(forge).execute(input(), "happy")
      assertTrue(
        run.records.map(_.verb) == List(
          Verb.Commit,
          Verb.PullRequestCreate,
          Verb.PullRequestStatus,
          Verb.Merge
        ),
        run.records.forall(_.refusal.isEmpty),
        run.records.forall(r => r.result.candidate == candidate),
        forge.mutations == List(
          Verb.Commit,
          Verb.PullRequestCreate,
          Verb.Merge
        ),
        forge.reconciles >= 3
      )
    },
    test(
      "SDLC-INT-02a: a red or absent required check refuses before mutation"
    ) {
      val forge = new FakeForge()
      val red = check.copy(outcome = CheckOutcome.Failed)
      val run = workflow(forge).execute(input(List(red)), "red")
      val absent = workflow(new FakeForge()).execute(input(Nil), "absent")
      assertTrue(
        run.records.head.refusal.exists(_.isInstanceOf[Refusal.MissingChecks]),
        absent.records.head.refusal
          .exists(_.isInstanceOf[Refusal.MissingChecks]),
        forge.mutations.isEmpty
      )
    },
    test("SDLC-INT-02b: missing required review refuses before mutation") {
      val forge = new FakeForge()
      val run = workflow(forge).execute(input(reviews = Nil), "review")
      assertTrue(
        run.records.head.refusal.contains(Refusal.MissingReview),
        forge.mutations.isEmpty
      )
    },
    test("SDLC-INT-02d: absent owner-granted mutation capability refuses") {
      val forge = new FakeForge()
      val denied = IntegrationWorkflow(
        forge,
        IntegrationRules("main", Set("unit"), true, "policy", Set("merge"))
      ).execute(input(), "denied")
      assertTrue(
        denied.records.head.refusal
          .contains(Refusal.CapabilityDenied("commit")),
        forge.mutations.isEmpty
      )
    },
    test("SDLC-INT-03a: stale ref is reconciled and refused without mutation") {
      val forge = new FakeForge(staleBefore = Some(Verb.Commit))
      val run = workflow(forge).execute(input(), "stale")
      assertTrue(
        run.records.head.refusal.exists(_.isInstanceOf[Refusal.StaleRef]),
        forge.reconciles == 1,
        forge.mutations.isEmpty
      )
    },
    test(
      "SDLC-INT-03b: unknown outcome takes a read-only reconcile path, never retries"
    ) {
      val forge = new FakeForge(Some(Verb.Commit -> ForgeOutcome.Unknown))
      val run = workflow(forge).execute(input(), "unknown")
      assertTrue(
        run.records.head.refusal.exists(_.isInstanceOf[Refusal.UnknownOutcome]),
        forge.mutations == List(Verb.Commit),
        forge.reconciles == 2
      )
    },
    test("SDLC-INT-02c: duplicate PR outcome is a typed refusal") {
      val forge =
        new FakeForge(Some(Verb.PullRequestCreate -> ForgeOutcome.Duplicate))
      val run = workflow(forge).execute(input(), "duplicate")
      assertTrue(
        run.records.last.refusal.exists(_.isInstanceOf[Refusal.Duplicate]),
        forge.mutations == List(Verb.Commit, Verb.PullRequestCreate)
      )
    }
  )
