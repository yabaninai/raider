package raider.backend.opencode

import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer

/** Scenario knobs for the deterministic OpenCode contract stand. Each stand
 * instance serves exactly one scenario; nothing depends on wall-clock timing
 * except the adapter's own deadlines/reconnect backoff, which the tests
 * configure explicitly.
 */
enum OcScenario:
  case Normal
  case Unhealthy
  case UnknownVersion
  case OldVersion
  /** The first SSE connection closes right after the server.connected frame. */
  case SseDropFirstConnection
  /** The first SSE connection closes after `n` bus events were delivered. */
  case SseDropAfterEvents(n: Int)
  /** Every SSE connection closes right after the headers. */
  case SseStayDown
  /** Prompt scripts hold at a gate step (after the tool observation) until an
   * abort opens the gate and replaces the remaining script with the abort
   * outcome events. With `silent=true` the abort is acknowledged but the gate
   * never opens, so the run never settles through events.
   */
  case AbortGate(silent: Boolean)
  /** Prompt scripts run to completion but never emit session.idle. */
  case NoIdle
  /** One scripted event frame is deliberately malformed (session.idle without
   * sessionID), breaking the event stream once.
   */
  case BadEventOnce

/** Deterministic loopback HTTP stand mirroring the pinned OpenCode server
 * surface (RAI-035 fixture, SDK wire shapes v1.19.4). Bound to port 0;
 * acquire per test, `stop()` on release. The request log supports
 * no-mutation-replay assertions; the Authorization header is recorded as a
 * presence flag only — secret values are never stored.
 *
 * Determinism: prompt scripts are advanced by a synchronous round-robin
 * stepper inside the prompt_async handler (one step per pending script per
 * advance tick), so event order is fixed by submission order, independent of
 * thread scheduling. The AbortGate scenario is fully deterministic: the
 * script pauses at a gate until the abort endpoint opens it.
 */
