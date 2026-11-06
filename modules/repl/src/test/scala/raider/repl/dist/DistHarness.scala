package raider.repl.dist

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Test-side packaging helpers for the installed-distribution acceptance
  * (RAI-262). sbt-assembly is unavailable inside a test JVM, so the fat jar is
  * packed here from the CURRENT test classpath — the same closure
  * `raiderRepl / assembly` packs (main classes + runtime dependencies, no test
  * classes). First-wins merge mirrors plain classpath semantics; service files
  * concatenate (sbt-assembly default); foreign manifests, signature files and
  * module-info stubs are dropped (raiderReplAssemblySettings).
  */
object DistHarness:

  private val FixedZipTimeMillis = 1262304000000L

  /** Packs the forked test JVM classpath into a fat jar. skipCompiler drops
    * every dotty/ entry (scala3-repl, compiler, interfaces and tasty all live
    * under dotty/) to produce the adversarial "distribution without the
    * compiler" fixture.
    */
  def packFatJar(
      outJar: Path,
      mainClass: String,
      skipCompiler: Boolean = false
  ): Path =
    val cpEntries = System
      .getProperty("java.class.path")
      .split(java.io.File.pathSeparator)
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .filterNot(p => p.toString.contains("test-classes"))
    val files = mutable.LinkedHashMap[String, Array[Byte]]()
    val services =
      mutable.LinkedHashMap[String, mutable.LinkedHashSet[String]]()
    def wanted(name: String): Boolean =
      name != "META-INF/MANIFEST.MF" &&
        name != "module-info.class" &&
        !name.endsWith(".SF") && !name.endsWith(".RSA") && !name.endsWith(
          ".DSA"
        ) &&
        !(skipCompiler && name.startsWith("dotty/"))
    def accept(name: String, content: () => Array[Byte]): Unit =
      if name.startsWith("META-INF/services/") then
        val lines = new String(content(), StandardCharsets.UTF_8)
          .split("\\r?\\n")
          .map(_.trim)
          .filter(_.nonEmpty)
        services
          .getOrElseUpdate(name, mutable.LinkedHashSet.empty[String])
          .++=(lines)
      else if !files.contains(name) then files(name) = content()
    for entry <- cpEntries do
      if Files.isRegularFile(entry) then
        Using.resource(JarFile(entry.toFile)) { jar =>
          val it = jar.entries()
          while it.hasMoreElements do
            val e = it.nextElement()
            if !e.isDirectory && wanted(e.getName) then
              accept(e.getName, () => readStream(jar.getInputStream(e)))
        }
      else if Files.isDirectory(entry) then
        val walk = Files.walk(entry)
        try
          walk
            .iterator()
            .asScala
            .filter(Files.isRegularFile(_))
            .foreach { p =>
              val rel = entry.relativize(p).iterator().asScala.mkString("/")
              if wanted(rel) then accept(rel, () => Files.readAllBytes(p))
            }
        finally walk.close()
    Files.createDirectories(outJar.toAbsolutePath.getParent)
    Using.resource(Files.newOutputStream(outJar)) { out =>
      Using.resource(new ZipOutputStream(out, StandardCharsets.UTF_8)) { zip =>
        def put(name: String, bytes: Array[Byte]): Unit =
          val e = new ZipEntry(name)
          e.setTime(FixedZipTimeMillis)
          zip.putNextEntry(e)
          zip.write(bytes)
          zip.closeEntry()
        put("META-INF/MANIFEST.MF", manifestBytes(mainClass))
        services.keys.toVector.sorted.foreach { name =>
          put(
            name,
            services(name)
              .mkString("", "\n", "\n")
              .getBytes(StandardCharsets.UTF_8)
          )
        }
        files.toVector.sortBy(_._1).foreach { (name, bytes) =>
          put(name, bytes)
        }
      }
    }
    outJar

  /** Unpacks a distribution zip to targetDir, restoring the executable bit on
    * the launcher (installers do this step; java.util.zip cannot carry POSIX
    * modes).
    */
  def unpack(zip: Path, targetDir: Path): Path =
    Files.createDirectories(targetDir)
    Using.resource(Files.newInputStream(zip)) { in =>
      Using.resource(new ZipInputStream(in, StandardCharsets.UTF_8)) { zis =>
        var entry = Option(zis.getNextEntry)
        while entry.isDefined do
          val je = entry.get
          if !je.isDirectory then
            val target = targetDir.resolve(je.getName).normalize()
            require(
              target.startsWith(targetDir),
              s"zip entry escapes target dir: ${je.getName}"
            )
            Files.createDirectories(target.getParent)
            Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING)
            if je.getName.endsWith("/raider-repl") then
              Files.setPosixFilePermissions(
                target,
                PosixFilePermissions.fromString("rwxr-xr-x")
              )
          entry = Option(zis.getNextEntry)
      }
    }
    targetDir

  private def manifestBytes(mainClass: String): Array[Byte] =
    ("Manifest-Version: 1.0\r\n" +
      "Created-By: raider-repl-dist-test\n" +
      s"Main-Class: $mainClass\r\n" +
      "\r\n").getBytes(StandardCharsets.UTF_8)

  private def readStream(in: InputStream): Array[Byte] =
    Using.resource(in) { s =>
      val buf = new ByteArrayOutputStream()
      s.transferTo(buf)
      buf.toByteArray
    }

  /** Locates a repository file from the forked test JVM regardless of its
    * working directory (walks up to the checkout root that contains the given
    * relative path).
    */
  def repoFile(rel: String): Path =
    var dir: Option[Path] = Some(Paths.get("").toAbsolutePath)
    while dir.exists(d => !Files.exists(d.resolve(rel))) do
      dir = dir.flatMap(p => Option(p.getParent))
    require(
      dir.exists(d => Files.exists(d.resolve(rel))),
      s"repo file not found walking up from cwd: $rel"
    )
    dir.get.resolve(rel)

  /** Independent SHA256SUMS verification through the platform digest tool:
    * `shasum -a 256 -c` (BSD/macOS) with a `sha256sum -c` fallback (GNU). Runs
    * inside the staged/unpacked directory so the relative payload names in
    * SHA256SUMS resolve. A tool that cannot even be started is skipped in favor
    * of the fallback; a tool that RUNS and fails is reported as a failure (no
    * silent retry with the next tool).
    */
  def shasumVerify(distDir: Path): (Boolean, String) =
    def attempt(cmd: Seq[String]): Option[(Int, String)] =
      try
        val pb = new ProcessBuilder(cmd*)
        pb.directory(distDir.toFile)
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out =
          new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        if p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) then
          Some((p.exitValue(), out))
        else
          p.destroyForcibly()
          None
      catch case _: java.io.IOException => None
    val tools = Seq(
      Seq("shasum", "-a", "256", "-c", "SHA256SUMS"),
      Seq("sha256sum", "-c", "SHA256SUMS")
    )
    var verified: Option[(Boolean, String)] = None
    for cmd <- tools if verified.isEmpty do
      attempt(cmd) match
        case Some((exit, out)) => verified = Some((exit == 0, out.trim))
        case None              => ()
    verified.getOrElse(
      (false, "no sha256 digest tool (shasum/sha256sum) runnable")
    )

  def deleteRecursive(dir: Path): Unit =
    if Files.exists(dir) then
      val walk = Files.walk(dir)
      try
        walk.sorted(java.util.Comparator.reverseOrder[Path]()).forEach { p =>
          Files.deleteIfExists(p): Unit
        }
      finally walk.close()

  /** Environment for child REPL processes: temp HOME, offline scripted
    * provider, deterministic TERM. Credentials from the outer environment are
    * never inherited (trust boundary: no secrets in child processes,
    * transcripts, or evidence).
    */
  def removedEnvKeys: Seq[String] = Seq(
    "RAIDER_API_KEY",
    "RAIDER_BASE_URL",
    "RAIDER_MODEL",
    "OPENAI_API_KEY",
    "ANTHROPIC_API_KEY",
    "OPENAI_BASE_URL",
    "ANTHROPIC_BASE_URL"
  )

  def childEnv(freshDir: Path): Map[String, String] = Map(
    "HOME" -> Files.createDirectories(freshDir.resolve("home")).toString,
    "RAIDER_PROVIDER" -> "mock",
    "TERM" -> "dumb"
  )

  /** Runs a child process to completion with the given stdin contents and a
    * combined stdout/stderr transcript. Exceeding the timeout is a hard failure
    * (the process is destroyed forcibly first) — a hang is never an acceptable
    * outcome for the adversarial cases.
    */
  def runPiped(
      command: Seq[String],
      env: Map[String, String] = Map.empty,
      input: String = "",
      cwd: Option[Path] = None,
      timeoutSeconds: Long = 240
  ): (Int, String) =
    val session = new DistSession(command, env, cwd)
    if input.nonEmpty then session.send(input)
    session.closeStdin()
    if !session.waitForExit(timeoutSeconds) then
      session.destroyForcibly()
      throw new IllegalStateException(
        s"process exceeded ${timeoutSeconds}s: ${command.mkString(" ")}"
      )
    (session.process.exitValue(), session.text)

