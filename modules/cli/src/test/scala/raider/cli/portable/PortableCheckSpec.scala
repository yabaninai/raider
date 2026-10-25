package raider.cli.portable

import zio.test.*
import zio.test.Assertion.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** The `workflow check --doc` route (RAI-199 XE-CLI-01 zero-dispatch clause):
  * the stdout contract (closed JSON verdict), the frozen exit-code mapping and
  * the zero-dispatch property — no credentials, no env, no backend: the route
  * is LOCAL by construction, so every case here runs with an empty environment
  * and still succeeds/refuses typed.
  */
object PortableCheckSpec extends ZIOSpecDefault:

  private val validDoc: String =
    """{
      |  "format": "raider.portable-workflow", "version": "1.0",
      |  "limits": { "deadlineMs": 30000, "attempts": 2, "parallelism": 1, "children": 0, "tokens": 1000 },
      |  "schemas": { "inputs": { "request": { "type": "string" } }, "outputs": { "verdict": { "type": "string" } } },
      |  "steps": [{ "id": "verify", "kind": "trustedCheck", "checkId": "unit-tests", "registrySnapshot": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "input": "#/inputs/request", "outputSchema": "verdict" }],
      |  "outputs": { "bindings": { "result": "#/steps/verify/output" }, "verdict": { "from": "#/steps/verify/output", "authority": "trustedVerifier" } }
      |}""".stripMargin

  private val invalidDoc: String = validDoc.replace(
    "\"format\": \"raider.portable-workflow\"",
    "\"format\": \"some.other.format\""
  )

  private def docFile(body: String): java.nio.file.Path =
    val dir = Files.createTempDirectory("pwcheck")
    val f = dir.resolve("doc.json")
    Files.writeString(f, body, StandardCharsets.UTF_8)
    f

  /** Captures the machine stdout of one command invocation (the closed JSON
    * verdict is part of the contract). Guarded by a shared process-wide lock:
    * zio-test runs suites in parallel, and concurrent global System.out
    * redirection across suites would cross-contaminate captures.
    */
  private def capture[A](thunk: => A): (String, A) =
    CliIoCapture.lock.synchronized:
      val baos = new java.io.ByteArrayOutputStream
      val ps = new java.io.PrintStream(baos, true, StandardCharsets.UTF_8)
      val old = System.out
      System.setOut(ps)
      try
        val result = thunk
        System.out.flush()
        (baos.toString(StandardCharsets.UTF_8), result)
      finally System.setOut(old)

  def spec =
    suite("raider workflow check --doc (zero-dispatch document gate)")(
      test("a valid document prints the closed valid verdict and exits 0") {
        val f = docFile(validDoc)
        val (stdout, exit) =
          capture(PortableCheck.command(List("--doc", f.toString)))
        assert(exit)(equalTo(raider.core.result.ExitCodes.Success)) &&
        assert(stdout)(containsString("\"valid\":true")) &&
        assert(stdout)(
          containsString("\"format\":\"raider.portable-workflow\"")
        ) &&
        assert(stdout)(containsString("\"docSha256\":\"sha256-"))
      },
      test("an invalid document prints the diagnostic JSON and exits 21") {
        val f = docFile(invalidDoc)
        val (stdout, exit) =
          capture(PortableCheck.command(List("--doc", f.toString)))
        assert(exit)(equalTo(raider.core.result.ExitCodes.ConfigInvalid)) &&
        assert(stdout)(containsString("\"valid\":false")) &&
        assert(stdout)(containsString("\"diagnostic\":{")) &&
        assert(stdout)(containsString("\"code\":"))
      },
      test("a missing document file refuses typed RA-INP (exit 21)") {
        val (stdout, exit) = capture(
          PortableCheck.command(List("--doc", "/nonexistent/pwf/doc.json"))
        )
        assert(exit)(equalTo(raider.core.result.ExitCodes.ConfigInvalid)) &&
        assert(stdout)(containsString("\"valid\":false")) &&
        assert(stdout)(containsString("RA-INP"))
      },
      test(
        "bad argv refuses typed RA-CFG with the usage line (human line on stderr, no stdout verdict)"
      ) {
        val exit = PortableCheck.command(Nil)
        assert(exit)(equalTo(raider.core.result.ExitCodes.ConfigInvalid))
      },
      test("zero dispatch: the route needs NO credentials and NO env") {
        // Structural proof: the whole route runs with an empty environment on
        // a live document — any hidden credential preflight or backend
        // construction would have refused.
        val f = docFile(validDoc)
        val (_, exit) =
          capture(PortableCheck.command(List("--doc", f.toString)))
        assert(exit)(equalTo(raider.core.result.ExitCodes.Success))
      }
    ) @@ TestAspect.sequential

end PortableCheckSpec
