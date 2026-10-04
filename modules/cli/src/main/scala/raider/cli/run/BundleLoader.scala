package raider.cli.run

import raider.core.*
import zio.json.{DecoderOps, DeriveJsonDecoder}
import raider.core.bundle.BundleManifest

import java.nio.file.Path
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*

/** Bundle loader (RAI-022 slice): reads the manifest from
  * `META-INF/raider-bundle.json` inside the jar, validates it, then
  * instantiates every entry's mainClass through an isolated URLClassLoader
  * (parent = the runner's own loader, which carries core).
  *
  * NOTE (slice honesty): parent delegation means fixture classes already on the
  * runner's classpath may be satisfied from there; strict isolation
  * (child-first + sealed parent) is a security-profile obligation. Loading
  * instantiates classes but dispatches nothing (programs are lazy, API-03).
  */
object BundleLoader:

  val ManifestEntry: String = "META-INF/raider-bundle.json"

  /** Read + validate the manifest from the jar (preflight step 1). */
  def readManifest(jar: Path): Either[RaiderError, BundleManifest] =
    try
      val jarFile = new JarFile(jar.toFile)
      try
        // JarFile.getEntry returns null for missing entries — wrapped in Option
        // (null-free code rule)
        Option(jarFile.getEntry(ManifestEntry)) match
          case None =>
            Left(
              RaiderError.InputValidation(
                s"bundle has no $ManifestEntry (not a raider bundle)"
              )
            )
          case Some(entry) =>
            val text =
              new String(jarFile.getInputStream(entry).readAllBytes(), UTF_8)
            text.fromJson[BundleManifest] match
              case Left(err) =>
                Left(
                  RaiderError.InputValidation(
                    s"unparsable bundle manifest: ${err.take(140)}"
                  )
                )
              case Right(m) =>
                BundleManifest.validate(m).map(_ => m)
      finally jarFile.close()
    catch
      case e: java.util.zip.ZipException =>
        Left(
          RaiderError.InputValidation(s"not a jar: ${e.getMessage.take(80)}")
        )
      case e: Exception =>
        Left(
          RaiderError.InputValidation(
            s"bundle read failure: ${e.getClass.getSimpleName}"
          )
        )

  /** Instantiate every manifest entry; names must match the manifest. */
  def load(
      jar: Path,
      manifest: BundleManifest
  ): Either[RaiderError, Map[String, Program[String, String]]] =
    try
      val loader = new java.net.URLClassLoader(
        Array(jar.toUri.toURL),
        getClass.getClassLoader
      )
      val loaded
          : Vector[Either[RaiderError, (String, Program[String, String])]] =
        manifest.entries.map { e =>
          val cls = Class.forName(e.mainClass, true, loader)
          cls.getDeclaredConstructor().newInstance() match
            case wf: BundleWorkflow =>
              if wf.name != e.name then
                Left(
                  RaiderError.InputValidation(
                    s"bundle entry '${e.name}' reports name '${wf.name}'"
                  )
                )
              else Right(e.name -> wf.program)
            case other =>
              Left(
                RaiderError.InputValidation(
                  s"${e.mainClass} does not implement BundleWorkflow " +
                    s"(got ${other.getClass.getSimpleName})"
                )
              )
        }.toVector
      val errors = loaded.collect { case Left(e) => e }
      if errors.nonEmpty then Left(errors.head)
      else Right(loaded.collect { case Right(kv) => kv }.toMap)
    catch
      case e: ClassNotFoundException =>
        Left(
          RaiderError.InputValidation(
            s"bundle class not found: ${e.getMessage.take(120)}"
          )
        )
      case e: Exception =>
        Left(
          RaiderError.InputValidation(
            s"bundle load failure: ${e.getClass.getSimpleName}"
          )
        )

  private val UTF_8 = java.nio.charset.StandardCharsets.UTF_8
end BundleLoader
