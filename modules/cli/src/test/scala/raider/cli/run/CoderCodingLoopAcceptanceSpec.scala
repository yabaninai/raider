package raider.cli.run

import raider.core.RaiderError
import raider.core.trace.{RunTrace, TraceEvent}
import raider.runtime.events.EventPayload
import raider.runtime.jobs.JobStatus
import raider.runtime.lineage.io.LineageWireReader
import raider.runtime.lineage.wire.{LineageEnvelope, LineagePayload}
import zio.json.{DecoderOps, DeriveJsonDecoder, JsonDecoder}
import zio.test.*
import zio.ZIO

import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ExecutorService, Executors}
import scala.jdk.CollectionConverters.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}

/** RAI-283: the end-to-end CODING-LOOP acceptance scenario, driven through the
  * PRODUCT entry (`raider run --agent coder`) against a LOCAL OpenAI-compatible
  * stand (JDK HttpServer on the loopback, ephemeral port — the
  * CoderDelegationRunSpec / CoderAggregateCeilingSpec framing: no real network,
  * no paid calls, credentials only through the injected test env
  * RAIDER_API_KEY, never argv).
  *
  * The card's scenario contract (docs/tasks/m1.md RAI-283), four legs:
  *
  *   1. Main loop over a temp workspace: fs_read → fs_patch_multi with
  *      expected-SHA provenance → a check tool run that FAILS on the first
  *      attempt (grep exit 1) → a corrective patch → the SAME check PERSISTED
  *      green in the run artifacts (events.jsonl / transcript.json / summary.md
  *      / run.json — not just the exit code). The adversarial sub-leg proves
  *      the provenance policy itself: a patch carrying a STALE expected-SHA is
  *      refused as structured tool feedback (RA-TOOLDENY) while the run stays
  *      green.
  *   2. Delegated child whose provider model is the PROPAGATED parent model
  *      (RAI-257 contract) plus a scripted explicit-override case; both
  *      children settle Succeeded with persisted Delegated events.
  *   3. Cancellation: a child stuck in a long proc_run is cancelled via
  *      cancel_agent (subtree cancel through the JobManager), settles Cancelled
  *      with the Cancelling/Cancelled events, well bounded in time, and the
  *      parent observes the typed RA-CANCEL await feedback.
  *   4. Budget-limit refusal: --max-dispatches 2 trips the aggregate ceiling
  *      after the root dispatch and one child dispatch, with the typed
  *      ProviderBudget error and exit code 23 — not a crash.
  *
  * Content routing (RAI-092 discipline): the stand picks the turn from the
  * request CONTENT (counts of tool-result shapes in the accumulated history),
  * never from a call index. The expected SHA is EXTRACTED from the fs_read
  * tool-result the model actually saw, so the patch provenance follows the real
  * read→patch data flow.
  *
  * Determinism: every turn's route is a pure function of the tool-result
  * counts, so the multi-turn flow is stable under scheduler interleaving of the
  * loop's internals; per-test unique temp dirs; no shared fixed paths; no
  * test-ordering dependencies.
  */
