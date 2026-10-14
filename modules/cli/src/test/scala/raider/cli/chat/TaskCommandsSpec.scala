package raider.cli.chat

import raider.core.RaiderError
import zio.*
import zio.test.*
import zio.test.Assertion.*

/** Explicit task-selection commands (RAI-155.b): parsing, mark/select/check
  * operations, renderers, and reopen notices (ADR-033 §4.1/§5.3/§11.1).
  */
object TaskCommandsSpec extends ZIOSpecDefault:

  private val refs = ConfigRefs("/tmp/ws", "mock", "test-model")

  private def turn(
      id: String,
      kind: String = TurnLedger.Kind.Ordinary,
      status: String = TurnLedger.Status.Completed
  ): TurnRecord =
    TurnRecord(
      turnId = id,
      kind = kind,
      request = s"request of $id",
      jobId = s"job-$id",
      status = status,
      configRefs = refs,
      atMs = 1L,
      finishedAtMs = Some(2L)
    )

  private def ledgerOf(ts: TurnRecord*): UIO[Ref[Vector[TurnRecord]]] =
    Ref.make(Vector(ts*))

  override def spec: Spec[Any, Any] =
    suite("TaskCommands")(
      suite("parse")(
        test("recognizes all subcommands") {
          assertTrue(
            TaskCommands.parse("list") == TaskCommands.ListCmd,
            TaskCommands.parse("status") == TaskCommands.StatusCmd,
            TaskCommands.parse("mark 2 intent") == TaskCommands
              .Mark(2, TurnLedger.Kind.Intent),
            TaskCommands.parse("mark 3 refinement") == TaskCommands
              .Mark(3, TurnLedger.Kind.Refinement),
            TaskCommands.parse("mark 1 ordinary") == TaskCommands
              .Mark(1, TurnLedger.Kind.Ordinary),
            TaskCommands.parse("select 4") == TaskCommands.Select(4),
            TaskCommands.parse("check compile sbt --batch compile") ==
              TaskCommands.Check("compile", List("sbt", "--batch", "compile"))
          )
        },
        test("malformed input becomes usage, unknown kinds are rejected") {
          val u1 = TaskCommands.parse("")
          val u2 = TaskCommands.parse("mark x intent")
          val u3 = TaskCommands.parse("mark 1 banana")
          val u4 = TaskCommands.parse("select")
          val u5 = TaskCommands.parse("check compile")
          val u6 = TaskCommands.parse("frobnicate")
          assertTrue(
            u1.isInstanceOf[TaskCommands.Usage],
            u2.isInstanceOf[TaskCommands.Usage],
            u3.isInstanceOf[TaskCommands.Usage],
            u4.isInstanceOf[TaskCommands.Usage],
            u5.isInstanceOf[TaskCommands.Usage],
            u6.isInstanceOf[TaskCommands.Usage]
          )
        }
      ),
      suite("mark")(
        test("sets the kind of the 1-based ledger entry") {
          for
            ref <- ledgerOf(turn("t1"), turn("t2"), turn("t3"))
            _ <- TaskCommands.mark(ref, 2, TurnLedger.Kind.Intent)
            ts <- ref.get
          yield assertTrue(
            ts(1).kind == TurnLedger.Kind.Intent,
            ts(0).kind == TurnLedger.Kind.Ordinary,
            ts(2).kind == TurnLedger.Kind.Ordinary
          )
        },
        test(
          "out-of-range index is a typed InputValidation, ledger untouched"
        ) {
          for
            ref <- ledgerOf(turn("t1"))
            e1 <- TaskCommands.mark(ref, 0, TurnLedger.Kind.Intent).flip
            e2 <- TaskCommands.mark(ref, 5, TurnLedger.Kind.Intent).flip
            ts <- ref.get
          yield assertTrue(
            e1.isInstanceOf[RaiderError.InputValidation],
            e2.isInstanceOf[RaiderError.InputValidation],
            ts.size == 1,
            ts.head.kind == TurnLedger.Kind.Ordinary
          )
        }
      ),
      suite("registerCheck")(
        test("appends a new check and replaces an existing one in place") {
          for
            ref <- Ref.make(List.empty[NamedCheck])
            _ <- TaskCommands.registerCheck(
              ref,
              "compile",
              List("sbt", "--batch", "compile")
            )
            _ <- TaskCommands.registerCheck(ref, "lint", List("sbt", "lint"))
            _ <- TaskCommands.registerCheck(
              ref,
              "compile",
              List("sbt", "Test/compile")
            )
            cs <- ref.get
          yield assertTrue(
            cs.map(_.name) == List("compile", "lint"),
            cs.head.argv == List("sbt", "Test/compile")
          )
        },
        test("invalid argv is a typed InputValidation") {
          for
            ref <- Ref.make(List.empty[NamedCheck])
            err <- TaskCommands.registerCheck(ref, "bad name!", List("x")).flip
          yield assertTrue(err.isInstanceOf[RaiderError.InputValidation])
        }
      ),
      suite("select")(
        test(
          "validates and returns the snapshot for the renderer (.c entry point)"
        ) {
          val ledger = List(
            turn("t1"),
            turn("t2"),
            turn("t3", TurnLedger.Kind.Intent),
            turn("t4", TurnLedger.Kind.Refinement)
          )
          val res =
            TaskCommands.select(ledger, List(NamedCheck("c", List("x"))), 2, 12)
          assertTrue(
            res.isRight,
            res.map(_.intent.turnId) == Right("t3"),
            res.map(_.refinements.map(_.turnId)) == Right(List("t4"))
          )
        },
        test("refusals ride the typed CWF detail") {
          val ledger = List(turn("t1", status = TurnLedger.Status.Unknown))
          val res =
            TaskCommands.select(ledger, List(NamedCheck("c", List("x"))), 1, 12)
          val legacy = TaskCommands.select(Nil, Nil, 1, 12)
          assertTrue(
            res.left.exists(_.detail.startsWith("CWF-R3")),
            legacy.left.exists(_.detail.startsWith("CWF-R2"))
          )
        }
      ),
      suite("renderers")(
        test("renderList names every turn with kind/status") {
          val out = TaskCommands.renderList(
            Vector(
              turn("t1"),
              turn("t2", TurnLedger.Kind.Intent, TurnLedger.Status.Failed)
            )
          )
          assertTrue(
            out.contains("t1"),
            out.contains("t2"),
            out.contains("Intent"),
            out.contains("Failed")
          )
        },
        test(
          "renderSelection names intent, refinements, checks, and the bound disclaimer"
        ) {
          val sel = TaskSelection(
            turns = List(
              turn("t3", TurnLedger.Kind.Intent),
              turn("t4", TurnLedger.Kind.Refinement)
            ),
            intent = turn("t3", TurnLedger.Kind.Intent),
            refinements = List(turn("t4", TurnLedger.Kind.Refinement)),
            checks =
              List(NamedCheck("compile", List("sbt", "--batch", "compile"))),
            maxRoundsBound = 12
          )
          val out = TaskCommands.renderSelection(sel)
          assertTrue(
            out.contains("t3"),
            out.contains("t4"),
            out.contains("compile = sbt --batch compile"),
            out.contains("maxRoundsBound=12"),
            out.contains("execution time")
          )
        },
        test("renderStatus distinguishes ready / not-ready / empty") {
          val empty = TaskCommands.renderStatus(Vector.empty, Nil, None)
          val notReady = TaskCommands.renderStatus(
            Vector(turn("t1")),
            Nil,
            None
          )
          val sel = TaskSelection(
            turns = List(turn("t1", TurnLedger.Kind.Intent)),
            intent = turn("t1", TurnLedger.Kind.Intent),
            refinements = Nil,
            checks = List(NamedCheck("c", List("x"))),
            maxRoundsBound = 12
          )
          val ready = TaskCommands.renderStatus(
            Vector(turn("t1", TurnLedger.Kind.Intent)),
            sel.checks,
            Some(sel)
          )
          assertTrue(
            empty.contains("NOT READY"),
            empty.contains("no turn ledger"),
            notReady.contains("NOT READY"),
            notReady.contains(":task mark"),
            ready.contains("READY")
          )
        }
      ),
      suite("restoreNotices")(
        test("legacy empty ledger is named explicitly with the remedy") {
          val notices =
            TaskCommands.restoreNotices(Nil, ConfigRefs("/w", "mock", "m"))
          assertTrue(
            notices.size == 1,
            notices.head.contains("no turn ledger"),
            notices.head.contains(":task mark")
          )
        },
        test("config mismatch is displayed explicitly before work continues") {
          val saved = List(
            turn("t1").copy(configRefs =
              ConfigRefs("/old-ws", "openai", "gpt-x")
            )
          )
          val notices =
            TaskCommands.restoreNotices(
              saved,
              ConfigRefs("/new-ws", "openai", "gpt-x")
            )
          assertTrue(
            notices.exists(_.contains("config mismatch")),
            notices.exists(
              _.contains("workspace: saved=/old-ws active=/new-ws")
            ),
            notices.exists(_.contains("does not restore authority"))
          )
        },
        test("matching config says so") {
          val saved = List(turn("t1"))
          val notices =
            TaskCommands.restoreNotices(saved, refs)
          assertTrue(notices.exists(_.contains("match")))
        }
      )
    )
