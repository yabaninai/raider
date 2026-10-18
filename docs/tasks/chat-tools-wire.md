# RAI-176: Preserve tool definitions in streaming Chat requests

Status: in_progress. Milestone: M2. Dependencies: RAI-012.

**Scenario and contract.** Interactive `raider chat` with the local Qwen model
returned XML tool-call text instead of executing `fs_tree`. The streaming
encoder omits `ModelRequest.tools`; a direct bounded local-server request with
the same tool definition returned structured SSE `delta.tool_calls`. Preserve
the normalized tool definitions in streaming requests using the existing Chat
wire format. No public contract change is required.

**Allowed paths.**
`modules/provider-chat/src/main/scala/raider/provider/chat/stream/OpenAIChatStreamingBackend.scala`,
`modules/provider-chat/src/main/scala/raider/provider/chat/OpenAIChatBackend.scala`,
`modules/provider-chat/src/test/scala/raider/provider/chat/stream/OpenAIChatStreamSpec.scala`,
and `docs/work/records/**`. Coordinator owns task metadata and the board.

**Acceptance.**

- Normal: CHATWIRE-01: an HTTP stand validates the actual streaming request
  contains tool name, description and JSON parameters; only a valid request
  receives a tool-call response, and the agent loop executes it and completes.
- Error: CHATWIRE-02: tool schemas retain JSON types/Unicode/escaping; empty
  tools do not introduce invalid definitions. Existing malformed/truncated
  stream cases remain unchanged and pass.
- Cancellation: CHATWIRE-03: existing streaming timeout, cancellation and
  uncertain-accounting cases pass without changing bounds or assertions.

**Required gates.** Focused OpenAIChatStreamSpec with a before/after regression;
`make quality-changed`, `make quality-changed QUALITY_EXECUTE=1`, and component
static/unit/contracts/transport profiles on the integrated candidate. A bounded
local-model chat probe verifies real file creation; no remote paid calls.

**Non-goals.** XML scraping, model/template changes, public message/schema
changes, new dependencies, tool-policy changes, unrelated runtime work, paid
provider calls, verifier or gate-policy changes.

**Execution.** One isolated visible worker and an independent reviewer; this
single regression does not create eight unrelated implementation lanes. The
Codex visible agent tools replace OpenCode's task tool in this interactive
session. Coordinator integrates sequentially and refreshes the user classpath.

**Deliverables.** Minimal provider correction, request-sensitive regression,
independent review, exact commands/exits and local-model limitations in a work
record using the repository template. Next available board cards remain
RAI-165 and LIN-04.
