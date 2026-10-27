// RAI-205 cross-ecosystem consumer (LLM4S side).
//
// A NON-Raider client that integrates Raider as an execution backend through
// the accepted local stdio process adapter (ADR-037 / contracts/process-api).
// Written against the pinned third-party stack org.llm4s:llm4s-core_3:0.5.1
// (resolved from Maven Central; closure inventoried in the acceptance docs):
//   - org.llm4s.Result / org.llm4s.error.*  -> the ecosystem's Result-based
//     error idiom drives argv parsing and outcome mapping;
//   - org.llm4s.toolapi.ToolFunction/ObjectSchema -> the equivalence tool
//     ("touch_marker") is declared the LLM4S way and rendered with
//     toOpenAITool() for the tool-contract parity row;
//   - ujson/upickle (LLM4S's own JSON stack) for protocol frame I/O.
//
// Deterministic fixture: every scenario spawns a fresh `raider process-api`
// child, exchanges documented frames, records checks, and emits a manifest
// for the independent checker. Exit 0 iff every requested check passed.

package xe.llm4s

import org.llm4s.Result
import org.llm4s.error.{CancelledError, ConfigurationError, InvalidInputError, LLMError, UnknownError}
import org.llm4s.toolapi.{ObjectSchema, PropertyDefinition, SafeParameterExtractor, StringSchema, ToolFunction}
import upickle.default.*

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.util.Try

final case class Check(name: String, pass: Boolean, detail: String)

final case class ScenarioResult(
    name: String,
    ok: Boolean,
    adapterExitCode: Option[Int],
    checks: Vector[Check]
)

/** One spawned `raider process-api` child process. */
final class AdapterProc(
    adapterJar: Path,
    workspace: Path,
    outDir: Path,
    providerArgs: Vector[String],
    apiKey: Option[String],
    stdErrFile: Path
):
  private val builder = new ProcessBuilder(
    ("java" :: "-jar" :: adapterJar.toString :: "process-api" ::
      "--workspace" :: workspace.toString ::
      "--out" :: outDir.toString ::
      providerArgs.toList)*
  )
  builder.redirectError(stdErrFile.toFile)
  apiKey.foreach(k => builder.environment().put("RAIDER_API_KEY", k))
  private[llm4s] val proc: Process = builder.start()
  private[llm4s] val stdin = new BufferedWriter(
    new OutputStreamWriter(proc.getOutputStream, StandardCharsets.UTF_8)
  )
  private[llm4s] val stdout = new BufferedReader(
    new InputStreamReader(proc.getInputStream, StandardCharsets.UTF_8)
  )

  def send(line: String): Unit =
    stdin.write(line)
    stdin.write("\n")
    stdin.flush()

  /** Reads one LF frame; null at EOF. */
  def readLine(): String = stdout.readLine()

  def closeStdin(): Unit = stdin.close()

  def waitFor(): Int = proc.waitFor()

  def destroy(): Unit = proc.destroyForcibly()

end AdapterProc

