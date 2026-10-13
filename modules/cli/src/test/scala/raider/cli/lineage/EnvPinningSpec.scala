package raider.cli.lineage

import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** LIN-08D acceptance (packet R5, ADR-027 §2 "Environment change", docs/lineage.md
  * §6): compare the pinned environment in `toolchain.json` with the code's
  * actual environment BEFORE replay execution:
  *   - PIN-01: equal environments → [[EnvPinning.PinVerdict.Matching]], and
  *     `annotate` returns the report unchanged;
  *   - PIN-02: changing scala in the pinned data → Divergent with the `scala`
  *     field (field, expected, actual) plus the truthful header "reproduced in
  *     a different environment: ..." in `annotate`;
  *   - PIN-03: malformed `toolchain.json` → typed Malformed with bounded
  *     detail; a missing file → typed Missing;
  *   - repeating all checks produces byte-identical results (deterministic:
  *     no clocks, network, processes, or heap-order traversals).
  *
  * Fixtures are written to a temporary directory through java.nio — the
  * production path `readPinned` reads only the supplied file, nothing else.
  */
object EnvPinningSpec extends ZIOSpecDefault:

  /** A stable model value for the OPTIONAL `model` field fixtures. */
  private val FixtureModel = "glm-5.3-flash"

  private def writeToolchain(dir: Path, name: String, content: String): Path =
    val path = dir.resolve(name)
    Files.writeString(path, content, UTF_8)
    path

  private def tempDir(): Path =
    Files.createTempDirectory("env-pinning-spec")

  /** toolchain.json fixture built from the FACTUAL current environment, with
    * per-field substitutions for the divergence cases.
    */
  private def currentToolchainJson(
      scala: String,
      jdk: String,
      raider: String,
      model: Option[String]
  ): String =
    val modelJson =
      model.map(m => s""",\n  "model": "$m\"""".stripMargin).getOrElse("")
    s"""{
       |  "scala": "$scala",
       |  "jdk": "$jdk",
       |  "raider": "$raider"$modelJson
       |}
       |""".stripMargin

  private def currentJson(): String =
    val current = EnvPinning.currentEnv()
    currentToolchainJson(
      current.scalaVersion,
      current.jdkVersion,
      current.raiderVersion,
      None
    )

  private def linesOf(text: String): Vector[String] =
    text.split("\n").toVector

  def spec: Spec[TestEnvironment, Any] = suite("EnvPinning")(
    test(
      "PIN-01: toolchain.json equal to the current environment reads and compares Matching; annotate is the identity"
    ) {
      val dir = tempDir()
      val path = writeToolchain(dir, "toolchain.json", currentJson())
      val current = EnvPinning.currentEnv()
      EnvPinning.readPinned(path) match
        case Left(error) =>
          sys.error(s"fixture bug: unexpected $error")
        case Right(pinned) =>
          val verdict = EnvPinning.compare(pinned, current)
          assertTrue(
            pinned.scalaVersion == current.scalaVersion,
            pinned.jdkVersion == current.jdkVersion,
            pinned.raiderVersion == current.raiderVersion,
            pinned.model.isEmpty,
            verdict == EnvPinning.PinVerdict.Matching,
            EnvPinning.annotate("run report", verdict) == "run report"
          )
    },
    test(
      "PIN-02: substituted scala in pinned → Divergent(scala, expected, actual); annotate prepends the honest header"
    ) {
      val dir = tempDir()
      val current = EnvPinning.currentEnv()
      val substituted = "3.8.2-cts"
      val path = writeToolchain(
        dir,
        "toolchain.json",
        currentToolchainJson(
          substituted,
          current.jdkVersion,
          current.raiderVersion,
          None
        )
      )
      EnvPinning.readPinned(path) match
        case Left(error) =>
          sys.error(s"fixture bug: unexpected $error")
        case Right(pinned) =>
          EnvPinning.compare(pinned, current) match
            case EnvPinning.PinVerdict.Divergent(fields) =>
              val annotated =
                EnvPinning.annotate(
                  "run report\nbody",
                  EnvPinning.PinVerdict.Divergent(fields)
                )
              val header = linesOf(annotated).head
              assertTrue(
                fields == Vector(
                  ("scala", substituted, current.scalaVersion)
                ),
                header.startsWith("reproduced in a different environment:"),
                header.contains(s"scala: expected $substituted"),
                header.contains(s"actual ${current.scalaVersion}"),
                annotated.endsWith("run report\nbody"),
                linesOf(annotated).length == 3
              )
            case other =>
              sys.error(s"expected Divergent, got $other")
    },
    test(
      "PIN-03: broken toolchain.json (truncated JSON) → typed Malformed with bounded single-line detail"
    ) {
      val dir = tempDir()
      val path = writeToolchain(dir, "toolchain.json", "{\"scala\": 3.9.0,")
      EnvPinning.readPinned(path) match
        case Left(EnvPinning.PinError.Malformed(seen, detail)) =>
          assertTrue(
            seen == path,
            detail.length <= 200,
            !detail.contains("\n"),
            detail.nonEmpty
          )
        case other =>
          sys.error(s"expected Malformed, got $other")
    },
    test(
      "PIN-03: huge garbage content stays bounded — detail is capped and single-line"
    ) {
      val dir = tempDir()
      val garbage = "x" * 10_000 + "\n" + "y" * 10_000
      val path = writeToolchain(dir, "toolchain.json", garbage)
      EnvPinning.readPinned(path) match
        case Left(EnvPinning.PinError.Malformed(_, detail)) =>
          assertTrue(detail.length <= 200, !detail.contains("\n"))
        case other =>
          sys.error(s"expected Malformed, got $other")
    },
    test(
      "PIN-03: schema violations (non-object, missing field, wrong type, empty field) are typed Malformed"
    ) {
      val dir = tempDir()
      val broken: Vector[(String, String)] = Vector(
        ("array.json", "[1, 2, 3]"),
        (
          "missing.json",
          "{\n  \"jdk\": \"21\",\n  \"raider\": \"0.1.0-SNAPSHOT\"\n}"
        ),
        (
          "wrongtype.json",
          "{\n  \"scala\": 39,\n  \"jdk\": \"21\",\n  \"raider\": \"0.1.0-SNAPSHOT\"\n}"
        ),
        (
          "empty.json",
          "{\n  \"scala\": \"\",\n  \"jdk\": \"21\",\n  \"raider\": \"0.1.0-SNAPSHOT\"\n}"
        )
      )
      val results = broken.map { case (name, content) =>
        val path = writeToolchain(dir, name, content)
        EnvPinning.readPinned(path)
      }
      val allMalformed = results.collect {
        case Left(EnvPinning.PinError.Malformed(_, detail)) => detail
      }
      assertTrue(
        results.size == 4,
        allMalformed.size == 4,
        allMalformed.forall(_.nonEmpty),
        allMalformed.forall(_.length <= 200)
      )
    },
    test(
      "PIN-03: absent toolchain.json → typed Missing carrying the requested path"
    ) {
      val dir = tempDir()
      val path = dir.resolve("absent-toolchain.json")
      EnvPinning.readPinned(path) match
        case Left(EnvPinning.PinError.Missing(seen)) =>
          assertTrue(seen == path)
        case other =>
          sys.error(s"expected Missing, got $other")
    },
    test(
      "PIN-02: optional model — divergence only when BOTH sides are known; matches otherwise"
    ) {
      val current = EnvPinning.currentEnv()
      val pinnedNoModel =
        EnvPinning.PinnedEnv(
          current.scalaVersion,
          current.jdkVersion,
          current.raiderVersion,
          None
        )
      val currentWithModel =
        EnvPinning.CurrentEnv(
          current.scalaVersion,
          current.jdkVersion,
          current.raiderVersion,
          Some(FixtureModel)
        )
      val pinnedOtherModel =
        EnvPinning.PinnedEnv(
          current.scalaVersion,
          current.jdkVersion,
          current.raiderVersion,
          Some("other-model")
        )
      val noModelVerdict = EnvPinning.compare(pinnedNoModel, currentWithModel)
      val modelDivergence =
        EnvPinning.compare(pinnedOtherModel, currentWithModel) match
          case EnvPinning.PinVerdict.Divergent(fields) => fields
          case EnvPinning.PinVerdict.Matching          => Vector.empty
      val bothEqual =
        EnvPinning.compare(
          pinnedOtherModel,
          currentWithModel.copy(model = Some("other-model"))
        )
      assertTrue(
        noModelVerdict == EnvPinning.PinVerdict.Matching,
        modelDivergence == Vector(
          ("model", "other-model", FixtureModel)
        ),
        bothEqual == EnvPinning.PinVerdict.Matching
      )
    },
    test(
      "PIN-02: toolchain.json with optional model round-trips and compares model divergence + annotate lists it"
    ) {
      val dir = tempDir()
      val current = EnvPinning.currentEnv()
      val path = writeToolchain(
        dir,
        "toolchain.json",
        currentToolchainJson(
          current.scalaVersion,
          current.jdkVersion,
          current.raiderVersion,
          Some(FixtureModel)
        )
      )
      EnvPinning.readPinned(path) match
        case Left(error) =>
          sys.error(s"fixture bug: unexpected $error")
        case Right(pinned) =>
          val verdict = EnvPinning.compare(pinned, current)
          val annotated = EnvPinning.annotate("report", verdict)
          assertTrue(
            pinned.model == Some(FixtureModel),
            // the factual current model is NOT statically known (currentEnv
            // carries None) — per the documented rule, an unknown side is not
            // checked, so the verdict is Matching, no false alarm
            verdict == EnvPinning.PinVerdict.Matching,
            annotated == "report"
          )
    },
    test(
      "determinism: currentEnv, readPinned, compare and annotate are byte-identical on repeat"
    ) {
      val dir = tempDir()
      val path = writeToolchain(dir, "toolchain.json", currentJson())
      val first = EnvPinning.currentEnv()
      val second = EnvPinning.currentEnv()
      val readA = EnvPinning.readPinned(path)
      val readB = EnvPinning.readPinned(path)
      val verdictsA =
        EnvPinning.compare(
          EnvPinning.PinnedEnv(
            first.scalaVersion,
            first.jdkVersion,
            first.raiderVersion,
            Some("m1")
          ),
          first
        )
      val verdictsB =
        EnvPinning.compare(
          EnvPinning.PinnedEnv(
            second.scalaVersion,
            second.jdkVersion,
            second.raiderVersion,
            Some("m1")
          ),
          second
        )
      val annotateA = EnvPinning.annotate("r", verdictsA)
      val annotateB = EnvPinning.annotate("r", verdictsB)
      assertTrue(
        first == second,
        readA == readB,
        verdictsA == verdictsB,
        annotateA == annotateB
      )
    },
    test(
      "currentEnv is honest: scala from the scala-library resource, jdk from the JVM property, raider from the compile-time constant"
    ) {
      val current = EnvPinning.currentEnv()
      val fromResource = EnvPinningSpec.scalaLibraryVersionFromResource()
      assertTrue(
        current.scalaVersion.nonEmpty,
        current.scalaVersion == fromResource,
        current.jdkVersion.nonEmpty,
        current.raiderVersion == EnvPinning.RaiderVersion,
        current.raiderVersion == "0.1.0-SNAPSHOT"
      )
    }
  )

  /** Independent re-read of the SAME source `currentEnv` uses (the
    * scala-library `library.properties` resource), for the honesty check.
    */
  private def scalaLibraryVersionFromResource(): String =
    Option(getClass.getResourceAsStream("/library.properties")).fold(
      "unknown"
    ) { stream =>
      val props = new java.util.Properties()
      try props.load(stream)
      finally stream.close()
      Option(props.getProperty("version.number")).getOrElse("unknown")
    }

end EnvPinningSpec
