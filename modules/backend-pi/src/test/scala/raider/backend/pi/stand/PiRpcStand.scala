package raider.backend.pi.stand

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable

import zio.json.*
import zio.json.ast.Json

/** Test-only stand that mimics the pinned pi RPC surface (upstream pin
  * 55a5c6914570e2ae31ca36297654483bbbf46883): JSONL commands on stdin,
  * responses and session events on stdout, diagnostics on stderr. It is a REAL
  * subprocess spawned by the specs, not an in-process mock.
  *
  * Scripts are selected with `--script <name>`; `--version-string` overrides
  * the reported version; `--version` prints the version and exits.
  */
object PiRpcStand:

  val DefaultVersion: String = "pi-rpc-stand 1.0.0"

  def main(args: Array[String]): Unit =
    var script = "prompt-tool-run"
    var version = DefaultVersion
    var probeOnly = false
    var i = 0
    while i < args.length do
      args(i) match
        case "--script" if i + 1 < args.length =>
          script = args(i + 1); i += 1
        case "--version-string" if i + 1 < args.length =>
          version = args(i + 1); i += 1
        case "--version" =>
          probeOnly = true
        case other =>
          err(s"unknown argument: $other")
          sys.exit(2)
      i += 1
    if probeOnly then
      out(version)
      sys.exit(0)
    Scripts.find(script) match
      case None =>
        err(s"unknown script: $script")
        sys.exit(2)
      case Some(s) =>
        err(s"pi-rpc-stand ready script=$script version=$version")
        RunLoop.run(s, version)

  private def err(line: String): Unit =
    System.err.synchronized:
      System.err.write((line + "\n").getBytes(StandardCharsets.UTF_8))
      System.err.flush()

  private def out(line: String): Unit =
    System.out.synchronized:
      System.out.write((line + "\n").getBytes(StandardCharsets.UTF_8))
      System.out.flush()

/** Shared mutable state for one stand run: emission helpers used by script
  * threads and the abort flag fed by the stdin reader.
  */
final class RunState(val version: String):
  val abortRequested = new AtomicBoolean(false)
  val settled = new AtomicBoolean(false)
  private val stdoutLock = new Object

  def respond(
      id: String,
      command: String,
      success: Boolean,
      disposition: Option[String]
  ): Unit =
    val disp =
      disposition.map(d => s""","data":{"disposition":"$d"}""").getOrElse("")
    val errPart = if success then "" else s""","error":"refused""""
    out(
      s"""{"id":"$id","type":"response","command":"$command","success":$success$errPart$disp}"""
    )

  def respondParseError(): Unit =
    // pi mirrors malformed stdin with an id-less parse response
    out(
      s"""{"type":"response","command":"parse","success":false,"error":"malformed JSON"}"""
    )

  def respondUnknown(id: String, what: String): Unit =
    out(
      s"""{"id":"$id","type":"response","command":"$what","success":false,"error":"unknown command"}"""
    )

  def event(line: String): Unit = out(line)

  /** Raw frame with literal U+2028/U+2029 data characters; hand-built because
    * encoders escape them, and the framer contract says they are data.
    */
  def unicodeDeltaFrame(): Unit =
    val delta = "sep\u2028inside\u2029frame"
    event(
      s"""{"type":"message_update","message":{"role":"assistant","content":[]},""" +
        s""""assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"$delta"}}"""
    )

  def sleep(ms: Long): Unit =
    try Thread.sleep(ms)
    catch case _: InterruptedException => Thread.currentThread().interrupt()

  def markSettled(aborted: Boolean): Unit =
    event(s"""{"type":"agent_settled","aborted":$aborted}""")
    settled.set(true)

  private def out(line: String): Unit =
    stdoutLock.synchronized:
      System.out.write((line + "\n").getBytes(StandardCharsets.UTF_8))
      System.out.flush()

/** Script behaviors. Every script that accepts a prompt must emit exactly one
  * terminal `agent_settled` unless it models a crash/ignore scenario.
  */
