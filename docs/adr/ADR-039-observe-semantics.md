# ADR-039: Observe delivery semantics — at-least-once with consumer-side dedup

Status: **Proposed** (authored by card RAI-248 on `lane/rai-248`; coordinator
review and integration pending). Date: 2026-10-30. Builds on
[ADR-037](ADR-037-local-process-protocol.md) (bounded local process protocol,
§2–§3 message table) and records the observe semantics **as measured** by
RAI-233 (`docs/work/records/RAI-233.md`; fix merged on `main` as `0f67323` —
"Merge lane/rai-233: Session observe/live-pump coordination (observe-dedup
race fix + langchain4j probes)"). Documentation only: this ADR changes no
code, no wire frame, no gate policy, no frozen consumer, and no probe.

## 1. Problem

The `raider process-api` observe guarantee — what delivery strength a consumer
may assume for `observation` frames, what deduplication is expected on the
client, and what settles cancel-vs-completed — existed only in three places:
the normative rows of ADR-037, the implementation and its probes
(`XeObserveDedupProbe`, `XeObserveCancelProbe`), and the RAI-233 work record.
Nothing addressed a consumer reading the contract: which parts are guarantee
and which parts are measured behavior of one adapter version. RAI-231
demonstrated the cost of ambiguity: the uncoordinated adapter duplicated
observation sequences on the wire under load (RAI-231 limitation 1: 2/6
iterations of a targeted block-shape run, `--scenario refusals,observe-dedup`
with one consumer JVM, failed intermittently, as did full-corpus
`run-all.sh` attempts 1 and 2; the record's only load figure, 1-minute host
load 290–324, belongs to the passing attempt-3 corpus run, which RAI-233
limitation 1 summarizes as "RAI-231 needed ~290"), and the first "no
duplicates" intuition turned out to be a property that had to be BUILT and
MEASURED, not assumed. This ADR
fixes the documented semantics with citations to the RAI-233 evidence; where a
statement is not backed by evidence, it is labeled as future work, not a
promise.

## 2. The observe guarantee, as measured

### 2.1 Delivery guarantee: at-least-once; the consumer dedups by sequence

Observation re-delivery on the process-api wire is **at-least-once**. The
client MUST dedup by observation `sequence`. There is **NO exactly-once
claim** — not at the protocol level, not across sessions, not across adapter
restarts, and not for the underlying work.

This is the contract as written and unchanged by RAI-233:

- ADR-037 §3: `observe` is "cursor/sequence from which to observe. Observation
  never starts or repeats work"; `observation` carries a "Correlated, monotonic
  per-root sequence"; gaps caused by coalesced progress are explicit (ADR-037
  §2: a coalesced sequence gap/count "must be represented in a later
  observation").
- `contracts/process-api/README.md` (§Streaming, backpressure, observation
  re-delivery): "`observe` is a bounded re-delivery query over the adapter's
  own retained observation envelopes (drop-oldest, bounded) … Re-delivery is
  at-least-once; the client dedups by `sequence`. `observe` with nothing
  retained past the requested sequence answers `observe_failed` (session
  continues)."

RAI-233 did not upgrade this contract, and this ADR does not either: the RAI-233
fix (`modules/cli/src/main/scala/raider/cli/processapi/Session.scala`) changed
no frame shapes and made no schema change (`docs/work/records/RAI-233.md`,
"Changed paths": "No public API/schema change").

### 2.2 What RAI-233 measured — and what that measurement is

