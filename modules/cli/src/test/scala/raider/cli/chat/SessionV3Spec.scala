package raider.cli.chat

import raider.core.{RaiderError, RequestMessage}
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** Session schema v3 (RAI-155.b, ADR-033 §5.2, ADDITIVE): the exportable turn
  * ledger + named checks persist beside the message history; v1/v2 documents
  * load read-only with the new fields empty; a present-but-malformed ledger is
  * a typed corrupt-document error (never silently emptied); byte-stable v3
  * roundtrip.
  */
object SessionV3Spec extends ZIOSpecDefault:

  private def tmpBase(): UIO[java.nio.file.Path] = ZIO.succeed {
    Files.createTempDirectory("raider-sessions-v3-test")
  }

  private def writeRaw(base: java.nio.file.Path, id: String, text: String) =
    ZIO.succeed {
      val p = base.resolve(s"$id.json")
      Files.writeString(p, text, UTF_8)
      p
    }

  private val refs = ConfigRefs("/tmp/ws-a", "mock", "test-model")

  private def turn(id: String, kind: String, status: String): TurnRecord =
    TurnRecord(
      turnId = id,
      kind = kind,
      request = s"request $id",
      jobId = s"job-$id",
      status = status,
      configRefs = refs,
      atMs = 1_700_000_000_000L,
      toolSummary = List(ToolOutcomeSummary("fs_edit", ToolOutcomeSummary.Ok)),
      finishedAtMs = Some(1_700_000_000_005L)
    )

  private val turns = List(
    turn("t-1", TurnLedger.Kind.Intent, TurnLedger.Status.Completed),
    turn("t-2", TurnLedger.Kind.Refinement, TurnLedger.Status.Completed),
    turn("t-3", TurnLedger.Kind.Ordinary, TurnLedger.Status.Interrupted)
  )

  private val checks = List(
    NamedCheck("compile", List("sbt", "--batch", "compile")),
    NamedCheck("lint", List("sbt", "--batch", "scalafmtCheckAll"))
  )

  private def session(id: String) = SavedSession(
    id = id,
    messages = List(
      RequestMessage("user", "fix the failing module compile"),
      RequestMessage("assistant", "[fixture] done")
    ),
    model = "test-model",
    timestamp = "2026-10-14T00:00:00Z",
    turns = turns,
    checks = checks
  )

  override def spec: Spec[Any, Any] =
    suite("SessionV3")(
      test(
        "v3 roundtrip: ledger + checks survive save/load with stable ids/order/status"
      ) {
        for
          base <- tmpBase()
          s = session("v3-rt")
          _ <- SessionStore.save(base, s)
          back <- SessionStore.load(base, "v3-rt")
        yield assertTrue(
          back == s,
          back.turns == turns,
          back.checks == checks,
          back.turns.map(_.turnId) == List("t-1", "t-2", "t-3"),
          back.turns.map(_.kind) ==
            List(
              TurnLedger.Kind.Intent,
              TurnLedger.Kind.Refinement,
              TurnLedger.Kind.Ordinary
            ),
          back.turns.map(_.status) ==
            List(
              TurnLedger.Status.Completed,
              TurnLedger.Status.Completed,
              TurnLedger.Status.Interrupted
            ),
          back.turns.head.toolSummary == List(
            ToolOutcomeSummary("fs_edit", ToolOutcomeSummary.Ok)
          )
        )
      },
      test("v3 save->load->save is byte-stable") {
        for
          base <- tmpBase()
          p1 <- SessionStore.save(base, session("v3-stable"))
          bytes1 <- ZIO.succeed(Files.readAllBytes(p1))
          back <- SessionStore.load(base, "v3-stable")
          p2 <- SessionStore.save(base, back)
          bytes2 <- ZIO.succeed(Files.readAllBytes(p2))
        yield assertTrue(bytes1.sameElements(bytes2))
      },
      test("a fresh save always writes schemaVersion 3 on disk") {
        for
          base <- tmpBase()
          _ <- SessionStore.save(base, session("v3-fresh"))
          text <- ZIO.succeed(
            Files.readString(base.resolve("v3-fresh.json"), UTF_8)
          )
        yield assertTrue(
          text.contains("\"schemaVersion\":3"),
          text.contains("\"turns\":"),
          text.contains("\"checks\":")
        )
      },
      test("legacy v1 document loads read-only: empty ledger + empty checks") {
        val v1 =
          """{"id":"legacy","messages":[{"role":"user","content":"old"}],"model":"m1","timestamp":"2026-01-01T00:00:00Z"}"""
        for
          base <- tmpBase()
          _ <- writeRaw(base, "legacy", v1)
          back <- SessionStore.load(base, "legacy")
        yield assertTrue(
          back.schemaVersion == 3,
          back.turns.isEmpty,
          back.checks.isEmpty,
          back.messages == List(RequestMessage("user", "old"))
        )
      },
      test("legacy v2 document loads read-only the same way") {
        val v2 =
          """{"id":"legacy2","messages":[{"role":"user","content":"old"}],"model":"m1","timestamp":"t","schemaVersion":2,"workspace":"/w","label":"l"}"""
        for
          base <- tmpBase()
          _ <- writeRaw(base, "legacy2", v2)
          back <- SessionStore.load(base, "legacy2")
        yield assertTrue(
          back.schemaVersion == 3,
          back.turns.isEmpty,
          back.checks.isEmpty,
          back.workspace == Some("/w"),
          back.label == Some("l")
        )
      },
      test(
        "malformed ledger field is a typed corrupt error, never silently emptied"
      ) {
        val doc =
          """{"id":"badturns","messages":[],"model":"m","timestamp":"t","schemaVersion":3,"turns":"banana"}"""
        for
          base <- tmpBase()
          _ <- writeRaw(base, "badturns", doc)
          err <- SessionStore.load(base, "badturns").flip
        yield assertTrue(
          err.isInstanceOf[RaiderError.InputValidation],
          err.detail.contains("corrupt")
        )
      },
      test("semantically invalid ledger entries are typed corrupt errors") {
        val badKind =
          """{"id":"badkind","messages":[],"model":"m","timestamp":"t","schemaVersion":3,"turns":[{"turnId":"t1","kind":"Banana","request":"r","jobId":"j","status":"Completed","configRefs":{"workspace":"/w","provider":"p","model":"m"},"atMs":1}]}"""
        val badStatus =
          """{"id":"badstatus","messages":[],"model":"m","timestamp":"t","schemaVersion":3,"turns":[{"turnId":"t1","kind":"Intent","request":"r","jobId":"j","status":"Maybe","configRefs":{"workspace":"/w","provider":"p","model":"m"},"atMs":1}]}"""
        for
          base <- tmpBase()
          _ <- writeRaw(base, "badkind", badKind)
          e1 <- SessionStore.load(base, "badkind").flip
          base2 <- tmpBase()
          _ <- writeRaw(base2, "badstatus", badStatus)
          e2 <- SessionStore.load(base2, "badstatus").flip
        yield assertTrue(
          e1.isInstanceOf[RaiderError.InputValidation],
          e1.detail.contains("invalid kind"),
          e2.isInstanceOf[RaiderError.InputValidation],
          e2.detail.contains("invalid status")
        )
      },
      test(
        "atomic write discipline unchanged: save over existing id replaces"
      ) {
        for
          base <- tmpBase()
          _ <- SessionStore.save(base, session("replace-me"))
          _ <- SessionStore.save(
            base,
            session("replace-me").copy(turns = turns.take(1))
          )
          back <- SessionStore.load(base, "replace-me")
        yield assertTrue(back.turns.map(_.turnId) == List("t-1"))
      }
    )