trait Script:
  def name: String

  /** Emit the event sequence for an accepted prompt (response already sent). */
  def play(state: RunState, id: String): Unit

  /** Reaction to an abort command. Default: acknowledge and settle aborted. */
  def onAbort(state: RunState, id: String): Unit =
    state.respond(id, "abort", success = true, None)
    state.markSettled(aborted = true)

  /** Message object for the assistant snapshot. */
  private def msg(content: String, stop: String, usage: String): String =
    val u = if usage.isEmpty then "" else s""","usage":$usage"""
    s"""{"role":"assistant","content":[{"type":"text","text":"$content"}],"uuid":"u-1","stopReason":"$stop"$u}"""

  protected def usageJson: String =
    s"""{"input":120,"output":34,"cost":{"total":0.0011,"currency":"USD"}}"""

  protected def messageStart(
      role: String,
      content: String,
      stop: String = "pending"
  ): String =
    s"""{"type":"message_start","message":{"role":"$role","content":[{"type":"text","text":"$content"}],"uuid":"u-1","stopReason":"$stop"}}"""

  protected def messageEnd(
      role: String,
      content: String,
      stop: String,
      usage: String = ""
  ): String =
    s"""{"type":"message_end","message":${msg(content, stop, usage)}}"""

  protected def updateText(delta: String, withUsage: Boolean = false): String =
    val m =
      if withUsage then msg("", "pending", usageJson)
      else msg("", "pending", "")
    s"""{"type":"message_update","message":$m,""" +
      s""""assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"$delta"}}"""

  protected def updateToolStart(
      index: Int,
      callId: String,
      toolName: String
  ): String =
    s"""{"type":"message_update","message":${msg("", "pending", "")},""" +
      s""""assistantMessageEvent":{"type":"toolcall_start","contentIndex":$index,"id":"$callId","toolName":"$toolName"}}"""

  protected def updateToolDelta(index: Int, fragment: String): String =
    // The fragment is raw argument text (quotes/braces included); it must be
    // emitted as an escaped JSON string, the way pi frames partial JSON.
    val escaped = Json.Str(fragment).toJson
    s"""{"type":"message_update","message":${msg("", "pending", "")},""" +
      s""""assistantMessageEvent":{"type":"toolcall_delta","contentIndex":$index,"delta":$escaped}}"""

  protected def updateToolEnd(
      index: Int,
      callId: String,
      toolName: String,
      args: String
  ): String =
    s"""{"type":"message_update","message":${msg("", "pending", "")},""" +
      s""""assistantMessageEvent":{"type":"toolcall_end","contentIndex":$index,""" +
      s""""toolCall":{"type":"toolCall","id":"$callId","name":"$toolName","arguments":$args}}}"""

  protected def toolExecStart(callId: String): String =
    s"""{"type":"tool_execution_start","toolCallId":"$callId","toolName":"proc_run","args":{"command":"make test"}}"""

  protected def toolExecUpdate(callId: String): String =
    s"""{"type":"tool_execution_update","toolCallId":"$callId","partial":{"prefix":"running"}}"""

  protected def toolExecEnd(callId: String): String =
    s"""{"type":"tool_execution_end","toolCallId":"$callId","isError":false,"durationMs":5,""" +
      s""""result":{"ok":true,"exitCode":0}}"""

  protected def agentStart = s"""{"type":"agent_start"}"""

  protected def turnStart(index: Int) =
    s"""{"type":"turn_start","index":$index}"""

  protected def turnEnd(index: Int) =
    s"""{"type":"turn_end","index":$index,"message":${msg(
        "",
        "stop",
        ""
      )},"toolResults":[]}"""

  protected def agentEnd(willRetry: Boolean) =
    s"""{"type":"agent_end","messages":[],"willRetry":$willRetry}"""

/** PIRPC-01: complete prompt + tool run with usage, tool execution and a single
  * settled terminal.
  */
object PromptToolRun extends Script:
  val name = "prompt-tool-run"

  def play(state: RunState, id: String): Unit =
    state.event(agentStart)
    state.event(turnStart(0))
    state.event(messageStart("user", "Run the checks", "stop"))
    state.event(messageEnd("user", "Run the checks", "stop"))
    state.event(messageStart("assistant", "", "pending"))
    state.event(updateText("Ran ", withUsage = true))
    state.event(updateText("the check."))
    state.event(updateToolStart(1, "call_1", "proc_run"))
    state.event(updateToolDelta(1, "{\"command\":"))
    state.event(updateToolDelta(1, "\"make test\"}"))
    state.event(
      updateToolEnd(1, "call_1", "proc_run", "{\"command\":\"make test\"}")
    )
    state.event(
      s"""{"type":"message_end","message":{"role":"assistant","uuid":"u-1","stopReason":"toolUse",""" +
        s""""content":[{"type":"text","text":"Ran the check."},""" +
        s"""{"type":"toolCall","id":"call_1","name":"proc_run","arguments":{"command":"make test"}}]}}"""
    )
    state.event(toolExecStart("call_1"))
    state.event(toolExecUpdate("call_1"))
    state.event(toolExecEnd("call_1"))
    state.event(
      s"""{"type":"message_start","message":{"role":"toolResult","uuid":"u-2","stopReason":"stop",""" +
        s""""content":[{"type":"toolResult","toolCallId":"call_1","output":{"ok":true,"exitCode":0},"isError":false}]}}"""
    )
    state.event(turnEnd(0))
    state.event(agentEnd(willRetry = false))
    state.markSettled(aborted = false)

