package raider.compat.pi

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

import zio.Unsafe
import zio.Runtime

/** PIIMP-01 (RAI-032): golden importer run over the vendored pi corpus.
  *
  * Pins, against the exact bytes of `vendor/pi-profiles/upstream`:
  *
  *   - provenance: upstream commit, license and copyright, vendored corpus
  *     location, per-file sha256 agreement with `MANIFEST.sha256`;
  *   - profile/tool-policy mapping for scout/planner/reviewer/worker;
  *   - workflow-prompt import with the explicitly ignored chain semantics;
  *   - the exact supported/ignored/rejected counts of the golden corpus;
  *   - byte-identical canonical report JSON
  *     (`contracts/compat/pi/golden/pi-import-golden.json`);
  *   - snapshot stability: same corpus -> identical report/snapshotId; a
  *     mutated corpus copy -> a different snapshotId while the original report
  *     object stays unchanged (library-level PIIMP-03 slice).
  *
  * Plain-main spec (ContractsSpec pattern): exit 0 iff every check passes, so
  * it runs both standalone (`java ...PiImportGoldenSpec`) and under sbt via
  * `Test / runMain` once the module is registered.
  *
  * `--update` regenerates the pinned golden file, then verifies it — a
  * deliberate, reviewable re-pin, never a silent one.
  */
