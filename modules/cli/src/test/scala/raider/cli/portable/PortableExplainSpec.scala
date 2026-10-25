package raider.cli.portable

import zio.test.*
import zio.test.Assertion.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** The `workflow explain --doc` route (RAI-199 XE-CLI-01 zero-dispatch clause):
  * prints the local outline (validated shape, resolved targets, required
  * trusted identities) WITHOUT any provider dispatch. The stdout contract is
  * one closed JSON object with `explainable`, the validated digest, the
  * required trusted refs and the effective declared limits.
  */
object PortableExplainSpec extends ZIOSpecDefault:

  private val validDoc: String =
    """{
      |  "format": "raider.portable-workflow", "version": "1.0",
      |  "limits": { "deadlineMs": 30000, "attempts": 2, "parallelism": 1, "children": 0, "tokens": 1000 },
      |  "schemas": { "inputs": { "request": { "type": "string" } }, "outputs": { "verdict": { "type": "string" } } },
      |  "steps": [{ "id": "verify", "kind": "trustedCheck", "checkId": "unit-tests", "registrySnapshot": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "input": "#/inputs/request", "outputSchema": "verdict" }],
      |  "outputs": { "bindings": { "result": "#/steps/verify/output" }, "verdict": { "from": "#/steps/verify/output", "authority": "trustedVerifier" } }
      |}""".stripMargin

  private val invalidDoc: String = validDoc.replace(
    "\"attempts\": 2",
    "\"attempts\": 999999"
  )

  private def docFile(body: String): java.nio.file.Path =
    val dir = Files.createTempDirectory("pwexplain")
    val f = dir.resolve("doc.json")
    Files.writeString(f, body, StandardCharsets.UTF_8)
    f

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
    suite("raider workflow explain --doc (zero-dispatch outline)")(
      test(
        "a valid document prints the outline with required trusted refs and declared limits"
      ) {
        val f = docFile(validDoc)
        val (stdout, exit) =
          capture(PortableExplain.command(List("--doc", f.toString)))
        assert(exit)(equalTo(raider.core.result.ExitCodes.Success)) &&
        assert(stdout)(
          containsString("\"format\":\"raider.portable-workflow\"")
        ) &&
        assert(stdout)(containsString("\"limits\":{")) &&
        assert(stdout)(containsString("\"structure\":{")) &&
        assert(stdout)(
          containsString(
            "\"requiredTrusted\":{\"profiles\":[],\"checks\":[{\"checkId\":\"unit-tests\""
          )
        )
      },
      test("an invalid document prints the diagnostic JSON and exits 21") {
        val f = docFile(invalidDoc)
        val (stdout, exit) =
          capture(PortableExplain.command(List("--doc", f.toString)))
        assert(exit)(equalTo(raider.core.result.ExitCodes.ConfigInvalid)) &&
        assert(stdout)(containsString("\"explainable\":false")) &&
        assert(stdout)(containsString("\"diagnostic\":{"))
      },
      test("a missing document file refuses typed RA-INP (exit 21)") {
        val (stdout, exit) = capture(
          PortableExplain.command(List("--doc", "/nonexistent/pwf/doc.json"))
        )
        assert(exit)(equalTo(raider.core.result.ExitCodes.ConfigInvalid)) &&
        assert(stdout)(containsString("\"explainable\":false"))
      },
      test(
        "bad argv refuses typed RA-CFG with the usage line (human line on stderr, no stdout outline)"
      ) {
        val exit = PortableExplain.command(Nil)
        assert(exit)(equalTo(raider.core.result.ExitCodes.ConfigInvalid))
      }
    ) @@ TestAspect.sequential

end PortableExplainSpec
