package raider.spike.oai

import zio.{Unsafe, ZIO, Exit as ZExit}
import zio.json.*

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration as JDuration

/** SPIKE-OAI-01 (owner-directed): OpenAI-compatible Chat wire feasibility.
  *
  * A real Scala client (JDK HttpClient + ZIO effect adapter + zio-json codecs)
  * talking to a local mock gateway at <baseUrl>/v1/chat/completions. Scenarios are
  * selected via the X-Spike-Scenario header by the harness: non-streaming text,
  * SSE streaming text, SSE streaming tool calls (assembled, never executed from
  * partial args), auth failure classification, and mid-stream cancellation.
  *
  * Mock-only: no real endpoints, no keys, no paid calls. Not the RAI-011 product
  * module; it validates the wire/codec/cancellation approach for that card.
  */
object OpenAiCompatSpike:

  private val runtime = zio.Runtime.default

  final case class AuthRejected(code: Int)
      extends RuntimeException(s"auth rejected: HTTP $code")

  final case class StreamProtocolError(reason: String)
      extends RuntimeException(s"stream protocol violation: $reason")

  /** Accepts either a base URL (…/v1) or a full endpoint; the /v1 prefix is a
    * default, not a hardcoded assumption (review 2026-10-03 P2). */
  private def endpoint(url: String): String =
    val u = url.stripSuffix("/")
    if u.endsWith("/chat/completions") then u
    else if u.endsWith("/v1") then u + "/chat/completions"
    else u + "/v1/chat/completions"

  // ---------- wire schemas (zio-json derivation) ----------

  final case class WireMessage(role: String, content: String)
  object WireMessage:
    given JsonCodec[WireMessage] = DeriveJsonCodec.gen[WireMessage]

  final case class WireRequest(model: String, messages: List[WireMessage], stream: Boolean)
  object WireRequest:
    given JsonCodec[WireRequest] = DeriveJsonCodec.gen[WireRequest]

  final case class Usage(prompt_tokens: Int, completion_tokens: Int, total_tokens: Int)
  object Usage:
    given JsonCodec[Usage] = DeriveJsonCodec.gen[Usage]

  final case class FnPayload(name: String, arguments: String)
  object FnPayload:
    given JsonCodec[FnPayload] = DeriveJsonCodec.gen[FnPayload]

  final case class ToolCall(`type`: String, id: String, function: FnPayload)
  object ToolCall:
    given JsonCodec[ToolCall] = DeriveJsonCodec.gen[ToolCall]

  final case class RespMessage(role: String, content: Option[String], tool_calls: Option[List[ToolCall]])
  object RespMessage:
    given JsonCodec[RespMessage] = DeriveJsonCodec.gen[RespMessage]

  final case class RespChoice(index: Int, message: RespMessage, finish_reason: Option[String])
  object RespChoice:
    given JsonCodec[RespChoice] = DeriveJsonCodec.gen[RespChoice]

  final case class WireResponse(choices: List[RespChoice], usage: Option[Usage])
  object WireResponse:
    given JsonCodec[WireResponse] = DeriveJsonCodec.gen[WireResponse]

  final case class FnDelta(name: Option[String], arguments: Option[String])
  object FnDelta:
    given JsonCodec[FnDelta] = DeriveJsonCodec.gen[FnDelta]

  final case class ToolCallDelta(index: Int, id: Option[String], `function`: Option[FnDelta])
  object ToolCallDelta:
    given JsonCodec[ToolCallDelta] = DeriveJsonCodec.gen[ToolCallDelta]

  final case class Delta(role: Option[String], content: Option[String],
                         tool_calls: Option[List[ToolCallDelta]])
  object Delta:
    given JsonCodec[Delta] = DeriveJsonCodec.gen[Delta]

  final case class StreamChoice(index: Int, delta: Delta, finish_reason: Option[String])
  object StreamChoice:
    given JsonCodec[StreamChoice] = DeriveJsonCodec.gen[StreamChoice]

  final case class StreamChunk(choices: List[StreamChoice], usage: Option[Usage])
  object StreamChunk:
    given JsonCodec[StreamChunk] = DeriveJsonCodec.gen[StreamChunk]

  // ---------- client ----------

  final case class ToolAcc(var id: Option[String], var name: Option[String],
                           args: StringBuilder):
    def render: String =
      s"id=${id.getOrElse("?")} name=${name.getOrElse("?")} args=${args.toString}"

  final case class StreamResult(text: String, toolCalls: List[ToolAcc],
                                usage: Option[Usage], sawDone: Boolean)

  private def newClient(): HttpClient =
    HttpClient.newBuilder().connectTimeout(JDuration.ofSeconds(10)).build()

  private def request(url: String, apiKey: String, scenario: String, body: String): HttpRequest =
    HttpRequest.newBuilder()
      .uri(URI.create(endpoint(url)))
      .timeout(JDuration.ofSeconds(60))
      .header("Authorization", s"Bearer $apiKey")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json, text/event-stream")
      .header("X-Spike-Scenario", scenario)
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
      .build()

  /** send + use within an acquire-release: the body stays open only inside `use`,
    * including on interruption (release closes it). */
  private def send[A](url: String, apiKey: String, scenario: String, body: String)(
      use: HttpResponse[java.io.InputStream] => ZIO[Any, Throwable, A])
      : ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.asyncInterrupt[Any, Throwable, HttpResponse[java.io.InputStream]] { register =>
        val cf = newClient()
          .sendAsync(request(url, apiKey, scenario, body),
                     HttpResponse.BodyHandlers.ofInputStream())
        cf.whenComplete { (resp, err) =>
          if err != null then register(ZIO.fail(err)) else register(ZIO.succeed(resp))
        }
        // fiber interruption withdraws the in-flight request (before headers too)
        Left(ZIO.succeed { cf.cancel(true); () })
      }
    )(resp => ZIO.succeed(resp.body().close())) { resp =>
      ZIO.suspendSucceed {
        if resp.statusCode() == 401 || resp.statusCode() == 403 then
          ZIO.fail(AuthRejected(resp.statusCode()))
        else if resp.statusCode() != 200 then
          ZIO.fail(new IllegalStateException(s"unexpected HTTP ${resp.statusCode()}"))
        else use(resp)
      }
    }

  /** Non-streaming chat completion. */
  def askOnce(url: String, apiKey: String, model: String, prompt: String)
      : ZIO[Any, Throwable, WireResponse] =
    send(url, apiKey, "text",
         WireRequest(model, List(WireMessage("user", prompt)), stream = false).toJson) { resp =>
      val text = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8)
      ZIO.fromEither(text.fromJson[WireResponse])
        .mapError(err => new IllegalStateException(s"decode failed: $err"))
    }

  /** Streaming chat completion: incremental UTF-8-safe SSE parsing (BufferedReader),
    * text deltas surfaced through onDelta as they arrive, tool-call fragments
    * accumulated per index and assembled only at completion (never executed). */
  def askStream(url: String, apiKey: String, model: String, prompt: String, scenario: String,
                onDelta: String => Unit = _ => ())
      : ZIO[Any, Throwable, StreamResult] =
    send(url, apiKey, scenario,
         WireRequest(model, List(WireMessage("user", prompt)), stream = true).toJson) { resp =>
      val reader = new BufferedReader(
        new InputStreamReader(resp.body(), StandardCharsets.UTF_8))
      val text = new StringBuilder
      val tools = scala.collection.mutable.SortedMap.empty[Int, ToolAcc]
      var usage: Option[Usage] = None
      var sawDone = false

      def handleLine(line: String): Unit =
        val trimmed = line.trim
        if trimmed.startsWith("data:") then
          val payload = trimmed.drop(5).trim
          if payload == "[DONE]" then sawDone = true
          else payload.fromJson[StreamChunk] match
            case Right(chunk) =>
              chunk.usage.foreach { u => usage = Some(u) }
              chunk.choices.foreach { ch =>
                ch.delta.content.foreach { c =>
                  if c.nonEmpty then { text.append(c); onDelta(c) }
                }
                ch.delta.tool_calls.foreach { deltas =>
                  deltas.foreach { d =>
                    val acc = tools.getOrElseUpdate(
                      d.index, ToolAcc(None, None, new StringBuilder))
                    d.id.foreach { v => acc.id = Some(v) }
                    d.`function`.foreach { fn =>
                      fn.name.foreach { v => acc.name = Some(v) }
                      fn.arguments.foreach { a => acc.args.append(a) }
                    }
                  }
                }
              }
            case Left(err) =>
              throw StreamProtocolError(s"malformed SSE payload: $err")

      def result(): StreamResult =
        StreamResult(text.toString, tools.values.toList, usage, sawDone)

      // one flatMap step per SSE line: interruption is observed at line boundaries
      def loop(): ZIO[Any, Throwable, StreamResult] =
        ZIO.suspend {  // suspend (not suspendSucceed): converts thrown StreamProtocolError to a failure
          // A truncated chunked body surfaces from the JDK decoder as an
          // IOException rather than a clean -1 read; both mean: no terminal event.
          val line =
            try Right(reader.readLine())
            catch case e: java.io.IOException => Left(e)
          line match
            case Left(_) =>
              ZIO.fail(StreamProtocolError("truncated body before terminal event"))
            case Right(null) =>
              ZIO.fail(StreamProtocolError("EOF before terminal event ([DONE]/finish)"))
            case Right(l) =>
              handleLine(l)
              if sawDone then ZIO.succeed(result()) else loop()
        }

      loop()
    }

  /** REPL-facing foreground ask: streams text into the terminal, returns the result. */
  def replAsk(url: String, apiKey: String, model: String, prompt: String): String =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(
        askStream(url, apiKey, model, prompt, "stream-text",
                  t => { print(t); Console.out.flush() }))
      match
        case ZExit.Success(r) => r.text
        case ZExit.Failure(cause) =>
          throw new IllegalStateException(s"replAsk failed: ${cause.prettyPrint.take(400)}")
    }

  // ---------- CLI ----------

  private def report(r: StreamResult): Unit =
    r.toolCalls.foreach { t => println(s"[tool-call] ${t.render}") }
    println(s"[usage] ${r.usage.map(u =>
      s"prompt=${u.prompt_tokens} completion=${u.completion_tokens} total=${u.total_tokens}"
    ).getOrElse("none")}")
    println(s"[done] sawDone=${r.sawDone}")
    println(s"[final] ${r.text}")

  def run(args: List[String]): ZIO[Any, Throwable, Int] =
    args match
      case "ask" :: url :: key :: model :: prompt :: Nil =>
        askOnce(url, key, model, prompt).map { r =>
          println(s"[final] ${r.choices.headOption.flatMap(_.message.content).getOrElse("")}")
          println(s"[usage] ${r.usage.map(u =>
            s"prompt=${u.prompt_tokens} completion=${u.completion_tokens} total=${u.total_tokens}"
          ).getOrElse("none")}")
          0
        }
      case "ask-stream" :: url :: key :: model :: prompt :: Nil =>
        askStream(url, key, model, prompt, "stream-text",
                  t => { print(t); Console.out.flush() })
          .map { r => println(); report(r); 0 }
      case "tool-stream" :: url :: key :: model :: prompt :: Nil =>
        askStream(url, key, model, prompt, "stream-tool", _ => ()).map { r =>
          println("[note] tool args assembled only; nothing executed")
          report(r); 0
        }
      case "malformed-stream" :: url :: key :: model :: prompt :: Nil =>
        askStream(url, key, model, prompt, "stream-malformed", _ => ()).fold(
          { case StreamProtocolError(reason) =>
              println(s"[classified] StreamProtocol reason=$reason"); 21
            case other =>
              println(s"[unexpected] $other"); 20 },
          { r => report(r); 0 })
      case "eof-stream" :: url :: key :: model :: prompt :: Nil =>
        askStream(url, key, model, prompt, "stream-eof", _ => ()).fold(
          { case StreamProtocolError(reason) =>
              println(s"[classified] StreamProtocol reason=$reason"); 21
            case other =>
              println(s"[unexpected] $other"); 20 },
          { r => report(r); 0 })
      case "cancel-headers" :: url :: key :: model :: afterMs :: Nil =>
        // Server hangs before responding. Interrupting the BARE pending request must
        // withdraw it (CompletableFuture.cancel) without a body ever being opened.
        // Note: ZIO.acquireRelease keeps its acquire phase uninterruptible by design,
        // so a connect-phase deadline needs an explicit race — an RAI-011 design note.
        ZIO.suspendSucceed {
          val opened = java.util.concurrent.atomic.AtomicBoolean(false)
          val pending: ZIO[Any, Throwable, Unit] =
            ZIO.asyncInterrupt { register =>
              val cf = newClient().sendAsync(
                request(url, key, "hang-headers", "{}"),
                HttpResponse.BodyHandlers.ofInputStream())
              cf.whenComplete { (resp, err) =>
                if err != null then register(ZIO.fail(err))
                else
                  opened.set(true)
                  register(ZIO.succeed(resp.body().close()))
              }
              Left(ZIO.succeed { cf.cancel(true); () })
            }
          for
            fib <- pending.fork
            _   <- ZIO.sleep(zio.Duration.fromMillis(afterMs.toLong))
            res <- fib.interrupt.timeout(zio.Duration.fromSeconds(3))
            clean = res.exists {
              case ZExit.Failure(cause) => cause.isInterrupted
              case _                    => false
            }
          yield
            println(s"[cancelled-before-headers] cleanly=$clean bodyOpened=${opened.get()}")
            if clean && !opened.get() then 0 else 10
        }
      case "cancel-stream" :: url :: key :: model :: afterMs :: Nil =>
        ZIO.suspendSucceed {
          val deltas = new java.util.concurrent.atomic.AtomicInteger(0)
          val flow = askStream(url, key, model, "-", "stream-slow",
                               _ => deltas.incrementAndGet())
          for
            fib    <- flow.fork
            _      <- ZIO.sleep(zio.Duration.fromMillis(afterMs.toLong))
            exit   <- fib.interrupt.timeout(zio.Duration.fromSeconds(3))
            _      <- fib.await.exit
          yield
            val interruptedCleanly = exit.exists {
              case ZExit.Failure(cause) => cause.isInterrupted
              case _                    => false
            }
            println(s"[cancelled] cleanly=$interruptedCleanly deltas=${deltas.get()} " +
                    s"noPartialToolExecution=true")
            if interruptedCleanly then 0 else 10
        }
      case _ =>
        ZIO.succeed {
          println("usage: OpenAiCompatSpike ask|ask-stream|tool-stream|malformed-stream|eof-stream <url> <key> <model> <prompt> | cancel-stream|cancel-headers <url> <key> <model> <afterMs>")
          2
        }

  def main(args: Array[String]): Unit =
    val outcome = Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(run(args.toList).catchAll {
        case AuthRejected(code) =>
          ZIO.succeed { println(s"[classified] ProviderAuth http=$code"); 10 }
        case StreamProtocolError(reason) =>
          ZIO.succeed { println(s"[classified] StreamProtocol reason=$reason"); 21 }
        case other =>
          ZIO.succeed { println(s"[unexpected] ${other.toString}"); 20 }
      })
    }
    outcome match
      case ZExit.Success(code) => if code != 0 then System.exit(code)
      case ZExit.Failure(cause) =>
        println(s"[fatal] ${cause.prettyPrint}")
        System.exit(20)

end OpenAiCompatSpike
