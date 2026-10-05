package raider.cli.chat

import raider.core.{RaiderError, RequestMessage}
import zio.*
import zio.test.*

import java.nio.file.Files
import java.time.Instant

/** Session persistence contract (nightly Phase 2.2): save/load/list over a
  * caller-provided base directory, with sanitized ids (no path traversal),
  * atomic writes, and typed errors for missing/corrupt sessions.
  */
object SessionStoreSpec extends ZIOSpecDefault:

  private def tmpBase(): UIO[java.nio.file.Path] = ZIO.succeed {
    Files.createTempDirectory("raider-sessions-test")
  }

  private val msgs = List(
    RequestMessage("user", "hello"),
    RequestMessage("assistant", "hi there"),
    RequestMessage("user", "fix the bug")
  )

  override def spec: Spec[Any, Any] =
    suite("SessionStore")(
      test("save writes parseable JSON; load roundtrips messages + model") {
        for
          base <- tmpBase()
          s = SavedSession("work-1", msgs, "local", Instant.now().toString)
          path <- SessionStore.save(base, s)
          back <- SessionStore.load(base, "work-1")
        yield assertTrue(
          Files.exists(path),
          path.getFileName.toString == "work-1.json",
          back == s,
          back.messages == msgs,
          back.model == "local"
        )
      },
      test("save over an existing id replaces it (atomic temp+rename)") {
        for
          base <- tmpBase()
          _ <- SessionStore.save(
            base,
            SavedSession("s", List(RequestMessage("user", "v1")), "m", "t1")
          )
          _ <- SessionStore.save(
            base,
            SavedSession("s", msgs, "m2", "t2")
          )
          back <- SessionStore.load(base, "s")
        yield assertTrue(
          back.messages == msgs,
          back.model == "m2",
          back.timestamp == "t2"
        )
      },
      test("load of a missing session is a typed InputValidation") {
        for
          base <- tmpBase()
          err <- SessionStore.load(base, "nope").flip
        yield assertTrue(err.isInstanceOf[RaiderError.InputValidation])
      },
      test("load of a corrupt session file is a typed InputValidation") {
        for
          base <- tmpBase()
          _ <- ZIO.succeed {
            val p = base.resolve("bad.json")
            Files.writeString(p, "{not json at all")
          }
          err <- SessionStore.load(base, "bad").flip
        yield assertTrue(err.isInstanceOf[RaiderError.InputValidation])
      },
      test("list returns sessions sorted by id; empty dir is empty") {
        for
          base <- tmpBase()
          empty <- SessionStore.list(base)
          _ <- SessionStore.save(
            base,
            SavedSession("b", msgs, "m", Instant.now().toString)
          )
          _ <- SessionStore.save(
            base,
            SavedSession("a", msgs, "m", Instant.now().toString)
          )
          nonEmpty <- SessionStore.list(base)
        yield assertTrue(
          empty == Nil,
          nonEmpty.map(_.id) == List("a", "b")
        )
      },
      test("ids with path separators / traversal are rejected") {
        val bad = List("../evil", "a/b", "..", ".", "", "x\\y")
        for results <- ZIO.foreach(bad)(id =>
            ZIO.succeed(SessionStore.sanitize(id))
          )
        yield assertTrue(results.forall(_.isEmpty))
      },
      test("normal ids sanitize to themselves") {
        val good = List("work-1", "fix_2026", "A.B", "abc123")
        for results <- ZIO.foreach(good)(id =>
            ZIO.succeed(SessionStore.sanitize(id))
          )
        yield assertTrue(results == good.map(Some(_)))
      }
    )
