import java.io.{File, FileInputStream, FileOutputStream, FileWriter}
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date

/** RAI-249: member-level dumper for zinc's persisted API analysis.
  *
  * Lives in the build scope (project/ sources) of DISPOSABLE experiment
  * copies only — never in the product build. Read-only instrumentation:
  * it depends only on setting-key inputs and `streams` (no update, no
  * compile), opens every modules/&lt;m&gt;/target/scala-&lt;v&gt;/zinc/inc_compile_*.zip
  * through zinc's own FileAnalysisStore, and records:
  *   - every internal class name with its persisted apiHash;
  *   - the full declared member set of every class API (class + companion);
  *   - an explicit id-present verdict for every class named *ToolCall*;
  *   - sha256 of the state zip and of the emitted raider/core/tool* artifacts.
  *
  * The verdict line is the at-write-time comparison material: what zinc
  * PERSISTED for raider.core.tool$ToolCall versus what the compiler EMITTED
  * (the emitted side is additionally checked for member `id` by the harness
  * via javap, outside this JVM).
  */
object ApiProbe {

  private def isoNow(): String = {
    val fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
    fmt.setTimeZone(new java.util.SimpleTimeZone(0, "UTC"))
    fmt.format(new Date())
  }

  private def sha256(f: File): Option[String] =
    try {
      val md = MessageDigest.getInstance("SHA-256")
      val in = new FileInputStream(f)
      try {
        val buf = new Array[Byte](8192)
        var n = in.read(buf)
        while (n >= 0) {
          if (n > 0) md.update(buf, 0, n)
          n = in.read(buf)
        }
        Some(md.digest().map(b => "%02x".format(b & 0xff)).mkString)
      } finally in.close()
    } catch {
      case _: Throwable => None
    }

  private def memberLine(d: xsbti.api.ClassDefinition): String = {
    val kind = try d.getClass.getSimpleName catch { case _: Throwable => "?" }
    val name = try d.name() catch { case _: Throwable => "<name-error>" }
    kind + " " + name
  }

  private def declaredNames(cl: xsbti.api.ClassLike): Seq[String] =
    try {
      val st = cl.structure()
      if (st == null || st.declared() == null) Seq.empty
      else st.declared().toSeq.map(d => try d.name() catch { case _: Throwable => "<name-error>" })
    } catch {
      case _: Throwable => Seq.empty
    }

  private def dumpClassLike(sb: StringBuilder, cl: xsbti.api.ClassLike, label: String): Unit =
    try {
      val name = try cl.name() catch { case _: Throwable => "<name-error>" }
      val defType = try cl.definitionType().toString catch { case _: Throwable => "?" }
      sb.append("    ").append(label).append(" name=").append(name)
        .append(" defType=").append(defType).append("\n")
      val st = cl.structure()
      val decl = if (st == null || st.declared() == null) Array.empty[xsbti.api.ClassDefinition] else st.declared()
      val inh = if (st == null || st.inherited() == null) Array.empty[xsbti.api.ClassDefinition] else st.inherited()
      sb.append("      declared[").append(decl.length).append("]: ")
        .append(decl.map(memberLine).mkString(", ")).append("\n")
      sb.append("      inherited-count=").append(inh.length).append("\n")
    } catch {
      case t: Throwable =>
        sb.append("    ").append(label).append(" <api-error: ").append(String.valueOf(t)).append(">\n")
    }

  /** Member names recorded in the persisted (minimized) API's name hashes.
    * By default zinc stores a minimized API whose structure() is empty; the
    * member-name set that survives minimization (and drives zinc's member-ref
    * dependency tracking) is nameHashes(). This is the name-level member set
    * to compare against the emitted TASTy. */
  private def nameHashNames(ac: xsbti.api.AnalyzedClass): Seq[(String, String)] =
    try {
      val nhs = ac.nameHashes()
      if (nhs == null) Seq.empty
      else nhs.toSeq.map(nh =>
        (try nh.name() catch { case _: Throwable => "<name-error>" },
             try String.valueOf(nh.scope()) catch { case _: Throwable => "?" }))
    } catch {
      case _: Throwable => Seq.empty
    }

