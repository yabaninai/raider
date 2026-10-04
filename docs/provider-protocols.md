<!-- Translated from Russian original. Key terms preserved as-is. -->

# Provider-neutral inference

Raider полностью работает без Yabanin. В MVP два first-class wire kinds: `openai-compatible` и `anthropic-compatible`; Yabanin endpoint уже можно использовать через совместимый wire profile. Enriched kind `yabanin` реализуется RAI-037/038 как optional integration с labels/session/accounting; отсутствие Yabanin не блокирует REPL, CI, tools, subagents, checks или local budgets.

## 1. Выбор connection

```toml
[providers.direct]
kind = "openai-compatible"
base_url = "https://your-endpoint.example/v1"
api_key_env = "RAIDER_DIRECT_KEY"

[providers.claude]
kind = "anthropic-compatible"
base_url = "https://api.anthropic.com/v1"
api_key_env = "ANTHROPIC_API_KEY"
api_version = "2023-06-01"

[providers.yabanin]
kind = "yabanin"
wire_protocol = "openai-chat"
base_url = "http://localhost:8082/v1"
api_key_env = "YABANIN_API_KEY"
session_key_mode = "disabled"

[models.fast]
provider = "direct"
model = "owner-configured-model"

[models.review]
provider = "claude"
model = "owner-configured-model"

[profiles.direct.models.fast]
provider = "direct"
model = "owner-configured-model"

[profiles.direct.models.review]
provider = "direct"
model = "owner-configured-review-model"

[profiles.claude.models.fast]
provider = "claude"
model = "owner-configured-model"

[profiles.claude.models.review]
provider = "claude"
model = "owner-configured-review-model"
```

M0 generic profile для Yabanin использует `kind = "openai-compatible"` и URL/key gateway; enriched TOML block `kind = "yabanin"` выше — beta contract, flags session mint/labels нельзя объявлять работающими раньше RAI-037/038.

Provider name — локальный ID, model — explicit endpoint model ID/alias. Preset role (`scout`, `reviewer`) выбирает model role, role разрешается через config. В одном workflow допустимы разные providers; root ledger общий. Profile выбирается явно (`--profile direct`/`claude`/`yabanin`) либо trusted default в config. Нет автоматического failover из Yabanin в direct provider, смены account или протокола после 401/403. Opt-in fallback policy — отдельная конфигурация с accounting provenance, не MVP default.

`profile` и `provider` — разные сущности. Profile задаёт role bindings/limits/policy defaults; provider — connection. Resolver: explicit Agent provider/model → selected `profiles.<name>.models.<role>` → global `models.<role>`; missing role error, никакого угадывания model ID. `.model("review")` выбирает роль, значит использует выбранный profile binding; явно заданная provider/model pair его обходит, оставаясь внутри mandatory policy. Примеры profiles выше переопределяют fast/review; остальные используемые preset roles тоже должны иметь binding. Profile yabanin настраивается аналогично через gateway provider. Config inheritance не расширяет mandatory capabilities или budgets. Doctor/launch report показывает resolved role/provider/model до spend.

Примеры TOML задают целевой schema; конкретные versions/model IDs подтверждаются implementation fixtures и deployment. Official Anthropic auth/version headers — default protocol profile; compatible endpoints могут выбрать allowlisted auth strategy (`x-api-key`/Bearer/none-local) явно. Произвольные секретные headers — env references, без literals в tracked config. Insecure remote HTTP disabled по default; loopback HTTP допустим для local gateways.

## 2. Общий контракт и самостоятельная работа

Core `ModelBackend` принимает normalized conversation/tools/output constraints и отдаёт normalized events/usage/error. Wire adapter переводит сообщения и tool rounds; runtime loop/cancellation/budgets не знают конкретного endpoint. Yabanin decorator не является частью default `ModelBackend` constructor. Pricing/usage source отдельный SPI.

`ModelBackend` — один model attempt. `AgentBackend` — целый agent execution (native loop либо external pi/OpenCode engine). Wire adapter не является агентным engine; внешний backend не прогоняется через native loop повторно. Exact interfaces: [runtime contracts](runtime-contracts.md).

