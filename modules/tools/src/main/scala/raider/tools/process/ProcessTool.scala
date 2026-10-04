package raider.tools.process

import raider.core.RaiderError
import raider.tools.files.read.Workspace
import zio.{Duration, ZIO}

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Owned process primitives (RAI-016.a, runtime-contracts §5/§10).
  *
  * Structured argv ONLY: the command never passes through a shell, so shell
  * metacharacters are literal data (verified by test). The child environment is
  * constructed EXCLUSIVELY from an explicit allowlist × explicit values — no
  * ambient inheritance, therefore no inherited credentials (verified by a
  * sentinel test). Output capture is byte-bounded with declared truncation.
  *
  * KNOWN OBLIGATION (from RAI-001/ADR-015, honestly not claimed here): killing
  * the direct child does NOT prove grandchildren/process-group cleanup. Every
  * outcome therefore reports `CleanupGuarantee.DirectChildOnly`; a real
  * process-group kill is a separate card (PROC parent obligations).
  *
  * Sandbox capability is a NEGOTIATION TYPE (Supported/Unsupported/Unknown).
  * This tool reports Unknown — an unknown isolation is a DENY when sandbox is
  * required, never a silent fallback.
  */
enum SandboxCapability:
  case Supported, Unsupported, Unknown

/** Honest cleanup evidence: we can only claim what we proved. */
enum CleanupGuarantee:
  /** Direct child terminated; grandchildren unproven (RAI-001 gap). */
  case DirectChildOnly

final case class ExecRequest(
  argv: List[String],
  cwdRelative: Option[String] = None,
  envAllowlist: List[String] = Nil,
  envValues: Map[String, String] = Map.empty,
  timeout: Duration = Duration.fromSeconds(30),
  maxOutputBytes: Int = 256 * 1024,
  sandboxRequired: Boolean = false
)

final case class ExecOutcome(
  exitCode: Option[Int],
  stdout: String,
  stderr: String,
  truncated: Boolean,
  timedOut: Boolean,
  durationMs: Long,
  cleanup: CleanupGuarantee
)

final class ProcessTool(workspace: Workspace, val sandbox: SandboxCapability =
                          SandboxCapability.Unknown):

  def exec(req: ExecRequest): ZIO[Any, RaiderError, ExecOutcome] =
    validate(req) *>
      resolveCwd(req.cwdRelative).flatMap { cwd =>
        ZIO.attemptBlocking {
          val startedAt = System.nanoTime()
          val pb = new ProcessBuilder(req.argv*) // structured argv; NO shell
          pb.directory(cwd.toFile)
          pb.redirectErrorStream(false)
          val env = pb.environment()
          env.clear() // no ambient inheritance
          req.envAllowlist.foreach { key =>
            req.envValues.get(key).foreach(value => env.put(key, value))
          }

          val proc = pb.start()
          var truncated = false

          def drain(stream: java.io.InputStream, into: StringBuilder): Thread =
            new Thread(() =>
              try
                val buf   = new Array[Byte](4096)
                var taken = 0
                var open  = true
                while open do
                  val n = stream.read(buf)
                  if n < 0 then open = false
                  else if taken >= req.maxOutputBytes then
                    truncated = true
                    open = false // stop draining beyond the declared bound
                  else
                    val chunk = math.min(n, req.maxOutputBytes - taken)
                    into.append(new String(buf, 0, chunk, StandardCharsets.UTF_8))
                    taken += chunk
              catch case _: java.io.IOException => ()
            )

          val stdout = new StringBuilder
          val stderr = new StringBuilder
          val outT   = drain(proc.getInputStream, stdout)
          val errT   = drain(proc.getErrorStream, stderr)
          outT.start(); errT.start()

          val finished = proc.waitFor(req.timeout.toMillis, TimeUnit.MILLISECONDS)
          var timedOut = false
          if !finished then
            timedOut = true
            proc.destroy() // graceful first (SIGTERM semantics)
            if !proc.waitFor(2, TimeUnit.SECONDS) then
              proc.destroyForcibly()
              if !proc.waitFor(2, TimeUnit.SECONDS) then
                // still alive: we do NOT claim a confirmed kill
                stderr.append("\n[process-tool] child unkillable within grace")
          outT.join(2000); errT.join(2000)

          val exitCode =
            if proc.isAlive then None
            else Some(proc.exitValue())
          val durationMs = (System.nanoTime() - startedAt) / 1_000_000

          ExecOutcome(
            exitCode = exitCode,
            stdout = stdout.result(),
            stderr = stderr.result(),
            truncated = truncated,
            timedOut = timedOut,
            durationMs = durationMs,
            cleanup = CleanupGuarantee.DirectChildOnly // honest: gap from RAI-001
          )
        }.mapError(e => RaiderError.ToolFailed(
            s"process exec failed: ${e.getMessage}"))
      }

  private def validate(req: ExecRequest): ZIO[Any, RaiderError, Unit] =
    if req.argv.isEmpty then
      ZIO.fail(RaiderError.InputValidation("argv must be non-empty"))
    else if req.argv.exists(_ == null) || req.argv.head.trim.isEmpty then
      ZIO.fail(RaiderError.InputValidation("argv head must be a non-empty program name"))
    else if !req.envValues.keys.forall(req.envAllowlist.contains) then
      ZIO.fail(RaiderError.InputValidation(
        "envValues contains keys outside the allowlist: policy violation"))
    else if req.maxOutputBytes < 1 || req.maxOutputBytes > ProcessTool.HardMaxOutputBytes then
      ZIO.fail(RaiderError.InputValidation(
        s"maxOutputBytes must be in [1, ${ProcessTool.HardMaxOutputBytes}]"))
    else if req.sandboxRequired && sandbox != SandboxCapability.Supported then
      ZIO.fail(RaiderError.CapabilityUnsupported(
        s"sandbox required but capability is $sandbox: denied, no fallback"))
    else ZIO.unit

  private def resolveCwd(cwdRelative: Option[String]): ZIO[Any, RaiderError, java.nio.file.Path] =
    cwdRelative match
      case None => ZIO.succeed(workspace.rootReal)
      case Some(rel) =>
        ZIO.attemptBlocking {
          val raw = workspace.rootReal.resolve(rel).normalize()
          val checked: Either[RaiderError, java.nio.file.Path] =
            if !raw.startsWith(workspace.rootReal) then
              Left(RaiderError.ToolDenied("cwd escapes workspace"))
            else if !Files.isDirectory(raw) then
              Left(RaiderError.InputValidation(s"cwd is not a directory: $rel"))
            else Right(raw.toRealPath())
          checked
        }.mapError(e => RaiderError.ToolFailed(s"cwd resolve failed: ${e.getMessage}"))
          .flatMap {
            case Left(err) => ZIO.fail(err)
            case Right(p)  => ZIO.succeed(p)
          }

object ProcessTool:
  val HardMaxOutputBytes: Int = 8 * 1024 * 1024
