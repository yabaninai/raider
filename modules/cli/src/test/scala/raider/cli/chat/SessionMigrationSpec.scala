package raider.cli.chat

import raider.core.{RaiderError, RequestMessage}
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** Session schema v2 migration contract (RAI-077): v1 documents (no
  * `schemaVersion` field) migrate in memory to v2 with the new optional fields
  * empty; v2 documents roundtrip byte-stably; documents from a newer schema
  * version fail with a typed error instead of being read silently.
  */
object SessionMigrationSpec extends ZIOSpecDefault:

  private def tmpBase(): UIO[java.nio.file.Path] = ZIO.succeed {
    Files.createTempDirectory("raider-sessions-migration-test")
  }

  private def writeRaw(base: java.nio.file.Path, id: String, text: String) =
    ZIO.succeed {
      val p = base.resolve(s"$id.json")
      Files.writeString(p, text, UTF_8)
      p
    }

  private val v1Doc =
    """{"id":"legacy","messages":[{"role":"user","content":"old"},{"role":"assistant","content":"done"}],"model":"m1","timestamp":"2026-01-01T00:00:00Z"}"""

  private val msgs = List(
    RequestMessage("user", "hello"),
    RequestMessage("assistant", "hi there")
  )

  override def spec: Spec[Any, Any] =
    suite("SessionMigration")(
      test("v1 fixture (no schemaVersion) loads and migrates to v2 in memory") {
        for
          base <- tmpBase()
          _ <- writeRaw(base, "legacy", v1Doc)
          back <- SessionStore.load(base, "legacy")
        yield assertTrue(
          back.schemaVersion == SavedSession.SchemaVersion,
          back.schemaVersion == 2,
          back.id == "legacy",
          back.messages == List(
            RequestMessage("user", "old"),
            RequestMessage("assistant", "done")
          ),
          back.model == "m1",
          back.timestamp == "2026-01-01T00:00:00Z",
          back.workspace.isEmpty,
          back.label.isEmpty
        )
      },
      test(
        "explicit schemaVersion 1 migrates the same way as a missing field"
      ) {
        val doc = v1Doc.replace(
          "\"timestamp\":\"2026-01-01T00:00:00Z\"",
          "\"timestamp\":\"2026-01-01T00:00:00Z\",\"schemaVersion\":1"
        )
        for
          base <- tmpBase()
          _ <- writeRaw(base, "explicit-v1", doc)
          back <- SessionStore.load(base, "explicit-v1")
        yield assertTrue(
          back.schemaVersion == 2,
          back.model == "m1",
          back.workspace.isEmpty,
          back.label.isEmpty
        )
      },
      test("v2 roundtrip is byte-stable (save -> load -> save, same bytes)") {
        for
          base <- tmpBase()
          s = SavedSession(
            "stable",
            msgs,
            "model-x",
            "2026-10-09T12:00:00Z",
            workspace = Some("/tmp/workspace"),
            label = Some("fix login bug")
          )
          p1 <- SessionStore.save(base, s)
          bytes1 <- ZIO.succeed(Files.readAllBytes(p1))
          back <- SessionStore.load(base, "stable")
          p2 <- SessionStore.save(base, back)
          bytes2 <- ZIO.succeed(Files.readAllBytes(p2))
        yield assertTrue(
          back == s,
          back.workspace == Some("/tmp/workspace"),
          back.label == Some("fix login bug"),
          bytes1.sameElements(bytes2)
        )
      },
      test("a fresh save always writes schemaVersion 2 on disk") {
        for
          base <- tmpBase()
          _ <- SessionStore.save(
            base,
            SavedSession("fresh", msgs, "m", "t")
          )
          text <- ZIO.succeed {
            Files.readString(base.resolve("fresh.json"), UTF_8)
          }
        yield assertTrue(text.contains("\"schemaVersion\":2"))
      },
      test("future schemaVersion (3 > 2) is a typed error, never silent-read") {
        val doc =
          """{"id":"future","messages":[{"role":"user","content":"x"}],"model":"m","timestamp":"t","schemaVersion":3}"""
        for
          base <- tmpBase()
          _ <- writeRaw(base, "future", doc)
          err <- SessionStore.load(base, "future").flip
        yield assertTrue(
          err.isInstanceOf[RaiderError.InputValidation],
          err.detail.contains("schemaVersion 3"),
          err.detail.contains("2")
        )
      },
      test("future-version file is skipped by list; readable sessions remain") {
        for
          base <- tmpBase()
          _ <- SessionStore.save(
            base,
            SavedSession("a-current", msgs, "m", "t")
          )
          _ <- writeRaw(base, "b-legacy", v1Doc)
          _ <- writeRaw(
            base,
            "c-future",
            """{"id":"c-future","messages":[],"model":"m","timestamp":"t","schemaVersion":99}"""
          )
          listed <- SessionStore.list(base)
        yield assertTrue(
          listed.map(_.id) == List("a-current", "legacy")
        )
      },
      test("invalid schemaVersion values are typed errors, never silent-read") {
        val docs = List(
          "not-a-number" -> """{"id":"weird","messages":[],"model":"m","timestamp":"t","schemaVersion":"banana"}""",
          "negative" -> """{"id":"weird","messages":[],"model":"m","timestamp":"t","schemaVersion":-1}""",
          "boolean" -> """{"id":"weird","messages":[],"model":"m","timestamp":"t","schemaVersion":true}"""
        )
        for errs <- ZIO.foreach(docs) { case (name, doc) =>
            for
              base <- tmpBase()
              _ <- writeRaw(base, name, doc)
              err <- SessionStore.load(base, name).flip
            yield err
          }
        yield assertTrue(
          errs.forall(_.isInstanceOf[RaiderError.InputValidation]),
          errs.forall(_.detail.contains("schemaVersion"))
        )
      },
      test("decodeDocument migrates a v1 fixture without touching the disk") {
        assertTrue(
          SavedSession.decodeDocument(v1Doc) == Right(
            SavedSession(
              "legacy",
              List(
                RequestMessage("user", "old"),
                RequestMessage("assistant", "done")
              ),
              "m1",
              "2026-01-01T00:00:00Z"
            )
          )
        )
      }
    )
