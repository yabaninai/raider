package raider.backend.opencode

import zio.Clock
import zio.Scope
import zio.test.*
import zio.test.Assertion.*

/** Unit coverage for the byte-level SSE framer and payload extraction
 * (RAI-035). Chunk boundaries, line endings, multi-data frames, comments,
 * frame-size bounds, and multibyte characters split across chunks.
 */
object OcSseSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("OcSse")(
    test("reassembles a frame pushed byte-by-byte across chunk boundaries") {
      val framer = new OcSseFramer(1024)
      val payload = "data: hello\n\n"
      val bytes = payload.getBytes("UTF-8")
      var acc = Vector.empty[String]
      bytes.foreach(b => acc = acc ++ framer.push(Array(b)))
      assertTrue(acc == Vector("data: hello"))
    },
    test("treats CRLF as a line terminator and still frames on the empty line") {
      val framer = new OcSseFramer(1024)
      val out = framer.push("data: x\r\n\r\n".getBytes("UTF-8"))
      assertTrue(out == Vector("data: x"))
    },
    test("joins multiple data lines in one frame with LF") {
      val framer = new OcSseFramer(1024)
      val out = framer.push("data: a\ndata: b\n\n".getBytes("UTF-8"))
      assertTrue(out == Vector("data: a\ndata: b")) &&
      assertTrue(OcSse.dataPayload(out.head) == Some("a\nb"))
    },
    test("emits comment frames that carry no data payload") {
      val framer = new OcSseFramer(1024)
      val out = framer.push(": keep-alive\n\n".getBytes("UTF-8"))
      assertTrue(out == Vector(": keep-alive")) &&
      assertTrue(OcSse.dataPayload(out.head) == None)
    },
    test("accepts data lines without the optional space") {
      assertTrue(OcSse.dataPayload("data:x") == Some("x")) &&
      assertTrue(OcSse.dataPayload("data: x") == Some("x"))
    },
    test("ignores event/id/retry fields when extracting the payload") {
      val frame = "event: session.idle\nid: 42\ndata: {\"k\":1}\nretry: 100\n"
      assertTrue(OcSse.dataPayload(frame) == Some("{\"k\":1}"))
    },
    test("returns None for a frame without data lines") {
      assertTrue(OcSse.dataPayload("event: x\nid: 1\n") == None)
    },
    test("raises Overflow with the configured bound when a frame exceeds it") {
      val framer = new OcSseFramer(8)
      val caught =
        try
          framer.push("abcdefghij".getBytes("UTF-8"))
          None
        catch case e: OcSse.Overflow => Some(e)
      assertTrue(caught.map(_.limit) == Some(8))
    },
    test("decodes multibyte characters split across chunks at frame boundaries") {
      val framer = new OcSseFramer(1024)
      val all = "data: é\n\n".getBytes("UTF-8")
      val first = all.take(all.length - 2) // cut inside the two-byte 'é'
      val out = framer.push(first) ++ framer.push(all.drop(first.length))
      assertTrue(out == Vector("data: é")) &&
      assertTrue(OcSse.dataPayload(out.head) == Some("é"))
    },
    test("treats a bare CR as a line terminator, producing two data lines") {
      val framer = new OcSseFramer(1024)
      val out = framer.push("data: a\rdata: a\n\n".getBytes("UTF-8"))
      assertTrue(out == Vector("data: a\ndata: a")) &&
      assertTrue(OcSse.dataPayload(out.head) == Some("a\na"))
    },
  )
