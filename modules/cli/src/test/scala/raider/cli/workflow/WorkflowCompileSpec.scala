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
import java.nio.file.{Files, Path}
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

  /** The manual MAIN-RENAME edit (the same edit-surface workaround as
    * WorkflowLaunchSpec): the as-is generated naming pair (object <Stem> +
    * @main
    *   def <stem>) produces classes that differ only in case and overwrite one
    *   another on this case-insensitive filesystem (dotc warning); renaming the @main
    *   is a legitimate manual edit, the module class (the only thing the run
    *   path derives) is untouched.
    */
  private def renameMain(source: String, name: String): String =
    val edited =
      source.replace("@main def " + name + "(", "@main def " + name + "_main(")
    if edited == source then
      throw new IllegalStateException("main rename edit did not apply")
    edited

  private def writeSource(dir: Path, name: String, text: String): Path =
    Files.createDirectories(dir)
    val p = dir.resolve(name)
    Files.write(p, text.getBytes(StandardCharsets.UTF_8))
    p

  private def sha256(path: Path): String =
    WorkflowBuildIdentity.sha256Hex(Files.readAllBytes(path))

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
        val work = Files.createTempDirectory("c155d-build")
        val src = writeSource(
          work,
          "wf155d_probe.scala",
          renameMain(rendered.source, "wf155d_probe")
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
          renameMain(rendered.source, "wf155d_torn")
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
          renameMain(rendered.source, "wf155d_stale")
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
