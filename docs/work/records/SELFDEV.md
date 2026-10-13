<!-- Work record. Original in Russian; key results in English below. -->

# SELFDEV: work record — coding agent (self-work on the repository)

Status: done (slice: coder = built-in agent with REAL workspace tools
fs_read/fs_search/proc_run + live OpenAI-compatible provider; edit tool
and multi-turn memory — obligations). Date: 2026-10-04. Baseline: previous
last-passing gate artifacts/quality/20261004-163645.

## Resulting behavior

`raider run --agent coder --provider openai --base-url http://127.0.0.1:8081/v1
--input "…" --out …` — Raider works on its OWN repository:

- `raider.tools.CodingToolset` — real RAI-014/016 tools under the JSON
  contract of core Tool: `fs_read` (bounded read + sha256 provenance + realpath
  containment — symlink/`..` escapes are rejected), `fs_search` (bounded,
  declared truncation), `proc_run` (EXPLICIT argv, NO shell, cwd in workspace,
  empty-env + allowlist, timeout graceful→force, bounded output). POLICY-
  denials (containment/bounds/bad args) provide structured feedback to the MODEL (§8/§10:
  the loop continues, allowing the model to correct itself); crash failures remain
  typed failures. This is the ONLY mutation channel (argv is fully visible).
- CLI: `--provider mock|openai` (default mock), `--base-url`
  (default 127.0.0.1:8081/v1 — local llama.cpp), `--model` (default
  local), `--api-key` (default no-key — NOT a secret literal), `--workspace`
  (default CWD). coder is in the BuiltInAgents whitelist.
- `MockBackend` — fixture-marked (`[mock]` in text) for --mock.
- Live backend: non-streaming OpenAIChatBackend (RAI-011 slice) — parses
  `tool_calls` → ToolCallReady → tools execute for real.

FOUND AND CLOSED with fixtures (reproduced, then fixed):
1. java-glob `**/*.scala` does NOT match a file at the workspace root (`*.scala` is needed
   or `\u007b\u007b` semantics — documented in the test).
2. a containment denial as a typed failure FAILED the entire run (LOOP-03
   “tool failure → Failed” semantics are too strict for policy denials) —
   the adapter now separates policy denial (feedback) from crash (fail).
3. `Stream.empty *> s` in zio-streams TERMINATES the combined stream without
   consuming s (zip semantics) — deterministic parking in TurnBackend changed
   to `++` + drain; parking changed from call INDEX to request CONTENT
   (parent/child fork order is nondeterministic under load).

## Changed paths

```
7088d4d2…  modules/tools/src/main/scala/raider/tools/CodingToolset.scala
8ca26368…  modules/cli/src/main/scala/raider/cli/run/CliArgs.scala
1b90aa38…  modules/cli/src/main/scala/raider/cli/run/HeadlessRunner.scala
00191342…  modules/cli/src/main/scala/raider/cli/run/MockBackend.scala
d53b0fce…  modules/cli/src/test/scala/raider/cli/run/CoderAgentSpec.scala
2bc4dc48…  modules/runtime/src/test/scala/raider/runtime/loop/AgentLoopDelegationSpec.scala
57127e11…  scripts/selfdev/raider.sh
18e7f020…  docs/selfdev-prompts.md
fbf91edb…  build.sbt
```

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| coder fs_read: the model requests → the loop reads the REAL file → content+sha256 reach the model, WITHOUT absolute paths | pass | CoderAgentSpec |
| coder fs_search: bounded search finds a REAL match | pass (after glob fix) | CoderAgentSpec |
| containment: fs_read OUTSIDE the workspace → model feedback, NO leak | pass | CoderAgentSpec |
| delegation: parking is deterministic by request content | pass | AgentLoopDelegationSpec |
| Stability | cli 10/10 ×2, tools 19/19, full suite 188 ×2 | /tmp/cli4.log, /tmp/c50.log, /tmp/full-final3.log |
| Full sbt test | 188 tests, 0 failed, exit 0 | /tmp/full-final3.log |
| cli_smoke (Main process, closed stdin) | PASS | /tmp/final-cli-smoke2.log |
| Quality gates 6/6 | exit 0 | artifacts/quality/20261004-181210/ |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderCli/test"` ×2 | 0 (10/10) | /tmp/c50.log, /tmp/cli4.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderTools/test"` | 0 (19/19) | /tmp/cli5.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` ×2 | 0 (188) | /tmp/full-final{,3}.log |
| `sh scripts/quality/cli_smoke.sh` | 0 | /tmp/final-cli-smoke2.log |
| `make quality-changed QUALITY_EXECUTE=1` | 0 (6/6) | artifacts/quality/20261004-181210/ |

## Limitations / obligations

- Edit tool RAI-015 IMPLEMENTED (fs_edit: expected-sha, atomic, stale-detect). proc_run remains for commands.
- Multi-turn memory for the CLI agent (the Chat class exists only in the REPL facade) —
  obligation; currently one --input = one run.
- Live-wire verification (real llama.cpp run) — is NOT part of this card:
  launched by the owner (see docs/selfdev-prompts.md); product
  tests do not touch 8081 (session rule).
- The coder does not yet receive a system prompt (ModelRequest without system) —
  structured message contract obligation.

## Review and next task

No independent review was performed (coordinator session). The self-hosting
loop is ready for owner-run execution; next cards: RAI-015 (edit tool),
RAI-023 (CI context), structured messages.