final class OpenCodeContractStand private (
    server: HttpServer,
    val port: Int,
    val password: Option[String],
    scenario: OcScenario,
    executor: java.util.concurrent.ExecutorService,
):

  def baseUrl: String = s"http://127.0.0.1:$port"

  // ---------------------------------------------------------------------
  // Request log (no secret values)
  // ---------------------------------------------------------------------

  final case class RequestLog(method: String, path: String, query: String, hasAuth: Boolean, body: String)

  private val requestLog = new java.util.Vector[RequestLog]()

  def requests: Vector[RequestLog] = Iterators.toVector(requestLog.iterator())

  def countRequests(method: String, path: String): Int =
    requests.count(r => r.method == method && r.path == path)

  // ---------------------------------------------------------------------
  // SSE bus
  // ---------------------------------------------------------------------

  private val sseConnections = new CopyOnWriteArrayList[LinkedBlockingQueue[String]]()
  private val sseConnectionCounter = new AtomicLong(0)

  def sseOpenCount: Long = sseConnectionCounter.get()

  private def appendBusEvent(json: String): Unit =
    val it = sseConnections.iterator()
    while it.hasNext do it.next().add(json)

  // ---------------------------------------------------------------------
  // Session fixture state
  // ---------------------------------------------------------------------

  private final case class AssistantMeta(aid: String, mid: String, created: Long)

  private final class FixtureSession(val id: String, val directory: String, val title: String, val timeCreated: Long):
    val running = new AtomicBoolean(false)
    val promptText = new AtomicReference[String]("")
    /** Final message-list entries ({"info":...,"parts":[...]}) served by
      * GET /session/{id}/message for reconciliation. Index 0 is the user
      * message, index 1 the assistant message. Rewritten by the abort
      * handler.
      */
    val messageList = new java.util.Vector[String]()
    val assistantMeta = new AtomicReference[Option[AssistantMeta]](None)

    def setMessages(userEntry: String, assistantEntry: String, meta: AssistantMeta): Unit =
      messageList.clear()
      messageList.add(userEntry)
      messageList.add(assistantEntry)
      assistantMeta.set(Some(meta))

  private val sessions = new ConcurrentHashMap[String, FixtureSession]()
  private val sessionCounter = new AtomicInteger(0)
  private val timeCounter = new AtomicInteger(0)

  private def nextTime(): Long = 1760000000000L + timeCounter.incrementAndGet() * 100L

  // ---------------------------------------------------------------------
  // JSON helpers (hand-rolled; the stand ships no JSON library)
  // ---------------------------------------------------------------------

  private def jsonEsc(s: String): String =
    s.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case '\n'         => "\\n"
      case '\r'         => "\\r"
      case '\t'         => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    }

  private def globalEvent(directory: String, payloadType: String, propertiesJson: String): String =
    s"""{"directory":"${jsonEsc(directory)}","payload":{"type":"$payloadType","properties":$propertiesJson}}"""

  private def sessionJson(s: FixtureSession): String =
    val t = s.timeCreated
    s"""{"id":"${jsonEsc(s.id)}","projectID":"proj-fixture","directory":"${jsonEsc(
        s.directory
      )}","title":"${jsonEsc(s.title)}","version":"1.19.4","time":{"created":$t,"updated":$t}}"""

  private def userMessageJson(sid: String, mid: String, directory: String, created: Long): String =
    s"""{"id":"${jsonEsc(mid)}","sessionID":"${jsonEsc(sid)}","role":"user","time":{"created":$created},"agent":"build","model":{"providerID":"fixture","modelID":"test-model"}}"""

  private def assistantMessageJson(
      sid: String,
      aid: String,
      mid: String,
      created: Long,
      completed: Option[Long],
      errorJson: Option[String],
  ): String =
    val completedPart = completed.map(c => s",\"completed\":$c").getOrElse("")
    val errorPart = errorJson.map(e => s",\"error\":$e").getOrElse("")
    s"""{"id":"${jsonEsc(aid)}","sessionID":"${jsonEsc(sid)}","role":"assistant","time":{"created":$created$completedPart}$errorPart,"parentID":"${jsonEsc(
        mid
      )}","modelID":"test-model","providerID":"fixture","mode":"build","path":{"cwd":"/fixture/ws","root":"/fixture/ws"},"cost":0.01,"tokens":{"input":10,"output":5,"reasoning":0,"cache":{"read":0,"write":0}},"finish":"stop"}"""

  private def abortedErrorJson: String =
    """{"name":"MessageAbortedError","data":{"message":"The user aborted the message."}}"""

  private def partUpdated(sid: String, partJson: String, delta: Option[String]): String =
    delta match
      case Some(d) => s"""{"part":$partJson,"delta":"${jsonEsc(d)}"}"""
      case None    => s"""{"part":$partJson}"""

  private def textPartJson(sid: String, mid: String, partId: String, text: String): String =
    s"""{"id":"$partId","sessionID":"${jsonEsc(sid)}","messageID":"${jsonEsc(mid)}","type":"text","text":"${jsonEsc(text)}"}"""

  private def toolPartJson(
      sid: String,
      mid: String,
      partId: String,
      status: String,
      timeStart: Long,
  ): String =
    val body = status match
      case "pending" =>
        s""""status":"pending","input":{"command":"echo fixture"},"raw":"{}""""
      case "running" =>
        s""""status":"running","input":{"command":"echo fixture"},"title":"echo fixture","time":{"start":$timeStart}"""
      case "completed" =>
        s""""status":"completed","input":{"command":"echo fixture"},"output":"fixture-output\\n","title":"echo fixture","metadata":{},"time":{"start":$timeStart,"end":${timeStart + 5}}"""
      case other =>
        s""""status":"$other","input":{"command":"echo fixture"},"error":"fixture failure","time":{"start":$timeStart,"end":${timeStart + 5}}"""
    s"""{"id":"$partId","sessionID":"${jsonEsc(sid)}","messageID":"${jsonEsc(mid)}","type":"tool","callID":"call-$partId","tool":"bash","state":{$body}}"""

  private def regexGroup(s: String, re: String): String =
    re.r.findFirstMatchIn(s).map(_.group(1)).orNull

  private def readBody(ex: HttpExchange): String =
    val in = ex.getRequestBody
    val buf = new Array[Byte](4096)
    val out = new java.io.ByteArrayOutputStream()
    var n = in.read(buf)
    while n > 0 do
      out.write(buf, 0, n)
      n = in.read(buf)
    new String(out.toByteArray, StandardCharsets.UTF_8)

  // ---------------------------------------------------------------------
  // Script construction
  // ---------------------------------------------------------------------

  private final class FixtureScript(
      val sessionId: String,
      val gateIndex: Option[Int],
      val gateOpen: AtomicBoolean,
      val steps: java.util.concurrent.ConcurrentLinkedDeque[String],
      val stepIndex: AtomicInteger,
  )

  /** Builds the deterministic prompt script for a session and stores the
   * final message-list entries used by read-only reconciliation.
   *
   * Pre-gate step order (indices 0..10):
   * 0 user message.updated
   * 1 session.status busy
   * 2 assistant message.updated (running)
   * 3 step-start part
   * 4 tool pending
   * 5 tool running
   * 6 tool completed
   * 7 text delta "Reply to: "
   * 8 text delta promptText
   * 9 text final part
   * 10 step-finish part
   */
  private def buildScript(s: FixtureSession, messageId: String, promptText: String): FixtureScript =
    val sid = s.id
    val dir = s.directory
    val aid = s"msg-asst-$sid"
    val created = nextTime()
    val textFinal = s"Reply to: $promptText"
    val toolPartId = s"part-tool-$aid"
    val textPartId = s"part-text-$aid"
    val toolStart = created + 10

    def assistantCompletedEvent = globalEvent(
      dir,
      "message.updated",
      s"""{"info":${assistantMessageJson(sid, aid, messageId, created, Some(created + 500), None)}}""",
    )
    def sessionStatusIdle = globalEvent(
      dir,
      "session.status",
      s"""{"sessionID":"${jsonEsc(sid)}","status":{"type":"idle","sessionID":"${jsonEsc(sid)}"}}""",
    )
    def sessionIdle = globalEvent(dir, "session.idle", s"""{"sessionID":"${jsonEsc(sid)}"}""")

    val pre = Vector(
      globalEvent(dir, "message.updated", s"""{"info":${userMessageJson(sid, messageId, dir, created)}}"""),
      globalEvent(
        dir,
        "session.status",
        s"""{"sessionID":"${jsonEsc(sid)}","status":{"type":"busy","sessionID":"${jsonEsc(sid)}"}}""",
      ),
      globalEvent(
        dir,
        "message.updated",
        s"""{"info":${assistantMessageJson(sid, aid, messageId, created, None, None)}}""",
      ),
      globalEvent(
        dir,
        "message.part.updated",
        partUpdated(
          sid,
          s"""{"id":"part-ss-$aid","sessionID":"${jsonEsc(sid)}","messageID":"${jsonEsc(aid)}","type":"step-start"}""",
          None,
        ),
      ),
      globalEvent(dir, "message.part.updated", partUpdated(sid, toolPartJson(sid, aid, toolPartId, "pending", toolStart), None)),
      globalEvent(dir, "message.part.updated", partUpdated(sid, toolPartJson(sid, aid, toolPartId, "running", toolStart), None)),
      globalEvent(dir, "message.part.updated", partUpdated(sid, toolPartJson(sid, aid, toolPartId, "completed", toolStart), None)),
      globalEvent(dir, "message.part.updated", partUpdated(sid, textPartJson(sid, aid, textPartId, ""), Some("Reply to: "))),
      globalEvent(dir, "message.part.updated", partUpdated(sid, textPartJson(sid, aid, textPartId, ""), Some(promptText))),
      globalEvent(dir, "message.part.updated", partUpdated(sid, textPartJson(sid, aid, textPartId, textFinal), None)),
      globalEvent(
        dir,
        "message.part.updated",
        partUpdated(
          sid,
          s"""{"id":"part-sf-$aid","sessionID":"${jsonEsc(sid)}","messageID":"${jsonEsc(aid)}","type":"step-finish","reason":"stop"}""",
          None,
        ),
      ),
    )

    val full: Vector[String] = scenario match
      case OcScenario.AbortGate(_) =>
        pre ++ Vector(assistantCompletedEvent, sessionStatusIdle, sessionIdle)
      case OcScenario.NoIdle =>
        pre :+ assistantCompletedEvent
      case OcScenario.BadEventOnce =>
        // Malformed session.idle right after the running-assistant event.
        val malformed = globalEvent(dir, "session.idle", """{"wrong":"shape"}""")
        (pre.take(3) :+ malformed) ++ pre.drop(3) ++ Vector(assistantCompletedEvent, sessionStatusIdle, sessionIdle)
      case _ =>
        pre ++ Vector(assistantCompletedEvent, sessionStatusIdle, sessionIdle)

    // Final message-list entries for reconciliation.
    s.promptText.set(promptText)
    val userEntry =
      s"""{"info":${userMessageJson(sid, messageId, dir, created)},"parts":[${textPartJson(
          sid,
          messageId,
          s"part-text-$messageId",
          promptText,
        )}]}"""
    val assistantCompletedInList = scenario match
      case OcScenario.AbortGate(_) => None // decided by the abort handler
      case _                       => Some(created + 500)
    val assistantEntry = assistantEntryJson(sid, aid, messageId, created, assistantCompletedInList, None, promptText)
    s.setMessages(userEntry, assistantEntry, AssistantMeta(aid, messageId, created))

    scenario match
      case OcScenario.AbortGate(silent) =>
        new FixtureScript(
          sid,
          gateIndex = Some(pre.size), // gate holds the assistant-completed step
          gateOpen = new AtomicBoolean(false),
          steps = asDeque(full),
          stepIndex = new AtomicInteger(0),
        )
      case _ =>
        new FixtureScript(sid, None, new AtomicBoolean(true), asDeque(full), new AtomicInteger(0))

  private def asDeque(v: Vector[String]) =
    val d = new java.util.concurrent.ConcurrentLinkedDeque[String]()
    v.foreach(d.add)
    d

  private def assistantEntryJson(
      sid: String,
      aid: String,
      mid: String,
      created: Long,
      completed: Option[Long],
      errorJson: Option[String],
      promptText: String,
  ): String =
    val toolPartId = s"part-tool-$aid"
    val textPartId = s"part-text-$aid"
    val text = if completed.isDefined then "Reply to: " + promptText else ""
    s"""{"info":${assistantMessageJson(sid, aid, mid, created, completed, errorJson)},"parts":[${toolPartJson(
        sid,
        aid,
        toolPartId,
        "completed",
        created + 10,
      )},${textPartJson(sid, aid, textPartId, text)}]}"""

  // ---------------------------------------------------------------------
  // Abort outcome (deterministic)
  // ---------------------------------------------------------------------

  /** Replaces the remaining script steps with the abort outcome events and
   * rewrites the stored assistant message list entry, then drains the
   * stepper.
   */
  private def applyAbortOutcome(script: FixtureScript): Unit =
    val s = sessions.get(script.sessionId)
    val dir = s.directory
    s.assistantMeta.get() match
      case Some(AssistantMeta(aid, mid, created)) =>
        s.messageList.set(
          1,
          assistantEntryJson(s.id, aid, mid, created, Some(created + 900), Some(abortedErrorJson), s.promptText.get()),
        )
        val outcome = Vector(
          globalEvent(
            dir,
            "message.updated",
            s"""{"info":${assistantMessageJson(s.id, aid, mid, created, Some(created + 900), Some(abortedErrorJson))}}""",
          ),
          globalEvent(dir, "session.error", s"""{"sessionID":"${jsonEsc(script.sessionId)}","error":$abortedErrorJson}"""),
          globalEvent(dir, "session.idle", s"""{"sessionID":"${jsonEsc(script.sessionId)}"}"""),
        )
        script.steps.clear()
        outcome.foreach(script.steps.add)
        drainAdvance(budget = 64)
      case None => ()

  // ---------------------------------------------------------------------
  // Round-robin stepper (synchronous, deterministic)
  // ---------------------------------------------------------------------

  private val pending = new ConcurrentLinkedQueue[FixtureScript]()

  /** One advance tick: every pending script yields its next step (gated
   * scripts hold). Submission order fixes the interleave.
   */
  private def advanceOnce(): Unit = synchronized {
    Iterators.toVector(pending.iterator()).foreach { script =>
      val idx = script.stepIndex.get()
      val gated = script.gateIndex.exists(_ == idx) && !script.gateOpen.get()
      if !gated then
        val step = script.steps.poll()
        if step ne null then
          script.stepIndex.incrementAndGet()
          appendBusEvent(step)
          if script.steps.isEmpty then pending.remove(script)
    }
  }

  private def drainAdvance(budget: Int): Unit =
    var i = 0
    while i < budget && !pending.isEmpty do
      advanceOnce()
      i += 1

  // ---------------------------------------------------------------------
  // HTTP plumbing
  // ---------------------------------------------------------------------

  private def queryParam(ex: HttpExchange, name: String): Option[String] =
    val raw = ex.getRequestURI.getRawQuery
    if raw eq null then None
    else
      val it = raw
        .split("&")
        .iterator
        .flatMap { kv =>
          val idx = kv.indexOf('=')
          if idx <= 0 then Iterator.empty
          else
            val k = URLDecoder.decode(kv.substring(0, idx), StandardCharsets.UTF_8)
            if k == name then Iterator.single(URLDecoder.decode(kv.substring(idx + 1), StandardCharsets.UTF_8))
            else Iterator.empty
        }
      if it.hasNext then Some(it.next()) else None

  private def authorized(ex: HttpExchange): Boolean =
    password match
      case None => true
      case Some(pw) =>
        val header = ex.getRequestHeaders.getFirst("Authorization")
        if header eq null then false
        else
          val expected =
            "Basic " + Base64.getEncoder.encodeToString(s"opencode:$pw".getBytes(StandardCharsets.UTF_8))
          java.security.MessageDigest.isEqual(
            header.getBytes(StandardCharsets.UTF_8),
            expected.getBytes(StandardCharsets.UTF_8),
          )

  private def respond(ex: HttpExchange, status: Int, body: String, contentType: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    ex.getResponseHeaders.add("Content-Type", contentType)
    ex.sendResponseHeaders(status, if bytes.isEmpty then -1 else bytes.length.toLong)
    if bytes.nonEmpty then ex.getResponseBody.write(bytes)
    ex.close()

  private def logRequest(ex: HttpExchange, body: String): Unit =
    requestLog.add(
      RequestLog(
        ex.getRequestMethod,
        ex.getRequestURI.getPath,
        Option(ex.getRequestURI.getRawQuery).getOrElse(""),
        ex.getRequestHeaders.getFirst("Authorization") ne null,
        body,
      )
    )

  private def guard(ex: HttpExchange)(body: => Unit): Unit =
    if authorized(ex) then body
    else respond(ex, 401, """{"name":"AuthError","data":{"message":"invalid credentials"}}""", "application/json")

  private def healthBody: String = scenario match
    case OcScenario.Unhealthy      => """{"healthy":false,"version":"0.0.0"}"""
    case OcScenario.UnknownVersion => """{"healthy":true,"version":"fixture-not-semver"}"""
    case OcScenario.OldVersion     => """{"healthy":true,"version":"0.9.2"}"""
    case _                         => """{"healthy":true,"version":"1.19.4"}"""

  private def handleHealth(ex: HttpExchange): Unit =
    logRequest(ex, "")
    guard(ex)(respond(ex, 200, healthBody, "application/json"))

  private def handleSessionCreate(ex: HttpExchange): Unit =
    val body = readBody(ex)
    logRequest(ex, body)
    guard(ex) {
      val id = s"ses-fixture-${sessionCounter.incrementAndGet()}"
      val dir = queryParam(ex, "directory").getOrElse("/fixture/ws")
      val title = Option(regexGroup(body, "\"title\"\\s*:\\s*\"([^\"]*)\"")).getOrElse("New Session")
      val s = new FixtureSession(id, dir, title, nextTime())
      sessions.put(id, s)
      appendBusEvent(globalEvent(dir, "session.created", sessionJson(s)))
      respond(ex, 200, sessionJson(s), "application/json")
    }

  private def sessionFromPath(path: String): Option[FixtureSession] =
    // path forms: /session/{id}, /session/{id}/prompt_async, /session/{id}/abort, /session/{id}/message
    val rest = path.stripPrefix("/session/")
    val id = rest.takeWhile(_ != '/')
    Option(sessions.get(id))

  private def handleSessionGet(ex: HttpExchange): Unit =
    logRequest(ex, "")
    guard(ex):
      sessionFromPath(ex.getRequestURI.getPath) match
        case Some(s) => respond(ex, 200, sessionJson(s), "application/json")
        case None =>
          respond(ex, 404, """{"name":"NotFoundError","data":{"message":"session not found"}}""", "application/json")

  private def handleSessionStatus(ex: HttpExchange): Unit =
    logRequest(ex, "")
    guard(ex):
      val entries = Iterators.toVector(sessions.values().iterator()).map { s =>
        s""""${jsonEsc(s.id)}":{"type":"${if s.running.get() then "busy" else "idle"}"}"""
      }
      respond(ex, 200, "{" + entries.mkString(",") + "}", "application/json")

  private def handleMessages(ex: HttpExchange): Unit =
    logRequest(ex, "")
    guard(ex):
      sessionFromPath(ex.getRequestURI.getPath) match
        case Some(s) =>
          val entries = Iterators.toVector(s.messageList.iterator())
          respond(ex, 200, "[" + entries.mkString(",") + "]", "application/json")
        case None =>
          respond(ex, 404, """{"name":"NotFoundError","data":{"message":"session not found"}}""", "application/json")

  private def handlePromptAsync(ex: HttpExchange): Unit =
    val body = readBody(ex)
    logRequest(ex, body)
    guard(ex):
      sessionFromPath(ex.getRequestURI.getPath) match
        case Some(s) =>
          val messageId = Option(regexGroup(body, "\"messageID\"\\s*:\\s*\"([^\"]+)\"")).getOrElse("msg-fixture")
          val promptText =
            Option(regexGroup(body, "\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""))
              .map(s => jsonUnescape(s))
              .getOrElse("")
          val script = buildScript(s, messageId, promptText)
          s.running.set(true)
          pending.add(script)
          val tickBudget = script.steps.size() + script.stepIndex.get() + 4
          var i = 0
          while i < tickBudget && !pending.isEmpty do
            advanceOnce()
            i += 1
          respond(ex, 204, "", "application/json")
        case None =>
          respond(ex, 404, """{"name":"NotFoundError","data":{"message":"session not found"}}""", "application/json")

  private def jsonUnescape(s: String): String =
    val sb = new StringBuilder
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      if c == '\\' && i + 1 < s.length then
        i += 1
        s.charAt(i) match
          case 'n'  => sb.append('\n')
          case 't'  => sb.append('\t')
          case 'r'  => sb.append('\r')
          case '"'  => sb.append('"')
          case '\\' => sb.append('\\')
          case _    => ()
      else sb.append(c)
      i += 1
    sb.result()

  private def handleAbort(ex: HttpExchange): Unit =
    logRequest(ex, "")
    guard(ex):
      sessionFromPath(ex.getRequestURI.getPath) match
        case None =>
          respond(ex, 404, """{"name":"NotFoundError","data":{"message":"session not found"}}""", "application/json")
        case Some(s) =>
          val script = Iterators.toVector(pending.iterator()).find(_.sessionId == s.id)
          scenario match
            case OcScenario.AbortGate(false) if s.running.get() =>
              respond(ex, 200, "true", "application/json")
              script.foreach { sc =>
                sc.gateOpen.set(true)
                applyAbortOutcome(sc)
              }
            case _ =>
              // Idle session or silent scenario: acknowledge but change nothing.
              respond(ex, 200, if s.running.get() then "true" else "false", "application/json")

  private def writeFrame(out: OutputStream, json: String): Unit =
    out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8))

  private def handleEvents(ex: HttpExchange): Unit =
    logRequest(ex, "")
    guard(ex):
      val connIndex = sseConnectionCounter.incrementAndGet()
      val queue = new LinkedBlockingQueue[String]()
      sseConnections.add(queue)
      try
        ex.getResponseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0L)
        val out = ex.getResponseBody
        writeFrame(out, globalEvent("/fixture/ws", "server.connected", "{}"))
        out.flush()
        val dropNow = scenario match
          case OcScenario.SseStayDown                                  => true
          case OcScenario.SseDropFirstConnection if connIndex == 1     => true
          case _                                                       => false
        var delivered = 0
        var keepGoing = !dropNow
        while keepGoing do
          val ev = queue.take()
          writeFrame(out, ev)
          out.flush()
          delivered += 1
          scenario match
            case OcScenario.SseDropAfterEvents(n) if connIndex == 1 && delivered >= n => keepGoing = false
            case _                                                                    => ()
      catch
        case _: IOException        => ()
        case _: InterruptedException => ()
      finally
        sseConnections.remove(queue)
        ex.close()

  // ---------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------

  def stop(): Unit =
    server.stop(0)
    // The fixed pool is non-daemon; without an explicit shutdown the JVM
    // would not exit after the test run.
    executor.shutdownNow()

