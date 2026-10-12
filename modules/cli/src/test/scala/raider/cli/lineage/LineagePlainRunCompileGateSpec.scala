package raider.cli.lineage

import zio.test.*

import raider.dsl.lineage.proj.{
  LineageRenderer,
  LineageView,
  NodeKind,
  NodeView
}

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

/** LIN-11 plain-run compile-gate: the projection of a graph WITHOUT named-def
  * roots (a plain headless scout/coder run — model/tool calls only, no Phase
  * and no Delegation) compiles with the PINNED scalac (dotc 3.9.0 from
  * `artifacts/tools/m2`). Before LIN-11 the renderer emitted `object X:` +
  * indented comment-only body; comments never bind a Scala 3 indentation
  * region, so dotc 3.9.0 rejected the artifact with "indented definitions
  * expected, eof found" (LIN-09 reproducer). The fix closes the def-free body
  * with the legal `()` statement; this gate pins the behavior against the real
  * compiler, not only the sbt build (same discipline as the frozen
  * LineageCompileGateSpec, whose private plumbing is deliberately duplicated
  * here — the frozen spec is not modifiable from this card).
  *
  * Acceptance: LIN11-01 the plain-run projection compiles (exit 0), and the
  * byte-identical repeat render re-compiles (exit 0); LIN11-02 the negative
  * stays negative: the pre-fix artifact (trailing `()` dropped — exactly the
  * LIN-09 defect shape) does NOT compile, and a torn object header does NOT
  * compile (a compile timeout or an IO failure is never counted as
  * non-compilation evidence).
  *
  * The view is hand-built (not parsed from golden documents): the golden
  * fixtures contain named defs by design and stay byte-identical under LIN-11
  * (their invariance is pinned by the frozen LineageCompileGateSpec and the
  * dsl-level golden specs); the def-free shape has no golden and needs none —
  * the renderer output is asserted byte-exactly in
  * raider.dsl.lineage.proj.LineageRendererSpec (LIN11-03).
  */
