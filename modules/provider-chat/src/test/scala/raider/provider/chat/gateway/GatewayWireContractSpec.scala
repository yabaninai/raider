package raider.provider.chat.gateway

import raider.core.*
import raider.provider.chat.OpenAIChatBackend
import raider.runtime.admission.Admission
import raider.runtime.budget.{BudgetLimits, MicroUsd}
import raider.runtime.loop.AgentLoop
import zio.ZIO
import zio.test._

/** GW17 (RAI-287) — candidate gateway contract checks at the TRANSPORT layer
  * against the local gateway contract stand ([[GatewayContractStand]]), which
  * serves the accepted Yabanin gateway wire (RAI-184/ADR) on the loopback.
  *
  * Covers the W17 candidate-fingerprint gaps that live below the runner:
  *   - config/URL: the gateway base URL is used VERBATIM (no /models probing,
  *     no path rewriting), trailing slashes normalized;
  *   - auth: the Bearer child credential rides the wire; a rejected credential
  *     is a typed RA-AUTH, first-shot (no retry), and the key value never
  *     appears in the error detail;
  *   - labels: the operator's gateway attribution header
  *     (RAIDER_OPENAI_EXTRA_HEADERS, e.g. X-Yabanin-Labels) lands on the wire
  *     verbatim via the hermetic env seam and never carries the credential;
  *   - wrapper-owned lifecycle: over a FULL tool round the request log shows
  *     ONLY /gw/v1/chat/completions calls — Raider sends no session-mint or
  *     lifecycle request of its own (exactly one mint exists and it belongs to
  *     the wrapper);
  *   - policy refusal: HTTP 403 maps to the same typed RA-AUTH refusal.
  *
  * The stand is per-test owned (ephemeral port, acquire/release) — no shared
  * fixed ports or paths. No real network, no spend.
  */
