<!-- Work record. Translated from the Russian original. -->

# RAI-016.a: Owned process primitives — record

Date: 2026-10-03. Writer: coordinator (root; lane path cancelled on escalation).
Dependencies: FROZEN core (RaiderError) + RAI-014 Workspace containment (cwd
check reuses the same realpath approach).

## Deliverable

Production: `modules/tools/src/main/scala/raider/tools/process/ProcessTool.scala`

- Structured argv ONLY (`ProcessBuilder(argv*)`, redirectErrorStream(false)) —
  shell metacharacters are passed as literal data (test: `a; rm -rf / | cat`
  is printed literally);
- env = `environment().clear()` + allowlist∩values ONLY: no ambient
  inheritance → no inherited credentials. Policy is stricter than a “filter”:
  envValues with keys outside allowlist → typed RA-INP BEFORE spawn;
- Output capture is byte-bounded (`maxOutputBytes`, hard limit 8 MiB; excess is
  declared `truncated`), drained on separate threads (deadlock-safe);
- Timeout: graceful `destroy()` → 2s grace → `destroyForcibly()` → 2s;
  `timedOut` is declared; “unkillable within grace” is recorded in stderr;
- `SandboxCapability` = negotiation type (Supported/Unsupported/Unknown); tool
  honestly reports Unknown; `sandboxRequired=true` when not Supported → RA-CAP
  denial (no silent fallback);
- `CleanupGuarantee.DirectChildOnly` in EVERY outcome is an explicit known
  obligation from RAI-001/ADR-015: killing the direct child does NOT prove
  process-group/grandchild cleanup. No unsupported claim is made;
- cwd: containment as for Workspace (realpath, escape → RA-TOOLDENY).

Tests (6): PROC-01 echo+exit+bounds; env allowlist (child does NOT have the
parent's PATH/HOME—checks absence of inheritance); shell-literal behavior;
declared byte-bound truncation; timeout kill within bounded wall time (sleep
30 @400ms); PROC-02 typed negatives (empty argv / empty head / cwd escape /
env-policy / zero bound / sandbox deny).

## Checks (exact)

- `sbt --batch "raiderTools/test"` → exit 0, 19/19 (read 8 + search 5 + process 6).
- `make quality-changed QUALITY_EXECUTE=1` → exit 0, 6/6 gates
  (artifacts/quality/20261003-142936/manifest.json; sbt-test: all modules).

## Findings during the card

1. Reversed direction of env policy (allowlist⊆values instead of
   values⊆allowlist) — caught by own negative test and fixed.
2. Sentinel test initially conflicted with strict policy (extra values are
   rejected before spawn); changed to honestly check absence of inheritance
   from the parent (PATH/HOME).
3. -Werror deprecation (`StringBuilder.newBuilder`) → `new StringBuilder`.

## Known obligations (honest; not closed by this card)

- Process-group/grandchild kill (RAI-016 parent, ADR-015) — documented as
  `CleanupGuarantee.DirectChildOnly`; actual process-group kill is a separate
  card (verification requires a platform contract; macOS/Linux differ).
- PID-reuse/foreign-process protection — outside this slice (PROC-03 partial).
- Uncertain status for an unkillable child: currently honestly marked in
  stderr + `timedOut`; a separate typed `Uncertain` outcome comes with the
  journal (RAI-027).