object LineagePlainRunCompileGateSpec extends ZIOSpecDefault:

  // ----- plain-run fixture (LIN-09 reproducer shape) ---------------------------

  private val plainRunView: LineageView = new LineageView:
    def runId: String = "run-plain-gate"
    def roots: Vector[NodeView] = Vector(
      NodeView(
        "m1",
        NodeKind.ModelCall("mock-coder", 1),
        None,
        Vector(
          NodeView(
            "t1",
            NodeKind.ToolCall("read_file", "ReadOnly"),
            Some("m1"),
            Vector.empty,
            Some("d1")
          )
        ),
        None
      ),
      NodeView(
        "m2",
        NodeKind.ModelCall("mock-coder", 2),
        None,
        Vector(
          NodeView(
            "t2",
            NodeKind.ToolCall("grep_search", "Idempotent"),
            Some("m2"),
            Vector.empty,
            Some("d2")
          )
        ),
        None
      )
    )

  // ----- pinned-dotc compile gate (plumbing verbatim in behavior from the
  // frozen LineageCompileGateSpec; self-contained because that spec's members
  // are private) -------------------------------------------------------------

  private val CompileTimeoutSeconds = 120L

  private def findAncestor(segments: String*): Path =
    val cwd = Path.of("").toAbsolutePath
    Iterator
      .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) => dir }
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
      .resolve("lineage-compile")

  private def m2Jar(rel: String): Path =
    val p = findAncestor("artifacts", "tools", "m2")
      .resolve("artifacts")
      .resolve("tools")
      .resolve("m2")
      .resolve(rel)
    if Files.isRegularFile(p) then p
    else throw new IllegalStateException("missing pinned jar: " + p)

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
          scala.jdk.CollectionConverters
            .ListHasAsScala(
              Files.readAllLines(log, StandardCharsets.UTF_8)
            )
            .asScala
            .toVector
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

  /** LIN11-02 negative (a): the PRE-FIX artifact — the trailing `  ()` closer
    * line dropped, which is exactly the LIN-09 defect shape (`object X:` +
    * indented comments only; no indentation region ever opens). Deterministic
    * against pinned dotc 3.9.0 ("indented definitions expected, eof found").
    */
  private def dropObjectCloser(src: String): String =
    val lines = src.linesIterator.toVector
    val idx = lines.lastIndexWhere(_.trim == "()")
    if idx < 0 then
      throw new IllegalStateException("render has no object closer to drop")
    lines.patch(idx, Nil, 1).mkString("", "\n", "\n")

  /** LIN11-02 negative (b): a structurally CORRUPTED render — the `object`
    * header torn into invalid Scala, the proven reliable negative of the frozen
    * LineageCompileGateSpec (LIN2-01-C).
    */
  private def corruptObjectHeader(src: String): String =
    val lines = src.linesIterator.toVector
    val idx = lines.indexWhere(_.trim.startsWith("object "))
    if idx < 0 then
      throw new IllegalStateException("render has no object header to corrupt")
    lines.updated(idx, "object ((:").mkString("", "\n", "\n")

  private def requireRight[A](e: Either[String, A], what: String): A =
    e match
      case Right(a) => a
      case Left(err) =>
        throw new IllegalStateException(what + " failed: " + err)

  /** The plain-run render as a source string; any typed render failure is an
    * honest fixture failure, never a compile-gate result.
    */
  private def plainRunSrc(what: String): String =
    LineageRenderer.render(plainRunView) match
      case Right(src) => src
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

  /** Negative discipline: exit != 0 IS the honest non-compilation evidence; a
    * Left (timeout/launch failure) is NEVER accepted as such.
    */
  private def requireNonCompilation(
      res: Either[String, Compiled],
      what: String
  ): Unit =
    res match
      case Right(Compiled(exit, _)) if exit != 0 => ()
      case Right(Compiled(exit, _)) =>
        throw new IllegalStateException(
          what + ": corrupted projection compiled (exit " + exit +
            ") — negative contract violated"
        )
      case Left(err) =>
        throw new IllegalStateException(
          what + ": " + err + " (timeout/IO is NOT honest non-compilation evidence)"
        )

  // ----- spec ----------------------------------------------------------------

  def spec = suite("LIN-11 plain-run compile-gate (pinned dotc 3.9.0)")(
    test(
      "LIN11-01: plain-run projection (no named-def roots) compiles with pinned dotc (exit 0), repeat artifact re-compiles"
    ) {
      val first = plainRunSrc("plain-run render")
      val second = plainRunSrc("plain-run repeat render")
      val byteIdentical =
        first.getBytes(StandardCharsets.UTF_8).toSeq ==
          second.getBytes(StandardCharsets.UTF_8).toSeq
      val path = requireRight(
        writeRender("plain-run", first),
        "write plain-run render"
      )
      requireExit0(
        dotc("plain-run", path, "out-plain-run"),
        "plain-run compile"
      )
      val repeatPath = requireRight(
        writeRender("plain-run-repeat", second),
        "write plain-run repeat render"
      )
      requireExit0(
        dotc("plain-run-repeat", repeatPath, "out-plain-run-repeat"),
        "plain-run repeat compile"
      )
      assertTrue(
        byteIdentical,
        Files.exists(path),
        Files.exists(repeatPath)
      )
    },
    test(
      "LIN11-02: pre-fix artifact (object closer dropped — the LIN-09 defect shape) honestly does NOT compile"
    ) {
      val src = plainRunSrc("plain-run render")
      val defect = dropObjectCloser(src)
      val path = requireRight(
        writeRender("plain-run-defect", defect),
        "write defect render"
      )
      val res = dotc("plain-run-defect", path, "out-plain-run-defect")
      requireNonCompilation(res, "plain-run defect compile")
      assertTrue(Files.exists(path))
    },
    test(
      "LIN11-02: corrupted projection (object header torn) honestly does NOT compile"
    ) {
      val src = plainRunSrc("plain-run render")
      val torn = corruptObjectHeader(src)
      val path = requireRight(
        writeRender("plain-run-torn", torn),
        "write torn render"
      )
      val res = dotc("plain-run-torn", path, "out-plain-run-torn")
      requireNonCompilation(res, "plain-run torn compile")
      assertTrue(Files.exists(path))
    }
  )
