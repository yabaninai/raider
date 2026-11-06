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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAI-233 cancellation/ownership probe (langchain4j-consumer lane path).
 *
 * Races an immediate observe(afterSequence=0) + cancel pair against the live
 * event stream of a mock-provider run and verifies the wire-level ownership
 * guarantees of the Session observe/live-pump coordination:
 *
 *   - the cancel is answered (accepted or already_terminal) and exactly one
 *     terminal frame settles the root;
 *   - delivered observation sequences are strictly increasing (no reorder
 *     between a replay batch and live admissions);
 *   - no sequence is delivered twice (no double delivery across replay+live);
 *   - no interior gap without a `skipped` marker covering it (a mid-batch
 *     interruption must not drop frames; trailing absence after cancellation
 *     is legal because cancelled runs stop streaming);
 *   - the verdict matches the race outcome: accepted cancel settles cancelled
 *     with adapter exit 26; already_terminal settles completed with exit 0.
 *
 * Self-contained on purpose: run_l4j compiles only XeLangChain4jConsumer.java,
 * so this probe must not depend on package-private members of that file.
 *
 * Usage: --adapter-jar &lt;jar&gt; --ws-root &lt;dir&gt; --out-root &lt;dir&gt;
 *        [--iterations N]   (default 10)
 * Exit code 0 iff every iteration passes.
 */
