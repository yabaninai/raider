package raider.cli.workflow

import raider.cli.run.HeadlessRunner
import raider.core.RaiderError
import zio.{ZIO, Unsafe}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import zio.json.EncoderOps

/** `raider compile <source>.scala --out <dir>` (RAI-155.d; ADR-033 §7 — the
  * reserved proposal names become real commands here).
  *
  * Compiles the EDITED generated workflow source with the PINNED toolchain
  * (dotc 3.9.0, the ChatWorkflowCompileGateSpec / scripts/build/compile.sh
  * subprocess discipline) into a runnable launcher artifact:
  *
  *   - `<out>/classes/` — compiled classes (the Option-L launcher artifact).
  *   - `<out>/build-identity.json` — the [[WorkflowBuildIdentity]] record
  *     (source + class digests, toolchain) consumed by `raider workflow run` so
  *     a manual edit invalidates earlier result claims (CWF-D02).
  *   - `<out>/dotc.log` — the full compiler log (evidence).
  *
  * Discipline:
  *   - Compilation dispatches NOTHING agent-shaped: dotc only type-checks
  *     trusted Scala (CWF-C02/A02 — the compiler never executes the source).
  *   - Publish is staged (`<out>.staging`, same parent for an atomic rename)
  *     and a partially written build is NEVER published (R-taxonomy: partial
  *     writes stay identifiable, never a complete-looking artifact).
  *   - An existing NON-EMPTY `--out` is refused (the R5 discipline of
  *     ChatWorkflowExport: derived build output is never mixed with existing
  *     content; recompiles go to a fresh directory or delete the old one).
  *   - Invalid source is a typed [[RaiderError.Compilation]] (exit 21, R7)
  *     carrying the bounded compiler-diagnostics tail; a dotc TIMEOUT or a
  *     launcher failure is NOT claimed as a compile verdict (typed 24/21).
  *   - The dotc child is destroyed on JVM shutdown (bounded owned-work
  *     cleanup).
  */
