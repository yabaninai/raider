package raider.backend.pi

import raider.core.AttemptId
import raider.core.ModelEvent
import raider.core.RaiderError
import raider.core.ToolCallId
import raider.core.Usage

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object PiRpcBackend:

  /** Builds a backend for the validated config. Construction is total; every
    * fallible operation (version gate, process spawn) happens inside `run`.
    */
  def make(cfg: PiRpcConfig): Task[PiRpcBackend] =
    ZIO.succeed(new PiRpcBackend(cfg))

/** Pi RPC external backend (task card RAI-034). Exactly ONE inference attempt
  * per `run` over the pi process JSONL protocol; the native agent loop is not
  * part of this card. Strict framing discipline per upstream rpc contract:
  * LF-terminated single-line JSON frames, CR stripped, U+2028/U+2029 are data
  * (byte-level split), blank lines and unparseable frames are RA-STREAM
  * violations, every `response` id must have been issued by Raider.
  */
final class PiRpcBackend private[pi] (cfg: PiRpcConfig):

  private val nextId = new AtomicLong(0)

  /** Runs `versionCommand`, takes the first stdout line (trimmed) and reports
    * whether it satisfies the configured expectation. No expectation means
    * `matchesExpectation = true`.
    */
  def probeVersion: Task[PiVersionInfo] =
    ZIO.attemptBlocking {
      val pb = new java.lang.ProcessBuilder(cfg.versionCommand.toList*)
      pb.redirectErrorStream(false)
      cfg.envExtra.foreach { case (k, v) => pb.environment().put(k, v) }
      val process = pb.start()
      try
        val reader = new java.io.BufferedReader(
          new java.io.InputStreamReader(process.getInputStream, UTF_8)
        )
        val line = Option(reader.readLine()).map(_.trim).getOrElse("")
        if line.isEmpty then
          throw new IOException("pi version probe produced no output on stdout")
        val exited = process.waitFor(30L, TimeUnit.SECONDS)
        if !exited then
          throw new IOException("pi version probe did not terminate within 30s")
        PiVersionInfo(
          line,
          matchesExpectation = cfg.expectedVersion.forall(_ == line)
        )
      finally process.destroyForcibly() // no-op once the process has exited
    }

  /** Starts exactly one pi child and writes the prompt as the first wire frame.
    * A configured expected version is checked first (RA-CFG before any child is
    * spawned). The scope finalizer performs an orderly stop: close stdin,
    * graceful destroy windows, force as last resort.
    */
  def run(prompt: String): ZIO[Scope, RaiderError, PiRpcRun] =
    val gate: ZIO[Any, RaiderError, Unit] = cfg.expectedVersion match
      case None => ZIO.unit
      case Some(expected) =>
        probeVersion.foldZIO(
          err =>
            ZIO.fail(
              RaiderError.ProviderUnavailable(
                s"pi version probe failed: ${err.getMessage}"
              )
            ),
          vi =>
            if vi.matchesExpectation then ZIO.unit
            else
              ZIO.fail(
                RaiderError.Configuration(
                  s"pi version mismatch: expected '$expected' but child reported '${vi.line}'"
                )
              )
        )
    for
      _ <- gate
      queue <- Queue.bounded[PiQueueItem](256)
      run <- ZIO.fromEither(spawn(prompt, queue))
      _ <- ZIO.addFinalizer(ZIO.attemptBlocking(run.release()).ignore)
    yield run

  private def spawn(
      prompt: String,
      queue: Queue[PiQueueItem]
  ): Either[RaiderError, PiRpcRun] =
    try
      val pb = new java.lang.ProcessBuilder(cfg.command.toList*)
      pb.redirectErrorStream(false)
      pb.redirectError(java.lang.ProcessBuilder.Redirect.INHERIT)
      cfg.envExtra.foreach { case (k, v) => pb.environment().put(k, v) }
      val process = pb.start()
      val stdin = process.getOutputStream
      val attemptId = AttemptId(s"r-${nextId.incrementAndGet()}")
      val run = new PiRpcRun(cfg, process, stdin, attemptId, queue, prompt)
      run.start()
      Right(run)
    catch
      case e: IOException =>
        Left(
          RaiderError.ProviderUnavailable(
            s"failed to start pi process: ${e.getMessage}"
          )
        )
      case e: SecurityException =>
        Left(
          RaiderError.ProviderUnavailable(
            s"failed to start pi process: ${e.getMessage}"
          )
        )

