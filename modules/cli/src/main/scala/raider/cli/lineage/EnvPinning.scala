package raider.cli.lineage

import zio.json.JsonDecoder
import zio.json.ast.Json

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, NoSuchFileException, Path}

/** Pinned-environment verification for lineage replay (LIN-08D, packet R5,
  * ADR-027 §2 «Смена окружения: предупредить + попытаться; сверка pinned-версий
  * (toolchain.json)», docs/lineage.md §6): the pinned toolchain is compared
  * with the FACTUAL environment of the code BEFORE a replay is attempted; a
  * divergence becomes a visible warning + the annotation «reproduced in a
  * different environment», which the diff report must carry — hiding a pinning
  * divergence is not allowed.
  *
  * Pinned side (`toolchain.json`, docs/lineage.md §6: toolchain Scala/JDK,
  * Raider version, model):
  * {{{
  * {
  *   "scala": "3.9.0",
  *   "jdk": "21.0.2",
  *   "raider": "0.1.0-SNAPSHOT",
  *   "model": "glm-5.3-flash"   // optional
  * }
  * }}}
  *
  * No network and no process execution on any path here (packet R5): the
  * factual side reads compile/runtime constants of THIS JVM only:
  *   - scala — the `version.number` property of the `library.properties`
  *     RESOURCE of the scala-library artifact on the classpath (the
  *     BuildInfo-like source the packet names); no subprocess;
  *   - jdk — the `java.version` JVM system property (the running JVM itself);
  *   - raider — the compile-time constant [[EnvPinning.RaiderVersion]]
  *     mirroring `ThisBuild / version` in build.sbt (no BuildInfo plugin is
  *     registered in the build and build.sbt is coordinator-owned, so the
  *     constant documents its source instead of pretending automation);
  *   - model — NOT statically knowable from code, so [[CurrentEnv.model]] is
  *     `None`; `compare` checks a field only when BOTH sides are known (a
  *     missing side is never reported as a divergence — no false alarms, and
  *     honestly documented instead of guessed).
  *
  * Determinism (packet acceptance «повтор всех — идентичен»): every function is
  * a pure function of its inputs plus the stable JVM constants above — no wall
  * clock, no randomness, no heap-order iteration ([[compare]] walks the fixed
  * field order scala, jdk, raider, model), so repeated calls are
  * byte-identical.
  */