object PiImportGoldenSpec:

  val GoldenFile = Paths.get("contracts/compat/pi/golden/pi-import-golden.json")
  val CorpusRoot = Paths.get("vendor/pi-profiles/upstream")
  val Manifest = Paths.get("vendor/pi-profiles/MANIFEST.sha256")

  val Provenance = PiProvenance(
    upstreamRepo = "https://github.com/earendil-works/pi",
    upstreamPath = "packages/coding-agent/examples/extensions/subagent",
    upstreamCommit = "55a5c6914570e2ae31ca36297654483bbbf46883",
    license = "MIT",
    licenseCopyright = "Copyright (c) 2025 Mario Zechner",
    vendoredCorpus = "vendor/pi-profiles/upstream",
    fetchedOn = "2026-11-05"
  )

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

  private def read(p: Path): String =
    new String(Files.readAllBytes(p), UTF_8)

  /** Parse `sha256sum`-style manifest lines: `<hex>  <relative path>`. */
  private def manifestEntries: Map[String, String] =
    read(Manifest).linesIterator.collect {
      case l if l.trim.nonEmpty =>
        val t = l.trim
        val idx = t.indexOf("  ")
        t.drop(idx + 2).trim -> t.take(idx)
    }.toMap

  def main(args: Array[String]): Unit =
    val update = args.contains("--update")

    // ---- 0. vendored corpus exists and matches the pinned manifest
    check("corpus-root-exists")(
      Files.isDirectory(CorpusRoot),
      s"$CorpusRoot missing"
    )
    if Files.isDirectory(CorpusRoot) then
      val manifest = manifestEntries
      check("manifest-nonempty")(
        manifest.nonEmpty,
        "MANIFEST.sha256 has no entries"
      )
      val mismatches = manifest.toList.flatMap { (rel, hex) =>
        val p = Paths.get("vendor/pi-profiles").resolve(rel)
        if !Files.exists(p) then Some(s"$rel: missing")
        else
          val actual = Hashing.sha256Hex(Files.readAllBytes(p))
          if actual == hex then None else Some(s"$rel: $actual != $hex")
      }
      check("manifest-sha256-agreement")(
        mismatches.isEmpty,
        mismatches.mkString("; ")
      )

    // ---- 1. import the vendored corpus
    val report = run(PiProfileImporter.importCorpus(CorpusRoot, Provenance))

    // ---- 2. provenance recorded in the report
    check("provenance-commit")(
      report.provenance.upstreamCommit == Provenance.upstreamCommit,
      report.provenance.upstreamCommit
    )
    check("provenance-repo")(
      report.provenance.upstreamRepo == Provenance.upstreamRepo
    )
    check("provenance-license")(
      report.provenance.license == "MIT" && report.provenance.licenseCopyright ==
        "Copyright (c) 2025 Mario Zechner",
      s"${report.provenance.license} / ${report.provenance.licenseCopyright}"
    )
    check("provenance-corpus-files-pinned")(
      report.provenance.corpusFiles.nonEmpty &&
        report.provenance.corpusFiles.map(_.path) ==
        report.provenance.corpusFiles.map(_.path).sorted,
      "corpus files must be present and path-sorted"
    )
    val manifestMap =
      if Files.exists(Manifest) then manifestEntries else Map.empty
    val hashAgree = report.provenance.corpusFiles.forall { f =>
      manifestMap.get(s"upstream/${f.path}").contains(f.sha256)
    }
    check("report-hashes-match-manifest")(
      hashAgree,
      report.provenance.corpusFiles
        .map(f => s"upstream/${f.path}")
        .mkString(",")
    )
    check("snapshot-id-stable-shape")(
      report.snapshotId.length == 64 &&
        report.snapshotId.forall(c => c.isDigit || (c >= 'a' && c <= 'f')),
      report.snapshotId
    )

    // ---- 3. profiles + tool policies
    val names = report.profiles.map(_.name)
    check("profile-names")(
      names == List("planner", "reviewer", "scout", "worker"),
      names.mkString(",")
    )

    def mappedRaider(profileName: String): List[String] =
      report.profiles
        .find(_.name == profileName)
        .toList
        .flatMap(_.toolPolicy.mapped.map(_.raider))

    check("scout-tool-policy")(
      mappedRaider("scout") ==
        List("proc_run", "fs_tree", "fs_search", "fs_tree", "fs_read"),
      mappedRaider("scout").mkString(",")
    )
    check("planner-tool-policy-no-shell")(
      mappedRaider("planner") == List(
        "fs_tree",
        "fs_search",
        "fs_tree",
        "fs_read"
      ),
      mappedRaider("planner").mkString(",")
    )
    check("reviewer-tool-policy")(
      mappedRaider("reviewer") ==
        List("proc_run", "fs_tree", "fs_search", "fs_tree", "fs_read"),
      mappedRaider("reviewer").mkString(",")
    )
    check("worker-mapped-defaults")(
      mappedRaider("worker") == List("proc_run", "fs_edit", "fs_read"),
      mappedRaider("worker").mkString(",")
    )
    val worker = report.profiles.find(_.name == "worker")
    check("worker-pi-default-policy")(
      worker.exists(_.toolPolicy.kind == "piDefault"),
      worker.map(_.toolPolicy.kind).getOrElse("<no worker>")
    )
    check("worker-write-parity-gap-rejected")(
      worker.exists(_.toolPolicy.unsupported.map(_.pi) == List("write")),
      worker.map(_.toolPolicy.unsupported.toString).getOrElse("<no worker>")
    )
    check("bash-mapping-degraded-flagged")(
      report.profiles.exists(p =>
        p.toolPolicy.mapped.exists(m =>
          m.pi == "bash" && m.raider == "proc_run" && m.degraded
        )
      ),
      "no degraded bash->proc_run mapping found"
    )
    check("requested-models-recorded")(
      report.profiles.forall(p =>
        p.requestedModel == "claude-haiku-4-5" || p.requestedModel ==
          "claude-sonnet-4-5"
      ),
      report.profiles.map(p => s"${p.name}=${p.requestedModel}").mkString(",")
    )
    check("system-prompts-imported")(
      report.profiles.forall(p => p.systemPrompt.contains("You are")),
      "agent bodies missing"
    )

    // ---- 4. workflow prompts with explicit ignored chain semantics
    val promptNames = report.workflowPrompts.map(_.name)
    check("workflow-prompt-names")(
      promptNames == List(
        "implement",
        "implement-and-review",
        "scout-and-plan"
      ),
      promptNames.mkString(",")
    )
    check(
      "workflow-chain-semantics-ignored-not-silent"
    )(
      report.workflowPrompts.forall(w =>
        w.ignored.map(_.field) ==
          List("$@ argument substitution", "{previous} chain placeholder")
      ),
      report.workflowPrompts
        .map(_.ignored.map(_.field).mkString("+"))
        .mkString(",")
    )

    // ---- 5. reference files + no rejected files in the golden corpus
    check("reference-files")(
      report.referenceFiles
        .map(_.path) == List("LICENSE", "README.md", "agents.ts"),
      report.referenceFiles.map(_.path).mkString(",")
    )
    check("no-rejected-files")(
      report.rejectedFiles.isEmpty,
      report.rejectedFiles.mkString(",")
    )

    // ---- 6. exact honesty counts for the golden corpus
    check("counts")(
      report.counts == Counts(supported = 43, ignored = 9, rejected = 1),
      report.counts.toString
    )

    // ---- 7. parity gaps + degraded semantics are explicit
    check("parity-gaps-explicit")(
      report.parityGaps.exists(_.scope == "profile worker") &&
        report.parityGaps.exists(_.scope == "piDefault extension tools"),
      report.parityGaps.map(_.scope).mkString(",")
    )
    check("degraded-listed")(
      report.degraded.exists(_.scope == "tool bash"),
      report.degraded.map(_.scope).mkString(",")
    )

    // ---- 8. golden output pin
    val canonical = report.renderCanonicalJson
    if update then
      Files.createDirectories(GoldenFile.getParent)
      Files.write(GoldenFile, canonical.getBytes(UTF_8))
      println(s"[update] golden rewritten: $GoldenFile")

    check("golden-file-exists")(
      Files.exists(GoldenFile),
      s"$GoldenFile missing"
    )
    if Files.exists(GoldenFile) then
      val pinned = read(GoldenFile)
      check("golden-byte-identical")(
        pinned == canonical,
        s"pinned ${pinned.length} chars vs rendered ${canonical.length} chars"
      )

    // ---- 9. snapshot stability (library-level PIIMP-03 slice)
    val again = run(PiProfileImporter.importCorpus(CorpusRoot, Provenance))
    check("reimport-identical")(
      again == report && again.snapshotId == report.snapshotId,
      "re-importing the same corpus produced a different snapshot"
    )
    val mutated = run(
      PiProfileImporter.importCorpus(
        mutateCorpusCopy().resolve("upstream"),
        Provenance
      )
    )
    check("mutated-copy-new-snapshot")(
      mutated.snapshotId != report.snapshotId && report.profiles.nonEmpty,
      "mutated corpus copy did not produce a distinct snapshotId"
    )

    if failures == 0 then println("PiImportGoldenSpec: all checks passed")
    else println(s"PiImportGoldenSpec: $failures check(s) FAILED")
    if failures > 0 then sys.exit(1)

  /** Copy the vendored corpus into a temp dir and mutate one prompt body. */
  private def mutateCorpusCopy(): Path =
    val tmp = Files.createTempDirectory("pi-import-snapshot")
    val src = CorpusRoot
    val dst = tmp.resolve("upstream")
    copyDir(src, dst)
    val target = dst.resolve("prompts/implement.md")
    Files.write(
      target,
      (read(target) + "\n<!-- mutated for snapshot test -->\n").getBytes(UTF_8)
    )
    tmp

  private def copyDir(src: Path, dst: Path): Unit =
    Files.createDirectories(dst)
    val stream = Files.list(src)
    try
      import scala.jdk.CollectionConverters.*
      stream.iterator().asScala.foreach { p =>
        if Files.isDirectory(p) then copyDir(p, dst.resolve(p.getFileName))
        else Files.copy(p, dst.resolve(p.getFileName))
      }
    finally stream.close()
