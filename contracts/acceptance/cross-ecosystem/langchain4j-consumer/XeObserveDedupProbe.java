package xe.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * RAI-233 red-first reproduction probe (langchain4j-consumer lane path).
 *
 * Deterministic twin of the frozen consumer's observe-dedup scenario: it
 * reads until the run is demonstrably live (accepted AND first observation),
 * holds still for {@code --delay-ms} (default 30) while the mock stream
 * finishes emitting, and only then sends observe(afterSequence=0). This
 * deliberately widens the scheduling window that the loaded corpus matrix
 * hits stochastically (RAI-231: 2/6 under aggregate host load ~290): on the
 * unfixed adapter the observe replay re-delivers every retained frame after
 * the live stream already delivered them, so the frozen checks
 * sequences-strictly-increasing and no-duplicate-frames-after-dedup fail with
 * a doubled sequence list such as [1..N, 1..N]. On the fixed adapter the
 * observe/live-pump coordination suppresses already-delivered sequences and
 * the same checks pass.
 *
 * Self-contained: run_l4j compiles only XeLangChain4jConsumer.java, so this
 * probe must not depend on package-private members of that file.
 *
 * Usage: --adapter-jar &lt;jar&gt; --ws-root &lt;dir&gt; --out-root &lt;dir&gt;
 *        [--delay-ms N]   (default 30)
 * Exit code 0 iff every check passes.
 */
