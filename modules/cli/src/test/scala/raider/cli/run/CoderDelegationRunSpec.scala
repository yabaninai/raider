package raider.cli.run

import raider.core.RootId
import raider.runtime.events.EventPayload
import raider.runtime.lineage.io.LineageWireReader
import raider.runtime.lineage.model.{GraphHash, SessionNode}
import raider.runtime.lineage.reduce.LineageReducer
import raider.runtime.lineage.wire.{LineageEnvelope, LineagePayload}
import raider.dsl.lineage.store.LineageDataStore
import zio.json.{DecoderOps, DeriveJsonDecoder, JsonDecoder}
import zio.test.*
import zio.ZIO

import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}

/** RAI-154 (WIR-01): the headless CODER agent delegates through the PRODUCT
  * entry — `raider run --agent coder` with a delegation-capable scripted policy
  * — and the run exports the UNIFIED multi-job lineage end-to-end (the RAI-144
  * MJE-01 semantics no longer fixture-only):
  *   - the campaign coder loop issues `delegate` → a REAL child job starts on
  *     the run's JobManager, its `Delegated(childJobId, agent)` fork record is
  *     published under the child's id
  *     ([[raider.runtime.delegate.DelegationEvents]] convention) and
  *     [[MultiJobExport]] joins the child segment into the run's single
  *     events.jsonl (correlated v2 envelopes);
  *   - `await_agent` observes the child; the campaign settles; exactly ONE
  *     canonical fork record lives in the campaign stream and exactly ONE
  *     `run_provenance` record rides immediately before the campaign Terminal;
  *   - the strict versioned reduction builds ONE tree (single-level product
  *     run: the Delegation node anchored in the campaign stream);
  *   - [[RunLineageArtifacts]] writes lineage-data SCHEMAVERSION 2 with the
  *     provenance record, next to lineage.scala and the checkpoint.
  *
  * The scripted policy is a LOCAL OpenAI-compatible stand (JDK HttpServer on
  * the loopback, ephemeral port — the OpenAIChatStandSpec framing): no real
  * network, no paid calls, credentials only through the injected test env
  * (RAIDER_API_KEY), never argv. Content routing (RAI-092 discipline): the
  * route picks the turn from the request CONTENT (model name + tool-result
  * presence), never from a call index.
  */
