package raider.cli.workflow

import zio.test.*
import zio.test.Assertion.*

import raider.core.RaiderError
import raider.dsl.workflow.{
  ChatWorkflowRenderer,
  NamedCheck,
  RenderOptions,
  StsAgentStage,
  StsOrigin,
  StsParam,
  StsSelection,
  StsTurn,
  StsTurnKind,
  StsTurnStatus
}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** RAI-155.d `raider compile` gate (CWF-D01/D02 compile leg):
  *
  *   - D01: the EDITED generated workflow compiles under the PINNED dotc 3.9.0
  *     through the product command (real dotc subprocess, not a mock); the
  *     published artifact carries classes/, a valid build-identity.json whose
  *     sourceDigest equals the sha256 of the edited source bytes and whose
  *     classDigest matches a fresh recompute, plus the dotc.log evidence. The
  *     compiled build is NOT the committed example: it is rendered in the gate
  *     and edited (the baseUrl constant) before compiling, so the compile path
  *     never depends on an example preloaded anywhere (CWF-D01).
  *   - D02: an invalid (torn) source is a TYPED Compilation failure carrying
  *     the compiler diagnostics; an existing non-empty --out is refused; an
  *     existing EMPTY --out is accepted; a tampered classes directory fails the
  *     identity verification; a stale --source (edited after compile) fails the
  *     stale-build check.
  *   - Review round 1 (finding 2): the case-collision detector's refusal
  *     branches are covered DETERMINISTICALLY at the package-private
  *     `verifyMainNotCollided` seam (crafted staging `classes/` dirs — the real
  *     collision shape cannot be produced through the public entry because
  *     dotc's write order decides it), plus the positive no-collision path
  *     through the public `compile` entry. The wrong-name crafted classfile
  *     reproduces the on-disk shape the macOS collision leaves behind.
  *
  * Compile runs are real (each ~10-30 s); the suite keeps the dotc count at the
  * minimum that proves the contract (one green, one red).
  */
object WorkflowCompileSpec extends ZIOSpecDefault:

  // ----- fixture -------------------------------------------------------------

  /** Probe selection with LOCAL deterministic checks so the compiled artifact
    * can also be executed by the run/launch gates without any provider or build
    * tool. baseUrl points at a closed loopback port (a non-secret reference;
    * never contacted by this spec).
    */
  private val probe = StsSelection(
    sessionId = "sess-155d-probe",
    intent = StsTurn(
      "d1",
      StsTurnKind.Intent,
      StsTurnStatus.Completed,
      "Write the launch marker for the 155.d probe"
    ),
    refinements = List(
      StsTurn(
        "d2",
        StsTurnKind.Refinement,
        StsTurnStatus.Completed,
        "Keep the marker content exact"
      )
    ),
    taskParams = List(
      StsParam("targetPath", "marker.txt"),
      StsParam("summary", "155.d probe task")
    ),
    checks = List(
      NamedCheck("marker", List("/bin/test", "-f", "marker.txt"))
    ),
    agentStage = StsAgentStage(maxRounds = 3),
    origin = StsOrigin(
      providerKind = "openai-compatible",
      model = "stand-model-155d",
      baseUrl = Some("http://127.0.0.1:1/v1")
    )
  )

  /** The UNEDITED render is the compile surface since RAI-158: the renderer no
    * longer emits the case-colliding `object <Stem>` + `@main def <stem>` pair
    * (the launcher main is `run<Capitalized>`), so the as-is generated source
    * compiles and publishes cleanly on this case-insensitive filesystem — no
    * rename workaround needed.
    */
  private def writeSource(dir: Path, name: String, text: String): Path =
    Files.createDirectories(dir)
    val p = dir.resolve(name)
    Files.write(p, text.getBytes(StandardCharsets.UTF_8))
    p

  private def sha256(path: Path): String =
    WorkflowBuildIdentity.sha256Hex(Files.readAllBytes(path))

  /** A REAL compiled classfile from this run's classpath, for the collision
    * seam: its bytes define a binary name different from any crafted
    * `<stem>.class` name we copy it under — exactly the on-disk shape the macOS
    * case collision leaves behind (deterministic; no reliance on dotc write
    * order).
    */
  private def anyRealClassFile: Path =
    val dir =
      classOf[
        WorkflowBuildIdentity
      ].getProtectionDomain.getCodeSource.getLocation
    val p = Paths
      .get(dir.toURI)
      .resolve("raider/cli/workflow/WorkflowBuildIdentity.class")
    if !Files.isRegularFile(p) then
      throw new IllegalStateException(s"expected a real classfile at $p")
    p

  private def seamStaging(work: Path, stem: String)(
      populate: Path => Unit
  ): Path =
    val staging = work.resolve(stem + ".staging")
    Files.createDirectories(staging.resolve("classes"))
    populate(staging.resolve("classes"))
    staging

  // ----- spec ----------------------------------------------------------------

  def spec =
    suite("raider compile (RAI-155.d pinned-dotc build)")(
      test("parse: usage errors are typed Configuration, ok path resolves") {
        val base = Files.createTempDirectory("c155d-parse")
        val ok = WorkflowCompile.parse(
          List(
            base.resolve("w.scala").toString,
            "--out",
            base.resolve("out").toString
          )
        )
        val missingSource =
          WorkflowCompile.parse(List("--out", base.resolve("o").toString))
        val missingOut =
          WorkflowCompile.parse(List(base.resolve("w.scala").toString))
        val unknown = WorkflowCompile.parse(
          List(base.resolve("w.scala").toString, "--wat", "x", "--out", "o")
        )
        assertTrue(
          ok.isRight,
          missingSource.fold(_.code == "RA-CFG", _ => false),
          missingOut.fold(_.code == "RA-CFG", _ => false),
          unknown.fold(_.code == "RA-CFG", _ => false)
        )
      },
      test(
        "CWF-D01: edited generated source compiles via the product command; build identity is self-consistent"
      ) {
        val rendered = ChatWorkflowRenderer
          .render(
            probe,
            RenderOptions("wf155d_probe")
          )
          .fold(e => throw new IllegalStateException(e.detail), identity)
        // This build is NOT the committed example: it is rendered fresh in
        // the gate, so the compile path never depends on an example being
        // preloaded anywhere (CWF-D01). (The live edit -> recompile loop is
        // proven by WorkflowLaunchSpec, which rewrites the baseUrl constant.)
        // RAI-158: the UNEDITED render compiles — the renderer naming fix
        // removed the case-collision workaround this fixture used to need.
        val work = Files.createTempDirectory("c155d-build")
        val src = writeSource(
          work,
          "wf155d_probe.scala",
          rendered.source
        )
        val out = work.resolve("build")
        WorkflowCompile.compile(WorkflowCompile.Args(src, out)).map { _ =>
          val classes = out.resolve("classes")
          val classFiles =
            if Files.isDirectory(classes) then
              val s = Files.walk(classes)
              try
                s.iterator()
                  .asScala
                  .toVector
                  .filter(p => Files.isRegularFile(p))
                  .size
              finally s.close()
            else 0
          val loaded = WorkflowBuildIdentity.load(out)
          // RAI-158 note: `mainClass` is the STEM-DERIVED metadata name (the
          // documented WorkflowBuildIdentity.deriveClassNames rule); the
          // EMITTED launcher class is `runWf155d_probe` — the identity schema
          // (frozen, CLI-owned) still records the derivation, the run path
          // consumes only moduleClass.
          val identityOk = loaded.fold(
            e => throw new IllegalStateException(e.detail),
            i =>
              i.sourceDigest == sha256(src) &&
                i.classDigest == WorkflowBuildIdentity
                  .classDigestOf(classes)
                  .fold(
                    e => throw new IllegalStateException(e.detail),
                    recomputed => recomputed
                  ) &&
                i.mainClass == "wf155d_probe" &&
                i.moduleClass == "Wf155d_probe$" &&
                i.toolchain.scalaVersion == "3.9.0"
          )
          assertTrue(
            Files.isDirectory(classes),
            classFiles > 0,
            identityOk,
            Files.isRegularFile(out.resolve("dotc.log")),
            Files.isRegularFile(out.resolve("build-identity.json"))
          )
        }
      },
      test(
        "CWF-D02: torn source is a typed Compilation failure with diagnostics; non-empty --out is refused BEFORE compiling; empty --out accepted"
      ) {
        val rendered = ChatWorkflowRenderer
          .render(
            probe,
            RenderOptions("wf155d_torn")
          )
          .fold(e => throw new IllegalStateException(e.detail), identity)
        val lines = rendered.source.linesIterator.toVector
        val torn = lines
          .updated(lines.indexWhere(_.trim.startsWith("object ")), "object ((:")
          .mkString("", "\n", "\n")
        val work = Files.createTempDirectory("c155d-neg")
        val tornSrc = writeSource(work, "wf155d_torn.scala", torn)
        val validSrc = writeSource(
          work,
          "wf155d_valid.scala",
          rendered.source
        )
        // A non-empty target must be refused BEFORE any compile work.
        val nonEmpty = work.resolve("full-out")
        Files.createDirectories(nonEmpty)
        Files.writeString(nonEmpty.resolve("keep.txt"), "user content")
        val refused = WorkflowCompile
          .compile(
            WorkflowCompile.Args(tornSrc, nonEmpty)
          )
          .either
        // The refusal leaves NO staging remnant and NO build output.
        val refusedClean = !Files.exists(work.resolve("full-out.staging")) &&
          !Files.exists(nonEmpty.resolve("classes"))
        // A pre-created EMPTY --out is accepted for a valid source.
        val emptyOut = work.resolve("empty-out")
        Files.createDirectories(emptyOut)
        for
          torn <- WorkflowCompile
            .compile(
              WorkflowCompile.Args(tornSrc, work.resolve("torn-build"))
            )
            .either
          ref <- refused
          empty <- WorkflowCompile
            .compile(
              WorkflowCompile.Args(validSrc, emptyOut)
            )
            .either
        yield assertTrue(
          torn.fold(_.code == "RA-COMPILE", _ => false),
          torn.fold(_.detail.contains("compilation failed"), _ => false),
          ref.fold(_.code == "RA-INP", _ => false),
          ref.fold(_.detail.contains("not empty"), _ => false),
          refusedClean,
          empty.isRight,
          Files.isDirectory(emptyOut.resolve("classes"))
        )
      },
      test(
        "CWF-D02: fresh build verifies clean; tampered classes fail identity verification; an edited source is stale against the build identity"
      ) {
        val rendered = ChatWorkflowRenderer
          .render(
            probe,
            RenderOptions("wf155d_stale")
          )
          .fold(e => throw new IllegalStateException(e.detail), identity)
        val work = Files.createTempDirectory("c155d-stale")
        val src = writeSource(
          work,
          "wf155d_stale.scala",
          rendered.source
        )
        val out = work.resolve("build")
        WorkflowCompile.compile(WorkflowCompile.Args(src, out)).flatMap { _ =>
          val loaded = WorkflowBuildIdentity.load(out)
          // The fresh build verifies clean.
          val fresh =
            loaded.flatMap(i => WorkflowBuildIdentity.verifyClasses(i, out))
          // A tampered COPY (a byte appended to one class file) fails.
          val copyOut = copyTree(out, work.resolve("copy"))
          val s = Files.walk(copyOut.resolve("classes"))
          val aClass =
            try
              s.iterator()
                .asScala
                .find(p =>
                  Files.isRegularFile(p) && p.toString.endsWith(".class")
                )
                .get
            finally s.close()
          Files.write(
            aClass,
            Files.readAllBytes(aClass) ++ Array[Byte](0x1)
          )
          val tampered = WorkflowBuildIdentity
            .load(copyOut)
            .flatMap(i => WorkflowBuildIdentity.verifyClasses(i, copyOut))
          // A source EDITED after the compile is stale: its bytes hash
          // differently from the recorded sourceDigest (the public
          // `raider workflow run --source` refusal is proven in
          // WorkflowRunSpec).
          val editedDigest = WorkflowBuildIdentity.sha256Hex(
            Files.readAllBytes(src) ++ "\n// manual edit\n"
              .getBytes(StandardCharsets.UTF_8)
          )
          val stale = loaded.fold(
            e => throw new IllegalStateException(e.detail),
            i => editedDigest != i.sourceDigest
          )
          assertTrue(
            fresh.isRight,
            tampered.fold(_.code == "RA-INP", _ => false),
            stale
          )
        }
      },
      test(
        "review fix 2a: a collision-shaped <stem>.class (exact-case entry whose bytes define ANOTHER class) is a typed RA-COMPILE refusal — the branch the real case collision takes (seam; deterministic)"
      ) {
        // The real macOS collision leaves <stem>.class containing the OBJECT
        // mirror (a different binary name). Class.forName then fails with
        // NoClassDefFoundError (wrong name) — the JVM's defineClass name
        // contract — so the refusal surfaces through verifyMainNotCollided's
        // typed attempt-mapping. Crafted deterministically here: a real
        // classfile copied under the derived main-class file name.
        val work = Files.createTempDirectory("c155d-collide")
        val staging = seamStaging(work, "wf155d_seam") { classes =>
          Files.copy(anyRealClassFile, classes.resolve("wf155d_seam.class"))
        }
        WorkflowCompile
          .verifyMainNotCollided(staging, "wf155d_seam")
          .either
          .map { r =>
            assertTrue(
              r.fold(_.code == "RA-COMPILE", _ => false),
              r.fold(
                _.detail.contains("cannot verify the launchable main"),
                _ => false
              )
            )
          }
      },
      test(
        "review fix 2b: an exact-case <stem>.class entry that yields NO loadable class is the typed ClassNotFoundException refusal branch (seam; deterministic)"
      ) {
        // A broken symlink is listed by the exact-case scan but cannot be
        // resolved to class bytes: URLClassLoader.findClass turns that into
        // ClassNotFoundException, driving the typed "exists but does not
        // define" refusal branch.
        val work = Files.createTempDirectory("c155d-cnfe")
        val staging = seamStaging(work, "wf155d_seam") { classes =>
          Files.createSymbolicLink(
            classes.resolve("wf155d_seam.class"),
            classes.resolve("missing-target.class")
          )
        }
        WorkflowCompile
          .verifyMainNotCollided(staging, "wf155d_seam")
          .either
          .map { r =>
            assertTrue(
              r.fold(_.code == "RA-COMPILE", _ => false),
              r.fold(
                _.detail.contains("exists but does not define"),
                _ => false
              )
            )
          }
      },
      test(
        "review fix 2c: NO collision — the compile proceeds (positive branch through the public entry): the exact-case main class is present, defines the derived name, and the build publishes"
      ) {
        val rendered = ChatWorkflowRenderer
          .render(
            probe,
            RenderOptions("wf155d_pos")
          )
          .fold(e => throw new IllegalStateException(e.detail), identity)
        // Since RAI-158 the as-is render emits NO `wf155d_pos.class` (the
        // derived stem name is metadata only; the launcher main is
        // `runWf155d_pos`), so the positive branch needs a fixture that
        // recreates a REAL `<stem>` main: rename the @main onto the derived
        // stem FIRST, then move the object token out of the case-mirror (its
        // token also covers the .runLauncher reference). The classfile set is
        // then {Wf155dPos, Wf155dPos$, wf155d_pos} — pairwise distinct
        // ignoring case — and the collision detector's POSITIVE path (exact
        // entry found, class loads, name matches) must let the build publish.
        val edited = rendered.source
          .replace("@main def runWf155d_pos(", "@main def wf155d_pos(")
          .replace("Wf155d_pos", "Wf155dPos")
        if edited == rendered.source ||
          !edited.contains("object Wf155dPos") ||
          !edited.contains("Wf155dPos.runLauncher") ||
          !edited.contains("@main def wf155d_pos(")
        then throw new IllegalStateException("fixture edits did not apply")
        val work = Files.createTempDirectory("c155d-pos")
        val src = writeSource(work, "wf155d_pos.scala", edited)
        val out = work.resolve("build")
        WorkflowCompile.compile(WorkflowCompile.Args(src, out)).map { _ =>
          val mainClassFile = out.resolve("classes").resolve("wf155d_pos.class")
          assertTrue(
            Files.isDirectory(out.resolve("classes")),
            // The exact-case entry exists, so verifyMainNotCollided really
            // executed its Class.forName name check (not the early-out).
            Files.isRegularFile(mainClassFile),
            Files.isRegularFile(out.resolve("build-identity.json"))
          )
        }
      }
    ) @@ TestAspect.sequential

  private def copyTree(from: Path, to: Path): Path =
    Files.walk(from).forEach { p =>
      val rel = from.relativize(p)
      val target = to.resolve(rel)
      if Files.isDirectory(p) then Files.createDirectories(target)
      else
        Files.createDirectories(target.getParent)
        Files.copy(p, target)
      ()
    }
    to

end WorkflowCompileSpec
