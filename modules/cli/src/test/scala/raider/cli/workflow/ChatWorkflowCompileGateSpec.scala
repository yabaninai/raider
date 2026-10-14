package raider.cli.workflow

import zio.test.*
import zio.test.Assertion.*

import raider.core.{
  CapabilityStatus,
  ModelBackend,
  ModelCapabilities,
  ModelEvent,
  ModelRequest,
  Program,
  RaiderError,
  Task,
  ToolRegistry
}
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
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** CWF-C01..C03 compile-and-behavior gate for the RAI-155.c chat-workflow
  * generator (ADR-033 §4/§6), modeled on the LIN2-01 lineage compile gate:
  *
  *   - CWF-C01: the representative STS export (the committed golden fixture
  *     selection) compiles under the PINNED dotc 3.9.0 (`artifacts/tools/m2`)
  *     as executable Scala — a real `Program[String,String]` over the existing
  *     runtime (AgentLoop agent stage, ProcessBuilder checks, Option-L
  *     launcher) — and the repeat render is byte-identical and re-compiles.
  *   - The hostile-payload export (U+0001/U+0003/U+0005, DEL, quotes,
  *     backslashes, newlines and a shell-injection attempt — all pure DATA)
  *     also compiles (exit 0), carries the `\uXXXX` literal discipline, keeps
  *     `\xHH` lexically inert inside `//` comments only, and a torn object
  *     header honestly does NOT compile (timeout/IO is never counted as
  *     non-compilation evidence).
  *   - WNF-01 (RAI-158): the pinned-dotc compile emits ZERO class-overwrite
  *     warnings on this case-insensitive filesystem (the log is read — the
  *     warning does not fail compilation), no two emitted class files collide
  *     ignoring case, and the generated @main launcher really LAUNCHES as a
  *     plain main (typed RA-INP failure on a missing input — the local-launch
  *     leg RAI-155.d found unreliable under the old case-mirror naming).
  *   - Behavior probes on the LOADED artifact (child classloader, parent = this
  *     test JVM, so raider/zio classes resolve from the same product classes
  *     the compiler ran against): (1) zero-dispatch laziness — an invalid input
  *     fails typed at the decode boundary and a sentinel backend is never
  *     touched; a valid input reaches the agent stage and the sentinel IS
  *     touched (the stage is real, lazy and wired; it fails typed against the
  *     sentinel, so no check runs); (2) second-input behavior — the SAME
  *     compiled artifact runs an input-dependent check (`/bin/test -f
  *     marker.txt`) differently per workspace input (CWF-C01 "accepts a second
  *     task/workspace input"; externalized parameters are read at execution);
  *     (3) edit-without-regeneration — editing the rendered SOURCE text (the
  *     check parameter `List("/usr/bin/true")` → `List("/usr/bin/false")`),
  *     recompiling, and running the GENERATED `runAllChecks` over the compiled
  *     `trustedChecks` constants on a marker-present workspace: the original
  *     artifact is green, the edited artifact fails its own "pass" check
  *     (CWF-C01 "editing a prompt/check parameter survives compilation and
  *     changes the later result"). NEGATIVE CONTROL: no check argv is passed by
  *     hand in this probe — if the source edit were reverted, the edited
  *     artifact would behave exactly like the original and the assertions would
  *     fail, so a vacuous red cannot pass. Editable constants are the source
  *     edit surface; the run-time task/workspace input is the input-JSON
  *     surface — both externalized and documented by the manifest.
  *
  * No provider/tool dispatch happens anywhere in this gate: the sentinel
  * backend replaces the transport, and every run that would dispatch fails
  * typed BEFORE spending anything.
  *
  * Classpath (same discipline as LineageCompileGateSpec): cli tests run FORKED,
  * so `java.class.path` is the real module test classpath (raider
  * core/runtime/tools/dsl/cli/provider classes + zio + zio-json); the PINNED m2
  * scala-library 3.9.0 is prepended and always wins resolution.
  */
object ChatWorkflowCompileGateSpec extends ZIOSpecDefault:

  // ----- fixtures -------------------------------------------------------------

  /** The representative selection: the same lifecycle shape as the committed
    * golden example (multi-turn repair intent + two refinements + named checks
    * + bounded agent stage). Provider/model are non-secret references to a
    * LOCAL stand.
    */
  private val representative = StsSelection(
    sessionId = "sess-20261014-fix-widget",
    intent = StsTurn(
      "t1",
      StsTurnKind.Intent,
      StsTurnStatus.Completed,
      "Make the failing widget module compile again and keep its checks green"
    ),
    refinements = List(
      StsTurn(
        "t2",
        StsTurnKind.Refinement,
        StsTurnStatus.Completed,
        "Do not touch the public API"
      ),
      StsTurn(
        "t3",
        StsTurnKind.Refinement,
        StsTurnStatus.Completed,
        "Prefer the smallest possible diff"
      )
    ),
    taskParams = List(
      StsParam("targetPath", "src/main/scala/widget/Widget.scala"),
      StsParam(
        "summary",
        "Widget.scala does not compile after the 0.4 refactor"
      )
    ),
    checks = List(
      NamedCheck("compile", List("sbt", "--batch", "compile")),
      NamedCheck("widget-tests", List("sbt", "--batch", "widget/test"))
    ),
    agentStage = StsAgentStage(maxRounds = 6),
    origin = StsOrigin(
      providerKind = "openai-compatible",
      model = "mock-coder",
      baseUrl = Some("http://127.0.0.1:8901/v1")
    )
  )

  private val representativeOptions = RenderOptions("fix_widget_task")

  /** Gate-local probe export: checks are LOCAL deterministic commands so the
    * loaded artifact can be executed inside the gate without any provider or
    * build tool (the marker check is input-dependent; the pass check is the
    * edit-probe's parameter).
    */
  private val probe = StsSelection(
    sessionId = "sess-cwf-gate-probe",
    intent = StsTurn(
      "p1",
      StsTurnKind.Intent,
      StsTurnStatus.Completed,
      "Gate probe intent: keep the marker file green"
    ),
    refinements = List(
      StsTurn(
        "p2",
        StsTurnKind.Refinement,
        StsTurnStatus.Completed,
        "Probe refinement: no extra work"
      )
    ),
    taskParams = List(
      StsParam("targetPath", "marker.txt"),
      StsParam("summary", "probe summary")
    ),
    checks = List(
      NamedCheck("marker", List("/bin/test", "-f", "marker.txt")),
      NamedCheck("pass", List("/usr/bin/true"))
    ),
    agentStage = StsAgentStage(maxRounds = 3),
    origin = StsOrigin(
      providerKind = "openai-compatible",
      model = "gate-probe-model",
      baseUrl = Some("http://127.0.0.1:8902/v1")
    )
  )

  private val probeOptions = RenderOptions("cwf_gate_probe")

  /** Hostile fixture built in code (frozen documents stay untouched, same
    * discipline as the ESC-01 lineage fixture). Control characters in check
    * ARGV are a legitimate R1 refusal (argv must be printable tokens), so the
    * hostile payload lives in texts, ids, names, param values and the origin.
    */
  private val hostile: StsSelection = representative.copy(
    sessionId = "sess-\u0001-hostile\"quote",
    intent = representative.intent.copy(
      turnId = "t\u0001i\"nl",
      text = "He said \"run `rm -rf /tmp/x` now\"\nand do it \u0001 FAST\\"
    ),
    refinements = List(
      StsTurn(
        "t2",
        StsTurnKind.Refinement,
        StsTurnStatus.Completed,
        "line1\r\ntab\there\u007fEND"
      )
    ),
    taskParams = List(
      StsParam("targetPath", "pa\"th\u0002one"),
      StsParam("summary", "sum; $(rm -rf /) `whoami` \\ end")
    ),
    checks = List(
      NamedCheck("check\u0003one", List("echo", "a\"b\\c"))
    ),
    origin = StsOrigin(
      providerKind = "anthropic-compatible",
      model = "mo\u0005del\"x",
      baseUrl = Some("http://127.0.0.1:1/v\u00061")
    )
  )

  // ----- pinned-dotc compile gate ---------------------------------------------

  private val CompileTimeoutSeconds = 120L

  private def findAncestor(segments: String*): Path =
    val cwd = Path.of("").toAbsolutePath
    Iterator
      .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) =>
        dir
      }
      .find(dir => Files.isDirectory(dir.resolve(segments.mkString("/"))))
      .getOrElse(
        throw new IllegalStateException(
          "no ancestor of " + cwd + " contains " + segments.mkString("/")
        )
      )

  private lazy val compileDir: Path =
    findAncestor("artifacts", "tools", "m2")
      .resolve("modules")
      .resolve("cli")
      .resolve("target")
      .resolve("chat-workflow-compile")

  private def m2Jar(rel: String): Path =
    val p = findAncestor("artifacts", "tools", "m2")
      .resolve("artifacts")
      .resolve("tools")
      .resolve("m2")
      .resolve(rel)
    if Files.isRegularFile(p) then p
    else throw new IllegalStateException("missing pinned jar: " + p)

  /** The pinned dotc launcher classpath — the canonical COMPILER_CP of
    * scripts/build/compile.sh (standalone dotc 3.9.0 needs the xsbti classes;
    * verified by the LIN2-01 fixture run).
    */
  private lazy val compilerClasspath: String =
    Vector(
      "org/scala-lang/scala3-compiler_3/3.9.0/scala3-compiler_3-3.9.0.jar",
      "org/scala-lang/scala3-interfaces/3.9.0/scala3-interfaces-3.9.0.jar",
      "org/scala-lang/tasty-core_3/3.9.0/tasty-core_3-3.9.0.jar",
      "org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar",
      "org/scala-lang/modules/scala-asm/9.9.0-scala-1/scala-asm-9.9.0-scala-1.jar",
      "org/scala-sbt/compiler-interface/1.12.0/compiler-interface-1.12.0.jar",
      "org/scala-sbt/util-interface/1.11.5/util-interface-1.11.5.jar"
    ).map(m2Jar).mkString(File.pathSeparator)

  /** The prescribed `-classpath` for the generated source: pinned m2
    * scala-library first (an sbt-launcher scala-library cannot be consumed by
    * dotc 3.9.0), then this forked test JVM's own classpath carrying the raider
    * module classes and zio/zio-json.
    */
  private lazy val userClasspath: String =
    val lib = m2Jar(
      "org/scala-lang/scala-library/3.9.0/scala-library-3.9.0.jar"
    )
    val base = System.getProperty("java.class.path", "")
    if base.isEmpty then lib.toString
    else lib.toString + File.pathSeparator + base

  private final case class Compiled(exit: Int, logTail: String)

  private def dotc(
      tag: String,
      source: Path,
      outName: String
  ): Either[String, Compiled] =
    for
      outDir <- freshDir(compileDir.resolve(outName))
      log = compileDir.resolve(tag + ".dotc.log")
      compiled <- runDotc(log, outDir, source)
    yield compiled

  private def freshDir(p: Path): Either[String, Path] =
    try
      if Files.isDirectory(p) then
        val listing = Files.list(p)
        try listing.forEach(entry => Files.delete(entry))
        finally listing.close()
        Files.delete(p)
      Files.createDirectories(p)
      Right(p)
    catch case t: Throwable => Left("fresh dir " + p + ": " + t)

  private def runDotc(
      log: Path,
      outDir: Path,
      source: Path
  ): Either[String, Compiled] =
    try
      Files.createDirectories(compileDir)
      val pb = new ProcessBuilder(
        "java",
        "-Xmx2g",
        "-cp",
        compilerClasspath,
        "dotty.tools.dotc.Main",
        "-classpath",
        userClasspath,
        "-d",
        outDir.toString,
        source.toString
      )
      pb.redirectErrorStream(true)
      pb.redirectOutput(log.toFile)
      val proc = pb.start()
      if !proc.waitFor(CompileTimeoutSeconds, TimeUnit.SECONDS) then
        proc.destroyForcibly()
        Left(
          "dotc did not finish within " + CompileTimeoutSeconds + "s (destroyed): " + source
        )
      else
        val all =
          Files.readAllLines(log, StandardCharsets.UTF_8).asScala.toVector
        val tail = if all.size <= 40 then all else all.takeRight(40)
        Right(Compiled(proc.exitValue(), tail.mkString("\n")))
    catch case t: Throwable => Left("dotc launch failed: " + t)

  private def writeRender(tag: String, src: String): Either[String, Path] =
    try
      Files.createDirectories(compileDir)
      val p = compileDir.resolve(tag + ".scala")
      Files.write(p, src.getBytes(StandardCharsets.UTF_8))
      Right(p)
    catch case t: Throwable => Left(tag + ".scala write failed: " + t)

  private def renderOrThrow(
      s: StsSelection,
      o: RenderOptions
  ): raider.dsl.workflow.RenderedExport =
    ChatWorkflowRenderer.render(s, o) match
      case Right(value) => value
      case Left(error)  => throw new IllegalStateException(error.detail)

  private def requireRight[A](e: Either[String, A], what: String): A =
    e match
      case Right(a) => a
      case Left(err) =>
        throw new IllegalStateException(what + " failed: " + err)

  private def requireExit0(res: Either[String, Compiled], what: String): Unit =
    res match
      case Right(Compiled(0, _)) => ()
      case Right(Compiled(exit, tail)) =>
        throw new IllegalStateException(
          what + ": expected exit 0, got " + exit + "; dotc log tail:\n" + tail
        )
      case Left(err) => throw new IllegalStateException(what + ": " + err)

  private def requireNonExit0(
      res: Either[String, Compiled],
      what: String
  ): Unit =
    res match
      case Right(Compiled(exit, _)) if exit != 0 => ()
      case Right(Compiled(exit, _)) =>
        throw new IllegalStateException(
          what + ": corrupted source compiled (exit " + exit +
            ") — negative contract violated"
        )
      case Left(err) =>
        throw new IllegalStateException(
          what + ": " + err + " (timeout/IO is NOT honest non-compilation evidence)"
        )

  /** RAI-151 invariant for the generated artifacts: `\xHH` must sit after the
    * `//` of its line (lexically inert only inside comments).
    */
  private def commentOnlyEscapeViolations(src: String): Vector[String] =
    src.linesIterator.zipWithIndex.flatMap { (line, i) =>
      if !line.contains("\\x") then None
      else
        val escapeIdx = line.indexOf("\\x")
        val commentIdx = line.indexOf("//")
        if commentIdx < 0 || escapeIdx < commentIdx then
          Some("line " + (i + 1) + ": \\x escape outside a // comment: " + line)
        else None
    }.toVector

  /** Composition-shape check (checkable by .e per ADR-033 §6.3): the export is
    * a real Program over the existing runtime — decoding inside the boundary,
    * one bounded agent stage, deterministic checks, explicit launcher — never a
    * comment-only skeleton with fixed outcomes.
    */
  private def assertRealComposition(src: String, what: String): Unit =
    val required = Vector(
      "Program.fromFunction(",
      "decodeOrRefuse(rawInput)",
      "agentStage(launch, prompt)",
      "runAllChecks(input)",
      "AgentLoop.newRoot()",
      "loop.runText(",
      "new ProcessBuilder(",
      "raider.cli.run.Credentials.resolve(credentialProvider)",
      "Admission.make(Right(limits))",
      "BudgetLimits.make(maxAttempts = maxRounds)",
      "CostEstimate.Unknown",
      "AgentLoop.InternalMarker",
      "@main def"
    )
    val missing = required.filterNot(src.contains)
    if missing.nonEmpty then
      throw new IllegalStateException(
        what + ": generated source lacks real composition: " + missing.mkString(
          ", "
        )
      )

  // ----- loaded-artifact behavior probes ---------------------------------------

  /** Sentinel backend: records every stream attempt; the probes use its count
    * to prove dispatch laziness (no model call before the agent stage) and that
    * the agent stage is really wired to the backend.
    */
  private final class SentinelBackend(counter: AtomicInteger)
      extends ModelBackend:

    def capabilities: ModelCapabilities =
      ModelCapabilities(
        streaming = CapabilityStatus.Supported,
        tools = CapabilityStatus.Unsupported,
        cancellationAck = CapabilityStatus.Unknown
      )

    def stream(
        input: ModelRequest
    ): zio.stream.ZStream[zio.Scope, RaiderError, ModelEvent] =
      zio.stream.ZStream
        .fromZIO(zio.ZIO.succeed(counter.incrementAndGet()))
        .drain ++
        zio.stream.ZStream.fail(
          RaiderError.ProviderUnavailable("sentinel stand (gate probe)")
        )

  /** Console-adapter-style blocking run of a generated Task value through the
    * single interpreter boundary (no second semantics).
    */
  private def runTask[A](task: Task[A]): Either[RaiderError, A] =
    zio.Unsafe.unsafe { implicit u =>
      zio.Runtime.default.unsafe.run(Task.zio(task).either).getOrThrow()
    }

  /** Reflection façade over the compiled probe object. */
  private final case class LoadedProbe(
      inputClass: Class[?],
      invoke: (String, Seq[AnyRef]) => AnyRef
  )

  private def loadProbe(outDir: Path, objectName: String): LoadedProbe =
    val loader =
      new java.net.URLClassLoader(
        Array(outDir.toUri.toURL),
        getClass.getClassLoader
      )
    val cls = Class.forName(objectName + "$", true, loader)
    val module =
      cls.getField("MODULE$").get(null) // scalafix:ok DisableSyntax.null
    val inputClass = Class.forName(objectName + "$Input", true, loader)
    LoadedProbe(
      inputClass,
      (name, args) =>
        // Lookup by NAME: parameter-class matching breaks on erased generic
        // params (List[String] arrives as ::), and these generated member
        // names are unique within the object.
        val candidates = cls.getDeclaredMethods.filter(_.getName == name)
        if candidates.isEmpty then
          throw new NoSuchMethodException(objectName + "$." + name)
        val m = candidates.head
        m.setAccessible(true)
        m.invoke(module, args*)
    )

  /** Builds an Input value (schemaVersion 1) through the compiled case class.
    */
  private def newInput(probe: LoadedProbe, workspace: Path): AnyRef =
    val loader = probe.inputClass.getClassLoader
    val taskCls = Class.forName("Cwf_gate_probe$TaskParams", true, loader)
    val task = taskCls
      .getDeclaredConstructor(classOf[String], classOf[String])
      .newInstance("marker.txt", "probe summary")
    probe.inputClass
      .getDeclaredConstructor(classOf[Int], classOf[String], taskCls)
      .newInstance(Integer.valueOf(1), workspace.toString, task)

  private def inputJson(workspace: Path): String =
    "{\n  \"schemaVersion\": 1,\n  \"workspace\": \"" +
      workspace.toString.replace("\\", "\\\\") +
      "\",\n  \"task\": {\n    \"targetPath\": \"marker.txt\",\n" +
      "    \"summary\": \"probe summary\"\n  }\n}"

  private def newAdmission: (Admission, BudgetLimits) =
    val limits = BudgetLimits.make(maxAttempts = 3).toOption.get
    val admission =
      zio.Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe
          .run(Admission.make(Right(limits)))
          .getOrThrow()
      }
    (admission, limits)

  /** Builds a Launch value through the compiled case class. */
  private def newLaunch(probe: LoadedProbe, backend: ModelBackend): AnyRef =
    val loader = probe.inputClass.getClassLoader
    val launchClass = Class.forName("Cwf_gate_probe$Launch", true, loader)
    val (admission, limits) = newAdmission
    launchClass
      .getDeclaredConstructor(
        classOf[ModelBackend],
        classOf[ToolRegistry],
        classOf[Admission],
        classOf[BudgetLimits]
      )
      .newInstance(backend, ToolRegistry.empty, admission, limits)

  private def deleteRecursively(p: Path): Unit =
    try
      if Files.isDirectory(p) then
        val listing = Files.list(p)
        try
          listing.forEach { child =>
            if Files.isDirectory(child) && !Files.isSymbolicLink(child) then
              deleteRecursively(child)
            else Files.deleteIfExists(child)
          }
        finally listing.close()
      Files.deleteIfExists(p)
    catch case NonFatal(_) => ()

  // ----- spec ------------------------------------------------------------------

  def spec =
    suite("CWF-C chat-workflow compile-and-behavior gate (pinned dotc 3.9.0)")(
      test(
        "CWF-C01: representative export compiles with pinned dotc (exit 0) as a real Program"
      ) {
        val rendered = renderOrThrow(representative, representativeOptions)
        val path = requireRight(
          writeRender("representative", rendered.source),
          "write representative render"
        )
        requireExit0(
          dotc("representative", path, "out-representative"),
          "representative compile"
        )
        assertRealComposition(rendered.source, "representative render")
        val manifestOk =
          rendered.manifestJson.contains("\"schemaVersion\": 1") &&
            rendered.manifestJson.contains(
              "\"kind\": \"chat-workflow-export\""
            ) &&
            rendered.manifestJson.contains(
              "\"sourceDigest\": \"sha256-" + rendered.sourceDigest + "\""
            )
        val inputOk =
          rendered.inputExampleJson.contains("\"schemaVersion\": 1") &&
            rendered.inputExampleJson.contains(
              "src/main/scala/widget/Widget.scala"
            )
        assertTrue(
          rendered.source.contains("object Fix_widget_task:"),
          rendered.source.contains(
            "@main def runFix_widget_task(args: String*): Unit ="
          ),
          manifestOk,
          inputOk,
          Files.exists(path)
        )
      },
      test(
        "WNF-01 (RAI-158): pinned-dotc compile emits ZERO class-overwrite warnings on this case-insensitive FS, no two emitted classes collide ignoring case, and the @main launcher LAUNCHES (typed RA-INP on a missing input)"
      ) {
        val rendered = renderOrThrow(probe, probeOptions)
        val path = requireRight(
          writeRender("wnf01", rendered.source),
          "write wnf01 render"
        )
        requireExit0(dotc("wnf01", path, "out-wnf01"), "wnf01 compile")
        // ZERO class-overwrite warnings: the RAI-155.d finding was dotc's
        // "Generated class X differs only in case from y" warning pair, which
        // does NOT fail compilation — the gate must read the log, not the
        // exit code.
        val logLines =
          Files
            .readAllLines(
              compileDir.resolve("wnf01.dotc.log"),
              StandardCharsets.UTF_8
            )
            .asScala
            .toVector
        val caseWarnings =
          logLines.filter(_.contains("differs only in case")).toVector
        // Exact-case tripwire over the produced classes: no two emitted
        // class files may collide ignoring case (a naive Files.exists check
        // false-positives on this case-insensitive filesystem — the
        // WorkflowCompile exact-case-scan discipline).
        val outDir = compileDir.resolve("out-wnf01")
        val walk = Files.list(outDir)
        val classFiles =
          try
            walk
              .iterator()
              .asScala
              .filter(p => p.toString.endsWith(".class"))
              .toVector
          finally walk.close()
        val byLower =
          classFiles.groupBy(p => p.getFileName.toString.toLowerCase)
        val collided = byLower
          .collect {
            case (k, v) if v.sizeIs > 1 =>
              k + " -> " + v.map(_.getFileName.toString).mkString(", ")
          }
          .toVector
          .sorted
        val launcherClassPresent =
          classFiles.exists(_.getFileName.toString == "runCwf_gate_probe.class")
        // Launch-the-main assertion (the leg RAI-155.d found unreliable under
        // the collision): run the compiled @main entry as a PLAIN main. The
        // input path does not exist, so the launcher must fail TYPED at the
        // input boundary — exit 1 with the `raider RA-INP` line on the merged
        // output — before any credential/provider contact. If the case pair
        // had eaten the launcher class, the child dies untyped with
        // "Could not find or load main class" and no raider line.
        val missingInput =
          compileDir.resolve("wnf01-no-such-input.json")
        Files.deleteIfExists(missingInput)
        val pb = new ProcessBuilder(
          Vector(
            "java",
            "-cp",
            userClasspath + File.pathSeparator + outDir,
            "runCwf_gate_probe",
            missingInput.toString
          )*
        )
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val finished = proc.waitFor(120, TimeUnit.SECONDS)
        val exit = if finished then proc.exitValue() else -1
        val output =
          new String(proc.getInputStream.readAllBytes, StandardCharsets.UTF_8)
        if !finished then proc.destroyForcibly()
        assertTrue(
          caseWarnings.isEmpty,
          collided.isEmpty,
          launcherClassPresent,
          finished,
          exit == 1,
          output.contains("raider RA-INP")
        )
      },
      test(
        "CWF-C01-B: repeat render is byte-identical and the repeat artifact re-compiles (exit 0)"
      ) {
        val first = renderOrThrow(representative, representativeOptions)
        val second = renderOrThrow(representative, representativeOptions)
        val path = requireRight(
          writeRender("representative-repeat", second.source),
          "write repeat render"
        )
        requireExit0(
          dotc("representative-repeat", path, "out-representative-repeat"),
          "repeat compile"
        )
        assertTrue(
          first.source == second.source,
          first.manifestJson == second.manifestJson,
          first.inputExampleJson == second.inputExampleJson,
          first.sourceDigest == second.sourceDigest,
          first.specDigest == second.specDigest,
          first.source.getBytes(StandardCharsets.UTF_8).toSeq ==
            second.source.getBytes(StandardCharsets.UTF_8).toSeq
        )
      },
      test(
        "hostile export compiles (exit 0); \\uXXXX literals; \\xHH only in comments; torn header does NOT compile"
      ) {
        val hostileOptions = probeOptions.copy(name = "cwf_gate_hostile")
        val first = renderOrThrow(hostile, hostileOptions)
        val second = renderOrThrow(hostile, hostileOptions)
        val path = requireRight(
          writeRender("hostile", first.source),
          "write hostile render"
        )
        requireExit0(dotc("hostile", path, "out-hostile"), "hostile compile")
        val violations = commentOnlyEscapeViolations(first.source)
        if violations.nonEmpty then
          throw new IllegalStateException(
            "hostile render escape violations: " + violations.mkString("; ")
          )
        // Negative: a torn object header is honest non-compilation evidence.
        val lines = first.source.linesIterator.toVector
        val torn = lines
          .updated(lines.indexWhere(_.trim.startsWith("object ")), "object ((:")
          .mkString("", "\n", "\n")
        val tornPath =
          requireRight(writeRender("hostile-torn", torn), "write torn render")
        requireNonExit0(
          dotc("hostile-torn", tornPath, "out-hostile-torn"),
          "torn compile"
        )
        assertTrue(
          first.source == second.source,
          first.source.contains("\\u0001"),
          first.source.contains("sess-\\x01-hostile\\\"quote"),
          violations.isEmpty,
          first.manifestJson.contains("\\u0001")
        )
      },
      test(
        "CWF-C02/C03 probes: zero-dispatch laziness, second-input behavior, edit-without-regeneration on the loaded artifact"
      ) {
        val rendered = renderOrThrow(probe, probeOptions)
        assertRealComposition(rendered.source, "probe render")
        val path = requireRight(
          writeRender("probe", rendered.source),
          "write probe render"
        )
        requireExit0(dotc("probe", path, "out-probe"), "probe compile")
        val loaded =
          loadProbe(compileDir.resolve("out-probe"), "Cwf_gate_probe")

        // Workspace inputs: ws1 carries the marker file, ws2 does not.
        val ws1 = Files.createTempDirectory("cwf-gate-ws1")
        Files.writeString(ws1.resolve("marker.txt"), "green\n")
        val ws2 = Files.createTempDirectory("cwf-gate-ws2")

        // -- (1) zero-dispatch laziness --------------------------------------
        val counter = new AtomicInteger(0)
        val sentinel = SentinelBackend(counter)
        val badDecode = runTask(
          loaded
            .invoke("decodeOrRefuse", Seq("{ not json"))
            .asInstanceOf[Task[?]]
        )
        val decodeTyped = badDecode match
          case Left(e: RaiderError.InputValidation) =>
            e.detail.contains("CWF-input")
          case other =>
            throw new IllegalStateException(
              "expected typed InputValidation at the decode boundary, got: " + other
            )
        val sentinelUntouchedDecode = counter.get() == 0

        val launch = newLaunch(loaded, sentinel)
        val workflow = loaded
          .invoke("workflow", Seq(launch))
          .asInstanceOf[Program[String, String]]
        val badRun = runTask(workflow("{ not json"))
        val badRunTyped = badRun match
          case Left(e: RaiderError.InputValidation) => true
          case other =>
            throw new IllegalStateException(
              "expected typed InputValidation from the workflow over invalid JSON, got: " + other
            )
        val sentinelUntouchedBadRun = counter.get() == 0

        // A VALID input reaches the agent stage: the sentinel IS touched (the
        // stage is real and lazy) and fails typed before any check runs.
        val rawGood = inputJson(ws1)
        val goodRun = runTask(workflow(rawGood))
        val goodRunTyped = goodRun match
          case Left(e: RaiderError.ProviderUnavailable) => true
          case other =>
            throw new IllegalStateException(
              "expected typed ProviderUnavailable from the sentinel stand, got: " + other
            )
        val sentinelTouched = counter.get() >= 1

        // -- (2) second-input behavior on the SAME compiled artifact ---------
        val markerArgv: AnyRef = List("/bin/test", "-f", "marker.txt")
        val ws1Check = runTask(
          loaded
            .invoke(
              "runNamedCheck",
              Seq(newInput(loaded, ws1), "marker", markerArgv)
            )
            .asInstanceOf[Task[?]]
        )
        val ws2Check = runTask(
          loaded
            .invoke(
              "runNamedCheck",
              Seq(newInput(loaded, ws2), "marker", markerArgv)
            )
            .asInstanceOf[Task[?]]
        )
        val ws1Green = ws1Check.isRight
        val ws2Red = ws2Check match
          case Left(e: RaiderError.OutputValidation) =>
            e.detail.contains("marker") && e.detail.contains("exit code 1")
          case _ => false

        // -- (3) edit-without-regeneration -----------------------------------
        // NEGATIVE CONTROL: the outcome flip below is driven ONLY by the
        // COMPILED constants — the generated `runAllChecks` reads its argv
        // from `trustedChecks`; no argv is passed by hand here. If the
        // source edit were reverted, the edited artifact would behave
        // exactly like the original on ws1 (both green) and the assertions
        // below would FAIL, catching a vacuous red.
        val editedSource = rendered.source.replace(
          "List(\"/usr/bin/true\")",
          "List(\"/usr/bin/false\")"
        )
        val editApplied = editedSource != rendered.source &&
          editedSource.contains("List(\"/usr/bin/false\")") &&
          !editedSource.contains("List(\"/usr/bin/true\")")
        val editedPath =
          requireRight(
            writeRender("probe-edited", editedSource),
            "write edited render"
          )
        requireExit0(
          dotc("probe-edited", editedPath, "out-probe-edited"),
          "edited compile"
        )
        val edited =
          loadProbe(compileDir.resolve("out-probe-edited"), "Cwf_gate_probe")
        // On ws1 (marker PRESENT) the ORIGINAL artifact's own compiled check
        // set is fully green: marker green, `/usr/bin/true` green.
        val originalAllGreen = runTask(
          loaded
            .invoke("runAllChecks", Seq(newInput(loaded, ws1)))
            .asInstanceOf[Task[?]]
        ).isRight
        // The EDITED artifact — identical except `/usr/bin/true` →
        // `/usr/bin/false` in the compiled `pass` constant — fails its OWN
        // `runAllChecks` on the SAME input: the edit changed run behavior
        // WITHOUT any regeneration, and the typed red names the flipped
        // check.
        val editedRed = runTask(
          edited
            .invoke("runAllChecks", Seq(newInput(edited, ws1)))
            .asInstanceOf[Task[?]]
        ) match
          case Left(e: RaiderError.OutputValidation) =>
            e.detail.contains("pass") && e.detail.contains("exit code 1")
          case _ => false

        // Capture every fact BEFORE cleanup (assertions re-evaluate lazily).
        val facts = assertTrue(
          decodeTyped,
          sentinelUntouchedDecode,
          badRunTyped,
          sentinelUntouchedBadRun,
          goodRunTyped,
          sentinelTouched,
          ws1Green,
          ws2Red,
          editApplied,
          originalAllGreen,
          editedRed
        )
        deleteRecursively(ws1)
        deleteRecursively(ws2)
        facts
      }
    )

end ChatWorkflowCompileGateSpec
