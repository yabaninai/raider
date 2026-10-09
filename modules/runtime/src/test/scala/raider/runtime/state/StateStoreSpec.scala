package raider.runtime.state

import raider.core.{JobId, RaiderError, RootId}
import zio.test.*
import zio.{UIO, ZIO}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** RAI-076 acceptance (m1.md STATE-01..03) over both backends.
  *
  * Determinism rules: no sleeps; concurrency fixtures assert interleaving-
  * independent INVARIANTS (exactly one CAS winner, revision = total committed
  * increments) via fiber join, and CAS retry loops are logically guaranteed to
  * terminate under fair scheduling. File fixtures use per-test temp dirs.
  *
  * Default-OFF (roadmap P5 exit) is structural, not fixture-tested: the object
  * holds no registry, spawns no fiber and touches no file — a store exists only
  * where a test/caller explicitly constructs one.
  */
object StateStoreSpec extends ZIOSpecDefault:

  private val ag1 = Scope.Agent(JobId("j_a1"))
  private val ag2 = Scope.Agent(JobId("j_a2"))
  private val rt = Scope.Root(RootId("r_1"))
  private val glob = Scope.Global

  /** Tiny agent-scope bounds for deterministic quota fixtures (8 bytes value
    * cap == 8 chars ASCII, 10 bytes total, 2 keys).
    */
  private val tiny = StateStore.Quotas(
    agent = StateStore.Limits(maxKeys = 2, maxValueLen = 8, maxTotalBytes = 10),
    root = StateStore.Limits.default,
    global = StateStore.Limits.default
  )

  private def tempDir: UIO[Path] =
    ZIO.attempt(Files.createTempDirectory("raider-state-spec")).orDie

  private def writeText(p: Path, text: String): UIO[Unit] =
    ZIO.attempt {
      Files.createDirectories(p.getParent)
      Files.writeString(p, text, StandardCharsets.UTF_8)
      ()
    }.orDie

  private def readBytes(p: Path): UIO[Array[Byte]] =
    ZIO.attempt(Files.readAllBytes(p)).orDie

  private def fileExists(p: Path): UIO[Boolean] =
    ZIO.attempt(Files.exists(p)).orDie

  private def corruptOf(
      attempt: Either[StateError, StateStore]
  ): Option[Path] =
    attempt.swap.toOption.collect { case StateError.Corrupt(p, _) => p }

  private def incrementLoop(
      store: StateStore,
      fiber: Int,
      times: Int
  ): UIO[Unit] =
    def step(n: Int): UIO[Unit] =
      if n <= 0 then ZIO.unit
      else
        store.revision(ag1, "ctr").either.flatMap {
          case Left(e) =>
            ZIO.dieMessage(s"unexpected revision read error: $e")
          case Right(current) =>
            store
              .putIfRevision(ag1, "ctr", current, s"f$fiber:$n")
              .either
              .flatMap {
                case Right(_)                                => step(n - 1)
                case Left(StateError.RevisionConflict(_, _)) => step(n)
                case Left(other) =>
                  ZIO.dieMessage(s"unexpected state error in CAS loop: $other")
              }
        }
    step(times)

  def spec = suite("RAI-076 StateStore")(
    suite("STATE-01: normal")(
      test("memory kv roundtrip with monotonic revisions and delete") {
        for
          store <- StateStore.memory()
          r1 <- store.put(ag1, "plan", "alpha")
          v1 <- store.get(ag1, "plan")
          r2 <- store.put(ag1, "plan", "beta")
          rv <- store.revision(ag1, "plan")
          ks <- store.keys(ag1)
          del <- store.delete(ag1, "plan")
          gone <- store.get(ag1, "plan")
          del2 <- store.delete(ag1, "plan")
          ghost <- store.revision(ag1, "plan")
        yield assertTrue(
          r1 == 1L,
          v1 == Some("alpha"),
          r2 == 2L,
          rv == Some(2L),
          ks == List("plan"),
          del,
          !del2,
          gone == None,
          ghost == None
        )
      },
      test("file kv roundtrip survives reopen (values, revisions, keys)") {
        for
          dir <- tempDir
          s1 <- StateStore.file(dir)
          r1 <- s1.put(rt, "plan", "alpha")
          r2 <- s1.put(rt, "plan", "beta")
          _ <- s1.put(ag1, "note", "n1")
          s2 <- StateStore.file(dir)
          v <- s2.get(rt, "plan")
          rv <- s2.revision(rt, "plan")
          av <- s2.get(ag1, "note")
          ks <- s2.keys(rt)
          r3 <- s2.put(rt, "plan", "gamma")
        yield assertTrue(
          r1 == 1L,
          r2 == 2L,
          v == Some("beta"),
          rv == Some(2L),
          av == Some("n1"),
          ks == List("plan"),
          r3 == 3L
        )
      },
      test("file delete removes the document and stays deleted after reopen") {
        for
          dir <- tempDir
          s1 <- StateStore.file(dir)
          _ <- s1.put(ag1, "gone", "x")
          doc = dir.resolve("agent").resolve("j_a1").resolve("gone.json")
          before <- fileExists(doc)
          del <- s1.delete(ag1, "gone")
          after <- fileExists(doc)
          s2 <- StateStore.file(dir)
          v <- s2.get(ag1, "gone")
        yield assertTrue(before, del, !after, v == None)
      },
      test("maxKeys quota: new keys bounded, updates unaffected") {
        for
          store <- StateStore.memory(tiny)
          _ <- store.put(ag1, "k1", "a")
          _ <- store.put(ag1, "k2", "b")
          third <- store.put(ag1, "k3", "c").either
          update <- store.put(ag1, "k2", "bb")
        yield
          val quota = third.swap.toOption.collect {
            case q: StateError.QuotaExceeded => q
          }
          assertTrue(
            quota.isDefined,
            quota.get.scope == ag1,
            quota.get.limit.startsWith("maxKeys"),
            update == 2L
          )
      },
      test(
        "maxValueLen quota: oversize value typed-denied, boundary accepted"
      ) {
        for
          store <- StateStore.memory(tiny)
          ok <- store.put(ag1, "k", "12345678")
          over <- store.put(ag1, "k2", "123456789").either
        yield
          val quota = over.swap.toOption.collect {
            case q: StateError.QuotaExceeded => q
          }
          assertTrue(
            ok == 1L,
            quota.isDefined,
            quota.get.limit.startsWith("maxValueLen")
          )
      },
      test(
        "maxTotalBytes quota: cumulative bytes bounded across keys/updates"
      ) {
        for
          store <- StateStore.memory(tiny)
          _ <- store.put(ag1, "a", "12345")
          overNew <- store.put(ag1, "b", "123456").either
          _ <- store.put(ag1, "b", "67890")
          overUpdate <- store.put(ag1, "b", "678901").either
          _ <- store.put(ag1, "b", "67890")
          va <- store.get(ag1, "a")
          vb <- store.get(ag1, "b")
        yield
          val q1 = overNew.swap.toOption.collect {
            case q: StateError.QuotaExceeded => q
          }
          val q2 = overUpdate.swap.toOption.collect {
            case q: StateError.QuotaExceeded => q
          }
          assertTrue(
            q1.isDefined,
            q1.get.limit.startsWith("maxTotalBytes"),
            q2.isDefined,
            q2.get.limit.startsWith("maxTotalBytes"),
            va == Some("12345"),
            vb == Some("67890")
          )
      },
      test("scope isolation: Agent/Root/Global are distinct namespaces") {
        for
          store <- StateStore.memory()
          _ <- store.put(ag1, "k", "agent1")
          _ <- store.put(ag2, "k", "agent2")
          _ <- store.put(rt, "k", "root")
          _ <- store.put(glob, "k", "global")
          a1 <- store.get(ag1, "k")
          a2 <- store.get(ag2, "k")
          r <- store.get(rt, "k")
          g <- store.get(glob, "k")
          rks <- store.keys(rt)
          aks <- store.keys(ag1)
        yield assertTrue(
          a1 == Some("agent1"),
          a2 == Some("agent2"),
          r == Some("root"),
          g == Some("global"),
          rks == List("k"),
          aks == List("k")
        )
      }
    ),
    suite("STATE-02: adversarial")(
      test("putIfRevision: create (None), CAS (Some), stale conflict") {
        for
          store <- StateStore.memory()
          r1 <- store.putIfRevision(ag1, "plan", None, "v1")
          r2 <- store.putIfRevision(ag1, "plan", Some(r1), "v2")
          stale <- store.putIfRevision(ag1, "plan", Some(r1), "v3").either
          absent <- store.putIfRevision(ag1, "ghost", Some(5L), "x").either
          exists <- store.putIfRevision(ag1, "plan", None, "v4").either
        yield
          val staleActual = stale match
            case Left(StateError.RevisionConflict(_, actual)) => actual
            case _                                            => None
          val absentActual = absent match
            case Left(StateError.RevisionConflict(_, actual)) => actual
            case _                                            => None
          assertTrue(
            r1 == 1L,
            r2 == 2L,
            stale.isLeft,
            staleActual == Some(2L),
            absent.isLeft,
            absentActual == None,
            exists.isLeft
          )
      },
      test(
        "scope violation: traversal and malformed keys are refused typed"
      ) {
        for
          store <- StateStore.memory()
          badKeys = List(
            "",
            "../escape",
            "a/b",
            ".hidden",
            "has space",
            "x" * 65
          )
          results <- ZIO.foreach(badKeys)(k => store.put(ag1, k, "v").either)
        yield
          val violations = results.collect {
            case Left(StateError.ScopeViolation(_, _)) => ()
          }
          assertTrue(violations.size == badKeys.length)
      },
      test("scope violation: empty or malformed scope identity is refused") {
        for
          store <- StateStore.memory()
          e1 <- store.put(Scope.Agent(JobId("")), "k", "v").either
          e2 <- store.put(Scope.Agent(JobId("../x")), "k", "v").either
          e3 <- store.put(Scope.Root(RootId(".dot")), "k", "v").either
          e4 <- store.get(Scope.Root(RootId("r/1")), "k").either
        yield
          val all = List(e1, e2, e3, e4)
          assertTrue(all.forall {
            case Left(StateError.ScopeViolation(_, _)) => true
            case _                                     => false
          })
      },
      test("corrupt document: unreadable JSON fails construction, untouched") {
        for
          dir <- tempDir
          doc = dir.resolve("global").resolve("k.json")
          _ <- writeText(doc, "{ definitely not json")
          before <- readBytes(doc)
          attempt <- StateStore.file(dir).either
          after <- readBytes(doc)
        yield assertTrue(
          corruptOf(attempt).contains(doc),
          before.sameElements(after)
        )
      },
      test("corrupt document: bad version / bad revision / key mismatch") {
        val cases = List(
          """{"v":99,"key":"k","rev":1,"value":"x"}""",
          """{"v":1,"key":"k","rev":0,"value":"x"}""",
          """{"v":1,"key":"other","rev":1,"value":"x"}"""
        )
        ZIO
          .foreach(cases) { text =>
            for
              dir <- tempDir
              doc = dir.resolve("global").resolve("k.json")
              _ <- writeText(doc, text)
              attempt <- StateStore.file(dir).either
            yield corruptOf(attempt).contains(doc)
          }
          .map(results => assertTrue(results.forall(identity)))
      },
      test("leftover tmp file after interrupted write is ignored") {
        for
          dir <- tempDir
          good = dir.resolve("agent").resolve("j_a1").resolve("k.json")
          tmp = dir.resolve("agent").resolve("j_a1").resolve("k.json.tmp")
          _ <- writeText(good, """{"v":1,"key":"k","rev":2,"value":"kept"}""")
          _ <- writeText(tmp, "interrupted garbage")
          store <- StateStore.file(dir)
          v <- store.get(ag1, "k")
          rv <- store.revision(ag1, "k")
        yield assertTrue(v == Some("kept"), rv == Some(2L))
      },
      test("base path that is a regular file is corrupt, never recreated") {
        for
          dir <- tempDir
          filePath = dir.resolve("not-a-dir")
          _ <- writeText(filePath, "i am a file")
          attempt <- StateStore.file(filePath).either
          still <- fileExists(filePath)
        yield assertTrue(corruptOf(attempt).contains(filePath), still)
      },
      test("every StateError maps into the frozen RaiderError families") {
        val quota = StateError.QuotaExceeded(glob, "maxKeys=1").toRaiderError
        val conflict = StateError.RevisionConflict("k", Some(2L)).toRaiderError
        val violation = StateError.ScopeViolation(glob, "bad key").toRaiderError
        val corrupt =
          StateError.Corrupt(Path.of("/tmp/x"), "bad json").toRaiderError
        val io =
          StateError.Io(Path.of("/tmp/x"), "disk full").toRaiderError
        assertTrue(
          quota.code == "RA-LBUDGET",
          quota.isInstanceOf[RaiderError.LocalBudgetExceeded],
          conflict.code == "RA-CAP",
          conflict.isInstanceOf[RaiderError.CapabilityUnsupported],
          violation.code == "RA-INP",
          violation.isInstanceOf[RaiderError.InputValidation],
          corrupt.code == "RA-JOURNAL",
          corrupt.isInstanceOf[RaiderError.JournalFailure],
          io.code == "RA-JOURNAL",
          io.isInstanceOf[RaiderError.JournalFailure]
        )
      }
    ),
    suite("STATE-03: concurrency")(
      test(
        "4 fibers CAS-creating one key: exactly one winner, three conflicts"
      ) {
        for
          store <- StateStore.memory()
          results <- ZIO.foreachPar(1 to 4)(i =>
            store.putIfRevision(ag1, "winner", None, s"f$i").either
          )
          finalRev <- store.revision(ag1, "winner")
          value <- store.get(ag1, "winner")
        yield
          val winners = results.collect { case Right(rev) => rev }
          val losers = results.collect {
            case Left(StateError.RevisionConflict(_, actual)) => actual
          }
          assertTrue(
            winners == List(1L),
            losers.size == 3,
            losers.forall(_ == Some(1L)),
            finalRev == Some(1L),
            value.isDefined && value.get.startsWith("f")
          )
      },
      test("4 fibers CAS increment loop: no lost updates") {
        for
          store <- StateStore.memory()
          _ <- ZIO.foreachPar(1 to 4)(f => incrementLoop(store, f, 10))
          rev <- store.revision(ag1, "ctr")
        yield assertTrue(rev == Some(40L))
      },
      test("4 fibers CAS-creating one key on the file backend: one winner") {
        for
          dir <- tempDir
          store <- StateStore.file(dir)
          results <- ZIO.foreachPar(1 to 4)(i =>
            store.putIfRevision(ag1, "winner", None, s"f$i").either
          )
          finalRev <- store.revision(ag1, "winner")
          reopened <- StateStore.file(dir)
          rv <- reopened.revision(ag1, "winner")
          v <- reopened.get(ag1, "winner")
        yield
          val winners = results.collect { case Right(rev) => rev }
          assertTrue(
            winners == List(1L),
            finalRev == Some(1L),
            rv == Some(1L),
            v.isDefined && v.get.startsWith("f")
          )
      }
    )
  )

end StateStoreSpec