The RAI-233 fix coordinates the two wire writers (live pump and observe
replay) through a shared delivery pointer: `delivery: Ref[Long]` holds the
highest sequence admitted to the wire by either path, and
`deliveryLock: Semaphore(1)` serializes admissions. A replay batch filters the
retention snapshot by `seq > afterSequence && seq > delivery`, commits the
pointer uninterruptibly BEFORE enqueueing, and the live pump admits a frame
under the same lock only when `env.seq > delivery` (RAI-233 record, "Resulting
behavior"; `Session.scala` `observe`/`forward`).

MEASURED result on the fixed candidate adapter jar
`afc9c14a3d7ed227ef5346a38cd21bc47e01454ecb0fb7133365bb1c114fdf5a`
(scalafmt-formatted; evidence in `docs/work/records/RAI-233.md`):

- **Red-first proof that duplicates were real, not hypothetical.** On the
  unfixed (sabotage-class) jar `b1950f31d811a7af030accf5b84199d133c25cf089921610d053959b4da9e7d5`,
  `XeObserveDedupProbe` failed 5/5 deterministically (probe exit 1) with the
  verbatim leak `sequences-strictly-increasing FAIL sequences=[2, 3, 4, 5, 6,
  1, 2, 3, 4, 5, 6]` and `no-duplicate-frames-after-dedup FAIL unique=6/11`
  (matching RAI-231's disclosed signature). Artifact:
  `artifacts/rai-233-lane/red/dedup-probe-red.log`, sha256
  `efb3d3ec227027b14f94d17f9e24b526367f4222a6cffe4d5c4f2f17671fe69e`.
- **Green probes.** `XeObserveDedupProbe --delay-ms 30` ×5: 5/5 `RESULT PASS`,
  exit 0. `XeObserveCancelProbe` ×10: 10 runs × 10 iterations = **100/100
  PASS**, every recorded outcome `cancel=accepted terminal=cancelled exit=26`,
  `failures=0` per run, no frame drops/duplicates/gaps. Artifact:
  `artifacts/rai-233-lane/green/probes-formatted.log`, sha256
  `46cf0eab1cbdfa7d0b613ab9da7af7928d78885c94b7d7ee3963ec43e0d0fb5c`.
- **Loaded corpus.** 6/6 consecutive full-corpus `run-all.sh` runs under
  in-scope load (1-min load readings 4.04→22.38→14.43 across runs), each with
  `PASS=20`, `[xe-checker] findings=160 failed=0`, `run-all exit: 0`; the
  fixed-window contrast run delivered `sequences-strictly-increasing True
  sequences=[2, 3, 4, 5, 6]`, `unique=5/5`. Final checker report:
  `artifacts/rai-233-lane/green/checker-report-fmt-run6.json`, sha256
  `b5ecf2e4f8f3c92cc465917cb2f06ef0c01a42f76c80ab7348191c435877ab9b`.

What this measurement IS: demonstrated suppression of uncoordinated
live/replay double delivery within one adapter session, under the recorded
conditions (mock provider, one consumer, one adapter process, the recorded run
counts above). What it is NOT: a protocol guarantee. A duplicate `observation`
sequence remains a wire event every consumer must tolerate (§2.3); the
evidence is bounded to the recorded runs and does not prove absence of
duplicates under all schedules and loads (RAI-233 limitation 1 discloses that
the sabotage corpus race never fired at achievable load, and that no live
IndependentChecker flag artifact is claimed on that lane). Accordingly the
contract stays at-least-once, and no exactly-once claim is made.

### 2.3 Dedup expectations for consumers

A consumer of `raider process-api` observations:

1. **Keeps a client watermark per root and dedups by `sequence`** (monotonic
   per root; the hub's per-job `seq`): ignore frames at or below the highest
   sequence already applied. This is load-bearing logic, not defensive
   clutter — the contract is at-least-once (§2.1).
2. **Treats `observe_failed` as a documented no-news answer, not a fault**:
   "no undelivered observations after sequence N" (verbatim adapter text) is
   answered when the pointer already covers every retained frame past the
   watermark; the session continues and live observations keep flowing. The
   frozen consumers tolerate `observe_failed` error frames, and
   `XeObserveDedupProbe` records error frames without stopping the stream.
3. **Treats an interior sequence gap as legal ONLY when covered by a
   `skipped {fromSequence, count}` marker** (backpressure coalescing; ADR-037
   §2). `XeObserveCancelProbe` enforces exactly this per iteration. Trailing
   absence after cancellation is legal — a cancelled run stops streaming.
4. **Expects exactly one `terminal` frame per dispatched root**, written last.
   `cancelAck.status = "accepted"` means REQUESTED, not settled; the terminal
   frame's `executionStatus` and the adapter exit code are authoritative.
5. **Correlates frames by `requestId`, not arrival order**: the receipt queue
   is FIFO, but an observation with sequence 1 can be forwarded before the
   `accepted` frame lands (RAI-201 exit-contract errata, commit `472d3f4`,
   Session comment fix).
6. **Does not expect ancient sequences from a replay**: retention is
   drop-oldest bounded; `observe` answers `observe_failed` for sequences below
   the retention floor. Absence of old sequences is the documented bound, not
   a delivery failure.

### 2.4 Cancel-settle semantics

Consistent with the RAI-201 exit-contract errata (commit `472d3f4`, which
narrowed the cancelled/exit-26 row to "client cancel / EOF after dispatch /
peer death" and routed fatal protocol failure to `failed`/exit 20 with bounded
cleanup):

- **Accepted cancel while live** (`cancelAck.status = "accepted"`): the root
  settles with exactly one terminal frame, `executionStatus "cancelled"`,
  adapter exit 26. MEASURED: 100/100 `XeObserveCancelProbe` iterations in the
  RAI-233 green evidence all recorded `cancel=accepted terminal=cancelled
  exit=26` (`docs/work/records/RAI-233.md`, "Acceptance evidence").
- **Late cancel after settle** (`cancelAck.status = "already_terminal"`): the
  run's own verdict stands — where the run had already completed, the terminal
  is `completed` with adapter exit 0. This is the mapping asserted by
  `XeObserveCancelProbe`'s checks ("already_terminal settles completed with
  exit 0" — probe header and `allChecksPass()`); the retained RAI-233 green
  log recorded only `accepted→cancelled` outcomes, so the already_terminal
  branch is probe-asserted contract consistent with the errata's terminal
  vocabulary, not a separately measured outcome in that log.
- **Settle-once discipline**: probes assert `terminal-settles-once` /
  one-terminal per run regardless of where the cancel lands in the
  observe/cancel race. RAI-233 introduced no new cancel semantics; the probes
  verify the observe/live-pump coordination does not disturb the existing
  settle behavior.

### 2.5 The direction exactly-once coordination would have to take (future work, NOT a promise)

If a future card ever wants a protocol-level exactly-once observe story, it
would have to address, at minimum:

1. **Durable, resumable delivery cursors.** The RAI-233 `delivery: Ref[Long]`
   is volatile, per-process, session-scoped state. Exactly-once across
   reconnects or adapter restarts requires persisted cursor state plus a
   client-visible session/epoch identity, so stale watermarks from a dead
   process cannot be confused with the live one.
2. **Acknowledged redelivery keys.** Replay suppression would have to be
   keyed by a persistent watermark the adapter acknowledges per request — not
   by best-effort in-memory pointer checks.
3. **Per-consumer cursor ownership.** The current pointer is session-global
   (first writer wins under the lock). Multiple consumers on one root would
   need explicit cursor ownership or per-consumer cursors; otherwise
   "delivered" is ill-defined.
4. **No spillover into work semantics.** End-to-end exactly-once of the
   executed work stays out of scope entirely: repository rules forbid claiming
   exactly-once execution or durable resume for arbitrary Scala lambdas, and
   the headless EOF-after-dispatch path deliberately stays exit 26
   (uncertain), never a fabricated resume.

This subsection is a direction statement only. No card is registered, no
design exists, and nothing here promises exactly-once. Until a future ADR
re-opens this decision with evidence, at-least-once delivery with consumer-side
dedup is the guarantee. The parallel in-process stance is unchanged (ADR-024:
subscribers must deduplicate by `(jobId, seq)`).

## 3. Evidence index

Every semantic claim above traces to the RAI-233 record; identifiers are
verbatim:

| Claim | Evidence (all from `docs/work/records/RAI-233.md` unless noted) |
| --- | --- |
| Duplicates were real pre-fix (no free exactly-once) | `XeObserveDedupProbe` 5/5 FAIL on jar `b1950f31…`; leak `sequences-strictly-increasing FAIL sequences=[2, 3, 4, 5, 6, 1, 2, 3, 4, 5, 6]`, `no-duplicate-frames-after-dedup FAIL unique=6/11`; `artifacts/rai-233-lane/red/dedup-probe-red.log` sha256 `efb3d3ec227027b14f94d17f9e24b526367f4222a6cffe4d5c4f2f17671fe69e` |
| Coordinated suppression measured | `XeObserveDedupProbe` ×5 = 5/5 PASS exit 0; `XeObserveCancelProbe` ×10 = 100/100 PASS; `artifacts/rai-233-lane/green/probes-formatted.log` sha256 `46cf0eab1cbdfa7d0b613ab9da7af7928d78885c94b7d7ee3963ec43e0d0fb5c`; candidate jar `afc9c14a3d7ed227ef5346a38cd21bc47e01454ecb0fb7133365bb1c114fdf5a` |
| Corpus-stable under load, checker intact | 6/6 loaded `run-all.sh` runs, `PASS=20`, `findings=160 failed=0`, exit 0 each; `artifacts/rai-233-lane/green/checker-report-fmt-run6.json` sha256 `b5ecf2e4f8f3c92cc465917cb2f06ef0c01a42f76c80ab7348191c435877ab9b` |
| Mechanism (delivery pointer + lock) | RAI-233 record "Resulting behavior"; `Session.scala` `observe`/`forward` (RAI-233 fix, merged as `0f67323`) |
| At-least-once contract, client dedup, `observe_failed`, `skipped` markers | ADR-037 §2–§3; `contracts/process-api/README.md` §Streaming, backpressure, observation re-delivery |
| Cancel vocabulary (`cancelled`/26 scope; `already_terminal`; protocol-fatal → `failed`/20) | RAI-201 exit-contract errata, commit `472d3f4`; `contracts/process-api/README.md` §Cancellation, EOF, death, signals |
| `accepted` ≠ settled; one terminal; requestId correlation | ADR-037 §3 (`cancelAck` row), §5; errata `472d3f4` Session comment fix |
| Evidence bounds (no all-schedules claim) | RAI-233 record, "Limitations / blockers" 1 (no live checker flag claimed; corpus race never fired at achievable load) |
| Probe sources | `contracts/acceptance/cross-ecosystem/langchain4j-consumer/XeObserveDedupProbe.java`, `contracts/acceptance/cross-ecosystem/langchain4j-consumer/XeObserveCancelProbe.java` |

## 4. Consequences

- Consumers must implement and keep sequence-dedup (§2.3); removing it is a
  contract violation, not an optimization.
- The two probes are the executable form of this ADR. They remain lane
  evidence, not registered lane tooling (registration is a separate
  coordinator decision — RAI-248 non-goal).
- This ADR records the semantics at RAI-233's merge (`0f67323`). Any adapter
  change that alters delivery or settle behavior must update this ADR with new
  measured evidence; aspiration is not evidence.
- Consumers reading only the cross-ecosystem corpus get the same rules in
  `contracts/acceptance/cross-ecosystem/README.md` §Observe semantics probes.

## 5. Non-goals

No code, wire, probe, fixture, gate-policy, or frozen-consumer change; no
exactly-once commitment of any kind; no claims about providers or transports
beyond the `raider process-api` observe path; no registration of the probes as
standard lane tooling; no rewrite of consumer examples beyond the
cross-ecosystem README section delivered by RAI-248.
