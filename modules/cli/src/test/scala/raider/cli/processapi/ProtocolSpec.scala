package raider.cli.processapi

import zio.json.*
import zio.json.ast.Json
import zio.test._

/** RAI-201 protocol unit coverage (ADR-037 §3-§4; fixtures `normal`,
  * `refusal-and-secret-fields`, `duplicate-submit`): strict per-frame
  * allowlists, version negotiation, bounded diagnostics, and encoder shapes.
  */
object ProtocolSpec extends ZIOSpecDefault:

  private def parse(wire: String): Either[Protocol.ProtocolFailure, Json] =
    JsonDecoder[Json]
      .decodeJson(wire)
      .left
      .map(_ => Protocol.MalformedFrame("invalid JSON"))

  private def decodeWire(
      wire: String
  ): Either[Protocol.ProtocolFailure, Protocol.ClientFrame] =
    parse(wire).flatMap(Protocol.decode)

  /** Failures live on the Left; matching them through Either.exists would
    * inspect the Right (frame) side instead.
    */
  private def failureOf(wire: String): Option[Protocol.ProtocolFailure] =
    decodeWire(wire).left.toOption

  private def helloWire: String =
    """{"protocolVersion":{"major":1,"minor":0},"type":"hello","requestId":"req-001",""" +
      """"supported":[{"major":1,"minor":0},{"major":1,"minor":1}],"requiredCapabilities":["observe","cancel"]}"""

  private def submitWire(task: String, extra: String = ""): String =
    s"""{"protocolVersion":{"major":1,"minor":0},"type":"submit","requestId":"req-002",""" +
      s""""input":{"schemaVersion":1,"task":"$task"$extra}}"""

  private def field(json: Json, name: String): Option[Json] =
    json match
      case obj: Json.Obj => obj.fields.toMap.get(name)
      case _             => None

  def spec = suite("RAI-201 process-api protocol")(
    test("hello decodes with supported versions and required capabilities") {
      assertTrue(
        decodeWire(helloWire) == Right(
          Protocol.Hello(
            "req-001",
            Vector(Protocol.ClientVersion(1, 0), Protocol.ClientVersion(1, 1)),
            Vector("observe", "cancel")
          )
        )
      )
    },
    test("helloAck carries the negotiated version and adapter capabilities") {
      val ack = Protocol.helloAck("req-001")
      val parsed = JsonDecoder[Json].decodeJson(ack.toJson).toOption.get
      val capabilities = field(parsed, "capabilities")
        .collect { case Json.Arr(items) =>
          items.collect { case Json.Str(s) => s }
        }
        .toVector
        .flatten
      assertTrue(
        field(parsed, "type").contains(Json.Str("helloAck")),
        field(parsed, "requestId").contains(Json.Str("req-001")),
        field(parsed, "negotiated").exists(v =>
          field(v, "major").contains(Json.Num(Protocol.ProtocolMajor)) &&
            field(v, "minor").contains(Json.Num(Protocol.ProtocolMinor))
        ),
        capabilities.contains("observe") && capabilities.contains("cancel"),
        field(parsed, "adapterVersion").exists {
          case Json.Str(s) => s.nonEmpty
          case _           => false
        }
      )
    },
    test("unsupported major version is refused as fatal before dispatch") {
      val wire =
        """{"protocolVersion":{"major":2,"minor":0},"type":"submit","requestId":"req-version",""" +
          """"input":{"schemaVersion":1,"task":"Do not dispatch"}}"""
      assertTrue(
        failureOf(wire).exists {
          case failure @ Protocol.UnsupportedVersion(_) =>
            failure.fatal && failure.code == "unsupported_version"
          case _ => false
        }
      )
    },
    test("non-integer version components are refused") {
      val wire =
        """{"protocolVersion":{"major":1.5,"minor":0},"type":"hello","requestId":"r",""" +
          """"supported":[{"major":1,"minor":0}],"requiredCapabilities":[]}"""
      assertTrue(failureOf(wire).exists {
        case Protocol.InvalidFrame(_) => true
        case _                        => false
      })
    },
    test(
      "first-frame handshake state is enforced by the session, decode stays pure"
    ) {
      // decode() itself is stateless per-frame; Session owns helloSeen. A hello
      // re-send after handshake maps to hello_already_negotiated there — here we
      // only pin that decode accepts a well-formed hello anywhere.
      assertTrue(decodeWire(helloWire).toOption.exists {
        case Protocol.Hello("req-001", _, _) => true
        case _                               => false
      })
    },
    test("unknown frame type yields a bounded, non-fatal unknown_type") {
      val longType = "x" * 200
      val wire =
        s"""{"protocolVersion":{"major":1,"minor":0},"type":"$longType","requestId":"r"}"""
      assertTrue(
        failureOf(wire).exists {
          case Protocol.UnknownFrameType(boundedType) =>
            boundedType.length <= 64 && boundedType.length < longType.length
          case _ => false
        },
        failureOf(wire).exists {
          case f: Protocol.UnknownFrameType =>
            !f.fatal && f.code == "unknown_type"
          case _ => false
        }
      )
    },
    test("malformed JSON wire is malformed_frame and fatal") {
      assertTrue(
        parse("{not json").left.toOption.exists {
          case f: Protocol.MalformedFrame =>
            f.fatal && f.code == "malformed_frame"
          case _ => false
        }
      )
    },
    test("submit decodes to an opaque input object") {
      assertTrue(
        decodeWire(submitWire("Inspect the supplied fixture")).toOption.exists {
          case Protocol.Submit("req-002", input) =>
            field(input, "schemaVersion").contains(Json.Num(1)) &&
            field(input, "task").contains(
              Json.Str("Inspect the supplied fixture")
            )
          case _ => false
        }
      )
    },
    test("submit input must be an object") {
      val wire =
        """{"protocolVersion":{"major":1,"minor":0},"type":"submit","requestId":"r","input":"nope"}"""
      assertTrue(failureOf(wire).exists {
        case Protocol.InvalidFrame(_) => true
        case _                        => false
      })
    },
    test("unknown outer fields are refused without echoing their values") {
      val wire =
        """{"protocolVersion":{"major":1,"minor":0},"type":"submit","requestId":"req-secret",""" +
          """"apiKey":"REDACTED-FIXTURE-VALUE","input":{"schemaVersion":1,"task":"x"}}"""
      assertTrue(
        failureOf(wire).exists {
          case f: Protocol.UnknownField =>
            !f.fatal && f.code == "unknown_field" && f.message.contains(
              "apiKey"
            )
          case _ => false
        },
        failureOf(wire).exists(f =>
          !f.message.contains("REDACTED-FIXTURE-VALUE")
        )
      )
    },
    test("requestId is required on typed frames") {
      val wire =
        """{"protocolVersion":{"major":1,"minor":0},"type":"cancel"}"""
      assertTrue(failureOf(wire).exists {
        case Protocol.InvalidFrame(_) => true
        case _                        => false
      })
    },
    test("observe accepts non-negative integer afterSequence only") {
      val ok =
        """{"protocolVersion":{"major":1,"minor":0},"type":"observe","requestId":"r","afterSequence":0}"""
      val ok5 =
        """{"protocolVersion":{"major":1,"minor":0},"type":"observe","requestId":"r","afterSequence":5}"""
      val negative =
        """{"protocolVersion":{"major":1,"minor":0},"type":"observe","requestId":"r","afterSequence":-1}"""
      val fractional =
        """{"protocolVersion":{"major":1,"minor":0},"type":"observe","requestId":"r","afterSequence":1.5}"""
      val huge =
        """{"protocolVersion":{"major":1,"minor":0},"type":"observe","requestId":"r","afterSequence":100000000000000000000}"""
      assertTrue(
        decodeWire(ok) == Right(Protocol.Observe("r", 0L)),
        decodeWire(ok5) == Right(Protocol.Observe("r", 5L)),
        failureOf(negative).exists {
          case Protocol.InvalidFrame(_) => true
          case _                        => false
        },
        failureOf(fractional).exists {
          case Protocol.InvalidFrame(_) => true
          case _                        => false
        },
        failureOf(huge).exists {
          case Protocol.InvalidFrame(_) => true
          case _                        => false
        }
      )
    },
    test("cancel decodes") {
      val wire =
        """{"protocolVersion":{"major":1,"minor":0},"type":"cancel","requestId":"req-cancel"}"""
      assertTrue(decodeWire(wire) == Right(Protocol.Cancel("req-cancel")))
    },
    test("observation encoder reports coalesced skips with the frame") {
      val frame = Protocol.observation(
        "req-slow",
        Protocol.Observation(
          7,
          "progress",
          "coalesced",
          Some(Protocol.Skipped(4, 3))
        )
      )
      val parsed = JsonDecoder[Json].decodeJson(frame.toJson).toOption.get
      val skipped = field(parsed, "skipped")
      assertTrue(
        field(parsed, "type").contains(Json.Str("observation")),
        field(parsed, "sequence").contains(Json.Num(7)),
        skipped.exists(v =>
          field(v, "fromSequence").contains(Json.Num(4)) &&
            field(v, "count").contains(Json.Num(3))
        )
      )
    },
    test(
      "terminal encoder carries verdicts, bounded exit code and artifact refs"
    ) {
      val body = Protocol.TerminalBody(
        executionStatus = "completed",
        taskVerdict = "success",
        safetyVerdict = "passed",
        protocolVerdict = "passed",
        accountingStatus = "unavailable",
        cleanupStatus = "complete",
        exitCode = 0,
        artifacts = Vector(
          Protocol.ArtifactRef(
            "result.json",
            "a" * 64,
            "application/json",
            128L,
            "proc_root"
          )
        ),
        primaryError = None,
        secondaryError = None
      )
      val parsed = JsonDecoder[Json]
        .decodeJson(Protocol.terminal("req-t", "proc_root", body).toJson)
        .toOption
        .get
      assertTrue(
        field(parsed, "type").contains(Json.Str("terminal")),
        field(parsed, "requestId").contains(Json.Str("req-t")),
        field(parsed, "rootId").contains(Json.Str("proc_root")),
        field(parsed, "executionStatus").contains(Json.Str("completed")),
        field(parsed, "exitCode").contains(Json.Num(0)),
        field(parsed, "artifacts").exists {
          case Json.Arr(items) => items.size == 1
          case _               => false
        },
        field(parsed, "primaryError").isEmpty,
        field(parsed, "secondaryError").isEmpty
      )
    },
    test("error encoder echoes requestId when present, null when absent") {
      val withId = Protocol.error(Some("req-x"), Protocol.DuplicateSubmit)
      val withoutId = Protocol.error(None, Protocol.DuplicateSubmit)
      val p1 = JsonDecoder[Json].decodeJson(withId.toJson).toOption.get
      val p2 = JsonDecoder[Json].decodeJson(withoutId.toJson).toOption.get
      assertTrue(
        field(p1, "requestId").contains(Json.Str("req-x")),
        field(p1, "code").contains(Json.Str("duplicate_submit")),
        // ADR-037 §4: correlation "where known" — with no requestId the
        // encoder may omit the field entirely rather than emit null.
        field(p2, "requestId").forall(_ == Json.Null),
        field(p2, "code").contains(Json.Str("duplicate_submit"))
      )
    }
  )

end ProtocolSpec
