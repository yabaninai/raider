package raider.provider.chat.stream

import raider.provider.chat.OpenAIChatBackend
import zio.test._
import zio.ZIO

object DebugFramerSpec extends ZIOSpecDefault:
  def spec = suite("debug")(
    test("backend trace") {
      val server = com.sun.net.httpserver.HttpServer.create(
        new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress, 0), 0)
      server.createContext("/v1/chat/completions", (ex: com.sun.net.httpserver.HttpExchange) =>
        try
          ex.getResponseHeaders.add("Content-Type", "text/event-stream")
          ex.sendResponseHeaders(200, 0)
          val frames = Vector(
            """{"id":"s1","choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}""",
            """{"id":"s1","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":null}]}""",
            """{"id":"s1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
            "[DONE]")
          val bytes = frames.map(f => s"data: $f\n\n").mkString.getBytes("UTF-8")
          var i = 0
          while i < bytes.length do
            ex.getResponseBody.write(bytes.slice(i, math.min(i + 2, bytes.length)))
            ex.getResponseBody.flush()
            i += 2
          ex.getResponseBody.close()
        finally ex.close())
      server.start()
      val url = s"http://127.0.0.1:${server.getAddress.getPort}/v1"
      for
        backend <- OpenAIChatStreamingBackend.make(
          OpenAIChatBackend.Config.make(url, "k", 30000))
        res <- ZIO.scoped(backend.stream(
                 raider.core.ModelRequest("x", Nil, 8)).runCollect.either)
        _ = println(s"INTEG-RESULT = ${res.left.map(e => e.code + " :: " + e.detail)}")
      yield assertTrue(true)
    }
  )
