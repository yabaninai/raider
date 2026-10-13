package demo

import raider.cli.run.{CliArgs, CiContext, HeadlessRunner}
import raider.core.{Program, RaiderError, Task}
import raider.core.trace.{RunTrace, TraceEvent}
import raider.dsl.composition.{BatchOutcome, all, batch}
import zio.json.*
import zio.{Console, Duration, Ref, Semaphore, ZIO, Unsafe}

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** A six-invocation generic CI workflow. Native runs remain independent; the
  * application index links their real artifact directories without claiming a
  * shared native root, budget, phase graph, or durable resume.
  * This is for a trusted local fixture: all coder calls receive the current
  * full tool registry and process privileges. Reviewer prompts request no
  * tools, and observed tool calls/workspace changes fail postconditions; those
  * checks are not a permission boundary or sandbox.
  */
object BillingCi:

  private val RequiredModel = "glm-5.3-flash"
  private val NativeDeadline = Duration.fromSeconds(120)
  private val PipelineDeadline = Duration.fromSeconds(300)

  enum StageStatus derives JsonCodec:
    case Pending, Running, Succeeded, Failed, Unknown

  final case class StageRef(
      name: String,
      status: StageStatus,
      nativeWorkDir: Option[String],
      nativeRootId: Option[String],
      nativeExitCode: Option[Int],
      lineageSummary: Option[String],
      lineageFiles: Vector[String],
      verdict: Option[String],
      answerFile: String
  ) derives JsonCodec

  final case class PipelineIndex(
      schemaVersion: Int,
      status: String,
      startedAt: String,
      updatedAt: String,
      stages: Vector[StageRef],
      decision: Option[Decision],
      detail: Option[String],
      budgetNote: String
  ) derives JsonCodec

  final case class Decision(
      verdict: String,
      repairCheckExit: Int,
      sourceChanged: Boolean,
      reviewPasses: Int,
      boundaryPasses: Int,
      requiredBoundaryCases: List[Int],
      detail: String
  ) derives JsonCodec

  final case class AgentInput(
      stage: String,
      prompt: String,
      workspace: Path,
      stageDir: Path,
      nativeOut: Path,
      runtimeClasspath: String,
      baseUrl: String,
      model: String,
      contextFile: Path,
      env: Map[String, String],
      index: Ref[PipelineIndex],
      indexLock: Semaphore,
      artifactRoot: Path
  )

  final case class AgentOutput(
      stage: String,
      exitCode: Int,
      workDir: Option[Path],
      answer: String,
      toolCalls: Int,
      traceParsed: Boolean,
      rootId: Option[String],
      lineageSummary: Option[String],
      lineageFiles: Vector[String],
      status: StageStatus
  )

  final case class ReviewInput(
      stage: String,
      source: String,
      workspace: Path,
      root: Path,
      runtimeClasspath: String,
      baseUrl: String,
      model: String,
      env: Map[String, String],
      context: Path,
      index: Ref[PipelineIndex],
      indexLock: Semaphore
  )

  final case class BoundaryInput(review: ReviewInput, subtotal: Int)
  final case class BoundaryEvidence(reply: BoundaryReply, attempt: AgentOutput)
  final case class VerifiedRepair(sourceChanged: Boolean, sourceText: String, attempt: AgentOutput)

  final case class ReviewReply(verdict: String, reason: String)
  object ReviewReply:
    given JsonDecoder[ReviewReply] = DeriveJsonDecoder.gen[ReviewReply]

  final case class BoundaryReply(
      subtotalCents: Int,
      expectedDiscountCents: Int,
      observedDiscountCents: Int,
      verdict: String,
      reason: String
  )
  object BoundaryReply:
    given JsonDecoder[BoundaryReply] = DeriveJsonDecoder.gen[BoundaryReply]

  def main(args: Array[String]): Unit =
    val program: ZIO[Any, Nothing, Int] =
      if args.length != 3 then
        Console.printLineError("usage: demo.BillingCi <runtime-classpath> <artifact-output-dir> <fixture-workspace>").ignore.as(1)
      else
        execute(args).timeout(PipelineDeadline).foldZIO(
          _ => ZIO.succeed(1),
          {
            case Some(true) => ZIO.succeed(0)
            case Some(false) => ZIO.succeed(pipelineExitCode(Paths.get(args(1)).toAbsolutePath))
            case None =>
              markUnknown(Paths.get(args(1)).toAbsolutePath, "pipeline deadline exceeded").as(24)
          }
        )
    val exitCode = Unsafe.unsafe { implicit unsafe =>
      zio.Runtime.default.unsafe.run(program).getOrThrowFiberFailure()
    }
    // Exit after the managed effect and its finalizers have completed.
    System.exit(exitCode)

  private def execute(args: Array[String]): ZIO[Any, Throwable, Boolean] =
    if args.length != 3 then
      Console.printLineError(
        "usage: demo.BillingCi <runtime-classpath> <artifact-output-dir> <fixture-workspace>"
      ).ignore.as(false)
    else
      val runtimeClasspath = args(0)
      val artifactRoot = Paths.get(args(1)).toAbsolutePath.normalize
      val fixture = Paths.get(args(2)).toAbsolutePath.normalize
      executePipeline(runtimeClasspath, artifactRoot, fixture)
        .catchAllCause { cause =>
          val detail = cause.failureOption.map(_.getClass.getSimpleName).getOrElse("pipeline failure")
          (if cause.isInterrupted then markUnknown(artifactRoot, detail) else markFailed(artifactRoot, detail)) *>
            Console.printLineError(s"Billing CI failed: $detail").ignore *>
            ZIO.failCause(cause)
        }

  private def executePipeline(
      runtimeClasspath: String,
      artifactRoot: Path,
      fixture: Path
  ): ZIO[Any, Throwable, Boolean] =
    for
      env <- ZIO.succeed(sys.env)
      _ <- ZIO.attemptBlocking {
        Files.createDirectories(artifactRoot)
        require(Files.isDirectory(fixture), "fixture workspace is not a directory")
      }
      now = Instant.now().toString
      initial = PipelineIndex(
        schemaVersion = 1,
        status = "RUNNING",
        startedAt = now,
        updatedAt = now,
        stages = Vector.empty,
        decision = None,
      detail = None,
      budgetNote = "Each native invocation has its own default maxAttempts=400; there is no shared pipeline budget."
      )
      index <- Ref.make(initial)
      indexLock <- Semaphore.make(1L)
      _ <- persistIndex(index, indexLock, artifactRoot)
      config <- ZIO.fromEither(validateLiveConfig(env))
      setup <- ZIO.attemptBlocking {
        val workspace = artifactRoot.resolve("workspace")
        copyTree(fixture, workspace)
        val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
        val checker = prepareChecker(workspace, java, runtimeClasspath)
        val checkerHash = sha256(Files.readAllBytes(checker))
        val context = artifactRoot.resolve("ci-context.json")
        writeAtomic(context, CiContext(
          ci = "generic",
          repo = "raider-user-guide-fixture",
          ref = "local",
          sha = sha256(Files.readAllBytes(workspace.resolve("src/main/scala/billing/Discount.scala"))),
          pipeline = "billing-ci-example",
          job = "example",
          trusted = false
        ).toJson)
        val source = workspace.resolve("src/main/scala/billing/Discount.scala")
        val initialFiles = inventory(workspace)
        Setup(workspace, checker, checkerHash, source, readUtf8(source), initialFiles,
          runtimeClasspath, config.baseUrl, config.model, env, context, index, indexLock, artifactRoot)
      }
      _ <- recordStage(index, indexLock, artifactRoot, "baseline-check", StageStatus.Running, None, None, None)
      baseline <- runCommand(List("/bin/sh", ".example-checks/discount-check.sh"), setup.workspace,
        artifactRoot.resolve("baseline-check.log"), 45)
      _ <- recordStage(index, indexLock, artifactRoot, "baseline-check",
        if baseline.exitCode.contains(1) && !baseline.timedOut && !baseline.truncated &&
          baseline.stdout == "FAIL exact-threshold\n" then StageStatus.Succeeded else StageStatus.Failed,
        None, baseline.exitCode, Some("expected exact-threshold failure"))
      _ <- ZIO.when(!(baseline.exitCode.contains(1) && !baseline.timedOut && !baseline.truncated &&
        baseline.stdout == "FAIL exact-threshold\n")) {
        ZIO.fail(new IllegalStateException("baseline did not compile and fail only at exact threshold"))
      }
      repairInput = AgentInput(
        "repair", repairPrompt(setup.sourceText), setup.workspace,
        artifactRoot.resolve("stages/repair"), artifactRoot.resolve("stages/repair/native"),
        runtimeClasspath, setup.baseUrl, setup.model, setup.context, env, index, indexLock, artifactRoot
      )
      repairProgram = agentProgram("billing-repair")
      verifyProgram = Program.fromFunction[AgentOutput, VerifiedRepair]("verify-repair") { attempt =>
        Task(ZIO.attemptBlocking {
          val evidence = validateRepair(setup, attempt)
          VerifiedRepair(evidence.sourceChanged, readUtf8(setup.source), attempt)
        }.mapError(error => RaiderError.OutputValidation(Option(error.getMessage).getOrElse("repair verification failed"))))
      }
      repairAndVerify = repairProgram.andThen(verifyProgram)
      repaired <- Task.zio(repairAndVerify(repairInput))
      repair = repaired.attempt
      _ <- recordStage(index, indexLock, artifactRoot, "repair", StageStatus.Succeeded,
        repair.workDir.map(relative(artifactRoot, _)), Some(repair.exitCode), Some("source changed; native run succeeded"),
        repair.rootId, repair.lineageSummary, repair.lineageFiles)
      finalCheck <- runCommand(List("/bin/sh", ".example-checks/discount-check.sh"), setup.workspace,
        artifactRoot.resolve("final-independent-check.log"), 45)
      _ <- ZIO.when(!(finalCheck.exitCode.contains(0) && !finalCheck.timedOut &&
        !finalCheck.truncated && finalCheck.stdout.contains("DiscountSpec: PASS 3 boundaries"))) {
        recordStage(index, indexLock, artifactRoot, "final-check", StageStatus.Failed,
          None, finalCheck.exitCode, Some("independent pinned check failed")) *>
          ZIO.fail(new IllegalStateException("independent compiler and boundary tests failed"))
      }
      _ <- recordStage(index, indexLock, artifactRoot, "final-check", StageStatus.Succeeded,
        None, finalCheck.exitCode, Some("DiscountSpec: PASS 3 boundaries"))
      reviewSource = repaired.sourceText
      reviewsInput <- makeReviewInputs(setup, reviewSource)
      reviewProgram = Program.fromFunction[ReviewInput, AgentOutput]("inline-review") { review =>
        val role = if review.stage.endsWith("-a") then "requirements and boundary" else "arithmetic and side-effects"
        Task(invokeAgent(prepareReviewer(review, review.stage, role, review.source)))
      }
      reviews <- Task.zio(all(reviewProgram(reviewsInput._1), reviewProgram(reviewsInput._2)))
      reviewResults = List(
        validateReview("independent-review-a", reviewSource, reviewsInput._1.workspace, reviews._1),
        validateReview("independent-review-b", reviewSource, reviewsInput._2.workspace, reviews._2)
      )
      _ <- ZIO.foreachDiscard(reviewResults) { r =>
        recordStage(index, indexLock, artifactRoot, r.stage,
          if r.passed then StageStatus.Succeeded else StageStatus.Failed,
          r.attempt.workDir.map(relative(artifactRoot, _)), Some(r.attempt.exitCode), Some(r.detail),
          r.attempt.rootId, r.attempt.lineageSummary, r.attempt.lineageFiles)
      }
      boundaryInputs = List(9999, 10000, 10001).map { cents =>
        BoundaryInput(reviewsInput._3.copy(
          stage = s"boundary-$cents",
          workspace = artifactRoot.resolve(s"review-workspaces/boundary-$cents"),
          context = artifactRoot.resolve(s"stages/boundary-$cents/ci-context.json")
        ), cents)
      }
      boundaryProgram = Program.fromFunction[BoundaryInput, BoundaryEvidence]("boundary-case-review") { input =>
        val stage = s"boundary-${input.subtotal}"
        val prompt = boundaryPrompt(reviewSource, input.subtotal)
        val request = AgentInput(stage, prompt, input.review.workspace.resolve(stage),
          input.review.root.resolve(s"stages/$stage"), input.review.root.resolve(s"stages/$stage/native"),
          runtimeClasspath, config.baseUrl, config.model, input.review.context, env, index, indexLock, artifactRoot)
        Task(invokeAgent(request).flatMap { attempt =>
          if attempt.status != StageStatus.Succeeded then
            ZIO.fail(RaiderError.ToolFailed(s"$stage native run failed (${attempt.exitCode})"))
          else if !attempt.traceParsed || attempt.toolCalls != 0 then
            ZIO.fail(RaiderError.OutputValidation(s"$stage used a tool or has no parseable transcript"))
          else ZIO.attemptBlocking(inventory(request.workspace).isEmpty)
            .mapError(_ => RaiderError.OutputValidation(s"$stage workspace could not be checked"))
            .flatMap { clean =>
              if !clean then ZIO.fail(RaiderError.OutputValidation(s"$stage changed its disposable workspace"))
              else parseBoundary(attempt, input.subtotal) match
                case Right(reply) => ZIO.succeed(BoundaryEvidence(reply, attempt))
                case Left(detail) => ZIO.fail(RaiderError.InputValidation(s"$stage: $detail"))
            }
        })
      }
      boundaryOutcomes <- Task.zio(batch.collect(boundaryInputs, parallelism = 2) { input =>
        boundaryProgram(input)
      })
      boundaryPasses = boundaryOutcomes.count {
        case BatchOutcome.Succeeded(evidence) => validBoundary(evidence.reply)
        case _ => false
      }
      _ <- ZIO.foreachDiscard(boundaryInputs.zip(boundaryOutcomes)) { case (input, outcome) =>
        val evidence = outcome match
          case BatchOutcome.Succeeded(value) => Some(value)
          case BatchOutcome.Failed(_)        => None
        val attempt = evidence.map(_.attempt)
        val passed = evidence.exists(value => validBoundary(value.reply))
        recordStage(index, indexLock, artifactRoot, s"boundary-${input.subtotal}",
          if passed then StageStatus.Succeeded else StageStatus.Failed,
          attempt.flatMap(_.workDir.map(relative(artifactRoot, _))),
          attempt.map(_.exitCode),
          Some(outcome match
            case BatchOutcome.Succeeded(evidence) => s"${evidence.reply.verdict}: ${evidence.reply.reason}"
            case BatchOutcome.Failed(error) => error.code
          ), attempt.flatMap(_.rootId), attempt.flatMap(_.lineageSummary), attempt.toVector.flatMap(_.lineageFiles))
      }
      decision = Decision(
        verdict = if reviewResults.forall(_.passed) && boundaryPasses == 3 then "READY" else "BLOCKED",
        repairCheckExit = finalCheck.exitCode.getOrElse(-1),
        sourceChanged = repaired.sourceChanged,
        reviewPasses = reviewResults.count(_.passed),
        boundaryPasses = boundaryPasses,
        requiredBoundaryCases = List(9999, 10000, 10001),
        detail = if reviewResults.forall(_.passed) && boundaryPasses == 3 then
          "Independent compile and all three expected-discount reviews passed."
        else "One or more model review stages did not satisfy the exact typed contract."
      )
      _ <- ZIO.attemptBlocking(writeAtomic(artifactRoot.resolve("decision.json"), decision.toJson))
      _ <- updatePipeline(index, indexLock, artifactRoot, if decision.verdict == "READY" then "SUCCEEDED" else "BLOCKED", Some(decision), None)
    yield decision.verdict == "READY"

  private final case class Setup(
      workspace: Path,
      checker: Path,
      checkerHash: String,
      source: Path,
      sourceText: String,
      initialFiles: Map[String, String],
      runtimeClasspath: String,
      baseUrl: String,
      model: String,
      env: Map[String, String],
      context: Path,
      index: Ref[PipelineIndex],
      indexLock: Semaphore,
      artifactRoot: Path
  ):
    val java: String = Paths.get(System.getProperty("java.home"), "bin", "java").toString

  private final case class ProcessResult(
      exitCode: Option[Int],
      stdout: String,
      stderr: String,
      timedOut: Boolean,
      truncated: Boolean = false
  )

  private final case class ReviewResult(stage: String, passed: Boolean, detail: String, attempt: AgentOutput)

  private final case class LiveConfig(baseUrl: String, model: String)

  private def validateLiveConfig(env: Map[String, String]): Either[Throwable, LiveConfig] =
    val required = List("RAIDER_LIVE_APPROVED", "RAIDER_PROVIDER", "RAIDER_BASE_URL", "RAIDER_MODEL")
    val missing = required.filter(k => env.get(k).forall(_.trim.isEmpty))
    if missing.nonEmpty then Left(new IllegalArgumentException(s"missing required environment names: ${missing.mkString(", ")}"))
    else if env("RAIDER_LIVE_APPROVED") != "1" || env("RAIDER_PROVIDER") != "openai" then
      Left(new IllegalArgumentException("live opt-in must be 1 and provider must be openai"))
    else if env("RAIDER_MODEL") != RequiredModel then
      Left(new IllegalArgumentException(s"RAIDER_MODEL must be $RequiredModel"))
    else if env.get("RAIDER_API_KEY").forall(_.trim.isEmpty) && env.get("RAIDER_API_KEY_CMD").forall(_.trim.isEmpty) then
      Left(new IllegalArgumentException("configure RAIDER_API_KEY or RAIDER_API_KEY_CMD in the private environment"))
    else
      try
        val uri = URI.create(env("RAIDER_BASE_URL"))
        if !Set("http", "https").contains(uri.getScheme) || uri.getHost == null ||
          uri.getUserInfo != null || uri.getQuery != null || uri.getFragment != null then
          Left(new IllegalArgumentException("RAIDER_BASE_URL must be an HTTP(S) endpoint without userinfo, query, or fragment"))
        else Right(LiveConfig(env("RAIDER_BASE_URL"), env("RAIDER_MODEL")))
      catch case _: IllegalArgumentException => Left(new IllegalArgumentException("RAIDER_BASE_URL is not a valid HTTP(S) endpoint"))

  private def agentProgram(name: String): Program[AgentInput, AgentOutput] =
    Program.fromFunction[AgentInput, AgentOutput](name)(input => Task(invokeAgent(input)))

  private def invokeAgent(input: AgentInput): ZIO[Any, Nothing, AgentOutput] =
    val answerFile = input.stageDir.resolve("answer.txt")
    val promptFile = input.stageDir.resolve("input.txt")
    for
      _ <- recordStage(input.index, input.indexLock, input.artifactRoot, input.stage,
        StageStatus.Running, None, None, None)
      prepared <- ZIO.attemptBlocking {
        Files.createDirectories(input.workspace)
        Files.createDirectories(input.nativeOut)
        Files.createDirectories(input.stageDir)
        writeAtomic(promptFile, input.prompt)
        writeAtomic(input.contextFile, CiContext(
          "generic", "raider-user-guide-fixture", "local", "fixture", "billing-ci-example", input.stage, false
        ).toJson)
        CliArgs.parse(List(
          "run", "--agent", "coder", "--provider", "openai", "--base-url", input.baseUrl,
          "--model", input.model, "--workspace", input.workspace.toString, "--input", input.prompt,
          "--out", input.nativeOut.toString, "--context", input.contextFile.toString
        ))
      }.either
      result <- prepared match
        case Left(error) =>
          val detail = s"setup failed: ${error.getClass.getSimpleName}"
          recordStage(input.index, input.indexLock, input.artifactRoot, input.stage,
            StageStatus.Failed, None, None, Some(detail)).as(
              AgentOutput(input.stage, 21, None, "", 0, false, None, None, Vector.empty, StageStatus.Failed)
            )
        case Right(Left(error)) =>
          val detail = s"CLI args rejected: ${error.code}"
          recordStage(input.index, input.indexLock, input.artifactRoot, input.stage,
            StageStatus.Failed, None, Some(21), Some(detail)).as(
              AgentOutput(input.stage, 21, None, "", 0, false, None, None, Vector.empty, StageStatus.Failed)
            )
        case Right(Right(cliArgs)) =>
          HeadlessRunner.run(cliArgs, input.env, Some("generic")).timeout(NativeDeadline).flatMap {
            case None =>
              recordStage(input.index, input.indexLock, input.artifactRoot, input.stage,
                StageStatus.Unknown, None, None, Some("native invocation deadline; outcome unknown")) *>
                ZIO.succeed(AgentOutput(input.stage, 24, None, "", 0, false, None, None, Vector.empty, StageStatus.Unknown))
            case Some(outcome) =>
              val answer = outcome.result.flatMap(_.output).flatMap(_.json.fromJson[String].toOption).getOrElse("")
              val tracePath = outcome.workDir.resolve("transcript.json")
              val trace = try readUtf8(tracePath).fromJson[RunTrace].toOption catch case _: Exception => None
              val toolCalls = trace.toVector.flatMap(_.events).count(_.isInstanceOf[TraceEvent.ToolCallStarted])
              val summary = outcome.workDir.resolve("summary.md")
              val lineageFiles = List("lineage.scala", "lineage-data.json").filter(n => Files.isRegularFile(outcome.workDir.resolve(n))).map(n => relative(input.artifactRoot, outcome.workDir.resolve(n))).toVector
              val status = if outcome.exitCode == 0 && answer.nonEmpty then StageStatus.Succeeded else StageStatus.Failed
              val work = Some(outcome.workDir)
              ZIO.attemptBlocking(writeAtomic(answerFile, answer)).orDie.flatMap { _ =>
                recordStage(input.index, input.indexLock, input.artifactRoot, input.stage,
                  status, work.map(relative(input.artifactRoot, _)), Some(outcome.exitCode),
                  Some(if status == StageStatus.Succeeded then "native run completed" else "native run failed"),
                  outcome.result.map(_.rootId.value),
                  if Files.isRegularFile(summary) then Some(relative(input.artifactRoot, summary)) else None,
                  lineageFiles) *>
                  ZIO.succeed(AgentOutput(input.stage, outcome.exitCode, work, answer, toolCalls, trace.nonEmpty,
                    outcome.result.map(_.rootId.value), if Files.isRegularFile(summary) then Some(relative(input.artifactRoot, summary)) else None,
                    lineageFiles, status))
              }
          }
    yield result

  private def repairPrompt(source: String): String =
    s"""Task: repair the billing discount boundary in this Scala 3 fixture.
       |Policy: a flat $$10 (1000 cents) discount applies when subtotal is at least $$100 (10000 cents).
       |Runbook: read RUNBOOK.md and run its exact checker before editing. It must compile and fail only at the exact threshold. Make the smallest change to Discount.scala, then run the same checker and require exit 0.
       |Do not edit the test, checker, or runbook. Do not run sbt or another command. Report the exact source change and check outputs.
       |
       |Current Discount.scala:
       |```scala
       |$source
       |```
       |""".stripMargin

  private def reviewPrompt(source: String, role: String): String =
    val focus =
      if role == "requirements and boundary" then
        "Check the inclusive 10000-cent threshold and the flat discount against the policy."
      else
        "Check integer arithmetic, possible overflow, deterministic output, and side effects in this function. Judge only the stated policy and visible source."
    s"""Review this Scala billing implementation as the $role reviewer.
       |Focus: $focus
       |Policy: a flat 1000-cent discount applies for every subtotal >= 10000 cents.
       |Analyze the inline source only. Do not use tools, read or write files, or run commands.
       |Return only JSON: {"verdict":"PASS"|"FAIL","reason":"one concise sentence"}.
       |
       |Inline source:
       |```scala
       |$source
       |```
       |""".stripMargin

  private def boundaryPrompt(source: String, cents: Int): String =
    s"""Review one required billing boundary using only the inline Scala source.
       |Policy: discountCents(s) returns 1000 exactly when s >= 10000, otherwise 0.
       |subtotalCents=$cents. Calculate expectedDiscountCents from that policy and observedDiscountCents from the inline function.
       |Do not use tools, read or write files, or run commands.
       |Return only JSON with integer fields subtotalCents, expectedDiscountCents, observedDiscountCents, string verdict (PASS or FAIL), and non-empty reason.
       |
       |Inline source:
       |```scala
       |$source
       |```
       |""".stripMargin

  private final case class RepairEvidence(sourceChanged: Boolean)
  private def validateRepair(setup: Setup, attempt: AgentOutput): RepairEvidence =
    require(attempt.status == StageStatus.Succeeded && attempt.exitCode == 0 && attempt.traceParsed, "repair native invocation failed")
    require(attempt.toolCalls > 0, "repair did not record native tool calls")
    val now = inventory(setup.workspace)
    val sourceRel = setup.workspace.relativize(setup.source).toString
    require(now.keySet == setup.initialFiles.keySet, "repair added or removed fixture files")
    require(now(sourceRel) != setup.initialFiles(sourceRel), "repair did not modify Discount.scala")
    now.filterNot(_._1 == sourceRel).foreach { case (p, hash) =>
      require(setup.initialFiles.get(p).contains(hash), s"protected fixture file changed: $p")
    }
    require(readUtf8(setup.source).contains("s >= 10000"), "repair does not use the inclusive threshold")
    require(sha256(Files.readAllBytes(setup.checker)) == setup.checkerHash, "pinned checker changed")
    RepairEvidence(sourceChanged = true)

  private def makeReviewInputs(
      setup: Setup,
      source: String
  ): ZIO[Any, Throwable, (ReviewInput, ReviewInput, ReviewInput)] =
    val specs = List("independent-review-a", "independent-review-b", "boundary-9999")
    ZIO.attemptBlocking {
      val workspaces = specs.map { name =>
        val path = setup.artifactRoot.resolve("review-workspaces").resolve(name)
        Files.createDirectories(path)
        path
      }
      val root = setup.artifactRoot
      val mk = (name: String, ws: Path) => ReviewInput(name, source, ws, root,
        setup.runtimeClasspath, setup.baseUrl, setup.model, setup.env,
        root.resolve(s"stages/$name/ci-context.json"), setup.index, setup.indexLock)
      (mk("independent-review-a", workspaces(0)), mk("independent-review-b", workspaces(1)), mk("boundary-9999", workspaces(2)))
    }

  private def validateReview(stage: String, source: String, workspace: Path, attempt: AgentOutput): ReviewResult =
    val callsAreForbidden = attempt.toolCalls == 0 && attempt.traceParsed
    val noWorkspaceFiles = inventory(workspace).isEmpty
    val parsed = jsonBody(attempt.answer).fromJson[ReviewReply].toOption
    val pass = attempt.status == StageStatus.Succeeded && callsAreForbidden && noWorkspaceFiles &&
      parsed.exists(r => r.verdict == "PASS" && r.reason.trim.nonEmpty) &&
      source.contains("s >= 10000")
    val detail = if !callsAreForbidden then "review requested a tool; rejected"
      else if !noWorkspaceFiles then "review workspace changed; rejected"
      else parsed.map(_.reason).getOrElse("invalid review JSON or native run failure")
    ReviewResult(stage, pass, detail, attempt)

  private def parseBoundary(attempt: AgentOutput, expectedSubtotal: Int): Either[String, BoundaryReply] =
    jsonBody(attempt.answer).fromJson[BoundaryReply].left.map(_ => "invalid JSON response").flatMap { reply =>
      if reply.subtotalCents != expectedSubtotal then Left("subtotal mismatch")
      else if reply.reason.trim.isEmpty then Left("reason is empty")
      else if !validBoundary(reply) then Left("verdict or expected/observed discount does not match policy")
      else Right(reply)
    }

  private def jsonBody(answer: String): String =
    val lines = answer.trim.linesIterator.toList
    if lines.size >= 3 && Set("```json", "```").contains(lines.head.trim) && lines.last.trim == "```" then
      lines.tail.init.mkString("\n").trim
    else answer.trim

  private def validBoundary(reply: BoundaryReply): Boolean =
    val expected = if reply.subtotalCents >= 10000 then 1000 else 0
    reply.expectedDiscountCents == expected && reply.observedDiscountCents == expected && reply.verdict == "PASS"

  private def runCommand(
      argv: List[String],
      cwd: Path,
      log: Path,
      timeoutSeconds: Long
  ): ZIO[Any, Throwable, ProcessResult] =
    ZIO.attemptBlocking(Files.createDirectories(log.getParent)) *>
      ZIO.scoped {
        val builder = new ProcessBuilder(argv*)
        builder.directory(cwd.toFile)
        builder.redirectErrorStream(true)
        builder.environment().clear()
        ZIO.acquireReleaseWith(ZIO.attemptBlockingInterrupt(builder.start()))(
          process => ZIO.attemptBlockingInterrupt(terminateProcess(process)).orDie
        ) { process =>
          for
            output <- ZIO.attemptBlockingInterrupt(captureOutput(process.getInputStream)).forkScoped
            finished <- ZIO.attemptBlockingInterrupt(process.waitFor(timeoutSeconds, TimeUnit.SECONDS))
            _ <- if !finished then ZIO.attemptBlockingInterrupt(terminateProcess(process)) else ZIO.unit
            captured <- output.join.timeoutFail(new IllegalStateException("process output did not close"))(Duration.fromSeconds(5))
            result <- ZIO.attemptBlocking {
              val (bytes, truncated) = captured
              val text = new String(bytes, UTF_8)
              writeAtomic(log, text)
              ProcessResult(
                if process.isAlive then None else Some(process.exitValue()),
                text, "", timedOut = !finished, truncated = truncated
              )
            }
          yield result
        }
      }

  private def captureOutput(input: java.io.InputStream): (Array[Byte], Boolean) =
    val out = new java.io.ByteArrayOutputStream(256 * 1024)
    val buffer = new Array[Byte](8192)
    var total = 0L
    var n = input.read(buffer)
    while n >= 0 do
      val keep = math.min(n, math.max(0, 256 * 1024 - out.size()))
      if keep > 0 then out.write(buffer, 0, keep)
      total += n
      n = input.read(buffer)
    input.close()
    (out.toByteArray, total > out.size())

  private def terminateProcess(process: Process): Unit =
    val descendants = process.toHandle.descendants().iterator().asScala.toList
    descendants.foreach(_.destroyForcibly())
    process.destroyForcibly()
    process.waitFor(3, TimeUnit.SECONDS)

  private def prepareChecker(workspace: Path, javaCommand: String, cp: String): Path =
    val build = workspace.resolve("build/classes")
    Files.createDirectories(build)
    val checker = workspace.resolve(".example-checks/discount-check.sh")
    val compile = List(javaCommand, "-cp", cp, "dotty.tools.dotc.Main", "-classpath", cp, "-Werror", "-d",
      "build/classes", "src/main/scala/billing/Discount.scala", "src/test/scala/billing/DiscountSpec.scala")
    val test = List(javaCommand, "-cp", s"build/classes${java.io.File.pathSeparator}$cp", "billing.DiscountSpec")
    val script = "#!/bin/sh\nset -eu\n" + shellJoin(compile) + "\n" + shellJoin(test) + "\n"
    writeAtomic(checker, script)
    writeAtomic(workspace.resolve("RUNBOOK.md"),
      "# Billing boundary check\n\nRun `/bin/sh .example-checks/discount-check.sh` from the workspace root before and after the edit. Before the fix, compilation succeeds and only the exact-threshold assertion fails. After the fix all three assertions pass. Do not edit this checker, this runbook, or the test.\n")
    checker

  private def copyTree(source: Path, target: Path): Unit =
    require(source != target && !target.startsWith(source), "output workspace must be outside fixture source")
    val stream = Files.walk(source)
    try stream.iterator().asScala.foreach { p =>
      val dest = target.resolve(source.relativize(p))
      if Files.isDirectory(p) then Files.createDirectories(dest)
      else Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING)
    }
    finally stream.close()

  private def inventory(root: Path): Map[String, String] =
    if !Files.exists(root) then Map.empty
    else
      val stream = Files.walk(root)
      try stream.iterator().asScala.filter(Files.isRegularFile(_)).filterNot { p =>
        root.relativize(p).toString.replace('\\', '/').startsWith("build/classes/")
      }.map { p =>
        root.relativize(p).toString -> sha256(Files.readAllBytes(p))
      }.toVector.toMap
      finally stream.close()

  private def shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
  private def shellJoin(argv: List[String]): String = argv.map(shellQuote).mkString(" ")
  private def readUtf8(path: Path): String = Files.readString(path, UTF_8)
  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
  private def writeAtomic(path: Path, text: String): Unit =
    Files.createDirectories(path.getParent)
    val temp = path.resolveSibling(path.getFileName.toString + ".tmp")
    Files.writeString(temp, text, UTF_8)
    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

  private def relative(root: Path, path: Path): String =
    try root.relativize(path.toAbsolutePath.normalize).toString.replace('\\', '/')
    catch case _: Exception => path.getFileName.toString

  private def recordStage(
      index: Ref[PipelineIndex], lock: Semaphore, root: Path, name: String,
      status: StageStatus, workDir: Option[String], exit: Option[Int], verdict: Option[String],
      rootId: Option[String] = None, lineageSummary: Option[String] = None, lineageFiles: Vector[String] = Vector.empty
  ): ZIO[Any, Nothing, Unit] =
    lock.withPermit {
      index.update { current =>
        val answerFile = relative(root, root.resolve(s"stages/$name/answer.txt"))
        val previous = current.stages.find(_.name == name)
        val next = StageRef(name, status,
          workDir.orElse(previous.flatMap(_.nativeWorkDir)),
          rootId.orElse(previous.flatMap(_.nativeRootId)),
          exit.orElse(previous.flatMap(_.nativeExitCode)),
          lineageSummary.orElse(previous.flatMap(_.lineageSummary)),
          if lineageFiles.nonEmpty then lineageFiles else previous.toVector.flatMap(_.lineageFiles),
          verdict, answerFile)
        val prior = current.stages.filterNot(_.name == name)
        current.copy(updatedAt = Instant.now().toString, stages = prior :+ next)
      } *> index.get.flatMap(current => ZIO.attemptBlocking(writeAtomic(root.resolve("pipeline-index.json"), current.toJson)).orDie)
    }

  private def updatePipeline(
      index: Ref[PipelineIndex], lock: Semaphore, root: Path,
      status: String, decision: Option[Decision], detail: Option[String]
  ): ZIO[Any, Nothing, Unit] =
    lock.withPermit {
      index.update(_.copy(status = status, updatedAt = Instant.now().toString, decision = decision, detail = detail)) *>
        index.get.flatMap(current => ZIO.attemptBlocking(writeAtomic(root.resolve("pipeline-index.json"), current.toJson)).orDie)
    }

  private def persistIndex(index: Ref[PipelineIndex], lock: Semaphore, root: Path): ZIO[Any, Nothing, Unit] =
    lock.withPermit(index.get.flatMap(current => ZIO.attemptBlocking(writeAtomic(root.resolve("pipeline-index.json"), current.toJson)).orDie))

  private def markUnknown(root: Path, detail: String): ZIO[Any, Nothing, Unit] =
    ZIO.attemptBlocking {
      if Files.isDirectory(root) then
        val p = root.resolve("pipeline-index.json")
        if Files.exists(p) then
          val current = readUtf8(p).fromJson[PipelineIndex].toOption
          current.foreach { i =>
            val stages = i.stages.map(s => if s.status == StageStatus.Running then s.copy(status = StageStatus.Unknown, verdict = Some(detail)) else s)
            writeAtomic(p, i.copy(status = "UNKNOWN", updatedAt = Instant.now().toString, stages = stages, detail = Some(detail)).toJson)
          }
    }.ignore

  private def markFailed(root: Path, detail: String): ZIO[Any, Nothing, Unit] =
    ZIO.attemptBlocking {
      if Files.isDirectory(root) then
        val p = root.resolve("pipeline-index.json")
        if Files.exists(p) then
          readUtf8(p).fromJson[PipelineIndex].toOption.foreach { i =>
            val stages = i.stages.map(s => if s.status == StageStatus.Running then s.copy(status = StageStatus.Failed, verdict = Some(detail)) else s)
            writeAtomic(p, i.copy(status = "FAILED", updatedAt = Instant.now().toString, stages = stages, detail = Some(detail)).toJson)
          }
    }.ignore

  private def pipelineExitCode(root: Path): Int =
    val index = root.resolve("pipeline-index.json")
    if !Files.isRegularFile(index) then 1
    else
      readUtf8(index).fromJson[PipelineIndex].toOption.map(_.status) match
        case Some("BLOCKED") => 1
        case Some("UNKNOWN") => 24
        case _ => 1

  private def prepareReviewer(review: ReviewInput, stage: String, role: String, source: String): AgentInput =
    AgentInput(stage, reviewPrompt(source, role), review.workspace, review.root.resolve(s"stages/$stage"),
      review.root.resolve(s"stages/$stage/native"), review.runtimeClasspath, review.baseUrl, review.model,
      review.context, review.env, review.index, review.indexLock, review.root)

end BillingCi
