package raider.cli.run

import raider.core.result.ArtifactEntry
import zio.json.{DecoderOps, EncoderOps}
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.util.jar.{JarEntry, JarOutputStream, Manifest}
import scala.jdk.CollectionConverters.*

/** RAI-210 (FCI-REPORT-02 deferred acceptance): post-run artifact mutation
  * fixture. After a REAL bundle run (real `raider.cli.Main` child, same
  * process-boundary discipline as RAI-185), the artifacts of the completed run
  * are mutated exactly the way the card names them — a partial byte mutation, a
  * removed required file, a swapped digest, a removed manifest and a torn
  * manifest (the deterministic shape of a mutation racing finalization) — and
  * `raider verify-artifacts <run-dir>` (real child) must refuse every one of
  * them truthfully: typed problem on stderr, no report on stdout, non-zero
  * (exit 27) — never a fabricated passed check. The pristine run dir must
  * verify with exit 0 (the integrity manifest re-hash over the generated set
  * passes).
  */
object ArtifactMutationSpec extends ZIOSpecDefault:

  private final case class Child(exit: Int, stdout: String, stderr: String)

  private def tempDir(): Path = Files.createTempDirectory("rai210-")

  // ---------------------------------------------------------------------------
  // Real-child harness (RAI-185 pattern: real raider.cli.Main processes)
  // ---------------------------------------------------------------------------

  private def fixtureClassesDir: Path =
    val location = Path.of(
      classOf[
        raider.fixtures.DemoWorkflow
      ].getProtectionDomain.getCodeSource.getLocation.toURI
    )
    if Files.isDirectory(location) then location
    else
      throw new IllegalStateException(
        s"fixture classes are not a directory: $location"
      )

  private def makeBundle(dir: Path): Path =
    val jar = dir.resolve("pipelines.jar")
    val manifest = new Manifest()
    manifest.getMainAttributes.putValue("Manifest-Version", "1.0")
    val output = new JarOutputStream(Files.newOutputStream(jar), manifest)
    try
      val bundleManifest =
        """{"schemaVersion":1,"bundleId":"first-ci","entries":[{"name":"inspect","mainClass":"raider.fixtures.DemoWorkflow","version":1}],"declaredTrustLevel":"trusted-local"}"""
      output.putNextEntry(new JarEntry(BundleLoader.ManifestEntry))
      output.write(bundleManifest.getBytes(UTF_8))
      output.closeEntry()
      val root = fixtureClassesDir
      val stream = Files.walk(root)
      try
        stream.iterator().asScala.filter(Files.isRegularFile(_)).foreach {
          file =>
            val name = root.relativize(file).toString.replace('\\', '/')
            output.putNextEntry(new JarEntry(name))
            Files.copy(file, output)
            output.closeEntry()
        }
      finally stream.close()
    finally output.close()
    jar

  /** Runs `raider.cli.Main` as a REAL child process; stdout/stderr are captured
    * next to `capture` and deleted after the read.
    */
  private def child(argv: List[String], capture: Path): Child =
    val captureName = capture.getFileName.toString
    val stdoutFile = capture.resolveSibling(captureName + ".stdout")
    val stderrFile = capture.resolveSibling(captureName + ".stderr")
    val java = Path.of(System.getProperty("java.home"), "bin", "java")
    val classpath = System.getProperty("java.class.path")
    val processBuilder = new ProcessBuilder(
      (java.toString :: "-cp" :: classpath :: "raider.cli.Main" :: argv)*
    )
    processBuilder.redirectOutput(stdoutFile.toFile)
    processBuilder.redirectError(stderrFile.toFile)
    val process = processBuilder.start()
    process.getOutputStream.close()
    if !process.waitFor(90, TimeUnit.SECONDS) then
      process.destroyForcibly()
      throw new IllegalStateException(
        "raider child did not exit within 90 seconds"
      )
    val result = Child(
      process.exitValue(),
      Files.readString(stdoutFile, UTF_8),
      Files.readString(stderrFile, UTF_8)
    )
    Files.deleteIfExists(stdoutFile)
    Files.deleteIfExists(stderrFile)
    result

  /** The documented First CI run argv, generic CI mode (no --context/--ci:
    * RAI-100 — absent means no wrapper behavior, nothing else changes).
    */
  private def baseArgs(bundle: Path, out: Path): List[String] =
    List(
      "run",
      "--bundle",
      bundle.toString,
      "--workflow",
      "inspect",
      "--input",
      "inspect the project",
      "--mock",
      "--out",
      out.toString
    )

  /** The runner owns its invocation root: `<out>/work-<uuid>` (RUN-03). */
  private def runDirOf(out: Path): Path =
    val stream = Files.list(out)
    try
      stream
        .iterator()
        .asScala
        .filter(Files.isDirectory(_))
        .filter(_.getFileName.toString.startsWith("work-"))
        .toVector match
        case Vector(single) => single
        case other =>
          throw new IllegalStateException(
            s"expected exactly one work-* invocation root, found: $other"
          )
    finally stream.close()

  private def copyDir(src: Path, dst: Path): Path =
    val stream = Files.walk(src)
    try
      stream.iterator().asScala.foreach { source =>
        val target = dst.resolve(src.relativize(source).toString)
        if Files.isDirectory(source) then Files.createDirectory(target)
        else Files.copy(source, target)
      }
    finally stream.close()
    dst

  // ---------------------------------------------------------------------------
  // Manifest fixtures (writer's exact algorithm: sorted paths, lowercase hex)
  // ---------------------------------------------------------------------------

  private def sha256(bytes: Array[Byte]): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"$b%02x")
      .mkString

  private def entryFor(dir: Path, relative: String): ArtifactEntry =
    val bytes = Files.readAllBytes(dir.resolve(relative))
    ArtifactEntry(
      raider.core.ArtifactId(relative.replace('/', '-')),
      sha256(bytes),
      "application/octet-stream",
      bytes.length.toLong,
      relative
    )

  /** A synthetic completed-run directory: the documented required set plus one
    * extra file, and a truthful manifest over all of them (the writer's shape:
    * a JSON array, self-excluded artifacts.json).
    */
  private def makeRunDir(work: Path, name: String): Path =
    val dir = work.resolve(name)
    Files.createDirectories(dir)
    Map(
      "run.json" -> """{"schemaVersion":1,"status":"Succeeded"}""",
      "result.json" -> """{"codec":"raider/text","codecVersion":1}""",
      "events.jsonl" -> ("""{"seq":1}""" + "\n"),
      "checks.junit.xml" -> "<testsuite name=\"rai210\"/>",
      "summary.md" -> "# raider run\n",
      "extra.txt" -> "untracked-but-listed extra file\n"
    ).foreach { case (relative, body) =>
      Files.writeString(dir.resolve(relative), body, UTF_8)
    }
    writeManifest(
      dir,
      fixtureNames
        .map(relative => entryFor(dir, relative))
        .toList
        .sortBy(_.path)
    )
    dir

  private def fixtureNames: Vector[String] =
    Vector(
      "run.json",
      "result.json",
      "events.jsonl",
      "checks.junit.xml",
      "summary.md",
      "extra.txt"
    )

  private def writeManifest(dir: Path, entries: List[ArtifactEntry]): Unit =
    Files.writeString(dir.resolve("artifacts.json"), entries.toJson, UTF_8)

  private def entriesOf(dir: Path): List[ArtifactEntry] =
    Files
      .readString(dir.resolve("artifacts.json"), UTF_8)
      .fromJson[
        List[ArtifactEntry]
      ]
      .fold(
        detail => throw new IllegalStateException(s"fixture manifest: $detail"),
        identity
      )

  private def digestOf(entries: List[ArtifactEntry], path: String): String =
    entries
      .find(_.path == path)
      .getOrElse(throw new IllegalStateException(s"no manifest entry: $path"))
      .sha256

  /** Total size of the listed fixture files (the honest `Verified.bytes`). */
  private def listedBytes(dir: Path): Long =
    fixtureNames.map(relative => Files.size(dir.resolve(relative))).sum

  /** Flips exactly one byte of a file in place — the same length, a different
    * digest (the card's "partial byte mutation").
    */
  private def flipLastByte(dir: Path, relative: String): Unit =
    val file = dir.resolve(relative)
    val bytes = Files.readAllBytes(file)
    bytes(bytes.length - 1) =
      if bytes(bytes.length - 1) == 0x7a.toByte then 0x7b.toByte
      else 0x7a.toByte
    Files.write(file, bytes)

  /** Swaps the sha256 of two manifest entries — evidence intact, manifest lying
    * (the card's "swapped digest").
    */
  private def swapDigests(dir: Path, a: String, b: String): Unit =
    val entries = entriesOf(dir)
    val digestA = digestOf(entries, a)
    val digestB = digestOf(entries, b)
    writeManifest(
      dir,
      entries.map(entry =>
        if entry.path == a then entry.copy(sha256 = digestB)
        else if entry.path == b then entry.copy(sha256 = digestA)
        else entry
      )
    )

  /** Truncates the manifest to half its bytes — the torn shape of an
    * interrupted finalization write.
    */
  private def tearManifest(dir: Path): Unit =
    val manifest = dir.resolve("artifacts.json")
    Files.write(
      manifest,
      Files.readAllBytes(manifest).take(Files.size(manifest).toInt / 2)
    )

  def spec = suite("RAI-210 artifact mutation fixture (FCI-REPORT-02)")(
    test(
      "RAI-210: typed integrity verdicts and the frozen exit mapping (unit)"
    ) {
      val work = tempDir()
      val pristine = makeRunDir(work, "pristine")
      val original = entriesOf(pristine)
      val verified = ArtifactIntegrityCommand.verify(pristine)

      val byteEdit = makeRunDir(work, "byte-edit")
      flipLastByte(byteEdit, "run.json")

      val removed = makeRunDir(work, "removed")
      Files.deleteIfExists(removed.resolve("checks.junit.xml"))

      val swapped = makeRunDir(work, "swapped")
      swapDigests(swapped, "run.json", "summary.md")

      val noManifest = makeRunDir(work, "no-manifest")
      Files.deleteIfExists(noManifest.resolve("artifacts.json"))

      val torn = makeRunDir(work, "torn")
      tearManifest(torn)
      val tornVerdict = ArtifactIntegrityCommand.verify(torn)
      // The zio-json detail string is not pinned; everything else is.
      val tornIsTypedMalformed = tornVerdict match
        case Left(
              Vector(
                ArtifactIntegrityCommand.Problem.ManifestMalformed(manifest, _)
              )
            ) =>
          manifest == torn.resolve("artifacts.json")
        case _ => false

      val stripped = makeRunDir(work, "stripped")
      writeManifest(stripped, List(entryFor(stripped, "extra.txt")))

      val duplicated = makeRunDir(work, "duplicated")
      writeManifest(
        duplicated,
        entriesOf(duplicated) :+ entryFor(duplicated, "run.json")
      )

      val escaped = makeRunDir(work, "escaped")
      writeManifest(
        escaped,
        entriesOf(escaped).map(entry =>
          if entry.path == "extra.txt" then entry.copy(path = "../outside")
          else entry
        )
      )

      val resized = makeRunDir(work, "resized")
      writeManifest(
        resized,
        entriesOf(resized).map(entry =>
          if entry.path == "run.json" then
            entry.copy(sizeBytes = entry.sizeBytes + 1L)
          else entry
        )
      )

      val corrupted = Vector(
        byteEdit,
        removed,
        swapped,
        noManifest,
        torn,
        stripped,
        duplicated,
        escaped,
        resized
      )
      assertTrue(
        verified == Right(
          ArtifactIntegrityCommand.Verified(
            runDir = pristine,
            manifest = pristine.resolve("artifacts.json"),
            entries = 6,
            bytes = listedBytes(pristine)
          )
        ),
        ArtifactIntegrityCommand.exitCode(verified) == 0,
        ArtifactIntegrityCommand.verify(byteEdit) == Left(
          Vector(
            ArtifactIntegrityCommand.Problem.DigestMismatch(
              "run.json",
              digestOf(original, "run.json"),
              sha256(Files.readAllBytes(byteEdit.resolve("run.json")))
            )
          )
        ),
        ArtifactIntegrityCommand.verify(removed) == Left(
          Vector(
            ArtifactIntegrityCommand.Problem.EntryMissing("checks.junit.xml")
          )
        ),
        ArtifactIntegrityCommand.verify(swapped) == Left(
          Vector(
            ArtifactIntegrityCommand.Problem.DigestMismatch(
              "run.json",
              digestOf(original, "summary.md"),
              sha256(Files.readAllBytes(swapped.resolve("run.json")))
            ),
            ArtifactIntegrityCommand.Problem.DigestMismatch(
              "summary.md",
              digestOf(original, "run.json"),
              sha256(Files.readAllBytes(swapped.resolve("summary.md")))
            )
          )
        ),
        ArtifactIntegrityCommand.verify(noManifest) == Left(
          Vector(
            ArtifactIntegrityCommand.Problem.ManifestMissing(
              noManifest,
              noManifest.resolve("artifacts.json")
            )
          )
        ),
        tornIsTypedMalformed,
        ArtifactIntegrityCommand.verify(stripped) == Left(
          Vector(
            ArtifactIntegrityCommand.Problem.RequiredAbsent(
              List(
                "run.json",
                "result.json",
                "events.jsonl",
                "checks.junit.xml",
                "summary.md"
              )
            )
          )
        ),
        ArtifactIntegrityCommand.verify(duplicated) == Left(
          Vector(ArtifactIntegrityCommand.Problem.DuplicateEntry("run.json"))
        ),
        ArtifactIntegrityCommand.verify(escaped) == Left(
          Vector(ArtifactIntegrityCommand.Problem.PathEscape("../outside"))
        ),
        ArtifactIntegrityCommand.verify(resized) == Left(
          Vector(
            ArtifactIntegrityCommand.Problem.SizeMismatch(
              "run.json",
              Files.size(resized.resolve("run.json")) + 1L,
              Files.size(resized.resolve("run.json"))
            )
          )
        ),
        // Every typed refusal maps to 27 — never green, never invented codes.
        corrupted.forall(dir =>
          ArtifactIntegrityCommand
            .exitCode(ArtifactIntegrityCommand.verify(dir)) == 27
        ),
        // Usage errors are 21 (ReplayCli/ResumeCommand precedent).
        ArtifactIntegrityCommand.parse(List("verify-artifacts")).isLeft,
        ArtifactIntegrityCommand
          .parse(List("verify-artifacts", "--flag"))
          .isLeft,
        ArtifactIntegrityCommand
          .parse(List("verify-artifacts", "a", "b"))
          .isLeft,
        ArtifactIntegrityCommand.exec(List("verify-artifacts")) == 21,
        ArtifactIntegrityCommand.exec(List("verify-artifacts", "a", "b")) == 21
      )
    },
    test(
      "FCI-REPORT-02: post-run mutations are refused truthfully by the real CLI"
    ) {
      val work = tempDir()
      val bundle = makeBundle(work)
      val out = work.resolve("run-out")
      val run = child(baseArgs(bundle, out), work.resolve("cap-run"))
      val runDir = runDirOf(out)
      // The pristine completed run verifies: the re-hash over the generated
      // set passes and the report is the only stdout content.
      val pristine = child(
        List("verify-artifacts", runDir.toString),
        work.resolve("cap-pristine")
      )
      // Post-run mutations, each on its own byte-identical copy of the run.
      val byteEdit = copyDir(runDir, work.resolve("mut-byte-edit"))
      flipLastByte(byteEdit, "result.json")
      val byteEditChild = child(
        List("verify-artifacts", byteEdit.toString),
        work.resolve("cap-byte-edit")
      )
      val removedFile = copyDir(runDir, work.resolve("mut-removed"))
      Files.deleteIfExists(removedFile.resolve("run.json"))
      val removedChild = child(
        List("verify-artifacts", removedFile.toString),
        work.resolve("cap-removed")
      )
      val swapped = copyDir(runDir, work.resolve("mut-swapped"))
      swapDigests(swapped, "run.json", "summary.md")
      val swappedChild = child(
        List("verify-artifacts", swapped.toString),
        work.resolve("cap-swapped")
      )
      val noManifest = copyDir(runDir, work.resolve("mut-no-manifest"))
      Files.deleteIfExists(noManifest.resolve("artifacts.json"))
      val noManifestChild = child(
        List("verify-artifacts", noManifest.toString),
        work.resolve("cap-no-manifest")
      )
      val torn = copyDir(runDir, work.resolve("mut-torn"))
      tearManifest(torn)
      val tornChild = child(
        List("verify-artifacts", torn.toString),
        work.resolve("cap-torn")
      )
      val corrupted =
        Vector(
          byteEditChild,
          removedChild,
          swappedChild,
          noManifestChild,
          tornChild
        )
      assertTrue(
        run.exit == 0,
        run.stdout.contains("\"exitCode\":0"),
        pristine.exit == 0,
        pristine.stdout.startsWith("artifact-integrity: verified"),
        // Five required artifacts + transcript.json (best-effort trace,
        // written and listed by the runner) = six listed entries.
        pristine.stdout.contains("entries: 6"),
        // Child JVMs may emit scala-library WARNING lines on stderr; the
        // meaningful success assertion is that no typed refusal was rendered.
        !pristine.stderr.contains("typed-error"),
        !pristine.stderr.contains("artifact-write-failure"),
        // Every post-run mutation is refused truthfully: exit 27, the named
        // typed problem on stderr, NO report on stdout (no fabricated pass).
        corrupted.forall(_.exit == 27),
        corrupted.forall(_.stdout.isEmpty),
        byteEditChild.stderr.contains("sha256 mismatch: result.json"),
        removedChild.stderr.contains("missing artifact file: run.json"),
        swappedChild.stderr.contains("sha256 mismatch: run.json") &&
          swappedChild.stderr.contains("sha256 mismatch: summary.md"),
        noManifestChild.stderr.contains("artifact manifest missing") &&
          noManifestChild.stderr.contains("artifacts.json"),
        tornChild.stderr.contains("artifact manifest malformed"),
        corrupted.forall(c =>
          !c.stdout.startsWith("artifact-integrity: verified")
        )
      )
    }
  ) @@ TestAspect.sequential
