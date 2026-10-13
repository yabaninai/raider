# ADR-026: Model-correctable read-tool errors — structured feedback, not run termination

Status: **Accepted** (operator decision, 2026-10-10, autonomous self-development campaign). Date: 2026-10-10.
Owner: operator (campaign driver). Card: cross-cutting, outside RAI numbering;
does not change `raider.core.tool` (RAI-002.b freeze) or AgentLoop emission semantics
(RAI-058, `AgentLoopEmissionSpec` remains the reference).

## 1. Context

The autonomous self-development campaign (53 cards, live model glm-5.3-flash through
the Yabanin gateway) repeatedly failed on ordinary model mistakes: `fs_read` of a
nonexistent path, reading a directory, or a binary file. The `CodingToolset.toolOf`
classification sent `ToolFailed` to `throw` → ZIO Defect → `JobManager.settle` recorded
“job died” and the entire run ended with exit 10. This made “model used the wrong
path → run lost” the norm, contrary to the purpose of the toolset (RAI-014: workspace
tools for a coding agent).

## 2. Decision

1. `ReadTool`: `file not found`, `binary file`, and “path is a directory” are
   reclassified from `ToolFailed` to `InputValidation`. `InputValidation` is already
   included in `isPolicyDenial` → `toolOf` returns structured `{"error":code,"detail":...}`
   to the model and the run continues (same channel as unknown_tool/invalid_args; see
   POLICY comment in `toolOf`).
2. `RateLimitRetries`: `ProviderUnavailable` (502/503) is retried like 429 — it is
   already `retryable=true` in the §14 taxonomy; it occurs before the first stream
   event, so no duplicate emissions result.
3. The agent loop (AgentLoop), JobManager, and error contracts (§14) were NOT changed:
   `AgentLoopEmissionSpec` (RA-TOOLFAIL/RA-DEADLINE scripted-tool fatality) was restored
   bit-for-bit after an experiment with catchAllCause was rejected as violating
   protected semantics.

## 3. Consequences

- The model gets a machine-readable error code and corrects its arguments; the run survives.
- Event honesty is preserved: `ToolFinished(Failed)` for refusal classes remains as before;
  error JSON in the success channel is visible in the transcript.
- Specs: `ReadToolSpec` does not assert not-found/binary (verified), so no spec edits were
  needed. This behavior is documented here as an ADR reference for future RAI-014/016 rev.
- Real I/O failures (vanished mid-read, resolve IOException) remain crash-flavored —
  successful reading is still not falsely reported.
