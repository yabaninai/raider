package raider.backend.opencode

import zio.Clock
import zio.Duration
import zio.Scope
import zio.ZIO
import zio.ZLayer
import zio.stream.ZStream
import zio.test.*
import zio.test.Assertion.*
import zio.test.Live

/** Integration coverage for the OpenCode HTTP backend adapter (RAI-035) run
 * against the deterministic loopback contract stand. Every mutating call is
 * asserted exactly-once against the stand request log (no mutation replay),
 * typed errors are checked for secret non-leakage, and the settle/reconcile
 * discipline is exercised through every scripted scenario.
 */
object OpenCodeBackendSpec extends ZIOSpecDefault:

  private final case class Rig(stand: OpenCodeContractStand, backend: OpenCodeBackend)

  private def startRig(
      scenario: OcScenario,
      password: Option[String] = None,
      auth: Option[OcAuth] = None,
      directory: Option[String] = None,
      settleDeadlineMs: Long = 15000,
      eventIdleTimeoutMs: Long = 5000,
      reconnectAttempts: Int = 3,
  ): ZIO[Scope & Clock, Throwable, Rig] =
    for
      stand <- ZIO.attempt(OpenCodeContractStand.start(scenario, password))
      _ <- ZIO.addFinalizer(ZIO.attempt(stand.stop()).ignore)
      cfg <- ZIO.fromEither(
        OcConfig.make(
          stand.baseUrl,
          directory,
          auth,
          eventIdleTimeoutMs = eventIdleTimeoutMs,
          settleDeadlineMs = settleDeadlineMs,
          reconnectAttempts = reconnectAttempts,
        )
      )
      backend <- OpenCodeBackend.make(cfg)
    yield Rig(stand, backend)

  private def attach(rig: Rig): ZIO[Scope & Clock, OcError, ConnectedBackend] = rig.backend.connect()

  /** Folds failures into assertions so failures carry context. The rig
   * startup itself fails with Throwable (OcError is a RuntimeException).
   */
  /** Each helper runs the rig under the zio-test Live service so every
   * timed wait (settle deadlines, SSE idle timeouts, reconnect backoff —
   * including inside forked fibers such as the shared event-bus reader) uses
   * the real clock. In zio-test 2.1.26 a layer-provided Clock.ClockLive does
   * NOT redirect ZIO.sleep/timeout scheduling away from the frozen TestClock
   * (verified with a minimal repro: service lookups see the provided
   * instance while sleeps still park forever), so Live.live is required; the
   * inner provideLayer stays to discharge the Clock requirement type-wise.
   * The rig scope closes inside the helper — the end of the helper is the
   * end of the test body, so finalizer semantics are unchanged.
   */
  private def expect[A](io: ZIO[Scope & Clock, Throwable, A])(
      f: A => TestResult,
  ): ZIO[Any, Nothing, TestResult] =
    Live.live(
      ZIO.scoped(
      io.either.map {
        case Right(a) => f(a)
        case Left(err: OcError) =>
          assertTrue(s"unexpected typed error ${err.code}: ${err.detail}" == "<expected success>")
        case Left(other) => assertTrue(s"unexpected failure: $other" == "<expected success>")
      },
      ).provideLayer(ZLayer.succeed(Clock.ClockLive))
    )

  private def expectFailure[A](io: ZIO[Scope & Clock, Throwable, A])(
      f: OcError => TestResult,
  ): ZIO[Any, Nothing, TestResult] =
    Live.live(
      ZIO.scoped(
      io.either.map {
        case Left(err: OcError) => f(err)
        case Left(other)        => assertTrue(s"expected a typed error, got: $other" == "<expected failure>")
        case Right(_)           => assertTrue("expected a typed error but the call succeeded" == "<expected failure>")
      },
      ).provideLayer(ZLayer.succeed(Clock.ClockLive))
    )

  private def completedOf(outcome: OcRunOutcome): Either[String, OcRunOutcome.Completed] = outcome match
    case c: OcRunOutcome.Completed => Right(c)
    case other                     => Left(s"expected Completed, got $other")

  private def promptAsyncCount(stand: OpenCodeContractStand): Int =
    stand.requests.count(r => r.method == "POST" && r.path.endsWith("/prompt_async"))

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("OpenCodeBackend")(
    test("prompt settles Completed from terminal events without reconciliation, exactly one mutation") {
      expect(
        for
          rig <- startRig(OcScenario.Normal)
          connected <- attach(rig)
          session <- connected.createSession()
          outcome <- connected.prompt(session.id, "ping")
        yield (rig, outcome)
      ) { case (rig, outcome) =>
        val promptBody = rig.stand.requests
          .find(r => r.method == "POST" && r.path.endsWith("/prompt_async"))
          .map(_.body)
          .getOrElse("")
        val prompts = promptAsyncCount(rig.stand)
        completedOf(outcome) match
          case Right(c) =>
            assertTrue(
              c.sessionId.startsWith("ses-fixture-") &&
                c.messageId.startsWith("msg-") &&
                c.assistantMessageId.isDefined &&
                c.text == "Reply to: ping" &&
                c.tools.size == 1 &&
                c.tools.head.state.isInstanceOf[OcToolState.Completed] &&
                c.tools.head.state.asInstanceOf[OcToolState.Completed].output == Some("fixture-output\n") &&
                c.cost == Some(0.01) &&
                c.usage.exists(_.input == 10) &&
                c.reconciled == false &&
                prompts == 1 &&
                promptBody.contains(c.messageId)
            )
          case Left(diag) => assertTrue(diag == "<expected Completed>")
      }
    },
    test("health, createSession, session, and sessionStatus round-trip with server identity") {
      expect(
        for
          rig <- startRig(OcScenario.Normal)
          connected <- attach(rig)
          health <- rig.backend.health()
          session <- connected.createSession(title = Some("T1"))
          back <- connected.session(session.id)
          status <- connected.sessionStatus()
        yield (rig, health, connected, session, back, status)
      ) { case (rig, health, connected, session, back, status) =>
        assertTrue(
          health == OcHealth(true, "1.19.4") &&
            back.id == session.id &&
            back.title == "T1" &&
            back.version == "1.19.4" &&
            connected.info.version == "1.19.4" &&
            connected.info.apiFamily == 1 &&
            connected.info.healthy &&
            connected.info.supportsPinnedSurface &&
            status.get(session.id).contains("idle")
        )
      }
    },
    test("connect refuses servers reporting healthy=false") {
      expectFailure(
        for
          rig <- startRig(OcScenario.Unhealthy)
          _ <- attach(rig)
        yield ()
      ) { err =>
        assertTrue(
          err.code == "OC-CFG" &&
            err.detail.contains("healthy=false")
        )
      }
    },
    test("connect refuses unparseable server versions (unknown guarantee never passes silently)") {
      expectFailure(
        for
          rig <- startRig(OcScenario.UnknownVersion)
          _ <- attach(rig)
        yield ()
      ) { err =>
        assertTrue(
          err.code == "OC-CFG" &&
            err.detail.contains("not a recognizable")
        )
      }
    },
    test("connect refuses servers outside the pinned v1 API family") {
      expectFailure(
        for
          rig <- startRig(OcScenario.OldVersion)
          _ <- attach(rig)
        yield ()
      ) { err =>
        assertTrue(
          err.code == "OC-CFG" &&
            err.detail.contains("outside the pinned v1")
        )
      }
    },
    test("wrong credentials fail typed OC-AUTH and never leak the secret values") {
      expectFailure(
        for
          rig <- startRig(OcScenario.Normal, password = Some("s3cret-value"), auth = Some(OcAuth.withPassword("wrong-pass")))
          _ <- rig.backend.health()
          noLeak = rig.stand.requests.forall(r =>
            !r.body.contains("s3cret-value") && !r.body.contains("wrong-pass")
          )
        yield noLeak
      ) { err =>
        assertTrue(
          err.code == "OC-AUTH" &&
            err.detail.contains("401") &&
            !err.detail.contains("s3cret-value") &&
            !err.detail.contains("wrong-pass")
        )
      }
    },
    test("correct credentials attach and every request carries the Authorization header") {
      expect(
        for
          rig <- startRig(OcScenario.Normal, password = Some("pw-1"), auth = Some(OcAuth.withPassword("pw-1")))
          connected <- attach(rig)
          _ <- connected.createSession()
        yield rig
      ) { rig =>
        val reqs = rig.stand.requests
        val allAuth = reqs.nonEmpty && reqs.forall(_.hasAuth)
        assertTrue(allAuth)
      }
    },
    test("abort mid-run acks true and the run settles Aborted with server abort evidence") {
      expect(
        for
          rig <- startRig(OcScenario.AbortGate(false))
          connected <- attach(rig)
          session <- connected.createSession()
          // The subscription queue is registered strictly BEFORE prompt_async:
          // run evidence emitted while the server processes the submission is
          // buffered, so the tool-completed event cannot be missed.
          toolQ <- connected.subscribeSessionQueue(session.id)
          outcomeFiber <- connected.prompt(session.id, "long").fork
          toolDone <- ZStream
            .fromQueue(toolQ)
            .flatMap {
              case Right(ev)   => ZStream.succeed(ev.payload)
              case Left(error) => ZStream.fail(error)
            }
            .filter {
              case OcBusEvent.MessagePartUpdated(OcPart.Tool(_, _, _, _, _, _: OcToolState.Completed), _) => true
              case _                                                                                     => false
            }
            .take(1)
            .runHead
            .timeout(Duration.fromSeconds(10))
          _ <- ZIO.fromOption(toolDone).orDieWith(_ => new RuntimeException("tool completed event not delivered within 10s"))
          ack <- connected.abort(session.id)
          outcome <- outcomeFiber.join
        yield (rig, ack, outcome)
      ) { case (rig, ack, outcome) =>
        val abortedOk = outcome match
          case OcRunOutcome.Aborted(_, _, serverError, _, ackRequested, reconciled) =>
            serverError.name == "MessageAbortedError" && ackRequested && !reconciled
          case other => false
        val prompts = promptAsyncCount(rig.stand)
        val aborts = rig.stand.requests.count(r => r.method == "POST" && r.path.endsWith("/abort"))
        assertTrue(
          ack.acked &&
            abortedOk &&
            prompts == 1 &&
            aborts == 1
        )
      }
    },
    test("abort on an idle session acks false and sends exactly one abort request") {
      expect(
        for
          rig <- startRig(OcScenario.Normal)
          connected <- attach(rig)
          session <- connected.createSession()
          ack <- connected.abort(session.id)
        yield (rig, ack)
      ) { case (rig, ack) =>
        val aborts = rig.stand.requests.count(r => r.method == "POST" && r.path.endsWith("/abort"))
        assertTrue(
          !ack.acked &&
            aborts == 1
        )
      }
    },
    test("silent abort: ack true, deadline passes, reconcile shows no completion -> Uncertain, no replay") {
      expect(
        for
          rig <- startRig(OcScenario.AbortGate(true), settleDeadlineMs = 800)
          connected <- attach(rig)
          session <- connected.createSession()
          // Subscription registered strictly before the submission (see the
          // abort mid-run test above).
          toolQ <- connected.subscribeSessionQueue(session.id)
          outcomeFiber <- connected.prompt(session.id, "hold").fork
          toolDone <- ZStream
            .fromQueue(toolQ)
            .flatMap {
              case Right(ev)   => ZStream.succeed(ev.payload)
              case Left(error) => ZStream.fail(error)
            }
            .filter {
              case OcBusEvent.MessagePartUpdated(OcPart.Tool(_, _, _, _, _, _: OcToolState.Completed), _) => true
              case _                                                                                     => false
            }
            .take(1)
            .runHead
            .timeout(Duration.fromSeconds(10))
          _ <- ZIO.fromOption(toolDone).orDieWith(_ => new RuntimeException("tool completed event not delivered within 10s"))
          ack <- connected.abort(session.id)
          outcome <- outcomeFiber.join
        yield (rig, ack, outcome)
      ) { case (rig, ack, outcome) =>
        val uncertainOk = outcome match
          case OcRunOutcome.Uncertain(_, _, reason) => reason.contains("settle deadline")
          case other                                => false
        val prompts = promptAsyncCount(rig.stand)
        val aborts = rig.stand.requests.count(r => r.path.endsWith("/abort"))
        assertTrue(
          ack.acked &&
            uncertainOk &&
            prompts == 1 &&
            aborts == 1
        )
      }
    },
    test("deadline without session.idle reconciles Completed from the read-only message list") {
      expect(
        for
          rig <- startRig(OcScenario.NoIdle, settleDeadlineMs = 800)
          connected <- attach(rig)
          session <- connected.createSession()
          outcome <- connected.prompt(session.id, "hello")
        yield (rig, outcome)
      ) { case (rig, outcome) =>
        val prompts = promptAsyncCount(rig.stand)
        completedOf(outcome) match
          case Right(c) =>
            assertTrue(
              c.text == "Reply to: hello" &&
                c.reconciled &&
                prompts == 1
            )
          case Left(diag) => assertTrue(diag == "<expected Completed>")
      }
    },
    test("a mid-run SSE drop never replays the mutation; the run reconciles Completed") {
      expect(
        for
          rig <- startRig(OcScenario.SseDropAfterEvents(6), settleDeadlineMs = 800)
          connected <- attach(rig)
          session <- connected.createSession()
          outcome <- connected.prompt(session.id, "drop")
        yield (rig, outcome)
      ) { case (rig, outcome) =>
        val prompts = promptAsyncCount(rig.stand)
        val opens = rig.stand.sseOpenCount
        completedOf(outcome) match
          case Right(c) =>
            assertTrue(
              c.reconciled &&
                prompts == 1 &&
                opens == 2
            )
          case Left(diag) => assertTrue(diag == "<expected Completed>")
      }
    },
    test("a fresh connection re-delivers server.connected (subscription across reconnects)") {
      expect(
        for
          rig <- startRig(OcScenario.SseDropFirstConnection)
          connected <- attach(rig)
          first <- connected.events.take(2).runCollect
        yield first
      ) { events =>
        assertTrue(
          events.size == 2 &&
            events.forall(_.payload == OcBusEvent.ServerConnected)
        )
      }
    },
    test("the bus gives up after reconnectAttempts and waiters reconcile read-only") {
      expect(
        for
          rig <- startRig(OcScenario.SseStayDown)
          connected <- attach(rig)
          session <- connected.createSession()
          outcome <- connected.prompt(session.id, "dead-bus")
        yield (rig, outcome)
      ) { case (rig, outcome) =>
        val opens = rig.stand.sseOpenCount
        val prompts = promptAsyncCount(rig.stand)
        val aborts = rig.stand.requests.count(r => r.method == "POST" && r.path.endsWith("/abort"))
        completedOf(outcome) match
          case Right(c) =>
            assertTrue(
              c.reconciled &&
                opens == 4 &&
                prompts == 1 &&
                aborts == 0
            )
          case Left(diag) => assertTrue(diag == "<expected Completed>")
      }
    },
    test("unknown session ids fail honest OC-INP (server 404), no settle hang") {
      expectFailure(
        for
          rig <- startRig(OcScenario.Normal)
          connected <- attach(rig)
          _ <- connected.session("ses-does-not-exist")
        yield ()
      ) { err =>
        assertTrue(
          err.code == "OC-INP" &&
            err.detail.contains("404") &&
            err.detail.contains("session not found")
        )
      }
    },
    test("promptAsync to an unknown session fails OC-INP and sends exactly one request") {
      expect(
        for
          rig <- startRig(OcScenario.Normal)
          connected <- attach(rig)
          result <- connected.promptAsync("ses-does-not-exist", "ghost").either
        yield (rig, result)
      ) { case (rig, result) =>
        val prompts = promptAsyncCount(rig.stand)
        result match
          case Left(err) =>
            assertTrue(
              err.code == "OC-INP" &&
                err.detail.contains("404") &&
                prompts == 1
            )
          case Right(_) =>
            assertTrue("expected OC-INP failure but promptAsync succeeded" == "<expected failure>")
      }
    },
    test("a malformed event breaks the stream once; the bus reconnects and the waiter reconciles") {
      expect(
        for
          rig <- startRig(OcScenario.BadEventOnce, settleDeadlineMs = 800)
          connected <- attach(rig)
          session <- connected.createSession()
          outcome <- connected.prompt(session.id, "bad-frame")
        yield (rig, outcome)
      ) { case (rig, outcome) =>
        val prompts = promptAsyncCount(rig.stand)
        val opens = rig.stand.sseOpenCount
        completedOf(outcome) match
          case Right(c) =>
            assertTrue(
              c.reconciled &&
                prompts == 1 &&
                opens == 2
            )
          case Left(diag) => assertTrue(diag == "<expected Completed>")
      }
    },
    test("session-scoped subscriptions do not cross-read concurrent sessions") {
      expect(
        for
          rig <- startRig(OcScenario.Normal)
          connected <- attach(rig)
          sessionA <- connected.createSession()
          sessionB <- connected.createSession()
          outcomeAFiber <- connected.prompt(sessionA.id, "alpha").fork
          outcomeBFiber <- connected.prompt(sessionB.id, "beta").fork
          outcomeA <- outcomeAFiber.join
          outcomeB <- outcomeBFiber.join
        yield (rig, outcomeA, outcomeB)
      ) { case (rig, outcomeA, outcomeB) =>
        val aOk = completedOf(outcomeA).toOption.exists(c => c.text == "Reply to: alpha" && !c.reconciled)
        val bOk = completedOf(outcomeB).toOption.exists(c => c.text == "Reply to: beta" && !c.reconciled)
        val prompts = promptAsyncCount(rig.stand)
        assertTrue(
          aOk && bOk &&
            outcomeA.sessionId != outcomeB.sessionId &&
            prompts == 2
        )
      }
    },
    test("scoped calls carry the directory query parameter") {
      expect(
        for
          rig <- startRig(OcScenario.Normal, directory = Some("/fixture/ws"))
          connected <- attach(rig)
          session <- connected.createSession()
          outcome <- connected.prompt(session.id, "scoped")
        yield rig
      ) { rig =>
        val createQuery = rig.stand.requests.find(r => r.method == "POST" && r.path == "/session").map(_.query).getOrElse("")
        val promptQuery = rig.stand.requests.find(_.path.endsWith("/prompt_async")).map(_.query).getOrElse("")
        assertTrue(
          createQuery.contains("directory=%2Ffixture%2Fws") &&
            promptQuery.contains("directory=%2Ffixture%2Fws")
        )
      }
    },
    test("empty prompt text is rejected locally before any request is sent") {
      Live.live(
      ZIO.scoped(
        (for
          rig <- startRig(OcScenario.Normal)
          connected <- attach(rig)
          session <- connected.createSession()
          result <- connected.promptAsync(session.id, "   ").either
          promptsSent = promptAsyncCount(rig.stand)
        yield (result, promptsSent)).flatMap {
          case (Left(err), promptsSent) =>
            assertTrue(err.code == "OC-INP" && promptsSent == 0)
          case (Right(_), _) =>
            assertTrue("expected OC-INP failure but promptAsync succeeded" == "<expected failure>")
        },
      ).provideLayer(ZLayer.succeed(Clock.ClockLive))
      )
    },
    test("owned server lifecycle configs are refused (CapabilityUnsupported)") {
      ZIO
        .fromEither(
          OcConfig.make(
            "http://127.0.0.1:1",
            lifecycle = OcLifecycle.Owned(Vector("opencode", "serve")),
          )
        )
        .flip
        .map { err =>
          assertTrue(err.isInstanceOf[OcError.CapabilityUnsupported])
        }
    },
  )