object CoderDelegationRunSpec extends ZIOSpecDefault:

  private val Key = "rai-154-stand-key"

  /** Minimal request view for the stand's routing. */
  private final case class ReqWire(model: String, messages: List[ReqMessage])
  private final case class ReqMessage(role: String, content: String)

  private object ReqWire:
    given JsonDecoder[ReqMessage] = DeriveJsonDecoder.gen[ReqMessage]
    given JsonDecoder[ReqWire] = DeriveJsonDecoder.gen[ReqWire]

  private def toolResults(req: ReqWire): List[String] =
    req.messages.collect { case m if m.role == "tool" => m.content }

  /** The child's id from a delegate tool result (JobManager `j_<n>` ids). */
  private def childIdOf(results: List[String]): String =
    val r = """"child_id":"(j_\d+)"""".r
    results
      .flatMap(m => r.findFirstMatchIn(m).map(_.group(1)))
      .headOption
      .getOrElse("j_missing")

  /** Content-routed delegation policy (RAI-257: the child is recognized by its
    * CONTENT — the single delegated-input message — never by a model-string
    * prefix; the stand records every request's model id and REQUIRES the
    * configured model from BOTH the campaign and the delegated child, refusing
    * any other id with a modeling error):
    *   - child loops (single-message `find x` request) answer with text;
    *   - the campaign's first turn issues `delegate`;
    *   - after the delegate result it issues `await_agent` for the REAL child
    *     id;
    *   - after the await result it finishes with text quoting the child.
    */
  private def startStand()
      : (String, HttpServer, CopyOnWriteArrayList[ReqWire]) =
    val requests = new CopyOnWriteArrayList[ReqWire]()
    val server = HttpServer.create(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      0
    )
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
                  val results = toolResults(req)
                  val childTurn =
                    req.messages.size == 1 &&
                      req.messages.head.content == "find x"
                  if req.model != "stand-model" then
                    // RAI-257: the stand requires the configured model from
                    // every caller — parent AND delegated child
                    reply(
                      400,
                      s"""{"error":{"message":"model ${req.model} not deployed; expected stand-model"}}"""
                    )
                  else if childTurn then
                    // the child loop's single turn
                    reply(
                      200,
                      """{"id":"chatcmpl-rai154-child","choices":[{"index":0,
                        "message":{"role":"assistant","content":"found x"},
                        "finish_reason":"stop"}],
                        "usage":{"prompt_tokens":9,"completion_tokens":3}}"""
                    )
                  else if results.exists(_.contains("\"status\"")) then
                    // await_agent observed the child: finish
                    reply(
                      200,
                      """{"id":"chatcmpl-rai154-final","choices":[{"index":0,
                        "message":{"role":"assistant",
                        "content":"child reported: found x"},
                        "finish_reason":"stop"}],
                        "usage":{"prompt_tokens":31,"completion_tokens":6}}"""
                    )
                  else if results.exists(_.contains("\"child_id\"")) then
                    // delegate returned: await the REAL child id
                    val cid = childIdOf(results)
                    reply(
                      200,
                      s"""{"id":"chatcmpl-rai154-await","choices":[{"index":0,
                        "message":{"role":"assistant","content":null,
                        "tool_calls":[{"id":"call_2","type":"function",
                        "function":{"name":"await_agent",
                        "arguments":"{\\"child_id\\":\\"$cid\\"}"}}]},
                        "finish_reason":"tool_calls"}],
                        "usage":{"prompt_tokens":21,"completion_tokens":4}}"""
                    )
                  else
                    // the campaign's first turn: delegate
                    reply(
                      200,
                      """{"id":"chatcmpl-rai154-delegate","choices":[{"index":0,
                        "message":{"role":"assistant","content":null,
                        "tool_calls":[{"id":"call_1","type":"function",
                        "function":{"name":"delegate",
                        "arguments":"{\"agent\":\"scout\",\"input\":\"find x\"}"}}]},
                        "finish_reason":"tool_calls"}],
                        "usage":{"prompt_tokens":11,"completion_tokens":5}}"""
                    )
        finally exchange.close()
    )
    server.start()
    (s"http://127.0.0.1:${server.getAddress.getPort}/v1", server, requests)

  private def coderArgs(out: Path, ws: Path, baseUrl: String) =
    CliArgs
      .parse(
        List(
          "run",
          "--agent",
          "coder",
          "--input",
          "delegate the search",
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
        )
      )
      .fold(e => throw e, identity)

  // RAI-178 (WIR-01 stability): the suite drives the REAL product runner
  // (HeadlessRunner.run) whose settle/export path polls with ZIO.sleep on the
  // ambient clock (EventExport.awaitCaughtUp, MultiJobExport.drain). On the
  // default zio-test TestClock those waits never advance and the run freezes
  // idle until the 5-minute budget (fiber-verified root cause; see
  // docs/work/records/RAI-178.md). The house pattern for specs that drive the
  // live headless runner is a live clock (ResumeCommandSpec,
  // CiAdapterBatchSpec): a real `raider run` process never runs on a frozen
  // clock. Assertions and the 300 s budget are unchanged.
  def spec = suite(
    "headless coder delegation through the product entry (RAI-154 WIR-01)"
  )(
    test(
      "WIR-01: raider run --agent coder with a delegation-capable scripted policy forks a REAL child, exports the unified tree end-to-end, and writes lineage-data v2 with provenance"
    ) {
      ZIO.acquireReleaseWith(ZIO.succeed(startStand()))(h =>
        ZIO.succeed(h._2.stop(0))
      ) { stand =>
        val baseUrl = stand._1
        val requests = stand._3
        ZIO.scoped {
          for
            work <- ZIO
              .attempt(Files.createTempDirectory("rai-154-wir01"))
              .orDie
            ws <- ZIO
              .attempt(Files.createTempDirectory("rai-154-wir01-ws"))
              .orDie
            outcome <- HeadlessRunner.run(
              coderArgs(work, ws, baseUrl),
              Map("RAIDER_API_KEY" -> Key)
            )
            checked <- ZIO.attempt {
              val dir = outcome.workDir
              // the exported stream, strict
              val wire =
                LineageWireReader.read(dir.resolve("events.jsonl")) match
                  case Right(p) =>
                    if p.truncatedTail.isDefined then
                      throw new IllegalStateException("truncated tail")
                    p.envelopes
                  case Left(e) =>
                    throw new IllegalStateException(s"events.jsonl refused: $e")
              // single-level product run: exactly ONE canonical fork record in
              // the CAMPAIGN stream; the child's own copy never joins
              val forkRecords = wire.collect {
                case e @ LineageEnvelope(
                      1,
                      _,
                      jid,
                      _,
                      _,
                      _,
                      LineagePayload.Frozen(EventPayload.Delegated(c, agent)),
                      _
                    ) if jid.value != c.value =>
                  (jid.value, c.value, agent, e)
              }
              val childLines = wire.filter(_.invocationId.isDefined)
              val provenance = wire.collectFirst {
                case le @ LineageEnvelope(
                      2,
                      _,
                      _,
                      _,
                      _,
                      _,
                      rp: LineagePayload.RunProvenance,
                      _
                    ) =>
                  (le, rp)
              }
              val reduction = LineageReducer.reduceWire(wire)
              val delegationNode = reduction.toOption.flatMap(
                _.graph.nodes.collectFirst { case d: SessionNode.Delegation =>
                  d
                }
              )
              val dataRaw =
                Files.readString(dir.resolve("lineage-data.json"), UTF_8)
              val data =
                LineageDataStore.read(dir.resolve("lineage-data.json"))
              val summary =
                Files.readString(dir.resolve("summary.md"), UTF_8)
              // The campaign JOB id: the manager assigns `j_<n>` ids (the
              // RootId is the run identity, not a job id). The campaign's
              // start transition is ALWAYS the file's first line.
              val campaignId = wire.head.jobId.value
              val campaignTerminalIdx = wire.lastIndexWhere(e =>
                e.jobId.value == campaignId &&
                  (e.payload match {
                    case LineagePayload.Frozen(EventPayload.Terminal(_)) =>
                      true
                    case _ => false
                  })
              )
              (
                campaignId,
                forkRecords,
                childLines,
                provenance,
                reduction,
                delegationNode,
                dataRaw,
                data,
                summary,
                dir,
                campaignTerminalIdx,
                wire
              )
            }.orDie
            (
              campaignId,
              forkRecords,
              childLines,
              provenance,
              reduction,
              delegationNode,
              dataRaw,
              data,
              summary,
              dir,
              campaignTerminalIdx,
              wire
            ) = checked
          yield
            // RAI-257: the recorded provider requests — the child turn is the
            // single-message `find x` request (content-based, never a
            // model-string prefix); the stand REFUSES any non-configured
            // model id, so the child could only succeed by requesting
            // `stand-model`
            val recorded = requests.asScala.toList
            val childReqs =
              recorded.filter(r =>
                r.messages.size == 1 && r.messages.head.content == "find x"
              )
            val parentReqs = recorded.filterNot(r =>
              r.messages.size == 1 && r.messages.head.content == "find x"
            )
            assertTrue(
              outcome.exitCode == 0,
              outcome.result.exists(r =>
                r.output.exists(_.json.contains("child reported: found x"))
              ),
              // RAI-257: the child reached the live-style stand under the
              // CONFIGURED model (the `scripted:scout` label never goes on
              // the wire)
              childReqs.nonEmpty,
              childReqs.forall(_.model == "stand-model"),
              parentReqs.nonEmpty,
              parentReqs.forall(_.model == "stand-model"),
              // the child REALLY ran: correlated v2 envelopes joined
              childLines.nonEmpty,
              childLines.forall(_.schemaVersion == 2),
              childLines.exists(_.payload match {
                case LineagePayload.Frozen(EventPayload.Terminal(_)) => true
                case _                                               => false
              }),
              // exactly ONE canonical fork record, in the campaign stream,
              // verbatim agent
              forkRecords.size == 1,
              forkRecords.headOption.map(_._1) == Some(campaignId),
              forkRecords.headOption.map(_._3) == Some("scout"),
              !childLines.exists(_.payload match {
                case LineagePayload.Frozen(EventPayload.Delegated(_, _)) => true
                case _ => false
              }),
              // exactly ONE provenance record, immediately before the campaign
              // Terminal, root run (parentRunId absent — never guessed)
              provenance.isDefined,
              provenance.map(_._1.jobId.value) == Some(campaignId),
              provenance.map(_._2.parentRunId) == Some(None),
              provenance.map(le => wire.indexOf(le._1)) ==
                Some(campaignTerminalIdx - 1),
              // ONE tree, strictly reduced, delegation node anchored in the
              // campaign stream (single-level anchoring), stable 64-hex hash
              reduction.isRight,
              delegationNode.exists(d => d.id.value.startsWith(campaignId)),
              reduction.map(red => GraphHash(red.graph).length) == Right(64),
              // the unified lineage-data: SCHEMAVERSION 2 with the provenance
              data.isRight,
              data.map(_.schemaVersion) == Right(2),
              data.map(_.provenance.isDefined) == Right(true),
              dataRaw.contains("\"provenance\""),
              Files.isRegularFile(dir.resolve("lineage.scala")),
              summary.contains("- lineage: graph "),
              summary.contains(
                "- lineage artifacts: lineage.scala, lineage-data.json (schemaVersion 2)"
              )
            )
        }
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(
    zio.Duration.fromSeconds(300)
  )

end CoderDelegationRunSpec