public final class XeObserveDedupProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) m.put(args[i], args[i + 1]);
        }
        if (!m.containsKey("--adapter-jar") || !m.containsKey("--ws-root")
                || !m.containsKey("--out-root")) {
            System.err.println("xe-observe-dedup-probe: need --adapter-jar "
                    + "--ws-root --out-root [--delay-ms N]");
            System.exit(2);
        }
        long delayMs = m.containsKey("--delay-ms")
                ? Long.parseLong(m.get("--delay-ms")) : 30L;

        Path adapterJar = Paths.get(m.get("--adapter-jar"));
        Path ws = Paths.get(m.get("--ws-root"));
        Path out = Paths.get(m.get("--out-root"));
        Files.createDirectories(ws);
        Files.createDirectories(out);

        List<String> argv = new ArrayList<>();
        argv.add("java");
        argv.add("-jar");
        argv.add(adapterJar.toString());
        argv.add("process-api");
        argv.add("--workspace");
        argv.add(ws.toString());
        argv.add("--out");
        argv.add(out.toString());
        argv.add("--provider");
        argv.add("mock");
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectError(out.resolve("adapter-stderr.log").toFile());
        Process proc = pb.start();
        BufferedWriter stdin = new BufferedWriter(new OutputStreamWriter(
                proc.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader stdout = new BufferedReader(new InputStreamReader(
                proc.getInputStream(), StandardCharsets.UTF_8));
        Thread watcher = new Thread(() -> {
            try {
                Thread.sleep(120000L);
                proc.destroyForcibly();
            } catch (InterruptedException ignored) {
            }
        });
        watcher.setDaemon(true);
        watcher.start();

        List<Long> seqs = new ArrayList<>();
        int terminals = 0;
        String terminalStatus = "none";
        int adapterExit = -1000;
        try {
            send(stdin, hello("rd-hello"));
            readLine(stdout); // helloAck
            send(stdin, submit("rd-run", "Say hi"));

            String line;
            boolean acceptedSeen = false;
            boolean firstObsSeen = false;
            while (!(acceptedSeen && firstObsSeen)
                    && (line = readLine(stdout)) != null) {
                JsonNode v = MAPPER.readTree(line);
                if ("accepted".equals(frameType(v))) acceptedSeen = true;
                if ("observation".equals(frameType(v))) firstObsSeen = true;
            }

            // The deliberate widening window: the live stream drains while we
            // hold still, so the subsequent observe(afterSequence=0) lands
            // after the frames are already on the wire.
            Thread.sleep(delayMs);
            send(stdin, observe("rd-replay", 0));

            while ((line = readLine(stdout)) != null) {
                JsonNode v = MAPPER.readTree(line);
                String t = frameType(v);
                if ("terminal".equals(t)) {
                    terminals++;
                    terminalStatus = v.path("executionStatus").asText("none");
                    break;
                } else if ("observation".equals(t)) {
                    seqs.add(v.path("sequence").asLong(-1L));
                }
                // error frames (e.g. observe_failed on the fixed adapter) are
                // recorded on the wire but do not stop the stream
            }
        } finally {
            try {
                stdin.close();
            } catch (IOException ignored) {
            }
            watcher.interrupt();
            int waited = 0;
            while (proc.isAlive() && waited < 120) {
                Thread.sleep(500L);
                waited++;
            }
            adapterExit = proc.isAlive() ? -999 : proc.exitValue();
        }

        List<Long> sortedDistinct = seqs.stream().sorted().distinct().toList();
        boolean nonEmpty = !seqs.isEmpty();
        boolean strictlyIncreasing = seqs.equals(sortedDistinct);
        boolean noDuplicates = seqs.stream().distinct().count() == seqs.size();
        boolean oneTerminal = terminals == 1;
        boolean exitOk = adapterExit == 0 && "completed".equals(terminalStatus);

        System.out.println("[xe-observe-dedup-probe] observations-nonempty "
                + (nonEmpty ? "PASS" : "FAIL") + " sequences=" + seqs);
        System.out.println("[xe-observe-dedup-probe] sequences-strictly-increasing "
                + (strictlyIncreasing ? "PASS" : "FAIL") + " sequences=" + seqs);
        System.out.println("[xe-observe-dedup-probe] no-duplicate-frames-after-dedup "
                + (noDuplicates ? "PASS" : "FAIL") + " unique="
                + seqs.stream().distinct().count() + "/" + seqs.size());
        System.out.println("[xe-observe-dedup-probe] terminal-settles-once "
                + (oneTerminal ? "PASS" : "FAIL") + " terminals=" + terminals);
        System.out.println("[xe-observe-dedup-probe] adapter-exit-0 "
                + (exitOk ? "PASS" : "FAIL") + " exit=" + adapterExit
                + " terminal=" + terminalStatus);

        boolean ok = nonEmpty && strictlyIncreasing && noDuplicates
                && oneTerminal && exitOk;
        System.out.println("[xe-observe-dedup-probe] RESULT "
                + (ok ? "PASS" : "FAIL"));
        System.exit(ok ? 0 : 1);
    }

    // ----- frame builders (exact documented key sets, mirror of the consumer) --

    private static ObjectNode proto() {
        ObjectNode n = MAPPER.createObjectNode();
        ObjectNode pv = n.putObject("protocolVersion");
        pv.put("major", 1);
        pv.put("minor", 0);
        return n;
    }

    private static ObjectNode hello(String requestId) {
        ObjectNode n = proto();
        n.put("type", "hello");
        n.put("requestId", requestId);
        ObjectNode v = MAPPER.createObjectNode();
        v.put("major", 1);
        v.put("minor", 0);
        n.putArray("supported").add(v);
        n.putArray("requiredCapabilities").add("observe").add("cancel");
        return n;
    }

    private static ObjectNode submit(String requestId, String task) {
        ObjectNode n = proto();
        n.put("type", "submit");
        n.put("requestId", requestId);
        ObjectNode input = n.putObject("input");
        input.put("schemaVersion", 1);
        input.put("task", task);
        return n;
    }

    private static ObjectNode observe(String requestId, long after) {
        ObjectNode n = proto();
        n.put("type", "observe");
        n.put("requestId", requestId);
        n.put("afterSequence", after);
        return n;
    }

    private static String frameType(JsonNode v) {
        return v.has("type") ? v.get("type").asText() : "<none>";
    }

    private static void send(BufferedWriter stdin, ObjectNode frame)
            throws IOException {
        stdin.write(MAPPER.writeValueAsString(frame));
        stdin.write("\n");
        stdin.flush();
    }

    private static String readLine(BufferedReader stdout) throws IOException {
        return stdout.readLine();
    }
}