object GatewayWireContractSpec extends ZIOSpecDefault:

  private def limsV() =
    BudgetLimits.make(maxAttempts = 8).fold(e => throw e, identity)

  private def withStand[A](
      use: GatewayContractStand => ZIO[Any, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(GatewayContractStand.start())(h =>
      ZIO.succeed(h.stop())
    )(use)

  def spec = suite("GW17 gateway wire contract (local gateway stand)")(
    test(
      "GW17-C1 config/URL: gateway base rides verbatim — one completions call, no /models probe, trailing slash trimmed"
    ) {
      withStand { stand =>
        for
          backend <- OpenAIChatBackend.make(
            OpenAIChatBackend.Config.make(
              stand.baseUrl + "/",
              GatewayContractStand.ChildKey
            )
          )
          events <- ZIO.scoped(
            backend
              .stream(
                ModelRequest(
                  "gw-flash",
                  List(RequestMessage("user", "hello ✓")),
                  64
                )
              )
              .runCollect
          )
          text = events.collect { case ModelEvent.TextDelta(_, t) =>
            t
          }.mkString
        yield assertTrue(
          text == "gateway ok",
          events.head == ModelEvent.Started(AttemptId("chatcmpl-gw17-1")),
          events.last == ModelEvent.Finished(
            AttemptId("chatcmpl-gw17-1"),
            "stop"
          ),
          // usage honesty: gateway usage object recorded, price NEVER invented
          events.exists {
            case ModelEvent.UsageObserved(_, u, known) =>
              u == Usage(23, 11, None) && !known
            case _ => false
          },
          // URL contract: exactly the accepted endpoint, verbatim base
          stand.paths == Vector("/gw/v1/chat/completions"),
          // no endpoint probing: /models was never contacted by the transport
          !stand.paths.contains("/gw/v1/models")
        )
      }
    },
    test(
      "GW17-C2 auth: rejected child credential → typed RA-AUTH, first shot, key value never in detail"
    ) {
      withStand { stand =>
        for
          bad <- OpenAIChatBackend.make(
            OpenAIChatBackend.Config.make(stand.baseUrl, "yb-wrong-child-key")
          )
          res <- ZIO.scoped(
            bad
              .stream(
                ModelRequest("gw-flash", List(RequestMessage("user", "u")), 8)
              )
              .runCollect
              .either
          )
          detail = res.fold(_.detail, _ => "")
        yield assertTrue(
          res.isLeft,
          res.fold(_.code == "RA-AUTH", _ => false),
          // secret hygiene: the wrong key never leaks into the error
          !detail.contains("yb-wrong-child-key"),
          // auth refusals are NOT 429s: exactly one attempt, no retry
          stand.paths == Vector("/gw/v1/chat/completions")
        )
      }
    },
    test(
      "GW17-C3 labels: RAIDER_OPENAI_EXTRA_HEADERS lands on the gateway wire verbatim; the label never carries the credential"
    ) {
      val labels = "task=GW17,worker=rai-287"
      withStand { stand =>
        for
          // hermetic env seam: production resolves sys.env; the test pins the
          // header source so the gateway attribution is exercised deterministically
          backend <- OpenAIChatBackend.make(
            OpenAIChatBackend.Config
              .make(stand.baseUrl, GatewayContractStand.ChildKey),
            Map(
              "RAIDER_OPENAI_EXTRA_HEADERS" ->
                s"X-Yabanin-Labels: $labels"
            )
          )
          _ <- ZIO.scoped(
            backend
              .stream(
                ModelRequest("gw-flash", List(RequestMessage("user", "u")), 8)
              )
              .runCollect
          )
          reqs = stand.requests
          // RFC 9110: header NAMES are case-insensitive on the wire; the
          // JDK server view may normalize them — the VALUE must be verbatim.
          labelEntry = reqs.head.yabaninHeaders
            .find((k, _) => k.equalsIgnoreCase("X-Yabanin-Labels"))
        yield assertTrue(
          reqs.length == 1,
          labelEntry.exists(_._2 == labels),
          // labels are lineage metadata only: they never carry the credential
          !reqs.head.yabaninHeaders.values.exists(
            _.contains(GatewayContractStand.ChildKey)
          ),
          reqs.head.authorization.contains(
            s"Bearer ${GatewayContractStand.ChildKey}"
          )
        )
      }
    },
    test(
      "GW17-C4 wrapper-owned lifecycle: full tool round — request log shows ONLY completions calls (Raider mints nothing)"
    ) {
      withStand { stand =>
        for
          backend <- OpenAIChatBackend.make(
            OpenAIChatBackend.Config
              .make(stand.baseUrl, GatewayContractStand.ChildKey)
          )
          adm <- Admission.make(BudgetLimits.make(maxAttempts = 8))
          root = AgentLoop.newRoot()
          echo = new Tool:
            def name = "lookup"; def version = 1; def description = ""
            def recovery = RecoveryClass.ReadOnly; def timeoutMs = 5000L
            def capabilities = ToolCapabilities(concurrentSafe = false)
            def invoke(args: String) = ZIO.succeed(s"""{"echo":$args}""")
          registry = ToolRegistry.of(echo).fold(e => throw e, identity)
          loop = AgentLoop(backend, registry, "gw-tool", adm, root)
          answer <- loop.runText(
            List(RequestMessage("user", "find x")),
            limsV(),
            4
          )
          usage <- adm.budgetLedger.usage
        yield assertTrue(
          answer == "gateway tool final",
          // the whole round rode the accepted endpoint — and NOTHING else:
          // zero session-mint / lifecycle / accounting requests of Raider's own
          stand.paths == Vector(
            "/gw/v1/chat/completions",
            "/gw/v1/chat/completions"
          ),
          !stand.paths.exists(p => !p.endsWith("/chat/completions")),
          usage.reservedActive == MicroUsd(0),
          usage.unpricedAttempts == 2L
        )
      }
    },
    test(
      "GW17-C5 policy refusal: gateway 403 → the same typed RA-AUTH refusal, single attempt"
    ) {
      withStand { stand =>
        for
          backend <- OpenAIChatBackend.make(
            OpenAIChatBackend.Config
              .make(stand.baseUrl, GatewayContractStand.ChildKey)
          )
          res <- ZIO.scoped(
            backend
              .stream(
                ModelRequest(
                  "gw-policy-deny",
                  List(RequestMessage("user", "u")),
                  8
                )
              )
              .runCollect
              .either
          )
        yield assertTrue(
          res.fold(_.code == "RA-AUTH", _ => false),
          res.fold(_.detail.contains("403"), _ => false),
          stand.paths.length == 1
        )
      }
    }
  )
