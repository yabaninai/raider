package raider.cli.processapi

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test._

import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** RAI-201 process-boundary acceptance (ADR-037; docs/fixtures/process-api):
  * real child JVMs driven over stdio JSON-lines against the mock provider.
  *
  * Transport: the child is a real fork/exec'd JVM (`java -cp ... Main
  * process-api`). stdin/stdout/stderr are redirected through files, and through
  * a named FIFO when a test must keep the session open (batch-file input
  * reaches EOF immediately, and EOF after dispatch settles the run as
  * cancelled/exit 26 by design — the consumer walked away). File redirection is
  * used instead of ProcessBuilder pipe streams because the development sandbox
  * interposes relay processes on spawned children's pipe descriptors, which
  * blackholes traffic of long-lived children; ordinary file I/O is not
  * intercepted. A file is also the canonical slow reader: the consumer reads
  * only after the child exits.
  *
  * Cases assert transport/protocol invariants — exactly one terminal, exit code
  * agreement, artifact existence, stdout purity — rather than timing-sensitive
  * orderings.
  */
object ProcessApiAcceptanceSpec extends ZIOSpecDefault:

  // ------------------------------------------------------------------
  // child process harness
  // ------------------------------------------------------------------

  private[processapi] final class ApiChild(
      process: Process,
      workRoot: Path,
      stdoutPath: Path,
      stderrPath: Path,
      private[processapi] val fifoInput: Option[RandomAccessFile]
  ):

    /** Writes one LF-terminated line to the FIFO stdin. Only valid for
      * FIFO-backed children; delivery is in-order and reliable (a FIFO is
      * ordinary file I/O, not a pipe descriptor the sandbox can intercept).
      */
    def send(line: String): Unit =
      fifoInput match
        case Some(raf) =>
          // RandomAccessFile writes are unbuffered syscalls: nothing to flush.
          raf.write((line + "\n").getBytes(UTF_8))
        case None =>
          sys.error("send is only available for FIFO-backed children")

    /** Closes the FIFO stdin: the child reads EOF at its next read. */
    def closeStdin(): Unit =
      fifoInput.foreach(raf => raf.close())

    /** Complete lines currently present in the child's stdout file. A torn
      * final line (mid-write) simply does not end with LF and is excluded by
      * the readAllLines contract, so every returned line is a full frame.
      */
    def rawStdoutLines: Vector[String] =
      if Files.exists(stdoutPath)
      then
        Files
          .readAllLines(stdoutPath, UTF_8)
          .asScala
          .toVector
          .filter(_.nonEmpty)
      else Vector.empty

    def frames: List[Json] =
      rawStdoutLines
        .flatMap(line => JsonDecoder[Json].decodeJson(line).toOption)
        .toList

    /** Polls the stdout file until a frame matches or the deadline passes. */
    def expect(timeoutMillis: Long)(p: Json => Boolean): Option[Json] =
      val deadline = java.lang.System.currentTimeMillis() + timeoutMillis
      var result: Option[Json] = None
      while result.isEmpty && java.lang.System.currentTimeMillis() < deadline do
        result = frames.find(p)
        if result.isEmpty then Thread.sleep(50L)
      result

    def stderr: String =
      if Files.exists(stderrPath) then Files.readString(stderrPath, UTF_8)
      else ""

    def destroy(): Unit = process.destroy() // SIGTERM

    def kill9(): Unit = process.destroyForcibly() // SIGKILL

    /** Closes the test-side read end of the child's stdout pipe. Only valid for
      * children started with a pipe stdout (broken-pipe case).
      */
    def closeStdoutReadEnd(): Unit = process.getInputStream.close()

    def waitForExit(seconds: Long): Int =
      if process.waitFor(seconds, TimeUnit.SECONDS) then process.exitValue()
      else
        process.destroyForcibly()
        process.waitFor(10, TimeUnit.SECONDS)
        -999

  end ApiChild

  private def baseProcessBuilder(workRoot: Path): ProcessBuilder =
    val javaBin =
      Path.of(java.lang.System.getProperty("java.home"), "bin", "java")
    val processBuilder = new ProcessBuilder(
      (javaBin.toString :: "-cp" :: java.lang.System
        .getProperty("java.class.path") ::
        "raider.cli.Main" :: "process-api" :: "--provider" :: "mock" ::
        "--out" :: workRoot.toString :: Nil)*
    )
    val env = processBuilder.environment()
    env.asScala.keySet.toList
      .filter(k =>
        k.startsWith("GITHUB_") || k.startsWith("CI_") || k.startsWith(
          "GITLAB_"
        )
      )
      .foreach(env.remove)
    processBuilder

  private def wireFiles(
      builder: ProcessBuilder,
      stdoutPath: Path,
      stderrPath: Path
  ): Unit =
    builder.redirectOutput(stdoutPath.toFile)
    builder.redirectError(stderrPath.toFile)

  private def writeStdinFile(workRoot: Path, inputLines: Seq[String]): Path =
    val stdinPath = workRoot.resolve("child.stdin")
    Files.write(
      stdinPath,
      inputLines.map(l => l + "\n").mkString.getBytes(UTF_8),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE
    )
    stdinPath

  /** Batch-input child: stdin is a file, so the child sees EOF immediately
    * after the last frame. Use only for tests whose asserted outcome is
    * EOF-driven by design (errors, EOF settle, no-dispatch cases).
    */
  private[processapi] def startFile(
      workRoot: Path,
      inputLines: Seq[String]
  ): ApiChild =
    val stdoutPath = workRoot.resolve("child.stdout")
    val stderrPath = workRoot.resolve("child.stderr")
    val stdinPath = writeStdinFile(workRoot, inputLines)
    val builder = baseProcessBuilder(workRoot)
    builder.redirectInput(stdinPath.toFile)
    wireFiles(builder, stdoutPath, stderrPath)
    new ApiChild(builder.start(), workRoot, stdoutPath, stderrPath, None)

  /** FIFO-backed child: stdin stays open until `closeStdin`, so a dispatched
    * run completes naturally and the session stays alive for signal cases.
    */
  private[processapi] def startFifo(workRoot: Path): ApiChild =
    val fifoPath = workRoot.resolve("child.stdin.fifo")
    val mkfifoBin =
      List("/bin/mkfifo", "/usr/bin/mkfifo").find(p => Files.exists(Path.of(p)))
    val mk = mkfifoBin match
      case Some(bin) =>
        val p = new ProcessBuilder(bin, fifoPath.toString)
        p.redirectError(ProcessBuilder.Redirect.DISCARD)
        val running = p.start()
        running.waitFor()
        running
      case None => sys.error("no mkfifo binary found")
    if mk.exitValue() != 0 || !Files.exists(fifoPath) then
      sys.error(s"mkfifo failed for $fifoPath")
    val stdoutPath = workRoot.resolve("child.stdout")
    val stderrPath = workRoot.resolve("child.stderr")
    // Open the writer BEFORE builder.start(): the parent's O_RDONLY open of
    // the FIFO would otherwise block until a writer appears.
    val raf = new RandomAccessFile(fifoPath.toFile, "rw")
    val builder = baseProcessBuilder(workRoot)
    builder.redirectInput(fifoPath.toFile)
    wireFiles(builder, stdoutPath, stderrPath)
    new ApiChild(builder.start(), workRoot, stdoutPath, stderrPath, Some(raf))

  /** Child whose stdout is a real pipe whose read end the test closes
    * immediately: every child write fails, exercising the dead-channel path.
    */
  private[processapi] def startBrokenPipe(
      workRoot: Path,
      inputLines: Seq[String]
  ): ApiChild =
    val stderrPath = workRoot.resolve("child.stderr")
    val stdinPath = writeStdinFile(workRoot, inputLines)
    val builder = baseProcessBuilder(workRoot)
    builder.redirectInput(stdinPath.toFile)
    builder.redirectError(stderrPath.toFile)
    val process = builder.start()
    // stdout stays a real pipe; the read end is closed immediately so every
    // child write hits EPIPE.
    val child = new ApiChild(
      process,
      workRoot,
      workRoot.resolve("child.stdout.absent"),
      stderrPath,
      None
    )
    child.closeStdoutReadEnd()
    child

  // ------------------------------------------------------------------
  // wire helpers (fixture shapes from docs/fixtures/process-api)
  // ------------------------------------------------------------------

  private[processapi] def hello(requestId: String): String =
    // Single literal, no concatenation seam: adjacent quote runs around an
    // interpolated id are exactly where triple-quote delimiters bite.
    s"""{"protocolVersion":{"major":1,"minor":0},"type":"hello","requestId":"$requestId","supported":[{"major":1,"minor":0}],"requiredCapabilities":["observe","cancel"]}"""

  /** `extra` is spliced in as top-level sibling fields after the task value (it
    * must already be valid JSON fragment text starting with a comma).
    */
  private def submit(
      requestId: String,
      task: String,
      extra: String = ""
  ): String =
    s"""{"protocolVersion":{"major":1,"minor":0},"type":"submit","requestId":"$requestId",""" +
      s""""input":{"schemaVersion":1,"task":"$task"$extra}}"""

  private def observe(requestId: String, after: Long): String =
    s"""{"protocolVersion":{"major":1,"minor":0},"type":"observe","requestId":"$requestId","afterSequence":$after}"""

  private def cancel(requestId: String): String =
    s"""{"protocolVersion":{"major":1,"minor":0},"type":"cancel","requestId":"$requestId"}"""

  private def str(json: Json, name: String): Option[String] =
    json match
      case obj: Json.Obj =>
        obj.fields.toMap.get(name).collect { case Json.Str(s) => s }
      case _ => None

  private def num(json: Json, name: String): Option[Long] =
    json match
      case obj: Json.Obj =>
        obj.fields.toMap.get(name).collect { case Json.Num(v) =>
          scala.math.BigDecimal(v).toLongExact
        }
      case _ => None

  private def arr(json: Json, name: String): Option[List[Json]] =
    json match
      case obj: Json.Obj =>
        obj.fields.toMap.get(name).collect { case Json.Arr(items) =>
          items.toList
        }
      case _ => None

  private def sub1(json: Json, name: String): Option[Json] =
    json match
      case obj: Json.Obj => obj.fields.toMap.get(name)
      case _             => None

  private def isTerminal(json: Json): Boolean =
    str(json, "type").contains("terminal")

  private def isError(json: Json): Boolean = str(json, "type").contains("error")

  private def invocationDir(workRoot: Path): Option[Path] =
    val dirs =
      if Files.isDirectory(workRoot)
      then
        Files
          .list(workRoot)
          .iterator()
          .asScala
          .filter(p => p.getFileName.toString.startsWith("invocation-"))
          .toList
      else Nil
    dirs.headOption

  /** True iff a run was actually dispatched in this child's invocation dir. The
    * invocation dir itself (with events.jsonl) is created at session boot, so
    * dir existence alone is not a dispatch marker.
    */
  private def dispatched(workRoot: Path): Boolean =
    invocationDir(workRoot).exists(dir => Files.exists(dir.resolve("run.json")))

  def spec = suite("RAI-201 process-api real-child acceptance")(
    // Guard against the triple-quote concatenation trap: every helper must
    // emit parseable JSON or every child test below fails mysteriously.
    test("wire helpers produce valid JSON frames") {
      val frames = List(
        hello("r1"),
        submit("r2", "task"),
        // Injects a top-level sibling field after the task value.
        submit("r3", "task", extra = ""","constraints":["no-secrets"]"""),
        observe("r4", 2L),
        cancel("r5")
      )
      assertTrue(
        frames.forall(line => JsonDecoder[Json].decodeJson(line).isRight)
      )
    },
    test(
      "normal handshake, one root, unique terminal, artifacts on disk (XE-PROC-01)"
    ) {
      ZIO.attemptBlocking {
        val work = Files.createTempDirectory("rai201-normal-")
        val child = startFifo(work)
        child.send(hello("req-001"))
        val ack = child
          .expect(60000)(f => str(f, "type").contains("helloAck"))
          .getOrElse(
            sys.error(s"no helloAck; stderr=${child.stderr.take(400)}")
          )
        child.send(
          submit("req-002", "Inspect the supplied fixture for process-api")
        )
        val accepted = child
          .expect(60000)(f =>
            str(f, "type").contains("accepted") && str(f, "requestId")
              .contains("req-002")
          )
          .getOrElse(
            sys.error(s"no accepted; stderr=${child.stderr.take(400)}")
          )
        child.send(observe("req-002", 0L))
        val terminal = child
          .expect(60000)(isTerminal)
          .getOrElse(
            sys.error(s"no terminal; stderr=${child.stderr.take(400)}")
          )

        val rootId = str(accepted, "rootId").getOrElse(
          sys.error("accepted without rootId")
        )
        val frames = child.frames
        val raw = child.rawStdoutLines
        val terminals = frames.filter(isTerminal)

        // XE-PROC-01: stdout carries only protocol messages — every line is
        // a JSON object with a type field (LF-delimited framing).
        val pure = raw.nonEmpty && raw.forall { line =>
          JsonDecoder[Json]
            .decodeJson(line)
            .toOption
            .exists(f => str(f, "type").isDefined)
        }

        // Observation evidence: every mock-run event (sequences 1..6) is
        // delivered. The cursor-driven replay (observe afterSequence=0)
        // overlaps the independent live forward by design (ADR-037 §3 —
        // clients dedupe by per-root sequence), so global frame order is
        // not monotonic; the DISTINCT sequences are exactly the event set.
        // Or the replay is honestly refused (the observe may race the
        // first retained event).
        val observationSeqs = frames
          .filter(f => str(f, "type").contains("observation"))
          .flatMap(num(_, "sequence"))
        val observeFailed = frames.exists(f =>
          isError(f) && str(f, "requestId").contains("req-002") &&
            str(f, "code").contains("observe_failed")
        )

        var acc = assertTrue(
          str(ack, "requestId").contains("req-001"),
          sub1(ack, "negotiated").flatMap(n => num(n, "major")).contains(1L),
          rootId.startsWith("proc_"),
          pure,
          terminals.size == 1,
          str(terminal, "requestId").contains("req-002"),
          str(terminal, "rootId").contains(rootId),
          str(terminal, "executionStatus").contains("completed"),
          str(terminal, "taskVerdict").contains("success"),
          str(terminal, "safetyVerdict").contains("passed"),
          str(terminal, "protocolVerdict").contains("passed"),
          str(terminal, "cleanupStatus").contains("complete"),
          num(terminal, "exitCode").contains(0L),
          observationSeqs.distinct.sorted == List(1L, 2L, 3L, 4L, 5L,
            6L) || observeFailed,
          observationSeqs.nonEmpty || observeFailed
        )

        val artifactRefs = arr(terminal, "artifacts").getOrElse(Nil)
        acc = acc && assertTrue(
          artifactRefs.nonEmpty,
          artifactRefs.forall { ref =>
            str(ref, "path").exists(_.nonEmpty) &&
            str(ref, "sha256").exists(_.matches("[0-9a-f]{64}")) &&
            num(ref, "sizeBytes").exists(_ >= 0L) &&
            str(ref, "provenance").contains(rootId)
          }
        )

        acc = acc && assertTrue(dispatched(work))
        invocationDir(work).foreach { dir =>
          val names =
            Files
              .list(dir)
              .iterator()
              .asScala
              .map(p => p.getFileName.toString)
              .toSet
          acc = acc && assertTrue(
            Set(
              "run.json",
              "result.json",
              "events.jsonl",
              "summary.md",
              "artifacts.json"
            )
              .subsetOf(names)
          )
          val manifest =
            JsonDecoder[Json].decodeJson(
              Files.readString(dir.resolve("artifacts.json"), UTF_8)
            )
          val run =
            JsonDecoder[Json].decodeJson(
              Files.readString(dir.resolve("run.json"), UTF_8)
            )
          val result =
            JsonDecoder[Json].decodeJson(
              Files.readString(dir.resolve("result.json"), UTF_8)
            )
          val events = Files.readAllLines(dir.resolve("events.jsonl"), UTF_8)
          val summary = Files.readString(dir.resolve("summary.md"), UTF_8)
          acc = acc && assertTrue(
            manifest.isRight,
            arr(manifest.toOption.get, "files").exists(_.size == 4),
            run.isRight,
            arr(run.toOption.get, "artifacts").exists(_.size == 3),
            arr(run.toOption.get, "checks")
              .exists(checks =>
                checks.size >= 2 && checks.flatMap(str(_, "id")).toSet ==
                  Set("run-outcome", "run-result-schema")
              ),
            result.isRight,
            events.asScala.nonEmpty,
            summary.contains(rootId)
          )
        }

        // Close stdin: the session has already settled, EOF must be clean.
        child.closeStdin()
        val exit = child.waitForExit(60)
        acc && assertTrue(exit == 0)
      }
    } @@ TestAspect.timeout(zio.Duration.fromSeconds(180)),
    test(
      "fragmented unicode submit across code-point boundaries survives (XE-PROC-02)"
    ) {
      ZIO.attemptBlocking {
        val work = Files.createTempDirectory("rai201-utf8-")
        val child = startFifo(work)
        child.send(hello("req-u0"))
        val task = "Δelta 漢字 🧪 with A\u2028B\u2029C separators"
        val bytes = (submit("req-u1", task) + "\n").getBytes(UTF_8)
        // Feed the frame through the fixture's byte-cut recipe over the
        // FIFO: reads may coalesce chunks arbitrarily, which the assembler
        // must tolerate (chunk boundaries are never load-bearing).
        val cuts = Vector(60, 63, 66, 68, 72, 76, bytes.length)
        var offset = 0
        val raf = child.fifoInput.getOrElse(sys.error("fifo child required"))
        cuts.foreach { cut =>
          val end = math.min(cut, bytes.length)
          if end > offset then
            raf.write(java.util.Arrays.copyOfRange(bytes, offset, end))
            offset = end
        }
        val accepted = child
          .expect(60000)(f =>
            str(f, "type").contains("accepted") && str(f, "requestId")
              .contains("req-u1")
          )
          .getOrElse(
            sys.error(s"no accepted; stderr=${child.stderr.take(400)}")
          )
        val terminal = child
          .expect(60000)(isTerminal)
          .getOrElse(
            sys.error(s"no terminal; stderr=${child.stderr.take(400)}")
          )
        child.closeStdin()
        val exit = child.waitForExit(60)
        val terminals = child.frames.filter(isTerminal)
        assertTrue(
          str(accepted, "rootId").exists(_.nonEmpty),
          str(terminal, "executionStatus").contains("completed"),
          num(terminal, "exitCode").contains(0L),
          terminals.size == 1,
          exit == 0
        )
      }
    } @@ TestAspect.timeout(zio.Duration.fromSeconds(180)),
    test(
      "refusal: secret-bearing field and unknown type are non-fatal bounded errors"
    ) {
      ZIO.attemptBlocking {
        val work = Files.createTempDirectory("rai201-refusal-")
        val secret = "SENTINEL-4f1a"
        val child = startFile(
          work,
          Seq(
            hello("req-s0"),
            submit(
              "req-s1",
              "Do not dispatch",
              extra = s""","leak":"$secret""""
            ),
            """{"protocolVersion":{"major":1,"minor":0},"type":"no-such-type-ααα","requestId":"req-s2"}"""
          )
        )
        val exit = child.waitForExit(120)
        val frames = child.frames
        val fieldError =
          frames.find(isError).getOrElse(sys.error("no error frame"))
        val typeError = frames
          .find(f => isError(f) && str(f, "requestId").contains("req-s2"))
          .getOrElse(sys.error("no unknown_type error"))
        val allOutput = child.stderr + child.rawStdoutLines.mkString("\n")
        assertTrue(
          exit == 0,
          // Pre-dispatch refusal carries the input_rejected code and names
          // the field without echoing its value (XE-WIRE-02 secret-bearing).
          str(fieldError, "code").contains("input_rejected"),
          str(fieldError, "requestId").contains("req-s1"),
          str(fieldError, "message").exists(m =>
            m.contains("leak") && !m.contains(secret)
          ),
          // Decode-level errors correlate when the requestId is known
          // (ADR-037 §3 — correlation where known).
          str(typeError, "code").contains("unknown_type"),
          str(typeError, "message").exists(_.contains("no-such-type")),
          frames.count(isTerminal) == 0,
          !dispatched(work),
          !allOutput.contains(secret)
        )
      }
    } @@ TestAspect.timeout(zio.Duration.fromSeconds(180)),
    test(
      "oversize frame is refused before dispatch and exits 20 (XE-PROC-02)"
    ) {
      ZIO.attemptBlocking {
        val work = Files.createTempDirectory("rai201-oversize-")
        val prefix =
          "{\"protocolVersion\":{\"major\":1,\"minor\":0},\"type\":\"submit\",\"requestId\":\"req-o1\",\"padding\":\""
        val suffix = "\"}"
        val pad = Framing.FrameMaxBytes + 1 - prefix.length - suffix.length
        val child = startFile(
          work,
          Seq(hello("req-o0"), prefix + ("x" * pad) + suffix)
        )
        val exit = child.waitForExit(120)
        val frames = child.frames
        val error =
          frames.find(isError).getOrElse(sys.error("no oversize error"))
        assertTrue(
          str(error, "code").contains("oversize_frame"),
          exit == 20,
          frames.count(isTerminal) == 0,
          !frames.exists(f => f.toJson.contains("xxxxx")),
          !dispatched(work)
        )
      }
    } @@ TestAspect.timeout(zio.Duration.fromSeconds(180)),
    test(
      "duplicate submit is refused; the first root keeps ownership (XE-PROC-03)"
    ) {
      ZIO.attemptBlocking {
        val work = Files.createTempDirectory("rai201-dup-")
        val child = startFifo(work)
        child.send(hello("req-d0"))
        child.send(submit("req-d1", "One invocation"))
        child.expect(60000)(f => str(f, "type").contains("accepted"))
        child.send(submit("req-d2", "Do not run this"))
        val duplicate = child
          .expect(60000)(f =>
            isError(f) && str(f, "requestId").contains("req-d2")
          )
          .getOrElse(
            sys.error(
              s"no duplicate_submit error; stderr=${child.stderr.take(400)}"
            )
          )
        val terminal = child
          .expect(60000)(isTerminal)
          .getOrElse(
            sys.error(s"no terminal; stderr=${child.stderr.take(400)}")
          )
        child.closeStdin()
        val exit = child.waitForExit(60)
        val terminals = child.frames.filter(isTerminal)
        val accepted =
          child.frames.count(f => str(f, "type").contains("accepted"))
        assertTrue(
          str(duplicate, "code").contains("duplicate_submit"),
          accepted == 1,
          terminals.size == 1,
          str(terminal, "executionStatus").contains("completed"),
          num(terminal, "exitCode").contains(0L),
          exit == 0
        )
      }
    } @@ TestAspect.timeout(zio.Duration.fromSeconds(180)),
    test("cancel mid-run settles with exactly one terminal (XE-PROC-03)") {
      ZIO.attemptBlocking {
        val work = Files.createTempDirectory("rai201-cancel-")
        val child = startFile(
          work,
          Seq(
            hello("req-c0"),
            submit("req-c1", "Task to cancel"),
            cancel("req-c1")
          )
        )
        val exit = child.waitForExit(120)
        val frames = child.frames
        val ack = frames.find(f => str(f, "type").contains("cancelAck"))
        val terminal = frames.filter(isTerminal)
        val status =
          terminal.headOption.flatMap(str(_, "executionStatus")).getOrElse("")
        val terminalExit =
          terminal.headOption.flatMap(num(_, "exitCode")).getOrElse(-1L)
        assertTrue(
          ack.isDefined, // accepted / already_terminal / not_found — all honest
          terminal.size == 1,
          Set("cancelled", "interrupted", "completed").contains(status),
          terminalExit == exit.toLong,
          if status == "completed" then exit == 0 else exit == 26
        )
      }
    } @@ TestAspect.timeout(zio.Duration.fromSeconds(180)),
    test(
      "clean EOF before submit closes without dispatching a root (XE-PROC-03)"
    ) {
      ZIO.attemptBlocking {
        val work = Files.createTempDirectory("rai201-eof-")
        val child = startFile(work, Seq(hello("req-e0")))
        val exit = child.waitForExit(120)
        assertTrue(
          exit == 0,
          child.frames.count(isTerminal) == 0,
          !dispatched(work),
          child.stderr.contains("settled exit=0")
        )
      }
    } @@ TestAspect.timeout(zio.Duration.fromSeconds(180)),
    test("EOF mid-frame is a protocol error with nonzero exit (XE-PROC-03)") {
      ZIO.attemptBlocking {
        val work = Files.createTempDirectory("rai201-midframe-")
        val child = startFile(work, Seq(hello("req-m0")))
        // hello line complete; then a partial frame without LF, then EOF.
        Files.write(
          work.resolve("child.stdin"),
          "{\"type\":\"sub".getBytes(UTF_8),
          StandardOpenOption.APPEND
        )
        val exit = child.waitForExit(120)
        assertTrue(
          exit == 20,
          child.frames.count(isTerminal) == 0,
          !dispatched(work)
        )
      }
    } @@ TestAspect.timeout(zio.Duration.fromSeconds(180)),
    test(
      "SIGTERM while running settles gracefully with exit 143 (XE-PROC-03)"
    ) {
      ZIO.attemptBlocking {
        val work = Files.createTempDirectory("rai201-sigterm-")
        val child = startFifo(work)
        // hello only: the session stays alive awaiting stdin (no root, so
        // no mock-run completion race — the signal always arrives while the
        // session is running). The helloAck proves the read loop is up.
        child.send(hello("req-t0"))
        child.expect(60000)(f => str(f, "type").contains("helloAck"))
        child.destroy()
        val exit = child.waitForExit(60)
        val terminal = child.frames.filter(isTerminal)
        val consistent =
          terminal.forall(t => num(t, "exitCode").contains(exit.toLong))
        assertTrue(
          exit == 143,
          terminal.size <= 1,
          consistent
        )
      }
    } @@ TestAspect.timeout(zio.Duration.fromSeconds(180)),
    test(
      "SIGKILL leaves no terminal proof of completion (death without terminal)"
    ) {
      ZIO.attemptBlocking {
        val work = Files.createTempDirectory("rai201-death-")
        val child = startFifo(work)
        // hello only: the session is provably alive (helloAck observed) and
        // cannot settle on its own, so the SIGKILL always lands on a live
        // process. A killed process cannot emit an honest terminal.
        child.send(hello("req-k0"))
        child.expect(60000)(f => str(f, "type").contains("helloAck"))
        child.kill9()
        val exit = child.waitForExit(60)
        // Any fully written line must still be a protocol frame (no torn
        // garbage); no reliable terminal may exist after SIGKILL.
        val parsed = child.frames
        assertTrue(
          exit == 137,
          parsed.count(isTerminal) == 0
        )
      }
    } @@ TestAspect.timeout(zio.Duration.fromSeconds(180)),
    test(
      "broken pipe: write failure stops admission and exits 26, never success (XE-PROC-02)"
    ) {
      ZIO.attemptBlocking {
        val work = Files.createTempDirectory("rai201-brokenpipe-")
        val child = startBrokenPipe(
          work,
          Seq(hello("req-b0"), submit("req-b1", "Nobody is listening"))
        )
        val exit = child.waitForExit(120)
        assertTrue(
          exit == 26,
          exit != 0
        )
      }
    } @@ TestAspect.timeout(zio.Duration.fromSeconds(180)),
    test(
      "slow reader: a stalled consumer still receives every frame (XE-PROC-03)"
    ) {
      ZIO.attemptBlocking {
        val work = Files.createTempDirectory("rai201-slowreader-")
        // FIFO stdin keeps the session open; the stdout file is the
        // canonical slow reader: nothing consumes it until after exit. The
        // bounded-queue pressure itself is covered at unit level in
        // OutboundSpec (saturation, drain wakeup, dead channel).
        val child = startFifo(work)
        child.send(hello("req-w0"))
        child.send(submit("req-w1", "Frames accumulate while nobody reads"))
        val terminal = child
          .expect(60000)(isTerminal)
          .getOrElse(
            sys.error(s"no terminal; stderr=${child.stderr.take(400)}")
          )
        child.closeStdin()
        val exit = child.waitForExit(60)
        val raw = child.rawStdoutLines
        val frames = child.frames
        val terminals = frames.filter(isTerminal)
        assertTrue(
          str(terminal, "executionStatus").contains("completed"),
          exit == 0,
          raw.nonEmpty,
          raw.size == frames.size, // every line parses: no loss, no interleaving
          terminals.size == 1,
          terminals.forall(t => num(t, "exitCode").contains(0L))
        )
      }
    } @@ TestAspect.timeout(zio.Duration.fromSeconds(180))
  )

end ProcessApiAcceptanceSpec
