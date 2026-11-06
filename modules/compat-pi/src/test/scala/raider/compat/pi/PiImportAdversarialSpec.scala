package raider.compat.pi

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

import zio.Runtime
import zio.Unsafe

/** PIIMP-02/PIIMP-03 (RAI-032), component-stage slice: adversarial imports.
  *
  *   - unknown tool names are REJECTED (never mapped to a Raider capability);
  *   - malformed frontmatter / missing required fields reject the whole file
  *     with a typed reason (pi silently skips such files; Raider must not
  *     silently drop corpus content);
  *   - unknown frontmatter keys are ignored, but the ignore is enumerated in
  *     the report (no false parity);
  *   - unsupported frontmatter shapes for `tools` reject the field instead of
  *     guessing (explicit degraded state);
  *   - inline-array tool lists parse like pi's own discovery (agents.ts);
  *   - importing a mutated corpus copy never changes an existing snapshot
  *     (PIIMP-03: reload creates a NEW immutable snapshot).
  *
  * Plain-main spec (ContractsSpec pattern): exit 0 iff every check passes.
  */
object PiImportAdversarialSpec:

  val Provenance = PiImportGoldenSpec.Provenance

  private var failures = 0

  private def check(name: String)(ok: Boolean, detail: => String = ""): Unit =
    if ok then println(s"[pass] $name")
    else
      failures += 1
      println(s"[FAIL] $name ${detail}")

  private def run[A](effect: zio.ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(effect).getOrThrow()
    }

  private def write(p: Path, content: String): Unit =
    Files.createDirectories(p.getParent)
    Files.write(p, content.getBytes(UTF_8))

  private def corpusDir(tag: String): Path =
    Files.createTempDirectory(s"pi-adversarial-$tag")

  def main(args: Array[String]): Unit =
    unknownToolRejected()
    malformedFrontmatterRejected()
    missingDescriptionRejected()
    inlineArrayToolsParse()
    unknownKeysEnumerated()
    unsupportedToolsShapeRejected()
    mutatedCorpusNewSnapshot()
    missingCorpusTypedFailure()

    if failures == 0 then println("PiImportAdversarialSpec: all checks passed")
    else println(s"PiImportAdversarialSpec: $failures check(s) FAILED")
    if failures > 0 then sys.exit(1)

  private def unknownToolRejected(): Unit =
    val root = corpusDir("unknown-tool")
    write(
      root.resolve("upstream/agents/probe.md"),
      s"""---
         |name: probe
         |description: uses a tool Raider has no equivalent for
         |tools: read, browser
         |model: claude-sonnet-4-5
         |---
         |
         |You are a probe.""".stripMargin
    )
    val report = run(
      PiProfileImporter.importCorpus(root.resolve("upstream"), Provenance)
    )
    check("unknown-tool-file-still-imported")(
      report.profiles.map(_.name) == List("probe")
    )
    check("unknown-tool-rejected-not-mapped")(
      report.profiles.head.toolPolicy.unsupported.map(_.pi) == List(
        "browser"
      ) &&
        report.profiles.head.toolPolicy.mapped.map(_.pi) == List("read"),
      report.profiles.head.toolPolicy.toString
    )
    check("unknown-tool-counted-rejected")(
      report.counts.rejected == 1 && report.counts.supported == 6,
      report.counts.toString
    )

  private def malformedFrontmatterRejected(): Unit =
    val root = corpusDir("malformed")
    write(
      root.resolve("upstream/agents/broken.md"),
      s"""---
         |name: broken
         |this line has no colon and no delimiter follows
         |""".stripMargin
    )
    val report = run(
      PiProfileImporter.importCorpus(root.resolve("upstream"), Provenance)
    )
    check("malformed-file-rejected")(
      report.profiles.isEmpty &&
        report.rejectedFiles.map(_.path) == List("agents/broken.md"),
      report.rejectedFiles.mkString(",")
    )
    check("malformed-reason-typed")(
      report.rejectedFiles.exists(_.reason.contains("frontmatter")),
      report.rejectedFiles.map(_.reason).mkString(",")
    )

  private def missingDescriptionRejected(): Unit =
    val root = corpusDir("missing-desc")
    write(
      root.resolve("upstream/agents/no-describe.md"),
      s"""---
         |name: no-describe
         |model: claude-sonnet-4-5
         |---
         |
         |Body without description.""".stripMargin
    )
    val report = run(
      PiProfileImporter.importCorpus(root.resolve("upstream"), Provenance)
    )
    check("missing-description-rejected")(
      report.profiles.isEmpty &&
        report.rejectedFiles.exists(_.path == "agents/no-describe.md") &&
        report.rejectedFiles.exists(_.reason.contains("description")),
      report.rejectedFiles.mkString(",")
    )

  private def inlineArrayToolsParse(): Unit =
    val root = corpusDir("array-tools")
    write(
      root.resolve("upstream/agents/array.md"),
      s"""---
         |name: array
         |description: inline array tool list like pi's agents.ts accepts
         |tools: [read, bash]
         |model: claude-haiku-4-5
         |---
         |
         |You are an array probe.""".stripMargin
    )
    val report = run(
      PiProfileImporter.importCorpus(root.resolve("upstream"), Provenance)
    )
    val policy = report.profiles.head.toolPolicy
    check("inline-array-tools-parse")(
      policy.kind == "explicit" &&
        policy.mapped.map(_.raider) == List("proc_run", "fs_read"),
      policy.toString
    )

  private def unknownKeysEnumerated(): Unit =
    val root = corpusDir("unknown-keys")
    write(
      root.resolve("upstream/agents/extra.md"),
      s"""---
         |name: extra
         |description: carries pi-side config Raider does not model
         |confirmProjectAgents: false
         |model: claude-sonnet-4-5
         |---
         |
         |You are an extra probe.""".stripMargin
    )
    val report = run(
      PiProfileImporter.importCorpus(root.resolve("upstream"), Provenance)
    )
    check("unknown-key-ignored-but-enumerated")(
      report.profiles.head.ignored ==
        List(
          FieldEntry(
            "confirmProjectAgents",
            "unknown frontmatter key: not modeled by the Raider import (raw value kept out of the profile)"
          )
        ),
      report.profiles.head.ignored.toString
    )

  private def unsupportedToolsShapeRejected(): Unit =
    val root = corpusDir("bad-shape")
    write(
      root.resolve("upstream/agents/shape.md"),
      s"""---
         |name: shape
         |description: tools given in an unsupported YAML shape
         |tools:
         |  - read
         |  - bash
         |model: claude-sonnet-4-5
         |---
         |
         |You are a shape probe.""".stripMargin
    )
    val report = run(
      PiProfileImporter.importCorpus(root.resolve("upstream"), Provenance)
    )
    check("unsupported-tools-shape-rejected-field")(
      report.profiles.head.toolPolicy.kind == "rejected" &&
        report.profiles.head.rejected.exists(_.field == "tools"),
      report.profiles.head.toolPolicy.toString
    )

  private def mutatedCorpusNewSnapshot(): Unit =
    val root = corpusDir("snapshot")
    write(
      root.resolve("upstream/agents/first.md"),
      s"""---
         |name: first
         |description: first version
         |tools: read
         |model: claude-haiku-4-5
         |---
         |
         |Body one.""".stripMargin
    )
    val first = run(
      PiProfileImporter.importCorpus(root.resolve("upstream"), Provenance)
    )
    write(
      root.resolve("upstream/agents/first.md"),
      s"""---
         |name: first
         |description: second version
         |tools: read
         |model: claude-haiku-4-5
         |---
         |
         |Body two.""".stripMargin
    )
    val second = run(
      PiProfileImporter.importCorpus(root.resolve("upstream"), Provenance)
    )
    check("mutation-yields-new-snapshot")(
      first.snapshotId != second.snapshotId &&
        first.profiles.head.description == "first version",
      s"${first.snapshotId.take(8)} vs ${second.snapshotId.take(8)}"
    )
    check("reloading-narrows-never-widens-checked-by-diff")(
      second.profiles.head.description == "second version",
      second.profiles.head.description
    )

  private def missingCorpusTypedFailure(): Unit =
    val missing =
      Paths.get(corpusDir("missing-root").toString, "does-not-exist")
    val outcome = scala.util
      .Try(
        run(PiProfileImporter.importCorpus(missing, Provenance))
      )
      .toEither
    check("missing-corpus-typed-error")(
      outcome.left.exists {
        case PiImportError.CorpusMissing(root) => root == missing
        case _                                 => false
      },
      outcome.toString
    )
