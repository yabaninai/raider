package raider.cli.portable

import raider.core.RaiderError
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
      }
    )

end PortableRunSpec
