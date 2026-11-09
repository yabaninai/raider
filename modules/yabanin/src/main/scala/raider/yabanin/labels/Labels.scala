package raider.yabanin.labels

import java.nio.charset.StandardCharsets

/** Provenance of a label pair. The Yabanin CI wrapper provides labels through
  * the `YABANIN_LABELS` environment variable; Raider itself provides labels
  * through its own configuration.
  */
enum LabelProvenance:
  case Wrapper
  case Client

  /** Wire spelling used in contract documents. */
  def encode: String = this match
    case Wrapper => "wrapper"
    case Client  => "client"

object LabelProvenance:
  def decode(s: String): Option[LabelProvenance] = s match
    case "wrapper" => Some(Wrapper)
    case "client"  => Some(Client)
    case _         => None

/** A single label pair in canonical (unescaped) form. */
final case class Label(key: String, value: String, provenance: LabelProvenance)

/** Limits enforced during canonicalization and merging. Defaults are pinned
  * in `contracts/yabanin/expected/labels-golden.json`; the contract checks
  * assert that [[Default]] equals the pinned values.
  */
final case class LabelLimits(
  maxPerSource: Int,
  maxEffective: Int,
  maxValueLengthBytes: Int,
  reservedKeyPrefix: String,
  clientKeyMaxLength: Int
)

object LabelLimits:
  val Default: LabelLimits = LabelLimits(
    maxPerSource = 20,
    maxEffective = 32,
    maxValueLengthBytes = 128,
    reservedKeyPrefix = "yabanin.",
    clientKeyMaxLength = 64
  )

/** Why a label pair was rejected. Every rejection is recorded as a visible
  * drop; nothing is silently discarded.
  */
enum DropReason:
  case ReservedKeyPrefix
  case ValueTooLong
  case MalformedKey
  case PerSourceOverflow
  case EffectiveCapOverflow
  case ProtectedWrapperKey
  case DuplicateKey

  /** Wire spelling used in contract documents. */
  def encode: String = this match
    case ReservedKeyPrefix    => "reserved_key_prefix"
    case ValueTooLong         => "value_too_long"
    case MalformedKey         => "malformed_key"
    case PerSourceOverflow    => "per_source_overflow"
    case EffectiveCapOverflow => "effective_cap_overflow"
    case ProtectedWrapperKey  => "protected_wrapper_key"
    case DuplicateKey         => "duplicate_key"

object DropReason:
  def decode(s: String): Option[DropReason] = DropReason.values.find(_.encode == s)

/** A recorded rejection of a label pair. `detail` is optional human-readable
  * context; it must never contain credential material.
  */
final case class DroppedLabel(key: String, reason: DropReason, detail: Option[String] = None)

/** Canonical form of a label set.
  *
  * Invariants:
  *   - `accepted` is sorted lexicographically by key (the render order);
  *   - `dropped` preserves canonicalization/merge processing order;
  *   - `insertionOrder` holds the keys of `accepted` in the order the pairs
  *     were admitted, which [[LabelHeader$.merge]] uses to honor the contract
  *     rule that client labels are appended in insertion order until the
  *     effective cap.
  */
final case class LabelSet(
  accepted: Vector[Label],
  dropped: Vector[DroppedLabel],
  insertionOrder: Vector[String]
):
  /** The `X-Yabanin-Labels` wire value: comma-separated `key=escapedValue`
    * pairs sorted by key, values escaped byte-wise exactly as Go
    * `net/url.PathEscape` does. Empty string when nothing is accepted.
    */
  def renderHeader: String =
    accepted.map(l => s"${l.key}=${PathEscape.escape(l.value)}").mkString(",")

  def label(key: String): Option[Label] = accepted.find(_.key == key)

object LabelSet:
  val empty: LabelSet = LabelSet(Vector.empty, Vector.empty, Vector.empty)

/** Typed failures of the strict canonical header decoder. `reason` carries
  * the wire spelling used in contract documents.
  */
