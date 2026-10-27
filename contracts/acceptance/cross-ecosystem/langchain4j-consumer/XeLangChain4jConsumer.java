// RAI-205 cross-ecosystem consumer (LangChain4j side).
//
// A NON-Raider client that integrates Raider as an execution backend through
// the accepted local stdio process adapter (ADR-037 / contracts/process-api).
// Written against the pinned third-party stack
// dev.langchain4j:langchain4j-core:1.22.0 (resolved from Maven Central;
// closure inventoried in the acceptance docs):
//   - dev.langchain4j.data.message.UserMessage + ChatMessageSerializer -> the
//     ecosystem's native message model carries the task text and is
//     round-tripped through the serializer as an integration check;
//   - dev.langchain4j.agent.tool.ToolSpecification / JsonObjectSchema -> the
//     equivalence tool ("touch_marker") is declared the LangChain4j way for
//     the tool-contract parity row (strict() recorded for the RAI-202 fact);
//   - Jackson (LangChain4j's own JSON stack) for protocol frame I/O.
//
// Deterministic fixture: every scenario spawns a fresh `raider process-api`
// child, exchanges documented frames, records checks, and emits a manifest
// for the independent checker. Exit 0 iff every requested check passed.
// Scenario and check names intentionally mirror the LLM4S consumer.

