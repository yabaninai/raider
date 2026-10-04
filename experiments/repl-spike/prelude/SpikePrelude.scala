package raider.spike

import zio.{Fiber, Unsafe, ZIO}
import zio.Exit as ZExit

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** RAI-001 feasibility prelude.
  *
  * One shared ZIO runtime object is loaded from the compiled spike classpath into the
  * real Scala REPL (classloader/prelude bridge). REPL bindings hold typed handles into
  * this object; state (job counter/registry) survives across REPL submissions, proving a
  * single runtime instance serves foreground, background and awaits.
  *
  * Mock-only: chunked "model output" with sleeps; no network, no paid calls.
  */
object RaiderSpike:

  private val runtime = zio.Runtime.default
  private val jobSeq = AtomicInteger(0)
  private val jobs = ConcurrentHashMap[String, Job]()

  final class Job(
    val id: String,
    private[spike] val fiber: Fiber.Runtime[Nothing, String],
    private[spike] val state: java.util.concurrent.atomic.AtomicReference[String]
  ):
    private def stateNow: String = state.get
    override def toString: String = s"Job($id, state=${stateNow})"

    def info: String =
      s"Job($id, state=${stateNow}, done=${stateNow != "running"})"

    /** Await existing job; repeated awaits must not re-run anything. */
    def await: String =
      Unsafe.unsafe { implicit u =>
        runtime.unsafe.run(fiber.await) match
          case ZExit.Success(ZExit.Success(value)) => value
          case ZExit.Success(ZExit.Failure(cause)) =>
            throw new IllegalStateException(s"job $id terminated: ${cause.prettyPrint}")
          case ZExit.Failure(cause) =>
            throw new IllegalStateException(s"await of $id was interrupted: ${cause.prettyPrint}")
      }

    def cancel(): Unit =
      Unsafe.unsafe { implicit u => runtime.unsafe.run(fiber.interrupt) }

  end Job

  /** Chunked mock model output, streaming prints from the fiber. */
  private def streaming(label: String, chunks: Int, delayMs: Long): ZIO[Any, Nothing, String] =
    ZIO
      .foreach((1 to chunks).toList) { i =>
        ZIO.sleep(zio.Duration.fromMillis(delayMs)) *> ZIO.succeed {
          println(s"[stream $label] chunk $i/$chunks")
          s"chunk-$i"
        }
      }
      .map(_.mkString("+"))

  /** Foreground ask: blocks the REPL evaluation thread, streams, returns typed String. */
  def ask(label: String, chunks: Int = 6, delayMs: Long = 250): String =
    val flow = for
      _ <- ZIO.succeed(println(s"[ask $label] dispatching foreground run"))
      o <- streaming(label, chunks, delayMs)
    yield o
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(flow) match
        case ZExit.Success(value) => value
        case ZExit.Failure(cause) =>
          throw new IllegalStateException(s"foreground run interrupted: ${cause.prettyPrint}")
    }

  /** Background start: returns immediately with a typed Job handle. */
  def start(label: String, chunks: Int = 6, delayMs: Long = 250): Job =
    val id = s"j_${jobSeq.incrementAndGet()}"
    val flow = for
      _ <- ZIO.succeed(println(s"[job $id] started: $label"))
      o <- streaming(label, chunks, delayMs)
      _ <- ZIO.succeed(println(s"[job $id] finished"))
    yield o
    val state = java.util.concurrent.atomic.AtomicReference("running")
    val wired = flow.onExit {
      case ZExit.Success(_) => ZIO.succeed(state.set("succeeded"))
      case ZExit.Failure(_) => ZIO.succeed(state.set("interrupted"))
    }
    val fiber = Unsafe.unsafe { implicit u => runtime.unsafe.fork(wired) }
    val job = Job(id, fiber, state)
    jobs.put(id, job)
    job

  /** Plain blocking sleep for foreground Ctrl+C interruptibility checks. */
  def blocking(ms: Long): String =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(ZIO.sleep(zio.Duration.fromMillis(ms)).as(s"slept ${ms}ms")) match
        case ZExit.Success(value) => value
        case ZExit.Failure(cause) =>
          throw new IllegalStateException(s"blocking run interrupted: ${cause.prettyPrint}")
    }

  def registrySize: Int = jobs.size()
  def counter: Int = jobSeq.get()

end RaiderSpike
