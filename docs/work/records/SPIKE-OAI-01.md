<!-- Work record. Original in Russian; key results in English below. -->

# SPIKE-OAI-01: work record (owner-directed)

Status: **changes-required** по independent review 2026-10-03 в конце record.
Исходный author status: done (review pending). Date: 2026-10-02 (UTC). Baseline: то же initial
untracked tree, что и RAI-001; RAI-001 не менялся (его suite не перезапускался —
продуктовых файлов RAI-001 spike не касается, классы компилируются рядом в
ignored artifacts/spike/classes).

Назначение: владелец направил приоритет на OpenAI-compatible gateway («пока просто
с OpenAI-compatible»). Это feasibility-spike к RAI-011/012, не их выполнение:
продуктовые модули `modules/provider-chat/**` и transport-профили не созданы.

## Resulting behavior

- Настоящий Scala-клиент OpenAI-compatible Chat: JDK HttpClient + ZIO
  acquire-release адаптер + zio-json derivation wire-схем.
- Non-streaming roundtrip; SSE-стриминг с покадровой прерываемостью
  (interruption на границе строк); tool-call дельты собираются по index, partial
  arguments не исполняются; 401 классифицируется как ProviderAuth (exit 10);
  fiber-interrupt посреди стрима закрывает body — mock-гейтвей фиксирует
  disconnect; тот же клиент используется из настоящего REPL (`replAsk`).
- Локальный mock-гейтвей (python stdlib, 127.0.0.1): Bearer auth, OpenAI-style
  error body, chunked SSE с намеренной фрагментацией каждого кадра на два
  HTTP-chunk (reassembly проверяется), сценарии через `X-Spike-Scenario`.

## Changed paths

- `experiments/openai-chat-spike/OpenAiCompatSpike.scala` (новый)
- `experiments/openai-chat-spike/harness/mock_gateway.py`, `.../oai_spike_harness.py` (новые)
- `experiments/openai-chat-spike/run_spike.sh`, `README.md` (новые)
- `docs/work/board.md`, этот record (обновлены)

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| OAI-01 non-stream | PASS exit=0, final + usage total=26 | artifacts/oai-spike/oai-01-ask.log |
| OAI-02 stream≡non-stream (fragmented frames) | PASS exit=0, sawDone=true | artifacts/oai-spike/oai-02-stream.log |
| OAI-03 tool args assembled, не исполнены | PASS args={"path":"README.md"} | artifacts/oai-spike/oai-03-tool.log |
| OAI-04 auth 401 classified | PASS exit=10 | artifacts/oai-spike/oai-04-auth.log |
| OAI-05 cancel midstream | PASS cleanly=true, gateway_saw_disconnect=True | artifacts/oai-spike/oai-05-cancel.log, gateway-disconnect.log |
| OAI-06 REPL streaming | PASS `answer: String = "…"` в транскрипте | artifacts/oai-spike/repl-transcript.txt |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `java -cp <repl+headless> dotty.tools.dotc.Main -classpath <cp> -d artifacts/spike/classes experiments/openai-chat-spike/OpenAiCompatSpike.scala` | 0 | artifacts/oai-spike/compile.log |
| `sh experiments/openai-chat-spike/run_spike.sh` | 0 | artifacts/oai-spike/run-spike.log (6/6) |

Версии те же, что в RAI-001 (Scala 3.9.0, ZIO 2.1.26, zio-json 0.10.0, JDK 20.0.1).

## Limitations / blockers

- Mock-гейтвей; нет TLS/redirects/429/5xx/multi-choice, нет настоящих эндпоинтов и
  ключей — всё это контракт RAI-011/012 с transport/security профилями.
- Один HTTP-chunk = части одного SSE-кадра; более широкий corpus фрагментаций
  (UTF-8 split по байтам, CRLF, keepalive) — RAI-012 fixtures.
- Прерываемость проверена на границах SSE-строк; blocking readLine внутри строки
  не прерывается (соответствует «safe boundary» плану).
- Independent review не проводился.

## Review and next task

Review: pending. Продуктовый путь не изменился: RAI-003 → RAI-002 → RAI-004 →
… → RAI-011/012; находки спайка (send/use контур, per-line interruptibility,
zio-json wire codecs) переходят в эти карточки как проверенные подходы.

## Independent review 2026-10-03

Current verdict: **changes-required**. Исходный author report выше сохранён как история. [Независимый review](../../reviews/2026-10-03-readiness.md) подтвердил fresh compile и воспроизвёл false-green launcher (producer17→suite0). HTTP 6/6 независимо не повторялись; cancellation/terminal-event/tool-round guarantees требуют отдельных fixtures. Эти approaches ещё не приняты как production adapters.