  /** Walks one module's zinc state and appends a per-module report. */
  private def dumpZincState(sb: StringBuilder, module: String, zincDir: File): String = {
    val zips = Option(zincDir.listFiles()).toSeq.flatten
      .filter(f => f.getName.startsWith("inc_compile") && f.getName.endsWith(".zip"))
      .sortBy(_.getName)
    if (zips.isEmpty) {
      sb.append("module=").append(module).append(" zinc=NO-STATE\n")
      "no-state"
    } else {
      val zip = zips.last
      val zsha = sha256(zip).getOrElse("sha-error")
      sb.append("module=").append(module).append(" zip=").append(zip.getName)
        .append(" zip_mtime_ms=").append(zip.lastModified())
        .append(" zip_sha256=").append(zsha).append("\n")
      var verdict = "read-error"
      var exactVerdict = ""
      try {
        val contentsOpt = sbt.internal.inc.FileAnalysisStore.binary(zip).get()
        if (!contentsOpt.isPresent) {
          sb.append("  analysis=NO-CONTENTS\n")
          verdict = "no-contents"
        } else {
          val analysis = contentsOpt.get().getAnalysis.asInstanceOf[sbt.internal.inc.Analysis]
          val apis = analysis.apis
          val names = apis.allInternalClasses.toSeq.sorted
          sb.append("  internal_classes=").append(names.size).append("\n")
          verdict = "no-toolcall-api"
          val it = names.iterator
          while (it.hasNext) {
            val name = it.next()
            val ac = apis.internalAPI(name)
            val nh = nameHashNames(ac)
            sb.append("  api name=").append(name).append(" apiHash=").append(ac.apiHash())
              .append(" namehash-count=").append(nh.size).append("\n")
            val comps = ac.api()
            if (comps != null) {
              dumpClassLike(sb, comps.classApi(), "classApi")
              dumpClassLike(sb, comps.objectApi(), "objectApi")
            }
            if (name.indexOf("ToolCall") >= 0) {
              val all =
                (if (comps == null) Seq.empty[String]
                 else declaredNames(comps.classApi()) ++ declaredNames(comps.objectApi()))
              val hasIdStruct = all.contains("id")
              val hasIdNh = nh.exists(_._1 == "id")
              sb.append("  TOOLCALL name=").append(name)
                .append(" members=[").append(all.mkString(", ")).append("]")
                .append(" namehashes=[").append(nh.map { case (n, s) => n + "/" + s }.mkString(", ")).append("]")
                .append(" id-structure-present=").append(hasIdStruct)
                .append(" id-namehash-present=").append(hasIdNh).append("\n")
              if (name == "raider.core.tool$.ToolCall") {
                exactVerdict = "verdict=tool$ToolCall id-structure-present=" + hasIdStruct +
                  " id-namehash-present=" + hasIdNh + " apiHash=" + ac.apiHash()
                verdict = exactVerdict.stripPrefix("verdict=")
              }
            }
          }
        }
      } catch {
        case t: Throwable =>
          sb.append("  ERROR ").append(String.valueOf(t)).append("\n")
      }
      if (exactVerdict.nonEmpty) sb.append("module=").append(module).append(" ").append(exactVerdict).append("\n")
      sb.append("module=").append(module).append(" verdict=").append(verdict).append("\n")
      verdict
    }
  }

  /** sha256 of every raider/core/tool* emitted artifact per module classes dir. */
  private def dumpEmitted(sb: StringBuilder, module: String, classesDir: File): Unit =
    try {
      val rc = new File(classesDir, "raider/core")
      val files = Option(rc.listFiles()).toSeq.flatten
        .filter(f => f.getName.startsWith("tool")).sortBy(_.getName)
      if (files.isEmpty) sb.append("emitted module=").append(module).append(" NONE\n")
      else {
        val it = files.iterator
        while (it.hasNext) {
          val f = it.next()
          sb.append("emitted module=").append(module).append(" file=raider/core/")
            .append(f.getName).append(" size=").append(f.length())
            .append(" mtime_ms=").append(f.lastModified())
            .append(" sha256=").append(sha256(f).getOrElse("sha-error")).append("\n")
        }
      }
    } catch {
      case t: Throwable =>
        sb.append("emitted module=").append(module).append(" ERROR ").append(String.valueOf(t)).append("\n")
    }

  private def listDirs(parent: File): Seq[File] =
    Option(parent.listFiles()).toSeq.flatten.filter(_.isDirectory).sortBy(_.getName)

  /** Entry point: dump all module zinc states + emitted artifacts under `base`. */
  def dump(base: File, log: sbt.Logger): Unit = {
    val ts = isoNow()
    val outDir = new File(base, ".api-probe")
    val stamp = ts.replaceAll("[:\\-]", "")
    try outDir.mkdirs() catch { case _: Throwable => () }
    val sb = new StringBuilder
    sb.append("RAI-249 ApiProbe dump ts=").append(ts).append(" base=").append(base.getPath).append("\n")

    val modules = listDirs(new File(base, "modules"))
    val it = modules.iterator
    while (it.hasNext) {
      val m = it.next()
      val targets = listDirs(new File(m, "target")).filter(_.getName.startsWith("scala-"))
      val ti = targets.iterator
      while (ti.hasNext) {
        val sd = ti.next()
        val zinc = new File(sd, "zinc")
        if (zinc.isDirectory) dumpZincState(sb, m.getName + "/" + sd.getName, zinc)
        val classes = new File(sd, "classes")
        if (classes.isDirectory) dumpEmitted(sb, m.getName + "/" + sd.getName, classes)
      }
    }

    val dumpFile = new File(outDir, stamp + ".dump.txt")
    val w = new FileWriter(dumpFile)
    try w.write(sb.toString) finally w.close()
    // latest-verdict.txt: compact pointer file the harness greps
    val verdicts = sb.toString.split("\n").filter(l =>
      l.contains("verdict=") || l.contains("TOOLCALL ") || l.startsWith("emitted ") || l.contains("zip_sha256="))
    val vw = new FileWriter(new File(outDir, "latest-verdict.txt"))
    try {
      vw.write("probe_ts=" + ts + "\n")
      vw.write(verdicts.mkString("\n"))
      vw.write("\n")
    } finally vw.close()
    log.info("apiProbeDump wrote " + dumpFile.getPath)
  }
}
