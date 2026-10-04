package raider.core

import zio.json._

/** Compiled bundle manifest (RAI-002 remainder; ci-runtime §2–3).
  *
  * A bundle is a compiled artifact carrying named, versioned workflow entries.
  * The CLI selects exactly one entry (`--workflow`); an unknown entry is a
  * typed InputValidation at load time that lists the known names — never a
  * runtime surprise and never a fallback to "some" entry. The manifest is data:
  * loading and validating it dispatches nothing.
  */
object bundle:

  final case class BundleEntry(name: String, mainClass: String, version: Int)

  final case class BundleManifest(
      schemaVersion: Int,
      bundleId: String,
      entries: List[BundleEntry],
      declaredTrustLevel: String
  )

  object BundleEntry:
    given JsonCodec[BundleEntry] = DeriveJsonCodec.gen[BundleEntry]

  object BundleManifest:
    given JsonCodec[BundleManifest] = DeriveJsonCodec.gen[BundleManifest]

    /** Structural validation BEFORE any use (RAI-002 API-02 style). */
    def validate(m: BundleManifest): Either[RaiderError, Unit] =
      if !schemas.supportedSchemaVersions.contains(m.schemaVersion) then
        Left(
          RaiderError.InputValidation(
            s"unsupported schemaVersion ${m.schemaVersion}; " +
              s"supported=${schemas.supportedSchemaVersions.mkString(",")}"
          )
        )
      else if m.bundleId.trim.isEmpty then
        Left(RaiderError.InputValidation("bundleId must be a non-empty string"))
      else if m.declaredTrustLevel.trim.isEmpty then
        Left(
          RaiderError.InputValidation(
            "declaredTrustLevel must be a non-empty string"
          )
        )
      else if m.entries.isEmpty then
        Left(
          RaiderError.InputValidation(
            "bundle must declare at least one workflow entry"
          )
        )
      else if m.entries.exists(_.name.trim.isEmpty) then
        Left(RaiderError.InputValidation("entry name must be non-empty"))
      else if m.entries.exists(_.mainClass.trim.isEmpty) then
        Left(RaiderError.InputValidation("entry mainClass must be non-empty"))
      else if m.entries.exists(_.version < 1) then
        Left(RaiderError.InputValidation("entry version must be >= 1"))
      else
        val dups = m.entries
          .map(_.name)
          .groupBy(identity)
          .collect { case (n, ns) if ns.size > 1 => n }
          .toList
          .sorted
        if dups.nonEmpty then
          Left(
            RaiderError.InputValidation(
              s"duplicate workflow names: ${dups.mkString(", ")}"
            )
          )
        else Right(())

    /** Explicit entry selection; unknown names fail with the known list. */
    def entry(
        m: BundleManifest,
        name: String
    ): Either[RaiderError, BundleEntry] =
      validate(m).flatMap { _ =>
        m.entries.find(_.name == name) match
          case Some(e) => Right(e)
          case None =>
            Left(
              RaiderError.InputValidation(
                s"unknown workflow '$name'; known: ${m.entries.map(_.name).sorted.mkString(", ")}"
              )
            )
      }

end bundle

/** The frozen v1 bundle SPI (RAI-022): a compiled bundle entry class must be
  * public, have a no-arg constructor and implement this trait. Programs are
  * LAZY descriptors — instantiating the class dispatches nothing (API-03);
  * execution happens only through the headless runner's single admission.
  * Adding methods = new schemaVersion, never a silent shape change.
  */
trait BundleWorkflow:
  def name: String
  def program: Program[String, String]
