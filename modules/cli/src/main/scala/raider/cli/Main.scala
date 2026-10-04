package raider.cli

import raider.cli.run.{CliArgs, HeadlessRunner}
import zio.Unsafe

import java.util.concurrent.atomic.AtomicBoolean

/** Headless entrypoint (RAI-022 slice): stdout JSON result line, stderr
  * diagnostics, exit code per ci-runtime §8. Stdin is NEVER read (closed
  * stdin safe by construction).
  *
  * SIGTERM bounded stop (RUN-03): a shutdown hook interrupts the runner
  * fiber and waits AT MOST 5 seconds for finalizers, then RETURNS — it
  * NEVER calls System.exit itself (calling exit from inside a shutdown
  * hook deadlocks Shutdown.runHooks — found by probe, 2026-10-04). The
  * POSIX SIGTERM death after hooks yields the 143 status; a normal
  * completion marks `done` first, so the hook is a no-op then.
  */
object Main:

  private val SigTermExit = 143

  private def argOr(argv: Array[String], flag: String, default: String): String =
    val i = argv.indexOf(flag)
    if i >= 0 && i + 1 < argv.length then argv(i + 1) else default

  private def argOrOpt(argv: Array[String], flag: String): Option[String] =
    val i = argv.indexOf(flag)
    if i >= 0 && i + 1 < argv.length then Some(argv(i + 1)) else None

  private def detectModel(): String =
    try
      val url = java.net.URI.create("http://127.0.0.1:8081/v1/models").toURL
      val conn = url.openConnection().asInstanceOf[java.net.HttpURLConnection]
      conn.setConnectTimeout(3000)
      conn.setReadTimeout(3000)
      val text = scala.io.Source.fromInputStream(conn.getInputStream)
        .mkString
      conn.disconnect()
      // parse first model name from JSON
      val idx = text.indexOf("\"model\":\"")
      if idx >= 0 then
        val start = idx + 9
        val end = text.indexOf('\"', start)
        text.substring(start, end)
      else "local"
    catch case _: Exception => "local"
  private val BoundedStopMillis = 5000L

  def main(argv: Array[String]): Unit =
    // `chat` mode: interactive session with context continuity
    if argv.nonEmpty && argv(0) == "chat" then
      Unsafe.unsafe { implicit u =>
        val exitCode = zio.Runtime.default.unsafe.run(
          raider.cli.chat.ChatLoop.run(
            provider = argOr(argv, "--provider", "openai"),
            baseUrl  = argOr(argv, "--base-url", "http://127.0.0.1:8081/v1"),
            model    = argOr(argv, "--model", detectModel()),
            apiKey   = argOr(argv, "--api-key", "no-key"),
            workspace = argOrOpt(argv, "--workspace")
          )
        ).getOrThrow()
        System.exit(exitCode)
      }
    else
    CliArgs.parse(argv.toList) match
      case Left(err) =>
        System.err.println(s"raider: ${err.code}: ${err.detail}")
        System.exit(HeadlessRunner.exitCodeFor(err))
      case Right(parsed) =>
        Unsafe.unsafe { implicit u =>
          val runtime = zio.Runtime.default
          val done    = new AtomicBoolean(false)
          // forkDaemon: a plain .fork child gets interrupted by the
          // runtime supervisor when its parent root fiber completes —
          // the runner must outlive this Unsafe block (found by probe)
          val fiber   = runtime.unsafe.run(HeadlessRunner.run(parsed).forkDaemon)
                          .getOrThrow()

          val hook = new Thread(() => {
            runtime.unsafe.run(fiber.interrupt.forkDaemon).getOrThrow()

            val deadline = System.currentTimeMillis() + BoundedStopMillis
            while !done.get() && System.currentTimeMillis() < deadline do
              Thread.sleep(50)
            // RETURN — never System.exit here (see class doc)
          }, "raider-sigterm-stop")
          Runtime.getRuntime.addShutdownHook(hook)

          val exit: Int =
            try
              val outcome = runtime.unsafe.run(fiber.join).getOrThrow()
              done.set(true)
              outcome.result.foreach { r =>
                val line =
                  s"""{"status":"${r.status}","exitCode":${r.exitCode},""" +
                    s""""workDir":"${outcome.workDir}"}"""
                System.out.println(line)
              }
              outcome.exitCode
            catch
              case _: InterruptedException => done.set(true); SigTermExit
              case _: zio.FiberFailure     => done.set(true); SigTermExit
          // System.exit does not flush piped stdout: flush explicitly
          System.out.flush()
          System.err.flush()
          System.exit(exit)
        }
