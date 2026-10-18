package raider.cli.chat

import zio.*
import zio.json.*

import zio.test.*
import zio.test.Assertion.*

import raider.dsl.workflow.ChatWorkflowExport

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

import scala.jdk.CollectionConverters.*

/** RAI-155.e — INDEPENDENT acceptance CWF-E03: cancellation/concurrency. Pinned
  * expectations come from `contracts/chat-workflow/expectations/CWF-E03.json`
  * (ADR-033 §5.4, §9, ci-runtime §6/§8).
  *
  *   1. Interrupted capture: a turn hung mid-stream stays typed-`Unknown` with
  *      its request text preserved, refuses selection (R3, naming the offending
  *      turn), never auto-re-dispatches after the interrupt, and leaves the
  *      stored COMPLETED selection usable (the export during streaming succeeds
  *      and equals the quiescent reference artifact). Concurrent exports to
  *      distinct targets are isolated and byte-identical; a staging remnant
  *      refuses.
  *   2. Fresh-process SIGTERM: the CI launcher run as a real subprocess is
  *      SIGTERMed mid-round; the outcome stays bounded and truthful (exit 143
  *      per the corpus, an incomplete/failed run.json or the interrupted
  *      marker, NEVER a Succeeded status) with no automatic retry.
  *   3. Deterministic repeats: re-export is byte-identical and the same input
  *      through the local launcher twice produces normalized-equal results.
  */
