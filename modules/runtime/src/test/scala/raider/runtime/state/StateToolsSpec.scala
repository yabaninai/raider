package raider.runtime.state

import raider.core.{
  JobId,
  RaiderError,
  RecoveryClass,
  RootId,
  Tool,
  ToolRegistry
}
import zio.json.{DecoderOps, DeriveJsonDecoder, JsonDecoder}
import zio.test.*
import zio.ZIO

/** RAI-085 acceptance (m1.md STT-01..03): the four opt-in StateTools behind the
  * frozen raider.core.Tool JSON contract.
  *
  * Determinism rules: no sleeps; concurrency fixtures assert interleaving-
  * independent invariants — the whole CAS check-and-write is ONE STM
  * transaction, so a `state_put` with `expectedRev` either commits or comes
  * back as typed RA-CAP feedback, and revision == number of committed puts. The
  * equal-expectedRev fixture is deterministic (exactly one winner), the
  * retry-loop fixture terminates under fair scheduling (same argument as
  * StateStoreSpec).
  */
object StateToolsSpec extends ZIOSpecDefault:

  private val job1 = JobId("j_tool1")
  private val job2 = JobId("j_tool2")
  private val ag1 = Scope.Agent(job1)

  /** Tiny agent bounds for deterministic quota fixtures (8-byte value cap). */
  private val tiny = StateStore.Quotas(
    agent = StateStore.Limits(maxKeys = 2, maxValueLen = 8, maxTotalBytes = 10),
    root = StateStore.Limits.default,
    global = StateStore.Limits.default
  )

  // ---- tool-output wires --------------------------------------------------

  private final case class PutOut(
      ok: Boolean,
      revision: Long,
      scope: String,
      key: String
  )

  private object PutOut:
    given JsonDecoder[PutOut] = DeriveJsonDecoder.gen[PutOut]

  private final case class GetOut(
      found: Boolean,
      scope: String,
      key: String,
      value: Option[String],
      revision: Option[Long]
  )

  private object GetOut:
    given JsonDecoder[GetOut] = DeriveJsonDecoder.gen[GetOut]

  private final case class DeleteOut(
      deleted: Boolean,
      scope: String,
      key: String
  )

  private object DeleteOut:
    given JsonDecoder[DeleteOut] = DeriveJsonDecoder.gen[DeleteOut]

  private final case class KeysOut(
      keys: List[String],
      count: Int,
      scope: String
  )

  private object KeysOut:
    given JsonDecoder[KeysOut] = DeriveJsonDecoder.gen[KeysOut]

  private final case class Feedback(error: String, detail: String)

  private object Feedback:
    given JsonDecoder[Feedback] = DeriveJsonDecoder.gen[Feedback]

  /** A tool output is either a committed write or structured feedback; the
    * shapes are disjoint (ok vs error), so full-string decoding disambiguates.
    */
  private sealed trait Outcome extends Product with Serializable

  private object Outcome:
    final case class Committed(revision: Long) extends Outcome
    final case class Refused(error: String, detail: String) extends Outcome

  private def outcome(out: String): Either[String, Outcome] =
    out.fromJson[Feedback] match
      case Right(Feedback(err, detail)) =>
        Right(Outcome.Refused(err, detail))
      case Left(_) =>
        out.fromJson[PutOut].map(p => Outcome.Committed(p.revision))

  private def asOutcome(out: String): ZIO[Any, Nothing, Outcome] =
    ZIO
      .fromEither(outcome(out))
      .catchAll(err => ZIO.dieMessage(s"unparseable tool output: $err"))

  /** Invoke a tool by name under a fresh Scope (invoke acquires nothing). */
  private def invoke(
      tools: List[Tool],
      name: String,
      args: String
  ): ZIO[Any, RaiderError, String] =
    ZIO.scoped:
      tools.find(_.name == name) match
        case Some(t) => t.invoke(args)
        case None    => ZIO.dieMessage(s"tool '$name' is not registered")

  private def parse[A: JsonDecoder](out: String): ZIO[Any, Nothing, A] =
    ZIO
      .fromEither(out.fromJson[A])
      .catchAll(err => ZIO.dieMessage(s"unparseable tool output: $err"))

  def spec = suite("RAI-085 StateTools")(
    suite("STT-01: normal")(
      test(
        "four tools behind the frozen Tool contract; registry accepts them"
      ) {
        for
          store <- StateStore.memory()
          tools = StateTools.store(store, job1)
          byName = tools.map(t => t.name -> t).toMap
        yield assertTrue(
          byName.keys.toList.sorted ==
            List("state_delete", "state_get", "state_keys", "state_put"),
          ToolRegistry.build(tools).isRight,
          byName("state_get").version == 1,
          byName("state_get").recovery == RecoveryClass.ReadOnly,
          byName("state_put").recovery == RecoveryClass.Mutating,
          byName("state_delete").recovery == RecoveryClass.Idempotent,
          byName("state_keys").recovery == RecoveryClass.ReadOnly,
          !byName("state_put").capabilities.concurrentSafe,
          byName("state_get").capabilities.concurrentSafe,
          byName("state_get").parametersJsonSchema.contains(
            "\"required\":[\"key\"]"
          )
        )
      },
      test(
        "STT-01: put/get/delete roundtrip via tool JSON; agent scope from jobId"
      ) {
        for
          store <- StateStore.memory()
          tools = StateTools.store(store, job1)
          put <- invoke(
            tools,
            "state_put",
            """{"key":"plan","value":"alpha"}"""
          )
            .flatMap(parse[PutOut])
          got <- invoke(tools, "state_get", """{"key":"plan"}""")
            .flatMap(parse[GetOut])
          otherJob <- store.get(Scope.Agent(job2), "plan")
          rootMiss <- store.get(Scope.Root(RootId("r_1")), "plan")
          globMiss <- store.get(Scope.Global, "plan")
          del <- invoke(tools, "state_delete", """{"key":"plan"}""")
            .flatMap(parse[DeleteOut])
          gone <- invoke(tools, "state_get", """{"key":"plan"}""")
            .flatMap(parse[GetOut])
          delAgain <- invoke(tools, "state_delete", """{"key":"plan"}""")
            .flatMap(parse[DeleteOut])
        yield assertTrue(
          put.ok,
          put.revision == 1L,
          put.scope == "agent(j_tool1)",
          got.found,
          got.value == Some("alpha"),
          got.revision == Some(1L),
          got.scope == "agent(j_tool1)",
          otherJob == None,
          rootMiss == None,
          globMiss == None,
          del.deleted,
          !gone.found,
          !delAgain.deleted
        )
      },
      test(
        "STT-01: explicit root/global scopes via args; agent default stays private"
      ) {
        for
          store <- StateStore.memory()
          tools = StateTools.store(store, job1)
          rootPut <- invoke(
            tools,
            "state_put",
            """{"key":"k","value":"r","scope":"root:r_1"}"""
          ).flatMap(parse[PutOut])
          globPut <- invoke(
            tools,
            "state_put",
            """{"key":"k","value":"g","scope":"global"}"""
          ).flatMap(parse[PutOut])
          atRoot <- store.get(Scope.Root(RootId("r_1")), "k")
          atGlob <- store.get(Scope.Global, "k")
          defGet <- invoke(tools, "state_get", """{"key":"k"}""")
            .flatMap(parse[GetOut])
          rootKeys <- invoke(tools, "state_keys", """{"scope":"root:r_1"}""")
            .flatMap(parse[KeysOut])
          rootGet <- invoke(
            tools,
            "state_get",
            """{"key":"k","scope":"root:r_1"}"""
          ).flatMap(parse[GetOut])
        yield assertTrue(
          rootPut.ok,
          rootPut.scope == "root(r_1)",
          globPut.ok,
          globPut.scope == "global",
          atRoot == Some("r"),
          atGlob == Some("g"),
          !defGet.found,
          rootKeys.keys == List("k"),
          rootKeys.count == 1,
          rootKeys.scope == "root(r_1)",
          rootGet.found && rootGet.value == Some("r")
        )
      }
    ),
    suite("STT-02: adversarial")(
      test(
        "STT-02: quota exceeded is structured RA-LBUDGET feedback, no crash"
      ) {
        for
          store <- StateStore.memory(tiny)
          tools = StateTools.store(store, job1)
          out <- invoke(
            tools,
            "state_put",
            s"""{"key":"k","value":"${"x" * 9}"}"""
          ).flatMap(parse[Feedback])
          remaining <- store.keys(ag1)
        yield assertTrue(
          out.error == "RA-LBUDGET",
          out.detail.contains("maxValueLen"),
          remaining.isEmpty
        )
      },
      test(
        "STT-02: stale CAS is structured RA-CAP feedback; matching CAS commits"
      ) {
        for
          store <- StateStore.memory()
          tools = StateTools.store(store, job1)
          p1 <- invoke(tools, "state_put", """{"key":"doc","value":"v1"}""")
            .flatMap(parse[PutOut])
          stale <- invoke(
            tools,
            "state_put",
            """{"key":"doc","value":"v2","expectedRev":99}"""
          ).flatMap(parse[Feedback])
          kept <- store.get(ag1, "doc")
          ok <- invoke(
            tools,
            "state_put",
            """{"key":"doc","value":"v2","expectedRev":1}"""
          ).flatMap(parse[PutOut])
        yield assertTrue(
          p1.revision == 1L,
          stale.error == "RA-CAP",
          stale.detail.contains("revision conflict"),
          kept == Some("v1"),
          ok.ok,
          ok.revision == 2L
        )
      },
      test(
        "STT-02: bad args, unknown scope, bad key are RA-INP feedback, no crash"
      ) {
        for
          store <- StateStore.memory()
          tools = StateTools.store(store, job1)
          badJson <- invoke(tools, "state_get", "{ not json")
            .flatMap(parse[Feedback])
          noKey <- invoke(tools, "state_get", """{"scope":"agent"}""")
            .flatMap(parse[Feedback])
          unknown <- invoke(
            tools,
            "state_get",
            """{"key":"k","scope":"galaxy"}"""
          ).flatMap(parse[Feedback])
          traversal <- invoke(tools, "state_get", """{"key":"../escape"}""")
            .flatMap(parse[Feedback])
          globalKeys <- invoke(tools, "state_keys", """{"scope":"global"}""")
            .flatMap(parse[KeysOut])
        yield assertTrue(
          badJson.error == "RA-INP",
          noKey.error == "RA-INP",
          unknown.error == "RA-INP",
          unknown.detail.contains("galaxy"),
          traversal.error == "RA-INP",
          globalKeys.keys.isEmpty
        )
      }
    ),
    suite("STT-03: concurrency & opt-in")(
      test("STT-03: absent store yields no tools; present store yields four") {
        for
          store <- StateStore.memory()
          present = StateTools.store(Some(store), job1)
        yield assertTrue(
          StateTools.store(None, job1).isEmpty,
          present.size == 4
        )
      },
      test(
        "STT-03: 2 agents CAS one root key via tools — exactly one winner, typed conflict"
      ) {
        def attempt(tools: List[Tool]): ZIO[Any, Nothing, Outcome] =
          invoke(
            tools,
            "state_put",
            """{"key":"board","value":"race","scope":"root:r_run","expectedRev":1}"""
          ).either.flatMap {
            case Right(out) =>
              asOutcome(out)
            case Left(e) => ZIO.dieMessage(s"crash-flavored failure: $e")
          }
        for
          store <- StateStore.memory()
          toolsA = StateTools.store(store, job1)
          toolsB = StateTools.store(store, job2)
          seed <- invoke(
            toolsA,
            "state_put",
            """{"key":"board","value":"seed","scope":"root:r_run"}"""
          ).flatMap(parse[PutOut])
          outcomes <- ZIO.foreachPar(List(toolsA, toolsB))(attempt)
          rev <- store.revision(Scope.Root(RootId("r_run")), "board")
          value <- store.get(Scope.Root(RootId("r_run")), "board")
        yield
          val winners = outcomes.collect { case c: Outcome.Committed => c }
          val losers = outcomes.collect { case r: Outcome.Refused => r }
          assertTrue(
            seed.revision == 1L,
            winners.size == 1,
            winners.headOption.exists(_.revision == 2L),
            losers.size == 1,
            losers.forall(_.error == "RA-CAP"),
            rev == Some(2L),
            value == Some("race")
          )
      },
      test("STT-03: 2 agents CAS retry loop via tools — no lost updates") {
        def casLoop(
            tools: List[Tool],
            times: Int
        ): ZIO[Any, Nothing, (Int, Int)] =
          def step(
              n: Int,
              ok: Int,
              conflicts: Int
          ): ZIO[Any, Nothing, (Int, Int)] =
            if n <= 0 then ZIO.succeed((ok, conflicts))
            else
              for
                cur <- invoke(
                  tools,
                  "state_get",
                  """{"key":"ctr","scope":"root:r_loop"}"""
                ).flatMap(parse[GetOut]).orDie
                attempt <-
                  if !cur.found then
                    invoke(
                      tools,
                      "state_put",
                      """{"key":"ctr","value":"n","scope":"root:r_loop"}"""
                    ).either
                  else
                    invoke(
                      tools,
                      "state_put",
                      s"""{"key":"ctr","value":"n","scope":"root:r_loop","expectedRev":${cur.revision
                          .getOrElse(0L)}}"""
                    ).either
                next <- attempt match
                  case Right(out) =>
                    asOutcome(out).flatMap {
                      case Outcome.Committed(_) =>
                        step(n - 1, ok + 1, conflicts)
                      case Outcome.Refused("RA-CAP", _) =>
                        step(n, ok, conflicts + 1)
                      case Outcome.Refused(err, detail) =>
                        ZIO.dieMessage(s"unexpected refusal: $err $detail")
                    }
                  case Left(e) => ZIO.dieMessage(s"crash-flavored failure: $e")
              yield next
          step(times, 0, 0)

        for
          store <- StateStore.memory()
          toolsA = StateTools.store(store, job1)
          toolsB = StateTools.store(store, job2)
          results <- ZIO.foreachPar(List(toolsA, toolsB))(casLoop(_, 8))
          committed = results.map(_._1).sum
          conflicts = results.map(_._2).sum
          rev <- store.revision(Scope.Root(RootId("r_loop")), "ctr")
        yield assertTrue(
          committed == 16,
          committed + conflicts >= 16,
          rev == Some(16L)
        )
      }
    )
  )

end StateToolsSpec
