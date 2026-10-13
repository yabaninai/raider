package raider.repl.spike

import org.jline.reader.{
  EndOfFileException,
  LineReader,
  LineReaderBuilder,
  UserInterruptException
}
import org.jline.terminal.{Terminal, TerminalBuilder}
import org.jline.utils.WCWidth

import java.io.{File, FileOutputStream}
import java.nio.charset.StandardCharsets
import scala.collection.mutable

/** RAI-056 / P0.C — inline renderer PTY spike (ADR-016 feasibility).
  *
  * Spikes the two ADR-016 risks with the REAL JLine 4.0.14 line editor on a
  * real PTY (driven by scripts/quality/renderer_pty_probe.py):
  *
  *   1. ONE terminal writer: a background "streaming" thread calls
  *      [[LineReader.printAbove]] while the main thread is blocked in
  *      [[LineReader.readLine]]. The in-progress draft must survive N
  *      background writes. (If JLine corrupts the buffer, the probe's ACCEPTED
  *      check fails — reported honestly, never faked.)
  *   2. GLYPH WIDTH: `org.jline.utils.WCWidth` column widths for a fixed table
  *      of codepoints (one `Char` is NOT one cell: CJK/emoji are wide,
  *      combining marks and ZWSP add zero cells).
  *
  * Test scope only. No product code, no new dependencies.
  *
  * Commands (typed on the PTY): :burst <n> spawn a background thread streaming
  * n `ev <i> payload ...` lines via printAbove; the main thread returns to the
  * prompt immediately so the user keeps typing while events stream. On
  * completion the thread writes a JSON report to `<argv(0)>.b<N>.json`, where N
  * is the 1-based burst sequence number assigned by the main loop. PER-BURST
  * FILES: the spike never writes a shared report file, so overlapping bursts
  * cannot clobber each other's counters (last-writer-wins on a single path lost
  * real data — fixed 2026-10-06, round 2). :width print
  * `WIDTH_JSON {"a":1,"ya":1,...}` (measured widths). :quit exit 0. <anything>
  * echo exactly `ACCEPTED <line>` (draft-integrity oracle).
  *
  * Per-burst report schema (written to `<argv(0)>.b<N>.json`):
  * {"burst":N,"events":n,"p50_ms":x,"p95_ms":x,"max_ms":x,"dropped":k,
  * "durations_ns":[...],"wcwidth_table":{"a":1,...}}
  *
  * `durations_ns` carries the RAW per-event call durations so the probe can
  * compute true global percentiles across overlapping bursts (a percentile of
  * per-burst percentiles would not be the global percentile). The PTY probe
  * aggregates per-burst files into the canonical renderer-baseline*.json.
  */