object OpenCodeContractStand:

  /** Starts a stand bound to a loopback port assigned by the OS. */
  def start(scenario: OcScenario = OcScenario.Normal, password: Option[String] = None): OpenCodeContractStand =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val executor = Executors.newFixedThreadPool(12)
    val stand = new OpenCodeContractStand(server, server.getAddress.getPort, password, scenario, executor)
    server.createContext("/global/health", ex => stand.handleHealth(ex))
    server.createContext("/global/event", ex => stand.handleEvents(ex))
    server.createContext("/session/status", ex => stand.handleSessionStatus(ex))
    server.createContext("/session/", ex => routeSession(stand, ex))
    server.createContext("/session", ex => stand.handleSessionCreate(ex))
    server.setExecutor(executor)
    server.start()
    stand

  private def routeSession(stand: OpenCodeContractStand, ex: HttpExchange): Unit =
    val path = ex.getRequestURI.getPath
    if path.endsWith("/prompt_async") then stand.handlePromptAsync(ex)
    else if path.endsWith("/abort") then stand.handleAbort(ex)
    else if path.endsWith("/message") then stand.handleMessages(ex)
    else stand.handleSessionGet(ex)

/** Small helpers to iterate Java collections without a Scala-Java
 * conversion dependency.
 */
private object Iterators:
  def toVector[A](it: java.util.Iterator[A]): Vector[A] =
    val b = Vector.newBuilder[A]
    while it.hasNext do b += it.next()
    b.result()
