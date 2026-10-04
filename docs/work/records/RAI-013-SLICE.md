<!-- Work record. Original in Russian; key results in English below. -->

# RAI-013-SLICE: work record (Anthropic-compatible Messages + streaming)

Status: done (slice: native Messages wire non-streaming + SSE streaming;
полный ANTH-01..03 corpus остаётся milestone-обязанностью). Date: 2026-10-04.
Baseline: предыдущий last-passing gate artifacts/quality/20261004-044001
(RAI-012-SLICE).

## Resulting behavior

RAI-013 slice — второй first-class wire (`anthropic-compatible`) поверх
реального локального HTTP (Scala-стенд; без сети/оплаты/8081):

- `raider.provider.anthropic.AnthropicMessagesBackend` — `/v1/messages`,
  заголовки `x-api-key` + pinned `anthropic-version: 2023-06-01` (Config
  apiKey write-only, toString redacted), required `max_tokens` из
  ModelRequest; ДВА транспорта в одном классе (non-streaming ofString +
  SSE ofInputStream), одинаковый статус-маппинг (401/403→AUTH,
  429→RATE, 5xx→UNAVAIL, прочий non-200→StreamProtocol с намёком на
  version/параметры); тела ошибок НЕ попадают в details с ключом;
- НАТИВНАЯ семантика без fake-OpenAI-конверсии (provider-protocols §4):
  ответы → content blocks (text / tool_use c input-JSON); tool-результаты
  цикла (role "tool", конверт `{"tool_call_id","output"}`) переводятся в
  НАТИВНЫЙ `tool_result`-блок в user-сообщении — correlation сохранена,
  НЕ plain text (полная блочная история = structured message contract,
  obligation); tool output rides как строковый payload внутри
  tool_result.content;
- `AnthropicStreamParser` — стейт-машина Anthropic SSE:
  message_start(usage.input) → content_block_start/delta/stop ПО ИНДЕКСАМ →
  message_delta(cumulative output_tokens — ПЕРЕЗАПИСЬ, никогда не сумма) →
  message_stop; input_json_delta накапливаются до content_block_stop —
  ToolCallReady только на block_stop (частичные аргументы не исполняемы);
  error-события после 200 → typed ProviderUnavailable с anthropic-типом;
  ping игнорируется; unknown CONTENT (block/delta типы) → typed fail с
  именем типа; unknown OPTIONAL события игнорируются; РОВНО ОДИН
  UsageObserved в финише (ANTH-03: нет двойного счёта);
- переиспользование фреймера ЗАРАБОТАЛО как заявлено: SseFramer из
  provider-chat (зависимость provider-anthropic → provider-chat, санкция
  provider-protocols §4 «reusable framing»), partition-property на
  anthropic-corpus зелёный;
- max_tokens truncation проходит честно как stop_reason (не полный output —
  политика у вызывающего).

Найдено и закрыто fixture'ами (воспроизведено тестом, потом устранено):

1. **ErrWire-поле**: anthropic error-объект несёт `type`, не `error_type` —
   тип ошибки терялся («unknown») → fixtures поймали, исправлено.
2. **Конверт tool-результата**: `output` — произвольный JSON (объект), а не
   строка → InputValidation на каждом tool-раунде. Фикс: output: Json.
3. **Стенд-детект tool-раунда**: tool_result — это user-сообщение с
   блоком, роль "tool" на wire не существует → стенд искал не там →
   бесконечные tool-раунды до maxAttempts (RA-LBUDGET). Фикс: детект по
   вхождению tool_result в контент.

## Changed paths

```
c8642421…  build.sbt (модуль raiderProviderAnthropic, root aggregate)
8ce3a8aa…  modules/provider-anthropic/src/main/scala/raider/provider/anthropic/AnthropicMessagesBackend.scala
6e092e0f…  modules/provider-anthropic/src/main/scala/raider/provider/anthropic/AnthropicStreamParser.scala
d21acf3b…  modules/provider-anthropic/src/test/scala/raider/provider/anthropic/AnthropicMessagesSpec.scala
```

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| ANTH-01 non-streaming: text + usage unpriced + end_turn | pass | AnthropicMessagesSpec |
| ANTH-01 tool round: НАТИВНЫЕ tool_use ×2 → loop исполняет → round2 → финал | pass | AnthropicMessagesSpec |
| ANTH-01 streaming: fragmented UTF-8 («привет ✓», мультибайт разрезан HTTP-чанками) | pass | AnthropicMessagesSpec |
| ANTH-01 streaming tool round: input_json_delta частями, ToolCallReady на block_stop | pass | AnthropicMessagesSpec |
| ANTH-03 cumulative usage БЕЗ двойного счёта (5→12 → ровно один UsageObserved(15,12)) | pass | AnthropicMessagesSpec |
| ANTH-02 error-after-200 → RA-UNAVAIL c anthropic-типом; truncated → RA-STREAM | pass | AnthropicMessagesSpec |
| ANTH-02 неверный ключ → RA-AUTH; неверная версия → typed 400 | pass | AnthropicMessagesSpec |
| ANTH-02 unknown block/delta тип → typed, имя типа в детали | pass | AnthropicMessagesSpec |
| ANTH-03 cancel между block_stop и message_stop → тело закрыто, Uncertain удержан | pass | AnthropicMessagesSpec |
| Фреймер реюзнен: partition-property на anthropic corpus (k=1,3,8,64,4096) | pass | AnthropicMessagesSpec |
| Стабильность ×3 | 11/11, exit 0 ×3 | /tmp/an9.log, /tmp/an-run-{1,2}.log |
| Полный sbt test | 162 теста, 0 failed, exit 0 | /tmp/full-test-R013.log |
| Quality gates 6/6 ×2 ПОДРЯД | exit 0 ×2 | artifacts/quality/20261004-05{1050,1133}/ |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderProviderAnthropic/test"` ×3 | 0,0,0 | /tmp/an9.log, /tmp/an-run-{1,2}.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 (162) | /tmp/full-test-R013.log |
| `make quality-changed QUALITY_EXECUTE=1` ×2 | 0, 0 | artifacts/quality/20261004-05{1050,1133}/ |

## Limitations / obligations

- Tool DEFINITIONS не отправляются на wire (ModelRequest плоский; перевод
  ToolRegistry → tools/input_schema = structured message contract, отдельная
  карточка) — tool_use в ОТВЕТАХ работает нативно.
- Полная история блоков (порядок text/tool_use в ПРЕДЫДУЩИХ ходах) —
  structured message contract obligation; сейчас ход цикла — text-сообщения.
- system-параметр, thinking/signature blocks, server-managed tools,
  multimodal — вне slice (unsupported → typed fail, не silent drop).
- Полный ANTH-corpus (sanitized matrix, tool error round, refusal) —
  milestone-обязательство карточки.

## Review and next task

Независимый review не проводился (координаторская сессия). Готовые
следующие: child delegation (§11) поверх JobManager+AgentLoop; RAI-022
(headless CLI/bundle); RAI-014/015 tools в loop-сценарии.
