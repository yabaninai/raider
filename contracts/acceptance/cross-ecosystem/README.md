# Cross-ecosystem acceptance corpus (RAI-205)

This directory holds the RAI-205 cross-ecosystem acceptance corpus: a scripted
OpenAI-compatible stand (`stand/`), two third-party-ecosystem consumers
(`langchain4j-consumer/`, `llm4s-consumer/`) that drive `raider process-api`
through the accepted local stdio adapter contract, an independent checker
(`checker/`), the scenario drivers (`scenarios/`), the committed goldens
(`expected/golden-checks.json`), and the pinned third-party closure manifest
(`consumer-deps.json`). The normative acceptance document is
`docs/acceptance/cross-ecosystem/RAI-205-cross-ecosystem.md`; this README only
maintains the dependency-pin contract and the observe-semantics probe contract
(RAI-233, see below).

## Observe semantics probes — `XeObserveDedupProbe` / `XeObserveCancelProbe` (RAI-233)

`langchain4j-consumer/XeObserveDedupProbe.java` and
`langchain4j-consumer/XeObserveCancelProbe.java` are standalone lane probes
delivered by RAI-233 (merge `0f67323`) alongside the Session observe/live-pump
delivery-pointer fix. They are the executable form of the observe semantics
recorded in [ADR-039](../../../docs/adr/ADR-039-observe-semantics.md):
observation re-delivery is **at-least-once**, consumers **must dedup by
`sequence`**, and **no exactly-once claim** is made. They compile against the
same pinned closure (`consumer-deps.json`) and run standalone
(`--adapter-jar <jar> --ws-root <dir> --out-root <dir>`); `scenarios/lib.sh`
`compile_all` does not compile them, and registering them as standard corpus
scenarios is a separate coordinator decision.

### What `XeObserveDedupProbe` asserts

One mock-provider run: the probe reads until the run is demonstrably live
(`accepted` AND first `observation`), holds still for `--delay-ms` (default
30) so the live stream drains, then sends `observe(afterSequence=0)` —
deliberately widening the scheduling window the loaded corpus hits
stochastically (RAI-231). Checks: `observations-nonempty`,
`sequences-strictly-increasing` (no reorder across replay+live),
`no-duplicate-frames-after-dedup` (no sequence delivered twice),
`terminal-settles-once`, `adapter-exit-0` (terminal `completed`, exit 0).
RAI-233 evidence: 5/5 deterministic FAIL on the unfixed adapter (verbatim leak
`sequences-strictly-increasing FAIL sequences=[2, 3, 4, 5, 6, 1, 2, 3, 4, 5, 6]`),
5/5 `RESULT PASS` exit 0 on the fixed candidate — run counts and artifact
hashes in `docs/work/records/RAI-233.md`.

### What `XeObserveCancelProbe` asserts

Per iteration (default 10), one mock-provider run raced with an immediate
`observe(afterSequence=0)` + `cancel` pair while the run is live. Checks: the
cancel is answered (`accepted` or `already_terminal`); exactly one `terminal`
frame settles the root; delivered sequences are strictly increasing with no
duplicates; no interior sequence gap without a
`skipped {fromSequence, count}` marker covering it (trailing absence after
cancellation is legal — cancelled runs stop streaming); the verdict matches
the race outcome — `accepted` → terminal `cancelled`, adapter exit 26;
`already_terminal` → terminal `completed`, exit 0. RAI-233 evidence: 10 runs ×
10 iterations = 100/100 PASS, every recorded outcome `cancel=accepted
terminal=cancelled exit=26` (`docs/work/records/RAI-233.md`).

### How consumers should treat duplicate observe events

1. Keep a client watermark per root and **dedup by observation `sequence`**:
   ignore frames at or below the highest sequence already applied. Re-delivery
   is at-least-once by contract; duplicates remain a wire event consumers must
   tolerate even though the coordinated adapter suppressed them in every
   RAI-233 run.
