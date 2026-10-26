package raider.cli.portable

import raider.core.RaiderError
import raider.dsl.portable.{
  PortableBundleVerifier,
  PortableChatExport,
  PortableExportOptions,
  PortableExportResult,
  PortableExportTarget,
  PortablePinnedCheck
}
import raider.dsl.workflow.{
  StsAgentStage,
  StsOrigin,
  StsParam,
  StsSelection,
  StsTurn,
  StsTurnKind,
  StsTurnStatus
}
import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The `workflow run --doc` route against the MOCK provider (RAI-199): typed
  * refusal matrix (XE-CLI-02), the trusted-verdict honesty rule (the model
  * CANNOT manufacture a passing verdict — the marker check decides), the
  * artifact/effective-limits splice and the exactly-once Terminal event.
  * Real-process, SIGTERM and deadline discipline live in [[PortableParitySpec]]
  * against the scripted stand.
  */
object PortableRunSpec extends ZIOSpecDefault:

  /** The pinned `workspace-marker-present` identity: checkId + argv hash,
    * computed once over the frozen registry definition.
    */
  private val MarkerSnapshot =
    "sha256:0c877972cf59788094e1d7653a1f01f6cd1b6c9108d117d5e069bc6b6bdaba0b"

  /** Resolves a repository file by walking up from the process cwd (sbt runs
    * tests with the module directory as cwd).
    */
  private def repoFile(rel: String): Path =
    val cwd = java.nio.file.Paths.get("").toAbsolutePath
    Iterator
      .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) => dir }
      .find(dir =>
        Files.isDirectory(dir.resolve("contracts/acceptance/portable"))
      )
      .getOrElse(
        throw new IllegalStateException("repository root not found from " + cwd)
      )
      .resolve(rel)

  /** The acceptance parity fixture: prepare (agent) -> gate (trusted marker
    * check) -> run (sequence); the root output is the gate's result, so the
    * verdict is decided by the TRUSTED check.
    */
  private def verdictDoc(): Path =
    repoFile("contracts/acceptance/portable/parity-verdict.json")

  /** RAI-212 adversarial shape: the ONLY step is an agent and BOTH the bindings
    * and the verdict.from (with the literal trustedVerifier authority) aim at
    * the agent's output — the declaration cannot give the model verdict
    * authority.
    */
  private val agentVerdictDoc: String =
    """{
      |  "format": "raider.portable-workflow", "version": "1.0",
      |  "limits": { "deadlineMs": 60000, "attempts": 2, "parallelism": 1, "children": 0, "tokens": 4000 },
      |  "schemas": { "inputs": { "request": { "type": "string" } }, "outputs": { "verdict": { "type": "string" } } },
      |  "steps": [{ "id": "prepare", "kind": "agent", "profile": "coder", "toolset": "workspace", "input": "#/inputs/request", "outputSchema": "verdict", "structuredOutput": true }],
      |  "outputs": { "bindings": { "result": "#/steps/prepare/output" }, "verdict": { "from": "#/steps/prepare/output", "authority": "trustedVerifier" } }
      |}""".stripMargin

  /** RAI-212 mixed shape: a join of the agent and the REAL marker check; the
    * root output is the joined ARRAY, so the run must succeed without a verdict
    * claim even though the check itself passed.
    */
  private val joinVerdictDoc: String =
    """{
      |  "format": "raider.portable-workflow", "version": "1.0",
      |  "limits": { "deadlineMs": 60000, "attempts": 2, "parallelism": 1, "children": 0, "tokens": 4000 },
      |  "schemas": { "inputs": { "request": { "type": "string" } }, "outputs": { "verdict": { "type": "string" } } },
      |  "steps": [{ "id": "prepare", "kind": "agent", "profile": "coder", "toolset": "workspace", "input": "#/inputs/request", "outputSchema": "verdict", "structuredOutput": true }, { "id": "gate", "kind": "trustedCheck", "checkId": "workspace-marker-present", "registrySnapshot": "sha256:0c877972cf59788094e1d7653a1f01f6cd1b6c9108d117d5e069bc6b6bdaba0b", "input": "#/steps/prepare/output", "outputSchema": "verdict" }, { "id": "combined", "kind": "join", "steps": ["prepare", "gate"], "failure": "failFast", "outputSchema": "verdict" }],
      |  "outputs": { "bindings": { "result": "#/steps/combined/output" }, "verdict": { "from": "#/steps/combined/output", "authority": "trustedVerifier" } }
      |}""".stripMargin

  /** The gate references a check the LAUNCHER does not pin (RAI-199: only the
    * launcher-pinned registry is trusted; an unknown checkId/snapshot pair is a
    * typed refusal, never a live lookup).
    */
  private def untrustedDoc(): Path =
    val f = Files.createTempDirectory("pwrun-doc").resolve("untrusted.json")
    val body = Files
      .readString(verdictDoc(), StandardCharsets.UTF_8)
      .replace("workspace-marker-present", "unit-tests")
      .replace(
        MarkerSnapshot,
        "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
      )
    Files.writeString(f, body, StandardCharsets.UTF_8)
    f

  /** An unsupported node kind for the pinned registry: the checkpoint step is
    * VALID schema-wise but the launcher pins no checkpoints.
    */
  private val checkpointDoc: String =
    """{
      |  "format": "raider.portable-workflow", "version": "1.0",
      |  "limits": { "deadlineMs": 60000, "attempts": 2, "parallelism": 1, "children": 0, "tokens": 1000 },
      |  "schemas": { "inputs": { "request": { "type": "string" } }, "outputs": { "verdict": { "type": "string" } } },
      |  "steps": [{ "id": "approval", "kind": "checkpoint", "checkpointRef": "review-before-write", "placement": "launchPolicy", "outputSchema": "verdict" }, { "id": "run", "kind": "sequence", "steps": ["approval"], "outputSchema": "verdict" }],
      |  "outputs": { "bindings": { "result": "#/steps/run/output" }, "verdict": { "from": "#/steps/run/output", "authority": "trustedVerifier" } }
      |}""".stripMargin

  private def docFile(body: String): Path =
    val dir = Files.createTempDirectory("pwrun-doc")
    val f = dir.resolve("doc.json")
    Files.writeString(f, body, StandardCharsets.UTF_8)
    f

  private def inputFile(body: String): Path =
    val dir = Files.createTempDirectory("pwrun-in")
    val f = dir.resolve("input.json")
    Files.writeString(f, body, StandardCharsets.UTF_8)
    f

  private def freshWs(): Path = Files.createTempDirectory("pwrun-ws")

  private def args(
      doc: Path,
      input: Path,
      ws: Path,
      out: Path = Files.createTempDirectory("pwrun-out")
  ): PortableRun.Args =
    PortableRun.Args(
      doc = doc,
      input = input,
      out = out,
      workspace = ws,
      provider = "mock",
      model = "mock-model",
      baseUrl = None,
      attempts = 8,
      deadlineSeconds = None
    )

  private def runJsonOf(out: Path): String =
    val dirs =
      Files.list(out).iterator().asScala.toVector.filter(Files.isDirectory(_))
    val candidates = dirs.flatMap(d => Option(d.resolve("run.json")))
    candidates.find(Files.isRegularFile(_)) match
      case Some(f) => Files.readString(f, StandardCharsets.UTF_8)
      case None    => ""

  private def eventsLinesOf(out: Path): Vector[String] =
    val dirs =
      Files.list(out).iterator().asScala.toVector.filter(Files.isDirectory(_))
    dirs
      .flatMap(d => Option(d.resolve("events.jsonl")))
      .find(Files.isRegularFile(_))
      .map(f => Files.readAllLines(f, StandardCharsets.UTF_8).asScala.toVector)
      .getOrElse(Vector.empty)

  /** The mock needs NO credentials: the env map is empty by design (the run
    * route resolves credentials only for live providers).
    */
  private val env: Map[String, String] = Map.empty

  // -- RAI-220 fixtures: REAL published bundles through the producer ---------

  private val BundleName = "rai220-bundle"
  private val BundleDocName = BundleName + ".workflow.json"
  private val BundleManifestName = BundleName + "-manifest.json"
  private val BundleInputName = BundleName + ".input.json"

  /** The chat-workflow selection the producer renders (mirrors the RAI-214
    * verifier fixtures).
    */
  private val bundleSelection: StsSelection = StsSelection(
    sessionId = "sess-rai220",
    intent = StsTurn(
      "t-1",
      StsTurnKind.Intent,
      StsTurnStatus.Completed,
      "make module X compile and keep checks green"
    ),
    refinements = List(
      StsTurn(
        "t-2",
        StsTurnKind.Refinement,
        StsTurnStatus.Completed,
        "do not touch the public API"
      )
    ),
    taskParams = List(
      StsParam("targetPath", "modules/x"),
      StsParam("summary", "one-paragraph run summary")
    ),
    checks = Nil,
    agentStage = StsAgentStage(2),
    origin = StsOrigin(
      "openai-compatible",
      "mock-model",
      Some("http://127.0.0.1:1/v1")
    )
  )

  /** The export target names EXACTLY what THIS launcher pins (profile, toolset
    * and the real marker check with its real snapshot), so the exported
    * document lowers natively on the run path.
    */
  private val bundleTarget = PortableExportTarget(
    profile = PortableTrustedRegistry.ProfileCoder,
    toolset = PortableTrustedRegistry.ToolsetWorkspace,
    checks = List(
      PortablePinnedCheck(
        PortableTrustedRegistry.CheckWorkspaceMarker,
        PortableTrustedRegistry.workspaceMarkerSnapshot
      )
    )
  )

  /** Builds a REAL published bundle through the producer (staging + atomic
    * publish): `parent` must exist, the target directory itself must not.
    */
  private def exportBundle(parent: Path): PortableExportResult =
    PortableChatExport
      .exportTo(
        bundleSelection,
        PortableExportOptions(BundleName),
        bundleTarget,
        parent.resolve("bundle"),
        1750000000000L
      ) match
      case Right(published) => published
      case Left(err) => throw err // typed fixture failure; never swallowed

  /** Extracts the `docDigest` hex from a run.json body. */
  private def docDigestHexOf(runJson: String): Option[String] =
    "\"docDigest\":\"sha256-([0-9a-f]{64})\"".r
      .findFirstMatchIn(runJson)
      .map(_.group(1))

  /** Appends one byte to a file (the tamper mutation). */
  private def appendByte(path: Path): Unit =
    Files.write(path, Files.readAllBytes(path) :+ 32.toByte)

  /** Waits (bounded) until the run's events.jsonl contains a line carrying the
    * marker text — the happens-AFTER-the-gate signal for mid-run mutations (the
    * events writer flushes per line, so the poll is deterministic).
    */
  private def awaitEventLine(
      out: Path,
      marker: String
  ): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking {
      val deadline = java.lang.System.currentTimeMillis() + 30000L
      var found = false
      while !found && java.lang.System.currentTimeMillis() < deadline do
        found = eventsLinesOf(out).exists(_.contains(marker))
        if !found then Thread.sleep(10L)
      if !found then
        throw new IllegalStateException(
          "the run never flushed the expected event line: " + marker
        )
    }

  def spec =
    suite("raider workflow run --doc (mock)")(
      test(
        "green verdict: the trusted check passes on a prepared workspace -> exit 0, verdict claimed, spliced artifacts, exactly one Terminal last"
      ) {
        val ws = freshWs()
        Files.writeString(ws.resolve("marker.txt"), "made-by-test")
        val doc = verdictDoc()
        val in = inputFile("""{"request": "prepare the workspace"}""")
        val out = Files.createTempDirectory("pwrun-green")
        for o <- PortableRun.run(args(doc, in, ws, out), env)
        yield
          val runJson = runJsonOf(out)
          val events = eventsLinesOf(out)
          val terminals = events.count(_.contains("\"Terminal\""))
          assertTrue(
            o.exitCode == 0,
            o.error.isEmpty,
            runJson.contains("\"taskVerdict\":\"trusted-checks-passed\""),
            runJson.contains(
              "\"verdictEvidence\":{\"origin\":\"trusted-check\",\"target\":\"#/steps/run/output\",\"steps\":[\"run\",\"gate\"],\"assertedStatus\":\"passed\"}"
            ),
            runJson.contains("\"declaredLimits\""),
            runJson.contains("\"effectiveLimits\""),
            runJson.contains("\"docDigest\":\"sha256-"),
            runJson.contains("\"inputDigest\""),
            runJson.contains("\"status\":\"Succeeded\""),
            terminals == 1,
            events.nonEmpty && events.last.contains("\"Terminal\"")
          )
      },
      test(
        "red verdict: the trusted check fails -> exit 10 RA-OUT, NO verdict claim, the check's own status recorded verbatim"
      ) {
        val ws = freshWs() // no marker.txt on purpose
        val doc = verdictDoc()
        val in = inputFile("""{"request": "prepare the workspace"}""")
        val out = Files.createTempDirectory("pwrun-red")
        for o <- PortableRun.run(args(doc, in, ws, out), env)
        yield
          val runJson = runJsonOf(out)
          assertTrue(
            o.exitCode == raider.core.result.ExitCodes.TaskRejected,
            o.error.exists(_.code == "RA-OUT"),
            o.error.exists(_.detail.contains("trusted verdict did not pass")),
            !runJson.contains("\"taskVerdict\""),
            runJson.contains(
              "\"verdictEvidence\":{\"origin\":\"trusted-check\",\"target\":\"#/steps/run/output\",\"steps\":[\"run\",\"gate\"],\"assertedStatus\":\"failed\"}"
            ),
            runJson.contains("\"status\":\"Failed\""),
            eventsLinesOf(out).nonEmpty // truthful evidence still lands
          )
      },
      test(
        "RAI-212 agent-step-only verdict: the agent's output is the root -> exit 0, NO claim, contradictory-capable agent evidence recorded"
      ) {
        val ws = freshWs()
        Files.writeString(ws.resolve("marker.txt"), "made-by-test")
        val doc = docFile(agentVerdictDoc)
        val in = inputFile("""{"request": "prepare the workspace"}""")
        val out = Files.createTempDirectory("pwrun-agent")
        for o <- PortableRun.run(args(doc, in, ws, out), env)
        yield
          val runJson = runJsonOf(out)
          assertTrue(
            o.exitCode == 0,
            o.error.isEmpty,
            // the declared trustedVerifier authority over agent output mints NOTHING
            !runJson.contains("\"taskVerdict\""),
            runJson.contains(
              "\"verdictEvidence\":{\"origin\":\"agent-step\",\"target\":\"#/steps/prepare/output\",\"steps\":[\"prepare\"]}"
            ),
            runJson.contains("\"status\":\"Succeeded\""),
            runJson.contains("[mock] answer to:"),
            eventsLinesOf(out).exists(
              _.contains("verdict evidence origin: agent-step")
            )
          )
      },
      test(
        "RAI-212 mixed verdict: join of the agent and a PASSING check -> exit 0, NO claim, mixed evidence recorded"
      ) {
        val ws = freshWs()
        Files.writeString(ws.resolve("marker.txt"), "made-by-test")
        val doc = docFile(joinVerdictDoc)
        val in = inputFile("""{"request": "prepare the workspace"}""")
        val out = Files.createTempDirectory("pwrun-join")
        for o <- PortableRun.run(args(doc, in, ws, out), env)
        yield
          val runJson = runJsonOf(out)
          assertTrue(
            o.exitCode == 0,
            o.error.isEmpty,
            // even the PASSING check inside the join cannot mint the claim:
            // the root output is the joined array, not one check's result
            !runJson.contains("\"taskVerdict\""),
            runJson.contains(
              "\"verdictEvidence\":{\"origin\":\"mixed\",\"target\":\"#/steps/combined/output\",\"steps\":[\"combined\",\"prepare\",\"gate\"]}"
            ),
            runJson.contains("\"status\":\"Succeeded\""),
            eventsLinesOf(out).exists(
              _.contains("verdict evidence origin: mixed")
            )
          )
      },
      test(
        "compiled-launch flags are refused typed RA-CFG (no mixing of forms)"
      ) {
        val doc = verdictDoc()
        val in = inputFile("{}")
        val ws = freshWs()
        val out = Files.createTempDirectory("pwrun-classes")
        val refused = PortableRun.parse(
          List(
            "--doc",
            doc.toString,
            "--input",
            in.toString,
            "--out",
            out.toString,
            "--workspace",
            ws.toString,
            "--classes",
            "/tmp/never"
          )
        )
        assert(refused.isLeft)(isTrue) &&
        assert(refused.left.toOption.map(_.code))(isSome(equalTo("RA-CFG")))
      },
      test(
        "an argv value-position element exactly '--doc' classifies as a VALUE, not a switch (RAI-211 pinned corner)"
      ) {
        val in = inputFile("{}")
        val out = Files.createTempDirectory("pwrun-docval")
        // (1) The routing flag's own value is exactly '--doc': positional
        // pairing keeps it a VALUE (a path named '--doc'); the parser must not
        // re-read it as a switch ("requires a value" / unknown argument).
        val routingValue = PortableRun.parse(
          List("--doc", "--doc", "--input", in.toString, "--out", out.toString)
        )
        // (2) The same corner behind a non-routing flag: '--doc' after
        // '--input' is that flag's value, never a second switch.
        val inputValue = PortableRun.parse(
          List("--doc", "doc.json", "--input", "--doc", "--out", out.toString)
        )
        // (3) The contrast stays pinned: a SWITCH-position duplicate '--doc'
        // is still the typed twice-error.
        val duplicateSwitch = PortableRun.parse(
          List(
            "--doc",
            "a.json",
            "--doc",
            "b.json",
            "--input",
            in.toString,
            "--out",
            out.toString
          )
        )
        assertTrue(
          routingValue.isRight,
          routingValue.toOption.map(_.doc.toString).contains("--doc"),
          inputValue.isRight,
          inputValue.toOption.map(_.input.toString).contains("--doc"),
          duplicateSwitch.isLeft,
          duplicateSwitch.left.toOption.map(_.code).contains("RA-CFG")
        )
      },
      test(
        "a missing workspace directory refuses typed RA-CFG before any dispatch"
      ) {
        val doc = verdictDoc()
        val in = inputFile("{}")
        val out = Files.createTempDirectory("pwrun-badws")
        for o <- PortableRun.run(
            args(doc, in, out.resolve("no-such-ws"), out),
            env
          )
        yield assertTrue(
          o.exitCode == raider.core.result.ExitCodes.ConfigInvalid,
          o.error.exists(_.code == "RA-CFG")
        )
      },
      test("an unwritable --out target refuses typed RA-JOURNAL (exit 27)") {
        val ws = freshWs()
        Files.writeString(ws.resolve("marker.txt"), "x")
        val doc = verdictDoc()
        val in = inputFile("{}")
        val outFile = Files.createTempFile("pwrun-outfile", ".txt")
        for o <- PortableRun.run(args(doc, in, ws, outFile), env)
        yield assertTrue(
          o.exitCode == raider.core.result.ExitCodes.ArtifactWriteFailure,
          o.error.exists(_.code == "RA-JOURNAL")
        )
      },
      test("malformed input JSON refuses typed RA-INP (exit 21)") {
        val ws = freshWs()
        val doc = verdictDoc()
        val in = inputFile("{not json")
        val out = Files.createTempDirectory("pwrun-badin")
        for o <- PortableRun.run(args(doc, in, ws, out), env)
        yield assertTrue(
          o.exitCode == raider.core.result.ExitCodes.ConfigInvalid,
          o.error.exists(_.code == "RA-INP")
        )
      },
      test(
        "an unpinned trusted check refuses typed RA-INP before any dispatch"
      ) {
        val ws = freshWs()
        Files.writeString(ws.resolve("marker.txt"), "x") // even green workspace
        val doc = untrustedDoc()
        val in = inputFile("""{"request": "prepare the workspace"}""")
        val out = Files.createTempDirectory("pwrun-untrusted")
        for o <- PortableRun.run(args(doc, in, ws, out), env)
        yield assertTrue(
          o.exitCode == raider.core.result.ExitCodes.ConfigInvalid,
          o.error.exists(_.code == "RA-INP")
        )
      },
      test("an unsupported node kind (checkpoint) refuses typed RA-INP") {
        val ws = freshWs()
        val doc = docFile(checkpointDoc)
        val in = inputFile("""{"request": "prepare the workspace"}""")
        val out = Files.createTempDirectory("pwrun-checkpoint")
        for o <- PortableRun.run(args(doc, in, ws, out), env)
        yield assertTrue(
          o.exitCode == raider.core.result.ExitCodes.ConfigInvalid,
          o.error.exists(_.code == "RA-INP")
        )
      },
      test(
        "RAI-220: a valid published bundle verifies and runs green end-to-end, unchanged from the plain document"
      ) {
        val ws = freshWs()
        Files.writeString(ws.resolve("marker.txt"), "made-by-test")
        val parent = Files.createTempDirectory("pwrun-bundle")
        val published = exportBundle(parent)
        val docPath = published.targetDir.resolve(BundleDocName)
        val detected = PortableBundleVerifier.detectForDocument(docPath)
        val verified = detected
          .flatMap(d => PortableBundleVerifier.verifyBundle(d).toOption)
        val inPath = published.targetDir.resolve(BundleInputName)
        // Control: the SAME document bytes WITHOUT the sibling manifest keep
        // the pre-existing un-manifested behavior (no bundle event either).
        val plainDir = Files.createTempDirectory("pwrun-plaindoc")
        val plainDoc = plainDir.resolve(BundleDocName)
        Files.write(plainDoc, Files.readAllBytes(docPath))
        val plainIn = plainDir.resolve("input.json")
        Files.write(plainIn, Files.readAllBytes(inPath))
        val out = Files.createTempDirectory("pwrun-bundlerun")
        val plainOut = Files.createTempDirectory("pwrun-plainrun")
        for
          bundled <- PortableRun.run(args(docPath, inPath, ws, out), env)
          plain <- PortableRun.run(args(plainDoc, plainIn, ws, plainOut), env)
        yield
          val bundledJson = runJsonOf(out)
          val plainJson = runJsonOf(plainOut)
          val expectedHex =
            verified.map(v => PortableDoc.sha256Hex(v.documentBytes))
          val verifiedBytesOnDisk = verified.exists(v =>
            java.util.Arrays.equals(
              v.documentBytes,
              Files.readAllBytes(docPath)
            )
          )
          assertTrue(
            detected.isDefined,
            verified.isDefined,
            verifiedBytesOnDisk,
            bundled.exitCode == 0,
            bundled.error.isEmpty,
            plain.exitCode == 0,
            plain.error.isEmpty,
            bundledJson.contains("\"taskVerdict\":\"trusted-checks-passed\""),
            plainJson.contains("\"taskVerdict\":\"trusted-checks-passed\""),
            // what was lowered is EXACTLY the verified payload
            docDigestHexOf(bundledJson) == expectedHex,
            docDigestHexOf(plainJson) == expectedHex,
            eventsLinesOf(out)
              .exists(_.contains("bundle verified: " + BundleManifestName)),
            !eventsLinesOf(plainOut).exists(_.contains("bundle verified"))
          )
      },
      test(
        "RAI-220: a tampered bundle refuses typed CWF-R1 before any dispatch"
      ) {
        val ws = freshWs()
        Files.writeString(ws.resolve("marker.txt"), "made-by-test")
        val parent = Files.createTempDirectory("pwrun-tamper")
        val published = exportBundle(parent)
        val docPath = published.targetDir.resolve(BundleDocName)
        appendByte(docPath)
        val out = Files.createTempDirectory("pwrun-tamperrun")
        for o <- PortableRun.run(
            args(
              docPath,
              published.targetDir.resolve(BundleInputName),
              ws,
              out
            ),
            env
          )
        yield
          val events = eventsLinesOf(out)
          val runJson = runJsonOf(out)
          assertTrue(
            o.exitCode == raider.core.result.ExitCodes.ConfigInvalid,
            o.error.exists(_.code == "RA-INP"),
            o.error.exists(_.detail.startsWith("CWF-R1: ")),
            o.error.exists(_.detail.contains("tampered or corrupted")),
            o.error.exists(_.detail.contains(BundleDocName)),
            runJson.contains("\"status\":\"Failed\""),
            !runJson.contains("\"taskVerdict\""),
            runJson.contains("CWF-R1"),
            // the refusal precedes launch-layer assembly and dispatch
            !events.exists(_.contains("effective launch limits")),
            events.nonEmpty && events.last.contains("\"Terminal\"")
          )
      },
      test(
        "RAI-220: a missing manifested file refuses typed before any dispatch"
      ) {
        val ws = freshWs()
        Files.writeString(ws.resolve("marker.txt"), "made-by-test")
        val docGone = exportBundle(Files.createTempDirectory("pwrun-miss-doc"))
        Files.deleteIfExists(docGone.targetDir.resolve(BundleDocName))
        val inputGone =
          exportBundle(Files.createTempDirectory("pwrun-miss-input"))
        Files.deleteIfExists(inputGone.targetDir.resolve(BundleInputName))
        val outDoc = Files.createTempDirectory("pwrun-missdocrun")
        val outInput = Files.createTempDirectory("pwrun-missinrun")
        for
          oDoc <- PortableRun.run(
            args(
              docGone.targetDir.resolve(BundleDocName),
              docGone.targetDir.resolve(BundleInputName),
              ws,
              outDoc
            ),
            env
          )
          oInput <- PortableRun.run(
            args(
              inputGone.targetDir.resolve(BundleDocName),
              inputGone.targetDir.resolve(BundleInputName),
              ws,
              outInput
            ),
            env
          )
        yield
          val docErr = oDoc.error.map(_.detail)
          val inputErr = oInput.error.map(_.detail)
          assertTrue(
            oDoc.exitCode == raider.core.result.ExitCodes.ConfigInvalid,
            oDoc.error.exists(_.code == "RA-INP"),
            docErr.exists(d =>
              d.startsWith("CWF-R1: ") && d
                .contains(
                  "the manifest names a file that is absent: " + BundleDocName
                )
            ),
            oInput.exitCode == raider.core.result.ExitCodes.ConfigInvalid,
            oInput.error.exists(_.code == "RA-INP"),
            inputErr.exists(d =>
              d.startsWith("CWF-R1: ") && d
                .contains(
                  "the manifest names a file that is absent: " + BundleInputName
                )
            ),
            !eventsLinesOf(outDoc).exists(
              _.contains("effective launch limits")
            ),
            !eventsLinesOf(outInput).exists(
              _.contains("effective launch limits")
            )
          )
      },
      test(
        "RAI-220: an extra unmanifested file refuses typed before any dispatch"
      ) {
        val ws = freshWs()
        Files.writeString(ws.resolve("marker.txt"), "made-by-test")
        val parent = Files.createTempDirectory("pwrun-extra")
        val published = exportBundle(parent)
        Files.writeString(
          published.targetDir.resolve("notes.txt"),
          "unmanifested"
        )
        val out = Files.createTempDirectory("pwrun-extrarun")
        for o <- PortableRun.run(
            args(
              published.targetDir.resolve(BundleDocName),
              published.targetDir.resolve(BundleInputName),
              ws,
              out
            ),
            env
          )
        yield assertTrue(
          o.exitCode == raider.core.result.ExitCodes.ConfigInvalid,
          o.error.exists(_.code == "RA-INP"),
          o.error.exists(err =>
            err.detail.startsWith("CWF-R1: ") && err.detail
              .contains("unmanifested entries: notes.txt")
          ),
          !runJsonOf(out).contains("\"taskVerdict\""),
          !eventsLinesOf(out).exists(_.contains("effective launch limits"))
        )
      },
      test(
        "RAI-220: lowering consumes the VERIFIED bytes - an on-disk mutation after the gate cannot change the run (TOCTOU pin)"
      ) {
        val ws = freshWs()
        Files.writeString(ws.resolve("marker.txt"), "made-by-test")
        val parent = Files.createTempDirectory("pwrun-toctou")
        val published = exportBundle(parent)
        val docPath = published.targetDir.resolve(BundleDocName)
        val detected = PortableBundleVerifier.detectForDocument(docPath)
        val verified = detected
          .flatMap(d => PortableBundleVerifier.verifyBundle(d).toOption)
        val inPath = published.targetDir.resolve(BundleInputName)
        val out = Files.createTempDirectory("pwrun-toctourun")
        for
          fiber <- PortableRun.run(args(docPath, inPath, ws, out), env).fork
          // The gate consumed the document bytes BEFORE this diagnostic line
          // is flushed; mutating afterwards cannot reach a consumer of the
          // returned bytes. The mutated text is not even valid JSON, so any
          // post-gate re-read would refuse typed.
          _ <- awaitEventLine(out, "portable document validated")
          _ <- ZIO.attempt(
            Files.writeString(docPath, "{tampered-after-verify")
          )
          o <- fiber.join
        yield
          val runJson = runJsonOf(out)
          val expectedHex =
            verified.map(v => PortableDoc.sha256Hex(v.documentBytes))
          assertTrue(
            o.exitCode == 0,
            o.error.isEmpty,
            runJson.contains("\"status\":\"Succeeded\""),
            runJson.contains("\"taskVerdict\":\"trusted-checks-passed\""),
            docDigestHexOf(runJson) == expectedHex,
            !runJson.contains("tampered-after-verify")
          )
      },
      test(
        "RAI-220: a *.workflow.json document WITHOUT a sibling manifest keeps the un-manifested behavior"
      ) {
        val ws = freshWs()
        Files.writeString(ws.resolve("marker.txt"), "made-by-test")
        val dir = Files.createTempDirectory("pwrun-nomanifest")
        val doc = dir.resolve("plain.workflow.json")
        Files.writeString(
          doc,
          Files.readString(verdictDoc(), StandardCharsets.UTF_8)
        )
        val detected = PortableBundleVerifier.detectForDocument(doc)
        val in = inputFile("""{"request": "prepare the workspace"}""")
        val out = Files.createTempDirectory("pwrun-nomanifestrun")
        for o <- PortableRun.run(args(doc, in, ws, out), env)
        yield assertTrue(
          detected.isEmpty,
          o.exitCode == 0,
          o.error.isEmpty,
          runJsonOf(out).contains("\"taskVerdict\":\"trusted-checks-passed\""),
          !eventsLinesOf(out).exists(_.contains("bundle verified"))
        )
      }
    )

end PortableRunSpec