object InlineRendererSpike:

  private val Prompt = "spike> "
  private val Payload = "x" * 32
  // Default inter-event delay: 5ms * 200 = ~1s streaming window so the probe's
  // draft editing overlaps the active burst. Overridable for tuning.
  private val DefaultDelayNs = 5_000_000L

  // Fixed width table: label -> list of Unicode code points. `wcwidth` is
  // per code point (JLine 4 has no whole-string wcswidth), so a multi-codepoint
  // entry is the sum over its code points.
  private val WidthTable: List[(String, List[Int])] =
    List(
      ("a", List(0x0061)), // 'a'
      ("ya", List(0x044f)), // code point U+044F
      ("han", List(0x6f22)), // CJK '漢'
      ("emoji", List(0x1f600)), // '😀' (UTF-16 surrogate pair)
      ("combining", List(0x0065, 0x0301)), // 'e' + U+0301 combining acute
      ("zwsp", List(0x200b)) // zero-width space
    )

  private def wcOf(codePoints: List[Int]): Int =
    codePoints.map(cp => WCWidth.wcwidth(cp)).sum

  private def wcTableJson: String =
    WidthTable
      .map { (label, cps) => s"\"$label\":${wcOf(cps)}" }
      .mkString("{", ",", "}")

  /** Linear-interpolation percentile of `sorted` ns durations, returned in ms.
    */
  private def percentile(sorted: List[Long], q: Double): Double =
    if sorted.isEmpty then 0.0
    else if sorted.length == 1 then sorted.head.toDouble * 1e-6
    else
      val pos = q * (sorted.length - 1)
      val lo = math.max(0, math.min(sorted.length - 1, math.floor(pos).toInt))
      val hi = math.max(0, math.min(sorted.length - 1, math.ceil(pos).toInt))
      val frac = (pos - lo).toDouble
      (sorted(lo).toDouble * (1.0 - frac) + sorted(hi).toDouble * frac) * 1e-6

  def main(args: Array[String]): Unit =
    // argv(0) is the report path BASE; each burst writes `<base>.b<N>.json`.
    val reportBase = if args.nonEmpty then args(0) else "renderer-baseline.json"
    val eventDelayNs =
      Option(System.getenv("SPIKE_EVENT_DELAY_NS"))
        .map(_.toLong)
        .getOrElse(DefaultDelayNs)

    // Serializes report file writes when bursts overlap (RENDER-04).
    val writeLock = new Object

    // Main-thread-only burst sequence (the readLine loop is single-threaded).
    var burstSeq = 0

    def writeReport(
        path: String,
        burstId: Int,
        events: Int,
        durations: List[Long],
        dropped: Int
    ): Unit =
      val sorted = durations.sorted
      val json =
        s"""{"burst":$burstId,"events":$events,"p50_ms":${percentile(
            sorted,
            0.50
          )},"p95_ms":${percentile(sorted, 0.95)},"max_ms":${
            if sorted.isEmpty then 0.0 else sorted.last.toDouble * 1e-6
          },"dropped":$dropped,"durations_ns":[${durations.mkString(
            ","
          )}],"wcwidth_table":$wcTableJson}"""
      writeLock.synchronized {
        val tmp = new File(path + ".tmp")
        val fos = new FileOutputStream(tmp)
        try fos.write(json.getBytes(StandardCharsets.UTF_8))
        finally fos.close()
        // atomic publish: rename over the target path
        tmp.renameTo(new File(path))
      }

    /** One background streaming burst: `n` printAbove calls, each timed. Runs
      * on a daemon thread so the main readLine loop keeps the user typing. Its
      * counters go to `<reportBase>.b<id>.json` — a path NO other burst
      * touches, so overlapping bursts keep honest, complete counters.
      */
    def burst(reader: LineReader, n: Int, id: Int): Unit =
      val t = new Thread(
        () => {
          val durations = mutable.ArrayBuffer.empty[Long]
          var dropped = 0
          var i = 0
          while i < n do
            val line = s"ev $i payload $Payload"
            val s = System.nanoTime()
            try reader.printAbove(line)
            catch case _: Throwable => dropped += 1
            val e = System.nanoTime()
            durations += (e - s)
            if eventDelayNs > 0 then
              try
                Thread.sleep(
                  eventDelayNs / 1_000_000L,
                  (eventDelayNs % 1_000_000L).toInt
                )
              catch case _: InterruptedException => ()
            i += 1
          writeReport(
            s"$reportBase.b$id.json",
            id,
            n,
            durations.toList,
            dropped
          )
        },
        s"raider-burst-$id"
      )
      t.setDaemon(true)
      t.start()

    val terminal: Terminal = TerminalBuilder.terminal()
    val reader: LineReader = LineReaderBuilder
      .builder()
      .terminal(terminal)
      .build()

    try
      var running = true
      while running do
        val lineOpt: Option[String] =
          try Some(reader.readLine(Prompt))
          catch
            case _: EndOfFileException     => None
            case _: UserInterruptException => Some("")
        lineOpt match
          case None =>
            running = false
          case Some(line) =>
            val trimmed = line.trim
            if trimmed.startsWith(":") then
              trimmed match
                case ":quit" | ":q" =>
                  running = false
                case cmd if cmd.startsWith(":burst") =>
                  val parts = cmd.split("\\s+").filter(_.nonEmpty)
                  val n =
                    if parts.length >= 2 then
                      try parts(1).toInt
                      catch case _: NumberFormatException => 100
                    else 100
                  burstSeq += 1
                  burst(reader, n, burstSeq)
                case ":width" =>
                  println(s"WIDTH_JSON $wcTableJson")
                  System.out.flush()
                case _ =>
                  () // unknown command: ignore, do NOT accept
            else
              println(s"ACCEPTED $line")
              System.out.flush()
        ()
    finally terminal.close()

end InlineRendererSpike