sealed trait ParseError extends Product with Serializable:
  def reason: String
  def message: String

/** Segment without an `=` separator. */
final case class MissingSeparator(position: Int, segment: String) extends ParseError:
  def reason  = "missing_separator"
  def message = s"missing '=' separator at char $position in segment '$segment'"

/** Segment whose key part is empty (`=value`). */
final case class EmptyKey(position: Int) extends ParseError:
  def reason  = "empty_key"
  def message = s"empty key at char $position"

/** Segment whose key violates the key shape. */
final case class MalformedKeyError(position: Int, key: String) extends ParseError:
  def reason  = "malformed_key"
  def message = s"malformed key at char $position: '$key'"

/** Value escaping problem; positions inside [[problem]] are absolute
  * character offsets into the raw header text.
  */
final case class EscapeProblem(problem: PathEscape.DecodeError) extends ParseError:
  def reason: String = problem match
    case _: PathEscape.InvalidEscape    => "invalid_escape"
    case _: PathEscape.InvalidUtf8      => "invalid_utf8"
    case _: PathEscape.NonCanonicalByte => "non_canonical_byte"
  def message = problem.message

/** Canonicalization, merging, rendering, and strict parsing of the
  * `X-Yabanin-Labels` header.
  */
object LabelHeader:

  val HeaderName: String = "X-Yabanin-Labels"

  /** Canonicalizes one source's pairs in insertion order with the contract
    * check order: key shape (`malformed_key`), reserved prefix for client
    * labels (`reserved_key_prefix`), value byte length (`value_too_long`),
    * duplicate key from the same source with first value winning
    * (`duplicate_key`), per-source cap (`per_source_overflow`).
    *
    * The reserved-prefix check is provenance-conditional: the `yabanin.`
    * namespace belongs to wrapper-provided labels (contract §1.2, "wrapper
    * provided namespace"), so wrapper pairs carrying the prefix are admitted,
    * while client pairs carrying it are dropped.
    */
  def canonicalize(
    pairs: Vector[(String, String)],
    provenance: LabelProvenance,
    limits: LabelLimits
  ): LabelSet =
    val accepted = Vector.newBuilder[Label]
    val dropped  = Vector.newBuilder[DroppedLabel]
    val order    = Vector.newBuilder[String]
    val seen     = scala.collection.mutable.Set.empty[String]
    var count    = 0
    pairs.foreach { case (key, value) =>
      if !isKeyShapeValid(key, limits) then dropped += DroppedLabel(key, MalformedKey)
      else if provenance == LabelProvenance.Client && key.startsWith(limits.reservedKeyPrefix) then
        dropped += DroppedLabel(key, ReservedKeyPrefix)
      else if value.getBytes(StandardCharsets.UTF_8).length > limits.maxValueLengthBytes then
        dropped += DroppedLabel(key, ValueTooLong)
      else if seen.contains(key) then dropped += DroppedLabel(key, DuplicateKey)
      else if count >= limits.maxPerSource then dropped += DroppedLabel(key, PerSourceOverflow)
      else
        val label = Label(key, value, provenance)
        accepted += label
        order += key
        seen += key
        count += 1
    }
    LabelSet(accepted.result(), dropped.result(), order.result())

  /** Canonicalizes Raider-side (client) labels. */
  def fromClient(pairs: Vector[(String, String)], limits: LabelLimits): LabelSet =
    canonicalize(pairs, LabelProvenance.Client, limits)

  /** Canonicalizes wrapper-side labels (raw text previously decoded by
    * [[parseRaw]]).
    */
  def fromWrapper(pairs: Vector[(String, String)], limits: LabelLimits): LabelSet =
    canonicalize(pairs, LabelProvenance.Wrapper, limits)

  /** Merges wrapper and client label sets. Wrapper labels are admitted first
    * and are protected: a client label whose key collides with a wrapper key
    * is dropped with `protected_wrapper_key`. Client labels are then appended
    * in insertion order until the effective cap; the excess is dropped with
    * `effective_cap_overflow`.
    */
  def merge(wrapper: LabelSet, client: LabelSet, limits: LabelLimits): LabelSet =
    val protectedKeys = wrapper.accepted.map(_.key).toSet
    val dropped       = Vector.newBuilder[DroppedLabel]
    dropped ++= wrapper.dropped
    val admitted = Vector.newBuilder[Label]
    val order    = Vector.newBuilder[String]
    var total    = wrapper.accepted.length
    client.insertionOrder.foreach { key =>
      client.label(key) match
        case None => // unreachable: insertionOrder only carries admitted keys
        case Some(label) =>
          if protectedKeys.contains(label.key) then
            dropped += DroppedLabel(
              label.key,
              ProtectedWrapperKey,
              Some(s"wrapper-provided label is protected; client value ${label.value}")
            )
          else if total >= limits.maxEffective then dropped += DroppedLabel(label.key, EffectiveCapOverflow)
          else
            admitted += label
            order += label.key
            total += 1
    }
    val all = wrapper.accepted ++ admitted.result()
    LabelSet(all.sortBy(_.key), dropped.result(), wrapper.insertionOrder ++ order.result())

  /** Strictly decodes canonical header text into wrapper-provenance pairs.
    *
    * Canonical text can be split on `,` and on the first `=` of each segment
    * without ambiguity: canonical escaping never emits a raw `,` (segment
    * split is safe), and keys never contain `=` (key shape
    * `[A-Za-z0-9._-]`), so each segment splits at its first `=` even though
    * `=` is in the PathEscape keep set and may appear raw inside values.
    * Keys are plain ASCII by construction and appear raw. The decoder is
    * strict: missing separators, empty keys, malformed keys, and any value
    * escaping/canonicality/UTF-8 problem are typed errors; a trailing empty
    * segment is a `missing_separator` failure, not a silently-ignored
    * artifact. Returns pairs in wire order (no sorting, no deduplication);
    * canonicalization happens in [[canonicalize]].
    */
  def parseRaw(raw: String, limits: LabelLimits): Either[ParseError, Vector[Label]] =
    if raw.isEmpty then Right(Vector.empty)
    else
      val labels  = Vector.newBuilder[Label]
      var failure: Option[ParseError] = None
      val segments = raw.split(",", -1)
      var offset   = 0
      var i        = 0
      while failure.isEmpty && i < segments.length do
        val segment = segments(i)
        val start   = offset
        val eq      = segment.indexOf('=')
        if eq < 0 then failure = Some(MissingSeparator(start, segment))
        else if eq == 0 then failure = Some(EmptyKey(start))
        else
          val key = segment.substring(0, eq)
          if !isKeyShapeValid(key, limits) then failure = Some(MalformedKeyError(start, key))
          else
            PathEscape.unescape(segment.substring(eq + 1)) match
              case Left(problem) => failure = Some(EscapeProblem(shift(problem, start + eq + 1)))
              case Right(value)  => labels += Label(key, value, LabelProvenance.Wrapper)
        offset += segment.length + 1
        i += 1
      failure match
        case Some(e) => Left(e)
        case None    => Right(labels.result())

  /** Key shape guard: 1..`clientKeyMaxLength` characters of
    * `[A-Za-z0-9._-]`.
    */
  def isKeyShapeValid(key: String, limits: LabelLimits): Boolean =
    key.nonEmpty && key.length <= limits.clientKeyMaxLength && key.forall { c =>
      (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
        c == '.' || c == '_' || c == '-'
    }

  private def shift(problem: PathEscape.DecodeError, delta: Int): PathEscape.DecodeError = problem match
    case PathEscape.InvalidEscape(p, d)    => PathEscape.InvalidEscape(p + delta, d)
    case PathEscape.NonCanonicalByte(p, b) => PathEscape.NonCanonicalByte(p + delta, b)
    case PathEscape.InvalidUtf8(p, d)      => PathEscape.InvalidUtf8(p + delta, d)
