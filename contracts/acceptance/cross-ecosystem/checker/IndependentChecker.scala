// RAI-205 independent checker for the cross-ecosystem acceptance fixtures.
//
// Validates the manifests produced by the LLM4S and LangChain4j consumers
// against the committed goldens (expected/golden-checks.json), recomputes
// artifact digests in the adapter's invocation outputs, checks the workspace
// marker, and verifies that the scripted stand's request logs carry only the
// redacted auth marker (never the fixture key value).
//
// Deliberately independent: this checker's classpath needs only the Scala
// standard library and ujson/upickle (the LLM4S project's own JSON stack,
// used here as a plain library, without llm4s-core). It shares no framework
// code with either consumer, so a coordinated bug cannot hide from it.
//
// Exit 0 iff every section passed.

package xe.checker

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.stream.Collectors
import scala.jdk.CollectionConverters.*
import upickle.default.*

final case class Finding(section: String, ok: Boolean, detail: String)

object IndependentChecker:

  private def readText(p: Path): String = Files.readString(p, StandardCharsets.UTF_8)

  private def listDirs(p: Path): Vector[Path] =
    if !Files.isDirectory(p) then Vector.empty
    else
      val s = Files.list(p)
      try
        s.collect(Collectors.toList[Path]).asScala.toVector.sortBy(_.toString)
      finally s.close()

  private def listFiles(p: Path): Vector[Path] = listDirs(p).filter(Files.isRegularFile(_))

  private def sha256(p: Path): (String, Long) =
    val bytes = Files.readAllBytes(p)
    val hex = MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x" format _).mkString
    (hex, bytes.length.toLong)

  private def isFatalGolden(exit: Int): Boolean = exit == 20

  def main(argv: Array[String]): Unit =
    val args = argv.grouped(2).collect { case Array(k, v) if k.startsWith("--") => k -> v }.toMap
    val findings = scala.collection.mutable.ArrayBuffer.empty[Finding]
    def add(section: String)(ok: Boolean, detail: String): Unit =
      findings += Finding(section, ok, detail)

    for key <- Seq("--goldens", "--manifests", "--adapter-jar", "--out-root", "--ws-root", "--stand-logs", "--report") do
      if !args.contains(key) then
        System.err.println(s"xe-checker: missing $key")
        sys.exit(2)

    val goldens = ujson.read(readText(Paths.get(args("--goldens"))))
    val goldenAdapterSha = goldens("adapterJarSha256").str
    val standKey = goldens("standKey").str

    // ---- section 1: manifests against goldens ------------------------------
    // The scenario matrix is partitioned across several manifests per
    // ecosystem (mock vs stand A vs stand B). Goldens are therefore matched
    // against the UNION of manifests of the same ecosystem: every golden
    // scenario must match exactly one manifest entry, and every manifest
    // entry must be consumed exactly once.
    val consumed = scala.collection.mutable.Set.empty[EntryRef] // manifest scenario entries already matched
    val manifestPaths = args("--manifests").split(',').toVector.map(Paths.get(_))

    case class ManifestView(path: Path, eco: String, entries: Vector[EntryRef])

    // identity wrapper: ujson.Value uses structural equality, which would let
    // one ecosystem's entry consume an identical-looking entry of another
    case class EntryRef(path: Path, idx: Int, value: ujson.Value)

    val views = manifestPaths.map { mp =>
      val m = ujson.read(readText(mp))
      ManifestView(mp, m("ecosystem").str, m("scenarios").arr.toVector.zipWithIndex.map((v, i) => EntryRef(mp, i, v)))
    }

    for view <- views do
      val m = ujson.read(readText(view.path))
      if !goldens("ecosystems").obj.contains(view.eco) then
        add("manifests")(ok = false, s"${view.path}: unknown ecosystem ${view.eco}")
      else
        val g = goldens("ecosystems")(view.eco)
        val fwOk = m("pinned")("framework").str == g("framework").str
        add("manifests")(fwOk, s"${view.path}: pinned framework ${m("pinned")("framework").str} vs golden ${g("framework").str}")
        val jarSha = sha256(Paths.get(args("--adapter-jar")))._1
        val shaOk = m("pinned")("adapterJarSha256").str == goldenAdapterSha && jarSha == goldenAdapterSha
        add("manifests")(shaOk, s"${view.path}: adapterJarSha256 manifest=${m("pinned")("adapterJarSha256").str} recomputed=$jarSha golden=$goldenAdapterSha")

    for (eco, g) <- goldens("ecosystems").obj.toVector do
      val ecoManifests = views.filter(_.eco == eco)
      if ecoManifests.isEmpty then
        add("manifests")(ok = false, s"ecosystem $eco: no manifest supplied")
      else
        for golden <- g("scenarios").arr do
          val name = golden("manifestName").str
          val anchor = golden("anchor").str
          val matches = ecoManifests.flatMap { v =>
            v.entries.filter { e =>
              e.value("name").str == name &&
                e.value("checks").arr.map(_("name").str).contains(anchor) &&
                !consumed(e)
            }.map(e => (v.path, e))
          }
          if matches.size != 1 then
            add("manifests")(ok = false, s"$eco: golden ${golden("key").str} matched ${matches.size} manifest entries (need exactly 1)")
          else
            val (mp, ref) = matches.head
            val e = ref.value
            consumed += ref
            val goldenChecks = golden("checks").arr.map(_.str).toVector
            val actualChecks = e("checks").arr.map(_("name").str).toVector
            val exitGolden = golden("adapterExitCode").num.toInt
            val exitActual = e("adapterExitCode") match
              case ujson.Null => -999
              case v          => v.num.toInt
            val allPass = e("checks").arr.forall(_("pass").bool)
            add("manifests")(e("ok").bool && allPass, s"$eco/$mp: ${golden("key").str} ok=${e("ok").bool} allChecksPass=$allPass")
            add("manifests")(exitActual == exitGolden, s"$eco/$mp: ${golden("key").str} adapterExitCode actual=$exitActual golden=$exitGolden")
            add("manifests")(actualChecks == goldenChecks,
              s"$eco/$mp: ${golden("key").str} checks match golden exactly: " +
                (if actualChecks == goldenChecks then "yes" else s"actual=$actualChecks golden=$goldenChecks"))
        // every supplied manifest entry must correspond to a golden scenario
        val allEcoEntries = ecoManifests.flatMap(_.entries)
        val leftover = allEcoEntries.filterNot(consumed).map(_.value)
        add("manifests")(leftover.isEmpty,
          s"$eco: unconsumed manifest entries: ${if leftover.isEmpty then "none" else leftover.map(_("name").str).mkString(",")}")

    // ---- section 2: adapter invocation artifacts ----------------------------
    val ecoSuffixes = goldens("ecosystems").obj.keys.toVector
    for eco <- ecoSuffixes do
      val g = goldens("ecosystems")(eco)
      val groups = g("scenarios").arr.map(_("manifestName").str).distinct
      for name <- groups do
        val groupGoldens = g("scenarios").arr.filter(_("manifestName").str == name)
        val outDir = Paths.get(args("--out-root")).resolve(s"$name-$eco")
        val invocations = listDirs(outDir).filter(p => Files.isDirectory(p) && p.getFileName.toString.startsWith("invocation-"))
        val full = invocations.filter(p => Files.exists(p.resolve("artifacts.json")))
        val fatalOnly = invocations.filterNot(p => Files.exists(p.resolve("artifacts.json")))
        val expectedFull = groupGoldens.count(gg => !isFatalGolden(gg("adapterExitCode").num.toInt))
        val expectedFatal = groupGoldens.count(gg => isFatalGolden(gg("adapterExitCode").num.toInt))
        add("artifacts")(full.size == expectedFull,
          s"$eco/$name: full invocation dirs ${full.size} expected $expectedFull")
        add("artifacts")(fatalOnly.size == expectedFatal,
          s"$eco/$name: fatal-only invocation dirs ${fatalOnly.size} expected $expectedFatal")
        val groupExit = groupGoldens.map(_("adapterExitCode").num.toInt).filterNot(_ == 20).distinct
        for dir <- full do
          val arts = ujson.read(readText(dir.resolve("artifacts.json")))
          var digestsOk = true
          val problems = scala.collection.mutable.ArrayBuffer.empty[String]
          for f <- arts("files").arr do
            val file = dir.resolve(f("name").str)
            if !Files.exists(file) then
              digestsOk = false
              problems += s"missing ${f("name").str}"
            else
              val (hex, size) = sha256(file)
              if hex != f("sha256").str then
                digestsOk = false
                problems += s"digest mismatch ${f("name").str}"
              if size != f("sizeBytes").num.toLong then
                digestsOk = false
                problems += s"size mismatch ${f("name").str}"
          // every regular file except artifacts.json must be listed
          val listed = arts("files").arr.map(_("name").str).toSet
          val onDisk = listFiles(dir).map(p => p.getFileName.toString).toSet - "artifacts.json"
          if onDisk != listed then
            digestsOk = false
            problems += s"file set differs: onDisk=$onDisk listed=$listed"
          for req <- Seq("run.json", "result.json", "events.jsonl", "summary.md") do
            if !listed.contains(req) then
              digestsOk = false
              problems += s"required artifact $req not listed"
          add("artifacts")(digestsOk, s"$eco/$name/${dir.getFileName}: artifact digests+sizes recomputed ${if digestsOk then "OK" else problems.mkString("; ")}")
          if Files.exists(dir.resolve("run.json")) then
            val run = ujson.read(readText(dir.resolve("run.json")))
            val runExit = run("exitCode").num.toInt
            val runStatus = run("status").str
            val expectExit = if groupExit.size == 1 then groupExit.head else -1000
            val expectStatus = if expectExit == 0 then "Succeeded" else "Cancelled"
            add("artifacts")(runExit == expectExit && runStatus == expectStatus,
              s"$eco/$name/${dir.getFileName}: run.json exit=$runExit status=$runStatus expected exit=$expectExit status=$expectStatus")
          val events = dir.resolve("events.jsonl")
          val evOk = Files.exists(events) && {
            val lines = Files.readAllLines(events, StandardCharsets.UTF_8).asScala
            lines.nonEmpty && lines.forall(l => Try(ujson.read(l)).isSuccess)
          }
          add("artifacts")(evOk, s"$eco/$name/${dir.getFileName}: events.jsonl non-empty and JSONL-parseable=$evOk")
        for dir <- fatalOnly do
          val events = dir.resolve("events.jsonl")
          val evOk = Files.exists(events) && {
            val lines = Files.readAllLines(events, StandardCharsets.UTF_8).asScala
            // fatal runs may legitimately export zero events (the adapter dies
            // before any recorded frame); non-empty lines must still parse
            lines.filter(_.trim.nonEmpty).forall(l => Try(ujson.read(l)).isSuccess)
          }
          add("artifacts")(evOk, s"$eco/$name/${dir.getFileName}: fatal run has parseable events.jsonl=$evOk")

    // ---- section 3: workspace marker ----------------------------------------
    val marker = Paths.get(args("--ws-root")).resolve("tool-task").resolve("marker.txt")
    add("workspace")(Files.isRegularFile(marker) && Files.size(marker) >= 0,
      s"tool-task marker exists: $marker present=${Files.exists(marker)}")

    // ---- section 4: stand logs are redacted ---------------------------------
    // Not every stand necessarily serves a request (the delayed stand only
    // widens the cancellation window; the consumer may cancel before the
    // first model call), so an empty log is legitimate. Positive evidence is
    // required ACROSS the log set: at least one line with auth present, and
    // the fixture key must appear in no log at all.
    var authPresentLines = 0
    for logPath <- args("--stand-logs").split(',').toVector.map(Paths.get(_)) do
      if !Files.exists(logPath) then
        add("stand-logs")(ok = false, s"$logPath: missing")
      else
        val raw = readText(logPath)
        val lines = raw.linesIterator.toVector
        val parsed = lines.map(l => Try(ujson.read(l)))
        val authOk = parsed.forall {
          case scala.util.Success(v) => Set("present", "absent").contains(v("auth").str)
          case _                     => false
        }
        authPresentLines += lines.count(l => Try(ujson.read(l)).toOption.exists(_("auth").str == "present"))
        add("stand-logs")(authOk, s"$logPath: ${lines.size} lines, all JSONL with auth present|absent only: $authOk")
        val keyLeak = raw.contains(standKey)
        add("stand-logs")(!keyLeak, s"$logPath: fixture key absent from log=${!keyLeak}")
    add("stand-logs")(authPresentLines > 0,
      s"stand logs: $authPresentLines auth-present lines across the corpus (positive redaction evidence)")

    // ---- report --------------------------------------------------------------
    val report = ujson.Obj(
      "generatedBy" -> "xe.checker.IndependentChecker",
      "goldens" -> Paths.get(args("--goldens")).toString,
      "ok" -> findings.forall(_.ok),
      "sections" -> ujson.Arr.from(
        findings.groupBy(_.section).toVector.map { (section, fs) =>
          ujson.Obj(
            "section" -> section,
            "ok" -> fs.forall(_.ok),
            "findings" -> fs.map(f => ujson.Obj("ok" -> f.ok, "detail" -> f.detail))
          )
        }
      )
    )
    val reportPath = Paths.get(args("--report"))
    Files.createDirectories(reportPath.getParent)
    Files.writeString(reportPath, ujson.write(report), StandardCharsets.UTF_8)
    val failed = findings.filterNot(_.ok)
    findings.foreach(f => if !f.ok then println(s"[xe-checker] FAIL ${f.section}: ${f.detail}"))
    println(s"[xe-checker] findings=${findings.size} failed=${failed.size} report=$reportPath")
    sys.exit(if findings.forall(_.ok) then 0 else 1)

  // small local Try to avoid importing scala.util in two places above
  private object Try:
    def apply[T](body: => T): scala.util.Try[T] = scala.util.Try(body)

end IndependentChecker