/** PIRPC-02: prompt accepted and streaming, but the run is final only at
  * agent_settled, well after the acceptance response.
  */
object SlowSettle extends Script:
  val name = "slow-settle"

  def play(state: RunState, id: String): Unit =
    state.event(agentStart)
    state.event(updateText("working"))
    state.sleep(1200)
    state.event(agentEnd(willRetry = false))
    state.markSettled(aborted = false)

/** PIRPC-02: raw garbage line on stdout (strict noise rule). */
object StdoutNoise extends Script:
  val name = "stdout-noise"

  def play(state: RunState, id: String): Unit =
    state.event(agentStart)
    state.event("this line is not json at all")
    state.markSettled(aborted = false)

/** PIRPC-02: truncated JSON frame on stdout. */
object CorruptJson extends Script:
  val name = "corrupt-json"

  def play(state: RunState, id: String): Unit =
    state.event(agentStart)
    state.event("""{"type":"agent_start"""")
    state.markSettled(aborted = false)

/** PIRPC-02: abrupt child death mid-run (exit 7, no settled). */
object CrashMidRun extends Script:
  val name = "crash-mid-run"

  def play(state: RunState, id: String): Unit =
    state.event(agentStart)
    state.event(updateText("about to die"))
    state.sleep(200)
    sys.exit(7)

/** PIRPC-02: response frame with an id the adapter never issued. */
object RogueResponse extends Script:
  val name = "rogue-response"

  def play(state: RunState, id: String): Unit =
    state.event(agentStart)
    state.event(
      s"""{"id":"ghost","type":"response","command":"prompt","success":true,""" +
        s""""data":{"disposition":"started"}}"""
    )
    state.markSettled(aborted = false)

/** PIRPC-03: text delta carrying literal U+2028/U+2029 data characters. */
object UnicodeSeparators extends Script:
  val name = "unicode-separators"

  def play(state: RunState, id: String): Unit =
    state.event(agentStart)
    state.unicodeDeltaFrame()
    state.markSettled(aborted = false)

/** PIRPC-03: auto-retry round; the first agent_end has willRetry=true and is
  * NOT terminal; completion only at the final agent_settled.
  */
object RetryThenSettle extends Script:
  val name = "retry-then-settle"

  def play(state: RunState, id: String): Unit =
    state.event(agentStart)
    state.event(turnStart(0))
    state.event(updateText("first"))
    state.event(
      s"""{"type":"message_end","message":{"role":"assistant","uuid":"u-1","stopReason":"error",""" +
        s""""errorMessage":"529 overloaded","content":[{"type":"text","text":"first"}]}}"""
    )
    state.event(turnEnd(0))
    state.event(
      s"""{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"delayMs":10}"""
    )
    state.event(agentEnd(willRetry = true))
    state.event(turnStart(1))
    state.event(updateText("recovered after retry"))
    state.event(
      s"""{"type":"message_end","message":{"role":"assistant","uuid":"u-1","stopReason":"stop",""" +
        s""""content":[{"type":"text","text":"recovered after retry"}]}}"""
    )
    state.event(turnEnd(1))
    state.event(s"""{"type":"auto_retry_end","attempt":1}""")
    state.event(agentEnd(willRetry = false))
    state.markSettled(aborted = false)

/** PIRPC-03: abort acknowledged; settles with aborted=true. */
object AbortAck extends Script:
  val name = "abort-ack"

  def play(state: RunState, id: String): Unit =
    state.event(agentStart)
    state.event(updateText("working"))
    var waited = 0L
    while !state.abortRequested.get && waited < 30000L do
      state.sleep(20)
      waited += 20
    if !state.settled.get then
      state.markSettled(aborted = state.abortRequested.get)

/** PIRPC-03: abort acknowledged but never settles (uncertain outcome). */
object AbortUnsettled extends Script:
  val name = "abort-unsettled"

  override def onAbort(state: RunState, id: String): Unit =
    state.respond(id, "abort", success = true, None)

    // deliberately never emits agent_settled; the adapter deadline must
    // destroy the child and report an uncertain outcome

  def play(state: RunState, id: String): Unit =
    state.event(agentStart)
    state.event(updateText("working"))
    var waited = 0L
    while waited < 30000L do
      state.sleep(20)
      waited += 20
    state.markSettled(aborted = false)

/** PIRPC-03: ignores abort entirely; the adapter must destroy within its
  * bounded deadline.
  */
object IgnoreAbort extends Script:
  val name = "ignore-abort"
  override def onAbort(state: RunState, id: String): Unit = ()

  def play(state: RunState, id: String): Unit =
    state.event(agentStart)
    state.event(updateText("working"))
    var waited = 0L
    while waited < 30000L do
      state.sleep(20)
      waited += 20
    state.markSettled(aborted = false)

object Scripts:
  def find(name: String): Option[Script] = all.find(_.name == name)

  def all: Vector[Script] =
    Vector(
      PromptToolRun,
      SlowSettle,
      StdoutNoise,
      CorruptJson,
      CrashMidRun,
      RogueResponse,
      UnicodeSeparators,
      RetryThenSettle,
      AbortAck,
      AbortUnsettled,
      IgnoreAbort
    )

/** stdin reader loop: strict JSONL, one object per LF-terminated record. */
private object RunLoop:

  def run(script: Script, version: String): Unit =
    val state = new RunState(version)
    val in = System.in
    val buf = mutable.ArrayBuilder.make[Byte]
    var pumpStarted = false
    var promptId = ""
    var eof = false
    val bytes = new Array[Byte](1)
    while !eof do
      val n = in.read(bytes)
      if n < 0 then eof = true
      else if bytes(0) == 0x0a.toByte then
        val raw = buf.result()
        buf.clear()
        val end =
          if raw.nonEmpty && raw.last == 0x0d.toByte then raw.length - 1
          else raw.length
        val line = new String(raw, 0, end, StandardCharsets.UTF_8)
        dispatch(state, script, line) match
          case Dispatch.StartPump(id) =>
            promptId = id
            if !pumpStarted then
              pumpStarted = true
              val t = new Thread(() => script.play(state, id), "pi-stand-pump")
              t.setDaemon(true)
              t.start()
          case Dispatch.None => ()
      else buf += bytes(0)
    // stdin EOF: orderly exit — main returns normally; every helper
    // thread (pump, sleep loops) is a daemon, so the JVM exits 0 without
    // an explicit System.exit (forbidden-apis: exits belong to the
    // allowlisted entrypoints only).

  private sealed trait Dispatch

  private object Dispatch:
    case object None extends Dispatch
    case class StartPump(id: String) extends Dispatch

  private def dispatch(
      state: RunState,
      script: Script,
      line: String
  ): Dispatch =
    if line.trim.isEmpty then
      state.respondParseError()
      Dispatch.None
    else
      line.fromJson[Json] match
        case Left(_) =>
          state.respondParseError()
          Dispatch.None
        case Right(Json.Null) =>
          state.respondParseError()
          Dispatch.None
        case Right(obj @ Json.Obj(_)) =>
          obj.get("type").flatMap(_.asString) match
            case Some("prompt") =>
              val id = obj.get("id").flatMap(_.asString).getOrElse("")
              if state.settled.get then
                state.respond(id, "prompt", success = false, None)
                Dispatch.None
              else
                state.respond(id, "prompt", success = true, Some("started"))
                Dispatch.StartPump(id)
            case Some("abort") =>
              val id = obj.get("id").flatMap(_.asString).getOrElse("")
              state.abortRequested.set(true)
              script.onAbort(state, id)
              Dispatch.None
            case Some(other) =>
              val id = obj.get("id").flatMap(_.asString).getOrElse("")
              state.respondUnknown(id, other)
              Dispatch.None
            case None =>
              state.respondParseError()
              Dispatch.None
        case Right(_) =>
          state.respondParseError()
          Dispatch.None