Standalone сохраняет attempts, provider/model, tokens, latency, costs при configured price catalog, uncertain/unpriced при отсутствии цены. Server accounting/session keys optional capability, не обязательный интерфейс generic API. Hard USD cap требует bounded price/output и conservative reservation; без них возможен explicit token/attempt/time cap, но не fabricated monetary guarantee. Model registry discovery optional: compatible API без `/models` работает с explicit configured model.

`doctor` не генерирует токены по умолчанию; сообщает endpoint/config/capabilities `supported|unsupported|unknown`. Capabilities задаются проверенным profile и versioned tests, при необходимости explicit capped inference probe. Не выводить tools/stream/JSON support из одного HTTP 200. Required capability проверяется до root dispatch.

Base URL contract: URL содержит API prefix, adapter добавляет относительный path (`chat/completions` или `messages`). Custom prefixes сохраняются; duplicate `/v1` не появляется. Error redirects на другой origin не пересылают auth. TLS/proxy/timeouts/bounded bodies общие; shared SSE framing не смешивает provider-specific state machines.

## 3. OpenAI-compatible MVP

Первый wire — Chat Completions, streaming/nonstreaming, user/assistant/tool rounds, tool call IDs, usage. Responses — будущий независимый wire adapter, не обязательство любого compatible server. Nonstandard fields/usage capabilities feature-profiled, неизвестные optional fields безопасно игнорируются, неизвестные required semantics не маскируются как support.

Transport fixtures: fragmented UTF-8/SSE/CRLF, null content/tool-only output, interleaved tool indices, complete argument assembly, usage-only chunks, finish reason, `[DONE]`, malformed/oversized data, unexpected EOF, structured error, retry-after и cancellation cleanup. Finish без финального usage может быть execution-complete/accounting-partial. Provider attempt retry безопасен только при известном pre-dispatch/explicit retry policy; оплаченные partial attempts учитываются, tools не переисполняются скрыто.

## 4. Anthropic-compatible MVP

Messages wire `/messages`: top-level system instructions, content blocks, tool_use/tool_result matching IDs, required max_tokens, explicit version/auth headers. Не конвертировать tool history в plain text: correlation, error flags и block order должны сохраняться. Server-managed tools/thinking/prompt-cache/multimodal — capability-specific extensions; required unsupported feature отклоняется, не silent drop.

SSE state machine: message_start → content_block_start/delta/stop по index → message_delta → message_stop; ping/error обрабатываются отдельно. Input JSON fragments собираются до готового tool block; callback partial args не исполняет tool. Usage обновления нормализуются как cumulative, без многократного прибавления totals. Unknown optional event фиксируется; unknown content semantics не превращается в строку успешного ответа. Thinking/signature blocks не логируются по умолчанию, при использовании feature сохраняется допустимая protocol round-trip форма без требования раскрывать reasoning. Основание: [Anthropic streaming](https://platform.claude.com/docs/en/build-with-claude/streaming), [tool definitions](https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools).

Fixtures включают несколько tool blocks, tool error round, stop_reason tool_use/end_turn/max_tokens/refusal, incomplete blocks, error после HTTP 200, cumulative usage, fragmented Unicode, oversized input, idle/deadline, cancel. Max-tokens truncation не считается полным task output без declared policy. JSON output schema validation runtime-side остаётся доступной, даже если endpoint не поддерживает native structured-output mode.

## 5. Гейты независимости

1. Clean environment без `YABANIN_*`, no Yabanin module/service: CI и REPL acceptance на обоих local mock HTTP protocols.
2. Один сценарий string/typed schema/tool round/fanout/timeout даёт equivalent normalized semantics на обоих wires.
3. Явный Anthropic endpoint не получает OpenAI/Yabanin headers; direct OpenAI не получает session mint requests.
4. Price unknown остаётся unknown; observed usage различается с estimates.
5. Mixed-provider children учитываются в одном root, каждый attempt сохраняет actual provider/role.
6. Doctor unavailable registry не блокирует explicit model, unsupported required tools блокируются до spend.
7. Optional Yabanin adapter проходит отдельный contract/stand profile; base release не требует deployed Yabanin.