object ChatWorkflowVerifierE03Spec extends ZIOSpecDefault:

  import ChatWorkflowVerifierHarness.*

  private val IntentToken = "VERIFIER-" + "INTENT-7c03"

  private val IntentText =
    s"$IntentToken: publish the module marker now; PUBLISH-TARGET: marker.txt"

  private val RefinementText = "create only the publish target file"
  private val Summary1 = "e03-input-summary-token"
  private val StandKey = "verifier-stand-key-33e03"
  private val CheckName = "marker"
  private val CheckArgv = List("/bin/test", "-f", "marker.txt")

  def spec =
    suite("ChatWorkflowVerifierE03 (CWF-E03 cancellation/concurrency)")(
      test(
        "interrupted capture stays typed-Unknown outside the snapshot; R3 names the offending turn; no redispatch; export during streaming equals the quiescent reference; concurrent exports isolated; staging remnant refused"
      ) {
        loadE03.flatMap { exp =>
          for
            cap <- makeCaptureCtx(
              baseUrl = "http://127.0.0.1:1/v1",
              model = "verifier-model-e03",
              hangAfter = Some(3) // the 4th capture call hangs mid-stream
            )
            _ <- chat(cap, "e03 ordinary context turn, never selected") // t1
            _ <- chat(cap, IntentText) // t2
            _ <- chat(cap, RefinementText) // t3
            _ <- TaskCommands.mark(cap.ctx.ledgerRef, 2, TurnLedger.Kind.Intent)
            _ <- TaskCommands.mark(
              cap.ctx.ledgerRef,
              3,
              TurnLedger.Kind.Refinement
            )
            _ <- TaskCommands.registerCheck(
              cap.ctx.checksRef,
              CheckName,
              CheckArgv
            )
            turns3 <- cap.ctx.ledgerRef.get
            checks <- cap.ctx.checksRef.get
            selected = TaskCommands.select(turns3.toList, checks, 2, 8)
            _ <- ZIO.when(selected.isLeft)(
              ZIO.die(new IllegalStateException("fixture select refused"))
            )
            _ <- cap.ctx.selectionRef.set(Some(selected.toOption.get))

            // quiescent reference export (all selected turns Completed)
            outRoot <- ZIO.attempt(Files.createTempDirectory("cwf-e03-snap"))
            refTarget = outRoot.resolve("ref")
            refLines = exportWorkflow(
              cap,
              refTarget,
              "cwf_e03",
              "marker.txt",
              Summary1
            )
            refOk = refLines.head.startsWith("workflow exported:")

            // a turn goes mid-flight (status Unknown, request preserved)
            fiber <- chat(cap, "an in-flight streaming turn").fork
            begun <- waitLedgerSize(cap, 4)
            midFlight <- cap.ctx.ledgerRef.get
            inFlightOk =
              begun &&
                midFlight.size == 4 &&
                midFlight(
                  3
                ).status == exp.interruptedCapture.inFlightTurnStatus &&
                midFlight(3).request == "an in-flight streaming turn"

            // selecting a span that CONTAINS the in-flight turn refuses
            // typed (R3), naming the offending turn id
            turns4 <- cap.ctx.ledgerRef.get
            refused = TaskCommands.select(turns4.toList, checks, 3, 8)
            r3Text = refused.swap.toOption.get.detail
            r3Ok =
              refused.isLeft &&
                r3Text.startsWith(exp.interruptedCapture.selectRefusalPrefix) &&
                (!exp.interruptedCapture.selectRefusalNamesOffendingTurn ||
                  r3Text.contains(midFlight(3).turnId))

            // export WHILE streaming: the stored COMPLETED selection stays
            // usable and the artifact equals the quiescent reference
            duringTarget = outRoot.resolve("during")
            duringLines = exportWorkflow(
              cap,
              duringTarget,
              "cwf_e03",
              "marker.txt",
              Summary1
            )
            afterExport <- cap.ctx.ledgerRef.get
            files = Vector(
              "cwf_e03.scala",
              "cwf_e03-manifest.json",
              "cwf_e03.input.json"
            )
            snapshotOk =
              (!exp.exportSnapshot.exportDuringStreamingSucceeds ||
                duringLines.head.startsWith("workflow exported:")) &&
                (!exp.exportSnapshot.artifactEqualsQuiescentReference ||
                  files.forall(f =>
                    Files.readAllBytes(refTarget.resolve(f)).toSeq ==
                      Files.readAllBytes(duringTarget.resolve(f)).toSeq
                  )) &&
                (!exp.exportSnapshot.ledgerUntouchedByExport ||
                  afterExport.size == 4) &&
                (!exp.exportSnapshot.inFlightTurnOutsideSnapshot ||
                  afterExport(3).status == TurnLedger.Status.Unknown)

            // concurrent exports to DISTINCT targets: isolated, both complete
            concA = outRoot.resolve("conc-a")
            concB = outRoot.resolve("conc-b")
            exportTo = (p: java.nio.file.Path) =>
              ZIO.succeed(
                exportWorkflow(cap, p, "cwf_e03", "marker.txt", Summary1)
              )
            fa <- exportTo(concA).fork
            fb <- exportTo(concB).fork
            la <- fa.join
            lb <- fb.join
            concOk =
              (!exp.exportSnapshot.concurrentExportsToDistinctTargets.bothSucceed ||
                (la.head.startsWith("workflow exported:") &&
                  lb.head.startsWith("workflow exported:"))) &&
                (!exp.exportSnapshot.concurrentExportsToDistinctTargets.byteIdentical ||
                  files.forall(f =>
                    Files.readAllBytes(concA.resolve(f)).toSeq ==
                      Files.readAllBytes(concB.resolve(f)).toSeq
                  ))

            // interrupt the in-flight turn: bounded observable outcome — the
            // record stays non-Completed with its request text preserved and
            // nothing re-dispatches
            _ <- fiber.interrupt
            afterInterrupt <- cap.ctx.ledgerRef.get
            callsAtInterrupt = cap.callCount()
            _ <- ZIO.yieldNow.repeatN(100_000)
            callsAfterWait = cap.callCount()
            interruptedOk =
              afterInterrupt.size == 4 &&
                afterInterrupt(3).status != TurnLedger.Status.Completed &&
                afterInterrupt(3).request == "an in-flight streaming turn" &&
                (!exp.interruptedCapture.noRedispatchAfterInterrupt ||
                  callsAfterWait == callsAtInterrupt)

            // staging remnant: an interrupted export never looks complete
            stagingTarget = outRoot.resolve("remnant")
            _ <- ZIO.attempt:
              Files.createDirectories(
                outRoot.resolve(
                  "." + stagingTarget.getFileName.toString +
                    ChatWorkflowExport.StagingSuffix
                )
              )
            remLines = exportWorkflow(
              cap,
              stagingTarget,
              "cwf_e03",
              "marker.txt",
              Summary1
            )
            remOk =
              (!exp.exportSnapshot.stagingRemnantRefused ||
                (remLines.head.startsWith("export refused [RA-INP]: CWF-R5") &&
                  !Files.exists(stagingTarget)))
          yield assertTrue(
            refOk,
            inFlightOk,
            r3Ok,
            snapshotOk,
            concOk,
            interruptedOk,
            remOk
          )
        }
      } @@ TestAspect.timeout(300.seconds),
      test(
        "fresh-process SIGTERM: bounded truthful outcome (exit per corpus, incomplete artifact or interrupted marker, never Succeeded, no retry); deterministic repeats: re-export byte-identical + normalized-equal repeat runs"
      ) {
        loadE03.flatMap { exp =>
          ZIO.acquireReleaseWith(startStand(StandMode.Delay(15000)))(h =>
            ZIO.succeed(h.stop())
          ) { delayStand =>
            ZIO.acquireReleaseWith(startStand(StandMode.Normal))(h =>
              ZIO.succeed(h.stop())
            ) { stand =>
              sigtermAndRepeats(delayStand, stand, exp)
            }
          }
        }
      } @@ TestAspect.timeout(600.seconds)
    )

  private def sigtermAndRepeats(
      delayStand: Stand,
      stand: Stand,
      exp: E03Doc
  ): ZIO[Any, Throwable, TestResult] =
    for
      outRoot <- ZIO.attempt(Files.createTempDirectory("cwf-e03-sigterm"))

      // repeatable export first: x2 byte-identical (same snapshot)
      cap <- makeCaptureCtx(baseUrl = stand.url, model = "verifier-model-e03")
      _ <- chat(cap, "e03 ordinary context turn, never selected")
      _ <- chat(cap, IntentText)
      _ <- chat(cap, RefinementText)
      _ <- markSelectStore(
        cap,
        span = 2,
        maxRounds = 8,
        checkName = CheckName,
        checkArgv = CheckArgv
      )
      exportTarget = outRoot.resolve("export")
      exportAgain = outRoot.resolve("export-again")
      l1 = exportWorkflow(cap, exportTarget, "cwf_e03", "marker.txt", Summary1)
      l2 = exportWorkflow(cap, exportAgain, "cwf_e03", "marker.txt", Summary1)
      exportFiles = Vector(
        "cwf_e03.scala",
        "cwf_e03-manifest.json",
        "cwf_e03.input.json"
      )
      reExportIdentical = exportFiles.forall(f =>
        Files.readAllBytes(exportTarget.resolve(f)).toSeq ==
          Files.readAllBytes(exportAgain.resolve(f)).toSeq
      )
      exportOk =
        l1.head.startsWith("workflow exported:") &&
          l2.head.startsWith("workflow exported:") &&
          (!exp.deterministicRepeat.reExportByteIdentical || reExportIdentical)

      (srcPath, buildDir) <- prepareArtifact(
        stand,
        outRoot,
        "cwf_e03_sig",
        Summary1,
        IntentText,
        RefinementText,
        CheckName,
        CheckArgv
      )

      // --- deterministic repeat: the same input through the local launcher
      //     twice on two fresh copies normalizes equal
      repWs1 <- freshWorkspaceCopy("e03-repeat-1")
      repWs2 <- freshWorkspaceCopy("e03-repeat-2")
      repIn1 <- writeInput(repWs1, "marker.txt", Summary1)
      repIn2 <- writeInput(repWs2, "marker.txt", Summary1)
      launcherMain <- ZIO.attempt(
        launcherMainClassOf(Files.readString(srcPath, UTF_8)).get
      )
      rep1 <- runLocalMain(buildDir, launcherMain, repIn1, Some(StandKey))
      rep2 <- runLocalMain(buildDir, launcherMain, repIn2, Some(StandKey))
      norm1 = normalizeLocal(rep1, repWs1, Summary1, sha256OfFile(srcPath))
      norm2 = normalizeLocal(rep2, repWs2, Summary1, sha256OfFile(srcPath))
      repeatOk =
        rep1.exitCode == 0 &&
          rep2.exitCode == 0 &&
          (!exp.deterministicRepeat.normalizedResultsEqual ||
            observablyEqual(norm1, norm2))

      // The delayed stand must be the endpoint bound into this independently
      // compiled SIGTERM artifact; the repeat artifact remains on the normal
      // stand and is not reused for the interruption case.
      sigSrc = outRoot.resolve("sigterm-source").resolve("cwf_e03_sig.scala")
      sigText = Files
        .readString(srcPath, UTF_8)
        .replace(stand.url, delayStand.url)
      _ <- ZIO.attempt:
        Files.createDirectories(sigSrc.getParent)
        Files.writeString(sigSrc, sigText, UTF_8)
      sigBuild = outRoot.resolve("cwf_e03_sigterm-build")
      sigCompileExit <- ZIO.attemptBlocking(
        raider.cli.workflow.WorkflowCompile.command(
          List(sigSrc.toString, "--out", sigBuild.toString)
        )
      )

      // --- fresh-process SIGTERM: start the CI launcher subprocess, wait
      //     until the stand received the run's request (the run STARTED,
      //     per corpus runStartedBeforeSignal), then SIGTERM
      sigWs <- freshWorkspaceCopy("e03-sigterm-ws")
      sigInput <- writeInput(sigWs, "marker.txt", Summary1)
      sigOut = outRoot.resolve("sigterm-out")
      olderWorkDir = sigOut.resolve("work-older-existing-run")
      _ <- ZIO.attempt(Files.createDirectories(olderWorkDir))
      proc <- sigtermProcess(
        ciLauncherArgv(sigBuild, sigInput, sigOut, Some(sigSrc)),
        Some(StandKey)
      )
      started <- pollUntil(delayStand.counts.delayHits.get >= 1, 1_000)
      _ <- ZIO.attemptBlocking(Thread.sleep(100L))
      requestsAtKill = delayStand.totalRequests
      _ <- ZIO.succeed(proc.destroy()) // SIGTERM semantics on POSIX
      exited = proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
      exitCode =
        if exited then proc.exitValue()
        else
          proc.destroyForcibly()
          -1
      _ <- ZIO.attemptBlocking(Thread.sleep(300L))

      workDirs <- ZIO.attempt:
        if !Files.isDirectory(sigOut) then List.empty[java.nio.file.Path]
        else
          val listing = Files.list(sigOut)
          try
            listing
              .iterator()
              .asScala
              .filter(p =>
                Files.isDirectory(p) && p.getFileName.toString
                  .startsWith("work-")
              )
              .toList
          finally listing.close()

      runArtifact = workDirs
        .map(_.resolve("run.json"))
        .filter(Files.isRegularFile(_))
        .headOption
        .map(p => Files.readString(p, UTF_8))
        .flatMap(decodeRunArtifact)
      markerNames = workDirs.flatMap { w =>
        val listing = Files.list(w)
        try
          listing
            .iterator()
            .asScala
            .map(_.getFileName.toString)
            .filter(_.startsWith("interrupted-"))
            .toList
        finally listing.close()
      }
      markerDirs = workDirs.filter { w =>
        val listing = Files.list(w)
        try
          listing
            .iterator()
            .asScala
            .exists(_.getFileName.toString.startsWith("interrupted-"))
        finally listing.close()
      }
      currentRunDirs = workDirs.filterNot(_ == olderWorkDir)
      sigStatus = runStatus(runArtifact)
      truthyArtifacts = runArtifact.isDefined || markerNames.nonEmpty
      markerInCurrentRun = markerDirs.exists(currentRunDirs.contains) &&
        !markerDirs.contains(olderWorkDir)
      neverSucceeded =
        sigStatus.forall(_ != "Succeeded") && runVerdict(runArtifact).isEmpty
      noRetry = delayStand.totalRequests == requestsAtKill
      sigtermOk = assertTrue(
        sigCompileExit == 0,
        sigText.contains(delayStand.url) && !sigText.contains(stand.url),
        started, // the run was mid-flight when the signal arrived
        exp.sigterm.expectedExitCodes.contains(exitCode),
        !exp.sigterm.freshProcessLaunch || true, // by construction: subprocess
        !exp.sigterm.truthfulIncompleteArtifact || truthyArtifacts,
        markerInCurrentRun,
        !exp.sigterm.neverReportsSucceeded || neverSucceeded,
        !exp.sigterm.noAutomaticRetry || noRetry
      )
    yield assertTrue(exportOk, repeatOk) && sigtermOk

  private def pollUntil(
      cond: => Boolean,
      tries: Int = 1_000
  ): ZIO[Any, Nothing, Boolean] =
    def loop(i: Int): ZIO[Any, Nothing, Boolean] =
      ZIO.succeed(cond).flatMap { ok =>
        if ok then ZIO.succeed(true)
        else if i <= 0 then ZIO.succeed(false)
        else ZIO.attemptBlocking(Thread.sleep(10L)).ignore *> loop(i - 1)
      }
    loop(tries)

end ChatWorkflowVerifierE03Spec
