package raider.cli.workflow

import zio.test.*
import zio.test.Assertion.*
import zio.{Chunk, Unsafe, ZIO}

import raider.core.RaiderError
import raider.core.sdlc.SdlcEvidence.CandidateIdentity
import raider.runtime.sdlc.approval.ApprovalArtifacts
import raider.dsl.workflow.{
  ChatWorkflowRenderer,
  NamedCheck,
  RenderOptions,
  StsAgentStage,
  StsOrigin,
  StsParam,
  StsSelection,
  StsTurn,
  StsTurnKind,
  StsTurnStatus
}

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.time.Instant
import scala.jdk.CollectionConverters.*

/** RAI-155.d `raider workflow run` typed-failure corpus (CWF-D02):
  *
  *   - Every refusal below is TYPED and surfaces as a stable nonzero §8 exit
  *     code with a truthful run.json/summary.md artifact written under `--out`
  *     (never a silent partial state).
  *   - The launch capability provides no dispatch before the typed decode
  *     boundary: the invalid-input case fails with the generated `CWF-input`
  *     prefix and (having no stand at all) provably contacts nothing.
  *   - Hostile EXTRA input fields (a "checks" array, an "argv" string in the
  *     input JSON) are DATA: the versioned decoder ignores them, the run
  *     proceeds on the compiled trusted checks only, and no injected file ever
  *     appears in the workspace (the no-argv-injection boundary — the .c review
  *     requirement, proven here at the launcher input boundary).
  *   - Missing credentials fail the RAI-064 preflight (exit 21) BEFORE any
  *     service construction.
  *
  * ONE real pinned-dotc build is shared by the whole suite (lazy, compiled
  * through the product `WorkflowCompile` command); every test uses its own
  * fresh `--out` so runs share no state (CWF-D03 structurally).
  */