package xe.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class XeLangChain4jConsumer {

    record Check(String name, boolean pass, String detail) {}

    record ScenarioResult(String name, boolean ok, Integer adapterExitCode, List<Check> checks) {}

    /** One spawned `raider process-api` child process. */
    static final class AdapterProc {
        final Process proc;
        private final BufferedWriter stdin;
        private final BufferedReader stdout;

        AdapterProc(Path adapterJar, Path workspace, Path outDir,
                    List<String> providerArgs, String apiKey, Path stdErrFile) throws IOException {
            List<String> argv = new ArrayList<>();
            argv.add("java");
            argv.add("-jar");
            argv.add(adapterJar.toString());
            argv.add("process-api");
            argv.add("--workspace");
            argv.add(workspace.toString());
            argv.add("--out");
            argv.add(outDir.toString());
            argv.addAll(providerArgs);
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectError(stdErrFile.toFile());
            if (apiKey != null) {
                pb.environment().put("RAIDER_API_KEY", apiKey);
            }
            this.proc = pb.start();
            this.stdin = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
            this.stdout = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        }

        void send(String line) throws IOException {
            stdin.write(line);
            stdin.write("\n");
            stdin.flush();
        }

        /** Reads one LF frame; null at EOF. */
        String readLine() throws IOException {
            return stdout.readLine();
        }

        void closeStdin() throws IOException {
            stdin.close();
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> VALID_SCENARIOS = List.of(
            "handshake-terminal", "refusals", "observe-dedup", "tool-task",
            "extraction-task", "cancel-mid-run", "eof-uncertain");

    private record Cfg(Path adapterJar, Path wsRoot, Path outRoot, Path manifest,
                       List<String> scenarios, String standUrl, String apiKey) {}

    // ----- frame builders (exact documented key sets) ------------------------

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

    private static String term(JsonNode t) {
        if (t == null) return "none";
        return t.get("executionStatus").asText() + "/" + t.get("taskVerdict").asText();
    }

    private static Check chk(String name, boolean pass, String detail) {
        return new Check(name, pass, detail);
    }

    // ----- equivalence tool, declared the LangChain4j way ---------------------

    /** The same filesystem-touch intent the scripted stand advertises as
     * `proc_run`; declared with the pinned LangChain4j tool API. `strict`
     * support is recorded in the check detail (RAI-202 native constrained
     * output is absent in the adapter; frameworks differ here). */
    private static ToolSpecification equivalenceTool() {
        return ToolSpecification.builder()
                .name("touch_marker")
                .description("Touch (create) a marker file at a filesystem path.")
                .parameters(JsonObjectSchema.builder()
                        .description("Arguments for touch_marker")
                        .addStringProperty("path", "target path")
                        .required("path")
                        .build())
                .strict(Boolean.TRUE)
                .build();
    }

    private static Check toolParityCheck() {
        try {
            ToolSpecification tool = equivalenceTool();
            boolean nameOk = "touch_marker".equals(tool.name());
            boolean hasPath = tool.parameters() != null
                    && tool.parameters().properties().containsKey("path");
            // ecosystem-native round trip of the declaration itself
            ToolSpecification reparsed = ToolSpecification.fromJson(tool.toJson());
            boolean roundTrips = reparsed != null && "touch_marker".equals(reparsed.name());
            return chk("tool-contract-parity",
                    nameOk && hasPath && roundTrips,
                    "ToolSpecification name=" + tool.name() + " properties.path=" + hasPath
                            + " jsonRoundTrip=" + roundTrips + " strict=" + tool.strict()
                            + "; the stand advertises proc_run (argv touch) covering the same "
                            + "filesystem-touch intent; framework strict mode exists but the "
                            + "adapter exposes no native constrained-output mode (RAI-202)");
        } catch (Exception e) {
            return chk("tool-contract-parity", false, "render failed: " + e);
        }
    }

    private static Check messageRoundTripCheck(String task) {
        try {
            UserMessage msg = new UserMessage(task);
            String json = ChatMessageSerializer.messageToJson(msg);
            ChatMessage back = ChatMessageDeserializer.messageFromJson(json);
            boolean ok = back instanceof UserMessage um && task.equals(um.singleText());
            return chk("langchain4j-message-roundtrip", ok,
                    "UserMessage serialized via ChatMessageSerializer and deserialized; "
                            + "native message model carries the task text unchanged");
        } catch (Exception e) {
            return chk("langchain4j-message-roundtrip", false, "round trip failed: " + e);
        }
    }

    // ----- driver plumbing -----------------------------------------------------

    private static boolean isStandScenario(String s) {
        return List.of("tool-task", "extraction-task", "cancel-mid-run", "eof-uncertain").contains(s);
    }

    private static List<String> providerArgs(Cfg cfg, String scenario, String agent) {
        if (!isStandScenario(scenario)) {
            return List.of("--provider", "mock");
        }
        if (cfg.standUrl == null) {
            throw new IllegalStateException("stand scenarios need --stand-url");
        }
        return List.of("--provider", "openai", "--model", "xe-stand-model",
                "--base-url", cfg.standUrl, "--agent", agent);
    }

    /** Runs `body` against a fresh adapter child with a 120 s watchdog; the
     * body returns checks, the adapter exit code is captured by this wrapper.
     * Lifecycle: the adapter settles (terminal + artifacts) but stays alive
     * while stdin is open; the client closes stdin to release the idle
     * process before awaiting the exit code. */
    private static ScenarioResult withAdapter(Cfg cfg, String scenario, String agent,
                                              AdapterBody body) throws Exception {
        Path ws = cfg.wsRoot.resolve(scenario);
        Files.createDirectories(ws);
        Path out = cfg.outRoot.resolve(scenario + "-langchain4j");
        Files.createDirectories(out);
        AdapterProc proc = new AdapterProc(cfg.adapterJar, ws, out,
                providerArgs(cfg, scenario, agent),
                isStandScenario(scenario) ? cfg.apiKey : null,
                out.resolve("adapter-stderr.log"));
        Thread watcher = new Thread(() -> {
            try {
                Thread.sleep(120000L);
                proc.proc.destroyForcibly();
            } catch (InterruptedException ignored) {
            }
        });
        watcher.setDaemon(true);
        watcher.start();
        List<Check> checks;
        try {
            checks = body.run(proc);
        } finally {
            watcher.interrupt();
        }
        if (proc.proc.isAlive()) {
            proc.closeStdin();
        }
        int code = awaitExit(proc, 60);
        return new ScenarioResult(scenario, checks.stream().allMatch(Check::pass), code, checks);
    }

    private interface AdapterBody {
        List<Check> run(AdapterProc proc) throws Exception;
    }

    /** Bounded wait for adapter exit; force-kills and reports -999 on timeout
     * (the corresponding exit-code check then fails instead of hanging). */
    private static int awaitExit(AdapterProc proc, int seconds) throws InterruptedException {
        int waited = 0;
        while (proc.proc.isAlive() && waited < seconds * 2) {
            Thread.sleep(500L);
            waited += 1;
        }
        if (proc.proc.isAlive()) {
            proc.proc.destroyForcibly();
            return -999;
        }
        return proc.proc.exitValue();
    }

    /** Appends an adapter exit-code expectation to a scenario result. */
    private static ScenarioResult withExit(ScenarioResult res, int expected) {
        Check c = chk("adapter-exit-" + expected,
                res.adapterExitCode != null && res.adapterExitCode == expected,
                "adapter exit=" + res.adapterExitCode);
        List<Check> all = new ArrayList<>(res.checks());
        all.add(c);
        return new ScenarioResult(res.name(), all.stream().allMatch(Check::pass),
                res.adapterExitCode(), all);
    }

    /** Reads frames until the first `terminal` frame (or EOF), returning all. */
    private static List<JsonNode> drainToTerminal(AdapterProc proc) throws IOException {
        List<JsonNode> seen = new ArrayList<>();
        while (true) {
            String line = proc.readLine();
            if (line == null) return seen;
            JsonNode v = MAPPER.readTree(line);
            seen.add(v);
            if ("terminal".equals(frameType(v))) return seen;
        }
    }

    /** Reads frames until `pred` matches (inclusive) or terminal/EOF. */
    private static List<JsonNode> readUntil(AdapterProc proc, java.util.function.Predicate<JsonNode> pred)
            throws IOException {
        List<JsonNode> seen = new ArrayList<>();
        while (true) {
            String line = proc.readLine();
            if (line == null) return seen;
            JsonNode v = MAPPER.readTree(line);
            seen.add(v);
            if (pred.test(v) || "terminal".equals(frameType(v))) return seen;
        }
    }

    // ----- scenarios -------------------------------------------------------------

    private static ScenarioResult handshakeTerminal(Cfg cfg) throws Exception {
        ScenarioResult res = withAdapter(cfg, "handshake-terminal", "plain", proc -> {
            List<Check> checks = new ArrayList<>();
            checks.add(messageRoundTripCheck("Say hi"));
            proc.send(MAPPER.writeValueAsString(hello("ht-hello")));
            JsonNode ack = MAPPER.readTree(proc.readLine());
            boolean negotiated = ack.has("negotiated")
                    && ack.get("negotiated").get("major").asInt() == 1
                    && ack.get("negotiated").get("minor").asInt() == 0;
            checks.add(chk("hello-ack-negotiated-1-0", negotiated,
                    "negotiated=" + ack.path("negotiated").toString()));
            var caps = new java.util.HashSet<String>();
            ack.path("capabilities").forEach(c -> caps.add(c.asText()));
            checks.add(chk("hello-ack-capabilities-observe-cancel",
                    caps.contains("observe") && caps.contains("cancel"), "capabilities=" + caps));
            checks.add(chk("adapter-version-raider-process-api-1",
                    "raider-process-api-1".equals(ack.path("adapterVersion").asText(null)),
                    "adapterVersion=" + ack.path("adapterVersion").asText("?")));
            proc.send(MAPPER.writeValueAsString(submit("ht-run", "Say hi")));
            List<JsonNode> frames = drainToTerminal(proc);
            JsonNode accepted = frames.stream().filter(f -> "accepted".equals(frameType(f)))
                    .findFirst().orElse(null);
            checks.add(chk("accepted-rootid-proc-prefix",
                    accepted != null && accepted.path("rootId").asText("").startsWith("proc_"),
                    "rootId=" + (accepted != null ? accepted.path("rootId").asText("none") : "none")));
            List<Long> seqs = frames.stream().filter(f -> "observation".equals(frameType(f)))
                    .map(f -> f.path("sequence").asLong(-1L)).toList();
            checks.add(chk("observations-monotone-sequence",
                    seqs.isEmpty() || (seqs.equals(seqs.stream().sorted().distinct().toList())),
                    "sequences=" + seqs));
            long terminals = frames.stream().filter(f -> "terminal".equals(frameType(f))).count();
            checks.add(chk("settle-once-single-terminal", terminals == 1, "terminal frames=" + terminals));
            JsonNode t = frames.stream().filter(f -> "terminal".equals(frameType(f)))
                    .findFirst().orElse(null);
            checks.add(chk("terminal-completed-success",
                    t != null && "completed".equals(t.path("executionStatus").asText())
                            && "success".equals(t.path("taskVerdict").asText()),
                    "terminal=" + term(t)));
            return checks;
        });
        return withExit(res, 0);
    }

    private static ScenarioResult refusalsNonFatal(Cfg cfg) throws Exception {
        ScenarioResult res = withAdapter(cfg, "refusals", "plain", proc -> {
            List<Check> checks = new ArrayList<>();
            proc.send(MAPPER.writeValueAsString(hello("rf-hello")));
            MAPPER.readTree(proc.readLine()); // helloAck
            // (a) second hello before any dispatch -> non-fatal hello_already_negotiated
            proc.send(MAPPER.writeValueAsString(hello("rf-hello2")));
            JsonNode e2 = MAPPER.readTree(proc.readLine());
            checks.add(chk("hello-already-negotiated-nonfatal",
                    "error".equals(frameType(e2)) && "hello_already_negotiated".equals(errorCode(e2)),
                    "frame=" + frameType(e2) + " code=" + errorCode(e2)));
            // (b) unknown field on hello -> unknown_field naming the path hello.bogus
            ObjectNode bogus = hello("rf-obs");
            bogus.put("bogus", 1);
            proc.send(MAPPER.writeValueAsString(bogus));
            JsonNode e3 = MAPPER.readTree(proc.readLine());
            checks.add(chk("unknown-field-path-reported",
                    "error".equals(frameType(e3)) && "unknown_field".equals(errorCode(e3))
                            && e3.path("message").asText("").contains("hello.bogus"),
                    "code=" + errorCode(e3) + " message=" + e3.path("message").asText("?")));
            // (c) unsupported input schemaVersion -> input_rejected, never dispatched
            ObjectNode bad = submit("rf-bad-schema", "x");
            ((ObjectNode) bad.get("input")).put("schemaVersion", 2);
            proc.send(MAPPER.writeValueAsString(bad));
            JsonNode e1 = MAPPER.readTree(proc.readLine());
            checks.add(chk("input-rejected-schema-2",
                    "error".equals(frameType(e1)) && "input_rejected".equals(errorCode(e1)),
                    "frame=" + frameType(e1) + " code=" + errorCode(e1)));
            // (d) the session still dispatches after all three non-fatal refusals;
            // post-terminal frames are NOT answered (terminal closes ordinary
            // input, Outbound.scala), so session-level probes precede settlement.
            // Run frames and error frames share one stream and accepted may
            // follow leading observations, so scan for the accepted frame.
            proc.send(MAPPER.writeValueAsString(submit("rf-good", "Say hi")));
            List<JsonNode> scan = readUntil(proc, f -> "accepted".equals(frameType(f)));
            boolean acceptedSeen = scan.stream().anyMatch(f -> "accepted".equals(frameType(f)));
            List<String> types = scan.stream().map(XeLangChain4jConsumer::frameType).toList();
            checks.add(chk("session-continues-after-refusal", acceptedSeen, "frames=" + types));
            drainToTerminal(proc);
            return checks;
        });
        return withExit(res, 0);
    }

    private static ScenarioResult refusalsMalformed(Cfg cfg) throws Exception {
        ScenarioResult res = withAdapter(cfg, "refusals", "plain", proc -> {
            proc.send("{not json");
            JsonNode e = MAPPER.readTree(proc.readLine());
            return List.of(chk("malformed-frame-fatal-error-frame",
                    "error".equals(frameType(e)) && "malformed_frame".equals(errorCode(e)),
                    "code=" + errorCode(e)));
        });
        return withExit(res, 20);
    }

    private static ScenarioResult refusalsOversize(Cfg cfg) throws Exception {
        ScenarioResult res = withAdapter(cfg, "refusals", "plain", proc -> {
            proc.send(MAPPER.writeValueAsString(hello("of-hello")));
            MAPPER.readTree(proc.readLine());
            ObjectNode big = observe("of-big", 0);
            big.put("padding", "x".repeat(1048600)); // XE-WIRE-02-oversize: > 1048576 payload bytes
            proc.send(MAPPER.writeValueAsString(big));
            JsonNode e = MAPPER.readTree(proc.readLine());
            return List.of(chk("oversize-frame-fatal-error-frame",
                    "error".equals(frameType(e)) && "oversize_frame".equals(errorCode(e)),
                    "code=" + errorCode(e)));
        });
        return withExit(res, 20);
    }

    private static ScenarioResult refusalsDuplicateSubmit(Cfg cfg) throws Exception {
        ScenarioResult res = withAdapter(cfg, "refusals", "plain", proc -> {
            List<Check> checks = new ArrayList<>();
            proc.send(MAPPER.writeValueAsString(hello("ds-hello")));
            MAPPER.readTree(proc.readLine());
            proc.send(MAPPER.writeValueAsString(submit("ds-1", "Say hi")));
            proc.send(MAPPER.writeValueAsString(submit("ds-2", "Say hi again")));
            // the duplicate_submit error races run 1's stream frames; scan until
            // it has been seen AND the owned root has settled
            List<JsonNode> seen = new ArrayList<>();
            boolean dupErr = false;
            while (true) {
                String line = proc.readLine();
                if (line == null) break;
                JsonNode v = MAPPER.readTree(line);
                seen.add(v);
                if ("error".equals(frameType(v)) && "duplicate_submit".equals(errorCode(v))) dupErr = true;
                if (dupErr && "terminal".equals(frameType(v))) break;
            }
            JsonNode dupFrame = seen.stream()
                    .filter(f -> "error".equals(frameType(f)) && "duplicate_submit".equals(errorCode(f)))
                    .findFirst().orElse(null);
            checks.add(chk("duplicate-submit-refused",
                    dupFrame != null && "ds-2".equals(dupFrame.path("requestId").asText("")),
                    "error=" + (dupFrame != null ? dupFrame.path("requestId").asText("?") : "none")));
            long terminals = seen.stream().filter(f -> "terminal".equals(frameType(f))).count();
            checks.add(chk("duplicate-submit-root-still-settles", terminals == 1, "terminals=" + terminals));
            return checks;
        });
        return withExit(res, 0);
    }

    private static ScenarioResult observeDedup(Cfg cfg) throws Exception {
        ScenarioResult res = withAdapter(cfg, "observe-dedup", "plain", proc -> {
            List<Check> checks = new ArrayList<>();
            proc.send(MAPPER.writeValueAsString(hello("od-hello")));
            MAPPER.readTree(proc.readLine());
            proc.send(MAPPER.writeValueAsString(submit("od-run", "Say hi")));
            // mirror XE-WIRE-01: observe(afterSequence=0) goes out right after
            // accepted, before consuming the live stream; observe is ordinary
            // input and is not answered post-terminal
            List<JsonNode> scan = readUntil(proc, f -> "accepted".equals(frameType(f)));
            checks.add(chk("accepted-before-observe",
                    scan.stream().anyMatch(f -> "accepted".equals(frameType(f))),
                    "frames=" + scan.stream().map(XeLangChain4jConsumer::frameType).toList()));
            proc.send(MAPPER.writeValueAsString(observe("od-replay", 0)));
            List<JsonNode> frames = drainToTerminal(proc);
            List<Long> seqs = frames.stream().filter(f -> "observation".equals(frameType(f)))
                    .map(f -> f.path("sequence").asLong(-1L)).toList();
            // dedup semantics: after observe(afterSequence=0) the stream
            // continues from the client's watermark; sequences already
            // delivered live are NOT re-delivered
            checks.add(chk("observations-nonempty", !seqs.isEmpty(), "sequences=" + seqs));
            checks.add(chk("sequences-strictly-increasing",
                    seqs.equals(seqs.stream().sorted().distinct().toList()), "sequences=" + seqs));
            checks.add(chk("no-duplicate-frames-after-dedup",
                    seqs.stream().distinct().count() == seqs.size(),
                    "unique=" + seqs.stream().distinct().count() + "/" + seqs.size()));
            long terminals = frames.stream().filter(f -> "terminal".equals(frameType(f))).count();
            checks.add(chk("terminal-settles-once", terminals == 1, "terminals=" + terminals));
            return checks;
        });
        return withExit(res, 0);
    }

    private static ScenarioResult toolTask(Cfg cfg) throws Exception {
        ScenarioResult res = withAdapter(cfg, "tool-task", "coder", proc -> {
            List<Check> checks = new ArrayList<>();
            proc.send(MAPPER.writeValueAsString(hello("tt-hello")));
            MAPPER.readTree(proc.readLine());
            proc.send(MAPPER.writeValueAsString(submit("tt-run",
                    "Create the marker file marker.txt in the workspace root using the proc_run tool.")));
            List<JsonNode> frames = drainToTerminal(proc);
            JsonNode t = frames.stream().filter(f -> "terminal".equals(frameType(f)))
                    .findFirst().orElse(null);
            checks.add(chk("terminal-completed-success",
                    t != null && "completed".equals(t.path("executionStatus").asText())
                            && "success".equals(t.path("taskVerdict").asText()),
                    "terminal=" + term(t)));
            var artPaths = new java.util.HashSet<String>();
            boolean hashesOk = true;
            if (t != null) {
                for (JsonNode a : t.path("artifacts")) {
                    artPaths.add(a.path("path").asText());
                    if (a.path("sha256").asText("").length() != 64) hashesOk = false;
                }
            }
            checks.add(chk("artifacts-manifest-nonempty",
                    artPaths.contains("run.json") && artPaths.contains("result.json"),
                    "artifacts=" + artPaths));
            checks.add(chk("artifact-sha256-64hex", hashesOk,
                    "terminal artifact digests are 64-hex sha256"));
            checks.add(toolParityCheck());
            return checks;
        });
        return withExit(res, 0);
    }

    private static ScenarioResult extractionTask(Cfg cfg) throws Exception {
        ScenarioResult res = withAdapter(cfg, "extraction-task", "plain", proc -> {
            List<Check> checks = new ArrayList<>();
            proc.send(MAPPER.writeValueAsString(hello("ex-hello")));
            MAPPER.readTree(proc.readLine());
            proc.send(MAPPER.writeValueAsString(submit("ex-run",
                    "Extract the total amount from the invoice text as a JSON object with a numeric key total.")));
            List<JsonNode> frames = drainToTerminal(proc);
            JsonNode t = frames.stream().filter(f -> "terminal".equals(frameType(f)))
                    .findFirst().orElse(null);
            checks.add(chk("terminal-completed-success",
                    t != null && "completed".equals(t.path("executionStatus").asText())
                            && "success".equals(t.path("taskVerdict").asText()),
                    "terminal=" + term(t)));
            boolean hasResult = t != null;
            if (t != null) {
                hasResult = false;
                for (JsonNode a : t.path("artifacts")) {
                    if ("result.json".equals(a.path("path").asText())) hasResult = true;
                }
            }
            checks.add(chk("result-artifact-present", hasResult, "terminal lists result.json"));
            checks.add(chk("constrained-output-fact-registered", true,
                    "RAI-202 fact: the adapter exposes no native constrained/JSON output mode "
                            + "(RAIDER_OPENAI_EXTRA_BODY is an untyped escape hatch without guarantees); "
                            + "schema adherence here is prompt-only discipline against a scripted stand "
                            + "and is validated by the independent checker, not by Raider verdict machinery"));
            return checks;
        });
        return withExit(res, 0);
    }

    private static ScenarioResult cancelMidRun(Cfg cfg) throws Exception {
        ScenarioResult res = withAdapter(cfg, "cancel-mid-run", "coder", proc -> {
            List<Check> checks = new ArrayList<>();
            proc.send(MAPPER.writeValueAsString(hello("cm-hello")));
            MAPPER.readTree(proc.readLine());
            proc.send(MAPPER.writeValueAsString(submit("cm-run",
                    "Create the marker file marker.txt in the workspace root using the proc_run tool.")));
            // run frames and error frames share one stream and observations may
            // precede accepted (journal events queue ahead of the receipt), so
            // scan until BOTH accepted and a first observation have been seen
            List<JsonNode> pre = new ArrayList<>();
            boolean acceptedOk = false;
            boolean sawFirstObs = false;
            while (true) {
                String line = proc.readLine();
                if (line == null) break;
                JsonNode v = MAPPER.readTree(line);
                pre.add(v);
                if ("accepted".equals(frameType(v))) acceptedOk = true;
                if ("observation".equals(frameType(v))) sawFirstObs = true;
                if (acceptedOk && sawFirstObs) break;
            }
            if (acceptedOk && sawFirstObs) {
                proc.send(MAPPER.writeValueAsString(cancel("cm-run")));
            }
            // collect everything until the cancelAck has been seen AND the
            // terminal; stop at the terminal in any case so a missing ack
            // cannot hang the reader
            List<JsonNode> rest = new ArrayList<>();
            boolean ackSeen = false;
            while (true) {
                String line = proc.readLine();
                if (line == null) break;
                JsonNode v = MAPPER.readTree(line);
                rest.add(v);
                if ("cancelAck".equals(frameType(v)) && "accepted".equals(v.path("status").asText())) ackSeen = true;
                if ("terminal".equals(frameType(v))) break;
            }
            List<String> preTypes = pre.stream().map(XeLangChain4jConsumer::frameType).toList();
            checks.add(chk("cancel-accepted-before-terminal", acceptedOk && sawFirstObs,
                    "accepted=" + acceptedOk + " firstObs=" + sawFirstObs + " pre=" + preTypes));
            JsonNode ack = rest.stream().filter(f -> "cancelAck".equals(frameType(f)))
                    .findFirst().orElse(null);
            checks.add(chk("cancel-ack-accepted",
                    ack != null && "accepted".equals(ack.path("status").asText()),
                    "cancelAck=" + (ack != null ? ack.path("status").asText("?") : "none")));
            JsonNode t = rest.stream().filter(f -> "terminal".equals(frameType(f)))
                    .findFirst().orElse(null);
            checks.add(chk("terminal-cancelled-incomplete",
                    t != null && "cancelled".equals(t.path("executionStatus").asText())
                            && "incomplete".equals(t.path("taskVerdict").asText()),
                    "terminal=" + term(t)));
            checks.add(chk("cleanup-complete",
                    t != null && "complete".equals(t.path("cleanupStatus").asText()),
                    "cleanupStatus=" + (t != null ? t.path("cleanupStatus").asText("?") : "?")));
            return checks;
        });
        return withExit(res, 26);
    }

    private static ScenarioResult eofUncertain(Cfg cfg) throws Exception {
        ScenarioResult res = withAdapter(cfg, "eof-uncertain", "coder", proc -> {
            List<Check> checks = new ArrayList<>();
            proc.send(MAPPER.writeValueAsString(hello("eo-hello")));
            MAPPER.readTree(proc.readLine());
            proc.send(MAPPER.writeValueAsString(submit("eo-run",
                    "Create the marker file marker.txt in the workspace root using the proc_run tool.")));
            // scan until accepted (observations may precede it on the stream)
            List<JsonNode> pre = readUntil(proc, f -> "accepted".equals(frameType(f)));
            checks.add(chk("dispatch-accepted-before-eof",
                    pre.stream().anyMatch(f -> "accepted".equals(frameType(f))),
                    "frames=" + pre.stream().map(XeLangChain4jConsumer::frameType).toList()));
            proc.closeStdin(); // uncertain mutation: the client disappears mid-run
            List<JsonNode> frames = drainToTerminal(proc);
            JsonNode t = frames.stream().filter(f -> "terminal".equals(frameType(f)))
                    .findFirst().orElse(null);
            checks.add(chk("eof-after-dispatch-settles", t != null,
                    "terminal=" + (t != null ? t.path("executionStatus").asText("none") : "none")));
            checks.add(chk("terminal-cancelled-uncertain",
                    t != null && "cancelled".equals(t.path("executionStatus").asText())
                            && "unavailable".equals(t.path("accountingStatus").asText()),
                    "terminal=" + (t != null ? t.path("executionStatus").asText() + "/"
                            + t.path("accountingStatus").asText() : "none")));
            return checks;
        });
        return withExit(res, 26);
    }

    // ----- driver -----------------------------------------------------------------

    private static List<ScenarioResult> runScenarios(Cfg cfg) throws Exception {
        List<ScenarioResult> out = new ArrayList<>();
        if (cfg.scenarios().contains("handshake-terminal")) out.add(handshakeTerminal(cfg));
        if (cfg.scenarios().contains("refusals")) {
            out.add(refusalsNonFatal(cfg));
            out.add(refusalsMalformed(cfg));
            out.add(refusalsOversize(cfg));
            out.add(refusalsDuplicateSubmit(cfg));
        }
        if (cfg.scenarios().contains("observe-dedup")) out.add(observeDedup(cfg));
        if (cfg.scenarios().contains("tool-task")) out.add(toolTask(cfg));
        if (cfg.scenarios().contains("extraction-task")) out.add(extractionTask(cfg));
        if (cfg.scenarios().contains("cancel-mid-run")) out.add(cancelMidRun(cfg));
        if (cfg.scenarios().contains("eof-uncertain")) out.add(eofUncertain(cfg));
        return out;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(Files.readAllBytes(path));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void writeManifest(Cfg cfg, List<ScenarioResult> results) throws Exception {
        ObjectNode manifest = MAPPER.createObjectNode();
        manifest.put("ecosystem", "langchain4j");
        ObjectNode pinned = manifest.putObject("pinned");
        pinned.put("framework", "dev.langchain4j:langchain4j:1.22.0");
        pinned.put("adapterJar", cfg.adapterJar().toString());
        pinned.put("adapterJarSha256", sha256(cfg.adapterJar()));
        ArrayNode scenarios = manifest.putArray("scenarios");
        for (ScenarioResult r : results) {
            ObjectNode s = scenarios.addObject();
            s.put("name", r.name());
            s.put("ok", r.ok());
            if (r.adapterExitCode() != null) s.put("adapterExitCode", r.adapterExitCode());
            ArrayNode checks = s.putArray("checks");
            for (Check c : r.checks()) {
                ObjectNode cn = checks.addObject();
                cn.put("name", c.name());
                cn.put("pass", c.pass());
                cn.put("detail", c.detail());
            }
        }
        Files.createDirectories(cfg.manifest().getParent());
        Files.writeString(cfg.manifest(), MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(manifest), StandardCharsets.UTF_8);
    }

    public static void main(String[] argv) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < argv.length; i += 2) {
            if (argv[i].startsWith("--")) m.put(argv[i], argv[i + 1]);
        }
        if (!m.containsKey("--adapter-jar") || !m.containsKey("--ws-root")
                || !m.containsKey("--out-root") || !m.containsKey("--manifest")) {
            System.err.println("xe-langchain4j-consumer: need --adapter-jar --ws-root --out-root --manifest "
                    + "[--scenario csv|all] [--stand-url url] [--key key]");
            System.exit(2);
        }
        List<String> scen = m.containsKey("--scenario")
                ? List.of(m.get("--scenario").split(","))
                : List.of("all");
        List<String> chosen = scen.contains("all") ? VALID_SCENARIOS : scen;
        for (String s : chosen) {
            if (!VALID_SCENARIOS.contains(s)) {
                System.err.println("xe-langchain4j-consumer: unknown scenario " + s);
                System.exit(2);
            }
        }
        Cfg cfg = new Cfg(Paths.get(m.get("--adapter-jar")), Paths.get(m.get("--ws-root")),
                Paths.get(m.get("--out-root")), Paths.get(m.get("--manifest")), chosen,
                m.get("--stand-url"), m.get("--key"));
        List<ScenarioResult> results = runScenarios(cfg);
        writeManifest(cfg, results);
        long failed = results.stream().filter(r -> !r.ok()).count();
        for (ScenarioResult r : results) {
            System.out.println("[xe-langchain4j] " + (r.ok() ? "PASS" : "FAIL") + " " + r.name()
                    + " exit=" + r.adapterExitCode());
            for (Check c : r.checks()) {
                if (!c.pass()) System.out.println("[xe-langchain4j]   failed check: " + c.name() + ": " + c.detail());
            }
        }
        System.out.println("[xe-langchain4j] manifest=" + cfg.manifest()
                + " scenarios=" + results.size() + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