object CoderCodingLoopAcceptanceSpec extends ZIOSpecDefault:

  private val Key = "rai-283-stand-key"

  /** Minimal request view for the stand's routing. */
  private final case class ReqWire(model: String, messages: List[ReqMessage])
  private final case class ReqMessage(role: String, content: String)

  private object ReqWire:
    given JsonDecoder[ReqMessage] = DeriveJsonDecoder.gen[ReqMessage]
    given JsonDecoder[ReqWire] = DeriveJsonDecoder.gen[ReqWire]

  private def toolResults(req: ReqWire): List[String] =
    req.messages.collect { case m if m.role == "tool" => m.content }

  private def userMsgs(req: ReqWire): List[String] =
    req.messages.collect { case m if m.role == "user" => m.content }

  /** JSON-escape for embedding inside a tool-call `arguments` string. */
  private def jesc(s: String): String =
    s.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")

  // The stand rewrites the CALL_ID placeholder per request, so each response
  // carries a fresh unique id; the tool_call id is fixed per route (each route
  // fires at most once per run, so ids stay unique within a run).
  private def textResponse(text: String, inTok: Long, outTok: Long): String =
    s"""{"id":"CALL_ID","choices":[{"index":0,"message":{"role":"assistant",
       "content":"$text"},"finish_reason":"stop"}],
       "usage":{"prompt_tokens":$inTok,"completion_tokens":$outTok}}"""

  private def toolResponse(
      callId: String,
      tool: String,
      argsJson: String,
      inTok: Long,
      outTok: Long
  ): String =
    s"""{"id":"CALL_ID","choices":[{"index":0,"message":{"role":"assistant",
       "content":null,"tool_calls":[{"id":"$callId","type":"function",
       "function":{"name":"$tool","arguments":"${jesc(argsJson)}"}}]},
       "finish_reason":"tool_calls"}],
       "usage":{"prompt_tokens":$inTok,"completion_tokens":$outTok}}"""

  // ---- workspace fixture ----

  private val InitialAnswer = "answer=41\n"
  private val FixedAnswer = "answer=42\n"

  private def newWorkspace(prefix: String): ZIO[Any, Nothing, Path] =
    ZIO.attempt {
      val ws = Files.createTempDirectory(prefix)
      Files.writeString(ws.resolve("ANSWER.txt"), InitialAnswer, UTF_8)
      ws
    }.orDie

  /** An absolute, env-free binary: proc_run clears the process env by default,
    * so the scripted tool argv must never rely on PATH.
    */
  private def resolveBin(
      names: List[String],
      what: String
  ): ZIO[Any, Nothing, String] =
    ZIO
      .attempt(names.find(p => Files.isExecutable(Path.of(p))))
      .orDie
      .someOrElseZIO(ZIO.dieMessage(s"no $what binary found for proc_run"))

  private def resolveGrep: ZIO[Any, Nothing, String] =
    resolveBin(List("/usr/bin/grep", "/bin/grep"), "grep")

  private def resolveSleep: ZIO[Any, Nothing, String] =
    resolveBin(List("/bin/sleep", "/usr/bin/sleep"), "sleep")

  // ---- fs_patch_multi diff (git-style sections; UnifiedDiff.parseSections) ----

  private def patchDiff(from: String, to: String): String =
    s"""diff --git a/ANSWER.txt b/ANSWER.txt
--- a/ANSWER.txt
+++ b/ANSWER.txt
@@ -1,1 +1,1 @@
-$from
+$to"""

  private val StaleSha = "0" * 64

  // ---- the scripted stand ----

  private final case class Stand(
      baseUrl: String,
      server: HttpServer,
      pool: ExecutorService,
      requests: CopyOnWriteArrayList[ReqWire]
  )

  private def startStand(
      allowedModels: Set[String],
      route: ReqWire => String
  ): ZIO[Any, Nothing, Stand] =
    ZIO.attempt {
      val requests = new CopyOnWriteArrayList[ReqWire]()
      val callSeq = new AtomicInteger(0)
      val server = HttpServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
        0
      )
      val pool = Executors.newFixedThreadPool(4)
      server.setExecutor(pool)
      server.createContext(
        "/v1/chat/completions",
        (exchange: HttpExchange) =>
          try
            def reply(code: Int, body: String): Unit =
              val bytes = body.getBytes(UTF_8)
              exchange.sendResponseHeaders(code, bytes.length)
              exchange.getResponseBody.write(bytes)
            Option(exchange.getRequestHeaders.getFirst("Authorization")) match
              case None =>
                reply(401, """{"error":{"message":"missing api key"}}""")
              case Some(a) if a != s"Bearer $Key" =>
                reply(401, """{"error":{"message":"incorrect api key"}}""")
              case Some(_) =>
                val body =
                  new String(exchange.getRequestBody.readAllBytes(), UTF_8)
                body.fromJson[ReqWire] match
                  case Left(_) =>
                    reply(400, """{"error":{"message":"bad request"}}""")
                  case Right(req) =>
                    requests.add(req)
                    if !allowedModels.contains(req.model) then
                      reply(
                        400,
                        s"""{"error":{"message":"model ${req.model} not deployed"}}"""
                      )
                    else
                      val callId = s"call_${callSeq.incrementAndGet()}"
                      reply(200, route(req).replace("CALL_ID", callId))
          finally exchange.close()
      )
      server.start()
      Stand(
        s"http://127.0.0.1:${server.getAddress.getPort}/v1",
        server,
        pool,
        requests
      )
    }.orDie

  private def stopStand(stand: Stand): Unit =
    stand.server.stop(0)
    stand.pool.shutdownNow()

  private def parseArgs(
      extra: List[String],
      out: Path,
      ws: Path,
      baseUrl: String
  ): Either[RaiderError, CliArgs] =
    CliArgs.parse(
      List(
        "run",
        "--agent",
        "coder",
        "--input",
        "fix the answer file and verify it",
        "--out",
        out.toString,
        "--provider",
        "openai",
        "--base-url",
        baseUrl,
        "--model",
        "stand-model",
        "--workspace",
        ws.toString
      ) ++ extra
    )

  private def runParsed(
      parsed: Either[RaiderError, CliArgs]
  ): ZIO[Any, String, HeadlessRunner.Outcome] =
    parsed match
      case Left(e) =>
        ZIO.fail(s"parse refused the argv: ${e.code}: ${e.detail}")
      case Right(args) =>
        HeadlessRunner.run(args, Map("RAIDER_API_KEY" -> Key))

  /** Strict events.jsonl read (the exported wire, never a partial stream). */
  private def readWire(dir: Path): Vector[LineageEnvelope] =
    LineageWireReader.read(dir.resolve("events.jsonl")) match
      case Right(p) =>
        if p.truncatedTail.isDefined then
          throw new IllegalStateException("truncated tail")
        p.envelopes
      case Left(e) =>
        throw new IllegalStateException(s"events.jsonl refused: $e")

  private def readTranscript(dir: Path): RunTrace =
    Files
      .readString(dir.resolve("transcript.json"), UTF_8)
      .fromJson[RunTrace] match
      case Right(t) => t
      case Left(e) => throw new IllegalStateException(s"transcript refused: $e")

  // ---- shared routing helpers for the delegation legs ----

  private val ChildIdR = """"child_id":"(j_\d+)"""".r

  private def childIdsOf(results: List[String]): List[String] =
    results
      .flatMap(r => ChildIdR.findAllMatchIn(r).map(_.group(1)))
      .distinct

  // delegate replies carry "state":"Running"; await_agent feedback carries
  // "status":"Succeeded"/"Failed" — the counts below drive the parent's turns
  private def delegCount(results: List[String]): Int =
    results.count(_.contains("\"state\":\"Running\""))

  private def awaitCount(results: List[String]): Int =
    results.count(_.contains("\"status\":\""))

  // ---- main-loop router: read → wrong patch → failing check → corrective
  // patch → passing check → stale-SHA adversarial patch → final answer ----

  private val ShaR = """"sha256":"([0-9a-f]{64})"""".r

  private def lastSha(results: List[String]): Option[String] =
    results
      .flatMap(m => ShaR.findFirstMatchIn(m).map(_.group(1)))
      .lastOption

  private def mainLoopRouter(grep: String)(req: ReqWire): String =
    val results = toolResults(req)
    val reads = results.filter(_.contains("\"sha256\":\""))
    val applied = results.filter(_.contains("\"applied\":[{"))
    val stale = results.filter(_.contains("STALE PATCH"))
    val checks = results.filter(_.contains("\"exitCode\":"))
    if reads.isEmpty then
      toolResponse(
        "CALL_ID",
        "fs_read",
        """{"path":"ANSWER.txt"}""",
        10,
        4
      )
    else if applied.isEmpty && stale.isEmpty then
      // first patch: correct provenance, WRONG target value (41→99) — the
      // check that follows must genuinely FAIL
      toolResponse(
        "CALL_ID",
        "fs_patch_multi",
        s"""{"diff":"${jesc(patchDiff("answer=41", "answer=99"))}",""" +
          s""""expected_sha256":{"ANSWER.txt":"${lastSha(reads).getOrElse(
              ""
            )}"}}""",
        12,
        5
      )
    else if checks.isEmpty then
      toolResponse(
        "CALL_ID",
        "proc_run",
        s"""{"argv":["$grep","-q","answer=42","ANSWER.txt"]}""",
        14,
        3
      )
    else if applied.length == 1 && reads.length == 1 then
      toolResponse(
        "CALL_ID",
        "fs_read",
        """{"path":"ANSWER.txt"}""",
        16,
        4
      )
    else if applied.length == 1 && reads.length == 2 then
      // corrective patch: provenance from the SECOND read (sha of answer=99)
      toolResponse(
        "CALL_ID",
        "fs_patch_multi",
        s"""{"diff":"${jesc(patchDiff("answer=99", "answer=42"))}",""" +
          s""""expected_sha256":{"ANSWER.txt":"${lastSha(reads).getOrElse(
              ""
            )}"}}""",
        18,
        5
      )
    else if checks.length == 1 then
      toolResponse(
        "CALL_ID",
        "proc_run",
        s"""{"argv":["$grep","-q","answer=42","ANSWER.txt"]}""",
        20,
        3
      )
    else if stale.isEmpty then
      // adversarial leg: SAME file, WRONG provenance (64 zeros) — the patch
      // MUST be refused as structured feedback, never applied, never fatal
      toolResponse(
        "CALL_ID",
        "fs_patch_multi",
        s"""{"diff":"${jesc(patchDiff("answer=42", "answer=43"))}",""" +
          s""""expected_sha256":{"ANSWER.txt":"$StaleSha"}}""",
        22,
        5
      )
    else
      textResponse(
        "RAI-283 final: answer=42 verified",
        24,
        6
      )

  // ---- propagation router: delegate (default) → child → delegate (override)
  // → child → await both → final answer ----

  private def propagationRouter(req: ReqWire): String =
    userMsgs(req) match
      case List("prop-default") =>
        textResponse("child one done on default", 30, 4)
      case List("prop-override") =>
        textResponse("child two done on override", 32, 4)
      case _ =>
        val results = toolResults(req)
        val childIds = childIdsOf(results)
        if results.isEmpty then
          toolResponse(
            "CALL_ID",
            "delegate",
            """{"agent":"scout","input":"prop-default"}""",
            30,
            5
          )
        else if delegCount(results) == 1 then
          toolResponse(
            "CALL_ID",
            "delegate",
            """{"agent":"scout","input":"prop-override","model":"override-model"}""",
            32,
            6
          )
        else if awaitCount(results) == 0 then
          toolResponse(
            "CALL_ID",
            "await_agent",
            s"""{"child_id":"${childIds(0)}"}""",
            34,
            4
          )
        else if awaitCount(results) == 1 then
          toolResponse(
            "CALL_ID",
            "await_agent",
            s"""{"child_id":"${childIds(1)}"}""",
            36,
            4
          )
        else textResponse("RAI-283 delegation propagation done", 38, 6)

  // ---- cancel router: delegate → child parks in proc_run(sleep 120) →
  // cancel_agent → await_agent (RA-CANCEL feedback) → final answer ----

  private def cancelRouter(sleep: String)(req: ReqWire): String =
    userMsgs(req) match
      case List("stall-work-please") =>
        toolResponse(
          "CALL_ID",
          "proc_run",
          s"""{"argv":["$sleep","120"],"timeout_s":120}""",
          40,
          3
        )
      case _ =>
        val results = toolResults(req)
        val childIds = childIdsOf(results)
        if results.isEmpty then
          toolResponse(
            "CALL_ID",
            "delegate",
            """{"agent":"scout","input":"stall-work-please"}""",
            40,
            5
          )
        else if results.count(_.contains("\"cancelled\":")) == 0 then
          toolResponse(
            "CALL_ID",
            "cancel_agent",
            s"""{"child_id":"${childIds.head}"}""",
            42,
            4
          )
        else if awaitCount(results) == 0 then
          toolResponse(
            "CALL_ID",
            "await_agent",
            s"""{"child_id":"${childIds.head}"}""",
            44,
            4
          )
        else textResponse("RAI-283 cancel done", 46, 6)

  // ---- budget router: delegate → child turn consumes the last dispatch →
  // the parent's NEXT dispatch is refused by the aggregate ceiling ----

  private def capRouter(req: ReqWire): String =
    userMsgs(req) match
      case List("cap-probe") =>
        textResponse("cap child done", 50, 4)
      case _ =>
        val results = toolResults(req)
        val childIds = childIdsOf(results)
        if results.isEmpty then
          toolResponse(
            "CALL_ID",
            "delegate",
            """{"agent":"scout","input":"cap-probe"}""",
            50,
            5
          )
        else if awaitCount(results) == 0 then
          toolResponse(
            "CALL_ID",
            "await_agent",
            s"""{"child_id":"${childIds.head}"}""",
            52,
            4
          )
        else
          // unreachable: the ceiling refuses the dispatch before this turn
          textResponse("RAI-283 cap leg leak", 54, 6)

  private def campaignTerminal(
      wire: Vector[LineageEnvelope],
      expected: JobStatus
  ): Boolean =
    wire.exists {
      case LineageEnvelope(
            _,
            _,
            _,
            _,
            _,
            _,
            LineagePayload.Frozen(EventPayload.Terminal(outcome)),
            _
          ) =>
        outcome == expected
      case _ => false
    }

  def spec = suite(
    "headless coder coding-loop acceptance through the product entry (RAI-283)"
  )(
    test(
      "coding loop: fs_read → provenance patch → check FAILS (exit 1 persisted) → corrective patch → same check PASSES persisted → stale-SHA patch refused (run stays green)"
    ) {
      ZIO.acquireReleaseWith(
        for
          grep <- resolveGrep
          stand <- startStand(Set("stand-model"), mainLoopRouter(grep))
        yield stand
      )(stand => ZIO.succeed(stopStand(stand))) { stand =>
        ZIO.scoped {
          for
            work <- ZIO
              .attempt(Files.createTempDirectory("rai-283-loop"))
              .orDie
            ws <- newWorkspace("rai-283-loop-ws")
            outcome <- runParsed(parseArgs(Nil, work, ws, stand.baseUrl))
            checked <- ZIO.attempt {
              val dir = outcome.workDir
              val wire = readWire(dir)
              val trace = readTranscript(dir)
              val summary = Files.readString(dir.resolve("summary.md"), UTF_8)
              val runJson =
                Files.readString(dir.resolve("run.json"), UTF_8)
              val answer =
                Files.readString(ws.resolve("ANSWER.txt"), UTF_8)

              val checkStarted = trace.events.collect {
                case TraceEvent.ToolCallStarted(_, _, "proc_run", _, args) =>
                  args
              }
              val checkFinished = trace.events.collect {
                case TraceEvent
                      .ToolCallFinished(_, _, "proc_run", _, res, ok) =>
                  (res, ok)
              }
              val patchFinished = trace.events.collect {
                case TraceEvent.ToolCallFinished(
                      _,
                      _,
                      "fs_patch_multi",
                      _,
                      res,
                      ok
                    ) =>
                  (res, ok)
              }
              val procStartedWire = wire.collect {
                case LineageEnvelope(
                      _,
                      _,
                      _,
                      _,
                      _,
                      _,
                      LineagePayload.Frozen(
                        EventPayload.ToolStarted(_, "proc_run", args)
                      ),
                      _
                    ) =>
                  args
              }

              assertTrue(
                outcome.exitCode == 0,
                outcome.result.exists(r =>
                  r.exitCode == 0 &&
                    r.output.exists(
                      _.json.contains("RAI-283 final: answer=42 verified")
                    )
                ),
                summary.contains("SUCCEEDED"),
                runJson.contains("\"exitCode\":0"),
                // the SAME check argv ran twice: first FAILED (exit 1),
                // then PASSED (exit 0) — persisted in the transcript
                checkStarted.length == 2,
                checkStarted.forall(args =>
                  args.contains("grep") &&
                    args.contains("-q") &&
                    args.contains("answer=42") &&
                    args.contains("ANSWER.txt")
                ),
                checkFinished.length == 2,
                checkFinished.forall(
                  _._2
                ), // check invocations are tool successes
                checkFinished(0)._1.contains("\"exitCode\":1"),
                checkFinished(1)._1.contains("\"exitCode\":0"),
                // the check result ALSO persists in events.jsonl (tool argv)
                procStartedWire.length == 2,
                procStartedWire.forall(args =>
                  args.contains("proc_run") || args.contains("grep")
                ),
                // two applied patches (wrong-value then corrective), each
                // with honest applied/edit provenance
                patchFinished
                  .count(_._1.contains("\"action\":\"modified\"")) == 2,
                // the stale-SHA adversarial patch is REFUSED as structured
                // feedback (transcript bound: resultJson keeps the first 300
                // chars — RA-TOOLDENY + STALE PATCH + echoed provenance are
                // inside; the run STAYS GREEN and the file is untouched,
                // which is the all-or-nothing observable)
                patchFinished.exists(p =>
                  p._2 && p._1.contains("\"applied\":[]") &&
                    p._1.contains("RA-TOOLDENY") &&
                    p._1.contains("STALE PATCH") &&
                    p._1.contains(StaleSha)
                ),
                answer == FixedAnswer
              )
            }
          yield checked
        }
      }
    },
    test(
      "delegated child: provider model propagates from the parent config (RAI-257) and an explicit scripted model overrides it; both children settle Succeeded with persisted Delegated events"
    ) {
      ZIO.acquireReleaseWith(
        for stand <- startStand(
            Set("stand-model", "override-model"),
            propagationRouter
          )
        yield stand
      )(stand => ZIO.succeed(stopStand(stand))) { stand =>
        ZIO.scoped {
          for
            work <- ZIO
              .attempt(Files.createTempDirectory("rai-283-prop"))
              .orDie
            ws <- newWorkspace("rai-283-prop-ws")
            outcome <- runParsed(parseArgs(Nil, work, ws, stand.baseUrl))
            checked <- ZIO.attempt {
              val dir = outcome.workDir
              val wire = readWire(dir)
              val trace = readTranscript(dir)
              val summary = Files.readString(dir.resolve("summary.md"), UTF_8)

              // stand-observed child dispatch models: the child's own request
              // is the single-user-message turn carrying the delegated input
              val defaultModelReq = stand.requests.asScala.toList
                .find(_.messages.exists(_.content == "prop-default"))
              val overrideModelReq = stand.requests.asScala.toList
                .find(_.messages.exists(_.content == "prop-override"))

              val delegatedWire = wire.collect {
                case LineageEnvelope(
                      _,
                      _,
                      _,
                      _,
                      _,
                      _,
                      LineagePayload.Frozen(EventPayload.Delegated(_, agent)),
                      _
                    ) =>
                  agent
              }
              val delegateFeedback = trace.events.collect {
                case TraceEvent.ToolCallFinished(
                      _,
                      _,
                      "delegate",
                      _,
                      res,
                      ok
                    ) =>
                  (res, ok)
              }
              val awaitFeedback = trace.events.collect {
                case TraceEvent.ToolCallFinished(
                      _,
                      _,
                      "await_agent",
                      _,
                      res,
                      ok
                    ) =>
                  (res, ok)
              }

              assertTrue(
                outcome.exitCode == 0,
                outcome.result.exists(r =>
                  r.exitCode == 0 && r.output.exists(
                    _.json.contains("RAI-283 delegation propagation done")
                  )
                ),
                summary.contains("SUCCEEDED"),
                // RAI-257: the parent's configured provider model reaches the
                // child dispatch unmodified
                defaultModelReq.exists(_.model == "stand-model"),
                // explicit delegate-arg override wins
                overrideModelReq.exists(_.model == "override-model"),
                delegatedWire.length == 2,
                delegatedWire.toSet == Set("scout"),
                delegateFeedback.length == 2,
                delegateFeedback.forall(_._2),
                awaitFeedback.length == 2,
                awaitFeedback.forall(_._2),
                awaitFeedback.forall(
                  _._1.contains("\"status\":\"Succeeded\"")
                )
              )
            }
          yield checked
        }
      }
    },
    test(
      "subtree cancellation: a child stuck in a long proc_run is cancelled via cancel_agent, settles Cancelled with the cancellation events, bounded in time, and the parent observes the typed RA-CANCEL feedback"
    ) {
      ZIO.acquireReleaseWith(
        for
          sleep <- resolveSleep
          stand <- startStand(Set("stand-model"), cancelRouter(sleep))
        yield stand
      )(stand => ZIO.succeed(stopStand(stand))) { stand =>
        ZIO.scoped {
          for
            work <- ZIO
              .attempt(Files.createTempDirectory("rai-283-cancel"))
              .orDie
            ws <- newWorkspace("rai-283-cancel-ws")
            startedAt <- zio.Clock.nanoTime
            outcome <- runParsed(parseArgs(Nil, work, ws, stand.baseUrl))
            finishedAt <- zio.Clock.nanoTime
            checked <- ZIO.attempt {
              val dir = outcome.workDir
              val wire = readWire(dir)
              val trace = readTranscript(dir)
              val summary = Files.readString(dir.resolve("summary.md"), UTF_8)
              val elapsedMs = (finishedAt - startedAt) / 1_000_000L

              val cancelFeedback = trace.events.collect {
                case TraceEvent.ToolCallFinished(
                      _,
                      _,
                      "cancel_agent",
                      _,
                      res,
                      ok
                    ) =>
                  (res, ok)
              }
              val awaitFeedback = trace.events.collect {
                case TraceEvent.ToolCallFinished(
                      _,
                      _,
                      "await_agent",
                      _,
                      res,
                      ok
                    ) =>
                  (res, ok)
              }
              val delegated = wire.collect {
                case LineageEnvelope(
                      _,
                      _,
                      _,
                      _,
                      _,
                      _,
                      LineagePayload.Frozen(EventPayload.Delegated(cid, _)),
                      _
                    ) =>
                  cid
              }
              val childId = delegated.headOption.getOrElse(
                throw new IllegalStateException(
                  "no Delegated event persisted for the cancelled child"
                )
              )
              val childEnvelopes = wire.filter(_.jobId == childId)
              val childCancelling = childEnvelopes.exists {
                case LineageEnvelope(
                      _,
                      _,
                      _,
                      _,
                      _,
                      _,
                      LineagePayload.Frozen(
                        EventPayload.JobStatusChanged(_, to, _)
                      ),
                      _
                    ) =>
                  to == JobStatus.Cancelling
                case _ => false
              }
              val childCancelled = childEnvelopes.exists {
                case LineageEnvelope(
                      _,
                      _,
                      _,
                      _,
                      _,
                      _,
                      LineagePayload.Frozen(EventPayload.Terminal(outcome)),
                      _
                    ) =>
                  outcome == JobStatus.Cancelled
                case _ => false
              }

              assertTrue(
                outcome.exitCode == 0,
                outcome.result.exists(r =>
                  r.exitCode == 0 && r.output.exists(
                    _.json.contains("RAI-283 cancel done")
                  )
                ),
                summary.contains("SUCCEEDED"),
                // bounded: without cancellation the child parks ~120s in the
                // tool; the subtree cancel must settle far below that
                elapsedMs < 30_000L,
                cancelFeedback.length == 1,
                cancelFeedback.forall(_._2),
                cancelFeedback.exists(_._1.contains("\"cancelled\":true")),
                awaitFeedback.length == 1,
                awaitFeedback.forall(_._2),
                awaitFeedback.exists(_._1.contains("RA-CANCEL")),
                childCancelling,
                childCancelled,
                // the campaign itself stays green (the parent handles the
                // cancelled child as value feedback)
                campaignTerminal(wire, JobStatus.Succeeded)
              )
            }
          yield checked
        }
      }
    },
    test(
      "budget-limit refusal: --max-dispatches 2 trips the aggregate ceiling after the root dispatch and the child dispatch, with the typed ProviderBudget error and exit code 23 — not a crash"
    ) {
      ZIO.acquireReleaseWith(
        for stand <- startStand(Set("stand-model"), capRouter)
        yield stand
      )(stand => ZIO.succeed(stopStand(stand))) { stand =>
        ZIO.scoped {
          for
            work <- ZIO
              .attempt(Files.createTempDirectory("rai-283-cap"))
              .orDie
            ws <- newWorkspace("rai-283-cap-ws")
            outcome <- runParsed(
              parseArgs(
                List("--max-dispatches", "2"),
                work,
                ws,
                stand.baseUrl
              )
            )
            checked <- ZIO.attempt {
              val dir = outcome.workDir
              val wire = readWire(dir)
              val trace = readTranscript(dir)
              val summary = Files.readString(dir.resolve("summary.md"), UTF_8)
              val runJson = Files.readString(dir.resolve("run.json"), UTF_8)

              val runFailed = trace.events.collect {
                case TraceEvent.RunFailed(_, code, _) => code
              }
              // exactly two dispatches reached the stand: the root turn and
              // the child turn; the parent's next dispatch is refused BEFORE
              // the transport
              val childReq = stand.requests.asScala.toList
                .find(_.messages.exists(_.content == "cap-probe"))

              assertTrue(
                outcome.exitCode == 23,
                outcome.result.exists(r =>
                  r.exitCode == 23 &&
                    r.error.contains(
                      RaiderError.ProviderBudget(
                        "aggregate dispatch cap of 2 reached; " +
                          "further model dispatch is refused"
                      )
                    )
                ),
                runJson.contains("\"exitCode\":23"),
                summary.contains("FAILED"),
                summary.contains("RA-PBUDGET"),
                runFailed.contains("RA-PBUDGET"),
                campaignTerminal(wire, JobStatus.Failed),
                stand.requests.size == 2,
                childReq.exists(_.model == "stand-model")
              )
            }
          yield checked
        }
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(
    zio.Duration.fromSeconds(300)
  )

end CoderCodingLoopAcceptanceSpec