object XeLlm4sConsumer:

  // ----- frame builders (exact documented key sets) ------------------------

  private def hello(requestId: String): ujson.Value = ujson.Obj(
    "protocolVersion" -> ujson.Obj("major" -> 1, "minor" -> 0),
    "type" -> "hello",
    "requestId" -> requestId,
    "supported" -> ujson.Arr(ujson.Obj("major" -> 1, "minor" -> 0)),
    "requiredCapabilities" -> ujson.Arr("observe", "cancel")
  )

  private def submit(requestId: String, task: String): ujson.Value = ujson.Obj(
    "protocolVersion" -> ujson.Obj("major" -> 1, "minor" -> 0),
    "type" -> "submit",
    "requestId" -> requestId,
    "input" -> ujson.Obj("schemaVersion" -> 1, "task" -> task)
  )

  private def observe(requestId: String, after: Long): ujson.Value = ujson.Obj(
    "protocolVersion" -> ujson.Obj("major" -> 1, "minor" -> 0),
    "type" -> "observe",
    "requestId" -> requestId,
    "afterSequence" -> after
  )

  private def cancel(requestId: String): ujson.Value = ujson.Obj(
    "protocolVersion" -> ujson.Obj("major" -> 1, "minor" -> 0),
    "type" -> "cancel",
    "requestId" -> requestId
  )

  private def frameType(v: ujson.Value): String =
    Try(v("type").str).getOrElse("<none>")

  private def errorCode(v: ujson.Value): Option[String] =
    Try(v("code").str).toOption

  private def term(t: Option[ujson.Value]): String =
    t.map(x => s"${x("executionStatus").str}/${x("taskVerdict").str}").getOrElse("none")

  private def chk(name: String, pass: Boolean, detail: String): Check =
    Check(name, pass, detail)

  // ----- equivalence tool, declared the LLM4S way ---------------------------

  /** The same filesystem-touch intent the scripted stand advertises as
    * `proc_run`; declared with the pinned LLM4S tool API and rendered to the
    * OpenAI tool JSON schema for the parity row.
    */
  private lazy val equivalenceTool: ToolFunction[String, String] =
    ToolFunction[String, String](
      "touch_marker",
      "Touch (create) a marker file at a filesystem path.",
      ObjectSchema[String](
        "Arguments for touch_marker",
        Seq(
          PropertyDefinition("path", StringSchema("target path"), required = true)
        ),
        additionalProperties = false
      ),
      (ex: SafeParameterExtractor) => ex.getString("path").map(p => s"touched:$p")
    )

  private def toolParityCheck(): Check =
    Result.safely {
      val rendered = equivalenceTool.toOpenAITool()
      val name = rendered("function")("name").str
      val hasPath = rendered("function")("parameters")("properties").obj.contains("path")
      chk(
        "tool-contract-parity",
        name == "touch_marker" && hasPath,
        s"rendered OpenAI tool function.name=$name properties.path=$hasPath; the stand " +
          "advertises proc_run (argv touch) covering the same filesystem-touch intent"
      )
    }.left.map(e => chk("tool-contract-parity", false, s"render failed: $e")).merge

  // ----- configuration --------------------------------------------------------

  private final case class Cfg(
      adapterJar: Path,
      wsRoot: Path,
      outRoot: Path,
      manifest: Path,
      scenarios: Vector[String],
      standUrl: Option[String],
      apiKey: Option[String]
  )

  private val validScenarios: Vector[String] = Vector(
    "handshake-terminal",
    "refusals",
    "observe-dedup",
    "tool-task",
    "extraction-task",
    "cancel-mid-run",
    "eof-uncertain"
  )

  private def parseArgs(argv: List[String]): Either[LLMError, Cfg] =
    val m = argv.grouped(2).collect { case List(k, v) if k.startsWith("--") => k -> v }.toMap
    for
      jar <- m.get("--adapter-jar").toRight(ConfigurationError("missing --adapter-jar"): LLMError)
      wsRoot <- m.get("--ws-root").toRight(ConfigurationError("missing --ws-root"): LLMError)
      outRoot <- m.get("--out-root").toRight(ConfigurationError("missing --out-root"): LLMError)
      manifest <- m.get("--manifest").toRight(ConfigurationError("missing --manifest"): LLMError)
      scen = m.get("--scenario").fold(Vector("all"))(_.split(',').toVector.map(_.trim))
      _ <- Result.fromBoolean(
        scen.forall(s => validScenarios.contains(s) || s == "all"),
        InvalidInputError("scenario", scen.mkString(","), "unknown scenario name")
      )
    yield Cfg(
      adapterJar = Paths.get(jar),
      wsRoot = Paths.get(wsRoot),
      outRoot = Paths.get(outRoot),
      manifest = Paths.get(manifest),
      scenarios = if scen.contains("all") then validScenarios else scen,
      standUrl = m.get("--stand-url"),
      apiKey = m.get("--key")
    )

  private def isStandScenario(s: String): Boolean =
    Vector("tool-task", "extraction-task", "cancel-mid-run", "eof-uncertain").contains(s)

  private def providerArgs(cfg: Cfg, scenario: String, agent: String): Vector[String] =
    if !isStandScenario(scenario) then Vector("--provider", "mock")
    else
      val url = cfg.standUrl.getOrElse(
        throw new IllegalStateException("stand scenarios need --stand-url")
      )
      Vector(
        "--provider", "openai",
        "--model", "xe-stand-model",
        "--base-url", url,
        "--agent", agent
      )

  /** Runs `body` against a fresh adapter child with a 120 s watchdog; the
    * body returns checks, the adapter exit code is captured by this wrapper.
    * Lifecycle: the adapter settles (terminal + artifacts) but stays alive
    * while stdin is open; the client closes stdin to release the idle
    * process before awaiting the exit code.
    */
  private def withAdapter(cfg: Cfg, scenario: String, agent: String)(
      body: AdapterProc => Vector[Check]
  ): ScenarioResult =
    val ws = cfg.wsRoot.resolve(scenario)
    Files.createDirectories(ws)
    val out = cfg.outRoot.resolve(s"$scenario-llm4s")
    Files.createDirectories(out)
    val proc = new AdapterProc(
      cfg.adapterJar, ws, out,
      providerArgs(cfg, scenario, agent),
      if isStandScenario(scenario) then cfg.apiKey else None,
      out.resolve("adapter-stderr.log")
    )
    val watcher = new Thread(() =>
      try
        Thread.sleep(120000L)
        proc.destroy()
      catch case _: InterruptedException => ()
    )
    watcher.setDaemon(true)
    watcher.start()
    val checks =
      try body(proc)
      finally watcher.interrupt()
    if proc.proc.isAlive then proc.closeStdin()
    val code = awaitExit(proc, 60)
    ScenarioResult(scenario, checks.forall(_.pass), Some(code), checks)

  /** Bounded wait for adapter exit; force-kills and reports -999 on timeout
    * (the corresponding exit-code check then fails instead of hanging). */
  private def awaitExit(proc: AdapterProc, seconds: Int): Int =
    var waited = 0
    while proc.proc.isAlive && waited < seconds * 2 do
      Thread.sleep(500L)
      waited += 1
    if proc.proc.isAlive then
      proc.destroy()
      -999
    else proc.proc.exitValue()

  /** Appends an adapter exit-code expectation to a scenario result. */
  private def withExit(res: ScenarioResult, expected: Int): ScenarioResult =
    val c = chk(s"adapter-exit-$expected",
      res.adapterExitCode.contains(expected),
      s"adapter exit=${res.adapterExitCode.getOrElse("none")}")
    res.copy(checks = res.checks :+ c, ok = (res.checks :+ c).forall(_.pass))

  /** Reads frames until the first `terminal` frame (or EOF), returning all. */
  private def drainToTerminal(proc: AdapterProc): Vector[ujson.Value] =
    val seen = mutable.ArrayBuffer.empty[ujson.Value]
    var done = false
    while !done do
      val line = proc.readLine()
      if line == null then done = true
      else
        val v = ujson.read(line)
        seen += v
        if frameType(v) == "terminal" then done = true
    seen.toVector

  /** Reads frames until `pred` matches (inclusive) or terminal/EOF; returns
    * all frames read. Run frames (observations, accepted) and error frames
    * share one output stream, so a specific response must be scanned for. */
  private def readUntil(proc: AdapterProc)(pred: ujson.Value => Boolean): Vector[ujson.Value] =
    val seen = mutable.ArrayBuffer.empty[ujson.Value]
    var done = false
    while !done do
      val line = proc.readLine()
      if line == null then done = true
      else
        val v = ujson.read(line)
        seen += v
        if pred(v) || frameType(v) == "terminal" then done = true
    seen.toVector

  // ----- scenarios -------------------------------------------------------------

  private def handshakeTerminal(cfg: Cfg): ScenarioResult =
    val res = withAdapter(cfg, "handshake-terminal", "plain") { proc =>
      val checks = mutable.ArrayBuffer.empty[Check]
      proc.send(ujson.write(hello("ht-hello")))
      val ack = ujson.read(proc.readLine())
      checks += chk("hello-ack-negotiated-1-0",
        Try(ack("negotiated")("major").num == 1 && ack("negotiated")("minor").num == 0).getOrElse(false),
        s"negotiated=${Try(ack("negotiated").str).getOrElse("?")}")
      val caps = Try(ack("capabilities").arr.map(_.str).toSet).getOrElse(Set.empty)
      checks += chk("hello-ack-capabilities-observe-cancel", caps("observe") && caps("cancel"), s"capabilities=$caps")
      checks += chk("adapter-version-raider-process-api-1",
        Try(ack("adapterVersion").str == "raider-process-api-1").getOrElse(false),
        s"adapterVersion=${Try(ack("adapterVersion").str).getOrElse("?")}")
      proc.send(ujson.write(submit("ht-run", "Say hi")))
      val frames = drainToTerminal(proc)
      val accepted = frames.find(frameType(_) == "accepted")
      checks += chk("accepted-rootid-proc-prefix",
        accepted.exists(f => Try(f("rootId").str.startsWith("proc_")).getOrElse(false)),
        s"rootId=${accepted.map(f => Try(f("rootId").str).getOrElse("?")).getOrElse("none")}")
      val seqs = frames.filter(frameType(_) == "observation")
        .map(f => Try(f("sequence").num.toLong).getOrElse(-1L))
      checks += chk("observations-monotone-sequence",
        seqs.isEmpty || seqs == seqs.sorted.distinct, s"sequences=$seqs")
      val terminals = frames.filter(frameType(_) == "terminal")
      checks += chk("settle-once-single-terminal", terminals.size == 1, s"terminal frames=${terminals.size}")
      val t = terminals.headOption
      checks += chk("terminal-completed-success",
        t.exists(x => Try(x("executionStatus").str == "completed" && x("taskVerdict").str == "success").getOrElse(false)),
        s"terminal=${term(t)}")
      checks.toVector
    }
    withExit(res, 0)

  private def refusalsNonFatal(cfg: Cfg): ScenarioResult =
    val res = withAdapter(cfg, "refusals", "plain") { proc =>
      val checks = mutable.ArrayBuffer.empty[Check]
      proc.send(ujson.write(hello("rf-hello")))
      ujson.read(proc.readLine()) // helloAck
      // (a) second hello before any dispatch -> non-fatal hello_already_negotiated
      proc.send(ujson.write(hello("rf-hello2")))
      val e2 = ujson.read(proc.readLine())
      checks += chk("hello-already-negotiated-nonfatal",
        frameType(e2) == "error" && errorCode(e2).contains("hello_already_negotiated"),
        s"frame=${frameType(e2)} code=${errorCode(e2).getOrElse("-")}")
      // (b) unknown field on hello -> unknown_field naming the path hello.bogus
      val bogus = hello("rf-obs")
      bogus.obj.update("bogus", ujson.Num(1))
      proc.send(ujson.write(bogus))
      val e3 = ujson.read(proc.readLine())
      checks += chk("unknown-field-path-reported",
        frameType(e3) == "error" && errorCode(e3).contains("unknown_field") &&
          Try(e3("message").str.contains("hello.bogus")).getOrElse(false),
        s"code=${errorCode(e3).getOrElse("-")} message=${Try(e3("message").str).getOrElse("?")}")
      // (c) unsupported input schemaVersion -> input_rejected, never dispatched
      proc.send(ujson.write(ujson.Obj(
        "protocolVersion" -> ujson.Obj("major" -> 1, "minor" -> 0),
        "type" -> "submit", "requestId" -> "rf-bad-schema",
        "input" -> ujson.Obj("schemaVersion" -> 2, "task" -> "x"))))
      val e1 = ujson.read(proc.readLine())
      checks += chk("input-rejected-schema-2",
        frameType(e1) == "error" && errorCode(e1).contains("input_rejected"),
        s"frame=${frameType(e1)} code=${errorCode(e1).getOrElse("-")}")
      // (d) the session still dispatches after all three non-fatal refusals;
      // post-terminal frames are NOT answered (terminal closes ordinary input,
      // Outbound.scala), so every session-level probe must precede settlement.
      // Run frames and error frames share one stream and accepted may follow
      // leading observations, so scan for the accepted frame.
      proc.send(ujson.write(submit("rf-good", "Say hi")))
      val scan = readUntil(proc)(f => frameType(f) == "accepted")
      checks += chk("session-continues-after-refusal",
        scan.exists(frameType(_) == "accepted"), s"frames=${scan.map(frameType(_)).mkString(",")}")
      drainToTerminal(proc)
      checks.toVector
    }
    withExit(res, 0)

  private def refusalsMalformed(cfg: Cfg): ScenarioResult =
    val res = withAdapter(cfg, "refusals", "plain") { proc =>
      proc.send("{not json")
      val e = ujson.read(proc.readLine())
      Vector(chk("malformed-frame-fatal-error-frame",
        frameType(e) == "error" && errorCode(e).contains("malformed_frame"),
        s"code=${errorCode(e).getOrElse("-")}"))
    }
    withExit(res, 20)

  private def refusalsOversize(cfg: Cfg): ScenarioResult =
    val res = withAdapter(cfg, "refusals", "plain") { proc =>
      val checks = mutable.ArrayBuffer.empty[Check]
      proc.send(ujson.write(hello("of-hello")))
      ujson.read(proc.readLine())
      proc.send(ujson.write(ujson.Obj(
        "protocolVersion" -> ujson.Obj("major" -> 1, "minor" -> 0),
        "type" -> "observe", "requestId" -> "of-big", "afterSequence" -> 0,
        // XE-WIRE-02-oversize: the limit is 1048576 payload bytes checked
        // before JSON decoding; stay well above it
        "padding" -> "x".repeat(1048600))))
      val e = ujson.read(proc.readLine())
      checks += chk("oversize-frame-fatal-error-frame",
        frameType(e) == "error" && errorCode(e).contains("oversize_frame"),
        s"code=${errorCode(e).getOrElse("-")}")
      checks.toVector
    }
    withExit(res, 20)

  private def refusalsDuplicateSubmit(cfg: Cfg): ScenarioResult =
    val res = withAdapter(cfg, "refusals", "plain") { proc =>
      val checks = mutable.ArrayBuffer.empty[Check]
      proc.send(ujson.write(hello("ds-hello")))
      ujson.read(proc.readLine())
      proc.send(ujson.write(submit("ds-1", "Say hi")))
      proc.send(ujson.write(submit("ds-2", "Say hi again")))
      // the duplicate_submit error races run 1's stream frames; scan until it
      // has been seen AND the owned root has settled
      val seen = mutable.ArrayBuffer.empty[ujson.Value]
      var dupErr = false
      var done = false
      while !done do
        val line = proc.readLine()
        if line == null then done = true
        else
          val v = ujson.read(line)
          seen += v
          if frameType(v) == "error" && errorCode(v).contains("duplicate_submit") then dupErr = true
          if dupErr && frameType(v) == "terminal" then done = true
      val dupFrame = seen.find(f => frameType(f) == "error" && errorCode(f).contains("duplicate_submit"))
      checks += chk("duplicate-submit-refused",
        dupFrame.exists(f => Try(f("requestId").str == "ds-2").getOrElse(false)),
        s"error=${dupFrame.map(f => s"req=${Try(f("requestId").str).getOrElse("?")}").getOrElse("none")}")
      checks += chk("duplicate-submit-root-still-settles",
        seen.count(frameType(_) == "terminal") == 1,
        s"terminals=${seen.count(frameType(_) == "terminal")}")
      checks.toVector
    }
    withExit(res, 0)

  private def observeDedup(cfg: Cfg): ScenarioResult =
    val res = withAdapter(cfg, "observe-dedup", "plain") { proc =>
      val checks = mutable.ArrayBuffer.empty[Check]
      proc.send(ujson.write(hello("od-hello")))
      ujson.read(proc.readLine())
      proc.send(ujson.write(submit("od-run", "Say hi")))
      // mirror XE-WIRE-01: observe(afterSequence=0) goes out right after
      // accepted, before consuming the live stream; observe is ordinary input
      // and is not answered post-terminal
      val scan = readUntil(proc)(f => frameType(f) == "accepted")
      checks += chk("accepted-before-observe",
        scan.exists(frameType(_) == "accepted"), s"frames=${scan.map(frameType(_)).mkString(",")}")
      proc.send(ujson.write(observe("od-replay", 0)))
      val frames = drainToTerminal(proc)
      val seqs = frames.filter(frameType(_) == "observation")
        .map(f => Try(f("sequence").num.toLong).getOrElse(-1L))
      // dedup semantics: after observe(afterSequence=0) the stream continues
      // from the client's watermark; sequences already delivered live are NOT
      // re-delivered, so the whole session stream is strictly increasing with
      // no repeated sequence numbers
      checks += chk("observations-nonempty", seqs.nonEmpty, s"sequences=$seqs")
      checks += chk("sequences-strictly-increasing",
        seqs == seqs.sorted.distinct, s"sequences=$seqs")
      checks += chk("no-duplicate-frames-after-dedup",
        seqs.distinct.size == seqs.size,
        s"unique=${seqs.distinct.size}/${seqs.size}")
      checks += chk("terminal-settles-once",
        frames.count(frameType(_) == "terminal") == 1,
        s"terminals=${frames.count(frameType(_) == "terminal")}")
      checks.toVector
    }
    withExit(res, 0)

  private def toolTask(cfg: Cfg): ScenarioResult =
    val res = withAdapter(cfg, "tool-task", "coder") { proc =>
      val checks = mutable.ArrayBuffer.empty[Check]
      proc.send(ujson.write(hello("tt-hello")))
      ujson.read(proc.readLine())
      proc.send(ujson.write(submit("tt-run",
        "Create the marker file marker.txt in the workspace root using the proc_run tool.")))
      val frames = drainToTerminal(proc)
      val t = frames.find(frameType(_) == "terminal")
      checks += chk("terminal-completed-success",
        t.exists(x => Try(x("executionStatus").str == "completed" && x("taskVerdict").str == "success").getOrElse(false)),
        s"terminal=${term(t)}")
      val arts = t.map(x => Try(x("artifacts").arr.map(a => a("path").str).toSet).getOrElse(Set.empty))
        .getOrElse(Set.empty)
      checks += chk("artifacts-manifest-nonempty", arts("run.json") && arts("result.json"), s"artifacts=$arts")
      val hashesOk = t.map(x => Try(x("artifacts").arr.forall(a => a("sha256").str.length == 64)).getOrElse(false))
        .getOrElse(false)
      checks += chk("artifact-sha256-64hex", hashesOk, "terminal artifact digests are 64-hex sha256")
      checks += toolParityCheck()
      checks.toVector
    }
    withExit(res, 0)

  private def extractionTask(cfg: Cfg): ScenarioResult =
    val res = withAdapter(cfg, "extraction-task", "plain") { proc =>
      val checks = mutable.ArrayBuffer.empty[Check]
      proc.send(ujson.write(hello("ex-hello")))
      ujson.read(proc.readLine())
      proc.send(ujson.write(submit("ex-run",
        "Extract the total amount from the invoice text as a JSON object with a numeric key total.")))
      val frames = drainToTerminal(proc)
      val t = frames.find(frameType(_) == "terminal")
      checks += chk("terminal-completed-success",
        t.exists(x => Try(x("executionStatus").str == "completed" && x("taskVerdict").str == "success").getOrElse(false)),
        s"terminal=${term(t)}")
      checks += chk("result-artifact-present",
        t.map(x => Try(x("artifacts").arr.exists(a => a("path").str == "result.json")).getOrElse(false))
          .getOrElse(false),
        "terminal lists result.json")
      checks += Check(
        "constrained-output-fact-registered",
        pass = true,
        detail = "RAI-202 fact: the adapter exposes no native constrained/JSON output mode " +
          "(RAIDER_OPENAI_EXTRA_BODY is an untyped escape hatch without guarantees); schema " +
          "adherence here is prompt-only discipline against a scripted stand and is validated " +
          "by the independent checker, not by Raider verdict machinery"
      )
      checks.toVector
    }
    withExit(res, 0)

  private def cancelMidRun(cfg: Cfg): ScenarioResult =
    val res = withAdapter(cfg, "cancel-mid-run", "coder") { proc =>
      val checks = mutable.ArrayBuffer.empty[Check]
      proc.send(ujson.write(hello("cm-hello")))
      ujson.read(proc.readLine())
      proc.send(ujson.write(submit("cm-run",
        "Create the marker file marker.txt in the workspace root using the proc_run tool.")))
      // run frames and error frames share one stream and observations may
      // precede accepted (journal events queue ahead of the receipt), so scan
      // until BOTH accepted and a first observation have been seen
      val pre = mutable.ArrayBuffer.empty[ujson.Value]
      var acceptedOk = false
      var sawFirstObs = false
      var scanDone = false
      while !scanDone do
        val line = proc.readLine()
        if line == null then scanDone = true
        else
          val v = ujson.read(line)
          pre += v
          if frameType(v) == "accepted" then acceptedOk = true
          if frameType(v) == "observation" then sawFirstObs = true
          if acceptedOk && sawFirstObs then scanDone = true
      if acceptedOk && sawFirstObs then proc.send(ujson.write(cancel("cm-run")))
      // collect everything until the cancelAck has been seen AND the terminal;
      // stop at the terminal in any case so a missing ack cannot hang the reader
      val rest = mutable.ArrayBuffer.empty[ujson.Value]
      var ackSeen = false
      var done = false
      while !done do
        val line = proc.readLine()
        if line == null then done = true
        else
          val v = ujson.read(line)
          rest += v
          if frameType(v) == "cancelAck" && Try(v("status").str == "accepted").getOrElse(false) then ackSeen = true
          if frameType(v) == "terminal" then done = true
      checks += chk("cancel-accepted-before-terminal", acceptedOk && sawFirstObs,
        s"accepted=$acceptedOk firstObs=$sawFirstObs pre=${pre.map(frameType(_)).mkString(",")}")
      checks += chk("cancel-ack-accepted",
        rest.exists(f => frameType(f) == "cancelAck" && Try(f("status").str == "accepted").getOrElse(false)),
        s"cancelAck in=${rest.filter(frameType(_) == "cancelAck").map(f => Try(f("status").str).getOrElse("?"))}")
      val t = rest.find(frameType(_) == "terminal")
      checks += chk("terminal-cancelled-incomplete",
        t.exists(x => Try(x("executionStatus").str == "cancelled" && x("taskVerdict").str == "incomplete").getOrElse(false)),
        s"terminal=${term(t)}")
      checks += chk("cleanup-complete",
        t.exists(x => Try(x("cleanupStatus").str == "complete").getOrElse(false)),
        s"cleanupStatus=${t.map(x => Try(x("cleanupStatus").str).getOrElse("?")).getOrElse("?")}")
      checks.toVector
    }
    withExit(res, 26)

  private def eofUncertain(cfg: Cfg): ScenarioResult =
    val res = withAdapter(cfg, "eof-uncertain", "coder") { proc =>
      val checks = mutable.ArrayBuffer.empty[Check]
      proc.send(ujson.write(hello("eo-hello")))
      ujson.read(proc.readLine())
      proc.send(ujson.write(submit("eo-run",
        "Create the marker file marker.txt in the workspace root using the proc_run tool.")))
      // scan until accepted (observations may precede it on the stream)
      val pre = readUntil(proc)(f => frameType(f) == "accepted")
      checks += chk("dispatch-accepted-before-eof",
        pre.exists(frameType(_) == "accepted"), s"frames=${pre.map(frameType(_)).mkString(",")}")
      proc.closeStdin() // uncertain mutation: the client disappears mid-run
      val frames = drainToTerminal(proc)
      val t = frames.find(frameType(_) == "terminal")
      checks += chk("eof-after-dispatch-settles", t.isDefined,
        s"terminal=${t.map(x => x("executionStatus").str).getOrElse("none")}")
      checks += chk("terminal-cancelled-uncertain",
        t.exists(x => Try(x("executionStatus").str == "cancelled" && x("accountingStatus").str == "unavailable").getOrElse(false)),
        s"terminal=${t.map(x => s"${x("executionStatus").str}/${x("accountingStatus").str}").getOrElse("none")}")
      checks.toVector
    }
    withExit(res, 26)

  // ----- driver -----------------------------------------------------------------

  private def runScenarios(cfg: Cfg): Vector[ScenarioResult] =
    val out = mutable.ArrayBuffer.empty[ScenarioResult]
    if cfg.scenarios.contains("handshake-terminal") then out += handshakeTerminal(cfg)
    if cfg.scenarios.contains("refusals") then
      out += refusalsNonFatal(cfg)
      out += refusalsMalformed(cfg)
      out += refusalsOversize(cfg)
      out += refusalsDuplicateSubmit(cfg)
    if cfg.scenarios.contains("observe-dedup") then out += observeDedup(cfg)
    if cfg.scenarios.contains("tool-task") then out += toolTask(cfg)
    if cfg.scenarios.contains("extraction-task") then out += extractionTask(cfg)
    if cfg.scenarios.contains("cancel-mid-run") then out += cancelMidRun(cfg)
    if cfg.scenarios.contains("eof-uncertain") then out += eofUncertain(cfg)
    out.toVector

  private def sha256(path: Path): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.digest(Files.readAllBytes(path)).map("%02x" format _).mkString

  private def writeManifest(cfg: Cfg, results: Vector[ScenarioResult]): Unit =
    val manifest = ujson.Obj(
      "ecosystem" -> "llm4s",
      "pinned" -> ujson.Obj(
        "framework" -> "org.llm4s:llm4s-core_3:0.5.1",
        "adapterJar" -> cfg.adapterJar.toString,
        "adapterJarSha256" -> sha256(cfg.adapterJar)
      ),
      "scenarios" -> results.map { r =>
        ujson.Obj(
          "name" -> r.name,
          "ok" -> r.ok,
          "adapterExitCode" -> r.adapterExitCode.map(ujson.Num(_)).getOrElse(ujson.Null),
          "checks" -> r.checks.map(c => ujson.Obj(
            "name" -> c.name, "pass" -> c.pass, "detail" -> c.detail))
        )
      }
    )
    Files.createDirectories(cfg.manifest.getParent)
    Files.writeString(cfg.manifest, ujson.write(manifest), StandardCharsets.UTF_8)

  def main(argv: Array[String]): Unit =
    parseArgs(argv.toList) match
      case Left(err) =>
        System.err.println(s"xe-llm4s-consumer: invalid argv: ${err.toString}")
        sys.exit(2)
      case Right(cfg) =>
        val results = runScenarios(cfg)
        writeManifest(cfg, results)
        val failed = results.filterNot(_.ok)
        results.foreach { r =>
          println(s"[xe-llm4s] ${if r.ok then "PASS" else "FAIL"} ${r.name} exit=${r.adapterExitCode.getOrElse("-")}")
          r.checks.filterNot(_.pass).foreach(c =>
            println(s"[xe-llm4s]   failed check: ${c.name}: ${c.detail}"))
        }
        println(s"[xe-llm4s] manifest=${cfg.manifest} scenarios=${results.size} failed=${failed.size}")
        sys.exit(if failed.isEmpty then 0 else 1)

end XeLlm4sConsumer