object WorkflowCompile:

  final case class Args(source: Path, out: Path)

  /** Whole-command wall bound (generous: the .c gate compiles the generated
    * source in seconds).
    */
  val CompileTimeoutSeconds: Long = 120L

  // ------------------------------------------------------------------//
  // Argv parsing (pure; no secret ever travels in argv by contract)     //
  // ------------------------------------------------------------------//

  def parse(argv: List[String]): Either[RaiderError, Args] =
    @scala.annotation.tailrec
    def loop(
        rest: List[String],
        source: Option[String],
        out: Option[String]
    ): Either[RaiderError, Args] = rest match
      case Nil =>
        for
          src <- require(source, "source .scala file")
          o <- require(out, "--out <dir>")
        yield Args(Paths.get(src), Paths.get(o))
      case "--out" :: value :: tail => loop(tail, source, Some(value))
      case "--out" :: Nil =>
        Left(RaiderError.Configuration("--out requires a value"))
      case flag :: _ if flag.startsWith("--") =>
        Left(RaiderError.Configuration(s"unknown argument '$flag'"))
      case single :: tail if source.isEmpty =>
        // The source is a positional argument accepted anywhere before a
        // later --out (usage: raider compile <source>.scala --out <dir>).
        loop(tail, Some(single), out)
      case other :: _ =>
        Left(
          RaiderError.Configuration(
            s"unexpected argument '$other' (usage: raider compile <source>.scala --out <dir>)"
          )
        )
    loop(argv, None, None)

  private def require(
      v: Option[String],
      what: String
  ): Either[RaiderError, String] =
    v.filter(_.trim.nonEmpty) match
      case Some(x) => Right(x)
      case None    => Left(RaiderError.Configuration(s"$what is required"))

  // ------------------------------------------------------------------//
  // Compile                                                             //
  // ------------------------------------------------------------------//

  private sealed trait DotcOutcome
  private final case class DotcCompleted(code: Int) extends DotcOutcome
  private case object DotcTimedOut extends DotcOutcome

  /** Runs one compile to completion. Returns the PUBLISHED output directory.
    * Typed failures only; nothing dispatches.
    */
  def compile(args: Args): ZIO[Any, RaiderError, Path] =
    for
      sourceBytes <- readSource(args.source)
      classNames <- ZIO
        .fromOption(
          WorkflowBuildIdentity.deriveClassNames(
            args.source.getFileName.toString
          )
        )
        .mapError(_ =>
          RaiderError.InputValidation(
            s"source file name '${args.source.getFileName}' has no usable <name>.scala stem"
          )
        )
      classpaths <- ZIO.fromEither(resolveClasspaths)
      _ <- refuseNonEmptyTarget(args.out)
      staging <- prepareStaging(args.out)
      _ <- ZIO.succeed(
        // Bounded owned-work cleanup: if this JVM dies while dotc runs, the
        // child must not survive the launcher.
        registerShutdownDestroy()
      )
      outcome <- runDotc(staging, args.source, classpaths)
      _ <- outcome match
        case DotcCompleted(0) =>
          // Post-compile launchability check (see verifyMainNotCollided):
          // a typed, actionable failure INSTEAD of a silently broken
          // launcher artifact.
          verifyMainNotCollided(staging, classNames._1)
        case DotcCompleted(code) =>
          ZIO.fail(
            RaiderError.Compilation(
              s"compilation failed (dotc exit $code) for ${args.source}; diagnostics: " + logTail(
                staging
              )
            )
          )
        case DotcTimedOut =>
          ZIO.fail(
            RaiderError.DeadlineExceeded(
              s"dotc did not finish within $CompileTimeoutSeconds s and was destroyed; no compile verdict is claimed for ${args.source}"
            )
          )
      identity <- buildIdentity(staging, args.source, sourceBytes, classNames)
      _ <- writeIdentity(staging, identity)
      _ <- publish(staging, args.out)
    yield args.out

  /** Detects the case-collision emit hazard dotc warns about ("Generated class
    * X differs only in case from x. Such classes will overwrite one another on
    * case-insensitive filesystems"): the generated naming pair (`object <Stem>`
    * + `@main def <stem>`) produces TWO classes whose files collide on a
    * case-insensitive filesystem, and the write order decides which survives.
    * When the @main helper LOSES, its file defines the OBJECT instead and the
    * artifact has NO launchable main.
    *
    * This check LOADS (initializing NOTHING — `initialize = false`, no static
    * work, no dispatch) the `<stem>` main class when its file exists and
    * refuses the build with a typed, actionable Compilation failure when the
    * file was eaten by the collision. A MISSING `<stem>.class` is NOT a
    * failure: the user may have renamed the `@main` definition in the edited
    * source (the launcher main is then their own name; the run path derives
    * only the module class from the file stem).
    */
  /** Package-private for the deterministic WorkflowCompileSpec seam (review
    * round 1, finding 2): the collision-shaped staging directories the refusal
    * branches need cannot be produced deterministically through the public
    * `compile` entry (dotc's write order decides the real collision), so the
    * spec drives this check directly with crafted `classes/` dirs. No behavior
    * change: the positive no-collision path is additionally covered through the
    * public entry.
    */
  private[workflow] def verifyMainNotCollided(
      staging: Path,
      mainClass: String
  ): ZIO[Any, RaiderError, Unit] =
    ZIO
      .attempt {
        // EXACT-CASE directory scan: on a case-insensitive filesystem a
        // naive Files.exists(mainFile) would MATCH the object's
        // `Wf155d_launch.class` and false-positive (or hide) the collision.
        val classesDir = staging.resolve("classes")
        val listing = Files.list(classesDir)
        val exactEntry =
          try
            listing
              .iterator()
              .asScala
              .exists(p => p.getFileName.toString == mainClass + ".class")
          finally listing.close()
        if !exactEntry then Right(())
        else
          val loader = new java.net.URLClassLoader(
            Array(classesDir.toUri.toURL),
            null // isolated: the name check needs no product classes; scalafix:ok DisableSyntax.null
          )
          try
            val cls = Class.forName(mainClass, false, loader)
            if cls.getName == mainClass then Right(())
            else
              Left(
                RaiderError.Compilation(
                  s"launchable main lost to a class-name case collision: $classesDir/$mainClass.class defines '${cls.getName}', not '$mainClass' " +
                    "(dotc: \"Generated class ... differs only in case ...\"). On this case-insensitive filesystem the " +
                    "generated object/<main> pair overwrote one another; rename the @main definition in the edited " +
                    "source (for example `@main def " + mainClass + "_main`) and recompile"
                )
              )
          catch
            case e: ClassNotFoundException =>
              Left(
                RaiderError.Compilation(
                  s"launchable main lost to a class-name case collision: $classesDir/$mainClass.class exists but does not define '$mainClass' " +
                    s"(${e.getMessage}); on this case-insensitive filesystem the generated object/<main> pair " +
                    "overwrote one another; rename the @main definition in the edited source (for example " +
                    "`@main def <stem>_main`) and recompile"
                )
              )
          finally loader.close()
      }
      .mapError(t =>
        RaiderError.Compilation(
          s"cannot verify the launchable main after compile: ${String.valueOf(t)}"
        )
      )
      .flatMap(ZIO.fromEither(_))

  private def runDotc(
      staging: Path,
      source: Path,
      classpaths: (String, String)
  ): ZIO[Any, RaiderError, DotcOutcome] =
    val (compilerCp, userCp) = classpaths
    ZIO
      .attempt {
        Files.createDirectories(staging.resolve("classes"))
        val pb = new java.lang.ProcessBuilder(
          "java",
          "-Xmx2g",
          "-cp",
          compilerCp,
          "dotty.tools.dotc.Main",
          "-classpath",
          userCp,
          "-d",
          staging.resolve("classes").toString,
          source.toString
        )
        pb.redirectErrorStream(true)
        pb.redirectOutput(staging.resolve("dotc.log").toFile)
        val proc = pb.start()
        activeDotc.set(Some(proc))
        val finished = proc.waitFor(CompileTimeoutSeconds, TimeUnit.SECONDS)
        activeDotc.set(None)
        if !finished then
          proc.destroy() // graceful first (SIGTERM semantics)
          if !proc.waitFor(2, TimeUnit.SECONDS) then proc.destroyForcibly()
          DotcTimedOut
        else DotcCompleted(proc.exitValue())
      }
      .mapError(t =>
        RaiderError.Configuration(
          s"dotc launch failed (no compile verdict claimed): ${String.valueOf(t)}"
        )
      )

  /** The in-flight dotc process, destroyed by the shutdown hook. */
  private val activeDotc =
    new java.util.concurrent.atomic.AtomicReference(Option.empty[Process])

  private val shutdownHookInstalled =
    new java.util.concurrent.atomic.AtomicBoolean(false)

  private def registerShutdownDestroy(): Unit =
    if shutdownHookInstalled.compareAndSet(false, true) then
      Runtime.getRuntime.addShutdownHook(
        new Thread(
          () =>
            activeDotc.getAndSet(None).foreach { p =>
              p.destroy()
              if !p.waitFor(2, TimeUnit.SECONDS) then p.destroyForcibly()
            },
          "raider-compile-dotc-stop"
        )
      )

  // ---- pinned toolchain (scripts/build/compile.sh discipline) ------------

  private def m2Root: Path =
    sys.env.get("RAIDER_M2_REPO").map(Paths.get(_)).getOrElse {
      val cwd = Paths.get("").toAbsolutePath
      Iterator
        .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
        .takeWhile(_.isDefined)
        .collect { case Some(dir) => dir }
        .find(dir => Files.isDirectory(dir.resolve("artifacts/tools/m2")))
        .getOrElse(cwd)
        .resolve("artifacts/tools/m2")
    }

  private def m2Jar(rel: String): Either[RaiderError, Path] =
    val p = m2Root.resolve(rel)
    if Files.isRegularFile(p) then Right(p)
    else
      Left(
        RaiderError.Configuration(
          s"missing pinned toolchain jar: $p (run scripts/bootstrap.sh or set RAIDER_M2_REPO)"
        )
      )

  private def resolveClasspaths: Either[RaiderError, (String, String)] =
    val compilerRels = Vector(
      "org/scala-lang/scala3-compiler_3/3.9.0/scala3-compiler_3-3.9.0.jar",
      "org/scala-lang/scala3-interfaces/3.9.0/scala3-interfaces-3.9.0.jar",
      "org/scala-lang/tasty-core_3/3.9.0/tasty-core_3-3.9.0.jar",
      "org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar",
      "org/scala-lang/modules/scala-asm/9.9.0-scala-1/scala-asm-9.9.0-scala-1.jar",
      "org/scala-sbt/compiler-interface/1.12.0/compiler-interface-1.12.0.jar",
      "org/scala-sbt/util-interface/1.11.5/util-interface-1.11.5.jar"
    )
    for
      compilerJars <-
        compilerRels.foldLeft[Either[RaiderError, Vector[Path]]](
          Right(Vector.empty)
        ) { (acc, rel) =>
          for paths <- acc; jar <- m2Jar(rel)
          yield paths :+ jar
        }
      lib <- m2Jar("org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar")
    yield
      val compilerCp = compilerJars.mkString(java.io.File.pathSeparator)
      // The prescribed `-classpath` for the workflow source: the pinned
      // scala-library FIRST (an sbt-launcher scala-library cannot be consumed
      // by dotc 3.9.0), then this process's own runtime classpath carrying
      // the raider product classes + zio/zio-json. The example workflow is
      // NOT preloaded here — the edited source compiles against the product
      // classpath only (CWF-D01).
      val base = System.getProperty("java.class.path", "")
      val userCp =
        if base.isEmpty then lib.toString
        else lib.toString + java.io.File.pathSeparator + base
      (compilerCp, userCp)

  // ---- staging / publish -------------------------------------------------

  private def readSource(source: Path): ZIO[Any, RaiderError, Array[Byte]] =
    ZIO
      .attempt {
        if !Files.isRegularFile(source) then
          Left(
            RaiderError.InputValidation(
              s"source is not a readable regular file: $source"
            )
          )
        else Right(Files.readAllBytes(source))
      }
      .mapError(t =>
        RaiderError.InputValidation(
          s"cannot read source $source: ${String.valueOf(t)}"
        )
      )
      .flatMap(ZIO.fromEither(_))

  private def refuseNonEmptyTarget(out: Path): ZIO[Any, RaiderError, Unit] =
    ZIO
      .attempt {
        if !Files.exists(out) then Right(())
        else if Files.isSymbolicLink(out) || !Files.isDirectory(out) then
          Left(
            RaiderError.InputValidation(
              s"output target exists and is not a plain directory: $out; choose another --out"
            )
          )
        else
          val nonEmpty = withList(out)(_.hasNext)
          if nonEmpty then
            Left(
              RaiderError.InputValidation(
                s"output directory exists and is not empty: $out; a build is never mixed into existing content — remove it or choose another --out"
              )
            )
          else Right(())
      }
      .mapError(t =>
        RaiderError.InputValidation(
          s"cannot inspect output target $out: ${String.valueOf(t)}"
        )
      )
      .flatMap(ZIO.fromEither(_))

  private def withList[A](dir: Path)(f: java.util.Iterator[Path] => A): A =
    val listing = Files.list(dir)
    try f(listing.iterator())
    finally listing.close()

  /** Staging directory NEXT to `--out` (same parent: the final rename stays on
    * one filesystem). A leftover staging directory from an interrupted compile
    * is OUR derived data (nothing user-authored can be inside) and is removed
    * before reuse — an interrupted compile never looks published.
    */
  private def prepareStaging(out: Path): ZIO[Any, RaiderError, Path] =
    ZIO
      .attempt {
        val parent =
          Option(out.toAbsolutePath.getParent).getOrElse(Paths.get(""))
        Files.createDirectories(parent)
        val staging = parent.resolve(out.getFileName.toString + ".staging")
        deleteRecursively(staging)
        Files.createDirectories(staging.resolve("classes"))
        staging
      }
      .mapError(t =>
        RaiderError.JournalFailure(
          s"cannot prepare staging directory for $out: ${String.valueOf(t)}"
        )
      )

  private def buildIdentity(
      staging: Path,
      source: Path,
      sourceBytes: Array[Byte],
      classNames: (String, String)
  ): ZIO[Any, RaiderError, WorkflowBuildIdentity] =
    ZIO.fromEither {
      WorkflowBuildIdentity.classDigestOf(staging.resolve("classes")).map {
        classDigest =>
          val (mainClass, moduleClass) = classNames
          WorkflowBuildIdentity(
            schemaVersion = WorkflowBuildIdentity.SchemaVersion,
            kind = WorkflowBuildIdentity.Kind,
            sourceFileName = source.getFileName.toString,
            mainClass = mainClass,
            moduleClass = moduleClass,
            sourceDigest = WorkflowBuildIdentity.sha256Hex(sourceBytes),
            classDigest = classDigest,
            toolchain = WorkflowBuildIdentity.PinnedToolchain
          )
      }
    }

  private def writeIdentity(
      staging: Path,
      identity: WorkflowBuildIdentity
  ): ZIO[Any, RaiderError, Unit] =
    ZIO
      .attempt {
        Files.write(
          staging.resolve("build-identity.json"),
          identity.toJson.getBytes(StandardCharsets.UTF_8)
        )
        ()
      }
      .mapError(t =>
        RaiderError.JournalFailure(
          s"cannot write build identity: ${String.valueOf(t)}"
        )
      )

  private def publish(staging: Path, out: Path): ZIO[Any, RaiderError, Path] =
    ZIO
      .attempt {
        if Files.exists(out) then
          // empty directory: REPLACE_EXISTING rename (atomic on POSIX)
          Files.move(
            staging,
            out,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
          )
        else Files.move(staging, out, StandardCopyOption.ATOMIC_MOVE)
        out
      }
      .mapError(t =>
        RaiderError.JournalFailure(
          s"cannot publish build to $out: ${String.valueOf(t)}"
        )
      )

  private def deleteRecursively(p: Path): Unit =
    if Files.exists(p) then
      if Files.isDirectory(p) && !Files.isSymbolicLink(p) then
        withList(p)(_.forEachRemaining(child => deleteRecursively(child)))
      Files.deleteIfExists(p)

  private def logTail(staging: Path, maxLines: Int = 40): String =
    try
      val log = staging.resolve("dotc.log")
      if !Files.isRegularFile(log) then "(no compiler log written)"
      else
        val lines = Files.readAllLines(log, StandardCharsets.UTF_8).asScala
        val tail =
          if lines.size <= maxLines then lines else lines.takeRight(maxLines)
        if tail.isEmpty then "(empty compiler log)"
        else tail.mkString(" | ")
    catch
      case t: Throwable =>
        "(compiler log unreadable: " + String.valueOf(t) + ")"

  // ------------------------------------------------------------------//
  // Console adapter (the ONLY blocking interpreter boundary)            //
  // ------------------------------------------------------------------//

  /** Runs one compile and maps the outcome to the stable exit table: 0 success;
    * 21 invalid source/refused target/toolchain; 24 compile timeout; 27
    * staging/publish failure. `raider <CODE>: detail` on stderr.
    */
  def command(argv: List[String]): Int =
    parse(argv) match
      case Left(err) =>
        System.err.println(s"raider ${err.code}: ${err.detail}")
        HeadlessRunner.exitCodeFor(err)
      case Right(args) =>
        val outcome = Unsafe.unsafe { implicit u =>
          zio.Runtime.default.unsafe.run(compile(args).either).getOrThrow()
        }
        outcome match
          case Right(out) =>
            System.out.println(s"compiled workflow build: $out")
            raider.core.result.ExitCodes.Success
          case Left(err) =>
            System.err.println(s"raider ${err.code}: ${err.detail}")
            HeadlessRunner.exitCodeFor(err)

end WorkflowCompile