object EnvPinning:

  /** Version of THIS Raider build — compile-time constant mirroring
    * `ThisBuild / version` (build.sbt). Documented source of truth: build.sbt
    * (the constant must be updated together with it; there is no BuildInfo
    * plugin in the pinned build).
    */
  final val RaiderVersion: String = "0.1.0-SNAPSHOT"

  private val UnknownVersion = "unknown"

  /** `detail` in [[PinError.Malformed]] never exceeds this bound and is always
    * single-line — a broken toolchain.json must not flood the report (the
    * `argsSummary` redaction convention of the event layer).
    */
  private val MaxDetailLength = 200

  private val HeaderPrefix = "reproduced in a different environment: "

  /** The pinned environment read from `toolchain.json` (see the object scaladoc
    * for the exact schema). Plain data.
    */
  final case class PinnedEnv(
      scalaVersion: String,
      jdkVersion: String,
      raiderVersion: String,
      model: Option[String]
  )

  /** The factual environment of the running code — see the object scaladoc for
    * the per-field source and what is honestly unknown (`model`).
    */
  final case class CurrentEnv(
      scalaVersion: String,
      jdkVersion: String,
      raiderVersion: String,
      model: Option[String]
  )

  /** Typed pinning errors (the packet's CLOSED list — no third case):
    *   - [[Missing]] — the requested toolchain.json does not exist;
    *   - [[Malformed]] — the file exists but cannot yield a pinned environment:
    *     unreadable, invalid JSON or schema violation. `detail` is bounded (≤
    *     [[MaxDetailLength]] chars, single line); read/IO failures degrade to
    *     Malformed for the same reason — the typed list is closed by the card.
    */
  sealed trait PinError extends Product with Serializable

  object PinError:
    final case class Missing(path: Path) extends PinError
    final case class Malformed(path: Path, detail: String) extends PinError

  /** The pinning verdict: [[Matching]] (all checked fields agree) or
    * [[Divergent]] with the diverging fields in FIXED order — each triple is
    * `(field, expected, actual)` where expected is the pinned value and actual
    * is the factual one.
    */
  sealed trait PinVerdict extends Product with Serializable

  object PinVerdict:
    case object Matching extends PinVerdict

    final case class Divergent(fields: Vector[(String, String, String)])
        extends PinVerdict

  /** Reads the pinned environment from the toolchain.json at `path` — typed
    * [[PinError]] on absence/malformation, never throws for content problems.
    */
  def readPinned(path: Path): Either[PinError, PinnedEnv] =
    try
      val content = new String(Files.readAllBytes(path), UTF_8)
      summon[JsonDecoder[Json]].decodeJson(content) match
        case Left(reason) => Left(PinError.Malformed(path, bounded(reason)))
        case Right(json)  => decodeEnv(path, json)
    catch
      case _: NoSuchFileException => Left(PinError.Missing(path))
      case error: java.io.IOException =>
        Left(
          PinError.Malformed(
            path,
            bounded(s"unreadable: ${error.getClass.getSimpleName}")
          )
        )

  /** The factual environment of the running code — see the object scaladoc.
    * Never throws: unknown sources degrade to the `unknown` marker, which
    * `compare` treats as a checkable string value.
    */
  def currentEnv(): CurrentEnv =
    CurrentEnv(
      scalaVersion = scalaLibraryVersion(),
      jdkVersion = System.getProperty("java.version", UnknownVersion),
      raiderVersion = RaiderVersion,
      model = None
    )

  /** Compares the pinned environment against the factual one: scala, jdk and
    * raider are ALWAYS checked; model only when BOTH sides are known (see the
    * object scaladoc). The divergence order is fixed: scala, jdk, raider, model
    * — repeated comparisons of the same inputs are identical.
    */
  def compare(pinned: PinnedEnv, current: CurrentEnv): PinVerdict =
    val fields = Vector.newBuilder[(String, String, String)]
    def check(name: String, expected: String, actual: String): Unit =
      if expected != actual then fields.addOne((name, expected, actual))
    check("scala", pinned.scalaVersion, current.scalaVersion)
    check("jdk", pinned.jdkVersion, current.jdkVersion)
    check("raider", pinned.raiderVersion, current.raiderVersion)
    (pinned.model, current.model) match
      case (Some(expected), Some(actual)) => check("model", expected, actual)
      case _ => () // a side is unknown — not checked, not a divergence
    val divergences = fields.result()
    if divergences.isEmpty then PinVerdict.Matching
    else PinVerdict.Divergent(divergences)

  /** Prepends the honest header to a replay report when the environments
    * diverge: the first line is «reproduced in a different environment:»
    * followed by every diverging field (expected vs actual), the report text
    * follows unchanged. [[PinVerdict.Matching]] returns the report as-is.
    * Deterministic: byte-identical output for identical inputs.
    */
  def annotate(report: String, verdict: PinVerdict): String =
    verdict match
      case PinVerdict.Matching => report
      case PinVerdict.Divergent(fields) =>
        val rendered = fields
          .map { case (name, expected, actual) =>
            s"$name: expected $expected, actual $actual"
          }
          .mkString("; ")
        s"$HeaderPrefix$rendered\n$report"

  // ------------------------------------------------------------------ private

  /** The pinned-environment schema check over the parsed JSON: scala/jdk/
    * raider are required non-empty strings, model is an optional non-empty
    * string, the top-level value must be a JSON object. Every violation is a
    * typed bounded Malformed.
    */
  private def decodeEnv(path: Path, json: Json): Either[PinError, PinnedEnv] =
    json match
      case obj: Json.Obj =>
        (for
          scalaVersion <- requiredString(obj, "scala")
          jdkVersion <- requiredString(obj, "jdk")
          raiderVersion <- requiredString(obj, "raider")
          model <- optionalString(obj, "model")
        yield PinnedEnv(scalaVersion, jdkVersion, raiderVersion, model)).left
          .map(reason => PinError.Malformed(path, bounded(reason)))
      case _ =>
        Left(
          PinError.Malformed(
            path,
            bounded("top-level value must be a JSON object")
          )
        )

  private def member(obj: Json.Obj, key: String): Option[Json] =
    obj.fields.collectFirst { case (`key`, value) => value }

  private def requiredString(
      obj: Json.Obj,
      key: String
  ): Either[String, String] =
    member(obj, key) match
      case Some(Json.Str(value)) =>
        if value.isEmpty then Left(s"field '$key' must be non-empty")
        else Right(value)
      case Some(_) => Left(s"field '$key' must be a JSON string")
      case None    => Left(s"missing required field '$key'")

  private def optionalString(
      obj: Json.Obj,
      key: String
  ): Either[String, Option[String]] =
    member(obj, key) match
      case Some(Json.Str(value)) =>
        if value.isEmpty then Left(s"field '$key' must be non-empty")
        else Right(Some(value))
      case Some(_) => Left(s"field '$key' must be a JSON string")
      case None    => Right(None)

  /** The scala-library version from the `library.properties` resource of the
    * scala-library artifact on the classpath (key `version.number` — the same
    * source `scala.util.Properties.versionNumberString` reads); no subprocess,
    * no network. Absent/broken resource → `unknown`.
    */
  private def scalaLibraryVersion(): String =
    Option(getClass.getResourceAsStream("/library.properties")).fold(
      UnknownVersion
    ) { stream =>
      val props = new java.util.Properties()
      try
        props.load(stream)
        Option(props.getProperty("version.number")).getOrElse(UnknownVersion)
      catch case _: java.io.IOException => UnknownVersion
      finally stream.close()
    }

  /** Single-line, length-capped detail: control characters (including newlines)
    * become spaces, then the value is truncated — bounded honestly, never
    * silently dropped (Malformed still carries the type and the path).
    */
  private def bounded(detail: String): String =
    val singleLine = detail.replaceAll("[\\p{Cntrl}]", " ").trim
    if singleLine.length <= MaxDetailLength then singleLine
    else singleLine.take(MaxDetailLength)

end EnvPinning
