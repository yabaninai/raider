package raider.spike

import zio.{Unsafe, ZIO}
import zio.Exit as ZExit
import zio.json.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/** RAI-001 candidates probe.
  *
  * HTTP: JDK HttpClient (java.net.http) driven through a ZIO async adapter with
  * acquire-release body handling, so cancellation closes the response body mid-stream
  * (candidate check for runtime-contracts.md §1).
  *
  * JSON: zio-json DeriveJsonCodec derivation, decode/encode roundtrip and a negative
  * decode (candidate codec pair for versioned schemas).
  *
  * The probe reports PASS/FAIL lines and exits non-zero on any failure.
  */
object HttpJsonProbe:

  final case class ProbeInput(question: String, limits: Int)
  object ProbeInput:
    given JsonCodec[ProbeInput] = DeriveJsonCodec.gen[ProbeInput]

  private val bytesSeen = AtomicInteger(0)

  private def streamingGet(url: String, deadline: Long): ZIO[Any, Throwable, String] =
    ZIO.suspendSucceed {
      val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
      val request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build()
      ZIO.acquireReleaseWith(
        ZIO.async[Any, Throwable, HttpResponse[java.io.InputStream]] { register =>
          client
            .sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            .whenComplete { (resp, err) =>
              if err != null then register(ZIO.fail(err))
              else register(ZIO.succeed(resp))
            }
        }
      )(resp => ZIO.succeed(resp.body().close())) { resp =>
        if resp.statusCode() != 200 then
          ZIO.fail(new IllegalStateException(s"unexpected status ${resp.statusCode()}"))
        else
          def readLoop(acc: StringBuilder): ZIO[Any, Throwable, String] =
            ZIO.suspendSucceed {
              if System.nanoTime() > deadline then ZIO.succeed(acc.toString)
              else
                val buf = new Array[Byte](1024)
                val n = resp.body().read(buf)
                if n < 0 then ZIO.succeed(acc.toString)
                else
                  bytesSeen.addAndGet(n)
                  readLoop(acc.append(new String(buf, 0, n, StandardCharsets.UTF_8)))
            }
          readLoop(new StringBuilder)
      }
    }

  private def check(name: String)(body: => ZIO[Any, Throwable, Boolean]): ZIO[Any, Throwable, Boolean] =
    body.map { ok =>
      println(s"[probe] $name: ${if ok then "PASS" else "FAIL"}")
      ok
    }

  def run(args: List[String]): ZIO[Any, Throwable, Boolean] =
    args match
      case "http" :: url :: millis :: Nil =>
        val deadline = System.nanoTime() + millis.toLong * 1000000L
        for
          body <- streamingGet(url, deadline)
          _ = println(s"[probe] http bytes=${bytesSeen.get()} bodyHead=${body.take(80).replace('\n', ' ')}")
          ok <- check("http-stream-read")(ZIO.succeed(bytesSeen.get() > 0))
        yield ok

      case "http-interrupt" :: url :: afterMillis :: Nil =>
        // Fork the streaming read, then interrupt the fiber mid-stream: the
        // acquire-release finalizer must close the HTTP body (server observes it).
        for
          fib   <- streamingGet(url, Long.MaxValue).fork
          _     <- ZIO.sleep(zio.Duration.fromMillis(afterMillis.toLong))
          _     <- fib.interrupt.timeout(zio.Duration.fromSeconds(3))
          _     <- fib.await.exit
          ok    <- check("http-interrupt-cleanup")(ZIO.succeed(bytesSeen.get() > 0))
        yield ok

      case "http-cancel-headers" :: url :: afterMillis :: Nil =>
        // Cancel while the server deliberately delays its response: fiber
        // interruption must hook into CompletableFuture.cancel so the request is
        // withdrawn and no body is ever opened (review 2026-10-03: before-headers
        // cancellation; the plain ZIO.async variant had no cancel hook).
        val opened = java.util.concurrent.atomic.AtomicBoolean(false)
        val pending: ZIO[Any, Throwable, HttpResponse[java.io.InputStream]] =
          ZIO.asyncInterrupt { register =>
            val client = HttpClient.newBuilder()
              .connectTimeout(java.time.Duration.ofSeconds(10)).build()
            val req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build()
            val cf = client.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream())
            cf.whenComplete { (resp, err) =>
              if err != null then register(ZIO.fail(err))
              else { opened.set(true); register(ZIO.succeed(resp)) }
            }
            Left(ZIO.succeed { cf.cancel(true); () })
          }
        for
          fib <- (pending *> ZIO.never).fork
          _   <- ZIO.sleep(zio.Duration.fromMillis(afterMillis.toLong))
          res <- fib.interrupt.timeout(zio.Duration.fromSeconds(3))
          // cleanly means: interrupt observed (not a fast failure) and no body opened
          clean = res.exists {
            case ZExit.Failure(cause) => cause.isInterrupted
            case _                    => false
          }
          ok  <- check("http-cancel-before-headers")(ZIO.succeed(clean && !opened.get()))
          _    = println(s"[probe] cancelled cleanly=$clean bodyOpened=${opened.get()}")
        yield ok

      case "json" :: Nil =>
        val raw = """{"question":"проверь проект","limits":5}"""
        for
          decoded <- check("json-decode")(ZIO.succeed(raw.fromJson[ProbeInput].isRight))
          roundtripOk <- check("json-roundtrip")(
            ZIO.succeed(raw.fromJson[ProbeInput].map(_.toJson).flatMap(_.fromJson[ProbeInput]) == raw.fromJson[ProbeInput])
          )
          negativeOk <- check("json-negative")(ZIO.succeed("""{"bogus":1}""".fromJson[ProbeInput].isLeft))
        yield decoded && roundtripOk && negativeOk

      case _ =>
        ZIO.fail(new IllegalArgumentException("usage: HttpJsonProbe http <url> <deadline-ms> | json"))

  def main(args: Array[String]): Unit =
    val exit = Unsafe.unsafe { implicit u =>
      zio.Runtime.default.unsafe.run(run(args.toList))
    }
    exit match
      case ZExit.Success(true) => System.exit(0)
      case ZExit.Success(false) => System.exit(10)
      case ZExit.Failure(cause) =>
        println(s"[probe] ERROR ${cause.prettyPrint}")
        System.exit(20)

end HttpJsonProbe
