<!-- Work record. Original in Russian; key results in English below. -->

# RAI-012-SLICE: work record (OpenAI SSE streaming state machine)

Status: done (slice: framing+semantic parser+streaming backend; полный
acceptance RAI-012 остаётся milestone-обязанностью). Date: 2026-10-04.
Baseline: предыдущий last-passing gate artifacts/quality/20261003-221636.

## Resulting behavior

RAI-012 slice — SSE-стриминг как продуктовый ModelBackend поверх
РАЛЬНОГО локального HTTP (Scala-стенд, loopback:0; без сети/оплаты/8081):

- `raider.provider.chat.stream.SseFramer` — инкрементальный SSE-фреймер
  над БАЙТАМИ (0x0A не встречается внутри multi-byte UTF-8 — фрейминг до
  декодирования UTF-8 безопасен); состояние (частичная строка + накопленные
  data-строки) живёт в ПОЛЯХ — событие, разрезанное ЛЮБОЙ границей чанков
  (включая границу между \n данных и пустой строкой-терминатором), не
  теряется (SSE-01); multi-line data склеиваются "\n", комментарии `:` и
  event:/id:/retry: игнорируются; ограничение maxFrameBytes → typed
  StreamProtocol (не безграничный буфер); реюз для Anthropic закладен
  (семантика отдельно);
- `raider.provider.chat.stream.ChatChunkParser` — семантический стейт-машина
  чанков: delta.content → TextDelta сразу; tool_calls накапливаются ПО
  ИНДЕКСАМ (interleaved), ToolCallReady — ТОЛЬКО по finish_reason, в порядке
  индексов (частичные аргументы никогда не исполняемы, §8); первый
  finish_reason побеждает; [DONE] подтверждает конец; EOF-before-finish и
  DONE-без-finish_reason → typed StreamProtocol; usage-only чанки →
  UsageObserved(priceKnown=false — unpriced, не free);
- `raider.provider.chat.stream.OpenAIChatStreamingBackend` — POST с
  `"stream":true, stream_options.include_usage`; статус-маппинг как в
  non-streaming slice; тело закрывается на ЛЮБОМ выходе (acquire-release в
  scope ВЫЗЫВАЮЩЕГО — контракт ZStream[Scope,…]); idle-дедлайн = race
  чтения с таймером (проигравшее блокирующее чтение прерывается, его
  IOException проглатывается) → typed DeadlineExceeded; interrupt закрывает
  тело (SSE-03); retry'ев нет по дизайну; capabilities честные:
  streaming=Supported, cancellationAck=Unknown.

Найдено и закрыто fixture'ами 4 реальных интеграционных дефекта/ловушки
(каждая воспроизведена тестом, потом устранена):

1. **Потеря фреймов на границах чанков**: dataLines/hasData были ЛОКАЛЬНЫМИ
   в drain() — фрейм, разрезанный между \n данных и пустой строкой,
   исчезал. Фикс: состояние парсинга строк — поля фреймера; SSE-01
   partition-property (разбиения 1,2,3,5,7,13,64,1024 байт → идентичные
   события) ловит это классом.
2. **`ArrayBuffer[Byte].indexOf('\n', pos)`** — Char не матчится с Byte,
   indexOf всегда −1 (фреймер «не видел» ни одной строки). Фикс:
   `'\n'.toByte`.
3. **Жадная оценка финишера**: `ZStream.fromZIO(ZIO.fromEither(
   parser.finish()))` вычислял finish() при ПОСТРОЕНИИ стрима — до первого
   чанка → вечный «truncated». Фикс: финишер строится внутри ZStream.unwrap
   (по-настоящему лениво).
4. **Гонка idle-дедлайна с блокирующим чтением**: `.timeout` поверх
   attemptBlockingIO отдавал сырой IOException вместо typed-дедлайна.
   Фикс: `race` чтения с таймером (проигравшая сторона прерывается, её
   ошибка — потеря). Плюс `readNBytes(8192)` → `read(buf)` (readNBytes
   блокирует до ровно 8192/EOF — для SSE-инкремента неверный API).

Попутно в спеке: сценарии стенда stream-text/tool(round2 по tool-роли)/
truncated/garbage/parked/silent; фрагментация каждого фрейма по 2 байта
с flush (реальный chunked HTTP).

## Changed paths

```
e8ae72e2…  modules/provider-chat/src/main/scala/raider/provider/chat/stream/SseFramer.scala
679b0808…  modules/provider-chat/src/main/scala/raider/provider/chat/stream/ChatChunkParser.scala
a0bca07e…  modules/provider-chat/src/main/scala/raider/provider/chat/stream/OpenAIChatStreamingBackend.scala
adfe616d…  modules/provider-chat/src/test/scala/raider/provider/chat/stream/OpenAIChatStreamSpec.scala
```

Только новые файлы в `modules/provider-chat/**/stream/**` (allowed paths
RAI-012); verified non-streaming slice и контракты не тронуты.

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| SSE-01: все разбиения чанков → идентичные output/usage/tool IDs | pass (partition-property ×8 размеров) | OpenAIChatStreamSpec |
| SSE-02: EOF-before-finish / DONE-без-finish / invalid JSON / oversize → typed RA-STREAM | pass | OpenAIChatStreamSpec |
| SSE-02: ToolCallReady ТОЛЬКО по finish, порядок индексов, частичные аргументы не исполняются | pass | OpenAIChatStreamSpec |
| Интеграция: фрагментированный SSE по HTTP → текст+usage+Finished | pass | OpenAIChatStreamSpec |
| SSE-03: cancel mid-stream → тело закрыто (stand-side ref), Uncertain-бухгалтерия удержана | pass | OpenAIChatStreamSpec |
| AgentLoop завершает HTTP streaming tool-раунд (2 раунда) | pass | OpenAIChatStreamSpec |
| Стабильность ×3 | 17/17, exit 0 ×3 | /tmp/s28.log, /tmp/st-run-{1,2}.log |
| Полный sbt test | 151 тест, 0 failed, exit 0 | /tmp/full-test-R012.log |
| Quality gates 6/6 ×2 ПОДРЯД | exit 0 ×2 | artifacts/quality/20261004-04{3918,4001}/ |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderProviderChat/test"` ×3 | 0,0,0 | /tmp/s28.log, /tmp/st-run-{1,2}.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 (151) | /tmp/full-test-R012.log |
| `make quality-changed QUALITY_EXECUTE=1` ×2 | 0, 0 | artifacts/quality/20261004-04{3918,4001}/ |

## Limitations / obligations

- Карточка RAI-012 целиком (sanitized corpus matrix, interleaved multi-choice
  edge cases, protocolVerdict-эскалации) — milestone-обязательство; slice
  покрывает acceptance-скелет SSE-01..03.
- Anthropic-переиспользование фреймера — проверяется в RAI-013.
- SSE-idle только меж-чанковый: стартовый connect ограничен тем же
  callTimeoutMs (JDK client), отдельный per-read budget — при надобности.
- HTTP chunked + keepalive комментария — stand упрощён vs реальный
  провайдер; sanitized corpus matrix остаётся obligation.

## Review and next task

Независимый review не проводился (координаторская сессия). Следующие
готовые карточки: RAI-013 (Anthropic Messages поверх фреймера), child
delegation (§11) поверх JobManager+AgentLoop, RAI-022 (headless CLI).
