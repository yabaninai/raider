package raider.evalexport

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** SHA-256 helpers for export digests (RAI-040): tool-args digests, artifact
  * hashes, verifier-evidence hashes, section-integrity hashes and the
  * reconciliation append chain.
  *
  * Same convention as the rest of the repository: JDK
  * `java.security.MessageDigest`, lowercase hex, exactly 64 characters (see
  * modules/core provenance hashing, the lineage GraphHash hex encoding and
  * modules/compat-pi Hashing). No new dependencies, no second hashing scheme.
  */
object Digests:

  /** Lowercase hex sha256 over the given bytes. */
  def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /** Lowercase hex sha256 over the UTF-8 bytes of the text. */
  def sha256HexUtf8(text: String): String =
    sha256Hex(text.getBytes(UTF_8))

  /** sha256 of the empty byte string: the anchor value of the first record of
    * the reconciliation append chain.
    */
  lazy val emptySha256: String = sha256Hex(Array.emptyByteArray)

  /** True iff `s` is a lowercase 64-character hex sha256. */
  def isSha256Hex(s: String): Boolean =
    s.length == 64 && s.forall(c => c.isDigit || ('a' <= c && c <= 'f'))
