# ADR-040: Chat close lifecycle — the facade owns real close semantics (side a)

Status: **Proposed** (authored by card RAI-263 on `lane/rai-263`; coordinator
review and integration pending). Date: 2026-11-02. Builds on
`docs/product-repl.md` §8 (turn serialization, history never erased) and §10
(repl-world interactions), and the working-product §4 W11 golden scenario
(protected text, lines 88–96). Code: `modules/repl/src/main/scala/raider/repl/facade/FacadeOps.scala`
(class `Chat`), tests `modules/repl/src/test/scala/raider/repl/facade/ChatCloseSpec.scala`
and `modules/repl/src/test/scala/raider/repl/dist/InstalledChatClosePtySpec.scala`.

## 1. Problem

The W11 golden scenario required a `chat.close()` that is real semantics, not
a placeholder: a close that is bounded, idempotent, preserves earlier history,
refuses later asks with a typed error, and settles an in-flight turn honestly.
The REPL facade (`Chat` in `FacadeOps.scala`) had two possible directions:

- **Side (a)**: the facade GAINS close semantics — `Chat` owns a lifecycle,
  interrupts its own in-flight turns, seals itself, and refuses post-close
  asks with a typed `RaiderError.InputValidation` (RA-INP family).
- **Side (b)**: the facade stays lifecycle-free and the REPL-level `:reset`
  /process machinery owns teardown — the golden text would describe a
  different object.

`docs/working-product.md` §4 (W11) is protected text outside RAI-263's allowed
paths and describes side (a) behavior verbatim; no ADR recorded the decision,
the lifecycle states, the completion-vs-interrupt pick order, or the
interaction with session jobs (RAI-132 steering mailboxes, RAI-059 cancel).
Consumers reading the facade had no contract for what `close()` guarantees
toward concurrent `ask`/`start`/`send` users of the same session.

## 2. Decision

Side (a). The facade owns real close semantics. `Chat` is the unit of
closure; the session, its job manager, and steering mailboxes are NOT closed
by a chat close. This makes the W11 golden text executable as written and
keeps REPL-level teardown (`:reset`, SIGTERM, `:quit`) a separate layer with
its own contracts.

### Rollback (side b)

Rolling back means reverting to side (b) — no facade close semantics, the
golden text stays aspirational. See §1 for the rejected side (b) rationale;
the unit-of-closure split in this section (chat-scoped, session untouched)
is what side (b) would have to replace if it were ever adopted.

## 3. Semantics

### 3.1 Lifecycle states and transitions

`Chat` carries `lifecycle: AtomicInteger` — `0` open, `1` closing, `2` closed
(sealed) — plus `closeMonitor` and `turnLock`. `close()` CASes `0→1` under
`closeMonitor`; a second closer sees `1` or `2` and returns immediately
(idempotent, multi-closer safe). After the in-flight turn fibers are
interrupted, the state becomes `2` and every later observer sees the sealed
chat. There is no reopen.

### 3.2 Completion-vs-interrupt pick order (terminal-pick order)

A turn's reply pair (user prompt + assistant reply) is appended to the chat
history ON the turn fiber itself, before any observer of the turn's `Exit`:

1. **Completion wins**: if the turn finishes before the interrupt lands, the
   reply stays in history and `ask` returns it — a reply is never fabricated
   and a completed reply is never dropped.
2. **Interrupt wins**: otherwise `ask` surfaces a typed
   `RaiderError.Cancelled` (no half state, no synthetic reply).
3. Exactly one of the two outcomes per turn (reply kept XOR typed
   Cancelled); earlier history entries are never erased by a close.

### 3.3 Post-close refusal

`ask` on a closing/closed chat refuses with
`RaiderError.InputValidation("chat is closed: close() was already called; open
a new chat with openSession(...)")` — the RA-INP family, a typed value, never
an exception escape or a silent no-op. This is refusal of a chat turn only
(§3.4): `Chat` has no `start` member; `AgentRef.start` creates session jobs
and is deliberately not refused by a chat close — jobs keep running and their
steering receipts keep settling.

### 3.4 Close is chat-scoped

Closing a chat does not touch the session: jobs keep running, steering
receipts still settle (`Queued → Applied` at the next turn boundary, or
expired when the job goes terminal), mailboxes seal only through their own
terminal watcher (RAI-132 conservation, SEND-03). A concurrent job cancel and
chat close settle independently and both bounded.

### 3.5 Bounded close

`close()` returns after interrupting the registered in-flight fibers; it does
not await unrelated work. Every close-related await in the acceptance suite
is bounded (10 s live-clock timeouts); "bounded" is an acceptance property,
not a wall-clock promise in the signature.

## 4. Evidence

Unit layer — `ChatCloseSpec` (real loop + scripted gate backend, all awaits
bounded):

- CHAT-01 golden: two asks, close twice, history preserved, post-close ask
  refuses typed.
- CHAT-02 close during an in-flight parked turn: typed Cancelled, no
  fabricated reply, delivered deltas preserved.
- CHAT-03 completion wins; sealed and idempotent afterwards.
- CHAT-03b close vs completing turn race: reply-kept XOR typed-Cancelled,
  12 rounds.
- CHAT-04 chat close does not disturb session jobs: steering Queued→Applied,
  job completes, mailbox seals.
- CHAT-04b close + job cancel concurrently: both settle typed; queued receipt
  resolves expired.
- CHAT-05 two concurrent closers settle once.

Installed layer — `InstalledChatClosePtySpec`: the unpacked distribution's
`raider-repl` under `/usr/bin/script` (real pty, real scala3-repl compiler,
`RAIDER_PROVIDER=mock`) replays the golden sequence; transcript saved under
`modules/repl/target/rai263-pty/`. Observed: one answer in history
(`res0: Int = 2`), close twice silent, history preserved after close
(`res1: Int = 2`), post-close ask caught as
`raider.core.RaiderError.InputValidation` (`REFUSED-TYPED`), exactly one
mock answer in the transcript, `:quit` exits 0.

## 5. Measured constraints discovered while building the evidence

- The session's budget admission defaults to `maxConcurrentLlm = 1`
  (`BudgetLedger.scala`): a chat turn and a job turn cannot be in flight
  simultaneously on one session unless the session is built with a higher
  slot count. Tests that need both parked at once construct their own session
  with `maxConcurrentLlm = 2`; the product default is unchanged.
- Steering message ids come from a facade-wide counter
  (`FacadeOps.nextSteerId`), so acceptance assertions must read ids from the
  receipt, not from literals.
- `FacadeOps.steer/owners/started` registries are keyed by `JobId`; zio-test
  runs tests in parallel by default, so specs that start jobs must declare
  `@@ TestAspect.sequential` (same guard as `SteeringSpec`) or two suites can
  collide on `JobId("j_1")`.

## 6. Non-goals and future work

- No wall-clock timeout parameter on `close()`; boundedness comes from
  interrupt, not from waiting.
- No cross-chat teardown: closing the last chat does not close the session.
- REPL-level `:reset`/SIGTERM interactions keep their own contracts
  (`docs/product-repl.md` §8; PTY SIGTERM evidence stays with the installed
  REPL specs).
- A future card may extend `cancel`/`watch` with the same lifecycle
  vocabulary if a consumer needs per-job close semantics; that is a public
  contract change and needs its own ADR.
