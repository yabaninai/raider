# ADR-028: Plan mode — a read-only planning phase with explicit approval

Status: **Accepted** (owner mandate 2026-10-12: “Registration package: RAI-107
Plan mode (ADR-028) — mandatory”). Date: 2026-10-12. Owner: project owner
(mandate) + coordinator (design within frozen contracts). Does not change
ADR-024 (frozen event wire schema), gate policy, or runtime-contracts; it
narrows the execution surface in a dedicated session mode.

## 1. Decision

AgentLoop gains a **bounded `plan` execution mode**: during the plan phase the
model has access to **read-only tools only**, and the phase must end with a
**structured plan proposal**. Side-effecting tools cannot run and the plan
cannot proceed to execution **without the owner's explicit approval**.

## 2. Decisions recorded

| # | Decision |
| --- | --- |
| Enforcement of narrowing | At the **frozen `RecoveryClass`** level (`ReadOnly`/`Idempotent`/`Mutating`, core/tool.scala): the plan phase receives a ToolRegistry containing only `ReadOnly` tools. A prompt-only prohibition is not enforcement and does not count. |
| Entering the mode | Request/session flag (request context / `:plan` surface — separate wiring card); mode is off by default. |
| Leaving the phase | **Explicit owner approval** only; auto-approval is prohibited. Approval creates a **separate execution job** in normal mode, with the plan as context. |
| Plan artifact | Typed `PlanProposal` (the plan job's result), persisted alongside run artifacts; human-readable strings use existing Diagnostics events. **No new wire event is introduced** (ADR-024 is frozen; schema expansion requires a separate schema card and independent review). |
| Malformed plan | Empty/unstructured plan → typed `PlanRejected`; bounded repair attempts (invariant 11: output repair is an attempt, not unbounded); no artifact is written on rejection. |
| Cancellation | Plan job is cancelled as usual (bounded cleanup, invariants 4/5); cancellation ≠ approval. |
| Lineage | Plan phase and execution job are separate records (Phase nodes in ADR-027); the plan job is not “hidden” inside execution. |
| Budget | Plan phase uses the shared root budget (admission/usage accounting cannot be bypassed). |

## 3. Non-goals

A second scheduler/runtime; auto-approval or timer-based approval; silent
changes to ADR-024 wire schema or public core schemas; sandbox guarantees on
top of tool policy (see trust boundaries in AGENTS.md); UI surface (`:plan`
command, TUI plan screen) — separate wiring cards.

## 4. Acceptance (card RAI-107)

- PLAN-01: invoking a `Mutating`/`Idempotent` tool in the plan phase gives a
  typed policy denial while the loop remains alive; `ReadOnly` tools run.
- PLAN-02: malformed plan → typed `PlanRejected`, bounded repair, no artifact;
  valid plan → structured `PlanProposal` is persisted.
- PLAN-03: execution does not start without approval; approval → exactly one
  execution job; cancelling the plan phase performs bounded cleanup, and a
  new plan can be started.

Profiles: static/unit/contracts/runtime/eval. Deterministic fixtures only —
real paid calls are not implied (scripted backend).
