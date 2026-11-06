package raider.tools

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.loop.{AgentLoop, PlanMode, PlanStore}
import zio.json.*
import zio.stream.ZStream
import zio.test.*
import zio.{Scope, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** RAI-260 acceptance: structured multi-file edit (`fs_patch_multi`) and
  * single-file delete (`fs_delete`) as FIRST-CLASS model tools in the coding
  * toolset registry — on top of the RAI-067
  * [[raider.tools.files.edit.PatchTool]] `applyAll` engine — with the SAME
  * safety contract as fs_edit/fs_patch:
  *
  *   - MUT-01 — registry inventory: both tools exist, both are stamped
  *     `RecoveryClass.Mutating` (plan mode must exclude/deny them), both
  *     declare `concurrentSafe = false`.
  *   - MUT-02 — fs_patch_multi: all-or-nothing batch apply with an honest
  *     single-line JSON report (applied/failed/rolledBack/staged); a stale
  *     provenance on ANY file leaves the whole workspace untouched and is
  *     reported per-file, not thrown; a commit-phase I/O failure restores the
  *     already-applied files (best-effort restore contract) and says so;
  *     adversarial targets (workspace escape, missing file, empty diff) are
  *     typed; unicode filenames AND contents round-trip byte-exact.
  *   - MUT-03 — fs_delete: deletes exactly one regular file with provenance
  *     (stale sha refused, file stays); missing file / directory target /
  *     workspace escape are structured model-correctable feedback; unicode
  *     filename deletes cleanly.
  *   - MUT-04 — serialization: two concurrent fs_patch_multi calls through the
  *     SAME registry (the registry delegated children share) serialize on the
  *     shared file — exactly one lands, the other is stale-refused; the disk is
  *     never corrupted and never silently loses an update.
  *   - MUT-05 — loop wiring: plan mode excludes both tools from the advertised
  *     surface and denies a direct call typed (nothing executes); an ordinary
  *     runText round carries the fs_patch_multi report back to the model as
  *     structured tool feedback.
  *
  * All checks run against the REAL workspace tools with a deterministic
  * scripted backend — no network, no spend.
  */
object CodingToolsetMutationSpec extends ZIOSpecDefault:

  private val a = AttemptId("mut-att")

  private val ReadOnlyNames = List("fs_read", "fs_search", "fs_tree")

  private val validPlanJson =
    """{"title":"Ship it","steps":["patch the files","report"]}"""

  /** k-th call replays k-th turn. */
  private final class TurnBackend(
      turns: Vector[ModelRequest => Vector[ModelEvent]]
  ) extends ModelBackend:
    private val counter = AtomicInteger(0)

    private val recorded =
      new java.util.concurrent.CopyOnWriteArrayList[ModelRequest]

    def capabilities: ModelCapabilities =
      ModelCapabilities(
        CapabilityStatus.Supported,
        CapabilityStatus.Supported,
        CapabilityStatus.Unknown
      )

    def requests: List[ModelRequest] = recorded.asScala.toList

    def stream(input: ModelRequest): ZStream[Scope, RaiderError, ModelEvent] =
      ZStream.unwrap:
        ZIO.succeed:
          val k = counter.getAndIncrement()
          recorded.add(input)
          ZStream.fromIterable(
            ModelEvent.Started(AttemptId(s"t$k")) +:
              (if k < turns.length then turns(k)(input)
               else Vector(ModelEvent.Finished(AttemptId(s"t$k"), "stop")))
          )

  private def toolRound(
      callId: String,
      tool: String,
      args: String
  ): ModelRequest => Vector[ModelEvent] = _ =>
    Vector[ModelEvent](
      ModelEvent.Started(a),
      ModelEvent.ToolCallReady(a, ToolCallId(callId), tool, args),
      ModelEvent.Finished(a, "tool_round")
    )

  private def textTurn(text: String): ModelRequest => Vector[ModelEvent] = _ =>
    Vector[ModelEvent](
      ModelEvent.Started(a),
      ModelEvent.TextDelta(a, text),
      ModelEvent.Finished(a, "stop")
    )

  private def planTurn(json: String): ModelRequest => Vector[ModelEvent] = _ =>
    Vector[ModelEvent](
      ModelEvent.Started(a),
      ModelEvent.TextDelta(a, json),
      ModelEvent.Finished(a, "stop")
    )

  private def lims: BudgetLimits =
    BudgetLimits.make(maxAttempts = 16).fold(e => throw e, identity)

  private def sha256Hex(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(UTF_8))
      .map("%02x" format _)
      .mkString

  private def sha256OfFile(dir: java.nio.file.Path, name: String): String =
    val bytes = Files.readAllBytes(dir.resolve(name))
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map("%02x" format _)
      .mkString

  private def withWorkspace[R, E, A](
      f: (java.nio.file.Path) => ZIO[R, E, A]
  ): ZIO[R & Scope, E, A] =
    ZIO
      .acquireReleaseWith(
        ZIO
          .attempt(Files.createTempDirectory("raider-mutation-test"))
          .orDie
      )(dir =>
        ZIO
          .attempt(Delete.recursive(dir))
          .catchAll(_ => ZIO.unit)
      ) { dir =>
        f(dir)
      }

  private object Delete:

    def recursive(p: java.nio.file.Path): Unit =
      import scala.jdk.CollectionConverters.*
      if Files.isDirectory(p) then
        Files.list(p).iterator().asScala.foreach(recursive)
      Files.deleteIfExists(p)
      ()

  /** Invoke a registry tool the way the loop does (scoped) and surface a
    * missing tool as a loud failure — the RED state of this spec before
    * RAI-260.
    */
  private def invoke(
      reg: ToolRegistry,
      name: String,
      args: String
  ): ZIO[Scope, RaiderError, String] =
    reg.lookup(name) match
      case None =>
        ZIO.fail(RaiderError.ToolFailed(s"TOOL MISSING FROM REGISTRY: $name"))
      case Some(t) => t.invoke(args)

  private def write(
      dir: java.nio.file.Path,
      name: String,
      content: String
  ): String =
    Files.createDirectories(dir.resolve(name).getParent)
    Files.writeString(dir.resolve(name), content, UTF_8)
    sha256Hex(content)

  private def read(dir: java.nio.file.Path, name: String): String =
    if Files.exists(dir.resolve(name)) then
      Files.readString(dir.resolve(name), UTF_8)
    else "<absent>"

  /** A modify-section hunk replacing the line `editFrom` with `editTo`. */
  private def modPatch(
      name: String,
      lines: List[String],
      editFrom: String,
      editTo: String
  ): String =
    val n = lines.size
    val body = lines
      .map: l =>
        if l == editFrom then s"-$l\n+$editTo" else s" $l"
      .mkString("\n")
    s"""diff --git a/$name b/$name
       |index 0000000..1111111 100644
       |--- a/$name
       |+++ b/$name
       |@@ -1,$n +1,$n @@
       |$body
       |""".stripMargin

  private val LinesA = List("alpha-1", "alpha-2")
  private val LinesB = List("beta-1", "beta-2")
  private val LinesC = List("gamma-1", "gamma-2")

  private val A = "alpha-1\nalpha-2\n"
  private val B = "beta-1\nbeta-2\n"
  private val C = "gamma-1\ngamma-2\n"

  private def multiArgs(diff: String, shas: Map[String, String]): String =
    val shaJson = shas
      .map((k, v) => s"""${k.toJson}:${v.toJson}""")
      .mkString("{", ",", "}")
    s"""{"diff":${diff.toJson},"expected_sha256":$shaJson}"""

  private def deleteArgs(name: String, sha: String): String =
    s"""{"path":${name.toJson},"expected_sha256":${sha.toJson}}"""

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("coding toolset mutation tools (RAI-260)")(
      suite("MUT-01 registry inventory")(
        test(
          "fs_patch_multi and fs_delete are exposed, both Mutating and not concurrentSafe"
        ) {
          val work = Files.createTempDirectory("raider-mut-01")
          for
            ws <- CodingToolset.make(work.toString)
            reg = ws.registry.fold(e => throw e, identity)
          yield assertTrue(
            reg.names.contains("fs_patch_multi"),
            reg.names.contains("fs_delete"),
            reg.lookup("fs_patch_multi").map(_.recovery) ==
              Some(RecoveryClass.Mutating),
            reg.lookup("fs_delete").map(_.recovery) ==
              Some(RecoveryClass.Mutating),
            reg.lookup("fs_patch_multi").exists(!_.capabilities.concurrentSafe),
            reg.lookup("fs_delete").exists(!_.capabilities.concurrentSafe)
          )
        }
      ),
      suite("MUT-02 fs_patch_multi")(
        test(
          "applies a 3-file modify batch; single-line JSON report carries per-file results"
        ) {
          withWorkspace { dir =>
            val shas = Map(
              "A.txt" -> write(dir, "A.txt", A),
              "B.txt" -> write(dir, "B.txt", B),
              "C.txt" -> write(dir, "C.txt", C)
            )
            val diff =
              modPatch("A.txt", LinesA, "alpha-2", "alpha-2-edited") +
                modPatch("B.txt", LinesB, "beta-2", "beta-2-edited") +
                modPatch("C.txt", LinesC, "gamma-2", "gamma-2-edited")
            for
              ws <- CodingToolset.make(dir.toString)
              reg = ws.registry.fold(e => throw e, identity)
              out <- ZIO.scoped(
                invoke(reg, "fs_patch_multi", multiArgs(diff, shas))
              )
            yield
              val aTxt = read(dir, "A.txt")
              val bTxt = read(dir, "B.txt")
              val cTxt = read(dir, "C.txt")
              assertTrue(
                !out.contains("\n"),
                out.contains("\"rolledBack\":false"),
                out.contains("\"failed\":[]"),
                out.contains("\"path\":\"A.txt\""),
                out.contains("\"path\":\"B.txt\""),
                out.contains("\"path\":\"C.txt\""),
                aTxt.contains("alpha-2-edited"),
                bTxt.contains("beta-2-edited"),
                cTxt.contains("gamma-2-edited")
              )
          }
        },
        test(
          "stale 2nd of 3: NOTHING applied, structured report names the file with its typed reason, staged work disclosed"
        ) {
          withWorkspace { dir =>
            val shaA = write(dir, "A.txt", A)
            val shaB = write(dir, "B.txt", B)
            val shaC = write(dir, "C.txt", C)
            val diff =
              modPatch("A.txt", LinesA, "alpha-2", "alpha-2-edited") +
                modPatch("B.txt", LinesB, "beta-2", "beta-2-edited") +
                modPatch("C.txt", LinesC, "gamma-2", "gamma-2-edited")
            val shas = Map(
              "A.txt" -> shaA,
              "B.txt" -> ("deadbeef" + "0" * 56),
              "C.txt" -> shaC
            )
            val before =
              (read(dir, "A.txt"), read(dir, "B.txt"), read(dir, "C.txt"))
            for
              ws <- CodingToolset.make(dir.toString)
              reg = ws.registry.fold(e => throw e, identity)
              out <- ZIO.scoped(
                invoke(reg, "fs_patch_multi", multiArgs(diff, shas))
              )
            yield
              val after =
                (read(dir, "A.txt"), read(dir, "B.txt"), read(dir, "C.txt"))
              assertTrue(
                out.contains("\"applied\":[]"),
                out.contains("\"rolledBack\":true"),
                out.contains("\"file\":\"B.txt\""),
                out.contains("STALE PATCH"),
                out.contains("RA-TOOLDENY"),
                out.contains("\"A.txt\""),
                out.contains("\"C.txt\""),
                before == after
              )
          }
        },
        test(
          "commit-phase I/O failure: already-applied files are restored, report says commit failed (best-effort restore contract)"
        ) {
          withWorkspace { dir =>
            val shaA = write(dir, "A.txt", A)
            val shaB = write(dir, "B.txt", B)
            val shaC =
              write(dir, "sub3/C.txt", C)
            val diff =
              modPatch("A.txt", LinesA, "alpha-2", "alpha-2-edited") +
                modPatch("B.txt", LinesB, "beta-2", "beta-2-edited") +
                modPatch("sub3/C.txt", LinesC, "gamma-2", "gamma-2-edited")
            val shas = Map(
              "A.txt" -> shaA,
              "B.txt" -> shaB,
              "sub3/C.txt" -> shaC
            )
            // sub3 becomes unwritable AFTER validation succeeds: the commit
            // phase fails on C.txt and the restore path must kick in
            Files.createDirectories(dir.resolve("sub3"))
            for
              ws <- CodingToolset.make(dir.toString)
              reg = ws.registry.fold(e => throw e, identity)
              _ <- ZIO
                .attempt(dir.resolve("sub3").toFile.setWritable(false))
                .orDie
              out <- ZIO
                .scoped(invoke(reg, "fs_patch_multi", multiArgs(diff, shas)))
                .ensuring(
                  ZIO
                    .attempt(dir.resolve("sub3").toFile.setWritable(true))
                    .orDie
                )
            yield
              val after =
                (
                  read(dir, "A.txt"),
                  read(dir, "B.txt"),
                  read(dir, "sub3/C.txt")
                )
              assertTrue(
                out.contains("commit failed for sub3/C.txt"),
                out.contains("restored"),
                out.contains("\"rolledBack\":true"),
                out.contains("\"applied\":[]"),
                after == (A, B, C)
              )
          }
        },
        test("empty diff is a structured InputValidation, not a crash") {
          withWorkspace { dir =>
            for
              ws <- CodingToolset.make(dir.toString)
              reg = ws.registry.fold(e => throw e, identity)
              out <- ZIO.scoped(
                invoke(reg, "fs_patch_multi", multiArgs("   ", Map.empty))
              )
            yield assertTrue(
              out.contains("\"error\":\"RA-INP\""),
              out.contains("diff must be non-empty")
            )
          }
        },
        test(
          "workspace-escaping section is per-file RA-TOOLDENY; nothing applied, nothing leaked"
        ) {
          withWorkspace { dir =>
            val shaA = write(dir, "A.txt", A)
            val outside =
              """diff --git a/../escape.txt b/../escape.txt
                |--- a/../escape.txt
                |+++ b/../escape.txt
                |@@ -1,1 +1,1 @@
                |-secret
                |+pwned
                |""".stripMargin
            val diff =
              modPatch("A.txt", LinesA, "alpha-2", "alpha-2-edited") + outside
            val shas = Map("A.txt" -> shaA, "../escape.txt" -> "0" * 64)
            for
              ws <- CodingToolset.make(dir.toString)
              reg = ws.registry.fold(e => throw e, identity)
              out <- ZIO.scoped(
                invoke(reg, "fs_patch_multi", multiArgs(diff, shas))
              )
            yield
              val escapeLeak = Files.exists(dir.resolve("../escape.txt"))
              // EAGER capture (assertTrue thunks run after tmp-dir cleanup)
              val aAfter = read(dir, "A.txt")
              assertTrue(
                out.contains("\"file\":\"../escape.txt\""),
                out.contains("RA-TOOLDENY"),
                out.contains("\"rolledBack\":true"),
                out.contains("\"applied\":[]"),
                !escapeLeak,
                aAfter == A
              )
          }
        },
        test(
          "nonexistent target is a typed per-file failure naming the path; disk clean"
        ) {
          withWorkspace { dir =>
            val shaA = write(dir, "A.txt", A)
            val diff = modPatch("Ghost.txt", List("ghost-1"), "ghost-1", "boo")
            val shas = Map("A.txt" -> shaA, "Ghost.txt" -> "0" * 64)
            for
              ws <- CodingToolset.make(dir.toString)
              reg = ws.registry.fold(e => throw e, identity)
              out <- ZIO.scoped(
                invoke(reg, "fs_patch_multi", multiArgs(diff, shas))
              )
            yield
              // EAGER capture (assertTrue thunks run after tmp-dir cleanup)
              val aAfter = read(dir, "A.txt")
              assertTrue(
                out.contains("\"file\":\"Ghost.txt\""),
                out.contains("file not found"),
                out.contains("\"applied\":[]"),
                aAfter == A
              )
          }
        },
        test(
          "unicode filename AND unicode content round-trip byte-exact through a create section"
        ) {
          withWorkspace { dir =>
            val uniDir = "данные-папка"
            val uniName = s"$uniDir/отчёт-β.txt"
            val uniContent = "привет-мир\nсимволы-Ω-✓-日本語\n"
            val create =
              s"""diff --git a/$uniName b/$uniName
                 |new file mode 100644
                 |--- /dev/null
                 |+++ b/$uniName
                 |@@ -0,0 +1,2 @@
                 |+привет-мир
                 |+символы-Ω-✓-日本語
                 |""".stripMargin
            for
              ws <- CodingToolset.make(dir.toString)
              reg = ws.registry.fold(e => throw e, identity)
              out <- ZIO.scoped(
                invoke(reg, "fs_patch_multi", multiArgs(create, Map.empty))
              )
            yield
              val written = read(dir, uniName)
              // EAGER capture: ZIO Test evaluates assertTrue thunks AFTER the
              // test scope closes (the tmp workspace is gone by then), so all
              // disk state must be captured into vals before asserting.
              val writtenSha = sha256OfFile(dir, uniName)
              assertTrue(
                out.contains("\"rolledBack\":false"),
                out.contains("\"failed\":[]"),
                out.contains(uniName.toJson),
                written == uniContent,
                writtenSha == sha256Hex(uniContent)
              )
          }
        }
      ),
      suite("MUT-03 fs_delete")(
        test(
          "deletes exactly the named regular file; report carries the old sha"
        ) {
          withWorkspace { dir =>
            val sha = write(dir, "obsolete.txt", "obsolete-bytes\n")
            for
              ws <- CodingToolset.make(dir.toString)
              reg = ws.registry.fold(e => throw e, identity)
              out <- ZIO.scoped(
                invoke(reg, "fs_delete", deleteArgs("obsolete.txt", sha))
              )
            yield assertTrue(
              out.contains("\"action\":\"deleted\""),
              out.contains("\"path\":\"obsolete.txt\""),
              out.contains(sha),
              !Files.exists(dir.resolve("obsolete.txt"))
            )
          }
        },
        test(
          "missing file: structured model-correctable feedback, nothing deleted"
        ) {
          withWorkspace { dir =>
            for
              ws <- CodingToolset.make(dir.toString)
              reg = ws.registry.fold(e => throw e, identity)
              out <- ZIO.scoped(
                invoke(reg, "fs_delete", deleteArgs("never-was.txt", "0" * 64))
              )
            yield assertTrue(
              out.contains("\"error\":\"RA-INP\""),
              out.contains("file not found"),
              out.contains("never-was.txt")
            )
          }
        },
        test("directory target is refused, directory stays") {
          withWorkspace { dir =>
            Files.createDirectories(dir.resolve("a-dir"))
            for
              ws <- CodingToolset.make(dir.toString)
              reg = ws.registry.fold(e => throw e, identity)
              out <- ZIO.scoped(
                invoke(reg, "fs_delete", deleteArgs("a-dir", "0" * 64))
              )
            yield
              // EAGER capture (assertTrue thunks run after tmp-dir cleanup)
              val dirStays = Files.exists(dir.resolve("a-dir"))
              assertTrue(
                out.contains("\"error\":\"RA-INP\""),
                out.contains("directory"),
                dirStays
              )
          }
        },
        test("stale sha is a typed denial; the file survives") {
          withWorkspace { dir =>
            val sha = write(dir, "keep.txt", "keep-me\n")
            for
              ws <- CodingToolset.make(dir.toString)
              reg = ws.registry.fold(e => throw e, identity)
              out <- ZIO.scoped(
                invoke(
                  reg,
                  "fs_delete",
                  deleteArgs("keep.txt", "deadbeef" + "0" * 56)
                )
              )
            yield
              // EAGER capture (assertTrue thunks run after tmp-dir cleanup)
              val keepStays = Files.exists(dir.resolve("keep.txt"))
              val keepSha = sha256OfFile(dir, "keep.txt")
              assertTrue(
                out.contains("\"error\":\"RA-TOOLDENY\""),
                out.contains("STALE"),
                keepStays,
                keepSha == sha
              )
          }
        },
        test("workspace escape is a typed denial, nothing outside touched") {
          withWorkspace { dir =>
            val outside = dir.getParent.resolve("outside-victim.txt")
            Files.writeString(outside, "victim\n", UTF_8)
            val victimSha = sha256Hex("victim\n")
            for
              ws <- CodingToolset.make(dir.toString)
              reg = ws.registry.fold(e => throw e, identity)
              out <- ZIO.scoped(
                invoke(
                  reg,
                  "fs_delete",
                  deleteArgs("../outside-victim.txt", victimSha)
                )
              )
            yield assertTrue(
              out.contains("\"error\":\"RA-TOOLDENY\""),
              out.contains("escapes workspace"),
              Files.exists(outside)
            )
          }
        },
        test("unicode filename deletes cleanly with matching provenance") {
          withWorkspace { dir =>
            val uniName = "черновики/заметка-Ω.txt"
            val sha = write(dir, uniName, "удалить-меня\n")
            for
              ws <- CodingToolset.make(dir.toString)
              reg = ws.registry.fold(e => throw e, identity)
              out <- ZIO.scoped(
                invoke(reg, "fs_delete", deleteArgs(uniName, sha))
              )
            yield assertTrue(
              out.contains("\"action\":\"deleted\""),
              out.contains(uniName.toJson),
              !Files.exists(dir.resolve(uniName))
            )
          }
        }
      ),
      suite("MUT-04 serialization on shared files")(
        test(
          "two concurrent fs_patch_multi on the SAME file through one registry: exactly one lands, the other stale-refused, disk never corrupted"
        ) {
          withWorkspace { dir =>
            val base = "shared-v1\n"
            val sha = write(dir, "shared.txt", base)
            val diffOne = modPatch(
              "shared.txt",
              List("shared-v1"),
              "shared-v1",
              "edited-one"
            )
            val diffTwo = modPatch(
              "shared.txt",
              List("shared-v1"),
              "shared-v1",
              "edited-two"
            )
            for
              ws <- CodingToolset.make(dir.toString)
              reg = ws.registry.fold(e => throw e, identity)
              exits <- ZIO
                .collectAllPar(
                  List(
                    ZIO
                      .scoped(
                        invoke(
                          reg,
                          "fs_patch_multi",
                          multiArgs(diffOne, Map("shared.txt" -> sha))
                        )
                      )
                      .exit,
                    ZIO
                      .scoped(
                        invoke(
                          reg,
                          "fs_patch_multi",
                          multiArgs(diffTwo, Map("shared.txt" -> sha))
                        )
                      )
                      .exit
                  )
                )
            yield
              val outs = exits.map {
                case zio.Exit.Success(value) => value
                case zio.Exit.Failure(cause) =>
                  s"<failed:${cause.failures.headOption.map(_.code).getOrElse("?")}>"
              }
              val finalTxt = read(dir, "shared.txt")
              val oneLanded =
                finalTxt == "edited-one\n" || finalTxt == "edited-two\n"
              val lostUpdateDetected =
                outs.count(o => o.contains("\"rolledBack\":false")) == 1 &&
                  outs.exists(_.contains("STALE PATCH"))
              assertTrue(
                outs.size == 2,
                oneLanded,
                lostUpdateDetected
              )
          }
        }
      ),
      suite("MUT-05 loop wiring")(
        test(
          "plan phase: fs_patch_multi and fs_delete are NOT advertised and a direct call is denied typed with NOTHING executed"
        ) {
          withWorkspace { work =>
            val src = work.resolve("src.txt")
            Files.writeString(src, "hello\n", UTF_8)
            val sha = sha256Hex("hello\n")
            val patchArgs = multiArgs(
              modPatch("src.txt", List("hello"), "hello", "world"),
              Map("src.txt" -> sha)
            )
            for
              ws <- CodingToolset.make(work.toString)
              registry = ws.registry.fold(e => throw e, identity)
              backend = new TurnBackend(
                Vector(
                  toolRound("p1", "fs_patch_multi", patchArgs),
                  toolRound("p2", "fs_delete", deleteArgs("src.txt", sha)),
                  planTurn(validPlanJson)
                )
              )
              adm <- Admission.make(Right(lims))
              root = AgentLoop.newRoot()
              planStore = Files.createTempDirectory("raider-mut-plan-store")
              loop = AgentLoop(
                backend,
                registry,
                "m",
                adm,
                root,
                planMode = Some(PlanMode.of(registry, planStore))
              )
              proposal <- loop.runPlan(
                List(RequestMessage("user", "plan the change")),
                lims,
                maxRounds = 8
              )
              reqs = backend.requests
              finalToolMsgs = reqs.last.messages.filter(_.role == "tool")
            yield
              // EAGER capture (assertTrue thunks run after tmp-dir cleanup)
              val srcUntouched = Files.readString(src, UTF_8)
              assertTrue(
                proposal.title == "Ship it",
                // both new tools are Mutating → excluded from the advertised surface
                reqs.forall(_.tools.map(_.name) == ReadOnlyNames),
                // each direct call was denied typed, the loop stayed alive
                finalToolMsgs.count(_.content.contains("RA-TOOLDENY")) == 2,
                finalToolMsgs.exists(_.content.contains("fs_patch_multi")),
                finalToolMsgs.exists(_.content.contains("fs_delete")),
                // and NOTHING executed
                srcUntouched == "hello\n",
                srcUntouched.nonEmpty
              )
          }
        },
        test(
          "ordinary runText: an fs_patch_multi call applies and its report reaches the model as structured tool feedback"
        ) {
          withWorkspace { dir =>
            val shaB = write(dir, "B.txt", B)
            val diff = modPatch("B.txt", LinesB, "beta-2", "beta-2-edited")
            for
              ws <- CodingToolset.make(dir.toString)
              registry = ws.registry.fold(e => throw e, identity)
              backend = new TurnBackend(
                Vector(
                  toolRound(
                    "m1",
                    "fs_patch_multi",
                    multiArgs(diff, Map("B.txt" -> shaB))
                  ),
                  textTurn("done")
                )
              )
              adm <- Admission.make(Right(lims))
              loop = AgentLoop(backend, registry, "m", adm, AgentLoop.newRoot())
              answer <- loop.runText(
                List(RequestMessage("user", "apply the batch")),
                lims,
                maxRounds = 8
              )
              reqs = backend.requests
              toolMsgs = reqs(1).messages.filter(_.role == "tool")
            yield
              // EAGER capture (assertTrue thunks run after tmp-dir cleanup)
              val bAfter = read(dir, "B.txt")
              assertTrue(
                answer == "done",
                reqs.size == 2,
                toolMsgs.exists(_.content.contains("\"rolledBack\":false")),
                toolMsgs.exists(_.content.contains("B.txt")),
                bAfter.contains("beta-2-edited")
              )
          }
        }
      )
    )