2. Treat `observe_failed` ("no undelivered observations after sequence N") as
   a documented no-news answer, not a fault — the session continues and live
   observations keep flowing (the frozen consumers tolerate it).
3. Treat an interior sequence gap as legal only when covered by a
   `skipped {fromSequence, count}` marker; trailing absence after
   cancellation is legal.
4. Expect exactly one `terminal` frame; `cancelAck.status = "accepted"` means
   requested, not settled — the terminal frame's `executionStatus` and the
   adapter exit code are authoritative, and a late `already_terminal` leaves
   the run's own verdict (`completed`/exit 0 where the run finished first).
5. Correlate frames by `requestId`, not arrival order (an observation with
   sequence 1 can precede the `accepted` frame — RAI-201 exit-contract
   errata, commit `472d3f4`).

## The pinned closure — `consumer-deps.json`

`consumer-deps.json` pins every third-party jar the corpus consumers compile
and run against: 31 entries today, each keyed `group:artifact:version` with
`path` (relative to the repository root, into the gitignored
`artifacts/tools/m2/` cache), `sha256`, and `size` (bytes). The drivers in
`scenarios/lib.sh` build their classpaths from these pins via `$M2`
(`DOTC_CP`, `SCALA_LIB`, `UPICKLE_CP`, `LLM4S_CP`, `L4J_DEPS`); nothing in the
corpus resolves dependencies over the network at run time. The cache is
provisioned out of band (see the m2-bootstrap card, board follow-up 11);
`scripts/bootstrap.sh` covers only the compile toolchain, not this closure.

Scope note: the 31-pin contract covers the consumer closures. Other jars under
`artifacts/tools/m2/` (for example the Scala 3.9.0 dotc compiler stack, or an
unpinned `slf4j-api 1.7.36`) are cache contents, not contract pins, and the
verification below intentionally does not check them.

## slf4j-api pin-maintenance note (langchain4j consumer)

`dev.langchain4j:langchain4j-core:1.22.0` binds `org.slf4j.Logger` statically:
the first consumer touch of the framework (`new UserMessage(...)` in
`XeLangChain4jConsumer.messageRoundTripCheck`) initializes
`dev.langchain4j.internal.Utils`, whose `<clinit>` resolves
`org.slf4j.LoggerFactory`. The pin is therefore load-bearing at **run time,
not compile time**:

- **Compile without the pin succeeds.** A controlled drift probe (re-run
  2026-10-29 on this tree in the scratch directory
  `/var/folders/2q/nmsh46097bv_82prfpbwh22c0000gn/T/opencode/rai-244-drift2/`;
  first run 2026-10-28 under `.../opencode/rai-244-drift/` with an identical
  signature; macOS 26.5.1, JDK 21.0.12.1; the exact
  `XeLangChain4jConsumer.java` compiled by `javac` against the pinned
  langchain4j-core 1.22.0 + jackson 2.22.x jars, slf4j-api removed) exited 0.
  `scenarios/lib.sh` `compile_all` has no way to see the drift: the missing
  jar changes nothing at compile time and the guard only checks that the
  output directories are non-empty.