public final class XeObserveCancelProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) m.put(args[i], args[i + 1]);
        }
        if (!m.containsKey("--adapter-jar") || !m.containsKey("--ws-root")
                || !m.containsKey("--out-root")) {
            System.err.println("xe-observe-cancel-probe: need --adapter-jar "
                    + "--ws-root --out-root [--iterations N]");
            System.exit(2);
        }
        int iterations = m.containsKey("--iterations")
                ? Integer.parseInt(m.get("--iterations")) : 10;

        Path adapterJar = Paths.get(m.get("--adapter-jar"));
        Path wsRoot = Paths.get(m.get("--ws-root"));
        Path outRoot = Paths.get(m.get("--out-root"));
        Files.createDirectories(wsRoot);
        Files.createDirectories(outRoot);

        int failures = 0;
        for (int i = 1; i <= iterations; i++) {
            IterationResult r = runOnce(adapterJar, wsRoot, outRoot, i);
            boolean ok = r.allChecksPass();
            System.out.println("[probe] iteration " + i + " "
                    + (ok ? "PASS" : "FAIL") + " " + r.detail());
            if (!ok) failures++;
        }
        System.out.println("[probe] iterations=" + iterations + " failures=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static final class IterationResult {
        boolean cancelAckSeen;
        String cancelStatus = "none";
        boolean oneTerminal;
        String terminalStatus = "none";
        boolean strictlyIncreasing = true;
        boolean noDuplicates = true;
        boolean gapsCovered = true;
        String gapDetail = "n/a";
        List<Long> seqs = new ArrayList<>();
        int adapterExit = -1000;

        boolean allChecksPass() {
            boolean cancelAnswered = "accepted".equals(cancelStatus)
                    || "already_terminal".equals(cancelStatus);
            boolean exitOk = "accepted".equals(cancelStatus)
                    ? adapterExit == 26
                    : "already_terminal".equals(cancelStatus) && adapterExit == 0;
            boolean verdictOk = "accepted".equals(cancelStatus)
                    ? "cancelled".equals(terminalStatus)
                    : "already_terminal".equals(cancelStatus)
                        && "completed".equals(terminalStatus);
            return cancelAnswered && oneTerminal && strictlyIncreasing
                    && noDuplicates && gapsCovered && exitOk && verdictOk;
        }

        String detail() {
            return "cancel=" + cancelStatus + " terminal=" + terminalStatus
                    + " exit=" + adapterExit + " seqs=" + seqs
                    + " gaps=" + gapDetail;
        }
    }

    private static IterationResult runOnce(Path adapterJar, Path wsRoot,
                                           Path outRoot, int iter) throws Exception {
        Path ws = wsRoot.resolve("iter-" + iter);
        Path out = outRoot.resolve("iter-" + iter);
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

        IterationResult r = new IterationResult();
        try {
            send(stdin, hello("oc-hello"));
            readLine(stdout); // helloAck

            send(stdin, submit("oc-run", "Say hi"));
            String line;
            boolean acceptedSeen = false;
            boolean firstObsSeen = false;
            // Mirror cancel-mid-run timing: the pair goes out once the run is
            // demonstrably live (accepted AND one observation delivered), so
            // the frames are processed pre-terminal and the race is the
            // intended one — replay batch vs settle interrupting the pump.
            // Frames read here are part of the delivered stream and are
            // recorded like every other observation.
            while (!(acceptedSeen && firstObsSeen)
                    && (line = readLine(stdout)) != null) {
                JsonNode v = MAPPER.readTree(line);
                if ("accepted".equals(frameType(v))) acceptedSeen = true;
                if ("observation".equals(frameType(v))) {
                    firstObsSeen = true;
                    r.seqs.add(v.path("sequence").asLong(-1L));
                }
                if ("terminal".equals(frameType(v))) {
                    r.terminalStatus = v.path("executionStatus").asText("none");
                    r.oneTerminal = true;
                }
            }

            // Race: observe re-delivery request immediately followed by a
            // cancel, both while the run is live — the observe replay batch
            // races the settle path that interrupts the pump.
            send(stdin, observe("oc-observe", 0));
            send(stdin, cancel("oc-cancel"));

            List<long[]> skipped = new ArrayList<>();
            int terminals = 0;
            if (r.terminalStatus.equals("none")) {
                while ((line = readLine(stdout)) != null) {
                    JsonNode v = MAPPER.readTree(line);
                    String t = frameType(v);
                    if ("terminal".equals(t)) {
                        terminals++;
                        r.terminalStatus = v.path("executionStatus").asText("none");
                        break;
                    } else if ("error".equals(t)
                            && "observe_failed".equals(errorCode(v))) {
                        // documented no-news answer; observations continue live
                    } else if ("cancelAck".equals(t)) {
                        r.cancelStatus = v.path("status").asText("none");
                    } else if ("observation".equals(t)) {
                        long seq = v.path("sequence").asLong(-1L);
                        r.seqs.add(seq);
                        JsonNode sk = v.path("skipped");
                        if (sk.isObject()) {
                            skipped.add(new long[]{
                                    sk.path("fromSequence").asLong(-1L),
                                    sk.path("count").asLong(-1L)});
                        }
                    }
                }
            } else {
                // settled before the pair could be answered; the cancel is
                // post-terminal input (unanswered by design) — recorded as an
                // honest failure of this iteration below
                r.cancelStatus = "none";
            }
            r.oneTerminal = terminals == 1 || !r.terminalStatus.equals("none");

            long prev = 0;
            for (long seq : r.seqs) {
                if (seq <= prev) r.strictlyIncreasing = false;
                if (r.seqs.indexOf(seq) != r.seqs.lastIndexOf(seq)) {
                    r.noDuplicates = false;
                }
                for (long missing = prev + 1; missing < seq; missing++) {
                    boolean covered = false;
                    for (long[] s : skipped) {
                        if (missing >= s[0] && missing < s[0] + s[1]) {
                            covered = true;
                            break;
                        }
                    }
                    if (!covered) {
                        r.gapsCovered = false;
                        r.gapDetail = "missing " + missing
                                + " between " + prev + " and " + seq
                                + ", skipped=" + skipped;
                        break;
                    }
                }
                if (!r.gapsCovered) break;
                prev = seq;
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
            if (proc.isAlive()) {
                proc.destroyForcibly();
                r.adapterExit = -999;
            } else {
                r.adapterExit = proc.exitValue();
            }
        }
        return r;
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

    private static ObjectNode cancel(String requestId) {
        ObjectNode n = proto();
        n.put("type", "cancel");
        n.put("requestId", requestId);
        return n;
    }

    private static String frameType(JsonNode v) {
        return v.has("type") ? v.get("type").asText() : "<none>";
    }

    private static String errorCode(JsonNode v) {
        return v.has("code") ? v.get("code").asText() : "-";
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
