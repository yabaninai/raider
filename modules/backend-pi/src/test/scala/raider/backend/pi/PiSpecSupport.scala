package raider.backend.pi

import zio.*
import zio.Duration

/** Plain-main spec harness shared by the PIRPC specs (compat-pi pattern: no
  * zio-test dependency, exit code carries the verdict).
  */
object PiSpecSupport:

  private var failures = 0
  private var checks = 0

  def check(name: String)(ok: Boolean, detail: => String): Unit =
    checks += 1
    if ok then println(s"  [ok]   $name")
    else
      failures += 1
      println(s"  [FAIL] $name :: $detail")

  def runUnsafe(io: ZIO[Any, Throwable, Unit]): Unit =
    Unsafe.unsafe { implicit u =>
      try Runtime.default.unsafe.run(io).getOrThrow()
      catch
        case t: Throwable =>
          t.printStackTrace()
          failures += 1
    }
    println(s"checks=$checks failures=$failures")
    if failures > 0 then sys.exit(1)
    sys.exit(0)

  /** Guard every scenario with a hard timeout so a hang fails loudly. */
  def bounded[E, A](
      io: ZIO[Any, E, A],
      what: String
  ): ZIO[Any, E | Throwable, A] =
    io.timeout(60.seconds).flatMap {
      case Some(a) => ZIO.succeed(a)
      case None =>
        ZIO.fail(
          new RuntimeException(s"$what timed out after 60s")
            .asInstanceOf[E | Throwable]
        )
    }
