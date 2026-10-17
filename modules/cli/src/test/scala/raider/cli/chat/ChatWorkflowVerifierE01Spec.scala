package raider.cli.chat

import zio.*
import zio.json.*

import zio.test.*
import zio.test.Assertion.*

import raider.cli.workflow.WorkflowCompile

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** RAI-155.e — INDEPENDENT acceptance CWF-E01 (the owner's demonstration): real
  * multi-turn chat capture -> explicit :task selection -> :export-workflow ->
  * MEANINGFUL manual edit of the exported source (prompt text + check
  * parameter) -> `raider compile` on the pinned toolchain -> the SAME edited
  * artifact run by BOTH launchers (compiled launcher main as a local fresh
  * process; `raider workflow run` as a fresh non-TTY CI process) on TWO fresh
  * input cases over FOUR fresh workspace copies with pairwise-compared
  * normalized results, plus a FIFTH fresh run of the UNEDITED workflow on input
  * 1 isolating the observable effect of the edit.
  *
  * Every pinned value comes from the frozen corpus
  * `contracts/chat-workflow/expectations/CWF-E01.json` (contract-derived, never
  * a generator snapshot). No live provider: a scripted loopback
  * OpenAI-compatible stand; the agent stage still performs REAL tool work (a
  * real process tool creates the marker file in the fresh workspace) and the
  * trusted check is an independent process on the NEW workspace (STS repair
  * pattern: the check fails before the agent works and passes after).
  */
object ChatWorkflowVerifierE01Spec extends ZIOSpecDefault:

  import ChatWorkflowVerifierHarness.*

  private val IntentToken = "VERIFIER-INTENT-9d21"
  private val OrdinaryToken = "VERIFIER-ORDINARY-1c44"

  private val IntentText =
    s"$IntentToken: publish the module marker now; PUBLISH-TARGET: marker.txt"

  private val Refinement1 =
    "create only the publish target file and nothing else"

  private val Refinement2 =
    "verify the publish target exists before reporting done"

  private val OrdinaryText =
    s"$OrdinaryToken: thanks, keep this note as capture context only"

  private val Summary1 = "first-input-summary-token"
  private val Summary2 = "second-input-summary-token"
  private val StandKey = "verifier-stand-key-77e01"
  private val CheckName = "marker"
  private val CheckArgv = List("/bin/test", "-f", "marker.txt")

  def spec =
    suite("ChatWorkflowVerifierE01 (CWF-E01)")(
      test(
        "chat -> select -> export x2 -> meaningful manual edit -> pinned compile -> 2x2 launcher matrix on fresh copies + unedited control, pairwise-equal normalized results"
      ) {
        loadE01.flatMap { exp =>
          ZIO.acquireReleaseWith(startStand(StandMode.Capture))(h =>
            ZIO.succeed(h.stop())
          ) { stand => runMatrix(stand, exp) }
        }
      }
    ) @@ TestAspect.timeout(600.seconds)

  private def runMatrix(
      stand: Stand,
      exp: E01Doc
  ): ZIO[Any, Throwable, TestResult] =
    for
      // ---- 1. multi-turn capture through the REAL chat loop ----
      cap <- makeCaptureCtx(
        baseUrl = stand.url,
        model = "verifier-model-e01",
        useOpenAiStand = true
      )
      _ <- chat(cap, OrdinaryText) // turn 1: Ordinary, never selected
      _ <- chat(cap, IntentText) // turn 2
      _ <- chat(cap, Refinement1) // turn 3
      _ <- chat(cap, Refinement2) // turn 4
      ledger <- cap.ctx.ledgerRef.get
      captureOk =
        ledger.size == exp.capture.capturedTurns &&
          ledger.forall(_.status == TurnLedger.Status.Completed) &&
          ledger.head.request.contains(OrdinaryToken) &&
          ledger(1).request.contains(IntentToken) &&
          stand.captureRequests == exp.capture.capturedTurns &&
          stand.totalRequests == exp.capture.standRequestsDuringCapture

      // ---- 2. explicit :task selection (the real commands) ----
      selected <- markSelectStore(
        cap,
        span = exp.capture.selectionSpan,
        maxRounds = 12,
        checkName = CheckName,
        checkArgv = CheckArgv
      )
      sel = selected.toOption.get
      selectionOk =
        selected.isRight &&
          sel.turnIds == ledger
            .takeRight(exp.capture.selectionSpan)
            .map(_.turnId)
            .toList &&
          !sel.turnIds.contains(ledger.head.turnId) &&
          sel.checks == List(raider.cli.chat.NamedCheck(CheckName, CheckArgv))

      // ---- 3. export x2 through the real :export-workflow command ----
      outRoot <- ZIO.attempt(Files.createTempDirectory("cwf-e01-out"))
      targetA = outRoot.resolve("cwf-e01-a")
      targetB = outRoot.resolve("cwf-e01-b")
      tokenAbsentBefore = tokenAbsentUnder(outRoot, IntentToken)
      standBeforeExport = stand.totalRequests
      linesA = exportWorkflow(cap, targetA, "cwf_e01", "marker.txt", Summary1)
      exportStandDelta = stand.totalRequests - standBeforeExport
      sourceName = "cwf_e01.scala"
      srcAPath = targetA.resolve(sourceName)
      exportedA = Files.isRegularFile(srcAPath)
      linesB = exportWorkflow(cap, targetB, "cwf_e01", "marker.txt", Summary1)
      files = Vector(
        sourceName,
        "cwf_e01-manifest.json",
        "cwf_e01.input.json"
      )
      reExportIdentical = files.forall(f =>
        Files.readAllBytes(targetA.resolve(f)).toSeq ==
          Files.readAllBytes(targetB.resolve(f)).toSeq
      )
      srcA = Files.readString(srcAPath, UTF_8)
      srcB = Files.readString(targetB.resolve(sourceName), UTF_8)
      derivedFromCapture =
        srcA.contains(IntentToken) &&
          srcA.contains(PublishTokenPrefix + "marker.txt") &&
          srcA.contains(Refinement1) &&
          srcA.contains(Refinement2) &&
          srcA.contains(stand.url) && // origin came from the session
          srcA.contains("val maxRounds: Int = 12")
      manifestAst =
        Files
          .readString(targetA.resolve("cwf_e01-manifest.json"), UTF_8)
          .fromJson[ManifestArtifact]
          .toOption
      manifestOk =
        manifestAst.isDefined &&
          manifestAst.get.kind == exp.exportStage.manifestKind &&
          manifestAst.get.schemaVersion == exp.exportStage.manifestSchemaVersion
      manifestSourceDigest =
        manifestAst
          .flatMap(_.buildIdentity)
          .map(_.sourceDigest.stripPrefix("sha256-"))
      manifestDigestMatchesExport =
        manifestSourceDigest.contains(sha256OfFile(srcAPath))
      exportOk =
        linesA.head.startsWith(exp.exportStage.successLinePrefix) &&
          linesB.head.startsWith(exp.exportStage.successLinePrefix) &&
          exportedA &&
          files.forall(f => Files.isRegularFile(targetA.resolve(f))) &&
          files.size == exp.exportStage.artifactCount &&
          derivedFromCapture &&
          tokenAbsentBefore &&
          exportStandDelta == exp.exportStage.standRequestsDuringExportDelta

      // ---- 4. the MEANINGFUL manual edit on copy A (copy B stays the
      //        byte-identical UNEDITED control source). The bare filename
      //        is what a real user edits: it flips BOTH the prompt-template
      //        publish token AND the trusted-check argv target. ----
      (editedText, replacements) = applyManualEdit(
        srcA,
        "marker.txt",
        "marker2.txt"
      )
      editedPath = outRoot.resolve("edited").resolve(sourceName)
      _ <- ZIO.attempt:
        Files.createDirectories(editedPath.getParent)
        Files.writeString(editedPath, editedText, UTF_8)
      checkArgvAfterRendered =
        exp.manualEdit.checkArgvAfter
          .map(a => "\"" + a + "\"")
          .mkString(", ")
      editOk =
        replacements == exp.manualEdit.replacementsCount &&
          editedText.contains(exp.manualEdit.promptTokenAfter) &&
          editedText.contains("List(" + checkArgvAfterRendered + ")") &&
          !editedText.contains(exp.manualEdit.promptTokenBefore) &&
          !editedText.contains("marker.txt") &&
          srcB.contains(exp.manualEdit.promptTokenBefore) // control untouched
      launcherMain = launcherMainClassOf(editedText)
      editedDigest = sha256OfFile(editedPath)

      // ---- 5. raider compile (product command, pinned toolchain) ----
      standBeforeCompile = stand.totalRequests
      buildEdited = outRoot.resolve("build-edited")
      buildControl = outRoot.resolve("build-control")
      compileEditedExit <- ZIO.attemptBlocking(
        WorkflowCompile.command(
          List(editedPath.toString, "--out", buildEdited.toString)
        )
      )
      compileControlExit <- ZIO.attemptBlocking(
        WorkflowCompile.command(
          List(
            targetB.resolve(sourceName).toString,
            "--out",
            buildControl.toString
          )
        )
      )
      compileStandDelta = stand.totalRequests - standBeforeCompile
      identityAst = Files
        .readString(
          buildEdited.resolve(exp.compile.buildIdentityFile),
          UTF_8
        )
        .fromJson[BuildIdentityArtifact]
        .toOption
      identityOk =
        identityAst.isDefined &&
          identityAst.get.toolchain.scalaVersion ==
          exp.compile.pinnedToolchainVersion &&
          identityAst.get.kind == "chat-workflow-build"
      classesOk = Files.isDirectory(buildEdited.resolve("classes")) &&
        Files.isDirectory(buildControl.resolve("classes"))
      launcherMainOk = launcherMain.isDefined &&
        editedText.contains("@main def " + launcherMain.get + "(")

      // ---- 6. the matrix: 2 inputs x 2 launchers on FOUR fresh copies,
      //         plus the FIFTH unedited control run on input 1 ----
      ws1 <- freshWorkspaceCopy("e01-i1-local")
      ws2 <- freshWorkspaceCopy("e01-i1-ci")
      ws3 <- freshWorkspaceCopy("e01-i2-local")
      ws4 <- freshWorkspaceCopy("e01-i2-ci")
      ws5 <- freshWorkspaceCopy("e01-control")
      in1a <- writeInput(ws1, "marker.txt", Summary1)
      in1b <- writeInput(ws2, "marker.txt", Summary1)
      in2a <- writeInput(ws3, "marker.txt", Summary2)
      in2b <- writeInput(ws4, "marker.txt", Summary2)
      in1c <- writeInput(ws5, "marker.txt", Summary1)

      // STS repair pattern: the independent checker FAILS on the fresh copy
      // before any agent work (both candidate targets absent)
      pre1 <- independentCheck(ws1, CheckArgv)
      pre2 <- independentCheck(ws1, exp.manualEdit.checkArgvAfter)

      standBeforeR1 = stand.totalRequests
      local1 <- runLocalMain(
        buildEdited,
        launcherMain.get,
        in1a,
        Some(StandKey)
      )
      r1StandDelta = stand.totalRequests - standBeforeR1
      post1Edited <- independentCheck(ws1, exp.manualEdit.checkArgvAfter)
      post1Unedited <- independentCheck(ws1, CheckArgv)

      standBeforeR2 = stand.totalRequests
      ci1 <- runCiLauncher(
        buildEdited,
        in1b,
        outRoot.resolve("ci-out-1"),
        Some(editedPath),
        Some(StandKey)
      )
      r2StandDelta = stand.totalRequests - standBeforeR2

      standBeforeR3 = stand.totalRequests
      local2 <- runLocalMain(
        buildEdited,
        launcherMain.get,
        in2a,
        Some(StandKey)
      )
      r3StandDelta = stand.totalRequests - standBeforeR3

      standBeforeR4 = stand.totalRequests
      ci2 <- runCiLauncher(
        buildEdited,
        in2b,
        outRoot.resolve("ci-out-2"),
        Some(editedPath),
        Some(StandKey)
      )
      r4StandDelta = stand.totalRequests - standBeforeR4

      standBeforeR5 = stand.totalRequests
      ci5 <- runCiLauncher(
        buildControl,
        in1c,
        outRoot.resolve("ci-out-control"),
        Some(targetB.resolve(sourceName)),
        Some(StandKey)
      )
      r5StandDelta = stand.totalRequests - standBeforeR5

      // ---- 7. deterministic normalization + pairwise comparison ----
      norm1 = normalizeLocal(local1, ws1, Summary1, editedDigest)
      norm2 = normalizeCi(ci1, ws2, Summary1)
      norm3 = normalizeLocal(local2, ws3, Summary2, editedDigest)
      norm4 = normalizeCi(ci2, ws4, Summary2)
      norm5 = normalizeCi(ci5, ws5, Summary1)

      ci1Status = runStatus(ci1.runArtifact)
      ci1Verdict = runVerdict(ci1.runArtifact)
      ci1Digest = runSourceDigest(ci1.runArtifact)
      ciArtifactsPresent = ci1.workDir.isDefined &&
        exp.matrix.expectedCiArtifacts.forall(a =>
          Files.isRegularFile(ci1.workDir.get.resolve(a))
        )
    yield assertTrue(
      captureOk,
      selectionOk,
      exportOk,
      manifestOk,
      manifestDigestMatchesExport,
      reExportIdentical,
      editOk,
      compileEditedExit == exp.compile.expectedExitCode,
      compileControlExit == exp.compile.expectedExitCode,
      compileStandDelta == exp.compile.standRequestsDuringCompileDelta,
      identityOk,
      classesOk,
      launcherMainOk,
      pre1 != 0,
      pre2 != 0, // checks fail BEFORE the agent works
      post1Edited == 0,
      post1Unedited != 0, // and pass after, on the EDITED target only
      r1StandDelta >= exp.stsRepairPattern.minNewStandRequestsPerRun,
      r2StandDelta >= exp.stsRepairPattern.minNewStandRequestsPerRun,
      r3StandDelta >= exp.stsRepairPattern.minNewStandRequestsPerRun,
      r4StandDelta >= exp.stsRepairPattern.minNewStandRequestsPerRun,
      r5StandDelta >= exp.stsRepairPattern.minNewStandRequestsPerRun,
      local1.exitCode == exp.matrix.expectedExitCode,
      local2.exitCode == exp.matrix.expectedExitCode,
      ci1.process.exitCode == exp.matrix.expectedExitCode,
      ci2.process.exitCode == exp.matrix.expectedExitCode,
      ci5.process.exitCode == exp.matrix.expectedExitCode,
      ci1Status.contains(exp.matrix.expectedCiStatus),
      ci1Verdict.contains(exp.matrix.expectedCiTaskVerdict),
      ciArtifactsPresent,
      ci1Digest.contains(editedDigest),
      norm1 != norm3, // different inputs observably differ
      norm1.report.contains("published marker2.txt"),
      norm1.effects == List(exp.editIsolation.editedPublishTarget),
      norm5.effects == List(exp.editIsolation.uneditedPublishTarget),
      norm5.report.contains("published marker.txt"),
      norm1 != norm5, // the edit's observable effect is isolated
      norm5.exitCode == exp.matrix.expectedExitCode,
      !exp.matrix.pairwiseNormalizedEqualForSameInput ||
        (observablyEqual(norm1, norm2) && observablyEqual(norm3, norm4)),
      !exp.matrix.differentInputsObservablyDiffer || (norm1 != norm3 && norm2 != norm4)
    )

end ChatWorkflowVerifierE01Spec
