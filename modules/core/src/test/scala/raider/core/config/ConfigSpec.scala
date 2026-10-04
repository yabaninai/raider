package raider.core.config

import raider.core.RaiderError
import zio.ZIO
import zio.test.*

/** RAI-005.a: CFG-01 resolution without service calls; CFG-02 typed negatives
  * with redacted diagnostics; CFG-03 immutable snapshot + narrowing overrides.
  */
object ConfigSpec extends ZIOSpecDefault:

  private def profile(
      name: String,
      wire: WireKind = WireKind.OpenAICompatible,
      url: String = "https://api.example.com/v1",
      prefix: String = "/v1",
      envVar: String = "TEST_API_KEY",
      model: String = "test-model",
      priceKnown: Boolean = true
  ): ProviderProfile =
    ProviderProfile(
      name,
      wire,
      Endpoint.parse(url).toOption.get,
      prefix,
      CredentialRef.parse(envVar).toOption.get,
      model,
      priceKnown
    )

  private def wire(
      profiles: List[ProviderProfile],
      roles: Map[String, RoleBinding],
      ceilings: Ceilings = RaiderConfig.defaultCeilings
  ): ConfigWire = ConfigWire(1, roles, profiles, ceilings)

  def spec = suite("Provider config RAI-005.a")(
    suite("CFG-01: mixed roles resolve without service calls")(
      test(
        "three profiles, mixed roles, API prefix preserved, bounded env resolution"
      ) {
        val profiles = List(
          profile("openai", url = "https://api.openai.example/v1"),
          profile(
            "anthropic",
            wire = WireKind.AnthropicCompatible,
            url = "https://api.anthropic.example/v1"
          ),
          profile("local", url = "http://localhost:8081/v1")
        )
        val cfg = RaiderConfig.build(
          wire(
            profiles,
            Map(
              "primary" -> RoleBinding("openai"),
              "coder" -> RoleBinding(
                "local",
                modelOverride = Some("local-coder")
              ),
              "reviewer" -> RoleBinding(
                "anthropic",
                requiredOption = Some("tools")
              )
            )
          )
        )
        val resolved = cfg.flatMap(
          credentials.resolve(
            _,
            env = Map("TEST_API_KEY" -> "super-secret-value")
          )
        )
        val local = cfg.toOption.flatMap(_.profileForRole("coder"))
        for _ <- ZIO.unit
        yield assertTrue(
          cfg.isRight,
          resolved.isRight,
          cfg.toOption.get.roles.size == 3,
          // API prefix is preserved as configured (not rewritten)
          cfg.toOption.get.profiles("openai").apiPrefix == "/v1",
          local.map(_.model).contains("local-coder"), // model override honored
          cfg.toOption.get
            .profileForRole("primary")
            .map(_.wire)
            .contains(WireKind.OpenAICompatible)
        )
      }
    ),
    suite("CFG-02: typed negatives, redacted diagnostics")(
      test("missing role profile / missing env key / duplicate names") {
        val missingRole = RaiderConfig.build(
          wire(
            profiles = List(profile("openai")),
            roles = Map("primary" -> RoleBinding("ghost"))
          )
        )
        val duplicates = RaiderConfig.build(
          wire(
            profiles = List(profile("a"), profile("a")),
            roles = Map("primary" -> RoleBinding("a"))
          )
        )
        val missingKey = RaiderConfig
          .build(
            wire(
              profiles = List(profile("openai")),
              roles = Map("primary" -> RoleBinding("openai"))
            )
          )
          .flatMap(credentials.resolve(_, env = Map.empty)) // key NOT set
        for _ <- ZIO.unit
        yield assertTrue(
          missingRole.left.toOption
            .map(_.detail)
            .exists(_.contains("references missing profile 'ghost'")),
          duplicates.left.toOption
            .map(_.detail)
            .exists(_.contains("duplicate profile names: a")),
          missingKey.left.toOption.map(_.code).contains("RA-AUTH")
        )
      },
      test("insecure remote URL and invalid caps are Configuration errors") {
        // build the wire the way a JSON codec would: the smart constructor is
        // not in the path, so build() must catch the insecure endpoint itself
        val insecureProfile = ProviderProfile(
          "bad",
          WireKind.OpenAICompatible,
          Endpoint("http://api.example.com/v1"),
          "/v1",
          CredentialRef.parse("TEST_API_KEY").toOption.get,
          "m",
          true
        )
        val insecure = RaiderConfig.build(
          ConfigWire(
            1,
            Map("primary" -> RoleBinding("bad")),
            List(insecureProfile),
            RaiderConfig.defaultCeilings
          )
        )
        val negSteps = RaiderConfig.build(
          wire(
            List(profile("a")),
            Map("primary" -> RoleBinding("a")),
            ceilings = RaiderConfig.defaultCeilings.copy(maxSteps = 0)
          )
        )
        val negCap = RaiderConfig.build(
          wire(
            List(profile("a")),
            Map("primary" -> RoleBinding("a")),
            ceilings =
              RaiderConfig.defaultCeilings.copy(hardCapMicroUsd = Some(-5L))
          )
        )
        for _ <- ZIO.unit
        yield assertTrue(
          insecure.left.toOption.map(_.code).contains("RA-CFG"),
          insecure.left.toOption
            .map(_.detail)
            .exists(_.contains("insecure remote endpoint")),
          negSteps.left.toOption.map(_.detail).exists(_.contains("maxSteps")),
          negCap.left.toOption
            .map(_.detail)
            .exists(_.contains("hardCapMicroUsd"))
        )
      },
      test("unknown required option is refused before dispatch") {
        val cfg = RaiderConfig.build(
          wire(
            List(profile("a")),
            Map(
              "primary" -> RoleBinding("a", requiredOption = Some("telepathy"))
            )
          )
        )
        for _ <- ZIO.unit
        yield assertTrue(
          cfg.left.toOption
            .map(_.detail)
            .exists(_.contains("unknown required option 'telepathy'"))
        )
      },
      test("diagnostics never contain the secret value") {
        val resolved = RaiderConfig
          .build(
            wire(
              List(profile("openai")),
              Map("primary" -> RoleBinding("openai"))
            )
          )
          .flatMap(
            credentials
              .resolve(_, env = Map("TEST_API_KEY" -> "super-secret-value"))
          )
        val text =
          resolved.toString + resolved.fold(_.toString, _.values.mkString)
        for _ <- ZIO.unit
        yield assertTrue(
          !text.contains("super-secret-value"),
          text.contains("<redacted>")
        )
      }
    ),
    suite("CFG-03: immutable snapshot; overrides only narrow")(
      test("reload builds a new instance; the running snapshot is unchanged") {
        val original = RaiderConfig
          .build(
            wire(
              List(profile("a")),
              Map("primary" -> RoleBinding("a")),
              ceilings = Ceilings(8, 1024, 2, Some(1000L))
            )
          )
          .toOption
          .get
        val reloaded = RaiderConfig
          .build(
            wire(
              List(profile("a")),
              Map("primary" -> RoleBinding("a")),
              ceilings = Ceilings(4, 512, 1, Some(500L))
            )
          )
          .toOption
          .get
        for _ <- ZIO.unit
        yield assertTrue(
          original.ceilings.maxSteps == 8, // running snapshot untouched by reload
          reloaded.ceilings.maxSteps == 4
        )
      },
      test("narrowing override succeeds; widening override is refused") {
        val base = Ceilings(8, 1024, 2, Some(1000L))
        val narrower = Ceilings(4, 512, 1, Some(250L))
        val wider = Ceilings(16, 2048, 4, Some(5000L))
        for _ <- ZIO.unit
        yield assertTrue(
          base.narrowed(narrower).isRight,
          base.narrowed(narrower).toOption.get.maxSteps == 4,
          base.narrowed(narrower).toOption.get.hardCapMicroUsd.contains(250L),
          base
            .narrowed(wider)
            .left
            .toOption
            .map(_.detail)
            .exists(_.contains("only narrow"))
        )
      },
      test("decode validates the envelope version before the payload") {
        val bad = RaiderConfig.decode(
          """{"schemaVersion":99,"roles":{},"profiles":[],
            |"ceilings":{"maxSteps":1,"maxOutputTokens":1,"maxConcurrentModelCalls":1}}""".stripMargin
        )
        val malformed =
          RaiderConfig.decode("""{"schemaVersion":1,"roles":"nope"}""")
        for _ <- ZIO.unit
        yield assertTrue(
          bad.left.toOption
            .map(_.detail)
            .exists(_.contains("unsupported schemaVersion")),
          malformed.isLeft
        )
      }
    )
  )
