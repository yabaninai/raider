package raider.cli.chat

import zio.test.*
import zio.test.Assertion.*

import raider.core.RaiderError
import raider.dsl.workflow.{StsOrigin, StsParam, StsTurnKind, StsTurnStatus}

/** RAI-160 adapter unit checks: the TaskSelection snapshot -> dsl StsSelection
  * mapping is pure and 1:1 (intent/refinements/checks/agent stage/origin —
  * ADR-033 §4), the R4 secret scan runs at the adapter boundary too (the
  * matched value is never echoed), and the mock/fallback transport cannot be
  * declared as an export origin (typed refusal — no mislabeled origin).
  */
object TaskWorkflowAdapterSpec extends ZIOSpecDefault:

  private def turn(
      id: String,
      kind: String,
      status: String,
      request: String
  ): TurnRecord =
    TurnRecord(
      turnId = id,
      kind = kind,
      request = request,
      jobId = "job-" + id,
      status = status,
      configRefs = ConfigRefs("/tmp/ws", "openai", "test-model"),
      atMs = 0L
    )

  private val checks = List(
    NamedCheck("compile", List("sbt", "--batch", "compile"))
  )

  private val turns = List(
    turn(
      "t-1-aaaa",
      TurnLedger.Kind.Intent,
      TurnLedger.Status.Completed,
      "make module X compile and keep checks green"
    ),
    turn(
      "t-2-bbbb",
      TurnLedger.Kind.Refinement,
      TurnLedger.Status.Completed,
      "do not touch the public API"
    ),
    turn(
      "t-3-cccc",
      TurnLedger.Kind.Refinement,
      TurnLedger.Status.Completed,
      "prefer the smallest diff"
    )
  )

  private val origin = StsOrigin(
    providerKind = "openai-compatible",
    model = "test-model",
    baseUrl = Some("http://127.0.0.1:1/v1")
  )

  private val params = List(
    StsParam("targetPath", "marker.txt"),
    StsParam("summary", "adapter probe task")
  )

  private val selection: TaskSelection =
    TurnLedger.validateSelection(turns, checks, 3, 12).toOption.get

  private def adapt(sel: TaskSelection = selection) =
    TaskWorkflowAdapter.toStsSelection("sess-adapter", origin, sel, params)

  private def refusalOf(
      r: Either[RaiderError, ?]
  ): Option[(String, String)] = r.swap.toOption.map(e => (e.code, e.detail))

  def spec =
    suite("TaskWorkflowAdapter (RAI-160, ADR-033 §4 mapping)")(
      test(
        "pure 1:1 mapping: ids, kinds, statuses, bounded request text, checks, agent stage, params, origin"
      ) {
        val first = adapt()
        val second = adapt()
        val sts = first.toOption.get
        assertTrue(
          first == second, // deterministic pure mapping (§4.3 prerequisite)
          sts.sessionId == "sess-adapter",
          sts.intent.turnId == "t-1-aaaa",
          sts.intent.kind == StsTurnKind.Intent,
          sts.intent.status == StsTurnStatus.Completed,
          sts.intent.text == "make module X compile and keep checks green",
          sts.refinements.map(_.turnId) == List("t-2-bbbb", "t-3-cccc"),
          sts.refinements.forall(_.kind == StsTurnKind.Refinement),
          sts.checks.map(_.name) == List("compile"),
          sts.checks.head.argv == List("sbt", "--batch", "compile"),
          sts.agentStage.maxRounds == 12, // captured maxRoundsBound, 1:1
          sts.taskParams == params,
          sts.origin == origin
        )
      },
      test(
        "the request text flows through the capture bound (§2.5 payload-free bounded text)"
      ) {
        val long = "x" * (TurnLedger.RequestBound + 100)
        val sel = TurnLedger
          .validateSelection(
            List(
              turn(
                "t-1-long",
                TurnLedger.Kind.Intent,
                TurnLedger.Status.Completed,
                long
              ),
              turns(1)
            ),
            checks,
            2,
            12
          )
          .toOption
          .get
        val sts = adapt(sel).toOption.get
        assertTrue(
          sts.intent.text.length == TurnLedger.RequestBound,
          sts.intent.text == TurnLedger.boundedRequest(long)
        )
      },
      test(
        "R4 at the adapter boundary: a secret-bearing snapshot is refused independently of select time; the value is never echoed"
      ) {
        // Built DIRECTLY (bypassing validateSelection, which scans at select
        // time): the adapter must be the second, independent gate.
        val secret =
          "sk-" + "abcdefghijklmnopqrstuvwxyz012345" // synthetic; concatenated so the secret-literal scanner does not flag the refusal fixture
        val leakTurn = turn(
          "t-4-leak",
          TurnLedger.Kind.Refinement,
          TurnLedger.Status.Completed,
          "use key " + secret + " for the stand"
        )
        val leaky = selection.copy(
          turns = selection.turns :+ leakTurn,
          refinements = selection.refinements :+ leakTurn
        )
        val refused = adapt(leaky)
        val (code, detail) = refusalOf(refused).get
        assertTrue(
          code == "RA-INP",
          detail.startsWith("CWF-R4"),
          detail.contains("t-4-leak"),
          !detail.contains(secret)
        )
      },
      test(
        "R4 at the adapter boundary also covers check argv (never echoed)"
      ) {
        val github =
          "gh" + "p_" + "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnop" // synthetic ghp_ shape, concatenated
        val leakyChecks = List(
          NamedCheck(
            "upload",
            List("curl", "-H", "Authorization: Bearer " + github)
          )
        )
        val sel =
          TurnLedger.validateSelection(
            turns,
            leakyChecks,
            3,
            12
          ) // select already refuses
        val direct = selection.copy(checks = leakyChecks)
        val refused = adapt(direct)
        val (code, detail) = refusalOf(refused).get
        assertTrue(
          sel.isLeft, // select-time gate agrees
          code == "RA-INP",
          detail.startsWith("CWF-R4"),
          detail.contains("upload"),
          !detail.contains(github)
        )
      },
      test(
        "status maps 1:1 without editorializing (a non-Completed turn maps through; the renderer refuses it downstream)"
      ) {
        val direct = TaskSelection(
          turns = List(
            turn(
              "t-1-i",
              TurnLedger.Kind.Intent,
              TurnLedger.Status.Failed,
              "failed intent"
            ),
            turn(
              "t-2-u",
              TurnLedger.Kind.Refinement,
              TurnLedger.Status.Unknown,
              "torn refinement"
            )
          ),
          intent = turn(
            "t-1-i",
            TurnLedger.Kind.Intent,
            TurnLedger.Status.Failed,
            "failed intent"
          ),
          refinements = List(
            turn(
              "t-2-u",
              TurnLedger.Kind.Refinement,
              TurnLedger.Status.Unknown,
              "torn refinement"
            )
          ),
          checks = checks,
          maxRoundsBound = 6
        )
        val sts = adapt(direct).toOption.get
        assertTrue(
          sts.intent.status == StsTurnStatus.Failed,
          sts.refinements.head.status == StsTurnStatus.Unknown
        )
      },
      test(
        "origin: chat transports map honestly; mock/fallback is a typed refusal, never a mislabeled origin"
      ) {
        val openai =
          TaskWorkflowAdapter.chatProviderKind("openai").toOption.get
        val anthropic =
          TaskWorkflowAdapter.chatProviderKind("anthropic").toOption.get
        val mock = TaskWorkflowAdapter.chatProviderKind("mock")
        val blankModel = TaskWorkflowAdapter.toStsOrigin("openai", "  ", "")
        val blankUrl =
          TaskWorkflowAdapter.toStsOrigin("openai", "m", "").toOption.get
        assertTrue(
          openai == "openai-compatible",
          anthropic == "anthropic-compatible",
          refusalOf(mock).exists(_._1 == "RA-INP"),
          refusalOf(mock).exists(_._2.startsWith("CWF-R1")),
          blankModel.isLeft,
          blankUrl.baseUrl.isEmpty
        )
      }
    )

end TaskWorkflowAdapterSpec
