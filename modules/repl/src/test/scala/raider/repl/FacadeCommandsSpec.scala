package raider.repl

import raider.core.AttemptId
import raider.repl.facade.{
  DiffReviewCommands,
  ModelCommands,
  ReplCommands,
  ReplSession
}
import raider.testkit.ScriptedModelBackend
import raider.tools.files.edit.EditTool
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestAspect.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** RAI-192 facade acceptance: the per-hunk diff review commands apply accepted
  * hunks through the production `fs_patch` policy (PatchTool), fail closed on
  * stale provenance / empty selections / multi-target reviews, and the model
  * switcher changes the installed session without restarting, refusing safely
  * without credentials and never echoing a key.
  *
  * One spec object on purpose: both facades share the `ReplCommands` static
  * session slot, so their fixtures must not run concurrently (sbt may run
  * separate test classes in parallel; suites inside one ZIOSpec are
  * sequential).
  */
object FacadeCommandsSpec extends ZIOSpecDefault:

  private val fenceBody =
    """--- a/raider-review-demo.txt
      |+++ b/raider-review-demo.txt
      |@@ -1,3 +1,3 @@
      | alpha
      |-beta
      |+BETA
      | gamma
      |@@ -5,2 +5,2 @@
      | epsilon
      |-zeta
      |+ZETA""".stripMargin

  private val fence = s"```diff\n$fenceBody\n```"
  private val initialContent = "alpha\nbeta\ngamma\ndelta\nepsilon\nzeta\n"

  private def unsafeRun[A](eff: zio.ZIO[Any, Throwable, A]): A =
    zio.Unsafe.unsafe { implicit u =>
      zio.Runtime.default.unsafe.run(eff).getOrThrow()
    }

  private def tempRoot(): Path =
    Files.createTempDirectory("rai192-facade")

  private def writeTarget(root: Path, name: String, content: String): Path =
    val target = root.resolve(name)
    Files.write(target, content.getBytes(StandardCharsets.UTF_8))
    target

  /** Install a live session whose sink has already streamed `text` to
    * completion, with the patch root pinned to `root` for the fixture body.
    */
  private def withTurn[A](root: Path, text: String)(
      body: ChatLayoutSink => A
  ): A =
    val sink = new ChatLayoutSink(_ => (), model = "scripted")
    val attempt = AttemptId("rai192-facade-spec")
    val backend = unsafeRun(
      ScriptedModelBackend(
        raider.core.ModelEvent.Started(attempt),
        raider.core.ModelEvent.Finished(attempt, "stop")
      )
    )
    val session = unsafeRun(
      ReplSession.make(
        backend,
        renderSink = Some(sink),
        toolApproval = Some(sink)
      )
    )
    ReplCommands.install(session)
    DiffReviewCommands.workspaceRootOverride = Some(root.toString)
    try
      sink.beginAssistant()
      unsafeRun(sink.textDelta(text))
      sink.completeAssistant()
      body(sink)
    finally DiffReviewCommands.workspaceRootOverride = None

  private def shaOf(path: Path): String =
    EditTool.sha256OfFile(path).toOption.get

  def spec =
    suite("FacadeCommandsSpec (RAI-192)")(
      suite("DiffReviewCommands")(
        test("decide marks the hunk and anchors file sha256 provenance") {
          val root = tempRoot()
          val target =
            writeTarget(root, "raider-review-demo.txt", initialContent)
          withTurn(root, fence) { sink =>
            val response = DiffReviewCommands.decide(1, accept = true)
            val snap = sink.reviewSnapshot().get
            assertTrue(
              response.contains("hunk 1 accepted"),
              response.contains("1/2 decided"),
              snap.decisions == Vector(Some(true), None),
              snap.decidedCount == 1,
              snap.provenance == Some(shaOf(target)),
              !snap.applied
            )
          }
        },
        test("apply writes only accepted hunks and seals the review") {
          val root = tempRoot()
          val target =
            writeTarget(root, "raider-review-demo.txt", initialContent)
          withTurn(root, fence) { sink =>
            DiffReviewCommands.decide(1, accept = true)
            val applied = DiffReviewCommands.applyAccepted()
            val content = new String(
              Files.readAllBytes(target),
              StandardCharsets.UTF_8
            )
            val again = DiffReviewCommands.applyAccepted()
            val snap = sink.reviewSnapshot().get
            assertTrue(
              applied.contains("applied 1 hunk(s) to raider-review-demo.txt"),
              applied.contains("new sha256"),
              content == "alpha\nBETA\ngamma\ndelta\nepsilon\nzeta\n",
              again.contains("already applied"),
              snap.applied,
              snap.decisions == Vector(Some(true), None)
            )
          }
        },
        test("rejected hunks are never applied") {
          val root = tempRoot()
          val target =
            writeTarget(root, "raider-review-demo.txt", initialContent)
          withTurn(root, fence) { sink =>
            DiffReviewCommands.decide(1, accept = false)
            DiffReviewCommands.decide(2, accept = true)
            val applied = DiffReviewCommands.applyAccepted()
            val content = new String(
              Files.readAllBytes(target),
              StandardCharsets.UTF_8
            )
            assertTrue(
              applied.contains("applied 1 hunk(s)"),
              content == "alpha\nbeta\ngamma\ndelta\nepsilon\nZETA\n"
            )
          }
        },
        test("stale provenance refuses the apply and keeps the review open") {
          val root = tempRoot()
          val target =
            writeTarget(root, "raider-review-demo.txt", initialContent)
          withTurn(root, fence) { sink =>
            DiffReviewCommands.decide(2, accept = true)
            // Workspace changed under the open review: the anchored sha no
            // longer matches, so the production policy must refuse.
            Files.write(
              target,
              "alpha\nbeta\ngamma\ndelta\nepsilon\nzeta\nextra\n"
                .getBytes(StandardCharsets.UTF_8)
            )
            val applied = DiffReviewCommands.applyAccepted()
            val content = new String(
              Files.readAllBytes(target),
              StandardCharsets.UTF_8
            )
            val snap = sink.reviewSnapshot().get
            assertTrue(
              applied.contains("apply refused"),
              content.endsWith("extra\n"),
              !snap.applied,
              snap.decisions == Vector(None, Some(true))
            )
          }
        },
        test("out-of-range hunk index is refused with the valid range") {
          val root = tempRoot()
          writeTarget(root, "raider-review-demo.txt", initialContent)
          withTurn(root, fence) { sink =>
            val response = DiffReviewCommands.decide(9, accept = true)
            assertTrue(
              response.contains("hunk 9 does not exist"),
              response.contains("valid: 1..2"),
              sink.reviewSnapshot().get.decidedCount == 0
            )
          }
        },
        test("apply with no accepted hunks is refused") {
          val root = tempRoot()
          val target =
            writeTarget(root, "raider-review-demo.txt", initialContent)
          withTurn(root, fence) { sink =>
            DiffReviewCommands.decide(1, accept = false)
            val applied = DiffReviewCommands.applyAccepted()
            assertTrue(
              applied.contains("no hunks accepted"),
              new String(
                Files.readAllBytes(target),
                StandardCharsets.UTF_8
              ) == initialContent,
              !sink.reviewSnapshot().get.applied
            )
          }
        },
        test("multi-target reviews are refused before any write") {
          val root = tempRoot()
          val multiFence =
            "```diff\n--- a/one.txt\n+++ b/one.txt\n@@ -1,1 +1,1 @@\n-x\n+y\n```\n" +
              "```diff\n--- a/two.txt\n+++ b/two.txt\n@@ -1,1 +1,1 @@\n-a\n+b\n```"
          writeTarget(root, "one.txt", "x\n")
          writeTarget(root, "two.txt", "a\n")
          withTurn(root, multiFence) { sink =>
            DiffReviewCommands.decide(1, accept = true)
            val applied = DiffReviewCommands.applyAccepted()
            assertTrue(
              applied.contains("spans multiple files"),
              new String(
                Files.readAllBytes(root.resolve("one.txt")),
                StandardCharsets.UTF_8
              ) == "x\n",
              new String(
                Files.readAllBytes(root.resolve("two.txt")),
                StandardCharsets.UTF_8
              ) == "a\n"
            )
          }
        },
        test("fences without target headers refuse the apply") {
          val root = tempRoot()
          writeTarget(root, "raider-review-demo.txt", initialContent)
          val headerless = "```diff\n@@ -1,2 +1,2 @@\n alpha\n-beta\n+BETA\n```"
          withTurn(root, headerless) { sink =>
            DiffReviewCommands.decide(1, accept = true)
            val applied = DiffReviewCommands.applyAccepted()
            assertTrue(
              applied.contains("target path missing"),
              !sink.reviewSnapshot().get.applied
            )
          }
        },
        test("status and decide fail closed without an open review") {
          val root = tempRoot()
          writeTarget(root, "raider-review-demo.txt", initialContent)
          withTurn(root, "a plain answer without any diff fence") { sink =>
            assertTrue(
              DiffReviewCommands.status().contains("no diff review open"),
              DiffReviewCommands
                .decide(1, accept = true)
                .contains("no diff review open"),
              DiffReviewCommands
                .applyAccepted()
                .contains("no diff review open"),
              sink.reviewSnapshot().isEmpty
            )
          }
        }
      ),
      suite("ModelCommands")(
        test("describe reports the installed scripted binding") {
          val root = tempRoot()
          withTurn(root, "unused") { _ =>
            val described = ModelCommands.describe()
            assertTrue(
              described.contains("provider mock"),
              described.contains("model scripted")
            )
          }
        },
        test("unknown provider is refused and the session stays installed") {
          val root = tempRoot()
          withTurn(root, "unused") { _ =>
            val before = ReplCommands.currentSession.get
            val response = ModelCommands.switch("bogus")
            assertTrue(
              response.contains("unknown provider 'bogus'"),
              response.contains("valid: mock, openai, anthropic"),
              ReplCommands.currentSession.get eq before,
              before.model == "scripted"
            )
          }
        },
        test("live provider without a key is refused, model unchanged") {
          val root = tempRoot()
          withTurn(root, "unused") { _ =>
            val before = ReplCommands.currentSession.get
            val response =
              ModelCommands.switch("openai", "gpt-test", env = Map.empty)
            assertTrue(
              response.contains("no API key resolved for 'openai'"),
              response.contains("model unchanged: scripted"),
              response.contains("RAIDER_API_KEY"),
              ReplCommands.currentSession.get eq before
            )
          }
        },
        test("openai switch with a fake key constructs without dialing") {
          val root = tempRoot()
          withTurn(root, "unused") { _ =>
            val secret = "sk-test-secret-123456"
            val sink = ReplCommands.currentSession.get.renderSink
            val response =
              ModelCommands.switch(
                "openai",
                "gpt-test",
                env = Map("RAIDER_API_KEY" -> secret)
              )
            val now = ReplCommands.currentSession.get
            val described = ModelCommands.describe()
            assertTrue(
              response.contains("model switched: openai/gpt-test"),
              !response.contains(secret),
              !described.contains(secret),
              described.contains("provider openai"),
              now.model == "gpt-test",
              now.renderSink eq sink
            )
          }
        },
        test("mock switch keeps the render sink and changes the model") {
          val root = tempRoot()
          withTurn(root, "unused") { _ =>
            val sink = ReplCommands.currentSession.get.renderSink
            val response = ModelCommands.switch("mock", "scripted-2")
            val now = ReplCommands.currentSession.get
            assertTrue(
              response.contains("model switched: mock/scripted-2"),
              response.contains("no live network"),
              now.model == "scripted-2",
              now.renderSink eq sink
            )
          }
        }
      )
    ) @@ sequential // the facades share the process-global session slot
end FacadeCommandsSpec
