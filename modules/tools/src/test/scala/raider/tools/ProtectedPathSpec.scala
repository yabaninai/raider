package raider.tools

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.loop.AgentLoop
import zio.json.*
import zio.stream.ZStream
import zio.test.*
import zio.{Scope, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** RAI-259 acceptance: deny-by-default write-path policy for the coder's
  * structured mutation tools, exercised END-TO-END through the real
  * `CodingToolset` registry (the same registry delegated children share):
  *
  *   - PP-01 — `fs_edit` create outside the root: typed RA-TOOLDENY JSON
  *     feedback, NO file outside, NO file inside (nothing partial).
  *   - PP-02 — `fs_edit` create inside the root: allowed (the deny-by-default
  *     policy must not break the ordinary workflow).
  *   - PP-03 — THE RAI-259 FIX: create through a symlinked directory pointing
  *     OUTSIDE the root. The pre-RAI-259 check only realpath'd targets that
  *     already existed, so a not-yet-existing file under such a directory was
  *     written THROUGH the symlink; now it is denied with the outside directory
  *     left untouched.
  *   - PP-04 — absolute target: refused (root.resolve(absolute) would discard
  *     the root entirely).
  *   - PP-05 — `fs_patch` on an escaping path: denied, target untouched.
  *   - PP-06 — `fs_delete` on an escaping path: denied, outside victim file
  *     survives.
  *   - PP-07 — `fs_patch_multi` mixed batch (one in-root file + one escaping
  *     path): all-or-nothing holds — nothing applied, the in-root file is
  *     byte-identical to before, no leak.
  *   - PP-08 — stand transcript: a model round that calls fs_edit with an
  *     escaping path observes the explicit structured denial JSON as tool
  *     feedback in the NEXT request (model-correctable, loop stays alive).
  *
  * All checks run against the REAL workspace tools with a deterministic
  * scripted backend — no network, no spend.
  */
object ProtectedPathSpec extends ZIOSpecDefault:

  private val a = AttemptId("pp-att")

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

  private def lims: BudgetLimits =
    BudgetLimits.make(maxAttempts = 16).fold(e => throw e, identity)

  private def sha256Hex(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(UTF_8))
      .map("%02x" format _)
      .mkString

  private def withWorkspace[R, E, A](
      f: Path => ZIO[R, E, A]
  ): ZIO[R & Scope, E, A] =
    ZIO
      .acquireReleaseWith(
        ZIO.attempt(Files.createTempDirectory("raider-pp-test")).orDie
      )(dir => ZIO.attempt(deleteRecursive(dir)).catchAll(_ => ZIO.unit)) {
        dir => f(dir)
      }

  private def deleteRecursive(p: Path): Unit =
    if Files.isDirectory(p) then
      Files.list(p).iterator().asScala.foreach(deleteRecursive)
    Files.deleteIfExists(p)
    ()

  private def invoke(
      reg: ToolRegistry,
      name: String,
      args: String
  ): ZIO[Scope, RaiderError, String] =
    reg.lookup(name) match
      case None =>
        ZIO.fail(RaiderError.ToolFailed(s"TOOL MISSING FROM REGISTRY: $name"))
      case Some(t) => t.invoke(args)

  private def editArgs(path: String, content: String): String =
    s"""{"path":${path.toJson},"content":${content.toJson},"expected_sha256":""}"""

  private def write(dir: Path, name: String, content: String): String =
    Files.writeString(dir.resolve(name), content, UTF_8)
    sha256Hex(content)

  private def read(dir: Path, name: String): String =
    if Files.exists(dir.resolve(name)) then
      Files.readString(dir.resolve(name), UTF_8)
    else "<absent>"

  /** A modify-section hunk replacing the single line `from` with `to`. */
  private def oneLinePatch(name: String, from: String, to: String): String =
    s"""diff --git a/$name b/$name
       |index 0000000..1111111 100644
       |--- a/$name
       |+++ b/$name
       |@@ -1,1 +1,1 @@
       |-$from
       |+$to
       |""".stripMargin

  private def multiArgs(diff: String, shas: Map[String, String]): String =
    val shaJson = shas
      .map((k, v) => s"""${k.toJson}:${v.toJson}""")
      .mkString("{", ",", "}")
    s"""{"diff":${diff.toJson},"expected_sha256":$shaJson}"""

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("protected-path write policy (RAI-259)")(
      test(
        "PP-01: fs_edit create outside the root is denied typed with NOTHING written inside or outside"
      ) {
        withWorkspace { dir =>
          val outside = dir.getParent.resolve("pp01-outside.txt")
          for
            ws <- CodingToolset.make(dir.toString)
            reg = ws.registry.fold(e => throw e, identity)
            out <- ZIO.scoped(
              invoke(reg, "fs_edit", editArgs("../pp01-outside.txt", "pwned"))
            )
          yield
            val outsideLeak = Files.exists(outside)
            val insideLeak =
              Files.exists(dir.resolve("pp01-outside.txt"))
            assertTrue(
              out.contains("\"error\":\"RA-TOOLDENY\""),
              out.contains("escapes workspace"),
              !outsideLeak,
              !insideLeak
            )
        }
      },
      test(
        "PP-02: fs_edit create inside the root still lands (deny-by-default must not break the ordinary workflow)"
      ) {
        withWorkspace { dir =>
          for
            ws <- CodingToolset.make(dir.toString)
            reg = ws.registry.fold(e => throw e, identity)
            out <- ZIO.scoped(
              // flat path: fs_edit does not create parent directories (that
              // is pre-existing EditTool semantics, not this card's scope)
              invoke(reg, "fs_edit", editArgs("ok.txt", "fine\n"))
            )
          yield
            val onDisk = read(dir, "ok.txt")
            assertTrue(
              out.contains("\"action\":\"created\""),
              out.contains("\"path\":\"ok.txt\""),
              onDisk == "fine\n"
            )
        }
      },
      test(
        "PP-03: create through a symlinked directory pointing OUTSIDE the root is denied, outside dir untouched (the RAI-259 fix)"
      ) {
        withWorkspace { dir =>
          val outside = Files.createTempDirectory("raider-pp03-outside")
          Files.createSymbolicLink(dir.resolve("link"), outside)
          for
            ws <- CodingToolset.make(dir.toString)
            reg = ws.registry.fold(e => throw e, identity)
            out <- ZIO.scoped(
              invoke(reg, "fs_edit", editArgs("link/new.txt", "pwned"))
            )
          yield
            // EAGER capture (assertTrue thunks run after tmp-dir cleanup)
            val outsideEntries =
              Files.list(outside).iterator().asScala.toList.map(_.toString)
            val insideLeak = Files.exists(dir.resolve("new.txt"))
            assertTrue(
              out.contains("\"error\":\"RA-TOOLDENY\""),
              out.contains("symlink escapes workspace"),
              outsideEntries.isEmpty,
              !insideLeak
            )
        }
      },
      test(
        "PP-04: an absolute target is refused before any resolution side effect"
      ) {
        withWorkspace { dir =>
          val absTarget =
            dir.getParent.resolve("pp04-abs-escape.txt").toString
          for
            ws <- CodingToolset.make(dir.toString)
            reg = ws.registry.fold(e => throw e, identity)
            out <- ZIO.scoped(
              invoke(reg, "fs_edit", editArgs(absTarget, "pwned"))
            )
          yield
            val leak = Files.exists(java.nio.file.Paths.get(absTarget))
            assertTrue(
              out.contains("\"error\":\"RA-TOOLDENY\""),
              out.contains("escapes workspace"),
              !leak
            )
        }
      },
      test(
        "PP-05: fs_patch on an escaping path is denied typed, patch target untouched"
      ) {
        withWorkspace { dir =>
          val sha = write(dir, "src.txt", "line-1\n")
          for
            ws <- CodingToolset.make(dir.toString)
            reg = ws.registry.fold(e => throw e, identity)
            out <- ZIO.scoped(
              invoke(
                reg,
                "fs_patch",
                s"""{"path":"../pp05-target.txt","diff":${oneLinePatch(
                    "../pp05-target.txt",
                    "x",
                    "y"
                  ).toJson},"expected_sha256":${sha.toJson}}"""
              )
            )
          yield
            val srcAfter = read(dir, "src.txt")
            val noTarget =
              !Files.exists(dir.getParent.resolve("pp05-target.txt"))
            assertTrue(
              out.contains("\"error\":\"RA-TOOLDENY\""),
              out.contains("escapes workspace"),
              srcAfter == "line-1\n",
              noTarget
            )
        }
      },
      test(
        "PP-06: fs_delete on an escaping path is denied typed, the outside victim file survives"
      ) {
        withWorkspace { dir =>
          val victim = dir.getParent.resolve("pp06-victim.txt")
          Files.writeString(victim, "victim\n", UTF_8)
          for
            ws <- CodingToolset.make(dir.toString)
            reg = ws.registry.fold(e => throw e, identity)
            out <- ZIO.scoped(
              invoke(
                reg,
                "fs_delete",
                s"""{"path":"../pp06-victim.txt","expected_sha256":${sha256Hex(
                    "victim\n"
                  ).toJson}}"""
              )
            )
          yield
            val victimStays = Files.exists(victim)
            assertTrue(
              out.contains("\"error\":\"RA-TOOLDENY\""),
              out.contains("escapes workspace"),
              victimStays
            )
        }
      },
      test(
        "PP-07: fs_patch_multi with one in-root and one escaping section applies NOTHING (all-or-nothing), no leak, in-root file byte-identical"
      ) {
        withWorkspace { dir =>
          val shaA = write(dir, "A.txt", "alpha-1\n")
          val diff =
            oneLinePatch("A.txt", "alpha-1", "alpha-1-edited") +
              oneLinePatch("../pp07-escape.txt", "secret", "pwned")
          val shas = Map(
            "A.txt" -> shaA,
            "../pp07-escape.txt" -> sha256Hex("secret\n")
          )
          for
            ws <- CodingToolset.make(dir.toString)
            reg = ws.registry.fold(e => throw e, identity)
            out <- ZIO.scoped(
              invoke(reg, "fs_patch_multi", multiArgs(diff, shas))
            )
          yield
            // EAGER capture (assertTrue thunks run after tmp-dir cleanup)
            val aAfter = read(dir, "A.txt")
            val leak = Files.exists(dir.getParent.resolve("pp07-escape.txt"))
            assertTrue(
              out.contains("\"applied\":[]"),
              out.contains("\"rolledBack\":true"),
              out.contains("\"file\":\"../pp07-escape.txt\""),
              out.contains("RA-TOOLDENY"),
              aAfter == "alpha-1\n",
              !leak
            )
        }
      },
      test(
        "PP-08: stand transcript — the escaping fs_edit call comes back to the model as explicit RA-TOOLDENY JSON tool feedback and the loop continues"
      ) {
        withWorkspace { dir =>
          for
            ws <- CodingToolset.make(dir.toString)
            registry = ws.registry.fold(e => throw e, identity)
            backend = new TurnBackend(
              Vector(
                toolRound("p1", "fs_edit", editArgs("../pp08-escape.txt", "x")),
                textTurn("understood, staying inside the root")
              )
            )
            adm <- Admission.make(Right(lims))
            loop = AgentLoop(backend, registry, "m", adm, AgentLoop.newRoot())
            answer <- loop.runText(
              List(RequestMessage("user", "try the edit")),
              lims,
              maxRounds = 8
            )
          yield
            val toolMsgs =
              backend.requests(1).messages.filter(_.role == "tool")
            // EAGER capture (assertTrue thunks run after tmp-dir cleanup)
            val leak = Files.exists(dir.getParent.resolve("pp08-escape.txt"))
            assertTrue(
              answer == "understood, staying inside the root",
              backend.requests.size == 2,
              toolMsgs.exists(_.content.contains("\"error\":\"RA-TOOLDENY\"")),
              toolMsgs.exists(_.content.contains("escapes workspace")),
              !leak
            )
        }
      }
    )
