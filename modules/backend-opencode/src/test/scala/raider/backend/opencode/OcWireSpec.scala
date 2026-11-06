package raider.backend.opencode

import zio.Clock
import zio.Scope
import zio.ZIO
import zio.json.JsonDecoder
import zio.json.DeriveJsonDecoder
import zio.json.JsonEncoder
import zio.test.*
import zio.test.Assertion.*

import java.nio.file.{Files, Paths}

/** Wire-contract coverage for the pinned OpenCode shapes (RAI-035). Decodes
 * the immutable corpus contracts/backends/opencode/expected/pinned-events.json
 * (located via RAIDER_REPO_ROOT) and asserts each pinned frame maps to the
 * documented adapter event; plus request-body encoders and the typed decode
 * failures that must never be silently swallowed.
 */
object OcWireSpec extends ZIOSpecDefault:

  final case class CorpusEntry(name: String, raw: String, expectType: String, expectSession: Option[String])
  object CorpusEntry:
    private final case class Wire(name: String, raw: String, expectType: String, expectSession: Option[String])
        derives JsonDecoder
    given JsonDecoder[CorpusEntry] = summon[JsonDecoder[Wire]].map(w =>
      CorpusEntry(w.name, w.raw, w.expectType, w.expectSession)
    )

  final case class Corpus(events: Vector[CorpusEntry])
  object Corpus:
    given JsonDecoder[Corpus] = DeriveJsonDecoder.gen

  private def loadCorpus: Either[String, Corpus] =
    val root = sys.env.get("RAIDER_REPO_ROOT").filter(_.nonEmpty).getOrElse {
      // run-tests.sh exports RAIDER_REPO_ROOT; under sbt the test working
      // directory is the module dir, so walk up to the repo root.
      var dir = Option(Paths.get("").toAbsolutePath)
      var found = ""
      while dir.isDefined && found.isEmpty do
        val candidate = dir.get.resolve("contracts/backends/opencode/expected/pinned-events.json")
        if Files.exists(candidate) then found = dir.get.toString
        else dir = Option(dir.get.getParent)
      found
    }
    if root.isEmpty then Left(
      "pinned-events corpus not found from the test working directory; run via modules/backend-opencode/run-tests.sh or set RAIDER_REPO_ROOT"
    )
    else
      val path = Paths.get(root, "contracts", "backends", "opencode", "expected", "pinned-events.json")
      if !Files.exists(path) then Left(s"pinned-events corpus missing at $path")
      else
        val text = new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8)
        summon[JsonDecoder[Corpus]].decodeJson(text).left.map(e => s"corpus does not decode: $e")

  private def typeOk(payload: OcBusEvent, expectType: String): Boolean =
    scala.util.Try {
      expectType match
        case "ServerConnected" => payload == OcBusEvent.ServerConnected
        case "SessionCreated"  => payload.isInstanceOf[OcBusEvent.SessionCreated]
        case "SessionStatus"   => payload.asInstanceOf[OcBusEvent.SessionStatus].status == "busy"
        case "MessageUpdatedUser" =>
          payload.asInstanceOf[OcBusEvent.MessageUpdated].info.isInstanceOf[OcMessage.User]
        case "MessageUpdatedAssistant" =>
          payload.asInstanceOf[OcBusEvent.MessageUpdated].info.isInstanceOf[OcMessage.Assistant]
        case "PartOther" =>
          payload.asInstanceOf[OcBusEvent.MessagePartUpdated].part.isInstanceOf[OcPart.Other]
        case "ToolPending"   => toolState(payload).isInstanceOf[OcToolState.Pending]
        case "ToolRunning"   => toolState(payload).isInstanceOf[OcToolState.Running]
        case "ToolCompleted" =>
          toolState(payload) match
            case OcToolState.Completed(_, output, title, start, end) =>
              output == Some("hi\n") && title == Some("echo hi") && start.isDefined && end.isDefined
            case _ => false
        case "TextDelta" =>
          payload.asInstanceOf[OcBusEvent.MessagePartUpdated].delta == Some("Hello ")
        case "SessionIdle" => payload.isInstanceOf[OcBusEvent.SessionIdle]
        case "SessionErrorAborted" =>
          payload.asInstanceOf[OcBusEvent.SessionError].error.exists(_.name == "MessageAbortedError")
        case "PermissionUpdated" => payload.isInstanceOf[OcBusEvent.PermissionUpdated]
        case "Unknown" =>
          payload.asInstanceOf[OcBusEvent.Unknown].eventType == "custom.future.thing"
        case other => throw new IllegalStateException(s"corpus references unknown expectType $other")
    }.getOrElse(false)

  private def toolState(payload: OcBusEvent): OcToolState =
    payload.asInstanceOf[OcBusEvent.MessagePartUpdated].part
      .asInstanceOf[OcPart.Tool].state

  private def entryResult(entry: CorpusEntry): Either[String, TestResult] =
    summon[JsonDecoder[OcGlobalEvent]]
      .decodeJson(entry.raw)
      .left
      .map(e => s"${entry.name}: frame does not decode: $e")
      .map { ev =>
        val ok = typeOk(ev.payload, entry.expectType) && ev.payload.sessionID == entry.expectSession
        if ok then assertTrue(true)
        else
          assertTrue(
            s"${entry.name}: decoded as ${ev.payload} (sessionID=${ev.payload.sessionID}); expected ${entry.expectType} session=${entry.expectSession}" == "<never>"
          )
      }

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("OcWire")(
    test("decodes the entire pinned-events corpus against the documented adapter events") {
      ZIO.fromEither(loadCorpus).flatMap { corpus =>
        ZIO
          .foreach(corpus.events)(e => ZIO.fromEither(entryResult(e)))
          .map { results =>
            results.foldLeft[TestResult](assertTrue(true))(_ && _) &&
            assertTrue(corpus.events.size >= 15)
          }
      }
    },
    test("encodes prompt_async bodies with only the set fields") {
      val body = OcPromptBody(
        Some(OcModelRef("fixture", "test-model")),
        None,
        List(OcTextPart("hi \"quoted\"")),
        Some("msg_local_1"),
        None,
        None,
        None,
      )
      val json = JsonEncoderCompat.encode(body)
      assertTrue(json.contains("\"messageID\":\"msg_local_1\"")) &&
      assertTrue(json.contains("\"model\":{\"providerID\":\"fixture\",\"modelID\":\"test-model\"}")) &&
      assertTrue(json.contains("\"type\":\"text\"")) &&
      assertTrue(json.contains("hi \\\"quoted\\\"")) &&
      assertTrue(!json.contains("noReply")) &&
      assertTrue(!json.contains("system")) &&
      assertTrue(!json.contains("tools")) &&
      assertTrue(!json.contains("agent"))
    },
    test("rejects a session.idle frame without sessionID (typed failure, not Unknown)") {
      val frame =
        "{\"directory\":\"/w\",\"payload\":{\"type\":\"session.idle\",\"properties\":{\"wrong\":\"shape\"}}}"
      assertTrue(summon[JsonDecoder[OcGlobalEvent]].decodeJson(frame).isLeft)
    },
    test("rejects frames that are not JSON objects") {
      assertTrue(summon[JsonDecoder[OcGlobalEvent]].decodeJson("42").isLeft) &&
      assertTrue(summon[JsonDecoder[OcGlobalEvent]].decodeJson("\"str\"").isLeft)
    },
    test("keeps optional evidence fields on assistant messages (cost, tokens, finish)") {
      val info =
        """{"id":"msg_a1","sessionID":"ses_01","role":"assistant","time":{"created":1,"completed":9},
          |"parentID":"msg_u1","modelID":"m","providerID":"p","mode":"build","path":{"cwd":"/w","root":"/w"},
          |"cost":0.01,"tokens":{"input":10,"output":5,"reasoning":0,"cache":{"read":1,"write":2}},"finish":"stop"}""".stripMargin
      val decoded = summon[JsonDecoder[OcMessage]].decodeJson(info)
      decoded match
        case Right(OcMessage.Assistant(info)) =>
          assertTrue(
            info.cost == Some(0.01) &&
              info.tokens.exists(_.cache.read == 1) &&
              info.finish == Some("stop") &&
              info.time.completed == Some(9)
          )
        case Right(_) => assertTrue("decoded as user message" == "<expected assistant>")
        case Left(e)  => assertTrue(s"decode error: $e" == "<expected assistant>")
    },
    test("degrades malformed tool parts to Other instead of failing the stream") {
      val part =
        """{"id":"t1","sessionID":"ses","messageID":"m1","type":"tool","callID":"c1","tool":"bash",
          |"state":{"status":"later","input":{}}}""".stripMargin
      val decoded = summon[JsonDecoder[OcPart]].decodeJson(part)
      decoded match
        case Right(OcPart.Other(partType)) => assertTrue(partType == "tool")
        case Right(_)                      => assertTrue("decoded as non-Other part" == "<expected Other>")
        case Left(e)                       => assertTrue(s"decode error: $e" == "<expected Other>")
    },
  )

/** Local encoder helper so the spec has one place that stringifies bodies. */
object JsonEncoderCompat:
  def encode[A](a: A)(using enc: JsonEncoder[A]): String = enc.encodeJson(a, None).toString
