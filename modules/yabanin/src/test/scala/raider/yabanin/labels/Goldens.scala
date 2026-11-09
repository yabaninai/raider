package raider.yabanin.labels

import zio.json.DeriveJsonDecoder
import zio.json.JsonDecoder

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/** Wire models and loader for the immutable contract goldens under
  * `contracts/yabanin/expected/` (RAI-037 test support). Files are located
  * via `RAIDER_REPO_ROOT` (exported by `modules/yabanin/run-tests.sh`) with a
  * directory walk-up fallback. Goldens are immutable inputs: a missing or
  * undecodable golden is a hard failure, never an empty result.
  */
object Goldens:

  private def readExpected(fileName: String): String =
    val root = sys.env.get("RAIDER_REPO_ROOT").filter(_.nonEmpty).getOrElse {
      var dir   = Option(Paths.get("").toAbsolutePath)
      var found = ""
      while dir.isDefined && found.isEmpty do
        val candidate = dir.get.resolve("contracts/yabanin/expected").resolve(fileName)
        if Files.exists(candidate) then found = dir.get.toString
        else dir = Option(dir.get.getParent)
      found
    }
    if root.isEmpty then
      throw new IllegalStateException(
        s"contracts/yabanin/expected/$fileName not found; run via modules/yabanin/run-tests.sh or set RAIDER_REPO_ROOT"
      )
    val path = Paths.get(root, "contracts", "yabanin", "expected", fileName)
    if !Files.exists(path) then throw new IllegalStateException(s"golden missing at $path")
    else new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def decode[A](fileName: String)(using dec: JsonDecoder[A]): A =
    val text  = readExpected(fileName)
    val block = s"contracts/yabanin/expected/$fileName"
    summon[JsonDecoder[A]].decodeJson(text).fold(
      err => throw new IllegalStateException(s"$block does not decode: $err"),
      identity,
    )

  def pathEscape: PathEscapeGolden = decode("pathescape-golden.json")
  def labels: LabelsGolden         = decode("labels-golden.json")
  def doctor: DoctorGolden         = decode("doctor-golden.json")

  // -------------------------------------------------------------------
  // pathescape-golden.json
  // -------------------------------------------------------------------

  final case class EscapeCase(name: String, input: String, expected: String) derives JsonDecoder
  final case class PathEscapeGolden(schema: String, cases: Vector[EscapeCase]) derives JsonDecoder

  // -------------------------------------------------------------------
  // labels-golden.json
  // -------------------------------------------------------------------

  final case class LimitsGolden(
    max_per_source: Int,
    max_effective: Int,
    max_value_length_bytes: Int,
    reserved_key_prefix: String,
    client_key_max_length: Int,
  ) derives JsonDecoder

  /** A label pair pin; `provenance` is present only in merge-case pins. */
  final case class LabelPair(key: String, value: String, provenance: Option[String]) derives JsonDecoder
  final case class DroppedGolden(key: String, reason: String) derives JsonDecoder
  final case class RenderCase(
    name: String,
    client_labels: Vector[LabelPair],
    expected_header: String,
    expected_accepted: Vector[LabelPair],
    expected_dropped: Vector[DroppedGolden],
  ) derives JsonDecoder
  final case class MergeCase(
    name: String,
    wrapper_labels: Vector[LabelPair],
    client_labels: Vector[LabelPair],
    expected_header: String,
    expected_accepted: Vector[LabelPair],
    expected_dropped: Vector[DroppedGolden],
  ) derives JsonDecoder
  final case class ParseCase(
    name: String,
    raw: String,
    expected_pairs: Option[Vector[LabelPair]],
    expected_error_reason: Option[String],
  ) derives JsonDecoder
  final case class LabelsGolden(
    schema: String,
    limits: LimitsGolden,
    render_cases: Vector[RenderCase],
    merge_cases: Vector[MergeCase],
    parse_cases: Vector[ParseCase],
  ) derives JsonDecoder

  // -------------------------------------------------------------------
  // doctor-golden.json
  // -------------------------------------------------------------------

  final case class DoctorPin(
    expected_models_docs_version: String,
    labels_fixture_raw: String,
    expected_labels_header: String,
  ) derives JsonDecoder
  final case class ModelsExpected(
    classification: String,
    http_status: Int,
    model_ids: Option[Vector[String]],
    docs_version: Option[String],
  ) derives JsonDecoder
  final case class SessionKeysExpected(classification: String, http_status: Int) derives JsonDecoder
  final case class DecisionExpected(value: String, observed_on: String) derives JsonDecoder
  final case class ScenarioExpected(
    models: ModelsExpected,
    session_keys: SessionKeysExpected,
    decision: Option[DecisionExpected],
    fallback_attempted: Boolean,
  ) derives JsonDecoder
  final case class DoctorScenario(name: String, expected: ScenarioExpected) derives JsonDecoder
  final case class DoctorGolden(
    schema: String,
    pin: DoctorPin,
    decision_header: String,
    scenarios: Vector[DoctorScenario],
  ) derives JsonDecoder