/** Combined-transcript child process session for the installed-distribution
  * acceptance (RAI-262): start with a controlled environment, wait for output
  * fragments, send lines, destroy, inspect exit codes. Mirrors the
  * ChatLayoutPtySpec session mechanics without a PTY layer.
  */
final class DistSession(
    command: Seq[String],
    env: Map[String, String],
    cwd: Option[Path]
):
  private val pb = new ProcessBuilder(command*)
  pb.redirectErrorStream(true)
  cwd.foreach(p => pb.directory(p.toFile))
  private val childEnv = pb.environment()
  DistHarness.removedEnvKeys.foreach(childEnv.remove)
  env.foreach((k, v) => childEnv.put(k, v))
  val process: Process = pb.start()
  // java.lang.StringBuilder explicitly: a plain `new StringBuilder` resolves
  // to scala.collection.mutable.StringBuilder in this file, whose append(Any)
  // tupled the (buf, 0, n) call and corrupted the transcript.
  private val transcript = new java.lang.StringBuilder

  private val pump = new Thread(() => {
    Using.resource(
      new java.io.InputStreamReader(
        process.getInputStream,
        StandardCharsets.UTF_8
      )
    ) { reader =>
      val buf = new Array[Char](4096)
      var n = reader.read(buf)
      while n >= 0 do
        val chunk = new String(buf, 0, n)
        transcript.synchronized { transcript.append(chunk) }
        n = reader.read(buf)
    }
  })

  pump.setDaemon(true)
  pump.start()

  def text: String = transcript.synchronized(transcript.toString)

  /** Waits until the transcript contains the fragment; false on timeout. */
  def waitForOutput(fragment: String, timeoutSeconds: Long): Boolean =
    val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
    while System.currentTimeMillis() < deadline && !text.contains(fragment) do
      Thread.sleep(200)
    text.contains(fragment)

  def send(line: String): Unit =
    process.getOutputStream.write(
      (line + "\n").getBytes(StandardCharsets.UTF_8)
    )
    process.getOutputStream.flush()

  def closeStdin(): Unit = process.getOutputStream.close()

  def waitForExit(timeoutSeconds: Long): Boolean =
    process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)

  def destroyForcibly(): Unit = process.destroyForcibly()