object WorkflowRunSpec extends ZIOSpecDefault:

  // ----- fixture -------------------------------------------------------------

  private val probe = StsSelection(
    sessionId = "sess-155d-run",
    intent = StsTurn(
      "r1",
      StsTurnKind.Intent,
      StsTurnStatus.Completed,
      "Write the launch marker for the 155.d run probe"
    ),
    refinements = List(
      StsTurn(
        "r2",
        StsTurnKind.Refinement,
        StsTurnStatus.Completed,
        "Keep the marker content exact"
      )
    ),
    taskParams = List(
      StsParam("targetPath", "marker.txt"),
      StsParam("summary", "155.d run probe task")
    ),
    checks = List(
      NamedCheck("marker", List("/bin/test", "-f", "marker.txt"))
    ),
    agentStage = StsAgentStage(maxRounds = 3),
    origin = StsOrigin(
      providerKind = "openai-compatible",
      model = "stand-model-155d",
      // Closed loopback port: the dispatch-failure probe gets an immediate
      // typed ProviderUnavailable with no egress and no spend.
      baseUrl = Some("http://127.0.0.1:1/v1")
    )
  )

  /** The shared build (compiled once through the PRODUCT compile command). */
  private lazy val build: (Path, Path) =
    val rendered = ChatWorkflowRenderer
      .render(probe, RenderOptions("wf155d_run"))
      .fold(e => throw new IllegalStateException(e.detail), identity)
    val work = Files.createTempDirectory("r155d-build")
    val src = work.resolve("wf155d_run.scala")
    // RAI-158: the renderer no longer emits the case-colliding object/@main
    // pair, so the UNEDITED render compiles and publishes on this
    // case-insensitive filesystem (the old main-rename workaround is gone);
    // the module class the run path derives is untouched by the naming fix.
    Files.write(src, rendered.source.getBytes(StandardCharsets.UTF_8))
    val out = work.resolve("build")
    Unsafe.unsafe { implicit u =>
      zio.Runtime.default.unsafe
        .run(WorkflowCompile.compile(WorkflowCompile.Args(src, out)))
        .getOrThrow()
    }
    (out, src)

  private def freshWorkspace(): Path =
    Files.createTempDirectory("r155d-ws")

  private def inputJson(ws: Path): String =
    "{\n  \"schemaVersion\": 1,\n  \"workspace\": \"" +
      ws.toString.replace("\\", "\\\\") +
      "\",\n  \"task\": {\n    \"targetPath\": \"marker.txt\",\n" +
      "    \"summary\": \"155.d run probe task\"\n  }\n}"

  private def freshRunDir(): Path = Files.createTempDirectory("r155d-out")

  private def runArgs(
      classes: Path,
      input: Path,
      out: Path,
      source: Option[Path] = None
  ): WorkflowRun.Args =
    WorkflowRun.Args(classes, input, out, source)

  private def exec(args: WorkflowRun.Args, env: Map[String, String]) =
    WorkflowRun.run(args, env)

  /** Reads the single work-<id> directory the run created under `--out`. */
  private def workDirOf(out: Path): Path =
    val listing = Files.list(out)
    try
      listing
        .iterator()
        .asScala
        .filter(p =>
          Files.isDirectory(p) && p.getFileName.toString.startsWith("work-")
        )
        .toVector
        .head
    finally listing.close()

  private def runJsonOf(out: Path): String =
    Files.readString(
      workDirOf(out).resolve("run.json"),
      StandardCharsets.UTF_8
    )

  /** Navigates the run.json AST. */
  private def jsonField(ast: zio.json.ast.Json, path: String*): Option[String] =
    path
      .foldLeft(Option(ast)) { (acc, seg) =>
        acc.flatMap {
          case obj: zio.json.ast.Json.Obj =>
            obj.fields.collectFirst { case (k, v) if k == seg => v }
          case _ => None
        }
      }
      .collect { case zio.json.ast.Json.Str(s) => s }

  // ----- spec ----------------------------------------------------------------

  def spec =
    suite("raider workflow run (RAI-155.d typed-failure corpus)")(
      test("parse: usage errors are typed; ok path resolves") {
        val dir = Files.createTempDirectory("r155d-parse")
        val ok = WorkflowRun.parse(
          List(
            "--classes",
            dir.toString,
            "--input",
            dir.resolve("i.json").toString,
            "--out",
            dir.resolve("o").toString
          )
        )
        val missing = WorkflowRun.parse(List("--classes", dir.toString))
        val dupe = WorkflowRun.parse(
          List(
            "--classes",
            dir.toString,
            "--classes",
            dir.toString,
            "--input",
            "i",
            "--out",
            "o"
          )
        )
        assertTrue(
          ok.isRight,
          missing.fold(_.code == "RA-CFG", _ => false),
          dupe.fold(_.code == "RA-CFG", _ => false)
        )
      },
      test(
        "CWF-D02: missing build directory refuses typed (21) with artifacts"
      ) {
        val out = freshRunDir()
        val ws = freshWorkspace()
        val input = Files.createTempDirectory("r155d-in").resolve("i.json")
        Files.writeString(input, inputJson(ws))
        exec(
          runArgs(
            Files.createTempDirectory("r155d-nobuild"),
            input,
            out
          ),
          Map("RAIDER_API_KEY" -> "k")
        ).map { o =>
          assertTrue(
            o.exitCode == 21,
            o.error.fold(false)(_.code == "RA-INP"),
            Files.isRegularFile(workDirOf(out).resolve("run.json")),
            Files.isRegularFile(workDirOf(out).resolve("summary.md")),
            Files.isRegularFile(workDirOf(out).resolve("events.jsonl"))
          )
        }
      },
      test(
        "CWF-D02: tampered classes refuse typed (21); stale --source refuses typed (21)"
      ) {
        val (buildDir, src) = build
        // Tampered copy.
        val tampered = copyTree(
          buildDir,
          Files.createTempDirectory("r155d-tam").resolve("b")
        )
        val s = Files.walk(tampered.resolve("classes"))
        val aClass =
          try
            s.iterator()
              .asScala
              .find(p =>
                Files.isRegularFile(p) && p.toString.endsWith(".class")
              )
              .get
          finally s.close()
        Files.write(aClass, Files.readAllBytes(aClass) ++ Array[Byte](0x7f))
        // Stale source: an extra comment AFTER the compile.
        val staleSrc =
          Files.createTempDirectory("r155d-stale").resolve("edited.scala")
        Files.writeString(
          staleSrc,
          Files.readString(src) + "\n// manual edit\n"
        )
        val ws = freshWorkspace()
        def newInput(): Path =
          val p = Files.createTempDirectory("r155d-in").resolve("i.json")
          Files.writeString(p, inputJson(ws))
          p
        for
          tamperedRun <- exec(
            runArgs(tampered, newInput(), freshRunDir()),
            Map("RAIDER_API_KEY" -> "k")
          )
          staleRun <- exec(
            runArgs(buildDir, newInput(), freshRunDir(), Some(staleSrc)),
            Map("RAIDER_API_KEY" -> "k")
          )
        yield assertTrue(
          tamperedRun.exitCode == 21,
          tamperedRun.error.fold(false)(
            _.detail.contains("stale or corrupted build")
          ),
          staleRun.exitCode == 21,
          staleRun.error.fold(false)(_.detail.contains("stale build"))
        )
      },
      test(
        "CWF-D02: missing credentials fail the RAI-064 preflight (21) BEFORE any construction"
      ) {
        val (buildDir, _) = build
        val out = freshRunDir()
        val ws = freshWorkspace()
        val input = Files.createTempDirectory("r155d-in").resolve("i.json")
        Files.writeString(input, inputJson(ws))
        exec(
          runArgs(buildDir, input, out),
          Map.empty // NO RAIDER_API_KEY, NO helper
        ).map { o =>
          val runJson = runJsonOf(out)
          assertTrue(
            o.exitCode == 21,
            o.error.fold(false)(_.code == "RA-CFG"),
            o.error.fold(false)(_.detail.contains("RAIDER_API_KEY")),
            runJson.contains("\"error\""),
            runJson.contains("RA-CFG")
          )
        }
      },
      test(
        "SDLC-DISPATCH-01/02: launcher policy pauses with request artifact, then fresh decision invocation reaches workflow dispatch"
      ) {
        val (buildDir, _) = build
        val ws = freshWorkspace()
        val input =
          Files.createTempDirectory("rai179-input").resolve("input.json")
        Files.writeString(input, inputJson(ws))
        val out = freshRunDir()
        val (checkpointId, scope) = "before-run" -> "workflow-run"
        val requestPath = out.resolve(s"approval-$checkpointId.json")
        val args = WorkflowRun.Args(
          buildDir,
          input,
          out,
          None,
          Some(checkpointId -> scope),
          None,
          None
        )
        exec(args, Map.empty).flatMap { paused =>
          val request = ApprovalArtifacts
            .decodeRequestArtifact(Files.readString(requestPath))
            .toOption
            .get
          val identity = WorkflowBuildIdentity.load(buildDir).toOption.get
          val inputDigest =
            WorkflowBuildIdentity.sha256Hex(Files.readString(input))
          val treeDigest = workspaceDigest(ws)
          val candidate = CandidateIdentity(
            ws.toAbsolutePath.normalize.toString,
            ws.toAbsolutePath.normalize.toString,
            None,
            Some(identity.classDigest),
            Some(identity.sourceDigest),
            treeDigest,
            inputDigest,
            identity.sourceDigest,
            WorkflowBuildIdentity.sha256Hex("workflow-run:" + scope),
            WorkflowBuildIdentity.sha256Hex(identity.toolchain.toString)
          )
          val decisionPath = out.resolve("decision.json")
          Files.writeString(
            decisionPath,
            ApprovalArtifacts.decisionJson(
              ApprovalArtifacts.Decision(
                ApprovalArtifacts.SchemaVersion,
                checkpointId,
                request.candidateDigest,
                request.expiresAt,
                approved = true
              )
            )
          )
          val resumedInvocation = args.copy(decisionFile = Some(decisionPath))
          exec(resumedInvocation, Map("RAIDER_API_KEY" -> "stand-key"))
            .flatMap { proceeded =>
              Files.writeString(
                ws.resolve("changed-after-request.txt"),
                "state changed"
              )
              exec(args.copy(decisionFile = Some(decisionPath)), Map.empty)
                .flatMap { stale =>
                  val expired = request.copy(expiresAt = "2000-01-01T00:00:00Z")
                  ApprovalArtifacts.writeRequest(requestPath, expired)
                  val expiredDecision = out.resolve("expired-decision.json")
                  Files.writeString(
                    expiredDecision,
                    ApprovalArtifacts.decisionJson(
                      ApprovalArtifacts.Decision(
                        ApprovalArtifacts.SchemaVersion,
                        checkpointId,
                        expired.candidateDigest,
                        expired.expiresAt,
                        approved = true
                      )
                    )
                  )
                  exec(
                    args.copy(decisionFile = Some(expiredDecision)),
                    Map.empty
                  ).map { refused =>
                    assertTrue(
                      paused.exitCode == raider.core.result.ExitCodes.NeedsApproval,
                      Files.isRegularFile(requestPath),
                      request.candidateDigest == CandidateIdentity.digest(
                        candidate
                      ),
                      proceeded.exitCode == 20,
                      proceeded.error.exists(_.code == "RA-UNAVAIL"),
                      stale.exitCode == 21,
                      stale.error.exists(
                        _.detail.contains("CandidateMismatch")
                      ),
                      refused.exitCode == 21,
                      refused.error.exists(_.detail.contains("Expired"))
                    )
                  }
                }
            }
        }
      },
      test(
        "CWF-D02: invalid input JSON fails typed at the decode boundary (21, CWF-input prefix, zero dispatch)"
      ) {
        val (buildDir, _) = build
        val out = freshRunDir()
        val input = Files.createTempDirectory("r155d-in").resolve("i.json")
        Files.writeString(input, "{ not json")
        exec(
          runArgs(buildDir, input, out),
          Map("RAIDER_API_KEY" -> "k")
        ).map { o =>
          val runJson = runJsonOf(out)
          assertTrue(
            o.exitCode == 21,
            o.error.fold(false)(_.code == "RA-INP"),
            o.error.fold(false)(_.detail.contains("CWF-input")),
            // Truthful failed artifact: status Failed, schema check green.
            runJson.contains("\"status\":\"Failed\""),
            runJson.contains("\"run-result-schema\"")
          )
        }
      },
      test(
        "CWF-D02 + no-argv-injection: hostile EXTRA input fields are DATA (ignored), the run fails only on the provider (20), and no injected file is ever created"
      ) {
        val (buildDir, _) = build
        val out = freshRunDir()
        val ws = freshWorkspace()
        // The input carries injection-shaped EXTRA fields; the versioned
        // decoder ignores them and the trusted checks stay the compiled ones.
        val hostileInput =
          inputJson(ws).replace(
            "}",
            ",\"checks\": [\"touch\", \"pwned.txt\"], \"argv\": \"rm -rf /\"}"
          )
        val input = Files.createTempDirectory("r155d-in").resolve("i.json")
        Files.writeString(input, hostileInput)
        exec(
          runArgs(buildDir, input, out),
          Map("RAIDER_API_KEY" -> "stand-key")
        ).map { o =>
          val runJson = runJsonOf(out)
          val wsFiles =
            val s = Files.list(ws)
            try s.iterator().asScala.toVector.map(_.getFileName.toString)
            finally s.close()
          assertTrue(
            // The run got ALL the way to the dispatch: only the closed
            // loopback endpoint failed (typed, no retry wrapper on this
            // generated path).
            o.exitCode == 20,
            o.error.fold(false)(_.code == "RA-UNAVAIL"),
            // The injected "checks" never executed: no pwned.txt anywhere.
            !wsFiles.contains("pwned.txt"),
            wsFiles.isEmpty,
            // Build identity + input digest recorded in the failed artifact.
            runJson.contains("\"buildIdentity\""),
            runJson.contains("\"inputDigest\""),
            runJson.contains("\"status\":\"Failed\"")
          )
        }
      },
      test(
        "RAI-159 UO-01: a read-only parent --out fails TYPED (RA-JOURNAL, exit 27) with honest artifact absence — no work dir, no run.json, no events.jsonl"
      ) {
        // The round-1 preferred test (RAI-155.d limitations #8): the parent
        // directory exists and denies write, so --out cannot be created. The
        // typed output-dir probe must fail the run RA-JOURNAL/27 BEFORE the
        // events writer opens (whose open defect used to shadow it — the raw
        // AccessDeniedException crash, subprocess exit 1 with a raw stack).
        // The chmod and the run BOTH live in the effect (a bare try/finally
        // around the constructed ZIO would restore the permissions BEFORE
        // the effect ever runs — found by the first red run of this spec).
        val restore = (parent: Path) =>
          ZIO
            .attempt(
              Files.setPosixFilePermissions(
                parent,
                java.util.Set.of(
                  PosixFilePermission.OWNER_READ,
                  PosixFilePermission.OWNER_WRITE,
                  PosixFilePermission.OWNER_EXECUTE
                )
              )
            )
            .orDie
        ZIO
          .attempt {
            val parent = Files.createTempDirectory("r159-readonly-parent")
            Files.setPosixFilePermissions(
              parent,
              java.util.Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_EXECUTE
              )
            )
            val (buildDir, _) = build
            val ws = freshWorkspace()
            val input = Files.createTempDirectory("r159-in").resolve("i.json")
            Files.writeString(input, inputJson(ws))
            (parent, buildDir, input)
          }
          .flatMap { case (parent, buildDir, input) =>
            val out = parent.resolve("out")
            exec(
              runArgs(buildDir, input, out),
              Map("RAIDER_API_KEY" -> "k")
            ).map { o =>
              assertTrue(
                o.exitCode == 27,
                o.error.fold(false)(_.code == "RA-JOURNAL"),
                o.error.fold(false)(
                  _.detail.contains("output directory is not writable")
                ),
                // Honest artifact absence: the probe failed before ANY artifact
                // step — `out` was never created, so there is no work- dir and
                // no run.json/summary.md/events.jsonl anywhere under it.
                !Files.exists(out),
                !Files.exists(out.resolve("run.json")),
                !Files.exists(out.resolve("events.jsonl"))
              )
            }.ensuring(restore(parent))
          }
      }
    ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
    // RAI-178: WorkflowRun.run's ensurings poll on the ambient clock;
    // live-clock discipline (docs/work/records/RAI-178.md). Assertions
    // unchanged.

  private def copyTree(from: Path, to: Path): Path =
    Files.walk(from).forEach { p =>
      val rel = from.relativize(p)
      val target = to.resolve(rel)
      if Files.isDirectory(p) then Files.createDirectories(target)
      else
        Files.createDirectories(target.getParent)
        Files.copy(p, target)
      ()
    }
    to

  private def workspaceDigest(workspace: Path): String =
    val files = Files.walk(workspace)
    val ordered =
      try
        files
          .iterator()
          .asScala
          .toVector
          .filter(Files.isRegularFile(_))
          .sortBy(p => workspace.relativize(p).toString)
      finally files.close()
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    ordered.foreach { file =>
      val bytes = Files.readAllBytes(file)
      digest.update(
        (workspace
          .relativize(file)
          .toString + "\n" + bytes.length.toString + "\n")
          .getBytes(StandardCharsets.UTF_8)
      )
      digest.update(bytes)
    }
    digest.digest().map(b => f"${b & 0xff}%02x").mkString

end WorkflowRunSpec