- **Run without the pin fails on the first LangChain4j initialization.** A
  scratch probe reproducing the consumer's exact first-touch calls (the
  `UserMessage` round trip and the `ToolSpecification` parity declaration)
  against the same closure minus `slf4j-api` fails verbatim (transcript of
  the 2026-10-29 run, exit 1; the 2026-10-28 run printed the same frames):

  ```text
  Exception in thread "main" java.lang.NoClassDefFoundError: org/slf4j/LoggerFactory
  	at dev.langchain4j.internal.Utils.<clinit>(Utils.java:57)
  	at dev.langchain4j.internal.ValidationUtils.ensureNotEmpty(ValidationUtils.java:104)
  	at dev.langchain4j.data.message.UserMessage.<init>(UserMessage.java:105)
  	at dev.langchain4j.data.message.UserMessage.<init>(UserMessage.java:83)
  	at dev.langchain4j.data.message.UserMessage.<init>(UserMessage.java:62)
  	at Probe.main(Probe.java:14)
  Caused by: java.lang.ClassNotFoundException: org.slf4j.LoggerFactory
  	at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(BuiltinClassLoader.java:641)
  	at java.base/jdk.internal.loader.ClassLoaders$AppClassLoader.loadClass(ClassLoaders.java:188)
  	at java.base/java.lang.ClassLoader.loadClass(ClassLoader.java:526)
  	... 6 more
  ```

  With `slf4j-api 2.0.17` restored the identical probe prints
  `PROBE-OK roundTrip=true tool=touch_marker strict=true` and exits 0. The
  only residual output is SLF4J's no-binding warning
  (`No SLF4J providers were found ... Defaulting to no-operation (NOP)`),
  which is expected — the closure pins the API without a binding — and which
  `run_l4j` routes into `artifacts/xe-out/langchain4j-sl4j-warn.txt`.

The corpus pins two `slf4j-api` versions: **2.0.17** is the load-bearing one
(`L4J_DEPS` in `scenarios/lib.sh`); **2.0.18** is pinned in the contract but
not referenced by any driver classpath today. When re-pinning, keep the
`consumer-deps.json` entries and the `lib.sh` classpath lines consistent.

## Re-pinning on a consumer upgrade

On a LangChain4j (or Jackson/llm4s) upgrade:

1. Re-derive the runtime closure of the new version and fetch each jar into
   `artifacts/tools/m2/<group path>/<version>/`.
2. Update `consumer-deps.json`: key, `path`, `sha256`, `size` for every
   changed or added artifact (and remove entries the new closure drops).
3. Update the classpath lines in `scenarios/lib.sh` (`L4J_DEPS`, `LLM4S_CP`,
   `UPICKLE_CP`) to the new pinned paths. The compile toolchain jars in
   `DOTC_CP` are separate from this contract.
4. Verify the pins with the snippet below; it must print
   `31 pins checked, 0 drifted/missing` and exit 0 (the count tracks the file,
   so after adding/removing pins expect the new count with `0 drifted`).
5. Run the corpus: `bash contracts/acceptance/cross-ecosystem/scenarios/run-all.sh`.
   `compile_all` is the affected path — it compiles
   `langchain4j-consumer/XeLangChain4jConsumer.java` with `javac` against
   `L4J_DEPS` — but remember the failure mode above: a missing transitive jar
   can pass `compile_all` and only fail the consumer runs, so a re-pin is not
   proven until the full matrix (checker `ok=true`) is green.

### Pin verification snippet

Run from the repository root with a provisioned `artifacts/tools/m2/`
(python3, stdlib only). Verifies every pin's key consistency, jar existence,
sha256, and size; exits nonzero on any drift:

```bash
python3 -c '
import json,hashlib,os,sys
pins=json.load(open("contracts/acceptance/cross-ecosystem/consumer-deps.json"))
bad=0
for key in sorted(pins):
    p=pins[key]; why=""
    if key!="{0}:{1}:{2}".format(p["group"],p["artifact"],p["version"]): why="key-mismatch"
    elif not os.path.exists(p["path"]): why="missing-jar"
    else:
        b=open(p["path"],"rb").read()
        if hashlib.sha256(b).hexdigest()!=p["sha256"]: why="sha256-mismatch"
        elif len(b)!=p["size"]: why="size-mismatch"
    if why: print("DRIFT {0} ({1})".format(key,why)); bad+=1
    else: print("OK   {0}".format(key))
print("{0} pins checked, {1} drifted/missing".format(len(pins),bad))
sys.exit(1 if bad else 0)
'
```

On the current tree this prints one `OK` line per pin (31 lines) plus
`31 pins checked, 0 drifted/missing`, exit 0. The full verbatim transcript of
the clean run is in `docs/work/records/RAI-244.md`.
