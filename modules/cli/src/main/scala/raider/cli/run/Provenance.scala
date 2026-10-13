package raider.cli.run

import raider.core.RaiderError
import zio.json.{DeriveJsonDecoder, JsonDecoder}

import java.nio.file.{Files, Path}
import java.security.MessageDigest

/** RAI-131 (CI adapter batch): TRUST PREFLIGHT — provenance verification of a
  * bundle (and optionally its policy file) BEFORE any dispatch or spend.
  *
  * The CI contract (docs/ci-runtime.md §trust) requires that a bundle running
  * inside an orchestrator job matches its DECLARED provenance: the operator
  * passes `--require-provenance <manifest.json>` with
  * `{"bundle_sha256":"<hex64>","policy_sha256":"<hex64>"?}`; the runner hashes
  * the resolved `--bundle` jar (and the declared policy file when present) and
  * refuses the launch TYPED on any mismatch, missing manifest, malformed
  * manifest, or unreadable file — before admission and before the first model
  * dispatch (RUN-02 discipline: preflight precedes spend).
  * `RaiderError.InputValidation` maps to the preflight-failure exit family
  * (HeadlessRunner.isPreflightFailure), so a mismatched bundle never reaches a
  * provider.
  *
  * Trust discipline: the provenance manifest is DECLARED BY THE OPERATOR (a
  * trusted allowlist input, like `--context`'s `trusted` flag) — it is never
  * derived from the environment. Hashes are hex SHA-256 over the file bytes; no
  * signature verification is claimed (non-goal of this slice).
  */
object Provenance:

  /** Declared provenance manifest (operator-supplied, trusted input). */
  final case class Declared(
      bundle_sha256: String,
      policy_sha256: Option[String]
  )

  given JsonDecoder[Declared] = DeriveJsonDecoder.gen[Declared]

  /** Hex SHA-256 over the file bytes; unreadable files fail typed. */
  def sha256(path: Path): Either[RaiderError, String] =
    try
      val md = MessageDigest.getInstance("SHA-256")
      val in = Files.newInputStream(path)
      try
        val buf = new Array[Byte](8192)
        var n = in.read(buf)
        while n != -1 do
          md.update(buf, 0, n)
          n = in.read(buf)
      finally in.close()
      Right(md.digest().map(b => f"$b%02x").mkString)
    catch
      case e: Exception =>
        Left(
          RaiderError.InputValidation(
            s"provenance: cannot read ${path.getFileName}: ${e.getClass.getSimpleName}"
          )
        )

  /** Verify the resolved bundle (and optional policy file) against the declared
    * manifest. Any failure is typed InputValidation → preflight failure → the
    * launch is refused before spend.
    */
  def verify(
      manifestPath: Path,
      bundleJar: Path,
      policy: Option[Path]
  ): Either[RaiderError, Unit] =
    for
      raw <-
        try Right(Files.readString(manifestPath))
        catch
          case e: Exception =>
            Left(
              RaiderError.InputValidation(
                s"provenance: cannot read manifest ${manifestPath.getFileName}: ${e.getClass.getSimpleName}"
              )
            )
      declared <-
        import zio.json.DecoderOps
        raw.fromJson[Declared] match
          case Left(err) =>
            Left(
              RaiderError.InputValidation(
                s"provenance: malformed manifest: ${err.take(80)}"
              )
            )
          case Right(d) => Right(d)
      _ <- declared.bundle_sha256.trim.toLowerCase match
        case h
            if h.length == 64 && h
              .forall(c => c.isDigit || ('a' to 'f').contains(c)) =>
          Right(())
        case other =>
          Left(
            RaiderError.InputValidation(
              s"provenance: malformed bundle_sha256 (want 64 hex chars): ${other.take(12)}"
            )
          )
      actualBundle <- sha256(bundleJar)
      _ <-
        if actualBundle == declared.bundle_sha256.trim.toLowerCase then
          Right(())
        else
          Left(
            RaiderError.InputValidation(
              s"provenance mismatch: bundle sha256 expected ${declared.bundle_sha256}, actual $actualBundle"
            )
          )
      _ <- (policy, declared.policy_sha256) match
        case (Some(p), Some(expected)) =>
          sha256(p).flatMap { actual =>
            if actual == expected.trim.toLowerCase then Right(())
            else
              Left(
                RaiderError.InputValidation(
                  s"provenance mismatch: policy sha256 expected $expected, actual $actual"
                )
              )
          }
        case _ =>
          Right(()) // nothing declared for the policy → nothing to verify
    yield ()

end Provenance
