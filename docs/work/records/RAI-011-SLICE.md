<!-- Work record. Original in Russian; key results in English below. -->

# RAI-011-SLICE: work record (OpenAI-compatible transport feasibility slice)

Status: done (TRANSPORT SLICE — не готовый provider: non-streaming only).
Date: 2026-10-03. Baseline: предыдущий last-passing gate
artifacts/quality/20261003-220233 (RAI-009-B).

## Resulting behavior

Phase F (stretch): OpenAI-compatible Chat Completions как продуктовый
`ModelBackend` поверх РЕАЛЬНОГО локального HTTP — без сети, без оплаты, и
НИКОГДА 127.0.0.1:8081 (llama.cpp):

- `modules/provider-chat` (новый модуль, build.sbt — координаторская правка):
  `raider.provider.chat.OpenAIChatBackend` — JDK HttpClient,
  non-streaming `/chat/completions`; нормализация в ModelEvents (Started →
  TextDelta/tool_calls → UsageObserved(priceKnown=false) → Finished);
  статус-маппинг 401/403→RA-AUTH, 429→RA-RATE, 5xx→RA-UNAVAIL, прочий
  non-200/непарсируемое тело→RA-STREAM; apiKey write-only (toString redacted,
  в деталях ошибок не появляется); interrupt отменяет in-flight запрос
  (fromCompletionStage→CF.cancel), retries ОТСУТСТВУЮТ (дисциплина AGENTS);
  capabilities честные: streaming=Unsupported (SSE = RAI-012),
  cancellationAck=Unknown (CF cancel ≠ server ack);
- СТЕНД НА SCALA (языковая политика AGENTS/PLAN: provider-стенды — Scala
  JDK HttpServer внутри модуля): `OpenAIChatStandSpec` поднимает stand на
  127.0.0.1:0 (эфемерный порт), auth проверяется всегда, сценарии
  request-driven (по имени модели: ok/tool-model/boom/garbage + tool-role
  запрос получает финальный текст). Python-мок
  `experiments/openai-chat-spike/harness/mock_gateway.py` остаётся артефактом
  спайка; для этого slice он не нужен (Scala-стенд покрывает то же и
  детерминированнее) — интеграция с ним не проводилась, это не «пропуск»;
- capstone: AgentLoop (Phase A) завершает НАСТОЯЩИЙ HTTP tool-раунд:
  round1 = tool_calls от stand'а, native tool, round2 = финальный текст;
  usage провайдера учитывается как unpriced (не free), reserved сходится.

Попутный детерминизм-фикс (repl-engine, координаторская правка): под новым
8-м модулем ворота поймали нагрузочный флак компиляторного теста
("runtime failure..." получил обрезанный вывод драйвера). Исправлено
усилением воспроизводимости, БЕЗ ослабления проверок:
`Global / concurrentRestrictions += Tags.limit(Tags.Test, 2)` (build.sbt) и
`@@ TestAspect.sequential` на ReplEngineSpec (6 собственных компиляторов не
соперничают за память). Проверки в тестах не менялись; gate 6/6 ×2 подряд
после фикса.

## Changed paths

```
c4a3a82f…  build.sbt (модуль + Tags.limit(Tags.Test, 2))
906085fc…  modules/provider-chat/src/main/scala/raider/provider/chat/OpenAIChatBackend.scala
22e1c9ea…  modules/provider-chat/src/test/scala/raider/provider/chat/OpenAIChatStandSpec.scala
7ee852de…  modules/repl-engine/src/test/scala/raider/repl/engine/ReplEngineSpec.scala (sequential aspect)
```

Ни один существующий продукт/контракт не менялся (только build.sbt и тесты).

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| Text roundtrip поверх HTTP (stand) | 4 события, usage unpriced, auth ok | OpenAIChatStandSpec |
| 401 → RA-AUTH (ключ не утекает в детали) | pass | OpenAIChatStandSpec |
| 500 → RA-UNAVAIL; мусорное тело → RA-STREAM | pass | OpenAIChatStandSpec |
| Capstone: AgentLoop + реальный HTTP tool-раунд | pass, unpricedAttempts=2 | OpenAIChatStandSpec |
| Стабильность transport-тестов ×3 | 4/4, exit 0 ×3 | /tmp/f4.log, /tmp/f-run-{1,2}.log |
| Полный sbt test | 138 тестов, 0 failed, exit 0 | /tmp/full-test-F.log |
| Quality gates 6/6 ×2 ПОДРЯД (после детерминизм-фикса) | exit 0 ×2 | artifacts/quality/20261003-221154, 20261003-221229 |

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderProviderChat/test"` ×3 | 0,0,0 | /tmp/f{4,-run-1,-run-2}.log |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 (138) | /tmp/full-test-F.log |
| `make quality-changed QUALITY_EXECUTE=1` ×2 | 0, 0 | artifacts/quality/20261003-221{154,229}/ |
| `make quality-changed QUALITY_EXECUTE=1` (до фикса, честно) | exit 2, sbt-test fail | artifacts/quality/20261003-220946/ (не замалчивался) |

## Limitations / obligations

- Streaming/SSE — RAI-012 (обязательство); Anthropic Messages — RAI-013.
- Tool-раунды на wire: только non-streaming tool_calls; параллельные
  tool_calls, usage в streaming-чанках, retries/backoff, endpoint probing —
  вне slice.
- Интеграция с python-моком спайка — не проводилась (Scala-стенд достаточно;
  при необходимости — отдельная карточка по явной конфигурации stand'а).
- Долгий живой цикл отмены (stalled body, before-headers cancel) — CHAT-03,
  не в slice (частично покрыто спайком исторически).

## Review and next task

Независимый review не проводился (координаторская сессия). Финал сессии:
полный прогон всех приёмок + board/obligations.
