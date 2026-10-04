package raider.spike

import zio.{Unsafe, ZIO}
import zio.Exit as ZExit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

/** RAI-001 headless feasibility spike.
  *
  * Runs as a plain `java -cp <headless-classpath>` process: no TTY, no Scala compiler,
  * no JLine on the classpath, stdin never read. One root fiber owns a direct child OS
  * process (simulated tool subtree). SIGTERM/SIGINT triggers bounded cancellation: the
  * root is interrupted with a cleanup grace, the direct child is destroyed (verified by
  * PID; process-group/grandchild cleanup is an RAI-016 obligation, see the grandchild
  * reproducer mode), a truthful partial artifact is written atomically, and the process
  * exits with 143/130.
  */
object HeadlessSpike:

  private val runtime = zio.Runtime.default

  private val steps = AtomicInteger(0)
  private val interrupted = AtomicBoolean(false)
  private val normalCompletion = AtomicBoolean(false)
  @volatile private var signalName: String = null
  @volatile private var cleanupTimedOut: Boolean = false
  @volatile private var childAliveAfterCleanup: Boolean = true
  @volatile private var childPid: Long = -1L
  @volatile private var grandchildPid: Long = -1L
  @volatile private var startedAt: Instant = Instant.now()
  @volatile private var finishedAt: Instant = null

  def main(args: Array[String]): Unit =
    try
      runMain(args)
    catch
      case e: Throwable =>
        // setup/artifact-write failures are reported, never swallowed into exit 0
        println(s"[fatal] ${e}")
        Runtime.getRuntime.halt(27)

  private def runMain(args: Array[String]): Unit =
    val totalMs = args.headOption.map(_.toLong).getOrElse(3000L)
    val tickMs = args.lift(1).map(_.toLong).getOrElse(300L)
    val outDir =
      Path.of(Option(System.getenv("RAIDER_SPIKE_OUT")).getOrElse("artifacts/rai-001/headless"))
    Files.createDirectories(outDir)
    val artifact = outDir.resolve("run.json")

    // Owned child process (subtree simulation).
    // mode=direct  : single sleep child (direct-child cleanup proven);
    // mode=grandchild: bash spawns sleep and waits -> a real grandchild exists;
    //                 cleanup of the direct child does NOT reach it. This is an
    //                 intentional reproducer of the process-group gap that RAI-016
    //                 must close; the spike makes no group-cleanup claim.
    val childMode = Option(System.getenv("RAIDER_SPIKE_CHILD_MODE")).getOrElse("direct")
    val child =
      if childMode == "grandchild" then
        new ProcessBuilder("bash", "-c",
                           s"sleep 300 & echo $$! > '${outDir.toAbsolutePath}/grandchild.pid'; wait")
          .start()
      else new ProcessBuilder("sleep", "300").start()
    childPid = child.pid()
    if childMode == "grandchild" then
      val pidFile = outDir.resolve("grandchild.pid")
      val deadline = System.nanoTime() + 2_000_000_000L
      while !pidFile.toFile.exists() && System.nanoTime() < deadline do Thread.sleep(50)
      if pidFile.toFile.exists() then
        grandchildPid = new String(Files.readAllBytes(pidFile)).trim.toLong

    def root: ZIO[Any, Nothing, String] =
      println(s"[root] acquired child pid=$childPid")
      def cleanupChild(): Unit =
        if child.isAlive then
          child.destroy()
          val graceful = child.waitFor(2, TimeUnit.SECONDS)
          if !graceful then child.destroyForcibly()
        childAliveAfterCleanup = child.isAlive
        println(s"[cleanup] child alive after cleanup=$childAliveAfterCleanup")
      stepLoop(tickMs, totalMs).ensuring(ZIO.succeed(cleanupChild()))

    def stepLoop(tick: Long, total: Long): ZIO[Any, Nothing, String] =
      ZIO.sleep(zio.Duration.fromMillis(tick)) *> ZIO.succeed(steps.incrementAndGet()) *> {
        if steps.get.toLong * tick >= total then
          ZIO.succeed(s"completed steps=${steps.get()}")
        else stepLoop(tick, total)
      }

    // Signal handling: record which signal, exit with 128+n; shutdown hook does cleanup.
    def install(signal: String): Unit =
      sun.misc.Signal.handle(
        new sun.misc.Signal(signal),
        _ => {
          signalName = signal
          Runtime.getRuntime.exit(128 + signalToInt(signal))
        }
      )

    def signalToInt(s: String): Int = s match
      case "INT" => 2
      case "TERM" => 15
      case _ => 0

    install("TERM")
    install("INT")

    // Start the root fiber; the shutdown hook owns bounded cancellation + reporting.
    val rootFiber = Unsafe.unsafe { implicit u => runtime.unsafe.fork(root) }

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      finishedAt = Instant.now()
      if normalCompletion.get() then
        writeArtifact(artifact, "succeeded")
      else
        interrupted.set(true)
        Unsafe.unsafe { implicit u =>
          runtime.unsafe.run(rootFiber.interrupt.timeout(zio.Duration.fromSeconds(4))) match
            case ZExit.Success(Some(_)) => ()
            case _ => cleanupTimedOut = true
        }
        writeArtifact(artifact, if cleanupTimedOut then "interrupted-cleanup-timeout" else "interrupted")
    }, "raider-spike-reporter"))

    // Main thread waits for the root outcome (no polling of stdin).
    val outcome = Unsafe.unsafe { implicit u => runtime.unsafe.run(rootFiber.await) }
    normalCompletion.set(true)
    finishedAt = Instant.now()
    val (status, result) = outcome match
      case ZExit.Success(ZExit.Success(text)) => ("succeeded", text)
      case ZExit.Success(ZExit.Failure(cause)) =>
        ("failed", s"root failed: ${cause.prettyPrint.take(500)}")
      case ZExit.Failure(cause) =>
        ("interrupted-observed", s"await interrupted: ${cause.prettyPrint.take(500)}")
    // Give the child finalizer a moment to settle before reading its state.
    Unsafe.unsafe { implicit u => runtime.unsafe.run(ZIO.sleep(zio.Duration.fromMillis(200))) }
    writeArtifact(artifact, status)
    println(s"[main] outcome=$status result=$result")
    // truthful exits: succeeded=0, any observed failure=20; signal paths already
    // exited with 143/130 inside their handlers
    status match
      case "succeeded" => Runtime.getRuntime.exit(0)
      case _           => Runtime.getRuntime.exit(20)

  private val artifactWritten = java.util.concurrent.atomic.AtomicBoolean(false)

  private def writeArtifact(path: Path, status: String): Unit =
    if !artifactWritten.get() then synchronized {
      val json = s"""{
        |  "kind": "raider-spike-headless",
        |  "status": "$status",
        |  "signal": "${if signalName == null then "" else signalName}",
        |  "stepsCompleted": ${steps.get()},
        |  "childPid": $childPid,
        |  "grandchildPid": $grandchildPid,
        |  "childAliveAfterCleanup": $childAliveAfterCleanup,
        |  "cleanupTimedOut": $cleanupTimedOut,
        |  "startedAt": "$startedAt",
        |  "finishedAt": "${if finishedAt == null then "" else finishedAt.toString}"
        |}""".stripMargin
      val tmp = path.resolveSibling(path.getFileName.toString + ".tmp")
        Files.write(tmp, json.getBytes(StandardCharsets.UTF_8))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        artifactWritten.set(true)  // flag only after a successful atomic publish
        println(s"[artifact] wrote ${path.toAbsolutePath} status=$status")
      }

end HeadlessSpike
