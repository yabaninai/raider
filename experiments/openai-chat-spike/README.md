# SPIKE-OAI-01: OpenAI-compatible Chat wire spike (owner-directed)

Feasibility-прототип будущего RAI-011/012: настоящий Scala-клиент
(JDK HttpClient + ZIO-адаптер + zio-json codecs) против локального mock-гейтвея,
реализующего `POST /v1/chat/completions`. Не продуктовый модуль.

## Запуск

```sh
sh experiments/repl-spike/run_spike.sh        # один раз: toolchain + classpath'ы
sh experiments/openai-chat-spike/run_spike.sh
```

Выход 0 ⟺ 9/9 проверок (F0-remediation добавила malformed-SSE, premature-EOF,
cancel-before-headers). Evidence: `artifacts/oai-spike/` (override через
`RAIDER_OAI_EVIDENCE`; summary.json, логи сценариев, source manifest,
REPL-транскрипт). Fault-injection: `run_spike.sh --self-test-fault-injection`.

## Что доказано

| ID | Сценарий |
| --- | --- |
| OAI-01 | non-streaming: JSON roundtrip, usage |
| OAI-02 | SSE streaming == non-streaming (каждый SSE-кадр фрагментирован на два HTTP-chunk) |
| OAI-03 | tool-calls: id/name/args собираются по index из дельт, partial args не исполняются |
| OAI-04 | 401 → `[classified] ProviderAuth`, exit 10 |
| OAI-05 | fiber-interrupt посреди стрима закрывает body, гейтвей видит disconnect |
| OAI-06 | тот же клиент стримит из настоящего REPL (`replAsk`) |
| OAI-07 | malformed SSE payload → `[classified] StreamProtocol`, exit 21 |
| OAI-08 | premature EOF/truncated body без terminal event → exit 21 |
| OAI-09 | cancel до headers отзывает request (`cf.cancel`), body не открывается |

## Границы

Mock-гейтвей на python stdlib; нет TLS/redirect/429/5xx-сценариев, нет настоящих
эндпоинтов. Продуктовая реализация — RAI-011 (HTTP Chat) и RAI-012 (SSE state
machine) со своими transport/security профилями. Record: `docs/work/records/SPIKE-OAI-01.md`.
