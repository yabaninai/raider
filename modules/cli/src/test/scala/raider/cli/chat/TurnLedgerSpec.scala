package raider.cli.chat

import raider.core.RaiderError
import raider.core.trace.TraceEvent
import zio.test.*
import zio.test.Assertion.*

/** Turn-ledger invariants (RAI-155.b, ADR-033 §5): bounded summaries without
  * payloads, STS selection validation with typed CWF-R refusals, terminal
  * status mapping, credential-shape scanning that never echoes values, and
  * decode-time ledger validation.
  */
object TurnLedgerSpec extends ZIOSpecDefault:

  private val refs = ConfigRefs("/tmp/ws", "mock", "test-model")

  private def turn(
      id: String,
      kind: String = TurnLedger.Kind.Ordinary,
      status: String = TurnLedger.Status.Completed,
      request: String = "body",
      tools: List[ToolOutcomeSummary] = Nil
  ): TurnRecord =
    TurnRecord(
      turnId = id,
      kind = kind,
      request = request,
      jobId = s"job-$id",
      status = status,
      configRefs = refs,
      atMs = 1_700_000_000_000L,
      toolSummary = tools,
      finishedAtMs = Some(1_700_000_000_001L)
    )

  private val check = NamedCheck("compile", List("sbt", "--batch", "compile"))

  override def spec: Spec[Any, Any] =
    suite("TurnLedger")(
      suite("toolOutcomes")(
        test("extracts ok/failed/refused classes; payloads never enter") {
          val events = Vector(
            TraceEvent.RunStarted("at", "m", 3),
            TraceEvent.ToolCallStarted("at", 0, "fs_edit", "c1", "{}"),
            TraceEvent.ToolCallFinished(
              "at",
              0,
              "fs_edit",
              "c1",
              """{"patch":"SECRET-PAYLOAD-LONG-TEXT"}""",
              success = true
            ),
            TraceEvent.ToolCallStarted("at", 1, "proc_run", "c2", "{}"),
            TraceEvent.ToolCallFinished(
              "at",
              1,
              "proc_run",
              "c2",
              """{"stdout":"compile error payload"}""",
              success = false
            ),
            TraceEvent.ToolCallRefused("at", 2, "fs_read", "c3", "denied")
          )
          val out = TurnLedger.toolOutcomes(events)
          assertTrue(
            out == List(
              ToolOutcomeSummary("fs_edit", ToolOutcomeSummary.Ok),
              ToolOutcomeSummary("proc_run", ToolOutcomeSummary.Failed),
              ToolOutcomeSummary("fs_read", ToolOutcomeSummary.Refused)
            ),
            out.forall(_.toString.contains("PAYLOAD") == false)
          )
        },
        test("summary is bounded by ToolSummaryBound") {
          val events = (1 to 100).map { i =>
            TraceEvent.ToolCallFinished("at", i, s"tool$i", s"c$i", "{}", true)
          }.toVector
          assertTrue(
            TurnLedger.toolOutcomes(events).size == TurnLedger.ToolSummaryBound
          )
        }
      ),
      suite("validateSelection")(
        test("happy path: one intent + ordered refinements over a span") {
          val ledger = List(
            turn("t1", request = "make module X compile and keep checks green"),
            turn("t2", request = "filler context"),
            turn("t3", kind = TurnLedger.Kind.Intent),
            turn("t4", kind = TurnLedger.Kind.Refinement),
            turn("t5", kind = TurnLedger.Kind.Refinement)
          )
          val res = TurnLedger.validateSelection(ledger, List(check), 3, 12)
          assertTrue(
            res.isRight,
            res.map(_.intent.turnId) == Right("t3"),
            res.map(_.refinements.map(_.turnId)) == Right(List("t4", "t5")),
            res.map(_.turnIds) == Right(List("t3", "t4", "t5")),
            res.map(_.maxRoundsBound) == Right(12)
          )
        },
        test(
          "empty ledger is the typed legacy/no-context refusal (R2) with remedy"
        ) {
          val res = TurnLedger.validateSelection(Nil, List(check), 1, 12)
          assertTrue(
            res == Left(
              RaiderError.InputValidation(
                "CWF-R2: this session has no turn ledger (conversation-only " +
                  "save or nothing captured yet); export cannot invent absent " +
                  "execution records — re-run the task in the current chat and " +
                  "mark it explicitly with :task mark"
              )
            )
          )
        },
        test(
          "span out of range is a typed unsupported-selection refusal (R1)"
        ) {
          val ledger = List(turn("t1"))
          val zero = TurnLedger.validateSelection(ledger, List(check), 0, 12)
          val big = TurnLedger.validateSelection(ledger, List(check), 5, 12)
          val huge = TurnLedger.validateSelection(
            ledger,
            List(check),
            TurnLedger.SpanBound + 1,
            12
          )
          assertTrue(
            zero.left.exists(_.detail.startsWith("CWF-R1")),
            big.left.exists(_.detail.startsWith("CWF-R1")),
            huge.left.exists(_.detail.startsWith("CWF-R1"))
          )
        },
        test(
          "missing intent, duplicate intents, and missing checks are typed R1"
        ) {
          val noIntent = List(turn("t1"), turn("t2"))
          val twoIntents = List(
            turn("t1", TurnLedger.Kind.Intent),
            turn("t2", TurnLedger.Kind.Intent)
          )
          val r1 = List(
            TurnLedger.validateSelection(noIntent, List(check), 2, 12),
            TurnLedger.validateSelection(twoIntents, List(check), 2, 12),
            TurnLedger.validateSelection(
              List(turn("t1", TurnLedger.Kind.Intent)),
              Nil,
              1,
              12
            )
          )
          assertTrue(
            r1.forall(_.left.exists(_.detail.startsWith("CWF-R1"))),
            r1.exists(_.left.exists(_.detail.contains("exactly ONE"))),
            r1.exists(_.left.exists(_.detail.contains("named trusted checks")))
          )
        },
        test(
          "non-Completed turns in the selection are refused with offending ids (R3)"
        ) {
          val ledger = List(
            turn("t1", TurnLedger.Kind.Intent),
            turn("t2", status = TurnLedger.Status.Interrupted),
            turn("t3", status = TurnLedger.Status.Unknown),
            turn("t4", status = TurnLedger.Status.Failed)
          )
          val res = TurnLedger.validateSelection(ledger, List(check), 4, 12)
          assertTrue(
            res.left.exists(_.detail.startsWith("CWF-R3")),
            res.left.exists(_.detail.contains("t2[Interrupted]")),
            res.left.exists(_.detail.contains("t3[Unknown]")),
            res.left.exists(_.detail.contains("t4[Failed]"))
          )
        },
        test(
          "credential-shaped material refuses with location+class, never the value (R4)"
        ) {
          // sample material assembled from fragments so the SOURCE never
          // contains a credential-shaped literal (forbidden-apis discipline);
          // the runtime value still matches the shapes
          val secret = "sk-" + "abc123def456ghi789jkl0"
          val ledger = List(
            turn(
              "t1",
              TurnLedger.Kind.Intent,
              request = s"set api_key = $secret please"
            ),
            turn("t2")
          )
          val res = TurnLedger.validateSelection(ledger, List(check), 2, 12)
          assertTrue(
            res.left.exists(_.detail.startsWith("CWF-R4")),
            res.left.exists(!_.detail.contains(secret)),
            res.left.exists(_.detail.contains("t1"))
          )
        }
      ),
      suite("secretHit")(
        test("detects AWS / GitHub / OpenAI shapes and key assignments") {
          val aws = "use AKIA" + "IOSFODNN7EXAMPLE for the call"
          val github = "token=ghp_" + "0123456789abcdefghijklmnopqrstuv"
          val t1 = turn("t1", request = aws)
          val t2 = turn("t2", request = github)
          val t3 =
            turn("t3", request = "password: hunter2correcthorsebatterystaple")
          assertTrue(
            TurnLedger.secretHit(List(t1), Nil).exists(_.contains("t1")),
            TurnLedger.secretHit(List(t2), Nil).exists(_.contains("t2")),
            // long key-like material after an assignment keyword is flagged
            // (over-refusal is the safe direction for export)
            TurnLedger.secretHit(List(t3), Nil).exists(_.contains("t3"))
          )
        },
        test("clean selections and ordinary words produce no hit") {
          val clean = List(
            turn(
              "t1",
              request =
                "read the token bucket limiter implementation and fix it"
            ),
            turn("t2", request = "api_key from the env helper reference only")
          )
          assertTrue(TurnLedger.secretHit(clean, List(check)).isEmpty)
        }
      ),
      suite("terminalStatus")(
        test("typed failure -> Failed; interruption/defect -> Interrupted") {
          assertTrue(
            TurnLedger.terminalStatus(
              Some(RaiderError.ProviderUnavailable("x"))
            ) ==
              TurnLedger.Status.Failed,
            TurnLedger.terminalStatus(None) == TurnLedger.Status.Interrupted
          )
        }
      ),
      suite("beginTurn/finishTurn")(
        test(
          "begins Unknown with bounded request; terminal write carries tools"
        ) {
          val long = "x" * (TurnLedger.RequestBound + 500)
          val begun = TurnLedger.beginTurn(long, "job-1", refs, 42L)
          val tools = List(ToolOutcomeSummary("fs_edit", ToolOutcomeSummary.Ok))
          val finished = TurnLedger.finishTurn(
            begun.copy(kind = TurnLedger.Kind.Intent),
            TurnLedger.Status.Completed,
            tools,
            43L
          )
          assertTrue(
            begun.status == TurnLedger.Status.Unknown,
            begun.kind == TurnLedger.Kind.Ordinary,
            begun.request.length == TurnLedger.RequestBound,
            finished.status == TurnLedger.Status.Completed,
            finished.kind == TurnLedger.Kind.Intent,
            finished.toolSummary == tools,
            finished.finishedAtMs == Some(43L)
          )
        }
      ),
      suite("NamedCheck.validate")(
        test("valid argv accepted (the :fix shape)") {
          val res =
            NamedCheck.validate("compile", List("sbt", "--batch", "compile"))
          assertTrue(
            res.isRight,
            res.map(_.argv) == Right(List("sbt", "--batch", "compile"))
          )
        },
        test("bad names and malformed argv are typed InputValidation") {
          val bad = List(
            NamedCheck.validate("", List("x")),
            NamedCheck.validate("a b", List("x")),
            NamedCheck.validate("ok", Nil),
            NamedCheck.validate("ok", List("")),
            NamedCheck.validate("ok", List("s\bt")),
            NamedCheck.validate("ok", (1 to 17).map(_.toString).toList)
          )
          assertTrue(
            bad.forall(
              _.left.exists(_.isInstanceOf[RaiderError.InputValidation])
            )
          )
        }
      ),
      suite("validateTurnFields")(
        test("valid ledger passes") {
          assertTrue(
            TurnLedger
              .validateTurnFields(
                List(turn("t1"), turn("t2", TurnLedger.Kind.Intent))
              )
              .isRight
          )
        },
        test("invalid kind/status/ids are named entry-level errors") {
          val res =
            TurnLedger.validateTurnFields(List(turn("t1", kind = "Banana")))
          val res2 =
            TurnLedger.validateTurnFields(List(turn("t1", status = "Maybe")))
          val res3 = TurnLedger.validateTurnFields(List(turn("")))
          assertTrue(
            res.left.exists(_.contains("invalid kind 'Banana'")),
            res2.left.exists(_.contains("invalid status 'Maybe'")),
            res3.left.exists(_.contains("empty turnId"))
          )
        }
      ),
      suite("ConfigRefs.mismatch")(
        test("names each differing field; none when equal") {
          val saved = ConfigRefs("/old-ws", "openai", "gpt-x")
          val active = ConfigRefs("/new-ws", "anthropic", "claude-y")
          val diffs = ConfigRefs.mismatch(saved, active)
          assertTrue(
            diffs.size == 3,
            diffs.exists(_.contains("workspace: saved=/old-ws active=/new-ws")),
            diffs.exists(_.contains("provider: saved=openai active=anthropic")),
            diffs.exists(_.contains("model: saved=gpt-x active=claude-y")),
            ConfigRefs.mismatch(saved, saved).isEmpty
          )
        }
      )
    )
