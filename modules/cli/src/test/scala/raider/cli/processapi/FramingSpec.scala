package raider.cli.processapi

import zio.json.ast.Json
import zio.test._

import java.nio.charset.StandardCharsets.UTF_8

/** RAI-201 framing unit coverage (ADR-037 §3, §6; fixtures
  * `fragmented-frames-unicode`, `oversize-frame`, `eof`): arbitrary byte
  * chunking across UTF-8 code-point boundaries, U+2028/U+2029 never acting as
  * delimiters, the 1 MiB payload bound enforced before JSON decoding, strict
  * UTF-8, and EOF-mid-frame classification.
  */
object FramingSpec extends ZIOSpecDefault:

  private def newAssembler: Framing.LineAssembler = Framing.LineAssembler()

  private def submitWire(task: String): Array[Byte] =
    val wire =
      s"""{"protocolVersion":{"major":1,"minor":0},"type":"submit",""" +
        s""""requestId":"req-utf8","input":{"schemaVersion":1,"task":"$task"}}"""
    (wire + "\n").getBytes(UTF_8)

  private def field(json: Json, name: String): Option[Json] =
    json match
      case obj: Json.Obj => obj.fields.toMap.get(name)
      case _             => None

  private def taskOf(json: Json): Option[String] =
    field(json, "input").flatMap(field(_, "task")).collect { case Json.Str(s) =>
      s
    }

  private def isOversize(
      bytes: Long
  )(e: Either[Protocol.ProtocolFailure, Json]): Boolean =
    e.left.toOption.exists {
      case Protocol.OversizeFrame(observed) => observed == bytes
      case _                                => false
    }

  private def isMalformed(part: String)(
      failure: Protocol.ProtocolFailure
  ): Boolean =
    failure match
      case Protocol.MalformedFrame(reason) => reason.contains(part)
      case _                               => false

  private def isInvalidUtf8(
      e: Either[Protocol.ProtocolFailure, Json]
  ): Boolean =
    e.left.toOption.exists {
      case Protocol.InvalidUtf8 => true
      case _                    => false
    }

  def spec = suite("RAI-201 process-api framing")(
    test(
      "multibyte payload split at code-point boundaries reassembles exactly"
    ) {
      val bytes = submitWire("Δelta 漢字 🧪")
      // Cut relative to the task value: mid-Δ (2B), mid-漢 (3B), mid-🧪 (4B).
      val marker = "\"task\":\"".getBytes(UTF_8)
      val start = bytes.indexOfSlice(marker) + marker.length
      val cuts = Vector(
        start + 1,
        start + 4,
        start + 8,
        start + 10,
        start + 12,
        bytes.length
      )
      var offset = 0
      val assembler = newAssembler
      var results = Vector.empty[Either[Protocol.ProtocolFailure, Json]]
      cuts.foreach { cut =>
        val end = math.min(cut, bytes.length)
        if end > offset then
          results = results ++ assembler.feed(
            java.util.Arrays.copyOfRange(bytes, offset, end)
          )
          offset = end
      }
      val trailing = assembler.finish()
      assertTrue(
        results.size == 1,
        results.headOption.exists(_.isRight),
        results.headOption
          .flatMap(_.toOption)
          .flatMap(taskOf)
          .contains("Δelta 漢字 🧪"),
        trailing.isEmpty
      )
    },
    test("escaped U+2028 and U+2029 are payload, not delimiters") {
      // The fixture ships the separators as \u2028 / \u2029 escapes; the wire
      // bytes are pure ASCII, but a naive "split on any whitespace-ish byte"
      // reader could still mangle them. Delivery must be byte-exact.
      val bytes = submitWire("A\\u2028B\\u2029C")
      val assembler = newAssembler
      val results =
        assembler.feed(java.util.Arrays.copyOf(bytes, bytes.length / 2)) ++
          assembler.feed(
            java.util.Arrays.copyOfRange(bytes, bytes.length / 2, bytes.length)
          )
      assertTrue(
        results.size == 1,
        results.headOption
          .flatMap(_.toOption)
          .flatMap(taskOf)
          .contains("A\u2028B\u2029C")
      )
    },
    test("raw U+2028 bytes and lone CR are payload, not delimiters") {
      // Raw U+2028/U+2029 separator bytes inside the JSON string, plus a
      // lone 0x0D just before the terminating 0x0A: only 0x0A terminates a
      // frame, so the frame must arrive as exactly one unit, intact.
      val body =
        submitWire("A\u2028B\u2029C").dropRight(1) // strip trailing 0x0A
      val assembler = newAssembler
      val results =
        assembler.feed(body ++ Array(0x0d.toByte)) ++ assembler.feed(
          Array(0x0a.toByte)
        )
      assertTrue(
        results.size == 1,
        results.headOption
          .flatMap(_.toOption)
          .flatMap(taskOf)
          .contains("A\u2028B\u2029C")
      )
    },
    test(
      "oversize frame is rejected before JSON decoding and framing continues"
    ) {
      val oversize = ("x" * (Framing.FrameMaxBytes + 1)).getBytes(UTF_8)
      val assembler = newAssembler
      val r1 = assembler.feed(oversize ++ Array(0x0a.toByte))
      val valid = submitWire("after")
      val r2 = assembler.feed(valid)
      assertTrue(
        r1.size == 1,
        r1.headOption.exists(isOversize(Framing.FrameMaxBytes.toLong + 1L)),
        r2.size == 1,
        r2.head.isRight
      )
    },
    test("exactly FrameMaxBytes payload bytes are accepted") {
      val prefix = "{\"padding\":\""
      val suffix = "\"}"
      val padLen = Framing.FrameMaxBytes - prefix.length - suffix.length
      val wire = prefix + ("x" * padLen) + suffix + "\n"
      val assembler = newAssembler
      val results = assembler.feed(wire.getBytes(UTF_8))
      assertTrue(results.size == 1, results.head.isRight)
    },
    test("oversize bytes are discarded to the next LF, reported once") {
      val oversize = ("y" * (Framing.FrameMaxBytes + 40)).getBytes(UTF_8)
      val assembler = newAssembler
      val r1 = assembler.feed(oversize) // no LF yet: nothing reported
      val valid = submitWire("after")
      val r2 = assembler.feed(valid ++ valid)
      assertTrue(
        r1.isEmpty,
        r2.count(isOversize(Framing.FrameMaxBytes.toLong + 1L)) == 1,
        r2.count(_.isRight) == 1
      )
    },
    test("empty line is a malformed frame") {
      val assembler = newAssembler
      val results = assembler.feed(Array(0x0a.toByte, 0x0a.toByte))
      assertTrue(
        results.size == 2,
        results.forall(_.left.toOption.exists(isMalformed("empty frame")))
      )
    },
    test("invalid UTF-8 is rejected as invalid_utf8, not replaced") {
      val assembler = newAssembler
      val results = assembler.feed(Array(0x7b.toByte, 0xff.toByte, 0x0a.toByte))
      assertTrue(
        results.size == 1,
        results.headOption.exists(isInvalidUtf8)
      )
    },
    test("non-object JSON is malformed") {
      val assembler = newAssembler
      val results = assembler.feed("[1,2]\n\"just a string\"\n".getBytes(UTF_8))
      assertTrue(
        results.size == 2,
        results.forall(
          _.left.toOption.exists(isMalformed("frame must be a JSON object"))
        )
      )
    },
    test("invalid JSON syntax is malformed without echoing the payload") {
      val assembler = newAssembler
      val secretFragment = "TOPSECRET-should-never-be-echoed"
      val results = assembler.feed(
        s"{\"leaked\":\"$secretFragment\":".getBytes(UTF_8) ++ Array(
          0x0a.toByte
        )
      )
      assertTrue(
        results.size == 1,
        results.headOption
          .flatMap(_.left.toOption)
          .exists(isMalformed("invalid JSON")),
        results.headOption
          .flatMap(_.left.toOption)
          .exists(failure => !failure.message.contains(secretFragment))
      )
    },
    test("trailing partial frame at EOF is never dispatched") {
      val assembler = newAssembler
      val partial = assembler.feed(submitWire("partial").dropRight(1))
      val finish1 = assembler.finish()
      val finish2 = newAssembler.finish()
      assertTrue(
        partial.isEmpty,
        finish1.exists(isMalformed("EOF")),
        finish2.isEmpty
      )
    }
  )

end FramingSpec