private[pi] type PiQueueItem = Either[RaiderError, Option[ModelEvent]]

private[pi] final class PiRpcRun(
    cfg: PiRpcConfig,
    process: java.lang.Process,
    promptStdin: java.io.OutputStream,
    val attemptId: AttemptId,
    queue: Queue[PiQueueItem],
    prompt: String
):
  private val nextCommandId = new AtomicLong(0)
  private val promptId = s"c-${nextCommandId.incrementAndGet()}"
  private val outstanding = new ConcurrentHashMap[String, java.lang.Boolean]
  private val done = new AtomicBoolean(false)
  private val settledAborted = new AtomicBoolean(false)
  private val responseSeen = new AtomicBoolean(false)
  private val cancelDestroyed = new AtomicBoolean(false)
  private val stdinClosed = new AtomicBoolean(false)
  private val released = new AtomicBoolean(false)
  private val pendingCalls = new ConcurrentHashMap[Int, String]
  @volatile private var readerThread: Thread = null
  @volatile private var watchdogThread: Thread = null

  /** Re-playable events stream for this single run: normalized ModelEvents
    * until exactly one terminal event, then end (or a typed failure instead of
    * a terminal event). take(2) + a later full collect is a supported
    * consumption pattern; the stream is never shut down, so repeated pulls see
    * the same queue backlog.
    */
  def events: ZStream[Any, RaiderError, ModelEvent] =
    val keepUntilTerminal: PiQueueItem => Boolean =
      case Right(Some(_)) => true
      case _              => false
    // takeUntil is inclusive: it emits the first element that satisfies the
    // predicate (the terminal item: Left(err) or Right(None)) and then stops,
    // which is exactly the framing the queue sentinel protocol needs.
    ZStream
      .fromQueue(queue)
      .takeUntil(item => !keepUntilTerminal(item))
      .flatMap {
        case Right(Some(event)) => ZStream(event)
        case Right(None)        => ZStream.empty
        case Left(err)          => ZStream.fail(err)
      }

  /** Requests cancellation: sends an abort frame, waits up to settleWaitMs for
    * `agent_settled(aborted=true)`. If pi settles aborted, the outcome is
    * Acknowledged and the run stream ends with Failed(RA-CANCEL). Otherwise the
    * child is destroyed/stopped and the outcome is Uncertain — the run stream
    * ends with Failed(RA-CANCEL) stating that the result is not observable;
    * Raider never claims the cancel happened.
    */
  def cancel: ZIO[Any, Nothing, PiCancelOutcome] =
    ZIO.succeed {
      if done.get() then
        PiCancelOutcome.Acknowledged(
          settledAborted = settledAborted.get(),
          detail =
            s"cancel acknowledged after terminal state: aborted=${settledAborted.get()}"
        )
      else
        sendAbort()
        val deadlineNanos =
          java.lang.System.nanoTime() + cfg.settleWaitMs * 1000000L
        while !settledAborted.get() && !done.get() && java.lang.System
            .nanoTime() < deadlineNanos
        do Thread.sleep(20L)
        if settledAborted.get() then
          PiCancelOutcome.Acknowledged(
            settledAborted = true,
            detail =
              "cancel acknowledged: pi reported agent_settled(aborted=true)"
          )
        else
          cancelDestroyed.set(true)
          stopChild()
          PiCancelOutcome.Uncertain(
            detail =
              "cancel uncertain: pi did not settle aborted within the deadline; child destroyed/stopped; result not observable",
            childDestroyed = true
          )
    }

  def childAlive: ZIO[Any, Nothing, Boolean] = ZIO.succeed(process.isAlive)

  def childExitCode: ZIO[Any, Nothing, Option[Int]] =
    ZIO.succeed(if process.isAlive then None else Some(process.exitValue()))

  /** Orderly stop used by the scope finalizer (and idempotent anywhere else):
    * close stdin (orderly shutdown signal), wait gracefulStopMs, then destroy,
    * then destroyForcibly.
    */
  def release(): Unit =
    if released.compareAndSet(false, true) then
      closeStdinQuietly()
      if process.isAlive then
        if !process.waitFor(cfg.gracefulStopMs, TimeUnit.MILLISECONDS) then
          process.destroy()
          if !process.waitFor(cfg.forceStopMs, TimeUnit.MILLISECONDS) then
            process.destroyForcibly()
            process.waitFor(cfg.forceStopMs, TimeUnit.MILLISECONDS)
          ()

  private[pi] def start(): Unit =
    outstanding.put(promptId, java.lang.Boolean.TRUE)
    writeFrame(
      Json.Obj(
        zio.Chunk(
          "id" -> Json.Str(promptId),
          "type" -> Json.Str("prompt"),
          "prompt" -> Json.Str(prompt)
        )
      )
    )
    readerThread =
      new Thread(() => readerLoop(), s"pi-rpc-reader-${attemptId.value}")
    readerThread.setDaemon(true)
    readerThread.start()
    watchdogThread = new Thread(
      () => dispatchWatchdog(),
      s"pi-rpc-watchdog-${attemptId.value}"
    )
    watchdogThread.setDaemon(true)
    watchdogThread.start()

  // ---- wire writing ----

  private def writeFrame(obj: Json.Obj): Unit =
    synchronized {
      val bytes = (obj.toJson + "\n").getBytes(UTF_8)
      promptStdin.write(bytes)
      promptStdin.flush()
    }

  private def sendAbort(): Unit =
    val id = s"a-${nextCommandId.incrementAndGet()}"
    outstanding.put(id, java.lang.Boolean.TRUE)
    writeFrame(
      Json.Obj(
        zio.Chunk("id" -> Json.Str(id), "type" -> Json.Str("abort"))
      )
    )

  private def closeStdinQuietly(): Unit =
    if stdinClosed.compareAndSet(false, true) then
      try promptStdin.close()
      catch case _: IOException => ()

  private def stopChild(): Unit =
    closeStdinQuietly()
    process.destroy()
    if !process.waitFor(cfg.gracefulStopMs, TimeUnit.MILLISECONDS) then
      process.destroyForcibly()
      process.waitFor(cfg.forceStopMs, TimeUnit.MILLISECONDS)
      ()

  // ---- reader thread ----

  private def readerLoop(): Unit =
    val in = process.getInputStream
    val frame = new ByteArrayOutputStream()
    val buf = new Array[Byte](8192)
    var eof = false
    try
      while !eof && !done.get() do
        val n = in.read(buf)
        if n < 0 then eof = true
        else
          var i = 0
          while i < n do
            val b = buf(i)
            i += 1
            if b == '\n' then
              handleFrame(new String(frame.toByteArray, UTF_8))
              frame.reset()
              if done.get() then eof = true
            else
              frame.write(b)
              if frame.size() > cfg.maxFrameBytes then
                failStream(
                  RaiderError.StreamProtocol(
                    s"frame exceeds maxFrameBytes=${cfg.maxFrameBytes}"
                  )
                )
                eof = true
    catch
      case _: IOException =>
        () // classified below through the done flag
    finally
      if !done.get() then
        if frame.size() > 0 then
          failStream(
            RaiderError.StreamProtocol(
              "truncated final frame (no LF terminator)"
            )
          )
        else if cancelDestroyed.get() then
          failWithTerminalEvent(
            RaiderError.Cancelled(
              "pi child destroyed by cancel before agent_settled; outcome uncertain"
            )
          )
        else
          failStream(
            RaiderError.ProviderUnavailable(
              "pi stream ended before agent_settled; outcome uncertain"
            )
          )

  private def handleFrame(line: String): Unit =
    if line.trim.isEmpty then
      failStream(
        RaiderError.StreamProtocol(
          "blank line on stdout is a protocol violation"
        )
      )
    else
      line.fromJson[Json] match
        case Left(err) =>
          failStream(RaiderError.StreamProtocol(s"invalid json frame: $err"))
        case Right(obj: Json.Obj) =>
          obj.get("type").flatMap(_.asString) match
            case Some("response") => handleResponse(obj)
            case Some(kind)       => handleSessionEvent(kind, obj)
            case None =>
              failStream(RaiderError.StreamProtocol("frame has no type"))
        case Right(_) =>
          failStream(RaiderError.StreamProtocol("frame is not a JSON object"))

  private def handleResponse(obj: Json.Obj): Unit =
    val id = obj.get("id").flatMap(_.asString).getOrElse("")
    if !outstanding.remove(id) then
      failStream(
        RaiderError.StreamProtocol(s"response id not issued by Raider: $id")
      )
    else
      responseSeen.set(true)
      if id == promptId then
        obj.get("success").flatMap(_.asBoolean) match
          case Some(true) => ()
          case _ =>
            failStream(
              RaiderError.ProviderUnavailable(
                s"pi refused prompt (response success=false): ${snap(obj)}"
              )
            )

  private def handleSessionEvent(kind: String, obj: Json.Obj): Unit =
    kind match
      case "agent_start" => offer(Right(Some(ModelEvent.Started(attemptId))))
      case "agent_settled" =>
        val aborted = obj.get("aborted").flatMap(_.asBoolean).getOrElse(false)
        settledAborted.set(aborted)
        if aborted then
          settleTerminal(
            ModelEvent.Failed(
              attemptId,
              RaiderError.Cancelled(
                "pi run cancelled: agent_settled(aborted=true)"
              )
            )
          )
        else settleTerminal(ModelEvent.Finished(attemptId, "settled"))
      case "message_start" =>
        offer(
          Right(
            Some(
              ModelEvent.MetadataObserved(
                attemptId,
                "pi.message.start",
                obj.get("message").map(snap).getOrElse(snap(obj))
              )
            )
          )
        )
      case "message_end" =>
        offer(
          Right(
            Some(
              ModelEvent.MetadataObserved(
                attemptId,
                "pi.message.end",
                obj.get("message").map(snap).getOrElse(snap(obj))
              )
            )
          )
        )
      case "message_update" => handleUpdate(obj)
      case "turn_start" =>
        metadata("pi.turn.start", obj)
      case "turn_end" =>
        metadata("pi.turn.end", obj)
      case "tool_execution_start" =>
        metadata("pi.toolExecution.start", obj)
      case "tool_execution_update" =>
        metadata("pi.toolExecution.update", obj)
      case "tool_execution_end" =>
        metadata("pi.toolExecution.end", obj)
      case "auto_retry_start" =>
        metadata("pi.retry.start", obj)
      case "auto_retry_end" =>
        metadata("pi.retry.end", obj)
      case "agent_end" =>
        metadata("pi.agent.end", obj)
      case other =>
        // Unknown session events are recorded as metadata, never dropped.
        metadata(s"pi.$other", obj)

  /** message_update: message snapshots carry usage; assistantMessageEvent
    * entries carry the actual deltas. Snapshot fields are NOT emitted as text
    * deltas (upstream maps updates onto full messages).
    */
  private def handleUpdate(obj: Json.Obj): Unit =
    obj.get("message").flatMap(_.asObject).foreach { msg =>
      msg.get("usage").foreach(emitUsage)
    }
    obj.get("assistantMessageEvent").flatMap(_.asObject) match
      case None =>
        metadata("pi.message.update", obj)
      case Some(ev) =>
        ev.get("type").flatMap(_.asString) match
          case Some("text_delta") =>
            ev.get("delta").flatMap(_.asString).foreach { t =>
              offer(Right(Some(ModelEvent.TextDelta(attemptId, t))))
            }
          case Some("thinking_delta") =>
            ev.get("delta").flatMap(_.asString).foreach { t =>
              metadata("pi.reasoning", ev, valueOverride = t)
            }
          case Some("toolcall_start") =>
            val idx = intOf(ev.get("contentIndex"))
            val id = ev
              .get("id")
              .flatMap(_.asString)
              .getOrElse(s"call-index-$idx")
            pendingCalls.put(idx, id)
            metadata("pi.toolCall.start", ev)
          case Some("toolcall_delta") =>
            val idx = intOf(ev.get("contentIndex"))
            val callId =
              Option(pendingCalls.get(idx)).getOrElse(s"call-index-$idx")
            ev.get("delta").flatMap(_.asString).foreach { frag =>
              offer(
                Right(
                  Some(
                    ModelEvent.ToolCallDelta(
                      attemptId,
                      ToolCallId(callId),
                      idx,
                      frag
                    )
                  )
                )
              )
            }
          case Some("toolcall_end") =>
            val idx = intOf(ev.get("contentIndex"))
            val tc = ev.get("toolCall").flatMap(_.asObject)
            val id = tc
              .flatMap(_.get("id"))
              .flatMap(_.asString)
              .getOrElse(
                Option(pendingCalls.remove(idx)).getOrElse(s"call-index-$idx")
              )
            val name =
              tc.flatMap(_.get("name")).flatMap(_.asString).getOrElse("")
            val args =
              tc.flatMap(_.get("arguments")).map(_.toJson).getOrElse("{}")
            offer(
              Right(
                Some(
                  ModelEvent.ToolCallReady(
                    attemptId,
                    ToolCallId(id),
                    name,
                    args
                  )
                )
              )
            )
          case Some(other) =>
            metadata(s"pi.messageEvent.$other", ev)
          case None =>
            failStream(
              RaiderError.StreamProtocol("assistantMessageEvent without type")
            )

  private def emitUsage(usage: Json): Unit =
    val u = usage.asObject
    val input = u
      .flatMap(_.get("input"))
      .flatMap(_.asNumber)
      .map(n => n.value.longValue())
      .getOrElse(0L)
    val output = u
      .flatMap(_.get("output"))
      .flatMap(_.asNumber)
      .map(n => n.value.longValue())
      .getOrElse(0L)
    offer(
      Right(
        Some(
          ModelEvent.UsageObserved(
            attemptId,
            Usage(input, output, None),
            priceKnown = false
          )
        )
      )
    )
    u.flatMap(_.get("cost"))
      .flatMap(_.asObject)
      .flatMap(_.get("total"))
      .foreach { total =>
        // pi v0 costs are USD-denominated; the raw number token round-trips
        // through zio-json BigDecimal unchanged (e.g. 0.0011).
        offer(
          Right(
            Some(
              ModelEvent
                .MetadataObserved(attemptId, "pi.usage.cost.usd", total.toJson)
            )
          )
        )
      }

  // ---- queue plumbing ----

  private def offer(item: PiQueueItem): Unit =
    zio.Unsafe.unsafe { unsafe =>
      Runtime.default.unsafe.run(queue.offer(item))(using
        zio.Trace.empty,
        unsafe
      ) match
        case Exit.Success(_) => ()
        case Exit.Failure(cause) =>
          throw new IllegalStateException(
            s"pi event queue offer failed: $cause"
          )
    }

  private def metadata(
      key: String,
      obj: Json,
      valueOverride: String = ""
  ): Unit =
    val value = if valueOverride.isEmpty then snap(obj) else valueOverride
    offer(Right(Some(ModelEvent.MetadataObserved(attemptId, key, value))))

  private def settleTerminal(ev: ModelEvent): Unit =
    if done.compareAndSet(false, true) then
      offer(Right(Some(ev)))
      offer(Right(None))
      closeStdinQuietly()

  private def failStream(err: RaiderError): Unit =
    if done.compareAndSet(false, true) then
      offer(Left(err))
      offer(Right(None))

  /** Terminal failure that the caller must be able to observe as the last
    * stream event (destroyed cancel): the events channel ends cleanly with a
    * `Failed` event instead of failing the stream itself.
    */
  private def failWithTerminalEvent(err: RaiderError): Unit =
    if done.compareAndSet(false, true) then
      offer(Right(Some(ModelEvent.Failed(attemptId, err))))
      offer(Right(None))

  private def snap(j: Json): String =
    val raw = j.toJson
    if raw.length <= 4096 then raw else raw.take(4096)

  private def intOf(j: Option[Json]): Int =
    j.flatMap(_.asNumber).map(n => n.value.intValue()).getOrElse(0)

  private def dispatchWatchdog(): Unit =
    val deadline = java.lang.System.currentTimeMillis() + cfg.dispatchTimeoutMs
    while !done.get() && java.lang.System.currentTimeMillis() < deadline do
      Thread.sleep(50L)
    if !done.get() && !responseSeen.get() then
      failStream(
        RaiderError.ProviderUnavailable(
          s"pi did not accept the prompt within ${cfg.dispatchTimeoutMs}ms"
        )
      )
