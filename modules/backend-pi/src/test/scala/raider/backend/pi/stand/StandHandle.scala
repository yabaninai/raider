package raider.backend.pi.stand

import java.nio.file.Path
import zio.Task

/** Launches PiRpcStand as a REAL subprocess (java -cp <runtime cp> main class).
  * No ports, no fixed paths: the child communicates only over its
  * stdin/stdout/stderr pipes, per the contract ownership model.
  */
object StandHandle:

  def classpath: String = System.getProperty("java.class.path")

  def javaBinary: Path =
    val exe =
      if sys.props.get("os.name").exists(_.toLowerCase.contains("win")) then
        "java.exe"
      else "java"
    Path.of(System.getProperty("java.home"), "bin", exe)

  /** Full argv for the stand with a given script. */
  def argv(script: String, extra: String*): Vector[String] =
    Vector(
      javaBinary.toString,
      "-cp",
      classpath,
      "raider.backend.pi.stand.PiRpcStand",
      "--script",
      script
    ) ++
      extra.toVector

  /** Version-probe argv (stand prints the version line and exits). */
  def versionArgv(extra: String*): Vector[String] =
    Vector(
      javaBinary.toString,
      "-cp",
      classpath,
      "raider.backend.pi.stand.PiRpcStand",
      "--version"
    ) ++
      extra.toVector

  /** Direct handle over a spawned stand process (used by specs to observe
    * liveness/exit codes outside the adapter's scope).
    */
  def spawn(argv: Vector[String]): Task[java.lang.Process] =
    import zio.ZIO
    ZIO.attempt:
      val pb = new java.lang.ProcessBuilder(argv.toList*)
      pb.redirectErrorStream(false)
      pb.start()
