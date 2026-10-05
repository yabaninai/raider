package raider.cli.chat

import raider.core.*
import raider.runtime.budget.BudgetLimits
import raider.runtime.loop.AgentLoop
import zio.json.*
import zio.{Ref, ZIO}

import java.util.concurrent.atomic.AtomicInteger

/** Compilation error retry loop (nightly Phase 4.1): run the compile command
  * through the SAME proc_run tool the model uses, parse the output structurally
  * (OutputParser), and on failure send the errors back to the model as
  * conversation context with a fix request — up to `maxRounds` fix cycles. A
  * passing compile ends the loop immediately and never spends a model call;
  * persistent failure reports the remaining errors.
  *
  * The compile invocation is the RUNNER verifying, not an agent tool round — it
  * does not consume an AgentLoop round and is not retried on ambiguity (a
  * failed compile is a definitive, idempotent observation).
  */
object CompileFixLoop:

  final case class Outcome(
      attempts: Int,
      success: Boolean,
      lastErrors: List[String]
  )

  /** Wire shape of proc_run's `parsed` field (tolerant: everything optional).
    */
  private final case class ParsedWire(
      `type`: Option[String],
      success: Option[Boolean],
      errors: Option[List[ErrorWire]]
  )

  private object ParsedWire:
    given JsonDecoder[ParsedWire] = DeriveJsonDecoder.gen[ParsedWire]

  private final case class ErrorWire(
      file: String,
      line: Option[Int],
      col: Option[Option[Int]],
      message: Option[String]
  )

  private object ErrorWire:
    given JsonDecoder[ErrorWire] = DeriveJsonDecoder.gen[ErrorWire]

  private final case class ProcWire(
      exitCode: Option[Int],
      parsed: Option[ParsedWire]
  )

  private object ProcWire:
    given JsonDecoder[ProcWire] = DeriveJsonDecoder.gen[ProcWire]

  def run(
      loop: AgentLoop,
      registry: ToolRegistry,
      limits: BudgetLimits,
      historyRef: Ref[List[RequestMessage]],
      maxRounds: Int = 3,
      compileArgv: List[String] = List("sbt", "--batch", "compile")
  ): ZIO[Any, RaiderError, Outcome] =
    val tool = registry.lookup("proc_run") match
      case Some(t) => ZIO.succeed(t)
      case None =>
        ZIO.fail(
          RaiderError.InputValidation(
            "compile-fix loop needs the proc_run tool in the registry"
          )
        )
    tool.flatMap { proc =>
      val counter = AtomicInteger(0)

      def compileOnce(): ZIO[Any, RaiderError, CompileStatus] =
        val argvJson = compileArgv.mkString("[", ",", "]")
        val args = s"""{"argv":$argvJson,"timeout_s":300}"""
        ZIO
          .scoped:
            proc
              .invoke(args)
              .timeoutFail(
                RaiderError.DeadlineExceeded("compile exceeded 300s")
              )(zio.Duration.fromMillis(proc.timeoutMs))
          .flatMap: outputJson =>
            ZIO.fromEither(
              outputJson.fromJson[ProcWire].left.map { e =>
                RaiderError.OutputValidation(
                  s"proc_run output unparsable: ${e.take(120)}"
                )
              }
            )
          .flatMap: wire =>
            counter.incrementAndGet()
            classify(wire)

      def classify(wire: ProcWire): ZIO[Any, RaiderError, CompileStatus] =
        val parsed = wire.parsed
        val isCompile = parsed.flatMap(_.`type`).exists(_ == "sbt_compile")
        val failed = parsed.flatMap(_.success).exists(!_)
        if !failed && wire.exitCode.contains(0) then
          ZIO.succeed(CompileStatus.Success)
        else if isCompile then
          val errors = parsed.flatMap(_.errors).getOrElse(Nil).map(renderError)
          ZIO.succeed(CompileStatus.Failed(errors))
        else
          // unrecognized output: treat non-zero exit as generic failure with
          // no structured errors (honest: we don't fabricate error details)
          ZIO.succeed(CompileStatus.Failed(Nil))

      /** One fix cycle: send the errors to the model, then re-run the compile.
        * `round` bounds the model fix requests (master spec: repeat the
        * send→fix→recompile cycle up to maxRounds times).
        */
      def fixRound(
          round: Int,
          errors: List[String]
      ): ZIO[Any, RaiderError, Outcome] =
        if round > maxRounds then
          ZIO.succeed(Outcome(counter.get(), success = false, errors))
        else
          val errorBlock =
            if errors.isEmpty then
              "(compile exited non-zero; no structured errors parsed)"
            else errors.take(20).map(e => s"- $e").mkString("\n")
          val ask =
            RequestMessage(
              "user",
              s"""sbt compile failed (fix attempt $round/$maxRounds):
                 |$errorBlock
                 |
                 |Fix these errors. Read the files first (fs_read), make minimal
                 |edits (fs_patch for small changes, fs_edit for rewrites), then
                 |verify with proc_run.""".stripMargin
            )
          for
            history <- historyRef.get
            answer <- loop.runText(
              (history :+ ask).takeRight(ChatLoop.MaxHistoryMessages),
              limits,
              math.min(limits.maxAttempts, 12)
            )
            _ <- historyRef.update: h =>
              (h :+ ask :+ RequestMessage("assistant", answer))
                .takeRight(ChatLoop.MaxHistoryMessages)
            next <- compileOnce()
            outcome <- next match
              case CompileStatus.Success =>
                ZIO.succeed(Outcome(counter.get(), success = true, Nil))
              case CompileStatus.Failed(nextErrors) =>
                fixRound(round + 1, nextErrors)
          yield outcome
        // NOTE: fixRound recurses; ZIO laziness keeps this stack-safe

      compileOnce().flatMap:
        case CompileStatus.Success =>
          ZIO.succeed(Outcome(counter.get(), success = true, Nil))
        case CompileStatus.Failed(errors) =>
          fixRound(1, errors)
    }

  private def renderError(e: ErrorWire): String =
    val col = e.col.flatten match
      case Some(c) => s":$c"
      case None    => ""
    val line = e.line.map(l => s":$l").getOrElse("")
    s"${e.file}$line$col ${e.message.getOrElse("")}"

  private sealed trait CompileStatus

  private object CompileStatus:
    case object Success extends CompileStatus
    final case class Failed(errors: List[String]) extends CompileStatus
