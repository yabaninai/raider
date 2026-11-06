package raider.tools.files

import raider.core.*
import raider.runtime.admission.Admission
import raider.runtime.budget.BudgetLimits
import raider.runtime.loop.AgentLoop
import raider.tools.CodingToolset
import zio.stream.ZStream
import zio.test.*
import zio.{Scope, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** RAI-259 acceptance: the project-instruction file loader and its surfacing
  * into the coder system prompt.
  *
  *   - INS-01 — an AGENTS.md at the root loads verbatim and `surface` appends a
  *     clearly-marked section to the base prompt.
  *   - INS-02 — no instruction file: `load` is None and `surface` returns the
  *     base prompt UNCHANGED (fail-open; the coder must start without one).
  *   - INS-03 — precedence: the FIRST name in the candidate list wins.
  *   - INS-04 — oversized content is truncated at the hard cap and SAYS SO.
  *   - INS-05 — deny-by-default: an AGENTS.md whose symlink resolves OUTSIDE
  *     the root is ignored; a symlink resolving back INSIDE the root loads.
  *   - INS-06 — stand transcript: with
  *     `systemPrompt = Some(ProjectInstructions.surface(base, root))` the
  *     AgentLoop prepends a `system` message carrying base + instructions, on
  *     EVERY request of the session (observable in the scripted backend's
  *     recorded requests).
  *
  * No network, no spend.
  */
object ProjectInstructionsSpec extends ZIOSpecDefault:

  private val Base = "BASE-PROMPT-MARKER you are a coder"
  private val Instr = "INSTR-MARKER-CONTENT always run sbt test first\n"

  private val a = AttemptId("ins-att")

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

  private def lims: BudgetLimits =
    BudgetLimits.make(maxAttempts = 16).fold(e => throw e, identity)

  private def withRoot[A](
      f: Path => ZIO[Any, Any, A]
  ): ZIO[Any, Any, A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt(Files.createTempDirectory("raider-ins"))
    )(_ => ZIO.unit) { dir => f(dir.toRealPath()) }

  private def writeAgentRules(root: Path, content: String): Unit =
    Files.writeString(root.resolve("AGENTS.md"), content, UTF_8)
    ()

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("project instructions loader (RAI-259)")(
      test(
        "INS-01: AGENTS.md at the root loads verbatim; surface appends a marked section to the base"
      ) {
        withRoot { root =>
          writeAgentRules(root, Instr)
          val loaded = ProjectInstructions.load(root)
          val composed = ProjectInstructions.surface(Base, root)
          assertTrue(
            loaded.map(_.file) == Some("AGENTS.md"),
            loaded.map(_.content) == Some(Instr),
            loaded.map(_.truncated) == Some(false),
            loaded.map(_.bytes) == Some(Instr.getBytes(UTF_8).length.toLong),
            composed.startsWith(Base),
            composed.contains("## Project instructions (from AGENTS.md"),
            composed.contains("INSTR-MARKER-CONTENT")
          )
        }
      },
      test(
        "INS-02: no instruction file — load is None and surface returns the base prompt unchanged"
      ) {
        withRoot { root =>
          val loaded = ProjectInstructions.load(root)
          val composed = ProjectInstructions.surface(Base, root)
          assertTrue(
            loaded.isEmpty,
            composed == Base
          )
        }
      },
      test(
        "INS-03: precedence — the FIRST candidate name in the list wins"
      ) {
        withRoot { root =>
          writeAgentRules(root, "agents-rules\n")
          Files.writeString(root.resolve("RAIDER.md"), "raider-rules\n", UTF_8)
          val agentsFirst =
            ProjectInstructions.load(
              root,
              List("AGENTS.md", "RAIDER.md")
            )
          val raiderFirst =
            ProjectInstructions.load(
              root,
              List("RAIDER.md", "AGENTS.md")
            )
          assertTrue(
            agentsFirst.map(_.content) == Some("agents-rules\n"),
            raiderFirst.map(_.content) == Some("raider-rules\n")
          )
        }
      },
      test(
        "INS-04: oversized instructions are truncated at the hard cap and the section says so"
      ) {
        withRoot { root =>
          val big = "a" * 70000 // ASCII: bytes == chars
          writeAgentRules(root, big)
          val loaded = ProjectInstructions.load(root)
          val composed = ProjectInstructions.surface(Base, root)
          assertTrue(
            loaded.map(_.truncated) == Some(true),
            loaded.map(_.bytes) == Some(ProjectInstructions.MaxBytes.toLong),
            loaded.map(_.content.length) == Some(ProjectInstructions.MaxBytes),
            composed.contains("[instructions truncated at 65536 bytes]")
          )
        }
      },
      test(
        "INS-05: an instruction file whose symlink resolves OUTSIDE the root is ignored; one resolving INSIDE loads"
      ) {
        withRoot { root =>
          val outside = Files.createTempDirectory("raider-ins-outside")
          Files.writeString(
            outside.resolve("AGENTS.md"),
            "outside-rules\n",
            UTF_8
          )
          Files.createSymbolicLink(
            root.resolve("AGENTS.md"),
            outside.resolve("AGENTS.md")
          )
          val escaping = ProjectInstructions.load(root)
          val escapingSurface = ProjectInstructions.surface(Base, root)

          // Re-point the symlink INTO the root: same mechanism, allowed target
          Files.delete(root.resolve("AGENTS.md"))
          Files.writeString(root.resolve("real-notes.md"), Instr, UTF_8)
          Files.createSymbolicLink(
            root.resolve("AGENTS.md"),
            root.resolve("real-notes.md")
          )
          val contained = ProjectInstructions.load(root)
          assertTrue(
            escaping.isEmpty,
            escapingSurface == Base,
            contained.map(_.file) == Some("AGENTS.md"),
            contained.map(_.content) == Some(Instr)
          )
        }
      },
      test(
        "INS-06: stand transcript — the composed prompt reaches the model as the system message on EVERY request"
      ) {
        withRoot { root =>
          Files.writeString(root.resolve("notes.txt"), "n\n", UTF_8)
          writeAgentRules(root, Instr)
          val sys = ProjectInstructions.surface(Base, root)
          for
            ws <- CodingToolset.make(root.toString)
            registry = ws.registry.fold(e => throw e, identity)
            backend = new TurnBackend(
              Vector(
                _ =>
                  Vector[ModelEvent](
                    ModelEvent.Started(a),
                    ModelEvent.ToolCallReady(
                      a,
                      ToolCallId("r1"),
                      "fs_read",
                      """{"path":"notes.txt"}"""
                    ),
                    ModelEvent.Finished(a, "tool_round")
                  ),
                _ =>
                  Vector[ModelEvent](
                    ModelEvent.Started(a),
                    ModelEvent.TextDelta(a, "done"),
                    ModelEvent.Finished(a, "stop")
                  )
              )
            )
            adm <- Admission.make(Right(lims))
            loop = AgentLoop(
              backend,
              registry,
              "m",
              adm,
              AgentLoop.newRoot(),
              systemPrompt = Some(sys)
            )
            answer <- loop.runText(
              List(RequestMessage("user", "read the notes")),
              lims,
              maxRounds = 8
            )
          yield
            val reqs = backend.requests
            assertTrue(
              answer == "done",
              reqs.size == 2,
              reqs.forall(_.messages.head.role == "system"),
              reqs.forall(_.messages.head.content.startsWith(Base)),
              reqs.forall(
                _.messages.head.content.contains("INSTR-MARKER-CONTENT")
              )
            )
        }
      }
    )
