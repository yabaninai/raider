package raider.compat.pi

import java.security.MessageDigest

/** Minimal sha256 helper for corpus pins and snapshot ids (card RAI-032). */
object Hashing:

  /** Lowercase hex sha256 over the given bytes. */
  def sha256Hex(bytes: Array[Byte]): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(bytes).map(b => f"${b & 0xff}%02x").mkString
